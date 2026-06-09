# M2 · Task 7 — `call_hierarchy` (CALLS-edge traversal)

> Callers / callees of a method, grouped + snippet-bearing. The one §6 tool needing new graph work.

## Prerequisites (read first, fresh session)
- **Done before this:** tasks 2, 3 (+ task-4 wiring pattern).
- **Read:** M2 overview ; PRD §6 (`call_hierarchy`, `direction`) ; M1 task-10 (a reference is a
  `CALLS`/`REFERENCES` **edge**, `find_references = rev(target)` grouped by `edge.src`) ;
  `LsmStore.{fwd,rev}` + `jcma.index.{EdgeType,MonikerEdge,Occurrence}`.

## Backend status — **new** (a focused graph traversal, but on edges M1 already records)
- A resolved call is a `CALLS` edge `caller-decl —CALLS→ callee-decl` with the call-site as its
  `Occurrence`. So:
  - **callers** (`direction = callers`) = `rev(method)` filtered to `CALLS`, grouped by `edge.src`
    (the enclosing caller) — structurally identical to `find_references` minus non-`CALLS` edges.
  - **callees** (`direction = callees`) = `fwd(method)` filtered to `CALLS`, the methods this one
    invokes, grouped by callee.
- Resolution is lazy (the same `EdgeResolver.findReferences` path warms `CALLS` edges); callers reuse
  that warm path, callees need the method's own file resolved.

## Protocol (test-first; full version in the overview)
Write failing tests + fixtures → **STOP for review** → implement → verify.

## Scope — files to create/modify
- `src/main/java/jcma/resolve/CallHierarchy.java` (or methods on `EdgeResolver`) — `callers(method)`
  and `callees(method)` over the `CALLS` edges, returning groups with enclosing/target signature +
  call-site snippet + count (reuse `ReferenceGroup`-style shaping). **One level** by default (the agent
  expands by calling again on a returned node — keeps results bounded); recursion is a deferred option.
- `src/main/java/jcma/session/AnalysisSession.java` + `jcma/query/QueryService.java` —
  `callHierarchy(symbol, direction)` passthrough (refresh → cascade → serve).
- `src/main/java/jcma/tools/CallHierarchyTool.java` — input `symbol`, `direction`
  (`callers`|`callees`); output grouped callers/callees with snippets, budgeted; register with schema.

## Tests (red-first)
- Hand-authored fixture: `a()` calls `b()` and `c()`; `d()` and `e()` call `b()`.
  `call_hierarchy(b, callers)` → {a, d, e} grouped with call-site snippets + counts;
  `call_hierarchy(a, callees)` → {b, c}; a method with no callers/callees → empty (clean, not error);
  an unconfirmed/ambiguous call surfaces in the unconfirmed tail (no silent miss, per M1 contract).
- Integration: a known commons-lang method's callers match the find-references oracle (filtered to
  calls).

## Manual check
- `tools/call call_hierarchy {"symbol":"…","direction":"callers"}` and `"callees"` on the corpus —
  eyeball grouped, snippet-bearing output.

## Done when
- tests green · native green · both directions answer, grouped + snippet-bearing + budgeted ·
  unconfirmed tail preserved (no silent-wrong) · one-level default bounded.

## Decisions to settle / record (PRD §11)
- **One-level vs. recursive depth** — recommend one level + agent-driven expansion; record. If
  recursion is wanted, reuse the task-5 depth-bound + node-cap + truncation-marker machinery.
