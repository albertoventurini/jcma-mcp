# M1 · Task 11b — Unconfirmed references as persistent, name-keyed dependency edges
> A failed lookup is a dependency, not a footnote — model it so it can be revalidated.

## Prerequisites (read first, fresh session)
- **Read:** [`task-11-invalidation.md`](task-11-invalidation.md) (the model + *why*; esp. the
  "negative case" — an unresolved ref must cascade when its target name later becomes defined) ;
  PRD §5.1 ; M1 overview (protocol).
- **Done before this:** task-10 (`EdgeResolver`, `find_references` confirmed groups + the in-session
  `unconfirmedByName` tail, `FailureClassifier`/`Cause`, the moniker bridge + `~`-signature phantoms
  for resolved-but-external targets).

## The gap this closes
Today an unresolved candidate occurrence is recorded in the **in-session** map `unconfirmedByName`
and surfaced as the `find_references` **unconfirmed tail** — but it is *not persisted* and carries
*no graph dependency*. So a later edit that would make it resolve (the name becomes defined) cannot
cascade to it (task-11c can't reach it). Promote it to a first-class **graph edge**.

## The model (settled — see task-11 parent for the *why*)
An unconfirmed reference is modeled **faithfully to the syntax**, with resolution status on the
**target node**, never on the edge:

- **Edge type = the syntactic kind**, exactly as a confirmed reference would use: a failed `foo()`
  is a `CALLS` edge, a failed field/name use is `REFERENCES`, etc. (`edgeTypeOf(o.kind())`). There is
  **no synthetic `UNRESOLVED` edge type** — that would make "count the calls in a class" a union of
  edge types instead of one. *Model what the code is.*
- **`dst` = a name-keyed placeholder node** `<simple-name>~UNRESOLVED` (e.g. `run~UNRESOLVED`),
  constructed from the occurrence's attempted simple name. It **coalesces every failed reference to
  that name** onto one node. We do **not** resolve the receiver type and do **not** point at it
  (deferred precision optimization): the placeholder is what makes the negative-case cascade a plain
  name-keyed reverse walk, and it works **whether or not the receiver type ever existed**.
- **`src` = the enclosing decl moniker** (same as any reference edge).
- **`cause` rides the occurrence.** The `FailureClassifier.Cause` ordinal is stored in the
  occurrence's otherwise-unused `enclosingSymbolId` slot (reference edges carry the enclosing decl as
  the edge `src`, not in the occurrence, so the slot is free). It is **already serialized** by both
  `overlay.log` and the `Csr` base — *no new edge field, no Csr format change*. Read it back only for
  placeholder-targeted edges.
- **Why no edge-type / no per-edge "unresolved" flag is needed for queries:** in-edges of a
  `…~UNRESOLVED` node are unconfirmed *by construction* (the placeholder exists only because a ref
  failed; `targetMoniker` never mints a `<name>~UNRESOLVED` for a resolved target). And confirmed
  `find_references(X)` walks `rev(X)` over the *real* node `X`, which the placeholder edge never
  points at — so a placeholder edge can never be mistaken for a confirmed reference. Resolution
  status lives on the **node identity**, keeping every edge faithful.

`…~UNRESOLVED` is a reserved namespace: `~` never appears in a real moniker (built from Java
identifiers + `/ # ( ) . ;` + spaces), and the suffix can't collide with the `~signature` externals
(bare simple-name vs. qualified signature). Define the suffix as a single named constant so the
construct/strip logic has one home.

## Scope — files to create/modify
- `src/main/java/jcma/resolve/EdgeResolver.java`:
  - In `resolveFile`, the **failed** branch emits a graph edge instead of (only) appending to
    `unconfirmedByName`: `src` = enclosing decl moniker; `dst` = `o.targetName() + UNRESOLVED_SUFFIX`;
    `type` = `edgeTypeOf(o.kind())`; occurrence carries `(fileId, range, cause-ordinal-in-the-
    enclosing-slot, roleOf(o.kind()))`. Persisted via the same idempotent `applyEdit` (so it survives
    reopen via `overlay.log` replay).
  - `findReferences` builds the unconfirmed tail by **reading the placeholder's incoming edges from
    the store** — `store.rev(target.name() + UNRESOLVED_SUFFIX)` — mapping each to an `UnconfirmedRef`
    `(fileId, file, range, snippet, cause-from-occurrence)`. The tail is now **graph-backed and
    survives a restart**; the in-session `unconfirmedByName` map is retired. Keep the
    `References`/`UnconfirmedRef` output shape identical (file, range, snippet, cause).
  - A small helper to read the `Cause` back from an occurrence (`Cause.values()[enclosingSymbolId]`,
    guarded for the placeholder case) and to build the placeholder moniker.
- **No `EdgeType` change, no `LsmStore` (de)serialization change, no `Csr` format change, no
  `jcma.engine` receiver-type seam.** The placeholder moniker is a normal `dst` string (overlay.log
  serializes it; `compact()`'s existing phantom mint materializes it); the cause rides an existing
  occurrence column. *(This is the payoff of the name-keyed model — see task-11 parent.)*

## Tests (red-first)
Use `src/test/resources/fixtures/resolve/refs` (it has `Mystery.poke`'s `thing.run()` on the
**unknown** type `Unknown`) and **add a known-type miss** sharing the name `run`: a class `Widget`
with no `run()`, and a caller doing `w.run()` on a `Widget`-typed receiver — so a single
`find_references(Service.run())` surfaces *both* misses. (The task-10 `FindReferencesTest`'s tail
count for `run` grows `1 → 2`; update that assertion in the same change — the fixture grew.)

- **Graph-backed tail, same shape:** `find_references(Service.run())`'s tail is built from
  `rev("run~UNRESOLVED")`, contains both misses (Mystery + the known-type Widget miss), each with the
  right file, snippet, and `Cause` — equivalent to what the in-session map produced in task-10.
- **`dst` is the name placeholder for *both* branches:** assert each emitted edge's `dst` is exactly
  `run~UNRESOLVED` — for the unknown-type receiver (Mystery) **and** the known-type receiver (Widget).
  (Replaces the old "receiver type when known / phantom-by-name when not" assertion — there is now one
  branch.)
- **Survives `EdgeResolver` close + reopen (persistence):** after the first resolve + close, open a
  **fresh `LsmStore`** on the same index dir and assert `rev("run~UNRESOLVED")` returns the edges from
  the replayed `overlay.log` — proving the tail persists *independently of re-resolution* (the
  in-session map is gone).
- **No confirmed/unconfirmed double-counting:** the confirmed set is still exactly 3 groups /
  3 refs; no placeholder edge appears as a confirmed reference (`rev(Service#run().)` never returns
  them, since they point at `run~UNRESOLVED`).

## Manual CLI check
- `jcma refs <repo> <symbol>` shows the **same unconfirmed tail in a fresh process** (proves the tail
  is now persisted, not rebuilt in-session).

## Done when
- tests green · native green · unconfirmed tail is **graph-backed + persisted** · edge type is the
  **syntactic kind** (no synthetic `UNRESOLVED` type) · `dst` is the **name-keyed placeholder**
  `<name>~UNRESOLVED` so task-11c can cascade onto it by name · the `find_references` tail contract
  is unchanged.
