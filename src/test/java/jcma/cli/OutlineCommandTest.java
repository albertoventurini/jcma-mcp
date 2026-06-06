package jcma.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Task 06 · P4 — {@code jcma outline <file>}: re-parse one file (Tier-1, no index lookup) and print
 * its declaration containment tree, indented by nesting.
 */
class OutlineCommandTest {

    private static final Path CIRCLE =
            Path.of("src/test/resources/fixtures/indexer/com/example/shapes/Circle.java");

    private record Run(int exit, String out, String err) {}

    private static Run dispatch(String... args) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = Main.run(args, new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        return new Run(exit, outBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void outlinePrintsTheContainmentTree() {
        Run r = dispatch("outline", CIRCLE.toString());
        assertEquals(0, r.exit(), "outline should exit 0: " + r.out() + r.err());

        String o = r.out();
        assertTrue(o.contains("Circle"), "lists the top-level type: " + o);
        assertTrue(o.contains("area"), "lists a method");
        assertTrue(o.contains("Builder"), "lists the nested type");
        assertTrue(o.contains("build"), "lists a nested-type member");

        // The nested member is indented deeper than the top-level type (containment is visible).
        int circleIndent = indentOf(o, "Circle");
        int buildIndent = indentOf(o, "build(");
        assertTrue(buildIndent > circleIndent,
                "Circle#Builder#build is nested deeper than Circle: " + o);
    }

    @Test
    void usageWhenNoFile() {
        assertEquals(2, dispatch("outline").exit());
    }

    /** Leading-space count of the first line whose trimmed text contains {@code needle}. */
    private static int indentOf(String text, String needle) {
        for (String line : text.split("\n")) {
            if (line.contains(needle)) {
                int i = 0;
                while (i < line.length() && line.charAt(i) == ' ') {
                    i++;
                }
                return i;
            }
        }
        return -1;
    }
}
