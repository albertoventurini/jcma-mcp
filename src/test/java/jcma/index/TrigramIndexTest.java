package jcma.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jcma.index.TrigramIndex.Entry;

/**
 * Task 05 — the FFM trigram name index. Postings map a name's 3-grams → entries
 * ({@code symbolId, fileId, name}); a substring query AND-intersects its trigram posting lists, then
 * <em>verifies</em> the substring (a trigram match is necessary, not sufficient), then ranks. Two
 * outputs are exercised: {@link TrigramIndex#searchSymbols} (ranked ids, the {@code search} surface)
 * and {@link TrigramIndex#candidateFiles} (the find-references file-pruning primitive, PRD §5.1).
 */
class TrigramIndexTest {

    private static final String SEG = TrigramIndex.FILE_NAME;

    private static List<Entry> entries(String... names) {
        // symbolId = index, fileId = index, so each name gets a distinct symbol in its own file.
        List<Entry> es = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            es.add(new Entry(names[i], i, i));
        }
        return es;
    }

    private static Set<Integer> ids(TrigramIndex idx, String query) {
        return Set.copyOf(idx.searchSymbols(query));
    }

    @Test
    void roundTripsAndSearchesBySubstring(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        TrigramIndex.write(p, entries("Greeter", "greet", "render"));
        try (TrigramIndex idx = TrigramIndex.load(p)) {
            assertEquals(3, idx.entryCount());
            // "reet" is a substring of "Greeter" (#0) and "greet" (#1), not "render" (#2).
            assertEquals(Set.of(0, 1), ids(idx, "reet"));
            // "end" only in "render".
            assertEquals(Set.of(2), ids(idx, "end"));
        }
    }

    @Test
    void searchIsCaseSensitive(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        // Java identifiers are case-sensitive and an agent queries the exact name it read; a
        // lowercase query must NOT match a camelCase symbol.
        TrigramIndex.write(p, entries("toString", "hashCode"));
        try (TrigramIndex idx = TrigramIndex.load(p)) {
            assertEquals(Set.of(0), ids(idx, "toString"));
            assertTrue(idx.searchSymbols("tostring").isEmpty(), "case-sensitive: 'tostring' != 'toString'");
        }
    }

    @Test
    void trigramFalsePositiveIsFilteredByVerification(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        // "abczbcx" contains every trigram of "abcx" ("abc" and "bcx") but NOT the substring "abcx";
        // "fooabcxbar" actually contains "abcx". A naive trigram-only match would return both.
        TrigramIndex.write(p, entries("abczbcx", "fooabcxbar"));
        try (TrigramIndex idx = TrigramIndex.load(p)) {
            assertEquals(Set.of(1), ids(idx, "abcx"),
                    "the trigram-match-but-not-substring entry must be filtered out by verification");
        }
    }

    @Test
    void rankingPrefersExactThenPrefixThenSubstring(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        // "wraptoString" carries the lowercase substring mid-name (case-sensitive: a camelCase
        // "...ToString" would NOT match "toString", which is the point).
        TrigramIndex.write(p, entries("toString", "toStringBuilder", "wraptoString"));
        try (TrigramIndex idx = TrigramIndex.load(p)) {
            // exact (#0) before prefix (#1) before mid-substring (#2).
            assertEquals(List.of(0, 1, 2), idx.searchSymbols("toString"));
        }
    }

    @Test
    void shortQueryUnderThreeCharsStillMatches(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        // "io" has no trigram at all; "ration" contains the substring "io". A 2-char query has no
        // trigram either, so the index must fall back to verify-against-all rather than silently
        // returning nothing.
        TrigramIndex.write(p, entries("io", "ration", "render"));
        try (TrigramIndex idx = TrigramIndex.load(p)) {
            assertEquals(Set.of(0, 1), ids(idx, "io"));
            assertEquals(0, idx.searchSymbols("io").get(0), "exact 'io' outranks the substring match");
        }
    }

    @Test
    void absentSubstringReturnsEmpty(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        TrigramIndex.write(p, entries("Greeter", "render"));
        try (TrigramIndex idx = TrigramIndex.load(p)) {
            assertTrue(idx.searchSymbols("xyzzy").isEmpty(), "no candidate for an absent name");
            assertEquals(0, idx.candidateFiles("xyzzy").length);
        }
    }

    @Test
    void rareNamePrunesToASmallFractionOfFiles(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        List<Entry> es = new ArrayList<>();
        int files = 60;
        for (int f = 0; f < files; f++) {
            es.add(new Entry("process", f, f)); // a common name declared in every file
        }
        es.add(new Entry("zylophoneFactory", 999, 7)); // a rare name, only in file 7
        TrigramIndex.write(p, es);
        try (TrigramIndex idx = TrigramIndex.load(p)) {
            int[] rare = idx.candidateFiles("zylophone");
            assertEquals(1, rare.length, "rare name prunes to one file");
            assertEquals(7, rare[0]);
            assertTrue(rare.length < files / 10, "rare name touches a small fraction of files");

            int[] common = idx.candidateFiles("process");
            assertEquals(files, common.length, "the common name's files are all returned");
            assertEquals(IntStream.range(0, files).boxed().toList(),
                    java.util.Arrays.stream(common).boxed().toList(), "and they are exactly files 0..59, sorted");
        }
    }

    @Test
    void candidateFilesAreUniqueAndSorted(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        // Two distinct symbols named "render" declared in the same file 3 -> file id appears once.
        TrigramIndex.write(p, List.of(
                new Entry("render", 0, 3),
                new Entry("renderAll", 1, 3),
                new Entry("prerender", 2, 1)));
        try (TrigramIndex idx = TrigramIndex.load(p)) {
            assertEquals(List.of(1, 3),
                    java.util.Arrays.stream(idx.candidateFiles("render")).boxed().toList());
        }
    }

    @Test
    void emptyIndexRoundTrips(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        TrigramIndex.write(p, List.of());
        try (TrigramIndex idx = TrigramIndex.load(p)) {
            assertEquals(0, idx.entryCount());
            assertTrue(idx.searchSymbols("anything").isEmpty());
            assertEquals(0, idx.candidateFiles("anything").length);
        }
    }

    @Test
    void entriesOfBuildsFromASymbolStore(@TempDir Path dir) throws IOException {
        Path symSeg = dir.resolve(SymbolStore.FILE_NAME);
        String type = Moniker.forType(Moniker.forPackage("com.acme"), "Greeter");
        String greet = Moniker.forMethod(type, "greet", List.of());
        SymbolStore.write(symSeg, List.of(
                new Symbol(type, SymbolKind.CLASS, 0, null, 4, new Range(1, 1, 3, 1), "Greeter", null),
                new Symbol(greet, SymbolKind.METHOD, 0, type, 4, new Range(2, 3, 2, 20), "greet", null)));
        try (SymbolStore store = SymbolStore.load(symSeg)) {
            Path p = dir.resolve(SEG);
            TrigramIndex.write(p, TrigramIndex.entriesOf(store));
            try (TrigramIndex idx = TrigramIndex.load(p)) {
                // The store ids round-trip through the trigram search: "greet" -> the method symbol.
                int greetId = store.idOf(greet).getAsInt();
                assertTrue(idx.searchSymbols("greet").contains(greetId));
                // Both symbols are declared in file 4, so pruning by "ree" yields exactly file 4.
                assertEquals(List.of(4),
                        java.util.Arrays.stream(idx.candidateFiles("ree")).boxed().toList());
            }
        }
    }

    @Test
    void badMagicRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        Files.write(p, new byte[128]); // zero bytes: not our magic
        assertThrows(IOException.class, () -> TrigramIndex.load(p), "a bad magic must be rejected, not read");
    }
}
