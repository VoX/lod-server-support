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
        assertFalse(map.markDirtyIfKnown(POS), "dirty needs a recorded disposition; unknown (-1) is not one");
    }

    @Test
    void dirtyRescuesParkedNotGeneratedStamp() {
        // generation-disabled soak finding: a not-generated stamp parks the position, but a
        // dirty broadcast proves the server SAVED content there — the stamp is stale and the
        // position must become requestable again (ts=0 routes disk-first server-side).
        map.onNotGenerated(POS);
        assertEquals(SATISFIED, map.classify(POS, false), "parked while gen disabled");
        assertTrue(map.markDirtyIfKnown(POS), "dirty broadcast rescues the parked stamp");
        assertEquals(0L, map.classify(POS, false), "re-requestable with ts=0 (disk-first)");
        map.markSent(POS);
        map.onReceived(POS, 9000L);
        assertEquals(SATISFIED, map.classify(POS, false), "healed after the disk serve");
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

    // Contract change: retry marks used to survive onReceived deliberately, because their
    // only writer was a rate-limit bounce — a guarantee that no response was coming, so a
    // late receipt could only belong to an OLDER request and the retry still had to fire.
    // The timeout sweep (LodRequestManager.sweepTimeouts) broke that premise: it marks
    // retry for positions whose response can still arrive late, and an answer supersedes
    // the pending retry — keeping the mark re-requested every late-delivered column once
    // and reset ring confirmation for nothing.
    @Test
    void onReceivedClearsRetry() {
        map.onReceived(POS, 5000L);
        map.markRetry(POS);
        map.onReceived(POS, 6000L);
        assertEquals(SATISFIED, map.classify(POS, true),
                "a data answer supersedes the pending retry — no redundant re-request");
        assertFalse(map.hasRetries(), "lingering retry would pin confirmedRing to 0 for an extra scan");
    }

    @Test
    void onUpToDateClearsRetry() {
        map.onReceived(POS, 5000L);
        map.markRetry(POS);
        map.onUpToDate(POS);
        assertEquals(SATISFIED, map.classify(POS, true),
                "an up-to-date answer supersedes the pending retry exactly like data");
        assertFalse(map.hasRetries());
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

    @Test
    void onUpToDateResolvesNotGeneratedStamp() {
        map.onNotGenerated(POS);
        assertEquals(0L, map.classify(POS, true), "not-generated stamp retries as generation");
        map.onUpToDate(POS);
        assertEquals(SATISFIED, map.classify(POS, true),
                "server affirming up-to-date must end the generation retry loop (End void storm)");
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

    // ---- onIngestFailed ----

    @Test
    void ingestFailedForgetsReceivedStampAndMarksRetry() {
        map.onReceived(POS, 5000L);
        assertEquals(1, map.receivedCount());

        map.onIngestFailed(POS);

        assertEquals(-1L, map.timestampFor(POS), "stamp must be forgotten so the re-ask carries ts=-1");
        assertEquals(0, map.receivedCount(), "received count must not leak");
        assertTrue(map.hasRetries(), "retry mark forces the confirmed-ring reset");
        assertEquals(-1L, map.classify(POS, true), "position must re-request as unknown");
    }

    @Test
    void ingestFailedOnNotGeneratedStampKeepsEmptyCountConsistent() {
        map.onNotGenerated(POS);
        assertEquals(1, map.emptyCount());

        map.onIngestFailed(POS);

        assertEquals(0, map.emptyCount());
        assertEquals(-1L, map.timestampFor(POS));
    }

    @Test
    void ingestFailedOnAbsentPositionIsIgnored() {
        map.onIngestFailed(POS);

        assertEquals(0, map.receivedCount(), "no counter underflow for an unknown position");
        assertEquals(0, map.emptyCount());
        assertEquals(-1L, map.classify(POS, true));
        assertFalse(map.hasRetries(),
                "an unknown position must not gain a retry mark nothing can consume");
    }

    @Test
    void ingestFailureCapParksThePosition() {
        for (int i = 0; i < ColumnStateMap.MAX_INGEST_FAILURES; i++) {
            map.onReceived(POS, 5000L + i);
            map.onIngestFailed(POS);
            assertEquals(-1L, map.classify(POS, true), "failure " + (i + 1) + " must re-request");
        }
        map.onReceived(POS, 9000L);
        map.onIngestFailed(POS); // cap exceeded: park

        assertEquals(SATISFIED, map.classify(POS, true),
                "a permanently failing consumer must not drive an endless re-serve loop");
        assertFalse(map.hasRetries(), "parking must not leave an unconsumable retry mark");
        assertTrue(map.timestampFor(POS) > 0, "parked positions carry a satisfied epoch stamp");
        assertEquals(1, map.receivedCount(), "park bookkeeping must keep counts consistent");
    }

    @Test
    void ingestFailureCapResetsWithSessionState() {
        for (int i = 0; i <= ColumnStateMap.MAX_INGEST_FAILURES; i++) {
            map.onReceived(POS, 5000L);
            map.onIngestFailed(POS);
        }
        assertEquals(SATISFIED, map.classify(POS, true), "parked");

        map.clear(); // clearcache / dimension change / reconnect
        map.onReceived(POS, 6000L);
        map.onIngestFailed(POS);

        assertEquals(-1L, map.classify(POS, true), "the cap must reset with the session state");
    }

    @Test
    void ingestFailedClearsDirtyAndValidatedAndLifecycleResumes() {
        map.onReceived(POS, 5000L);
        map.markDirtyIfKnown(POS);

        map.onIngestFailed(POS);
        assertEquals(0, map.dirtyCount());

        // normal lifecycle resumes: re-request then receive again
        map.markSent(POS);
        map.onReceived(POS, 6000L);
        assertEquals(SATISFIED, map.classify(POS, true));
        assertEquals(1, map.receivedCount());
        assertFalse(map.hasRetries(),
                "a stuck retry mark would pin confirmedRing at 0 for the whole session");
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
