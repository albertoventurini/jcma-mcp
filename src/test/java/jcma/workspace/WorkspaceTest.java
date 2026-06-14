package jcma.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.index.SourceRoot;
import jcma.index.SourceSet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task-02 — {@link Workspace} discovery: cp.txt parsing (the {@code SolverSetup} read loop) and
 * pom source-directory discovery. Pure file-parsing, no live mvn.
 */
class WorkspaceTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures/engine");

    // ---------------------------------------------------------------- cp.txt parsing

    @Test
    void readClasspathJarsSplitsAndKeepsOnlyJars(@TempDir Path dir) throws Exception {
        Path a = dir.resolve("a.jar");
        Path b = dir.resolve("b.jar");
        Path classesDir = dir.resolve("target/classes");
        // pathSeparator-joined, mixing jars with a non-jar classes dir that must be dropped.
        String cp = String.join(File.pathSeparator,
                a.toString(), classesDir.toString(), b.toString());
        Path cpFile = dir.resolve("cp.txt");
        Files.writeString(cpFile, cp);

        List<Path> jars = Workspace.readClasspathJars(cpFile);

        assertEquals(List.of(a, b), jars, "only .jar entries, in order; the classes dir dropped");
    }

    @Test
    void readClasspathJarsToleratesMissingFile(@TempDir Path dir) {
        List<Path> jars = Workspace.readClasspathJars(dir.resolve("does-not-exist.txt"));
        assertTrue(jars.isEmpty(), "a missing cp.txt yields an empty classpath, not an error");
    }

    @Test
    void readClasspathJarsToleratesEmptyFile(@TempDir Path dir) throws Exception {
        Path cpFile = dir.resolve("cp.txt");
        Files.writeString(cpFile, "  \n");
        assertTrue(Workspace.readClasspathJars(cpFile).isEmpty(), "blank cp.txt yields empty");
    }

    // ---------------------------------------------------------------- pom source-dir discovery

    @Test
    void discoverSourceRootsReadsExplicitSourceDirectory() {
        Path root = FIXTURES.resolve("ws-custom-srcdir");
        List<Path> roots = Workspace.discoverSourceRoots(root);
        assertEquals(List.of(root.resolve("java")), roots,
                "<sourceDirectory>java</sourceDirectory> overrides the default layout");
    }

    @Test
    void discoverSourceRootsDefaultsToStandardLayout() {
        Path root = FIXTURES.resolve("ws-standard");
        List<Path> roots = Workspace.discoverSourceRoots(root);
        assertEquals(List.of(root.resolve("src/main/java")), roots,
                "no <sourceDirectory> → Maven standard layout src/main/java");
    }

    // ---------------------------------------------------------------- source-set tagging

    @Test
    void discoverSourceSetsTagsTestSourceDirectory() {
        Path root = FIXTURES.resolve("ws-testsrcdir");
        List<SourceRoot> roots = Workspace.discoverSourceSets(root);
        assertEquals(
                List.of(new SourceRoot(root.resolve("java"), SourceSet.MAIN),
                        new SourceRoot(root.resolve("javatest"), SourceSet.TEST)),
                roots,
                "<sourceDirectory> → MAIN, <testSourceDirectory> → TEST");
    }

    @Test
    void discoverSourceSetsAddsDefaultTestRootWhenPresent() {
        Path root = FIXTURES.resolve("ws-with-tests");
        List<SourceRoot> roots = Workspace.discoverSourceSets(root);
        assertEquals(
                List.of(new SourceRoot(root.resolve("src/main/java"), SourceSet.MAIN),
                        new SourceRoot(root.resolve("src/test/java"), SourceSet.TEST)),
                roots,
                "standard-layout pom with src/test/java present → main MAIN + test TEST");
    }

    @Test
    void discoverSourceSetsUsesConventionWithoutBuildTool() {
        Path root = FIXTURES.resolve("ws-gradle-like");
        List<SourceRoot> roots = Workspace.discoverSourceSets(root);
        assertEquals(
                List.of(new SourceRoot(root.resolve("src/main/java"), SourceSet.MAIN),
                        new SourceRoot(root.resolve("src/test/java"), SourceSet.TEST)),
                roots,
                "no pom but standard src/main|test/java present → tagged by convention");
    }

    @Test
    void discoverSourceSetsEmptyForAdHocLayout() {
        Path root = FIXTURES.resolve("ws-adhoc");
        assertTrue(Workspace.discoverSourceSets(root).isEmpty(),
                "no build model and no standard layout → nothing discovered (caller falls back)");
    }

    @Test
    void discoverSourceSetsFindsPerModuleRoots(@TempDir Path root) throws Exception {
        // A multi-module repo (Spring-style): no root-level src/main/java; sources live in per-module
        // roots. A build-output dir carrying its own src/main/java must be pruned, not indexed.
        Files.createDirectories(root.resolve("moduleA/src/main/java"));
        Files.createDirectories(root.resolve("moduleA/src/test/java"));
        Files.createDirectories(root.resolve("moduleB/src/main/java"));
        Files.createDirectories(root.resolve("build/src/main/java")); // decoy: build output

        List<SourceRoot> roots = Workspace.discoverSourceSets(root);

        assertEquals(
                List.of(new SourceRoot(root.resolve("moduleA/src/main/java"), SourceSet.MAIN),
                        new SourceRoot(root.resolve("moduleA/src/test/java"), SourceSet.TEST),
                        new SourceRoot(root.resolve("moduleB/src/main/java"), SourceSet.MAIN)),
                roots,
                "per-module src/main|test/java roots, path-sorted, with the build/ decoy pruned");
    }

    // ---------------------------------------------------------------- Gradle root discovery

    @Test
    void discoverTreatsGradleKtsAsProjectRootAndHonorsCache(@TempDir Path dir, @TempDir Path indexDir)
            throws Exception {
        Path jar = gradleProjectWithCachedClasspath(dir, indexDir, "build.gradle.kts");
        Workspace ws = Workspace.discover(dir.resolve("src/main/java/app/App.java"), indexDir);
        assertEquals(dir, ws.projectRoot(),
                "a build.gradle.kts dir is recognized as the project root (no pom required)");
        assertEquals(List.of(jar), ws.classpathJars(),
                "the index-dir classpath cache populates the classpath");
    }

    @Test
    void discoverRecognizesGroovyBuildFile(@TempDir Path dir, @TempDir Path indexDir) throws Exception {
        Path jar = gradleProjectWithCachedClasspath(dir, indexDir, "build.gradle");
        Workspace ws = Workspace.discover(dir.resolve("src/main/java/app/App.java"), indexDir);
        assertEquals(List.of(jar), ws.classpathJars(),
                "build.gradle (Groovy DSL) marks a Gradle root too");
    }

    @Test
    void discoverRecognizesSettingsGradleOnly(@TempDir Path dir, @TempDir Path indexDir) throws Exception {
        Path jar = gradleProjectWithCachedClasspath(dir, indexDir, "settings.gradle.kts");
        Workspace ws = Workspace.discover(dir.resolve("src/main/java/app/App.java"), indexDir);
        assertEquals(List.of(jar), ws.classpathJars(),
                "a settings.gradle.kts (no build.gradle) still marks a Gradle root");
    }

    /**
     * Build a minimal Gradle project under {@code dir}: the given build-file marker and a standard
     * source tree, plus a pre-seeded {@link IndexLayout#classpathCache index-dir classpath cache}
     * referencing one jar. Returns that jar's path. The seeded cache keeps these tests off the live
     * {@code gradle} subprocess (the cache short-circuits resolve), so they stay deterministic.
     */
    private static Path gradleProjectWithCachedClasspath(Path dir, Path indexDir, String marker)
            throws IOException {
        Files.writeString(dir.resolve(marker), "// gradle\n");
        Path src = Files.createDirectories(dir.resolve("src/main/java/app"));
        Files.writeString(src.resolve("App.java"), "package app;\nclass App {}\n");
        Path jar = dir.resolve("dep.jar");
        Files.writeString(IndexLayout.classpathCache(indexDir), jar.toString());
        return jar;
    }
}
