package dev.vox.lss.networking.client;

import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static dev.vox.lss.networking.client.ColumnStateMap.SATISFIED;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization tests for the per-column request-need ladder (the priority order
 * mirrors the original scanner ladder bit-for-bit: unknown &gt; generation &gt; dirty &gt;
 * rate-limit retry &gt; revalidation &gt; satisfied) and the state transitions.
 */
class ColumnStateMapTest {

    private static final long POS = PositionUtil.packPosition(10, -3);

    private ColumnStateMap map;

    @BeforeEach
    void setUp() {
        map = new ColumnStateMap();
    }

    // ---- classify ladder ----

    @Test
    void unknownPositionRequestsSyncOnLoad() {
        assertEquals(-1L, map.classify(POS, true));
        assertEquals(-1L, map.classify(POS, false), "unknown outranks generation availability");
    }

    @Test
    void notGeneratedRequestsGenerationOnlyWhenEnabled() {
        map.onNotGenerated(POS);
        assertEquals(0L, map.classify(POS, true));
        assertEquals(SATISFIED, map.classify(POS, false), "no gen retry when generation disabled");
    }

    @Test
    void receivedAndValidatedIsSatisfied() {
        map.onReceived(POS, 5000L);
        assertEquals(SATISFIED, map.classify(POS, true));
    }

    @Test
    void dirtyOutranksValidation() {
        map.onReceived(POS, 5000L);
        assertTrue(map.markDirtyIfKnown(POS));
        assertEquals(5000L, map.classify(POS, true), "dirty re-requests with the stored timestamp");
    }

    @Test
    void retryOutranksValidation() {
        map.onReceived(POS, 5000L);
        map.markRetry(POS);
        assertEquals(5000L, map.classify(POS, true));
    }

    @Test
    void cachedButNotValidatedRequestsResync() {
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 7000L);
        map.loadFrom(loaded);
        assertEquals(7000L, map.classify(POS, true), "cache-loaded position revalidates once per session");
    }

    @Test
    void dirtyOnUnknownPositionIsNotMarked() {
        assertFalse(map.markDirtyIfKnown(POS), "dirty only applies to known (>0) columns");
        map.onNotGenerated(POS);
        assertFalse(map.markDirtyIfKnown(POS), "not-generated (0) columns are not markable dirty");
    }

    // ---- transitions ----

    @Test
    void markSentConsumesDirtyAndRetry() {
        map.onReceived(POS, 5000L);
        map.markDirtyIfKnown(POS);
        map.markRetry(POS);
        map.markSent(POS);
        assertEquals(SATISFIED, map.classify(POS, true), "sent request consumed the pending marks");
    }

    @Test
    void onReceivedConsumesDirty() {
        map.onReceived(POS, 5000L);
        map.markDirtyIfKnown(POS);
        map.onReceived(POS, 6000L);
        assertEquals(SATISFIED, map.classify(POS, true),
                "data arriving while a dirty re-request is in flight consumes the dirty mark");
        assertEquals(0, map.dirtyCount());
    }

    @Test
    void onReceivedKeepsRetry() {
        map.onReceived(POS, 5000L);
        map.markRetry(POS);
        map.onReceived(POS, 6000L);
        assertEquals(6000L, map.classify(POS, true),
                "rate-limit retry mark deliberately survives onReceived (unlike markSent)");
    }

    @Test
    void onUpToDateStampsAbsentPositions() {
        map.onUpToDate(POS);
        assertEquals(SATISFIED, map.classify(POS, true),
                "empty column (never sent data) must not be re-requested every scan");
        assertEquals(1, map.receivedCount(), "stamped with a positive timestamp");
    }

    @Test
    void onUpToDateKeepsExistingTimestamp() {
        map.onReceived(POS, 5000L);
        map.onUpToDate(POS);
        assertEquals(1, map.receivedCount());
    }

    // ---- derived counts ----

    @Test
    void countsTrackTransitions() {
        map.onReceived(POS, 5000L);
        assertEquals(1, map.receivedCount());
        assertEquals(0, map.emptyCount());

        map.onNotGenerated(POS); // received -> empty
        assertEquals(0, map.receivedCount());
        assertEquals(1, map.emptyCount());

        map.onReceived(POS, 6000L); // empty -> received
        assertEquals(1, map.receivedCount());
        assertEquals(0, map.emptyCount());
    }

    @Test
    void loadFromRecounts() {
        var loaded = new Long2LongOpenHashMap();
        loaded.put(PositionUtil.packPosition(1, 1), 100L);
        loaded.put(PositionUtil.packPosition(2, 2), 0L);
        loaded.put(PositionUtil.packPosition(3, 3), 200L);
        map.loadFrom(loaded);
        assertEquals(2, map.receivedCount());
        assertEquals(1, map.emptyCount());
    }

    @Test
    void pruneOutOfRangeDropsAllStateAndCounts() {
        long near = PositionUtil.packPosition(1, 1);
        long far = PositionUtil.packPosition(500, 500);
        map.onReceived(near, 100L);
        map.onReceived(far, 200L);
        map.markDirtyIfKnown(far);
        map.markRetry(far);

        map.pruneOutOfRange(0, 0, 64);

        assertEquals(1, map.receivedCount());
        assertEquals(SATISFIED, map.classify(near, true));
        assertEquals(-1L, map.classify(far, true), "pruned position is unknown again");
        assertEquals(0, map.dirtyCount());
        assertFalse(map.hasRetries());
    }

    @Test
    void clearResetsEverything() {
        map.onReceived(POS, 100L);
        map.markRetry(POS);
        map.clear();
        assertEquals(0, map.receivedCount());
        assertEquals(0, map.emptyCount());
        assertFalse(map.hasRetries());
        assertEquals(-1L, map.classify(POS, true));
        assertTrue(map.isEmptyMap());
    }
}
