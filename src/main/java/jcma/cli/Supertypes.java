package jcma.cli;

import jcma.index.EdgeType;
import jcma.index.MonikerEdge;
import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.resolve.EdgeResolver;
import jcma.workspace.Workspace;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jcma supertypes <repo> <symbol>} (task-11a debug aid) — Tier-2 resolve-on-demand of the type
 * hierarchy, then print a symbol's {@code EXTENDS}/{@code IMPLEMENTS}/{@code OVERRIDES} edges in both
 * directions: its <b>supertypes</b> (out-edges) and its <b>subtypes / implementors / overriders</b>
 * (in-edges, the {@code find_subtypes} primitive). Minimal, eyeball-against-a-real-type surface.
 */
final class Supertypes {

    private Supertypes() {}

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 3) {
            err.println("jcma: usage: jcma supertypes <repo> <symbol>");
            return 2;
        }
        Path repo = Path.of(args[1]);
        String symbol = args[2];
        Path indexDir = repo.resolve(".jcma");
        if (!Files.isDirectory(indexDir)) {
            err.println("jcma: no index at " + indexDir + " — run `jcma index " + repo + "` first");
            return 1;
        }
        try (EdgeResolver resolver = EdgeResolver.open(indexDir, Workspace.discover(repo), Metrics.noop())) {
            List<Symbol> targets = resolver.declarations(symbol);
            if (targets.isEmpty()) {
                err.println("jcma: no declaration named '" + symbol + "' in the index");
                return 1;
            }
            for (Symbol target : targets) {
                print(out, resolver, target);
            }
            return 0;
        } catch (Exception e) {
            err.println("jcma: supertypes failed: " + e.getMessage());
            return 1;
        }
    }

    private static void print(PrintStream out, EdgeResolver resolver, Symbol target) {
        out.printf("%nhierarchy of %s  [%s]%n", display(target), target.moniker());
        List<MonikerEdge> supers = resolver.supertypes(target);
        List<MonikerEdge> subs = resolver.subtypes(target);
        out.println("  supertypes (out):");
        if (supers.isEmpty()) {
            out.println("    (none)");
        }
        for (MonikerEdge e : supers) {
            out.printf("    %-11s %s%n", label(e.type()), resolver.signatureOf(e.dst()));
        }
        out.println("  subtypes / overriders (in):");
        if (subs.isEmpty()) {
            out.println("    (none)");
        }
        for (MonikerEdge e : subs) {
            out.printf("    %-11s %s%n", label(e.type()), resolver.signatureOf(e.src()));
        }
    }

    private static String label(EdgeType t) {
        return t.name().toLowerCase();
    }

    private static String display(Symbol s) {
        return s.signature() != null ? s.signature() : s.moniker();
    }
}
