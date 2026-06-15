package jcma.cli;

import jcma.engine.JavaParserEngine;
import jcma.engine.ParsedUnit;
import jcma.engine.TypeDependency;
import jcma.workspace.IndexLayout;
import jcma.workspace.Workspace;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code jcma resolve-file <file>} — the QA dependency-extraction surface. Resolves a single source
 * file's whole-file type dependencies through {@link JavaParserEngine#resolveFileDependencies}: for
 * every declared type, its direct supertypes and every resolved type/annotation mention in its body,
 * each attributed to its immediately enclosing type. Prints one line-oriented TSV row per dependency,
 * the exact shape the QA javac oracle emits, so the two are diffable directly.
 *
 * <p>No persisted index is needed (the engine resolves from source + classpath, like {@code jcma
 * resolve}); the classpath is read from the per-repo {@link IndexLayout#classpathCache index cache}
 * when present, so a harness running this over hundreds of files never re-spawns the build tool.
 */
final class ResolveFile {

    private ResolveFile() {}

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 2) {
            err.println("jcma: usage: jcma resolve-file <file>");
            return 2;
        }
        Path file = Path.of(args[1]).toAbsolutePath().normalize();
        try {
            Path repo = Workspace.projectRoot(file);
            Workspace workspace = Workspace.discover(file, IndexLayout.defaultIndexDir(repo));
            JavaParserEngine engine = new JavaParserEngine(workspace);
            ParsedUnit unit = engine.parse(file);

            List<TypeDependency> deps = new ArrayList<>(engine.resolveFileDependencies(unit));
            deps.sort(Comparator.comparing(TypeDependency::relation)
                    .thenComparing(TypeDependency::ownerFqn)
                    .thenComparing(TypeDependency::target));
            for (TypeDependency d : deps) {
                // Confirmed supertype/typeref rows use the relation label; a safe-degraded miss is
                // tagged UNRESOLVED (target = the use-site's simple name) for spot-checking — the
                // comparator ignores UNRESOLVED rows when scoring.
                String relation = d.resolved() ? d.relation() : "UNRESOLVED";
                out.println(relation + "\t" + d.ownerFqn() + "\t" + d.target());
            }
            return 0;
        } catch (Exception e) {
            err.println("jcma: resolve-file failed: " + e.getMessage());
            return 1;
        }
    }
}
