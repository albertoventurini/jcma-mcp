# M1 · Task 09 — Freshness: live FS watcher + stat/hash-on-access backstop
> In-session freshness without MCP didChange (PRD §5.1 freshness).

## Prerequisites (read first, fresh session)
- **Done before this:** task-08 (Fingerprint + reconciliation supply the invalidation primitive).
- **Read:** PRD §5.1 (freshness pipeline) ; M1 overview.
- **Port from M0 (reference, don't extend):** none (new code).

## Protocol (test-first; full version in the overview)
Write failing tests + fixtures → **STOP for review** → implement → verify.

## Scope — files to create/modify
- `src/main/java/jcma/workspace/FsWatcher.java` — `WatchService` → proactive invalidation of
  changed files.
- **stat/hash-on-access backstop** on any queried file (guarantees freshness for files actually
  read) — fold into Workspace/query read path.
- `src/main/java/jcma/cli/` — `watch <repo>` (long-lived) subcommand.

## Tests (red-first)
- Unit: start watcher on a temp dir, mutate a file, assert invalidation observed within a bound;
  stat-on-access detects an out-of-band edit a missed watch event would lose.

## Manual CLI check
- `jcma watch <repo>` (long-lived); edit a file in another shell; a subsequent `jcma search`/`refs`
  reflects it.

## Done when
- tests green · native green · watcher + on-access backstop both observe an edit.
</content>
