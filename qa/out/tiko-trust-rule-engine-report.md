# jcma dependency-resolution QA — tiko-trust-rule-engine

Accuracy of `jcma resolve-file` (JavaParser + JavaSymbolSolver, source-level) against a **javac AST oracle** (full `parse()`+`analyze()` over the same sources and classpath). Apples-to-apples: both resolve type mentions at the source level; the comparison is over **erased type-element FQNs**, deduped, excluding primitives and `java.lang.Object`.

## Coverage

| metric | value |
|---|---|
| repo types (declared, from index) | 245 |
| types with ≥1 dependency (oracle) | 233 |
| types with ≥1 dependency (jcma) | 232 |
| oracle owner types jcma never emitted | 1 |
| jcma unresolved type mentions (safe-degraded misses) | 127 |

> Owner types present in the oracle but absent from jcma output: `tiko.trust.ruleengine.actor.internal.service.ActorServiceImpl`

## Supertypes (extends/implements)

| partition | oracle | jcma | matched | recall | precision |
|---|---|---|---|---|---|
| intra-repo | 87 | 86 | 86 | 98.9% | 100.0% |
| external | 24 | 24 | 24 | 100.0% | 100.0% |
| all | 111 | 110 | 110 | 99.1% | 100.0% |

Intra-repo **macro** (per-type mean): recall 98.9%, precision 100.0% over 87 owner type(s).

Worst intra-repo offenders (misses = oracle−jcma, extras = jcma−oracle):

| owner | misses | extras |
|---|---|---|
| `…service.ActorServiceImpl` | `…actor.ActorService` | — |

## Outgoing type references

| partition | oracle | jcma | matched | recall | precision |
|---|---|---|---|---|---|
| intra-repo | 789 | 752 | 752 | 95.3% | 100.0% |
| external | 1686 | 1626 | 1609 | 95.4% | 99.0% |
| all | 2475 | 2378 | 2361 | 95.4% | 99.3% |

Intra-repo **macro** (per-type mean): recall 98.5%, precision 100.0% over 201 owner type(s).

Worst intra-repo offenders (misses = oracle−jcma, extras = jcma−oracle):

| owner | misses | extras |
|---|---|---|
| `…service.ActorServiceImpl` | `…Actor.Type`, `…ActorJpa.ActorId`, `…actor.ActorService`, `…entity.ActorJpa`, `…entity.BusinessJpa`, `…entity.ConnectorJpa`, `…entity.RafikiJpa`, `…exception.ActorNotFoundException` … | — |
| `…crud.CrudEventListenerIT` | `…entity.ConnectorJpa`, `…model.Connector` | — |
| `…service.RafikiMergeEnrichServiceTest` | `…entity.MergeConflictEnrichedJpa`, `…entity.RafikiMergeEnrichedJpa` | — |
| `…internal.OrchestratorServiceIT` | `…RuleTriggeredEventMapper.SupportedSchemaVersions`, `…mapper.RuleTriggeredEventMapper` | — |
| `…strategy.CreateCaseStrategy` | `…CreateCase.Indicator` | — |
| `…strategy.CreateCaseStrategyTest` | `…CreateCase.Indicator` | — |
| `…crud.CrudEventListenerV2FlagEnabledIT` | `…model.Business` | — |
| `…service.ServiceValidationEnrichService` | `…Business.WeeklyHours` | — |
| `…internal.OrchestratorService` | `…evaluation.EvaluationRow` | — |
| `…internal.TargetResolverTest` | `…target.ActionTarget` | — |
| `…RuleTriggeredEventMapper.SupportedSchemaVersions` | `…mapper.RuleTriggeredEventMapper` | — |
| `…jooq.JooqCountOccurrencesEvaluatorIT` | `…evaluation.EvaluationRow` | — |
| `…jooq.JooqMatchEventEvaluatorIT` | `…evaluation.EvaluationRow` | — |
| `…jooq.JooqMatchLatestConsecutiveEventsEvaluatorIT` | `…evaluation.EvaluationRow` | — |
| `…mapper.ActionMapper` | `…CreateCase.Indicator` | — |

## Performance

| metric | value |
|---|---|
| Cold index (wall, incl. classpath resolve) | 4.64s |
| Cold index (internal parse+persist) | 0.34s |
| Classpath resolve (mvn) | 4.30s |
| Classpath jars | 312 |
| Indexed LOC | 12485 |
| Indexed symbols | 1438 |
| Index throughput | 36720 LOC/s |
| | |
| resolve-file total (all files) | 127.2s |
| resolve-file files | 207 |
| resolve-file mean / file | 609.0 ms |
| resolve-file p95 / file | 641.0 ms |
| resolve-file max / file | 10289.1 ms |

## Method

- **Oracle**: `javac` `JavacTask.parse()+analyze()` over all sources with the full Maven classpath; a `TreePathScanner` emits each declared type's direct supertypes and every resolved type mention (field/var/param/return/throws/type-args/extends/implements/new/cast/instanceof/annotation), erased to the type-element FQN.
- **jcma**: `jcma resolve-file <file>` runs the same JavaParserSymbolSolver per-node resolve a real `find_references` uses, but over **every** type mention in the file (exhaustive selection, identical resolution), attributed to the enclosing type.
- **Intra-repo** = the dependency is a repo-declared type; this is jcma's primary correctness signal. **External** resolution depends on classpath completeness and is reported separately.
- A jcma **miss** (oracle has it, jcma doesn't) is the safe-degrading mode; a jcma **extra** (jcma has it, oracle doesn't) is the one to scrutinise. See the `*-diffs.tsv` for the full per-type divergence list.
