# M1 · Task 02 — `AnalysisEngine` interface + JavaParser engine + workspace/classpath
> Resolve a symbol in a real project through the swappable engine interface.

## Prerequisites (read first, fresh session)
- **Done before this:** task-01 (Gradle build + native smoke harness + `jcma` CLI dispatch).
- **Read:** PRD §4 (engine + fallback seam) ; M1 overview ; M0-RESULTS §"M1 requirements surfaced
  by Spike C" #3 (reflection-scaling open question).
- **Port from M0 (reference, don't extend):**
  - `milestones/m0-spike/src/main/java/m0/SolverSetup.java` — `build()` type-solver wiring
    (`ReflectionTypeSolver(false)` + `JavaParserTypeSolver` + `JarTypeSolver`).

## Protocol (test-first; full version in the overview)
Write failing tests + fixtures → **STOP for review** → implement → verify.

## Scope — files to create/modify
- `src/main/java/jcma/engine/AnalysisEngine.java` — interface (keeps the §4 fallback seam:
  parse, resolve-method-call, resolve-type, declaration-location).
- `src/main/java/jcma/engine/JavaParserEngine.java` — impl porting `SolverSetup` wiring.
- `src/main/java/jcma/workspace/Workspace.java` — source-root discovery; **Maven `pom.xml`
  parsing** for source dirs; **manual classpath file** (`cp.txt`, `File.pathSeparator`-split);
  classpath auto-detect via `mvn dependency:build-classpath` (invoked/loaded here).
- `src/main/java/jcma/cli/` — wire a `resolve <file> <line:col>` subcommand.

## Tests (red-first)
- Unit (`fixtures/engine/`): a 2-class project (caller + callee) — assert a method call resolves
  to the callee's FQN/signature.
- Integration: on `milestones/m0-spike/corpus/commons-lang`, assert a known method-call resolves
  to its declaration (reuse a site from `out/gotodef-worksheet-commons.md`).
- **Native sub-check (M0 open question):** a native smoke that resolves one **cross-jar** symbol
  via `JarTypeSolver` over a real jar — confirms agent-traced metadata scales past the minimal
  resolve. Record the finding (ratifies/answers PRD §11 reflection-scaling).

## Manual CLI check
- `jcma resolve <file> <line:col>` → prints resolved FQN + declaration site.

## Done when
- tests green · native green · cross-jar resolve works native · reflection-scaling finding recorded.
</content>
