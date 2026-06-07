package jcma.engine;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.util.ArrayList;
import java.util.List;

/**
 * The shared use-site enumeration over the seven occurrence categories (ported from M0
 * {@code SpikeA.occurrences}). Package-private and JavaParser-typed: it is the one place inside the
 * {@code jcma.engine} seam that walks the AST for use-sites, consumed by both the parse-only
 * {@link StructuralParser#usages} (→ usage-name index) and the resolving
 * {@link JavaParserEngine#resolveOccurrences} (→ Tier-2 edges). Keeping it shared means both tiers
 * see exactly the same set of use-sites.
 */
final class Occurrences {

    /** One enumerated use-site: its category, the AST node (to resolve), its syntactic name + range. */
    record Occ(OccurrenceKind kind, Node node, String targetName,
            int startLine, int startCol, int endLine, int endCol) {}

    private Occurrences() {}

    /** Every use-site in {@code cu}, in a stable category order (mirrors {@code SpikeA.occurrences}). */
    static List<Occ> scan(CompilationUnit cu) {
        List<Occ> out = new ArrayList<>();
        cu.findAll(MethodCallExpr.class).forEach(n -> add(out, OccurrenceKind.CALL, n, n.getNameAsString()));
        cu.findAll(ObjectCreationExpr.class).forEach(n ->
                add(out, OccurrenceKind.INSTANTIATION, n, n.getType().getNameAsString()));
        cu.findAll(NameExpr.class).forEach(n -> add(out, OccurrenceKind.NAME, n, n.getNameAsString()));
        cu.findAll(FieldAccessExpr.class).forEach(n ->
                add(out, OccurrenceKind.FIELD_ACCESS, n, n.getNameAsString()));
        cu.findAll(MethodReferenceExpr.class).forEach(n ->
                add(out, OccurrenceKind.METHOD_REF, n, n.getIdentifier()));
        cu.findAll(ClassOrInterfaceType.class).forEach(n ->
                add(out, OccurrenceKind.TYPE_REF, n, n.getNameAsString()));
        cu.findAll(AnnotationExpr.class).forEach(n -> add(out, OccurrenceKind.ANNOTATION, n, n.getNameAsString()));
        return out;
    }

    private static void add(List<Occ> out, OccurrenceKind kind, Node node, String name) {
        node.getRange().ifPresent(r -> out.add(new Occ(kind, node, name,
                r.begin.line, r.begin.column, r.end.line, r.end.column)));
    }
}
