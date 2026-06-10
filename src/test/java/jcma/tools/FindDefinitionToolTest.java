package jcma.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.obs.Metrics;
import jcma.query.QueryService;
import jcma.response.BudgetPolicy;
import jcma.response.ToolResult;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M2 task-04 (red-first) — {@code find_definition} tool over the in-tree {@code resolve/refs} and
 * {@code indexer} (shapes) fixtures: symbol mode (single + multi-match), the qualified-name filter,
 * position mode (go-to-def on a labeled site), and bad input → {@code isError} (a tool outcome, not a
 * transport error). The {@code area()} pair (interface {@code Shape.area} + override {@code
 * Circle.area}) is the multi-match case.
 */
class FindDefinitionToolTest {

    private static final Path REFS = Path.of("src/test/resources/fixtures/resolve/refs");
    private static final Path SHAPES = Path.of("src/test/resources/fixtures/indexer");

    private static BudgetPolicy budget() {
        return BudgetPolicy.defaultPolicy(Metrics.noop());
    }

    private static FindDefinitionTool tool(QueryService svc) {
        return new FindDefinitionTool(() -> svc, budget());
    }

    @Test
    void symbolModeReturnsTheDeclarationSiteAndSnippet(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(REFS, indexDir)) {
            ToolResult r = tool(svc).call(args("{\"symbol\":\"run\"}"));
            assertFalse(r.isError(), () -> r.render());
            String out = r.render();
            assertTrue(out.contains("Service.java:6"), out);
            assertTrue(out.contains("run"), out);
        }
    }

    @Test
    void symbolModeEmitsOneDefinitionPerMatch(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(SHAPES, indexDir)) {
            ToolResult r = tool(svc).call(args("{\"symbol\":\"area\"}"));
            assertFalse(r.isError(), () -> r.render());
            assertEquals(2, r.fragments().size(), "one definition fragment per matching declaration");
            String out = r.render();
            assertTrue(out.contains("Shape.java"), out);
            assertTrue(out.contains("Circle.java"), out);
        }
    }

    @Test
    void qualifiedNameFilterNarrowsToOneDeclaration(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(SHAPES, indexDir)) {
            ToolResult r = tool(svc).call(args("{\"symbol\":\"Circle.area\"}"));
            assertFalse(r.isError(), () -> r.render());
            assertEquals(1, r.fragments().size(), "the qualifier picks the Circle override only");
            String out = r.render();
            assertTrue(out.contains("Circle.java"), out);
            assertFalse(out.contains("Shape.java"), "the interface declaration is filtered out: " + out);
        }
    }

    @Test
    void fullyQualifiedNameSelectsExactlyOneDeclaration(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(SHAPES, indexDir)) {
            ToolResult r = tool(svc).call(args("{\"symbol\":\"com.example.shapes.Circle.area\"}"));
            assertFalse(r.isError(), () -> r.render());
            assertEquals(1, r.fragments().size(), "the full FQN is the maximal suffix → one match");
            assertTrue(r.render().contains("Circle.java"), r.render());
        }
    }

    @Test
    void positionModeIsGoToDefinition(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(REFS, indexDir)) {
            // ClientA.java:7  ->  new Service().run();   (cursor inside the run() call)
            String file = REFS.resolve("app/ClientA.java").toString();
            ToolResult r = tool(svc).call(args(
                    "{\"file\":\"" + file + "\",\"line\":7,\"col\":24}"));
            assertFalse(r.isError(), () -> r.render());
            assertTrue(r.render().contains("Service.java:6"), "go-to-def lands at the run() declaration: " + r.render());
        }
    }

    @Test
    void unknownSymbolIsAToolError(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(REFS, indexDir)) {
            ToolResult r = tool(svc).call(args("{\"symbol\":\"doesNotExist\"}"));
            assertTrue(r.isError(), "an unresolved name is a tool error: " + r.render());
        }
    }

    @Test
    void noModeOrBothModesIsAToolError(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(REFS, indexDir)) {
            assertTrue(tool(svc).call(jcma.mcp.json.JsonValue.NULL).isError(), "no input is a tool error");
            ToolResult both = tool(svc).call(args(
                    "{\"symbol\":\"run\",\"file\":\"X.java\",\"line\":1,\"col\":1}"));
            assertTrue(both.isError(), "both modes at once is a tool error");
        }
    }

    @Test
    void endToEndThroughTheMcpServer(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(REFS, indexDir)) {
            var reply = ToolTestSupport.callThroughServer(tool(svc), "{\"symbol\":\"run\"}");
            assertFalse(ToolTestSupport.isError(reply), "a resolved definition is not an error result");
            assertTrue(ToolTestSupport.textOf(reply).contains("Service.java:6"),
                    ToolTestSupport.textOf(reply));
        }
    }

    private static jcma.mcp.json.JsonValue args(String json) {
        return jcma.mcp.json.JsonReader.parse(json);
    }
}
