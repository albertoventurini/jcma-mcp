package jcma.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import jcma.index.CompactionPolicy;
import jcma.index.FileIndex;
import jcma.index.LsmStore;
import jcma.index.Range;
import jcma.index.Symbol;
import jcma.index.SymbolKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task 06 · P4 — {@code jcma compact <indexDir>}: fold a pending overlay into a fresh base. Builds
 * an index dir with uncompacted overlay edits, runs the command, and asserts the overlay is gone.
 */
class CompactCommandTest {

    private record Run(int exit, String out, String err) {}

    private static Run dispatch(String... args) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = Main.run(args, new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        return new Run(exit, outBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8));
    }

    private static FileIndex file(int id) {
        return new FileIndex(id, List.of(new Symbol("m" + id + ".", SymbolKind.METHOD, 0, null, id,
                new Range(1, 1, 1, 1), "m" + id, null)), List.of());
    }

    @Test
    void compactFoldsThePendingOverlay(@TempDir Path indexDir) throws IOException {
        try (LsmStore store = LsmStore.open(indexDir, CompactionPolicy.manual())) {
            store.applyEdit(file(0));
            store.applyEdit(file(1));
            assertEquals(2, store.overlayFileCount(), "two files pending, uncompacted");
        }

        Run r = dispatch("compact", indexDir.toString());
        assertEquals(0, r.exit(), "compact should exit 0: " + r.out() + r.err());

        try (LsmStore reopened = LsmStore.open(indexDir, CompactionPolicy.manual())) {
            assertTrue(reopened.isCompacted(), "overlay folded into the base after compact");
            assertTrue(reopened.contains("m0."), "data preserved across compaction");
            assertTrue(reopened.contains("m1."));
        }
    }

    @Test
    void usageWhenNoIndexDir() {
        assertEquals(2, dispatch("compact").exit());
    }
}
