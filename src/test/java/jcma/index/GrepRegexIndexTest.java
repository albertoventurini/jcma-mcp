package jcma.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.engine.TextKind;
import jcma.engine.TextUnit;
import jcma.index.TextIndex.TextOccurrence;
import jcma.index.TrigramIndex.Entry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M3 task-03 (red-first) — the {@link SearchSpec} match policy threaded through the index seams.
 * Asserts the index-level invariants the {@code grep_java} regex feature rests on, independent of the
 * tool wiring: regex over a {@link TextIndex} segment (per-line MULTILINE anchoring, char classes,
 * case-insensitivity), {@code fixed_string} vs. regex divergence, the literal fast path being result-
 * identical, regex over the {@link LsmStore} base ∪ overlay, and regex/insensitive over a
 * {@link TrigramIndex} (verify-all when the trigram fast path is ineligible).
 */
class GrepRegexIndexTest {

    private static final Set<TextKind> ALL = java.util.EnumSet.allOf(TextKind.class);

    private static Map<Integer, List<TextUnit>> corpus() {
        return Map.of(0, List.of(
                new TextUnit(TextKind.STRING_LITERAL, 10, 5, 10, 30, "lookup failed; code 256"),
                new TextUnit(TextKind.COMMENT, 12, 1, 12, 30, "see [0-9] pattern"),
                // multi-line unit: first line "first beta", second "second gamma".
                new TextUnit(TextKind.JAVADOC, 1, 4, 2, 20, "first beta\nsecond gamma")));
    }

    private static TextIndex textIndex(Path dir) throws IOException {
        Path p = dir.resolve(TextIndex.FILE_NAME);
        TextIndex.write(p, corpus(), ALL);
        return TextIndex.load(p);
    }

    // ------------------------------------------------------------------ TextIndex

    @Test
    void textIndexRegexMatchesAndAnchorsPerLine(@TempDir Path dir) throws IOException {
        try (TextIndex idx = textIndex(dir)) {
            // `l..kup` (any-char) hits the literal "lookup …" on line 10.
            List<TextOccurrence> dot = idx.search(SearchSpec.of("l..kup", false, true));
            assertEquals(1, dot.size());
            assertEquals(10, dot.get(0).line());
            // MULTILINE: `^second` anchors the 2nd physical line of the Javadoc unit (line 2).
            List<TextOccurrence> anchored = idx.search(SearchSpec.of("^second", false, true));
            assertEquals(1, anchored.size(), "`^` matches per physical line (MULTILINE)");
            assertEquals(2, anchored.get(0).line());
            // `^beta` does NOT match — beta is mid-line on the first line.
            assertTrue(idx.search(SearchSpec.of("^beta", false, true)).isEmpty(), "`^beta` is mid-line");
            // `gamma$` anchors the end of the 2nd line.
            assertEquals(1, idx.search(SearchSpec.of("gamma$", false, true)).size(), "`$` matches line end");
        }
    }

    @Test
    void textIndexFixedStringDivergesFromRegex(@TempDir Path dir) throws IOException {
        try (TextIndex idx = textIndex(dir)) {
            // Regex `[0-9]` hits BOTH the literal's digits (256) and the comment's "[0-9]" digits.
            assertEquals(2, idx.search(SearchSpec.of("[0-9]", false, true)).size(), "regex char class spans both units");
            // Literal `[0-9]` hits ONLY the comment carrying the text "[0-9]".
            List<TextOccurrence> lit = idx.search(SearchSpec.of("[0-9]", true, true));
            assertEquals(1, lit.size(), "fixed_string `[0-9]` is the literal substring only");
            assertEquals(TextKind.COMMENT, lit.get(0).kind());
        }
    }

    @Test
    void textIndexCaseInsensitiveOptIn(@TempDir Path dir) throws IOException {
        try (TextIndex idx = textIndex(dir)) {
            assertTrue(idx.search(SearchSpec.of("LOOKUP", false, true)).isEmpty(), "sensitive by default: miss");
            assertEquals(1, idx.search(SearchSpec.of("LOOKUP", false, false)).size(), "case_sensitive:false matches");
        }
    }

    @Test
    void textIndexLiteralFastPathIsResultIdentical(@TempDir Path dir) throws IOException {
        try (TextIndex idx = textIndex(dir)) {
            // A metachar-free query routed via the (default-sensitive) fast path equals the String overload.
            assertEquals(idx.search("lookup").size(), idx.search(SearchSpec.of("lookup", false, true)).size());
        }
    }

    // ------------------------------------------------------------------ LsmStore base ∪ overlay

    private static FileIndex fileWithText(int fileId, TextKind kind, String text) {
        return new FileIndex(fileId, List.of(), List.of(),
                List.of(new TextUnit(kind, 1, 1, 1, 1 + text.length(), text)));
    }

    @Test
    void lsmRegexSpansBaseAndOverlay(@TempDir Path dir) throws IOException {
        try (LsmStore store = LsmStore.open(dir, CompactionPolicy.manual())) {
            store.applyEdit(fileWithText(0, TextKind.STRING_LITERAL, "alpha lookup beta"));
            store.compact(); // file 0 → base
            store.applyEdit(fileWithText(1, TextKind.COMMENT, "gamma lookup delta")); // file 1 → overlay
            assertEquals(2, store.searchText(SearchSpec.of("l..kup", false, true)).size(),
                    "regex matches across base ∪ overlay");
            assertEquals(2, store.searchText(SearchSpec.of("LOOKUP", false, false)).size(),
                    "case-insensitive across base ∪ overlay");
            assertTrue(store.searchText(SearchSpec.of("LOOKUP", false, true)).isEmpty(),
                    "sensitive miss across both");
        }
    }

    // ------------------------------------------------------------------ TrigramIndex

    private static TrigramIndex trigram(Path dir, String... names) throws IOException {
        Path p = dir.resolve(TrigramIndex.FILE_NAME);
        java.util.List<Entry> es = new java.util.ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            es.add(new Entry(names[i], i));
        }
        TrigramIndex.write(p, es);
        return TrigramIndex.load(p);
    }

    @Test
    void trigramRegexAndInsensitiveVerifyAll(@TempDir Path dir) throws IOException {
        try (TrigramIndex idx = trigram(dir, "lookup", "cacheSize", "render")) {
            // Regex `c.che` matches "cacheSize"; the literal "c.che" matches nothing.
            assertEquals(List.of(1), idx.searchSymbols(SearchSpec.of("c.che", false, true)), "regex any-char");
            assertTrue(idx.searchSymbols(SearchSpec.of("c.che", true, true)).isEmpty(), "fixed_string is literal");
            // Case-insensitive opt-in (the trigram index is case-sensitive → verify-all).
            assertEquals(List.of(0), idx.searchSymbols(SearchSpec.of("LOOKUP", false, false)), "insensitive matches");
            assertTrue(idx.searchSymbols(SearchSpec.of("LOOKUP", false, true)).isEmpty(), "sensitive miss");
        }
    }

    @Test
    void searchSpecClassifiesLiteralAndFastPath() {
        assertTrue(SearchSpec.of("lookup", false, true).fastPathEligible(), "metachar-free + sensitive = fast path");
        assertFalse(SearchSpec.of("look.p", false, true).fastPathEligible(), "a metachar disables the fast path");
        assertFalse(SearchSpec.of("lookup", false, false).fastPathEligible(), "insensitive disables the fast path");
        assertTrue(SearchSpec.of("look.p", true, true).isLiteral(), "fixed_string forces literal");
        assertEquals("look.p", SearchSpec.of("look.p", true, true).literal(), "literal() is the raw substring");
    }
}
