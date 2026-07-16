package dev.vox.lss.networking.client;

import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static dev.vox.lss.networking.client.ColumnStateMap.SATISFIED;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization tests for the per-column request-need ladder (dirty &gt; session-satisfied
 * &gt; unknown &gt; generation &gt; ingest-failure retry &gt; revalidation &gt; satisfied) and
 * the state transitions. The retry rung is named for its only surviving writer: v17 retired
 * the rate-limit bounce that originally wrote it.
 */
class ColumnStateMapTest {

    private static final long POS = PositionUtil.packPosition(10, -3);

    private ColumnStateMap map;

    @BeforeAll
    static void setup() {
        // Only the ColumnCacheStore composite (CL-032) touches MC types; bootstrap is
        // idempotent and matches the ColumnCacheStoreTest sibling.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        map = new ColumnStateMap();
    }

    /** Drives a position to the parked state: MAX_INGEST_FAILURES + 1 failed deliveries. */
    private void parkViaIngestFailures(long packed) {
        for (int i = 0; i <= ColumnStateMap.MAX_INGEST_FAILURES; i++) {
            map.onReceived(packed, 5000L + i);
            map.onIngestFailed(packed);
        }
        assertEquals(SATISFIED, map.classify(packed), "precondition: parked");
    }

    // ---- classify ladder ----

    @Test
    void unknownPositionRequestsSyncOnLoad() {
        assertEquals(-1L, map.classify(POS));
        assertEquals(-1L, map.classify(POS), "unknown outranks generation availability");
    }

    @Test
    void notGeneratedIsPermanentSessionSatisfy() {
        map.onNotGenerated(POS);
        assertEquals(SATISFIED, map.classify(POS),
                "NOT_GENERATED parks the position for the whole session — no client gen retry exists");
        assertTrue(map.isSessionSatisfied(POS));
        assertEquals(-1L, map.timestampFor(POS), "no 0-stamp is written — the mark is the state");

        // The ONE revival path: a dirty broadcast (the server saved content there).
        assertTrue(map.markDirtyIfKnown(POS), "dirty fires on the session-satisfied mark");
        assertFalse(map.isSessionSatisfied(POS), "the dirty un-parks it");
        assertEquals(-1L, map.classify(POS), "revived with -1 — the client holds nothing");
    }

    @Test
    void receivedAndValidatedIsSatisfied() {
        map.onReceived(POS, 5000L);
        assertEquals(SATISFIED, map.classify(POS));
    }

    // ---- sessionSatisfied: satisfied-without-a-stamp, dirty always wins (delivery-honesty) ----

    @Test
    void sessionSatisfiedClassifiesSatisfiedButDirtyStillWins() {
        map.markSessionSatisfied(POS);
        assertEquals(SATISFIED, map.classify(POS), "a session-satisfied position needs no request");
        assertEquals(-1L, map.timestampFor(POS), "no timestamp is fabricated for a satisfied position");

        // A dirty broadcast must outrank sessionSatisfied so an air->content edit re-requests.
        assertTrue(map.markDirtyIfKnown(POS), "dirty fires for a session-satisfied position");
        assertFalse(map.isSessionSatisfied(POS), "the dirty un-parks it");
        assertEquals(-1L, map.classify(POS), "dirty outranks sessionSatisfied — re-request as first serve");
    }

    @Test
    void staleInFlightRecordsAndResolvesOnce() {
        map.noteStaleIfInFlight(POS, true);     // dirty crossed the in-flight first serve
        assertTrue(map.resolveStale(POS), "a crossed-dirty position resolves stale exactly once");
        assertFalse(map.resolveStale(POS), "the mark is consumed");

        long other = PositionUtil.packPosition(9, 9);
        map.noteStaleIfInFlight(other, false);  // not in flight -> no mark (dirty handled normally)
        assertFalse(map.resolveStale(other));
    }

