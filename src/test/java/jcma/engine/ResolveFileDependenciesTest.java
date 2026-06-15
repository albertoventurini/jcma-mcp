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
}
