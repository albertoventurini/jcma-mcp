package jcma.tools;

import jcma.index.SymbolKind;
import jcma.mcp.json.JsonValue;
import jcma.mcp.json.JsonValue.JsonNull;
import jcma.mcp.json.JsonValue.JsonObject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
                .with("symbol", typed("string",
                        "Simple name (`Foo`, `parse`) → all matches; qualified (`com.acme.Foo.parse`) → "
                                + "narrowed. Use this OR file/line/col."))
                .with("file", typed("string", ".java path; with line+col, points at one identifier."))
                .with("line", typed("integer", "1-based line (position mode)."))
                .with("col", typed("integer", "1-based column (position mode)."));
        return JsonObject.empty()
                .with("type", JsonValue.of("object"))
                .with("properties", props);
    }

    static JsonObject typed(String type) {
        return JsonObject.empty().with("type", JsonValue.of(type));
    }

    static JsonObject typed(String type, String description) {
        return typed(type).with("description", JsonValue.of(description));
    }

    /** {@code args} as an object, or an empty object when it is absent / JSON {@code null} / not an object. */
    static JsonObject obj(JsonValue args) {
        return args instanceof JsonObject o ? o : JsonObject.empty();
    }

    /** The declaration kinds worth filtering on, as a JSON enum (matches {@link SymbolKind} names). */
    static JsonValue.JsonArray kindEnum() {
        SymbolKind[] kinds = {
            SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.ENUM, SymbolKind.RECORD,
            SymbolKind.ANNOTATION, SymbolKind.METHOD, SymbolKind.CONSTRUCTOR, SymbolKind.FIELD,
            SymbolKind.ENUM_CONSTANT
        };
        List<JsonValue> names = new ArrayList<>(kinds.length);
        for (SymbolKind k : kinds) {
            names.add(JsonValue.of(k.name()));
        }
        return new JsonValue.JsonArray(names);
    }

    /** The integer member at {@code key}, or {@code null} if absent / JSON {@code null}. */
    static Integer optInt(JsonObject obj, String key) {
        JsonValue v = obj.get(key);
        if (v == null || v instanceof JsonNull) {
            return null;
        }
        return v.asInt();
    }

    /** The boolean member at {@code key}, or {@code dflt} if absent / JSON {@code null}. */
    static boolean optBool(JsonObject obj, String key, boolean dflt) {
        JsonValue v = obj.get(key);
        if (v == null || v instanceof JsonNull) {
            return dflt;
        }
        return v.asBoolean();
    }
}
