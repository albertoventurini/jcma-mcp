package jcma.resolve;

import java.util.List;

/**
 * Confirmed references that share an enclosing declaration — the "grouped by enclosing symbol"
 * unit of a {@code find_references} answer (PRD §6). The enclosing symbol is the {@code src} of each
 * reference edge; rendering it as a group is what lets an agent read "called from {@code Foo.bar}"
 * without a follow-up lookup.
 *
 * @param enclosingMoniker   moniker of the declaration the references sit inside
 * @param enclosingSignature human-readable signature of that declaration (for display)
 * @param refs               the individual reference sites within it
 */
public record ReferenceGroup(String enclosingMoniker, String enclosingSignature, List<Ref> refs) {

    public ReferenceGroup {
        refs = List.copyOf(refs);
    }

    /** Number of reference sites in this group. */
    public int count() {
        return refs.size();
    }
}
