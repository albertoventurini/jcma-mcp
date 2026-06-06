package jcma.engine;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.AssociableToAST;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import jcma.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Default {@link AnalysisEngine}: JavaParser + JavaSymbolSolver, ported from the M0 spike wiring
 * ({@code SolverSetup.build()} type-solver stack, {@code HarnessSmoke} parse loop,
 * {@code SpikeA.attempt()}/{@code locate()} guarded-resolve + declaration-location). Constructed
 * from a {@link Workspace} (source roots + classpath jars).
 *
 * <p>Every resolution is guarded down to {@link Throwable} (incl. {@code StackOverflowError}, which
 * the deep SymbolSolver recursion can raise) and degrades to {@link Optional#empty()} — never a
 * silent-wrong answer (PRD §4 / M0 safe-degrade principle).
 */
public final class JavaParserEngine implements AnalysisEngine {

    /** Set {@code JCMA_DEBUG} to print the underlying cause of a guarded (swallowed) resolve. */
    private static final boolean DEBUG = System.getenv("JCMA_DEBUG") != null;

    private final JavaParser parser;

    public JavaParserEngine(Workspace workspace) {
        // CombinedTypeSolver = JDK + each project source root + every dependency jar.
        CombinedTypeSolver solver = new CombinedTypeSolver();
        // JDK (kept first, mirroring the M0 spike order). Native-image serves reflection only for
        // build-time-registered classes, so under native-image we resolve the JDK from a host-derived
        // byte-parsed jar (Task-02b); on the JVM/dev path reflection is a known-good fallback (host
        // classes are loaded). Selector avoids any GraalVM class dependency.
        if ("runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
            HostJdkIndex.resolveCacheJar().ifPresent(jar -> {
                try {
                    solver.add(new JarTypeSolver(jar));
                } catch (IOException e) {
                    if (DEBUG) {
                        System.err.println("  skip JDK index: " + e);
                    }
                }
            });
        } else {
            solver.add(new ReflectionTypeSolver(false));
        }
        for (Path sourceRoot : workspace.sourceRoots()) {
            if (Files.isDirectory(sourceRoot)) {
                solver.add(new JavaParserTypeSolver(sourceRoot));
            }
        }
        for (Path jar : workspace.classpathJars()) {
            try {
                solver.add(new JarTypeSolver(jar));
            } catch (IOException e) {
                System.err.println("  skip jar (" + e.getMessage() + "): " + jar);
            }
        }
        // RAW skips the post-parse language-level validators (see StructuralParser): they read node
        // properties through JavaParser's reflective meta-model, which is a native-image NoSuchFieldError
        // hazard and gives us nothing (we emit no diagnostics). Symbol resolution is driven by the
        // SymbolResolver below, independent of the language level, so it is unaffected.
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(LanguageLevel.RAW)
                .setSymbolResolver(new JavaSymbolSolver(solver));
        this.parser = new JavaParser(config);
    }

    @Override
    public ParsedUnit parse(Path file) throws IOException {
        ParseResult<CompilationUnit> r = parser.parse(file);
        CompilationUnit cu = r.getResult().orElseThrow(
                () -> new IOException("parse failed: " + file + " — " + r.getProblems()));
        return new ParsedUnit(cu, file);
    }

    @Override
    public Optional<ResolvedRef> resolveMethodCall(ParsedUnit unit, Position pos) {
        // Method calls and constructor (object-creation) calls are both "method-like" refs.
        Node node = smallestEnclosing(unit, pos, MethodCallExpr.class, ObjectCreationExpr.class);
        if (node == null) {
            return Optional.empty();
        }
        try {
            ResolvedMethodLikeDeclaration d = node instanceof MethodCallExpr m
                    ? m.resolve()
                    : ((ObjectCreationExpr) node).resolve();
            Loc loc = locate(d);
            return Optional.of(new ResolvedRef(d.getQualifiedName(), d.getQualifiedSignature(),
                    loc.file(), loc.line()));
        } catch (Throwable t) {
            if (DEBUG) t.printStackTrace();
            return Optional.empty();
        }
    }

    @Override
    public Optional<ResolvedType> resolveType(ParsedUnit unit, Position pos) {
        ClassOrInterfaceType type = (ClassOrInterfaceType)
                smallestEnclosing(unit, pos, ClassOrInterfaceType.class);
        if (type == null) {
            return Optional.empty();
        }
        try {
            com.github.javaparser.resolution.types.ResolvedType rt = type.resolve();
            if (!rt.isReferenceType()) {
                return Optional.empty();
            }
            var ref = rt.asReferenceType();
            Loc loc = ref.getTypeDeclaration().map(this::locate).orElse(Loc.EXTERNAL);
            return Optional.of(new ResolvedType(ref.getQualifiedName(), loc.file(), loc.line()));
        } catch (Throwable t) {
            if (DEBUG) t.printStackTrace();
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- node lookup + locate

    /** The smallest-range node of the given AST type(s) whose range contains {@code pos}. */
    @SafeVarargs
    private static Node smallestEnclosing(ParsedUnit unit, Position pos, Class<? extends Node>... types) {
        com.github.javaparser.Position jp = new com.github.javaparser.Position(pos.line(), pos.col());
        Node best = null;
        long bestSpan = Long.MAX_VALUE;
        for (Class<? extends Node> type : types) {
            for (Node n : unit.cu().findAll(type)) {
                var range = n.getRange().orElse(null);
                if (range == null || !range.contains(jp)) {
                    continue;
                }
                long span = (long) (range.end.line - range.begin.line) * 1_000_000
                        + (range.end.column - range.begin.column);
                if (span < bestSpan) {
                    bestSpan = span;
                    best = n;
                }
            }
        }
        return best;
    }

    /** Declaration site of a resolved symbol; {@link Loc#EXTERNAL} when it lives in a jar/the JDK. */
    private record Loc(Path file, int line) {
        static final Loc EXTERNAL = new Loc(null, -1);
    }

    private Loc locate(Object resolved) {
        try {
            Node n = resolved instanceof AssociableToAST a ? a.toAst().orElse(null) : null;
            if (n == null) {
                return Loc.EXTERNAL;
            }
            Path file = n.findCompilationUnit()
                    .flatMap(CompilationUnit::getStorage)
                    .map(CompilationUnit.Storage::getPath)
                    .orElse(null);
            int line = n.getRange().map(r -> r.begin.line).orElse(-1);
            return file == null ? Loc.EXTERNAL : new Loc(file, line);
        } catch (Throwable t) {
            return Loc.EXTERNAL;
        }
    }
}
