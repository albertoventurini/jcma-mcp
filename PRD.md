# PRD: `jcma` — Java Code-Map for Agents

> **An AI-coding-agent-native code-intelligence engine for Java (JDK 25+).**
> Not an editor LSP. Its job is to let an AI agent (Claude Code first) navigate a Java
> codebase semantically, with the lowest possible memory footprint and instant startup.

*(`jcma` = "Java Code-Map for Agents" — the adopted name and repo dir. Deliberately **not** an LSP; see §3 non-goals.)*

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
- **No Gradle / BSP** classpath integration in the MVP (deferred). Specifically: no parsing of
  `build.gradle(.kts)` / no build-server protocol. Standard Gradle layouts are still indexed — both
  `src/main/java` and `src/test/java` are discovered **by convention** (see below), so a Gradle
  project's sources and tests are covered without reading its build files.
- *Indexing scope clarification (not a non-goal):* **test sources are indexed**, tagged
  `SourceSet.TEST`. Discovery uses the Maven `<sourceDirectory>`/`<testSourceDirectory>` and the
  standard `src/main/java`/`src/test/java` convention; an ad-hoc tree (no build model, no standard
  layout) is indexed at the repo root as `MAIN`.

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
- **Resolving parser = project Java level, validator stripped** (2026-06-15; supersedes the earlier
  RAW-everywhere stance for this parser). The engine's *resolving* parser parses at the build's
  discovered source level (runtime-JDK fallback) so `yield`/records/sealed/patterns parse and the
  symbol resolver attaches to the whole compilation unit; the language-level **validator** is removed
  (it walks JavaParser's reflective meta-model — a native-image `NoSuchFieldError` hazard — and we emit
  no diagnostics). The source-root solver and the Tier-1 structural parse stay on `LanguageLevel.RAW`
  (declarations only, native-safe for free). The level is cached at index time next to the classpath.
  See `docs/whole-file-resolution-degradation.md` and `docs/native-jdk-resolution-gap.md`.

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
                    │   • declaration trigram + usage        │
                    │     exact-match name indexes  (mmap)  │
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
  resolved by SymbolSolver **on demand, then cached** — never fully precomputed up front, never
  recomputed per query. The cache unit is **`(file, name)`** for the value layer (calls/reads) and
  the type-ref layer (type/annotation uses) — a query resolves only the *queried name's* edges in a
  candidate file, not every edge in it — and **`file`** for the name-independent hierarchy layer
  (`EXTENDS`/`IMPLEMENTS`/`OVERRIDES`), resolved once and only when a hierarchy query asks.

