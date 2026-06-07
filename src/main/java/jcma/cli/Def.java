package jcma.cli;

import jcma.engine.Position;
import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.resolve.Definition;
import jcma.resolve.EdgeResolver;
import jcma.workspace.Workspace;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@code jcma def <repo> <symbol>} | {@code jcma def <repo> <file> <line:col>} (task-10) — find-definition
 * in both PRD §6 input modes: by symbol (declaration lookup from the index) or by use-site position
 * (go-to-def, resolved through the engine). The index lives at {@code <repo>/.jcma}.
 */
final class Def {

    private Def() {}

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 3 && args.length != 4) {
            err.println("jcma: usage: jcma def <repo> <symbol>  |  jcma def <repo> <file> <line:col>");
            return 2;
        }
        Path repo = Path.of(args[1]);
        Path indexDir = repo.resolve(".jcma");
        if (!Files.isDirectory(indexDir)) {
            err.println("jcma: no index at " + indexDir + " — run `jcma index " + repo + "` first");
            return 1;
        }
        try (EdgeResolver resolver = EdgeResolver.open(indexDir, Workspace.discover(repo), Metrics.noop())) {
            return args.length == 3
                    ? bySymbol(resolver, args[2], out, err)
                    : byPosition(resolver, args[2], args[3], out, err);
        } catch (Exception e) {
            err.println("jcma: def failed: " + e.getMessage());
            return 1;
        }
    }

    private static int bySymbol(EdgeResolver resolver, String symbol, PrintStream out, PrintStream err) {
        List<Symbol> targets = resolver.declarations(symbol);
        if (targets.isEmpty()) {
            err.println("jcma: no declaration named '" + symbol + "' in the index");
            return 1;
        }
        for (Symbol target : targets) {
            print(out, resolver.findDefinition(target));
        }
        return 0;
    }

    private static int byPosition(EdgeResolver resolver, String file, String posArg,
            PrintStream out, PrintStream err) {
        Position pos = parsePosition(posArg);
        if (pos == null) {
            err.println("jcma: bad position '" + posArg + "' — expected <line:col> (1-based)");
            return 2;
        }
        Optional<Definition> def = resolver.findDefinitionAt(Path.of(file), pos);
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
