package jcma.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import jcma.workspace.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Task-02 — the {@link AnalysisEngine} seam, exercised through {@link JavaParserEngine}.
 *
 * <p>Two layers: a hand-authored caller/callee fixture (deterministic, committed, no deps) proving
 * the resolve + declaration-location + safe-degrade contract; and a pinned-corpus integration test
 * reproducing M0 go-to-def worksheet <b>site #1</b> on commons-lang, which additionally exercises
 * {@code JarTypeSolver} (the corpus classpath) end-to-end.
 */
class JavaParserEngineTest {

    private static final Path BASIC = Path.of("src/test/resources/fixtures/engine/basic");

    /** Engine over the basic fixture dir as the lone source root, with no classpath jars. */
    private static JavaParserEngine basicEngine() {
        Workspace ws = new Workspace(BASIC, List.of(BASIC), List.of());
        return new JavaParserEngine(ws);
    }

    // ---------------------------------------------------------------- unit: resolve + locate

    @Test
    void resolvesMethodCallToCalleeSignatureAndDeclSite() throws Exception {
        JavaParserEngine engine = basicEngine();
        ParsedUnit unit = engine.parse(BASIC.resolve("Caller.java"));

        // Caller.java line 7: `        int r = c.compute(21);` — `compute` name token at column 19.
        Optional<ResolvedRef> ref = engine.resolveMethodCall(unit, new Position(7, 19));

        assertTrue(ref.isPresent(), "c.compute(21) should resolve");
        assertEquals("Callee.compute(int)", ref.get().signature(),
                "default-package callee signature");
        assertEquals("Callee.compute", ref.get().fqn());
        assertEquals(BASIC.resolve("Callee.java").toAbsolutePath(),
                ref.get().declFile().toAbsolutePath(), "declared in Callee.java");
        assertEquals(5, ref.get().declLine(), "compute(int) is declared on Callee.java:5");
    }

    @Test
    void unresolvableCallSafeDegradesToEmpty() throws Exception {
        JavaParserEngine engine = basicEngine();
        ParsedUnit unit = engine.parse(BASIC.resolve("Unresolvable.java"));

        // Unresolvable.java line 7: `        m.doStuff();` — `doStuff` token at column 11. `Mystery`
        // is declared nowhere → resolve throws internally → empty (never a silent-wrong guess).
        Optional<ResolvedRef> ref = engine.resolveMethodCall(unit, new Position(7, 11));

        assertFalse(ref.isPresent(), "an unresolvable call must yield Optional.empty(), not a guess");
    }

    // ---------------------------------------------------------------- integration: corpus site #1

    private static final Path SLICE =
            Path.of("src/test/resources/fixtures/engine/commons-lang-slice/src");

    /**
     * M0 worksheet site #1, reproduced against a vendored 2-file slice of commons-lang 3.20.0
     * (see the slice's NOTICE.md): AnnotationUtils.java:55 `setDefaultFullDetail(true)` must resolve
     * to {@code org.apache.commons.lang3.builder.ToStringStyle.setDefaultFullDetail(boolean)}
     * declared at ToStringStyle.java:2089. The resolution is source→source (JavaParserTypeSolver +
     * JDK reflection), so the closure is exactly these two files and the test needs no classpath —
     * hermetic, offline, and frozen at the pinned version. (The JarTypeSolver-over-jars surface is
     * covered by the native cross-jar smoke, not here.)
     */
    @Test
    void corpusSite1ResolvesToToStringStyle() throws Exception {
        Workspace ws = new Workspace(SLICE, List.of(SLICE), List.of());
        JavaParserEngine engine = new JavaParserEngine(ws);

        Path annotationUtils = SLICE.resolve("org/apache/commons/lang3/AnnotationUtils.java");
        ParsedUnit unit = engine.parse(annotationUtils);

        // AnnotationUtils.java line 55: `            setDefaultFullDetail(true);` — token at column 13.
        Optional<ResolvedRef> ref = engine.resolveMethodCall(unit, new Position(55, 13));

        assertTrue(ref.isPresent(), "site #1 should resolve");
        assertEquals("org.apache.commons.lang3.builder.ToStringStyle.setDefaultFullDetail(boolean)",
                ref.get().signature());
        assertEquals("ToStringStyle.java", ref.get().declFile().getFileName().toString());
        assertEquals(2089, ref.get().declLine(), "setDefaultFullDetail is declared on ToStringStyle.java:2089");
    }

    // ---------------------------------------------------------------- cross-jar (JarTypeSolver)

