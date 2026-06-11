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

import java.util.Locale;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M3 task-02 (red-first) — the {@code grep_java} MVP over a dedicated {@code fixtures/grep} fixture
 * (one class with a known method/field <em>plus</em> a string literal, a line comment, and a Javadoc,
 * including a <b>literal-only</b> token present only as text). Asserts the reflex-flipping properties:
 * symbol matches ranked above labelled text, the {@code match} tier selector, the no-symbol-hole, the
 * three text-kind labels, an honest bounded cap with a true total, and the clean error/empty paths —
 * end-to-end through a real {@link jcma.mcp.McpServer}.
 *
 * <p>Token map of the fixture: {@code lookup} is both a method symbol and a substring of a string
 * literal; {@code telemetry} appears only in a string literal (the no-hole token); {@code heuristic}
 * only in a line comment; {@code throughput} only in the Javadoc.
 */
class GrepJavaToolTest {

    private static final Path GREP = Path.of("src/test/resources/fixtures/grep");

    private static GrepJavaTool tool(QueryService svc) {
        return new GrepJavaTool(() -> svc, BudgetPolicy.defaultPolicy(Metrics.noop()));
    }

    private static SearchSymbolsTool symbolsTool(QueryService svc) {
        return new SearchSymbolsTool(() -> svc, BudgetPolicy.defaultPolicy(Metrics.noop()));
    }

    @Test
    void advertisedNameIsGrepJava() {
        assertEquals("grep_java", tool(null).name());
    }

    @Test
    void descriptionSaysJavaOnlyAndGrepReplacement() {
        String d = tool(null).description().toLowerCase(Locale.ROOT);
        assertTrue(d.contains("java"), "description names Java: " + d);
        assertTrue(d.contains("grep"), "description frames itself as the grep replacement: " + d);
    }

    @Test
    void bothTierRanksSymbolsAboveLabelledText(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(GREP, indexDir)) {
            // "lookup" is the method symbol AND a substring of the string literal "lookup failed …".
            ToolResult r = tool(svc).call(args("{\"query\":\"lookup\"}"));
            assertFalse(r.isError(), () -> r.render());
            String out = r.render();
            int symbol = out.indexOf("METHOD");           // the symbol entry carries its SymbolKind
            int text = out.indexOf("[string-literal]");   // the text entry carries its kind label
            assertTrue(symbol >= 0, "the method symbol is present: " + out);
            assertTrue(text >= 0, "the labelled text hit is present: " + out);
            assertTrue(symbol < text, "symbol matches rank above text matches: " + out);
        }
    }

    @Test
    void matchSymbolsEqualsSearchJavaSymbols(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(GREP, indexDir)) {
            String grep = tool(svc).call(args("{\"query\":\"lookup\",\"match\":\"symbols\"}")).render();
            String search = symbolsTool(svc).call(args("{\"query\":\"lookup\"}")).render();
            assertEquals(search, grep, "match=symbols is exactly the search_java_symbols answer");
        }
    }

    @Test
    void matchTextReturnsOnlyLabelledText(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(GREP, indexDir)) {
            ToolResult r = tool(svc).call(args("{\"query\":\"lookup\",\"match\":\"text\"}"));
            assertFalse(r.isError(), () -> r.render());
            String out = r.render();
            assertTrue(out.contains("[string-literal]"), "the text hit is present: " + out);
            assertFalse(out.contains("METHOD"), "no symbol entry under match=text: " + out);
        }
    }

    @Test
    void noSymbolHole_literalOnlyTokenReturnsUnderBoth(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(GREP, indexDir)) {
            // "telemetry" is in no symbol — only the string literal. The whole point: grep_java still
            // answers where search_java_symbols would have a hole.
            ToolResult r = tool(svc).call(args("{\"query\":\"telemetry\"}"));
            assertFalse(r.isError(), () -> r.render());
            assertTrue(r.render().contains("[string-literal]"),
                    "a literal-only token still returns a labelled text hit under match=both: " + r.render());
        }
    }

    @Test
    void allThreeTextKindsSurfaceTheirLabel(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(GREP, indexDir)) {
            assertTrue(tool(svc).call(args("{\"query\":\"telemetry\"}")).render().contains("[string-literal]"),
                    "string literal labelled");
            assertTrue(tool(svc).call(args("{\"query\":\"heuristic\"}")).render().contains("[comment]"),
                    "line comment labelled");
            assertTrue(tool(svc).call(args("{\"query\":\"throughput\"}")).render().contains("[javadoc]"),
                    "Javadoc labelled");
        }
    }

    @Test
    void honestCapIsBoundedAndCarriesTrueTotal(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(GREP, indexDir)) {
            // "lookup" yields 2 matches total (1 symbol + 1 text). limit=1 shows only the symbol (ranked
            // first) and must say so honestly — never claim exhaustiveness.
            ToolResult r = tool(svc).call(args("{\"query\":\"lookup\",\"limit\":1}"));
            assertFalse(r.isError(), () -> r.render());
            String out = r.render();
            assertTrue(out.contains("METHOD"), "the one shown match is the top-ranked symbol: " + out);
            assertFalse(out.contains("[string-literal]"), "the 2nd match is dropped by limit=1: " + out);
            assertTrue(out.contains("not exhaustive"), "the cap is declared non-exhaustive: " + out);
            assertTrue(out.contains("1 of 2"), "the true total (2) is reported: " + out);
        }
    }

    @Test
    void invalidMatchAndKindAreToolErrors(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(GREP, indexDir)) {
            assertTrue(tool(svc).call(args("{\"query\":\"lookup\",\"match\":\"bogus\"}")).isError(),
                    "an unknown match tier is a tool error");
            assertTrue(tool(svc).call(args("{\"query\":\"lookup\",\"kind\":\"bogus\"}")).isError(),
                    "an unknown kind is a tool error");
        }
    }

    @Test
    void emptyQueryAndNoMatchAreCleanTextResults(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(GREP, indexDir)) {
            assertFalse(tool(svc).call(args("{\"query\":\"\"}")).isError(), "an empty query is not an error");
            assertFalse(tool(svc).call(args("{\"query\":\"zzzzznope\"}")).isError(), "no matches is not an error");
        }
    }

    @Test
    void endToEndThroughTheMcpServer(@TempDir Path indexDir) throws Exception {
        try (QueryService svc = ToolTestSupport.queryService(GREP, indexDir)) {
            var reply = ToolTestSupport.callThroughServer(tool(svc), "{\"query\":\"telemetry\"}");
            assertFalse(ToolTestSupport.isError(reply), "a literal-only grep is not an error result");
            assertTrue(ToolTestSupport.textOf(reply).contains("[string-literal]"),
                    "the labelled text hit survives the wire: " + ToolTestSupport.textOf(reply));
        }
    }

    private static JsonValue args(String json) {
        return JsonReader.parse(json);
    }
}
