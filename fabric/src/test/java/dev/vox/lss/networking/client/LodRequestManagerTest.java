package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.networking.payloads.BatchResponseS2CPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static dev.vox.lss.networking.client.ColumnStateMap.SATISFIED;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for the manager's response callbacks and want-set scan loop — the glue the
 * position-keyed (no request id) protocol leans on. Pins the asymmetries that look like bugs
 * but are deliberate:
 * <ul>
 *   <li>Status responses (up-to-date / not-generated) are gated on the awaiting set, so a late
 *       or duplicate status can never stamp column state the client never asked about (a wrong
 *       SATISFIED stamp is a permanent invisible hole that no soak conservation law can see).</li>
 *   <li>Column DATA is the opposite: it applies even untracked (self-heal) but never
 *       cross-dimension, because the dispatch drain discards the bytes.</li>
 *   <li>Marks are consumed by ANSWERS, never by sends: under want-set re-declaration a mark
 *       consumed at send would classify SATISFIED while the answer was still outstanding, so a
 *       server-side supersession would lose the edit for the session.</li>
 * </ul>
 * Plus the two hard protocol invariants of the want-set: a converged scan sends NOTHING (a
 * heartbeat would blind the soak quiescence predicate), and entering the decode-backpressure
 * halt sends exactly ONE empty clear batch.
 */
class LodRequestManagerTest {

    private static final long POS = PositionUtil.packPosition(10, -3);

