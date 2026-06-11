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
import jcma.response.ToolResult.TextFragment;
import jcma.session.SymbolHit;
import jcma.session.TextHit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The {@code grep_java} §6 tool (M3 task-02): the agent's own verb, with <b>no symbol-hole</b>. It
 * returns semantic <b>symbol</b> matches first (the {@link SearchSymbolsTool} path) and degrades to
 * labelled <b>text</b> matches — string-literal / comment / Javadoc — so a token that exists only as
 * text (a log message, a TODO) still answers where {@code search_java_symbols} would silently miss.
 * This is the reflex flip: use it instead of {@code grep} for {@code .java}.
 *
 * <p>MVP scope (this task): literal/substring matching only (regex is task-03); a combined display
 * {@code limit} (symbols first) with an <em>honest</em> {@code Showing K of N} marker derived from an
 * uncapped scan (the search primitives are uncapped, so the true total is free); aggregation/collapse
 * and text-tier relevance ranking are task-04. The result is routed through the existing swappable
 * {@link BudgetPolicy} token seam.
 */
public final class GrepJavaTool implements ToolHandler {

    /** Default combined display cap (across both tiers) when {@code limit} is omitted. */
    static final int DEFAULT_LIMIT = 50;

    /** Which tier(s) to search. {@code BOTH} is the no-hole default. */
    private enum Match { SYMBOLS, TEXT, BOTH }

    private final Supplier<QueryService> svc;
    private final BudgetPolicy budget;

    public GrepJavaTool(Supplier<QueryService> svc, BudgetPolicy budget) {
        this.svc = svc;
        this.budget = budget;
    }

    @Override
    public String name() {
        return "grep_java";
    }

    @Override
    public String description() {
        return "Search Java sources — use instead of grep for `.java`. Returns semantic Java symbol "
                + "matches first, then plain string-literal / comment / Javadoc text matches, so a token "
                + "that exists only as text still hits. `query` = literal substring (case-sensitive; regex "
                + "is not yet supported). `match` = symbols | text | both (default both). `kind` filters the "
                + "symbol tier. `limit` (default " + DEFAULT_LIMIT + ") caps the combined, symbols-first result.";
    }

    @Override
    public JsonValue schema() {
        JsonObject props = JsonObject.empty()
                .with("query", ToolSupport.typed("string",
                        "Literal substring to find (case-sensitive). Regex is not yet supported."))
                .with("match", ToolSupport.typed("string",
                                "Which tier(s) to search (default both).")
                        .with("enum", new JsonValue.JsonArray(List.of(
                                JsonValue.of("symbols"), JsonValue.of("text"), JsonValue.of("both")))))
                .with("kind", ToolSupport.typed("string",
                        "Optional declaration-kind filter (symbol tier only).").with("enum", ToolSupport.kindEnum()))
                .with("limit", ToolSupport.typed("integer",
                        "Max combined results, symbols first (default " + DEFAULT_LIMIT + ")."));
        return JsonObject.empty()
                .with("type", JsonValue.of("object"))
                .with("properties", props)
                .with("required", new JsonValue.JsonArray(List.of(JsonValue.of("query"))));
    }

    @Override
    public ToolResult call(JsonValue args) {
        JsonObject in = ToolSupport.obj(args);
        String query = in.optString("query");
        if (query == null || query.isEmpty()) {
            return ToolResult.text("grep_java: provide a non-empty query");
        }

        Match match = Match.BOTH;
        String matchArg = in.optString("match");
        if (matchArg != null) {
            try {
                match = Match.valueOf(matchArg.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ToolResult.error("unknown match '" + matchArg + "' (expected symbols | text | both)");
            }
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
            // Uncapped scans (Integer.MAX_VALUE): the true totals are free, so the cap marker is honest.
            List<SymbolHit> symbols = match == Match.TEXT
                    ? List.of()
                    : svc.get().searchSymbols(query, kind, Integer.MAX_VALUE, ToolSupport.DEFAULT_DEADLINE);
            List<TextHit> text = match == Match.SYMBOLS
                    ? List.of()
                    : svc.get().searchText(query, Integer.MAX_VALUE, ToolSupport.DEFAULT_DEADLINE);

            int totalSymbols = symbols.size();
            int totalText = text.size();
            int total = totalSymbols + totalText;
            if (total == 0) {
                return ToolResult.text("no matches for '" + query + "'");
            }

            // Symbols first, then text, capped at the combined limit.
            List<Fragment> out = new ArrayList<>(Math.min(limit, total) + 1);
            for (SymbolHit h : symbols) {
                if (out.size() >= limit) {
                    break;
                }
                out.add(Shaping.symbol(h.symbol(), h.file()));
            }
            for (TextHit h : text) {
                if (out.size() >= limit) {
                    break;
                }
                out.add(Shaping.lineMatch(h));
            }

            int shown = out.size();
            if (shown < total) {
                out.add(new TextFragment("Showing " + shown + " of " + total + " total matches ("
                        + totalSymbols + " symbols, " + totalText + " text) — not exhaustive; "
                        + "raise `limit` or narrow the query."));
            }
            return budget.apply(name(), ToolResult.of(out));
        } catch (IOException | QueryTimeoutException e) {
            return ToolResult.error("grep_java failed: " + e.getMessage());
        }
    }
}
