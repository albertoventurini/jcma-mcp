package jcma.tools;

import jcma.index.SymbolKind;
import jcma.mcp.ToolHandler;
import jcma.mcp.json.JsonValue;
import jcma.mcp.json.JsonValue.JsonObject;
import jcma.query.QueryService;
import jcma.query.QueryTimeoutException;
import jcma.response.BudgetPolicy;
import jcma.response.Shaping;
import jcma.response.ToolResult;
import jcma.response.ToolResult.Fragment;
import jcma.session.SymbolHit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The {@code search_symbols} §6 tool (M2 task-04): a name search over the index, returned in
 * relevance order ({@link jcma.query.SymbolRanking}) with an optional {@code kind} filter and a
 * {@code limit}. A pure read over the overlay-aware name index (phantoms excluded). An empty query or
 * no matches is a clean text result, not an error.
 */
public final class SearchSymbolsTool implements ToolHandler {

    /** Default result cap when {@code limit} is omitted. */
    static final int DEFAULT_LIMIT = 50;

    private final Supplier<QueryService> svc;
    private final BudgetPolicy budget;

    public SearchSymbolsTool(Supplier<QueryService> svc, BudgetPolicy budget) {
        this.svc = svc;
        this.budget = budget;
    }

    @Override
    public String name() {
        return "search_java_symbols";
    }

    @Override
    public String description() {
        return "Find Java declarations by name, ranked by relevance — locate a type or member when you know "
                + "part of its name. `query` = case-sensitive substring; optional `kind` filter; `limit` "
                + "(default 50). Then use find_java_definition/find_java_references to navigate.";
    }

    @Override
    public JsonValue schema() {
        JsonObject props = JsonObject.empty()
                .with("query", ToolSupport.typed("string", "Case-sensitive substring of the simple name."))
                .with("kind", ToolSupport.typed("string", "Optional declaration-kind filter.").with("enum", kindEnum()))
                .with("limit", ToolSupport.typed("integer", "Max results (default " + DEFAULT_LIMIT + ")."));
        return JsonObject.empty()
                .with("type", JsonValue.of("object"))
                .with("properties", props)
                .with("required", new JsonValue.JsonArray(java.util.List.of(JsonValue.of("query"))));
    }

    /** The declaration kinds worth filtering on, as a JSON enum (matches {@link SymbolKind} names). */
    private static JsonValue.JsonArray kindEnum() {
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

    @Override
    public ToolResult call(JsonValue args) {
        JsonObject in = ToolSupport.obj(args);
        String query = in.optString("query");
        if (query == null || query.isEmpty()) {
            return ToolResult.text("search_java_symbols: provide a non-empty query");
        }
        SymbolKind kind = null;
        String kindArg = in.optString("kind");
        if (kindArg != null) {
            try {
                kind = SymbolKind.valueOf(kindArg.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ToolResult.error("unknown kind '" + kindArg + "'");
            }
        }
        Integer limitArg = ToolSupport.optInt(in, "limit");
        int limit = limitArg == null ? DEFAULT_LIMIT : limitArg;
        try {
            List<SymbolHit> hits = svc.get().searchSymbols(query, kind, limit, ToolSupport.DEFAULT_DEADLINE);
            if (hits.isEmpty()) {
                return ToolResult.text("no symbols match '" + query + "'");
            }
            List<Fragment> out = new ArrayList<>(hits.size());
            for (SymbolHit h : hits) {
                out.add(Shaping.symbol(h.symbol(), h.file()));
            }
            return budget.apply(name(), ToolResult.of(out));
        } catch (IOException | QueryTimeoutException e) {
            return ToolResult.error("search_symbols failed: " + e.getMessage());
        }
    }
}
