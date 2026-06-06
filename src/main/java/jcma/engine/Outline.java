package jcma.engine;

import java.util.List;
import java.util.Objects;

/**
 * One declaration in a file's structural outline — the neutral, AST-free shape the Tier-1
 * {@link StructuralParser} hands across the engine seam (PRD §4: the JavaParser {@code
 * CompilationUnit} must not leak out of this package). A {@code jcma.index} consumer maps an
 * {@code Outline} tree to its own symbol model (monikers, kinds, containment edges).
 *
 * <p>Containment is the {@link #children} tree (a type's methods/fields/nested types). {@link
 * #paramTypes} is the textual parameter-type list for a {@link Kind#METHOD}/{@link Kind#CONSTRUCTOR}
 * (as written in source — Tier-1 does not resolve to FQNs), empty otherwise. {@link #signature} is a
 * human-readable signature or {@code null} when the kind has none.
 */
public record Outline(
        Kind kind,
        String name,
        String signature,
        List<String> paramTypes,
        int startLine,
        int startCol,
        int endLine,
        int endCol,
        List<Outline> children) {

    /** The declaration kinds Tier-1 extracts (a parse-only subset; mapped to {@code SymbolKind}). */
    public enum Kind {
        CLASS, INTERFACE, ENUM, RECORD, ANNOTATION, METHOD, CONSTRUCTOR, FIELD, ENUM_CONSTANT
    }

    public Outline {
        Objects.requireNonNull(kind, "outline kind");
        Objects.requireNonNull(name, "outline name");
        paramTypes = List.copyOf(paramTypes);
        children = List.copyOf(children);
    }
}
