# Structural resolve split (B0+B1) + the parse-cache lever

> **Status: B0+B1 DONE (2026-06-12, commit `b028fb6` on `main`); parse/resolve split now MEASURED
> (2026-06-12, see "Measured" section below) — the data reorders the next levers (Tier-1 re-parse
> elimination first; parse cache + parallel resolve after).** This is the record of the structural-layer split, its
> measured (modest) cold-`find_references` win on Spring, *why* it's modest, and the design for the
> parse cache that the residual cost points to. Companion to
> [`find-references-cold-resolution-perf.md`](find-references-cold-resolution-perf.md), which calls
> this same work "Option A" and first flagged the residual as parsing-bound.

## B0 + B1 — what shipped

- **B0** — `find_references` no longer resolves the hierarchy layer (it never reads
  EXTENDS/IMPLEMENTS/OVERRIDES; those carry `Occurrence.NONE`). Hierarchy is now gated behind
  `subtypes`/`supertypes` only.
- **B1** — type-refs are name-scoped: `resolveTypeReferences(ParsedUnit, String simpleName)` mirrors
  `resolveOccurrences`. Type-ref cache unit is now `(file, name)` (was whole-file). New
  `resolve.typerefs` metric. `FileSlice.structural` → `typeRefNames` (Set) + `hierarchy` (bool);
  `resolveStructuralInto` → `resolveTypeRefsInto` + `resolveHierarchyInto`; `warmStructural` →
  `warmHierarchy`. `reResolve` re-resolves previously-warm value+typeref names, hierarchy whole-file.

### Cascade gotcha the plan missed (decision #2 was wrong)
Name-scoped type-refs make `rev(type)` *partial* — a file warm for `find_references(X)` never wrote
its type-ref edge to a *different* changed type, so the cascade reverse walk can't find it. Fix in
`EdgeResolver.invalidateReferrers`: for each changed **type** moniker and each changed **name**, also
fold in `candidateFiles(name)` (complete, name-independent usage-name set); only warm files dropped
(safe). Without this, `CascadeTest.addingSupertypeBindsAPreviouslyUnconfirmedRef` regressed — a real
stale-binding bug, not just an impl-detail assertion.

## Perf reality (cold, Spring 1.5M LOC, isolated XDG cache copy per run)

`Nullable` 110→**80s**, `getName` 110→**89s**, `getBean` **38.5s**. ~20-28%, NOT the "→seconds"
the plan predicted for Nullable. Why:
- **Value layer was already name-scoped** before this change → `getName`'s dominant cost (cubic
  `StatementContext` value resolution) is untouched; B0/B1 only removed the wasted whole-file
  hierarchy+typeref passes (~20s).
- **`@Nullable` is the worst case for B1 pruning**: the queried name IS the ubiquitous one, so the
  type-ref name filter prunes ~nothing. B1 helps a lot only when the queried type is rare among the
  types its referrers also use (proven on the `typeref-scope` unit fixture: 5 type-refs → 2 resolved).
- Remaining dominant cost = **per-candidate-file `engine.parse` + value resolution**. Next levers:
  decoupled file-level parse cache + **Direction A (parallel resolution)** — both out-of-scope for the
  split. Direction A is the planned follow-up.

## Design sketch: a parse cache (next lever)

### Two parses hide in one query
`JavaParserEngine.parse(file)` does `parser.parse(file)` **with no cache** — every `find_references`
candidate file is re-lexed/re-built on every query and every name. And there are *two* parse costs:
- **Explicit** — the `CompilationUnit` we build in `parse()` for the file we scan occurrences in.
- **Implicit** — inside the source `JavaParserTypeSolver` (a root of the `CombinedTypeSolver`): when
  `.resolve()` needs a type/method declared in *another* source file, that solver parses *that* file
  to find the declaration (`JavaParserTypeSolver.parseDirectory` — the dominant residual in the
  commons-lang profile, see `find-references-cold-resolution-perf.md`).

`refresh()` today = `JavaParserFacade.clearInstances()` + `buildParser()`, and `buildParser()` makes
**fresh** `JavaParserTypeSolver`s with empty AST caches. So on *any* file change both parse layers are
discarded and re-filled by re-parsing everything on the next query.

### The distinction that makes a cache sound: syntax vs semantics
A file's **AST is purely syntactic** — `G.java`'s tree depends only on `G.java`'s bytes, never on any
other file. A file's **resolutions are semantic and cross-file** — `G`'s resolved `.foo()` may point
into `F.java`, so it goes stale when `F` changes. Today jcma conflates them: no AST cache *and* it
nukes the resolution caches together. A parse cache is just **separating the two lifetimes**:
- **AST cache** (syntax): keep `G`'s tree until **`G`'s own bytes change**; survives edits to every
  other file.
