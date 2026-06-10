package jcma.obs;

/**
 * An append-only record of each MCP {@code tools/call} (PRD §11 "Observability"): which tool ran,
 * a summary of its request, whether it succeeded, how long it took, and how big the response was.
 * The sole consumer is an AI agent over stdio, so the wire carries no debug trace — this is the only
 * persistent per-call trail an operator can grep after the fact.
 *
 * <p>A seam, like {@link Metrics}: the live server writes to a {@link FileCallLog}; tests pass a
 * capturing double; {@link #noop()} is the disabled path (zero work).
 */
public interface CallLog {

    /**
     * Append one call record. {@code request} is a compact, possibly truncated summary of the tool
     * arguments (not the full response — that stays on the wire to the agent). {@code responseBytes}
     * is the size of the rendered response text.
     */
    void record(String tool, String request, boolean ok, long latencyNanos, int responseBytes);

    /** A disabled log: every {@link #record} is a no-op. */
    static CallLog noop() {
        return (tool, request, ok, latencyNanos, responseBytes) -> { };
    }
}
