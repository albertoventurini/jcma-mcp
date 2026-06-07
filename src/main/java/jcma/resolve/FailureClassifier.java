package jcma.resolve;

import jcma.engine.ResolveFailure;

/**
 * Buckets a safe-degrading resolve miss into a cause (ported from M0 {@code m0.FailureClassifier};
 * see PRD §4 / M0-RESULTS §"Failure-cause histogram"). A miss is never a wrong answer — the engine
 * knows it failed and the result carries the occurrence in the <em>unconfirmed tail</em>; this only
 * explains <em>why</em>, so {@code find_references}/{@code find_definition} can report it usefully.
 *
 * <p><b>Seam split (task-10):</b> the M0 classifier inspected JavaParser AST nodes, which must not
 * cross the {@code jcma.engine} seam (PRD §4). So the engine precomputes neutral facts into a
 * {@link ResolveFailure} (throwable type/message + context flags) and this classifier — in
 * {@code resolve/} per the task scope — maps those to a {@link Cause}.
 */
public final class FailureClassifier {

    /** The 12 miss-cause buckets (M0 Spike A.3 enumeration; order is not persisted). */
    public enum Cause {
        OVERLOAD_AMBIGUITY,     // SymbolSolver couldn't pick among overloads
        LAMBDA_METHODREF,       // failure at/under a lambda or method reference
        PATTERN_RECORD_SEALED,  // failure inside pattern-matching / record / sealed context
        VAR_INFERENCE,          // failure tied to a `var` local
        GENERICS_INFERENCE,     // generics / type-variable / deep inference (incl. StackOverflow)
        NESTED_MEMBER_ACCESS,   // Outer.Nested.X where Outer resolves — SymbolSolver nested-access gap
        ANNOTATION_MEMBER,      // calling an annotation attribute accessor (ann.value()) — unsupported
        NON_SYMBOL_QUALIFIER,   // a package-path segment (java.io) used as access scope — not a symbol
        MISSING_CLASSPATH,      // genuinely unresolved type absent from the classpath
        UNSUPPORTED,            // other UnsupportedOperationException (resolution gap)
        PARSER_GAP,             // node never produced (parse failure) — handled upstream
        OTHER                   // unclassified; inspect the exemplar
    }

    private FailureClassifier() {}

    /** Map a miss's neutral facts to a {@link Cause} (port of {@code m0.FailureClassifier.classify}). */
    public static Cause classify(ResolveFailure f) {
        String exType = f.throwableType();
        String msg = f.message() == null ? "" : f.message();

        // 1. Unambiguous from exception type.
        if ("MethodAmbiguityException".equals(exType)) {
            return Cause.OVERLOAD_AMBIGUITY;
        }
        if (f.stackOverflow()) {
            return Cause.GENERICS_INFERENCE;
        }

        // 2. Message-shape (the dominant real causes, independent of node context).
        String unsolved = unsolvedTarget(msg);
        if (unsolved != null) {
            if (isPackagePath(unsolved)) {
                return Cause.NON_SYMBOL_QUALIFIER;
            }
            if (isNestedTypeRef(unsolved)) {
                return Cause.NESTED_MEMBER_ACCESS;
            }
        }
        if ("UnsupportedOperationException".equals(exType) && msg.contains("AnnotationDeclaration")) {
            return Cause.ANNOTATION_MEMBER;
        }

        // 3. Context-driven (precomputed by the engine).
        if (f.lambdaOrMethodRef()) {
            return Cause.LAMBDA_METHODREF;
        }
        if (f.patternRecordSealed()) {
            return Cause.PATTERN_RECORD_SEALED;
        }
        if (f.varInference()) {
            return Cause.VAR_INFERENCE;
        }

        // 4. Exception-type fallbacks.
        if ("UnsupportedOperationException".equals(exType)) {
            return Cause.UNSUPPORTED;
        }
        if (f.hasTypeArguments()) {
            return Cause.GENERICS_INFERENCE;
        }
        if ("UnsolvedSymbolException".equals(exType)) {
            return Cause.MISSING_CLASSPATH;
        }
        return Cause.OTHER;
    }

    /** Extract the unresolved symbol name from an UnsolvedSymbolException message, else null. */
    private static String unsolvedTarget(String msg) {
        String marker = "corresponding to ";
        int i = msg.indexOf(marker);
        if (i >= 0) {
            return tidy(msg.substring(i + marker.length()));
        }
        marker = "Unsolved symbol : ";
        i = msg.indexOf(marker);
        if (i >= 0) {
            return tidy(msg.substring(i + marker.length()));
        }
        return null;
    }

    private static String tidy(String s) {
        int sp = s.indexOf(' ');
        if (sp >= 0) {
            s = s.substring(0, sp);
        }
        return s.replaceAll("[^A-Za-z0-9_.:]+$", "").trim();
    }

    /** A pure package path used as an access scope, e.g. {@code java} or {@code java.io} — not a symbol. */
    private static boolean isPackagePath(String target) {
        return target.matches("[a-z][a-zA-Z0-9_]*(\\.[a-z][a-zA-Z0-9_]*)*");
    }

    /** {@code Outer.Nested} member/type access (the outer type resolves; SymbolSolver nested-access gap). */
    private static boolean isNestedTypeRef(String target) {
        return !target.contains("::") && target.matches("[A-Z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }
}
