package jcma.index;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeSet;

/**
 * The use-site name index ({@code usage-names.seg}) — the {@code find_references} candidate-file
 * prune (PRD §5.1). A purpose-built <b>exact-match</b> inverted index: a simple name (as read from
 * {@link Symbol#name()} / {@code getNameAsString}) maps to the sorted, distinct file ids that contain
 * an unresolved <em>use</em> of it, so the first {@code find_references(X)} resolves a handful of
 * files instead of the tree.
 *
 * <p><b>Why not the declaration {@link TrigramIndex}?</b> The two consumers diverged (executive
 * decision 2026-06-07, superseding the one-format clause of {@code graph-native-index-design}): the
 * declaration index answers fuzzy <em>substring</em> search and returns {@code nodeId}s; this one
 * answers an <em>exact</em> simple name and returns {@code fileId}s. On this repo's own index the
 * shared trigram form was 60% trigram machinery the exact path never touches plus a 12% all-{@code -1}
 * dead {@code symbolId} column; the purpose-built form is ~11.5× smaller (494 KB → ~43 KB). Exact match
 * is also tighter pruning: a substring query only ever over-matched (it matched {@code sayHello} for
 * {@code hello}), so correctness is preserved — every true use-site records the exact simple name.
 *
 * <p><b>Storage = mmap'd</b>, in the house style ({@link SymbolStore}, {@link TrigramIndex}): an
 * FFM {@code Arena.ofShared()} + {@code FileChannel.map} → {@code MemorySegment}, validate-on-read
 * (magic + version), no heap deserialisation. <b>Layout:</b> a {@value #HEADER_BYTES}-byte header
 * (magic, version, {@code nNames}, {@code nPostings}, section offsets), a dedup {@link StringArena}
 * (the distinct names), {@code nameRef[nNames]} <b>sorted by name</b> (so a lookup binary-searches the
 * dictionary), {@code postingOff[nNames+1]}, and {@code postings[]} — each name's sorted distinct
 * {@code fileId}s as plain {@code int32}, concatenated (the offset-slice trick; delta+varint was
 * weighed and rejected for v1: ~2% more saving for variable-width-decode complexity).
 */
public final class UsageNameIndex implements AutoCloseable {

    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT_UNALIGNED;
    private static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG_UNALIGNED;
    private static final long MAGIC = 0x4A434D4155534731L; // "JCMAUSG1"
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 64;

    private final Arena arena;
    private final MemorySegment seg;
    private final int nNames;
    private final int nPostings;
    private final long arenaOff;
    private final long nameRefOff;
    private final long postingOffOff;
    private final long postingsOff;

    private UsageNameIndex(Arena arena, MemorySegment seg) {
        this.arena = arena;
        this.seg = seg;
        this.nNames = seg.get(I, 12);
        this.nPostings = seg.get(I, 16);
        this.arenaOff = seg.get(L, 24);
        this.nameRefOff = seg.get(L, 32);
        this.postingOffOff = seg.get(L, 40);
        this.postingsOff = seg.get(L, 48);
    }

