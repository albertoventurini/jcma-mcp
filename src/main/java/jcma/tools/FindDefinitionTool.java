package jcma.tools;

import jcma.engine.Position;
import jcma.index.Symbol;
import jcma.mcp.ToolHandler;
import jcma.mcp.json.JsonValue;
import jcma.mcp.json.JsonValue.JsonObject;
import jcma.query.QueryService;
import jcma.query.QueryTimeoutException;
import jcma.resolve.Definition;
import jcma.response.BudgetPolicy;
import jcma.response.Shaping;
import jcma.response.ToolResult;
import jcma.response.ToolResult.Fragment;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The {@code find_definition} §6 tool (M2 task-04): jump to a symbol's declaration in either input
 * mode — by <b>symbol</b> (a simple name → all matching declarations, or a dotted
 * <em>qualified.name</em> that filters those matches) or by <b>position</b> ({@code file}+{@code
 * line}+{@code col}, the precise go-to-def disambiguator). Shapes each answer via {@link
 * jcma.response.Shaping} and bounds it via the injected {@link BudgetPolicy}. The {@link QueryService}
 * is reached through a {@link Supplier} — the same lazy session seam {@code HealthTool} uses.
 */
public final class FindDefinitionTool implements ToolHandler {

    private final Supplier<QueryService> svc;
    private final BudgetPolicy budget;

    public FindDefinitionTool(Supplier<QueryService> svc, BudgetPolicy budget) {
        this.svc = svc;
        this.budget = budget;
    }

    @Override
    public String name() {
        return "find_definition";
    }

    @Override
    public String description() {
        return "Find the declaration of a symbol — by name (optionally qualified) or by file:line:col position.";
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
            return ToolResult.error("find_definition: provide either {symbol} or {file, line, col}");
        }
        try {
            QueryService q = svc.get();
            if (posMode) {
                Optional<Definition> def =
                        q.findDefinitionAt(Path.of(file), new Position(line, col), ToolSupport.DEFAULT_DEADLINE);
                if (def.isEmpty()) {
                    return ToolResult.error("unresolved at " + file + ":" + line + ":" + col);
                }
                return budget.apply(name(), ToolResult.of(List.of(Shaping.definition(def.get()))));
            }
            List<Symbol> targets = q.resolveTargets(symbol, ToolSupport.DEFAULT_DEADLINE);
            if (targets.isEmpty()) {
                return ToolResult.error("no declaration named '" + symbol + "'");
            }
            List<Fragment> out = new ArrayList<>(targets.size());
            for (Symbol t : targets) {
                out.add(Shaping.definition(q.findDefinition(t, ToolSupport.DEFAULT_DEADLINE)));
            }
            return budget.apply(name(), ToolResult.of(out));
        } catch (IOException | QueryTimeoutException e) {
            return ToolResult.error("find_definition failed: " + e.getMessage());
        }
    }
}
