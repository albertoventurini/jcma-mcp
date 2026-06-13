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
import com.github.javaparser.ast.expr.Expression;
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
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.AssociableToAST;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory;
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

    // The immutable input solvers (JDK + dependency jars), each wrapped so it tolerates being re-added
    // to a fresh CombinedTypeSolver on every refresh() — the wrapper holds the (once-built) jar/JDK
    // index, so refresh re-indexes nothing. Source roots are NOT wrapped: a fresh JavaParserTypeSolver
    // is built per refresh so its parsed-AST cache starts empty (task-11c freshness).
    private final TypeSolver jdkSolver; // null if the native-image JDK index is unavailable
    private final List<TypeSolver> jarSolvers;
    private final List<Path> sourceRoots;
    private volatile JavaParser parser;
    // The CombinedTypeSolver backing the current parser, retained so attempt() can solve a name as a
    // *type* in its AST context (the static-qualifier fallback). Rebuilt in lockstep with parser on
    // refresh() — always the same solver the parser's SymbolResolver uses.
    private volatile TypeSolver typeSolver;

    // The source-root JavaParserTypeSolvers' own parser config. Built once and reused across every
    // refresh() (unlike the engine parser's config, which rebinds its SymbolResolver to each rebuild's
    // CombinedTypeSolver): this one carries NO resolver — the source solver only extracts type
    // declarations by name — so it is refresh-invariant. RAW skips the post-parse language-level
    // validators, same as buildParser()'s parser: those validators read node properties through
    // JavaParser's reflective meta-model, a native-image NoSuchFieldError hazard (the JavaParserTypeSolver
    // default is BLEEDING_EDGE, which runs them, so without this the source re-parse fails under native).
    private final ParserConfiguration sourceSolverConfig =
            new ParserConfiguration().setLanguageLevel(LanguageLevel.RAW);

    public JavaParserEngine(Workspace workspace) {
        // JDK (kept first, mirroring the M0 spike order). Native-image serves reflection only for
        // build-time-registered classes, so under native-image we resolve the JDK from a host-derived
        // byte-parsed jar (Task-02b); on the JVM/dev path reflection is a known-good fallback (host
        // classes are loaded). Selector avoids any GraalVM class dependency.
        TypeSolver jdk = null;
        if ("runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
            var jarOpt = HostJdkIndex.resolveCacheJar();
            if (jarOpt.isPresent()) {
                try {
                    jdk = new JarTypeSolver(jarOpt.get());
                } catch (IOException e) {
                    if (DEBUG) {
                        System.err.println("  skip JDK index: " + e);
                    }
                }
            }
        } else {
            jdk = new ReflectionTypeSolver(false);
        }
        this.jdkSolver = jdk == null ? null : new StableSolver(jdk);
        List<TypeSolver> jars = new ArrayList<>();
        for (Path jar : workspace.classpathJars()) {
            try {
                jars.add(new StableSolver(new JarTypeSolver(jar)));
            } catch (IOException e) {
                System.err.println("  skip jar (" + e.getMessage() + "): " + jar);
            }
        }
        this.jarSolvers = List.copyOf(jars);
        List<Path> roots = new ArrayList<>();
        for (Path sourceRoot : workspace.sourceRoots()) {
            if (Files.isDirectory(sourceRoot)) {
                roots.add(sourceRoot);
            }
        }
        this.sourceRoots = List.copyOf(roots);
        this.parser = buildParser();
    }

    /**
     * Build a parser over a <b>fresh</b> {@code CombinedTypeSolver} = JDK + source roots + jars (the M0
     * spike order). A fresh combined solver is what sheds the stale view on {@link #refresh()}: it has
     * its own type cache (and a new {@code JavaParserFacade} keyed by it), and its source
     * {@link JavaParserTypeSolver}s start with empty AST caches. The wrapped jar/JDK solvers are reused
     * as-is (their indexes are not rebuilt) and tolerate the re-parenting a fresh combined solver does.
     */
    private JavaParser buildParser() {
        CombinedTypeSolver solver = new CombinedTypeSolver();
        if (jdkSolver != null) {
            solver.add(jdkSolver);
        }
        for (Path sourceRoot : sourceRoots) {
            // RAW config (sourceSolverConfig) so this solver's own re-parses skip the validators'
            // reflective meta-model access — native-image-safe; see the field's note. Fresh solver
            // per rebuild → empty parsed-AST cache (task-11c freshness).
            solver.add(new JavaParserTypeSolver(sourceRoot, sourceSolverConfig));
        }
        for (TypeSolver jar : jarSolvers) {
            solver.add(jar);
        }
        // RAW skips the post-parse language-level validators (see StructuralParser): they read node
        // properties through JavaParser's reflective meta-model, which is a native-image NoSuchFieldError
        // hazard and gives us nothing (we emit no diagnostics). Symbol resolution is driven by the
        // SymbolResolver below, independent of the language level, so it is unaffected.
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(LanguageLevel.RAW)
                .setSymbolResolver(new JavaSymbolSolver(solver));
        this.typeSolver = solver; // retained for attempt()'s name-as-type fallback (same solver as above)
        return new JavaParser(config);
    }

    /**
     * Shed the engine's stale cross-file view so the next resolution reflects the current bytes on disk
     * (task-11c). A fresh {@code CombinedTypeSolver}/parser drops <em>all</em> the stale caches at once —
     * the source parsed-AST caches, the combined solver's type cache, and the per-solver
     * {@link JavaParserFacade} resolution cache — while the wrapped jar/JDK indexes are reused (no
     * re-indexing). {@link JavaParserFacade#clearInstances()} also evicts the now-dead facade for the
     * old solver so its static map does not grow across edits.
     */
    @Override
    public void refresh() {
        JavaParserFacade.clearInstances();
        this.parser = buildParser();
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
                    loc.file(), loc.line(), loc.col()));
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
            return Optional.of(new ResolvedType(ref.getQualifiedName(), loc.file(), loc.line(), loc.col()));
        } catch (Throwable t) {
            if (DEBUG) t.printStackTrace();
            return Optional.empty();
        }
    }

    @Override
    public List<ResolvedOccurrence> resolveOccurrences(ParsedUnit unit, String simpleName) {
        List<ResolvedOccurrence> out = new ArrayList<>();
        for (Occurrences.Occ o : Occurrences.scan(unit.cu())) {
            // Name-scope before .resolve(): only the queried name's value use-sites pay the cubic cost.
            if (isValueKind(o.kind()) && o.targetName().equals(simpleName)) {
                out.add(attempt(o));
            }
        }
        return out;
    }

    @Override
    public List<ResolvedOccurrence> resolveTypeReferences(ParsedUnit unit, String simpleName) {
        List<ResolvedOccurrence> out = new ArrayList<>();
        for (Occurrences.Occ o : Occurrences.scan(unit.cu())) {
            // Name-scope before .resolve(): only the queried type's use-sites are surfaced (B1).
            if ((o.kind() == OccurrenceKind.TYPE_REF || o.kind() == OccurrenceKind.ANNOTATION)
                    && o.targetName().equals(simpleName)) {
                out.add(attempt(o));
            }
        }
        return out;
    }

    /** The cubic-cost value-name kinds (vs the cheap type-solver kinds {@code TYPE_REF}/{@code ANNOTATION}). */
    private static boolean isValueKind(OccurrenceKind kind) {
        return switch (kind) {
            case CALL, NAME, FIELD_ACCESS, METHOD_REF, INSTANTIATION -> true;
            case TYPE_REF, ANNOTATION -> false;
        };
    }

    /** Resolve one enumerated use-site; never throws (guards {@code Throwable}, incl. StackOverflow). */
    private ResolvedOccurrence attempt(Occurrences.Occ o) {
        try {
            // NAME / FIELD_ACCESS are the syntactically *ambiguous* use-sites (JLS §6.5): a bare or
            // qualified name resolved as an ambiguous name — a value, else a type — by decision. Every
            // other kind denotes one committed thing.
            ResolvedTarget target = switch (o.kind()) {
                case NAME         -> resolveAmbiguousName((NameExpr) o.node());
                case FIELD_ACCESS -> resolveAmbiguousName((FieldAccessExpr) o.node());
                default           -> describe(resolveCommitted(o));
            };
            return target != null
                    ? resolved(o, target)
                    : unresolved(o, unsolvedName(o.node(), o.targetName()));
        } catch (Throwable t) {
            // Safe-degrade net (PRD §4): any resolution can fail *unexpectedly* (UnsupportedOperation,
            // StackOverflow in deep inference, …) — never a wrong answer; carry the miss in the tail.
            if (DEBUG) {
                t.printStackTrace();
            }
            return unresolved(o, failureOf(o.node(), t));
        }
    }

    /** The committed-role kinds: each denotes exactly one thing, so resolution either binds or misses. */
    private Object resolveCommitted(Occurrences.Occ o) {
        return switch (o.kind()) {
            case CALL          -> ((MethodCallExpr) o.node()).resolve();
            case INSTANTIATION -> ((ObjectCreationExpr) o.node()).resolve();
            case METHOD_REF    -> ((MethodReferenceExpr) o.node()).resolve();
            case TYPE_REF      -> ((ClassOrInterfaceType) o.node()).resolve();
            case ANNOTATION    -> ((AnnotationExpr) o.node()).resolve();
            case NAME, FIELD_ACCESS ->
                    throw new IllegalStateException(o.kind() + " resolves as an ambiguous name");
        };
    }

    /**
     * Resolve an <b>ambiguous name</b> (JLS §6.5): a name denotes a <em>value</em> if one is in scope,
     * else a <em>type</em>, else a package (which we do not track). The two ambiguous syntactic shapes:
     * <ul>
     *   <li>a bare {@link NameExpr} — e.g. {@code Foo} in {@code Foo.bar()} / {@code Foo.FIELD};</li>
     *   <li>a <em>qualified</em> {@link FieldAccessExpr} (JLS §6.5.2) — e.g. {@code Outer.Inner} in
     *       {@code Outer.Inner.staticCall()}, a nested-type reference.</li>
     * </ul>
     * The choice is a decision over the non-throwing {@code isSolved()} API — a type qualifier is a
     * first-class outcome, not a value-resolution failure recovered from a {@code catch}.
     */
    private ResolvedTarget resolveAmbiguousName(NameExpr name) {
        // A bare name is always a type candidate (its simple name).
        return ambiguous(name, JavaParserFacade.get(typeSolver).solve(name), name.getNameAsString());
    }

    private ResolvedTarget resolveAmbiguousName(FieldAccessExpr name) {
        // A field access is a type candidate only when it is a pure name path (Outer.Inner); a real
        // field access (obj.field, foo().bar, this.x) gets typeName == null → value-only resolution.
        return ambiguous(name, JavaParserFacade.get(typeSolver).solve(name),
                isQualifiedName(name) ? qualifiedName(name) : null);
    }

    /**
     * The shared ambiguous-name decision: value first, then type. {@code typeName} is the dotted name to
     * try as a type ({@code null} when the node cannot denote a type — a real field access).
     * {@code solveType} handles dotted names like {@code "Outer.Inner"} via recursive contexts. Returns
     * {@code null} when the name denotes neither a value nor a type in scope (a genuine miss).
     */
    private ResolvedTarget ambiguous(Expression node,
            SymbolReference<? extends ResolvedValueDeclaration> asValue, String typeName) {
        if (asValue.isSolved()) {
            return describe(asValue.getCorrespondingDeclaration());
        }
        if (typeName == null) {
            return null;
        }
        SymbolReference<ResolvedTypeDeclaration> asType =
                JavaParserFactory.getContext(node, typeSolver).solveType(typeName, List.of());
        return asType.isSolved() ? describe(asType.getCorrespondingDeclaration()) : null;
    }

    /** A pure name path (a {@link NameExpr}, or a field-access chain of names) — the only shape that can denote a type. */
    private static boolean isQualifiedName(Expression e) {
        if (e.isNameExpr()) {
            return true;
        }
        return e.isFieldAccessExpr() && isQualifiedName(e.asFieldAccessExpr().getScope());
    }

    /** The dotted name of a pure name-path field access, e.g. {@code Outer.Inner}. */
    private static String qualifiedName(FieldAccessExpr fae) {
        Expression s = fae.getScope();
        String prefix = s.isFieldAccessExpr()
                ? qualifiedName(s.asFieldAccessExpr())
                : s.asNameExpr().getNameAsString();
        return prefix + "." + fae.getNameAsString();
    }

    private ResolvedOccurrence resolved(Occurrences.Occ o, ResolvedTarget target) {
        return new ResolvedOccurrence(o.kind(), o.targetName(),
                o.startLine(), o.startCol(), o.endLine(), o.endCol(), target, null);
    }

    private ResolvedOccurrence unresolved(Occurrences.Occ o, ResolveFailure failure) {
        return new ResolvedOccurrence(o.kind(), o.targetName(),
                o.startLine(), o.startCol(), o.endLine(), o.endCol(), null, failure);
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
                        return new ResolvedTarget(fqn, fqn, null, -1, -1, DeclKind.OTHER);
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

    /** 1-based {@code [line, col]} of a declaration's name node (the moniker-bridge attribution key). */
    private static int[] namePos(com.github.javaparser.ast.expr.SimpleName name) {
        return name.getBegin().map(p -> new int[] {p.line, p.column}).orElse(new int[] {-1, -1});
    }

    /** A resolved declaration → engine-neutral {@link ResolvedTarget} (fqn/signature/site/kind). */
    private ResolvedTarget describe(Object r) {
        Loc loc = locate(r);
        if (r instanceof ResolvedMethodLikeDeclaration m) {
            DeclKind kind = m instanceof ResolvedConstructorDeclaration ? DeclKind.CONSTRUCTOR : DeclKind.METHOD;
            return new ResolvedTarget(safe(m::getQualifiedName), safe(m::getQualifiedSignature),
                    loc.file(), loc.line(), loc.col(), kind);
        }
        if (r instanceof ResolvedTypeDeclaration t) {
            String fqn = safe(t::getQualifiedName);
            return new ResolvedTarget(fqn, fqn, loc.file(), loc.line(), loc.col(), typeKind(t));
        }
        if (r instanceof ResolvedValueDeclaration v) {
            String type = safe(() -> v.getType().describe());
            return new ResolvedTarget(v.getName(), type + " " + v.getName(),
                    loc.file(), loc.line(), loc.col(), DeclKind.FIELD);
        }
        if (r instanceof com.github.javaparser.resolution.types.ResolvedType rt) {
            // A reference type carries a type *declaration*; unwrap to it so a reference to a PROJECT
            // type locates its source (→ real moniker app/Foo#), not the external-phantom fallback
            // (task-11c). A jar/JDK type's declaration has no AST → still resolves to a ~fqn phantom.
            if (rt.isReferenceType()) {
                Optional<ResolvedReferenceTypeDeclaration> decl = rt.asReferenceType().getTypeDeclaration();
                if (decl.isPresent()) {
                    return describe(decl.get());
                }
            }
            String desc = safe(rt::describe);
            return new ResolvedTarget(desc, desc, loc.file(), loc.line(), loc.col(), DeclKind.OTHER);
        }
        String s = String.valueOf(r);
        return new ResolvedTarget(s, s, loc.file(), loc.line(), loc.col(), DeclKind.OTHER);
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
        return failure(node, t.getClass().getSimpleName(), msg, t instanceof StackOverflowError);
    }

    /**
     * Neutral facts for an ambiguous name that resolved to <em>neither</em> a value nor a type — the
     * same facts the equivalent {@code UnsolvedSymbolException} carries, so the classifier buckets it
     * identically (typically {@code MISSING_CLASSPATH}). No exception is thrown: the non-throwing
     * {@code solve()}/{@code solveType()} reported the miss cleanly; this only records its cause.
     */
    private static ResolveFailure unsolvedName(Node node, String name) {
        return failure(node, "UnsolvedSymbolException", "Unsolved symbol : " + name, false);
    }

    private static ResolveFailure failure(Node node, String throwableType, String message, boolean stackOverflow) {
        return new ResolveFailure(throwableType, message, stackOverflow,
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
    private record Loc(Path file, int line, int col) {
        static final Loc EXTERNAL = new Loc(null, -1, -1);
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
            int col = n.getRange().map(r -> r.begin.column).orElse(-1);
            return file == null ? Loc.EXTERNAL : new Loc(file, line, col);
        } catch (Throwable t) {
            return Loc.EXTERNAL;
        }
    }

    // ---------------------------------------------------------------- reusable stable solver

    /**
     * Wraps an immutable input solver (a {@link JarTypeSolver} or {@link ReflectionTypeSolver}) so it
     * can be re-added to a fresh {@code CombinedTypeSolver} on every {@link #refresh()} without its
     * index being rebuilt and without the one-parent rule tripping (task-11c). The inner solver's
     * parent is fixed to this wrapper once; the wrapper's own parent is re-settable, so each rebuild
     * re-points the delegation chain at the current combined solver while the wrapped index is reused.
     */
    private static final class StableSolver implements TypeSolver {

        private final TypeSolver inner;
        private TypeSolver parent;

        StableSolver(TypeSolver inner) {
            this.inner = inner;
            inner.setParent(this); // inner delegates upward through this wrapper (set exactly once)
        }

        @Override
        public TypeSolver getParent() {
            return parent;
        }

        @Override
        public void setParent(TypeSolver parent) {
            this.parent = parent; // lenient: a fresh combined solver re-parents us on each rebuild
        }

        @Override
        public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
            return inner.tryToSolveType(name);
        }

        @Override
        public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveTypeInModule(String module, String name) {
            return inner.tryToSolveTypeInModule(module, name);
        }
    }
}
