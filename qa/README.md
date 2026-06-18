# jcma dependency-resolution QA harness

A committed, repeatable harness that measures **jcma's accuracy and performance** at resolving each
type's dependencies, on **any compilable Maven Java repo** — against a `javac` AST oracle. It exists
to answer "how good is jcma's source-level resolution on a repo it has never seen?" with numbers, not
vibes, and to let that check be re-run as jcma evolves.

This is QA infrastructure: it lives under `qa/`, never touches `src/main`, and the oracle/comparator
run as **JDK single-file source-launches** (`java Oracle.java`) — stock JDK, no Gradle wiring, no
`javac` on jcma's native path.

## What it measures

For every declared type (top-level + nested, main + test), two dependency relations:

1. **Supertypes** — its direct `extends` / `implements` targets.
2. **Outgoing type references** — every type mentioned in its body: field/local/param/return/throws
   types, generic type arguments, `extends`/`implements`/`permits`, `new`, casts, `instanceof`,
   annotations, class literals, switch patterns.

Both sides resolve at the **source level** and the comparison is over **erased type-element FQNs**
(generics dropped), deduped, excluding primitives, `java.lang.Object`, and self-edges.

Type references split by what jcma's syntactic scan can *see*. A type **written** in source is jcma's
**navigation contract** (`TYPEREF`) and counts toward headline recall. A type only **inferred** —
a `var` local or an implicit lambda parameter, where the name is never written — is
`TYPEREF_INFERRED`: javac resolves it, jcma's syntactic scan (like IntelliJ's *Find Usages* on a
type) does not, so it is reported as a separate **out-of-contract** category, not charged against
headline recall.

- **Oracle** (`qa/oracle/Oracle.java`): full `javac` `JavacTask.parse() + analyze()` over all sources
  with the project's Maven classpath, then a `TreePathScanner` emits the truth set. This is the
  apples-to-apples reference for jcma's own source-level resolver.
- **jcma** (`jcma resolve-file <file>`): runs the *same* per-node JavaParserSymbolSolver resolve a real
  `find_references` uses, but over **every** type mention in the file (exhaustive selection, identical
  resolution), attributed to the enclosing type. (jcma has no single "dependencies of X" surface;
  `resolve-file` is that surface, added for this QA.)

Results are partitioned **intra-repo** (the dependency is a repo-declared type — jcma's primary
correctness signal) vs **external** (JDK/library — classpath-dependent, reported separately).

## Run it

```sh
./gradlew nativeCompile                       # build the jcma binary the harness drives
qa/scripts/qa-deps-accuracy.sh <repo-path>    # e.g. ~/Work/tiko-trust-rule-engine
```

Outputs (under `qa/out/`, `<repo>` = the repo's directory name):

| file | contents |
|---|---|
| `<repo>-report.md` | the summary: coverage, per-relation recall/precision (intra/external, micro+macro), worst offenders, performance |
| `<repo>-diffs.tsv` | every per-type divergence (`relation`, `MISS`/`EXTRA`, `owner`, `dep`, `partition`) — for spot-checking |
| `<repo>-oracle.tsv` / `<repo>-jcma.tsv` | the raw dependency sets each side produced |

The script is idempotent and deterministic: it uses a fresh temp `XDG_CACHE_HOME` per run (so it never
collides with a live `jcma serve` index lock), and re-running yields identical TSVs. The generated
artifacts (report, diffs, raw TSVs) are host-dependent and reproducible, so they are **not** committed —
only the hand-written `tiko-trust-rule-engine-spotcheck.md` (authored divergence analysis) is.

## Reading the numbers

- **recall** = `|jcma ∩ oracle| / |oracle|` — did jcma find the dependency javac sees? Misses are the
  **safe-degrading** mode (jcma resolves nothing rather than guessing wrong).
- **precision** = `|jcma ∩ oracle| / |jcma|` — is every dependency jcma reports real? An **extra**
  (jcma has it, oracle doesn't) is the mode to scrutinise — a potential silent-wrong answer.
- **micro** pools all dependency pairs; **macro** averages per type.

A jcma miss lowers recall but is tolerable (PRD safe-degrade contract); a jcma extra lowers precision
and is the harmful mode. The `*-diffs.tsv` lists each so divergences can be classified by hand (real
jcma miss / real jcma extra / oracle artifact / classpath gap / inferred-only).

- **Inferred-only** is a separate report section, not a recall miss: oracle deps reachable only through
  a `var`/lambda inference (`TYPEREF_INFERRED` minus `TYPEREF`). jcma's coverage there is ~0% **by
  design** — it is the navigation contract, not a bug. Detection is source-faithful: a type position is
  inferred iff its source span is empty, the text `var`, or (for a lambda param) the param-name token.

## Scope / follow-ups

- **Maven only** in this first cut (resolves the classpath jcma persisted at index time). Gradle is a
  noted follow-up (jcma already discovers Gradle classpaths; the script's source-root + classpath
  wiring would need the Gradle equivalents).
- The per-file `resolve-file` invocation pays a cold engine + jar-solver start each call; the harness
  reports that per-file latency. A warm session (one process) would amortise it — out of scope here,
  where one-shot per-file keeps the harness simple and the timing per-type honest.
- The **intra-repo partition** uses the index's declared type symbols as the repo-type set. This is
  exact when those are all repo-owned (e.g. tiko). For a repo where jcma's index also records library
  type symbols (jcma's own repo does), "intra" will include some library deps — read the **all**
  partition as the headline there. Partitioning off the source-root paths instead is a follow-up.
