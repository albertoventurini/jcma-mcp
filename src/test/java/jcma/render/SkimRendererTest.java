package jcma.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import jcma.engine.SkimUnit;
import jcma.engine.StructuralParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code skim_java} presentation layer (red-first) — {@link SkimRenderer} turns the engine
 * {@link SkimUnit} into the gutter'd, real-Java rendering: source-true line numbers, source-preserved
 * indentation, the {@code inlineBodyMaxChars} char gate (inline {@code { … }} vs elided {@code { … }}),
 * and the {@code includeDocs} on/off (a clean drop, never a clip). Driven over
 * {@code fixtures/skim/Sample.java} whose line layout is fixed.
 */
class SkimRendererTest {

    private static final Path SAMPLE = Path.of("src/test/resources/fixtures/skim/Sample.java");

    private SkimUnit skim() throws IOException {
        return new StructuralParser().collect(SAMPLE).skim();
    }

    private String render(int gate, boolean includeDocs) throws IOException {
        return SkimRenderer.render(skim(), gate, includeDocs);
    }

    @Test
    void gutterCarriesSourceTrueLineNumbers() throws IOException {
        String out = render(50, true);
        // The package is line 1; the import block lines 3–4 — each rendered row is prefixed by its line.
        assertTrue(line(out, "package fix.skim;").matches("\\s*1\\s+package fix.skim;"),
                "package row gutter'd with source line 1: [" + line(out, "package fix.skim;") + "]");
        assertTrue(line(out, "import java.util.List;").matches("\\s*4\\s+import java.util.List;"),
                "import row gutter'd with source line 4");
        // The class brace line is 10.
        assertTrue(line(out, "class Cart").matches("\\s*10\\s+public final class Cart \\{"),
                "class header row gutter'd with source line 10: [" + line(out, "class Cart") + "]");
    }

    @Test
    void shortBodyInlinesAndLongBodyElides() throws IOException {
        String out = render(50, true);
        // addItem's body normalizes to ~`items.add(i);` (< 50 chars) → inlined verbatim on the signature line.
        assertTrue(out.contains("public void addItem(Item i) { items.add(i); }"),
                "short body inlined: " + out);
        // total's body is real logic (> 50 chars) → elided to `{ … }`, and its statements are absent.
        assertTrue(line(out, "public int total()").contains("{ … }"), "long body elided to { … }: " + out);
        assertFalse(out.contains("sum += i.price();"), "an elided body's statements are not rendered: " + out);
    }

    @Test
    void elisionLeavesAVisibleLineGap() throws IOException {
        String out = render(50, true);
        // total() renders on source line 20; the next rendered row jumps to the interface at line 31 —
        // the gap (lines 21–29 are the elided body) is the honest size-at-a-glance signal.
        assertTrue(line(out, "public int total()").matches("\\s*20\\s+.*\\{ … \\}"),
                "elided method sits on its own source line 20: [" + line(out, "public int total()") + "]");
        assertTrue(line(out, "interface Pricer").matches("\\s*31\\s+.*interface Pricer \\{"),
                "the next declaration jumps to line 31, leaving a visible gutter gap");
    }

    @Test
    void charGateBoundaryFlipsAtTheConfiguredWidth() throws IOException {
        // gate 5: even `items.add(i);` exceeds it → addItem elides too.
        String tight = render(5, true);
        assertTrue(line(tight, "addItem").contains("{ … }"), "a tiny gate elides the short body too: " + tight);
        // gate 10000: even total's real-logic body fits → it inlines, statements present on one line.
        String loose = render(10000, true);
        assertTrue(line(loose, "total").contains("sum += i.price();"),
                "a huge gate inlines the long body verbatim on one line: " + loose);
    }

    @Test
    void docsRenderVerbatimByDefaultAndDropCleanlyWhenOff() throws IOException {
        String withDocs = render(50, true);
        assertTrue(withDocs.contains("An immutable snapshot"), "class Javadoc rendered by default");
        assertTrue(withDocs.contains("persists items to the database"), "doc tail not clipped");
        assertTrue(withDocs.contains("Adds one item"), "method Javadoc rendered by default");

        String noDocs = render(50, false);
        assertFalse(noDocs.contains("An immutable snapshot"), "includeDocs=false drops the class doc");
        assertFalse(noDocs.contains("Adds one item"), "includeDocs=false drops the method doc");
        // Code is untouched by the doc drop.
        assertTrue(noDocs.contains("public void addItem(Item i) { items.add(i); }"), noDocs);
    }

    @Test
    void nestedDeclarationsKeepSourceIndentation() throws IOException {
        String out = render(50, true);
        // The interface method `price` is indented 8 spaces in source; the gutter precedes that indent.
        assertTrue(line(out, "int price(Item i);").matches("\\s*32\\s{2,}\\s{8}int price\\(Item i\\);")
                        || line(out, "int price(Item i);").contains("        int price(Item i);"),
                "nested member keeps its source indentation: [" + line(out, "int price(Item i);") + "]");
    }

    @Test
    void shortBodyWithLineCommentElidesSoItStaysValidJava(@TempDir Path dir) throws IOException {
        // A short body whose only statement is preceded by a `//` comment: collapsing it to one line would
        // pull the `}` into the comment. It must elide (not inline) even well under the gate.
        Path f = dir.resolve("C.java");
        Files.writeString(f, "class C {\n    int n() {\n        // the count\n        return 1;\n    }\n}\n");
        String out = SkimRenderer.render(new StructuralParser().collect(f).skim(), 200, true);
        assertTrue(line(out, "int n()").contains("{ … }"), "a line-commented body elides: " + out);
        assertFalse(out.contains("// the count }"), "the closing brace is never swallowed into a comment: " + out);
    }

    @Test
    void slashesInaStringLiteralDoNotBlockInlining(@TempDir Path dir) throws IOException {
        // `//` inside a string is not a comment (token-based check), so a short body still inlines.
        Path f = dir.resolve("U.java");
        Files.writeString(f, "class U {\n    String u() { return \"http://x\"; }\n}\n");
        String out = SkimRenderer.render(new StructuralParser().collect(f).skim(), 200, true);
        assertTrue(line(out, "String u()").contains("{ return \"http://x\"; }"),
                "a `//` inside a string literal does not force elision: " + out);
    }

    /** The first rendered output line whose text contains {@code needle} (gutter included). */
    private static String line(String rendered, String needle) {
        List<String> hits = Arrays.stream(rendered.split("\n")).filter(l -> l.contains(needle)).toList();
        assertFalse(hits.isEmpty(), "no rendered line contains '" + needle + "' in:\n" + rendered);
        return hits.get(0);
    }
}
