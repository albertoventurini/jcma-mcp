package jcma.cli;

import jcma.engine.Position;
import jcma.index.MonikerEdge;
import jcma.index.Symbol;
import jcma.obs.Metrics;
import jcma.obs.MetricsReport;
import jcma.query.QueryService;
import jcma.resolve.Definition;
import jcma.resolve.Ref;
import jcma.resolve.ReferenceGroup;
import jcma.resolve.References;
import jcma.resolve.UnconfirmedRef;
import jcma.workspace.FreshnessSource;
import jcma.workspace.IndexLayout;
import jcma.workspace.TreeScanSource;
import jcma.workspace.Workspace;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * {@code jcma repl} (M1 task-11c; time-boxed in task-12) — a tiny long-running query loop over a
 * single {@link AnalysisSession}, served through a {@link QueryService}. Unlike the one-shot
 * subcommands (a fresh process, cold cache, per query), the REPL keeps the session — and its Tier-2
 * edge cache — alive across queries, and drives a {@link TreeScanSource} so an out-of-band edit is
 * detected, re-indexed, and <b>cascaded</b> before the next query serves an answer. Each command takes
 * an optional {@code --deadline <ms>} time-box (e.g. {@code refs Foo --deadline 50}), exercising
 * cancellation against the warm cache by hand. This is the in-process model the MCP server (M2) uses.
 */
final class Repl {

    private Repl() {}

    static int run(Path cwd, String[] args, PrintStream out, PrintStream err) {
        // Optional `--metrics`: record across the whole warm session and dump the cumulative timer
        // split on exit. In a warm session resolve.tier1 fires once per file ever touched (= unique
        // files), while resolve.parse fires per (file, name) — so (parse.count − tier1.count) is the
        // redundant re-parse the semantic parse cache would eliminate (its realized session win).
        boolean recordMetrics = args.length == 2 && args[1].equals("--metrics");
        if (!(args.length == 1 || recordMetrics)) {
            err.println("jcma: usage: jcma repl [--metrics]");
            return 2;
        }
        Metrics metrics = recordMetrics ? Metrics.create() : Metrics.noop();
        Path repo = Workspace.projectRoot(cwd);
        Path indexDir = IndexLayout.defaultIndexDir(repo);
        if (!Files.isDirectory(indexDir)) {
            err.println("jcma: no index for " + repo + " — run `jcma index` first");
            return 1;
        }
        Workspace workspace = Workspace.discover(repo);
        FreshnessSource source = new TreeScanSource(workspace.sourceRoots());
        try (QuerySessions.Held held = QuerySessions.open(indexDir, workspace, source, metrics, err);
                Terminal terminal = TerminalBuilder.terminal()) {
            QueryService svc = held.service();
            out.println("jcma repl — commands: refs <symbol> | def <symbol> | def <file> <line:col> "
                    + "| supertypes <symbol> | quit   (any query takes an optional --deadline <ms>)");

            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .history(new DefaultHistory())
                    .build();

            String line;
            while ((line = reader.readLine("jcma> ")) != null) {
                String trimmed = line.trim();
                if (trimmed.equals("quit") || trimmed.equals("exit")) {
                    break;
                }
                if (!trimmed.isEmpty()) {
                    Deadline.Parsed parsed = Deadline.parse(trimmed.split("\\s+"));
                    if (parsed.error() != null) {
                        err.println(parsed.error());
                    } else {
                        try {
                            dispatch(svc, parsed.positional(), parsed.deadline(), out, err);
                        } catch (Exception e) {
                            err.println("jcma: " + e.getMessage());
                        }
                    }
                }
            }
            if (recordMetrics) {
                err.println();
                err.println(MetricsReport.format(metrics));
            }
            return 0;
        } catch (Exception e) {
            err.println("jcma: repl failed: " + e.getMessage());
            return 1;
        }
    }

