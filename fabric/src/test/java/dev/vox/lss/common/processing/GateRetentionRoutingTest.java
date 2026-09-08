package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Router-level gate retention (disk-read-concurrency-gate-plan.md Amendment 2): when the
 * disk-read gate is SATURATED (permits exhausted AND the park would be filled by the
 * park plus permit-less in-flight work), the router RETAINS the entry and stops the
 * player's pass — the {@code hasDiskHeadroom} semantics — instead of submitting into a
 * certain park-overflow drop (the live 767/min WARN storm the amendment retired). These
 * pin the ROUTER wiring against an injected saturation signal; the reader-side predicate
 * itself is pinned in {@code AbstractChunkDiskReaderTest}.
 */
class GateRetentionRoutingTest {

    private static final String DIM = LSSConstants.DIM_STR_OVERWORLD;

    private static final class TestState extends AbstractPlayerRequestState<Object> {
        TestState(UUID uuid) { super(uuid, 4, 4); }
        @Override public String getPlayerName() { return "gate-retention-test"; }
    }

    private static final class StubDiskReader extends AbstractChunkDiskReader {
        StubDiskReader() { super(1); }
    }

    private static final class TestProcessor extends OffThreadProcessor<TestState> {
        final ConcurrentLinkedQueue<Long> submits = new ConcurrentLinkedQueue<>();
        final AtomicInteger gateStops = new AtomicInteger();
        volatile BooleanSupplier saturation = () -> false;
        volatile boolean headroom = true;

        TestProcessor(Map<UUID, TestState> players) {
            super(players, new StubDiskReader(), false, null, 1, 0);
        }
        @Override
        boolean hasDiskHeadroom() { return this.headroom; }
        @Override
        boolean gateSaturated() { return this.saturation.getAsBoolean(); }
        @Override
        void recordGateStop() { this.gateStops.incrementAndGet(); }
        @Override
        protected boolean submitDiskRead(UUID playerUuid, RequestRegistration registration, String dimension, int cx, int cz, long order, long clientTimestamp) {
            this.submits.add(dev.vox.lss.common.PositionUtil.packPosition(cx, cz));
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for " + what);
            Thread.sleep(2);
        }
    }