**Full-text segment (`text.seg`) — the coverage floor under `grep_java` (M3).** A third token
source indexing **string literals, comments, and Javadoc** so `grep_java` has no "wasn't-a-symbol"
hole (the root cause of the grep reflex — see §6). It is an **inline-snapshot segment scanned
linearly, *not* a trigram index**: the measurement showed a trigram form is Javadoc-dominated and
~2.4× over the §5.1 low-memory budget, and a ~3 MB-class corpus does not need a sub-linear query —
so the access pattern (linear scan) is matched to the data rather than reusing the decl-trigram
machinery (mirroring the decl-vs-usage split). **Measured footprint:** all three sources ≈ **411 KB
on this repo / ~3.9 MB on commons-lang** (≈ the size of the existing whole index). `grep_java` ranks
symbol matches (Tier-1/2) first, then `text.seg` matches as an explicitly labelled text tier.

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
resolution state (per-occurrence: unresolved|resolved|stale) ·
declaration trigram name index · usage exact-match name index ·
full-text segment (text.seg)
```
**CSR (compressed sparse row)** = one `offset[symbolId]` array + a flat `targets[]` array:
compact, cache-friendly traversal. **Memory payoff:** only small bounded caches sit in the Java
heap; the big arrays — including the trigram name index (M1 Task-05: mmap'd, not heap-resident) —
live in the mmap'd file and the OS page cache holds only the working set → **RSS scales with what
you touch, not index size**, and warm startup is "mmap + go" (no deserialization → serves the
sub-100ms target).

### Store location — user cache, not in-repo
The index lives **outside the repo**, under a per-user cache: `${XDG_CACHE_HOME:-$HOME/.cache}/jcma/index/<repo-name>-<hash>`,
where `<hash>` is over the repo's canonical absolute path (disambiguates same-named repos). This is
how IntelliJ keeps project caches under its system dir, and for the same reasons: an in-repo
`.jcma/` dir would need gitignoring **and** gets crawled by IDE indexers, leaking jcma's internal
segments into IDE search. It also colocates with the existing JDK-signature cache
(`~/.cache/jcma/jdk-*.jar`). `jcma index` still accepts an explicit `[indexDir]` override; the
resolver is `jcma.workspace.IndexLayout`.

### Lazy-resolve-and-cache — reverse edges are a *byproduct* of forward resolution
The unit of resolution is a **`(file, name)`, not a symbol and not a whole file** — so we never do
a per-symbol whole-repo scan for incoming edges, *nor* resolve a file's every edge to answer about
one name. Resolving one use-site U→D yields **both** the forward edge `U→D` and the reverse edge
`D←U` in the same operation; resolving the queried name in file F populates F's outgoing edges *for
that name* and contributes the corresponding incoming edges.

- **Index time:** parse-only — records *unresolved* occurrences (use-site + syntactic target
  name + lexical context). No SymbolSolver.
- **First `find_references(X)`:** use the **usage exact-match index to prune to candidates** =
  only files containing a use of X's exact simple name (a small fraction of the repo, *not* the
  whole project); in each, resolve **only X's** value + type-ref use-sites (name-scoped — resolving
  every other type-ref just to surface X's was the dominant cold cost on large repos), caching
  forward+reverse edges. The **hierarchy layer is not touched** — `find_references` never reads it.
  Later `find_references(X)` queries in that neighborhood are pure lookups.
- **First `find_supertypes/subtypes(X)`:** resolves the **hierarchy layer only** of X's
  neighbourhood (its file + the candidate files naming its anchor type), never the type-ref layer.
- *(Optional)* an instant **"candidate references"** answer (syntactic name-match, unresolved)
  upgradable to **"confirmed"** as background resolution completes, under a time budget.

So "store both directions" describes the cache's *structure*; both directions are populated
**together, lazily**. Because type-refs are name-scoped, `rev(type)` is only as complete as the
names queried so far — so the **cascade** (§"Invalidation") sources a changed type's referrers from
the name-independent **usage-name candidate index**, not from the partial reverse type-ref edges.

### Freshness & incrementality — filesystem-driven (no document-sync)
MCP is request/response: there is **no client push channel**. And even LSP's `didChange` would
not close this — it only reports edits made *through the client that owns the buffer* (the same
blind spot as an agent hook), missing out-of-band edits, while adding an unsaved-buffer/disk
duality our agent consumer doesn't have. So the single source of truth for freshness is the
**filesystem**, which uniformly handles every edit path — the agent's Edit tool, `bash sed`,
`git checkout/pull`, another editor — *because* it is uniform, not as a workaround. (See
Task-09 for the full producer/backstop analysis.)

- **Per-file fingerprint** in the file table: `(path, size, mtime, contentHash)`. Hash is a
  **fast non-cryptographic** one (e.g. xxHash64) — we need "did the bytes change," not security.
- **Host-JDK signature cache (M1 Task-02b)** uses the same fingerprint model at JDK granularity:
  a fast hash of `$JAVA_HOME/release` + `lib/modules` size keys a `~/.cache/jcma/jdk-<fp>.jar`
  (the de-moduled JDK signatures). Built **once per JDK version** by a short-lived helper JVM that
  reads the host JDK's own `jrt:/` image, then reused across runs/projects (cache hit = no
  subprocess). This is the native-image substitute for `ReflectionTypeSolver`; the JVM/dev path
  still reflects directly. *(File fingerprints use xxHash64 (M1 Task-08); this JDK-cache key keeps
  FNV-1a — a deliberate split, hash matched to input size; see §11.)*
- **Cold start = one full parse-only scan** (only when the persisted index is missing/
  incompatible): read+hash+parse every file, build Tier-1 + trigram. Inherent (can't index
  without reading every file once), parallel, persisted. **Warm reopen ≠ full scan.**
- **Warm-reopen reconciliation:** walk the tree, `stat` (metadata only). Diff vs. the file
  table → **new** (parse+add), **deleted** (tombstone), **suspect** (mtime/size differ).
  Hash *suspects only* to confirm; re-parse genuinely-changed, skip mtime-lies (hash matches).
  Fast path: all mtime+size match → straight to mmap+go. *(Git accelerator considered and
  rejected (Task-09): `git diff <rev> HEAD` is cheap but sees only committed changes; `git status`
  stats every tracked file = O(tree) like our own walk unless fsmonitor-backed — i.e. dominated.)*
- **During a session:** the freshness *trigger* is a swappable producer behind a `FreshnessSource`
  seam (Task-09). M1 ships the minimal **stat/hash-on-access** backstop on any queried file
  (negligible cost, guarantees freshness for files we actually read, no O(tree)-per-query, no
  freshness window for read files), **plus** a **tree-scan `FreshnessSource`** (Task-11c) — an
  O(tree) poller that streams changed paths while the process is live (the signal is *complete*; only
  latency is left to improve). *New-file completeness during a session* — indexing a brand-new
  untracked file and making it a `find_references` candidate (the case the on-access backstop alone
  can't cover) — is owned by **M2 Task-10** (`milestones/M2/tasks/task-10-in-session-new-file-discovery.md`),
  which closes the M1 Task-09→Task-11 deferral. A proactive **OS filesystem watcher** is the *optional* latency upgrade,
  **deferred past M1** (FFM-inotify as the native-clean path, or an external watcher process as the
  cross-platform escape hatch) — justified by measurement, not assumed. Both producers and the backstop
  are owned by the session-scoped **`AnalysisSession`** (Task-11c), which holds the one live
  store+resolver+guard for the process and runs *refresh → cascade → serve* per query; cancellation /
  time-boxing (Task-12) and the MCP transport (M2) wrap it later.

### Invalidation — model-everything, node-diff cascade
> *Revised (M1 task-11): the earlier "per-edge dependency fingerprint + changed-simple-name-set +
> trigram" model was undermodelled — a hash is a proxy that detects* that *something changed but
> discards* what *changed and* who *depended on it, forcing a lexical guess at the blast radius that
> under-approximates Java's non-lexical dependencies (supertypes, overrides, overloads).
> Invalidation is now exact graph reachability.*
- **Change trigger (the filesystem boundary):** a file is stale **iff its bytes changed** — detected
  by its **content fingerprint** (`size, mtime, xxHash64`). This is the one fact the graph cannot
  derive about itself, so it stays; everything else is modeled. A changed file is re-parsed and its
  slice swapped (structural layer).
- **Edit-locality falls out of the graph, not a heuristic.** References are edges to
  **member-granularity** nodes, and a node's incoming edges live in the *referrer's* slice. So a
  **method-body edit** re-parses the edited file (re-deriving only its own outgoing edges) and
  touches nothing else — its members keep their identity, so every incoming edge stays valid.
- **Cross-file invalidation = node-diff + reverse-edge walk (exact, not lexical).** Re-indexing a
  changed file diffs its old vs new node set; for each **removed / signature-changed** node, walk its
  reverse edges to the *exact* referrers and return them to **unresolved** (they re-resolve lazily on
  next access). The graph already records who depends on what — we walk it instead of guessing.
  *(One exception: type-refs are name-scoped (§5.1), so `rev(type)` is partial — a changed **type**'s
  referrers are sourced from the type-name's usage-name candidate set, which is complete and
  name-independent. Still exact-not-lexical; only warm files are returned to unresolved.)*
- **Completeness requires modeling the non-lexical dependencies too:** the type hierarchy
  (`EXTENDS`/`IMPLEMENTS`/`OVERRIDES`) and **unconfirmed references** are first-class edges, so
  supertype edits and newly-satisfiable lookups cascade by the same reverse-edge walk. An unconfirmed
  reference keeps its **syntactic edge type** (a failed `foo()` is still a `CALLS` edge — *model what
  the code is*, so "count the calls in a class" stays one edge type) and points at a **name-keyed
  placeholder node** `<simple-name>~UNRESOLVED` that coalesces every failed reference to that name.
  Resolution status thus lives on the **target node**, not the edge: a newly-defined `foo` is a node
  with simple name `foo`, so the cascade walks `rev(foo~UNRESOLVED)` to the exact referrers and
  re-resolves them — the negative case (a name that didn't resolve now does) is the *same* name-keyed
  reverse walk, and it works whether or not the receiver type ever existed. (Receiver-type precision —
  re-resolving only when *that* type changes — is a deferred optimization, not needed for
  correctness. Generic-inference / overload corners remain a thin approximation frontier; the
  external-code boundary bottoms out at content fingerprints on source files + jars, plus phantom
  nodes — a confirmed reference to a library symbol is a normal edge to its own phantom, distinct from
  an `…~UNRESOLVED` placeholder.)
- **Correctness floor:** a query stat/hash-checks the files it actually reads before answering
  (on-access backstop), and a tree-scan `FreshnessSource` feeds the changed-file stream while the
  process is live; either way a changed file is re-indexed and its cascade applied before the read —
  worst case a re-resolve, never a wrong answer (the rust-analyzer/Salsa revalidation principle).
  *Value-hash early-cutoff (stop propagating when a re-derived node is identical) is an optional
  optimization atop the model, deferred until measurement asks for it.*

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
| `grep_java` *(M3)* | `query`, `match?` (symbols/text/both), `fixed_string?`, `case_sensitive?`, `kind?`, `path?`, `output?` (content/files/count), `limit?` | the **grep replacement** for `.java`: semantic **symbol** matches ranked first, then labelled **text** matches (string-literal / comment / Javadoc), so it has no "wasn't-a-symbol" hole and is never worse than grep on coverage |

**`grep_java` — the no-hole front door (M3).** Added so the agent can *reach for jcma first* on any
`.java` search rather than grep-then-maybe-jcma: it subsumes `search_symbols` (≡ `grep_java(match=symbols)`)
and degrades gracefully to a text tier, IntelliJ-shift+shift style. Routing is advisory and **portable**
— carried in the MCP server instructions + the tool's own description, so it ships to every client at zero
config (no `PreToolUse` hook; a hook is host-specific and was rejected, 2026-06-11). Large results
rank-before-truncate and auto-collapse `content`→per-file `count`. jcma is a strict superset of grep only
for `.java`; non-Java files stay on built-in grep.

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

## 9. Project structure (as built)

```
jcma/
├─ build.gradle.kts            Gradle build + GraalVM native-image plugin
├─ build-native-image.sh       native-image build wrapper
├─ src/main/java/jcma/
│   ├─ engine/                 AnalysisEngine interface + JavaParser impl, structural parser
│   ├─ workspace/              source-root + classpath discovery, FS-driven freshness, reconciler
│   ├─ index/                  §5.1 mmap store: columns, CSR graph, name/text indexes, LSM overlay
│   ├─ jdkindex/               host-JDK signature index (native-safe JDK resolution)
│   ├─ resolve/                Tier-2 lazy-resolve-and-cache: edges, hierarchy, node-diff cascade
│   ├─ session/                live per-repo state (refresh → cascade → serve)
│   ├─ query/                  cancellable, time-boxed query serving + target selection
│   ├─ mcp/                    MCP/JSON-RPC stdio server (+ mcp/json, dependency-free JSON)
│   ├─ tools/                  the §6 tool handlers
│   ├─ response/               agent-response shaping (snippets, grouping, budgeting)
│   ├─ obs/                    lightweight native-friendly metrics (counters, timers, call log)
│   └─ cli/                    `jcma` CLI dispatch (serve, repl, index, refs, def, …)
├─ src/main/resources/META-INF/native-image/   reflection/reachability config
└─ milestones/m0-spike/        retained de-risking harness + corpus oracles (reference)
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
- **Naming:** ~~adopt a name reflecting "agent-native Java code map"?~~ **Resolved — `jcma` (Java Code-Map for Agents).**
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
  demands. **Name indexes — two purpose-built formats (*split M1, 2026-06-07*):** the original plan
  reused one trigram format for both name lookups; that clause of `graph-native-index-design` was
  **dropped** (graph-native kept; format-reuse dropped) once the two consumers' requirements diverged.
  - **Declaration trigram index (`trigrams.seg`, `jcma.index.TrigramIndex`)** — the `search` surface:
    **substring** match over declaration names → **`symbolId`**. mmap'd (not heap-resident), consistent
    with the symbol/CSR segments so the heap stays bounded and RSS scales with the trigrams a query
    touches; **case-sensitive** (an agent queries exact identifiers); ranking exact → prefix →
    mid-substring, tie-broken by name length, then lexicographic, then id; queries shorter than a
    trigram fall back to verify-against-all. A case-insensitive variant stays additive (a second
    case-folded posting set under a bumped segment version). *No `fileId` column* (it was derivable
    from the node and only the usage path read it).
  - **Usage exact-match index (`usage-names.seg`, `jcma.index.UsageNameIndex`)** — the
    `find_references` candidate-file prune: **exact** simple name → **sorted distinct `fileId`s**. An
    mmap'd inverted index (name dictionary, binary-searched; plain `int32` posting slices). Exact match
    is both tighter pruning (fewer wasted resolves) and correct: every true use-site records the exact
    simple name, and substring only ever *over*-matched. **Why split:** on this repo's own index the
    shared trigram form was 60% trigram machinery the exact path never touches plus a 12% all-`-1` dead
    `symbolId` column; use-site rows dedup 3.5× → the purpose-built form is **494 KB → ~43 KB (11.5×
    smaller)**. (Delta+varint postings would save a further ~2%; rejected for v1 as not worth the
    variable-width-decode complexity.)
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
- **Transitive type-hierarchy tools — *decided (M2 Task-05)*:** `find_supertypes` + `find_subtypes`
  (wire names `find_java_supertypes`/`find_java_subtypes`) BFS-walk the direct
  `EXTENDS`/`IMPLEMENTS`/`OVERRIDES` primitives to the whole closure, warming each node's structural
  layer lazily as it is dequeued (a subtype file names its supertype, so it is a candidate at every
  level; a *method* node anchors that warm on its **enclosing type's** name, since an overrider lives
  in a subtype *type*, not at a call of the method). Each node carries its shortest-hop **depth** + the
  **`via` edge kind** (`extends → depth 2`), not a full path. **Walk bound = node-count cap, not depth**
  (real hierarchies are shallow but can fan out): **default 500**, unbounded depth; hitting it sets a
  `truncated` flag surfaced as a `(truncated at 500)` header marker — the answer is never silently
  short. (The output-token `BudgetPolicy` cap is separate and still applies.) Cycle-safe via a visited
  set (Java forbids inheritance cycles, but a stale graph cycle must still terminate). **Two-tool
  scope:** `find_implementations` is **deferred** — its "concrete only" semantics needs an
  *abstract-class* filter, but `Symbol.flags` has no abstract bit today (only `INTERFACE`/`ANNOTATION`
  are distinguishable by `kind`). The follow-up task threads an abstract modifier bit from the engine
  `Outline` → a `Symbol.flags` bit → the filter, then ships `find_implementations` on top of the same
  subtype walk.
