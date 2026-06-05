package jcma.index;

/**
 * The kind of a symbol node (PRD §5.1 {@code kind} column). Stored in the columnar store as its
 * {@link #ordinal()}, so the order is part of the on-disk format: <b>append new kinds at the end,
 * never reorder or remove</b> (an older persisted index would otherwise read kinds shifted).
 */
public enum SymbolKind {
    PACKAGE,
    CLASS,
    INTERFACE,
    ENUM,
    RECORD,
    ANNOTATION,
    METHOD,
    CONSTRUCTOR,
    FIELD,
    ENUM_CONSTANT,
    PARAMETER,
    TYPE_PARAMETER,
    MODULE,
    /** A symbol of an unknown/unsupported kind — never guessed into a concrete kind. */
    UNKNOWN;

    private static final SymbolKind[] VALUES = values();

    /** Reverse of {@link #ordinal()} for the store's read path; rejects an out-of-range ordinal. */
    public static SymbolKind byOrdinal(int ordinal) {
        if (ordinal < 0 || ordinal >= VALUES.length) {
            throw new IllegalArgumentException("bad SymbolKind ordinal: " + ordinal);
        }
        return VALUES[ordinal];
    }
}
