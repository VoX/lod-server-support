package dev.vox.lss.networking.client;

import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * The awaiting-answer set: positions of the most recent want-set batch, minus answers
 * received since. This is NOT a send-suppression structure — the scanner re-declares
 * every unsatisfied position each scan regardless. It exists for exactly two consumers:
 * status-response gating (BatchResponse carries no dimension, so up_to_date /
 * not_generated gate on membership here — cleared on dimension change, matching the
 * pre-v16 requestId gate) and dirty-crossing detection (a dirty broadcast landing while
 * a position awaits an answer must survive that answer's mark-clear; see
 * ColumnStateMap.noteStaleIfInFlight).
 *
 * <p>Replaced wholesale on every fired scan ({@link #replaceWith}), so a position that
 * left the scan window (superseded + excluded) cannot linger past the next scan.
 *
 * <p><b>Thread safety:</b> Not thread-safe. All methods must be called from the main
 * client thread (render/tick loop).
 */
class InFlightTracker {

    private final LongOpenHashSet awaiting = new LongOpenHashSet();

    /** Replace the set with the positions of the batch just sent (count may be 0). */
    void replaceWith(long[] positions, int count) {
        this.awaiting.clear();
        for (int i = 0; i < count; i++) {
            this.awaiting.add(positions[i]);
        }
    }

    /** An answer arrived for this position. No-op if the position was not tracked. */
    void removeByPosition(long position) {
        this.awaiting.remove(position);
    }

    boolean isInFlight(long position) {
        return this.awaiting.contains(position);
    }

    int size() {
        return this.awaiting.size();
    }

    /**
     * Remove all awaited positions outside the given Chebyshev distance from the player.
     * Answers for pruned positions fall to the untracked (metrics-only) path; column DATA
     * still applies untracked, so nothing is lost.
     */
    void pruneOutOfRange(int playerCx, int playerCz, int pruneDistance) {
        var iter = this.awaiting.iterator();
        while (iter.hasNext()) {
            if (PositionUtil.isOutOfRange(iter.nextLong(), playerCx, playerCz, pruneDistance)) {
                iter.remove();
            }
        }
    }

    void clear() {
        this.awaiting.clear();
    }
}
