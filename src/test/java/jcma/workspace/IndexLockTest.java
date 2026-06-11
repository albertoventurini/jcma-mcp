package jcma.workspace;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The per-index single-writer lock ({@link IndexLock}). Exercises the policy the M2 lock encodes: a
 * writer {@link IndexLock#acquire acquire} fails fast when held; a query {@link IndexLock#tryAcquire
 * tryAcquire} reports "held" so the caller can degrade to read-only; the lock is re-acquirable once
 * released. A second holder within this JVM stands in for a foreign process (same observable result).
 */
class IndexLockTest {

    @Test
    void acquiresWhenFree(@TempDir Path dir) throws IOException {
        try (IndexLock lock = IndexLock.acquire(dir)) {
            assertNotNull(lock);
        }
    }

    @Test
    void tryAcquireIsEmptyWhileHeld(@TempDir Path dir) throws IOException {
        try (IndexLock held = IndexLock.acquire(dir)) {
            Optional<IndexLock> second = IndexLock.tryAcquire(dir);
            assertTrue(second.isEmpty(), "a held index must not be re-acquirable");
        }
    }

    @Test
    void acquireFailsFastWhileHeld(@TempDir Path dir) throws IOException {
        try (IndexLock held = IndexLock.acquire(dir)) {
            assertThrows(IndexLockedException.class, () -> IndexLock.acquire(dir),
                    "a writer must fail fast, not corrupt, when another process owns the index");
        }
    }

    @Test
    void reacquirableAfterRelease(@TempDir Path dir) throws IOException {
        IndexLock.acquire(dir).close();
        try (IndexLock again = IndexLock.acquire(dir)) {
            assertNotNull(again, "the lock is free again once the holder releases it");
        }
    }
}
