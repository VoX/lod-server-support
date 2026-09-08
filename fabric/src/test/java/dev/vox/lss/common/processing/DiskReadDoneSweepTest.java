package dev.vox.lss.common.processing;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.SharedBandwidthLimiter;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The served-set sweep (M3, 2026-07-28 review round): diskReadDone otherwise grows
 * monotonically for the whole session — tens of MB per long roaming player, released only
 * at disconnect. Out-of-range entries are semantically dead (ingress range-filters every
 * declaration strictly inside the sweep radius), so removing them can only ever cost an
 * honest re-resolution if the player returns — never a false up_to_date.
 */
class DiskReadDoneSweepTest {

    private static final class TestState extends AbstractPlayerRequestState<Object> {
        TestState(UUID uuid) { super(uuid, 4, 4); }
        @Override public String getPlayerName() { return "sweep-test"; }
        void grace(long nanos, java.util.function.LongSupplier clock) {
            setDepartureGraceForTest(nanos, clock);
        }
    }

    private static final class StubDiskReader extends AbstractChunkDiskReader {
        StubDiskReader() { super(1); }
    }

    private static final class TestProcessor extends OffThreadProcessor<TestState> {
        final java.util.concurrent.ConcurrentLinkedQueue<Long> submits =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        TestProcessor(Map<UUID, TestState> players) {
            // Old-signature ctor: the fail-safe DEFAULT sweep radius (2096) applies.
            super(players, new StubDiskReader(), false, null, 1, 0);
        }
        @Override
        protected boolean submitDiskRead(UUID playerUuid, RequestRegistration registration, String dimension, int cx, int cz, long order, long clientTimestamp) {
            this.submits.add(PositionUtil.packPosition(cx, cz));
            return true;
        }
        @Override
        protected boolean buildAndEnqueueColumnPayload(TestState state, int cx, int cz, String dimension,
                                                        long columnTimestamp, long submissionOrder,
                                                        ColumnBytes bytes, int estimatedBytes, byte source) {
            return true;
        }
    }

