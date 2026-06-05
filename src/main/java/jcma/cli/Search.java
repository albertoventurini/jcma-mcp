package jcma.cli;

import jcma.index.Symbol;
import jcma.index.SymbolStore;
import jcma.index.TrigramIndex;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jcma search <indexDir> <query>} — name search over a persisted index (task 05). A
 * <em>pure reader</em>: it loads {@code symbols.seg} + {@code trigrams.seg} and prints the ranked
 * symbols whose name contains {@code query} (case-sensitive). Building the trigram segment is the
 * indexing pipeline's job (task-07), and invalidating it is the freshness pipeline's — {@code
 * search} never writes, so there is no stale-cache question here. The same {@code Main.run} dispatch
 * runs under native-image.
 */
final class Search {

    private Search() {}

    /** Dispatch {@code search} args; return the process exit code (0 ok, 1 failure, 2 usage). */
    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 3) {
            err.println("jcma: usage: jcma search <indexDir> <query>");
            return 2;
        }
        Path indexDir = Path.of(args[1]);
        String query = args[2];
        Path symSeg = indexDir.resolve(SymbolStore.FILE_NAME);
        Path triSeg = indexDir.resolve(TrigramIndex.FILE_NAME);
        if (!Files.exists(symSeg)) {
            err.println("jcma: no symbol store at " + symSeg);
            return 1;
        }
        if (!Files.exists(triSeg)) {
            err.println("jcma: no trigram index at " + triSeg + " — build the index first");
            return 1;
        }
        try (SymbolStore store = SymbolStore.load(symSeg); TrigramIndex idx = TrigramIndex.load(triSeg)) {
            List<Integer> ids = idx.searchSymbols(query);
            out.println("search \"" + query + "\": " + ids.size() + " match(es)  (" + triSeg + ")");
            for (int id : ids) {
                Symbol s = store.symbol(id);
                out.printf("#%-5d %-12s %-48s %-20s %s%s%n",
                        id, s.kind(), s.moniker(), s.name(), site(s),
                        s.signature() == null ? "" : "  " + s.signature());
            }
            return 0;
        } catch (Exception e) {
            err.println("jcma: search failed: " + e.getMessage());
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
