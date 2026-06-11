package jcma.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.engine.TextKind;
import jcma.engine.TextUnit;
import jcma.index.TextIndex.TextOccurrence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M3 task-01 — the {@link TextIndex} segment ({@code text.seg}): an inverted trigram index over the
 * D2 text corpus. A substring query is trigram-pruned then verified, returning the match site
 * ({@code file:line:col}) labelled by {@link TextKind}. Mirrors {@link TrigramIndexTest}: round-trip,
 * case-sensitive substring, byte-deterministic, absent → empty, plus per-kind labelling and the
 * {@code includedKinds} toggle.
 */
class TextIndexTest {

    private static final String SEG = TextIndex.FILE_NAME;
    private static final Set<TextKind> ALL = EnumSet.allOf(TextKind.class);

    private static Map<Integer, List<TextUnit>> corpus() {
        return Map.of(
                0, List.of(
                        new TextUnit(TextKind.STRING_LITERAL, 10, 20, 10, 40, "user not found"),
                        new TextUnit(TextKind.COMMENT, 5, 1, 5, 30, "TODO refactor user lookup"),
                        // multi-line unit: the match is on the unit's second line.
                        new TextUnit(TextKind.STRING_LITERAL, 30, 9, 31, 20, "first line\nNEEDLE here")),
                1, List.of(
                        new TextUnit(TextKind.JAVADOC, 1, 1, 3, 3, "Looks up a user by id")));
    }

    @Test
    void roundTripsAndSearchesBySubstring(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        TextIndex.write(p, corpus(), ALL);
        try (TextIndex idx = TextIndex.load(p)) {
            assertEquals(4, idx.unitCount());
            // "user" appears in all three single-line kinds.
            assertEquals(3, idx.search("user").size());
        }
    }

    @Test
    void labelsHitsByKind(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        TextIndex.write(p, corpus(), ALL);
        try (TextIndex idx = TextIndex.load(p)) {
            List<TextOccurrence> notFound = idx.search("not found");
            assertEquals(1, notFound.size());
            assertEquals(TextKind.STRING_LITERAL, notFound.get(0).kind(), "labelled string-literal");
            assertEquals(10, notFound.get(0).line());

            List<TextOccurrence> todo = idx.search("TODO");
            assertEquals(1, todo.size());
            assertEquals(TextKind.COMMENT, todo.get(0).kind(), "labelled comment");

            List<TextOccurrence> doc = idx.search("Looks up");
            assertEquals(1, doc.size());
            assertEquals(TextKind.JAVADOC, doc.get(0).kind(), "labelled javadoc");
            assertEquals(1, doc.get(0).fileId(), "carries its declaring fileId");
        }
    }

    @Test
    void reportsTheMatchedLineWithinAMultiLineUnit(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        TextIndex.write(p, corpus(), ALL);
        try (TextIndex idx = TextIndex.load(p)) {
            List<TextOccurrence> hits = idx.search("NEEDLE");
            assertEquals(1, hits.size());
            assertEquals(31, hits.get(0).line(),
                    "the match is on the unit's second line (startLine 30 + one newline)");
        }
    }

    @Test
    void searchIsCaseSensitive(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        TextIndex.write(p, corpus(), ALL);
        try (TextIndex idx = TextIndex.load(p)) {
            assertTrue(idx.search("TODO REFACTOR").isEmpty(), "case-sensitive: upper != mixed");
        }
    }

    @Test
    void absentSubstringReturnsEmpty(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        TextIndex.write(p, corpus(), ALL);
        try (TextIndex idx = TextIndex.load(p)) {
            assertTrue(idx.search("zzzznope").isEmpty(), "no match → empty, not error");
        }
    }

    @Test
    void includedKindsTogglefiltersAtWrite(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        // Literals-only: comment + javadoc units are dropped at the single write point.
        TextIndex.write(p, corpus(), EnumSet.of(TextKind.STRING_LITERAL));
        try (TextIndex idx = TextIndex.load(p)) {
            assertEquals(2, idx.unitCount(), "only the two string-literal units indexed");
            assertTrue(idx.search("TODO").isEmpty(), "comment text excluded by the toggle");
            assertEquals(1, idx.search("not found").size(), "literal text still indexed");
        }
    }

    @Test
    void bytesAreDeterministicAcrossRebuilds(@TempDir Path dir) throws IOException {
        Path a = dir.resolve("a-" + SEG);
        Path b = dir.resolve("b-" + SEG);
        TextIndex.write(a, corpus(), ALL);
        TextIndex.write(b, corpus(), ALL);
        assertArrayEquals(Files.readAllBytes(a), Files.readAllBytes(b),
                "same units → byte-identical segment (round-trippable like other segs)");
    }

    @Test
    void badMagicRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        Files.write(p, new byte[128]); // zero bytes: not our magic
        assertThrows(IOException.class, () -> TextIndex.load(p), "a bad magic must be rejected");
    }
}
