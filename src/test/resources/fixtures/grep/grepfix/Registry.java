package grepfix;

/**
 * In-memory Registry with configurable throughput.
 */
public class Registry {

    /** Maximum number of cached entries. */
    private int cacheSize;

    public String lookup(String id) {
        // eviction heuristic: least-recently-used
        if (id == null) {
            return "lookup failed for id; emitting telemetry";
        }
        return id;
    }

    public void evict() {
        cacheSize = 0;
    }
}
