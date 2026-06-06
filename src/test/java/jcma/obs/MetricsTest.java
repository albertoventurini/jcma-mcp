package jcma.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Task 06 · P3 — the {@link Metrics} registry primitives: counters, timers, snapshots, and the
 * zero-overhead {@link Metrics#noop()} contract that lets a metrics-on-vs-off benchmark prove the
 * probes are negligible.
 */
class MetricsTest {

    @Test
    void counterAddsAndIncrements() {
        Metrics m = Metrics.create();
        Counter c = m.counter("c");
        c.add(5);
        c.increment();
        assertEquals(6, c.sum());
        assertEquals(6, m.counter("c").sum(), "same name → same handle");
        assertEquals(6L, m.counterValues().get("c"));
    }

    @Test
    void timerAccumulatesCountTotalAndMax() {
        Metrics m = Metrics.create();
        Timer t = m.timer("t");
        t.record(100);
        t.record(250);
        assertEquals(2, t.count());
        assertEquals(350, t.totalNanos());
        assertEquals(250, t.maxNanos());
        assertEquals(new Timer.Snapshot(2, 350, 250), m.timerValues().get("t"));
    }

    @Test
    void noopRecordsNothing() {
        Metrics m = Metrics.noop();
        assertFalse(m.enabled());
        m.counter("c").add(99);
        m.timer("t").record(1234);
        assertEquals(0, m.counter("c").sum());
        assertEquals(0, m.timer("t").count());
        assertTrue(m.counterValues().isEmpty());
        assertTrue(m.timerValues().isEmpty());
    }

    @Test
    void countersAreContentionSafeAcrossThreads() throws Exception {
        Metrics m = Metrics.create();
        Counter c = m.counter("c");
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?>[] fs = new Future<?>[100];
            for (int i = 0; i < fs.length; i++) {
                fs[i] = pool.submit(() -> {
                    for (int j = 0; j < 1000; j++) {
                        c.increment();
                    }
                });
            }
            for (Future<?> f : fs) {
                f.get();
            }
        }
        assertEquals(100_000, c.sum(), "LongAdder counts every increment under concurrency");
    }
}
