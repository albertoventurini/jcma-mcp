package jcma.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.index.Range;
import jcma.index.Symbol;
import jcma.index.SymbolKind;
import jcma.resolve.Definition;
import jcma.resolve.FailureClassifier;
import jcma.resolve.Ref;
import jcma.resolve.ReferenceGroup;
import jcma.resolve.References;
import jcma.resolve.UnconfirmedRef;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Task-03 — the pure, stateless shaping layer (PRD principle #4: context-bearing answers). Every
 * fragment renders FQN/signature + {@code file:line} + snippet so the agent rarely needs a follow-up
 * read. Exercised against the M1 resolve shapes and a synthetic {@link Symbol}.
 */
class ShapingTest {

    @Test
    void projectDefinitionRendersSignatureLocationAndSnippet() {
        Definition d = new Definition("com/acme/Foo#bar().", "void com.acme.Foo.bar()",
                Path.of("src/com/acme/Foo.java"), 42, "void bar() { return; }");
        String r = Shaping.definition(d).render();
        assertTrue(r.contains("void com.acme.Foo.bar()"), r);
        assertTrue(r.contains("Foo.java:42"), r);
        assertTrue(r.contains("void bar() { return; }"), r);
    }

    @Test
    void externalDefinitionRendersUniformExternalFormNoSnippet() {
        Definition d = new Definition("java/lang/String#", "java.lang.String", null, -1, "");
        String r = Shaping.definition(d).render();
        assertTrue(r.contains("external (jar/JDK)"), r);
        assertTrue(r.contains("java.lang.String"), r);
        assertFalse(r.contains(":-1"), "no bogus -1 line for an external def: " + r);
    }

    @Test
    void symbolRendersFqnKindLocationAndSignature() {
        // The caller resolves fileId -> Path (via the FileTable) and passes it in; Symbol carries no Path.
        Symbol s = new Symbol("com/acme/Foo#bar().", SymbolKind.METHOD, 0, "com/acme/Foo#",
                3, new Range(42, 3, 50, 4), "bar", "void com.acme.Foo.bar()");
        String r = Shaping.symbol(s, Path.of("src/com/acme/Foo.java")).render();
        assertTrue(r.contains("METHOD"), "kind-bearing path renders the kind: " + r);
        assertTrue(r.contains("void com.acme.Foo.bar()"), r);
        assertTrue(r.contains("Foo.java:42"), "renders a real file:line location: " + r);
    }

    @Test
    void phantomSymbolRendersExternalLocationWithNoPath() {
        // A phantom/external symbol (fileId == -1) has no source path: caller passes null -> external form.
        Symbol s = new Symbol("java/lang/String#", SymbolKind.CLASS, 0, "java/lang/",
                -1, Range.NONE, "String", "java.lang.String");
        String r = Shaping.symbol(s, null).render();
        assertTrue(r.contains("CLASS"), r);
        assertTrue(r.contains("java.lang.String"), r);
        assertTrue(r.contains("external (jar/JDK)"), r);
    }

    @Test
    void referenceGroupRendersEnclosingSignatureCountAndPerRefSnippet() {
        Ref a = new Ref(1, Path.of("src/A.java"), new Range(10, 5, 10, 8), "foo();");
        Ref b = new Ref(1, Path.of("src/A.java"), new Range(20, 5, 20, 8), "foo();");
        ReferenceGroup g = new ReferenceGroup("com/acme/Caller#run().",
                "void com.acme.Caller.run()", List.of(a, b));
        String r = Shaping.refGroup(g).render();
        assertTrue(r.contains("void com.acme.Caller.run()"), r);
        assertTrue(r.contains("(2 refs)"), "renders count(): " + r);
        assertTrue(r.contains("A.java:10"), r);
        assertTrue(r.contains("A.java:20"), r);
    }

    @Test
    void referencesWithUnconfirmedRendersNonExhaustiveTail() {
        Ref a = new Ref(1, Path.of("src/A.java"), new Range(10, 5, 10, 8), "foo();");
        ReferenceGroup g = new ReferenceGroup("com/acme/Caller#run().",
                "void com.acme.Caller.run()", List.of(a));
        UnconfirmedRef u = new UnconfirmedRef(2, Path.of("src/B.java"), new Range(7, 3, 7, 6),
                "foo();", FailureClassifier.Cause.OVERLOAD_AMBIGUITY);
        References refs = new References(List.of(g), List.of(u));
        String r = ToolResult.of(Shaping.references(refs)).render();
        assertTrue(r.contains("NOT exhaustive"), "tail flagged as non-exhaustive: " + r);
        assertTrue(r.contains("B.java:7"), "the unconfirmed candidate is carried: " + r);
    }

    @Test
    void displayDegradesNullSignatureToMonikerWithPhantomMarkerStripped() {
        assertEquals("com/acme/Foo#", Shaping.display(null, "~com/acme/Foo#"));
        assertEquals("com/acme/Foo#", Shaping.display(null, "com/acme/Foo#"));
        assertEquals("sig", Shaping.display("sig", "~whatever"));
    }
}
