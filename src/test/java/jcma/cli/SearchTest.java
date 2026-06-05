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
import jcma.index.TrigramIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task 05 — the {@code jcma search <indexDir> <query>} surface, exercised through the same
 * {@code Main.run} dispatch the native binary uses. {@code search} is a <em>pure reader</em>: the
 * test pre-builds {@code symbols.seg} + {@code trigrams.seg} (the pipeline's job in task-07), and
 * {@code search} reads them and prints ranked symbols.
 */
class SearchTest {

    private record Run(int exit, String out, String err) {}

    private static Run dispatch(String... args) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);
        int exit = Main.run(args, out, err);
        return new Run(exit, outBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8));
    }

    // Build a small index dir: a Greeter class with greet()/render() methods + a who field.
    private static void buildIndex(Path dir) throws Exception {
        String type = Moniker.forType(Moniker.forPackage("com.acme"), "Greeter");
        String greet = Moniker.forMethod(type, "greet", List.of());
        String render = Moniker.forMethod(type, "render", List.of());
        String who = Moniker.forField(type, "who");
        SymbolStore.write(dir.resolve(SymbolStore.FILE_NAME), List.of(
                new Symbol(type, SymbolKind.CLASS, 0, null, 0, new Range(1, 1, 9, 1), "Greeter", null),
                new Symbol(greet, SymbolKind.METHOD, 0, type, 0, new Range(2, 3, 4, 3),
                        "greet", "java.lang.String greet()"),
                new Symbol(render, SymbolKind.METHOD, 0, type, 0, new Range(5, 3, 7, 3), "render", null),
                new Symbol(who, SymbolKind.FIELD, 0, type, 0, new Range(8, 3, 8, 20), "who", "java.lang.String")));
        try (SymbolStore store = SymbolStore.load(dir.resolve(SymbolStore.FILE_NAME))) {
            TrigramIndex.write(dir.resolve(TrigramIndex.FILE_NAME), TrigramIndex.entriesOf(store));
        }
    }

    @Test
    void searchPrintsRankedMatchingSymbols(@TempDir Path dir) throws Exception {
        buildIndex(dir);
        Run r = dispatch("search", dir.toString(), "reet");
        assertEquals(0, r.exit(), "search should exit 0: " + r.out() + r.err());
        assertTrue(r.out().contains("greet"), "lists the matching symbol name: " + r.out());
        assertTrue(r.out().contains("METHOD"), "lists the kind: " + r.out());
        assertTrue(r.out().contains("java.lang.String greet()"), "lists the signature: " + r.out());
    }

    @Test
    void searchReportsNoMatchButStillSucceeds(@TempDir Path dir) throws Exception {
        buildIndex(dir);
        Run r = dispatch("search", dir.toString(), "zzznotpresent");
        assertEquals(0, r.exit(), "an empty result is still a successful query, not an error");
        assertTrue(r.out().contains("0"), "reports zero matches: " + r.out());
    }

    @Test
    void searchMissingIndexExitsNonZero(@TempDir Path dir) {
        Run r = dispatch("search", dir.toString(), "greet");
        assertNotEquals(0, r.exit(), "a missing index should fail, not silently print nothing");
    }

    @Test
    void searchUsageErrorWithoutQuery(@TempDir Path dir) {
        Run r = dispatch("search", dir.toString());
        assertEquals(2, r.exit(), "missing <query> is a usage error");
    }
}
