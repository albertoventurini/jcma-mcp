package jcma.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task 08 — the pure-Java {@link Fingerprint#xxHash64} hash and {@link Fingerprint#of} stat+hash.
 *
 * <p>The expected hashes are <b>independent reference vectors</b> generated from the canonical
 * implementation (Python {@code xxhash} 3.7.0, seed 0 unless noted), chosen to exercise every code
 * path: the empty input, a 1-byte and 3-byte tail (&lt;32), a 13-byte tail, a 32-byte input (exactly
 * one main-loop stripe), a 43-byte input (main loop + tail), and a non-zero seed.
 */
class FingerprintTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void xxHash64MatchesCanonicalVectors() {
        assertEquals(0xEF46DB3751D8E999L, Fingerprint.xxHash64(b("")), "empty");
        assertEquals(0xD24EC4F1A98C6E5BL, Fingerprint.xxHash64(b("a")), "1-byte tail");
        assertEquals(0x44BC2CF5AD770999L, Fingerprint.xxHash64(b("abc")), "3-byte tail");
        assertEquals(0xC49AACF8080FE47FL, Fingerprint.xxHash64(b("Hello, World!")), "13-byte tail");
        assertEquals(0x51ACEF020CD423B1L,
                Fingerprint.xxHash64(b("0123456789ABCDEF0123456789ABCDEF")), "32 bytes (one stripe)");
        assertEquals(0x0B242D361FDA71BCL,
                Fingerprint.xxHash64(b("The quick brown fox jumps over the lazy dog")),
                "43 bytes (main loop + tail)");
    }

    @Test
    void xxHash64HonoursTheSeed() {
        byte[] abc = b("abc");
        assertEquals(0x1318DF30094A85FDL, Fingerprint.xxHash64(abc, 0, abc.length, 0x9E3779B1L),
                "abc with seed 0x9E3779B1");
        assertNotEquals(Fingerprint.xxHash64(abc), Fingerprint.xxHash64(abc, 0, abc.length, 0x9E3779B1L),
                "a different seed yields a different hash");
    }

    @Test
    void xxHash64RespectsOffsetAndLength() {
        // Hashing a window of a larger array equals hashing just that window.
        byte[] framed = b("XXabcYY");
        assertEquals(Fingerprint.xxHash64(b("abc")), Fingerprint.xxHash64(framed, 2, 3, 0L));
    }

    @Test
    void xxHash64IsDeterministicAndSensitive() {
        assertEquals(Fingerprint.xxHash64(b("hello world")), Fingerprint.xxHash64(b("hello world")),
                "same bytes → same hash");
        assertNotEquals(Fingerprint.xxHash64(b("hello world")), Fingerprint.xxHash64(b("hello worlc")),
                "a single flipped byte changes the hash");
    }

    @Test
    void ofCapturesSizeMtimeAndContentHash(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("F.java");
        Files.writeString(f, "abc");
        Fingerprint fp = Fingerprint.of(f);
        assertEquals(3, fp.size(), "size is the byte length");
        assertEquals(Files.getLastModifiedTime(f).toMillis(), fp.mtime(), "mtime is the file's mtime");
        assertEquals(0x44BC2CF5AD770999L, fp.contentHash(), "contentHash is xxHash64 of the bytes");
    }
}
