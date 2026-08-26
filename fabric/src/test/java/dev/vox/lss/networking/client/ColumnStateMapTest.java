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

    // ---- stamped-up_to_date ratchet (stamped-up-to-date-plan.md §4) ----

    @Test
    void ratchetAdvancesOnlyForwardOnStampedMarkFreePositions() {
        map.onReceived(POS, 5000L);
        map.onUpToDate(POS);
        assertFalse(map.ratchetStamp(POS, 4000L), "backward never");
        assertFalse(map.ratchetStamp(POS, 5000L), "equal never");
        assertTrue(map.ratchetStamp(POS, 7000L), "forward advances");
        assertFalse(map.ratchetStamp(POS, 7000L), "monotonic — idempotent replays no-op");
        assertEquals(SATISFIED, map.classify(POS), "validated state untouched by the ratchet");
        map.markDirtyIfKnown(POS);
        assertEquals(7000L, map.classify(POS),
                "the dirty re-declare carries the RATCHETED stamp — what the next "
                        + "want-set (and the cache save) persists");
    }

    @Test
    void ratchetSkipsUnstampedAndMarkedPositions() {
        int leaves = map.leafCountForTest();
        assertFalse(map.ratchetStamp(POS, 7000L), "unknown position: nothing to extend");
        assertEquals(leaves, map.leafCountForTest(), "hostile frames must not allocate");

        map.onReceived(POS, 5000L);
        map.markDirtyIfKnown(POS);
        assertFalse(map.ratchetStamp(POS, 7000L), "dirty-marked: the mark outranks");
        assertEquals(5000L, map.classify(POS), "stamp untouched under a dirty mark");

        long pos2 = PositionUtil.packPosition(11, -3);
        map.onReceived(pos2, 5000L);
        map.markRetry(pos2);
        assertFalse(map.ratchetStamp(pos2, 7000L), "retry-marked: defense-in-depth skip");

        // sessionSatisfied (3-Opus fold — the applyTileValidation exclusion mirrored):
        // the lost-CLEAR park retains a positive pre-clear stamp under
        // sessionSatisfied; ratcheting it past the server's cached clear stamp would
        // be the F2 ghost seal.
        long pos3 = PositionUtil.packPosition(12, -3);
        map.onReceived(pos3, 5000L);
        map.markSessionSatisfied(pos3);
        assertFalse(map.ratchetStamp(pos3, 7000L), "sessionSatisfied never ratchets");
    }

    @Test
    void ratchetedStampHealsTheTileCompare() {
        // The Tier-1 heal chain (plan §9.9's mechanism half): stale -> stamped -> clean.
        // Seeded via loadFrom — the REJOIN shape: a cache-loaded stamp is a
        // revalidation need, NOT a per-column server proof (onReceived would set
        // `validated`, and server-proofed conflicts are deliberately not residue).
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 5000L);
        map.loadFrom(loaded);
        int tileX = PositionUtil.unpackX(POS) >> 5, tileZ = PositionUtil.unpackZ(POS) >> 5;
        var stale = map.applyTileValidation(tileX, tileZ, 6000L);
        assertFalse(stale.fullyValidated(), "5000 <= 6000: the serve-then-save shape");
        assertTrue(map.ratchetStamp(POS, 7000L), "the verification stamp arrives");
        var healed = map.applyTileValidation(tileX, tileZ, 6000L);
        assertTrue(healed.fullyValidated(), "7000 > 6000: the same frame now validates");
        assertEquals(SATISFIED, map.classify(POS));
    }

    @Test
    void ratchetedStampSurvivesTheSessionRoundTrip() {
        // The one cross-session link (plan §9.8): the ratcheted ts IS the ts the cache
        // persists and the next session re-declares — pin it through the same
        // loadFrom shape ColumnCacheStore round-trips. Seeded via loadFrom (the
        // rejoin shape — see ratchetedStampHealsTheTileCompare).
        var seed = new Long2LongOpenHashMap();
        seed.put(POS, 5000L);
        map.loadFrom(seed);
        assertTrue(map.ratchetStamp(POS, 7000L));
        assertEquals(7000L, map.classify(POS), "the re-declaration carries the ratchet");

        var nextSession = new ColumnStateMap();
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 7000L);
        nextSession.loadFrom(loaded);
        int tileX = PositionUtil.unpackX(POS) >> 5, tileZ = PositionUtil.unpackZ(POS) >> 5;
        assertTrue(nextSession.applyTileValidation(tileX, tileZ, 6000L).fullyValidated(),
                "the next session's frame validates off the carried ratchet");
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
    void notGeneratedOnLegacyZeroStampPurgesItAtPark() {
        // The gen-disabled twin of the onUpToDate purge below: on such servers a legacy
        // 0-stamp position parks via NOT_GENERATED every session, and before this purge the
        // 0 persisted to the cache immortally (onUpToDate never fires for it there).
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 0L);
        map.loadFrom(loaded);
        assertEquals(1, map.emptyCount(), "premise: the legacy stamp loaded as an empty");

        map.onNotGenerated(POS);

        assertEquals(SATISFIED, map.classify(POS), "parked for the session as before");
        assertEquals(0, map.emptyCount(), "the 0-stamp is purged, not parked around");
        assertEquals(-1L, map.timestampFor(POS),
                "nothing persists to the cache — next session starts clean at unknown");
    }

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

    /**
     * The CL-029 invariant's CLEAR-path twin (2026-08-05 review F2): the lost-clear branch
     * restores a &gt;0 stamp instead of unstamping, so before the sibling-echo guard each
     * consumer's report of the SAME rejected clear delivery counted a fresh strike — with
     * two consumers the park tripped after 2 failed clear deliveries instead of 4.
     */
    @Test
    void clearPathIngestFailuresCountDeliveriesNotConsumerReports() {
        map.onReceived(POS, 5000L); // the content the client holds pre-clear
        for (int delivery = 1; delivery <= ColumnStateMap.MAX_INGEST_FAILURES; delivery++) {
            // A clearing column arrives (content->air, newer stamp), flagged with the
            // pre-clear stamp, and is rejected by TWO consumers — one delivery, one strike.
            map.onReceived(POS, 6000L + delivery);
            map.markAuthoritativeClear(POS, 5000L);
            map.onIngestFailed(POS);
            map.onIngestFailed(POS); // sibling echo of the same delivery — absorbed
            assertEquals(5000L, map.timestampFor(POS),
                    "delivery " + delivery + ": pre-clear stamp restored exactly once");
            assertFalse(map.isSessionSatisfied(POS),
                    "delivery " + delivery + " must not park — per-report counting would");
        }
        // The (MAX + 1)-th failed clear delivery parks, retaining the honest pre-clear stamp.
        map.onReceived(POS, 6099L);
        map.markAuthoritativeClear(POS, 5000L);
        map.onIngestFailed(POS);
        assertTrue(map.isSessionSatisfied(POS),
                "park trips at the (MAX_INGEST_FAILURES + 1)-th failed clear delivery");
        assertEquals(5000L, map.timestampFor(POS),
                "clear-flavor park retains the pre-clear stamp (next session re-draws the clear)");
    }

    /**
     * A sibling echo arriving AFTER the park must be absorbed outright: before the
     * sessionSatisfied guard it re-entered the cap branch, found clearedResync already
     * consumed by the park, took the lost-CONTENT flavor, and destroyed the retained
     * pre-clear stamp — recreating the permanent ghost-terrain hole the clear-flavor
     * park's stamp retention exists to prevent.
     */
    @Test
    void postParkSiblingEchoKeepsTheRetainedPreClearStamp() {
        map.onReceived(POS, 5000L);
        for (int delivery = 1; delivery <= ColumnStateMap.MAX_INGEST_FAILURES + 1; delivery++) {
            map.onReceived(POS, 6000L + delivery);
            map.markAuthoritativeClear(POS, 5000L);
            map.onIngestFailed(POS);
        }
        assertTrue(map.isSessionSatisfied(POS), "precondition: parked on the capping delivery");
        assertEquals(5000L, map.timestampFor(POS), "precondition: stamp retained by the park");

        map.onIngestFailed(POS); // the capping delivery's sibling echo, landing post-park

        assertTrue(map.isSessionSatisfied(POS), "still parked");
        assertEquals(5000L, map.timestampFor(POS),
                "the echo must not strip the retained stamp (the pre-guard stamp-destruction bug)");
    }

    /**
     * Three-lens review scoping pin: the post-park absorb guard covers the CAP park only.
     * A NOT_GENERATED park deliberately keeps a real &gt;0 stamp; a straggler ingest
     * report against it (cache-load failure replay, undispatched-at-teardown) must still
     * take the honest lost-content unstamp — an absorbed report would persist a false
     * data claim to the cache file.
     */
    @Test
    void reportAgainstANotGeneratedParkedStampStillUnstampsHonestly() {
        map.onReceived(POS, 5000L);
        map.onNotGenerated(POS);
        assertTrue(map.isSessionSatisfied(POS), "premise: parked by NOT_GENERATED");
        assertEquals(5000L, map.timestampFor(POS), "premise: the stale-but-real stamp is kept");

        map.onIngestFailed(POS);

        assertEquals(-1L, map.timestampFor(POS),
                "the honest unstamp must apply — the guard is scoped to the cap park");
        assertFalse(map.mapForSave().containsKey(POS),
                "no false data claim persists to the cache file");
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
            // A PREVIOUS session's save left a stamp for POS on disk. Merge-on-save preserves
            // file-only entries, so without the honesty-removal pass the old stamp would
            // resurrect the parked position's claim.
            var prior = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
            prior.defaultReturnValue(-1L);
            prior.put(POS, 4000L);
            ColumnCacheStore.save(server, dim, prior);

            parkViaIngestFailures(POS);
            assertEquals(-1L, map.timestampFor(POS), "park holds no stamp");
            assertFalse(map.mapForSave().containsKey(POS),
                    "a parked position must not persist a fabricated stamp (the permanent-hole bug)");

            // The disconnect saveCache path: merge-save with the session's honesty removals.
            ColumnCacheStore.mergeSave(server, dim, map.mapForSave(),
                    map.persistentRemovalsForSave(), 10, -3);

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

    // ---- persistent removals (F2): deliberate deletes must reach the file ----

    @Test
    void honestyRemovalsAreRecordedAndSurviveThePrune() {
        map.onReceived(POS, 5000L);
        map.onIngestFailed(POS); // lost content: deliberate unstamp
        assertTrue(map.persistentRemovalsForSave().contains(POS),
                "an ingest-failure unstamp is recorded for the merge-save's removal pass");
        assertTrue(map.hasPersistentRemovals());

        // The player walks far away: the range prune drops the working state, but the
        // removal record must survive to the next save — pruning it would resurrect the
        // deleted stamp from the file for any position walked away from before saving.
        map.pruneOutOfRange(1000, 1000, 4);
        assertTrue(map.persistentRemovalsForSave().contains(POS),
                "persistentRemovals is deliberately NOT range-pruned");

        map.clear(); // session teardown
        assertFalse(map.hasPersistentRemovals(), "removals die with the session state");
    }

    @Test
    void legacyZeroPurgesRecordPersistentRemovals() {
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 0L);
        long pos2 = PositionUtil.packPosition(11, -3);
        loaded.put(pos2, 0L);
        map.loadFrom(loaded);

        map.onUpToDate(POS);      // park-time purge of the legacy 0-stamp
        map.onNotGenerated(pos2); // its onNotGenerated twin
        assertTrue(map.persistentRemovalsForSave().contains(POS),
                "the onUpToDate legacy-0 purge must reach the file or it resurrects every session");
        assertTrue(map.persistentRemovalsForSave().contains(pos2),
                "the onNotGenerated legacy-0 purge must reach the file or it resurrects every session");
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

    // ---- region-summary tile validation (region-summary-sync-plan.md §6) ----

    /** POS = (10,-3) → leaf (0,-1) → tile (0,-1) (a 32×32 tile is 4×4 leaves). */
    private static final int POS_TILE_X = 10 >> 5;
    private static final int POS_TILE_Z = -3 >> 5;

    @Test
    void tileValidationIsStrictlyAboveTheMarginedStamp() {
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 7000L);
        map.loadFrom(loaded);
        // Equal is NOT newer: the wire stamp is a margined upper bound, and a stamp AT
        // the bound could be a raced read's — the strict compare is the honesty line.
        var equal = map.applyTileValidation(POS_TILE_X, POS_TILE_Z, 7000L);
        assertEquals(0, equal.newlyValidated());
        assertFalse(equal.fullyValidated(), "the residue marks the tile stale");
        assertEquals(7000L, map.classify(POS), "still revalidates per column");

        var below = map.applyTileValidation(POS_TILE_X, POS_TILE_Z, 6999L);
        assertEquals(1, below.newlyValidated());
        assertTrue(below.fullyValidated());
        assertEquals(SATISFIED, map.classify(POS), "validated — no resync ask");
    }

    @Test
    void tileValidationPreservesDirtyAndRetryMarks() {
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 7000L);
        map.loadFrom(loaded);
        map.markDirtyIfKnown(POS);
        var outcome = map.applyTileValidation(POS_TILE_X, POS_TILE_Z, 0L);
        assertEquals(1, outcome.newlyValidated(),
                "the stamp validates — validation is about FRESHNESS, not about marks");
        assertEquals(7000L, map.classify(POS),
                "dirty outranks validated: a dirty notice racing the summary in either"
                        + " order still re-asks");

        long pos2 = PositionUtil.packPosition(11, -3);
        var loaded2 = new Long2LongOpenHashMap();
        loaded2.put(pos2, 7000L);
        map.loadFrom(loaded2);
        map.markRetry(pos2);
        map.applyTileValidation(POS_TILE_X, POS_TILE_Z, 0L);
        assertEquals(7000L, map.classify(pos2), "retry outranks validated too");
    }

    @Test
    void tileValidationNeverCreatesLeaves() {
        assertEquals(0, map.leafCountForTest());
        var outcome = map.applyTileValidation(0, 0, 0L);
        assertEquals(0, outcome.newlyValidated());
        assertTrue(outcome.fullyValidated(), "an empty tile has no residue");
        assertEquals(0, map.leafCountForTest(),
                "a hostile/stale frame must not allocate client state");
    }

    @Test
    void tileValidationSkipsUnstampedStates() {
        // Session-satisfied (NOT_GENERATED park) has no positive stamp — untouchable.
        map.onNotGenerated(POS);
        var outcome = map.applyTileValidation(POS_TILE_X, POS_TILE_Z, 0L);
        assertEquals(0, outcome.newlyValidated());
        assertTrue(outcome.fullyValidated());
        assertEquals(SATISFIED, map.classify(POS), "parked exactly as before");
        assertTrue(map.markDirtyIfKnown(POS), "the dirty revival path survives");
        assertEquals(-1L, map.classify(POS));
    }

    @Test
    void tileValidationWithAZeroFloorValidatesEveryPositiveStamp() {
        // stampM = 0 as a margined FLOOR validates even the minimum positive stamp.
        // NOTE this is the map-level compare only: the manager never passes the wire's
        // STAMP_NO_REGION sentinel here — "no region file" is NO EVIDENCE, not a claim
        // (a region deleted while the server was OFF also reads never-observed, and
        // validating cached stamps against it would seal the deleted terrain forever —
        // the final honesty review's MAJOR-1; the sentinel skip is pinned at the
        // manager level).
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 1L);
        map.loadFrom(loaded);
        var outcome = map.applyTileValidation(POS_TILE_X, POS_TILE_Z, 0L);
        assertEquals(1, outcome.newlyValidated());
        assertEquals(SATISFIED, map.classify(POS));
    }

    @Test
    void tileValidationNeverRevokesServerPerColumnProofs() {
        // Provenance scoping (final review, client lens MAJOR-2): onUpToDate/onReceived
        // proofs are strictly stronger evidence than a coarse tile stamp — a failing
        // frame compare must not downgrade them, or one warm-rejoin frame (whose tile
        // headers postdate the client's stamps on every recently-saved region) revokes
        // the whole freshly-answered disc into a redundant re-declaration.
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 7000L);
        map.loadFrom(loaded);
        map.onUpToDate(POS); // the server's per-column proof
        var outcome = map.applyTileValidation(POS_TILE_X, POS_TILE_Z, 9999L);
        assertEquals(0, outcome.newlyValidated());
        assertTrue(outcome.fullyValidated(),
                "a server-proofed position is not residue — it will not re-declare");
        assertEquals(SATISFIED, map.classify(POS), "the proof survives the tile stamp");

        // And a RECEIVED column's proof survives identically.
        long pos2 = PositionUtil.packPosition(11, -3);
        map.onReceived(pos2, 7000L);
        map.applyTileValidation(POS_TILE_X, POS_TILE_Z, 9999L);
        assertEquals(SATISFIED, map.classify(pos2));
    }

    @Test
    void tileValidationReportsRevokedPositionsToTheCaller() {
        // The revocation consumer (final review, client lens MAJOR-1): a revoked
        // position may sit below the scanner's confirmed prefix, so the caller must
        // learn WHICH positions to reopen — silence orphans them until a full reset.
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 7000L);
        map.loadFrom(loaded);
        map.applyTileValidation(POS_TILE_X, POS_TILE_Z, 0L); // summary-validates
        var revoked = new java.util.ArrayList<Long>();
        map.applyTileValidation(POS_TILE_X, POS_TILE_Z, 9999L, revoked::add);
        assertEquals(java.util.List.of(POS), revoked,
                "exactly the revoked position, as its packed coordinate");
        assertEquals(7000L, map.classify(POS), "and it re-declares");
    }

    @Test
    void tileValidationResidueKeepsPerColumnRevalidation() {
        long newer = PositionUtil.packPosition(12, -3);
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 5000L);
        loaded.put(newer, 9000L);
        map.loadFrom(loaded);
        var outcome = map.applyTileValidation(POS_TILE_X, POS_TILE_Z, 6000L);
        assertEquals(1, outcome.newlyValidated(), "only the strictly-newer stamp");
        assertFalse(outcome.fullyValidated());
        assertEquals(SATISFIED, map.classify(newer));
        assertEquals(5000L, map.classify(POS),
                "the residue re-declares per column — graceful degradation, never a hole");
    }

    @Test
    void tileValidationIsTwoDirectionalAFresherFrameRevokesAStalerOnes() {
        // P2 client review MAJOR-2: frames can land out of order (dimension excursion,
        // mid-session manager rebuild), and the dirty-broadcast heal channel does not
        // reach a player who was elsewhere at drain time — so a failing compare must
        // REVOKE, or the first (staler) frame's over-validation is permanent.
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 7000L);
        map.loadFrom(loaded);
        map.applyTileValidation(POS_TILE_X, POS_TILE_Z, 0L); // stale frame: validates
        assertEquals(SATISFIED, map.classify(POS));
        var fresher = map.applyTileValidation(POS_TILE_X, POS_TILE_Z, 8000L);
        assertEquals(0, fresher.newlyValidated());
        assertFalse(fresher.fullyValidated());
        assertEquals(7000L, map.classify(POS),
                "the fresher frame's failing compare must revoke — fail toward serving");
    }

    @Test
    void tileValidationSkipsLegacyZeroStamps() {
        // A released client's cached 0-stamp (the legacy NOT_GENERATED marker) declares
        // "I have nothing" — it must never be validated into SATISFIED by a frame.
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 0L);
        map.loadFrom(loaded);
        var outcome = map.applyTileValidation(POS_TILE_X, POS_TILE_Z, 0L);
        assertEquals(0, outcome.newlyValidated());
        assertEquals(-1L, map.classify(POS), "still re-declares as a first serve");
    }

    @Test
    void tileValidationIsEquivalentToUpToDateForMarkFreeStampedPositions() {
        // The §6 differential: for MARK-FREE stamped positions, validating via a tile
        // stamp M must classify exactly like the per-column up_to_date answer the
        // server would have sent for a strictly-newer stamp (and like NO answer
        // otherwise). Seeded random stamps across one tile.
        var rng = new java.util.Random(0x5EED);
        long stampM = 5000L;
        var viaSummary = new ColumnStateMap();
        var viaAnswers = new ColumnStateMap();
        var loaded = new Long2LongOpenHashMap();
        long[] positions = new long[64];
        long[] stamps = new long[64];
        for (int i = 0; i < 64; i++) {
            positions[i] = PositionUtil.packPosition(i % 32, i / 32); // distinct, one tile
            stamps[i] = 1 + rng.nextInt(10_000);
            loaded.put(positions[i], stamps[i]);
        }
        viaSummary.loadFrom(loaded);
        viaAnswers.loadFrom(loaded);
        viaSummary.applyTileValidation(0, 0, stampM);
        for (int i = 0; i < 64; i++) {
            if (stamps[i] > stampM) viaAnswers.onUpToDate(positions[i]);
        }
        for (int i = 0; i < 64; i++) {
            assertEquals(viaAnswers.classify(positions[i]), viaSummary.classify(positions[i]),
                    "position " + i + " (stamp " + stamps[i] + " vs M " + stampM + ")");
        }
    }

    // ---- the region walk's probes (region-scan-plan.md §2.2) ----

    @Test
    void auditRegionNeedsHealsACorruptedBitAndCountsIt() {
        var m = new ColumnStateMap();
        long pk = PositionUtil.packPosition(3, 3); // never received -> genuinely needy
        m.onReceived(PositionUtil.packPosition(3, 4), 1000L); // materialize the leaf,
        // so the premise below reads a REAL bit, not the absent-leaf default (review NIT)
        assertTrue(m.needsBitForTest(pk));
        assertEquals(0, m.auditRegionNeeds(0, 0), "a healthy region audits to zero");
        m.corruptNeedsBitForTest(pk);
        assertFalse(m.needsBitForTest(pk), "premise: the bit is corrupted OFF");
        assertEquals(1, m.auditRegionNeeds(0, 0), "the audit heals exactly the one leaf");
        assertTrue(m.needsBitForTest(pk), "the recompute restored the bit");
        assertEquals(0, m.auditRegionNeeds(0, 0), "and the region is healthy again");
    }

    @Test
    void rectNeedsFreeIsLeafGranularAndConservative() {
        // The hybrid walk's residue probe (hybrid-scan-plan.md §2.1/§8 pin 12):
        // leaf-granular over the rectangle — absent leaf = needs, partial leaf
        // conservative toward walking (the UNALIGNED-rect arm; the leaf-aligned
        // exactness rides the SectionStateFuzzTest differential).
        var m = new ColumnStateMap();
        assertFalse(m.rectNeedsFree(0, 0, 7, 7), "an empty map's rect reads as needing");
        for (int cx = 0; cx < 8; cx++) {
            for (int cz = 0; cz < 8; cz++) {
                long pk = PositionUtil.packPosition(cx, cz);
                m.onReceived(pk, 1000L);
                m.onUpToDate(pk);
            }
        }
        assertTrue(m.rectNeedsFree(0, 0, 7, 7), "a fully satisfied leaf-aligned rect is free");
        // A rect INTERSECTING a leaf with needs elsewhere in the leaf: conservative.
        assertFalse(m.rectNeedsFree(4, 4, 12, 12),
                "a straddling rect touching the absent leaf (8..15) reads needs");
        assertTrue(m.markDirtyIfKnown(PositionUtil.packPosition(0, 0)));
        assertFalse(m.rectNeedsFree(2, 2, 7, 7),
                "needs ANYWHERE in an intersecting leaf refuses the skip — even outside"
                        + " the rect (the conservative partial-leaf convention)");
        // Negative coordinates floor-shift clean.
        var neg = new ColumnStateMap();
        for (int cx = -8; cx < 0; cx++) {
            for (int cz = -8; cz < 0; cz++) {
                long pk = PositionUtil.packPosition(cx, cz);
                neg.onReceived(pk, 1000L);
                neg.onUpToDate(pk);
            }
        }
        assertTrue(neg.rectNeedsFree(-8, -8, -1, -1), "negative leaf-aligned rect is free");
        assertFalse(neg.rectNeedsFree(-9, -8, -1, -1), "one column into the absent leaf = needs");
    }
}
