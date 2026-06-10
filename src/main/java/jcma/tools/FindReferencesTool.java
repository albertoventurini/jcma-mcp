package jcma.tools;

import jcma.engine.Position;
import jcma.index.Symbol;
import jcma.mcp.ToolHandler;
import jcma.mcp.json.JsonValue;
import jcma.mcp.json.JsonValue.JsonObject;
import jcma.query.QueryService;
import jcma.query.QueryTimeoutException;
import jcma.resolve.References;
import jcma.response.BudgetPolicy;
import jcma.response.Shaping;
import jcma.response.ToolResult;
import jcma.response.ToolResult.Fragment;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The {@code find_references} §6 tool (M2 task-04) — the agent's grep replacement: every confirmed use
 * of a symbol, grouped by enclosing declaration, with the mandatory unconfirmed tail. Same two input
 * modes as {@code find_definition} (by symbol with optional qualified filter, or by position). A
 * simple name that resolves to several declarations emits one reference section per match with the
 * single name-keyed unconfirmed tail printed once. Output is the result most likely to overflow, so it
 * is always routed through the injected {@link BudgetPolicy} (counts sacred, snippets elastic).
 */
public final class FindReferencesTool implements ToolHandler {

    private final Supplier<QueryService> svc;
    private final BudgetPolicy budget;

    public FindReferencesTool(Supplier<QueryService> svc, BudgetPolicy budget) {
        this.svc = svc;
        this.budget = budget;
    }

    @Override
    public String name() {
        return "find_java_references";
    }

    @Override
    public String description() {
        return "Return every confirmed use of a Java symbol (class, method, field, etc.) across the project — a "
                + "semantic grep: resolved uses, not text hits. By name (optionally qualified) or by "
                + "file:line:col; grouped by enclosing declaration.";
    }

    @Override
    public JsonValue schema() {
        return ToolSupport.symbolOrPositionSchema();
    }

    @Override
    public ToolResult call(JsonValue args) {
        JsonObject in = ToolSupport.obj(args);
        String symbol = in.optString("symbol");
        String file = in.optString("file");
        Integer line = ToolSupport.optInt(in, "line");
        Integer col = ToolSupport.optInt(in, "col");
        boolean symbolMode = symbol != null && !symbol.isBlank();
        boolean posMode = file != null && line != null && col != null;
        if (symbolMode == posMode) {
            return ToolResult.error("find_java_references: provide either {symbol} or {file, line, col}");
        }
        try {
            QueryService q = svc.get();
            if (posMode) {
                References refs =
                        q.findReferencesAt(Path.of(file), new Position(line, col), ToolSupport.DEFAULT_DEADLINE);
                return budget.apply(name(), ToolResult.of(Shaping.references(refs)));
            }
            List<Symbol> targets = q.resolveTargets(symbol, ToolSupport.DEFAULT_DEADLINE);
            if (targets.isEmpty()) {
                return ToolResult.error("no declaration named '" + symbol + "'");
            }
            if (targets.size() == 1) {
                References refs = q.findReferences(targets.get(0), ToolSupport.DEFAULT_DEADLINE);
                return budget.apply(name(), ToolResult.of(Shaping.references(refs)));
            }
            // Multi-match: one section per declaration, then the single name-keyed tail (identical for
            // every same-named target, so it is printed once).
            List<Fragment> out = new ArrayList<>();
            References last = null;
            for (Symbol t : targets) {
                References refs = q.findReferences(t, ToolSupport.DEFAULT_DEADLINE);
                String header = display(t) + " — " + Shaping.referenceHeader(refs);
                out.addAll(Shaping.referenceSection(header, refs));
                last = refs;
            }
            Shaping.unconfirmedTail(last).ifPresent(out::add);
            return budget.apply(name(), ToolResult.of(out));
        } catch (IOException | QueryTimeoutException e) {
            return ToolResult.error("find_java_references failed: " + e.getMessage());
        }
    }

    /** A target's display: its signature, else its moniker (the always-present identity). */
    private static String display(Symbol s) {
        return s.signature() != null ? s.signature() : s.moniker();
    }
}
