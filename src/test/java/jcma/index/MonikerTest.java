package jcma.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Task 03 — the SCIP-style moniker grammar (PRD §5.1 identity; closes a PRD §11 sub-decision) and
 * the moniker ↔ int32 interner. These assertions <em>are</em> the grammar specification: each
 * descriptor carries its own terminator so monikers compose by concatenation.
 */
class MonikerTest {

    @Test
    void packageMonikerSlashSeparatedTrailingSlash() {
        assertEquals("com/acme/foo/", Moniker.forPackage("com.acme.foo"));
    }

    @Test
    void defaultPackageIsEmpty() {
        assertEquals("", Moniker.forPackage(""));
    }

    @Test
    void typeMonikerUnderPackage() {
        String pkg = Moniker.forPackage("com.acme.foo");
        assertEquals("com/acme/foo/Bar#", Moniker.forType(pkg, "Bar"));
    }

    @Test
    void defaultPackageTypeHasNoLeadingSlash() {
        assertEquals("Foo#", Moniker.forType(Moniker.forPackage(""), "Foo"));
    }

    @Test
    void nestedTypeComposesByConcatenation() {
        String bar = Moniker.forType(Moniker.forPackage("com.acme.foo"), "Bar");
        assertEquals("com/acme/foo/Bar#Baz#", Moniker.forType(bar, "Baz"));
    }

    @Test
    void methodMonikerCarriesCommaJoinedSignature() {
        String bar = Moniker.forType(Moniker.forPackage("com.acme.foo"), "Bar");
        assertEquals("com/acme/foo/Bar#doIt(int,java.lang.String).",
                Moniker.forMethod(bar, "doIt", List.of("int", "java.lang.String")));
    }

    @Test
    void noArgMethodHasEmptyParens() {
        String bar = Moniker.forType(Moniker.forPackage(""), "Bar");
        assertEquals("Bar#run().", Moniker.forMethod(bar, "run", List.of()));
    }

    @Test
    void constructorIsInitMethod() {
        String bar = Moniker.forType(Moniker.forPackage(""), "Bar");
        assertEquals("Bar#<init>(int).", Moniker.forConstructor(bar, List.of("int")));
    }

    @Test
    void fieldMoniker() {
        String bar = Moniker.forType(Moniker.forPackage(""), "Bar");
        assertEquals("Bar#value.", Moniker.forField(bar, "value"));
    }

    @Test
    void internerAssignsDenseStableIds() {
        Moniker.Interner in = new Moniker.Interner();
        int a = in.intern("a/");
        int b = in.intern("b/");
        assertEquals(a, in.intern("a/"), "re-interning returns the same id");
        assertEquals(2, in.size(), "two distinct monikers interned");
        assertEquals("a/", in.monikerOf(a), "id resolves back to its moniker");
        assertEquals(b, in.idOf("b/"), "idOf returns the interned id");
        assertEquals(-1, in.idOf("never-seen/"), "idOf of an unknown moniker is -1");
    }
}
