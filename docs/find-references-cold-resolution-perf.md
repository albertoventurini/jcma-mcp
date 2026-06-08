# Bug: `find_references` cold resolution takes minutes on large source files

> **Status: FIXED via Option A (2026-06-08).** Surfaced via `EdgeResolverCommonsIT` on the pinned
> commons-lang corpus, which appeared to "hang." It is not a hang: a cold `find_references` over a
> real project spends minutes inside JavaSymbolSolver. This doc is the self-contained record of the
> investigation, the root cause, the upstream status, and the fix options. **Option A is now
> implemented** — see *Implemented* below; the whole `EdgeResolverCommonsIT` (incl. the 64-file
> `isEmpty` case that previously ran for minutes) now completes in ~27 s.

> **Update — Option B measured: no gain ON THIS CORPUS (2026-06-08).** Option B (patch
> `BlockStmtContext`'s `indexOf`) was implemented behind a red→pause→green gate, measured, and
> **reverted**: on the commons-lang corpus it produced **no measurable gain** (cold `isEmpty`
> 10.1 s stock vs 9.1 s patched — within run-to-run noise; `getProperty` was marginally *slower*
> patched). A JFR profile of the residual ~27 s shows **why**: Option A already drove the JavaParser
> #4975 pathology to **0 profiler samples** (`EqualsVisitor`/`NodeList.indexOf`/`typePatternExprs`
> never appear) *on this corpus*, so making each such resolve cheaper buys nothing **here**. The
> residual cost here is **plain parsing** — ~75 % of samples are in `GeneratedJavaParser`, dominated by
> `JavaParserTypeSolver.parseDirectory` re-parsing whole source *directories* to resolve a type.
> **This is workload-dependent, not a verdict on Option B:** commons-lang is `parseDirectory`-bound,
> not #4975-bound. Option B remains the correct lever for a workload that genuinely *is* #4975-bound
> (a hot name resolved many times inside a few very large methods, or type-pattern code), and its
> delivery mechanism was built and proven. **Profile first and let the profile pick the lever** — see
> *Fix options → B* and *What the residual ~27 s actually is → the levers* below.

## Implemented (Option A — two-layer, name-scoped)

The literal "name-scope every occurrence" of Option A as first written has **two gaps** the
investigation missed, both found while wiring the red test:

1. **Hierarchy can't be fully split out.** `HierarchyEdgesTest` drives `find_references(Base)` and
   asserts it emits `EXTENDS`/`IMPLEMENTS`/`OVERRIDES` as a side effect.
2. **Pure name-scoping breaks the cascade.** `CascadeTest.addingSupertypeBindsAPreviouslyUnconfirmedRef`
   relies on a referrer's *type-reference* edge (`Client → Service`) to find referrers when a type
   gains a supertype. Resolving only the queried name never creates that edge → a cross-type edit
   silently fails to cascade (a correctness regression, not just lost tail completeness).

The measurement that resolves both: the cubic cost is **entirely** in value-name resolution
(CALL/NAME/FIELD_ACCESS = 11,312 of 11,543 value use-sites in the `getProperty` candidate files);
**type/annotation references resolve through the type solver, not the `StatementContext` walk — cheap**
(1,169 + 137). So resolution is now **two layers**:

- **Value layer — per `(file, name)`:** resolve only the queried name's value use-sites (the
  cubic-cost class) + bucket its misses onto `name~UNRESOLVED`. This is the ~26–53× cut.
- **Structural layer — per `file`, name-independent:** always resolve type/annotation references +
  hierarchy edges. Cheap, and it *is* the dependency layer the node-diff cascade walks.

