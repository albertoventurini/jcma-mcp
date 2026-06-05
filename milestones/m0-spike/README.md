# M0 spike harness (throwaway)

De-risking spike for `jcma` — see [`../M0-de-risking-spike.md`](../M0-de-risking-spike.md).
**Throwaway code:** deleted after `M0-RESULTS.md` is written; M1 starts clean.

All four gated spikes (A accuracy, B perf/memory, C native-image, D incremental format) are
**complete and GREEN** — verdict **GO** in [`../M0-RESULTS.md`](../M0-RESULTS.md). The wiring-smoke
section below is the original harness check; per-spike reproduce + raw artifacts follow / live in
`out/`.

## Pinned versions

| Thing | Pin |
|---|---|
| JDK | Temurin 25 (`25+36-LTS`), via SDKMAN `current` |
| Engine dep | `com.github.javaparser:javaparser-symbol-solver-core:3.28.2` (documents Java 1–25) |
| Accuracy/labeling repo | apache/commons-lang @ `rel/commons-lang-3.20.0` — SHA `598dfc163b8b410fb3bb8794521206ec8dcec82a` |
| Scale/perf repo | FasterXML/jackson-databind @ `jackson-databind-2.20.2` — SHA `34097b77d41b7ff835fdbe9bf274b96a0c640df9` |
| Native-image distro (Spike C) | GraalVM CE 25.0.2 (`25.0.2-graalce` via SDKMAN); `mvn` stays on Temurin |

Source roots: both repos use `src/main/java` (commons-lang 259 files, jackson-databind 481).

## Layout

```
m0-spike/
├─ pom.xml                       # javaparser-symbol-solver-core:3.28.2 + shade (fat-jar for Spike C)
├─ src/main/java/m0/HarnessSmoke.java
└─ corpus/
    ├─ commons-lang/      + cp.txt   (21 jars; incl. test deps)
    └─ jackson-databind/  + cp.txt   (38 jars; jackson-core/annotations etc.)
```

## Reproduce

```sh
# 1. corpus checkout (already done)
cd corpus
git clone --depth 1 --branch rel/commons-lang-3.20.0 https://github.com/apache/commons-lang
git clone --depth 1 --branch jackson-databind-2.20.2  https://github.com/FasterXML/jackson-databind

# 2. dependency classpath (manual classpath SymbolSolver consumes; PRD §4 / M0 Spike A.1)
( cd commons-lang     && mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt )
( cd jackson-databind && mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt )

# 3. build + run the wiring smoke (from m0-spike/)
mvn -q package -DskipTests
java -jar target/m0-spike.jar corpus/commons-lang/src/main/java     corpus/commons-lang/cp.txt
java -jar target/m0-spike.jar corpus/jackson-databind/src/main/java corpus/jackson-databind/cp.txt
```

## Smoke result (this session)

Wiring proven end-to-end: parse @ `LanguageLevel.JAVA_25` + ReflectionTypeSolver (JDK) +
JavaParserTypeSolver (project source) + JarTypeSolver (deps) → method calls actually resolve.

| Repo | files (budget 60) | parse-fail | method calls | resolved |
|---|---|---|---|---|
| commons-lang | 60 | 0 | 2741 | **94.7%** |
| jackson-databind | 60 | 0 | 2161 | **100%** |

Smoke only (a 60-file slice, not the gated measurement). Notes for Spike A:
- **JDK-25 syntax parses clean** — zero parse failures; the recorded "confirm JDK-25 parsing"
  risk is retired for these repos.
- jackson-databind's 100% includes **cross-jar** resolution into jackson-core → JarTypeSolver
  wiring is correct.
- The ~5% unresolved on commons-lang is the *kind* of signal Spike A must bucket into the
  failure-cause histogram (generics/overloads/lambdas/var/…), over the **whole** repo, not a
  slice — not a pass/fail here.

## Spike C — native-image (reproduce)

The native build needs GraalVM on PATH; `mvn` stays on Temurin. Order matters — **trace →
package → build** (package-before-trace ships a config-less jar):

```sh
GVM=~/.sdkman/candidates/java/25.0.2-graalce
CFG=src/main/resources/META-INF/native-image

# 1. trace reachability metadata on the GraalVM JVM, both run modes (agent → committed M1 seed)
mvn -q package                                                    # build the jar to trace against
$GVM/bin/java -agentlib:native-image-agent=config-output-dir=$CFG \
  --enable-native-access=ALL-UNNAMED -cp target/m0-spike.jar m0.SpikeC selftest
$GVM/bin/java -agentlib:native-image-agent=config-merge-dir=$CFG \
  --enable-native-access=ALL-UNNAMED -cp target/m0-spike.jar m0.SpikeC mcp < out/scripted-initialize.jsonl

# 2. re-package so META-INF/native-image is bundled into the jar, THEN build the binary
mvn -q package
$GVM/bin/native-image --no-fallback --enable-native-access=ALL-UNNAMED \
  -H:+UnlockExperimentalVMOptions -H:+SharedArenaSupport \
  -cp target/m0-spike.jar m0.SpikeC -o out/spikec

# 3. run + measure
out/spikec selftest                          # parse + resolve + mmap → ALL PASS
out/spikec mcp < out/scripted-initialize.jsonl   # initialize + tools/list JSON-RPC
```

Result: G4 PASS — all four capabilities run natively; ~27 MB binary, ~14 ms start, 25.8 MB RSS.
Full detail in `out/spikeC-results.md`; verdict in `../M0-RESULTS.md`.

## Spikes A/B/D — see the decision memo
`../M0-RESULTS.md` (gate table + failure histogram + reachability config + GO/FALLBACK), with raw
artifacts under `out/` (gitignored).
