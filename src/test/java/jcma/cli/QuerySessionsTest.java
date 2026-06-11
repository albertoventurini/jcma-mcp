package jcma.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import jcma.IndexFixture;
import jcma.obs.Metrics;
import jcma.workspace.FreshnessSource;
import jcma.workspace.IndexLock;
import jcma.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lock-aware query opener ({@link QuerySessions}) — the try→degrade half of the M2 policy. When the
 * write lock is free a query command runs writable and holds it; when another jcma process owns it, the
 * command degrades to a read-only session that still answers. (A second holder in this JVM stands in for
 * a live {@code serve}.)
 */
class QuerySessionsTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/indexer");

    private static PrintStream discard() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    @Test
    void holdsTheLockAndRunsWritableWhenFree(@TempDir Path tmp) throws Exception {
        Path indexDir = tmp.resolve("idx");
        IndexFixture.build(FIXTURE, indexDir);

        try (QuerySessions.Held held = QuerySessions.open(
                indexDir, Workspace.discover(FIXTURE), FreshnessSource.none(), Metrics.noop(), discard())) {
            assertFalse(held.readOnly(), "a free index is opened writable");
            assertTrue(IndexLock.tryAcquire(indexDir).isEmpty(),
                    "a writable query session must hold the write lock for its lifetime");
            assertFalse(held.service().declarations("Shape", java.time.Duration.ofSeconds(5)).isEmpty());
        }
    }

    @Test
    void degradesToReadOnlyWhenLockHeld(@TempDir Path tmp) throws Exception {
        Path indexDir = tmp.resolve("idx");
        IndexFixture.build(FIXTURE, indexDir);

        try (IndexLock owner = IndexLock.acquire(indexDir)) { // stands in for a live `serve`
            try (QuerySessions.Held held = QuerySessions.open(
                    indexDir, Workspace.discover(FIXTURE), FreshnessSource.none(), Metrics.noop(), discard())) {
                assertTrue(held.readOnly(), "a held index degrades the query session to read-only");
                assertFalse(held.service().declarations("Shape", java.time.Duration.ofSeconds(5)).isEmpty(),
                        "the read-only session still answers");
            }
        }
    }
}
