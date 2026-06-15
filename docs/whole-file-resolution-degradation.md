# Whole-file resolution degradation

> **Status: RESOLVED (2026-06-15).** Root-caused to parsing the resolving CU at `LanguageLevel.RAW`:
> RAW (literally `null`) never enables `yield` support, so a `yield` statement becomes a parse
> *problem*; JavaParser injects the `SymbolResolver` only on a problem-free parse
> (`ParseResult.ifSuccessful`), so the whole CU comes back resolver-less and every `.resolve()`
> throws "Symbol resolution not configured" → the per-node guard swallows it → whole-file
> zero-confirmed. RAW's error recovery additionally *discards the entire `yield` block arm*, so
> symbols inside it never even enter the AST. **Fix:** the engine's *resolving* parser now parses at
> the project's discovered Java level (from the build; runtime-JDK fallback) with the language-level
> **validator stripped** (the var→VarType post-processor kept) — so the CU is problem-free (resolver
> attaches, even inside the yield arm) and no reflective meta-model is touched (native-safe; see
> `docs/native-jdk-resolution-gap.md`). See **"Resolution (2026-06-15)"** at the bottom. The original
> investigation is kept below for the record.

**Found:** 2026-06-15, by the `qa/` dependency-resolution QA harness · **Severity:** *was* the dominant
recall-loss mode for type-reference resolution (precision is otherwise ~100%).

## Symptom

For certain source files, jcma's resolver yields **zero** confirmed type references — *every* type
mention in the file safe-degrades to "unconfirmed" — even though the file **parses fine** (all mentions
are enumerated) and contains ordinary, resolvable types. No exception is surfaced; it is a silent,
whole-compilation-unit failure, not a scatter of per-mention misses.

This is correctness-safe (the PRD §4 safe-degrade contract holds — unconfirmed, never wrong) but it
sinks recall: an entire file's dependency edges go missing.

## Evidence

Measured by `qa/scripts/qa-deps-accuracy.sh` (`jcma resolve-file` vs a javac AST oracle):

- **`tiko-trust-rule-engine` → `ActorServiceImpl`**: `jcma resolve-file` returns **40/40 `UNRESOLVED`**,
  including `java.util.Optional` and intra-repo `Actor`. It is the single owner type jcma emitted
  nothing for, and accounts for **20 of 37 intra-repo type-ref misses (~54%)** plus the sole supertype
  miss. Excluding this one file, intra-repo type-ref recall rises **95.3% → 97.8%**.
- **jcma's own repo → `jcma.mcp.json.JsonReader`**: same shape — the lone owner with no emitted
  dependencies in the self-run.

Both implicated files lean on patterns that historically stress JavaSymbolSolver (Spring
`ApplicationContext` / `getBean`, nested-type imports such as `ActorJpa.ActorId`). This is the same
family as the prior Spring `getName`/`getBean` near-zero-confirmed investigation — but that one was a
multi-module source-root discovery gap (fixed in commit `03852a3`); this is a **single-file**,
all-mentions-fail cliff, so the cause is likely different.

## Reproduce

```sh
./gradlew nativeCompile
JCMA_BINARY=build/native/nativeCompile/jcma
# index the repo first so the classpath cache exists, then:
$JCMA_BINARY resolve-file <repo>/src/main/java/tiko/trust/ruleengine/actor/internal/service/ActorServiceImpl.java
# → every row tagged UNRESOLVED
```

The harness reports it directly under **"owner types jcma never emitted"** in `qa/out/<repo>-report.md`.

## Hypotheses to chase (fresh context)

The per-node `attempt()` in `JavaParserEngine` is individually guarded, so all-fail points at the
**unit-level type-solver setup**, not the individual resolves:

1. The `CombinedTypeSolver` / source `JavaParserTypeSolver` for this unit throws or returns
   unsolved-for-everything on first touch — e.g. an import or member whose resolution poisons the
   solver's cached state for the whole file (suspect: `ApplicationContext.getBean(...)`, the
   nested-type import `ActorJpa.ActorId`).
2. A `StackOverflowError` / `UnsupportedOperationException` early in the first resolve leaves the
   facade in a state where subsequent resolves all miss (the `JavaParserFacade` cache is per-solver).
