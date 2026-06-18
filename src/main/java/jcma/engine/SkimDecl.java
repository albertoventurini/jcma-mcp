package jcma.engine;

import java.util.List;
import java.util.Objects;

/**
 * One declaration in a {@link SkimUnit} ({@code skim_java}), carrying the <b>verbatim source spans</b>
 * a renderer needs to reproduce the file as real Java with bodies elided — never re-synthesized Java.
 * AST-free (PRD §4): the {@link StructuralParser} slices these substrings off the source text behind
 * the engine seam, so the JavaParser AST never crosses it.
 *
 * <p>Spans, all sliced from column 1 of their start line (leading indentation kept, so a renderer
 * reproduces the source layout without re-indenting):
 * <ul>
 *   <li>{@link #docText} — the verbatim Javadoc {@code /** … *}{@code /}, or {@code null} if none /
 *       dropped; {@link #docStartLine} is its 1-based start line ({@code -1} when absent).
 *   <li>{@link #headerText} — for a type or a body-bearing method/constructor, the declaration from
 *       its first modifier/annotation up to (not including) the opening {@code &#123;} (so it carries
 *       annotations, modifiers, generics, params, {@code throws}, {@code extends}/{@code implements},
 *       and a record's components). For a no-body member (field, abstract/interface method, enum
 *       constant) it is the <em>whole</em> declaration, including the trailing {@code ;}.
 *   <li>{@link #bodyInner} — the verbatim text <em>between</em> the body braces (the char gate measures
 *       its normalized length), or {@code null} when there is no block body ({@link #hasBody} false).
 * </ul>
 *
 * <p>{@link #bodyHasLineComment} flags a block body that contains a {@code //} line comment (decided
 * by tokens, so a {@code //} inside a string literal is not a false positive): such a body can never be
 * inlined (collapsing its newlines would pull the closing brace into the comment), so a renderer elides
 * it regardless of the char gate — keeping the inlined view valid Java.
 *
 * <p>{@link #startLine}/{@link #endLine} are the declaration's 1-based range (the {@code endLine} is
 * the closing-brace line for a type / body-method), used for the gutter and the elision line-gap.
 * Containment is the {@link #children} tree (a type's members + a nested type's own members; record
 * components and a method's body are <em>not</em> children — they live in the spans).
 */
public record SkimDecl(
        Outline.Kind kind,
        String name,
        String docText,
        int docStartLine,
        String headerText,
        int startLine,
        int endLine,
        String bodyInner,
        boolean hasBody,
        boolean bodyHasLineComment,
        List<SkimDecl> children) {

    public SkimDecl {
        Objects.requireNonNull(kind, "skim decl kind");
        Objects.requireNonNull(name, "skim decl name");
        Objects.requireNonNull(headerText, "skim decl headerText");
        children = List.copyOf(children);
    }

    /** Whether this declaration is a type (renders its children inside braces) vs a member. */
    public boolean isType() {
        return switch (kind) {
            case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION -> true;
            case METHOD, CONSTRUCTOR, FIELD, ENUM_CONSTANT -> false;
        };
    }
}
