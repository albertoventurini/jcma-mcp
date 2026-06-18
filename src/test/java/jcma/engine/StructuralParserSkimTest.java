package jcma.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code skim_java} engine projection (red-first) — the fourth {@link StructuralParser.Parsed}
 * projection {@code skim()}, returning a {@link SkimUnit} of <b>verbatim source spans</b> (doc /
 * header / body) + 1-based ranges, AST-free so it crosses the engine seam (PRD §4). Asserts the
 * structure and the verbatim slices over {@code fixtures/skim/Sample.java} (line layout is fixed by
 * the fixture, so the ranges are deterministic).
 */
class StructuralParserSkimTest {

    private static final Path SAMPLE = Path.of("src/test/resources/fixtures/skim/Sample.java");

    private SkimUnit skim() throws IOException {
        return new StructuralParser().collect(SAMPLE).skim();
    }

    @Test
    void packageAndImportsCarryTheirSourceLines() throws IOException {
        SkimUnit u = skim();
        assertEquals("fix.skim", u.packageName());
        assertEquals(1, u.packageLine());
        List<SkimImport> imports = u.imports();
        assertEquals(2, imports.size(), "two import declarations");
        assertEquals("import java.util.ArrayList;", imports.get(0).text());
        assertEquals(3, imports.get(0).line());
        assertEquals("import java.util.List;", imports.get(1).text());
        assertEquals(4, imports.get(1).line());
    }

    @Test
    void topTypeIsCartWithVerbatimDocAndRanges() throws IOException {
        SkimUnit u = skim();
        assertEquals(1, u.types().size());
        SkimDecl cart = u.types().get(0);
        assertEquals(Outline.Kind.CLASS, cart.kind());
        assertEquals("Cart", cart.name());

        // Verbatim Javadoc — kept whole (no clipping), with its `*` gutter, starting at its source line.
        assertNotNull(cart.docText());
        assertTrue(cart.docText().contains("An immutable snapshot"), cart.docText());
        assertTrue(cart.docText().contains("persists items to the database"), "doc tail not clipped: " + cart.docText());
        assertTrue(cart.docText().contains("*"), "Javadoc gutter preserved verbatim");
        assertEquals(6, cart.docStartLine());

        // Header is the declaration up to (but not including) the opening brace.
        assertTrue(cart.headerText().contains("public final class Cart"), cart.headerText());
        assertFalse(cart.headerText().contains("{"), "type header stops before the body brace: " + cart.headerText());
        assertEquals(10, cart.startLine());
        assertEquals(36, cart.endLine());
    }

    @Test
    void fieldIsAVerbatimLeafWithNoBody() throws IOException {
        SkimDecl field = child(topType(), "items");
        assertEquals(Outline.Kind.FIELD, field.kind());
        assertFalse(field.hasBody());
        assertNull(field.bodyInner());
        // The whole declaration, including its initializer and trailing `;`, kept verbatim.
        assertTrue(field.headerText().contains("private final List<Item> items = new ArrayList<>();"),
                field.headerText());
        assertEquals(12, field.startLine());
    }

    @Test
    void methodWithBodyCarriesHeaderAnnotationAndVerbatimBody() throws IOException {
        SkimDecl add = child(topType(), "addItem");
        assertEquals(Outline.Kind.METHOD, add.kind());
        assertTrue(add.hasBody());
        // Header includes the annotation + signature (generics/params as written), no brace.
        assertTrue(add.headerText().contains("@Override"), "annotation in header: " + add.headerText());
        assertTrue(add.headerText().contains("public void addItem(Item i)"), add.headerText());
        assertFalse(add.headerText().contains("{"), "method header stops before the body brace: " + add.headerText());
        // Body is the verbatim block content (between the braces).
        assertTrue(add.bodyInner().contains("items.add(i);"), "verbatim body: " + add.bodyInner());
        assertNotNull(add.docText());
        assertTrue(add.docText().contains("Adds one item"), add.docText());
        assertEquals(14, add.docStartLine());
    }

    @Test
    void longMethodSpansMultipleLinesWithVerbatimBody() throws IOException {
        SkimDecl total = child(topType(), "total");
        assertTrue(total.hasBody());
        assertTrue(total.startLine() < total.endLine(), "a real-logic body spans multiple source lines");
        assertEquals(20, total.startLine());
        assertEquals(29, total.endLine());
        assertTrue(total.bodyInner().contains("sum += i.price();"), total.bodyInner());
    }

    @Test
    void nestedTypesAreChildrenAndAbstractMethodHasNoBody() throws IOException {
        SkimDecl pricer = child(topType(), "Pricer");
        assertEquals(Outline.Kind.INTERFACE, pricer.kind());
        SkimDecl price = child(pricer, "price");
        assertEquals(Outline.Kind.METHOD, price.kind());
        assertFalse(price.hasBody(), "an interface method has no body");
        assertNull(price.bodyInner());
        // The no-body member keeps its full verbatim declaration (incl. the trailing `;`).
        assertTrue(price.headerText().contains("int price(Item i);"), price.headerText());

        SkimDecl item = child(topType(), "Item");
        assertEquals(Outline.Kind.RECORD, item.kind());
        // Record components live in the header, not as children.
        assertTrue(item.headerText().contains("record Item(String name, int price)"), item.headerText());
        assertTrue(item.children().isEmpty(), "no member children on the record");
    }

    private SkimDecl topType() throws IOException {
        return skim().types().get(0);
    }

    private static SkimDecl child(SkimDecl parent, String name) {
        return parent.children().stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no child '" + name + "' under " + parent.name()
                        + " — children: " + parent.children().stream().map(SkimDecl::name).toList()));
    }
}
