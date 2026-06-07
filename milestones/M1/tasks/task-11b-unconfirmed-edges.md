# M1 · Task 11b — Unconfirmed references as persistent dependency edges
> A failed lookup is a dependency, not a footnote — model it so it can be revalidated.

## Prerequisites (read first, fresh session)
- **Read:** [`task-11-invalidation.md`](task-11-invalidation.md) (the model + *why*; esp. the
  "negative case" — an unresolved ref must cascade when its target type later gains the member) ;
  PRD §5.1 ; M1 overview (protocol).
- **Done before this:** task-10 (`EdgeResolver.unconfirmedByName`, `UnconfirmedRef`, the `References`
  tail, `FailureClassifier`/`Cause`, the moniker bridge + phantom monikers).

## The gap this closes
Today an unresolved candidate occurrence is recorded in the **in-session** map `unconfirmedByName`
and surfaced as the `find_references` **unconfirmed tail** — but it is *not persisted* and carries
*no graph dependency*. So a later edit that would make it resolve (a type gains the member, or a
supertype) cannot cascade to it (task-11c can't reach it). Promote it to a first-class edge.

## Scope — files to create/modify
- `src/main/java/jcma/index/EdgeType.java` — **append** an `UNRESOLVED` edge type (append-only,
  ordinal-stable). It carries the use-site `Occurrence`; the attempted **simple name** and the
  `FailureClassifier.Cause` ride along (reuse `Occurrence`/edge fields or a small parallel field —
  keep the segment + `overlay.log` (de)serialization in sync in `LsmStore`).
- `src/main/java/jcma/resolve/EdgeResolver.java` — in `resolveFile`, when an occurrence fails to
  resolve, emit an `UNRESOLVED` edge instead of (only) appending to `unconfirmedByName`:
  - `src` = enclosing decl moniker;
  - `dst` = the **receiver/scope type** the member was attempted on, resolved via the engine **even
    though the member itself wasn't found** (so the dependency points at the right type node); if the
    receiver type is itself unresolvable, fall back to a **phantom keyed by the simple name**
    (`~name`).
  - Persist via `applyEdit` (idempotent full edge set per file), so it survives reopen.
- `find_references`: build the unconfirmed tail by **reading `UNRESOLVED` edges back from the store**
  (scan/`rev`), not from the in-session map → the tail is **graph-backed and survives a restart**.
  Keep the `References`/`UnconfirmedRef` output shape identical (file, range, snippet, cause).
- `src/main/java/jcma/engine/` — likely a small seam addition to resolve the **receiver type of a
  failed call/access** (the type is often resolvable when the member is not).

## Tests (red-first)
- Reuse `src/test/resources/fixtures/resolve/refs` (it already has `Mystery.poke`'s `thing.run()` on
  an unknown type) and add a case where an unresolved member is attempted on a **known** type:
  - assert the unconfirmed tail is **identical** to task-10's (same count, files, snippets, causes);
  - assert the tail **survives `EdgeResolver` close + reopen** (proves persistence — the in-session
    map is gone but the edges remain);
  - assert the `UNRESOLVED` edge's `dst` is the **receiver type node** when the type is known, and a
    **phantom-by-name** (`~run`) when it is not (`Mystery.poke`);
  - assert no confirmed/unconfirmed **double-counting** (an `UNRESOLVED` edge is never reported as a
    confirmed reference).

## Manual CLI check
- `jcma refs <repo> <symbol>` shows the **same unconfirmed tail in a fresh process** (proves the tail
  is now persisted, not rebuilt in-session).

## Done when
- tests green · native green · unconfirmed tail is **graph-backed + persisted** · `dst` is the
  receiver type (or phantom-by-name) so task-11c can cascade onto it · the `find_references` tail
  contract is unchanged.
