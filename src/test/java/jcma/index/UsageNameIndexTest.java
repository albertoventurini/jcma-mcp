package jcma.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The purpose-built use-site name index ({@code usage-names.seg}) — a split-out from the declaration
 * {@link TrigramIndex} (executive decision 2026-06-07). Unlike the substring/trigram declaration
 * index, this is an <b>exact-match</b> inverted index: a simple name (from {@code Symbol.name()})
 * maps to the sorted, distinct file ids that <em>use</em> it — the {@code find_references}
 * candidate-file prune (PRD §5.1). Exact match is the precision win: substring only ever over-matched.
 */
class UsageNameIndexTest {

    private static final String SEG = UsageNameIndexer.FILE_NAME; // "usage-names.seg"

    /** A {@code name -> fileIds} map in the shape {@link UsageNameIndex#write} consumes. */
    private static TreeMap<String, Collection<Integer>> map() {
        return new TreeMap<>();
    }

    private static List<Integer> files(UsageNameIndex idx, String name) {
        return Arrays.stream(idx.candidateFiles(name)).boxed().toList();
    }

    @Test
    void roundTripsWriteLoad(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        TreeMap<String, Collection<Integer>> m = map();
        m.put("hello", new TreeSet<>(List.of(2)));
        m.put("render", new TreeSet<>(List.of(1, 5)));
        UsageNameIndex.write(p, m);
        try (UsageNameIndex idx = UsageNameIndex.load(p)) {
            assertEquals(2, idx.nNames(), "two distinct names");
            assertEquals(3, idx.entryCount(), "three distinct (name,file) postings");
            assertEquals(List.of(2), files(idx, "hello"));
            assertEquals(List.of(1, 5), files(idx, "render"));
        }
    }

    @Test
    void matchesExactNameNotSubstring(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        // "hello" used in file 1; the only uses in files 2 and 3 are *different* names that merely
        // contain "hello" as a substring. The old trigram/substring path matched all three; exact
        // match must return only file 1 — the precision win.
        TreeMap<String, Collection<Integer>> m = map();
        m.put("hello", new TreeSet<>(List.of(1)));
        m.put("sayHello", new TreeSet<>(List.of(2)));
        m.put("helloWorld", new TreeSet<>(List.of(3)));
        UsageNameIndex.write(p, m);
        try (UsageNameIndex idx = UsageNameIndex.load(p)) {
            assertEquals(List.of(1), files(idx, "hello"), "exact 'hello' matches only the file using 'hello'");
            assertEquals(List.of(2), files(idx, "sayHello"));
            assertEquals(List.of(3), files(idx, "helloWorld"));
            assertTrue(idx.candidateFiles("ell").length == 0, "no substring match: 'ell' is no name's exact value");
        }
    }

    @Test
    void dedupsAndSortsFileIds(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        // "hello" used three times in file 5 (+ file 2 + file 9) — passed as a raw list with repeats.
        // The index dedups (5 appears once) and sorts ascending regardless of insertion order.
        TreeMap<String, Collection<Integer>> m = map();
        m.put("hello", List.of(5, 5, 5, 2, 9, 2));
        UsageNameIndex.write(p, m);
        try (UsageNameIndex idx = UsageNameIndex.load(p)) {
            assertEquals(List.of(2, 5, 9), files(idx, "hello"), "distinct + sorted ascending");
            assertEquals(3, idx.entryCount());
        }
    }

    @Test
    void absentNameAndBlankQueryReturnEmpty(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        TreeMap<String, Collection<Integer>> m = map();
        m.put("hello", new TreeSet<>(List.of(1)));
        UsageNameIndex.write(p, m);
        try (UsageNameIndex idx = UsageNameIndex.load(p)) {
            assertEquals(0, idx.candidateFiles("xyzzy").length, "absent name → empty");
            assertEquals(0, idx.candidateFiles("").length, "blank query → empty");
        }
    }

    @Test
    void emptyIndexRoundTrips(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        UsageNameIndex.write(p, map());
        try (UsageNameIndex idx = UsageNameIndex.load(p)) {
            assertEquals(0, idx.nNames());
            assertEquals(0, idx.entryCount());
            assertEquals(0, idx.candidateFiles("anything").length);
        }
    }

    @Test
    void rareNamePrunesToASingleFile(@TempDir Path dir) throws IOException {
        // Exact-match analogue of TrigramIndexTest#rareNamePrunesToASmallFractionOfFiles: a common
        // name used in every file, a rare name used in only one — and the common name's exact value
        // does NOT match a longer name that merely starts with it.
        Path p = dir.resolve(SEG);
        int files = 60;
        TreeMap<String, Collection<Integer>> m = map();
        TreeSet<Integer> all = new TreeSet<>();
        for (int f = 0; f < files; f++) {
            all.add(f);
        }
        m.put("process", all);
        m.put("processAll", new TreeSet<>(List.of(7)));
        m.put("zylophoneFactory", new TreeSet<>(List.of(7)));
        UsageNameIndex.write(p, m);
        try (UsageNameIndex idx = UsageNameIndex.load(p)) {
            assertEquals(List.of(7), files(idx, "zylophoneFactory"), "rare name prunes to one file");
            assertEquals(IntStream.range(0, files).boxed().toList(), files(idx, "process"),
                    "the common name's files are all returned, sorted 0..59");
            assertEquals(List.of(7), files(idx, "processAll"),
                    "exact: 'process' must not also collect 'processAll' files, nor vice versa");
        }
    }

    @Test
    void candidateFilesAreUniqueAndSortedAcrossNames(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        TreeMap<String, Collection<Integer>> m = map();
        m.put("render", List.of(3, 1, 3)); // duplicate file 3 collapses; result sorted
        UsageNameIndex.write(p, m);
        try (UsageNameIndex idx = UsageNameIndex.load(p)) {
            assertEquals(List.of(1, 3), files(idx, "render"));
        }
    }

    @Test
    void badMagicRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve(SEG);
        Files.write(p, new byte[128]); // zero bytes: not our magic
        assertThrows(IOException.class, () -> UsageNameIndex.load(p), "a bad magic must be rejected, not read");
    }
}
