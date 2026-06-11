package jcma.index;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The declaration trigram name index (PRD §5.1 "trigram name index") — the {@code search} surface, the
 * cheap Tier-1 lookup over declarations. Each indexed {@link Entry} ({@code name, symbolId}) contributes
 * its name's distinct 3-grams to a postings map; a substring query AND-intersects its trigrams' posting
 * lists to a candidate set, then <b>verifies the substring</b> against each candidate's name (a trigram
 * match is necessary, not sufficient), then ranks. Queries shorter than three characters carry no
 * trigram, so they fall back to verify-against-all rather than silently returning nothing.
 *
 * <p>The find-references candidate-file prune lives in a separate purpose-built exact-match index now,
 * {@link UsageNameIndex} — the one-format clause of {@code graph-native-index-design} was dropped
 * (executive decision 2026-06-07) once the two consumers' requirements diverged (substring→{@code id}
 * here vs. exact-name→{@code fileId} there). This index is therefore a clean {@code name → symbolId}:
 * no {@code fileId} column (it was derivable from the node).
 *
 * <p><b>Storage = mmap'd</b> (PRD §11 sub-decision, ratified M1 Task-05): like {@link SymbolStore}
 * and {@link Csr} the postings live in an FFM segment read in place, so only the trigram lists a
 * query touches page in and the Java heap stays bounded — not a heap-resident map rebuilt on open.
 * Read/written via {@code Arena.ofShared()} + {@code FileChannel.map} → {@code MemorySegment};
 * mirrors those stores' {@code write(Path,…)} / {@code load(Path)} shape.
 *
 * <p>Matching is <b>case-sensitive</b>: an agent queries the exact identifier it read, and the
 * find-references prune matches a symbol's simple name against case-sensitive Java source. A future
 * case-insensitive M2 tool is additive — a second posting set built over case-folded names under a
 * bumped {@link #VERSION}, leaving this path untouched.
 *
 * <p><b>Segment layout:</b> a {@value #HEADER_BYTES}-byte header (magic, version, entry count {@code
 * nEntries}, trigram count {@code nTrigrams}, section offsets), a dedup {@link StringArena} (names),
 * the entry columns ({@code symbolId, nameRef}), the {@code nTrigrams} sorted trigram keys (each a
 * {@code long} of three packed UTF-16 units), {@code postingOffset[nTrigrams+1]}, and the flat {@code
 * postings[]} (entry indices, grouped by trigram in key order, ascending within a group).
 */
public final class TrigramIndex implements AutoCloseable {

    /** File name of the trigram segment within an index directory (read by {@code jcma search}). */
    public static final String FILE_NAME = "trigrams.seg";

    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT_UNALIGNED;
    private static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG_UNALIGNED;
    private static final long MAGIC = 0x4A434D4154524731L; // "JCMATRG1"
    private static final int VERSION = 2; // v2: dropped the fileId column (find-references → UsageNameIndex)
    private static final int HEADER_BYTES = 64;

    // Entry columns (each is nEntries int32s), struct-of-arrays like SymbolStore.
    private static final int C_SYMBOL_ID = 0;
    private static final int C_NAME_REF = 1;
    private static final int NUM_ENTRY_COLS = 2;

    /**
     * One indexable declaration name: a {@code name} to index by trigram and the {@code symbolId} a
     * search hit reports. Built from the symbol set (see {@link #entriesOf}).
     *
     * @param name     the name to index (its 3-grams become postings); never {@code null}
     * @param symbolId the symbol id a search result returns
     */
    public record Entry(String name, int symbolId) {
        public Entry {
            if (name == null) {
                throw new IllegalArgumentException("entry name must not be null");
            }
        }
    }

    private final Arena arena;
    private final MemorySegment seg;
    private final int nEntries;
    private final int nTrigrams;
    private final long arenaOff;
    private final long entryColOff;
    private final long trigramKeyOff;
    private final long postingOffOff;
    private final long postingsOff;

    private TrigramIndex(Arena arena, MemorySegment seg) {
        this.arena = arena;
        this.seg = seg;
        this.nEntries = seg.get(I, 12);
        this.nTrigrams = seg.get(I, 16);
        this.arenaOff = seg.get(L, 24);
        this.entryColOff = seg.get(L, 32);
        this.trigramKeyOff = seg.get(L, 40);
        this.postingOffOff = seg.get(L, 48);
        this.postingsOff = seg.get(L, 56);
    }

    /** Build the entries for every symbol in {@code store} (name indexed; symbol id carried). */
    public static List<Entry> entriesOf(SymbolStore store) {
        List<Entry> entries = new ArrayList<>(store.size());
        for (int id = 0; id < store.size(); id++) {
            Symbol s = store.symbol(id);
            entries.add(new Entry(s.name() == null ? "" : s.name(), id));
        }
        return entries;
    }

