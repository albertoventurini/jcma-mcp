# M2 · Task 6 — Tools batch 3: `get_type_at`, `outline`, `get_source` (thin engine backends)

> Three tools whose backends sit unused behind the M1 engine seam — each a small session passthrough.

## Prerequisites (read first, fresh session)
- **Done before this:** tasks 2, 3 (+ task-4 wiring pattern).
- **Read:** M2 overview ; PRD §6 (`get_type_at`, `outline`, `get_source`) ; `AnalysisEngine.
  resolveType` + `jcma.engine.ResolvedType` (get_type_at backend) ; `StructuralParser` +
  `jcma.engine.{FileOutline,Outline}` + `jcma.cli.Outline` (outline backend) ; M1 `describe`/
  `snippetOf` lineage + `Symbol.range()` (get_source backend).

## Backend status — all **new but thin** (wrap existing engine/index capability)
- `get_type_at` — `engine.resolveType(unit, pos)` → type FQN + decl site. Add a **member summary**
  (the type's fields/methods, name + signature) from the resolved type or its outline.
- `outline` — `StructuralParser` → `FileOutline`/`Outline` tree. Pure parse; freshness-checked via
  the session (stat/hash-on-access). No resolve.
- `get_source` — declaration **source text** (not the one-line snippet `find_definition` returns) for
  a symbol by FQN/moniker: resolve to its `Symbol`, read the declaring file's byte range
  (`Symbol.range()` start→end), return it bounded by the task-3 budget.

## Protocol (test-first; full version in the overview)
Write failing tests + fixtures → **STOP for review** → implement → verify.

## Scope — files to create/modify
- `src/main/java/jcma/session/AnalysisSession.java` + `jcma/query/QueryService.java` —
  `typeAt(file, pos)`, `outline(file)`, `sourceOf(symbolOrMoniker)` passthroughs (refresh → serve;
  `outline`/`get_source` are reads, `get_type_at` resolves a single position).
- `src/main/java/jcma/tools/GetTypeAtTool.java` — input `file`+`position`; output resolved type FQN +
  member summary (hover-equivalent), member list budgeted.
- `src/main/java/jcma/tools/OutlineTool.java` — input `file`; output the structural outline
  (types → methods/fields, with `file:line` + signature), nesting preserved, budgeted.
- `src/main/java/jcma/tools/GetSourceTool.java` — input symbol FQN; output the declaration's source
  text, budgeted (large declarations truncated + marked).
- Register all three with input schemas.

## Tests (red-first)
- `GetTypeAtToolTest`: on a fixture, a position inside a variable use resolves to the right type FQN +
  members; an external (JDK) type resolves FQN + members with `external` decl; an unresolvable
  position → clean empty/`isError`, never a guess (safe-degrade contract).
- `OutlineToolTest`: a fixture with nested types/methods/fields → the expected outline tree
  (kinds, names, lines); default-package fixture handled.
- `GetSourceToolTest`: `get_source(FQN of a method)` returns exactly that declaration's source span
  (not the whole file, not one line); an oversized declaration is truncated + marked; an external
  symbol (no project source) → clean "no source available".

## Manual check
- `tools/call outline {"file":"…/StringUtils.java"}`, `get_type_at`, `get_source` on the corpus —
  eyeball the outline tree, the hover, the exact declaration source.

## Done when
- tests green · native green · all three answer over MCP, context-bearing + budgeted · safe-degrade
  on unresolvable input (no silent-wrong).

## Decisions to settle / record (PRD §11)
- **`get_type_at` member-summary depth** (own members only vs. inherited) and **`get_source` size
  bound** — recommend own-members + a generous source cap; record both.
