package jcma.cli;

import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.resolve.EdgeResolver;
import jcma.resolve.Ref;
import jcma.resolve.ReferenceGroup;
import jcma.resolve.References;
import jcma.resolve.UnconfirmedRef;
import jcma.workspace.Workspace;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jcma refs <repo> <symbol>} (task-10) — Tier-2 find-references: resolve-on-demand, then print
 * confirmed references <b>grouped by enclosing symbol</b> with counts, plus the mandatory
 * <b>unconfirmed tail</b>. The index lives at {@code <repo>/.jcma} (build it with {@code jcma index}).
 */
final class Refs {

    private Refs() {}

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 3) {
            err.println("jcma: usage: jcma refs <repo> <symbol>");
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
                print(out, target, resolver.findReferences(target));
            }
            return 0;
        } catch (Exception e) {
            err.println("jcma: refs failed: " + e.getMessage());
            return 1;
        }
    }

    private static void print(PrintStream out, Symbol target, References refs) {
        out.printf("%nreferences to %s  [%s]%n", display(target), target.moniker());
        out.printf("  %d reference(s) in %d enclosing symbol(s)%n", refs.totalRefs(), refs.groups().size());
        for (ReferenceGroup g : refs.groups()) {
            out.printf("  %s  (%d)%n", g.enclosingSignature(), g.count());
            for (Ref r : g.refs()) {
                out.printf("    %s:%d  %s%n", r.file().getFileName(), r.range().startLine(), r.snippet());
            }
        }
        if (refs.hasUnconfirmedTail()) {
            out.printf("  unconfirmed (%d) — could not be resolved, so this set is not exhaustive:%n",
                    refs.unconfirmed().size());
            for (UnconfirmedRef u : refs.unconfirmed()) {
                out.printf("    %s:%d  %s  [%s]%n",
                        u.file().getFileName(), u.range().startLine(), u.snippet(), u.cause());
            }
        }
    }

    private static String display(Symbol s) {
        return s.signature() != null ? s.signature() : s.moniker();
    }
}
