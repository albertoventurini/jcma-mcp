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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M2 task-04 (red-first) — {@code search_symbols} tool over the {@code indexer} (shapes) fixture:
 * relevance-ordered results (per {@link jcma.query.SymbolRanking}), the {@code kind} filter and
 * {@code limit}, a sub-trigram ({@code <3}-char) query still returning, and the clean (non-error)
 * empty-query / no-match path.
 */
class SearchSymbolsToolTest {

    private static final Path SHAPES = Path.of("src/test/resources/fixtures/indexer");

    private static SearchSymbolsTool tool(QueryService svc) {
        return new SearchSymbolsTool(() -> svc, BudgetPolicy.defaultPolicy(Metrics.noop()));
    }

    @Test
    void resultsAreRelevanceOrdered(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(SHAPES, indexDir)) {
            // "area" matches Circle.area + Shape.area (both exact); the moniker tiebreak puts Circle first.
            String out = tool(svc).call(args("{\"query\":\"area\"}")).render();
            int circle = out.indexOf("Circle.area");
            int shape = out.indexOf("Shape.area");
            assertTrue(circle >= 0 && shape >= 0, out);
            assertTrue(circle < shape, "Circle.area ranks ahead of Shape.area (moniker tiebreak): " + out);
        }
    }

    @Test
    void kindFilterIsHonored(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(SHAPES, indexDir)) {
            ToolResult r = tool(svc).call(args("{\"query\":\"r\",\"kind\":\"FIELD\"}"));
            assertFalse(r.isError(), () -> r.render());
            String out = r.render();
            assertTrue(out.contains("radius"), "the FIELD named radius matches: " + out);
            assertFalse(out.contains("circumference"), "the METHOD is filtered out by kind=FIELD: " + out);
        }
    }

    @Test
    void limitIsHonored(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(SHAPES, indexDir)) {
            ToolResult r = tool(svc).call(args("{\"query\":\"r\",\"limit\":1}"));
            assertFalse(r.isError(), () -> r.render());
            assertEquals(1, r.fragments().size(), "limit caps the number of hits");
        }
    }

    @Test
    void invalidKindIsAToolError(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(SHAPES, indexDir)) {
            assertTrue(tool(svc).call(args("{\"query\":\"r\",\"kind\":\"bogus\"}")).isError(),
                    "an unknown kind is a tool error");
        }
    }

    @Test
    void subTrigramQueryStillReturns(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(SHAPES, indexDir)) {
            ToolResult r = tool(svc).call(args("{\"query\":\"ad\"}"));
            assertFalse(r.isError(), () -> r.render());
            assertTrue(r.render().contains("radius"), "a 2-char query still matches via the verify-all path: " + r.render());
        }
    }

    @Test
    void emptyQueryAndNoMatchAreCleanTextResults(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(SHAPES, indexDir)) {
            assertFalse(tool(svc).call(args("{\"query\":\"\"}")).isError(), "an empty query is not an error");
            assertFalse(tool(svc).call(args("{\"query\":\"zzzzznope\"}")).isError(), "no matches is not an error");
        }
    }

    @Test
    void endToEndThroughTheMcpServer(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(SHAPES, indexDir)) {
            var reply = ToolTestSupport.callThroughServer(tool(svc), "{\"query\":\"area\"}");
            assertFalse(ToolTestSupport.isError(reply), "a name search is not an error result");
            assertTrue(ToolTestSupport.textOf(reply).contains("area"), ToolTestSupport.textOf(reply));
        }
    }

    private static JsonValue args(String json) {
        return JsonReader.parse(json);
    }
}
