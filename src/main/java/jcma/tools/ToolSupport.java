package jcma.tools;

import jcma.mcp.json.JsonValue;
import jcma.mcp.json.JsonValue.JsonNull;
import jcma.mcp.json.JsonValue.JsonObject;

import java.time.Duration;

/**
 * Shared plumbing for the §6 query tools (M2 task-04): the common input schema, JSON arg helpers, and
 * the generous default deadline. The name+qualified-filter target selector now lives one layer down as
 * {@code QueryService.resolveTargets} (over {@link jcma.query.QualifiedName}), so the tools, the CLI,
 * and the REPL all resolve a qualified name identically.
 */
final class ToolSupport {

    /** A fixed generous time-box for a tool query (mirrors the CLI {@code Deadline.DEFAULT}). */
    static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(30);

    private ToolSupport() {}

    /** The shared {@code {symbol?, file?, line?, col?}} input schema for find_definition/find_references. */
    static JsonValue symbolOrPositionSchema() {
        JsonObject props = JsonObject.empty()
                .with("symbol", typed("string"))
                .with("file", typed("string"))
                .with("line", typed("integer"))
                .with("col", typed("integer"));
        return JsonObject.empty()
                .with("type", JsonValue.of("object"))
                .with("properties", props);
    }

    static JsonObject typed(String type) {
        return JsonObject.empty().with("type", JsonValue.of(type));
    }

    /** {@code args} as an object, or an empty object when it is absent / JSON {@code null} / not an object. */
    static JsonObject obj(JsonValue args) {
        return args instanceof JsonObject o ? o : JsonObject.empty();
    }

    /** The integer member at {@code key}, or {@code null} if absent / JSON {@code null}. */
    static Integer optInt(JsonObject obj, String key) {
        JsonValue v = obj.get(key);
        if (v == null || v instanceof JsonNull) {
            return null;
        }
        return v.asInt();
    }
}
