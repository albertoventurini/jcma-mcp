# M1 · Task 01 — Gradle + GraalVM scaffolding, `jcma` CLI skeleton, native build green
> Stand up the build so a native `jcma` binary exists from day one.

## Prerequisites (read first, fresh session)
- **Done before this:** nothing (first task).
- **Read:** PRD §9 (project layout) ; M1 overview `milestones/M1/M1-core-engine-index.md` ;
  M0-RESULTS §"Native-image (Spike C)" + §"M1 requirements surfaced by Spike C".
- **Port from M0 (reference, don't extend):**
  - `milestones/m0-spike/src/main/java/m0/SpikeC.java` — `cap()`/`selftest()` capability runner →
    reusable native smoke harness; native-image flags.
  - `milestones/m0-spike/src/main/resources/META-INF/native-image/reachability-metadata.json` —
    committed reachability seed.

## Protocol (test-first; full version in the overview)
Write failing tests + fixtures → **STOP for review** → implement → verify:
`./gradlew test` green · `nativeCompile` + native smoke green · manual CLI check.

## Scope — files to create/modify
- `settings.gradle.kts`, `build.gradle.kts` — Java 25, JUnit 5, `application` plugin,
  `org.graalvm.buildtools.native`, `javaparser-symbol-solver-core:3.28.2`. Encode the
  *agent-trace (`-Pagent`) → `metadataCopy` → `nativeCompile`* order. Carry native args
  `--enable-native-access=ALL-UNNAMED -H:+UnlockExperimentalVMOptions -H:+SharedArenaSupport`.
- `gradle/wrapper/*`, `gradlew`, `gradlew.bat` — pinned wrapper (9.5.1).
- `src/main/resources/META-INF/native-image/reachability-metadata.json` — ported seed.
- `src/main/java/jcma/cli/Main.java` — arg dispatch + trivial `version` subcommand.
- `src/main/java/jcma/cli/NativeSmoke.java` (or `cli/SelfTest.java`) — ported `cap()`/`selftest()`
  capability runner (times + PASS/FAIL each capability), reused by later tasks' native verify.

## Tests (red-first)
- Unit: `MainTest` runs `version` through the dispatcher (capture stdout), asserts a version
  string is printed and exit code 0.
- Native smoke: a test (or gradle check) asserting `./build/native/nativeCompile/jcma version`
  exits 0 and prints the version (the cap-harness PASS line).

## Manual CLI check
- `./gradlew nativeCompile && ./build/native/nativeCompile/jcma version` → prints version, exit 0.

## Done when
- `./gradlew test` green · `nativeCompile` green · `jcma version` works native · seed metadata
  bundled in the jar before the native build (M0's load-bearing order preserved).
</content>