Result on the corpus: cold `find_references(getProperty)` drops from **11,543** value `.resolve()`
calls to **~244** (the `resolve.values` metric, gated by `EdgeResolverCommonsIT`); all existing
tests stay green. Engine seam: `AnalysisEngine.resolveOccurrences(unit, simpleName)` (value layer,
filters on the cheap syntactic `Occ.targetName()` *before* `.resolve()`) + `resolveTypeReferences(unit)`
(structural layer). `EdgeResolver` accumulates the layers per file in a `FileSlice` and re-applies
their union (since `applyEdit` replaces a file's whole slice). ~~Option B below remains the orthogonal
lever if a single name in a giant method ever still blows the budget.~~ **(Corrected 2026-06-08:
Option B was implemented, measured to not help, and reverted — see the *Update* callout at the top
and *What the residual ~27 s actually is* below. The next lever is the type solver, not the engine.)**

## TL;DR

The tool's core query — `find_references(symbol)` — can take **minutes** on its first (cold) call
over a real codebase. The cost is **not** in our index or graph walk; it is in JavaSymbolSolver's
per-name resolution, which hits a known, still-open **JavaParser performance bug**
([#4975](https://github.com/javaparser/javaparser/issues/4975)): finding a statement's position in
its block via `NodeList.indexOf`, which uses JavaParser's **structural** `Node.equals` (a full deep
AST comparison) instead of identity. This runs for *every* name resolved inside *any* block, and
goes roughly **cubic in block size** on large methods.

We make it worse in one way that is **entirely ours to fix**: for a query on name `X`, we resolve
**every** occurrence in each candidate file and cache all edges, then filter — so ~96% of the
expensive resolutions are for names we never asked about.

The warm path is fine (measured `find_references` p95 ≈ 2.6 ms, task-12) — the Tier-2 edge cache
turns repeats into pure graph walks. The problem is purely the **cold** first resolution.

## Symptom and how it was diagnosed

`./gradlew clean test` sat on `jcma.resolve.EdgeResolverCommonsIT` for a very long time. The test
indexes the 527-file commons-lang corpus once (`@BeforeAll`), then:
1. `reproducesLabeledFoundRefsForTheTopSymbol` → `find_references(getProperty)` (13 candidate files).
2. `overloadedSymbolSurfacesAnUnconfirmedTail` → resolves every `isEmpty` overload (64 candidate
   files — `isEmpty` is referenced by the biggest classes: `StringUtils`, `ArrayUtils`, …).

Thread dumps of the test worker (`jstack`, 5+ consecutive samples) were **all** pinned in the same
stack — not random sampling, a single hot spot:

```
EqualsVisitor.visit …                                      ← ~all CPU (deep structural AST equals)
  Node.equals  (Node.java:383 → EqualsVisitor.equals)
  ArrayList.indexOf → NodeList.indexOf (NodeList.java:433)
  BlockStmtContext.typePatternExprsExposedToChild(BlockStmtContext.java:80)
  AbstractJavaParserContext.findExposedPatternInParentContext
  StatementContext.solveSymbol (… :228 ← :304 ← :199, the adjacent-statement loop)
  JavaParserFacade.solveArguments / getType …               ← typing a call's arguments
```

Engine: JavaParser **3.28.2** (`javaparser-core`, `javaparser-symbol-solver-core`).

## Root cause, link by link (verified against the 3.28.2 sources)

**1. Every name resolution checks for pattern variables.** Resolving a name used as a value (e.g.
the argument `KEY` in `getProperty(KEY)`) walks up the context tree. `StatementContext.solveSymbol`
calls `findExposedPatternInParentContext(parent, name)` — "did a preceding statement introduce a
type-pattern variable named `name`?" (the JLS 6.3.2 flow-scoping rule, see *Type patterns* below).

**2. To answer, it needs the statement's position.** `findExposedPatternInParentContext` →
`BlockStmtContext.typePatternExprsExposedToChild` (line 80) opens with:

```java
int position = wrappedNode.getStatements().indexOf(child);   // ← the hot line
```

so it can scan the statements *before* `child` for pattern bindings.

**3. `indexOf` uses `equals`, not identity.** `NodeList.indexOf` (NodeList.java:433) delegates to its
backing `ArrayList`, whose `indexOf(o)` walks the list evaluating `o.equals(element[i])`.

**4. `Node.equals` is a full structural deep-compare.** `Node.java:383`:

```java
public boolean equals(final Object obj) {
    if (!(obj instanceof Node)) return false;
    return EqualsVisitor.equals(this, (Node) obj);   // NOT ==
}
```

`EqualsVisitor` recurses through **both subtrees in lockstep** — every child node, every `NodeList`,
and even attached **comments** (`commonNodeEquality`). It returns `false` at the first difference,
but `true` only after fully traversing two identical trees. (`Node.hashCode` is likewise structural,
so you cannot cheaply key nodes by identity in a `HashMap` either.)

### The cost model

For the statement at position `p`, `indexOf` calls `equals` against each of the `p` preceding
statements. Two facts make this explode:

- When `indexOf` reaches the statement *itself*, `equals` short-circuits instantly (`n == n2`). So the
  cost is **entirely** the comparisons against *preceding, non-identical* statements.
- Each such `equals` is cheap only when the statements differ near the top (different kind → bail).
  It is **expensive when statements are structurally similar** — same shape, differing in a deep leaf
  — because the visitor descends all the way down. This is exactly what validation/builder-heavy code
  (`Validate.notNull(a)`, `Validate.notNull(b)`, …) looks like — commons-lang's bread and butter.

Then the amplifier in `StatementContext.solveSymbol`: resolving one name iterates over the
**preceding statements** of the block (the loop near line 304, which the JavaParser authors annotate
with a warning about a *"factorial increase in calls to solveSymbol"* and tame with an
`iterateAdjacentStmts=false` flag). The flag stops the recursion, but each visited statement still
calls its own `indexOf`. Net, per block of `B` statements with average subtree size `S`:

```
one name        ≈ O(B²) pairwise structural-equals, each up to O(S)
one block (~B names) → trends toward O(B³ · S)
```

`B` and `S` are both large in commons-lang's big classes → the multi-minute wall.

### The bitter irony

The entire computation exists to find type-pattern variables. commons-lang declares
`maven.compiler.source = 1.8` and contains **zero** type patterns (244 plain `instanceof`, none with
a binding). So `typePatternExprsExposedToChild` **always returns empty** — and the `indexOf` runs at
the *top* of the method, before any pattern is examined, so we pay the full cost to learn nothing.
This is dead overhead, not wrong answers; it scales with how big and repetitive your methods are.

## Background: type patterns and "why Java 16"

A **type pattern** is the `String s` in `if (obj instanceof String s)` — it tests *and* binds in one
step, replacing the old `if (obj instanceof String) { String s = (String) obj; … }`. It became a
permanent language feature in **Java 16** ([JEP 394](https://openjdk.org/jeps/394); preview in 14/15).

It forces the sibling-scan because of **flow scoping** — a pattern variable's scope follows wherever
the test is *definitely true*, which can extend past the `if`:

```java
if (!(obj instanceof String s)) return;
System.out.println(s.length());   // s IS in scope here
```

So resolving `s` can't just look at the enclosing braces; it must scan preceding statements. The
feature added a genuine need; the bug is *how* the position is found.

## Upstream status — known, acknowledged, unfixed

- **[Issue #4975](https://github.com/javaparser/javaparser/issues/4975)** — "Fix
  `BlockStmtContext.typePatternExprsExposedToChild` performance issue." Opened **2026-02-24** by a
  JavaParser maintainer (`johannescoetzee`), labeled *Improvement*, **open**. Their words: *"finding
  the child node index with `wrappedNode.getStatements().indexOf(child)` is very slow."* Two proposed
  fixes: (1) an `order`/index property on each statement for O(1) lookup; (2) a smarter
  `NodeList.indexOf`.
- **[PR #4976](https://github.com/javaparser/javaparser/pull/4976)** — the fix in progress (closes
  #4975). Adds a `nodeListIndex` field to `Node`, a `fastIndexOf()` to `NodeList` (reads the cached
  index, verifies it), and switches `BlockStmtContext` to it → **O(1)**. **Still open, not merged**
  (~83% patch coverage); the author flags caveats — the cached index has no meaning outside
  `NodeList` and "only works reliably for standard list operations, not sublists or nodes shared
  across multiple lists."
- The current `master` `BlockStmtContext` **still** has `getStatements().indexOf(child)` verbatim. **No
  released version fixes this.**

So: not weird that it exists unfixed — it's recent (the cliff appeared with pattern-scoping
resolution), only bites callers resolving names in **very large methods**, and the clean fix is
structural.

## What we do wrong (and what we don't)

**The pathology is upstream, not misuse.** We call `expr.resolve()` — the documented API. There is no
"correct" call that avoids the `indexOf`.

**But we amplify it, fixably.** `EdgeResolver.ensureResolved` resolves **every occurrence in each
candidate file** and caches all edges ("resolve once, answer any future query"). Measured for
`find_references(getProperty)`: ~**6,483** call-sites resolved across the 13 files to surface ~**249**
real `getProperty` sites — **~96% of the entry-points into the slow machinery are for names we don't
care about.** Each wasted `resolve()` pays the full block walk.

Secondary: JavaSymbolSolver caches *type-solver lookups* but **not context walks**, so resolving N
names in one method re-pays the backward scan N times. Inherent to the API; the defenses are
resolving fewer things (below) and our own edge cache (already done — the warm 2.6 ms).

## Things that do NOT work (checked, with reasons)

- **Configuration levers — none.** Grepped `StatementContext` / `AbstractJavaParserContext`: there is
  **no language-level (or any) guard** around the pattern walk; it runs unconditionally during
  `solveSymbol`. Setting `LanguageLevel` below `JAVA_16` does **not** skip it (level governs
  parsing/validation, not the resolution algorithm). `setAttributeComments(false)` makes each `equals`
  marginally cheaper but doesn't change the O(B²) shape. `clearInstances()` is about memory.
- **Subclassing the parser to install our own node classes — impossible.** `public final class
  JavaParser` (can't subclass); it news up `private GeneratedJavaParser` directly; `final class
  GeneratedJavaParser` (can't subclass/replace); node construction is hardcoded inline `new
  XxxNode(...)` in generated JavaCC code with **no node-factory SPI** anywhere in the jar. The only
  way to custom node classes is forking + regenerating the parser from the `.jj` grammar — far more
  than warranted.
- **Overriding `Node.equals` — wrong target anyway.** Even if we could install custom nodes,
  `equals`/`hashCode` are structural *by design* and other subsystems rely on it (lexical
  preservation, node observers/dedup, parts of the solver). Flipping it to identity globally is the
  *risky* change. The pathology only needs the position lookup to stop using `equals`.

## Fix options

### A. Name-scope the resolution (no fork — entirely ours; recommended first)

Every occurrence already carries a cheap, purely-syntactic simple name *before* any resolution
(`Occurrences.scan`: `n.getNameAsString()`). Resolve only occurrences whose `targetName` equals the
queried name:

```java
for (Occ o : Occurrences.scan(unit.cu()))
    if (o.targetName().equals(simpleName)) resolve(o);   // ~249 instead of ~6483
```

≈ **26× fewer** entries into the slow path. Consequences (the "resolve whole file once" assumptions
that must change):

1. **Cache unit: file → (file, name).** `warmFiles` (`Set<Integer>`) becomes a set of `(fileId,
   name)` pairs; a candidate file is skipped only when *that pair* is resolved — else a later query
   for a different name in the same file would silently miss it.
2. **`applyEdit` replaces a file's whole edge slice — so accumulate.** Writing only name `X`'s edges
   then later name `Y`'s would wipe `X`'s. Keep the union per warm file (accumulate in the resolver
   and re-apply, or add an additive per-file merge to the store).
3. **Unconfirmed tail becomes per-name — which is exactly what the query needs.** We resolve `X`'s
   occurrences across *all* `X`-candidate files, so the tail is complete *for X*. We drop only the
   "complete for names you haven't asked about" property — the very thing causing the waste.
4. **Split hierarchy resolution out.** `resolveFile` also computes `EXTENDS`/`IMPLEMENTS`/`OVERRIDES`
   (name-independent, for `supertypes`/`subtypes`). Make it its own per-file step so `find_references`
   doesn't drag in hierarchy and vice-versa.

**Ceiling:** this cuts the **number** of slow resolves, not the **cost of each** — a lone target call
inside a 600-line method still pays one block walk. For typical names this is seconds cold and
instant warm; a common name called many times *inside* a few giant methods may still need option B.

A finer prefilter (gate on **arity** before resolving) is possible but risks turning a found-ref into
a silent miss (overloads/varargs) for a few percent gain — not worth it unless measurement demands.

### B. Patch the JavaParser pathology — ⏸ MEASURED: NO GAIN ON THIS CORPUS, REVERTED (2026-06-08)

> **This option was implemented, measured, and reverted — because commons-lang is `parseDirectory`-bound,
> not #4975-bound, not because the patch is wrong.** After Option A, JavaParser #4975 is **0 profiler
> samples** on the commons-lang corpus, so making each `indexOf` cheaper changes nothing *here*. Clean
> A/B: cold `isEmpty` 10.1 s stock vs 9.1 s patched (within noise), `getProperty` marginally *slower*
> patched; the `StatementContext:258` site was also a non-hotspot (~396 hits, no gain). **Option B
> stays a recommended lever for a workload that genuinely IS #4975-bound** — a hot name resolved many
> times inside a few very large methods, or type-pattern code, where the profile shows
> `EqualsVisitor` / `NodeList.indexOf` / `typePatternExprs`. The delivery mechanism — "patch +
> repackage one class" (a Gradle task rebuilding `javaparser-symbol-solver-core` with a single
> `BlockStmtContext.class` swapped; no classpath-shadow / split-package fragility, native-image-safe) —
> was **built and proven to work**; only the *need* was absent here. PR #4976 is the orthogonal upstream
> alternative. **Profile first**: if `parseDirectory` / `GeneratedJavaParser` dominate (as here), use
> the index-backed solver (*What the residual ~27 s actually is* below) instead; if the #4975 frames
> dominate, this is the lever. The concrete proposal follows.

The original (now-superseded) proposal: mirror PR #4976's *target* but with a simpler, stateless
mechanism: patch **only**
`BlockStmtContext.typePatternExprsExposedToChild` to find the position by an **identity** scan,
leaving `NodeList`/`Node` untouched:

```java
// identity scan: a node is in its parent list at most once by identity, so this returns
// the same position the structural indexOf would, minus the deep compares.
int position = -1;
List<Statement> stmts = wrappedNode.getStatements();
for (int i = 0; i < stmts.size(); i++)
    if (stmts.get(i) == child) { position = i; break; }
```

| | This identity patch | PR #4976 (`fastIndexOf` + cached field) |
|---|---|---|
| Per-call | O(position), pointer compares | O(1) |
| New state | none | `nodeListIndex` on every `Node` |
| Mutation bookkeeping | none | every add/remove/set/sublist |
| Edge cases | none | sublists, shared nodes |
| Surface | one method | field + method + call site |

Delivered as a shaded/patched `javaparser-symbol-solver-core` (one class). Smallest blast radius,
no new state to keep consistent. **Track #4976** so the fork is temporary: when a JavaParser release
lands the fix, drop the patch and upgrade — no permanent fork.

### Recommended sequence

1. **Name-scope (A)** — no fork, no correctness risk, ~26× fewer slow resolutions; may get us under
   budget on its own (warm cache already handles repeats). Follow the red→pause→green gate. **✅ Done
   (commit a165707).**
2. **Measure** on the commons-lang corpus (cold `find_references` on `getProperty`, `isEmpty`).
   **✅ Done — A alone drives #4975 to 0 profiler samples.**
3. **Only if a real name still blows the budget on #4975, add the one-method `BlockStmtContext`
   identity patch (B).** Tried on commons-lang (2026-06-08) and reverted — **not** because B is wrong,
   but because no name blows the #4975 budget on *this* corpus: the residual ~27 s is parsing, not the
   `indexOf` pathology (see the next section). B stays the lever for a workload that profiles as
   #4975-bound; on a `parseDirectory`-bound workload (like this one) use the index-backed solver
   instead. **Profile decides.**

## What the residual ~27 s actually is (2026-06-08 JFR profile)

After Option A, the cold `EdgeResolverCommonsIT` suite is ~27 s. Profiling it (JFR, `settings=profile`,
cold `refs isEmpty` over the 527-file commons-lang corpus) localises the cost decisively — and it is
**not** JavaParser #4975:

- **0 samples** in `EqualsVisitor` / `NodeList.indexOf` / `typePatternExprs` — the #4975 pathway is
  gone *on this corpus* (this is exactly why Option B above buys nothing **here** — it's the right
  lever only when these frames *do* dominate).
- **~75 % of samples** are in `GeneratedJavaParser` (the lexer/parser): `jjMoveNfa_0`, `jj_3R_*`,
  `getNextToken`, `jj_scan_token`, … — i.e. **parsing Java source**.
- **751 / 981 samples** sit under **`JavaParserTypeSolver.parseDirectory`**: JavaSymbolSolver's
  source-root type solver re-parses whole package **directories** to resolve a single type. For
  commons-lang's flat `org.apache.commons.lang3` package (~50 large files, `StringUtils` ≈ 9k lines),
  resolving *any* type there re-parses the entire package — redoing work jcma's own index already holds.
- The solver's caches (`parsedFiles` / `parsedDirectories`, built in `JavaParserEngine.buildParser()`)
  use Guava `.softValues()` with **no size cap** (`CACHE_SIZE_UNSET`), so entries are GC-evictable and
  directories get **re-parsed** under memory pressure.
- jcma's own `Occurrences.scan` parses candidate files **separately** from the solver (~45 % sample
  overlap) → likely **double-parsing** the same files.

The split is ~equal between the two parse paths (type-solver `parseDirectory` ≈ 42 %, occurrence
scan ≈ 45 %, overlapping in the parser).

### The levers for a `parseDirectory`-bound workload (effort / payoff order)

These address the parse-bound profile above. They are the alternative to Option B, *chosen by the
profile* — not a universal replacement for it (a #4975-bound profile still wants Option B).

1. **Bounded strong-ref CU cache for the source solver** (cheap): construct `JavaParserTypeSolver`
   via its 5-arg constructor with an explicit bounded *strong* cache sized to the corpus, so parsed
   directories aren't GC-evicted and re-parsed. **Spiked + reverted (2026-06-08):** a re-parse gate
   came back green at the 2 GB test heap (0 within-instance re-parses), so there's no thrashing to fix
   *at that heap* — this is a memory-pressure safety net (a constrained native binary could still
   evict), not the cold-latency fix.
2. **Share one parsed-CU cache** between `Occurrences.scan` and the type solver — stop parsing each
   candidate file twice.
3. **Index-backed `TypeSolver`** (architectural, biggest win): jcma already maps type→file, so a
   custom solver can parse the *single* file for a requested type instead of `parseDirectory`'s
   whole-package parse. More design + correctness work (nested types, `package-info`, etc.), but it
   removes the dominant cost structurally. (~450 of the 527 corpus files are parsed to resolve just
   two symbols today — this is what that fixes.)

All three live at the **type-solver / parse** layer (`JavaParserEngine.buildParser`), distinct from
the resolution-walk layer that #4975 / Option B addresses — which is why *the profile picks the lever*.

## Implementation handoff (Option A)

Concrete starting points a fresh context will otherwise re-derive. Follow the **red→pause→green**
gate: write the failing test, **stop for human confirmation**, then implement.

### 1. The seam — the filter MUST be inside the engine, before `.resolve()`

This is the single most important point. `AnalysisEngine` (one impl, `JavaParserEngine`) exposes:

```
ParsedUnit parse(...)              Optional<ResolvedRef> resolveMethodCall(...)
List<ResolvedOccurrence> resolveOccurrences(ParsedUnit)      // ← resolves ALL occurrences
List<ResolvedHierarchy> resolveHierarchy(ParsedUnit)         void refresh()
```

`resolveOccurrences` already calls the expensive `.resolve()` on every occurrence (inside
`JavaParserEngine.attempt`). **Filtering the returned list in `EdgeResolver` is too late** — the cost
is already paid. The name filter has to happen *before* `.resolve()`. So:

- **Recommended:** add a name-scoped overload to the interface — `List<ResolvedOccurrence>
  resolveOccurrences(ParsedUnit, String simpleName)` — and filter inside it on the cheap, syntactic
  `Occ.targetName()` (from `Occurrences.scan`) before calling `attempt`. One impl to change.
- Keep the un-scoped overload (or a `null`/empty-name = "all") for the hierarchy/cascade paths that
  still want a whole-file resolve.

### 2. Warm-tracking ripple — `warmFiles` is not local to `ensureResolved`

Moving the cache unit from *file* to *(file, name)* touches all six `warmFiles` sites in
`EdgeResolver.java`. Suggested shape: replace `Set<Integer> warmFiles` with
`Map<Integer, Set<String>> resolvedNames` (names resolved per file) **plus** a separate
`Set<Integer> hierarchyWarm` (Option A item 4 — hierarchy is name-independent). Then:

- `ensureResolved` (was line ~197): skip a file only when `(fid, name)` is already in `resolvedNames`.
- `ensureFileResolved` (~287): this is the **hierarchy** path (via `ensureHierarchyResolved`) — gate on
  `hierarchyWarm`, resolve hierarchy (+ optionally all occurrences) once per file.
- `reResolve` (~351, task-11c cascade eager re-resolve): re-resolve **every** name previously warm for
  the changed file (or invalidate `(file, *)` and let lazy re-resolution redo it).
- `dropTier2` (~376): clear **all** `(file, *)` entries for the file, not just one.
- `invalidateReferrers` (~407): the referrer check is file-granular — treat "any `(file, *)` warm" as
  warm.

### 3. The accumulation crux — `applyEdit` replaces a file's whole slice

`store.applyEdit(FileIndex)` *replaces* the file's overlay (that is how `dropTier2` works). Writing
only name `X`'s edges, then later name `Y`'s, **wipes `X`'s edges**. Resolve it by accumulating: hold
`Map<Integer, FileIndex> resolvedSlice` in the resolver (Tier-1 symbols + the *union* of all resolved
Tier-2 edges for the file), add each name's edges to the slice, dedupe, and re-`applyEdit` the union.
Align with `dropTier2`, which already rebuilds the Tier-1-only slice. This is the main design work in
Option A — the rest is bookkeeping.

### 4. Tests that pin current behavior (+ the perf gate)

- `FindReferencesTest.secondQueryIsPureCacheLookupNoReResolve` — asserts via the `resolve.files`
  metric that a **repeat** query resolves nothing. Must still hold for a **same-name** repeat; decide
  whether the metric counts per-file or per-(file,name).
- `FindReferencesTest.surfacesUnconfirmedTailForAnUnresolvableCandidate`, `UnconfirmedEdgeTest.*` — the
  unconfirmed tail must still surface for the **queried** name (the tail is per-name now).
- `CascadeTest` — exercises task-11c invalidation over warm state; re-verify after the warm-granularity
  change (items 2 above).
- `EdgeResolverCommonsIT` — the perf oracle. **No existing test asserts a cross-name / query-order tail
  property**, so dropping "complete for names you didn't query" should not break a test (confirm). The
  natural **failing test to write first**: a metric/`@Timeout` assertion that a cold
  `find_references(getProperty)` resolves ≪ all occurrences (e.g. on the order of the target-named
  count, not ~6,483) and/or finishes within a wall-clock budget.

## Reproduction (manual, via the REPL)

The corpus is git-ignored; this assumes it's present at
`milestones/m0-spike/corpus/commons-lang` with an in-place `.jcma` index (`jcma index <repo>`).

```bash
./gradlew -q installDist
# cold find_references — the time-box makes the slowness visible immediately:
printf 'refs getProperty --deadline 1500\nquit\n' \
  | build/install/jcma/bin/jcma repl milestones/m0-spike/corpus/commons-lang
# → "jcma: query exceeded its deadline of 1500 ms"  (cold resolution of even 13 files blows 1.5 s)
```

Without `--deadline`, `refs isEmpty` (64 candidate files, incl. the largest classes) runs for
minutes. To watch where the time goes, `jstack` the test/REPL JVM mid-run — every sample is in
`EqualsVisitor` under `BlockStmtContext.typePatternExprsExposedToChild`.

Note: the task-12 `--deadline` time-box is a **safety net** (the caller returns promptly), not a fix —
a query that returns "deadline exceeded" instead of an answer is still unusable. The fix must make the
cold path actually fast.

## References

- Issue: <https://github.com/javaparser/javaparser/issues/4975>
- PR (in progress): <https://github.com/javaparser/javaparser/pull/4976>
- `master` BlockStmtContext (still has the slow `indexOf`):
  <https://github.com/javaparser/javaparser/blob/master/javaparser-symbol-solver-core/src/main/java/com/github/javaparser/symbolsolver/javaparsermodel/contexts/BlockStmtContext.java>
- JEP 394 — Pattern Matching for instanceof (Java 16): <https://openjdk.org/jeps/394>
- Verified locally against `javaparser-core` / `javaparser-symbol-solver-core` **3.28.2** source jars:
  `Node.java:383`, `NodeList.java:433`, `EqualsVisitor.java:46`, `BlockStmtContext.java:80`,
  `StatementContext.java:~228/304`, `AbstractJavaParserContext.findExposedPatternInParentContext`.
- In-repo: `EdgeResolver.ensureResolved`/`resolveFile`
  (`src/main/java/jcma/resolve/EdgeResolver.java`), `Occurrences.scan`
  (`src/main/java/jcma/engine/Occurrences.java`), `EdgeResolverCommonsIT`
  (`src/test/java/jcma/resolve/EdgeResolverCommonsIT.java`).
