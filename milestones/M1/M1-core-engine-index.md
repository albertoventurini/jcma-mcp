# M1 — Core engine + index (overview)

> **Type:** deliverable (production code). **Predecessor:** M0 (GO; all 8 gates GREEN,
> `milestones/M0-RESULTS.md`). **Parent:** `PRD.md` §7 (Milestone 1) · §5.1 (index) · §4 (engine)
> · §8 (targets). **This doc is the *how*; PRD is the *what/why*.** Consult PRD by section, on
> demand — don't read it wholesale.

## Why this milestone

M0 retired every architectural risk: JavaParser + JavaSymbolSolver resolves accurately (99.5%
coverage, **0% silent-wrong**, all misses safe-degrading), the FFM columnar CSR store round-trips
under LSM edits (0 mismatches / 200 edits), and the whole stack builds & runs as a GraalVM
native-image (~27 MB binary, ~14 ms start, ~26 MB RSS). **Nothing triggered the javac-hybrid
fallback.**

M1 turns the four throwaway spikes into the **production core engine + index**: a workspace +
classpath layer, the §5.1 mmap store (symbol columns, CSR fwd/rev, occurrences, trigram), the
freshness pipeline (fingerprints, cold scan, warm reconciliation, on-access backstop), lazy-resolve-and-
cache with edit-locality + validate-on-read invalidation, and virtual-thread cancellable query
serving. **M2 (MCP surface) and M3 (packaging/hardening) are out of scope** — M1's verification
surface is a small dev-only `jcma` CLI, not MCP.

The spikes under `milestones/m0-spike/` are **reference, not a base to extend** (CLAUDE.md): M1 is
fresh production code, but it ports proven pieces (see Ports inventory). The spikes stay intact.

## Decisions locked for M1

- **Build tool: Gradle (Kotlin DSL)** — for the mature `org.graalvm.buildtools.native` plugin.
  Closes PRD §11 "build tool". (Wrapper pinned to Gradle 9.5.1.)
- **Native-image is built from task 1 onward** — every task keeps `nativeCompile` green and runs
  a native smoke test of the `jcma` CLI. M0 proved the build order is load-bearing
  (*agent-trace → package → native-image*); keep it green continuously, never deferring.
- **Full PRD §M1 scope** — includes cancellable/time-boxed query serving. *(The live FS watcher was
  demoted during Task-09 design to an optional, deferred producer behind a `FreshnessSource` seam; M1
  ships the on-access backstop instead. See task-09.)*
- **Deliverable = separate task files** (`tasks/task-NN-*.md`), one per task, each pickable in a
  fresh Claude Code session.

## Targets — calibrated from M0 actuals (not round numbers)

The bars M1 work is measured against; they ratify the recalibrated M0 gates and PRD §8 / §11.
Carry the **measured-vs-target** framing M0-RESULTS used.

| Aspect | M1 target | Source (M0 actual) |
|---|---|---|
| Resolution coverage (safe-degrading misses ok) | ≥ 97% | 99.61% jackson / 99.48% commons |
| Go-to-def correctness (silent-wrong is the harmful mode) | ≥ 99%, **0 silent-wrong** | 100% (147/147 commons) |
| Find-refs recall **+ mandatory unconfirmed tail** | ≥ 98%, ~0% FP | 100% recall, ~0% FP |
| Cold full index, ~100k LOC (parse-only, parallel) | a few seconds | 0.29 s commons / 0.48 s jackson |
| Warm find_references p95 (with edge cache) | < 200 ms | 262 ms worst *uncached* (cache is the M1 fix) |
| Native binary: cold start / RSS | < 100 ms / < 100 MB | 14 ms / 25.8 MB |
| Incremental edit → query → compaction | 0 mismatches | 0 / 200 edits |

## Standard task protocol (applies to every task)

Each task runs as a **test-first, checkpointed** unit, sized to ~30–40% of a context window
(≈3–6 production files + their tests).

1. **Write failing tests first.** Author the test(s) **and the sample Java fixtures** they run
   against (fixtures live in `src/test/resources/fixtures/<task>/…` and are themselves the input
   under test). Tests must compile but fail (red).
2. **STOP — checkpoint for review.** Present the test list + fixtures + intended assertions and
   **wait for approval** before implementing. (Explicit gate.)
3. **Implement** the production code to turn the tests green.
4. **Verify three ways:** (a) `./gradlew test` green; (b) `./gradlew nativeCompile` green + the
   native CLI smoke check still passes; (c) the **manual CLI check** for that task (run the `jcma`
   binary against a real codebase and eyeball the result).

