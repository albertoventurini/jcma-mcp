package jcma.resolve;

import java.util.List;

/**
 * The {@code find_references(X)} answer (PRD §6): confirmed references <b>grouped by enclosing
 * symbol</b>, plus the mandatory <b>unconfirmed tail</b> (M0 Spike A requirement) — candidate
 * occurrences with X's simple name that failed to resolve, so the set is never presented as
 * exhaustive when a candidate could not be ruled in or out.
 *
 * @param groups      confirmed references, one {@link ReferenceGroup} per enclosing declaration
 * @param unconfirmed candidates that matched X's name but failed to resolve (never silently dropped)
 */
public record References(List<ReferenceGroup> groups, List<UnconfirmedRef> unconfirmed) {

    public References {
        groups = List.copyOf(groups);
        unconfirmed = List.copyOf(unconfirmed);
    }

    /** Total confirmed references across all groups. */
    public int totalRefs() {
        int n = 0;
        for (ReferenceGroup g : groups) {
            n += g.refs().size();
        }
        return n;
    }

    /** True if any candidate could not be resolved — the result is then <em>not</em> exhaustive. */
    public boolean hasUnconfirmedTail() {
        return !unconfirmed.isEmpty();
    }
}
