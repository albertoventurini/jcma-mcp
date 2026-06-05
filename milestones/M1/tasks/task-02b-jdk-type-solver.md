# M1 · Task 02b — native JDK type resolution (host-derived signature index)
> Make the **native** binary resolve JDK symbols, by byte-parsing the host JDK instead of reflecting it.

> **STATUS: DONE (2026-06-05).** Implemented via a **helper-JVM indexer**, not the in-process `jmods`
> reader the Design section below originally proposed — see **Design pivot** immediately under it.
> Calibration + measured numbers + locked decisions: [`M1-RESULTS.md`](../../M1-RESULTS.md).
>
> **Design pivot (what shipped).** Two findings overrode the `jmods` plan: (1) the default host JDK
> here is **jimage-only — no `jmods/`** (true of JREs / jlink images generally), so a jmods reader
> can't even dogfood; (2) a short-lived **helper JVM** = `$JAVA_HOME/bin/java` running *on* the target
> JDK reads that JDK's own classes through the built-in **`jrt:/` filesystem** — works on **every
> JDK 9+** regardless of `jmods`, with **zero JDK-internal API** in the native image. So on a cache
> miss the native binary spawns that helper ([`JdkIndexer`](../../../src/main/java/jcma/jdkindex/JdkIndexer.java)),
> which writes a **de-moduled JDK jar**; the native side
> ([`HostJdkIndex`](../../../src/main/java/jcma/engine/HostJdkIndex.java)) feeds it to a plain
> **`JarTypeSolver`** (reusing the proven `--enable-url-protocols=jar` path — **no custom
> `TypeSolver`**). It is **native-only**: the JVM/dev path keeps `ReflectionTypeSolver` (a known-good
> fallback; host classes are loaded), selected by
> `"runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))`. The jar is
> fingerprint-cached at `~/.cache/jcma/jdk-<fp>.jar` (cache hit = no subprocess). The in-process
> `jmods` byte-parse below is **superseded** but kept for the rationale trail.

## Why (surfaced by Task-02)
Running `jcma resolve` on this repo exposed it: the native binary resolves project source and
third-party jars, but **JDK-target symbols are unresolved** (`java.util.Arrays.equals`,
`java.io.PrintStream.println` — both fine on the JVM). Cause: the three type-solvers split under
native-image — `JavaParserTypeSolver` (source) and `JarTypeSolver` (jars) **byte-parse** and work;
`ReflectionTypeSolver` (the JDK) uses **runtime reflection**, which native-image serves only for
build-time-registered classes. Since jcma analyzes *arbitrary* projects/JDKs, the JDK surface
**cannot be pre-seeded** — and it can't be baked in either (which JDK version/vendor?). See
M0-RESULTS §"M1 requirements surfaced by Spike C" #3 (JDK half).

JDK types aren't wanted for navigation *into* the JDK (the agent already knows the JDK); they're
load-bearing **intermediates** for project queries — overload selection in find-refs, `get_type_at`
answers that are themselves JDK generics (`List<Order>`), type/call hierarchy through JDK supertypes.
So this degrades **project-symbol** accuracy, which collides with the §M0 "0 silent-wrong" stance.

## Prerequisites
- **Done before this:** task-02 (`AnalysisEngine` + `JavaParserEngine` + `Workspace`; the
  `--enable-url-protocols=jar` jar fix; `JCMA_DEBUG` hook).
- **Read:** PRD §3 (goal: resolve against *project source + JDK + third-party jars*), §4 (engine
  seam + native-image), §5.1 (fingerprint/freshness model — the cache mirrors it); this task's
  M0-RESULTS finding.

## Sequencing
Recommended **immediately after task-02** (context is hot). Does **not** block the index tasks 3–9
(storage/CSR/trigram/freshness — none need JDK resolution), but **must land before task-10**
(lazy-resolve-and-cache: definition & references) so find-refs/overload correctness is measured
against the real solver, not a JDK-blind one — and before the M2 MCP surface ships to the agent.

## Protocol (test-first; full version in the overview)
**Calibrate first**, then write failing tests + fixtures → **STOP for review** → implement → verify.

## Step 0 — calibrate the bar (don't size on faith)
Reuse the M0 Spike-A harness to measure, on commons-lang + a jackson slice, how much **project**
symbol resolution drops when `ReflectionTypeSolver` is removed (JDK unresolvable). This sizes the
solver: if project resolution barely moves, lazy/partial JDK signatures suffice; if it falls hard,
the JDK index is first-class. Record the number — it sets the Task-02b coverage target the same way
M0 set the others.

## Design (confirm during the task)
- **`jcma.engine.JdkTypeSolver`** — a javaparser `TypeSolver` that resolves JDK types from
  **byte-parsed class signatures**, not reflection. Carries **signatures + hierarchy only** (FQN,
  member signatures, supertypes, generic arity) — **no method bodies, no source, no decl `file:line`**
  (go-to-def *into* the JDK is explicitly out of scope).
- **Source of bytes = the host JDK.** Discover it (`JAVA_HOME` / the project toolchain — sibling of
  Workspace's classpath discovery) and read `$JAVA_HOME/jmods/*.jmod` (zip-format → class entries →
  byte-parse, the same native-friendly path the jar fix proved). **The right JDK is the one already
  on the machine** (the project compiled against it) — so never bake JDKs into the binary.
- **Cache, fingerprinted per JDK.** Build the index once per JDK (version + vendor + hash of
  `release`/`modules`), persist at `~/.cache/jcma/jdk-<fp>.idx`, reuse across runs/projects; freshness
  via the §5.1 fingerprint model.
- **Wire into `CombinedTypeSolver` in place of `ReflectionTypeSolver`.** Decide (and record) whether
  to use `JdkTypeSolver` in **both** native and JVM/dev (consistent behaviour, one path) or only
  native. Replacing reflection everywhere also fixes the Task-02 **solver-ordering wart** (project
  types that are also on jcma's own runtime classpath currently resolve reflectively → external decl
  instead of their source `file:line`).
- **Caveat (write it down):** `jmods/` ship with a full **JDK**; a bare **JRE** / `jlink` runtime
  carries only the `lib/modules` jimage. Full-JDK-present is a safe MVP assumption on a build machine;
  jimage-only is a later fallback (read the jimage, or shell out to the host `java`).

## Tests (red-first)
- **Native JDK-target resolve:** `jcma resolve` (native binary) on a call whose target is a JDK
  method — e.g. `Arrays.equals(byte[],byte[])`, `List.add` — resolves to the JDK FQN + signature,
  `declared: <external>` (no file:line). These **fail today**; the test is the contract.
- **Signature/hierarchy:** a project type override/implements of a JDK type (e.g. `implements
  Comparable` / overrides `Runnable.run`) resolves the JDK supertype.
- **Cache:** index builds once and is reused (fingerprint hit); a different JDK fingerprint rebuilds.
- **Dogfood (the action that surfaced this):** native `jcma resolve` on a jcma source file with a
  JDK-typed expression resolves — i.e. the thing that failed now works on the native binary.

## Manual check
- `JCMA_DEBUG=1 jcma resolve src/main/java/jcma/cli/Main.java 43:21` (native) → `java.io.PrintStream.println(java.lang.String)`.

## Done when
- Native binary resolves JDK-target symbols that are unresolved today (`Arrays.equals`,
  `PrintStream.println`) · `jcma resolve` on this repo works natively · zero JDKs baked into the
  binary; index derived + cached from the host JDK · calibration number + design decisions recorded
  in `M1-RESULTS.md`, and the M0-RESULTS "JDK half" item closed.