    private static void dispatch(QueryService svc, String[] cmd, Duration deadline, PrintStream out, PrintStream err)
            throws Exception {
        switch (cmd[0]) {
            case "refs" -> {
                if (cmd.length != 2) {
                    err.println("usage: refs <symbol> [--deadline <ms>]");
                    return;
                }
                List<Symbol> targets = svc.resolveTargets(cmd[1], deadline);
                if (targets.isEmpty()) {
                    err.println("no declaration named '" + cmd[1] + "' in the index");
                    return;
                }
                // The unconfirmed tail is name-keyed, so it is identical for every same-named
                // declaration (e.g. a type and its constructor) — print it once for the query.
                References shared = null;
                for (Symbol target : targets) {
                    References refs = svc.findReferences(target, deadline);
                    printRefs(out, target, refs);
                    shared = refs;
                }
                if (shared != null && shared.hasUnconfirmedTail()) {
                    printUnconfirmed(out, shared);
                }
            }
            case "supertypes" -> {
                if (cmd.length != 2) {
                    err.println("usage: supertypes <symbol> [--deadline <ms>]");
                    return;
                }
                forEachDeclaration(svc, cmd[1], deadline, out, err,
                        target -> printHierarchy(out, svc, target, deadline));
            }
            case "def" -> {
                if (cmd.length == 2) {
                    forEachDeclaration(svc, cmd[1], deadline, out, err,
                            target -> printDef(out, svc.findDefinition(target, deadline)));
                } else if (cmd.length == 3) {
                    Position pos = parsePosition(cmd[2]);
                    if (pos == null) {
                        err.println("bad position '" + cmd[2] + "' — expected <line:col> (1-based)");
                        return;
                    }
                    Optional<Definition> def = svc.findDefinitionAt(Path.of(cmd[1]), pos, deadline);
                    if (def.isEmpty()) {
                        err.println("unresolved at " + cmd[1] + ":" + cmd[2]);
                    } else {
                        printDef(out, def.get());
                    }
                } else {
                    err.println("usage: def <symbol>  |  def <file> <line:col>  [--deadline <ms>]");
                }
            }
            case "help" -> printHelp(out);
            default -> err.println("unknown command '" + cmd[0] + "' — try: help | refs | def | supertypes | quit | exit");
        }
    }

    /** Resolve {@code symbol} to its declaration(s) and apply {@code action} to each; report if none. */
    private static void forEachDeclaration(QueryService svc, String symbol, Duration deadline, PrintStream out,
            PrintStream err, ThrowingConsumer<Symbol> action) throws Exception {
        List<Symbol> targets = svc.resolveTargets(symbol, deadline);
        if (targets.isEmpty()) {
            err.println("no declaration named '" + symbol + "' in the index");
            return;
        }
        for (Symbol target : targets) {
            action.accept(target);
        }
    }

    private static void printHelp(PrintStream out) {
        out.println("""
                jcma repl — interactive query loop over a warm AnalysisSession

                Commands:
                  refs <symbol>
                    Find all references to a declaration. <symbol> is a simple
                    (unqualified) name, e.g. a method name, class name, or field name.
                    If multiple declarations share the same name, results for each are printed.
                    Examples:
                      refs run
                      refs Service
                      refs execute --deadline 500

                  def <symbol>
                    Find the definition of a declaration by its simple name.
                    Examples:
                      def run
                      def getProperty
                      def Base --deadline 2s

                  def <file> <line:col>
                    Find the definition at a specific use-site position. <file> is a
                    path relative to the repo root. <line:col> is 1-based.
                    Example:
                      def src/main/java/Client.java 7:24

                  supertypes <symbol>
                    Print supertype/subtype edges in both directions (EXTENDS, IMPLEMENTS,
                    OVERRIDES), showing what a type inherits from and what inherits from it.
                    Examples:
                      supertypes Base
                      supertypes ArrayList

                  help                        print this message
                  quit | exit                 exit the REPL

                Every query command accepts an optional --deadline <ms> time-box.
                Deadlines accept bare milliseconds (2000), ms suffix (2000ms), or
                s suffix (3s). The default is 30 seconds.

                The session keeps the Tier-2 edge cache alive across queries, so
                repeated lookups are fast. Out-of-band file edits are detected and
                re-indexed before the next query.""");
    }

    private static void printRefs(PrintStream out, Symbol target, References refs) {
        out.printf("%nreferences to %s  [%s]%n",
                target.signature() != null ? target.signature() : target.moniker(), target.moniker());
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
        out.printf("%nunconfirmed (%d):%n", refs.unconfirmed().size());
        for (UnconfirmedRef u : refs.unconfirmed()) {
            out.printf("    %s:%d  %s  [%s]%n", u.file().getFileName(), u.range().startLine(), u.snippet(), u.cause());
        }
    }

    private static void printHierarchy(PrintStream out, QueryService svc, Symbol target, Duration deadline)
            throws Exception {
        out.println("  supertypes (out):");
        for (MonikerEdge e : svc.supertypes(target, deadline)) {
            out.printf("    %-11s %s%n", e.type().name().toLowerCase(), svc.signatureOf(e.dst()));
        }
        out.println("  subtypes / overriders (in):");
        for (MonikerEdge e : svc.subtypes(target, deadline)) {
            out.printf("    %-11s %s%n", e.type().name().toLowerCase(), svc.signatureOf(e.src()));
        }
    }

    private static void printDef(PrintStream out, Definition def) {
        out.println("  signature: " + def.signature());
        out.println("  declared:  " + (def.file() == null ? "<external (jar/jdk)>" : def.file() + ":" + def.line()));
        if (!def.snippet().isEmpty()) {
            out.println("  snippet:   " + def.snippet());
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

    /** A query action that may throw (the session's query methods declare {@code IOException}). */
    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T t) throws Exception;
    }
}
