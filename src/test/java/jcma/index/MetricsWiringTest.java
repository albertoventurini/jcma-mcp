package jcma.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.obs.Metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task 06 · P3 — metrics wired into the index pipeline. Asserts the probes are populated and, most
 * importantly, placed at <b>coarse boundaries</b> (parse timed once per file, not per symbol) — the
 * placement that keeps overhead negligible — plus that {@link Metrics#noop()} records nothing.
 */
class MetricsWiringTest {

    private static final Path SHAPES_ROOT = Path.of("src/test/resources/fixtures/indexer");

    @Test
    void indexRecordsCoarseThroughputMetrics(@TempDir Path dir) throws IOException {
        Metrics m = Metrics.create();
        try (LsmStore store = LsmStore.open(dir, CompactionPolicy.manual(), m)) {
            Indexer.IndexStats stats = new Indexer(m).indexRepo(List.of(new SourceRoot(SHAPES_ROOT, SourceSet.MAIN)), store);

            assertEquals(4, m.counter("index.files").sum());
            assertEquals(stats.symbols(), m.counter("index.symbols").sum());
            assertEquals(1, m.timer("compaction").count(), "indexRepo compacts once");

            // The negligibility-by-placement proof: parse is timed once PER FILE (4), never per
            // symbol (17) — a probe in the inner loop would make this count == symbols.
            assertEquals(4, m.timer("index.parse").count());
            assertTrue(m.timer("index.parse").count() < stats.symbols(),
                    "probe fires per file, not per symbol");
        }
    }

    @Test
    void reopenReplayIsTimed(@TempDir Path dir) throws IOException {
        try (LsmStore store = LsmStore.open(dir, CompactionPolicy.manual())) {
            for (int i = 0; i < 3; i++) {
                store.applyEdit(new FileIndex(i,
                        List.of(new Symbol("m" + i + ".", SymbolKind.METHOD, 0, null, i,
                                new Range(1, 1, 1, 1), "m" + i, null)),
                        List.of()));
            }
        }
        Metrics m = Metrics.create();
        try (LsmStore reopened = LsmStore.open(dir, CompactionPolicy.manual(), m)) {
            assertEquals(1, m.timer("replay").count(), "one replay on reopen");
            assertEquals(3, m.counter("replay.records").sum(), "all three committed edits replayed");
        }
    }

    @Test
    void noopMetricsLeaveTheRegistryEmpty(@TempDir Path dir) throws IOException {
        Metrics m = Metrics.noop();
        try (LsmStore store = LsmStore.open(dir, CompactionPolicy.manual(), m)) {
            new Indexer(m).indexRepo(List.of(new SourceRoot(SHAPES_ROOT, SourceSet.MAIN)), store);
            assertTrue(m.counterValues().isEmpty(), "no-op records nothing");
            assertTrue(m.timerValues().isEmpty());
        }
    }

    @Test
    void enabledMetricsStayWithinNoiseOfNoop() throws IOException {
        // Deterministic proof is the coarse-placement assertion above; this is a loose wall-clock
        // guard that metrics-on is not catastrophically slower than metrics-off (min-of-N to de-jitter).
        long noop = minIndexNanos(Metrics.noop(), 5);
        long on = minIndexNanos(Metrics.create(), 5);
        assertTrue(on < noop * 2 + 5_000_000L,
                "metrics overhead must be negligible: on=" + on + "ns noop=" + noop + "ns");
    }

    private static long minIndexNanos(Metrics m, int iterations) throws IOException {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < iterations; i++) {
            Path dir = Files.createTempDirectory("jcma-overhead");
            long t0 = System.nanoTime();
            try (LsmStore store = LsmStore.open(dir, CompactionPolicy.manual(), m)) {
                new Indexer(m).indexRepo(List.of(new SourceRoot(SHAPES_ROOT, SourceSet.MAIN)), store);
            }
            best = Math.min(best, System.nanoTime() - t0);
        }
        return best;
    }
}
