package dev.vox.lss.networking.client;

import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the position-keyed in-flight tracker. The two internal structures
 * (pending map + generation-position set) must stay in sync on every removal
 * path, because generationCount() directly drives the per-type concurrency
 * gates in LodRequestManager.drainQueue.
 */
class InFlightTrackerTest {

    private static final long GEN_POS = PositionUtil.packPosition(1, 1);
    private static final long SYNC_POS = PositionUtil.packPosition(2, 2);
    private static final long FAR_GEN_POS = PositionUtil.packPosition(100, 0);

    /** A threshold no test waits out: entries stamped "now" never expire against it. */
    private static final long ONE_HOUR_NANOS = TimeUnit.HOURS.toNanos(1);

    private InFlightTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new InFlightTracker();
    }

    @Test
    void markPendingTracksGenerationAndSyncSeparately() {
        tracker.markPending(GEN_POS, System.nanoTime(), true);
        tracker.markPending(SYNC_POS, System.nanoTime(), false);

        assertEquals(2, tracker.size());
        assertEquals(1, tracker.generationCount());
        assertTrue(tracker.isInFlight(GEN_POS));
        assertTrue(tracker.isInFlight(SYNC_POS));
    }

    @Test
    void removeByPositionReleasesBothStructures() {
        tracker.markPending(GEN_POS, System.nanoTime(), true);
        tracker.markPending(SYNC_POS, System.nanoTime(), false);

        tracker.removeByPosition(GEN_POS);
        assertEquals(1, tracker.size());
        assertEquals(0, tracker.generationCount(), "generation slot must be released");
        assertFalse(tracker.isInFlight(GEN_POS));

        tracker.removeByPosition(SYNC_POS);
        assertEquals(0, tracker.size());
    }

    @Test
    void removeByPositionOnUntrackedPositionIsNoOp() {
        tracker.markPending(GEN_POS, System.nanoTime(), true);

        tracker.removeByPosition(SYNC_POS);

        assertEquals(1, tracker.size());
        assertEquals(1, tracker.generationCount());
    }

    @Test
    void timeoutSweepRemovesExpiredFromBothStructures() {
        // Sent two thresholds ago -> deterministically expired in the sweep.
        tracker.markPending(GEN_POS, System.nanoTime() - 2 * ONE_HOUR_NANOS, true);
        tracker.markPending(SYNC_POS, System.nanoTime() - 2 * ONE_HOUR_NANOS, false);
        long freshGen = PositionUtil.packPosition(3, 3);
        tracker.markPending(freshGen, System.nanoTime(), true);

        tracker.timeoutSweep(ONE_HOUR_NANOS);

        assertEquals(1, tracker.size());
        assertEquals(1, tracker.generationCount(), "expired generation slot must be released");
        assertFalse(tracker.isInFlight(GEN_POS));
        assertFalse(tracker.isInFlight(SYNC_POS));
        assertTrue(tracker.isInFlight(freshGen));
    }

    @Test
    void pruneOutOfRangeRemovesFromBothStructures() {
        long now = System.nanoTime();
        tracker.markPending(GEN_POS, now, true);     // (1, 1)   - in range
        tracker.markPending(SYNC_POS, now, false);   // (2, 2)   - in range
        tracker.markPending(FAR_GEN_POS, now, true); // (100, 0) - out of range

        tracker.pruneOutOfRange(0, 0, 10);

        assertEquals(2, tracker.size());
        assertEquals(1, tracker.generationCount(), "pruned generation slot must be released");
        assertFalse(tracker.isInFlight(FAR_GEN_POS));
        assertTrue(tracker.isInFlight(GEN_POS));
        assertTrue(tracker.isInFlight(SYNC_POS));
    }

    @Test
    void pruneKeepsPositionsExactlyAtTheBoundary() {
        long boundary = PositionUtil.packPosition(10, -10); // Chebyshev distance exactly 10
        tracker.markPending(boundary, System.nanoTime(), false);

        tracker.pruneOutOfRange(0, 0, 10);

        assertTrue(tracker.isInFlight(boundary), "distance == pruneDistance is in range");
    }

    @Test
    void clearEmptiesBothStructures() {
        tracker.markPending(GEN_POS, System.nanoTime(), true);
        tracker.markPending(SYNC_POS, System.nanoTime(), false);

        tracker.clear();

        assertEquals(0, tracker.size());
        assertEquals(0, tracker.generationCount());
        assertFalse(tracker.isInFlight(GEN_POS));
    }
}
