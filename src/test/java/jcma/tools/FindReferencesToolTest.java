package jcma.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.mcp.json.JsonReader;
import jcma.mcp.json.JsonValue;
import jcma.obs.Metrics;
import jcma.query.QueryService;
import jcma.response.BudgetPolicy;
import jcma.response.ToolResult;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M2 task-04 (red-first) — {@code find_references} tool over {@code resolve/refs} and {@code indexer}
 * (shapes): single-match grouping/counts + the mandatory unconfirmed tail, multi-match (one section
 * per declaration, one shared tail), position-mode go-to-refs, the over-cap result bounded + marked by
 * the {@link BudgetPolicy} (counts stay sacred), and bad input → {@code isError}.
 */
class FindReferencesToolTest {

    private static final Path REFS = Path.of("src/test/resources/fixtures/resolve/refs");
    private static final Path SHAPES = Path.of("src/test/resources/fixtures/indexer");

    private static FindReferencesTool tool(QueryService svc) {
        return new FindReferencesTool(() -> svc, BudgetPolicy.defaultPolicy(Metrics.noop()));
    }

    @Test
    void advertisedNameEmbedsJava() {
        // The wire name is the only per-tool signal that survives tool-name-only deferral, and the
        // jcma namespace is opaque — so the name itself must say "java".
        assertEquals("find_java_references", tool(null).name());
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void singleMatchGroupsByEnclosingWithCountsAndUnconfirmedTail(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(REFS, indexDir)) {
            ToolResult r = tool(svc).call(args("{\"symbol\":\"run\"}"));
            assertFalse(r.isError(), () -> r.render());
            String out = r.render();
            assertTrue(out.contains("Total refs: 3 across 2 files"), "the sacred count header: " + out);
            assertTrue(count(out, "called from") == 3, "one group per enclosing method: " + out);
            assertTrue(out.contains("NOT exhaustive"), "Mystery/KnownMiss surface as the unconfirmed tail: " + out);
        }
    }

    @Test
    void multiMatchEmitsOneSectionPerDeclarationWithOneSharedTail(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(SHAPES, indexDir)) {
            ToolResult r = tool(svc).call(args("{\"symbol\":\"area\"}"));
            assertFalse(r.isError(), () -> r.render());
            String out = r.render();
            assertTrue(out.contains("Shape.area()"), "a section for the interface declaration: " + out);
            assertTrue(out.contains("Circle.area()"), "a section for the override: " + out);
        }
    }

    @Test
    void positionModeIsGoToReferences(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(REFS, indexDir)) {
            // ClientA.java:7  ->  new Service().run();  — go-to-refs from a use lands the full ref set.
            String file = REFS.resolve("app/ClientA.java").toString();
            ToolResult r = tool(svc).call(args("{\"file\":\"" + file + "\",\"line\":7,\"col\":24}"));
            assertFalse(r.isError(), () -> r.render());
            assertTrue(r.render().contains("Total refs: 3 across 2 files"), r.render());
        }
    }

    @Test
    void overCapResultIsBoundedButKeepsTheSacredCount(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(REFS, indexDir)) {
            // A deliberately tiny cap forces the budget to degrade fidelity (drop snippets / roll up).
            BudgetPolicy tiny = BudgetPolicy.capped(Map.of(), 5, Metrics.noop());
            FindReferencesTool t = new FindReferencesTool(() -> svc, tiny);
            ToolResult r = t.call(args("{\"symbol\":\"run\"}"));
            assertFalse(r.isError(), () -> r.render());
            String out = r.render();
            assertTrue(out.contains("Total refs: 3 across 2 files"), "the total stays exhaustive: " + out);
            assertTrue(out.contains("token budget"), "a degrade advisory note is present: " + out);
        }
    }

    @Test
    void noInputIsAToolError(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(REFS, indexDir)) {
            assertTrue(tool(svc).call(JsonValue.NULL).isError(), "no input is a tool error");
        }
    }

    @Test
    void endToEndThroughTheMcpServer(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(REFS, indexDir)) {
            var reply = ToolTestSupport.callThroughServer(tool(svc), "{\"symbol\":\"run\"}");
            assertFalse(ToolTestSupport.isError(reply), "a confirmed ref set is not an error result");
            assertTrue(ToolTestSupport.textOf(reply).contains("Total refs: 3 across 2 files"),
                    ToolTestSupport.textOf(reply));
        }
    }

    private static JsonValue args(String json) {
        return JsonReader.parse(json);
    }
}
