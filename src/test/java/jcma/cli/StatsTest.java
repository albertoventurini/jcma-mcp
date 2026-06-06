package jcma.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import jcma.index.CompactionPolicy;
import jcma.index.Indexer;
import jcma.index.LsmStore;
import jcma.index.SourceRoot;
import jcma.index.SourceSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task 06 · P3 — the {@code jcma stats <indexDir>} surface, through the same {@code Main.run}
 * dispatch the native binary uses. Builds an index dir directly (the {@code index} CLI is P4), then
 * asserts {@code stats} reports the base/overlay state.
 */
class StatsTest {

    private record Run(int exit, String out, String err) {}

    private static Run dispatch(String... args) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);
        int exit = Main.run(args, out, err);
        return new Run(exit, outBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void reportsBaseAndOverlayState(@TempDir Path dir) throws Exception {
        try (LsmStore store = LsmStore.open(dir, CompactionPolicy.manual())) {
            new Indexer().indexRepo(
                    List.of(new SourceRoot(Path.of("src/test/resources/fixtures/indexer"), SourceSet.MAIN)), store);
        }
        Run r = dispatch("stats", dir.toString());
        assertEquals(0, r.exit(), "stats should exit 0: " + r.out() + r.err());
        assertTrue(r.out().contains("base:"), "reports base size: " + r.out());
        assertTrue(r.out().contains("overlay log:"), "reports overlay log: " + r.out());
        assertTrue(r.out().contains("ratio"), "reports overlay/base ratio: " + r.out());
    }

    @Test
    void usageWhenWrongArgs() {
        assertEquals(2, dispatch("stats").exit());
    }

    @Test
    void failsCleanlyOnMissingIndex(@TempDir Path dir) {
        Run r = dispatch("stats", dir.resolve("nope").toString());
        assertEquals(1, r.exit());
        assertTrue(r.err().contains("no index"), r.err());
    }
}
