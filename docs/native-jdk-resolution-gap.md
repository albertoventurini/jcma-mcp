# Bug: native-image fails some symbol resolutions the JVM dev path resolves

> **Status: FIXED (2026-06-08).** Pre-existing (not introduced by M1 task-11c — surfaced during its
> native smoke; out of 11c's scope). This doc is the self-contained record of the investigation.
>
> **Root cause was _not_ the JDK solver** — the original "JDK is resolved with lower fidelity under
> native" framing (kept below for the record) was disproven. Real cause: the source-root
> `JavaParserTypeSolver`'s own parser ran language-level validators that hit JavaParser's reflective
> meta-model, unregistered under native-image (`NoSuchFieldError`). **Fixed** by giving that solver a
> `RAW` (validator-free) `ParserConfiguration`. See **"Update (2026-06-08)"** below for the captured
> trace, mechanism, and the applied fix; the rest of the doc is the original investigation.
>
> **Update (2026-06-15): the *resolving* parser no longer uses RAW.** RAW also dropped `yield`/record/
> pattern parsing, sinking whole-file resolution (`docs/whole-file-resolution-degradation.md`). The
> engine's resolving parser now parses at the project's discovered Java level with the language-level
> **validator post-processor stripped** (constructor processor index 3; the var→`VarType`
> post-processor kept) — same native-safety as RAW (no reflective meta-model access) but with the
> contextual features parsed. The **source-root `JavaParserTypeSolver`** parser described below **stays
> RAW**: it extracts only declarations (unaffected by `yield` in a body), so RAW's native-safety is free
> there. The `NoSuchFieldError` mechanism documented here is exactly why the resolving parser strips the
> validator rather than raising the level naively.

## TL;DR

Symbol resolution can differ between the **JVM dev path** and the **native-image binary**, because
the two use *different* JDK type-solvers:

- **JVM dev path** → `ReflectionTypeSolver` (reads the host JVM's live, loaded classes).
- **native-image** → `JarTypeSolver` over a **byte-parsed, de-moduled jar of the host JDK** (task-02b),
  because general reflection isn't available under native-image.

These are two different JavaParser `TypeSolver` implementations with different fidelity on corner
cases. The observable effect: a method call that resolves on the JVM can come back **unresolved**
under native (and then safe-degrades to the unconfirmed tail). The cached JDK jar is therefore **not**
a shared source of truth across both runtimes — it is the *native-only substitute for reflection*.

## Update (2026-06-08): the gap is native-runtime-specific, NOT the bare solver choice

The "cheap & faithful" repro below (variant a) was built — and it **does not reproduce the gap**.
Building `JavaParserEngine`'s exact solver stack on the JVM but serving the JDK via
`JarTypeSolver` over a *fresh, in-process `JdkIndexer` jar* (the same indexer `HostJdkIndex` caches
for the native binary) resolves `new Service().run()` **identically to reflection** —
`app.Service.run()`, same answer, same stack:

```
[gap-repro] reflection=Optional[app.Service.run()]  hostJdkJar=Optional[app.Service.run()]
```

So the headline framing — "`JarTypeSolver(jdk-jar)` vs `ReflectionTypeSolver`" — is **not** the
root cause. On the JVM the jar has full reflection parity for this case. The differentiator is the
**native-image runtime itself** (how `JarTypeSolver` / its javassist-backed class reading behaves
under native), *not* the bare solver choice or the jar's content. That means:

- Variant (a) cannot pin this bug; it is reframed as a **parity-regression guard**
  (`NativeJdkResolutionGapTest`) — green today, red only if the gap ever reaches the JVM jar path.
- Variant (b), running under the **native binary**, is the only faithful repro. It is realized as
  the **`nativeJdkGapSmoke`** Gradle task (a CLI smoke on the real `jcma` binary, mirroring
  `jdkResolveSmoke`), not the whole-suite `nativeTest` (several tests — e.g. anything driving
  `JdkIndexer`'s `jrt:/` read — cannot run inside a native image).

### Actual root cause (found by running variant b — 2026-06-08)

`nativeJdkGapSmoke` reproduces the miss, and `JCMA_DEBUG=1` on the native binary dumps the swallowed
`Throwable`. **It is not the JDK solver at all.** The (single) failing stack is:

```
java.lang.NoSuchFieldError: variables
  at com.github.javaparser.metamodel.PropertyMetaModel.getValue(PropertyMetaModel.java:263)
  at …validator.language_level_validations.chunks.CommonValidators.lambda$new$7(CommonValidators.java:78)
  at …validator.TreeVisitorValidator.accept / Validators.accept
  at com.github.javaparser.ParserConfiguration$2.postProcess(ParserConfiguration.java:368)
  at com.github.javaparser.JavaParser.parse(JavaParser.java:128)
  at …typesolvers.JavaParserTypeSolver.parse(JavaParserTypeSolver.java:220)   ← re-parsing Service.java
  at …typesolvers.JavaParserTypeSolver.tryToSolveType → CombinedTypeSolver.tryToSolveType
  at …contexts.MethodCallExprContext.solveMethod → JavaParserFacade.solve
  at com.github.javaparser.ast.expr.MethodCallExpr.resolve
  at jcma.engine.JavaParserEngine.resolveMethodCall(JavaParserEngine.java:174)
```

Resolving `new Service().run()` needs the type of `Service`, so the **source-root**
`JavaParserTypeSolver` re-parses `Service.java`. *Its* internal parser runs the post-parse
**language-level validators**, which read node properties through JavaParser's **reflective
meta-model** (`PropertyMetaModel.getValue` → the `variables` property of `VariableDeclarationExpr`,
from `Service`'s `int x = 1;`). Under native-image that meta-model field is **not registered for
reflection** → `NoSuchFieldError` → the engine's `Throwable` guard swallows it → `unresolved`.

This is exactly the hazard `JavaParserEngine.buildParser()` already calls out and avoids by setting
`LanguageLevel.RAW` (*"the validators read node properties through JavaParser's reflective meta-model,
a native-image NoSuchFieldError hazard"*) — **but `JavaParserTypeSolver` constructs its own parser**,
which the engine never configures, so the validators run there at full language level. So the bug
lives one layer below the engine's own parser, in the solver's source re-parse.

It also explains the **2-vs-6-file mystery**: whether this particular meta-model field access was hit
during the committed agent trace (and thus registered for reflection) is input-shape-dependent — so
some repos resolve under native and some don't, with no JDK-solver involvement.

**Fix applied (2026-06-08, fix (i)).** `JavaParserEngine` now hands every source-root
`JavaParserTypeSolver` a shared, refresh-invariant `ParserConfiguration` with
`LanguageLevel.RAW` (field `sourceSolverConfig`), so the hazardous validators never run during the
solver's re-parses — parity with the engine's own parser. `nativeJdkGapSmoke` flips **RED → green**
(the native binary now prints `app.Service.run()`), and the JVM suite — including the
`NativeJdkResolutionGapTest` parity guard — stays green. The narrower of the two candidates: it
removes the reflective meta-model access rather than registering it, so it can't go input-shape-stale.
(Rejected alternative (ii): register the JavaParser meta-model fields in `META-INF/native-image` — it
would keep the fragile, agent-trace-dependent reflective surface that caused the 2-vs-6-file flakiness.)

## Symptom (reproduced)

A minimal 2-file repo, `app/Service.java` + `app/Client.java`:

```java
// app/Service.java
package app;
public class Service { public void run() { int x = 1; } }

// app/Client.java
package app;
public class Client { void go() { new Service().run(); } }
```

Resolving the `run()` call site (`Client.java:3`, the `.run(` column), engine-direct via
`jcma resolve` (no Tier-2 / cascade / `AnalysisSession` involved — pure `JavaParserEngine`):

| Runtime | Result |
|---|---|
| JVM (`build/install/jcma/bin/jcma resolve …`) | ✅ `fqn: app.Service.run` / `signature: app.Service.run()` / declared `.../Service.java:3` |
| native (`build/native/nativeCompile/jcma resolve …`) | ❌ `jcma: unresolved at .../Client.java:3:31` |

Same divergence through `jcma refs` on this repo: JVM → 1 confirmed reference; native → 0 confirmed,
1 unconfirmed (`[OTHER]`).

### Two facts that frame the bug

1. **It is pre-existing.** Building the native image from `HEAD`'s `JavaParserEngine` (none of the
   11c changes) reproduces the *identical* failure. 11c neither caused nor fixed it. (The 11c engine
   changes — `describe()` type-ref unwrap, `refresh()`, `StableSolver` — are orthogonal; the failing
   path is `resolveMethodCall`, which they don't touch.)
2. **It is input-dependent — and this part is NOT root-caused.** The committed 6-file fixture
   `src/test/resources/fixtures/resolve/refs` (which contains the *same* `new Service().run()`
   pattern in `ClientA`) resolves **correctly under native** (3 confirmed, 2 unconfirmed — matching
   the JVM and `FindReferencesTest`). Only the minimal 2-file repo fails under native. Both repos use
   the same engine, the same JDK-jar path, and (verified) the same source-root discovery, so the
   differentiator is something subtler in the JDK-jar resolution (lookup order? a type only reachable
   once >1 project type is present?). **This is the open question to chase.**

## Root cause

`JavaParserEngine`'s constructor selects the JDK solver by runtime:

```java
// src/main/java/jcma/engine/JavaParserEngine.java  (the JDK branch)
if ("runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
    // NATIVE: resolve the JDK from the cached host-JDK jar, via JarTypeSolver
    HostJdkIndex.resolveCacheJar().ifPresent(jar -> solver.add(new JarTypeSolver(jar)));
} else {
    // JVM: resolve the JDK via reflection over the running JVM's loaded classes
    solver.add(new ReflectionTypeSolver(false));
}
```

- On the JVM, reflection works and is the "known-good" path; the cached JDK jar is **never used**.
- Under native-image, general reflection is unavailable (only build-time-registered classes are
  reflectively present), so task-02b spins up a **non-native JVM helper, once**, to byte-index the
  JDK's `jrt:/` image into a de-moduled jar (cached; JDKs are stable), and native resolves it with
  `JarTypeSolver`. See the `build.gradle` comment: *"byte-index that JDK's own `jrt:/` image into a
  de-moduled jar (then resolves it via `JarTypeSolver`, the proven native-safe path)."*

So the native/JVM gap was *hypothesised* to be exactly **`JarTypeSolver(jdk-bytecode-jar)` vs
`ReflectionTypeSolver`**: resolving a method call on `Service` makes JavaParser's `SymbolSolver` walk
`Service`'s full ancestry, including the implicit `extends java.lang.Object`; the two JDK solvers
*might* differ on some such lookup, and the engine (which guards every resolve down to `Throwable` and
safe-degrades) reports "unresolved" rather than a wrong answer.

> **Disproven (2026-06-08).** Swapping in `JarTypeSolver(jdk-jar)` on the JVM resolves the call
> *identically* to reflection, and variant (b) then showed the real native failure has nothing to do
> with the JDK solver: it is a `NoSuchFieldError` from the **source-root** `JavaParserTypeSolver`
> re-parsing project source under native-image (its validators hit the reflective meta-model). The
> "ancestry walk through the host-JDK jar is lossy" reasoning in this section is **wrong** — see
> "Actual root cause (found by running variant b)" under the Update at the top.

### Why the cache is bytecode, not "persisted reflection results"

A natural alternative design (and the one a reader might assume): run reflection **once** on a real
JVM, persist the *resolved symbol facts*, and have native reuse them — giving native reflection
fidelity and no gap. That is **not** what's built, and there's a concrete reason:

JavaParser's `SymbolSolver` does not consume flat resolved symbols. It consumes rich
`ResolvedReferenceTypeDeclaration` objects (methods, fields, ancestors, type parameters, overload
sets). JavaParser ships exactly **two** producers of those: `ReflectionTypeSolver` (from live `Class`
objects) and `JarTypeSolver` (from `.class` bytecode). **There is no "frozen reflection results →
TypeSolver" path.** So persisting reflection results would require writing a **custom `TypeSolver`**
that reconstructs JavaParser's full resolution model from our persisted graph — a large, ongoing
build. Byte-indexing the JDK to a jar and reusing the stock `JarTypeSolver` sidesteps all of that.
The fidelity gap is the price of that shortcut.

## The fix worth considering: make the JVM dev path use the same `JarTypeSolver`

The gap is **invisible during development** only because dev uses reflection and native uses the jar.
If the **JVM dev path also resolved the JDK via the cached jar** (`JarTypeSolver`), then dev and native
would be identical, and the normal JVM test suite would *catch* native resolution gaps — the whole
"native surprised us" class of bug becomes test-visible without ever building a native image.

**Recommendation:** yes, lean toward routing the JVM path through the same `JarTypeSolver` as native —
**for parity / test-fidelity**, but weigh the trade-offs and probably make it opt-in first:

- **Pros**
  - Dev == native: JVM tests reproduce native resolution behaviour; no more native-only surprises.
  - One JDK-resolution code path to reason about and maintain (not two with divergent fidelity).
  - Directly enables the repro test below to run on plain `./gradlew test`.
- **Cons / risks**
  - The cached JDK jar must exist on the dev path too → first run pays the one-time `jrt:/`
    byte-index (the helper-JVM scan). Mitigated by caching, but it's a cold-start cost in dev/CI.
  - If `JarTypeSolver` fidelity is genuinely *lower* than reflection (this bug suggests it is), making
    dev use it means dev now also "loses" those resolutions — we'd be trading silent native gaps for
    visible dev gaps. That's *better* (visible > silent), but it may surface a batch of newly-failing
    resolutions to triage. **Sequence it:** first confirm with the repro how wide the gap is, fix the
    `JarTypeSolver`/jar (or de-moduling) so it reaches reflection parity, *then* flip dev onto it.
  - Keep reflection available as a fallback/comparison harness (useful for the differential test).
- **Suggested shape:** a selector on `JavaParserEngine` (constructor arg or system property) —
  `JdkSolver.{REFLECTION, HOST_JAR}` — defaulting to today's behaviour (reflection on JVM, jar on
  native), but overridable so tests can force `HOST_JAR` on the JVM. This is also exactly the hook the
  repro test needs.

## Sketch of the failing test(s)

### (a) Cheap & faithful — force the jar path on the JVM (no native build) — ✅ BUILT, did NOT reproduce

> **Outcome (2026-06-08):** built as `src/test/java/jcma/engine/NativeJdkResolutionGapTest.java`. The
> premise below — "the JDK-solver choice is the only difference, so swapping it reproduces native" —
> **does not hold**: the host-JDK jar resolves `new Service().run()` identically to reflection on the
> JVM. The test is kept as a **parity-regression guard** (asserts the two paths agree), not a repro.
> See the Update at the top. The hypothesis text is retained below for the record.

On a repo with **no dependency jars**, the JDK-solver choice is the *only* engine-level difference
between native and JVM. So we can reproduce the native behaviour on the JVM by building the solver
stack the way native does (`JarTypeSolver` over `HostJdkIndex.resolveCacheJar()` instead of
`ReflectionTypeSolver`) and resolving the 2-file `new Service().run()`. *(This turned out false — see
the outcome note above.)*

```java
// src/test/java/jcma/engine/NativeJdkResolutionGapTest.java  (sketch)
@Test
void methodCallResolvesUnderReflectionButNotUnderTheHostJdkJar(@TempDir Path repo) throws Exception {
    Files.createDirectories(repo.resolve("app"));
    Files.writeString(repo.resolve("app/Service.java"),
            "package app;\npublic class Service { public void run() { int x = 1; } }\n");
    Files.writeString(repo.resolve("app/Client.java"),
            "package app;\npublic class Client { void go() { new Service().run(); } }\n");
    Path call = repo.resolve("app/Client.java");
    Position runCall = /* line 2, column of `.run(` */;

    // 1) JVM/reflection path → resolves (the baseline today).
    assertTrue(resolveWith(jdkReflectionSolver(), repo, call, runCall).isPresent(),
            "reflection resolves new Service().run()");

    // 2) Native path emulated on the JVM: JDK via JarTypeSolver over the cached host-JDK jar.
    Path jdkJar = HostJdkIndex.resolveCacheJar().orElseThrow();
    Optional<?> viaJar = resolveWith(new JarTypeSolver(jdkJar), repo, call, runCall);

    // This is the bug: today this is EMPTY (reproduces native). Once fixed, flip to assertTrue.
    assertTrue(viaJar.isEmpty(), "BUG: host-JDK jar path fails to resolve — reproduces native");
}

// resolveWith(jdkSolver, repo, file, pos):
//   CombinedTypeSolver = jdkSolver + JavaParserTypeSolver(repo);
//   new JavaParser(config.setSymbolResolver(new JavaSymbolSolver(combined)));
//   parse(file); smallestEnclosing MethodCallExpr at pos; return guarded .resolve().
```

Implementation note: `JavaParserEngine` currently hardcodes the JDK-solver choice, so the test either
(i) reconstructs the small solver stack itself (as sketched), or (ii) we add the `JdkSolver` selector
above and the test forces `HOST_JAR`. Option (ii) is preferable — it doubles as the parity hook.

Once this test reproduces the gap, **instrument it** to find the actual cause: e.g.
`combined.tryToSolveType("java.lang.Object")`, dump whether `Service`'s resolved declaration exposes
`run()`, and compare what `JarTypeSolver(jdkJar)` returns vs `ReflectionTypeSolver` for the ancestry
walk. Also probe the 6-file vs 2-file difference (the unexplained part) — add a third project type and
see if resolution flips.

### (b) Faithful — exercise the real native binary — ✅ BUILT as `nativeJdkGapSmoke`

Since (a) did *not* reproduce, the cause is native-runtime-specific and (b) is the only way to chase
it. Realized as the **`nativeJdkGapSmoke`** Gradle verification task (mirroring `jdkResolveSmoke` /
`crossJarSmoke`): it stages the minimal 2-file repo, runs the real native `jcma` binary
(`jcma resolve <Client.java> <line:col>`), and **asserts** it resolves to `app.Service.run()`. It
reproduced the gap (binary printed `unresolved …`, exit 1), which is what exposed the real cause; it
now **passes** after the fix (binary prints `app.Service.run()`). It stays as the native regression
guard, the counterpart to the JVM `NativeJdkResolutionGapTest`.

> **Why a CLI smoke, not the literal `nativeTest` task.** GraalVM buildtools' `nativeTest` compiles
> the *entire* JUnit suite into one native image, but parts of this suite cannot run there —
> `JdkIndexTest` and the parity probe drive `JdkIndexer`, which reads the JDK's `jrt:/` image (absent
> under native-image; the engine deliberately offloads that to a *subprocess* host `java`). The
> repo's whole native-verification convention is therefore targeted CLI smokes on the `jcma` binary,
> which is also the surface on which this gap was first observed. The smoke is the faithful, idiomatic
> realization of (b); a full `nativeTest` would require tagging/excluding the non-native-runnable
> tests first (separate scope).

## Pointers

- `src/main/java/jcma/engine/JavaParserEngine.java` — the `imagecode == "runtime"` JDK-solver branch
  (the fork between reflection and the host-JDK jar). Also `describe()` / `resolveMethodCall`.
- `src/main/java/jcma/engine/HostJdkIndex.java` — `resolveCacheJar()` (locates/builds the cached
  de-moduled host-JDK jar).
- `build.gradle` — the `jdkIndexer` helper jar + the comment describing the `jrt:/` byte-index design.
- `src/test/java/jcma/engine/JdkIndexTest.java` — exercises the JDK indexer (related fixtures).
- Committed comparison fixtures: `src/test/resources/fixtures/resolve/refs` (resolves under native),
  vs the ad-hoc 2-file repo (does not).
- Task background: `milestones/M1/tasks/task-02b-jdk-type-solver.md`.

## Decisions to make when picking this up

All resolved — kept as the trail:

1. ~~Write repro (a).~~ **Done — (a) did NOT reproduce** (JVM jar == reflection): not the solver choice.
2. ~~Root-cause via (b).~~ **Done** — `JCMA_DEBUG=1` on the failing native binary dumped the
   `NoSuchFieldError`: the source-root `JavaParserTypeSolver`'s validators hitting the reflective
   meta-model. Not "jar vs reflection" at all.
3. ~~Fix.~~ **Done** — `RAW` config on the source-root solver (fix (i) above); `nativeJdkGapSmoke`
   green. The `JdkSolver`-selector / dev-`HOST_JAR` parity idea is **moot for this bug** (the JVM jar
   path already had parity, so it never would have caught it) — revisit only for a *different*,
   JVM-visible jar-fidelity gap.
4. ~~6-file vs 2-file mystery.~~ **Explained** — input-shape-dependent agent-trace coverage of the
   meta-model field access; removed at the root by the `RAW` fix (no reflective access to register).
