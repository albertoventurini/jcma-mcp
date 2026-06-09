package jcma.mcp;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import jcma.mcp.json.JsonReader;
import jcma.mcp.json.JsonValue;
import jcma.obs.Metrics;
import org.junit.jupiter.api.Test;

/**
 * Task-2 — the MCP/JSON-RPC stdio loop, exercised transport-only (no repo, no indexing): a
 * {@link ToolRegistry} holding the trivial {@link HealthTool} and a caller-supplied {@code bootstrap}
 * {@link Runnable} stand in for {@code Serve}'s pause-to-index. Drives the loop with newline-delimited
 * JSON-RPC over piped byte streams (mirroring {@code jcma.cli.IndexCommandTest}'s in-process style)
 * and parses each reply line back through the real {@link JsonReader}.
 */
class McpServerTest {

    /** Build a registry holding the one trivial tool; its status text is fixed for the transport tests. */
    private static ToolRegistry registry() {
        ToolRegistry reg = new ToolRegistry();
        reg.register(new HealthTool(() -> "ok"));
        return reg;
    }

    /** Run the loop over {@code input}, returning every reply line parsed back to a {@link JsonValue}. */
    private static List<JsonValue> drive(String input, ToolRegistry reg, Runnable bootstrap) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(UTF_8));
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBuf, true, UTF_8);
        PrintStream err = new PrintStream(errBuf, true, UTF_8);
        new McpServer(in, out, err, reg, bootstrap, Metrics.noop()).serve();
        List<JsonValue> replies = new ArrayList<>();
        for (String line : outBuf.toString(UTF_8).split("\n")) {
            if (!line.isBlank()) {
                replies.add(JsonReader.parse(line.trim()));
            }
        }
        return replies;
    }

    private static List<JsonValue> drive(String input) throws IOException {
        return drive(input, registry(), () -> {});
    }

    private static final String INIT =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2025-06-18\"}}";
    private static final String INIT_NO_VERSION =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}";
    private static final String TOOLS_LIST =
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
    private static final String CALL_HEALTH =
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"health\",\"arguments\":{}}}";

    // ---- handshake ------------------------------------------------------------------------------

    @Test
    void initializeReturnsHandshakeEchoingTheClientProtocolVersion() throws IOException {
        JsonValue reply = drive(INIT + "\n").get(0);
        JsonValue result = reply.get("result");
        assertNotNull(result, "initialize replies with a result");
        assertEquals("2025-06-18", result.get("protocolVersion").asString(),
                "protocolVersion echoes the client's");
        assertEquals("jcma", result.get("serverInfo").get("name").asString());
        assertNotNull(result.get("serverInfo").get("version"), "serverInfo carries a version");
        assertInstanceOf(JsonValue.JsonObject.class, result.get("capabilities").get("tools"),
                "capabilities.tools is an (empty) object");
    }

    @Test
    void initializeDefaultsProtocolVersionWhenClientOmitsIt() throws IOException {
        JsonValue reply = drive(INIT_NO_VERSION + "\n").get(0);
        assertEquals("2024-11-05", reply.get("result").get("protocolVersion").asString());
    }

    @Test
    void initializeDoesNotTriggerBootstrap() throws IOException {
        AtomicInteger boots = new AtomicInteger();
        drive(INIT + "\n" + TOOLS_LIST + "\n", registry(), boots::incrementAndGet);
        assertEquals(0, boots.get(), "neither initialize nor tools/list builds an index");
    }

    @Test
    void toolsListReturnsTheRegisteredHealthSchema() throws IOException {
        JsonValue reply = drive(TOOLS_LIST + "\n").get(0);
        List<JsonValue> tools = reply.get("result").get("tools").asArray().elements();
        JsonValue health = tools.stream()
                .filter(t -> "health".equals(t.get("name").asString()))
                .findFirst().orElse(null);
        assertNotNull(health, "tools/list advertises health");
        assertNotNull(health.get("description"), "health carries a description");
        assertEquals("object", health.get("inputSchema").get("type").asString());
    }

    // ---- tools/call -----------------------------------------------------------------------------

    @Test
    void toolsCallHealthReturnsASuccessfulResultWithTextContent() throws IOException {
        JsonValue reply = drive(CALL_HEALTH + "\n").get(0);
        JsonValue result = reply.get("result");
        assertNotNull(result, "a tool call returns a JSON-RPC result, not an error");
        assertFalse(result.get("isError").asBoolean(), "a healthy call is not an error");
        JsonValue first = result.get("content").asArray().elements().get(0);
        assertEquals("text", first.get("type").asString());
        assertEquals("ok", first.get("text").asString());
    }

    @Test
    void firstToolsCallTriggersBootstrapExactlyOnce() throws IOException {
        AtomicInteger boots = new AtomicInteger();
        drive(INIT + "\n" + CALL_HEALTH + "\n" + CALL_HEALTH + "\n", registry(), boots::incrementAndGet);
        assertEquals(1, boots.get(), "bootstrap is guarded — built once across many tool calls");
    }

    // ---- error model ----------------------------------------------------------------------------

    @Test
    void unknownMethodIsMethodNotFound() throws IOException {
        JsonValue reply = drive("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"bogus\"}\n").get(0);
        assertEquals(-32601, reply.get("error").get("code").asInt());
        assertEquals(9, reply.get("id").asInt());
    }

    @Test
    void malformedJsonIsAParseErrorWithNullId() throws IOException {
        JsonValue reply = drive("{ this is not json\n").get(0);
        assertEquals(-32700, reply.get("error").get("code").asInt());
        assertInstanceOf(JsonValue.JsonNull.class, reply.get("id"), "a parse error cannot echo an id");
    }

    @Test
    void missingOrWrongJsonrpcVersionIsInvalidRequest() throws IOException {
        JsonValue reply = drive("{\"jsonrpc\":\"1.0\",\"id\":5,\"method\":\"initialize\"}\n").get(0);
        assertEquals(-32600, reply.get("error").get("code").asInt());
        assertEquals(5, reply.get("id").asInt());
    }

    @Test
    void unknownToolNameIsInvalidParams() throws IOException {
        String call = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"nope\",\"arguments\":{}}}";
        JsonValue reply = drive(call + "\n").get(0);
        assertEquals(-32602, reply.get("error").get("code").asInt());
    }

    // ---- protocol mechanics ---------------------------------------------------------------------

    @Test
    void notificationProducesNoReply() throws IOException {
        // No "id" → a notification: process side effects, emit nothing.
        List<JsonValue> replies = drive("{\"jsonrpc\":\"2.0\",\"method\":\"initialize\"}\n");
        assertTrue(replies.isEmpty(), "a notification gets no reply");
    }

    @Test
    void exitTerminatesTheLoop() throws IOException {
        // After exit the loop returns; the trailing initialize must never be answered.
        List<JsonValue> replies = drive("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"exit\"}\n" + INIT + "\n");
        assertTrue(replies.isEmpty(), "nothing after exit is processed");
    }

    @Test
    void idIsEchoedExactlyForNumericAndStringIds() throws IOException {
        JsonValue numeric = drive("{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"ping\"}\n").get(0);
        assertEquals(42, numeric.get("id").asInt());

        JsonValue string = drive("{\"jsonrpc\":\"2.0\",\"id\":\"abc-1\",\"method\":\"ping\"}\n").get(0);
        assertEquals("abc-1", string.get("id").asString());
    }
}
