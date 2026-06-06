package jcma.index;

/**
 * The source set a symbol's declaring file belongs to: production code ({@link #MAIN}) versus test
 * code ({@link #TEST}). Lets a consumer tell prod from test in {@code search} results and (with
 * Tier-2 references, task-10) reason about blast radius — e.g. "referenced only by tests".
 *
 * <p><b>Storage = packed into {@link Symbol#flags()} bit 0</b> (no dedicated column): {@code flags}
 * is a jcma-owned, store-opaque bitset, so {@link #MAIN} is the zero encoding and a legacy/untagged
 * symbol ({@code flags == 0}) reads back as {@code MAIN}. Bits 1+ stay free for future modifier
 * flags. This keeps the on-disk {@link SymbolStore}/overlay-log format unchanged (PRD §5.1).
 */
public enum SourceSet {
    /** Production source ({@code src/main/java} or Maven {@code <sourceDirectory>}). The zero value. */
    MAIN,
    /** Test source ({@code src/test/java} or Maven {@code <testSourceDirectory>}). */
    TEST;

    private static final int BIT = 0x1;

    /** Decode the source set from a {@link Symbol#flags()} value: bit 0 set ⇒ {@link #TEST}. */
    public static SourceSet of(int flags) {
        return (flags & BIT) != 0 ? TEST : MAIN;
    }

    /** The {@code flags} bits this source set contributes ({@code MAIN}=0, {@code TEST}=bit 0). */
    public static int flagBits(SourceSet set) {
        return set == TEST ? BIT : 0;
    }

    /** This source set's {@code flags} encoding; see {@link #flagBits(SourceSet)}. */
    public int toFlags() {
        return flagBits(this);
    }
}
