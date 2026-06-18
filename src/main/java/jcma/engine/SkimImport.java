package jcma.engine;

import java.util.Objects;

/**
 * One {@code import} declaration in a {@link SkimUnit}, kept verbatim ({@code text}, e.g.
 * {@code import static java.util.Map.entry;}) with its 1-based source {@code line} — so the
 * {@code skim_java} gutter stays coordinate-coherent with the line-reporting tools. AST-free
 * (PRD §4).
 */
public record SkimImport(String text, int line) {

    public SkimImport {
        Objects.requireNonNull(text, "import text");
    }
}
