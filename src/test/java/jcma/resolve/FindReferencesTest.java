package jcma.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.cli.Main;
import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.workspace.Workspace;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task-10 (red-first) — {@code find_references} over the tiny {@code resolve/refs} fixture: confirmed
 * references grouped by enclosing symbol + counts, the <b>mandatory unconfirmed tail</b>, and the
 * cache property (a second query re-resolves nothing).
 *
 * <p>The fixture (package {@code app}) has the target {@code Service.run()} referenced from three
 * enclosing methods ({@code ClientA.go}, {@code ClientB.first}, {@code ClientB.second}) and one
 * candidate that cannot be resolved ({@code Mystery.poke}'s {@code thing.run()} on an unknown type) —
 * which must surface as an unconfirmed candidate, never a silent miss.
 */
class FindReferencesTest {

    private static final Path REFS = Path.of("src/test/resources/fixtures/resolve/refs");
    private static final String TARGET_MONIKER = "app/Service#run().";

    @Test
    void groupsConfirmedRefsByEnclosingSymbolWithCounts(@TempDir Path indexDir) throws Exception {
        index(REFS, indexDir);
        try (EdgeResolver resolver = EdgeResolver.open(indexDir, Workspace.discover(REFS), Metrics.create())) {
            References refs = resolver.findReferences(target(resolver));

            assertEquals(3, refs.totalRefs(), "ClientA.go + ClientB.first + ClientB.second");
            assertEquals(3, refs.groups().size(), "one group per enclosing method");
            assertEquals(1, countFor(refs, "app/ClientA#go()."));
            assertEquals(1, countFor(refs, "app/ClientB#first(Service)."));
            assertEquals(1, countFor(refs, "app/ClientB#second(Service)."));
        }
    }

    @Test
    void surfacesUnconfirmedTailForAnUnresolvableCandidate(@TempDir Path indexDir) throws Exception {
        index(REFS, indexDir);
        try (EdgeResolver resolver = EdgeResolver.open(indexDir, Workspace.discover(REFS), Metrics.create())) {
            References refs = resolver.findReferences(target(resolver));

            assertTrue(refs.hasUnconfirmedTail(), "Mystery.poke's thing.run() cannot be ruled in or out");
            assertEquals(1, refs.unconfirmed().size());
            UnconfirmedRef u = refs.unconfirmed().get(0);
            assertTrue(u.file().toString().endsWith("Mystery.java"), "the unresolved candidate's file");
            assertTrue(u.snippet().contains("thing.run()"), "carries the candidate snippet: " + u.snippet());
            assertFalse(monikersOf(refs).contains("app/Mystery#poke()."),
                    "the unresolved candidate is NOT presented as a confirmed reference");
        }
    }

    @Test
    void secondQueryIsPureCacheLookupNoReResolve(@TempDir Path indexDir) throws Exception {
        index(REFS, indexDir);
        Metrics metrics = Metrics.create();
        try (EdgeResolver resolver = EdgeResolver.open(indexDir, Workspace.discover(REFS), metrics)) {
            resolver.findReferences(target(resolver));
            long afterFirst = metrics.counter("resolve.files").sum();
            assertTrue(afterFirst > 0, "the first query resolves candidate files");

            References again = resolver.findReferences(target(resolver));
            long afterSecond = metrics.counter("resolve.files").sum();

            assertEquals(afterFirst, afterSecond, "second query resolves nothing — pure graph walk");
            assertEquals(3, again.totalRefs(), "and returns the same confirmed set from the cache");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Symbol target(EdgeResolver resolver) {
        return resolver.declarations("run").stream()
                .filter(s -> TARGET_MONIKER.equals(s.moniker()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Service.run() declaration not indexed"));
    }

    private static int countFor(References refs, String enclosingMoniker) {
        return refs.groups().stream()
                .filter(g -> enclosingMoniker.equals(g.enclosingMoniker()))
                .mapToInt(ReferenceGroup::count)
                .sum();
    }

    private static List<String> monikersOf(References refs) {
        return refs.groups().stream().map(ReferenceGroup::enclosingMoniker).toList();
    }

    /** Build a full persisted index (symbols + edges + usage names + file table) via {@code jcma index}. */
    private static void index(Path repo, Path indexDir) {
        PrintStream sink = new PrintStream(OutputStream.nullOutputStream());
        int code = Main.run(new String[] {"index", repo.toString(), indexDir.toString()}, sink, sink);
        assertEquals(0, code, "jcma index should succeed");
    }
}
