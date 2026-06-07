package jcma.resolve;

import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.cli.Main;
import jcma.index.EdgeType;
import jcma.index.LsmStore;
import jcma.index.MonikerEdge;
import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.workspace.Workspace;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task-11a (red-first) — the type-hierarchy edges {@code EXTENDS}/{@code IMPLEMENTS}/{@code OVERRIDES}
 * over the tiny {@code resolve/hierarchy} fixture (package {@code app}):
 * <ul>
 *   <li>{@code Sub extends Base implements Iface}, overriding {@code Base.run()} and implementing
 *       {@code Iface.ping()} — the EXTENDS / IMPLEMENTS / OVERRIDES (incl. interface-impl) cases;</li>
 *   <li>{@code Ext extends java.util.ArrayList} — the external supertype → <b>phantom</b> {@code dst};</li>
 * </ul>
 *
 * <p>Resolution is Tier-2 lazy: a {@code find_references} on the simple name {@code Base} (used in both
 * {@code Sub} and {@code Ext}) warms those files, so resolving them must emit the hierarchy edges as a
 * side effect — alongside the reference edges, in the same {@code applyEdit}. The assertions then read
 * the edges straight back from the persisted {@link LsmStore} (the {@code find_subtypes} primitive is
 * literally {@code store.rev(type)}), which also proves they survive a reopen and a compaction.
 *
 * <p><b>Edge-type convention (decided with the user):</b> by keyword — {@code extends}→EXTENDS,
 * {@code implements}→IMPLEMENTS. OVERRIDES is <b>direct</b> and includes interface-method
 * implementation (both are facts the source states).
 */
class HierarchyEdgesTest {

    private static final Path HIER = Path.of("src/test/resources/fixtures/resolve/hierarchy");

    @Test
    void extendsImplementsOverridesEdgesPersistWithCorrectMonikers(@TempDir Path indexDir) throws Exception {
        index(HIER, indexDir);
        warm(indexDir);
        try (LsmStore store = LsmStore.open(indexDir)) {
            assertTrue(hasDst(store.fwd("app/Sub#"), EdgeType.EXTENDS, "app/Base#"),
                    "Sub --EXTENDS--> Base");
            assertTrue(hasDst(store.fwd("app/Sub#"), EdgeType.IMPLEMENTS, "app/Iface#"),
                    "Sub --IMPLEMENTS--> Iface");
            assertTrue(hasDst(store.fwd("app/Sub#run()."), EdgeType.OVERRIDES, "app/Base#run()."),
                    "Sub#run --OVERRIDES--> Base#run (superclass override)");
            assertTrue(hasDst(store.fwd("app/Sub#ping()."), EdgeType.OVERRIDES, "app/Iface#ping()."),
                    "Sub#ping --OVERRIDES--> Iface#ping (interface-method implementation)");
        }
    }

    @Test
    void externalSupertypeYieldsPhantomDst(@TempDir Path indexDir) throws Exception {
        index(HIER, indexDir);
        warm(indexDir);
        try (LsmStore store = LsmStore.open(indexDir)) {
            MonikerEdge ext = store.fwd("app/Ext#").stream()
                    .filter(e -> e.type() == EdgeType.EXTENDS)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Ext should have an EXTENDS edge"));
            assertTrue(ext.dst().startsWith("~"),
                    "external supertype resolves to a phantom node, got: " + ext.dst());
            assertTrue(ext.dst().contains("ArrayList"),
                    "phantom keyed by the external signature, got: " + ext.dst());
        }
    }

    @Test
    void reverseWalkReturnsSubtype(@TempDir Path indexDir) throws Exception {
        index(HIER, indexDir);
        warm(indexDir);
        try (LsmStore store = LsmStore.open(indexDir)) {
            // find_subtypes primitive: who EXTENDS / IMPLEMENTS the target?
            assertTrue(hasSrc(store.rev("app/Base#"), EdgeType.EXTENDS, "app/Sub#"),
                    "rev(Base) returns the subtype Sub");
            assertTrue(hasSrc(store.rev("app/Iface#"), EdgeType.IMPLEMENTS, "app/Sub#"),
                    "rev(Iface) returns the implementor Sub");
        }
    }

    @Test
    void hierarchyEdgesSurviveReopenAndCompaction(@TempDir Path indexDir) throws Exception {
        index(HIER, indexDir);
        warm(indexDir);
        try (LsmStore store = LsmStore.open(indexDir)) {
            // Reopen already happened (open replays the overlay log); now force a compaction.
            store.compact();
            assertTrue(hasDst(store.fwd("app/Sub#"), EdgeType.EXTENDS, "app/Base#"),
                    "EXTENDS survives compaction");
            assertTrue(hasDst(store.fwd("app/Sub#"), EdgeType.IMPLEMENTS, "app/Iface#"),
                    "IMPLEMENTS survives compaction");
            assertTrue(hasDst(store.fwd("app/Sub#run()."), EdgeType.OVERRIDES, "app/Base#run()."),
                    "OVERRIDES survives compaction");
            assertTrue(hasSrc(store.rev("app/Base#"), EdgeType.EXTENDS, "app/Sub#"),
                    "reverse walk survives compaction");
            MonikerEdge ext = store.fwd("app/Ext#").stream()
                    .filter(e -> e.type() == EdgeType.EXTENDS).findFirst().orElseThrow();
            assertTrue(ext.dst().startsWith("~"), "phantom supertype preserved across compaction");
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Trigger Tier-2 resolution of the subtype files via the {@code Base}/{@code Iface} simple names. */
    private static void warm(Path indexDir) throws Exception {
        try (EdgeResolver resolver = EdgeResolver.open(indexDir, Workspace.discover(HIER), Metrics.create())) {
            for (Symbol s : resolver.declarations("Base")) {
                resolver.findReferences(s);
            }
            for (Symbol s : resolver.declarations("Iface")) {
                resolver.findReferences(s);
            }
        }
    }

    private static boolean hasDst(Set<MonikerEdge> edges, EdgeType type, String dst) {
        return edges.stream().anyMatch(e -> e.type() == type && dst.equals(e.dst()));
    }

    private static boolean hasSrc(Set<MonikerEdge> edges, EdgeType type, String src) {
        return edges.stream().anyMatch(e -> e.type() == type && src.equals(e.src()));
    }

    private static void index(Path repo, Path indexDir) {
        PrintStream sink = new PrintStream(OutputStream.nullOutputStream());
        int code = Main.run(new String[] {"index", repo.toString(), indexDir.toString()}, sink, sink);
        assertTrue(code == 0, "jcma index should succeed");
    }
}
