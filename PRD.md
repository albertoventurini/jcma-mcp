# PRD: `jcma` — Java Code-Map for Agents

> **An AI-coding-agent-native code-intelligence engine for Java (JDK 25+).**
> Not an editor LSP. Its job is to let an AI agent (Claude Code first) navigate a Java
> codebase semantically, with the lowest possible memory footprint and instant startup.

*(Working name; the repo dir is `java-lsp`, but the product is deliberately **not** an LSP — see §11 Naming.)*

---

## 1. Context — why this exists

AI coding agents navigate Java codebases today mostly with `grep`/text search. That is
*lexical*, not *semantic*: it can't distinguish a class `User` from the word "user" in a
comment, can't tell two overloads of `save()` apart, can't follow a call into a dependency
jar, and floods the agent with false positives. The result is wasted tokens, wrong guesses,
and slow code comprehension.

The mature semantic alternatives — **Eclipse JDT LS** (the Red Hat VS Code server) and to a
lesser degree Fraser's `java-language-server` — are built for **a human in an editor**. JDT
LS routinely consumes 1–2 GB and starts slowly; every existing server bolts agent use onto a
human-centric design, and none shapes its answers for token efficiency.

There is an unfilled niche: a **lean, agent-native Java navigation engine** whose *primary
consumer is an AI agent*, whose *primary surface is MCP* (the protocol Claude Code uses to
call tools), and whose design center is *low memory + fast start + context-rich, token-economical
answers*. This PRD defines that tool.

This document is the product of a design discussion; the decisions below are settled unless
listed under Open Questions.

---

## 2. Vision & core principles

1. **Agent-native, not human-native.** The consumer is an AI agent. We build what an agent
   *queries in batch* (semantic navigation), not what a human *cursor triggers* (formatting,
   inlay hints, as-you-type completion, coloring). This is the core differentiator.
2. **Lowest possible memory is a feature, not an afterthought.** Target footprint an order of
   magnitude below JDT LS. This is the single most important non-functional requirement and it
   drives the engine and distribution choices.
3. **Instant start, never freeze.** Launch to query-ready in well under a second. Indexing runs
   in the background; queries are served from a compact index and are cancellable/time-boxed.
4. **Answers shaped for tokens.** Every result carries enough context (enclosing symbol, FQN,
   signature, snippet) that the agent rarely needs a follow-up read, and is bounded so it never
   blows the context budget.
5. **Navigation first; writing maybe later.** Primary purpose is reading/navigating a codebase.
   Edit-assist tooling is a possible future, explicitly not the MVP.

---

## 3. Goals & non-goals

### Goals
- Semantic **navigation** over a Java (JDK 25+) project: symbol search, go-to-definition,
  find-references, type-at-position (hover-equivalent), file outline, type hierarchy
  (implementations/subtypes/overrides), call hierarchy.
- Expose these as **MCP tools** with agent-tuned, context-bearing, token-bounded responses.
- **GraalVM native-image** distribution: a single self-contained binary, tens-of-MB baseline
  memory, sub-100ms cold start.
- Resolve symbols against **project source + JDK + third-party jars** via Maven / a manual
  classpath.
- Incremental, persisted index so reopen is fast and steady-state memory stays bounded.

### Non-goals (explicitly out of scope)
- **No LSP surface.** No `textDocument/*`, no editor/Visual Studio/VS Code integration. (The
  shared-core design keeps an LSP adapter *possible* later, but it is not built and not a goal.)
- **No compiler-grade diagnostics** in the core (type errors, overload/inference errors,
  definite assignment, exhaustiveness, checked exceptions, override checks, warning families).
  Rationale: an agent gets authoritative correctness by *running the build* (`mvn/gradle`),
  which is cheap and already in its workflow; diagnostics are the human-valuable capability we
  least need. Deferred to an optional future javac/HotSpot "precision mode."
- **No human-editor ergonomics:** formatting, rename refactoring UI, code lenses, inlay hints,
  semantic-token coloring, as-you-type completion, signature help, document highlights.
- **No Gradle / BSP** classpath integration in the MVP (deferred).

---

## 4. Settled architectural decisions (with rationale)

