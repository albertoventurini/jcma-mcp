# jcma architecture

How jcma is built and why. This is the *implementation* companion to [`PRD.md`](PRD.md) (the
stable what/why) and [`milestones/`](milestones/) (the task-by-task how). Read the PRD section
cited in each part for the full rationale; this doc is the map that ties the packages together.

jcma answers one kind of question — *navigate this Java codebase* — for one kind of caller: an
AI coding agent, over MCP. Everything below serves three properties: **semantic accuracy**
(resolve through the type system, not text), **instant start + low RSS** (a GraalVM native
binary over a memory-mapped index), and **token-bounded answers** (results shaped to fit an
agent's context).

## Request flow

```
  Claude Code (MCP client)
        │  JSON-RPC 2.0 over stdio
        ▼
  jcma.mcp          McpServer · ToolRegistry · ToolHandler        initialize / tools/list / tools/call
        ▼
  jcma.tools        FindDefinition · FindReferences · FindSupertypes ·
                    FindSubtypes · SearchSymbols · GrepJava         one ToolHandler per MCP tool
        ▼
  jcma.response     BudgetPolicy · Shaping · ToolResult            token-bounded, snippet-bearing answers
        ▼
  jcma.query        QueryService                                   cancellable, time-boxed (--deadline)
        ▼
  jcma.session      AnalysisSession                                live state: refresh → cascade → serve
        ├─► jcma.resolve   EdgeResolver · Cascade · References · Hierarchy   Tier-2 lazy-resolve-and-cache
        ├─► jcma.engine    AnalysisEngine ← JavaParserEngine        parse + JavaSymbolSolver (swappable)
        ├─► jcma.index     LsmStore · Csr · SymbolStore · *Index    mmap graph store (two tiers, base+overlay)
        └─► jcma.workspace Workspace · Reconciler · FreshnessGuard  source roots, classpath, FS-driven freshness
```

A long-running process (`jcma serve`, or the `repl`) keeps **one** `AnalysisSession` alive for
the repo. Each query runs **refresh → cascade → serve**: pick up filesystem changes, return the
affected edges to *unresolved*, then answer — resolving on demand and caching as it goes.

## The layers

### MCP server — `jcma.mcp`
The only protocol surface (PRD §4: MCP only, no LSP). `McpServer` speaks JSON-RPC 2.0 over
stdio with a hand-rolled, dependency-free JSON layer (`jcma.mcp.json`) — no Jackson, to keep the
native image small and reflection-free. The **handshake answers instantly**; the index is built
lazily on the first `tools/call`, so server start never blocks on indexing. `ToolRegistry` holds
the handlers and emits `tools/list`; it marks jcma's tools `alwaysLoad` (`_meta`) so Claude Code
does not defer them behind a tool-search step (see the `mcp-tools-deferred-by-harness` note).

### Tools — `jcma.tools`
One `ToolHandler` per MCP tool. These are thin: parse arguments, call `QueryService`, hand the
result to the response layer. The six tools:

| Tool | Backed by | Answers |
|---|---|---|
| `search_java_symbols` | declaration trigram index (Tier 1) | find a type/member by partial name |
| `find_java_definition` | name index + on-demand resolve | where a symbol is declared |
| `find_java_references` | Tier-2 edge resolve, `rev(X)` | every confirmed use of a symbol |
| `find_java_supertypes` / `find_java_subtypes` | hierarchy layer (Tier 2) | walk EXTENDS/IMPLEMENTS/OVERRIDES |
| `grep_java` | symbols first, then `text.seg` | search source; never worse than grep on coverage |

### Response layer — `jcma.response`
Agent answers are **token-bounded** (PRD principle #4). `BudgetPolicy` is a swappable seam
(`manual` / `capped`) that every tool routes its result through. Its primitive is **counts
sacred, snippets elastic**: an over-budget result first drops snippets (keeping every
`file:line` + count, losslessly re-fetchable), and only then truncates the set — completeness of
the reference *set* is never silently lost. `Shaping` groups references by enclosing symbol so a
`find_references` answer carries its own context with no follow-up read.

### Query serving — `jcma.query` + `jcma.session`
`QueryService` wraps the session with **cancellation and time-boxing**. Each query runs on a
single-thread virtual-thread executor; the caller blocks on `Future.get(deadline)`, so it returns
promptly even if a worker stalls, and on expiry the worker is interrupted at a candidate-file
boundary (never mid-edit) and a timeout is reported — no partial result. The single worker is
deliberate: it serializes mutation of the one shared store, so there is no locking and no
concurrent-overlay hazard. The agent consumer issues one query at a time; cross-query concurrency
is a deferred optimization (and parallel *resolve* was tried and reverted — see
`parallel-resolve-shipped`).

`AnalysisSession` owns the live `(LsmStore, FileTable, Indexer, AnalysisEngine)` for the
session's lifetime and runs the **refresh → cascade → serve** loop described above.

### Engine — `jcma.engine`
`AnalysisEngine` is the interface; `JavaParserEngine` is the implementation
(**JavaParser + JavaSymbolSolver**, `LanguageLevel.JAVA_25`). The interface exists so the engine
is swappable — the PRD's documented fallback was a javac-hybrid, which M0 proved unnecessary.
`StructuralParser` does the cheap **parse-only** pass that builds Tier 1 (symbols, containment,
signatures, unresolved occurrences, text units); the resolving methods (`resolveType`,
`resolveMethodCall`, occurrences) are the Tier-2 SymbolSolver path. **Native-image constraint:**
nothing here pulls in `javac` / `com.sun.source` — that is what keeps the native path reachable
(PRD §4).

### Resolve — `jcma.resolve`
**Tier-2 lazy-resolve-and-cache** (PRD §5.1), the heart of the design. `EdgeResolver` answers a
first `find_references(X)` by pruning to candidate files via the **usage-name index** (only files
that mention X's simple name — a small fraction of the repo), resolving **only X's** use-sites in
each, and writing the confirmed `CALLS`/`REFERENCES`/… edges **into the graph in both
directions** — the reverse edge `D←U` is a byproduct of resolving the forward edge `U→D`, so
"who uses X" is never a whole-repo scan. The answer is `rev(X)` grouped by enclosing symbol, plus
an **unconfirmed tail** (syntactic name-matches the resolver could not confirm — the safe-degrade
property: the agent is told, never silently shown "absent"). The second query re-resolves
nothing. `Hierarchy` resolves the name-independent EXTENDS/IMPLEMENTS/OVERRIDES layer, only when a
hierarchy query asks. `Cascade` implements node-diff invalidation: on an edit it returns the
changed nodes' referrers to *unresolved*, sourced from the usage-name candidate index (not the
partial reverse type-ref edges — see `graph-native-index-design`).

### Index — `jcma.index`
A **custom memory-mapped graph store** (PRD §5.1 — not SQLite, not a graph DB). The store is
mmap'd through `java.lang.foreign` (FFM; zero JNI, native-image-native), so warm start is
"mmap + go" with no deserialization, and **RSS scales with the working set, not the index size**.

- **Two tiers.** Tier 1 is structural (parse-only): symbol columns, the containment tree, a
  **declaration trigram** name index. Tier 2 is the **resolved-edge cache**, populated lazily by
  `jcma.resolve`.
- **Graph model.** Nodes = symbols, stored columnar (`SymbolStore`, struct-of-arrays). Edges =
  typed directed `(src, type, dst, occurrence?)` (`EdgeType`, `MonikerEdge`), stored **both
  directions** as **CSR** (`Csr` — compressed sparse row: `offset[]` + flat `targets[]`,
  cache-friendly traversal). Identity is a stable SCIP-style **moniker** (`Moniker`) interned to
  an `int32` for the adjacency lists. Strings are deduplicated in a `StringArena`.
- **LSM base + overlay.** `LsmStore` is an immutable base plus an append overlay; edits go to the
  overlay and a background `CompactionPolicy` folds it back into a fresh base. `validate-on-read`
  guards staleness.
- **Name indexes are split by access pattern**, not unified: declarations use a **trigram** index
  (`TrigramIndex`, partial-name search), usages use an **exact-match** index (`UsageNameIndex` /
  `UsageNameIndexer`, candidate pruning). Forcing one format on both was measured worse and
  dropped (`graph-native-index-design`).
- **Text tier (`TextIndex` → `text.seg`).** The coverage floor under `grep_java`: string
  literals, comments, and Javadoc, scanned as an **inline snapshot, not a trigram index** (the
  trigram form was Javadoc-dominated and ~2.4× over budget; a ~MB-class corpus does not need a
  sub-linear query — `text-tier-inline-scan`). `grep_java` ranks symbol matches first, then this
  text tier, so it has no "wasn't-a-symbol" hole and never loses to grep on coverage.

### Workspace & freshness — `jcma.workspace`
`Workspace` discovers **source roots** (per-module, Maven `pom.xml` or standard layout) and the
**classpath** (manual `cp.txt`, Maven, or Gradle — `gradle-classpath-discovery`). Multi-module
source-root discovery is load-bearing: incomplete roots silently starve resolution
(`getname-getbean-near-zero-confirmed`).

Freshness is **filesystem-driven** — MCP has no client push channel, and the filesystem uniformly
covers every edit path (the agent's Edit tool, `bash sed`, `git checkout`, another editor). Each
file carries a `(path, size, mtime, contentHash)` `Fingerprint`. `Reconciler` diffs the
`FileTable` against a metadata-only walk on reopen and classifies each file
**unchanged / suspect / new / deleted** — only new+changed files are re-parsed; an all-match
reopen does zero parse work (the base is already mmap'd). `IndexLock` is a cross-process
single-writer lock; a second process degrades to read-only. The index lives **outside the repo**
under `${XDG_CACHE_HOME:-$HOME/.cache}/jcma/index/<repo>-<hash>` (`IndexLayout`), like IntelliJ's
project caches — an in-repo dir would need gitignoring and would leak into IDE search.

### JDK signature index — `jcma.jdkindex` + `jcma.engine.HostJdkIndex`
Resolving project symbols routes constantly through JDK types — dropping the JDK solver costs
**~36–42 points** of *all* resolution (M1-RESULTS Task-02b), so it is first-class. But the native
image cannot use `ReflectionTypeSolver` (no reflective JDK metadata). Fix: a short-lived helper
JVM reads the host JDK's own `jrt:/` image (works on every JDK 9+, no `jmods` needed) and emits a
de-moduled **jar of JDK signatures**, fed to the proven `JarTypeSolver` path — pulling **zero**
JDK-internal API into the native image. It is fingerprint-cached per JDK version at
`~/.cache/jcma/jdk-<fp>.jar` (cache hit ≈ 0.2 s, no subprocess). On the JVM/dev path
`ReflectionTypeSolver` is retained.

### Observability — `jcma.obs`
A lightweight, dependency-free metrics registry (`Metrics` → `Counter` / `Timer`, backed by
`LongAdder`) so tuning decisions are data-driven, not guessed — deliberately **not**
Micrometer/Prometheus (reflection-heavy, hurts native start + RSS). `noop()` returns a
zero-overhead registry so the same instrumented code runs with metrics off. `CallLog` /
`FileCallLog` record per-tool calls. This is the `observability-throughout` principle.

## Distribution — GraalVM native image
The whole thing compiles to a **single GraalVM native binary** (`build-native-image.sh` →
`./gradlew nativeCompile`). In the M0 spike that binary was ~27 MB, started in **~14 ms**, and
ran at **~26 MB RSS** — the instant-start, low-memory target. Reachability rests on two rules
enforced throughout: the core never touches `javac` / `com.sun.source` (engine), and the index is
mmap'd via FFM with no JNI and no extracted `.so` (index). `.mcp.json` points Claude Code at the
built binary with `-C <repo> serve`.

## CLI surface
The same binary is also a dev/verification CLI (`jcma.cli`, dispatched in `Main`). `serve` (MCP)
and `repl` (warm interactive session) are the long-running entry points; `index`, `compact`,
`refs`, `def`, `supertypes`, `search`, `outline`, `stats`, `index-dump`, `resolve`, `selftest`
are direct one-shot commands over the same engine/index. `--deadline <ms>` time-boxes any query;
`-C <dir>` overrides the inferred repo root.

## Where to go next
- [`PRD.md`](PRD.md) — stable what/why; §4 decisions, §5.1 index design, §6 tool surface, §11 open questions.
- [`milestones/`](milestones/) — executable plans. `M0-RESULTS.md` (the GO verdict + measured
  gates) and `M1-RESULTS.md` (locked decisions) are the calibration record.
- [`docs/`](docs/) — deep-dive notes on specific problems (cold-resolution perf, the native JDK
  gap, the grep degrade-to-text design, the structural-resolve split).