    private static void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for " + what);
            Thread.sleep(2);
        }
    }

    // ---- Unit: range semantics ----

    @Test
    void sweepRemovesBeyondTheRadiusAndKeepsTheExactBoundary() {
        var s = new TestState(UUID.randomUUID());
        s.updatePlayerChunk(100, -100);
        s.markDiskReadDone(100, -100);       // distance 0
        s.markDiskReadDone(108, -100);       // Chebyshev exactly 8
        s.markDiskReadDone(109, -100);       // 9 — beyond
        s.markDiskReadDone(100, -109);       // 9 on the other axis — beyond
        assertEquals(2, s.sweepDiskReadDoneOutsideRange(8), "exactly the two beyond-radius entries");
        assertTrue(s.hasDiskReadDone(100, -100));
        assertTrue(s.hasDiskReadDone(108, -100), "the exact boundary is KEPT — a position at "
                + "the ingress edge must never be swept while still declarable");
        assertFalse(s.hasDiskReadDone(109, -100));
        assertFalse(s.hasDiskReadDone(100, -109));
    }

    @Test
    void sweepIsANoOpUntilThePlayerChunkIsStamped() {
        var s = new TestState(UUID.randomUUID());
        s.markDiskReadDone(10_000, 10_000);
        assertEquals(0, s.sweepDiskReadDoneOutsideRange(1),
                "no player-chunk stamp: nothing may be swept against an unknown origin");
        assertTrue(s.hasDiskReadDone(10_000, 10_000));
    }

    @Test
    void sweepHandlesNegativeCoordinatesAcrossTheOrigin() {
        var s = new TestState(UUID.randomUUID());
        s.updatePlayerChunk(-5, 5);
        s.markDiskReadDone(-9, 5);   // distance 4 — kept at radius 4
        s.markDiskReadDone(-10, 5);  // 5 — swept
        s.markDiskReadDone(3, -3);   // max(8, 8) = 8 — swept
        assertEquals(2, s.sweepDiskReadDoneOutsideRange(4));
        assertTrue(s.hasDiskReadDone(-9, 5));
        assertFalse(s.hasDiskReadDone(-10, 5));
        assertFalse(s.hasDiskReadDone(3, -3));
    }

    // ---- Unit: pipeline/grace protection ----

    @Test
    void inPipelineAndGraceWindowEntriesSurviveTheSweepUntilExpiry() throws Exception {
        var s = new TestState(UUID.randomUUID());
        var clock = new AtomicLong(1_000_000_000L);
        s.grace(500_000_000L, clock::get);
        s.updatePlayerChunk(0, 0);
        long farPacked = PositionUtil.packPosition(1000, 1000);
        s.markDiskReadDone(1000, 1000);

        // In the send pipeline: the sweep must keep it (a ts<=0 re-ask relies on the
        // silent skip while the payload is in flight).
        s.addReadyPayload(new QueuedPayload<>(new Object(), 10, 1, farPacked));
        assertEquals(0, s.sweepDiskReadDoneOutsideRange(4), "enqueued column is kept");
        assertTrue(s.hasEnqueuedColumn(farPacked));

        // Flushed: departure stamped, grace active — still kept.
        Thread.sleep(50); // token-bucket refill (see FlushSendQueueTest header)
        s.flushSendQueue(1_000_000_000L, new SharedBandwidthLimiter(1_000_000_000L),
                new TickDiagnostics(), payload -> { });
        assertFalse(s.hasEnqueuedColumn(farPacked), "payload fully departed");
        assertTrue(s.isWithinDepartureGrace(farPacked));
        assertEquals(0, s.sweepDiskReadDoneOutsideRange(4), "grace-window entry is kept");
        assertTrue(s.hasDiskReadDone(1000, 1000));

        // Grace expired: now it sweeps.
        clock.addAndGet(600_000_000L);
        assertEquals(1, s.sweepDiskReadDoneOutsideRange(4));
        assertFalse(s.hasDiskReadDone(1000, 1000));
    }

    // ---- Wiring pin: the eviction cycle actually runs the sweep ----

    @Test
    void processorSweepsARegisteredPlayerOnTheEvictionCycleAndTheHealIsHonest() throws Exception {
        var players = new ConcurrentHashMap<UUID, TestState>();
        var state = new TestState(UUID.randomUUID());
        state.markHandshakeComplete();
        state.setCapabilities(dev.vox.lss.common.LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        players.put(state.getPlayerUUID(), state);
        state.updatePlayerChunk(0, 0);
        state.markDiskReadDone(5000, 5000); // beyond the fail-safe default radius (2096)
        state.markDiskReadDone(1, 1);

        var proc = new TestProcessor(players);
        try {
            proc.primeEvictionCounterForTest();
            proc.start();
            proc.postSnapshot(new TickSnapshot(new HashMap<>(), Map.of(), 0, false), List.of());
            waitFor(() -> !state.hasDiskReadDone(5000, 5000),
                    "the eviction cycle to sweep the out-of-range served-set entry");
            assertTrue(state.hasDiskReadDone(1, 1), "in-range entries survive");

            // The honest heal — the property that makes sweeping SAFE: a ts<=0
            // re-declaration of the swept position must re-resolve (submit a disk read),
            // never terminally answer off a phantom done-bit.
            state.offerIncomingBatch(new IncomingBatch(new IncomingRequest[]{
                    new IncomingRequest(5000, 5000, -1)}));
            var dims = new HashMap<UUID, String>();
            dims.put(state.getPlayerUUID(), dev.vox.lss.common.LSSConstants.DIM_STR_OVERWORLD);
            proc.postSnapshot(new TickSnapshot(dims, Map.of(), 0, false), List.of());
            waitFor(() -> proc.submits.contains(PositionUtil.packPosition(5000, 5000)),
                    "the swept position's re-declaration to re-resolve via a disk read");
        } finally {
            proc.shutdown();
        }
    }

    /** v0.11.0 stage C (/lsslod set lodDistanceChunks): the sweep radius follows config
     *  as a MONOTONIC MAX — raising grows it next tick; lowering never shrinks it within
     *  the run (an under-radius sweep is semantically free but causes redundant serves;
     *  the too-large cost is only memory already bounded by M3's trim). */
    @org.junit.jupiter.api.Test
    void updateSweepRadiusIsAMonotonicMax() {
        var proc = new TestProcessor(new java.util.concurrent.ConcurrentHashMap<>());
        try {
            int boot = proc.sweepRadiusForTest();
            proc.updateSweepRadius(boot + 100);
            org.junit.jupiter.api.Assertions.assertEquals(boot + 100, proc.sweepRadiusForTest(),
                    "raising the derived radius must grow the sweep radius");
            proc.updateSweepRadius(boot);
            org.junit.jupiter.api.Assertions.assertEquals(boot + 100, proc.sweepRadiusForTest(),
                    "lowering must never shrink it within the run — monotonic max");
        } finally {
            proc.shutdown();
        }
    }
}
