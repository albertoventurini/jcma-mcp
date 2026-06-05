# M1 · Task 08 — Freshness: fingerprints, cold scan, warm-reopen reconciliation
> Warm reopen ≠ full scan; touching one file reprocesses only that file.

## Prerequisites (read first, fresh session)
- **Done before this:** task-07 (Indexer + LsmStore persist the index + file table).
- **Read:** PRD §5.1 (filesystem-driven freshness, validate-on-read) + §10 ; M1 overview.
- **Port from M0 (reference, don't extend):** none directly (new code); reuse `SpikeB` scan driver shape.

## Protocol (test-first; full version in the overview)
Write failing tests + fixtures → **STOP for review** → implement → verify.

## Scope — files to create/modify
- `src/main/java/jcma/workspace/Fingerprint.java` — `path, size, mtime, contentHash`;
  **pure-Java xxHash64** (no native lib → minimal reachability; closes the M0 hash decision).
- `src/main/java/jcma/workspace/FileTable.java` (or fold into Workspace) — file-table persistence;
  warm-reopen reconciliation: walk + `stat`, diff vs file table → **new/deleted/suspect**, hash
  suspects only, skip mtime-lies; fast path all-match → mmap + go.
  *(Optional git accelerator: store indexed HEAD; `git diff --name-only`.)*

## Tests (red-first)
- Unit/integration: index → reopen with no change asserts **0 files reparsed** (fast path);
  touch one file → assert **exactly that file** reparsed; delete → tombstone; mtime-lie (touch,
  same bytes) → hash confirms skip.

## Manual CLI check
- `jcma index <repo>` twice (second prints "warm, 0 reparsed"); edit one file, re-run → "1 reparsed".

## Done when
- tests green · native green · hash algo (xxHash64) recorded (PRD §11) · only-touched-file reprocessed.
</content>
