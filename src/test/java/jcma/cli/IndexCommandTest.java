package jcma.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task 06 · P4 — {@code jcma index <repo> [indexDir]}, the cold full-index command, through the same
 * {@code Main.run} dispatch the native binary uses. Indexes a controlled package, then verifies the
 * persisted index is queryable by {@code search} and {@code stats} (PRD §10 M1 verification).
 */
class IndexCommandTest {

    private static final Path REPO = Path.of("src/test/resources/fixtures/indexer");

    private record Run(int exit, String out, String err) {}

    private static Run dispatch(String... args) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = Main.run(args, new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        return new Run(exit, outBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void indexBuildsAQueryablePersistedIndex(@TempDir Path indexDir) {
        Run idx = dispatch("index", REPO.toString(), indexDir.toString());
        assertEquals(0, idx.exit(), "index should exit 0: " + idx.out() + idx.err());
        assertTrue(idx.out().contains("symbols"), "reports a symbol count: " + idx.out());

        // The persisted index is real: search and stats read it back.
        Run search = dispatch("search", indexDir.toString(), "Circle");
        assertEquals(0, search.exit(), search.err());
        assertTrue(search.out().contains("Circle"), "search finds an indexed symbol: " + search.out());

        Run stats = dispatch("stats", indexDir.toString());
        assertEquals(0, stats.exit(), stats.err());
        assertTrue(stats.out().contains("base:"), stats.out());
    }

    @Test
    void indexDefaultsToDotJcmaUnderTheRepo(@TempDir Path repoCopy) throws Exception {
        // Copy the fixture into a temp repo so the default <repo>/.jcma index dir lands in temp.
        Files.createDirectories(repoCopy.resolve("com/example"));
        Files.copy(REPO.resolve("com/example/shapes/Shape.java"),
                repoCopy.resolve("Shape.java"));
        Run idx = dispatch("index", repoCopy.toString());
        assertEquals(0, idx.exit(), idx.err());
        assertTrue(Files.isDirectory(repoCopy.resolve(".jcma")), "default index dir <repo>/.jcma created");
    }

    @Test
    void usageWhenNoRepo() {
        assertEquals(2, dispatch("index").exit());
    }
}
