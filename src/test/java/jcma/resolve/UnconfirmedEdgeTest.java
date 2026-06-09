package jcma.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task-11b (red-first) — an unconfirmed reference is a persistent, <b>name-keyed</b> graph edge:
 * its syntactic edge type is preserved ({@code CALLS}), and its {@code dst} is the placeholder node
 * {@code <name>~UNRESOLVED} (never the receiver type). The {@code find_references} unconfirmed tail
 * is then read back from {@code rev(<name>~UNRESOLVED)} — graph-backed and surviving a restart —
 * instead of the in-session {@code unconfirmedByName} map.
 *
 * <p>The {@code resolve/refs} fixture has two misses spelled {@code run()}: {@code Mystery.poke}'s
 * {@code thing.run()} on the <b>unknown</b> type {@code Unknown}, and {@code KnownMiss.use}'s
 * {@code w.run()} on the <b>known</b> type {@code Widget} (which has no {@code run()}). Both must
 * surface, and both edges must point at the same {@code run~UNRESOLVED} placeholder.
 */
class UnconfirmedEdgeTest {

    private static final Path REFS = Path.of("src/test/resources/fixtures/resolve/refs");
    private static final String TARGET_MONIKER = "app/Service#run().";
    private static final String PLACEHOLDER = "run~UNRESOLVED";

    @Test
    void unconfirmedRefsArePersistedAsEdgesToTheNamePlaceholder(@TempDir Path indexDir) throws Exception {
        index(REFS, indexDir);
        // Resolve by issuing the query, then close so overlay.log is flushed to disk.
        try (EdgeResolver resolver = EdgeResolver.open(indexDir, Workspace.ofSourceRoot(REFS), Metrics.create())) {
            resolver.findReferences(target(resolver));
        }
        // Re-open the store independently: the placeholder's incoming edges must come from the
        // replayed overlay.log — proving persistence (the in-session map is gone).
        try (LsmStore store = LsmStore.open(indexDir)) {
            Set<MonikerEdge> in = store.rev(PLACEHOLDER);
            Set<String> srcs = in.stream().map(MonikerEdge::src).collect(Collectors.toSet());
            assertTrue(srcs.contains("app/Mystery#poke()."), "unknown-type receiver miss persisted: " + srcs);
            assertTrue(srcs.contains("app/KnownMiss#use(Widget)."), "known-type receiver miss persisted: " + srcs);
            assertTrue(in.stream().allMatch(e -> PLACEHOLDER.equals(e.dst())),
                    "every unconfirmed edge's dst is the name placeholder, never the receiver type");
            assertTrue(in.stream().allMatch(e -> e.type() == EdgeType.CALLS),
                    "edge keeps its syntactic kind (CALLS), not a synthetic UNRESOLVED edge type");
        }
    }

    @Test
    void tailIsGraphBackedAndCarriesBothMissesWithoutDoubleCounting(@TempDir Path indexDir) throws Exception {
        index(REFS, indexDir);
        try (EdgeResolver resolver = EdgeResolver.open(indexDir, Workspace.ofSourceRoot(REFS), Metrics.create())) {
            References refs = resolver.findReferences(target(resolver));

            assertEquals(2, refs.unconfirmed().size(), "Mystery.poke + KnownMiss.use");
            Set<String> files = refs.unconfirmed().stream()
                    .map(u -> u.file().getFileName().toString()).collect(Collectors.toSet());
            assertEquals(Set.of("Mystery.java", "KnownMiss.java"), files);
            assertTrue(refs.unconfirmed().stream().allMatch(u -> u.cause() != null), "each carries a cause");
            assertEquals(3, refs.totalRefs(), "confirmed set unchanged — no placeholder edge counted as confirmed");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Symbol target(EdgeResolver resolver) {
        return resolver.declarations("run").stream()
                .filter(s -> TARGET_MONIKER.equals(s.moniker()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Service.run() declaration not indexed"));
    }

    private static void index(Path repo, Path indexDir) {
        PrintStream sink = new PrintStream(OutputStream.nullOutputStream());
        int code = Main.run(new String[] {"index", repo.toString(), indexDir.toString()}, sink, sink);
        assertEquals(0, code, "jcma index should succeed");
    }
}
