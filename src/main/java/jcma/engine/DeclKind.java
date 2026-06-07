package jcma.engine;

/**
 * Engine-neutral kind of a <em>resolved declaration</em> — what a use-site resolved to (a method, a
 * class, a field, …). Lets {@code jcma.resolve} mint a jar/JDK <b>phantom node</b> with its real kind
 * instead of {@code UNKNOWN}, without the engine depending on {@code jcma.index.SymbolKind} (PRD §4).
 */
public enum DeclKind {
    CLASS, INTERFACE, ENUM, RECORD, ANNOTATION, METHOD, CONSTRUCTOR, FIELD, OTHER
}
