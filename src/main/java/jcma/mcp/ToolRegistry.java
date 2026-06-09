package jcma.mcp;

import jcma.mcp.json.JsonValue;
import jcma.mcp.json.JsonValue.JsonArray;
import jcma.mcp.json.JsonValue.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link ToolHandler}s a server exposes, keyed by name. Built upfront in {@code Serve} (before any
 * index exists) so {@code tools/list} — whose entries are static metadata — answers during the
 * handshake. Insertion-ordered so {@link #schemas()} is deterministic and testable.
 */
public final class ToolRegistry {

    private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();

    /** Register {@code handler}; the last registration for a name wins. */
    public void register(ToolHandler handler) {
        handlers.put(handler.name(), handler);
    }

    /** The handler named {@code name}, or {@code null} if none is registered. */
    public ToolHandler get(String name) {
        return handlers.get(name);
    }

    /** The {@code tools/list} array: one {@code {name, description, inputSchema}} object per handler. */
    public JsonArray schemas() {
        List<JsonValue> tools = new ArrayList<>(handlers.size());
        for (ToolHandler h : handlers.values()) {
            tools.add(JsonObject.empty()
                    .with("name", JsonValue.of(h.name()))
                    .with("description", JsonValue.of(h.description()))
                    .with("inputSchema", h.schema()));
        }
        return new JsonArray(tools);
    }
}
