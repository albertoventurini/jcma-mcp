package jcma.mcp;

import jcma.mcp.json.JsonParseException;
import jcma.mcp.json.JsonReader;
import jcma.mcp.json.JsonValue;
import jcma.mcp.json.JsonValue.JsonArray;
import jcma.mcp.json.JsonValue.JsonObject;
import jcma.mcp.json.JsonWriter;
import jcma.obs.CallLog;
import jcma.obs.Metrics;
import jcma.response.ToolResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The MCP/JSON-RPC stdio server (PRD §4: MCP is jcma's only protocol surface). A line-delimited
 * read→parse→route→reply loop over the given streams — transport-only, with no knowledge of repos or
 * indexing: the pause-to-index is a caller-supplied {@code bootstrap} {@link Runnable} (wired by
 * {@code Serve}), guarded here so it fires <b>once</b>, lazily, on the first {@code tools/call}. The
 * handshake ({@code initialize}, {@code tools/list}) answers instantly without it.
 *
 * <p>The M0 {@code SpikeC.mcpLoop} is the wire-shape reference re-typed here over the real
 * {@link JsonReader}/{@link JsonWriter}; the spike's naive substring parser is gone.
 *
 * <p>Error model (JSON-RPC): {@code -32700} parse error (replied with {@code id:null}), {@code -32600}
 * invalid request, {@code -32601} method not found, {@code -32602} invalid params. A <em>tool</em>
 * failure is not a transport error — it returns a successful {@code result} with {@code isError:true}.
 */
public final class McpServer {

    private static final String DEFAULT_PROTOCOL_VERSION = "2024-11-05";

    private final InputStream in;
    private final PrintStream out;
    private final PrintStream err;
    private final ToolRegistry registry;
    private final Runnable bootstrap;
    private final Metrics metrics;
    private final CallLog callLog;

    private boolean bootstrapped;

    public McpServer(InputStream in, PrintStream out, PrintStream err,
            ToolRegistry registry, Runnable bootstrap, Metrics metrics) {
        this(in, out, err, registry, bootstrap, metrics, CallLog.noop());
    }

    public McpServer(InputStream in, PrintStream out, PrintStream err,
            ToolRegistry registry, Runnable bootstrap, Metrics metrics, CallLog callLog) {
        this.in = in;
        this.out = out;
        this.err = err;
        this.registry = registry;
        this.bootstrap = bootstrap;
        this.metrics = metrics;
        this.callLog = callLog;
    }

    /** Run the loop until {@code shutdown}/{@code exit} or EOF. */
    public void serve() throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            JsonValue request;
            try {
                request = JsonReader.parse(trimmed);
            } catch (JsonParseException e) {
                reply(error(JsonValue.NULL, -32700, "parse error: " + e.getMessage()));
                continue;
            }
            if (!handle(request)) {
                return; // shutdown / exit
            }
        }
    }

    /** Route one parsed message; return {@code false} to terminate the loop. */
    private boolean handle(JsonValue request) {
        if (!(request instanceof JsonObject obj)) {
            reply(error(JsonValue.NULL, -32600, "invalid request: not a JSON object"));
            return true;
        }
        JsonValue id = obj.get("id"); // null (Java) == absent == a notification
        String jsonrpc = obj.optString("jsonrpc");
        if (!"2.0".equals(jsonrpc)) {
            replyTo(id, error(idOrNull(id), -32600, "invalid request: jsonrpc must be \"2.0\""));
            return true;
        }
        String method = obj.optString("method");
        if (method == null) {
            replyTo(id, error(idOrNull(id), -32600, "invalid request: missing method"));
            return true;
        }
        switch (method) {
            case "initialize" -> replyTo(id, result(id, initialize(obj)));
            case "tools/list" -> replyTo(id, result(id, JsonObject.empty().with("tools", registry.schemas())));
            case "tools/call" -> replyTo(id, toolsCall(id, obj));
            case "ping" -> replyTo(id, result(id, JsonObject.empty()));
            case "shutdown", "exit" -> {
                return false;
            }
            default -> replyTo(id, error(idOrNull(id), -32601, "method not found: " + method));
        }
        return true;
    }

    private JsonValue initialize(JsonObject request) {
        JsonValue params = request.get("params");
        String protocolVersion = params == null ? null : params.optString("protocolVersion");
        if (protocolVersion == null) {
            protocolVersion = DEFAULT_PROTOCOL_VERSION;
        }
        return JsonObject.empty()
                .with("protocolVersion", JsonValue.of(protocolVersion))
                .with("serverInfo", JsonObject.empty()
                        .with("name", JsonValue.of("jcma"))
                        .with("version", JsonValue.of(jcma.cli.Main.VERSION)))
                .with("capabilities", JsonObject.empty().with("tools", JsonObject.empty()));
    }

    /** Build the {@code tools/call} reply: an error object for transport faults, else a result. */
    private JsonObject toolsCall(JsonValue id, JsonObject request) {
        ensureBootstrapped();
        JsonValue params = request.get("params");
        String name = params == null ? null : params.optString("name");
        if (name == null) {
            return error(idOrNull(id), -32602, "invalid params: missing tool name");
        }
        ToolHandler handler = registry.get(name);
        if (handler == null) {
            return error(idOrNull(id), -32602, "invalid params: unknown tool '" + name + "'");
        }
        JsonValue args = params.get("arguments");
        long t0 = System.nanoTime();
        ToolResult toolResult;
        try {
            toolResult = handler.call(args == null ? JsonValue.NULL : args);
        } catch (RuntimeException e) {
            // A tool failure is a result with isError:true, never a transport error.
            toolResult = ToolResult.error(name + " failed: " + e.getMessage());
        }
        long elapsed = System.nanoTime() - t0;
        String rendered = toolResult.render();
        int responseBytes = rendered.getBytes(StandardCharsets.UTF_8).length;

        // Per-tool observability: a named counter/timer (not one shared bucket) plus the call log.
        metrics.counter("mcp.call." + name).increment();
        metrics.timer("mcp.call." + name).record(elapsed);
        if (toolResult.isError()) {
            metrics.counter("mcp.call." + name + ".error").increment();
        }
        callLog.record(name, summarize(args), !toolResult.isError(), elapsed, responseBytes);

        JsonObject content = JsonObject.empty()
                .with("type", JsonValue.of("text"))
                .with("text", JsonValue.of(rendered));
        return result(id, JsonObject.empty()
                .with("content", new JsonArray(List.of(content)))
                .with("isError", JsonValue.of(toolResult.isError())));
    }

    /** A compact, length-capped JSON rendering of the call arguments for the call log. */
    private static String summarize(JsonValue args) {
        if (args == null || args instanceof JsonValue.JsonNull) {
            return "{}";
        }
        String json = JsonWriter.write(args);
        return json.length() <= 512 ? json : json.substring(0, 509) + "...";
    }

    /** Run the pause-to-index bootstrap once, on the first tool call. */
    private void ensureBootstrapped() {
        if (!bootstrapped) {
            bootstrapped = true;
            bootstrap.run();
        }
    }

    // ---- reply plumbing -------------------------------------------------------------------------

    /** Emit {@code envelope} only for a request (a notification — absent id — gets no reply). */
    private void replyTo(JsonValue id, JsonObject envelope) {
        if (id != null) {
            reply(envelope);
        }
    }

    private void reply(JsonObject envelope) {
        out.println(JsonWriter.write(envelope));
        out.flush();
    }

    private static JsonObject result(JsonValue id, JsonValue result) {
        return envelope(id).with("result", result);
    }

    private static JsonObject error(JsonValue id, int code, String message) {
        return envelope(id).with("error", JsonObject.empty()
                .with("code", JsonValue.of(code))
                .with("message", JsonValue.of(message)));
    }

    private static JsonObject envelope(JsonValue id) {
        return JsonObject.empty()
                .with("jsonrpc", JsonValue.of("2.0"))
                .with("id", id == null ? JsonValue.NULL : id);
    }

    /** The request id echoed exactly, or JSON {@code null} when absent (notification/parse fault). */
    private static JsonValue idOrNull(JsonValue id) {
        return id == null ? JsonValue.NULL : id;
    }
}
