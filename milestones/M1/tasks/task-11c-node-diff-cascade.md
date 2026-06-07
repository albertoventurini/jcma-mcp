# M1 · Task 11c — Node-diff cascade invalidation + tree-scan FreshnessSource
> On re-index, diff the nodes and walk the reverse edges. Exact, lazy, no heuristic.

## Prerequisites (read first, fresh session)
- **Read:** [`task-11-invalidation.md`](task-11-invalidation.md) (the model + the three worked
  cases — this task *implements* them) ; PRD §5.1 (freshness, validate-on-read) ; M1 overview.
- **Done before this:** task-08 (`Fingerprint`, `Reconciler` diff), task-09 (`FreshnessGuard`,
  `FreshnessSource`, `reindexOne`, the on-access backstop), task-10 (`EdgeResolver`, `warmFiles`,
  lazy resolve), **task-11a** (hierarchy edges) + **task-11b** (unconfirmed edges) — the cascade must
  walk *all* dependency edge types, so these must be in the graph first.

## What exists vs. what's missing
- **Exists:** the change *trigger* and the Tier-1 re-index (`FreshnessGuard.reindexOne`, `Reconciler`
  startup) which replaces a changed file's slice — already dropping that file's *own* stale edges.
- **Missing:** (a) a **live producer** of changed files while the process runs; (b) the **Tier-2
  cascade** that re-validates the *referrers* of a changed file's removed/changed nodes.

## Scope — files to create/modify
- **Tree-scan `FreshnessSource`** — `src/main/java/jcma/workspace/` (e.g. `TreeScanSource`): an
  O(tree) polling producer — walk the source roots, stat/hash vs `FileTable`, emit changed paths via
  `drainChanged()`. The M1 stand-in for the deferred OS watcher; it plugs into the existing seam and
  is drained by `FreshnessGuard.ensureFresh`. (A full scan can't drop events → the "what changed"
  signal is complete; latency is the only thing the real watcher later improves.)
- **Node-diff** — when a file is re-indexed (`FreshnessGuard.reindexOne` *and* the `Reconciler`
  startup path), compute **old-slice vs new-slice** node diff = `{removed monikers}` ∪
  `{signature-changed monikers}`. Surface it (extend `reindexOne`'s `boolean` return to a diff
  result / callback) so the Tier-2 layer can consume it.
- **Cascade** — `src/main/java/jcma/resolve/` (the seam between workspace Tier-1 and resolve Tier-2):
  for each removed/changed node, walk `store.rev(node)` → **referrer files**, and return those files
  to **unresolved** — re-emit their Tier-1 slice (dropping their Tier-2 edges) and/or clear their
  `warmFiles` status so `ensureResolved` re-does them. **Lazy:** they re-resolve on the next query
  that touches them; the cascade itself does no resolution.
- **Wire both entry points** (startup reconcile + on-access/scanner) so a changed file's cascade is
  applied **before** a query serves an answer.

## Tests (red-first) — the three cases + the floor
- **Body edit** (edit a method body in a *referenced* file) → assert cross-file cached refs are
  **untouched** (no re-resolve; member node identity stable). *[edit-locality]*
- **API edit** (rename / change a signature) → re-index → diff finds the removed/changed node → only
  its **referrers** re-resolve; assert the *specific* referrer files re-resolve and **non-referrers
  do not** (not repo-wide). *[exact cascade]*
- **Supertype / negative case** (uses 11a/11b edges): add a member or a supertype that makes a
  previously-**unconfirmed** ref resolve → assert the unconfirmed edge's referrer is cascaded and the
  ref **now binds** (moves from the tail to a confirmed group). *[completeness]*
- **Tree-scan source:** edit a file the query does **not** directly read → scanner emits it →
  cascade applied → next query is correct. *[live producer]*
- **Stale/corrupt floor:** mutate a file's bytes behind the index with no event → the **on-access
  backstop** content-fingerprint check forces re-index + cascade → correct answer. *[validate-on-read]*

## Manual CLI check
- `jcma refs <X>` (warm) → edit a method body → `jcma refs <X>` returns **instantly** (cache hit, no
  re-resolve). Change `X`'s signature → next query re-resolves **only the affected referrers**.

## Done when
- tests green · native green · body edit doesn't invalidate cross-file refs · API edit cascades to
  **exactly** the referrers via reverse edges (no repo-wide, no lexical guess) · supertype/unconfirmed
  cases cascade correctly · tree-scan source **and** on-access backstop both trigger the cascade.
