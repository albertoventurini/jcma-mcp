# M3 · Task 4 — Large-result UX (`grep_java`)

> Make broad queries graceful (D3): content-with-overflow-fallback to a per-file aggregation view,
> grep-style `output` modes, `path` scope, `limit`. Where `grep_java` beats grep — it **ranks
> before truncating** instead of cutting arbitrarily. Parallelizable with task-03 after task-02.

## Prerequisites (read first, fresh session)
- **Done before this:** task-01 (text index), task-02 (`grep_java` MVP).
- **Read:** `milestones/M3/M3-grep-java-degrade-to-text.md` ; `docs/grep-java-degrade-to-text.md`
  (D3a–d + "Large-result behaviour") ; M2 task-03 `ToolResult` budget/shaping ;
  `jcma.tools.FindReferencesTool` (grouped + not-exhaustive tail — the pattern to mirror) ; how
  grep's `-c`/`-l` and the built-in `Grep` tool's `output_mode` behave (the model the agent knows).
- **Decisions locked:** D3a (content-with-overflow-fallback), D3b (`output: content|files|count`),
  D3c (`path` + `limit`), D3d (rank-before-truncate + not-exhaustive marker).

## [OPEN] — raise with the user *before* implementing
1. **Threshold + budget caps — calibrate from actuals, not round numbers** (memory
   `calibrate-targets-from-failure-modes`): measure match-count distributions on a real corpus, set
   the content→aggregation collapse threshold and caps from the curve + the token budget + safe-vs-
   silent failure behaviour.
2. **Text-tier ranking function:** exact-token > substring, then fewer-files-first — confirm/refine.

## Scope — files to modify
- `GrepJavaTool`: add `output` (`content | files | count`, default `content`), `path` (glob scope),
  `limit`.
- **Overflow behaviour (D3a):** ranked content within budget; when matches exceed the calibrated
  threshold, **auto-collapse to the aggregation view** — total count, per-file counts, file list, a
  few top-ranked snippets, plus an actionable hint to narrow via `path`/tighter `query`. Same view
  reachable explicitly via `output: files | count`.
- **Always** rank symbols-first then text-by-relevance **before** applying the cap, and emit the
  *not-exhaustive, N total* marker (D3d). Reuse/extend M2 task-03 budget.

## Protocol (test-first — hard gate)
Failing tests + the calibration measurement → **STOP for user review of the threshold/caps + ranking
[OPEN] items** → implement → verify.

## Tests (red-first)
- Below threshold → ranked content (symbols-first); above threshold → **auto-collapse** to
  aggregation (per-file counts + total + narrow hint), not a flat wall.
- `output=files` → file list only; `output=count` → totals only; `output=content` → lines.
- `path` restricts to the subtree; `limit` bounds and the marker reflects the true total.
- Rank-before-truncate: with a forced tiny budget, the surviving entries are the **top-ranked**
  ones (symbol/exact before incidental), proving the differentiator over grep's arbitrary cut.

## Manual check
- A deliberately broad query (e.g. `"id"`) → aggregation view with counts + hint, bounded tokens;
  then narrow with `path` → content. Record the chosen threshold/caps + their basis in "As built".

## Done when
- tests green · native green · content-with-overflow-fallback working with `output`/`path`/`limit` ·
  thresholds/caps **calibrated from measured actuals** and recorded · rank-before-truncate verified ·
  honest not-exhaustive marker on every truncated result.
