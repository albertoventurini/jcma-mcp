package jcma.obs;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link MetricsReport} renders the registry to the shared summary used by the {@code serve}
 * shutdown dump and the {@code health} tool.
 */
class MetricsReportTest {

    @Test
    void namesEachMetricAndShowsItsValue() {
        Metrics m = Metrics.create();
        m.counter("mcp.call.health").increment();
        m.counter("mcp.call.health").increment();
        m.timer("mcp.call.health").record(3_000_000L);

        String report = MetricsReport.format(m);
        assertTrue(report.contains("mcp.call.health"), "names each metric, was:\n" + report);
        assertTrue(report.contains("= 2"), "shows the counter value, was:\n" + report);
        assertTrue(report.contains("3.000"),
                "timer ms uses a locale-independent dot decimal, was:\n" + report);
    }

    @Test
    void emptyRegistryFormatsWithoutError() {
        assertNotNull(MetricsReport.format(Metrics.create()));
        assertNotNull(MetricsReport.format(Metrics.noop()));
    }
}
