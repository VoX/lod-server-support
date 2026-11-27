package dev.vox.lss.networking.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.Test;

/** Issue #437 (plan §3.4): Vertigo hollowed a delivered column in Voxy, so its stamp is
 *  forgotten without a strike and the next scan re-asks it with ts=-1. */
class ColumnStateMapHollowedTest {
    static final long P = PositionUtil.packPosition(12, -7);

    @Test void aDeliveredColumnIsForgottenAndReasked() {
        var map = new ColumnStateMap();
        map.onReceived(P, 1_234L);
        assertEquals(ColumnStateMap.SATISFIED, map.classify(P), "premise: delivered, nothing to ask");
        assertEquals(1_234L, map.timestampFor(P));
        assertTrue(map.onHollowed(P));
        assertEquals(-1L, map.timestampFor(P));
        assertEquals(-1L, map.classify(P), "re-asked with ts=-1, so the server serves it again");
        assertTrue(map.hasRetries(), "the retry mark reopens the ring");
        assertTrue(map.persistentRemovalsForSave().contains(P), "the removal reaches the cache file");
    }

    @Test void aLegacyZeroStampIsForgottenToo() {
        var map = new ColumnStateMap();
        map.onReceived(P, 0L);
        assertTrue(map.onHollowed(P));
        assertEquals(-1L, map.classify(P));
    }

    @Test void aColumnFlownOverAgainAndAgainNeverParks() {
        var map = new ColumnStateMap();
        for (int i = 1; i <= 100; i++) {
            map.onReceived(P, 1_000L + i);
            assertTrue(map.onHollowed(P), "round " + i);
            assertEquals(-1L, map.classify(P), "round " + i);
        }
        assertEquals(0, map.ingestParkedCount(), "no strikes: the consumer did nothing wrong");
        assertFalse(map.isSessionSatisfied(P));
    }

    @Test void nothingInMemoryStillRecordsTheRemoval() {
        // Pruned out of range before the release (a teleport): the file still holds the stamp.
        var map = new ColumnStateMap();
        assertFalse(map.onHollowed(P));
        assertTrue(map.persistentRemovalsForSave().contains(P));
        assertFalse(map.hasRetries(), "nothing to re-ask in memory");
    }

    @Test void aSessionSatisfiedStampIsDroppedWithoutARetryMark() {
        var map = new ColumnStateMap();
        map.onReceived(P, 4_000L);
        map.onNotGenerated(P); // keeps the real stamp, parks the position for the session
        assertEquals(ColumnStateMap.SATISFIED, map.classify(P), "premise");
        assertFalse(map.onHollowed(P));
        assertEquals(-1L, map.timestampFor(P), "no false claim reaches the file");
        assertTrue(map.persistentRemovalsForSave().contains(P));
        assertFalse(map.hasRetries(), "a retry mark could never be consumed while SATISFIED");
        assertEquals(ColumnStateMap.SATISFIED, map.classify(P));
    }

    @Test void aPendingClearResyncIsLeftAlone() {
        // The last delivery cleared the column to air, which is what Voxy holds now.
        var map = new ColumnStateMap();
        map.onReceived(P, 5_000L);
        map.onReceived(P, 6_000L);
        map.markAuthoritativeClear(P, 5_000L);
        assertFalse(map.onHollowed(P));
        assertEquals(6_000L, map.timestampFor(P));
    }
}
