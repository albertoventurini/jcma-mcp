package jcma.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.workspace.Workspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The whole-file dependency extraction behind the QA {@code resolve-file} surface: for each declared
 * type in a unit, its direct supertypes ({@code EXTENDS}/{@code IMPLEMENTS}) and every resolved
 * type-mention in its body, each attributed to its <em>immediately enclosing</em> type. Reuses the
 * same per-node resolve the name-scoped queries use — exhaustive selection, identical resolution.
 */
class ResolveFileDependenciesTest {

    private static final Path DIR = Path.of("src/test/resources/fixtures/engine/resolve-file");

    private static JavaParserEngine engine() {
        Workspace ws = new Workspace(DIR, List.of(DIR), List.of());
        return new JavaParserEngine(ws);
    }

    /** Render the resolved triples as a stable {@code RELATION owner -> dep} set for assertion. */
    private static Set<String> resolvedTriples(List<TypeDependency> deps) {
        return deps.stream()
                .filter(TypeDependency::resolved)
                .map(d -> d.relation() + " " + d.ownerFqn() + " -> " + d.target())
                .collect(Collectors.toSet());
    }

    @Test
    void extractsSupertypesAndTypeRefsAttributedToEnclosingType() throws Exception {
        JavaParserEngine engine = engine();
        ParsedUnit unit = engine.parse(DIR.resolve("q/Sample.java"));

        List<TypeDependency> deps = engine.resolveFileDependencies(unit);
        Set<String> got = resolvedTriples(deps);

        // Sample implements Base — counted both as a supertype and (the clause's type mention) a typeref.
        assertTrue(got.contains("SUPERTYPE q.Sample -> q.Base"), got.toString());
        assertTrue(got.contains("TYPEREF q.Sample -> q.Base"), got.toString());
        // Helper is referenced three ways in Sample (field, return, `new`) — deduped to one typeref.
        assertTrue(got.contains("TYPEREF q.Sample -> q.Helper"), got.toString());
        // The nested type's Helper reference attributes to the nested type, not the outer Sample.
        assertTrue(got.contains("TYPEREF q.Sample.Inner -> q.Helper"), got.toString());

        // Dedup: Helper appears once for Sample despite three syntactic mentions.
        long sampleHelper = got.stream()
                .filter(s -> s.equals("TYPEREF q.Sample -> q.Helper")).count();
        assertEquals(1, sampleHelper, "Helper must be deduped to a single Sample typeref");

        // The outer type must not absorb the nested type's reference.
        assertTrue(!got.contains("TYPEREF q.Sample -> q.Helper")
                        || got.contains("TYPEREF q.Sample.Inner -> q.Helper"),
                "nested reference stays attributed to Inner");
    }

    /**
     * Regression for the record-container nested-type cliff, <em>at arbitrary depth</em>: a type path
     * that descends through several records (interface → record → record → record → record) must
     * resolve at every level. JavaParser 3.28.2's {@code JavaParserTypeAdapter.solveType} recurses into
     * class/interface/enum/annotation containers but not records, throwing
     * {@code UnsupportedOperationException} on every descend-through-record hop — so jcma safe-degrades.
     *
     * <p>This pins the <em>recursion</em> in the scope-nested fallback, not just a one-level patch: the
     * outer hops ({@code Nest}, {@code Nest.Mid}) resolve via the stock path, but {@code .Deeper} and
     * {@code .Deepest} require the broken scope itself to be recovered by the fallback. A non-recursive
     * fallback resolves only {@code Nest.Mid.Deep} and fails these — so they are the load-bearing
     * assertions.
     */
    @Test
    void deepTypeNestedThroughRecordsResolvesAtEveryLevel() throws Exception {
        JavaParserEngine engine = engine();
        ParsedUnit unit = engine.parse(DIR.resolve("q/NestUser.java"));

        List<TypeDependency> deps = engine.resolveFileDependencies(unit);
        Set<String> got = resolvedTriples(deps);

        // Outer hops resolve via the stock path (sanity — these pass without the fix).
        assertTrue(got.contains("TYPEREF q.NestUser -> q.Nest"), got.toString());
        assertTrue(got.contains("TYPEREF q.NestUser -> q.Nest.Mid"), got.toString());
        // First broken hop — a one-level fallback would already satisfy this one.
        assertTrue(got.contains("TYPEREF q.NestUser -> q.Nest.Mid.Deep"),
                "a type nested one level through a record must resolve: " + got);
        // Deeper hops — these require the RECURSION (the scope is itself a broken hop).
        assertTrue(got.contains("TYPEREF q.NestUser -> q.Nest.Mid.Deep.Deeper"),
                "two records deep must resolve (recursive fallback): " + got);
        assertTrue(got.contains("TYPEREF q.NestUser -> q.Nest.Mid.Deep.Deeper.Deepest"),
                "three records deep must resolve (recursive fallback): " + got);
    }

    /**
     * Regression for the whole-file resolution cliff (docs/whole-file-resolution-degradation.md): a
     * source file containing a {@code yield} statement must not lose <em>all</em> its resolved type
     * references. Under {@code LanguageLevel.RAW} the {@code yield} is a parse problem, which suppresses
     * JavaParser's symbol-resolver injection and makes every mention in the unit safe-degrade — even
     * ordinary types like {@code Helper} and {@code java.util.List}.
     *
     * <p>Beyond whole-file recovery, this also asserts {@code Yielded} — referenced <em>only inside</em>
     * the {@code yield} block arm RAW would discard — resolves, locking the in-yield recovery that
     * parsing at a real language level (validator stripped) uniquely gives (vs merely attaching the
     * resolver under RAW, which cannot recover symbols inside the dropped arm).
     */
    @Test
    void yieldStatementDoesNotSinkWholeFileResolution() throws Exception {
        JavaParserEngine engine = engine();
        ParsedUnit unit = engine.parse(DIR.resolve("q/Yielding.java"));

        List<TypeDependency> deps = engine.resolveFileDependencies(unit);
        Set<String> got = resolvedTriples(deps);

        assertTrue(got.contains("TYPEREF q.Yielding -> q.Helper"), got.toString());
        assertTrue(got.contains("TYPEREF q.Yielding -> java.util.List"), got.toString());
        assertTrue(got.contains("TYPEREF q.Yielding -> q.Yielded"),
                "a type referenced only inside the yield block arm must resolve: " + got);
    }
}
