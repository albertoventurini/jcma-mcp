# M1 · Task 07 — Tier-1 structural indexing (parse-only, virtual-thread parallel)
> Build the structural index from a repo; serve outline + name search from it.

## Prerequisites (read first, fresh session)
- **Done before this:** task-02 (engine/parse), task-03 (SymbolStore), task-04 (Csr/Occurrence),
  task-05 (TrigramIndex), task-06 (LsmStore).
- **Read:** PRD §5.1 (Tier-1 structural) + §10 (M1 verification) ; M1 overview ; M0-RESULTS
  §"Performance & memory (Spike B)".
- **Port from M0 (reference, don't extend):**
  - `milestones/m0-spike/src/main/java/m0/SpikeB.java` — virtual-thread parallel parse-only scan driver.
  - `milestones/m0-spike/src/main/java/m0/SpikeA.java` — `occurrences(cu)` enumeration over the 7
    node categories, **no `.resolve()`** (Tier-1 is lexical/cheap).

## Protocol (test-first; full version in the overview)
Write failing tests + fixtures → **STOP for review** → implement → verify.

## Scope — files to create/modify
- `src/main/java/jcma/index/Indexer.java` — parse-only pass populating symbols, containment
  (→ outline free), signatures, **unresolved** occurrences + trigram; parallelized across
  **virtual threads**. No SymbolSolver here.
- `src/main/java/jcma/cli/` — `index <repo>` (report LOC/s + symbol count), `outline <file>`.

## Tests (red-first)
- Unit: index a multi-type fixture; assert symbol set + containment tree + outline order.
- Integration: index commons-lang; assert expected top-level types/method counts present
  (PRD §10 M1: "assert the persisted index contains expected symbols").

## Manual CLI check
- `jcma index <repo>` (LOC/s + symbol count); `jcma outline <file>`.

## Done when
- tests green · native green · cold index of ~100k LOC within "a few seconds" (§Targets).
</content>
