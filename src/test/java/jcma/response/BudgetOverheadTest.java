package jcma.response;

import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.index.Range;
import jcma.obs.Metrics;
import jcma.resolve.Ref;
import jcma.resolve.ReferenceGroup;
import jcma.resolve.References;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Task-03 — the instrumentation is at the per-result boundary (one estimate + a handful of counter
 * adds per {@code apply}), so metrics-on must stay within noise of metrics-off. Mirrors
 * {@code MetricsWiringTest}'s min-of-N {@code noop()} vs {@code create()} convention.
 */
class BudgetOverheadTest {

    private static ToolResult small() {
        Ref a = new Ref(0, Path.of("src/A.java"), new Range(1, 1, 1, 4), "foo();");
        ReferenceGroup g = new ReferenceGroup("com/acme/C#run().", "void com.acme.C.run()", List.of(a));
        return ToolResult.of(Shaping.references(new References(List.of(g), List.of())));
    }

    @Test
    void applyOverheadIsWithinNoiseOfPassthrough() {
        ToolResult in = small();
        long noop = minApplyNanos(BudgetPolicy.capped(Map.of(), BudgetPolicy.DEFAULT_CAP, Metrics.noop()), in, 50);
        long on = minApplyNanos(BudgetPolicy.capped(Map.of(), BudgetPolicy.DEFAULT_CAP, Metrics.create()), in, 50);
        assertTrue(on < noop * 2 + 5_000_000L,
                "budget instrumentation overhead must be negligible: on=" + on + "ns noop=" + noop + "ns");
    }

    private static long minApplyNanos(BudgetPolicy p, ToolResult in, int iterations) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            p.apply("find_references", in);
            best = Math.min(best, System.nanoTime() - t0);
        }
        return best;
    }
}