    @Test
    void sessionSatisfiedIsClearedAndDistancePruned() {
        long near = PositionUtil.packPosition(0, 0);
        long far = PositionUtil.packPosition(1000, 1000);
        map.markSessionSatisfied(near);
        map.markSessionSatisfied(far);

        map.pruneOutOfRange(0, 0, 32);
        assertTrue(map.isSessionSatisfied(near), "in-range stays");
        assertFalse(map.isSessionSatisfied(far), "out-of-range pruned so the set cannot grow unbounded");

        map.clear();
        assertFalse(map.isSessionSatisfied(near), "clear (reconnect/dimension change) empties it");
    }

    @Test
    void dirtyOutranksValidation() {
        map.onReceived(POS, 5000L);
        assertTrue(map.markDirtyIfKnown(POS));
        assertEquals(5000L, map.classify(POS), "dirty re-requests with the stored timestamp");
    }

    @Test
    void retryOutranksValidation() {
        map.onReceived(POS, 5000L);
        map.markRetry(POS);
        assertEquals(5000L, map.classify(POS));
    }

    @Test
    void cachedButNotValidatedRequestsResync() {
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 7000L);
        map.loadFrom(loaded);
        assertEquals(7000L, map.classify(POS), "cache-loaded position revalidates once per session");
    }

    @Test
    void dirtyOnUnknownPositionIsNotMarked() {
        assertFalse(map.markDirtyIfKnown(POS), "dirty needs a recorded disposition; unknown (-1) is not one");
    }

    @Test
    void dirtyRescuesParkedNotGeneratedPosition() {
        // generation-disabled soak finding, inverted for server-owned generation: a
        // NOT_GENERATED answer parks the position permanently (sessionSatisfied, no stamp),
        // and a dirty broadcast proves the server SAVED content there — the ONE revival.
        map.onNotGenerated(POS);
        assertEquals(SATISFIED, map.classify(POS), "parked for the session");
        assertTrue(map.markDirtyIfKnown(POS), "dirty broadcast rescues the parked position");
        assertEquals(-1L, map.classify(POS), "re-requestable as a first serve (disk-first)");
        map.onReceived(POS, 9000L);
        assertEquals(SATISFIED, map.classify(POS), "healed after the disk serve");
    }

    // ---- transitions ----

    // markSentConsumesDirtyAndRetry is DELETED with markSent itself. Its exact premise — a SEND
    // consumes the dirty/retry marks — is the bug the want-set had to remove: under re-declaration
    // a mark consumed at send classifies the position SATISFIED while its answer is still
    // outstanding, so a server-side supersession loses the edit for the whole session. The marks'
    // consumption is now pinned on the ANSWERS that replaced it (onReceivedConsumesDirty below,
    // and the onUpToDate / onNotGenerated answer-time tests).

    @Test
    void onReceivedConsumesDirty() {
        map.onReceived(POS, 5000L);
        map.markDirtyIfKnown(POS);
        map.onReceived(POS, 6000L);
        assertEquals(SATISFIED, map.classify(POS),
                "data arriving while a dirty re-request is in flight consumes the dirty mark");
        assertEquals(0, map.dirtyCount());
    }

    // Contract history: retry marks used to survive onReceived deliberately, because their only
    // writer was a rate-limit bounce — a guarantee that no response was coming, so a late receipt
    // could only belong to an OLDER request and the retry still had to fire. The timeout sweep
    // broke that premise (it retry-marked positions whose response could still arrive late), and
    // an answer must supersede the pending retry or every late-delivered column re-requests once
    // and resets ring confirmation for nothing. The want-set deleted BOTH original writers — the
    // bounce and the sweep — leaving onIngestFailed as the only one. The conclusion is unchanged
    // and now uniform: an answer is authoritative for the position, so it clears the retry.
    @Test
    void onReceivedClearsRetry() {
        map.onReceived(POS, 5000L);
        map.markRetry(POS);
        map.onReceived(POS, 6000L);
        assertEquals(SATISFIED, map.classify(POS),
                "a data answer supersedes the pending retry — no redundant re-request");
        assertFalse(map.hasRetries(), "lingering retry would pin confirmedRing to 0 for an extra scan");
    }

    @Test
    void onUpToDateClearsRetry() {
        map.onReceived(POS, 5000L);
        map.markRetry(POS);
        map.onUpToDate(POS);
        assertEquals(SATISFIED, map.classify(POS),
                "an up-to-date answer supersedes the pending retry exactly like data");
        assertFalse(map.hasRetries());
    }

    @Test
    void onUpToDateSatisfiesAbsentPositionsWithoutFabricatingAStamp() {
        map.onUpToDate(POS);
        assertEquals(SATISFIED, map.classify(POS),
                "empty column (never sent data) must not be re-requested every scan");
        assertEquals(0, map.receivedCount(),
                "no client-clock timestamp is fabricated — the position is session-satisfied");
        assertEquals(-1L, map.timestampFor(POS), "timestamps stays honest (unknown)");
        assertTrue(map.isSessionSatisfied(POS));
    }

    @Test
    void onUpToDateKeepsExistingTimestamp() {
        map.onReceived(POS, 5000L);
        map.onUpToDate(POS);
        assertEquals(1, map.receivedCount());
    }

    @Test
    void onUpToDateAfterNotGeneratedStaysSatisfied() {
        map.onNotGenerated(POS);
        assertEquals(SATISFIED, map.classify(POS), "parked by the NOT_GENERATED answer");
        map.onUpToDate(POS); // late/duplicate answer for the same position
        assertEquals(SATISFIED, map.classify(POS),
                "a crossing up-to-date answer must not un-park the position");
        assertTrue(map.isSessionSatisfied(POS), "still satisfied without a fabricated stamp");
    }

    // ---- derived counts ----

    @Test
    void countsTrackTransitions() {
        map.onReceived(POS, 5000L);
        assertEquals(1, map.receivedCount());
        assertEquals(0, map.emptyCount());

        map.onNotGenerated(POS); // stamps are untouched: the mark is the state now
        assertEquals(1, map.receivedCount(),
                "NOT_GENERATED keeps the stale-but-real stamp (no received->empty transition)");
        assertEquals(0, map.emptyCount());
        assertTrue(map.isSessionSatisfied(POS));

        map.onReceived(POS, 6000L); // real data supersedes the session-satisfied mark
        assertEquals(1, map.receivedCount());
        assertEquals(0, map.emptyCount());
        assertFalse(map.isSessionSatisfied(POS));
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
        assertEquals(SATISFIED, map.classify(near));
        assertEquals(-1L, map.classify(far), "pruned position is unknown again");
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
        assertEquals(-1L, map.classify(POS), "position must re-request as unknown");
    }

    @Test
    void ingestFailedOnLegacyZeroStampKeepsEmptyCountConsistent() {
        // A legacy 0-stamp can only arrive via a pre-server-owned-generation cache load;
        // onNotGenerated no longer writes one. The count bookkeeping must still hold.
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 0L);
        map.loadFrom(loaded);
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
        assertEquals(-1L, map.classify(POS));
        assertFalse(map.hasRetries(),
                "an unknown position must not gain a retry mark nothing can consume");
    }

    @Test
    void ingestFailureCapParksThePosition() {
        for (int i = 0; i < ColumnStateMap.MAX_INGEST_FAILURES; i++) {
            map.onReceived(POS, 5000L + i);
            map.onIngestFailed(POS);
            assertEquals(-1L, map.classify(POS), "failure " + (i + 1) + " must re-request");
        }
        map.onReceived(POS, 9000L);
        map.onIngestFailed(POS); // cap exceeded: park

        assertEquals(SATISFIED, map.classify(POS),
                "a permanently failing consumer must not drive an endless re-serve loop");
        assertFalse(map.hasRetries(), "parking must not leave an unconsumable retry mark");
        assertEquals(-1L, map.timestampFor(POS),
                "park drops to unknown — no fabricated or retained >0 stamp to lie next session");
        assertTrue(map.isSessionSatisfied(POS), "parked via session-satisfied, not a timestamp");
        assertEquals(0, map.receivedCount(), "park bookkeeping keeps counts consistent (no stamp held)");
    }

    @Test
    void ingestFailureCapResetsWithSessionState() {
        for (int i = 0; i <= ColumnStateMap.MAX_INGEST_FAILURES; i++) {
            map.onReceived(POS, 5000L);
            map.onIngestFailed(POS);
        }
        assertEquals(SATISFIED, map.classify(POS), "parked");

        map.clear(); // clearcache / dimension change / reconnect
        map.onReceived(POS, 6000L);
        map.onIngestFailed(POS);

        assertEquals(-1L, map.classify(POS), "the cap must reset with the session state");
    }

    @Test
    void ingestFailedClearsDirtyAndValidatedAndLifecycleResumes() {
        map.onReceived(POS, 5000L);
        map.markDirtyIfKnown(POS);

        map.onIngestFailed(POS);
        assertEquals(0, map.dirtyCount());

        // normal lifecycle resumes: re-request then receive again
        map.onReceived(POS, 6000L);
        assertEquals(SATISFIED, map.classify(POS));
        assertEquals(1, map.receivedCount());
        assertFalse(map.hasRetries(),
                "a stuck retry mark would pin confirmedRing at 0 for the whole session");
    }

    // ---- rejected authoritative clear self-heals (WS3 completion, review #2/#3) ----

    @Test
    void rejectedClearReRequestsWithPreClearStampNotMinusOne() {
        // Client held content at T_content=3000, then the server sent a 0-section clearing column
        // at T_clear=5000 (content->air). The consumer rejected it. The re-request MUST carry the
        // pre-clear stamp (3000, a real server-issued value < the server's cached clear stamp) so
        // the up_to_date check fails and the server re-sends the clear. A ts=-1 re-request would
        // instead draw an all-air up_to_date (the clear is only sent for claimsData/ts>0), leaving
        // ghost terrain stranded for the whole session.
        map.onReceived(POS, 3000L);              // pre-clear content
        map.onReceived(POS, 5000L);              // the clearing column overwrites the stamp
        map.markAuthoritativeClear(POS, 3000L);  // networking flags this delivery as a 0-section clear

        map.onIngestFailed(POS);                 // consumer rejects the clear

        assertEquals(3000L, map.classify(POS),
                "a rejected clear re-requests with the pre-clear content stamp, not ts=-1");
        assertTrue(map.hasRetries(), "retry mark forces the confirmed-ring reset so it is rescanned");
    }

    @Test
    void contentSupersedesAPendingClearFlag() {
        map.onReceived(POS, 5000L);
        map.markAuthoritativeClear(POS, 3000L);
        map.onReceived(POS, 7000L);   // real content arrives AFTER the clear flag — supersedes it

        map.onIngestFailed(POS);      // now this is a plain content rejection

        assertEquals(-1L, map.classify(POS),
                "once real content supersedes the clear flag, a rejection re-requests as content (ts=-1)");
    }

    @Test
    void repeatedlyRejectedClearParksAtCap() {
        for (int i = 0; i < ColumnStateMap.MAX_INGEST_FAILURES; i++) {
            map.onReceived(POS, 5000L + i);          // server re-sends the clear
            map.markAuthoritativeClear(POS, 3000L);
            map.onIngestFailed(POS);
            assertEquals(3000L, map.classify(POS),
                    "clear failure " + (i + 1) + " re-requests with the pre-clear stamp");
        }
        map.onReceived(POS, 9000L);
        map.markAuthoritativeClear(POS, 3000L);
        map.onIngestFailed(POS); // cap exceeded

        assertEquals(SATISFIED, map.classify(POS),
                "a permanently rejected clear must not drive an endless re-clear loop");
        assertTrue(map.isSessionSatisfied(POS), "parked via session-satisfied");
        assertEquals(3000L, map.timestampFor(POS),
                "parking a lost clear RETAINS the pre-clear stamp: the consumer still holds "
                        + "the pre-clear content, and a -1 park would draw an all-air "
                        + "up_to_date next session (clears are only sent for ts>0) — the "
                        + "ghost terrain would be permanent instead of one-session");
        assertEquals(1, map.receivedCount(),
                "the park swaps one >0 stamp for another — counts net zero");
    }

    /**
     * The park-time twin of {@link #cacheLoadedLegacyZeroStampDeclaresAsNoData}: a legacy
     * 0-stamp that draws up_to_date parks session-satisfied, and the 0 itself must be
     * PURGED — left in timestamps it persists to the cache file and resurrects every
     * session, immortal. Purged, the position becomes -1/unknown, which declares the same
     * thing on the wire (&le;0 = "I hold nothing") but finally lets the artifact die.
     */
    @Test
    void upToDateOnLegacyZeroStampPurgesItAtPark() {
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 0L);
        map.loadFrom(loaded);
        assertEquals(1, map.emptyCount(), "premise: the legacy stamp loaded as an empty");

        map.onUpToDate(POS);

        assertEquals(SATISFIED, map.classify(POS), "parked for the session as before");
        assertTrue(map.isSessionSatisfied(POS));
        assertEquals(0, map.emptyCount(), "the 0-stamp is purged, not parked around");
        assertEquals(-1L, map.timestampFor(POS),
                "nothing persists to the cache — next session starts clean at unknown");
    }

    @Test
    void clearResetsEverything() {
        map.onReceived(POS, 100L);
        map.markRetry(POS);
        map.markAuthoritativeClear(POS, 50L);
        map.clear();
        assertEquals(0, map.receivedCount());
        assertEquals(0, map.emptyCount());
        assertFalse(map.hasRetries());
        assertEquals(-1L, map.classify(POS));
        assertTrue(map.isEmptyMap());
    }

    // ---- cache-loaded stamps (CL-027, CL-028) ----

    @Test
    void cacheLoadedLegacyZeroStampDeclaresAsNoData() {
        // Released clients (pre-server-owned-generation) persisted 0-stamps for
        // NOT_GENERATED answers. Loaded today they mean "no data": declare -1 — a
        // 0-stamp silently classifying SATISFIED would seal a permanent hole (R5).
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 0L);
        map.loadFrom(loaded);

        assertEquals(1, map.emptyCount(), "a cache-loaded legacy 0 still counts as an empty stamp");
        assertEquals(-1L, map.classify(POS),
                "next session re-declares a legacy 0-stamp as no-data (the client never emits 0)");
    }

    /**
     * CL-028 fix: loadFrom clamps a corrupt cache value below -1 (e.g. negative garbage
     * surviving the v2 migration, see ColumnCacheStoreTest#v2MigrationSignExtendsNegativeValues)
     * to the -1 "unknown" sentinel. Without the clamp such a value matched no classify rung
     * and parked the position SATISFIED for the rest of the session; clamped, it re-requests
     * on the next scan and the next save rewrites it clean.
     */
    @Test
    void subMinusOneCacheValueClampsToUnknownAndReRequests() {
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, -4L);
        map.loadFrom(loaded);

        assertEquals(0, map.receivedCount(), "a sub--1 value must not count as received");
        assertEquals(0, map.emptyCount(), "...nor as a not-generated stamp");
        assertEquals(-1L, map.classify(POS),
                "a corrupt negative stamp clamps to unknown and re-requests (no silent SATISFIED park)");

        // It is now -1 (unknown); the scan ladder requests -1 positions anyway, so the dirty
        // rescue (which only fires for a KNOWN disposition) correctly does not apply.
        assertFalse(map.markDirtyIfKnown(POS), "a clamped-to-unknown stamp is not a known disposition");
        map.onReceived(POS, 8000L);
        assertEquals(SATISFIED, map.classify(POS));
        assertEquals(1, map.receivedCount(), "the heal restores consistent counts");
    }

    // ---- ingest-failure cap accounting (CL-029, CL-031, CL-032, CL-033) ----

    /**
     * CL-029 decision pin — failures are counted PER DELIVERY, not per consumer report:
     * the first report of a delivery unstamps the position (timestamps.remove), so every
     * sibling report from other consumers rejecting the SAME delivery hits the
     * absent-position guard in onIngestFailed and is absorbed. N failing consumers
     * therefore cost one cap increment per serve, and the park trips on the
     * (MAX_INGEST_FAILURES + 1)-th failed delivery regardless of consumer count.
     */
    @Test
    void ingestFailureCapCountsDeliveriesNotConsumerReports() {
        for (int delivery = 1; delivery <= 2; delivery++) {
            map.onReceived(POS, 5000L + delivery);
            map.onIngestFailed(POS);
            map.onIngestFailed(POS); // sibling consumer rejecting the same delivery — absorbed
            assertEquals(-1L, map.classify(POS), "delivery " + delivery
                    + " still re-requests — per-report counting would already park here");
        }
        map.onReceived(POS, 5003L);
        map.onIngestFailed(POS);
        assertEquals(-1L, map.classify(POS), "third failed delivery still re-requests");
        map.onReceived(POS, 5004L);
        map.onIngestFailed(POS);
        assertEquals(SATISFIED, map.classify(POS),
                "park trips at the 4th failed delivery (MAX_INGEST_FAILURES + 1), independent of consumer count");
    }

    @Test
    void dirtyUnparksAnIngestParkedPositionSoItReRequests() {
        parkViaIngestFailures(POS);
        assertEquals(-1L, map.timestampFor(POS), "park holds no fabricated stamp");

        // A dirty broadcast (content changed there) must un-park it so it re-requests as a
        // first serve — not stay SATISFIED forever (the air->content permanent hole).
        assertTrue(map.markDirtyIfKnown(POS), "dirty reaches a session-satisfied parked position");
        assertFalse(map.isSessionSatisfied(POS), "un-parked");
        assertEquals(-1L, map.classify(POS), "re-requests as a first serve (no fabricated stamp)");

        // The re-served content resolves it honestly with the real server stamp.
        map.onReceived(POS, 9000L);
        assertEquals(SATISFIED, map.classify(POS), "resolved with the server stamp");
        assertEquals(9000L, map.timestampFor(POS));
        assertFalse(map.hasRetries(), "no unconsumable retry mark left behind");
    }

    @Test
    void ingestParkDoesNotPersistAFalseStampAndReAsksNextSession() {
        var dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse("lss_test:park_persist"));
        final String server = "test-park-persist";
        try {
            parkViaIngestFailures(POS);
            assertEquals(-1L, map.timestampFor(POS), "park holds no stamp");
            assertFalse(map.mapForSave().containsKey(POS),
                    "a parked position must not persist a fabricated stamp (the permanent-hole bug)");

            ColumnCacheStore.save(server, dim, map.mapForSave()); // the disconnect saveCache path

            var next = new ColumnStateMap(); // fresh session
            next.loadFrom(ColumnCacheStore.load(server, dim));
            assertEquals(-1L, next.timestampFor(POS), "next session holds nothing for the parked position");
            assertEquals(-1L, next.classify(POS),
                    "next session re-asks honestly (-1), never a false up-to-date");

            // The re-serve gives the consumer a fresh chance; a transient failure heals.
            next.onReceived(POS, 6000L);
            assertEquals(SATISFIED, next.classify(POS), "transient failure heals next session");
        } finally {
            ColumnCacheStore.clearForServer(server);
        }
    }

    @Test
    void pruneResetsIngestFailureCounts() {
        for (int i = 0; i < ColumnStateMap.MAX_INGEST_FAILURES; i++) {
            map.onReceived(POS, 5000L + i);
            map.onIngestFailed(POS);
        }
        // One more failure would park; the player walks away and back instead.
        map.pruneOutOfRange(1000, 1000, 4); // POS=(10,-3) is far out of range
        map.onReceived(POS, 9000L);         // returned: served again
        map.onIngestFailed(POS);

        assertEquals(-1L, map.classify(POS),
                "a pruned-and-returned position gets a fresh failure cap — a stale count would park here");
    }

    // ---- classify ladder: dirty on a legacy 0-stamp (CL-034, inverted) ----

    @Test
    void legacyZeroStampWithDirtyDeclaresAsNoData() {
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 0L); // legacy cache stamp — the client never writes 0 anymore
        map.loadFrom(loaded);
        assertTrue(map.markDirtyIfKnown(POS), "a dirty broadcast lands on the legacy stamp");
        assertEquals(-1L, map.classify(POS),
                "the dirty arm normalizes a legacy 0 to -1 (no data) — never a >0 claim, never a 0");
    }

    // ---- response × prior-state matrix, unpinned cells (CL-035) ----

    @Test
    void retryMarkAcrossDirtyZeroAndParkedPriorStates() {
        // retry × dirty: both marks coexist; one stored-ts request collapses them, and the
        // request's ANSWER consumes both (pre-want-set the SEND did — see markSentConsumes-
        // DirtyAndRetry's deletion note above; the collapse invariant is unchanged).
        long dirtyPos = PositionUtil.packPosition(1, 1);
        map.onReceived(dirtyPos, 5000L);
        map.markDirtyIfKnown(dirtyPos);
        map.markRetry(dirtyPos);
        assertEquals(5000L, map.classify(dirtyPos), "dirty+retry collapse into one stored-ts request");
        map.onUpToDate(dirtyPos); // the terminal answer: server says the held content is current
        assertEquals(SATISFIED, map.classify(dirtyPos), "one answer consumes both marks");

        // retry × not-generated: the session-satisfied mark outranks a stray retry mark —
        // a NOT_GENERATED position is parked for the session, and no mark resurrects it.
        long genPos = PositionUtil.packPosition(2, 2);
        map.onNotGenerated(genPos);
        map.markRetry(genPos);
        assertEquals(SATISFIED, map.classify(genPos),
                "sessionSatisfied outranks a retry mark on a NOT_GENERATED position");

        // retry × parked: a parked position is session-satisfied (never declared), so a stray
        // retry mark cannot resurrect it — it heals via dirty or next session.
        long parked = PositionUtil.packPosition(3, 3);
        parkViaIngestFailures(parked);
        map.markRetry(parked);
        assertEquals(SATISFIED, map.classify(parked),
                "sessionSatisfied outranks a spurious retry mark on a parked position");
    }

    @Test
    void notGeneratedAnswerAcrossDirtyRetryAndParkedPriorStates() {
        // Marks are consumed by ANSWERS, not by sends (want-set model — markSent is gone; under
        // re-declaration a send-time consumption would classify SATISFIED while the answer was
        // still in flight, losing the edit to any server-side supersession).
        //
        // A dirty that CROSSED the in-flight ask is not lost by this: LodRequestManager records
        // every crossing in staleInFlight (onDirtyColumns calls noteStaleIfInFlight regardless of
        // markDirtyIfKnown's result, covering stored>0 as well as stored==-1) and re-marks it at
        // the terminal outcome via consumeStaleCrossing. That protection belongs at the manager
        // layer, which is the layer that knows what is in flight — the map deliberately does not.
        //
        // notGenerated × dirty: the answer consumes the mark and parks the position;
        // the stale-but-real stamp survives untouched.
        long dirtyPos = PositionUtil.packPosition(1, 1);
        map.onReceived(dirtyPos, 5000L);
        map.markDirtyIfKnown(dirtyPos);
        map.onNotGenerated(dirtyPos);
        assertEquals(0, map.dirtyCount(), "a not-generated answer consumes the dirty mark");
        assertEquals(SATISFIED, map.classify(dirtyPos),
                "the answered position parks for the session — a surviving mark would re-declare it forever");
        assertEquals(5000L, map.timestampFor(dirtyPos), "the stale-but-real stamp is kept, never zeroed");

        // notGenerated × retry: the pending retry is consumed too.
        long retryPos = PositionUtil.packPosition(2, 2);
        map.onReceived(retryPos, 6000L);
        map.markRetry(retryPos);
        map.onNotGenerated(retryPos);
        assertFalse(map.hasRetries(), "a not-generated answer consumes the pending retry");
        assertEquals(SATISFIED, map.classify(retryPos), "the consumed retry leaves the position parked");

        // notGenerated × parked: already session-satisfied — the answer is a no-op that
        // keeps it parked (no stamp is written, no state is re-opened).
        long parked = PositionUtil.packPosition(3, 3);
        parkViaIngestFailures(parked);
        map.onNotGenerated(parked);
        assertEquals(SATISFIED, map.classify(parked), "a parked position stays parked");
        assertEquals(0, map.emptyCount(), "no 0-stamps are ever written — counts consistent");
        assertEquals(2, map.receivedCount(), "the two kept stale stamps still count as received");
    }

    @Test
    void lateDataOnParkedPositionRefreshesStampAndStaysSatisfied() {
        parkViaIngestFailures(POS);
        long parkStamp = map.timestampFor(POS);

        map.onReceived(POS, parkStamp + 777); // a late response lands on the parked position

        assertEquals(parkStamp + 777, map.timestampFor(POS), "late data refreshes the parked stamp");
        assertEquals(SATISFIED, map.classify(POS), "still satisfied — no re-request storm");
        assertFalse(map.hasRetries(), "no retry resurrection");
        assertEquals(1, map.receivedCount());

        map.onIngestFailed(POS);
        assertEquals(SATISFIED, map.classify(POS),
                "the surviving failure count re-parks immediately on the next rejection");
    }

    // ---- Answer-time mark consumption (want-set groundwork) ----
    // Under want-set re-declaration the client re-declares every unsatisfied position each scan
    // and markSent() is gone, so the terminal ANSWER is the only thing that may consume a
    // dirty/retry mark. A mark consumed at send would classify SATISFIED while the answer is
    // still in flight — and if the server superseded that ask, the edit is lost until next session.

    @Test
    void upToDateAnswerConsumesDirtyAndRetryMarks() {
        var map = new ColumnStateMap();
        long pos = PositionUtil.packPosition(3, 4);
        map.onReceived(pos, 100L);
        map.markDirtyIfKnown(pos);
        map.markRetry(pos);
        map.onUpToDate(pos);
        assertEquals(SATISFIED, map.classify(pos),
                "an up-to-date answer must consume the dirty and retry marks — under "
                + "re-declaration nothing else ever consumes them");
    }

    @Test
    void notGeneratedAnswerConsumesDirtyAndRetryMarksAndParks() {
        var map = new ColumnStateMap();
        long pos = PositionUtil.packPosition(5, 6);
        map.onReceived(pos, 100L);
        map.markDirtyIfKnown(pos);
        map.markRetry(pos);
        map.onNotGenerated(pos);
        assertEquals(SATISFIED, map.classify(pos),
                "the answer consumes the dirty and retry marks and parks the position for the "
                + "session — a surviving mark would re-declare it forever");
        assertFalse(map.hasRetries());
        assertEquals(0, map.dirtyCount());
    }
}
