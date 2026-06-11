package jcma.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M3 task-01 — the {@link StructuralParser} text projection ({@code textUnits()}): the D2 corpus
 * (string literals + text blocks → {@code STRING_LITERAL}; Javadoc → {@code JAVADOC}; line/block
 * comments → {@code COMMENT}) extracted off the single parse, each with its correct {@code
 * file:line:col}. Code identifiers and keywords are <b>not</b> text units — the symbol tier serves
 * those.
 */
class StructuralParserTextTest {

    private static final String SRC = """
            package fix;

            /** Greets a user by name. */
            public class Sample {
                // trailing line comment
                /* a block comment here */
                String greeting = "hello world";

                String poem = ""\"
                        roses are red
                        violets are blue
                        ""\";

                /**
                 * Renders the greeting text.
                 * @return text
                 */
                String render() {
                    return greeting;
                }
            }
            """;

    private List<TextUnit> extract(Path dir) throws IOException {
        Path file = dir.resolve("Sample.java");
        Files.writeString(file, SRC);
        return new StructuralParser().textUnits(file);
    }

    @Test
    void extractsEachKindWithCorrectPosition(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Sample.java"), SRC);
        List<String> lines = Files.readAllLines(dir.resolve("Sample.java"));
        List<TextUnit> units = new StructuralParser().textUnits(dir.resolve("Sample.java"));

        TextUnit literal = only(units, TextKind.STRING_LITERAL, "hello world");
        assertTrue(lines.get(literal.startLine() - 1).contains("\"hello world\""),
                "the string literal's startLine must point at its source line");

        TextUnit classDoc = only(units, TextKind.JAVADOC, "Greets a user by name");
        assertTrue(lines.get(classDoc.startLine() - 1).contains("Greets a user by name"));

        assertTrue(units.stream().anyMatch(u -> u.kind() == TextKind.JAVADOC
                && u.text().contains("Renders the greeting")), "method Javadoc indexed");
        assertTrue(units.stream().anyMatch(u -> u.kind() == TextKind.COMMENT
                && u.text().contains("trailing line comment")), "line comment indexed");
        assertTrue(units.stream().anyMatch(u -> u.kind() == TextKind.COMMENT
                && u.text().contains("a block comment here")), "block comment indexed");
        assertTrue(units.stream().anyMatch(u -> u.kind() == TextKind.STRING_LITERAL
                && u.text().contains("roses are red")), "text block folds into STRING_LITERAL");
    }

    @Test
    void codeIdentifiersAreNotTextUnits(@TempDir Path dir) throws IOException {
        List<TextUnit> units = extract(dir);
        // `poem` and `Sample` appear only in code (field decl, class decl) — never in a
        // literal/comment/Javadoc — so they must not surface as text units.
        assertFalse(units.stream().anyMatch(u -> u.text().contains("poem")),
                "the code identifier `poem` is not text-indexed");
        assertFalse(units.stream().anyMatch(u -> u.text().contains("Sample")),
                "the type name `Sample` is not text-indexed");
        assertFalse(units.stream().anyMatch(u -> u.text().contains("class")),
                "the `class` keyword is not text-indexed");
    }

    private static TextUnit only(List<TextUnit> units, TextKind kind, String contains) {
        List<TextUnit> matches = units.stream()
                .filter(u -> u.kind() == kind && u.text().contains(contains)).toList();
        assertEquals(1, matches.size(), "exactly one " + kind + " containing " + contains + ": " + matches);
        return matches.get(0);
    }
}