| Decision | Choice | Why |
|---|---|---|
| **Analysis engine** | **JavaParser + JavaSymbolSolver** | Pure Java, no `jdk.compiler` dependency → *native-image-capable* (javac is not). Gives ~80–85% of an agent's LSP value: parsing + symbol/type resolution = exactly navigation. Avoids the multi-person-year cost of a custom analyzer. |
| **Distribution / startup** | **GraalVM native-image** (single binary); fat-JAR for dev | Biggest lever for principle #2/#3: tens-of-MB memory, ~ms startup. *Only reachable because the engine avoids javac.* |
| **Protocol surface** | **MCP only** (stdio) | MCP is how Claude Code natively calls tools. We control the response schema entirely → token-efficient, context-rich answers. No LSP impedance. |
| **Audience** | **AI agents, Claude Code first** | Sets the entire feature subset and response shaping. |
| **Build integration (MVP)** | **Maven pom + manual classpath** | Lowest-risk path to symbol resolution; the historic failure point of Java servers. Gradle/BSP deferred. |
| **Diagnostics** | **Deferred** (syntactic + unresolved-symbol only) | See non-goals. |
| **Concurrency** | **Virtual threads + structured concurrency** (stable in JDK 25) | Parallel indexing; non-blocking, cancellable query loop. |

### Known risks accepted with this engine
- **JavaSymbolSolver accuracy:** not a full type checker — deep generics / tricky overload
  resolution can resolve incorrectly or fail. Acceptable for *navigation*; validated in spike.
- **JavaSymbolSolver perf/memory at scale:** limited caching, can re-resolve aggressively.
  This is the #1 technical risk → gated by Milestone 0.
- **Native-image symbol resolution = byte-parsing, not reflection** (refined in M1 Task-02): source
  and third-party jars resolve by parsing bytes off disk (jars need `--enable-url-protocols=jar`),
  with **zero** per-project reachability metadata. The JDK is the exception — `ReflectionTypeSolver`
  uses runtime reflection, which native-image can't serve for an *arbitrary* target JDK; it is
  replaced by a host-derived, fingerprint-cached JDK **signature** index (M1 Task-02b). Only jcma's
  own deps + any MCP library still need reachability metadata (agent-traced). See M0-RESULTS §"Spike
  C" #3.

### Contingency / reversibility
The engine sits behind an **`AnalysisEngine` interface**. If the Milestone 0 spike fails its
gates, the documented fallback is a **javac-hybrid engine on HotSpot** (embed `javac` via
`com.sun.source` for resolution + AppCDS/jlink for startup), trading the native-image memory
win for correctness and free diagnostics. The same interface also leaves room for the optional
future javac-backed precision mode.

---

## 5. System design (target shape)

```
                    ┌──────────────────────────────────────┐
   Claude Code ───► │  MCP stdio server (JSON-RPC 2.0)      │   ← only protocol surface
   (MCP client)     │  tools/list, tools/call, initialize   │
                    ├──────────────────────────────────────┤
                    │  Agent-response layer                 │   ← context-bearing, token-bounded
                    │  (groups refs by enclosing symbol,    │
                    │   attaches snippets, FQNs, limits)    │
                    ├──────────────────────────────────────┤
                    │  AnalysisEngine (interface)           │
                    │   └─ JavaParser + JavaSymbolSolver    │   ← swappable; javac-hybrid = fallback
                    ├──────────────────────────────────────┤
                    │  Index  (custom mmap store, §5.1)     │
                    │   • trigram name index   (mmap)       │
                    │   • symbol columns + CSR graph (mmap) │   ← edges stored both directions
                    │   • immutable base + overlay (LSM)    │   ← incremental, bg-compacted
                    │   • per-file fingerprint sz/mtime/hash│
                    ├──────────────────────────────────────┤
                    │  Workspace                            │
                    │   • source-root + classpath (Maven/   │
                    │     manual)                           │
                    │   • FS watcher + parse-only indexing  │   ← virtual threads
                    └──────────────────────────────────────┘
                              compiled to → GraalVM native-image (single binary)
```

## 5.1 Index design

The index is the make-or-break component for query latency *and* for the low-memory
principle. Design decisions (settled): **custom memory-mapped store** (not SQLite, not a
graph-DB engine), a **graph-shaped, general-purpose data model**, relationships built by
**lazy-resolve-and-cache**, edges stored in **both directions**.

### Two tiers (keeps memory low)
- **Tier 1 — structural index** built by cheap **parse-only** passes (symbols, containment,
  signatures, *unresolved* occurrences) + a name index. Serves `search_symbols`, `outline`,
  definition-by-name cheaply.
- **Tier 2 — resolved-edge cache**: precise relationships (references/calls/overrides/…)
  resolved by SymbolSolver **on demand, at file granularity, then cached** — never fully
  precomputed up front, never recomputed per query.