    /** Write {@code entries} to {@code path} as a fresh segment (overwriting any existing file). */
    public static void write(Path path, List<Entry> entries) throws IOException {
        int nEntries = entries.size();

        StringArena.Builder strings = new StringArena.Builder();
        int[] nameRef = new int[nEntries];
        // Postings, keyed by trigram (TreeMap => keys serialise ascending so load can binary-search);
        // within a list, entry indices are appended in increasing order, so they stay ascending.
        TreeMap<Long, List<Integer>> postings = new TreeMap<>();
        for (int e = 0; e < nEntries; e++) {
            String name = entries.get(e).name();
            nameRef[e] = strings.intern(name);
            for (long key : distinctTrigrams(name)) {
                postings.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
            }
        }

        int nTrigrams = postings.size();
        int totalPostings = 0;
        for (List<Integer> list : postings.values()) {
            totalPostings += list.size();
        }

        long arenaLen = strings.byteSize();
        long arenaOff = HEADER_BYTES;
        long entryColOff = arenaOff + arenaLen;
        long trigramKeyOff = entryColOff + (long) NUM_ENTRY_COLS * nEntries * 4;
        long postingOffOff = trigramKeyOff + (long) nTrigrams * 8;
        long postingsOff = postingOffOff + (long) (nTrigrams + 1) * 4;
        long total = postingsOff + (long) totalPostings * 4;

        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                Arena a = Arena.ofShared()) {
            MemorySegment seg = ch.map(FileChannel.MapMode.READ_WRITE, 0, total, a);
            seg.set(L, 0, MAGIC);
            seg.set(I, 8, VERSION);
            seg.set(I, 12, nEntries);
            seg.set(I, 16, nTrigrams);
            seg.set(L, 24, arenaOff);
            seg.set(L, 32, entryColOff);
            seg.set(L, 40, trigramKeyOff);
            seg.set(L, 48, postingOffOff);
            seg.set(L, 56, postingsOff);

            strings.writeTo(seg, arenaOff);
            for (int e = 0; e < nEntries; e++) {
                Entry entry = entries.get(e);
                setCol(seg, entryColOff, nEntries, C_SYMBOL_ID, e, entry.symbolId());
                setCol(seg, entryColOff, nEntries, C_NAME_REF, e, nameRef[e]);
            }

            int ti = 0;
            int row = 0;
            seg.set(I, postingOffOff, 0);
            for (Map.Entry<Long, List<Integer>> p : postings.entrySet()) {
                seg.set(L, trigramKeyOff + (long) ti * 8, p.getKey());
                for (int entryIdx : p.getValue()) {
                    seg.set(I, postingsOff + (long) row * 4, entryIdx);
                    row++;
                }
                seg.set(I, postingOffOff + (long) (ti + 1) * 4, row);
                ti++;
            }
            seg.force();
        }
    }

    /** Memory-map an existing segment at {@code path}; throws {@link IOException} on a bad/short magic. */
    public static TrigramIndex load(Path path) throws IOException {
        long size = Files.size(path);
        Arena a = Arena.ofShared();
        boolean ok = false;
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            if (size < HEADER_BYTES) {
                throw new IOException("trigram index too small (" + size + " bytes): " + path);
            }
            MemorySegment seg = ch.map(FileChannel.MapMode.READ_ONLY, 0, size, a);
            if (seg.get(L, 0) != MAGIC) {
                throw new IOException("bad magic — not a jcma trigram index: " + path);
            }
            int version = seg.get(I, 8);
            if (version != VERSION) {
                throw new IOException("unsupported trigram-index version " + version + ": " + path);
            }
            TrigramIndex idx = new TrigramIndex(a, seg);
            ok = true;
            return idx;
        } finally {
            if (!ok) {
                a.close();
            }
        }
    }

    /** Number of indexed entries. */
    public int entryCount() {
        return nEntries;
    }

    /** Number of distinct trigrams in the postings. */
    public int trigramCount() {
        return nTrigrams;
    }

    /**
     * Symbol ids whose indexed name contains {@code query} as a substring (case-sensitive), ranked:
     * exact match, then prefix, then mid-substring; ties broken by shorter name, then lexicographic
     * name, then id. Deduped by symbol id (best rank kept). Empty for a blank or absent query.
     */
    public List<Integer> searchSymbols(String query) {
        return searchSymbols(SearchSpec.literal(query == null ? "" : query));
    }

    /**
     * Symbol ids whose indexed name matches {@code spec} (literal substring on the fast path, else
     * regex), ranked as above. On the fast path the candidate set is trigram-pruned; when the pattern
     * carries a metacharacter or is case-insensitive the trigram index cannot prune, so every entry is
     * verified against the compiled pattern. The ranking key stays the raw pattern text. Empty for a
     * blank pattern.
     */
    public List<Integer> searchSymbols(SearchSpec spec) {
        if (spec == null || spec.isEmpty()) {
            return List.of();
        }
        List<Integer> verified = verifiedEntries(spec);
        String key = spec.pattern();
        verified.sort(Comparator
                .comparingInt((Integer e) -> rankTier(nameOf(e), key))
                .thenComparingInt(e -> nameOf(e).length())
                .thenComparing(this::nameOf)
                .thenComparingInt(e -> col(C_SYMBOL_ID, e)));
        LinkedHashSet<Integer> ids = new LinkedHashSet<>(verified.size() * 2);
        for (int e : verified) {
            ids.add(col(C_SYMBOL_ID, e));
        }
        return new ArrayList<>(ids);
    }

    @Override
    public void close() {
        arena.close();
    }

    // Entry indices whose name actually matches spec. On the fast path (literal + case-sensitive) the
    // candidate set is trigram-pruned by the literal substring; otherwise (a metacharacter, or
    // case-insensitive) the case-sensitive trigram index cannot prune, so every entry is a candidate.
    // Each candidate is then verified by spec.matches (substring on the fast path, else Matcher.find).
    private List<Integer> verifiedEntries(SearchSpec spec) {
        List<Integer> candidates;
        Set<Long> qtri = spec.fastPathEligible() ? distinctTrigrams(spec.literal()) : Set.of();
        if (spec.fastPathEligible() && !qtri.isEmpty()) {
            candidates = intersect(qtri);
        } else {
            // No usable trigram (short literal, or the regex/insensitive path): verify against every entry.
            candidates = new ArrayList<>(nEntries);
            for (int e = 0; e < nEntries; e++) {
                candidates.add(e);
            }
        }
        List<Integer> verified = new ArrayList<>(candidates.size());
        for (int e : candidates) {
            if (spec.matches(nameOf(e))) {
                verified.add(e);
            }
        }
        return verified;
    }

    // AND-intersection of the posting lists of every query trigram (necessary condition for a match).
    private List<Integer> intersect(Set<Long> qtri) {
        int need = qtri.size();
        int[] count = new int[nEntries];
        for (long key : qtri) {
            int ti = indexOfTrigram(key);
            if (ti < 0) {
                return List.of(); // a required trigram is absent → no entry can match
            }
            int start = seg.get(I, postingOffOff + (long) ti * 4);
            int end = seg.get(I, postingOffOff + (long) (ti + 1) * 4);
            for (int row = start; row < end; row++) {
                count[seg.get(I, postingsOff + (long) row * 4)]++;
            }
        }
        List<Integer> out = new ArrayList<>();
        for (int e = 0; e < nEntries; e++) {
            if (count[e] == need) {
                out.add(e);
            }
        }
        return out;
    }

    // Binary search the sorted trigram-key column for key; returns its index or -1.
    private int indexOfTrigram(long key) {
        int lo = 0;
        int hi = nTrigrams - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long k = seg.get(L, trigramKeyOff + (long) mid * 8);
            if (k < key) {
                lo = mid + 1;
            } else if (k > key) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    private String nameOf(int entry) {
        return StringArena.read(seg, arenaOff, col(C_NAME_REF, entry));
    }

    private int col(int c, int entry) {
        return seg.get(I, entryColOff + ((long) c * nEntries + entry) * 4);
    }

    // 0 = exact, 1 = prefix, 2 = mid-substring (caller guarantees name contains query).
    private static int rankTier(String name, String query) {
        if (name.equals(query)) {
            return 0;
        }
        return name.startsWith(query) ? 1 : 2;
    }

    /** The distinct 3-grams of {@code s} (each three consecutive UTF-16 units packed into a long). */
    private static Set<Long> distinctTrigrams(String s) {
        int len = s.length();
        if (len < 3) {
            return Set.of();
        }
        Set<Long> out = new HashSet<>(len);
        for (int i = 0; i + 3 <= len; i++) {
            long key = ((long) (s.charAt(i) & 0xFFFF) << 32)
                    | ((long) (s.charAt(i + 1) & 0xFFFF) << 16)
                    | (s.charAt(i + 2) & 0xFFFF);
            out.add(key);
        }
        return out;
    }

    private static void setCol(MemorySegment seg, long colOff, int n, int c, int entry, int value) {
        seg.set(I, colOff + ((long) c * n + entry) * 4, value);
    }
}
