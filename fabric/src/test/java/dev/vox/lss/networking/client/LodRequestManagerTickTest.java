package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for the tick phases ({@link LodRequestManager#tickWithContext}) — the fixed
 * phase order (dimension/cache → movement → metrics → backpressure → cache gate → scan+sweep →
 * drain+send), the derived per-tick send cap, and the lifecycle exits (movement prune,
 * dimension change, disconnect) that must never silently orphan a position. Also pins two
 * deliberate quirks of the movement phase (see its javadoc): the (0,0) lastChunk init cancels
 * the primed first scan for any player joining outside chunk (0,0), and boundary crossings
 * faster than the 20-tick cadence starve both scans and the timeout sweep that rides them.
 */
class LodRequestManagerTickTest {

    private LodRequestManager manager;

    /** Copies of sent batches — the manager reuses its send buffers across ticks. */
    record SentBatch(long[] positions, long[] timestamps, int count) {}

    private final List<SentBatch> sent = new ArrayList<>();

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        setupManager(config(2, 100, 100, true));
    }

    private void setupManager(SessionConfigS2CPayload cfg) {
        setupManager(cfg, "lss-tick-test");
    }

    private void setupManager(SessionConfigS2CPayload cfg, String serverAddress) {
        manager = new LodRequestManager();
        manager.onSessionConfig(cfg, serverAddress);
        manager.markCacheLoadedForTest();
        sent.clear();
        manager.setBatchSenderForTest(p -> sent.add(new SentBatch(
                Arrays.copyOf(p.packedPositions(), p.count()),
                Arrays.copyOf(p.clientTimestamps(), p.count()),
                p.count())));
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

    /** Drive tickScanPhase until the 20-tick cadence fires; returns the scanned count. */
    private int fireScanPhase(int playerCx, int playerCz, int viewDistance) {
        for (int i = 0; i <= LSSConstants.TICKS_PER_SECOND; i++) {
            int n = manager.tickScanPhase(playerCx, playerCz, viewDistance, 0, () -> 0);
            if (n >= 0) return n;
        }
        throw new AssertionError("scan cadence never fired");
    }

    // ---- first-tick movement quirk (CL-002, pinned) ----

    @Test
    void firstTickAtChunkOriginRunsThePrimedScanImmediately() {
        manager.tickWithContext(0, 0, dim("overworld"), 0, 0, () -> 0);

        assertEquals(1, sent.size(), "a (0,0) join keeps the primed immediate first scan");
        assertEquals(16, sent.get(0).count(), "lod 2 / vd 0 annulus (24) drains at the 16 floor");
        assertEquals(8, manager.getQueueRemaining());
    }

    @Test
    void firstTickOutsideChunkOriginCancelsThePrimedImmediateScan() {
        // Pinned quirk (tickMovementPhase javadoc): lastChunkX/Z init to (0,0), so a player
        // joining anywhere else takes the movement branch on tick 1, which restarts the primed
        // join cadence — the first scan lands a full window after join instead of immediately.
        var overworld = dim("overworld");

        manager.tickWithContext(10, 0, overworld, 0, 0, () -> 0);
        assertEquals(0, sent.size(), "tick-1 scan cancelled by the first-tick movement branch");
        assertEquals(0, manager.getQueueRemaining());

        for (int i = 0; i < LSSConstants.TICKS_PER_SECOND - 2; i++) {
            manager.tickWithContext(10, 0, overworld, 0, 0, () -> 0);
        }
        assertEquals(0, sent.size(), "still inside the restarted cadence window");

        manager.tickWithContext(10, 0, overworld, 0, 0, () -> 0); // 20th tick after the reset
        assertEquals(1, sent.size(), "the first scan fires one full window after join");
    }

    // ---- cadence starvation under boundary crossing (CL-003, pinned) ----

    @Test
    void chunkBoundaryCrossingFasterThanTheCadenceStarvesScansAndSweepsUntilRest() {
        var overworld = dim("overworld");
        var tracker = manager.trackerForTest();
        long stale = PositionUtil.packPosition(5, 5);
        tracker.markPending(stale, System.nanoTime() - 11_000L * LSSConstants.NANOS_PER_MS, false);

        // Cross a chunk boundary every 10 ticks for 60 ticks: each crossing restarts the cadence.
        for (int i = 0; i < 60; i++) {
            int cx = 1 - (i / 10) % 2;
            manager.tickWithContext(cx, 0, overworld, 0, 0, () -> 0);
        }
        assertEquals(0, sent.size(),
                "pinned coupling: scans starve while boundary crossings outpace the 20-tick cadence");
        assertTrue(tracker.isInFlight(stale),
                "pinned coupling: the timeout sweep rides the scan cadence, so it starves too");

        // Resting at the last position recovers both within one window.
        for (int i = 0; i < LSSConstants.TICKS_PER_SECOND; i++) {
            manager.tickWithContext(0, 0, overworld, 0, 0, () -> 0);
        }
        assertFalse(tracker.isInFlight(stale), "rest restores the timeout sweep");
        assertTrue(manager.columnsForTest().hasRetries(), "the evicted request is marked for retry");
        assertFalse(sent.isEmpty(), "rest restores scanning");
    }

    // ---- backoff-skipped scans still sweep; backoffs never compound (CL-004 tick leg) ----

    @Test
    void backoffSkippedScanStillSweepsTimeoutsAndBackoffsDoNotCompound() {
        var overworld = dim("overworld");
        var tracker = manager.trackerForTest();
        long stale = PositionUtil.packPosition(5, 5);
        tracker.markPending(stale, System.nanoTime() - 11_000L * LSSConstants.NANOS_PER_MS, false);
        manager.onRateLimited(PositionUtil.packPosition(40, 40)); // latch backoff...
        manager.onRateLimited(PositionUtil.packPosition(41, 40)); // ...twice (must not compound)

        manager.tickWithContext(0, 0, overworld, 0, 0, () -> 0); // primed cadence fires: skipped scan
        assertEquals(0, sent.size(), "the backoff skipped the scan");
        assertEquals(0, manager.getQueueRemaining());
        assertFalse(tracker.isInFlight(stale), "a skipped scan must still run the timeout sweep");
        assertTrue(manager.columnsForTest().hasRetries());

        for (int i = 0; i < LSSConstants.TICKS_PER_SECOND; i++) {
            manager.tickWithContext(0, 0, overworld, 0, 0, () -> 0);
        }
        assertFalse(sent.isEmpty(), "two rate-limited responses cost one skipped scan, not two");
    }

    // ---- per-tick send cap derivation (CL-021) ----

    @Test
    void sendPerTickDerivesFromScannedCountWithFloorSixteen() {
        // Floor: 24 scanned -> ceil(24/20)=2, floored to MIN_SEND_PER_TICK.
        manager.tickWithContext(0, 0, dim("overworld"), 0, 0, () -> 0);
        assertEquals(1, sent.size());
        assertEquals(16, sent.get(0).count(), "small scans drain at the 16 floor");
        assertEquals(8, manager.getQueueRemaining(), "the remainder drip-feeds on later ticks");

        // Boundary: 320 scanned -> ceil(320/20) = 16 exactly.
        setupManager(config(10, 80, 100, true)); // budget 80*4 = 320, rings 1..10 hold 440
        assertEquals(320, fireScanPhase(0, 0, 0), "premise: budget-capped scan count");
        assertEquals(16, manager.tickDrainPhase());

        // Above the floor: 324 scanned -> ceil(324/20) = 17.
        setupManager(config(10, 81, 100, true)); // budget 324
        assertEquals(324, fireScanPhase(0, 0, 0));
        assertEquals(17, manager.tickDrainPhase(), "the derived cap is ceil(scanned/20) above the floor");
        assertEquals(17, sent.get(0).count());
    }

    @Test
    void oneTickNeverSendsMoreThanTheProtocolBatchCap() {
        setupManager(config(512, 10_000, 100, true)); // budget 40000 — a huge backfill scan
        assertEquals(40_000, fireScanPhase(0, 0, 0), "premise: scan filled the whole budget");

        int sentCount = manager.tickDrainPhase();

        assertEquals(LSSConstants.MAX_BATCH_CHUNK_REQUESTS, sentCount,
                "the derived send rate is capped at the protocol batch limit — the fixed send"
                        + " buffers are sized to it, so exceeding it would overflow them");
        assertEquals(LSSConstants.MAX_BATCH_CHUNK_REQUESTS, sent.get(0).count());
        assertEquals(40_000 - LSSConstants.MAX_BATCH_CHUNK_REQUESTS, manager.getQueueRemaining());
    }

    // ---- lifecycle exits inform the state map — no silent orphans (CL-025 matrix rows) ----

    @Test
    void movementPruneDropsOutOfRangeColumnsAndTrackingTogether() {
        var overworld = dim("overworld");
        long far = PositionUtil.packPosition(500, 500);
        long near = PositionUtil.packPosition(1, 1);
        manager.onColumnReceived(far, 5000L, overworld);
        manager.onColumnReceived(near, 5000L, overworld);
        manager.trackerForTest().markPending(far, System.nanoTime(), false);

        manager.tickWithContext(3, 0, overworld, 64, 0, () -> 0); // movement: prune at lod+32 = 34

        assertEquals(-1L, manager.columnsForTest().timestampFor(far),
                "out-of-range stamp pruned (re-requested as unknown when back in range)");
        assertFalse(manager.trackerForTest().isInFlight(far),
                "out-of-range in-flight tracking pruned with it — no slot leak");
        assertEquals(5000L, manager.columnsForTest().timestampFor(near), "in-range state survives");
        assertEquals(1, manager.getReceivedColumnCount());
    }

    @Test
    void dimensionChangeResetsAllRequestStateWithoutSilentOrphans() {
        var overworld = dim("overworld");
        var end = dim("the_end");
        setupManager(config(2, 100, 100, true), "lss-cl025-" + System.nanoTime());
        manager.tickWithContext(0, 0, overworld, 64, 0, () -> 0); // establish dimension A
        long stamped = PositionUtil.packPosition(1, 1);
        long inFlightOnly = PositionUtil.packPosition(2, 1);
        manager.onColumnReceived(stamped, 5000L, overworld);
        manager.trackerForTest().markPending(inFlightOnly, System.nanoTime(), false);
        seedQueue(PositionUtil.packPosition(2, 2), -1L);

        manager.tickWithContext(0, 0, end, 64, 0, () -> 0); // the flip

        assertEquals(0, manager.getPendingCount(), "no in-flight survivor can stamp the fresh dimension");
        assertEquals(0, manager.getQueueRemaining(), "queued old-dimension asks die with the session state");
        assertEquals(0, manager.getReceivedColumnCount());
        assertEquals(-1L, manager.columnsForTest().timestampFor(stamped),
                "wholesale reset: the old stamp lives on only in the saved cache");
        assertEquals(-1L, manager.columnsForTest().classify(inFlightOnly, true),
                "a position in flight at the flip stays unknown -> re-requested, never orphaned");
        assertFalse(manager.columnsForTest().hasRetries(), "no stale retry marks leak into the fresh map");
        assertEquals(0, sent.size(), "nothing was sent around the flip");
        assertEquals(1, manager.getTotalColumnsReceived(),
                "lifetime totals survive the flip (soak A1/A6 anchors; only rolling rates reset)");
    }

    @Test
    void disconnectClearsInFlightTrackingButKeepsColumnStampsForTheCacheSave() {
        var overworld = dim("overworld");
        long stamped = PositionUtil.packPosition(1, 1);
        long inFlightOnly = PositionUtil.packPosition(2, 1);
        manager.setLastDimensionForTest(overworld);
        manager.onColumnReceived(stamped, 5000L, overworld);
        manager.trackerForTest().markPending(inFlightOnly, System.nanoTime(), false);

        manager.disconnect();

        assertEquals(0, manager.getPendingCount(), "disconnect drops all in-flight tracking");
        assertEquals(5000L, manager.columnsForTest().timestampFor(stamped),
                "stamps survive disconnect — the session-gate teardown saves them to the cache next");
        assertEquals(-1L, manager.columnsForTest().classify(inFlightOnly, true),
                "an answer-less in-flight position stays unknown -> the next session re-requests it");
        assertFalse(manager.columnsForTest().hasRetries(),
                "no retry marks at disconnect: the session is over, nothing left to rescan");
    }

    // ---- backpressure halt order (CL-070) ----

    @Test
    void backpressureHaltsBeforeCachePollSweepAndDrain() {
        var overworld = dim("overworld");
        var tracker = manager.trackerForTest();
        long stale = PositionUtil.packPosition(5, 5);
        long cached = PositionUtil.packPosition(1, 1);
        tracker.markPending(stale, System.nanoTime() - 11_000L * LSSConstants.NANOS_PER_MS, false);
        var loaded = new Long2LongOpenHashMap();
        loaded.put(cached, 5000L);
        manager.setPendingCacheLoadForTest(CompletableFuture.completedFuture(loaded));
        seedQueue(PositionUtil.packPosition(2, 2), -1L);

        int halt = ClientColumnProcessor.MAX_QUEUED_COLUMNS * 3 / 4;
        assertEquals(6000, halt, "pinned threshold: 3/4 of the 8000-column decode queue");
        manager.tickWithContext(0, 0, overworld, 64, halt, () -> 0); // exactly at threshold: halted

        assertEquals(-1L, manager.columnsForTest().timestampFor(cached),
                "cache poll skipped while backpressured");
        assertTrue(tracker.isInFlight(stale), "timeout sweep skipped while backpressured");
        assertEquals(0, sent.size(), "drain skipped while backpressured");
        assertEquals(1, manager.getQueueRemaining());

        manager.tickWithContext(0, 0, overworld, 64, halt - 1, () -> 0); // one below: all resume

        assertEquals(5000L, manager.columnsForTest().timestampFor(cached), "cache result applied");
        assertFalse(tracker.isInFlight(stale), "sweep evicted the stale request");
        assertTrue(manager.columnsForTest().hasRetries());
        assertEquals(1, sent.size(), "the queued ask went out");
        assertEquals(PositionUtil.packPosition(2, 2), sent.get(0).positions()[0]);
    }

    @Test
    void movementPruneStillRunsOnABackpressuredTick() {
        var overworld = dim("overworld");
        long far = PositionUtil.packPosition(500, 500);
        manager.onColumnReceived(far, 5000L, overworld);

        manager.tickWithContext(3, 0, overworld, 64,
                ClientColumnProcessor.MAX_QUEUED_COLUMNS * 3 / 4, () -> 0);

        assertEquals(-1L, manager.columnsForTest().timestampFor(far),
                "the movement prune precedes the backpressure halt — state never goes stale"
                        + " just because the decode queue is full");
    }

    // ---- queue is exempt from the movement prune (CL-071, pinned) ----

    @Test
    void stalePreTeleportQueueEntriesStillSendOnceAndAreAbsorbedByTheServerGuard() {
        var overworld = dim("overworld");
        long far = PositionUtil.packPosition(500, 500);
        manager.onColumnReceived(far, 5000L, overworld);
        seedQueue(far, 5000L); // scanned before the teleport

        manager.tickWithContext(300, 0, overworld, 64, 0, () -> 0); // teleport

        assertEquals(-1L, manager.columnsForTest().timestampFor(far), "premise: column state pruned");
        assertEquals(1, sent.size(),
                "the committed queue entry is exempt from the prune and still goes out —"
                        + " the server's distance guard absorbs it");
        assertEquals(far, sent.get(0).positions()[0]);
        assertEquals(-1L, sent.get(0).timestamps()[0],
                "the drain re-derives the ts from classify at send time: the pruned position is now"
                        + " unknown (-1), and the server distance guard absorbs it regardless — CL-071's"
                        + " send-once + in-flight-churn-bound essence holds");
        assertTrue(manager.trackerForTest().isInFlight(far));

        seedQueue(far, 5000L);
        manager.tickWithContext(300, 0, overworld, 64, 0, () -> 0);
        assertEquals(1, sent.size(),
                "churn bound: in-flight tracking stops a duplicate send — one ask per stale entry");
    }

    // ---- generation toggle suppression at queue/drain level (CG-029) ----

    @Test
    void generationDisabledQueuesAndSendsZeroGenRequestsAndReEnableResumes() {
        setupManager(config(2, 100, 100, false)); // generation disabled
        long notGen = PositionUtil.packPosition(1, 1);
        manager.trackerForTest().markPending(notGen, System.nanoTime(), false);
        manager.onColumnNotGenerated(notGen); // server said not-generated -> stored ts == 0

        assertEquals(23, fireScanPhase(0, 0, 0),
                "the parked ts==0 position is excluded from the scan (24-position annulus minus it)");
        while (manager.getQueueRemaining() > 0) {
            manager.tickDrainPhase();
        }
        assertEquals(0, manager.trackerForTest().generationCount(),
                "zero generation requests sent while disabled");
        for (var batch : sent) {
            for (int i = 0; i < batch.count(); i++) {
                assertNotEquals(0L, batch.timestamps()[i],
                        "no ts==0 (generation) ask may leave the client while generation is disabled");
                assertNotEquals(notGen, batch.positions()[i], "the parked position is never asked");
            }
        }

        // The toggle flip arrives as a fresh session config; the cache restores the ts==0 stamp.
        setupManager(config(2, 100, 100, true));
        var restored = new Long2LongOpenHashMap();
        restored.put(notGen, 0L);
        manager.columnsForTest().loadFrom(restored);
        assertEquals(24, fireScanPhase(0, 0, 0),
                "premise: the restored ts==0 stamp re-enters the scan once generation is enabled");
        manager.tickDrainPhase();
        while (manager.getQueueRemaining() > 0) {
            manager.tickDrainPhase();
        }
        assertEquals(1, manager.trackerForTest().generationCount(),
                "re-enabled: the restored not-generated stamp asks for generation");
        boolean genAskSent = false;
        for (var batch : sent) {
            for (int i = 0; i < batch.count(); i++) {
                if (batch.positions()[i] == notGen) {
                    assertEquals(0L, batch.timestamps()[i]);
                    genAskSent = true;
                }
            }
        }
        assertTrue(genAskSent, "the generation ask went on the wire with ts=0");
    }

    // ---- requested total counts at send (HD-040 manager half) ----

    @Test
    void totalPositionsRequestedCountsAtSendNotAtQueueAccept() {
        seedQueue(PositionUtil.packPosition(1, 1), -1L,
                PositionUtil.packPosition(2, 1), -1L,
                PositionUtil.packPosition(3, 1), -1L);
        assertEquals(0, manager.getTotalPositionsRequested(), "queue accept must not count");
        assertEquals(0, manager.getTotalSendCycles());

        assertEquals(3, manager.tickDrainPhase());

        assertEquals(3, manager.getTotalPositionsRequested(),
                "totalPositionsRequested counts at send (recordSendCycle)");
        assertEquals(1, manager.getTotalSendCycles());
    }
}
