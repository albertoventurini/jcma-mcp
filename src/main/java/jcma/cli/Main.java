package jcma.cli;

import jcma.engine.JavaParserEngine;
import jcma.engine.ParsedUnit;
import jcma.engine.Position;
import jcma.engine.ResolvedRef;
import jcma.engine.ResolvedType;
import jcma.workspace.Workspace;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Dev-only {@code jcma} CLI — the M1 verification surface (PRD §9; M2 replaces/augments this with
 * the MCP server). Argument dispatch only; subcommands are added per M1 task. The exact same
 * binary runs under GraalVM native-image, so the entry points here are also the native smoke
 * targets (task protocol step 4b).
 */
public final class Main {

    /** Bumped manually; surfaced by {@code jcma version} and the native smoke. */
    static final String VERSION = "0.1.0-SNAPSHOT";

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Testable entry point: dispatch {@code args}, write to the given streams, return the process
     * exit code (0 ok, 2 usage error, 1 subcommand failure).
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            usage(err);
            return 2;
        }
        String cmd = args[0];
        switch (cmd) {
            case "version", "--version", "-v" -> {
                out.println("jcma " + VERSION);
                return 0;
            }
            case "selftest" -> {
                return SelfTest.run(out);
            }
            case "resolve" -> {
                return resolve(args, out, err);
            }
            case "index-dump" -> {
                return IndexDump.run(args, out, err);
            }
            case "search" -> {
                return Search.run(args, out, err);
            }
            case "stats" -> {
                return Stats.run(args, out, err);
            }
            default -> {
                err.println("jcma: unknown subcommand '" + cmd + "'");
                usage(err);
                return 2;
            }
        }
    }

    /**
     * {@code jcma resolve <file> <line:col>} — discover the enclosing workspace, resolve the symbol
     * at the position through {@link JavaParserEngine}, print FQN + signature + declaration site.
     * Exit 0 on resolved, 1 on unresolved, 2 on usage error.
     */
    private static int resolve(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 3) {
            err.println("jcma: usage: jcma resolve <file> <line:col>");
            return 2;
        }
        Path file = Path.of(args[1]);
        Position pos = parsePosition(args[2]);
        if (pos == null) {
            err.println("jcma: bad position '" + args[2] + "' — expected <line:col> (1-based)");
            return 2;
        }
        try {
            JavaParserEngine engine = new JavaParserEngine(Workspace.discover(file));
            ParsedUnit unit = engine.parse(file);

            Optional<ResolvedRef> ref = engine.resolveMethodCall(unit, pos);
            if (ref.isPresent()) {
                ResolvedRef r = ref.get();
                out.println("fqn:       " + r.fqn());
                out.println("signature: " + r.signature());
                out.println("declared:  " + declSite(r.declFile(), r.declLine()));
                return 0;
            }
            Optional<ResolvedType> type = engine.resolveType(unit, pos);
            if (type.isPresent()) {
                ResolvedType t = type.get();
                out.println("fqn:       " + t.fqn());
                out.println("declared:  " + declSite(t.declFile(), t.declLine()));
                return 0;
            }
            err.println("jcma: unresolved at " + args[1] + ":" + pos.line() + ":" + pos.col());
            return 1;
        } catch (Exception e) {
            err.println("jcma: resolve failed: " + e.getMessage());
            return 1;
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

    private static String declSite(Path declFile, int declLine) {
        return declFile == null ? "<external (jar/jdk)>" : declFile + ":" + declLine;
    }

    private static void usage(PrintStream err) {
        err.println("""
                usage: jcma <subcommand> [args]

                subcommands:
                  version              print the jcma version
                  selftest             run the native-image smoke capabilities (parse, mmap, ...)
                  resolve <file> <line:col>
                                       resolve the symbol at a 1-based position; print its
                                       FQN, signature, and declaration site
                  index-dump --symbols <indexDir>
                                       list the symbols + monikers in a persisted index store
                  index-dump --edges <indexDir> <moniker>
                                       print a symbol's fwd/rev neighbours with edge types
                  search <indexDir> <query>
                                       ranked symbols whose name contains <query>
                                       (case-sensitive), read from the trigram index
                  stats <indexDir>     base/overlay sizes, overlay-file count, and the
                                       overlay/base ratio the compaction policy reasons about
                """);
    }
}
