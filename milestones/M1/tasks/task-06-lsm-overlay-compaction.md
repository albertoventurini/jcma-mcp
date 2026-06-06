# M1 · Task 06 — LSM store + Tier-1 indexing (merged 06+07) + observability
> Per-file mutation with no rescan, **fed by a real parse-only indexer**, with built-in metrics.

> **Merged 2026-06-06:** former task-07 (Tier-1 structural indexing) folded in here, because a
> working `jcma index <repo>` needs both halves — the **extractor** (`Indexer`, parses `.java` →
> symbols/edges) and the **store** (`LsmStore`, persists/mutates them). Larger than the usual
> ~3–6-file task; tackle it in the phases below, each test-first.

## Prerequisites (read first, fresh session)
- **Done before this:** task-02/02b (engine/parse + JDK solver), task-03 (SymbolStore), task-04
  (Csr/Occurrence), task-05 (TrigramIndex).
- **Read:** PRD §5.1 (LSM base+overlay, validate-on-read, Tier-1 structural) + §10 (M1 verify)
  + §11 (decisions recorded below) ; M1 overview ; M0-RESULTS §"Incremental mmap format (Spike D)"
  + §"M1 requirements surfaced by Spike D" + §"Performance & memory (Spike B)".
- **Port from M0 (reference, don't extend):**
  - `m0-spike/src/main/java/m0/SpikeD.java` — `Store` (indexed overlay, tombstones, crash log +
    replay, compaction, phantom nodes); `Oracle` round-trip driver as a test double.
  - `m0-spike/src/main/java/m0/SpikeB.java` — virtual-thread parallel parse-only scan driver.
  - `m0-spike/src/main/java/m0/SpikeA.java` — `occurrences(cu)` enumeration over the 7 node
    categories, **no `.resolve()`** (Tier-1 is lexical/cheap).

## Protocol (test-first, phased; full version in the overview)
Each phase: write failing tests + fixtures → **STOP for review** → implement → verify.
- **P1 — `LsmStore`:** base ∪ indexed overlay + tombstones + log + replay + compaction + phantoms.
- **P2 — `Indexer`:** parse-only extraction (symbols, containment, signatures, unresolved
  occurrences, trigram) across virtual threads, writing **through** `LsmStore`.
- **P3 — Observability:** metrics registry + `jcma stats` (compaction, reopen-replay, throughput).
- **P4 — CLI:** wire `index <repo>`, `compact <indexDir>`, `outline <file>`.

## Scope — files to create/modify
- `src/main/java/jcma/index/LsmStore.java` — immutable mmap base (the **SymbolStore + Csr +
  TrigramIndex** triple) ∪ **indexed** in-memory overlay in **moniker space** (new files introduce
  monikers with no base id; M0 found naive linear merge ~340× slower — keep src/dst indices),
  carrying full `Symbol` decls + typed `Csr.Edge`s with `Occurrence`s; tombstone-by-fileId;
  crash-durable overlay log + replay-on-reopen; **phantom nodes for dangling edges** preserved on
  compaction.
  - **Compaction** rewrites all three segments together (fsync + atomic rename), **including the
    trigram** so name search is correct immediately after; ids may be reassigned (moniker-stable
    identity).
  - **Trigger = relative-to-base, behind a swappable `CompactionPolicy` seam** (relative ↔ absolute
    ↔ manual); default fires when overlay-log size rivals base size. Instrumented (see P3).
  - **Durability = flush-to-OS per edit**, **checksummed records**; the log is a *validated cache*
    (correctness rests on freshness, Task-08 — a lost/torn log only costs re-parse, never a wrong
    answer). `fsync` only at compaction's atomic swap. Swappable to fsync-per-edit.
- `src/main/java/jcma/index/Indexer.java` — parse-only pass populating symbols, containment
  (→ outline free), signatures, **unresolved** occurrences + trigram; virtual-thread parallel; no
  SymbolSolver. Writes through `LsmStore` per file (so re-indexing one file is one overlay edit).
- `src/main/java/jcma/obs/` (metrics) — dependency-free registry: atomic counters + timers, a
  **no-op mode**, coarse-placed probes (per file/query/compaction). Native-image-clean (no
  reflection). See PRD §11 "Observability".
- `src/main/java/jcma/cli/` — `index <repo>` (LOC/s + symbol count), `compact <indexDir>`,
  `outline <file>`, `stats <indexDir>` subcommands.

## Tests (red-first)
- **P1:** port the Spike D oracle-driven round-trip — random add / modify-body / modify-api /
  delete edits, assert **0 mismatches** vs oracle; log replay after simulated crash; **query-
  identical pre/post compaction (all three segments, incl. trigram search)**; phantom preservation;
  torn-record (truncated log tail) is detected + skipped, not fatal.
- **P2:** index a multi-type fixture; assert symbol set + containment tree + outline order. Integ:
  index commons-lang; assert expected top-level types/method counts (PRD §10).
- **P3:** metrics-on **vs no-op** throughput within noise (~<1%) — the negligible-overhead proof;
  compaction/replay counters populated with expected values.
- **P4:** CLI smoke for each subcommand (eyeballed output shape; exit codes).

## Manual CLI check
- `jcma index <repo>` (LOC/s + symbol count); `<edit a file>`; `jcma index <repo>` (re-processes
  only the changed file) then `jcma search` shows the change; `jcma outline <file>`;
  `jcma compact <indexDir>` succeeds and queries match; `jcma stats <indexDir>` shows
  compaction/replay/throughput numbers.

## Done when
- tests green · native green (each subcommand a native smoke target) · 0 mismatches over edit batch
  · cold index of ~100k LOC within "a few seconds" (§Targets) · metrics overhead within noise ·
  decisions recorded in PRD §11 (compaction trigger, durability, observability).