    private LodRequestManager manager;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        manager = new LodRequestManager();
        manager.onSessionConfig(config(64, true), "lss-test");
        sent.clear();
        recordSends(); // never let a scan reach the real ClientPlayNetworking transport
    }

    private static SessionConfigS2CPayload config(int lodDistance, boolean generationEnabled) {
        return new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true,
                lodDistance, generationEnabled);
    }

    private static ResourceKey<Level> dim(String name) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse("lss_test:" + name));
    }

    /** Copies of sent batches — the manager reuses its send buffers across ticks. */
    record SentBatch(long[] positions, long[] timestamps, int count) {}

    private final List<SentBatch> sent = new ArrayList<>();

    /** Install a recording batch sender; captured arrays are copied at send time. */
    private void recordSends() {
        manager.setBatchSenderForTest(p -> sent.add(new SentBatch(
                Arrays.copyOf(p.packedPositions(), p.count()),
                Arrays.copyOf(p.clientTimestamps(), p.count()),
                p.count())));
    }

    /** Positions of the single batch sent by the last fired scan. */
    private List<Long> lastBatch() {
        assertEquals(1, sent.size(), "expected exactly one batch on the wire");
        var out = new ArrayList<Long>();
        for (long p : sent.get(0).positions()) out.add(p);
        return out;
    }

    // The scan buffers the cadence-only helpers write into (the manager owns its own).
    private final long[] scanPos = new long[LSSConstants.MAX_BATCH_CHUNK_REQUESTS];
    private final long[] scanTs = new long[LSSConstants.MAX_BATCH_CHUNK_REQUESTS];

    /**
     * One maybeScan call against the manager's own collaborators with viewDistance covering
     * the lod distance, so a fired scan declares nothing — pure cadence observation.
     */
    private int maybeScanOnce() {
        return manager.scannerForTest().maybeScan(0, 0, 64, 0, 1000, () -> 0,
                manager.columnsForTest(), scanPos, scanTs);
    }

    /**
     * Reconfigure to a one-ring disc (lodDistance 1, viewDistance 0) so ring 1's 8 positions are
     * the entire want-set universe and a batch's contents are exactly assertable. Returns the
     * ring's packed positions. Call BEFORE seeding: onSessionConfig resets all request state.
     */
    private long[] configureRing1Disc() {
        manager.onSessionConfig(config(1, true), "lss-test");
        manager.markCacheLoadedForTest();
        recordSends();
        sent.clear();
        var ring = new long[8];
        int[] c = new int[2];
        for (int i = 0; i < 8; i++) {
            SpiralScanner.ringIndexToCoord(1, i, 0, 0, c);
            ring[i] = PositionUtil.packPosition(c[0], c[1]);
        }
        return ring;
    }

    /** Drive the manager's scan phase over the ring-1 disc until the cadence fires. */
    private int fireScanRing1() {
        return fireScan(0);
    }

    /** Drive tickScanPhase at the origin until the 20-tick cadence fires; returns the want-set size. */
    private int fireScan(int viewDistance) {
        for (int i = 0; i < LSSConstants.TICKS_PER_SECOND + 1; i++) {
            int n = manager.tickScanPhase(0, 0, viewDistance, 0, () -> 0);
            if (n >= 0) return n;
        }
        throw new AssertionError("scan cadence never fired");
    }

    /**
     * A scan over a FULLY excluded disc: walks, wants nothing. viewDistance must be 90, not 64 —
     * vanilla's view is a rounded (1-chunk-buffered Euclidean) disc, so at vd == lodDistance == 64
     * the lod square's corners still fall outside it and are correctly declared (that is the
     * corner-annulus fix pinned in SpiralScannerTest). Subsuming the whole square needs
     * (64-1)^2 * 2 = 7938 < vd^2, i.e. vd >= 90.
     */
    private int fireScanAtOrigin() {
        return fireScan(90);
    }

    /** Fire one scan to normalize the primed join cadence (counter deterministically 0 after). */
    private void normalizeScanCadence() {
        for (int i = 0; i <= LSSConstants.TICKS_PER_SECOND; i++) {
            if (maybeScanOnce() >= 0) return;
        }
        throw new AssertionError("scan cadence never fired");
    }

    /** Normalize, then advance to exactly one call before the next cadence fire. */
    private void advanceToOneCallBeforeScanFire() {
        normalizeScanCadence();
        for (int i = 0; i < LSSConstants.TICKS_PER_SECOND - 1; i++) {
            assertEquals(-1, maybeScanOnce(), "cadence must not fire while advancing");
        }
    }

    // ---- untracked status responses: count in metrics, never touch column state ----

    @Test
    void untrackedNotGeneratedCountsMetricButNeverStampsState() {
        manager.onColumnNotGenerated(POS);

        assertEquals(1, manager.getTotalNotGenerated(), "late responses still count in diagnostics");
        assertEquals(0, manager.getEmptyColumnCount());
        assertEquals(-1L, manager.columnsForTest().classify(POS),
                "an untracked status must not fabricate a not-generated stamp"
                        + " (would trigger spurious generation requests every scan)");
    }

    @Test
    void untrackedUpToDateNeverFabricatesSatisfiedState() {
        manager.onColumnUpToDate(POS);

        assertEquals(1, manager.getTotalUpToDate());
        assertEquals(0, manager.getReceivedColumnCount());
        assertEquals(-1L, manager.columnsForTest().classify(POS),
                "a wrongly-SATISFIED stamp would be a permanent invisible hole — no data was ever delivered");
    }

    // untrackedRateLimitedNeverPoisonsSatisfiedColumnIntoRetry and
    // untrackedRateLimitedStillLatchesScanBackoff are DELETED with their subjects: a bounce no
    // longer marks retry (nothing to poison) and the scan backoff no longer exists (a full slot
    // retains the entry in the server backlog instead of bouncing it). v17 then retired the
    // bounce from the wire outright — byte 0 is reserved and inert, pinned by
    // dispatchRoutesEachTypeAndUnknownTypeSkipsOnlyThatEntry. The surviving "a stray status must
    // not touch column state" invariant is pinned by the two untracked tests above and by
    // duplicatePositionInOneBatchFrameResolvesConsistently below.

    // ---- dirty crossing an in-flight first serve forces a re-request (#11) ----

    @Test
    void dirtyCrossingAnInFlightFirstServeReRequestsOnUpToDate() {
        var tracker = manager.trackerForTest();
        tracker.replaceWith(new long[]{POS}, 1); // first serve in flight (stored == -1)

        manager.onDirtyColumns(new long[]{POS});            // dirty crosses the in-flight serve
        manager.onColumnUpToDate(POS);                      // the pre-edit (all-air) answer lands

        assertNotEquals(SATISFIED, manager.columnsForTest().classify(POS),
                "a dirty that crossed the in-flight first serve must force a re-request, not settle");
    }

    @Test
    void dirtyCrossingAnInFlightFirstServeReRequestsOnNotGenerated() {
        // The NOT_GENERATED terminal is the one whose park is PERMANENT, so the ordering in
        // onColumnNotGenerated is load-bearing: columns.onNotGenerated parks FIRST, then
        // consumeStaleCrossing's markDirtyIfKnown fires on the just-added session-satisfied
        // mark and un-parks it. Swapped, a crossed edit would consume staleInFlight, fail
        // markDirtyIfKnown (no disposition yet), and the park would hold for the session.
        var tracker = manager.trackerForTest();
        tracker.replaceWith(new long[]{POS}, 1); // first serve in flight (stored == -1)

        manager.onDirtyColumns(new long[]{POS});            // dirty crosses the in-flight serve
        manager.onColumnNotGenerated(POS);                  // the pre-edit answer lands

        assertNotEquals(SATISFIED, manager.columnsForTest().classify(POS),
                "a dirty that crossed the in-flight answer must outlive the permanent park");
        assertFalse(manager.columnsForTest().isSessionSatisfied(POS),
                "the crossing un-parks the position — the edit is not lost for the session");
        assertEquals(0, manager.getConfirmedRing(),
                "the stale-crossing path does its own confirmed-ring reset to re-reach the position");
    }

    @Test
    void dirtyCrossingAnInFlightFirstServeReRequestsOnReceived() {
        manager.setLastDimensionForTest(dim("overworld"));
        var tracker = manager.trackerForTest();
        tracker.replaceWith(new long[]{POS}, 1);

        manager.onDirtyColumns(new long[]{POS});
        manager.onColumnReceived(POS, 5000L, dim("overworld")); // pre-edit content lands

        assertNotEquals(SATISFIED, manager.columnsForTest().classify(POS),
                "a crossed dirty re-requests even when real (pre-edit) data arrived");
    }

    @Test
    void dirtyCrossingAnInFlightResyncReRequests() {
        // A second edit landing while the FIRST edit's resync is still in flight. markDirtyIfKnown
        // returns true here (the column is already held, stored > 0), so the crossing is preserved
        // only if noteStaleIfInFlight is called unconditionally — not just when the position is
        // unknown. Without that, onColumnReceived clears the dirty mark and the second edit is lost.
        manager.setLastDimensionForTest(dim("overworld"));
        manager.onColumnReceived(POS, 5000L, dim("overworld")); // held from an earlier serve
        assertEquals(SATISFIED, manager.columnsForTest().classify(POS));

        var tracker = manager.trackerForTest();
        tracker.replaceWith(new long[]{POS}, 1); // resync (first edit) in flight

        manager.onDirtyColumns(new long[]{POS});               // second edit crosses the resync
        manager.onColumnReceived(POS, 6000L, dim("overworld")); // first-edit content lands, clears dirty

        assertNotEquals(SATISFIED, manager.columnsForTest().classify(POS),
                "a dirty crossing an in-flight resync must re-request, not settle on stale content");
    }

    // ---- a rejected authoritative clear self-heals (WS3 completion, review #2/#3) ----

    @Test
    void rejectedAuthoritativeClearReRequestsWithPreClearStamp() {
        // End-to-end through the manager: the client holds content, the server sends a 0-section
        // clearing column (content->air), the consumer rejects it. The re-request must carry the
        // pre-clear stamp so the server re-sends the clear, not ts=-1 (which draws an all-air
        // up_to_date and strands ghost terrain for the session).
        manager.setLastDimensionForTest(dim("overworld"));
        manager.onColumnReceived(POS, 3000L, dim("overworld"));       // pre-clear content held
        manager.onColumnReceived(POS, 5000L, dim("overworld"), true); // authoritative 0-section clear

        manager.onIngestFailure(dim("overworld"), POS);               // consumer rejects the clear

        assertEquals(3000L, manager.columnsForTest().classify(POS),
                "a rejected clear re-requests with the pre-clear content stamp, not ts=-1");
    }

    // ---- tracked status responses: apply state AND release the in-flight slot ----

    @Test
    void trackedUpToDateStampsValidationAndReleasesSlot() {
        var tracker = manager.trackerForTest();
        tracker.replaceWith(new long[]{POS}, 1);

        manager.onColumnUpToDate(POS);

        assertFalse(tracker.isInFlight(POS), "slot leak would throttle later requests until timeout");
        assertEquals(0, manager.getReceivedColumnCount(),
                "up-to-date on a never-seen position is session-satisfied, not stamped");
        assertEquals(SATISFIED, manager.columnsForTest().classify(POS));
        assertTrue(manager.columnsForTest().isSessionSatisfied(POS));
        assertEquals(1, manager.getTotalUpToDate());
    }

    @Test
    void trackedNotGeneratedSessionSatisfiesAndReleasesSlot() {
        var tracker = manager.trackerForTest();
        tracker.replaceWith(new long[]{POS}, 1);

        manager.onColumnNotGenerated(POS);

        assertFalse(tracker.isInFlight(POS));
        assertEquals(SATISFIED, manager.columnsForTest().classify(POS),
                "NOT_GENERATED is a permanent session-satisfy — the spiral never re-asks");
        assertTrue(manager.columnsForTest().isSessionSatisfied(POS));
        assertEquals(0, manager.getEmptyColumnCount(), "no 0-stamp is written anymore");
        assertEquals(1, manager.getTotalNotGenerated());
    }

    // trackedRateLimitedMarksRetryAndReleasesSlot is DELETED with the bounce reaction: a slot
    // bounce no longer marks retry and no longer consumes the awaiting entry, because the next
    // scan re-declares the position unconditionally. What survives of it — the bounce must not
    // corrupt column state — is pinned on the stub in dispatchRoutesEachTypeAndUnknownType-
    // SkipsOnlyThatEntry and duplicatePositionInOneBatchFrameResolvesConsistently.

    // ---- column data: self-heal when untracked, discard cross-dimension ----

    @Test
    void columnDataAppliesEvenWhenNoLongerTracked() {
        manager.setLastDimensionForTest(dim("overworld"));

        manager.onColumnReceived(POS, 5000L, dim("overworld")); // e.g. timed out client-side

        assertEquals(1, manager.getTotalColumnsReceived());
        assertEquals(1, manager.getReceivedColumnCount());
        assertEquals(SATISFIED, manager.columnsForTest().classify(POS),
                "data is authoritative for the position; gating on isInFlight reintroduces"
                        + " the timeout -> silent-duplicate -> second-timeout stall");
    }

    @Test
    void trackedColumnDataReleasesInFlightSlot() {
        manager.setLastDimensionForTest(dim("overworld"));
        var tracker = manager.trackerForTest();
        tracker.replaceWith(new long[]{POS}, 1);

        manager.onColumnReceived(POS, 5000L, dim("overworld"));

        assertFalse(tracker.isInFlight(POS));
        assertEquals(1, manager.getReceivedColumnCount());
    }

    @Test
    void crossDimensionColumnDataIsNeverStamped() {
        manager.setLastDimensionForTest(dim("overworld"));

        manager.onColumnReceived(POS, 5000L, dim("nether"));

        assertEquals(0, manager.getTotalColumnsReceived());
        assertEquals(0, manager.getReceivedColumnCount());
        assertEquals(-1L, manager.columnsForTest().classify(POS),
                "the dispatch drain discards cross-dimension bytes, so stamping here would mark"
                        + " SATISFIED with no data delivered — a permanent hole after a dimension trip");
    }

    @Test
    void columnDataBeforeFirstTickApplies() {
        // lastDimension is null until the first tick samples the client level
        manager.onColumnReceived(POS, 5000L, dim("nether"));

        assertEquals(1, manager.getReceivedColumnCount(),
                "no dimension is known yet, so data must not be dropped");
    }

    @Test
    void farOutOfRangeUnsolicitedColumnIsNeverStamped() {
        manager.setLastDimensionForTest(dim("overworld"));

        // The movement pruner only runs on chunk crossings, so a stationary client fed
        // arbitrary far positions would otherwise grow the state map without bound.
        long far = PositionUtil.packPosition(100_000, 100_000);
        manager.onColumnReceived(far, 5000L, dim("overworld"));

        assertEquals(0, manager.getReceivedColumnCount(),
                "a column far outside the prune radius must not stamp");
        assertEquals(-1L, manager.columnsForTest().timestampFor(far),
                "no state map entry for an unbounded-growth vector");

        // In-range unsolicited data is still authoritative and still applies.
        manager.onColumnReceived(POS, 5000L, dim("overworld"));
        assertEquals(1, manager.getReceivedColumnCount());
    }

    // ---- the want-set scan: re-declaration, mark survival, convergence ----
    //
    // The per-type concurrency drain tests (fullGenLimiterDoesNotStarveQueuedSyncRequests,
    // fullSyncLimiterDoesNotStarveQueuedGenerationRequests, generationSlotsDoNotCountAgainst-
    // SyncCapacity, drainStopsAtMaxToSendAndKeepsRemainder) are DELETED with the drip-feed: the
    // client no longer mirrors the server's slot caps and no longer holds a queue, so there is no
    // client-side limiter to head-of-line block and no remainder to drip. The head-of-line
    // invariant itself moved SERVER-side, where a full slot now retains a backlog entry in order
    // instead of bouncing it (Task 3/4's BacklogReplaceTest). sweepTimeoutsEvictsStaleRequests-
    // AndMarksThemForRetry is DELETED with the 10s timeout sweep: its orphan-rescue job is now
    // done structurally by re-declaration — every unsatisfied position is re-declared every scan,
    // which is exactly what anyChaosInterleavingLeavesNoPositionPermanentlyOrphaned pins.

    @Test
    void scanReDeclaresAwaitedPositionsAndSkipsSatisfiedOnes() {
        var ring = configureRing1Disc();
        long awaited = ring[0];
        long satisfied = ring[1];
        var columns = manager.columnsForTest();
        columns.onReceived(satisfied, 5000L); // held + validated this session -> SATISFIED
        for (int i = 2; i < ring.length; i++) columns.onReceived(ring[i], 5000L);
        // awaited was declared by an earlier batch and has had no answer yet.
        manager.trackerForTest().replaceWith(new long[]{awaited}, 1);

        assertEquals(1, fireScanRing1(), "only the unsatisfied position belongs in the want-set");

        assertEquals(List.of(awaited), lastBatch(),
                "an AWAITED position must be re-declared, not suppressed: the server may silently"
                        + " supersede any not-yet-admitted ask and the 1 Hz re-declare is the only heal");
        assertFalse(lastBatch().contains(satisfied), "a satisfied position must never be declared");
    }

    @Test
    void marksSurviveSendAndAreConsumedByAnswers() {
        var ring = configureRing1Disc();
        long target = ring[0];
        var columns = manager.columnsForTest();
        for (long p : ring) columns.onReceived(p, 5000L); // whole ring satisfied...
        assertTrue(columns.markDirtyIfKnown(target));     // ...until the server says target changed

        assertEquals(1, fireScanRing1(), "scan 1 declares the dirty position");
        assertEquals(1, fireScanRing1(), "scan 2 must RE-DECLARE it — the send consumed no mark");
        assertEquals(2, sent.size());
        assertEquals(target, sent.get(1).positions()[0]);
        assertEquals(1, manager.getDirtyColumnCount(),
                "the mark survived both sends: consuming it at send would classify SATISFIED while"
                        + " the answer was still outstanding, so a supersession would lose the edit");

        manager.onColumnReceived(target, 6000L, dim("overworld")); // the ANSWER is the only consumer

        assertEquals(0, manager.getDirtyColumnCount(), "the terminal answer consumed the mark");
        assertEquals(SATISFIED, columns.classify(target));
        assertEquals(0, fireScanRing1(), "scan 3 omits it");
        assertEquals(2, sent.size(), "...and sends nothing at all");
    }

    // ---- the two hard want-set protocol invariants ----

    @Test
    void convergedScanSendsNothingAtAll() {
        // HARD protocol invariant: at convergence the client is silent. A heartbeat batch would
        // keep service.requests_received moving forever, which blinds the soak quiescence
        // predicate and thereby silently disables EVERY soak conservation law.
        assertEquals(0, fireScanAtOrigin(), "premise: the scan walked and wanted nothing");

        assertTrue(sent.isEmpty(), "a walked-but-empty want-set must send NO batch");
        assertEquals(0, manager.getPendingCount(),
                "...but the awaiting set is still replaced, so phantom entries cannot linger");
    }

    @Test
    void noWalkTickNeitherSendsNorReplacesTheAwaitingSet() {
        // The -1 ("no walk") return is NOT convergence and must not be treated as one. A budget
        // scaled to zero by vanilla load happens on a normal join; if it replaced the awaiting set
        // it would drop the gating for every outstanding answer. (Successor to CL-005's
        // zeroBudgetScanPreservesCommittedRemainder, whose drip-feed remainder no longer exists.)
        manager.trackerForTest().replaceWith(new long[]{POS}, 1);

        int walked = -1;
        for (int i = 0; i < LSSConstants.TICKS_PER_SECOND + 1; i++) {
            // viewDistance 2 -> exclusion area 25, all 25 missing -> vanilla scale 0 -> budget 0
            walked = manager.tickScanPhase(0, 0, 2, 0, () -> 25);
            if (walked >= 0) break;
        }

        assertEquals(-1, walked, "a budget-zeroed cadence fire must report NO walk, not an empty one");
        assertTrue(sent.isEmpty(), "no walk, no batch");
        assertTrue(manager.trackerForTest().isInFlight(POS),
                "a no-walk tick must leave the awaiting set alone — replacing it would un-gate"
                        + " the status responses for every outstanding declaration");
    }

    @Test
    void enteringBackpressureHaltSendsExactlyOneEmptyClearBatch() {
        manager.markCacheLoadedForTest();
        var dim = dim("overworld");
        int halt = LodRequestManager.haltThreshold();
        // viewDistance 90 fully subsumes the lod-64 disc (corners included — see fireScanAtOrigin),
        // so a recovered tick walks and wants nothing. Every batch this test sees is therefore a
        // backpressure clear, never a want-set.
        manager.tickWithContext(0, 0, dim, 90, halt, () -> 0);  // enters halt
        manager.tickWithContext(0, 0, dim, 90, halt, () -> 0);  // still halted

        assertEquals(1, sent.size(), "edge-triggered: exactly one clear per halt episode");
        assertEquals(0, sent.get(0).count(),
                "the clear is the EMPTY want-set — the explicit 'want nothing' that replaces the"
                        + " server backlog with nothing (the only empty batch the protocol allows)");

        manager.tickWithContext(0, 0, dim, 90, 0, () -> 0);     // recovered
        manager.tickWithContext(0, 0, dim, 90, halt, () -> 0);  // re-enters halt

        assertEquals(2, sent.size(), "a new halt episode re-arms the clear");
        assertEquals(0, sent.get(1).count());
    }


    // ---- batch-response dispatch routing ----

    @Test
    void dispatchRoutesEachTypeAndUnknownTypeSkipsOnlyThatEntry() {
        var tracker = manager.trackerForTest();
        long pReserved = PositionUtil.packPosition(1, 0);
        long pUpToDate = PositionUtil.packPosition(2, 0);
        long pNotGen = PositionUtil.packPosition(3, 0);
        long pUnknown = PositionUtil.packPosition(4, 0);
        long pAfterUnknown = PositionUtil.packPosition(5, 0);
        tracker.replaceWith(new long[]{pReserved, pUpToDate, pNotGen, pUnknown, pAfterUnknown}, 5);

        // Byte 0 (v16's retired rate-limited tag) and byte 99 (a hypothetical future type) are
        // both unknown to a v17 client and must behave identically: inert.
        LSSClientNetworking.dispatchBatchResponses(manager, new BatchResponseS2CPayload(
                new byte[]{(byte) 0, LSSConstants.RESPONSE_UP_TO_DATE,
                        LSSConstants.RESPONSE_NOT_GENERATED, (byte) 99, LSSConstants.RESPONSE_UP_TO_DATE},
                new long[]{pReserved, pUpToDate, pNotGen, pUnknown, pAfterUnknown}, 5));

        assertEquals(2, manager.getTotalUpToDate(), "the entry AFTER the unknown type must still dispatch");
        assertEquals(1, manager.getTotalNotGenerated());
        assertEquals(0, manager.getReceivedColumnCount(),
                "up-to-date on never-seen positions is session-satisfied, not stamped");
        assertEquals(0, manager.getEmptyColumnCount(),
                "not-generated writes no 0-stamp — it session-satisfies");
        assertTrue(manager.columnsForTest().isSessionSatisfied(pNotGen));
        assertFalse(tracker.isInFlight(pUpToDate));
        assertFalse(tracker.isInFlight(pNotGen));
        assertFalse(tracker.isInFlight(pAfterUnknown));
        assertTrue(tracker.isInFlight(pUnknown),
                "an unknown response type must not consume the awaiting entry");

        // The RESERVED-byte forward-safety pin. Byte 0 must never again mean anything: it is
        // fully inert, exactly like the unknown byte 99. This is what makes leaving 0 retired
        // (rather than renumbering UP_TO_DATE/NOT_GENERATED down) safe forever — and it is the
        // load-bearing half, because an inert byte 0 that silently SATISFIED a position would
        // be a permanent invisible hole.
        assertEquals(-1L, manager.columnsForTest().classify(pReserved),
                "a reserved-type entry must not stamp, satisfy, or retry-mark its position");
        assertFalse(manager.columnsForTest().hasRetries(), "a reserved-type entry marks no retry");
        assertTrue(tracker.isInFlight(pReserved),
                "a reserved-type entry consumes no awaiting entry — the next scan re-declares it");
    }

    @Test
    void dispatchHonorsCountOverArrayLength() {
        var tracker = manager.trackerForTest();
        long p1 = PositionUtil.packPosition(1, 0);
        long p2 = PositionUtil.packPosition(2, 0);
        tracker.replaceWith(new long[]{p2}, 1);

        // Arrays hold 2 entries but count says 1 — the count field is the batch boundary
        // (server-side send buffers are reused, so trailing slots can hold stale data).
        LSSClientNetworking.dispatchBatchResponses(manager, new BatchResponseS2CPayload(
                new byte[]{LSSConstants.RESPONSE_UP_TO_DATE, LSSConstants.RESPONSE_UP_TO_DATE},
                new long[]{p1, p2}, 1));

        assertEquals(1, manager.getTotalUpToDate());
        assertTrue(tracker.isInFlight(p2), "entries past count are not part of the batch");
    }

    // ---- onIngestFailure ----

    @Test
    void ingestFailureUnstampsSoTheReAskCarriesUnknown() {
        manager.setLastDimensionForTest(dim("overworld"));
        manager.onColumnReceived(POS, 5000L, dim("overworld"));
        assertEquals(SATISFIED, manager.columnsForTest().classify(POS));

        manager.onIngestFailure(dim("overworld"), POS);

        assertEquals(-1L, manager.columnsForTest().classify(POS),
                "the position must re-request with ts=-1 so the server re-serves the data");
        assertTrue(manager.columnsForTest().hasRetries(),
                "retry mark must reset the confirmed ring so the position is rescanned");
        assertEquals(1, manager.getTotalIngestFailures());
    }

    @Test
    void ingestFailureForAnotherDimensionDoesNotTouchTheCurrentMap() {
        manager.setLastDimensionForTest(dim("overworld"));
        manager.onColumnReceived(POS, 5000L, dim("overworld"));

        manager.onIngestFailure(dim("the_end"), POS);

        assertEquals(SATISFIED, manager.columnsForTest().classify(POS),
                "a report for another dimension's column must not unstamp the current map");
        assertEquals(0, manager.getTotalIngestFailures());
    }

    @Test
    void crossDimensionIngestReportUnstampsTheOtherDimensionsSavedCache() {
        var mgr = new LodRequestManager();
        final String server = "test-crossdim-" + System.nanoTime();
        var dimA = dim("overworld");
        mgr.onSessionConfig(config(64, true), server); // sets serverAddress
        try {
            // A false stamp is already persisted in dimA's cache (as saveCache wrote it on the
            // dimension change) — the report arrives after the player moved to dimB.
            var map = new Long2LongOpenHashMap();
            map.put(POS, 5000L);
            ColumnCacheStore.save(server, dimA, map);

            mgr.setLastDimensionForTest(dim("the_end")); // player is now in dimB
            mgr.onIngestFailure(dimA, POS);              // async report for dimA
            ColumnCacheStore.flushPendingIo();

            assertFalse(ColumnCacheStore.load(server, dimA).containsKey(POS),
                    "a cross-dimension report unstamps the false stamp in the other dimension's cache (#36)");
        } finally {
            ColumnCacheStore.clearForServer(server);
        }
    }

    @Test
    void ingestFailureNeverDelaysTheScanCadence() {
        manager.setLastDimensionForTest(dim("overworld"));
        manager.onColumnReceived(POS, 5000L, dim("overworld"));
        advanceToOneCallBeforeScanFire();

        manager.onIngestFailure(dim("overworld"), POS);

        assertTrue(manager.columnsForTest().hasRetries(), "premise: the failure marked retry");
        assertTrue(maybeScanOnce() >= 0,
                "onIngestFailure must not reset the scan counter — a steady failure trickle would"
                        + " push the cadence back indefinitely; the retry mark alone re-reaches the position");
    }

    // ---- send failure: every position stays re-declarable (D1 fix, re-derived for the want-set) ----

    @Test
    void sendFailureLeavesEveryPositionReDeclarableNextScan() {
        var ring = configureRing1Disc();
        manager.setBatchSenderForTest(p -> { throw new IllegalStateException("transport down"); });
        var columns = manager.columnsForTest();
        long held = ring[0];
        columns.onReceived(held, 5000L);            // validated this session
        assertTrue(columns.markDirtyIfKnown(held)); // server says it changed -> classify 5000
        // ring[1..7] are unknown -> classify -1

        assertEquals(8, fireScanRing1(), "the whole want-set is declared; the send throws");

        var tracker = manager.trackerForTest();
        for (long p : ring) {
            assertFalse(tracker.isInFlight(p),
                    "a batch that never reached the wire must not be credited as awaiting, or a"
                            + " late status would be gated in against a declaration that never happened");
        }
        assertEquals(5000L, columns.classify(held),
                "the dirty mark survives the failed send (marks are answer-consumed now, so there"
                        + " is nothing to restore) and re-asks with the STORED timestamp");
        assertEquals(-1L, columns.classify(ring[1]), "no invented timestamps");
        assertEquals(8, manager.getTotalPositionsRequested(), "a failed batch still counts as attempted");

        recordSends(); // transport recovers
        assertEquals(8, fireScanRing1(), "every position in the dead batch reappears in the next want-set");

        assertEquals(1, sent.size());
        assertEquals(8, sent.get(0).count());
        var batch = lastBatch();
        for (long p : ring) assertTrue(batch.contains(p), "position " + p + " was stranded by the failed send");
        for (int i = 0; i < 8; i++) {
            if (sent.get(0).positions()[i] == held) {
                assertEquals(5000L, sent.get(0).timestamps()[i],
                        "the surviving dirty mark re-asks as a resync, not a first serve");
            }
        }
    }

    // ---- terminal up_to_date for an unknown ask (SP-078 client leg) ----

    @Test
    void unknownAskAnsweredUpToDateSatisfiesWithoutStampAndStopsReAsking() {
        var ring = configureRing1Disc();
        long target = ring[0];
        var columns = manager.columnsForTest();
        for (int i = 1; i < ring.length; i++) columns.onReceived(ring[i], 5000L);

        assertEquals(1, fireScanRing1(), "the ts=-1 ask goes out");
        assertEquals(-1L, sent.get(0).timestamps()[0], "premise: declared as an unknown first serve");

        manager.onColumnUpToDate(target); // terminal answer (e.g. oversized column dropped before send)

        assertEquals(-1L, columns.timestampFor(target),
                "an unknown (ts=-1) ask answered up_to_date is session-satisfied — no fabricated stamp");
        assertTrue(columns.isSessionSatisfied(target));
        assertEquals(SATISFIED, columns.classify(target));
        assertFalse(manager.trackerForTest().isInFlight(target));

        assertEquals(0, fireScanRing1(),
                "the client must stop re-asking — the terminal answer converges, no loop");
        assertEquals(1, sent.size(), "convergence sends nothing at all");
    }

    // ---- duplicate positions inside one batch frame resolve consistently ----

    @Test
    void duplicatePositionInOneBatchFrameResolvesConsistently() {
        var tracker = manager.trackerForTest();
        long pA = PositionUtil.packPosition(1, 0);
        long pB = PositionUtil.packPosition(2, 0);
        tracker.replaceWith(new long[]{pA, pB}, 2);

        // Same position twice in one frame — server send buffers are reused, so duplicate entries
        // are legal wire input. The FIRST entry consumes the awaiting entry; the duplicate must
        // then hit the untracked gate and change nothing. That gate is the whole defence: without
        // it the trailing up_to_date below would session-satisfy pB and erase its ts=0 generation
        // retry — a permanent ungenerated hole.
        LSSClientNetworking.dispatchBatchResponses(manager, new BatchResponseS2CPayload(
                new byte[]{LSSConstants.RESPONSE_UP_TO_DATE, (byte) 0,
                        LSSConstants.RESPONSE_NOT_GENERATED, LSSConstants.RESPONSE_UP_TO_DATE},
                new long[]{pA, pA, pB, pB}, 4));

        assertEquals(SATISFIED, manager.columnsForTest().classify(pA),
                "the trailing duplicate (reserved byte 0) must not un-satisfy the up-to-date position");
        assertFalse(tracker.isInFlight(pA));
        assertEquals(SATISFIED, manager.columnsForTest().classify(pB),
                "the first entry (NOT_GENERATED) session-satisfies pB; the trailing duplicate"
                        + " up-to-date arrives untracked and changes nothing — convergent");
        assertTrue(manager.columnsForTest().isSessionSatisfied(pB),
                "parked by the NOT_GENERATED answer, no stamp written");
        assertEquals(-1L, manager.columnsForTest().timestampFor(pB));
        assertFalse(tracker.isInFlight(pB));
        assertEquals(2, manager.getTotalUpToDate(), "every known-type entry still counts in diagnostics");
        assertEquals(1, manager.getTotalNotGenerated());
    }


    // ---- onDirtyColumns: debounce only for known positions, never poisons unknown state ----

    @Test
    void dirtyBroadcastAllUnknownLeavesScanCadenceAndConfirmedRingUntouched() {
        advanceToOneCallBeforeScanFire();
        int confirmedBefore = manager.getConfirmedRing();
        assertTrue(confirmedBefore > 0, "premise: the normalized scan confirmed the excluded disc");

        manager.onDirtyColumns(new long[]{POS, PositionUtil.packPosition(7, 8)}); // nothing known

        assertEquals(0, manager.getDirtyColumnCount(), "unknown positions must not take dirty marks");
        assertEquals(confirmedBefore, manager.getConfirmedRing(),
                "no confirmed-ring reset for a no-op broadcast");
        assertTrue(maybeScanOnce() >= 0,
                "an all-unknown dirty broadcast must not debounce the imminent scan");
    }

    @Test
    void dirtyBroadcastWithKnownPositionDefersExactlyOneScanAndResetsConfirmedRing() {
        manager.onColumnReceived(POS, 5000L, dim("overworld")); // lastDimension null -> applies
        advanceToOneCallBeforeScanFire();
        assertTrue(manager.getConfirmedRing() > 0);

        manager.onDirtyColumns(new long[]{POS});

        assertEquals(1, manager.getDirtyColumnCount());
        assertEquals(0, manager.getConfirmedRing(),
                "a known dirty mark must reset ring confirmation so the position is rescanned");
        assertEquals(-1, maybeScanOnce(), "the imminent scan is deferred by the counter reset");
        for (int i = 0; i < LSSConstants.TICKS_PER_SECOND - 2; i++) {
            assertEquals(-1, maybeScanOnce(), "still inside the single deferred window");
        }
        assertTrue(maybeScanOnce() >= 0,
                "cadence resumes one full window after the broadcast — exactly one deferral");
    }

    @Test
    void notGeneratedResponseDoesNotResetTheConfirmedRing() {
        // CL-014's reset is deliberately GONE under server-owned generation: a NOT_GENERATED
        // position is permanently session-satisfied, so there is no hole below the ring to
        // re-walk — and during a gen-disabled backfill a per-answer reset would force a full
        // re-walk per answer. The stale-crossing path (consumeStaleCrossing) keeps its own
        // reset; that is pinned by dirtyCrossingInFlightAnswer tests, not this one.
        manager.onColumnReceived(POS, 5000L, dim("overworld")); // a known column so the scan confirms
        advanceToOneCallBeforeScanFire();
        int confirmedBefore = manager.getConfirmedRing();
        assertTrue(confirmedBefore > 0, "precondition: ring confirmed");

        manager.trackerForTest().replaceWith(new long[]{POS}, 1); // awaited -> tracked path

        manager.onColumnNotGenerated(POS);

        assertEquals(confirmedBefore, manager.getConfirmedRing(),
                "a not-generated answer must NOT reset ring confirmation — the position is "
                        + "satisfied, and a reset per answer is pure re-walk churn");
    }

    @Test
    void maxSizeDirtyFrameMarksKnownPositionsOnlyAndDebouncesOnce() {
        long known1 = PositionUtil.packPosition(20, 20);
        long known2 = PositionUtil.packPosition(21, 20);
        long known3 = PositionUtil.packPosition(22, 20);
        manager.onColumnReceived(known1, 5000L, dim("overworld"));
        manager.onColumnReceived(known2, 5000L, dim("overworld"));
        manager.onColumnReceived(known3, 5000L, dim("overworld"));
        advanceToOneCallBeforeScanFire();

        var frame = new long[LSSConstants.MAX_DIRTY_COLUMN_POSITIONS]; // the wire-cap frame size
        frame[0] = known1;
        frame[1] = known2;
        frame[2] = known3;
        for (int i = 3; i < frame.length; i++) {
            frame[i] = PositionUtil.packPosition(100_000 + i, 200_000); // unknown filler
        }
        manager.onDirtyColumns(frame);

        assertEquals(3, manager.getDirtyColumnCount(), "only known positions take dirty marks");
        assertEquals(-1L, manager.columnsForTest().timestampFor(frame[3]),
                "unknown positions stay unknown — the scan ladder requests those anyway");
        assertEquals(0, manager.getConfirmedRing());
        assertEquals(-1, maybeScanOnce(), "deferred");
        for (int i = 0; i < LSSConstants.TICKS_PER_SECOND - 2; i++) {
            assertEquals(-1, maybeScanOnce());
        }
        assertTrue(maybeScanOnce() >= 0,
                "a max-size (10240) frame defers exactly one scan window, not one per position");
    }

    @Test
    void emptyDirtyFrameIsACompleteNoOp() {
        manager.onColumnReceived(POS, 5000L, dim("overworld")); // known state that COULD be marked
        advanceToOneCallBeforeScanFire();
        int confirmedBefore = manager.getConfirmedRing();

        assertDoesNotThrow(() -> manager.onDirtyColumns(new long[0]));

        assertEquals(0, manager.getDirtyColumnCount());
        assertEquals(confirmedBefore, manager.getConfirmedRing());
        assertTrue(maybeScanOnce() >= 0, "a zero-length dirty frame must not debounce the scan");
    }

    @Test
    void dirtyPushAfterDimensionChangeNeitherDebouncesNorPoisonsTheFreshMap() {
        var overworld = dim("overworld");
        var end = dim("the_end");
        manager.onSessionConfig(config(64, true), "lss-cl064-" + System.nanoTime());
        manager.markCacheLoadedForTest();
        manager.tickWithContext(0, 0, overworld, 64, 0, () -> 0); // establish dimension A
        manager.onColumnReceived(POS, 5000L, overworld);

        manager.tickWithContext(0, 0, end, 64, 0, () -> 0); // the flip resets all request state
        assertEquals(0, manager.getReceivedColumnCount(), "premise: dimension flip reset the map");

        advanceToOneCallBeforeScanFire();
        manager.onDirtyColumns(new long[]{POS}); // late push listing the OLD dimension's column

        assertEquals(0, manager.getDirtyColumnCount(),
                "old-dimension positions are unknown in the fresh map and must not be marked");
        assertEquals(-1L, manager.columnsForTest().timestampFor(POS));
        assertTrue(maybeScanOnce() >= 0,
                "an all-unknown late push must not debounce the fresh dimension's scan");
    }

    // ---- the want-set carries CURRENT classifications, never a stale claim (delivery-honesty #5) ----

    @Test
    void wantSetCarriesTheClassifyDerivedTimestampNotAStaleClaim() {
        // Pre-want-set this invariant needed an explicit re-derivation in drainQueue, because the
        // scan accepted a position into a queue and the send happened ticks later — an ingest
        // failure landing in that window would otherwise ship the stale scan-time claim. The
        // want-set closes that window BY CONSTRUCTION (classify and send are the same tick), and
        // this test pins that the construction actually holds: no path may reintroduce a stored,
        // pre-classified timestamp.
        var ring = configureRing1Disc();
        long target = ring[0];
        manager.setLastDimensionForTest(dim("overworld"));
        var columns = manager.columnsForTest();
        for (int i = 1; i < ring.length; i++) columns.onReceived(ring[i], 5000L);
        var cached = new Long2LongOpenHashMap();
        cached.put(target, 5000L);
        columns.loadFrom(cached);                          // cached, unvalidated -> classify 5000
        manager.onIngestFailure(dim("overworld"), target); // the consumer never actually held it

        assertEquals(-1L, columns.classify(target), "premise: classification is now unknown");
        assertEquals(1, fireScanRing1());

        assertEquals(1, sent.size());
        assertEquals(target, sent.get(0).positions()[0]);
        assertEquals(-1L, sent.get(0).timestamps()[0],
                "the want-set carries the classify-derived ts (-1 now), NOT the cached 5000L: a"
                        + " ts>0 claim for data the client no longer holds would be answered up_to_date"
                        + " from the server's done-bit, sealing a hole. Sending -1 makes the server"
                        + " re-resolve honestly.");
    }

    // ---- ingest failures racing a pending cache load ----

    @Test
    void ingestFailureDuringPendingCacheLoadIsNotResurrectedByTheLoad() {
        // A consumer rejection (any-thread API, hops to the main thread) lands while the
        // dimension's cache load is still in flight: applied against the empty map it is
        // absorbed, and loadFrom would then resurrect the stale ts>0 stamp — a claim for
        // data no consumer holds, revalidated up_to_date every session (the same-dimension
        // sibling of the WS5/#36 cross-dimension hole).
        manager.setLastDimensionForTest(dim("overworld"));
        var pending = new CompletableFuture<Long2LongOpenHashMap>();
        manager.setPendingCacheLoadForTest(pending);

        manager.onIngestFailure(dim("overworld"), POS); // absorbed by the empty pre-load map

        var loaded = new Long2LongOpenHashMap();
        loaded.put(POS, 5000L);
        pending.complete(loaded);
        assertTrue(manager.tickCacheGatePhase());
        assertEquals(-1L, manager.columnsForTest().timestampFor(POS),
                "the loaded stale stamp must be re-unstamped for the rejected column");
    }

    // ---- flushCache drops an in-flight cache load ----

    @Test
    void flushCacheDropsAPendingCacheLoadResultThatCompletesAfterTheFlush() {
        manager.onSessionConfig(config(64, true), "lss-cl051-" + System.nanoTime());
        manager.setLastDimensionForTest(dim("overworld"));
        var pending = new CompletableFuture<Long2LongOpenHashMap>();
        manager.setPendingCacheLoadForTest(pending);

        manager.flushCache();

        var staleLoad = new Long2LongOpenHashMap();
        staleLoad.put(POS, 5000L);
        pending.complete(staleLoad); // the IO thread finishes the pre-flush load AFTER the flush
        assertTrue(manager.tickCacheGatePhase(), "the gate must not be waiting on the dropped load");
        assertEquals(-1L, manager.columnsForTest().timestampFor(POS),
                "a pre-flush load result must not resurrect flushed timestamps");
        assertEquals(0, manager.getReceivedColumnCount());
    }
}
