package m0;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared type-solver + parser construction for the M0 spikes (throwaway). Extracted from
 * HarnessSmoke so SpikeA reuses exactly the same wiring: JDK (reflection) + project source +
 * dependency jars, parsing at JDK-25 language level.
 */
public final class SolverSetup {

    /** A configured parser plus the pieces SpikeA needs to introspect. */
    public record Wiring(JavaParser parser, CombinedTypeSolver solver, int jars) {}

    private SolverSetup() {}

    public static Wiring build(Path sourceRoot, Path cpFile) throws IOException {
        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver(false));
        solver.add(new JavaParserTypeSolver(sourceRoot));
        int jars = 0;
        if (Files.exists(cpFile)) {
            String cp = Files.readString(cpFile).trim();
            if (!cp.isEmpty()) {
                for (String entry : cp.split(java.io.File.pathSeparator)) {
                    entry = entry.trim();
                    if (entry.endsWith(".jar")) {
                        try {
                            solver.add(new JarTypeSolver(entry));
                            jars++;
                        } catch (IOException e) {
                            System.err.println("  skip jar (" + e.getMessage() + "): " + entry);
                        }
                    }
                }
            }
        }
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(LanguageLevel.JAVA_25)
                .setSymbolResolver(new JavaSymbolSolver(solver));
        return new Wiring(new JavaParser(config), solver, jars);
    }
}
