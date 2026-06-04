package m0;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.PatternExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeArguments;

/**
 * Heuristic attribution of a resolve() failure to a cause bucket (M0 doc Spike A.3). Attribution
 * is best-effort — exception type + node context — so SpikeA dumps exemplars per bucket for
 * manual validation. ALL thrown failures are "safe-degrading" by construction (the engine knows
 * it failed and the product surfaces an "unconfirmed" result); this classifier only explains WHY.
 */
public final class FailureClassifier {

    public enum Cause {
        OVERLOAD_AMBIGUITY,     // SymbolSolver couldn't pick among overloads
        LAMBDA_METHODREF,       // failure at/under a lambda or method reference
        PATTERN_RECORD_SEALED,  // failure inside pattern-matching / record / sealed context
        VAR_INFERENCE,          // failure tied to a `var` local
        GENERICS_INFERENCE,     // generics / type-variable / deep inference (incl. StackOverflow)
        NESTED_MEMBER_ACCESS,   // Outer.Nested.X where Outer resolves — SymbolSolver nested-access gap
        ANNOTATION_MEMBER,      // calling an annotation attribute accessor (ann.value()) — unsupported
        NON_SYMBOL_QUALIFIER,   // a package-path segment (java.io) used as access scope — not a symbol
        MISSING_CLASSPATH,      // genuinely unresolved type absent from the manual classpath
        UNSUPPORTED,            // other UnsupportedOperationException (resolution gap)
        PARSER_GAP,             // node never produced (parse failure) — handled upstream
        OTHER                   // unclassified; inspect the exemplar
    }

    public record Result(Cause cause, String note) {}

    private FailureClassifier() {}

    public static Result classify(Node node, Throwable t) {
        String exType = t.getClass().getSimpleName();
        String msg = t.getMessage() == null ? "" : t.getMessage().replaceAll("\\s+", " ").trim();
        String note = exType + (msg.isEmpty() ? "" : ": " + truncate(msg, 160));

        // 1. Unambiguous from exception type.
        if (exType.equals("MethodAmbiguityException")) return new Result(Cause.OVERLOAD_AMBIGUITY, note);
        if (t instanceof StackOverflowError)           return new Result(Cause.GENERICS_INFERENCE, note);

        // 2. Message-shape (independent of node context — these are the dominant real causes).
        String unsolved = unsolvedTarget(msg);
        if (unsolved != null) {
            if (isPackagePath(unsolved))   return new Result(Cause.NON_SYMBOL_QUALIFIER, note);
            if (isNestedTypeRef(unsolved)) return new Result(Cause.NESTED_MEMBER_ACCESS, note);
        }
        if (t instanceof UnsupportedOperationException && msg.contains("AnnotationDeclaration"))
            return new Result(Cause.ANNOTATION_MEMBER, note);

        // 3. Context-driven (lambda/pattern/var).
        if (involvesLambdaOrMethodRef(node))  return new Result(Cause.LAMBDA_METHODREF, note);
        if (inPatternRecordSealed(node))      return new Result(Cause.PATTERN_RECORD_SEALED, note);
        if (involvesVar(node))                return new Result(Cause.VAR_INFERENCE, note);

        // 4. Exception-type fallbacks.
        if (t instanceof UnsupportedOperationException) return new Result(Cause.UNSUPPORTED, note);
        if (hasTypeArguments(node))                     return new Result(Cause.GENERICS_INFERENCE, note);
        if (exType.equals("UnsolvedSymbolException"))   return new Result(Cause.MISSING_CLASSPATH, note);

        return new Result(Cause.OTHER, note);
    }

    private static boolean involvesLambdaOrMethodRef(Node node) {
        if (node instanceof MethodReferenceExpr) return true;
        if (node.findAncestor(LambdaExpr.class).isPresent()) return true;
        if (node.findAncestor(MethodReferenceExpr.class).isPresent()) return true;
        // an overload target whose arguments include a lambda/method-ref (the classic inference pain)
        if (node instanceof MethodCallExpr mce) {
            return mce.getArguments().stream()
                    .anyMatch(a -> a instanceof LambdaExpr || a instanceof MethodReferenceExpr);
        }
        return false;
    }

    private static boolean inPatternRecordSealed(Node node) {
        if (node.findAncestor(PatternExpr.class).isPresent()) return true;
        if (node.findAncestor(RecordDeclaration.class).isPresent()) return true;
        return node.findAncestor(InstanceOfExpr.class)
                .map(io -> io.getPattern().isPresent())
                .orElse(false);
    }

    private static boolean involvesVar(Node node) {
        return node.findAncestor(VariableDeclarator.class)
                .map(vd -> vd.getType().isVarType())
                .orElse(false);
    }

    private static boolean hasTypeArguments(Node node) {
        if (node instanceof NodeWithTypeArguments<?> nwta && nwta.getTypeArguments().isPresent()) {
            return !nwta.getTypeArguments().get().isEmpty();
        }
        return false;
    }

    /** Extract the unresolved symbol name from an UnsolvedSymbolException message, else null. */
    private static String unsolvedTarget(String msg) {
        String marker = "corresponding to ";
        int i = msg.indexOf(marker);
        if (i >= 0) return tidy(msg.substring(i + marker.length()));
        marker = "Unsolved symbol : ";
        i = msg.indexOf(marker);
        if (i >= 0) return tidy(msg.substring(i + marker.length()));
        return null;
    }

    private static String tidy(String s) {
        int sp = s.indexOf(' ');
        if (sp >= 0) s = s.substring(0, sp);
        return s.replaceAll("[^A-Za-z0-9_.:]+$", "").trim();
    }

    /** A pure package path used as an access scope, e.g. `java` or `java.io` — not a symbol. */
    private static boolean isPackagePath(String target) {
        return target.matches("[a-z][a-zA-Z0-9_]*(\\.[a-z][a-zA-Z0-9_]*)*");
    }

    /** `Outer.Nested` member/type access (the outer type resolves; SymbolSolver nested-access gap). */
    private static boolean isNestedTypeRef(String target) {
        return !target.contains("::") && target.matches("[A-Z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
