import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.DeconstructionPatternTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The javac AST accuracy oracle for the jcma dependency-resolution QA harness. Run as a JDK
 * single-file source-launch ({@code java Oracle.java <out.tsv> <classpath> <srcRoot>...}); needs no
 * build wiring and never touches jcma's sources, so the "no javac in the native path" rule is
 * untouched — this is stock-JDK QA tooling.
 *
 * <p>It full-compiles ({@code parse() + analyze()}) every source under the given roots with the given
 * classpath, then walks each compilation unit with a {@link TreePathScanner}. For every declared type
 * (top-level + nested) it emits, deduped, line-oriented TSV — the exact shape {@code jcma resolve-file}
 * produces, so the two are diffed directly:
 * <pre>
 *   SUPERTYPE       &lt;ownerFqn&gt; &lt;depFqn&gt;   # a direct extends/implements target
 *   TYPEREF         &lt;ownerFqn&gt; &lt;depFqn&gt;   # a type written in source (jcma's navigation contract)
 *   TYPEREF_INFERRED &lt;ownerFqn&gt; &lt;depFqn&gt;  # a type only javac infers: `var` local / implicit lambda param
 * </pre>
 *
 * <p>A type mention is collected at the same syntactic positions jcma's occurrence scan covers —
 * field/local/param types, method return/param/throws, type parameters and bounds, generic type
 * arguments, {@code extends}/{@code implements}, {@code new}, casts, {@code instanceof}, annotations —
 * reduced to the erased type-element FQN (type arguments are themselves recursed into, not flattened).
 * A {@code var} local or implicit lambda parameter has no type written in source: javac infers it, but
 * jcma's syntactic scan (like IntelliJ's Find Usages) cannot, so those go out as {@code TYPEREF_INFERRED}
 * — the comparator reports them as an out-of-contract category, not against headline recall. Primitives
 * and {@code java.lang.Object} are dropped. Each mention attributes to its nearest enclosing
 * <em>named</em> type (an anonymous/local class body attributes to the enclosing named type — matching
 * jcma's enclosing-type attribution).
 */
public final class Oracle {

    private final Trees trees;
    private final Set<String> rows = new TreeSet<>();

    private Oracle(Trees trees) {
        this.trees = trees;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: java Oracle.java <out.tsv> <classpath> <srcRoot>...");
            System.exit(2);
        }
        Path out = Path.of(args[0]);
        String classpath = args[1];
        List<Path> roots = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            roots.add(Path.of(args[i]));
        }

        List<Path> sources = new ArrayList<>();
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
                }
            }
        }
        if (sources.isEmpty()) {
            System.err.println("oracle: no .java sources under " + roots);
            System.exit(1);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
        Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(sources);
        List<String> options = new ArrayList<>(List.of(
                "-classpath", classpath,
                "-proc:none"));            // no annotation processing — we only need resolved types

        JavacTask task = (JavacTask) compiler.getTask(
                new PrintWriter(System.err), fm, diagnostic -> { /* swallow: partial resolution is fine */ },
                options, null, units);
        Trees trees = Trees.instance(task);
        Oracle oracle = new Oracle(trees);

        Iterable<? extends CompilationUnitTree> parsed = task.parse();
        task.analyze();                    // attribute types so getElement resolves
        for (CompilationUnitTree cu : parsed) {
            oracle.new Collector().scan(cu, null);
        }

        Files.write(out, oracle.rows.stream().sorted().toList());
        System.err.println("oracle: " + oracle.rows.size() + " dependency rows from "
                + sources.size() + " source file(s) → " + out);
    }

    private void emit(String relation, String owner, String dep) {
        if (owner == null || dep == null || dep.isEmpty() || owner.isEmpty()) {
            return;
        }
        if (dep.equals("java.lang.Object") || owner.equals(dep)) {
            return;                        // Object is universal noise; a self-edge is not a dependency
        }
        rows.add(relation + "\t" + owner + "\t" + dep);
    }

    /** Walk one compilation unit, tracking the nearest enclosing named type as the attribution owner. */
    private final class Collector extends TreePathScanner<Void, Void> {

        private final Deque<String> owners = new ArrayDeque<>();
        private CharSequence src;          // this unit's source text, lazily read for span checks

        private String owner() {
            return owners.peek();
        }

        @Override
        public Void visitClass(ClassTree node, Void p) {
            Element el = trees.getElement(getCurrentPath());
            String fqn = (el instanceof TypeElement te) ? te.getQualifiedName().toString() : "";
            // Anonymous/local classes have an empty qualified name → attribute their bodies to the
            // nearest enclosing named type (jcma's enclosing-type rule), so push that instead.
            String pushed = fqn.isEmpty() ? owner() : fqn;
            owners.push(pushed == null ? "" : pushed);

            String owner = owner();
            if (!fqn.isEmpty()) {
                // Supertypes: the direct extends/implements targets of THIS type.
                collectSupertype(node.getExtendsClause(), fqn);
                for (Tree iface : node.getImplementsClause()) {
                    collectSupertype(iface, fqn);
                }
            }
            // The extends/implements clauses are also type mentions (the oracle double-counts, as the
            // plan specifies) — collected as TYPEREF here, attributed to the current owner.
            collectType(node.getExtendsClause(), owner);
            for (Tree iface : node.getImplementsClause()) {
                collectType(iface, owner);
            }
            // A sealed type's `permits` entries are type mentions (compile-time coupling to the
            // permitted subtypes) — jcma's type scan sees them, so the oracle must too.
            for (Tree permit : node.getPermitsClause()) {
                collectType(permit, owner);
            }
            for (TypeParameterTree tp : node.getTypeParameters()) {
                for (Tree bound : tp.getBounds()) {
                    collectType(bound, owner);
                }
            }
            Void r = super.visitClass(node, p);
            owners.pop();
            return r;
        }

        @Override
        public Void visitVariable(VariableTree node, Void p) {
            // A field/param/local with a written type is a syntactic mention jcma's scan sees (TYPEREF).
            // A `var` local or an implicit lambda parameter has NO type written in source — javac infers
            // it, but jcma's syntactic ClassOrInterfaceType scan (like IntelliJ's Find Usages) cannot
            // see it. Tag those TYPEREF_INFERRED so the comparator can report them out-of-contract
            // instead of charging them against the headline navigation recall.
            String relation = isInferredType(node) ? "TYPEREF_INFERRED" : "TYPEREF";
            collectType(node.getType(), owner(), relation);
            return super.visitVariable(node, p);
        }

        /**
         * Was this variable's type <em>inferred</em> (not written in source)? True for a {@code var}
         * local and for an implicit lambda parameter. The test is source-faithful: the type position
         * carries no text, the text {@code var}, or — for a lambda param whose synthesized type javac
         * pins to the name token — text equal to the parameter name.
         */
        private boolean isInferredType(VariableTree node) {
            Tree typeTree = node.getType();
            if (typeTree == null) {
                return true;                   // implicit lambda parameter: no type node at all
            }
            CompilationUnitTree cu = getCurrentPath().getCompilationUnit();
            SourcePositions sp = trees.getSourcePositions();
            long start = sp.getStartPosition(cu, typeTree);
            long end = sp.getEndPosition(cu, typeTree);
            if (start < 0 || end < 0 || end <= start) {
                return true;                   // no / zero-width span → synthesized type
            }
            CharSequence s = source();
            if (s == null || end > s.length()) {
                return false;
            }
            String text = s.subSequence((int) start, (int) end).toString().strip();
            if (text.isEmpty() || text.equals("var")) {
                return true;
            }
            // An implicit lambda parameter's synthesized type can be pinned to the parameter-name token
            // (`row -> …` resolves to EvaluationRow but spans "row"). An explicitly-typed param has a
            // distinct type token, so name-coincidence under a lambda parent means it was inferred.
            TreePath parent = getCurrentPath().getParentPath();
            boolean lambdaParam = parent != null && parent.getLeaf() instanceof LambdaExpressionTree;
            return lambdaParam && text.equals(node.getName().toString());
        }

        /** The current compilation unit's source text (cached; one Collector per unit). */
        private CharSequence source() {
            if (src == null) {
                try {
                    src = getCurrentPath().getCompilationUnit().getSourceFile().getCharContent(true);
                } catch (IOException e) {
                    src = "";
                }
            }
            return src;
        }

        @Override
        public Void visitMethod(MethodTree node, Void p) {
            collectType(node.getReturnType(), owner());
            for (Tree thrown : node.getThrows()) {
                collectType(thrown, owner());
            }
            for (TypeParameterTree tp : node.getTypeParameters()) {
                for (Tree bound : tp.getBounds()) {
                    collectType(bound, owner());
                }
            }
            return super.visitMethod(node, p);
        }

        @Override
        public Void visitNewClass(NewClassTree node, Void p) {
            collectType(node.getIdentifier(), owner());
            return super.visitNewClass(node, p);
        }

        @Override
        public Void visitNewArray(NewArrayTree node, Void p) {
            collectType(node.getType(), owner());
            return super.visitNewArray(node, p);
        }

        @Override
        public Void visitTypeCast(TypeCastTree node, Void p) {
            collectType(node.getType(), owner());
            return super.visitTypeCast(node, p);
        }

        @Override
        public Void visitInstanceOf(InstanceOfTree node, Void p) {
            collectType(node.getType(), owner());
            return super.visitInstanceOf(node, p);
        }

        @Override
        public Void visitBindingPattern(BindingPatternTree node, Void p) {
            // A type pattern `case Foo f` / `x instanceof Foo f` names Foo.
            collectType(node.getVariable().getType(), owner());
            return super.visitBindingPattern(node, p);
        }

        @Override
        public Void visitDeconstructionPattern(DeconstructionPatternTree node, Void p) {
            // A record deconstruction pattern `case Foo(int a, String b)` names Foo (nested component
            // patterns are visited by recursion).
            collectType(node.getDeconstructor(), owner());
            return super.visitDeconstructionPattern(node, p);
        }

        @Override
        public Void visitAnnotation(AnnotationTree node, Void p) {
            collectType(node.getAnnotationType(), owner());
            return super.visitAnnotation(node, p);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree node, Void p) {
            // A type method reference `Type::method` names Type. (An instance reference `expr::method`
            // has a value qualifier → not a TypeElement → harmlessly dropped by collectType.)
            collectType(node.getQualifierExpression(), owner());
            return super.visitMemberReference(node, p);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree node, Void p) {
            // A class literal `Foo.class` / `Outer.Inner.class` is a type mention — the selected
            // expression resolves to the type. (jcma's ClassOrInterfaceType scan catches it; e.g.
            // MapStruct's @SubclassMapping(source = FooJpa.class) references FooJpa.)
            if (node.getIdentifier().contentEquals("class")) {
                collectType(node.getExpression(), owner());
            }
            return super.visitMemberSelect(node, p);
        }

        private void collectSupertype(Tree typeTree, String owner) {
            if (typeTree == null) {
                return;
            }
            Element el = trees.getElement(new TreePath(getCurrentPath(), erase(typeTree)));
            if (el instanceof TypeElement te) {
                emit("SUPERTYPE", owner, te.getQualifiedName().toString());
            }
        }

        /** Collect every named type referenced by {@code typeTree} (recursing into type arguments). */
        private void collectType(Tree typeTree, String owner) {
            collectType(typeTree, owner, "TYPEREF");
        }

        /** As above, but emitting under {@code relation} (TYPEREF for written types, TYPEREF_INFERRED for var/lambda). */
        private void collectType(Tree typeTree, String owner, String relation) {
            if (typeTree == null || owner == null || owner.isEmpty()) {
                return;
            }
            collectType(new TreePath(getCurrentPath(), typeTree), owner, relation);
        }

        private void collectType(TreePath path, String owner, String relation) {
            Tree t = path.getLeaf();
            switch (t.getKind()) {
                case PARAMETERIZED_TYPE -> {
                    ParameterizedTypeTree pt = (ParameterizedTypeTree) t;
                    collectType(new TreePath(path, pt.getType()), owner, relation);
                    for (Tree arg : pt.getTypeArguments()) {
                        collectType(new TreePath(path, arg), owner, relation);
                    }
                }
                case ARRAY_TYPE -> collectType(new TreePath(path, ((ArrayTypeTree) t).getType()), owner, relation);
                case ANNOTATED_TYPE ->
                        collectType(new TreePath(path, ((AnnotatedTypeTree) t).getUnderlyingType()), owner, relation);
                case EXTENDS_WILDCARD, SUPER_WILDCARD -> {
                    Tree bound = ((WildcardTree) t).getBound();
                    if (bound != null) {
                        collectType(new TreePath(path, bound), owner, relation);
                    }
                }
                case PRIMITIVE_TYPE, UNBOUNDED_WILDCARD -> { /* no named type */ }
                default -> {
                    Element el = trees.getElement(path);
                    if (el instanceof TypeElement te) {
                        emit(relation, owner, te.getQualifiedName().toString());
                    }
                    // A qualified type name `Outer.Inner` (a MEMBER_SELECT in a type position) names
                    // its qualifier too — recurse so `Outer` counts, matching jcma's per-node scan,
                    // which sees the nested ClassOrInterfaceType scope. A package qualifier resolves to
                    // a PackageElement (not a TypeElement) and is harmlessly dropped.
                    if (t instanceof MemberSelectTree ms) {
                        collectType(new TreePath(path, ms.getExpression()), owner, relation);
                    }
                }
            }
        }

        /** Strip a parameterized type down to its raw type tree (for supertype-element resolution). */
        private Tree erase(Tree typeTree) {
            return typeTree.getKind() == Tree.Kind.PARAMETERIZED_TYPE
                    ? ((ParameterizedTypeTree) typeTree).getType()
                    : typeTree;
        }
    }
}
