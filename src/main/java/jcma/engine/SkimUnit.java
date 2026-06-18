package jcma.engine;

import java.util.List;
import java.util.Objects;

/**
 * A whole compilation unit shaped for {@code skim_java} (the fourth {@link StructuralParser.Parsed}
 * projection, beside {@code outline()}/{@code usages()}/{@code textUnits()}): the package + imports +
 * top-level declarations, each carrying its <b>verbatim source spans</b> ({@link SkimDecl}) so a
 * renderer can reproduce the file as real Java with method bodies elided. AST-free (PRD §4) — the
 * JavaParser {@code CompilationUnit} stays behind the engine seam.
 *
 * @param packageName the package name, or {@code ""} for the default package
 * @param packageLine the 1-based line of the package declaration, or {@code -1} if none
 * @param imports     the verbatim import declarations, in source order
 * @param types       the top-level type declarations, in source order
 */
public record SkimUnit(String packageName, int packageLine, List<SkimImport> imports, List<SkimDecl> types) {

    public SkimUnit {
        Objects.requireNonNull(packageName, "packageName (use \"\" for default package)");
        imports = List.copyOf(imports);
        types = List.copyOf(types);
    }
}
