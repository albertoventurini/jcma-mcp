package jcma.engine;

import java.util.List;
import java.util.Objects;

/**
 * A whole compilation unit's structural outline (the {@link StructuralParser} result): the package
 * name (empty for the default package) plus the top-level type declarations, each carrying its
 * members as {@link Outline#children()}. AST-free, so it crosses the engine seam (PRD §4).
 */
public record FileOutline(String packageName, List<Outline> types) {

    public FileOutline {
        Objects.requireNonNull(packageName, "packageName (use \"\" for default package)");
        types = List.copyOf(types);
    }
}
