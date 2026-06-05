# M1 · Task 06 — Index: LSM base + indexed overlay + compaction + durability
> Per-file mutation with no rescan, productionizing `SpikeD.Store`.

## Prerequisites (read first, fresh session)
- **Done before this:** task-03 (SymbolStore base), task-04 (Csr/Occurrence).
- **Read:** PRD §5.1 (LSM base+overlay, validate-on-read) ; M1 overview ; M0-RESULTS
  §"Incremental mmap format (Spike D)" + §"M1 requirements surfaced by Spike D".
- **Port from M0 (reference, don't extend):**
  - `milestones/m0-spike/src/main/java/m0/SpikeD.java` — `Store` (indexed overlay, tombstones,
    crash log + replay, compaction, phantom nodes); `Oracle` round-trip driver as a test double.

## Protocol (test-first; full version in the overview)
Write failing tests + fixtures → **STOP for review** → implement → verify.

## Scope — files to create/modify
- `src/main/java/jcma/index/LsmStore.java` — immutable mmap base ∪ **indexed** in-memory overlay
  (src/dst indices — M0 found naive linear merge ~340× slower), tombstone-by-fileId, crash-durable
  overlay log + replay-on-reopen, compaction (rewrite base + fsync + atomic rename), **phantom
  nodes for dangling edges** preserved on compaction.
- `src/main/java/jcma/cli/` — `index`, `compact <indexDir>` subcommands.
- **In-task decision:** overlay/compaction trigger policy (size/edit-count threshold) — pick + record.

## Tests (red-first)
- Unit/integration: port the Spike D oracle-driven round-trip — random add / modify-body /
  modify-api / delete edits, assert **0 mismatches** vs oracle; log replay after simulated crash;
  query-identical pre/post compaction; phantom preservation.

## Manual CLI check
- `jcma index <repo>; <edit a file>; jcma index <repo>` then `jcma search` shows the change;
  `jcma compact <indexDir>` succeeds and queries match.

## Done when
- tests green · native green · 0 mismatches over edit batch · compaction trigger policy recorded.
</content>
