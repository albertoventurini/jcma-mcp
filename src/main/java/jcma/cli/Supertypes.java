package jcma.cli;

import jcma.index.EdgeType;
import jcma.index.MonikerEdge;
import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.query.QueryService;
import jcma.query.QueryTimeoutException;
import jcma.workspace.FreshnessSource;
import jcma.workspace.IndexLayout;
import jcma.workspace.Workspace;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * {@code jcma supertypes <symbol> [--deadline <ms>]} (task-11a debug aid; time-boxed in
 * task-12) — Tier-2 resolve-on-demand of the type hierarchy, then print a symbol's
 * {@code EXTENDS}/{@code IMPLEMENTS}/{@code OVERRIDES} edges in both directions: its <b>supertypes</b>
 * (out-edges) and its <b>subtypes / implementors / overriders</b> (in-edges, the {@code find_subtypes}
 * primitive). Served through a {@link QueryService} under {@code --deadline}.
 */
final class Supertypes {

    private Supertypes() {}

    static int run(Path cwd, String[] args, PrintStream out, PrintStream err) {
        Deadline.Parsed parsed = Deadline.parse(args);
        if (parsed.error() != null) {
            err.println("jcma: " + parsed.error());
            return 2;
        }
        String[] a = parsed.positional();
        if (a.length != 2) {
            err.println("jcma: usage: jcma supertypes <symbol> [--deadline <ms>]");
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
        try (QuerySessions.Held held = QuerySessions.open(
                indexDir, Workspace.discover(repo), FreshnessSource.none(), Metrics.noop(), err)) {
            QueryService svc = held.service();
            List<Symbol> targets = svc.declarations(symbol, deadline);
            if (targets.isEmpty()) {
                err.println("jcma: no declaration named '" + symbol + "' in the index");
                return 1;
            }
            for (Symbol target : targets) {
                print(out, svc, target, deadline);
            }
            return 0;
        } catch (QueryTimeoutException te) {
            err.println("jcma: " + te.getMessage());
            return 1;
        } catch (Exception e) {
            err.println("jcma: supertypes failed: " + e.getMessage());
            return 1;
        }
    }

    private static void print(PrintStream out, QueryService svc, Symbol target, Duration deadline)
            throws IOException, QueryTimeoutException {
        out.printf("%nhierarchy of %s  [%s]%n", display(target), target.moniker());
        List<MonikerEdge> supers = svc.supertypes(target, deadline);
        List<MonikerEdge> subs = svc.subtypes(target, deadline);
        out.println("  supertypes (out):");
        if (supers.isEmpty()) {
            out.println("    (none)");
        }
        for (MonikerEdge e : supers) {
            out.printf("    %-11s %s%n", label(e.type()), svc.signatureOf(e.dst()));
        }
        out.println("  subtypes / overriders (in):");
        if (subs.isEmpty()) {
            out.println("    (none)");
        }
        for (MonikerEdge e : subs) {
            out.printf("    %-11s %s%n", label(e.type()), svc.signatureOf(e.src()));
        }
    }

    private static String label(EdgeType t) {
        return t.name().toLowerCase();
    }

    private static String display(Symbol s) {
        return s.signature() != null ? s.signature() : s.moniker();
    }
}
