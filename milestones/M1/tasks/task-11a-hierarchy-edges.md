# M1 · Task 11a — Type-hierarchy edges (EXTENDS / IMPLEMENTS / OVERRIDES)
> The graph must know inheritance, or cross-type invalidation can't be exact.

## Prerequisites (read first, fresh session)
- **Read:** [`task-11-invalidation.md`](task-11-invalidation.md) (the model + *why* this exists) ;
  PRD §5.1 (graph store) + §6 (`find_subtypes`/`find_supertypes`/`find_implementations` — these
  edges serve those tools too) ; M1 overview (protocol + targets).
- **Done before this:** task-10 (`EdgeResolver`: occurrence resolution, the **moniker bridge**
  `targetMoniker`/`monikerAt`, phantom monikers for external decls, edge write-back via `applyEdit`).
- **Port from M0 (reference, don't extend):** `m0-spike/.../SpikeA.java` — `attempt(cat,node)`
  guarded `.resolve()` and `describe()`/`locate()` decl→FQN+sig; the same JavaSymbolSolver wiring
  resolves ancestors/overrides.

## Goal
Resolve and persist three structural edge types so the graph captures inheritance:
- `subtype  --EXTENDS-->    superclass`
- `subtype  --IMPLEMENTS--> interface`
- `method   --OVERRIDES-->  overridden-method`

These make a type's **effective surface** (own ∪ inherited) reachable, which task-11c's cascade
walks; they are also the backbone of PRD §6's hierarchy tools.

## Scope — files to create/modify
- `src/main/java/jcma/index/EdgeType.java` — **append** `EXTENDS`, `IMPLEMENTS`, `OVERRIDES`
  (append-only: `byOrdinal` serialization is ordinal-stable; new values must go at the end).
- `src/main/java/jcma/engine/` (`AnalysisEngine` + `JavaParserEngine`) — extend the §4 seam (the way
  task-10 added `resolveOccurrences`) with hierarchy resolution: for each **type declaration**,
  resolve its extends/implements targets; for each **method declaration**, resolve the method(s) it
  overrides. Use JavaSymbolSolver (`ResolvedReferenceTypeDeclaration` ancestors; overridden-method
  lookup) and surface neutral results (decl `file:line` + signature/fqn), keeping JavaParser behind
  the seam. External (jar/JDK) supertypes resolve to a signature only (→ phantom node).
- `src/main/java/jcma/resolve/EdgeResolver.java` — in `resolveFile`, additionally emit the hierarchy
  edges: `src` = the subtype/overriding member moniker (via the existing `monikerAt`/enclosing
  logic), `dst` = the supertype/overridden member moniker via the moniker bridge, or a phantom
  (`~signature`) for external targets. Persist alongside the reference edges in the same `applyEdit`
  (idempotent full edge set per file).

## Tests (red-first)
- **Unit** (new fixture under `src/test/resources/fixtures/resolve/hierarchy/`): a class `extends` a
  base, `implements` an interface, and `@Override`s a method →
  - assert `EXTENDS`/`IMPLEMENTS`/`OVERRIDES` edges exist with the correct `src`/`dst` monikers;
  - assert an **external** supertype (e.g. `extends` a JDK/jar type) yields a **phantom** `dst`;
  - assert the **reverse walk** `store.rev(Base)` returns the subtype (the `find_subtypes` primitive).
- **Integration:** the hierarchy edges **survive reopen** and **compaction** (re-open the resolver /
  force `compact()`, re-query, identical edge set; phantoms preserved).
- Assert **no regression** in task-10 `find_references`/`find_definition` over the `resolve/refs`
  fixture.

## Manual CLI check
- Add a tiny debug subcommand `jcma supertypes <repo> <symbol>` (or `subtypes`) that prints the
  hierarchy edges for a symbol; eyeball it against a real type. (Reuses the §6 shape; keep it minimal.)

## Done when
- tests green · native green · `EXTENDS`/`IMPLEMENTS`/`OVERRIDES` present, correct, reverse-walkable,
  and persisted (survive reopen + compaction) · external supertypes → phantom · task-10 refs/def
  unchanged.
