package jcma.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import jcma.IndexFixture;
import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.workspace.Workspace;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A nested type used as the qualifier of a static-member access ({@code Outer.Inner.staticCall()}) is a
 * <em>qualified</em> ambiguous name (JLS §6.5.2) — a {@code FieldAccessExpr} that denotes a value if one
 * is in scope, else a type. JavaSymbolSolver's value resolution rejects it (the rightmost identifier
 * {@code Inner} is a type, not a field), so the {@code FailureClassifier} bucketed it as
 * {@code MISSING_CLASSPATH} and the reference was dropped into the unconfirmed tail. The
 * qualified-name-as-type resolution in {@code JavaParserEngine.attempt()} resolves it correctly; this
 * pins that behavior. (The direct {@code FieldAccessExpr} analog of {@link StaticQualifierReferenceTest}.)
 */
class NestedTypeReferenceTest {

    private static final Path REPO = Path.of("src/test/resources/fixtures/resolve/nested-qualifier");
    private static final String INNER = "app/Outer#Inner#";

    @Test
    void nestedTypeQualifierIsAConfirmedTypeReference(@TempDir Path indexDir) throws Exception {
        IndexFixture.build(REPO, indexDir);
        try (EdgeResolver resolver = EdgeResolver.open(indexDir, Workspace.ofSourceRoot(REPO), Metrics.create())) {
            Symbol inner = resolver.declarations("Inner").stream()
                    .filter(s -> INNER.equals(s.moniker()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Inner nested type not indexed"));
            References refs = resolver.findReferences(inner);

            assertFalse(refs.hasUnconfirmedTail(),
                    "the `Outer.Inner.staticCall()` qualifier resolves as a type — not a MISSING_CLASSPATH miss: "
                            + refs.unconfirmed());
            assertEquals(1, refs.totalRefs(), "the lone reference: the static-call qualifier `Outer.Inner`");
            assertEquals(1, refs.groups().size(), "one enclosing symbol");
            assertEquals("app/Caller#go().", refs.groups().get(0).enclosingMoniker(),
                    "enclosed by Caller.go()");
        }
    }
}
