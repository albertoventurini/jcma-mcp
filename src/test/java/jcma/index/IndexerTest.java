package jcma.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Task 06 · P2 — the Tier-1 {@link Indexer} structural extraction (parse-only, no SymbolSolver).
 * Unit-level: index single hand-authored fixtures and assert the symbol set, SCIP-style monikers,
 * kinds, containment (enclosing moniker + {@link EdgeType#CONTAINS} edges) and signatures. The
 * fixtures under {@code fixtures/indexer/com/example/shapes} <em>are</em> the input under test.
 */
class IndexerTest {

    private static final Path SHAPES = Path.of("src/test/resources/fixtures/indexer/com/example/shapes");
    private static final String PKG = "com/example/shapes/";

    private final Indexer indexer = new Indexer();

    @Test
    void extractsClassContainmentKindsAndEdges() throws IOException {
        FileIndex fi = indexer.indexFile(0, SHAPES.resolve("Circle.java"));
        Map<String, Symbol> byMoniker = byMoniker(fi);

        // Top-level class.
        Symbol circle = require(byMoniker, PKG + "Circle#");
        assertEquals(SymbolKind.CLASS, circle.kind());
        assertNull(circle.enclosingMoniker(), "top-level type has no enclosing");
        assertEquals(0, circle.fileId());
        assertEquals("Circle", circle.name());
        assertNotNull(circle.signature(), "a type carries a signature");

        // Members of Circle (containment depth 1).
        assertMember(byMoniker, PKG + "Circle#radius.", SymbolKind.FIELD, PKG + "Circle#");
        assertMember(byMoniker, PKG + "Circle#<init>(double).", SymbolKind.CONSTRUCTOR, PKG + "Circle#");
        Symbol area = assertMember(byMoniker, PKG + "Circle#area().", SymbolKind.METHOD, PKG + "Circle#");
        assertNotNull(area.signature(), "a method carries a signature");
        assertMember(byMoniker, PKG + "Circle#circumference().", SymbolKind.METHOD, PKG + "Circle#");

        // Nested class + its members (containment depth 2).
        assertMember(byMoniker, PKG + "Circle#Builder#", SymbolKind.CLASS, PKG + "Circle#");
        assertMember(byMoniker, PKG + "Circle#Builder#build().", SymbolKind.METHOD, PKG + "Circle#Builder#");
        assertMember(byMoniker, PKG + "Circle#Builder#radius.", SymbolKind.FIELD, PKG + "Circle#Builder#");

        // CONTAINS edges mirror enclosing → enclosed, structural (no occurrence).
        assertContains(fi, PKG + "Circle#", PKG + "Circle#area().");
        assertContains(fi, PKG + "Circle#", PKG + "Circle#Builder#");
        assertContains(fi, PKG + "Circle#Builder#", PKG + "Circle#Builder#build().");
    }

    @Test
    void extractsInterface() throws IOException {
        FileIndex fi = indexer.indexFile(0, SHAPES.resolve("Shape.java"));
        Map<String, Symbol> byMoniker = byMoniker(fi);
        assertEquals(SymbolKind.INTERFACE, require(byMoniker, PKG + "Shape#").kind());
        assertMember(byMoniker, PKG + "Shape#area().", SymbolKind.METHOD, PKG + "Shape#");
    }

    @Test
    void extractsEnumAndConstants() throws IOException {
        FileIndex fi = indexer.indexFile(0, SHAPES.resolve("Day.java"));
        Map<String, Symbol> byMoniker = byMoniker(fi);
        assertEquals(SymbolKind.ENUM, require(byMoniker, PKG + "Day#").kind());
        assertMember(byMoniker, PKG + "Day#MONDAY.", SymbolKind.ENUM_CONSTANT, PKG + "Day#");
        assertMember(byMoniker, PKG + "Day#FRIDAY.", SymbolKind.ENUM_CONSTANT, PKG + "Day#");
        assertMember(byMoniker, PKG + "Day#isFriday().", SymbolKind.METHOD, PKG + "Day#");
    }

    @Test
    void extractsRecordComponentsAsFields() throws IOException {
        FileIndex fi = indexer.indexFile(0, SHAPES.resolve("Point.java"));
        Map<String, Symbol> byMoniker = byMoniker(fi);
        assertEquals(SymbolKind.RECORD, require(byMoniker, PKG + "Point#").kind());
        assertMember(byMoniker, PKG + "Point#x.", SymbolKind.FIELD, PKG + "Point#");
        assertMember(byMoniker, PKG + "Point#y.", SymbolKind.FIELD, PKG + "Point#");
    }

    @Test
    void defaultOverloadTagsSymbolsMain() throws IOException {
        FileIndex fi = indexer.indexFile(0, SHAPES.resolve("Circle.java"));
        for (Symbol s : fi.symbols()) {
            assertEquals(SourceSet.MAIN, s.sourceSet(), "indexFile(id,file) defaults to MAIN: " + s.moniker());
        }
    }

    @Test
    void taggedOverloadCarriesSourceSetOntoEverySymbol() throws IOException {
        FileIndex fi = indexer.indexFile(0, SHAPES.resolve("Circle.java"), SourceSet.TEST);
        assertTrue(fi.symbols().size() > 1, "fixture has several symbols");
        for (Symbol s : fi.symbols()) {
            assertEquals(SourceSet.TEST, s.sourceSet(), "TEST tag propagates to nested members: " + s.moniker());
        }
    }

    @Test
    void symbolsCarryTheirDeclarationRange() throws IOException {
        FileIndex fi = indexer.indexFile(3, SHAPES.resolve("Circle.java"));
        Symbol circle = require(byMoniker(fi), PKG + "Circle#");
        assertEquals(3, circle.fileId(), "fileId is the one passed in");
        assertTrue(circle.range().startLine() >= 1, "a real declaration range");
        assertTrue(circle.range().endLine() >= circle.range().startLine());
    }

    @Test
    void tier1EdgesAreNodeDerived() throws IOException {
        // Tier-1 emits only CONTAINS, node-derived (enclosingMoniker -> moniker) — what lets
        // EdgeResolver rebuild a file's Tier-1 base with no re-parse. Add a Tier-1 edge type and this
        // fails by design: pick its recovery rung (node-derived -> reconstruct; non-node data -> read
        // back from the store; never re-parse) and update tier1Slice first.
        FileIndex fi = indexer.indexFile(0, SHAPES.resolve("Circle.java"));
        for (MonikerEdge e : fi.edges()) {
            assertEquals(EdgeType.CONTAINS, e.type(), "Tier-1 emits only CONTAINS: " + e);
            assertTrue(e.occurrence().isNone(), "a node-derived edge carries no occurrence: " + e);
        }
        // The reconstruction EdgeResolver.tier1Slice performs: every edge derived from the symbols alone.
        Set<MonikerEdge> reconstructed = new HashSet<>();
        for (Symbol s : fi.symbols()) {
            if (s.enclosingMoniker() != null) {
                reconstructed.add(new MonikerEdge(s.enclosingMoniker(), s.moniker(),
                        EdgeType.CONTAINS, Occurrence.NONE));
            }
        }
        assertEquals(new HashSet<>(fi.edges()), reconstructed,
                "every Tier-1 edge reconstructs from the symbols — no edge carries non-node data");
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Symbol> byMoniker(FileIndex fi) {
        Map<String, Symbol> m = new HashMap<>();
        for (Symbol s : fi.symbols()) {
            m.put(s.moniker(), s);
        }
        return m;
    }

    private static Symbol require(Map<String, Symbol> byMoniker, String moniker) {
        Symbol s = byMoniker.get(moniker);
        assertNotNull(s, "expected a symbol with moniker " + moniker);
        return s;
    }

    private static Symbol assertMember(Map<String, Symbol> byMoniker, String moniker, SymbolKind kind,
            String enclosing) {
        Symbol s = require(byMoniker, moniker);
        assertEquals(kind, s.kind(), "kind of " + moniker);
        assertEquals(enclosing, s.enclosingMoniker(), "enclosing of " + moniker);
        return s;
    }

    private static void assertContains(FileIndex fi, String container, String contained) {
        List<MonikerEdge> hits = fi.edges().stream()
                .filter(e -> e.type() == EdgeType.CONTAINS
                        && e.src().equals(container) && e.dst().equals(contained))
                .toList();
        assertEquals(1, hits.size(), "one CONTAINS edge " + container + " -> " + contained);
        assertTrue(hits.get(0).occurrence().isNone(), "a CONTAINS edge is structural (no occurrence)");
    }
}