### Data model — a general-purpose graph
- **Symbol identity, two IDs:** a stable **moniker** (SCIP-style structured string; survives
  re-indexing; can name dependency symbols inside jars we never parse) ↔ an interned
  **`int32`** used inside all adjacency lists/occurrences (compact, cache-friendly).
- **Nodes** = symbols, stored **columnar (struct-of-arrays)**: `kind, flags, enclosing,
  fileId, range, nameRef, sigRef, monikerRef`. `enclosing` is the containment tree (→
  `outline` free). Files are first-class nodes (path, source-root, + fingerprint, below).
- **Edges** = uniform typed directed `(src, type, dst, occurrence?)` with an **extensible
  type enum** (`CONTAINS, REFERENCES, CALLS, EXTENDS, IMPLEMENTS, OVERRIDES, HAS_TYPE,
  INSTANTIATES, ANNOTATED_BY, THROWS, IMPORTS, …`) — kept rich and general so future
  "how is this codebase connected / blast-radius / cycles" queries are plain traversals.
- **Both directions stored.** The *reverse* direction ("who references/calls/implements X")
  is the whole-project computation that is brutal on-demand, so it is exactly what we cache.
  Reference edges carry an **occurrence**: `(fileId, range, enclosingSymbolId, role)` where
  role ∈ {read, write, call, typeref, …} — that enclosing-symbol + range is what lets
  `find_references` return grouped, snippet-bearing results with no follow-up read.

### Physical layout — memory-mapped via FFM, zero JNI
A segmented file mmap'd through `java.lang.foreign` (`Arena.ofShared()` + `FileChannel.map`
→ `MemorySegment`; stable in JDK 25, native-image-native, no extracted `.so`):

```
header · string arena (dedup UTF-8) · symbol columns · file table ·
fwd adjacency (CSR) · rev adjacency (CSR) · occurrences (per-file slices) ·
resolution state (per-occurrence: unresolved|resolved|stale) · trigram name index
```
**CSR (compressed sparse row)** = one `offset[symbolId]` array + a flat `targets[]` array:
compact, cache-friendly traversal. **Memory payoff:** only small bounded caches sit in the Java
heap; the big arrays — including the trigram name index (M1 Task-05: mmap'd, not heap-resident) —
live in the mmap'd file and the OS page cache holds only the working set → **RSS scales with what
you touch, not index size**, and warm startup is "mmap + go" (no deserialization → serves the
sub-100ms target).

### Lazy-resolve-and-cache — reverse edges are a *byproduct* of forward resolution
The unit of resolution is a **file's occurrences, not a symbol** — so we never do a
per-symbol whole-repo scan for incoming edges. Resolving one use-site U→D yields **both**
the forward edge `U→D` and the reverse edge `D←U` in the same operation; resolving file F
populates F's outgoing edges *and* contributes incoming edges to every declaration F touches.

- **Index time:** parse-only — records *unresolved* occurrences (use-site + syntactic target
  name + lexical context). No SymbolSolver.
- **First `find_references(X)`:** use the **trigram index to prune to candidates** = only
  files whose text contains X's simple name (a small fraction of the repo, *not* the whole
  project); resolve those candidates (bounded pool, cancellable); cache forward+reverse edges.
  Paid once; resolving those files also warms reverse edges for every other symbol they touch.
  Later queries in that neighborhood are pure lookups.
- *(Optional)* an instant **"candidate references"** answer (syntactic name-match, unresolved)
  upgradable to **"confirmed"** as background resolution completes, under a time budget.

So "store both directions" describes the cache's *structure*; both directions are populated
**together, lazily**. The reverse direction for X becomes *complete* once its name-candidate
files are resolved — which the first reverse query triggers and caches until they change.

### Freshness & incrementality — filesystem-driven (no document-sync)
MCP is request/response: there is **no `didChange`** notification. So the single source of
truth for freshness is the **filesystem**, which uniformly handles every edit path — the
agent's Edit tool, `bash sed`, `git checkout/pull`, another editor.

- **Per-file fingerprint** in the file table: `(path, size, mtime, contentHash)`. Hash is a
  **fast non-cryptographic** one (e.g. xxHash64) — we need "did the bytes change," not security.
