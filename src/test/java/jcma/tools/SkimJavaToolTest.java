package jcma.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.mcp.json.JsonReader;
import jcma.mcp.json.JsonValue;
import jcma.response.ToolResult;

import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * {@code skim_java} tool (red-first) — the §6 front door for the "read this file to learn its shape"
 * reflex: parse a single file fresh and render it as real Java with method bodies elided. Asserts the
 * advertised name, the schema (required {@code path}, optional {@code inlineBodyMaxChars}/{@code
 * includeDocs}), the rendering over {@code fixtures/skim/Sample.java}, the param pass-through, and the
 * clean missing-file error — including end-to-end through a real {@link jcma.mcp.McpServer}.
 */
class SkimJavaToolTest {

    private static SkimJavaTool tool() {
        // Relative paths in args resolve against this root (as Serve injects the repo root).
        return new SkimJavaTool(Path.of("."));
    }

    private static JsonValue args(String json) {
        return JsonReader.parse(json);
    }

    @Test
    void advertisedNameIsSkimJava() {
        assertEquals("skim_java", tool().name());
    }

    @Test
    void schemaRequiresPathAndExposesTheTwoDials() {
        JsonValue schema = tool().schema();
        JsonValue props = schema.get("properties");
        assertTrue(props.get("path") != null, "path property present");
        assertTrue(props.get("inlineBodyMaxChars") != null, "inlineBodyMaxChars property present");
        assertTrue(props.get("includeDocs") != null, "includeDocs property present");
        boolean pathRequired = schema.get("required").asArray().elements().stream()
                .anyMatch(v -> v.asString().equals("path"));
        assertTrue(pathRequired, "path is required");
    }

    @Test
    void rendersTheFileAsElidedJava() {
        ToolResult r = tool().call(args("{\"path\":\"src/test/resources/fixtures/skim/Sample.java\"}"));
        assertFalse(r.isError(), () -> r.render());
        String out = r.render();
        assertTrue(out.contains("public final class Cart"), out);
        assertTrue(out.contains("public void addItem(Item i) { items.add(i); }"), "short body inlined: " + out);
        assertTrue(out.contains("{ … }"), "a long body is elided: " + out);
        assertFalse(out.contains("sum += i.price();"), "the elided body's statements are absent: " + out);
        assertTrue(out.contains("An immutable snapshot"), "docs render by default: " + out);
    }

    @Test
    void includeDocsFalseDropsDocs() {
        String out = tool().call(args("{\"path\":\"src/test/resources/fixtures/skim/Sample.java\","
                + "\"includeDocs\":false}")).render();
        assertFalse(out.contains("An immutable snapshot"), "includeDocs=false drops docs: " + out);
        assertTrue(out.contains("public final class Cart"), "code still rendered: " + out);
    }

    @Test
    void inlineBodyMaxCharsTightensTheGate() {
        // A tiny gate elides even the short addItem body.
        String out = tool().call(args("{\"path\":\"src/test/resources/fixtures/skim/Sample.java\","
                + "\"inlineBodyMaxChars\":5}")).render();
        assertFalse(out.contains("{ items.add(i); }"), "a tiny gate elides the short body: " + out);
    }

    @Test
    void missingFileIsACleanToolError() {
        ToolResult r = tool().call(args("{\"path\":\"src/test/resources/fixtures/skim/Nope.java\"}"));
        assertTrue(r.isError(), "a missing file is a tool error: " + r.render());
    }

    @Test
    void endToEndThroughTheMcpServer() throws Exception {
        var reply = ToolTestSupport.callThroughServer(tool(),
                "{\"path\":\"src/test/resources/fixtures/skim/Sample.java\"}");
        assertFalse(ToolTestSupport.isError(reply), "a valid skim is not an error result");
        assertTrue(ToolTestSupport.textOf(reply).contains("public final class Cart"),
                "the rendering survives the wire: " + ToolTestSupport.textOf(reply));
    }

    @Test
    void descriptionFramesItAsTheReadReflex() {
        String d = tool().description().toLowerCase(Locale.ROOT);
        assertTrue(d.contains("skim") || d.contains("shape"), "description frames the skim purpose: " + d);
    }
}