3. A classpath gap specific to a jar this file needs (e.g. `org.tiko.jedicommons`) cascades, though
   that would not explain `java.util.Optional` also failing.

Start by running `resolve-file` on `ActorServiceImpl` with `DEBUG` stack traces enabled in
`JavaParserEngine` and bisecting the file (remove imports/members) until resolution recovers.

## Don't confuse with

- **Per-mention misses** (generic type args like `List<EvaluationRow>`, deeply-nested types like
  `Action.CreateCase.Indicator`) — those are a separate, smaller recall gap, not a whole-file cliff.
- See also `docs/native-jdk-resolution-gap.md` for the JDK-signature resolution path.

## Resolution (2026-06-15)

The hypotheses above ("poisoned solver state", "first-resolve `StackOverflowError`", "classpath gap")
were all wrong — it is a **parse-level** failure, upstream of any resolution:

- `JavaParserEngine.buildParser()` parsed at `LanguageLevel.RAW`, and `ParserConfiguration.RAW` is
  literally `null`. With a null level JavaParser never calls `setYieldSupported()`, so the lexer
  **demotes the `yield` keyword to an identifier** and the switch-expression block arm fails to parse →
  a parse *problem* on the compilation unit.
- JavaParser injects the `SymbolResolver` into a CU only via `ParseResult.ifSuccessful()` (zero
  problems). With a problem present, the CU comes back **without a resolver**, so *every* `.resolve()`
  in the file throws "Symbol resolution not configured" — even `java.util.Optional` / `String`. The
  engine's per-node `Throwable` guard swallows each → whole-file zero-confirmed.
- RAW's error recovery also **discards the whole `yield` block arm**, so symbols inside it (the
  `ActorJpa.ActorId`-style nested references, etc.) never enter the AST at all.

This is upstream-known and WONTFIX-by-doc (javaparser #4813, "YieldStmt lost when parsing with
`LanguageLevel.RAW`", closed by a docs-only PR): RAW means "base grammar, no version-gated contextual
features".

**Fix (the settled-decision reversal, per `CLAUDE.md` — new evidence + PRD update).** The engine's
*resolving* parser now parses at the project's discovered Java level instead of RAW, with the
language-level **validator** post-processor stripped:

- A non-null level makes `yield`/`record`/`sealed`/patterns parse → the CU is problem-free → the
  resolver attaches → even in-`yield` symbols resolve.
- The level is **derived from the build** (not forced to newest — contextual keywords cut both ways, so
  an old-level project using `record`/`yield` as *identifiers* must still parse): Gradle via the
  classpath init-script (one subprocess, keyed `JCMA_SRC_LEVEL` marker); Maven via `help:effective-pom`
  piggybacked on the classpath `mvn` run, with a raw-`pom.xml` regex fallback. No declared level →
  jcma's runtime JDK (`Runtime.version().feature()`). Cached next to the classpath at index time
  (`<indexDir>/java-level.txt`).
- The validator is stripped because it walks the AST through JavaParser's **reflective meta-model**
  (`PropertyMetaModel.getValue`) — the native-image `NoSuchFieldError` hazard that made RAW the
  original choice (`docs/native-jdk-resolution-gap.md`). It lives in a *separate, removable*
  constructor processor (index 3, distinct from yield-flagging and resolver injection at index 4), so
  removing just it gives yield parsing with **no reflective meta-model access** (native-safe) and **no
  per-parse validator cost** (Tier-1 stays fast). The var→`VarType` post-processor is kept.

The **source-solver** parser stays on RAW: it only extracts type *declarations* (which `yield` in a
method body does not affect), and RAW keeps it trivially native-safe.

Regression coverage: `ResolveFileDependenciesTest.yieldStatementDoesNotSinkWholeFileResolution`
(now also asserts an in-`yield`-only type resolves), the engine guard test
`JavaParserEngineLevelTest` (pins the index-3 surgery: yield resolves, var resolves, validator off),
`WorkspaceTest` (level discovery + cache), and the `nativeYieldResolveSmoke` Gradle task (the real
native binary: no `NoSuchFieldError`, yield symbols resolved).
