package jcma.engine;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithExtends;
import com.github.javaparser.ast.nodeTypes.NodeWithImplements;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeArguments;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.AssociableToAST;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import jcma.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    @Override
    public List<ResolvedOccurrence> resolveOccurrences(ParsedUnit unit) {
        List<ResolvedOccurrence> out = new ArrayList<>();
        for (Occurrences.Occ o : Occurrences.scan(unit.cu())) {
            out.add(attempt(o));
        }
        return out;
    }

    /** Resolve one enumerated use-site; never throws (guards {@code Throwable}, incl. StackOverflow). */
    private ResolvedOccurrence attempt(Occurrences.Occ o) {
        try {
            Object resolved = switch (o.kind()) {
                case CALL          -> ((MethodCallExpr) o.node()).resolve();
                case INSTANTIATION -> ((ObjectCreationExpr) o.node()).resolve();
                case NAME          -> ((NameExpr) o.node()).resolve();
                case FIELD_ACCESS  -> ((FieldAccessExpr) o.node()).resolve();
                case METHOD_REF    -> ((MethodReferenceExpr) o.node()).resolve();
                case TYPE_REF      -> ((ClassOrInterfaceType) o.node()).resolve();
                case ANNOTATION    -> ((AnnotationExpr) o.node()).resolve();
            };
            ResolvedTarget target = describe(resolved);
            return new ResolvedOccurrence(o.kind(), o.targetName(),
                    o.startLine(), o.startCol(), o.endLine(), o.endCol(), target, null);
        } catch (Throwable t) {
            if (DEBUG) {
                t.printStackTrace();
            }
            return new ResolvedOccurrence(o.kind(), o.targetName(),
                    o.startLine(), o.startCol(), o.endLine(), o.endCol(), null, failureOf(o.node(), t));
        }
    }

    // ---------------------------------------------------------------- hierarchy resolution

    @Override
    public List<ResolvedHierarchy> resolveHierarchy(ParsedUnit unit) {
        List<ResolvedHierarchy> out = new ArrayList<>();
        for (TypeDeclaration<?> td : unit.cu().findAll(TypeDeclaration.class)) {
            int[] at = namePos(td.getName());
            if (td instanceof NodeWithExtends<?> ext) {
                for (ClassOrInterfaceType sup : ext.getExtendedTypes()) {
                    addSupertype(out, HierarchyKind.EXTENDS, at, sup);
                }
            }
            if (td instanceof NodeWithImplements<?> impl) {
                for (ClassOrInterfaceType iface : impl.getImplementedTypes()) {
                    addSupertype(out, HierarchyKind.IMPLEMENTS, at, iface);
                }
            }
        }
        for (MethodDeclaration md : unit.cu().findAll(MethodDeclaration.class)) {
            addOverrides(out, md);
        }
        return out;
    }

    /** Resolve one {@code extends}/{@code implements} type and append its edge; guarded — skip on miss. */
    private void addSupertype(List<ResolvedHierarchy> out, HierarchyKind kind, int[] at, ClassOrInterfaceType type) {
        try {
            var rt = type.resolve();
            if (!rt.isReferenceType()) {
                return;
            }
            ResolvedReferenceType ref = rt.asReferenceType();
            ResolvedTarget target = ref.getTypeDeclaration()
                    .map(this::describe)                       // project decl → locatable; jar/JDK → external
                    .orElseGet(() -> {                         // resolvable name but no declaration handle
                        String fqn = safe(ref::getQualifiedName);
                        return new ResolvedTarget(fqn, fqn, null, -1, DeclKind.OTHER);
                    });
            out.add(new ResolvedHierarchy(kind, at[0], at[1], target));
        } catch (Throwable t) {
            if (DEBUG) t.printStackTrace();
        }
    }

    /**
     * Append an {@code OVERRIDES} relation for {@code md} to each <b>direct</b> ancestor method it
     * overrides/implements (same name + erased parameter list). Guarded — a method that does not
     * override, or whose ancestors don't resolve, contributes nothing.
     */
    private void addOverrides(List<ResolvedHierarchy> out, MethodDeclaration md) {
        try {
            ResolvedMethodDeclaration rm = md.resolve();
            int[] at = namePos(md.getName());
            String name = rm.getName();
            int n = rm.getNumberOfParams();
            for (ResolvedReferenceType ancestor : rm.declaringType().getAncestors(true)) {
                ResolvedReferenceTypeDeclaration decl = ancestor.getTypeDeclaration().orElse(null);
                if (decl == null) {
                    continue;
                }
                for (ResolvedMethodDeclaration cand : decl.getDeclaredMethods()) {
                    if (cand.getName().equals(name) && cand.getNumberOfParams() == n && sameParams(rm, cand)) {
                        out.add(new ResolvedHierarchy(HierarchyKind.OVERRIDES, at[0], at[1], describe(cand)));
                    }
                }
            }
        } catch (Throwable t) {
            if (DEBUG) t.printStackTrace();
        }
    }

    /** True if two same-arity methods have erasure-equal parameter types (the override-match test). */
    private static boolean sameParams(ResolvedMethodLikeDeclaration a, ResolvedMethodLikeDeclaration b) {
        for (int i = 0; i < a.getNumberOfParams(); i++) {
            int idx = i;
            if (!safe(() -> a.getParam(idx).getType().describe())
                    .equals(safe(() -> b.getParam(idx).getType().describe()))) {
                return false;
            }
        }
        return true;
    }

    /** 1-based {@code [line, col]} of a declaration's name node (the {@code monikerAt} bridge key). */
    private static int[] namePos(com.github.javaparser.ast.expr.SimpleName name) {
        return name.getBegin().map(p -> new int[] {p.line, p.column}).orElse(new int[] {-1, -1});
    }

    /** A resolved declaration → engine-neutral {@link ResolvedTarget} (fqn/signature/site/kind). */
    private ResolvedTarget describe(Object r) {
        Loc loc = locate(r);
        if (r instanceof ResolvedMethodLikeDeclaration m) {
            DeclKind kind = m instanceof ResolvedConstructorDeclaration ? DeclKind.CONSTRUCTOR : DeclKind.METHOD;
            return new ResolvedTarget(safe(m::getQualifiedName), safe(m::getQualifiedSignature),
                    loc.file(), loc.line(), kind);
        }
        if (r instanceof ResolvedTypeDeclaration t) {
            String fqn = safe(t::getQualifiedName);
            return new ResolvedTarget(fqn, fqn, loc.file(), loc.line(), typeKind(t));
        }
        if (r instanceof ResolvedValueDeclaration v) {
            String type = safe(() -> v.getType().describe());
            return new ResolvedTarget(v.getName(), type + " " + v.getName(), loc.file(), loc.line(), DeclKind.FIELD);
        }
        if (r instanceof com.github.javaparser.resolution.types.ResolvedType rt) {
            String desc = safe(rt::describe);
            DeclKind kind = rt.isReferenceType()
                    ? rt.asReferenceType().getTypeDeclaration().map(JavaParserEngine::typeKind).orElse(DeclKind.OTHER)
                    : DeclKind.OTHER;
            return new ResolvedTarget(desc, desc, loc.file(), loc.line(), kind);
        }
        String s = String.valueOf(r);
        return new ResolvedTarget(s, s, loc.file(), loc.line(), DeclKind.OTHER);
    }

    private static DeclKind typeKind(ResolvedTypeDeclaration t) {
        try {
            if (t.isInterface()) {
                return DeclKind.INTERFACE;
            }
            if (t.isEnum()) {
                return DeclKind.ENUM;
            }
            if (t.isAnnotation()) {
                return DeclKind.ANNOTATION;
            }
            return DeclKind.CLASS;
        } catch (Throwable ignore) {
            return DeclKind.OTHER;
        }
    }

    /** Neutral facts for a safe-degrading miss (the node-inspecting half of the M0 FailureClassifier). */
    private static ResolveFailure failureOf(Node node, Throwable t) {
        String msg = t.getMessage() == null ? "" : t.getMessage().replaceAll("\\s+", " ").trim();
        return new ResolveFailure(t.getClass().getSimpleName(), msg, t instanceof StackOverflowError,
                involvesLambdaOrMethodRef(node), inPatternRecordSealed(node),
                involvesVar(node), hasTypeArguments(node));
    }

    private static boolean involvesLambdaOrMethodRef(Node node) {
        if (node instanceof MethodReferenceExpr) {
            return true;
        }
        if (node.findAncestor(LambdaExpr.class).isPresent()
                || node.findAncestor(MethodReferenceExpr.class).isPresent()) {
            return true;
        }
        if (node instanceof MethodCallExpr mce) {
            return mce.getArguments().stream()
                    .anyMatch(a -> a instanceof LambdaExpr || a instanceof MethodReferenceExpr);
        }
        return false;
    }

    private static boolean inPatternRecordSealed(Node node) {
        if (node.findAncestor(com.github.javaparser.ast.expr.PatternExpr.class).isPresent()
                || node.findAncestor(RecordDeclaration.class).isPresent()) {
            return true;
        }
        return node.findAncestor(InstanceOfExpr.class).map(io -> io.getPattern().isPresent()).orElse(false);
    }

    private static boolean involvesVar(Node node) {
        return node.findAncestor(VariableDeclarator.class).map(vd -> vd.getType().isVarType()).orElse(false);
    }

    private static boolean hasTypeArguments(Node node) {
        return node instanceof NodeWithTypeArguments<?> nwta
                && nwta.getTypeArguments().isPresent() && !nwta.getTypeArguments().get().isEmpty();
    }

    private static String safe(java.util.function.Supplier<String> s) {
        try {
            return s.get();
        } catch (Throwable t) {
            return "?";
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
