package jcma.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.cli.Main;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M2 task-09 — the classpath cache (<code>&lt;indexDir&gt;/classpath.txt</code>). The build-tool
 * subprocess moves into the explicit {@code jcma index} step, which resolves the classpath once and
 * persists it; every later session ({@code repl}/{@code serve}/one-shot query) reads that file instead
 * of spawning {@code mvn}/{@code gradle}. These tests pin the contract: index writes the cache, a
 * session reads it (short-circuiting any subprocess), a cache miss degrades to empty, and a re-index
 * refreshes it. No live build tool is invoked — temp repos carry no resolvable dependencies, and the
 * "reads the cache" test pre-seeds a <em>sentinel</em> jar path no live resolve could produce.
 */
class ClasspathCacheTest {

    private record Run(int exit, String out, String err) {}

    private static Run dispatch(String... args) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = Main.run(args, new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        return new Run(exit, outBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8));
    }

    /** A minimal one-file source tree under {@code repo} (no build markers → no subprocess on resolve). */
    private static void writeSource(Path repo) throws IOException {
        Path src = Files.createDirectories(repo.resolve("src/main/java/app"));
        Files.writeString(src.resolve("App.java"), "package app;\npublic class App {}\n");
    }

    // 1. Index writes the cache file under the index dir.
    @Test
    void indexWritesTheClasspathCache(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        writeSource(repo);

        Run idx = dispatch("index", "-C", repo.toString(), indexDir.toString());
        assertEquals(0, idx.exit(), "index should exit 0: " + idx.out() + idx.err());

        assertTrue(Files.isRegularFile(IndexLayout.classpathCache(indexDir)),
                "jcma index persists the resolved classpath to <indexDir>/classpath.txt");
    }

    // 2. A session reads the cache and never spawns a subprocess: a pom.xml would make a cache-miss
    //    run `mvn`, but a pre-seeded cache holding a sentinel jar short-circuits before that.
    @Test
    void discoverReadsCacheAndSkipsSubprocess(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        Files.writeString(repo.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>x</groupId><artifactId>x</artifactId><version>1</version>
                </project>
                """);
        writeSource(repo);
        // A path no live mvn/gradle resolve could ever produce — proves the cache, not a subprocess.
        Path sentinel = repo.resolve("sentinel-only-in-cache.jar");
        Files.writeString(IndexLayout.classpathCache(indexDir), sentinel.toString());

        Workspace ws = Workspace.discover(repo, indexDir);

        assertEquals(List.of(sentinel), ws.classpathJars(),
                "discover(repo, indexDir) returns exactly the cached sentinel — the cache short-circuited mvn");
    }

    // 3. Cache miss with no build markers → empty classpath, no error, no subprocess.
    @Test
    void cacheMissFallsBackToEmpty(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        writeSource(repo); // no pom, no gradle marker, no cache file

        Workspace ws = Workspace.discover(repo, indexDir);

        assertTrue(ws.classpathJars().isEmpty(),
                "no build markers and no cache → empty classpath (source→source resolution still works)");
    }

    // 4. A re-index re-resolves and overwrites a stale cache (classpath freshness ties to the index).
    @Test
    void reindexRefreshesTheCache(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        writeSource(repo);
        Path cache = IndexLayout.classpathCache(indexDir);
        Files.createDirectories(indexDir);
        Files.writeString(cache, repo.resolve("stale-leftover.jar").toString());

        Run idx = dispatch("index", "-C", repo.toString(), indexDir.toString());
        assertEquals(0, idx.exit(), "index should exit 0: " + idx.out() + idx.err());

        assertTrue(Files.isRegularFile(cache), "the cache survives the re-index");
        assertFalse(Files.readString(cache).contains("stale-leftover.jar"),
                "the re-index overwrote the stale cache — content is tied to the index lifecycle");
    }
}
