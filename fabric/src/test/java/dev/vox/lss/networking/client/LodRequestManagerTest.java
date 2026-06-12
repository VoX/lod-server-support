package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.networking.payloads.BatchResponseS2CPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse("lss_test:" + name));
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

    // ---- tracked status responses: apply state AND release the in-flight slot ----

    @Test
    void trackedUpToDateStampsValidationAndReleasesSlot() {
        var tracker = manager.trackerForTest();
        tracker.markPending(POS, System.nanoTime(), false);

        manager.onColumnUpToDate(POS);

        assertFalse(tracker.isInFlight(POS), "slot leak would throttle later requests until timeout");
        assertEquals(1, manager.getReceivedColumnCount(), "up-to-date stamps the never-seen position");
        assertEquals(SATISFIED, manager.columnsForTest().classify(POS, true));
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
        assertEquals(2, manager.getReceivedColumnCount(), "both up-to-date entries stamped");
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
    void ingestFailureForAnotherDimensionIsIgnored() {
        manager.setLastDimensionForTest(dim("overworld"));
        manager.onColumnReceived(POS, 5000L, dim("overworld"));

        manager.onIngestFailure(dim("the_end"), POS);

        assertEquals(SATISFIED, manager.columnsForTest().classify(POS, true),
                "a report for another dimension's column must not unstamp the current map");
        assertEquals(0, manager.getTotalIngestFailures());
    }
}
