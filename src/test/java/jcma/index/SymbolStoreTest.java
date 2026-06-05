package jcma.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task 03 — the FFM columnar {@link SymbolStore}, the production form of M0 {@code SpikeD.Base}.
 * Round-trips a containment chain (package → class → method/field), a phantom symbol, checks
 * moniker stability across a rewrite, and rejects a bad magic.
 */
class SymbolStoreTest {

    // A small package → class → {method, field} containment chain, plus a phantom (external) symbol.
    private static final String PKG = Moniker.forPackage("com.acme");
    private static final String TYPE = Moniker.forType(PKG, "Greeter");
    private static final String GREET = Moniker.forMethod(TYPE, "greet", List.of());
    private static final String WHO = Moniker.forField(TYPE, "who");
    private static final String PHANTOM = "ext/Lib#run().";

    private static List<Symbol> sample() {
        List<Symbol> s = new ArrayList<>();
        s.add(new Symbol(PKG, SymbolKind.PACKAGE, 0, null, 0, Range.NONE, "com.acme", null));
        s.add(new Symbol(TYPE, SymbolKind.CLASS, 0x1, PKG, 0, new Range(2, 1, 7, 1), "Greeter", null));
        s.add(new Symbol(GREET, SymbolKind.METHOD, 0x1, TYPE, 0, new Range(4, 5, 4, 40),
                "greet", "java.lang.String greet()"));
        s.add(new Symbol(WHO, SymbolKind.FIELD, 0x2, TYPE, 0, new Range(3, 5, 3, 30),
                "who", "java.lang.String"));
        // Phantom: referenced but declared in no file we parse — fileId -1, no range, no enclosing.
        s.add(new Symbol(PHANTOM, SymbolKind.METHOD, 0, null, -1, Range.NONE, "run", null));
        return s;
    }

    private static Symbol byMoniker(SymbolStore store, String moniker) {
        OptionalInt id = store.idOf(moniker);
        assertTrue(id.isPresent(), "moniker should resolve: " + moniker);
        return store.symbol(id.getAsInt());
    }

    @Test
    void roundTripsEveryColumn(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SymbolStore.FILE_NAME);
        SymbolStore.write(p, sample());
        try (SymbolStore store = SymbolStore.load(p)) {
            assertEquals(5, store.size(), "all symbols persisted");
            for (Symbol in : sample()) {
                assertEquals(in, byMoniker(store, in.moniker()),
                        "every column round-trips for " + in.moniker());
            }
        }
    }

    @Test
    void enclosingChainResolvesByMoniker(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SymbolStore.FILE_NAME);
        SymbolStore.write(p, sample());
        try (SymbolStore store = SymbolStore.load(p)) {
            assertEquals(TYPE, byMoniker(store, GREET).enclosingMoniker(), "method's enclosing is its class");
            assertEquals(TYPE, byMoniker(store, WHO).enclosingMoniker(), "field's enclosing is its class");
            assertEquals(PKG, byMoniker(store, TYPE).enclosingMoniker(), "class's enclosing is its package");
            assertEquals(null, byMoniker(store, PKG).enclosingMoniker(), "package is top-level (no enclosing)");
        }
    }

    @Test
    void phantomSymbolPreserved(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SymbolStore.FILE_NAME);
        SymbolStore.write(p, sample());
        try (SymbolStore store = SymbolStore.load(p)) {
            Symbol ph = byMoniker(store, PHANTOM);
            assertTrue(ph.isPhantom(), "fileId -1 marks a phantom");
            assertEquals(-1, ph.fileId());
            assertTrue(ph.range().isNone(), "phantom has no source range");
            assertEquals(null, ph.enclosingMoniker());
        }
    }

    @Test
    void monikerStableAcrossRewriteEvenIfIdsShift(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SymbolStore.FILE_NAME);

        SymbolStore.write(p, sample());
        int idBefore;
        Symbol greetBefore;
        try (SymbolStore store = SymbolStore.load(p)) {
            idBefore = store.idOf(GREET).getAsInt();
            greetBefore = store.symbol(idBefore);
        }

        // Rewrite with an extra symbol whose moniker sorts before greet's — this perturbs id
        // assignment, so greet's int32 id may change while its moniker identity must not.
        List<Symbol> grown = new ArrayList<>(sample());
        String earlier = Moniker.forType(Moniker.forPackage("aaa.first"), "Aaa");
        grown.add(new Symbol(earlier, SymbolKind.CLASS, 0, null, 1, new Range(1, 1, 1, 1), "Aaa", null));
        SymbolStore.write(p, grown);

        try (SymbolStore store = SymbolStore.load(p)) {
            assertEquals(6, store.size());
            int idAfter = store.idOf(GREET).getAsInt();
            assertNotEquals(idBefore, idAfter, "the extra earlier-sorting symbol shifts greet's id");
            assertEquals(greetBefore, store.symbol(idAfter),
                    "greet's data is unchanged; the moniker still resolves to the same symbol");
        }
    }

    @Test
    void idOfUnknownMonikerIsEmpty(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SymbolStore.FILE_NAME);
        SymbolStore.write(p, sample());
        try (SymbolStore store = SymbolStore.load(p)) {
            assertFalse(store.idOf("nope/Nope#nope().").isPresent());
        }
    }

    @Test
    void badMagicRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SymbolStore.FILE_NAME);
        Files.write(p, new byte[128]); // zero bytes: not our magic
        assertThrows(IOException.class, () -> SymbolStore.load(p), "a bad magic must be rejected, not read");
    }
}
