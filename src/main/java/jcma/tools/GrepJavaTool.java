package jcma.tools;

import jcma.index.SearchSpec;
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
import java.util.regex.PatternSyntaxException;

/**
 * The {@code grep_java} §6 tool (M3 task-02): the agent's own verb, with <b>no symbol-hole</b>. It
 * returns semantic <b>symbol</b> matches first (the {@link SearchSymbolsTool} path) and degrades to
 * labelled <b>text</b> matches — string-literal / comment / Javadoc — so a token that exists only as
 * text (a log message, a TODO) still answers where {@code search_java_symbols} would silently miss.
 * This is the reflex flip: use it instead of {@code grep} for {@code .java}.
 *
 * <p>{@code query} is a <b>regular expression by default</b> (the agent's grep reflex; D-a), applied
 * with uniform semantics across both tiers (D-b): {@code fixed_string} opts into literal matching
 * (grep {@code -F}), {@code case_sensitive} (default true; D-c) opts case mode. Anchors are per
 * physical line ({@link java.util.regex.Pattern#MULTILINE}; {@code .} does not cross {@code \n} — D-d).
 * The match policy is the swappable {@link SearchSpec}, so a metachar-free case-sensitive query keeps
 * the trigram/{@code indexOf} fast path unchanged.
 *
 * <p>A combined display {@code limit} (symbols first) carries an <em>honest</em> {@code Showing K of N}
 * marker derived from an uncapped scan (the search primitives are uncapped, so the true total is free);
 * the result is routed through the existing swappable {@link BudgetPolicy} token seam.
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
                + "that exists only as text still hits. `query` = regular expression (default; `^`/`$` "
                + "anchor per line); set `fixed_string` for a literal substring. `case_sensitive` defaults "
                + "true. `match` = symbols | text | both (default both). `kind` filters the symbol tier. "
                + "`limit` (default " + DEFAULT_LIMIT + ") caps the combined, symbols-first result.";
    }

    @Override
    public JsonValue schema() {
        JsonObject props = JsonObject.empty()
                .with("query", ToolSupport.typed("string",
                        "Regular expression to find (default); `^`/`$` anchor per line. Set `fixed_string` "
                                + "for a literal substring."))
                .with("fixed_string", ToolSupport.typed("boolean",
                        "Treat `query` as a literal substring, not a regex (grep -F). Default false."))
                .with("case_sensitive", ToolSupport.typed("boolean",
                        "Case-sensitive matching. Default true."))
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

        boolean fixedString = ToolSupport.optBool(in, "fixed_string", false);
        boolean caseSensitive = ToolSupport.optBool(in, "case_sensitive", true);
        SearchSpec spec = SearchSpec.of(query, fixedString, caseSensitive);
        try {
            spec.validate(); // surface a malformed regex as a clean error before any scan
        } catch (PatternSyntaxException e) {
            return ToolResult.error("grep_java: invalid regex: " + e.getMessage());
        }

        try {
            // Uncapped scans (Integer.MAX_VALUE): the true totals are free, so the cap marker is honest.
            List<SymbolHit> symbols = match == Match.TEXT
                    ? List.of()
                    : svc.get().searchSymbols(spec, kind, Integer.MAX_VALUE, ToolSupport.DEFAULT_DEADLINE);
            List<TextHit> text = match == Match.SYMBOLS
                    ? List.of()
                    : svc.get().searchText(spec, Integer.MAX_VALUE, ToolSupport.DEFAULT_DEADLINE);

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
