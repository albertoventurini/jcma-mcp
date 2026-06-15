# Whole-file resolution degradation (open finding)

**Status:** open · **Found:** 2026-06-15, by the `qa/` dependency-resolution QA harness · **Severity:**
the dominant recall-loss mode for type-reference resolution (precision is otherwise ~100%).

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