    /**
     * Write {@code byName} ({@code name → fileIds}) to {@code path} as a fresh segment (overwriting
     * any existing file). The map's key order is the on-disk name order, so a {@link SortedMap}'s
     * natural (ascending) ordering is what makes {@link #candidateFiles} a binary search. Each name's
     * file ids are deduped and sorted ascending here, so the caller may pass a raw {@link Collection}.
     */
    public static void write(Path path, SortedMap<String, ? extends Collection<Integer>> byName)
            throws IOException {
        int nNames = byName.size();

        StringArena.Builder strings = new StringArena.Builder();
        int[] nameRef = new int[nNames];
        int[][] postings = new int[nNames][];
        int nPostings = 0;
        int idx = 0;
        for (Map.Entry<String, ? extends Collection<Integer>> e : byName.entrySet()) {
            nameRef[idx] = strings.intern(e.getKey());
            TreeSet<Integer> distinct = new TreeSet<>(e.getValue());
            int[] files = new int[distinct.size()];
            int k = 0;
            for (int f : distinct) {
                files[k++] = f;
            }
            postings[idx] = files;
            nPostings += files.length;
            idx++;
        }

        long arenaLen = strings.byteSize();
        long arenaOff = HEADER_BYTES;
        long nameRefOff = arenaOff + arenaLen;
        long postingOffOff = nameRefOff + (long) nNames * 4;
        long postingsOff = postingOffOff + (long) (nNames + 1) * 4;
        long total = postingsOff + (long) nPostings * 4;

        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                Arena a = Arena.ofShared()) {
            MemorySegment seg = ch.map(FileChannel.MapMode.READ_WRITE, 0, total, a);
            seg.set(L, 0, MAGIC);
            seg.set(I, 8, VERSION);
            seg.set(I, 12, nNames);
            seg.set(I, 16, nPostings);
            seg.set(L, 24, arenaOff);
            seg.set(L, 32, nameRefOff);
            seg.set(L, 40, postingOffOff);
            seg.set(L, 48, postingsOff);

            strings.writeTo(seg, arenaOff);
            int row = 0;
            seg.set(I, postingOffOff, 0);
            for (int n = 0; n < nNames; n++) {
                seg.set(I, nameRefOff + (long) n * 4, nameRef[n]);
                for (int f : postings[n]) {
                    seg.set(I, postingsOff + (long) row * 4, f);
                    row++;
                }
                seg.set(I, postingOffOff + (long) (n + 1) * 4, row);
            }
            seg.force();
        }
    }

    /** Memory-map an existing segment at {@code path}; throws {@link IOException} on a bad/short magic. */
    public static UsageNameIndex load(Path path) throws IOException {
        long size = Files.size(path);
        Arena a = Arena.ofShared();
        boolean ok = false;
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            if (size < HEADER_BYTES) {
                throw new IOException("usage-name index too small (" + size + " bytes): " + path);
            }
            MemorySegment seg = ch.map(FileChannel.MapMode.READ_ONLY, 0, size, a);
            if (seg.get(L, 0) != MAGIC) {
                throw new IOException("bad magic — not a jcma usage-name index: " + path);
            }
            int version = seg.get(I, 8);
            if (version != VERSION) {
                throw new IOException("unsupported usage-name-index version " + version + ": " + path);
            }
            UsageNameIndex idx = new UsageNameIndex(a, seg);
            ok = true;
            return idx;
        } finally {
            if (!ok) {
                a.close();
            }
        }
    }

    /** Number of distinct indexed names. */
    public int nNames() {
        return nNames;
    }

    /** Total number of {@code (name, fileId)} postings (distinct file ids summed over all names). */
    public int entryCount() {
        return nPostings;
    }

    /**
     * File ids that contain a use of <b>exactly</b> {@code exactName} (case-sensitive simple-name
     * equality, not substring) — the find-references candidate-file prune. The result is the name's
     * stored posting list: distinct file ids, sorted ascending. Empty for a blank or absent name.
     */
    public int[] candidateFiles(String exactName) {
        if (exactName == null || exactName.isEmpty()) {
            return new int[0];
        }
        int n = indexOfName(exactName);
        if (n < 0) {
            return new int[0];
        }
        int start = seg.get(I, postingOffOff + (long) n * 4);
        int end = seg.get(I, postingOffOff + (long) (n + 1) * 4);
        int[] out = new int[end - start];
        for (int i = 0; i < out.length; i++) {
            out[i] = seg.get(I, postingsOff + (long) (start + i) * 4);
        }
        return out;
    }

    @Override
    public void close() {
        arena.close();
    }

    // Binary search the name dictionary (sorted ascending by String natural order) for an exact match.
    private int indexOfName(String name) {
        int lo = 0;
        int hi = nNames - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = nameOf(mid).compareTo(name);
            if (cmp < 0) {
                lo = mid + 1;
            } else if (cmp > 0) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    private String nameOf(int n) {
        return StringArena.read(seg, arenaOff, seg.get(I, nameRefOff + (long) n * 4));
    }
}