    private static TestState registeredPlayer(Map<UUID, TestState> players, int... cxCzPairs) {
        var s = new TestState(UUID.randomUUID());
        s.markHandshakeComplete();
        s.setCapabilities(LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        var reqs = new IncomingRequest[cxCzPairs.length / 2];
        for (int i = 0; i < reqs.length; i++) {
            reqs[i] = new IncomingRequest(cxCzPairs[i * 2], cxCzPairs[i * 2 + 1], -1);
        }
        s.offerIncomingBatch(new IncomingBatch(reqs));
        players.put(s.getPlayerUUID(), s);
        return s;
    }

    private static TickSnapshot snapshotOf(Map<UUID, TestState> players) {
        var dims = new HashMap<UUID, String>();
        for (var u : players.keySet()) dims.put(u, DIM);
        return new TickSnapshot(dims, Map.of(), 0, false);
    }

    @Test
    void saturatedGateRetainsTheEntryStopsThePassAndCountsExactlyOneStop() throws Exception {
        var players = new ConcurrentHashMap<UUID, TestState>();
        var state = registeredPlayer(players, 10, 0, 11, 0, 12, 0);
        var proc = new TestProcessor(players);
        proc.saturation = () -> true;
        try {
            proc.start();
            proc.postSnapshot(snapshotOf(players), List.of());
            waitFor(() -> proc.routeCyclesForTest() >= 1, "routing cycle");

            assertTrue(proc.submits.isEmpty(),
                    "a saturated gate must admit NOTHING — a submit here is a certain "
                            + "park-overflow drop");
            assertEquals(3, state.getBacklogSize(),
                    "ALL entries are retained (the stopped head re-prepended, the rest "
                            + "never polled) — retention, not drops");
            assertEquals(1, proc.gateStops.get(),
                    "exactly ONE gate_stops per stopped player-pass — it is a pass "
                            + "counter, never a held-reads count");

            // The UNWIND pin (3-Opus round MINOR-3 — the one silent session-permanent
            // regression): the retained head must hold NO pending slot. A leaked slot
            // resolves Duplicate.IN_FLIGHT forever (no result will ever clear it) and
            // wedges one SYNC slot per gate stop until the cap parks the player.
            assertFalse(state.hasPendingRequest(10, 0),
                    "a gate-retained entry must be fully unwound — no pending slot");

            // ...and no leaked dedup group: once the gate clears, the SAME positions
            // must admit as fresh submissions (a leaked group would silently attach).
            proc.saturation = () -> false;
            proc.postSnapshot(snapshotOf(players), List.of());
            waitFor(() -> proc.routeCyclesForTest() >= 2, "post-clear routing cycle");
            assertEquals(3, proc.submits.size(),
                    "after the gate clears, every retained entry admits fresh — a leaked "
                            + "dedup group or pending slot would swallow the resubmission");
            assertEquals(0, state.getBacklogSize());
            assertEquals(1, proc.gateStops.get(), "the cleared pass books no stop");
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void unsaturatedGateRoutesByteIdenticallyWithZeroStops() throws Exception {
        var players = new ConcurrentHashMap<UUID, TestState>();
        var state = registeredPlayer(players, 10, 0, 11, 0, 12, 0);
        var proc = new TestProcessor(players);
        try {
            proc.start();
            proc.postSnapshot(snapshotOf(players), List.of());
            waitFor(() -> proc.routeCyclesForTest() >= 1, "routing cycle");

            assertEquals(3, proc.submits.size(),
                    "an unsaturated gate must not change routing at all");
            assertEquals(0, state.getBacklogSize());
            assertEquals(0, proc.gateStops.get(), "no stop, no count");
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void midPassSaturationFlipStopsAdmissionAtTheFlip() throws Exception {
        // Per-entry evaluation (Amendment 2 revision R-MAJ-2): the predicate is read for
        // EVERY fresh submission, so a flip mid-drain stops the pass at the flip instead
        // of admitting the rest of the backlog into the overflow.
        var players = new ConcurrentHashMap<UUID, TestState>();
        var state = registeredPlayer(players, 10, 0, 11, 0, 12, 0);
        var proc = new TestProcessor(players);
        proc.saturation = () -> !proc.submits.isEmpty(); // saturates after the 1st submit
        try {
            proc.start();
            proc.postSnapshot(snapshotOf(players), List.of());
            waitFor(() -> proc.routeCyclesForTest() >= 1, "routing cycle");

            assertEquals(1, proc.submits.size(), "only the pre-flip entry is admitted");
            assertEquals(2, state.getBacklogSize(),
                    "the flip entry is retained in order ahead of the never-polled tail");
            assertEquals(1, proc.gateStops.get());
        } finally {
            proc.shutdown();
        }

        // Order pin: the retained head precedes the never-polled tail (restoreBacklog
        // re-prepends). Safe to poll after shutdown — the processing thread is gone.
        var first = state.pollBacklog();
        var second = state.pollBacklog();
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(11, first.cx(), "the stopped entry stays at the backlog head");
        assertEquals(12, second.cx(), "declaration order is preserved behind it");
    }

    @Test
    void poolHeadroomStopIsNeverBilledToTheGate() throws Exception {
        // Attribution (Amendment 2 revision R-MAJ-2): hasDiskHeadroom is checked FIRST,
        // so a pool-full stop keeps its NO_DISK_HEADROOM shape and gate_stops counts
        // only gate-attributable stops — pool-full behavior stays byte-identical.
        var players = new ConcurrentHashMap<UUID, TestState>();
        var state = registeredPlayer(players, 10, 0, 11, 0);
        var proc = new TestProcessor(players);
        proc.headroom = false;
        proc.saturation = () -> true; // both true: headroom must win the attribution
        try {
            proc.start();
            proc.postSnapshot(snapshotOf(players), List.of());
            waitFor(() -> proc.routeCyclesForTest() >= 1, "routing cycle");

            assertTrue(proc.submits.isEmpty());
            assertEquals(2, state.getBacklogSize(), "retained either way");
            assertEquals(0, proc.gateStops.get(),
                    "a pool-headroom stop is never billed to the gate");
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void dedupAttachedRequestsRideThroughSaturation() throws Exception {
        // The dedup argument at the headroom check applies verbatim (Amendment 2): an
        // attached request rides another player's already-submitted read and costs the
        // gate nothing, so saturation must not defer it — that would throttle exactly
        // the cross-player convergence dedup exists to accelerate.
        var players = new ConcurrentHashMap<UUID, TestState>();
        var a = registeredPlayer(players, 10, 0);
        var b = registeredPlayer(players, 10, 0); // the SAME position
        var proc = new TestProcessor(players);
        proc.saturation = () -> !proc.submits.isEmpty(); // saturated once the read exists
        try {
            proc.start();
            proc.postSnapshot(snapshotOf(players), List.of());
            waitFor(() -> proc.routeCyclesForTest() >= 1, "routing cycle");

            assertEquals(1, proc.submits.size(),
                    "one submission — the second player attaches to the first's read");
            assertEquals(0, proc.gateStops.get(),
                    "an attach is not a stop — it rides through saturation");
            assertEquals(0, a.getBacklogSize());
            assertEquals(0, b.getBacklogSize(),
                    "both entries dispositioned: one submitted, one attached");
        } finally {
            proc.shutdown();
        }
    }
}
