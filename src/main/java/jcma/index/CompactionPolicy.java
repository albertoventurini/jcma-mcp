package jcma.index;

/**
 * Decides <em>when</em> the {@link LsmStore} folds its overlay back into a fresh base (PRD §11,
 * "overlay/compaction trigger policy"). A swappable seam on purpose: the store calls
 * {@link #shouldCompact} after each edit, and the policy can be relative-to-base, an absolute
 * threshold, or manual-only — changed without touching the store.
 *
 * <p><b>Decided (M1 Task-06):</b> the default is {@link #relativeToBase} — compact once the overlay
 * log grows to rival the base. A relative trigger self-scales to repo size (it avoids a magic
 * absolute constant that is simultaneously too large for a tiny repo and too small for a huge one),
 * and crucially it bounds <em>write amplification</em>: compaction rewrites the whole base, whose
 * cost scales with base size, so we wait until enough change has accumulated to be worth that
 * rewrite. The chosen ratio is provisional and re-calibrated from the metrics each compaction
 * records (overlay/base bytes, ratio at trigger, rewrite + replay durations).
 */
@FunctionalInterface
public interface CompactionPolicy {

    /**
     * @param overlayLogBytes  current size of the durable overlay log
     * @param baseBytes        total size of the immutable base segments (symbols + edges + trigram)
     * @param overlayFileCount number of files currently held in the overlay (edited or tombstoned)
     * @return {@code true} to compact now
     */
    boolean shouldCompact(long overlayLogBytes, long baseBytes, int overlayFileCount);

    /** Never auto-compacts; only an explicit {@link LsmStore#compact()} folds the overlay. */
    static CompactionPolicy manual() {
        return (overlayLogBytes, baseBytes, overlayFileCount) -> false;
    }

    /**
     * Compact once the overlay log reaches {@code ratio}× the base size (the default policy). With no
     * base yet (cold), never fires — the first base is written by an explicit compaction.
     */
    static CompactionPolicy relativeToBase(double ratio) {
        if (ratio <= 0) {
            throw new IllegalArgumentException("ratio must be > 0: " + ratio);
        }
        return (overlayLogBytes, baseBytes, overlayFileCount) ->
                baseBytes > 0 && overlayLogBytes >= (long) (baseBytes * ratio);
    }
}