- **Host-JDK signature cache (M1 Task-02b)** uses the same fingerprint model at JDK granularity:
  a fast hash of `$JAVA_HOME/release` + `lib/modules` size keys a `~/.cache/jcma/jdk-<fp>.jar`
  (the de-moduled JDK signatures). Built **once per JDK version** by a short-lived helper JVM that
  reads the host JDK's own `jrt:/` image, then reused across runs/projects (cache hit = no
  subprocess). This is the native-image substitute for `ReflectionTypeSolver`; the JVM/dev path
  still reflects directly. *(Currently SHA-free FNV-1a; xxHash64 is the eventual project-wide hash.)*
- **Cold start = one full parse-only scan** (only when the persisted index is missing/
  incompatible): read+hash+parse every file, build Tier-1 + trigram. Inherent (can't index
  without reading every file once), parallel, persisted. **Warm reopen ≠ full scan.**
- **Warm-reopen reconciliation:** walk the tree, `stat` (metadata only). Diff vs. the file
  table → **new** (parse+add), **deleted** (tombstone), **suspect** (mtime/size differ).
  Hash *suspects only* to confirm; re-parse genuinely-changed, skip mtime-lies (hash matches).
  Fast path: all mtime+size match → straight to mmap+go. *(Git accelerator: store the indexed
  HEAD in the header; `git diff --name-only <rev> HEAD` yields the changed set for free.)*
- **During a session:** a **filesystem watcher** (`WatchService`/native) invalidates
  proactively; **stat/hash-on-access** on any queried file is the backstop (negligible cost,
  guarantees freshness for files we actually read).

### Invalidation — edit-locality + validate-on-read
- **Structural layer:** stale **iff that file's bytes changed** — purely local, re-parse the
  one file, swap its slice.
- **Resolved-edge cache — edit-locality asymmetry:** a **method-body edit** (the common case)
  changes *no* cross-file resolution — only that file's outgoing edges re-resolve. Only an
  **API-surface edit** (a public/protected signature, type, supertype, modifier, or the set of
  overloads) can change how *other* files resolve, and it is scoped by the **changed simple-name
  set + trigram pruning** to just the files lexically mentioning those names — never a repo-wide
  invalidation.
- **Validate-on-read backstop:** every cached edge carries the **dependency fingerprint**
  (file hashes) it was resolved against; on read, a mismatch ⇒ stale ⇒ re-resolve. Correctness
  therefore never depends on getting eager invalidation perfect — worst case is a re-resolve,
  never a wrong answer (the rust-analyzer/Salsa revalidation principle, in miniature).

---

## 6. MCP tool surface (the differentiator)

Agent-shaped tools. Every response is **context-bearing** (enclosing symbol + FQN + signature +
snippet, so no follow-up read is needed) and **token-bounded** (limits + truncation). Exact
schemas finalized in Milestone 2; initial set:

| Tool | Input | Output (agent-shaped) |
|---|---|---|
| `search_symbols` | `query`, `kind?`, `limit?` | ranked symbols: FQN, kind, `file:line`, signature |
| `find_definition` | symbol **or** `file`+`position` | declaration `file:line`, signature, context snippet |
| `find_references` | symbol **or** `file`+`position` | refs **grouped by enclosing symbol**, with snippets + counts |
| `get_type_at` | `file`, `position` | resolved type FQN + member summary (hover-equivalent) |
| `outline` | `file` | structural outline (types, methods, fields) |
| `find_implementations` | type/method | implementors / overriders, context-bearing |
| `find_subtypes` / `find_supertypes` | type | type-hierarchy navigation |
| `call_hierarchy` | symbol, `direction` (callers/callees) | grouped callers or callees with snippets |
| `get_source` | symbol (FQN) | declaration source by symbol (vs. raw file+range) |

**MCP transport:** stdio (Claude Code launches the binary as a subprocess). Implementation:
prefer a **minimal hand-rolled MCP/JSON-RPC stdio layer** to keep native-image clean and avoid
SDK reflection issues; evaluate the official MCP Java SDK only if native-image-friendly.

---

## 7. Milestones

> Each milestone has a concrete execution doc under [`milestones/`](milestones/). This section
> is the summary; the docs hold the tasks, gates, and verification.
> — M0: [`milestones/M0-de-risking-spike.md`](milestones/M0-de-risking-spike.md)

### Milestone 0 — De-risking spike *(days; a gate, not a deliverable)*
Prove on **a real medium-to-large repo** (e.g. a ~100k+ LOC OSS Java project):
1. **Accuracy:** JavaParser + SymbolSolver resolves references/types correctly often enough for
   navigation (measure hit rate on a sample of go-to-def / find-refs).
2. **Native-image:** the parser + symbol solver + a trivial MCP stdio echo loop **build and run
   as a GraalVM native-image** (reflection config solved). Confirm **FFM mmap reads** work
   under native-image.