**Test layering, every task:**
- **Unit** — tiny hand-authored fixtures exercising exactly the feature (the file *is* the input).
- **Integration** — assert against the **pinned M0 corpora** reused in-place:
  `milestones/m0-spike/corpus/commons-lang` (accuracy/labeling) and `…/jackson-databind` (scale).
  Reuse the M0 hand-labeled worksheets (`out/gotodef-worksheet-commons.md`,
  `findrefs-worksheet-commons.md`) as recall oracles where relevant.
- **Manual** — a `jcma <subcommand>` invocation, output eyeballed.

## Project structure (fresh, per PRD §9)

```
java-lsp/
├─ settings.gradle.kts · build.gradle.kts        Gradle (Kotlin DSL) + GraalVM native plugin
├─ gradlew · gradle/wrapper/…                     pinned wrapper (9.5.1)
├─ src/main/java/jcma/
│   ├─ engine/        AnalysisEngine interface + JavaParser/SymbolSolver impl   (ports SolverSetup)
│   ├─ workspace/     source-root discovery, Maven pom parse, manual classpath, fingerprints,
│   │                 reconcile + on-access freshness backstop (FreshnessGuard/FreshnessSource)
│   ├─ index/         §5.1 store: columns, CSR, occurrences, trigram, LSM overlay, fingerprints
│   │                 (ports SpikeD.Base/Store)
│   ├─ resolve/       lazy-resolve-and-cache, invalidation                       (ports FailureClassifier)
│   ├─ query/         virtual-thread cancellable/time-boxed serving
│   └─ cli/           dev-only `jcma` harness (the M1 verification surface)
├─ src/main/resources/META-INF/native-image/      reachability config (seed from m0-spike)
├─ src/test/java/jcma/…                            JUnit 5 tests
└─ src/test/resources/fixtures/<task>/…            hand-authored sample .java inputs
```

`milestones/m0-spike/` stays untouched (reference).

## Ports inventory (from the M0 spikes — re-typed into production shape, not copied wholesale)

- **Spike D → `index/`** (`SpikeD.Base`, `Store`, `Oracle`): FFM columnar base read/write, LSM
  base+indexed-overlay+compaction, crash log + replay, phantom nodes. `Oracle` ports as a **test
  double**. → tasks 3, 4, 6.
- **Spike A → `engine/` + `resolve/`** (`SpikeA`, `SolverSetup`, `FailureClassifier`):
  - `SolverSetup.build` — type-solver wiring (JDK + source + jars). → task 2.
  - `occurrences(cu)` + `attempt(cat,node)` — guarded occurrence walk over the 7 node categories,
    `StackOverflowError` guard, the `excluded` logic. → task 7 (enumeration) **and** task 10 (resolution).
  - `describe()` / `locate()` / `loc()` — resolved decl → FQN + signature + `file:line`. → task 10.
  - `snippetOf()` + line cache — context-snippet extraction. → task 10.
  - `FailureClassifier` (12-bucket cause enum) — explains safe-degrading misses. → task 10.
  - findrefs/gotodef worksheet logic = the **reference algorithm + oracle** for task 10 (group by
    resolved signature; not-linked candidates; **unconfirmed-tail** flag). Committed
    `out/*-worksheet-*.md` are the hand-labeled recall corpus.
- **Spike C → `cli/` + native build** (`SpikeC`): `cap()`/`selftest()` → native smoke harness;
  `capMmap()` → native FFM round-trip smoke (task 3); native flags + `reachability-metadata.json`
  seed → task 1. `mcpLoop()` + hand-rolled JSON-RPC **kept for M2** (not M1).
- **Spike B → `index/` + tests** (`SpikeB`): virtual-thread parallel parse-only scan → task 7;
  live-set/RSS + p50/p90/p99 latency harness + "resolve files whose text contains the simple name"
  find_references simulation → task 10 pruning reference + the perf tests asserting §Targets.

## Task index

