# M2 · Task 3 — Agent-response layer: shaping + token budget & truncation

> Principle #4 made real: every result is context-bearing (FQN + signature + snippet) and
> **token-bounded** so it never blows the agent's context budget.

## Prerequisites (read first, fresh session)
- **Done before this:** task-2 (the `ToolResult` seam it enriches).
- **Read:** M2 overview ; PRD §2 #4 (answers shaped for tokens) + §6 (each tool's "agent-shaped"
  output column) ; M1 `jcma.resolve.{References,ReferenceGroup,Ref,UnconfirmedRef,Definition}` (the
  shapes already produced) ; the `observability-throughout` convention (swappable + instrumented).

## Protocol (test-first; full version in the overview)
Write failing tests + fixtures → **STOP for review** → implement → verify. *(The review reshaped the
truncation strategy substantially — see Decisions.)*

## Scope — files created/modified  *(as built)*
- `src/main/java/jcma/response/ToolResult.java` — task-2's minimal result promoted to the real model:
  a list of typed `Fragment`s (`TextFragment`, `SymbolFragment`, `RefGroupFragment` carrying a
  `Fidelity`, `FileRollupFragment`, `UnconfirmedTailFragment`) rendered by `render()` to one MCP
  `{type:"text"}` block + `isError`. Task-2 factories preserved (`text`/`error`); `of(fragments)` for
  the §6 tools. Moved `jcma.mcp.ToolResult` → `jcma.response.ToolResult` (refs updated; `McpServer`
  serialises `render()`).
- `src/main/java/jcma/response/Shaping.java` — pure, stateless helpers: `symbol(Symbol, Path)`
  (FQN + kind + `file:line` + signature — the only kind-bearing path; caller resolves the path so no
  `fileId` leaks); `definition(Definition)` (signature + `file:line` + snippet, uniform
  `external (jar/JDK)` for `file == null`); `refGroup` / `references` (a `Total refs: N across M files`
  header + enclosing-symbol groups + the non-exhaustive tail). `display(sig, moniker)` mirrors
  `EdgeResolver.display` (strip leading `~`); monikers are never parsed back to FQN/kind.
- `src/main/java/jcma/response/BudgetPolicy.java` — **swappable, instrumented** token budget mirroring
  `CompactionPolicy` (`manual()` ↔ `capped(perToolCaps, defaultCap, metrics)`, `DEFAULT_CAP`). Estimate
  = `ceil(chars/4)`. **Counts-sacred / snippets-elastic** degrade (see Decisions), not lossy
  group-dropping. Instruments `response.budget.{pre_tokens,post_tokens,truncated,applied,bypassed}` + a
  timer; `isError` bypasses.

## Tests (red-first)
- `ShapingTest`: a project `Definition`, an external (jar/JDK) `Definition`, and a `Symbol` (kind path)
  render the right fields; a ref group renders enclosing-signature + count + per-ref snippet; a
  `References` with a tail renders it explicitly non-exhaustive; `display` strips the `~` marker.
- `BudgetPolicyTest`: under-cap passes through untouched; rung-1 drops snippets but keeps **every**
  `file:line` + the true total; rung-2 rolls up to per-file counts keeping **every file** + true total;
  a pathological cap stays lossless (no file dropped) + advises pagination; the unconfirmed-tail header
  is never dropped; `isError` bypasses; estimate is monotone; `manual()` never truncates.
- `BudgetOverheadTest`: budgeting a small result is within noise of passthrough (min-of-N, on < noop·2).

## Manual check  *(done — eyeballed)*
- A large synthetic `find_references` result through `Shaping.references` → `ToolResult.of` →
  `BudgetPolicy.apply` at four caps printed all four tiers (FULL → drop-snippets → per-file rollup →
  over-budget floor); every tier kept the `Total refs` header + the non-exhaustive tail. Native MCP
  smoke (`jcma serve`: initialize + tools/list + tools/call health) round-trips via `render()`.

## Done when  ✅
- tests green · native green · over-cap results bounded (and the tail honest/lossless) · policy
  swappable + instrumented · overhead within noise · decisions recorded (PRD §11).

## Decisions recorded (PRD §11 fold-back)
- **Token-estimate method** — `ceil(chars/4)` heuristic (no dependency, native-clean, monotone).
  **Adopted.**
- **Truncation strategy — *reshaped in review; supersedes the plan's "drop whole ref-groups + marker".***
  Dropping references is lossy and can drive wrong agent decisions; instead **counts are sacred,
  snippets are elastic**: a `Total refs: N across M files` header always leads; over-cap, fidelity
  degrades (drop snippets → per-file rollup → paginate-deferred) but the reference set never does. Full
  fidelity keeps M1's enclosing-symbol grouping; regroup-by-file only on degrade. See PRD §11 for the
  full rationale (incl. why snippets are the lossless drop, and pagination as the tail's hard bound).
- **Pagination** — the hard token bound for the pathological tail; **deferred to tasks 4–7** as a
  `find_references` `offset`/`limit` concern. Task-03 leaves the seam (advisory note + true total).
- **Per-tool caps** — one generous `DEFAULT_CAP` (~4000 tokens) now, **provisional/uncalibrated**;
  per-tool table + corpus calibration deferred to tasks 4–7 via the keyed `capped(...)` seam.
