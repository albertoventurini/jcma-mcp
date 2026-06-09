# M2 · Task 5 — Tools batch 2: `find_supertypes`, `find_subtypes`, `find_implementations` (transitive)

> Type-hierarchy navigation. M1 ships **direct** edges; M2 walks them **transitively**.

## Decision locked (overview)
**Transitive, not direct-only.** M1's `EdgeResolver.supertypes`/`subtypes` return *direct*
`EXTENDS`/`IMPLEMENTS`/`OVERRIDES` neighbours; these tools do a depth-bounded transitive walk.

## Prerequisites (read first, fresh session)
- **Done before this:** tasks 2, 3 (+ task-4 establishes the tool-wiring pattern).
- **Read:** M2 overview ; PRD §6 (`find_implementations`, `find_subtypes`/`find_supertypes`) ;
  M1 task-11a (hierarchy edges) ; `EdgeResolver.{supertypes,subtypes,hierarchyOut}` +
  `LsmStore.{fwd,rev}` (direct primitives this builds on) ; `jcma.index.{EdgeType,MonikerEdge}`.

## Backend status
- **Direct** primitives ready (`supertypes`/`subtypes`). M2 adds a **transitive walk** over the same
  edges (BFS over `fwd` for supertypes, `rev` for subtypes/implementors, filtered to
  `EXTENDS`/`IMPLEMENTS`/`OVERRIDES`), depth-bounded, cycle-safe (visited set), each hop still
  freshness-cascaded by the session. `find_implementations` = the subtype/overrider walk (concrete
  implementors of an interface/abstract type, overriders of a method).

## Protocol (test-first; full version in the overview)
Write failing tests + fixtures → **STOP for review** → implement → verify.

## Scope — files to create/modify
- `src/main/java/jcma/resolve/Hierarchy.java` (or methods on `EdgeResolver`) — transitive walks:
  `supertypesTransitive`, `subtypesTransitive`, `implementations`, returning each reached node with
  its **distance** and the edge kind that reached it (so the tool can show the path/relationship).
  Depth-bounded; visited-set cycle guard; truncation-marked when the bound is hit.
- `src/main/java/jcma/session/AnalysisSession.java` + `jcma/query/QueryService.java` — session/query
  passthroughs (refresh → cascade → serve, like the direct ones).
- `src/main/java/jcma/tools/FindSupertypesTool.java`, `FindSubtypesTool.java`,
  `FindImplementationsTool.java` — input type (or method, for overriders); output context-bearing
  rows (FQN + kind + `file:line` + relationship), budgeted; register in the `ToolRegistry`.

## Tests (red-first)
- Hand-authored fixture: `interface A`, `class B implements A`, `class C extends B`, `class D extends C`.
  `find_subtypes(A)` → {B, C, D} transitively (not just B); `find_supertypes(D)` → {C, B, A};
  `find_implementations(A)` → concrete implementors; a deliberate cycle (illegal in Java but a graph
  hazard) terminates; the depth bound truncates **and** marks.
- Method overrides: `find_implementations(A#m())` → overriders down the chain.
- Integration: a known commons-lang hierarchy resolves transitively.

## Manual check
- `tools/call find_subtypes {"type":"...Base"}` / `find_implementations {"type":"...SomeInterface"}`
  on the corpus — eyeball transitive closure + relationship labels.

## Done when
- tests green · native green · walks are transitive, cycle-safe, depth-bounded + marked · results
  context-bearing + budgeted.

## Decisions to settle / record (PRD §11)
- **Default depth bound** + how truncation is signalled — recommend a generous default (e.g. unbounded
  with a node-count cap rather than a depth cap, since real hierarchies are shallow) calibrated on the
  corpora; record the choice.
