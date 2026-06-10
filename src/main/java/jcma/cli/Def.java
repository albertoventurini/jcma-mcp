package jcma.cli;

import jcma.engine.Position;
import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.query.QueryService;
import jcma.query.QueryTimeoutException;
import jcma.resolve.Definition;
import jcma.session.AnalysisSession;
import jcma.workspace.IndexLayout;
import jcma.workspace.Workspace;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * {@code jcma def <symbol>} | {@code jcma def <file> <line:col>} {@code [--deadline <ms>]}
 * (task-10; time-boxed in task-12) — find-definition in both PRD §6 input modes: by symbol (declaration
 * lookup from the index) or by use-site position (go-to-def, resolved through the engine). Served
 * through a {@link QueryService} under {@code --deadline}. The index lives in the user cache.
 */
final class Def {

    private Def() {}

    static int run(Path cwd, String[] args, PrintStream out, PrintStream err) {
        Deadline.Parsed parsed = Deadline.parse(args);
        if (parsed.error() != null) {
            err.println("jcma: " + parsed.error());
            return 2;
        }
        String[] a = parsed.positional();
        if (a.length != 2 && a.length != 3) {
            err.println("jcma: usage: jcma def <symbol>  |  jcma def <file> <line:col>"
                    + "  [--deadline <ms>]");
            return 2;
        }
        Path repo = Workspace.projectRoot(cwd);
        Duration deadline = parsed.deadline();
        Path indexDir = IndexLayout.defaultIndexDir(repo);
        if (!Files.isDirectory(indexDir)) {
            err.println("jcma: no index for " + repo + " — run `jcma index` first");
            return 1;
        }
        try (QueryService svc = new QueryService(
                AnalysisSession.open(indexDir, Workspace.discover(repo), Metrics.noop()))) {
            return a.length == 2
                    ? bySymbol(svc, a[1], deadline, out, err)
                    : byPosition(svc, a[1], a[2], deadline, out, err);
        } catch (QueryTimeoutException te) {
            err.println("jcma: " + te.getMessage());
            return 1;
        } catch (Exception e) {
            err.println("jcma: def failed: " + e.getMessage());
            return 1;
        }
    }

    private static int bySymbol(QueryService svc, String symbol, Duration deadline, PrintStream out, PrintStream err)
            throws IOException, QueryTimeoutException {
        List<Symbol> targets = svc.resolveTargets(symbol, deadline);
        if (targets.isEmpty()) {
            err.println("jcma: no declaration named '" + symbol + "' in the index");
            return 1;
        }
        for (Symbol target : targets) {
            print(out, svc.findDefinition(target, deadline));
        }
        return 0;
    }

    private static int byPosition(QueryService svc, String file, String posArg, Duration deadline,
            PrintStream out, PrintStream err) throws IOException, QueryTimeoutException {
        Position pos = parsePosition(posArg);
        if (pos == null) {
            err.println("jcma: bad position '" + posArg + "' — expected <line:col> (1-based)");
            return 2;
        }
        Optional<Definition> def = svc.findDefinitionAt(Path.of(file), pos, deadline);
        if (def.isEmpty()) {
            err.println("jcma: unresolved at " + file + ":" + pos.line() + ":" + pos.col());
            return 1;
        }
        print(out, def.get());
        return 0;
    }

    private static void print(PrintStream out, Definition def) {
        out.println("signature: " + def.signature());
        out.println("moniker:   " + def.moniker());
        out.println("declared:  " + (def.file() == null ? "<external (jar/jdk)>"
                : def.file() + ":" + def.line()));
        if (!def.snippet().isEmpty()) {
            out.println("snippet:   " + def.snippet());
        }
    }

    private static Position parsePosition(String s) {
        int colon = s.indexOf(':');
        if (colon <= 0 || colon == s.length() - 1) {
            return null;
        }
        try {
            int line = Integer.parseInt(s.substring(0, colon).trim());
            int col = Integer.parseInt(s.substring(colon + 1).trim());
            return (line >= 1 && col >= 1) ? new Position(line, col) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
