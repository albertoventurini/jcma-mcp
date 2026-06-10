# M2 · Task 4 — Tools batch 1: `find_definition`, `find_references`, `search_symbols`

> The highest-payoff tools — the agent's grep replacement. Two backends already exist in M1.

## Prerequisites (read first, fresh session)
- **Done before this:** task-2 (server + `ToolHandler`/`ToolRegistry`), task-3 (`ToolResult` +
  shaping + budget).
- **Read:** M2 overview ; PRD §6 (the three rows) ; M1 `QueryService`
  (`findDefinition`/`findDefinitionAt`/`findReferences`/`declarations`) ; `jcma.cli.{Def,Refs,Search}`
  (existing render logic to reuse) ; `jcma.index.{TrigramIndex,SymbolStore}` (the search backend).

## Backend status
- `find_definition`, `find_references` — **ready** in `QueryService` (symbol- and position-modes;
  grouped refs with the unconfirmed tail). Just wire + shape + bound.
- `search_symbols` — backend is a **pure index read** not yet on the session: `TrigramIndex.
  searchSymbols(query)` → `symbolId`s → `SymbolStore.symbol(id)`. Add a thin `QueryService`/session
  passthrough (no resolve, no deadline-sensitive work) returning ranked `Symbol`s, with `kind?` filter
  + `limit?`.

## Protocol (test-first; full version in the overview)
Write failing tests + transcripts → **STOP for review** → implement → verify.

## Scope — files to create/modify
- `src/main/java/jcma/tools/FindDefinitionTool.java` — input `symbol` **or** `file`+`position`
  (PRD §6); output: declaration `file:line`, signature, context snippet (external → no file, signature
  still populated). Via `QueryService.findDefinition`/`findDefinitionAt`.
- `src/main/java/jcma/tools/FindReferencesTool.java` — input `symbol` **or** `file`+`position`;
  output: refs **grouped by enclosing symbol** with snippets + counts + the **mandatory unconfirmed
  tail** (never present a set as exhaustive). Via `QueryService.findReferences`; shaped + **budgeted**
  (task-3 caps — this is the result most likely to overflow).
- `src/main/java/jcma/tools/SearchSymbolsTool.java` — input `query`, `kind?`, `limit?`; output ranked
  symbols: FQN, kind, `file:line`, signature. Via the new session passthrough.
- `src/main/java/jcma/query/QueryService.java` + `jcma/session/AnalysisSession.java` — add
  `searchSymbols(query, kind?, limit?)` (pure read over trigram + symbol store).
- Register all three in the `ToolRegistry` with their input schemas.

## Tests (red-first)
- `FindReferencesToolTest` / `FindDefinitionToolTest` (scripted `tools/call`): on a commons-lang
  fixture/corpus symbol, the result matches the M1 worksheet oracle (grouping, counts, unconfirmed
  tail for the overload-ambiguity case); position-mode go-to-def resolves the labeled site; an
  over-cap refs result is **bounded + marked** (task-3).
- `SearchSymbolsToolTest`: ranked order (exact → prefix → substring) matches `TrigramIndex`; `kind`
  filter and `limit` honored; a query shorter than a trigram still returns (verify-against-all path).
- Each tool surfaces a clean `isError` result (not a transport error) on bad/empty input.

## Manual check
- Scripted stdio session (or `jcma serve <repo>`): `tools/call search_symbols {"query":"StringUtils"}`,
  `find_definition`, `find_references` on a hot symbol — eyeball context-bearing, bounded output.

## Done when
- tests green · native green · all three tools answer over MCP, context-bearing + token-bounded ·
  `search_symbols` p95 < 50 ms / find_* p95 < 200 ms preserved (no regression vs M1).

## As built (deviations from the plan, decided with the user)
- **Target selection promoted to the query layer, not the tool layer.** The name→declarations
  selector is `QueryService.resolveTargets(symbol, deadline)` over the new pure
  `jcma.query.QualifiedName` (alongside `SymbolRanking`), so the §6 tools **and** the CLI
  `def`/`refs` **and** the REPL resolve a qualified name identically. `Def`/`Refs`/`Repl` were
  switched from `declarations` → `resolveTargets`.
- **Qualified filtering is suffix-anchored, segment-exact — not `String.contains`.** A dotted
  `symbol` matches iff its `.`-split segments are a contiguous *tail* of the moniker's name-path
  (package/type/member, descriptor + `~` stripped). So `Circle.area` ✗ `…/Circles/MyAwesomeCircle`
  (substring false-positive) and ✗ `…/Shape` (wrong enclosing type), two real `Circle`s are honest
  multi-match (narrow with `shapes.Circle.area`), a full FQN is the maximal suffix (unique up to
  overloads — overloads share a name-path; position mode separates them).
- `Shaping.references` was split into `referenceHeader` + `referenceSection` + `unconfirmedTail`
  so the multi-match path (one section per declaration, one shared name-keyed tail) reuses it.
