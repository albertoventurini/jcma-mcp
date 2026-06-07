package jcma.engine;

/**
 * The syntactic category of a use-site (PRD §5.1 occurrence; ported from M0 {@code SpikeA.Cat}) — the
 * seven node kinds Tier-2 enumerates and resolves. Engine-neutral so the {@link AnalysisEngine} seam
 * never leaks a JavaParser type (PRD §4); {@code jcma.resolve} maps each kind to a graph
 * {@code EdgeType} + occurrence role.
 */
public enum OccurrenceKind {
    /** A method invocation, e.g. {@code foo.bar()}. */
    CALL,
    /** A constructor invocation, e.g. {@code new Foo()}. */
    INSTANTIATION,
    /** A bare name reference (variable/field/type used as a value), e.g. {@code x}. */
    NAME,
    /** A qualified field access, e.g. {@code obj.field}. */
    FIELD_ACCESS,
    /** A method reference, e.g. {@code Foo::bar}. */
    METHOD_REF,
    /** A type reference (extends/implements, declared type, cast, throws, …), e.g. {@code List}. */
    TYPE_REF,
    /** An annotation application, e.g. {@code @Override}. */
    ANNOTATION
}
