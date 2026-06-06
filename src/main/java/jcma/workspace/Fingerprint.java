package jcma.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A file's freshness fingerprint (PRD §5.1) — {@code (size, mtime, contentHash)} — the per-file row
 * the {@link FileTable} stores so {@link Reconciler} can decide, on warm reopen, whether a file
 * changed without re-parsing it. The path is the table key, so it is not duplicated here.
 *
 * <p>The content hash is {@link #xxHash64 xxHash64}, a <b>fast non-cryptographic</b> hash — we need
 * "did the bytes change", not security — implemented pure-Java for minimal native-image reachability
 * (closes the M0/PRD §11 hash decision; no native lib, no extracted {@code .so}).
 */
public record Fingerprint(long size, long mtime, long contentHash) {

    // xxHash64 primes (the canonical constants).
    private static final long P1 = 0x9E3779B185EBCA87L;
    private static final long P2 = 0xC2B2AE3D27D4EB4FL;
    private static final long P3 = 0x165667B19E3779F9L;
    private static final long P4 = 0x85EBCA77C2B2AE63L;
    private static final long P5 = 0x27D4EB2F165667C5L;

    /** Stat {@code path} and hash its bytes into a full fingerprint. */
    public static Fingerprint of(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        long mtime = Files.getLastModifiedTime(path).toMillis();
        return new Fingerprint(bytes.length, mtime, xxHash64(bytes));
    }

    /** xxHash64 (seed 0) of the whole array. */
    public static long xxHash64(byte[] data) {
        return xxHash64(data, 0, data.length, 0L);
    }

    /** xxHash64 of {@code data[off, off+len)} with the given seed. */
    public static long xxHash64(byte[] data, int off, int len, long seed) {
        int end = off + len;
        int p = off;
        long h;
        if (len >= 32) {
            int limit = end - 32;
            long v1 = seed + P1 + P2;
            long v2 = seed + P2;
            long v3 = seed;
            long v4 = seed - P1;
            do {
                v1 = round(v1, readLong(data, p)); p += 8;
                v2 = round(v2, readLong(data, p)); p += 8;
                v3 = round(v3, readLong(data, p)); p += 8;
                v4 = round(v4, readLong(data, p)); p += 8;
            } while (p <= limit);
            h = Long.rotateLeft(v1, 1) + Long.rotateLeft(v2, 7)
                    + Long.rotateLeft(v3, 12) + Long.rotateLeft(v4, 18);
            h = mergeRound(h, v1);
            h = mergeRound(h, v2);
            h = mergeRound(h, v3);
            h = mergeRound(h, v4);
        } else {
            h = seed + P5;
        }
        h += len;

        while (p + 8 <= end) {
            h ^= round(0, readLong(data, p));
            h = Long.rotateLeft(h, 27) * P1 + P4;
            p += 8;
        }
        if (p + 4 <= end) {
            h ^= (readInt(data, p) & 0xFFFFFFFFL) * P1;
            h = Long.rotateLeft(h, 23) * P2 + P3;
            p += 4;
        }
        while (p < end) {
            h ^= (data[p] & 0xFFL) * P5;
            h = Long.rotateLeft(h, 11) * P1;
            p++;
        }

        h ^= h >>> 33;
        h *= P2;
        h ^= h >>> 29;
        h *= P3;
        h ^= h >>> 32;
        return h;
    }

    private static long round(long acc, long input) {
        acc += input * P2;
        acc = Long.rotateLeft(acc, 31);
        acc *= P1;
        return acc;
    }

    private static long mergeRound(long acc, long val) {
        val = round(0, val);
        acc ^= val;
        acc = acc * P1 + P4;
        return acc;
    }

    private static long readLong(byte[] b, int i) {
        return (b[i] & 0xFFL)
                | (b[i + 1] & 0xFFL) << 8
                | (b[i + 2] & 0xFFL) << 16
                | (b[i + 3] & 0xFFL) << 24
                | (b[i + 4] & 0xFFL) << 32
                | (b[i + 5] & 0xFFL) << 40
                | (b[i + 6] & 0xFFL) << 48
                | (b[i + 7] & 0xFFL) << 56;
    }

    private static int readInt(byte[] b, int i) {
        return (b[i] & 0xFF)
                | (b[i + 1] & 0xFF) << 8
                | (b[i + 2] & 0xFF) << 16
                | (b[i + 3] & 0xFF) << 24;
    }
}
