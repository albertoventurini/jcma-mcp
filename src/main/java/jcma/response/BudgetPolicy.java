package jcma.response;

import jcma.obs.Metrics;
import jcma.response.ToolResult.Fidelity;
import jcma.response.ToolResult.FileCount;
import jcma.response.ToolResult.FileRollupFragment;
import jcma.response.ToolResult.Fragment;
import jcma.response.ToolResult.RefGroupFragment;
import jcma.response.ToolResult.RefLine;
import jcma.response.ToolResult.TextFragment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The swappable, instrumented token-budget layer (PRD principle #4: token-bounded answers). Mirrors
 * {@link jcma.index.CompactionPolicy}: a single-method seam with static factories ({@link #manual},
 * {@link #capped}) so the cap strategy is changed without touching callers. A §6 tool routes its
 * result through {@link #apply} before returning; the transport stays unaware of budgeting.
 *
 * <h2>Primitive: counts sacred, snippets elastic</h2>
 * Over-cap results degrade <em>fidelity</em>, never the completeness of the reference set:
 * <ol>
 *   <li><b>Drop snippets</b> — keep every {@code file:line} + count ({@link Fidelity#LOCATIONS}).
 *       Lossless: a snippet is re-fetchable by reading the kept location.</li>
 *   <li><b>Roll up per file</b> — {@code path: N refs}. Lossy on exact lines but still navigable (the
 *       agent knows which file to open); the total stays exhaustive in the {@code Total refs} header.</li>
 *   <li><b>Paginate</b> — when even the file summary is too big. The {@code offset}/{@code limit}
 *       mechanism is a query concern (a {@code find_references} feature, tasks 4–7); here the policy
 *       returns the file rollup over budget with an advisory note rather than dropping any file. The
 *       sacred total is never wrong, and the hard bound for the tail lands with pagination.</li>
 * </ol>
 *
 * <h2>Caps</h2>
 * One generous {@link #DEFAULT_CAP} now, keyed by tool name so a per-tool table can be supplied later
 * via {@link #capped}. <b>Provisional / uncalibrated</b> — the per-tool table + corpus calibration are
 * deferred to tasks 4–7.
 */
@FunctionalInterface
public interface BudgetPolicy {

    /**
     * Default per-result cap, in tokens (the agent's currency; internal compares use the chars/4
     * estimate). <b>Provisional / uncalibrated</b> — one generous constant until tasks 4–7 measure real
     * results and decide per-tool caps. ~4000 tokens ≈ 16k chars ≈ a few hundred reference locations.
     */
    int DEFAULT_CAP = 4000;

    /** Shape {@code result} to {@code toolName}'s token cap (see the class primitive). */
    ToolResult apply(String toolName, ToolResult result);

    /** Token estimate: {@code ceil(chars/4)} — no tokenizer dependency, native-clean, monotone in length. */
    default int estimateTokens(String text) {
        return tokens(text);
    }

    static int tokens(String text) {
        return (text.length() + 3) / 4;
    }

    /** Never truncates (cap = ∞) — the {@link jcma.index.CompactionPolicy#manual} analogue. */
    static BudgetPolicy manual() {
        return (toolName, result) -> result;
    }

    /** The default wiring: a generous {@link #DEFAULT_CAP} for every tool, instrumented via {@code metrics}. */
    static BudgetPolicy defaultPolicy(Metrics metrics) {
        return capped(Map.of(), DEFAULT_CAP, metrics);
    }

    /**
     * A cap of {@code perToolCaps.get(name)} (else {@code defaultCap}) tokens per result, instrumented
     * at the per-result boundary (coarse — never per fragment/token) via {@code metrics}.
     */
    static BudgetPolicy capped(Map<String, Integer> perToolCaps, int defaultCap, Metrics metrics) {
        Map<String, Integer> caps = Map.copyOf(perToolCaps);
        return (toolName, result) -> {
            long t0 = System.nanoTime();
            try {
                if (result.isError()) {
                    metrics.counter("response.budget.bypassed").increment();
                    return result; // a tool failure is returned verbatim — never budgeted
                }
                int cap = caps.getOrDefault(toolName, defaultCap);
                int pre = tokens(result.render());
                metrics.counter("response.budget.pre_tokens").add(pre);
                metrics.counter("response.budget.applied").increment();
                if (pre <= cap) {
                    metrics.counter("response.budget.post_tokens").add(pre);
                    return result;
                }
                ToolResult reduced = reduceToFit(result, cap);
                metrics.counter("response.budget.truncated").increment();
                metrics.counter("response.budget.post_tokens").add(tokens(reduced.render()));
                return reduced;
            } finally {
                metrics.timer("response.budget").record(System.nanoTime() - t0);
            }
        };
    }

    // ---- reduction (counts sacred, snippets elastic) --------------------------------------------

    private static ToolResult reduceToFit(ToolResult result, int cap) {
        // Reference groups are contiguous (Shaping: header, groups, then the tail). Split around them.
        List<Fragment> head = new ArrayList<>();
        List<RefGroupFragment> groups = new ArrayList<>();
        List<Fragment> tail = new ArrayList<>();
        boolean seenGroup = false;
        for (Fragment f : result.fragments()) {
            if (f instanceof RefGroupFragment g) {
                groups.add(g);
                seenGroup = true;
            } else if (seenGroup) {
                tail.add(f);
            } else {
                head.add(f);
            }
        }
        if (groups.isEmpty()) {
            return result; // nothing reference-shaped to degrade (e.g. a definition) — pass through
        }

        // Rung 1: drop snippet previews; keep every file:line + count. Lossless.
        List<Fragment> atLoc = new ArrayList<>(head);
        for (RefGroupFragment g : groups) {
            atLoc.add(g.at(Fidelity.LOCATIONS));
        }
        atLoc.addAll(tail);
        atLoc.add(new TextFragment(
                "Snippet previews omitted to fit the token budget; open each file:line for the source line."));
        if (tokens(render(atLoc)) <= cap) {
            return ToolResult.of(atLoc);
        }

        // Rung 2: roll references up to a per-file count. Lossy on exact lines, still file-navigable.
        FileRollupFragment rollup = rollupByFile(groups);
        List<Fragment> atRollup = new ArrayList<>(head);
        atRollup.add(rollup);
        atRollup.addAll(tail);
        boolean fits = tokens(render(atRollup)) <= cap;
        atRollup.add(new TextFragment(fits
                ? "Per-line locations rolled up to per-file counts to fit the token budget; "
                        + "narrow the query (or paginate) for line-level detail."
                : "Result exceeds the token budget even as a per-file summary; "
                        + "paginate with offset/limit or narrow the query for detail."));
        return ToolResult.of(atRollup);
    }

    /** Aggregate every reference across all groups into a per-file count, in first-seen file order. */
    private static FileRollupFragment rollupByFile(List<RefGroupFragment> groups) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RefGroupFragment g : groups) {
            for (RefLine r : g.refs()) {
                counts.merge(ToolResult.fileOf(r.location()), 1, Integer::sum);
            }
        }
        List<FileCount> files = new ArrayList<>(counts.size());
        counts.forEach((file, n) -> files.add(new FileCount(file, n)));
        return new FileRollupFragment(files);
    }

    private static String render(List<Fragment> fragments) {
        return new ToolResult(fragments, false).render();
    }
}
