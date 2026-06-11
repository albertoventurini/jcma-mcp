# M3 · Task 2 — `grep_java` MVP: both tiers, literal matching

> The first reflex-flipping win: a working `grep_java` over MCP — symbols-first, degrading to a
> labelled text tier, **literal/substring** only. No regex, no aggregation view, no `output` modes
> yet (task-03/04). This is the increment that earns the default over built-in grep.

## Prerequisites (read first, fresh session)
- **Done before this:** task-01 (text index + pure read API).
- **Read:** `milestones/M3/M3-grep-java-degrade-to-text.md` ; `docs/grep-java-degrade-to-text.md`
  (tool contract) ; `jcma.tools.SearchSymbolsTool` + `jcma.query.QueryService.searchSymbols`
  (the symbol tier to reuse) ; `jcma.mcp.{ToolRegistry,ToolHandler}` (registration + the `_meta`
  always-load it inherits automatically) ; M2 task-03 `ToolResult` shaping/budget ;
  `jcma.tools.FindReferencesTool` (the "never present a set as exhaustive" pattern).
- **Decisions locked:** D1 (keep `search_java_symbols`), D2 (corpus), D5 (`.java` only), D3d (honest
  truncation). Deferred to later tasks: D4 regex (task-03), D3a/b/c overflow+modes+scope (task-04).

## Scope — files to create/modify
- `src/main/java/jcma/tools/GrepJavaTool.java` — new `ToolHandler`. **Input (this task's subset):**
  `query` (string, **literal/substring** for now), `match` (`symbols | text | both`, default
  `both`), optional `limit`, optional `kind` (symbol-tier filter, reuse existing). **Output:** ranked
  entries, **every entry labelled** `kind`:
  - `symbol` → FQN, symbol-kind, `file:line`, signature (the `search_java_symbols` shape).
  - `string-literal` / `comment` / `javadoc` → `file:line[:col]` + grep-style line snippet.
  - **Symbols first** (existing `SymbolRanking`), then text. Bounded by the M2 task-03 budget with
    the **not-exhaustive, N total** marker (D3d). No aggregation collapse yet — just a clean cap.
- Symbol tier reuses `QueryService.searchSymbols`; text tier calls task-01's `searchText`.
- Register `grep_java` in `ToolRegistry`. **Description must state: Java sources only; returns
  semantic symbol matches first, then plain string-literal/comment/Javadoc matches; use instead of
  grep for `.java`.** (D5 + the reflex-capture rationale.)

## Protocol (test-first — hard gate)
Write failing tests + scripted `tools/call` transcripts → **STOP for user review** → implement →
verify.

## Tests (red-first)
- `GrepJavaToolTest` (scripted `tools/call`): on a fixture, `match=both` returns symbol hits ranked
  above text hits, **each labelled**; `match=symbols` ≡ `search_java_symbols` output; `match=text`
  returns only labelled text hits.
- A token that is **only** a string literal (no symbol) still returns results under `both` — proving
  the no-hole property (the whole point).
- Over-budget result is **bounded + marked not-exhaustive with a total count** (task-03 budget),
  never claimed exhaustive.
- Bad/empty input → clean `isError` result, not a transport error.

## Manual check
- `jcma serve` (or scripted stdio): `grep_java {"query":"<a known log string>"}` and
  `grep_java {"query":"StringUtils"}` → eyeball symbols-first, labelled, bounded output.
- **Dogfood:** in a Claude Code session, confirm the call-log (`~/.cache/jcma/logs/`) records
  `grep_java` being reached for where the agent previously grepped.

## Done when
- tests green · native green · `grep_java` answers over MCP with labelled symbols-first + text tiers,
  `match` param, honest cap · no-hole property demonstrated · symbol-tier perf unchanged
  (p95 < 50 ms) · description states Java-only + the grep-replacement framing.
