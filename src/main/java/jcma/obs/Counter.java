package jcma.obs;

/**
 * A monotonic count of how many times something happened (PRD §11 "Observability"). Resolve the
 * handle once (e.g. into a field) and call {@link #add}/{@link #increment} on the hot path — never
 * look it up by name per event. The no-op variant ({@link Metrics#noop()}) makes both methods empty
 * so a disabled registry costs nothing.
 */
public interface Counter {

    /** Add {@code delta} to the count. */
    void add(long delta);

    /** Add one. */
    default void increment() {
        add(1L);
    }

    /** The current total. */
    long sum();
}
