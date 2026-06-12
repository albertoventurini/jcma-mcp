package jcma.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.IndexFixture;
import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.workspace.Workspace;

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

    private static final Path REFS_SUPERTYPE = Path.of("src/test/resources/fixtures/resolve/refs-supertype");
    private static final String SHAPE_MONIKER = "app/Shape#";

    @Test
    void groupsConfirmedRefsByEnclosingSymbolWithCounts(@TempDir Path indexDir) throws Exception {
        index(REFS, indexDir);
        try (EdgeResolver resolver = EdgeResolver.open(indexDir, Workspace.ofSourceRoot(REFS), Metrics.create())) {
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
        try (EdgeResolver resolver = EdgeResolver.open(indexDir, Workspace.ofSourceRoot(REFS), Metrics.create())) {
            References refs = resolver.findReferences(target(resolver));

            assertTrue(refs.hasUnconfirmedTail(), "Mystery.poke's thing.run() cannot be ruled in or out");
            assertEquals(2, refs.unconfirmed().size(), "Mystery.poke (unknown type) + KnownMiss.use (known type)");
            UnconfirmedRef mystery = refs.unconfirmed().stream()
                    .filter(u -> u.file().toString().endsWith("Mystery.java"))
                    .findFirst().orElseThrow(() -> new AssertionError("Mystery.poke candidate missing"));
            assertTrue(mystery.snippet().contains("thing.run()"), "carries the candidate snippet: " + mystery.snippet());
            assertFalse(monikersOf(refs).contains("app/Mystery#poke()."),
                    "the unresolved candidate is NOT presented as a confirmed reference");
        }
    }

    @Test
    void secondQueryIsPureCacheLookupNoReResolve(@TempDir Path indexDir) throws Exception {
        index(REFS, indexDir);
        Metrics metrics = Metrics.create();
        try (EdgeResolver resolver = EdgeResolver.open(indexDir, Workspace.ofSourceRoot(REFS), metrics)) {
            resolver.findReferences(target(resolver));
            long afterFirst = metrics.counter("resolve.files").sum();
            assertTrue(afterFirst > 0, "the first query resolves candidate files");

            References again = resolver.findReferences(target(resolver));
            long afterSecond = metrics.counter("resolve.files").sum();

            assertEquals(afterFirst, afterSecond, "second query resolves nothing — pure graph walk");
            assertEquals(3, again.totalRefs(), "and returns the same confirmed set from the cache");
        }
    }

    /**
     * Regression (the crash): find_references on a type that is <b>implemented</b> ({@code Shape},
     * implemented by {@code Circle} and {@code Square}) must not emit the structural {@code IMPLEMENTS}
     * hierarchy edges as references. Those carry {@code Occurrence.NONE} (no file, {@code fileId ==
     * -1}, {@code Range.NONE}); turning them into {@code Ref}s yields location-less entries that NPE
     * the moment any consumer dereferences {@code Ref.file()} (the CLI printer, the MCP shaper),
     * aborting the whole answer. Before the fix only {@code CONTAINS} was filtered.
     *
     * <p>But the <em>textual</em> {@code implements Shape} clauses ARE genuine {@code TYPE_REF}
     * references (with real locations) and must be kept — the fix drops only the location-less
     * duplicate, never the real supertype reference.
     */
    @Test
    void dropsPhantomHierarchyEdgesButKeepsRealSupertypeReferences(@TempDir Path indexDir) throws Exception {
        index(REFS_SUPERTYPE, indexDir);
        try (EdgeResolver resolver =
                EdgeResolver.open(indexDir, Workspace.ofSourceRoot(REFS_SUPERTYPE), Metrics.create())) {
            References refs = resolver.findReferences(shapeType(resolver));

            assertTrue(
                    allRefs(refs).allMatch(r -> r.file() != null && r.fileId() >= 0 && !r.range().isNone()),
                    "no location-less phantom ref from an IMPLEMENTS/EXTENDS hierarchy edge");
            assertEquals(4, refs.totalRefs(),
                    "Canvas field + Canvas param + `Circle implements Shape` + `Square implements Shape`");
            assertTrue(
                    allRefs(refs).anyMatch(r -> r.file().toString().endsWith("Circle.java")
                            && r.snippet().contains("implements Shape")),
                    "the `implements Shape` clause is kept as a confirmed reference with its real location");
            assertTrue(
                    allRefs(refs).anyMatch(r -> r.file().toString().endsWith("Square.java")
                            && r.snippet().contains("implements Shape")),
                    "both implementors' supertype clauses are confirmed references");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static java.util.stream.Stream<Ref> allRefs(References refs) {
        return refs.groups().stream().flatMap(g -> g.refs().stream());
    }

    private static Symbol shapeType(EdgeResolver resolver) {
        return resolver.declarations("Shape").stream()
                .filter(s -> SHAPE_MONIKER.equals(s.moniker()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Shape type declaration not indexed"));
    }

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
        IndexFixture.build(repo, indexDir);
    }
}