- **Observability (cross-cutting) — *decided (M1 Task-06)*:** jcma carries a lightweight, built-in
  metrics layer so tuning decisions are data-driven rather than guessed. A dependency-free,
  native-image-friendly registry (plain atomic counters/timers — *not* Micrometer/Prometheus
  client, which are reflection-heavy and hurt instant-start/low-RSS); surfaced via `jcma stats` +
  structured stderr diagnostics. Probes are placed at **coarse boundaries** (per file / query /
  compaction, never inner loops), aggregate locally and publish once, use `LongAdder`/per-task
  locals to avoid contention, and resolve handles once (no per-event name lookup or allocation).
  Overhead is **proven** by a metrics-on-vs-no-op benchmark asserted within noise. Each task
  instruments its own hot paths; the registry + `jcma stats` land in Task-06.
- **Freshness hash — *decided (M1 Task-08)*:** **xxHash64**, implemented **pure-Java** (no native
  lib, no extracted `.so` → minimal native-image reachability), seed 0, over each file's bytes. We
  need "did the bytes change", not security — a fast non-cryptographic hash. It backs the per-file
  fingerprint `(size, mtime, contentHash)` in the persisted **file table** (`jcma.workspace.FileTable`),
  which drives warm-reopen reconciliation: stat-only fast path (size+mtime match → skip), hash only
  the *suspects* (size/mtime differ) to confirm real change vs. an mtime-lie. Implemented in
  `jcma.workspace.Fingerprint`.
  - **Two hashes, deliberately — not one.** xxHash64's speed comes from a 4-lane, 32-byte-stripe
    design that only engages on **large buffers**; file fingerprints (whole source files, thousands
    on a cold scan) are exactly that regime, so xxHash64 is a real wall-clock win there. The M1
    Task-02b **JDK-signature-cache key keeps FNV-1a**: it hashes a one-line `$JAVA_HOME/release` file
    + the jimage size, **once per startup** on a cache miss. At that size xxHash64's fixed
    setup/avalanche overhead gives no edge, and FNV-1a is simpler and at least as efficient — so each
    hash is matched to its input size rather than unified for its own sake.