3. **Budget:** hits the memory + latency targets in §8.
4. **Incremental format (§5.1):** prototype the **LSM base + overlay + background-compaction**
   write path and confirm CSR adjacency can be mutated incrementally via the overlay — this is
   the trickiest part of the store and is worth de-risking before committing to the format.

**Gate:** any failure → fall back to javac-hybrid on HotSpot (§4 contingency) and re-baseline §8.

### Milestone 1 — Core engine + index
- Workspace/source-root discovery; **Maven pom parsing** + **manual classpath file** support;
  SymbolSolver type-solvers wired to source + jars + JDK.
- **The §5.1 index:** mmap'd custom store (FFM) — symbol columns, CSR fwd/rev adjacency,
  occurrences, trigram name index; **moniker ↔ int32** symbol identity.
- **Freshness pipeline:** per-file `(size, mtime, hash)` fingerprints; cold full parse-only
  scan; warm-reopen reconciliation (new/deleted/suspect → hash-confirm); FS watcher +
  stat-on-access.
- **Lazy-resolve-and-cache** with trigram-pruned candidate resolution; **edit-locality +
  validate-on-read** invalidation.
- Virtual-thread parallel indexing; non-blocking, cancellable query serving.

### Milestone 2 — Agent-native MCP surface
- MCP stdio server (`initialize`, `tools/list`, `tools/call`).
- Implement the §6 tools against the engine.
- **Agent-response layer**: grouping, snippets, FQNs, token budgeting/truncation.

### Milestone 3 — Scale & robustness hardening
- Native-image packaging → single-binary distribution; release pipeline.
- Large-repo perf + enforced memory ceilings; graceful degradation on unresolved symbols /
  partial classpath (return best-effort + clear "unresolved" markers, never crash).
- Query cancellation / time-boxing.
- Benchmark suite + **navigation-correctness test corpus**.

### Later / optional (explicitly post-MVP)
- javac-backed **precision mode** (compiler diagnostics, HotSpot) behind `AnalysisEngine`.
- **Gradle / BSP** classpath integration.
- Edit-assist ("write") tools.
- An LSP adapter *if* a real need appears (currently a non-goal).

---

## 8. Success metrics (initial targets — calibrate/validate in Milestone 0)

| Metric | Target |
|---|---|
| Cold start (binary launch → query-ready) | < 100 ms (native-image) |
| Baseline idle memory | < 100 MB |
| Steady-state memory, medium repo (~100k LOC) | < 250 MB |
| Steady-state memory, large repo (~1M LOC) | < 750 MB |
| First full index, ~100k LOC | a few seconds (parallel) |
| `search_symbols` p95 (served from index) | < 50 ms |
| `find_definition` / `find_references` p95 | < 200 ms |
| Navigation correctness (go-to-def / find-refs hit rate on corpus) | high enough to beat grep decisively; exact bar set in M0 |

These are aspirational and the SymbolSolver may push memory/latency higher — **Milestone 0
calibrates them and decides go / fall-back.**

---

## 9. Proposed initial project structure (no code exists yet)

```
java-lsp/                      (consider renaming, e.g. jcma/)
├─ build.gradle(.kts)|pom.xml  build + GraalVM native-image plugin
├─ src/main/java/.../
│   ├─ engine/                 AnalysisEngine interface + JavaParser impl
│   ├─ workspace/              source-root discovery, classpath (Maven/manual)
│   ├─ index/                  §5.1 mmap store: columns, CSR graph, trigram, LSM overlay, fingerprints
│   ├─ mcp/                    minimal MCP/JSON-RPC stdio server
│   ├─ tools/                  the §6 tool handlers
│   └─ response/               agent-response shaping (snippets, grouping, budgeting)
├─ src/main/resources/META-INF/native-image/   reflection/reachability config
└─ bench/                      benchmark + navigation-correctness corpus harness
```

---

## 10. Verification (how we'll know each stage works)

- **Milestone 0:** run the spike binary against a checked-out OSS Java repo; record memory (RSS),
  cold-start time, query latency, and resolution hit rate on a hand-labeled sample; confirm the
  native-image builds. Compare against the §8 table → go / fall-back decision.
- **Milestone 1:** index a known repo; assert the persisted index contains expected symbols;
  re-index after touching one file and assert only that file is reprocessed; verify classpath
  resolution against a Maven project with third-party deps.