- **Resolution cache** (semantics): the existing broad reset on any change is fine at first —
  rebuilding *empty* facade/solver caches is free; the cost was re-*filling* them by re-parsing
  thousands of files, which the AST cache now avoids.

That separation is the whole win: an edit to one file no longer forces a re-parse of the other
thousands.

### What to cache, and where
- **Artifact:** the `ParsedUnit` (the `CompilationUnit`). Key by **fileId** (`FileTable` gives a
  stable id).
- **Owner:** `JavaParserEngine` holds a `Map<Integer, ParsedUnit>`; `parse()` checks it first. The
  source `JavaParserTypeSolver` should read from the same cache (or be replaced by a custom
  `TypeSolver` that does) so the *implicit* parse reuses the same trees — otherwise only half the cost
  is fixed.
- **Lifetime:** the long-lived `AnalysisSession` (MCP server) — where `getName`, then `getBean`, then
  `Nullable` re-parse heavily-overlapping candidate sets. The one-shot CLI gets ~nothing (each file is
  already parsed once per query), so this is explicitly a *session* optimization.
- **Not on disk.** A serialized AST isn't meaningfully cheaper to load than to re-parse, can't carry
  its symbol-resolver binding, and a big on-disk AST store fights the low-memory/instant-start
  identity. Persisted artifacts stay the Tier-1/Tier-2 index — the *results*, not the trees.
- **Bounded (LRU).** The real tension: a CU for a big file is large; thousands cached blows the
  low-memory budget that is jcma's whole point. Cap by count or estimated bytes, evict LRU, treat it
  as a measured memory-for-time trade.

### How it's invalidated
Hook the **existing** freshness machinery — one source of truth:
- "Bytes changed" is already detected by the `(size, mtime, xxHash64)` fingerprint, and the `Cascade`
  already calls `resolver.refreshEngine()` → `engine.refresh()` on a change.
