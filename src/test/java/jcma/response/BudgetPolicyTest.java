package jcma.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jcma.index.Range;
import jcma.obs.Metrics;
import jcma.resolve.FailureClassifier;
import jcma.resolve.Ref;
import jcma.resolve.ReferenceGroup;
import jcma.resolve.References;
import jcma.resolve.UnconfirmedRef;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Task-03 — the swappable, instrumented token-budget layer (PRD principle #4: token-bounded answers).
 *
 * <p>The primitive is <b>counts sacred, snippets elastic</b>. Over-cap results degrade <em>fidelity</em>
 * — drop re-fetchable snippet previews (keep every {@code file:line} + count), then roll up to per-file
 * counts (still navigable) — never the completeness of the reference set. The exhaustive total is
 * reported in the {@code Total refs} header at every tier, so the agent is never misled about magnitude.
 */
class BudgetPolicyTest {

    /** A synthetic {@code find_references} result; {@code snippetLen} controls how token-heavy each ref is. */
    private static ToolResult refs(int groups, int refsPerGroup, int unconfirmed, int snippetLen) {
        String body = "SNIPPETBODY" + "z".repeat(Math.max(0, snippetLen));
        List<ReferenceGroup> gs = new ArrayList<>();
        for (int i = 0; i < groups; i++) {
            List<Ref> rs = new ArrayList<>();
            for (int j = 0; j < refsPerGroup; j++) {
                rs.add(new Ref(i, Path.of("src/pkg/File" + i + ".java"),
                        new Range(j + 1, 1, j + 1, 4), body));
            }
            gs.add(new ReferenceGroup("com/acme/Caller" + i + "#run().",
                    "void com.acme.Caller" + i + ".run()", rs));
        }
        List<UnconfirmedRef> tail = new ArrayList<>();
        for (int k = 0; k < unconfirmed; k++) {
            tail.add(new UnconfirmedRef(1000 + k, Path.of("src/pkg/Maybe" + k + ".java"),
                    new Range(k + 1, 1, k + 1, 4), "maybe" + k + "();",
                    FailureClassifier.Cause.OVERLOAD_AMBIGUITY));
        }
        return ToolResult.of(Shaping.references(new References(gs, tail)));
    }

    @Test
    void underCapPassesThroughUntouched() {
        BudgetPolicy p = BudgetPolicy.capped(Map.of(), BudgetPolicy.DEFAULT_CAP, Metrics.noop());
        ToolResult in = refs(3, 2, 0, 10);
        ToolResult out = p.apply("find_references", in);
        assertEquals(in.render(), out.render(), "under-cap result is untouched");
    }

    @Test
    void rungOneDropsSnippetsButKeepsEveryLocationAndTheTrueTotal() {
        Metrics m = Metrics.create();
        BudgetPolicy p = BudgetPolicy.capped(Map.of("find_references", 300), 300, m);
        ToolResult in = refs(4, 3, 0, 140);   // FULL is huge (long snippets); LOCATIONS easily fits 300
        ToolResult out = p.apply("find_references", in);
        String r = out.render();

        assertTrue(p.estimateTokens(r) <= 300, "bounded to the cap, was " + p.estimateTokens(r));
        assertFalse(r.contains("SNIPPETBODY"), "snippet previews are dropped: " + r);
        // Every reference LOCATION survives — the decision-/navigation-bearing data is never lost.
        for (int i = 0; i < 4; i++) {
            for (int j = 1; j <= 3; j++) {
                assertTrue(r.contains("File" + i + ".java:" + j),
                        "kept location File" + i + ".java:" + j + ": " + r);
            }
        }
        assertTrue(r.contains("Total refs: 12 across 4 files"), "true total reported: " + r);
        assertTrue(r.contains("omitted"), "the agent is told snippets were dropped: " + r);
        assertEquals(1, m.counter("response.budget.truncated").sum(), "degrade is instrumented");
        assertTrue(m.counter("response.budget.pre_tokens").sum() > 0, "pre-size recorded");
        assertTrue(m.counter("response.budget.post_tokens").sum() > 0, "post-size recorded");
    }

    @Test
    void rungTwoRollsUpToPerFileCountsButKeepsEveryFileAndTheTrueTotal() {
        BudgetPolicy p = BudgetPolicy.capped(Map.of(), 200, Metrics.noop());
        ToolResult in = refs(4, 30, 0, 2);   // 120 refs: even bare locations bust 200; per-file rollup fits
        ToolResult out = p.apply("find_references", in);
        String r = out.render();

        assertTrue(p.estimateTokens(r) <= 200, "bounded to the cap, was " + p.estimateTokens(r));
        assertTrue(r.contains("Total refs: 120 across 4 files"), "true total still reported: " + r);
        assertFalse(r.contains(".java:1"), "per-reference line entries are rolled away: " + r);
        for (int i = 0; i < 4; i++) {
            assertTrue(r.contains("src/pkg/File" + i + ".java: 30 refs"),
                    "every file is kept (navigable) with its count: " + r);
        }
        assertTrue(r.contains("rolled up"), "the agent is told it was rolled up: " + r);
    }

    @Test
    void pathologicalCapStillReportsTheTrueTotalAndDropsNoFile() {
        // Even the per-file summary busts a 1-token cap: returned over budget (lossless), with the
        // exhaustive total and an advisory to paginate/narrow — never silently dropping a file.
        BudgetPolicy p = BudgetPolicy.capped(Map.of(), 1, Metrics.noop());
        ToolResult out = p.apply("find_references", refs(4, 30, 0, 2));
        String r = out.render();

        assertTrue(r.contains("Total refs: 120 across 4 files"), "exhaustive total is never lost: " + r);
        for (int i = 0; i < 4; i++) {
            assertTrue(r.contains("src/pkg/File" + i + ".java: 30 refs"), "no file dropped: " + r);
        }
        assertTrue(r.contains("paginate"), "advises how to get the rest: " + r);
    }

    @Test
    void unconfirmedTailHeaderIsNeverDropped() {
        BudgetPolicy p = BudgetPolicy.capped(Map.of(), 1, Metrics.noop());
        ToolResult out = p.apply("find_references", refs(4, 30, 3, 2));
        assertTrue(out.render().contains("NOT exhaustive"),
                "the non-exhaustive tail header survives even a tiny cap: " + out.render());
    }

    @Test
    void errorResultBypassesBudgeting() {
        Metrics m = Metrics.create();
        BudgetPolicy p = BudgetPolicy.capped(Map.of(), 1, m);
        ToolResult err = ToolResult.error("boom: the tool blew up with a very long message ".repeat(20));
        ToolResult out = p.apply("find_references", err);
        assertEquals(err.render(), out.render(), "error is returned verbatim");
        assertTrue(out.isError());
        assertEquals(1, m.counter("response.budget.bypassed").sum());
        assertEquals(0, m.counter("response.budget.applied").sum(), "an error is never budgeted");
    }

    @Test
    void estimateTokensIsMonotoneInContentLength() {
        BudgetPolicy p = BudgetPolicy.capped(Map.of(), BudgetPolicy.DEFAULT_CAP, Metrics.noop());
        assertEquals(0, p.estimateTokens(""));
        assertEquals(1, p.estimateTokens("x"));
        assertEquals(1, p.estimateTokens("xxxx"));
        assertEquals(2, p.estimateTokens("xxxxx"));
        for (int n = 0; n < 500; n++) {
            assertTrue(p.estimateTokens("x".repeat(n)) <= p.estimateTokens("x".repeat(n + 1)),
                    "estimate must be monotone in length at n=" + n);
        }
    }

    @Test
    void manualNeverTruncates() {
        BudgetPolicy p = BudgetPolicy.manual();
        ToolResult in = refs(100, 5, 0, 40);
        ToolResult out = p.apply("find_references", in);
        assertEquals(in.render(), out.render(), "manual() passes everything through");
    }
}