    private static final Path CROSSJAR_APP =
            Path.of("src/test/resources/fixtures/engine/crossjar/app");
    private static final Path CROSSJAR_JAR = Path.of("build/fixtures/crossjar-lib.jar");

    /**
     * Cross-jar resolution through {@code JarTypeSolver}: the callee {@code crossjar.lib.Greeting}
     * exists only inside the fixture jar (built by the {@code crossJarFixtureJar} Gradle task, on
     * which {@code test} depends), so resolving {@code g.hello("world")} necessarily goes through
     * the jar. The declaration is external (no project source) → {@code declFile == null}.
     *
     * <p>This is the JVM half of the M0 reflection-scaling check; it also seeds the native-image
     * agent trace ({@code -Pagent test}) with the {@code JarTypeSolver}/javassist surface the native
     * cross-jar smoke needs. The native half is the {@code crossJarSmoke} Gradle task.
     */
    @Test
    void crossJarCallResolvesThroughJarTypeSolver() throws Exception {
        assumeTrue(Files.exists(CROSSJAR_JAR),
                "fixture jar not built (./gradlew crossJarFixtureJar) — skipping");

        Workspace ws = new Workspace(CROSSJAR_APP, List.of(CROSSJAR_APP), List.of(CROSSJAR_JAR));
        JavaParserEngine engine = new JavaParserEngine(ws);
        ParsedUnit unit = engine.parse(CROSSJAR_APP.resolve("crossjar/app/App.java"));

        // App.java line 12: `        String s = g.hello("world");` — `hello` name token at column 22.
        Optional<ResolvedRef> ref = engine.resolveMethodCall(unit, new Position(12, 22));

        assertTrue(ref.isPresent(), "g.hello(\"world\") should resolve via the jar");
        assertEquals("crossjar.lib.Greeting.hello(java.lang.String)", ref.get().signature());
        assertNull(ref.get().declFile(), "callee lives only in the jar → external decl");
    }

    // ---------------------------------------------------------------- JDK targets (Task-02b)

    private static final Path JDK_RESOLVE =
            Path.of("src/test/resources/fixtures/engine/jdk-resolve");

    private static JavaParserEngine jdkResolveEngine() {
        Workspace ws = new Workspace(JDK_RESOLVE, List.of(JDK_RESOLVE), List.of());
        return new JavaParserEngine(ws);
    }

    /**
     * JDK-target method calls resolve to their JDK FQN + signature, with an external declaration site
     * (no project {@code file:line}). On the JVM this exercises {@code ReflectionTypeSolver}; on the
     * native binary the same contract is carried by the host-derived index ({@code jdkResolveSmoke}).
     */
    @Test
    void resolvesJdkMethodCalls() throws Exception {
        JavaParserEngine engine = jdkResolveEngine();
        ParsedUnit unit = engine.parse(JDK_RESOLVE.resolve("JdkCalls.java"));

        // JdkCalls.java:13 `boolean eq = Arrays.equals(a, b);` — `equals` token at column 29.
        Optional<ResolvedRef> arrays = engine.resolveMethodCall(unit, new Position(13, 29));
        assertTrue(arrays.isPresent(), "Arrays.equals should resolve against the JDK");
        assertEquals("java.util.Arrays.equals(byte[], byte[])", arrays.get().signature());
        assertNull(arrays.get().declFile(), "JDK target → external decl (no project file:line)");

        // JdkCalls.java:14 `System.out.println("hi");` — `println` token at column 22.
        Optional<ResolvedRef> println = engine.resolveMethodCall(unit, new Position(14, 22));
        assertTrue(println.isPresent(), "PrintStream.println should resolve against the JDK");
        assertEquals("java.io.PrintStream.println(java.lang.String)", println.get().signature());
    }

    /** A project type's JDK supertype resolves through the hierarchy (the load-bearing-intermediate case). */
    @Test
    void resolvesJdkSupertypeInHierarchy() throws Exception {
        JavaParserEngine engine = jdkResolveEngine();
        ParsedUnit unit = engine.parse(JDK_RESOLVE.resolve("JdkCalls.java"));

        // JdkCalls.java:8 `public class JdkCalls implements Comparable<JdkCalls> {` — `Comparable` at col 34.
        Optional<ResolvedType> comparable = engine.resolveType(unit, new Position(8, 34));
        assertTrue(comparable.isPresent(), "the JDK supertype Comparable should resolve");
        assertEquals("java.lang.Comparable", comparable.get().fqn());
    }
}