- Refine `refresh()` into **per-file eviction** (`engine.invalidate(fileId)`): drop *that* file's AST
  entry (and the implicit solver's entry for it); keep every unchanged file's AST. Keep the broad
  resolution-cache reset for now (cheap, as above).
- Deletes/renames: evict the entry (the tombstone path already fires here).
- **Validate-on-read:** key the entry on the fingerprint; if stored ≠ current, treat as a miss and
  re-parse. Consistent with the index's existing validate-on-read stance, and a backstop for a missed
  change-signal.

### Hazard for Direction A (parallel resolve)

The terse version: a shared resolver mutated by two threads at once is unsafe. The full picture, since
it governs whether A is even reachable:

**It is not the writes.** `.resolve()` is independent per candidate file and is ~99% of cold cost;
only the *store write* must be serialized. jcma already runs queries on a single platform-thread
worker (`QueryService` — "one writer at a time … M1 serves one query") precisely to keep `LsmStore`
single-writer. The right shape is **producer/consumer**, not one-thread-for-everything: N resolver
workers compute `FileIndex`/edge lists, a single consumer applies them over a FIFO. Both invariants
survive — single-writer store and the cancel-at-file-boundary checkpoint (producers never touch
`LsmStore`; the consumer applies whole files between cancel points).

**The blocker is that JavaSymbolSolver is not thread-safe against a shared solver.**
`JavaParserEngine` holds exactly one `parser` + one `CombinedTypeSolver`/`JavaSymbolSolver`
(`buildParser`), shared across every file. Three pieces of shared mutable state race under a fan-out
of `.resolve()`:
- the `CombinedTypeSolver`'s **type cache**, filled during resolution;
- each source **`JavaParserTypeSolver`'s lazy AST cache** (the *implicit* parse of §"Two parses hide
  in one query") — populated as resolution chases declarations into other files;
- **`JavaParserFacade`** — the resolution memo — which lives in a **static, unsynchronized
  `WeakHashMap`** registry inside the library (the same one `refresh()`'s `clearInstances()` hooks).

The jar/JDK roots are already `StableSolver`-wrapped immutable indexes (safe to share); it is these
source-AST + type + facade caches that are hot and mutable. Fanning `.resolve()` across them yields a
`ConcurrentModificationException`, a corrupted memo, or — the dangerous one — a **silently wrong
answer**.

**The one failure mode the safe-degrade net misses.** `attempt()` turns a *thrown* failure (incl.
`StackOverflowError`) into an unconfirmed-tail miss — correct-by-degradation. A data race that stores a
*corrupted cache entry* returns a wrong answer **without throwing**, so it slips past the net. jcma's
correctness contract is "never silent-wrong" (PRD §4), so this — not throughput — is the gating risk
for A.

**JavaParser project guidance** (pointers to verify, not load-bearing claims). Issue
[#2671](https://github.com/javaparser/javaparser/issues/2671) ("not possible to use JavaSymbolSolver
in a multithreading context") prescribes the architecture: *"separate the dictionary from the
typesolver: multiple type solvers, same dictionary"* — share the immutable reference dictionaries
(JDK + jars) across per-thread solver front-ends, keep per-analysis resolution state private. The
static `JavaParserFacade.get()` `WeakHashMap` race is reported fixable with a `synchronized` get
("resolves 99% of the problems"; a commenter reports 60k sources across 3 threads with no observed
issue — endorsed, not guaranteed). PR
[#3343](https://github.com/javaparser/javaparser/pull/3343) (merged; predates jcma's `3.28.2`) adds a
pluggable **`Cache` interface** (`NoCache` / `InMemoryCache` / bounded `GuavaCache`) on
`JavaParserTypeSolver` (file→CU, dir→CUs, name→ref) and a symbol cache on `CombinedTypeSolver` — the
seam that lets you inject one *shared, bounded* cache instead of K× copies, defusing the memory
objection.

**Design shape — sharded resolvers + single writer.** Shard a query's candidate files K ways (K≈4–8).
Each worker owns its own `CombinedTypeSolver`/parser/facade; the immutable `StableSolver` jar/JDK roots
are shared; edges merge onto the FIFO; the single consumer applies. Speedup ≈ K on the CPU-bound
`.resolve()` (the conservative variant trades it against K× source-cache memory).

**Start conservative.** First variant: **per-shard mutable caches** (each worker its own source-AST +
facade), share only the read-mostly jar/JDK dictionaries. Zero cross-thread cache mutation → zero
silent-wrong risk, still K× on `.resolve()`. You pay K× source-AST memory and lose cross-shard reuse —
measure that delta before reaching for a shared concurrent `Cache` (which must be a *custom* concurrent
impl: stock `InMemoryCache` is a bare `WeakHashMap`, and even a synchronized facade get is "99%, not
100%"). Acquire/seed the static facade before the parallel region. **Ship a concurrency stress test.**

**Interaction with the parse cache.** The two levers are coupled by exactly this hazard: a **shared
cached CU re-resolved by two threads** is unsafe. So either (a) **partition candidate files across
workers** — each owns its CUs, which gives per-worker AST locality for free and *is* the conservative
per-shard variant — or (b) prove the shared-CU resolve path safe. (a) composes cleanly: the parse
cache becomes per-shard and the AST-locality win and the concurrency-safe variant are one design.
Decide partition-vs-shared *before* building either.

### Measured (2026-06-12): the parse/resolve split — and what it changes

We instrumented the resolve hot path (coarse per-file timers in `EdgeResolver`, surfaced behind
`jcma refs … --metrics` and `jcma repl --metrics`) and re-ran the three Spring queries. The result
**reframes the two-lever question** the rest of this doc sets up.

**Reproduce:** build a pristine (Tier-1-only) Spring index into an isolated `XDG_CACHE_HOME`, then for
each cold run copy it fresh and `jcma refs <name> --metrics --deadline 600s` from the repo root; for
the warm run, feed all three `refs` lines to one `jcma repl --metrics` session. Timers: `resolve.parse`
(explicit `engine.parse` semantic AST), `resolve.tier1` (the Tier-1 *structural* re-parse in
`sliceFor`), `resolve.values`/`resolve.typerefs` (resolution proper), `resolve.apply` (the serial store
write). Numbers below are JVM-with-JIT (`installDist`), not native — the **fractions** transfer; the
absolute times (~30s Nullable here vs ~80s at the top of this doc) do not.

**Cold, single query (% of wall):**

| leg | Nullable | getName | getBean |
|---|---|---|---|
| `engine.parse` (semantic) | 45% | 29% | 27% |
| `tier1` (structural re-parse) | 40% | 26% | 32% |
| **both parses** | **85%** | **55%** | **59%** |
| resolution proper (values+typerefs) | 7% | 13% | 20% |
| `store.applyEdit` (serial writer) | 2% | 1% | 2% |

Three things the cold split settles:
- **The residual is parsing, not resolution.** Resolution proper is 7–20%; the "cubic" value cost the
  B1 worry was about is real but never dominant. This confirms (emphatically) the "parsing-bound"
  suspicion from [`find-references-cold-resolution-perf.md`](find-references-cold-resolution-perf.md).
- **The serial leg (`applyEdit`) is ~2%.** Good news for Direction A: the part parallel resolve *can't*
  parallelize is tiny, so its Amdahl ceiling is ~K×.
- **There are *two* parses, and the second isn't in the two-lever framing.** `sliceFor` calls
  `indexer.indexFile` to rebuild each candidate's Tier-1 base by **structurally re-parsing the file** —
  even though those exact Tier-1 symbols/edges were just persisted at index time. That's 26–40% of cold
  wall re-deriving data already on disk.

**Warm session (all three queries in one process) — what the AST cache *actually* reclaims:**
`resolve.parse` fired **5146** times (= the sum of the three cold runs: zero cross-query reuse today,
since it re-parses per `(file, name)`); `resolve.tier1` fired **4129** times (= unique files — the slice
cache already collapses Tier-1 across queries). So:
- **Unique files: 4129. Cross-query file overlap: only 1017 touches (20%).** The "heavily-overlapping
  candidate sets" premise of the parse-cache motivation is only ~20% true for these three names.
- **The semantic parse cache's *realized* win on this sequence ≈ 1017 × 6.3ms ≈ 6.4s (6% of session
  wall).** The cold 55–85% is the *parse share*, not what the cache reclaims: within one query each file
  is parsed once, so the cache only reclaims cross-query overlap — small for distinct names. Its real
  payoff is **repeated / post-edit re-queries** (where `engine.refresh()` nukes everything today → up to
  100% overlap) and the **implicit parse** it shares — neither of which this distinct-name sequence
  isolates.
- **Session overhead is large and separate:** the warm session's wall (115.7s) *exceeds* the three cold
  runs summed (82.9s) despite doing *less* parsing, and only 54% of it is accounted by the resolve
  timers — the other ~53s is GC / overlay growth / freshness scans accruing over a long-lived session.
  That is the actual MCP-server shape and deserves its own look (it is neither lever).

### Revised lever order (what the measurement changed)
1. **Stop re-parsing Tier-1 (`resolve.tier1`) — read the persisted base from the store instead.** Biggest
   single reclaimable chunk (~40% of a cold query, ~20s/session), helps the **first** query, zero
   concurrency risk. *Caveat:* `LsmStore` today exposes only `symbolsOf(fileId)` — no per-file
   edges/texts read-back — so this needs a small store read path, not a one-liner. This is the standout
   finding and it was outside the original two-lever framing.
2. **Then the semantic parse cache.** Still the safer of the original two, but its realized win is
   overlap-/repeat-bound: modest on distinct queries (~6% here), large on repeats and post-edit
   re-queries. It is a **session-quality** lever, not a first-query fix. Measure the repeat/post-edit win
   (re-run the *same* query after an unrelated edit) before sizing it.
3. **Parallel resolve last.** It is the only lever that cuts *first-query* latency of the irreducible
   parse+resolve mass, and its serial floor is tiny (~2%) — but it carries the silent-wrong concurrency
   risk (above), and levers 1–2 shrink the mass it would parallelize. Reduce, then parallelize.

> **Open thread — RESOLVED (2026-06-12):** on this Spring index `getName` and `getBean` returned
> **near-zero confirmed references** (e.g. `getName`: 4958 value occurrences, ~0 confirmed), while
> `Nullable` confirmed normally (2 refs, 194 unconfirmed). Root cause was **not** the resolver and not
> a perf issue: it was **multi-module source-root discovery**. `Workspace.discoverSourceSets` applied
> the `src/main/java` / `src/test/java` convention only at the *repo root*. Spring's root has no such
> dir — its sources live in 24 per-module roots — so discovery returned empty, indexing/resolution fell
> back to registering the **repo root** as one source-solver root, and `JavaParserTypeSolver`'s
> package→path mapping then made every `org.springframework.*` type invisible → mass MISSING_CLASSPATH
> → ~0 confirmed. Fix: a pruned recursive **per-module** convention walk (`findModuleSourceRoots`) that
> unions every nested `src/main|test/java` into the discovered set. Pure source→source (empty classpath)
> already flips `getBean` to 4073 confirmed / 498 tail; single-module repos (commons-lang) were always
> fine because their root `src/main/java` exists. Hermetic by design — no build tool runs at index time;
> deriving roots from the evaluated Gradle/Maven model stays a possible future *query-time-only* layer.
