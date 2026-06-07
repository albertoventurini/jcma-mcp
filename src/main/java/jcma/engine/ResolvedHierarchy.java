package jcma.engine;

/**
 * One resolved structural hierarchy relation (task-11a): a declaration in the parsed file relates to
 * a supertype / overridden method. The <em>source</em> declaration is identified by its name position
 * ({@code srcLine}/{@code srcCol}) so {@code jcma.resolve} can map it to the enclosing graph moniker
 * (the same {@code monikerAt} bridge the reference path uses); the <em>target</em> is a
 * {@link ResolvedTarget} (a project decl, or {@code null} {@code declFile} → a phantom node).
 *
 * <p>A relation is only surfaced when the target resolved; an unresolvable supertype safe-degrades to
 * nothing (no guessed edge), per the {@link AnalysisEngine} contract.
 *
 * @param kind    the relation kind
 * @param srcLine 1-based line of the source declaration's name
 * @param srcCol  1-based column of the source declaration's name
 * @param target  the resolved supertype / overridden method; never {@code null}
 */
public record ResolvedHierarchy(HierarchyKind kind, int srcLine, int srcCol, ResolvedTarget target) {}
