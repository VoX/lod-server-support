package dev.vox.lss.networking.client;

import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The awaiting-answer set: replaced per scan, drained by answers, pruned by movement. */
class InFlightTrackerTest {

    @Test
    void replaceWithInstallsExactlyTheBatchPositions() {
        var t = new InFlightTracker();
        t.replaceWith(new long[]{1L, 2L, 3L, 99L}, 3); // count gates, not array length
        assertTrue(t.isInFlight(1L));
        assertTrue(t.isInFlight(3L));
        assertFalse(t.isInFlight(99L), "entries past count must not be installed");
        assertEquals(3, t.size());
    }

    @Test
    void replaceWithDropsPositionsNotReDeclared() {
        var t = new InFlightTracker();
        t.replaceWith(new long[]{1L, 2L}, 2);
        t.replaceWith(new long[]{2L}, 1);
        assertFalse(t.isInFlight(1L),
                "a position not re-declared left the want-set; its late status answers "
                + "must fall to the untracked metrics-only path");
        assertTrue(t.isInFlight(2L));
    }

    @Test
    void replaceWithEmptyClearsTheSet() {
        var t = new InFlightTracker();
        t.replaceWith(new long[]{1L}, 1);
        t.replaceWith(new long[0], 0);
        assertEquals(0, t.size(), "an empty fired scan must clear phantoms (rough edge #4)");
    }

    @Test
    void answersDrainWithoutAffectingOthers() {
        var t = new InFlightTracker();
        t.replaceWith(new long[]{1L, 2L}, 2);
        t.removeByPosition(1L);
        assertFalse(t.isInFlight(1L));
        assertTrue(t.isInFlight(2L));
        t.removeByPosition(42L); // untracked: no-op
        assertEquals(1, t.size());
    }

    @Test
    void pruneOutOfRangeKeepsBoundaryDropsOutside() {
        var t = new InFlightTracker();
        long inside = PositionUtil.packPosition(10, 0);
        long outside = PositionUtil.packPosition(11, 0);
        t.replaceWith(new long[]{inside, outside}, 2);
        t.pruneOutOfRange(0, 0, 10);
        assertTrue(t.isInFlight(inside), "exactly-at-boundary is kept");
        assertFalse(t.isInFlight(outside));
    }

    @Test
    void clearEmptiesTheSet() {
        var t = new InFlightTracker();
        t.replaceWith(new long[]{1L}, 1);
        t.clear();
        assertEquals(0, t.size());
    }
}
