package jcma.obs;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * jcma's lightweight, dependency-free metrics registry (PRD §11 "Observability") — named
 * {@link Counter}s and {@link Timer}s so tuning decisions (e.g. the compaction trigger) are
 * data-driven, not guessed. Deliberately <b>not</b> Micrometer/Prometheus client: those are
 * reflection-heavy and hurt native-image instant-start / low-RSS. Counters use {@link LongAdder}
 * (contention-free under the indexer's virtual threads); handles are resolved once and called on
 * coarse boundaries, so overhead is negligible.
 *
 * <p>{@link #noop()} returns a registry whose handles do nothing, so the same instrumented code path
 * runs with zero overhead when disabled — and a metrics-on-vs-{@code noop} benchmark can prove it.
 */
public final class Metrics {

    private final boolean enabled;
    private final ConcurrentHashMap<String, Counter> counters;
    private final ConcurrentHashMap<String, Timer> timers;

    private Metrics(boolean enabled) {
        this.enabled = enabled;
        this.counters = enabled ? new ConcurrentHashMap<>() : null;
        this.timers = enabled ? new ConcurrentHashMap<>() : null;
    }

    /** An active registry that records. */
    public static Metrics create() {
        return new Metrics(true);
    }

    /** A registry whose handles do nothing (zero overhead). */
    public static Metrics noop() {
        return new Metrics(false);
    }

    /** Whether this registry records (false for {@link #noop()}). */
    public boolean enabled() {
        return enabled;
    }

    /** The counter named {@code name} (created on first use); a shared no-op when disabled. */
    public Counter counter(String name) {
        return enabled ? counters.computeIfAbsent(name, k -> new AdderCounter()) : NoopCounter.INSTANCE;
    }

    /** The timer named {@code name} (created on first use); a shared no-op when disabled. */
    public Timer timer(String name) {
        return enabled ? timers.computeIfAbsent(name, k -> new AdderTimer()) : NoopTimer.INSTANCE;
    }

    /** A snapshot of every counter's value, name-sorted (empty when disabled). */
    public Map<String, Long> counterValues() {
        if (!enabled) {
            return Map.of();
        }
        Map<String, Long> out = new TreeMap<>();
        counters.forEach((name, c) -> out.put(name, c.sum()));
        return out;
    }

    /** A snapshot of every timer's state, name-sorted (empty when disabled). */
    public Map<String, Timer.Snapshot> timerValues() {
        if (!enabled) {
            return Map.of();
        }
        Map<String, Timer.Snapshot> out = new TreeMap<>();
        timers.forEach((name, t) -> out.put(name, t.snapshot()));
        return out;
    }

    // ------------------------------------------------------------------ active impls

    private static final class AdderCounter implements Counter {
        private final LongAdder adder = new LongAdder();

        @Override public void add(long delta) {
            adder.add(delta);
        }

        @Override public long sum() {
            return adder.sum();
        }
    }

    private static final class AdderTimer implements Timer {
        private final LongAdder count = new LongAdder();
        private final LongAdder total = new LongAdder();
        private final AtomicLong max = new AtomicLong();

        @Override public void record(long nanos) {
            count.increment();
            total.add(nanos);
            max.accumulateAndGet(nanos, Math::max);
        }

        @Override public long count() {
            return count.sum();
        }

        @Override public long totalNanos() {
            return total.sum();
        }

        @Override public long maxNanos() {
            return max.get();
        }
    }

    // ------------------------------------------------------------------ no-op impls

    private enum NoopCounter implements Counter {
        INSTANCE;

        @Override public void add(long delta) {
        }

        @Override public long sum() {
            return 0L;
        }
    }

    private enum NoopTimer implements Timer {
        INSTANCE;

        @Override public void record(long nanos) {
        }

        @Override public long count() {
            return 0L;
        }

        @Override public long totalNanos() {
            return 0L;
        }

        @Override public long maxNanos() {
            return 0L;
        }
    }
}
