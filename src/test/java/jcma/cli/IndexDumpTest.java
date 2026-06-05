package jcma.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import jcma.index.Moniker;
import jcma.index.Range;
import jcma.index.Symbol;
import jcma.index.SymbolKind;
import jcma.index.SymbolStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task 03 — the {@code jcma index-dump --symbols <indexDir>} manual-check surface, exercised on the
 * JVM through the same {@code Main.run} dispatch the native binary uses.
 */
class IndexDumpTest {

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
    void dumpListsSymbolsAndMonikers(@TempDir Path dir) throws Exception {
        String type = Moniker.forType(Moniker.forPackage("com.acme"), "Greeter");
        String greet = Moniker.forMethod(type, "greet", List.of());
        SymbolStore.write(dir.resolve(SymbolStore.FILE_NAME), List.of(
                new Symbol(type, SymbolKind.CLASS, 0, null, 0, new Range(1, 1, 3, 1), "Greeter", null),
                new Symbol(greet, SymbolKind.METHOD, 0, type, 0, new Range(2, 3, 2, 20),
                        "greet", "java.lang.String greet()")));

        Run r = dispatch("index-dump", "--symbols", dir.toString());
        assertEquals(0, r.exit(), "dump should exit 0: " + r.out() + r.err());
        assertTrue(r.out().contains(greet), "dump lists monikers: " + r.out());
        assertTrue(r.out().contains("METHOD"), "dump lists kinds: " + r.out());
        assertTrue(r.out().contains("Greeter"), "dump lists names: " + r.out());
    }

    @Test
    void dumpMissingStoreExitsNonZero(@TempDir Path dir) {
        Run r = dispatch("index-dump", "--symbols", dir.toString());
        assertNotEquals(0, r.exit(), "a missing store should fail, not print nothing and succeed");
    }

    @Test
    void dumpUsageErrorWithoutSymbolsFlag(@TempDir Path dir) {
        Run r = dispatch("index-dump", dir.toString());
        assertEquals(2, r.exit(), "missing --symbols flag is a usage error");
    }
}
