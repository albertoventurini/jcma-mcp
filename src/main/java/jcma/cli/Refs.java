package jcma.cli;

import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.obs.MetricsReport;
import jcma.query.QueryService;
import jcma.query.QueryTimeoutException;
import jcma.resolve.Ref;
import jcma.resolve.ReferenceGroup;
import jcma.resolve.References;
import jcma.resolve.UnconfirmedRef;
import jcma.workspace.FreshnessSource;
import jcma.workspace.IndexLayout;
import jcma.workspace.Workspace;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * {@code jcma refs <symbol> [--deadline <ms>]} (task-10; time-boxed in task-12) — Tier-2
 * find-references: resolve-on-demand, then print confirmed references <b>grouped by enclosing
 * symbol</b> with counts, plus the mandatory <b>unconfirmed tail</b>. The query is served through a
 * {@link QueryService} under {@code --deadline} (a clean timeout if exceeded). The index lives in
 * the user cache (build it with {@code jcma index}).
 */
final class Refs {

    private Refs() {}

    static int run(Path cwd, String[] args, PrintStream out, PrintStream err) {
        // Opt-in perf instrumentation: `--metrics` records the resolve.parse / resolve.values /
        // resolve.typerefs timer split and dumps it to stderr after the query. Stripped before the rest
        // of arg parsing so the positional check is unaffected.
        boolean recordMetrics = false;
        List<String> filtered = new java.util.ArrayList<>();
        for (String arg : args) {
            if (arg.equals("--metrics")) {
                recordMetrics = true;
            } else {
                filtered.add(arg);
            }
        }
        args = filtered.toArray(new String[0]);
        Metrics metrics = recordMetrics ? Metrics.create() : Metrics.noop();
        Deadline.Parsed parsed = Deadline.parse(args);
        if (parsed.error() != null) {
            err.println("jcma: " + parsed.error());
            return 2;
        }
        String[] a = parsed.positional();
        if (a.length != 2) {
            err.println("jcma: usage: jcma refs <symbol> [--deadline <ms>]");
            return 2;
        }
        Path repo = Workspace.projectRoot(cwd);
        String symbol = a[1];
        Duration deadline = parsed.deadline();
        Path indexDir = IndexLayout.defaultIndexDir(repo);
        if (!Files.isDirectory(indexDir)) {
            err.println("jcma: no index for " + repo + " — run `jcma index` first");
            return 1;
        }
        Workspace workspace = Workspace.discover(repo, indexDir);
        err.println("jcma: loading " + workspace.classpathJars().size() + " dependency jars…");
        try (QuerySessions.Held held = QuerySessions.open(
                indexDir, workspace, FreshnessSource.none(), metrics, err)) {
            QueryService svc = held.service();
            List<Symbol> targets = svc.resolveTargets(symbol, deadline);
            if (targets.isEmpty()) {
                err.println("jcma: no declaration named '" + symbol + "' in the index");
                return 1;
            }
            References shared = null;
            for (Symbol target : targets) {
                References refs = svc.findReferences(target, deadline);
                printConfirmed(out, target, refs);
                shared = refs;
            }
            // The unconfirmed tail is keyed by the query's simple name (every miss on that name
            // coalesces onto one placeholder node), so it is identical for every same-named declaration
            // — e.g. a type and its constructor. Print it once for the query, not once per declaration.
            if (shared != null && shared.hasUnconfirmedTail()) {
                printUnconfirmed(out, shared);
            }
            if (recordMetrics) {
                err.println();
                err.println(MetricsReport.format(metrics));
            }
            return 0;
        } catch (QueryTimeoutException te) {
            err.println("jcma: " + te.getMessage());
            return 1;
        } catch (Exception e) {
            err.println("jcma: refs failed: " + e.getMessage());
            return 1;
        }
    }

    private static void printConfirmed(PrintStream out, Symbol target, References refs) {
        out.printf("%nreferences to %s  [%s]%n", display(target), target.moniker());
        out.printf("  %d reference(s) in %d enclosing symbol(s)%n", refs.totalRefs(), refs.groups().size());
        for (ReferenceGroup g : refs.groups()) {
            out.printf("  %s  (%d)%n", g.enclosingSignature(), g.count());
            for (Ref r : g.refs()) {
                out.printf("    %s:%d  %s%n", r.file().getFileName(), r.range().startLine(), r.snippet());
            }
        }
    }

    /** The query-wide unconfirmed tail (name-keyed; printed once, after every declaration's confirmed set). */
    private static void printUnconfirmed(PrintStream out, References refs) {
        out.printf("%nunconfirmed (%d) — could not be resolved, so this set is not exhaustive:%n",
                refs.unconfirmed().size());
        for (UnconfirmedRef u : refs.unconfirmed()) {
            out.printf("    %s:%d  %s  [%s]%n",
                    u.file().getFileName(), u.range().startLine(), u.snippet(), u.cause());
        }
    }

    private static String display(Symbol s) {
        return s.signature() != null ? s.signature() : s.moniker();
    }
}