| # | File | Goal |
|---|---|---|
| 1 | [task-01-scaffolding.md](tasks/task-01-scaffolding.md) | Gradle+GraalVM scaffold; native `jcma` binary from day one |
| 2 | [task-02-engine-workspace-classpath.md](tasks/task-02-engine-workspace-classpath.md) | `AnalysisEngine` + JavaParser impl + workspace/classpath |
| 2b | [task-02b-jdk-type-solver.md](tasks/task-02b-jdk-type-solver.md) | Native JDK resolution: host-derived, fingerprint-cached signature index (replaces `ReflectionTypeSolver` in the native path) |
| 3 | [task-03-symbol-columns.md](tasks/task-03-symbol-columns.md) | Columnar symbol store + moniker↔int32 + string arena (FFM) |
| 4 | [task-04-csr-occurrences.md](tasks/task-04-csr-occurrences.md) | CSR fwd/rev adjacency + occurrences + edge-type enum |
| 5 | [task-05-trigram-index.md](tasks/task-05-trigram-index.md) | Trigram name index (name search + candidate pruning) |
| 6 | [task-06-lsm-overlay-compaction.md](tasks/task-06-lsm-overlay-compaction.md) | **LSM store + Tier-1 indexing (merged 06+07) + observability** — base+overlay+compaction+durability, the parse-only `Indexer`, and the metrics registry + `jcma stats` |
| 7 | [task-07-tier1-indexing.md](tasks/task-07-tier1-indexing.md) | *Merged into task 06* (Tier-1 indexing is task-06 phase P2) |
| 8 | [task-08-freshness-fingerprints.md](tasks/task-08-freshness-fingerprints.md) | Fingerprints, cold scan, warm-reopen reconciliation |
| 9 | [task-09-fs-watcher.md](tasks/task-09-fs-watcher.md) | Freshness: `reindexOne` + on-access backstop behind a `FreshnessSource` seam (watcher demoted to an optional, deferred producer) |
| 10 | [task-10-lazy-resolve-cache.md](tasks/task-10-lazy-resolve-cache.md) | Lazy-resolve-and-cache (Tier-2): definition & references |
| 11 | [task-11-invalidation.md](tasks/task-11-invalidation.md) | **Invalidation (model-everything, node-diff cascade)** — parent; split into 11a/11b/11c |
| 11a | [task-11a-hierarchy-edges.md](tasks/task-11a-hierarchy-edges.md) | Type-hierarchy edges (`EXTENDS`/`IMPLEMENTS`/`OVERRIDES`) |
| 11b | [task-11b-unconfirmed-edges.md](tasks/task-11b-unconfirmed-edges.md) | Unconfirmed references as persistent dependency edges |
| 11c | [task-11c-node-diff-cascade.md](tasks/task-11c-node-diff-cascade.md) | Node-diff cascade invalidation + tree-scan `FreshnessSource` |
| 12 | [task-12-query-serving.md](tasks/task-12-query-serving.md) | Virtual-thread cancellable, time-boxed query serving |

## Exit criteria (definition of done)

- All 12 tasks green (unit + integration), `nativeCompile` green, native CLI smoke passing.
- **PRD §10 M1 verification reproduced end-to-end:** index commons-lang & a jackson slice; assert
  persisted index contains expected symbols; touch one file and assert only it is reprocessed;
  verify classpath resolution against a Maven project with third-party deps.
- `milestones/M1-RESULTS.md` (mirroring M0-RESULTS): measured-vs-target table from §Targets,
  decisions ratified (build tool, moniker scheme, trigram heap-vs-mmap, hash algo) folded back
  into **PRD §11**.
- Spikes under `milestones/m0-spike/` left intact (reference).

## Open decisions to resolve *during* M1 (record as ratified)

- **Moniker grammar** (Task 3) — concrete SCIP-style scheme. *(PRD §11)*
- **Trigram postings: heap vs mmap** (Task 5) — recommend mmap. *(PRD §11)*
- **Overlay/compaction trigger policy** (Task 6) — *decided:* relative-to-base, swappable policy,
  instrumented; compaction rewrites all 3 segments incl. trigram. *Overlay durability — decided:*
  flush-to-OS per edit, checksummed, validated-cache (freshness owns correctness). *Observability —
  decided:* lightweight native-friendly metrics registry + `jcma stats`, built in Task 6. (PRD §11.)
- **Hash algo** (Task 8) — pure-Java xxHash64 (chosen for native friendliness). *(PRD §11/M0)*
- **Reflection-scaling under native-image** (Task 2 / 2b) — *split & partly resolved.* **Jars
  (Task 2):** byte-parse, not reflection — works with `--enable-url-protocols=jar`, zero per-project
  metadata. **JDK (Task 2b):** `ReflectionTypeSolver` can't resolve arbitrary JDK targets natively
  (can't pre-seed an unknown JDK); fix = host-derived, fingerprint-cached signature index. See
  M0-RESULTS §"Spike C" #3.
</content>
</invoke>
