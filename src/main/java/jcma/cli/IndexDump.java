package jcma.cli;

import jcma.index.Symbol;
import jcma.index.SymbolStore;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code jcma index-dump --symbols <indexDir>} — the task-03 verification surface: load the §5.1
 * {@link SymbolStore} from {@code <indexDir>/symbols.seg} and list each symbol's id, moniker, kind,
 * name, and declaration site. A dev-only eyeball of the columnar store (PRD §9).
 */
final class IndexDump {

    private IndexDump() {}

    /** Dispatch {@code index-dump} args; return the process exit code (0 ok, 1 failure, 2 usage). */
    static int run(String[] args, PrintStream out, PrintStream err) {
        // args = ["index-dump", "--symbols", "<indexDir>"]
        if (args.length != 3 || !"--symbols".equals(args[1])) {
            err.println("jcma: usage: jcma index-dump --symbols <indexDir>");
            return 2;
        }
        Path indexDir = Path.of(args[2]);
        Path seg = indexDir.resolve(SymbolStore.FILE_NAME);
        if (!Files.exists(seg)) {
            err.println("jcma: no symbol store at " + seg);
            return 1;
        }
        try (SymbolStore store = SymbolStore.load(seg)) {
            out.println("symbols: " + store.size() + "  (" + seg + ")");
            for (int id = 0; id < store.size(); id++) {
                Symbol s = store.symbol(id);
                out.printf("#%-5d %-12s %-48s %-20s %s%s%n",
                        id, s.kind(), s.moniker(), s.name(), site(s),
                        s.signature() == null ? "" : "  " + s.signature());
            }
            return 0;
        } catch (Exception e) {
            err.println("jcma: index-dump failed: " + e.getMessage());
            return 1;
        }
    }

    /** Declaration site of {@code s}: {@code file<id>:line:col}, or {@code <external>} for a phantom. */
    private static String site(Symbol s) {
        if (s.isPhantom() || s.range().isNone()) {
            return "<external>";
        }
        return "file" + s.fileId() + ":" + s.range().startLine() + ":" + s.range().startCol();
    }
}