- **Milestone 2:** drive the MCP server over stdio with scripted `tools/call` requests
  (and, end-to-end, by registering it with Claude Code); assert each tool returns correct,
  context-bearing, bounded results.
- **Milestone 3:** run the benchmark suite on small/medium/large repos; assert the §8 ceilings
  hold; fault-injection for partial classpath / unresolved symbols; verify query cancellation.

---

## 11. Open questions
- **Naming:** the product is not an LSP — adopt a name reflecting "agent-native Java code map"?
- **Build tool for the project itself:** Gradle vs Maven (Gradle has the more mature
  native-image plugin story).
- **Index persistence format:** *decided* — custom memory-mapped store (§5.1). **Overlay/compaction
  trigger policy — *decided (M1 Task-06)*:** compact when the overlay grows to *rival the base*
  (overlay-log size relative to base size), expressed behind a **swappable policy** (relative ↔
  absolute ↔ manual) so it can change without touching the store, and **instrumented** (each
  compaction records overlay/base bytes, ratio at trigger, rewrite duration; each reopen records
  replayed-edit count + duration) so the threshold is re-calibrated from data, not faith. `jcma
  compact` forces it manually. Compaction rewrites **all three segments together** (symbols + edges
  + trigram) atomically, so name search is correct immediately post-compaction. **Overlay durability
  — *decided (M1 Task-06)*:** the overlay log is **flushed to the OS per edit** (survives process
  crash; ~µs cost) with **checksummed records**, and treated as a *validated cache* — correctness
  rests on freshness/validate-on-read (§5.1, Task-08) re-indexing against the actual files, never on
  the log surviving; a lost or torn log only costs re-parsing, never a wrong answer. `fsync` is
  reserved for compaction's atomic base swap. Also swappable (→ fsync-per-edit) if data ever
  demands. **Trigram index — *decided (M1 Task-05)*:** mmap'd
  (not heap-resident), consistent with the symbol/CSR segments so the heap stays bounded and RSS
  scales with the trigrams a query touches; **case-sensitive** matching (an agent queries exact
  identifiers and find-refs name-pruning is case-sensitive Java); ranking is exact → prefix →
  mid-substring, tie-broken by name length, then lexicographic, then id; queries shorter than a
  trigram fall back to verify-against-all. A case-insensitive variant stays additive (a second
  case-folded posting set under a bumped segment version). Implemented in `jcma.index.TrigramIndex`.
  - **Moniker scheme:** *decided (M1 Task-03)* — SCIP-style structured string, built bottom-up so
    descriptors compose by concatenation (each self-terminates): package `com.acme.foo` →
    `com/acme/foo/` (dots→`/`, trailing `/`; default package → empty); type → `…Bar#` (nested:
    `…Bar#Baz#`); method → `…Bar#doIt(int,String).` (comma-joined param type names **as written in the
    declaration** — generics erased, arrays/varargs kept as `[]`; *not* resolved to FQNs, since
    Tier-1 is parse-only); constructor → method named `<init>`; field/term → `…Bar#value.`. A
    *local* scheme (no package-manager/version coordinates). **Param-type spelling (amended M1
    Task-06):** a *project* symbol's moniker is always built from its declaration on both tiers —
    Tier-1 indexing and Tier-2 resolve (task-10) — so they agree by construction; the moniker is an
    opaque identity key, never parsed for type facts (those come from resolved
    `HAS_TYPE`/`REFERENCES` edges). *External* jar/JDK symbols, which have no project declaration,
    take the same shape keyed by the resolved FQN. **Task-10 constraint:** Tier-2 must rebuild a
    project method's moniker from the declaration AST, not from the resolved FQN signature.
    Implemented in `jcma.index.Moniker`.
- **Observability (cross-cutting) — *decided (M1 Task-06)*:** jcma carries a lightweight, built-in
  metrics layer so tuning decisions are data-driven rather than guessed. A dependency-free,
  native-image-friendly registry (plain atomic counters/timers — *not* Micrometer/Prometheus
  client, which are reflection-heavy and hurt instant-start/low-RSS); surfaced via `jcma stats` +
  structured stderr diagnostics. Probes are placed at **coarse boundaries** (per file / query /
  compaction, never inner loops), aggregate locally and publish once, use `LongAdder`/per-task
  locals to avoid contention, and resolve handles once (no per-event name lookup or allocation).
  Overhead is **proven** by a metrics-on-vs-no-op benchmark asserted within noise. Each task
  instruments its own hot paths; the registry + `jcma stats` land in Task-06.
- **Exact navigation-correctness bar** for the M0 go/fall-back gate.
