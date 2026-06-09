package jcma.mcp;

import jcma.mcp.json.JsonValue;
import jcma.mcp.json.JsonValue.JsonObject;

import java.util.function.Supplier;

/**
 * The one trivial tool wired in task-2 to prove the dispatch loop end-to-end (the real §6 tools land
 * in tasks 4–7). Named {@code health} — not {@code ping} — to avoid colliding with the MCP {@code ping}
 * <em>method</em>. Its status text comes from a caller-supplied {@link Supplier} so the same handler
 * serves both the transport tests (a fixed {@code "ok"}) and {@code Serve} (repo path + indexed-symbol
 * count, read from the lazily opened session); the tool itself needs no session and ignores its args.
 */
public final class HealthTool implements ToolHandler {

    private final Supplier<String> status;

    public HealthTool(Supplier<String> status) {
        this.status = status;
    }

    @Override
    public String name() {
        return "health";
    }

    @Override
    public String description() {
        return "Report jcma server status (a liveness/readiness check).";
    }

    @Override
    public JsonValue schema() {
        return JsonObject.empty()
                .with("type", JsonValue.of("object"))
                .with("properties", JsonObject.empty());
    }

    @Override
    public ToolResult call(JsonValue args) {
        return ToolResult.text(status.get());
    }
}
