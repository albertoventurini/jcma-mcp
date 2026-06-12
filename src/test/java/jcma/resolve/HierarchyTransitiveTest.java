package jcma.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.IndexFixture;
import jcma.index.CompactionPolicy;
import jcma.index.EdgeType;
import jcma.index.FileIndex;
import jcma.index.LsmStore;
import jcma.index.MonikerEdge;
import jcma.index.Occurrence;
import jcma.index.Range;
import jcma.index.Symbol;
import jcma.index.SymbolKind;
import jcma.obs.Metrics;
import jcma.engine.JavaParserEngine;
import jcma.workspace.FileTable;
import jcma.workspace.Workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * M2 task-05 (red-first) — the <b>transitive</b> type-hierarchy walk ({@code find_supertypes} /
 * {@code find_subtypes} primitives) over the {@code resolve/hierarchy-chain} fixture (package
 * {@code app}): {@code interface A}, {@code class B implements A}, {@code class C extends B},
 * {@code class D extends C}, with {@code m()} overridden down the chain.
 *
 * <p>Unlike the direct {@code supertypes}/{@code subtypes} primitives (one hop), the transitive walk
 * returns the whole closure — {@code subtypes(A)} = {@code {B, C, D}}, not just {@code B} — each node
 * carrying its shortest-hop {@code depth} and the {@code via} edge that reached it. Resolution is fully
 * lazy: the test does <b>not</b> pre-warm, so the walk must warm each node's neighbourhood on demand
 * (a subtype file references its supertype's simple name, so it is a candidate at each level).
 *
 * <p>The cycle + node-cap cases use a hand-built graph (source cannot express an inheritance cycle, and
 * a low cap avoids a 500-node fixture): edges are injected straight into a heap store, then walked.
 */
class HierarchyTransitiveTest {

    private static final Path CHAIN = Path.of("src/test/resources/fixtures/resolve/hierarchy-chain");

    @Test
    void subtypesTransitiveReturnsTheWholeClosureWithDepthAndVia(@TempDir Path indexDir) throws Exception {
        IndexFixture.build(CHAIN, indexDir);
        try (EdgeResolver resolver = open(indexDir)) {
            Hierarchy.Result result = resolver.subtypesTransitive(type(resolver, "A", "app/A#"));
            assertFalse(result.truncated(), "the tiny chain is well under the node cap");
            Map<String, HierarchyNode> byMoniker = byMoniker(result.nodes());

            assertEquals(3, byMoniker.size(), "transitive subtypes of A are {B, C, D}, not just the direct B");
            assertNode(byMoniker, "app/B#", 1, EdgeType.IMPLEMENTS);
            assertNode(byMoniker, "app/C#", 2, EdgeType.EXTENDS);
            assertNode(byMoniker, "app/D#", 3, EdgeType.EXTENDS);
        }
    }

    @Test
    void supertypesTransitiveWalksUpTheWholeChain(@TempDir Path indexDir) throws Exception {
        IndexFixture.build(CHAIN, indexDir);
        try (EdgeResolver resolver = open(indexDir)) {
            Hierarchy.Result result = resolver.supertypesTransitive(type(resolver, "D", "app/D#"));
            Map<String, HierarchyNode> byMoniker = byMoniker(result.nodes());

            assertEquals(3, byMoniker.size(), "transitive supertypes of D are {C, B, A}");
            assertNode(byMoniker, "app/C#", 1, EdgeType.EXTENDS);
            assertNode(byMoniker, "app/B#", 2, EdgeType.EXTENDS);
            assertNode(byMoniker, "app/A#", 3, EdgeType.IMPLEMENTS);
        }
    }

    @Test
    void methodOverridesWalkTransitivelyDownTheChain(@TempDir Path indexDir) throws Exception {
        IndexFixture.build(CHAIN, indexDir);
        try (EdgeResolver resolver = open(indexDir)) {
            Hierarchy.Result result = resolver.subtypesTransitive(method(resolver, "app/A#m()."));
            Map<String, HierarchyNode> byMoniker = byMoniker(result.nodes());

            assertTrue(byMoniker.containsKey("app/B#m()."), "B#m overrides A#m: " + byMoniker.keySet());
            assertTrue(byMoniker.containsKey("app/C#m()."), "C#m overrides (transitively) A#m: " + byMoniker.keySet());
            assertTrue(byMoniker.containsKey("app/D#m()."), "D#m overrides (transitively) A#m: " + byMoniker.keySet());
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aGraphCycleTerminatesViaTheVisitedSet(@TempDir Path indexDir) throws Exception {
        // X2 -> X1, X3 -> X2, X1 -> X3 (EXTENDS): a cycle source could never declare. The walk must
        // terminate (the visited set), returning each other node once.
        try (LsmStore store = LsmStore.open(indexDir, CompactionPolicy.manual(), Metrics.noop())) {
            store.applyEdit(new FileIndex(0,
                    List.of(klass("app/X1#", "X1"), klass("app/X2#", "X2"), klass("app/X3#", "X3")),
                    List.of(extendsEdge("app/X2#", "app/X1#"),
                            extendsEdge("app/X3#", "app/X2#"),
                            extendsEdge("app/X1#", "app/X3#"))));
            try (EdgeResolver resolver = over(store)) {
                Hierarchy.Result result = resolver.subtypesTransitive(store.symbol("app/X1#").orElseThrow());
                Map<String, HierarchyNode> byMoniker = byMoniker(result.nodes());
                assertEquals(2, byMoniker.size(), "the two other nodes, each once: " + byMoniker.keySet());
                assertTrue(byMoniker.containsKey("app/X2#") && byMoniker.containsKey("app/X3#"),
                        byMoniker.keySet().toString());
            }
        }
    }

    @Test
    void nodeCapTruncatesAndMarksTheResult(@TempDir Path indexDir) throws Exception {
        // A wide fan-out (8 direct subtypes) walked under a deliberately low cap of 3 → the result is
        // truncated and flagged, never silently short.
        List<Symbol> symbols = new ArrayList<>(List.of(klass("app/W#", "W")));
        List<MonikerEdge> edges = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            symbols.add(klass("app/S" + i + "#", "S" + i));
            edges.add(extendsEdge("app/S" + i + "#", "app/W#"));
        }
        try (LsmStore store = LsmStore.open(indexDir, CompactionPolicy.manual(), Metrics.noop())) {
            store.applyEdit(new FileIndex(0, symbols, edges));
            try (EdgeResolver resolver = over(store)) {
                Hierarchy.Result result = resolver.subtypesTransitive(store.symbol("app/W#").orElseThrow(), 3);
                assertTrue(result.truncated(), "the cap fired");
                assertEquals(3, result.nodes().size(), "capped to the node limit");
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static EdgeResolver open(Path indexDir) throws Exception {
        return EdgeResolver.open(indexDir, Workspace.ofSourceRoot(CHAIN), Metrics.create());
    }

    /** A bare resolver over a hand-built heap store (no real files → warming is a no-op, edges stand). */
    private static EdgeResolver over(LsmStore store) {
        return EdgeResolver.over(store, null, emptyTable(),
                new JavaParserEngine(Workspace.ofSourceRoot(CHAIN)),
                CHAIN.toAbsolutePath().normalize(), Metrics.noop());
    }

    private static FileTable emptyTable() {
        try {
            return FileTable.load(Path.of(System.getProperty("java.io.tmpdir"), "jcma-no-such-" + System.nanoTime()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Symbol type(EdgeResolver resolver, String name, String moniker) {
        return resolver.declarations(name).stream()
                .filter(s -> moniker.equals(s.moniker()))
                .findFirst().orElseThrow(() -> new AssertionError(moniker + " not indexed"));
    }

    private static Symbol method(EdgeResolver resolver, String moniker) {
        String simple = moniker.substring(moniker.indexOf('#') + 1, moniker.indexOf('('));
        return resolver.declarations(simple).stream()
                .filter(s -> moniker.equals(s.moniker()))
                .findFirst().orElseThrow(() -> new AssertionError(moniker + " not indexed"));
    }

    private static Map<String, HierarchyNode> byMoniker(List<HierarchyNode> nodes) {
        Map<String, HierarchyNode> out = new HashMap<>();
        for (HierarchyNode n : nodes) {
            out.put(n.moniker(), n);
        }
        return out;
    }

    private static void assertNode(Map<String, HierarchyNode> byMoniker, String moniker, int depth, EdgeType via) {
        HierarchyNode n = byMoniker.get(moniker);
        assertTrue(n != null, moniker + " in closure: " + byMoniker.keySet());
        assertEquals(depth, n.depth(), moniker + " shortest-hop depth");
        assertEquals(via, n.via(), moniker + " reached via");
    }

    private static Symbol klass(String moniker, String name) {
        return new Symbol(moniker, SymbolKind.CLASS, 0, null, 0, Range.NONE, name, name);
    }

    private static MonikerEdge extendsEdge(String src, String dst) {
        return new MonikerEdge(src, dst, EdgeType.EXTENDS, Occurrence.NONE);
    }
}
