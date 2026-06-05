package jcma.index;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A dedup'd UTF-8 string table (PRD §5.1 "string arena"). Names, signatures, and monikers are
 * interned to a single byte offset so equal strings share one slot; that offset (the "ref") is what
 * the symbol columns store. On disk each entry is {@code [int32 length][utf-8 bytes]} and the ref is
 * the entry's byte offset within the arena.
 *
 * <p>Split into a {@link Builder} (accumulate + serialise) and a static {@link #read} (FFM-mapped
 * lookup) so the write path lives on heap and the read path is pure {@code MemorySegment} access.
 */
public final class StringArena {

    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT_UNALIGNED;

    private StringArena() {}

    /** Accumulates interned strings, then serialises them into a {@link MemorySegment}. */
    public static final class Builder {
        // Insertion order is the serialisation order; value is the assigned byte-offset ref.
        private final Map<String, Integer> refByString = new LinkedHashMap<>();
        private long size;

        /** Intern {@code s}; return its byte-offset ref within the arena (equal strings → same ref). */
        public int intern(String s) {
            Integer existing = refByString.get(s);
            if (existing != null) {
                return existing;
            }
            int ref = Math.toIntExact(size);
            refByString.put(s, ref);
            size += 4L + s.getBytes(StandardCharsets.UTF_8).length;
            return ref;
        }

        /** Total serialised size in bytes (so the caller can size the segment / lay out the file). */
        public long byteSize() {
            return size;
        }

        /** Write the packed arena into {@code seg} starting at {@code offset}. */
        public void writeTo(MemorySegment seg, long offset) {
            for (Map.Entry<String, Integer> e : refByString.entrySet()) {
                byte[] b = e.getKey().getBytes(StandardCharsets.UTF_8);
                long p = offset + e.getValue();
                seg.set(I, p, b.length);
                MemorySegment.copy(MemorySegment.ofArray(b), 0, seg, p + 4, b.length);
            }
        }
    }

    /** Read the interned string at {@code ref} (a byte offset relative to {@code arenaBase}). */
    public static String read(MemorySegment seg, long arenaBase, int ref) {
        long p = arenaBase + ref;
        int len = seg.get(I, p);
        byte[] b = new byte[len];
        MemorySegment.copy(seg, p + 4, MemorySegment.ofArray(b), 0, len);
        return new String(b, StandardCharsets.UTF_8);
    }
}
