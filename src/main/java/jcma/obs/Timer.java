package jcma.obs;

/**
 * Accumulates how long (and how often) something took (PRD §11 "Observability"). The caller grabs
 * {@code System.nanoTime()} around a <em>coarse</em> region (per file / query / compaction — never an
 * inner loop) and passes the elapsed nanos to {@link #record}; there is no per-event allocation. The
 * no-op variant makes {@link #record} empty.
 */
public interface Timer {

    /** Record one elapsed duration in nanoseconds. */
    void record(long nanos);

    /** How many durations were recorded. */
    long count();

    /** Sum of all recorded durations (ns). */
    long totalNanos();

    /** Largest single recorded duration (ns), or 0 if none. */
    long maxNanos();

    /** An immutable read-out of a timer's state. */
    record Snapshot(long count, long totalNanos, long maxNanos) {}

    /** Snapshot the timer's current state. */
    default Snapshot snapshot() {
        return new Snapshot(count(), totalNanos(), maxNanos());
    }
}
