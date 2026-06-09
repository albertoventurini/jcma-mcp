package jcma.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.cli.Main;
import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.resolve.References;
import jcma.session.AnalysisSession;
import jcma.workspace.Workspace;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task-12 (red-first) — the time-box + cancellation contract of {@link QueryService}. The simplified
 * M1 scope (no concurrency, no partial result): a query that exceeds its deadline throws
 * {@link QueryTimeoutException} <b>promptly</b> and the worker observes the cancellation and stops;
 * a generous deadline returns the <b>full</b> answer unchanged from the underlying session.
 */
class QueryServiceTest {

    private static final Path REFS = Path.of("src/test/resources/fixtures/resolve/refs");
    private static final String TARGET_MONIKER = "app/Service#run().";

    @Test
    void timeBoxThrowsPromptlyAndCancelsTheWorker(@TempDir Path indexDir) throws Exception {
        index(REFS, indexDir);
        try (QueryService svc = open(indexDir)) {
            CountDownLatch stopped = new CountDownLatch(1);

            long t0 = System.nanoTime();
            assertThrows(QueryTimeoutException.class, () -> svc.execute(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(10); // a long op that only ends on cancellation
                    }
                } catch (InterruptedException expected) {
                    // cancel(true) interrupted the worker — cooperative stop
                } finally {
                    stopped.countDown();
                }
                return "never";
            }, Duration.ofMillis(50)));
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

            assertTrue(elapsedMs < 2_000, "the caller returns promptly after the deadline, not hangs: " + elapsedMs + " ms");
            assertTrue(stopped.await(2, TimeUnit.SECONDS), "the worker observed the cancellation and stopped");
        }
    }

    @Test
    void generousDeadlineReturnsTheFullAnswer(@TempDir Path indexDir) throws Exception {
        index(REFS, indexDir);
        try (QueryService svc = open(indexDir)) {
            Symbol target = svc.declarations("run", Duration.ofSeconds(10)).stream()
                    .filter(s -> TARGET_MONIKER.equals(s.moniker()))
                    .findFirst().orElseThrow(() -> new AssertionError("Service.run() not indexed"));

            References refs = svc.findReferences(target, Duration.ofSeconds(10));

            assertEquals(3, refs.totalRefs(), "full confirmed set, no false timeout");
            assertEquals(3, refs.groups().size(), "one group per enclosing method");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static QueryService open(Path indexDir) throws Exception {
        return new QueryService(AnalysisSession.open(indexDir, Workspace.ofSourceRoot(REFS), Metrics.create()));
    }

    private static void index(Path repo, Path indexDir) {
        PrintStream sink = new PrintStream(OutputStream.nullOutputStream());
        assertEquals(0, Main.run(new String[] {"index", repo.toString(), indexDir.toString()}, sink, sink),
                "jcma index should succeed");
    }
}
