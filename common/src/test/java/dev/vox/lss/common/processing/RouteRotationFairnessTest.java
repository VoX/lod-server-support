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
 * Drain-order rotation (M4, 2026-07-28 review round): routeAll used to iterate the
 * snapshot map in identical (UUID hash bucket) order every cycle against the GLOBAL
 * disk-headroom gate — under pool saturation the first player absorbed every freed slot
 * and later players got no disk reads (and no generation — the disk miss is the trigger)
 * until the first converged. The rotation gives every player the leading position in turn.
 */
class RouteRotationFairnessTest {

    private static final String DIM = LSSConstants.DIM_STR_OVERWORLD;

    private static final class TestState extends AbstractPlayerRequestState<Object> {
        TestState(UUID uuid) { super(uuid, 4, 4); }
        @Override public String getPlayerName() { return "fairness-test"; }
    }

    private static final class StubDiskReader extends AbstractChunkDiskReader {
        StubDiskReader() { super(1); }
    }

    /** Emulates a saturated pool that frees exactly {@code headroomBudget} slots per cycle. */
    private static final class TestProcessor extends OffThreadProcessor<TestState> {
        final AtomicInteger headroomBudget = new AtomicInteger();
        final ConcurrentLinkedQueue<UUID> submitters = new ConcurrentLinkedQueue<>();

        TestProcessor(Map<UUID, TestState> players) {
            super(players, new StubDiskReader(), false, null, 1, 0);
        }
        @Override
        boolean hasDiskHeadroom() {
            return this.headroomBudget.get() > 0;
        }
        @Override
        protected boolean submitDiskRead(UUID playerUuid, RequestRegistration registration, String dimension, int cx, int cz, long order, long clientTimestamp) {
            this.headroomBudget.decrementAndGet();
            this.submitters.add(playerUuid);
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

    private static TestState registeredPlayer(Map<UUID, TestState> players, long... packedWants) {
        var s = new TestState(UUID.randomUUID());
        s.markHandshakeComplete();
        s.setCapabilities(LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        var reqs = new IncomingRequest[packedWants.length / 2];
        for (int i = 0; i < reqs.length; i++) {
            reqs[i] = new IncomingRequest((int) packedWants[i * 2], (int) packedWants[i * 2 + 1], -1);
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
    void bothPlayersGetDiskServiceWithinTwoSaturatedCycles() throws Exception {
        var players = new ConcurrentHashMap<UUID, TestState>();
        var a = registeredPlayer(players, 10, 0, 11, 0);
        var b = registeredPlayer(players, 20, 0, 21, 0);
        var proc = new TestProcessor(players);
        try {
            proc.start();

            // Cycle 1: the pool frees exactly one slot — one player submits, the other
            // hits NO_DISK_HEADROOM on its first cold entry and retains.
            proc.headroomBudget.set(1);
            proc.postSnapshot(snapshotOf(players), List.of());
            // Gate on CYCLE COMPLETION, not on the submission count: refilling the budget
            // mid-cycle would let the leading player consume it for its second entry
            // within the same pass on a descheduled-thread window (a misleading red).
            waitFor(() -> proc.routeCyclesForTest() >= 1, "first routing cycle to complete");
            assertEquals(1, proc.submitters.size(), "one slot, one submission in cycle 1");

            // Cycle 2: one more slot. Under the unrotated order the SAME player leads
            // (it still holds a backlog) and takes this slot too — the starvation this
            // test exists to pin. With rotation the other player leads.
            proc.headroomBudget.set(1);
            proc.postSnapshot(snapshotOf(players), List.of());
            waitFor(() -> proc.routeCyclesForTest() >= 2, "second routing cycle to complete");
            assertEquals(2, proc.submitters.size(), "one slot, one submission in cycle 2");

            assertEquals(2, proc.submitters.stream().distinct().count(),
                    "two saturated cycles must serve TWO distinct players — a repeat "
                            + "submitter means the drain order is not rotating (M4)");
            var served = proc.submitters.stream().distinct().toList();
            assertTrue(served.contains(a.getPlayerUUID()) && served.contains(b.getPlayerUUID()));
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void emptyBacklogPlayerNeverBlocksTheWorkingOneInAnyLeadPosition() throws Exception {
        // Not a rotation discriminator (fixed order also serves only the working player) —
        // this pins that an idle player in the LEAD position drains nothing and cannot
        // wedge or consume the budget, across both parities of the rotation.
        var players = new ConcurrentHashMap<UUID, TestState>();
        registeredPlayer(players);                    // empty backlog
        var b = registeredPlayer(players, 30, 0, 31, 0);
        var proc = new TestProcessor(players);
        try {
            proc.start();
            for (int cycle = 1; cycle <= 2; cycle++) {
                proc.headroomBudget.set(1);
                proc.postSnapshot(snapshotOf(players), List.of());
                final int expected = cycle;
                waitFor(() -> proc.routeCyclesForTest() >= expected, "cycle " + expected);
                assertEquals(expected, proc.submitters.size(), "one submission per cycle");
            }
            assertTrue(proc.submitters.stream().allMatch(u -> u.equals(b.getPlayerUUID())),
                    "only the working player submits, in either lead position");
        } finally {
            proc.shutdown();
        }
    }
}
