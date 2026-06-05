# M1 · Task 10 — Lazy-resolve-and-cache (Tier-2): definition & references
> Precise edges resolved on demand, cached, with the mandatory unconfirmed tail.

## Prerequisites (read first, fresh session)
- **Done before this:** task-02 (engine resolve), task-04 (Csr/Occurrence cache target),
  task-05 (TrigramIndex pruning), task-06 (LsmStore cache writes), task-07 (Tier-1 occurrences).
- **Read:** PRD §4 + §5.1 (lazy-resolve-and-cache) ; §6 (find_definition/find_references shaping) ;
  M1 overview ; M0-RESULTS §"M1 requirements surfaced by Spike A/B".
- **Port from M0 (reference, don't extend):**
  - `milestones/m0-spike/src/main/java/m0/SpikeA.java` — `attempt(cat,node)` guarded `.resolve()`;
    `describe()`/`locate()`/`loc()` decl→FQN+sig+`file:line`; `snippetOf()` + line cache.
  - `milestones/m0-spike/src/main/java/m0/FailureClassifier.java` — 12-bucket miss causes.
  - `milestones/m0-spike/src/main/java/m0/SpikeB.java` — "resolve files whose text contains the
    simple name" find_references pruning simulation.
  - Oracles: `out/findrefs-worksheet-commons.md`, `out/gotodef-worksheet-commons.md` (hand-labeled).

## Protocol (test-first; full version in the overview)
Write failing tests + fixtures → **STOP for review** → implement → verify.

## Scope — files to create/modify
- `src/main/java/jcma/resolve/EdgeResolver.java` — on first `find_references(X)`: trigram-prune to
  candidate files, resolve their occurrences (bounded pool; port `SpikeA.attempt`), cache
  **forward + reverse** edges together (reverse is the byproduct). `find_definition` likewise,
  shaped via `describe`/`locate`/`loc` + `snippetOf`. Port `FailureClassifier` to bucket misses.
- **Mandatory "unconfirmed tail"** (M0 Spike A requirement): never present a refs/impls set as
  exhaustive when any candidate occurrence failed to resolve — carry unresolved candidates explicitly.
- `src/main/java/jcma/cli/` — `refs <symbol>` (grouped + counts + unconfirmed tail), `def <symbol>`.

## Tests (red-first)
- Integration: find_refs recall on **M0-labeled commons symbols** (worksheet oracle) — assert 100%
  of labeled refs **and** that an overload-ambiguity case surfaces an unconfirmed tail (not a silent
  miss); find_definition correctness on labeled go-to-def sites; second query = pure cache lookup
  (assert no re-resolve).

## Manual CLI check
- `jcma refs <symbol>` (grouped by enclosing symbol + counts + unconfirmed tail); `jcma def <symbol>`.

## Done when
- tests green · native green · §Targets find-refs recall/0-silent-wrong + unconfirmed-tail enforced.
</content>
