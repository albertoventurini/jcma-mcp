# M2 · Task 3 — Agent-response layer: shaping + token budget & truncation

> Principle #4 made real: every result is context-bearing (FQN + signature + snippet) and
> **token-bounded** so it never blows the agent's context budget.

## Prerequisites (read first, fresh session)
- **Done before this:** task-2 (the `ToolResult` seam it enriches).
- **Read:** M2 overview ; PRD §2 #4 (answers shaped for tokens) + §6 (each tool's "agent-shaped"
  output column) ; M1 `jcma.resolve.{References,ReferenceGroup,Ref,UnconfirmedRef,Definition}` (the
  shapes already produced) ; the `observability-throughout` convention (swappable + instrumented).

## Protocol (test-first; full version in the overview)
Write failing tests + fixtures → **STOP for review** → implement → verify.

## Scope — files to create/modify
- `src/main/java/jcma/response/ToolResult.java` — promote task-2's minimal result to the real model:
  structured content (text blocks the MCP client renders) built from typed fragments, `isError`, and
  a **truncation marker** (what was dropped + how to narrow). Serialises through the task-1 writer.
- `src/main/java/jcma/response/Shaping.java` — helpers shared by all tools: render a symbol as
  FQN + kind + `file:line` + signature; attach a context snippet; group refs by enclosing symbol
  (reuse `ReferenceGroup`); a uniform "external (jar/JDK)" rendering for `file == null` targets.
- `src/main/java/jcma/response/BudgetPolicy.java` — **swappable** token-budget policy: a cheap
  token *estimate* (chars/≈4, no tokenizer dep — native-clean), per-tool result caps, and bounded
  truncation that always emits the marker. Default policy + a `manual`/override seam, mirroring the
  M1 `CompactionPolicy` pattern. **Instrumented** (per-result: pre/post size, whether truncated).
- Overhead **proven** by a budget-on-vs-passthrough microtest asserted within noise (M1 metrics
  convention).

## Tests (red-first)
- `ShapingTest`: a project symbol and an external (jar/JDK) symbol render with the right
  fields; a ref group renders enclosing-signature + count + per-ref snippet; the unconfirmed tail is
  rendered as explicitly non-exhaustive.
- `BudgetPolicyTest`: a result under the cap passes through untouched; an over-cap result is
  truncated to the cap **and** carries the truncation marker (count dropped + narrowing hint); the
  marker itself is never truncated away; estimate is monotone in content size.
- `BudgetOverheadTest`: budgeting a small result is within noise of passthrough.

## Manual check
- Feed a large synthetic `find_references`-shaped result through the policy; eyeball the bounded
  output + truncation marker (a unit-driven print is fine — no MCP needed yet).

## Done when
- tests green · native green · over-cap results are bounded **and** marked · policy is swappable +
  instrumented · overhead within noise.

## Decisions to settle / record (PRD §11 fold-back)
- **Per-tool caps** — calibrate from real output sizes on the corpora (e.g. a hot `find_references`),
  not round numbers (`calibrate-targets-from-failure-modes`). Record the table.
- **Token-estimate method** — chars/4 heuristic vs. anything heavier; recommend the heuristic
  (no dependency, native-clean) and record.
- **Truncation strategy** — drop whole ref-groups (keep each group intact + counted) vs. cut
  mid-group; recommend whole-group with a "+N more in M files" marker.
