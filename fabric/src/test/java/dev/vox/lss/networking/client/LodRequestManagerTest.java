package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.networking.payloads.BatchResponseS2CPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
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
 * Contract tests for the manager's response callbacks and queue drain — the glue the
 * position-keyed (no request id) protocol leans on. Pins three asymmetries that look like
 * bugs but are deliberate:
 * <ul>
 *   <li>Status responses (rate-limited / up-to-date / not-generated) are gated on in-flight
 *       tracking, so a late or duplicate status can never stamp column state the client
 *       never asked about (a wrong SATISFIED stamp is a permanent invisible hole that no
 *       soak conservation law can see).</li>
 *   <li>Column DATA is the opposite: it applies even untracked (self-heal after a client
 *       timeout) but never cross-dimension, because the dispatch drain discards the bytes.</li>
 *   <li>onRateLimited latches the scan backoff BEFORE its tracking gate.</li>
 * </ul>
 * Plus the drain's per-type concurrency: one full limiter type must never head-of-line
 * block the other (skip-continue, not break), and slot accounting must not leak.
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
        manager.onSessionConfig(config(64, 100, 100, true), "lss-test");
    }

    private static SessionConfigS2CPayload config(int lodDistance, int syncLimit, int genLimit,
                                                  boolean generationEnabled) {
        return new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true,
                lodDistance, syncLimit, genLimit, generationEnabled);
    }

    private static ResourceKey<Level> dim(String name) {
        return ResourceKey.create(Registries.DIMENSION, new ResourceLocation("lss_test:" + name));
    }

    /** Seed the manager's request queue with (position, clientTimestamp) pairs. */
    private void seedQueue(long... posTsPairs) {
        var queue = manager.queueForTest();
        int n = posTsPairs.length / 2;
        queue.ensureCapacity(n);
        for (int i = 0; i < n; i++) {
            queue.put(i, posTsPairs[2 * i], posTsPairs[2 * i + 1]);
        }
        queue.commit(n);
    }

    /** Drive maybeScan until the 20-tick cadence fires; returns the queued count. */
    private static int fireScan(SpiralScanner scanner) {
        var columns = new ColumnStateMap();
        var queue = new RequestQueue();
        for (int i = 0; i < LSSConstants.TICKS_PER_SECOND + 1; i++) {
            int n = scanner.maybeScan(0, 0, 2, 0, 1000, () -> 0, columns, pos -> false, queue);
            if (n >= 0) return n;
        }
        throw new AssertionError("scan cadence never fired");
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

    /**
     * One maybeScan call against the manager's own collaborators with viewDistance covering
     * the lod distance, so a fired scan queues nothing — pure cadence observation.
     */
    private int maybeScanOnce() {
        return manager.scannerForTest().maybeScan(0, 0, 64, 0, 1000, () -> 0,
                manager.columnsForTest(), pos -> false, manager.queueForTest());
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
        assertEquals(-1L, manager.columnsForTest().classify(POS, true),
                "an untracked status must not fabricate a not-generated stamp"
                        + " (would trigger spurious generation requests every scan)");
    }

    @Test
    void untrackedUpToDateNeverFabricatesSatisfiedState() {
        manager.onColumnUpToDate(POS);

        assertEquals(1, manager.getTotalUpToDate());
        assertEquals(0, manager.getReceivedColumnCount());
        assertEquals(-1L, manager.columnsForTest().classify(POS, true),
                "a wrongly-SATISFIED stamp would be a permanent invisible hole — no data was ever delivered");
    }

    @Test
    void untrackedRateLimitedNeverPoisonsSatisfiedColumnIntoRetry() {
        var columns = manager.columnsForTest();
        columns.onReceived(POS, 5000L); // satisfied + validated this session

        manager.onRateLimited(POS); // late bounce for a request that already timed out client-side

        assertEquals(1, manager.getTotalRateLimited());
        assertEquals(SATISFIED, columns.classify(POS, true),
                "a late rate-limited must not schedule a pointless re-request of a satisfied column");
    }

    @Test
    void untrackedRateLimitedStillLatchesScanBackoff() {
        manager.onSessionConfig(config(4, 100, 100, true), "lss-test");

        manager.onRateLimited(POS); // untracked — the gate returns early, but backoff latches first

        var scanner = manager.scannerForTest();
        assertEquals(0, fireScan(scanner), "scan after a rate-limited response is skipped");
        assertTrue(fireScan(scanner) > 0, "backoff lasts exactly one scan");
    }

    // ---- dirty crossing an in-flight first serve forces a re-request (#11) ----

    @Test
    void dirtyCrossingAnInFlightFirstServeReRequestsOnUpToDate() {
        var tracker = manager.trackerForTest();
        tracker.markPending(POS, System.nanoTime(), false); // first serve in flight (stored == -1)

        manager.onDirtyColumns(new long[]{POS});            // dirty crosses the in-flight serve
        manager.onColumnUpToDate(POS);                      // the pre-edit (all-air) answer lands

        assertNotEquals(SATISFIED, manager.columnsForTest().classify(POS, true),
                "a dirty that crossed the in-flight first serve must force a re-request, not settle");
    }

    @Test
    void dirtyCrossingAnInFlightFirstServeReRequestsOnReceived() {
        manager.setLastDimensionForTest(dim("overworld"));
        var tracker = manager.trackerForTest();
        tracker.markPending(POS, System.nanoTime(), false);

        manager.onDirtyColumns(new long[]{POS});
        manager.onColumnReceived(POS, 5000L, dim("overworld")); // pre-edit content lands

        assertNotEquals(SATISFIED, manager.columnsForTest().classify(POS, true),
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
        assertEquals(SATISFIED, manager.columnsForTest().classify(POS, true));

        var tracker = manager.trackerForTest();
        tracker.markPending(POS, System.nanoTime(), false); // resync (first edit) in flight

        manager.onDirtyColumns(new long[]{POS});               // second edit crosses the resync
        manager.onColumnReceived(POS, 6000L, dim("overworld")); // first-edit content lands, clears dirty

        assertNotEquals(SATISFIED, manager.columnsForTest().classify(POS, true),
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

        assertEquals(3000L, manager.columnsForTest().classify(POS, true),
                "a rejected clear re-requests with the pre-clear content stamp, not ts=-1");
    }

    // ---- tracked status responses: apply state AND release the in-flight slot ----

    @Test
    void trackedUpToDateStampsValidationAndReleasesSlot() {
        var tracker = manager.trackerForTest();
        tracker.markPending(POS, System.nanoTime(), false);

        manager.onColumnUpToDate(POS);

        assertFalse(tracker.isInFlight(POS), "slot leak would throttle later requests until timeout");
        assertEquals(0, manager.getReceivedColumnCount(),
                "up-to-date on a never-seen position is session-satisfied, not stamped");
        assertEquals(SATISFIED, manager.columnsForTest().classify(POS, true));
        assertTrue(manager.columnsForTest().isSessionSatisfied(POS));
        assertEquals(1, manager.getTotalUpToDate());
    }

    @Test
    void trackedNotGeneratedStampsGenerationRetryAndReleasesSlot() {
        var tracker = manager.trackerForTest();
        tracker.markPending(POS, System.nanoTime(), false);

        manager.onColumnNotGenerated(POS);

        assertFalse(tracker.isInFlight(POS));
        assertEquals(1, manager.getEmptyColumnCount());
        assertEquals(0L, manager.columnsForTest().classify(POS, true),
                "not-generated retries as a generation request");
        assertEquals(1, manager.getTotalNotGenerated());
    }

    @Test
    void trackedRateLimitedMarksRetryAndReleasesSlot() {
        var columns = manager.columnsForTest();
        var tracker = manager.trackerForTest();
        columns.onReceived(POS, 5000L); // a known column being resynced
        tracker.markPending(POS, System.nanoTime(), false);

        manager.onRateLimited(POS);

        assertFalse(tracker.isInFlight(POS));
        assertEquals(5000L, columns.classify(POS, true),
                "bounced request retries with the stored timestamp on a later scan");
        assertEquals(1, manager.getTotalRateLimited());
    }

    // ---- column data: self-heal when untracked, discard cross-dimension ----

    @Test
    void columnDataAppliesEvenWhenNoLongerTracked() {
        manager.setLastDimensionForTest(dim("overworld"));

        manager.onColumnReceived(POS, 5000L, dim("overworld")); // e.g. timed out client-side

        assertEquals(1, manager.getTotalColumnsReceived());
        assertEquals(1, manager.getReceivedColumnCount());
        assertEquals(SATISFIED, manager.columnsForTest().classify(POS, true),
                "data is authoritative for the position; gating on isInFlight reintroduces"
                        + " the timeout -> silent-duplicate -> second-timeout stall");
    }

    @Test
    void trackedColumnDataReleasesInFlightSlot() {
        manager.setLastDimensionForTest(dim("overworld"));
        var tracker = manager.trackerForTest();
        tracker.markPending(POS, System.nanoTime(), false);

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
        assertEquals(-1L, manager.columnsForTest().classify(POS, true),
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

    // ---- drainQueue: per-type concurrency without cross-type starvation ----

    @Test
    void fullGenLimiterDoesNotStarveQueuedSyncRequests() {
        manager.onSessionConfig(config(64, 8, 1, true), "lss-test");
        var tracker = manager.trackerForTest();
        tracker.markPending(PositionUtil.packPosition(50, 50), System.nanoTime(), true); // gen limit (1) full

        long gen1 = PositionUtil.packPosition(1, 0);
        long gen2 = PositionUtil.packPosition(2, 0);
        long sync1 = PositionUtil.packPosition(3, 0);
        long sync2 = PositionUtil.packPosition(4, 0);
        // drainQueue re-derives the send ts from classify, so a gen request (ts=0) must have a
        // not-generated column state; sync requests (ts=-1) stay unstamped.
        manager.columnsForTest().onNotGenerated(gen1);
        manager.columnsForTest().onNotGenerated(gen2);
        seedQueue(gen1, 0L, gen2, 0L, sync1, -1L, sync2, -1L); // gen requests head the queue

        int sent = manager.drainQueue(16);

        assertEquals(2, sent, "sync requests behind the bounced gen head must still send");
        assertTrue(tracker.isInFlight(sync1));
        assertTrue(tracker.isInFlight(sync2));
        assertFalse(tracker.isInFlight(gen1), "gen request bounced by the full gen limiter");
        assertFalse(tracker.isInFlight(gen2));
        assertEquals(1, tracker.generationCount(), "only the pre-existing generation slot is held");
        assertEquals(0, manager.getQueueRemaining(), "bounced entries are consumed, not retained");
    }

    @Test
    void fullSyncLimiterDoesNotStarveQueuedGenerationRequests() {
        manager.onSessionConfig(config(64, 1, 8, true), "lss-test");
        var tracker = manager.trackerForTest();
        tracker.markPending(PositionUtil.packPosition(50, 50), System.nanoTime(), false); // sync limit (1) full

        long sync1 = PositionUtil.packPosition(1, 0);
        long gen1 = PositionUtil.packPosition(2, 0);
        long gen2 = PositionUtil.packPosition(3, 0);
        // Re-derived drain ts: gen requests need not-generated column state (ts=0).
        manager.columnsForTest().onNotGenerated(gen1);
        manager.columnsForTest().onNotGenerated(gen2);
        seedQueue(sync1, -1L, gen1, 0L, gen2, 0L);

        int sent = manager.drainQueue(16);

        assertEquals(2, sent, "generation requests behind the bounced sync head must still send");
        assertFalse(tracker.isInFlight(sync1));
        assertTrue(tracker.isInFlight(gen1));
        assertTrue(tracker.isInFlight(gen2));
        assertEquals(2, tracker.generationCount(), "both sends must be accounted as generation slots");
    }

    @Test
    void generationSlotsDoNotCountAgainstSyncCapacity() {
        manager.onSessionConfig(config(64, 1, 8, true), "lss-test");
        var tracker = manager.trackerForTest();
        tracker.markPending(PositionUtil.packPosition(50, 50), System.nanoTime(), true); // size()==1, but a GEN slot

        long sync1 = PositionUtil.packPosition(1, 0);
        seedQueue(sync1, -1L);

        assertEquals(1, manager.drainQueue(16),
                "sync capacity is size() - generationCount(); a held gen slot must not consume it");
        assertTrue(tracker.isInFlight(sync1));
    }

    @Test
    void drainStopsAtMaxToSendAndKeepsRemainder() {
        seedQueue(PositionUtil.packPosition(1, 0), -1L,
                PositionUtil.packPosition(2, 0), -1L,
                PositionUtil.packPosition(3, 0), -1L,
                PositionUtil.packPosition(4, 0), -1L,
                PositionUtil.packPosition(5, 0), -1L);

        assertEquals(2, manager.drainQueue(2));
        assertEquals(3, manager.getQueueRemaining(), "unsent entries drip-feed on later ticks");
        assertEquals(2, manager.getPendingCount());

        assertEquals(3, manager.drainQueue(16), "a later drain picks up exactly the remainder");
        assertEquals(0, manager.getQueueRemaining());
        assertEquals(5, manager.getPendingCount());
    }

    @Test
    void drainSkipsInFlightAndSatisfiedEntriesWithoutResending() {
        var tracker = manager.trackerForTest();
        long inflight = PositionUtil.packPosition(1, 0);
        long satisfied = PositionUtil.packPosition(2, 0);
        long fresh = PositionUtil.packPosition(3, 0);
        tracker.markPending(inflight, System.nanoTime(), false);
        manager.columnsForTest().onReceived(satisfied, 5000L);

        seedQueue(inflight, -1L, satisfied, 5000L, fresh, -1L);

        assertEquals(1, manager.drainQueue(16), "only the fresh position needs a request");
        assertTrue(tracker.isInFlight(fresh));
        assertEquals(2, tracker.size(), "in-flight and satisfied entries were not (re)marked");
    }

    @Test
    void drainConsumesRetryAndDirtyMarksOnSend() {
        var columns = manager.columnsForTest();
        columns.onReceived(POS, 5000L);
        columns.markRetry(POS);
        assertTrue(columns.markDirtyIfKnown(POS));
        seedQueue(POS, 5000L);

        assertEquals(1, manager.drainQueue(16));

        assertEquals(SATISFIED, columns.classify(POS, true),
                "the on-the-wire request consumed the retry/dirty marks — no double re-request later");
        assertEquals(0, manager.getDirtyColumnCount());
        assertTrue(manager.trackerForTest().isInFlight(POS));
    }

    // ---- timeout sweep: the orphan-rescue wiring tick() runs on the scan cadence ----

    @Test
    void sweepTimeoutsEvictsStaleRequestsAndMarksThemForRetry() {
        var tracker = manager.trackerForTest();
        long stale = PositionUtil.packPosition(1, 0);
        long fresh = PositionUtil.packPosition(2, 0);
        tracker.markPending(stale, System.nanoTime() - 11_000L * LSSConstants.NANOS_PER_MS, false);
        tracker.markPending(fresh, System.nanoTime(), false);

        manager.sweepTimeouts();

        assertFalse(tracker.isInFlight(stale), "timed-out request must release its in-flight slot");
        assertTrue(tracker.isInFlight(fresh), "fresh request must survive the sweep");
        assertTrue(manager.columnsForTest().hasRetries(),
                "eviction must mark retry: in-flight counts as satisfied for ring confirmation,"
                        + " so an unmarked eviction inside a confirmed ring is never rescanned"
                        + " (the bandwidth-throttle soak's 161 permanently orphaned columns)");
    }

    // ---- batch-response dispatch routing ----

    @Test
    void dispatchRoutesEachTypeAndUnknownTypeSkipsOnlyThatEntry() {
        var tracker = manager.trackerForTest();
        long pRate = PositionUtil.packPosition(1, 0);
        long pUpToDate = PositionUtil.packPosition(2, 0);
        long pNotGen = PositionUtil.packPosition(3, 0);
        long pUnknown = PositionUtil.packPosition(4, 0);
        long pAfterUnknown = PositionUtil.packPosition(5, 0);
        long now = System.nanoTime();
        for (long p : new long[]{pRate, pUpToDate, pNotGen, pUnknown, pAfterUnknown}) {
            tracker.markPending(p, now, false);
        }

        LSSClientNetworking.dispatchBatchResponses(manager, new BatchResponseS2CPayload(
                new byte[]{LSSConstants.RESPONSE_RATE_LIMITED, LSSConstants.RESPONSE_UP_TO_DATE,
                        LSSConstants.RESPONSE_NOT_GENERATED, (byte) 99, LSSConstants.RESPONSE_UP_TO_DATE},
                new long[]{pRate, pUpToDate, pNotGen, pUnknown, pAfterUnknown}, 5));

        assertEquals(1, manager.getTotalRateLimited());
        assertEquals(2, manager.getTotalUpToDate(), "the entry AFTER the unknown type must still dispatch");
        assertEquals(1, manager.getTotalNotGenerated());
        assertEquals(0, manager.getReceivedColumnCount(),
                "up-to-date on never-seen positions is session-satisfied, not stamped");
        assertEquals(1, manager.getEmptyColumnCount(), "not-generated entry stamped");
        assertFalse(tracker.isInFlight(pRate));
        assertFalse(tracker.isInFlight(pUpToDate));
        assertFalse(tracker.isInFlight(pNotGen));
        assertFalse(tracker.isInFlight(pAfterUnknown));
        assertTrue(tracker.isInFlight(pUnknown),
                "an unknown response type must not consume the in-flight slot");
    }

    @Test
    void dispatchHonorsCountOverArrayLength() {
        var tracker = manager.trackerForTest();
        long p1 = PositionUtil.packPosition(1, 0);
        long p2 = PositionUtil.packPosition(2, 0);
        tracker.markPending(p2, System.nanoTime(), false);

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
        assertEquals(SATISFIED, manager.columnsForTest().classify(POS, true));

        manager.onIngestFailure(dim("overworld"), POS);

        assertEquals(-1L, manager.columnsForTest().classify(POS, true),
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

        assertEquals(SATISFIED, manager.columnsForTest().classify(POS, true),
                "a report for another dimension's column must not unstamp the current map");
        assertEquals(0, manager.getTotalIngestFailures());
    }

    @Test
    void crossDimensionIngestReportUnstampsTheOtherDimensionsSavedCache() {
        var mgr = new LodRequestManager();
        final String server = "test-crossdim-" + System.nanoTime();
        var dimA = dim("overworld");
        mgr.onSessionConfig(config(64, 100, 100, true), server); // sets serverAddress
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

    // ---- send failure: marks restored so failed batches never strand positions (D1 fix) ----

    @Test
    void sendFailureRestoresRequestabilityForEveryPositionInTheFailedBatch() {
        manager.setBatchSenderForTest(p -> { throw new IllegalStateException("transport down"); });
        var columns = manager.columnsForTest();
        long unknownPos = PositionUtil.packPosition(7, 7);
        columns.onReceived(POS, 5000L);            // validated this session
        assertTrue(columns.markDirtyIfKnown(POS)); // server says it changed
        seedQueue(POS, 5000L, unknownPos, -1L);

        assertEquals(2, manager.tickDrainPhase()); // drain consumes the marks, the send throws

        var tracker = manager.trackerForTest();
        assertFalse(tracker.isInFlight(POS), "failed-batch slots must be released");
        assertFalse(tracker.isInFlight(unknownPos));
        assertEquals(5000L, columns.classify(POS, true),
                "markSent consumed the dirty mark, so without restoration this validated position"
                        + " classifies SATISFIED with its request lost in the dead batch — a permanent"
                        + " hole; the restored mark re-asks with the stored timestamp");
        assertEquals(-1L, columns.classify(unknownPos, true), "no invented timestamps");
        assertTrue(columns.hasRetries(),
                "restored marks must reset ring confirmation so the scanner re-reaches the positions");
        assertEquals(2, manager.getTotalPositionsRequested(), "a failed batch still counts as attempted");

        recordSends(); // transport recovers
        seedQueue(POS, 5000L);
        assertEquals(1, manager.tickDrainPhase(), "the restored position re-sends once the transport recovers");
        assertEquals(5000L, sent.get(0).timestamps()[0]);
    }

    // ---- terminal up_to_date for an unknown ask (SP-078 client leg) ----

    @Test
    void unknownAskAnsweredUpToDateSatisfiesWithoutStampAndStopsReAsking() {
        seedQueue(POS, -1L);
        assertEquals(1, manager.drainQueue(16), "the ts=-1 ask goes out");

        manager.onColumnUpToDate(POS); // terminal server answer (e.g. oversized column dropped before send)

        assertEquals(-1L, manager.columnsForTest().timestampFor(POS),
                "an unknown (ts=-1) ask answered up_to_date is session-satisfied — no fabricated stamp");
        assertTrue(manager.columnsForTest().isSessionSatisfied(POS));
        assertEquals(SATISFIED, manager.columnsForTest().classify(POS, true));
        assertFalse(manager.trackerForTest().isInFlight(POS));
        seedQueue(POS, -1L);
        assertEquals(0, manager.drainQueue(16),
                "the client must stop re-asking — the terminal answer converges, no loop");
    }

    // ---- duplicate positions inside one batch frame resolve consistently ----

    @Test
    void duplicatePositionInOneBatchFrameResolvesConsistently() {
        var tracker = manager.trackerForTest();
        long pA = PositionUtil.packPosition(1, 0);
        long pB = PositionUtil.packPosition(2, 0);
        tracker.markPending(pA, System.nanoTime(), false);
        tracker.markPending(pB, System.nanoTime(), false);

        // Same position twice in one frame, both orders — server send buffers are reused,
        // so duplicate entries are legal wire input. The first entry consumes the in-flight
        // slot; the duplicate must hit the untracked gate and change nothing.
        LSSClientNetworking.dispatchBatchResponses(manager, new BatchResponseS2CPayload(
                new byte[]{LSSConstants.RESPONSE_UP_TO_DATE, LSSConstants.RESPONSE_RATE_LIMITED,
                        LSSConstants.RESPONSE_RATE_LIMITED, LSSConstants.RESPONSE_UP_TO_DATE},
                new long[]{pA, pA, pB, pB}, 4));

        assertEquals(SATISFIED, manager.columnsForTest().classify(pA, true),
                "the trailing duplicate rate-limited must not un-satisfy the up-to-date position");
        assertFalse(tracker.isInFlight(pA));
        assertEquals(-1L, manager.columnsForTest().timestampFor(pB),
                "the trailing duplicate up-to-date must not fabricate a stamp for the bounced request");
        assertFalse(tracker.isInFlight(pB));
        assertTrue(manager.columnsForTest().hasRetries(),
                "the bounced position retries on a later scan — convergent, never half-applied");
        assertEquals(2, manager.getTotalUpToDate(), "every entry still counts in diagnostics");
        assertEquals(2, manager.getTotalRateLimited());
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
    void notGeneratedResponseResetsConfirmedRingSoTheStrandedHoleIsRewalked() {
        // CL-014 end-to-end wiring: a not-generated response must force a confirmed-ring re-walk so
        // a stationary player does not strand an ungenerated hole below the ring. SpiralScannerTest
        // pins resetConfirmedRing() in isolation; this pins that onColumnNotGenerated actually calls
        // it (deleting LodRequestManager's scanner.resetConfirmedRing() leaves that suite green).
        manager.onColumnReceived(POS, 5000L, dim("overworld")); // a known column so the scan confirms
        advanceToOneCallBeforeScanFire();
        assertTrue(manager.getConfirmedRing() > 0, "precondition: ring confirmed past the hole");

        manager.trackerForTest().markPending(POS, System.nanoTime(), false); // in-flight -> tracked path

        manager.onColumnNotGenerated(POS);

        assertEquals(0, manager.getConfirmedRing(),
                "a not-generated response must reset ring confirmation so the hole is re-walked");
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
        manager.onSessionConfig(config(64, 100, 100, true), "lss-cl064-" + System.nanoTime());
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

    // ---- drain RE-DERIVES the timestamp at send time (delivery-honesty #5) ----

    @Test
    void drainReDerivesTheTimestampFromClassifyAtSendTime() {
        recordSends();
        manager.setLastDimensionForTest(dim("overworld"));
        var cached = new Long2LongOpenHashMap();
        cached.put(POS, 5000L);
        manager.columnsForTest().loadFrom(cached);      // cached, unvalidated -> classify 5000
        seedQueue(POS, 5000L);                          // the scan queued the resync claim
        manager.onIngestFailure(dim("overworld"), POS); // failure lands between scan and drain

        assertEquals(-1L, manager.columnsForTest().classify(POS, true),
                "premise: classification changed to unknown after the queue accept");
        assertEquals(1, manager.tickDrainPhase());

        assertEquals(1, sent.size());
        assertEquals(POS, sent.get(0).positions()[0]);
        assertEquals(-1L, sent.get(0).timestamps()[0],
                "the drain re-derives the send ts from classify (-1 now), NOT the stale scan-time"
                        + " 5000L: a ts>0 claim for data the client no longer holds would be answered"
                        + " up_to_date from the server's done-bit, sealing a hole. Sending -1 makes the"
                        + " server re-resolve honestly.");
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
        manager.onSessionConfig(config(64, 100, 100, true), "lss-cl051-" + System.nanoTime());
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
