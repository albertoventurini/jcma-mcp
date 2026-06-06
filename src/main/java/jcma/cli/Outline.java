package jcma.cli;

import jcma.index.FileIndex;
import jcma.index.Indexer;
import jcma.index.Symbol;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code jcma outline <file>} (task-06 P4) — re-parse a single file (Tier-1, no index lookup) and
 * print its declaration containment tree, indented by nesting. The same {@code Main.run} dispatch
 * runs under native-image.
 */
final class Outline {

    private Outline() {}

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 2) {
            err.println("jcma: usage: jcma outline <file>");
            return 2;
        }
        Path file = Path.of(args[1]);
        if (!Files.isRegularFile(file)) {
            err.println("jcma: not a file: " + file);
            return 1;
        }
        try {
            FileIndex fi = new Indexer().indexFile(0, file);

            // Group by enclosing moniker (preserving extraction order), then print the tree.
            Map<String, List<Symbol>> childrenOf = new LinkedHashMap<>();
            List<Symbol> roots = new ArrayList<>();
            for (Symbol s : fi.symbols()) {
                if (s.enclosingMoniker() == null) {
                    roots.add(s);
                } else {
                    childrenOf.computeIfAbsent(s.enclosingMoniker(), k -> new ArrayList<>()).add(s);
                }
            }
            out.println(file.toString());
            for (Symbol root : roots) {
                printNode(out, root, childrenOf, 0);
            }
            return 0;
        } catch (Exception e) {
            err.println("jcma: outline failed: " + e.getMessage());
            return 1;
        }
    }

    private static void printNode(PrintStream out, Symbol s, Map<String, List<Symbol>> childrenOf, int depth) {
        String indent = "  ".repeat(depth + 1);
        String sig = s.signature() == null ? "" : "  " + s.signature();
        out.println(indent + s.kind() + " " + s.name() + sig);
        for (Symbol child : childrenOf.getOrDefault(s.moniker(), List.of())) {
            printNode(out, child, childrenOf, depth + 1);
        }
    }
}
