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
 * Characterization tests for the per-column request-need ladder (the priority order
 * mirrors the original scanner ladder bit-for-bit: unknown &gt; generation &gt; dirty &gt;
 * rate-limit retry &gt; revalidation &gt; satisfied) and the state transitions.
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
        assertEquals(SATISFIED, map.classify(packed, true), "precondition: parked");
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

    // ---- sessionSatisfied: satisfied-without-a-stamp, dirty always wins (delivery-honesty) ----

    @Test
    void sessionSatisfiedClassifiesSatisfiedButDirtyStillWins() {
        map.markSessionSatisfied(POS);
        assertEquals(SATISFIED, map.classify(POS, true), "a session-satisfied position needs no request");
        assertEquals(-1L, map.timestampFor(POS), "no timestamp is fabricated for a satisfied position");

        // A dirty broadcast must outrank sessionSatisfied so an air->content edit re-requests.
        assertTrue(map.markDirtyIfKnown(POS), "dirty fires for a session-satisfied position");
        assertFalse(map.isSessionSatisfied(POS), "the dirty un-parks it");
        assertEquals(-1L, map.classify(POS, true), "dirty outranks sessionSatisfied — re-request as first serve");
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
        map.onReceived(POS, 9000L);
        assertEquals(SATISFIED, map.classify(POS, false), "healed after the disk serve");
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
        assertEquals(SATISFIED, map.classify(POS, true),
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
    void onUpToDateSatisfiesAbsentPositionsWithoutFabricatingAStamp() {
        map.onUpToDate(POS);
        assertEquals(SATISFIED, map.classify(POS, true),
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
        map.onReceived(POS, 6000L);
        assertEquals(SATISFIED, map.classify(POS, true));
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

        assertEquals(3000L, map.classify(POS, true),
                "a rejected clear re-requests with the pre-clear content stamp, not ts=-1");
        assertTrue(map.hasRetries(), "retry mark forces the confirmed-ring reset so it is rescanned");
    }

    @Test
    void contentSupersedesAPendingClearFlag() {
        map.onReceived(POS, 5000L);
        map.markAuthoritativeClear(POS, 3000L);
        map.onReceived(POS, 7000L);   // real content arrives AFTER the clear flag — supersedes it

        map.onIngestFailed(POS);      // now this is a plain content rejection

        assertEquals(-1L, map.classify(POS, true),
                "once real content supersedes the clear flag, a rejection re-requests as content (ts=-1)");
    }

    @Test
    void repeatedlyRejectedClearParksAtCap() {
        for (int i = 0; i < ColumnStateMap.MAX_INGEST_FAILURES; i++) {
            map.onReceived(POS, 5000L + i);          // server re-sends the clear
            map.markAuthoritativeClear(POS, 3000L);
            map.onIngestFailed(POS);
            assertEquals(3000L, map.classify(POS, true),
                    "clear failure " + (i + 1) + " re-requests with the pre-clear stamp");
        }
        map.onReceived(POS, 9000L);
        map.markAuthoritativeClear(POS, 3000L);
        map.onIngestFailed(POS); // cap exceeded

        assertEquals(SATISFIED, map.classify(POS, true),
                "a permanently rejected clear must not drive an endless re-clear loop");
        assertTrue(map.isSessionSatisfied(POS), "parked via session-satisfied");
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
        assertEquals(-1L, map.classify(POS, true));
        assertTrue(map.isEmptyMap());
    }

    // ---- cache-loaded stamps (CL-027, CL-028) ----

    @Test
    void cacheLoadedZeroStampRetriesGenerationOnlyWhenEnabled() {
        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 0L);
        map.loadFrom(loaded);

        assertEquals(1, map.emptyCount(), "a cache-loaded 0 counts as a not-generated stamp");
        assertEquals(0L, map.classify(POS, true),
                "next session re-asks generation for a cached not-generated stamp");
        assertEquals(SATISFIED, map.classify(POS, false), "generation disabled parks the cached stamp");
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
        assertEquals(-1L, map.classify(POS, true),
                "a corrupt negative stamp clamps to unknown and re-requests (no silent SATISFIED park)");

        // It is now -1 (unknown); the scan ladder requests -1 positions anyway, so the dirty
        // rescue (which only fires for a KNOWN disposition) correctly does not apply.
        assertFalse(map.markDirtyIfKnown(POS), "a clamped-to-unknown stamp is not a known disposition");
        map.onReceived(POS, 8000L);
        assertEquals(SATISFIED, map.classify(POS, true));
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
            assertEquals(-1L, map.classify(POS, true), "delivery " + delivery
                    + " still re-requests — per-report counting would already park here");
        }
        map.onReceived(POS, 5003L);
        map.onIngestFailed(POS);
        assertEquals(-1L, map.classify(POS, true), "third failed delivery still re-requests");
        map.onReceived(POS, 5004L);
        map.onIngestFailed(POS);
        assertEquals(SATISFIED, map.classify(POS, true),
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
        assertEquals(-1L, map.classify(POS, true), "re-requests as a first serve (no fabricated stamp)");

        // The re-served content resolves it honestly with the real server stamp.
        map.onReceived(POS, 9000L);
        assertEquals(SATISFIED, map.classify(POS, true), "resolved with the server stamp");
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
            assertEquals(-1L, next.classify(POS, true),
                    "next session re-asks honestly (-1), never a false up-to-date");

            // The re-serve gives the consumer a fresh chance; a transient failure heals.
            next.onReceived(POS, 6000L);
            assertEquals(SATISFIED, next.classify(POS, true), "transient failure heals next session");
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

        assertEquals(-1L, map.classify(POS, true),
                "a pruned-and-returned position gets a fresh failure cap — a stale count would park here");
    }

    // ---- classify ladder: gen-enabled dirty arm (CL-034) ----

    @Test
    void zeroStampWithDirtyEmitsGenerationRequestWhenGenerationEnabled() {
        map.onNotGenerated(POS);
        assertTrue(map.markDirtyIfKnown(POS), "a dirty broadcast lands on the not-generated stamp");
        assertEquals(0L, map.classify(POS, true),
                "gen-enabled arm: the re-request must go out as ts=0 (disk-first), never a >0 claim");
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
        assertEquals(5000L, map.classify(dirtyPos, true), "dirty+retry collapse into one stored-ts request");
        map.onUpToDate(dirtyPos); // the terminal answer: server says the held content is current
        assertEquals(SATISFIED, map.classify(dirtyPos, true), "one answer consumes both marks");

        // retry × ts==0: the bounced gen request retries once even after generation flips off.
        long genPos = PositionUtil.packPosition(2, 2);
        map.onNotGenerated(genPos);
        map.markRetry(genPos);
        assertEquals(0L, map.classify(genPos, true), "the gen rung outranks the retry mark");
        assertEquals(0L, map.classify(genPos, false),
                "a retry-marked gen request stays requestable once with generation disabled");
        map.onNotGenerated(genPos); // the terminal answer to that ts=0 ask
        assertEquals(SATISFIED, map.classify(genPos, false), "the answered retry re-parks the gen-disabled stamp");

        // retry × parked: a parked position is session-satisfied (never declared), so a stray
        // retry mark cannot resurrect it — it heals via dirty or next session.
        long parked = PositionUtil.packPosition(3, 3);
        parkViaIngestFailures(parked);
        map.markRetry(parked);
        assertEquals(SATISFIED, map.classify(parked, true),
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
        // notGenerated × dirty: the answer consumes the mark.
        long dirtyPos = PositionUtil.packPosition(1, 1);
        map.onReceived(dirtyPos, 5000L);
        map.markDirtyIfKnown(dirtyPos);
        map.onNotGenerated(dirtyPos);
        assertEquals(0, map.dirtyCount(), "a not-generated answer consumes the dirty mark");
        assertEquals(0L, map.classify(dirtyPos, true), "ts=0 stays a generation retry while generation is on");
        assertEquals(SATISFIED, map.classify(dirtyPos, false),
                "generation off: the answered position parks — a surviving mark would re-declare it forever");

        // notGenerated × retry: the pending retry is consumed too.
        long retryPos = PositionUtil.packPosition(2, 2);
        map.onReceived(retryPos, 6000L);
        map.markRetry(retryPos);
        map.onNotGenerated(retryPos);
        assertFalse(map.hasRetries(), "a not-generated answer consumes the pending retry");
        assertEquals(SATISFIED, map.classify(retryPos, false),
                "the consumed retry parks the gen-disabled stamp");

        // notGenerated × parked: the answer overwrites the park stamp with 0 — re-opened as
        // a generation retry (gen on) or silently re-parked (gen off).
        long parked = PositionUtil.packPosition(3, 3);
        parkViaIngestFailures(parked);
        map.onNotGenerated(parked);
        assertEquals(0L, map.classify(parked, true), "not-generated re-opens a parked position as a gen retry");
        assertEquals(SATISFIED, map.classify(parked, false));
        assertEquals(3, map.emptyCount(), "all three cells ended as not-generated stamps — counts consistent");
        assertEquals(0, map.receivedCount());
    }

    @Test
    void lateDataOnParkedPositionRefreshesStampAndStaysSatisfied() {
        parkViaIngestFailures(POS);
        long parkStamp = map.timestampFor(POS);

        map.onReceived(POS, parkStamp + 777); // a late response lands on the parked position

        assertEquals(parkStamp + 777, map.timestampFor(POS), "late data refreshes the parked stamp");
        assertEquals(SATISFIED, map.classify(POS, true), "still satisfied — no re-request storm");
        assertFalse(map.hasRetries(), "no retry resurrection");
        assertEquals(1, map.receivedCount());

        map.onIngestFailed(POS);
        assertEquals(SATISFIED, map.classify(POS, true),
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
        assertEquals(SATISFIED, map.classify(pos, true),
                "an up-to-date answer must consume the dirty and retry marks — under "
                + "re-declaration nothing else ever consumes them");
    }

    @Test
    void notGeneratedAnswerConsumesDirtyAndRetryMarksButStaysGenRetryable() {
        var map = new ColumnStateMap();
        long pos = PositionUtil.packPosition(5, 6);
        map.onReceived(pos, 100L);
        map.markDirtyIfKnown(pos);
        map.markRetry(pos);
        map.onNotGenerated(pos);
        assertEquals(0L, map.classify(pos, true),
                "ts=0 + generation enabled must still classify as a gen retry");
        assertEquals(SATISFIED, map.classify(pos, false),
                "with generation disabled the position must park — a surviving dirty/retry "
                + "mark would re-declare it forever");
    }
}
