package jcma.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

/**
 * Task 03 — the dedup UTF-8 string arena (PRD §5.1). The arena interns names/signatures/monikers to
 * a byte-offset ref; equal strings must share one slot, and a ref must round-trip through an
 * FFM segment (the same read path the mmap'd store uses).
 */
class StringArenaTest {

    @Test
    void internDedupsEqualStrings() {
        StringArena.Builder b = new StringArena.Builder();
        int r1 = b.intern("java.util.List");
        int r2 = b.intern("java.util.List");
        assertEquals(r1, r2, "equal strings must intern to the same ref");
    }

    @Test
    void distinctStringsGetDistinctRefs() {
        StringArena.Builder b = new StringArena.Builder();
        assertNotEquals(b.intern("alpha"), b.intern("beta"), "distinct strings must get distinct refs");
    }

    @Test
    void roundTripsThroughSegmentIncludingUtf8AndEmpty() {
        StringArena.Builder b = new StringArena.Builder();
        int rEmpty = b.intern("");
        int rAscii = b.intern("Greeter");
        int rUtf8 = b.intern("café—π");           // non-ASCII: multi-byte UTF-8
        int rAsciiAgain = b.intern("Greeter");
        assertEquals(rAscii, rAsciiAgain, "re-interning is still deduped");

        try (Arena a = Arena.ofConfined()) {
            MemorySegment seg = a.allocate(b.byteSize());
            b.writeTo(seg, 0);
            assertEquals("", StringArena.read(seg, 0, rEmpty), "empty string round-trips");
            assertEquals("Greeter", StringArena.read(seg, 0, rAscii), "ascii string round-trips");
            assertEquals("café—π", StringArena.read(seg, 0, rUtf8), "utf-8 string round-trips byte-exact");
        }
    }
}
