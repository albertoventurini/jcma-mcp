package jcma.engine;

/**
 * Neutral facts about a <em>safe-degrading</em> resolve miss — the throwable's type + message plus a
 * few precomputed AST-context flags. The engine computes these (it can see the JavaParser node);
 * {@code jcma.resolve.FailureClassifier} maps them to a cause bucket, so the node-inspecting logic
 * stays behind the §4 seam (decided in task-10 design). A miss is never a wrong answer — the engine
 * knows it failed and the use-site is carried in the unconfirmed tail.
 *
 * @param throwableType       simple class name of the throwable (e.g. {@code MethodAmbiguityException})
 * @param message             its message (whitespace-collapsed), or empty
 * @param stackOverflow       true if the miss was a {@code StackOverflowError} (deep inference)
 * @param lambdaOrMethodRef   the use-site is at/under a lambda or method reference
 * @param patternRecordSealed the use-site is inside pattern-matching / a record / a sealed context
 * @param varInference        the use-site is tied to a {@code var} local
 * @param hasTypeArguments    the use-site node carries explicit type arguments
 */
public record ResolveFailure(String throwableType, String message, boolean stackOverflow,
        boolean lambdaOrMethodRef, boolean patternRecordSealed, boolean varInference,
        boolean hasTypeArguments) {}
