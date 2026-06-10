package jcma.obs;

import java.util.Locale;
import java.util.Map;

/**
 * Renders a {@link Metrics} registry to a compact, human-readable multi-line summary — the shared
 * formatter for the {@code serve} shutdown dump (to stderr) and the {@code health} tool's live
 * read-out. Keeps the per-tool counters and latency timers in one place so both surfaces agree.
 */
public final class MetricsReport {

    private MetricsReport() {
    }

    /**
     * A name-sorted block of every counter ({@code name = value}) and timer
     * ({@code name  count=N avg=A.AAAms max=M.MMMms}), or {@code ""} when nothing has been recorded
     * (or the registry is {@link Metrics#noop()}).
     */
    public static String format(Metrics metrics) {
        Map<String, Long> counters = metrics.counterValues();
        Map<String, Timer.Snapshot> timers = metrics.timerValues();
        if (counters.isEmpty() && timers.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!counters.isEmpty()) {
            sb.append("counters:");
            counters.forEach((name, value) ->
                    sb.append("\n  ").append(name).append(" = ").append(value));
        }
        if (!timers.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("timers:");
            timers.forEach((name, t) -> sb.append("\n  ").append(name)
                    .append("  count=").append(t.count())
                    .append(" avg=").append(millis(t.count() == 0 ? 0 : t.totalNanos() / t.count())).append("ms")
                    .append(" max=").append(millis(t.maxNanos())).append("ms"));
        }
        return sb.toString();
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }
}
