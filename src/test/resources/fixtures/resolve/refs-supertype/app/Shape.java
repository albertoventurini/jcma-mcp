package app;

/**
 * A find-references target that is <b>implemented</b> by other types — so its reverse edges include
 * structural {@code IMPLEMENTS} hierarchy edges (which carry no use site, {@code Occurrence.NONE}),
 * not only real {@code REFERENCES} edges. find_references must not treat those hierarchy edges as
 * reference sites.
 */
public interface Shape {
    void draw();
}
