package jcma.engine;

/**
 * The kind of a <em>structural hierarchy</em> relation resolved for a declaration (task-11a) —
 * engine-neutral so the {@link AnalysisEngine} seam never leaks a JavaParser type (PRD §4);
 * {@code jcma.resolve} maps each kind to a graph {@code EdgeType}.
 *
 * <p>By the decided convention these mirror the <em>source keyword</em>, not the target's kind:
 * an {@code extends} clause is {@link #EXTENDS} (even interface-extends-interface), an
 * {@code implements} clause is {@link #IMPLEMENTS}. {@link #OVERRIDES} is a method overriding a
 * superclass method <em>or</em> implementing an interface method (both are facts the source states);
 * it is emitted for the <b>direct</b> overridden declaration only — transitive overrides are reached
 * by walking the graph.
 */
public enum HierarchyKind {
    /** An {@code extends} clause entry (subtype → superclass / super-interface). */
    EXTENDS,
    /** An {@code implements} clause entry (subtype → interface). */
    IMPLEMENTS,
    /** A method overriding a superclass method or implementing an interface method. */
    OVERRIDES
}
