# M1 · Task 11 — Invalidation: edit-locality + validate-on-read
> Correctness never depends on perfect eager invalidation.

## Prerequisites (read first, fresh session)
- **Done before this:** task-08 (fingerprints), task-10 (cached edges carry dependency fingerprints).
- **Read:** PRD §5.1 (validate-on-read, edit-locality) ; M1 overview ; M0-RESULTS §"M1 requirements
  surfaced by Spike D".
- **Port from M0 (reference, don't extend):** the Spike D edit-scoping behaviour (API-surface edit
  scoped to referrers) validated in `SpikeD.java`.

## Protocol (test-first; full version in the overview)
Write failing tests + fixtures → **STOP for review** → implement → verify.

## Scope — files to create/modify
- `src/main/java/jcma/resolve/` — cached edges carry the **dependency fingerprint** (file hashes
  resolved against); **validate-on-read** → mismatch ⇒ stale ⇒ re-resolve.
- Edit-locality: a **method-body edit** re-resolves only that file's outgoing edges; an
  **API-surface edit** is scoped to files lexically mentioning the **changed simple-name set**
  (trigram), never repo-wide.

## Tests (red-first)
- Integration: body-edit a method → assert cross-file cached refs untouched (no re-resolve);
  API-surface edit (rename/signature) → assert only trigram-matching files re-resolve; hand-corrupt
  a dependency fingerprint → assert validate-on-read forces re-resolve and returns the correct answer.

## Manual CLI check
- `jcma refs <X>` (warm), edit a method body, `jcma refs <X>` again returns instantly (cache hit);
  change `X`'s signature, next query re-resolves only the affected files.

## Done when
- tests green · native green · body-edit doesn't invalidate cross-file refs · validate-on-read
  recovers from a stale/corrupt fingerprint.
</content>