- **Agent-response shaping + token budget — *decided (M2 Task-03)*:** every tool result is a list of
  typed fragments rendered to one MCP `{type:"text"}` block (`jcma.response.ToolResult`), shaped
  (`jcma.response.Shaping`) and token-bounded behind a **swappable, instrumented** policy
  (`jcma.response.BudgetPolicy`, mirroring `CompactionPolicy`: `manual()` ↔ `capped(...)` factories).
  - **Token estimate:** `ceil(chars/4)` — no tokenizer dependency, native-clean, monotone in length.
    Caps are expressed in **tokens** (the agent's currency); internal compares use this estimate.
  - **Symbol display:** render from the precomputed `signature`; degrade via `display(sig, moniker)`
    (signature if non-null, else the moniker with a leading `~` phantom-marker stripped — mirrors
    `EdgeResolver.display`). Monikers are SCIP-style/build-only and **never** parsed back to FQN/kind.
    `kind` is carried only on the `Symbol` path; `Shaping.symbol(Symbol, Path)` takes a caller-resolved
    path (a `Symbol` has only a `fileId`) so **no raw `fileId` ever reaches the agent**.
  - **Truncation strategy — *revised from the task plan after review*:** the original "drop whole
    ref-groups + `+N more` marker" was **rejected** as lossy — for `find_references` the count and the
    set of locations are the decision-/navigation-bearing data; dropping references can cause wrong
    agent decisions (rename safety, blast radius). Adopted instead: **counts sacred, snippets elastic**.
    A `find_references` answer always opens with a `Total refs: N across M files` header (the exhaustive
    count, never wrong). Over-cap, fidelity degrades but the reference set never does:
    (1) **drop snippet previews**, keep every `file:line` + count (lossless — a snippet is re-fetchable
    by reading the kept location); (2) **roll up to per-file counts** (`path: N refs`) — lossy on exact
    lines but still file-navigable; (3) when even the file summary busts the cap, return it **over budget,
    lossless** with an advisory to paginate/narrow (no file is ever silently dropped). Full fidelity
    keeps M1's **enclosing-symbol grouping** ("called from `X`"); the regroup-by-file happens only on
    degrade. The non-exhaustive unconfirmed-tail header is always kept.
  - **Pagination is the hard bound for the tail — *deferred to tasks 4–7*:** a hard token bound *and*
    zero reference loss can't both come from a post-hoc transform; `find_references` `offset`/`limit`
    (a query concern) closes the pathological tail. Task-03 leaves the seam (advisory note + true total);
    until then the cap is advisory at the extreme, by design (correctness over strict bounding).
  - **Per-tool caps — *provisional/uncalibrated*:** one generous `DEFAULT_CAP` (~4000 tokens ≈ 16k
    chars) now, keyed by tool name via `capped(perToolCaps, defaultCap, metrics)`. The per-tool table +
    corpus calibration are deferred to tasks 4–7 (`calibrate-targets-from-failure-modes`).
  - **Instrumented** at the per-result boundary (coarse — never per fragment/token):
    `response.budget.{pre_tokens, post_tokens, truncated, applied, bypassed}` + a `response.budget`
    timer; overhead proven within noise of `Metrics.noop()` (`BudgetOverheadTest`). An `isError` result
    **bypasses** budgeting and is returned verbatim.
- **Degrade-to-text coverage + `grep_java` — *decided (M3)*:** jcma loses to built-in grep on
  *coverage*, not protocol — grep has no "wasn't-a-symbol" hole, so the agent rationally greps first.
  Fix = subsume grep for `.java`: a `grep_java` tool (§6) that ranks symbol matches first then degrades
  to a labelled text tier backed by `text.seg` (§5.1: literals/comments/Javadoc, inline-snapshot scan,
  measured ≈ 411 KB here / ~3.9 MB on commons-lang). Coverage **precedes** routing — a redirect onto a
  symbol-only tool would force the agent onto a holey tool. Routing is therefore **advisory + portable**
  (MCP server instructions + tool description, shipped to every client at zero config); a `PreToolUse`
  hook was **rejected** (2026-06-11) as host-specific config that doesn't fit a client-agnostic server.
  Still open: timing of any future `search_symbols` deprecation (kept for now — it is `grep_java(match=symbols)`).
- **Exact navigation-correctness bar** for the M0 go/fall-back gate.
