package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins mailbox losslessness for the dirty-column events: timestamp invalidations and
 * diskReadDone-clears posted while a snapshot is pending must survive that snapshot being
 * replaced (latest-wins applies to snapshots only), and once applied they must make a
 * previously served position re-servable end-to-end — re-admitted to the disk route
 * instead of answered duplicate/up-to-date forever. Complements
 * {@link OffThreadProcessorMailboxTest}, whose losslessness test covers only generation
 * events.
 */
class DirtyEventMailboxTest {

    private static final String DIM = "minecraft:overworld";

    private static final class TestState extends AbstractPlayerRequestState<Object> {
        TestState(UUID uuid) { super(uuid, 4, 4); }
        @Override public String getPlayerName() { return "test"; }
        /** Seeds one want-set batch carrying a single request (the pre-v17 per-request
         *  enqueue has no equivalent — each declaration REPLACES the backlog). Every call
         *  site here is separated from the next by a routing cycle, so nothing is superseded. */
        void enqueue(IncomingRequest r) { offerIncomingBatch(new IncomingBatch(new IncomingRequest[]{r})); }
    }

    /**
     * The first column delivery blocks on {@code deliveryGate}, holding the processing
     * thread mid-cycle so the test can deterministically stack events and a replaced
     * snapshot in the mailbox before the next cycle begins.
     */
    private static final class TestProcessor extends OffThreadProcessor<TestState> {
        final ConcurrentLinkedQueue<Long> deliveredPositions = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Long> submittedReadPositions = new ConcurrentLinkedQueue<>();
        final CountDownLatch deliveryStarted = new CountDownLatch(1);
        final CountDownLatch deliveryGate = new CountDownLatch(1);

        TestProcessor(Map<UUID, TestState> players) {
            super(players, null, false, null, 1);
        }

        @Override
        protected boolean submitDiskRead(UUID playerUuid, String dimension, int cx, int cz, long order) {
            this.submittedReadPositions.add(PositionUtil.packPosition(cx, cz));
            return true;
        }

        @Override
        protected boolean buildAndEnqueueColumnPayload(TestState state, int cx, int cz, String dimension,
                                                     long columnTimestamp, long submissionOrder,
                                                     byte[] sectionBytes, int estimatedBytes) {
            this.deliveredPositions.add(PositionUtil.packPosition(cx, cz));
            this.deliveryStarted.countDown();
            try {
                if (!this.deliveryGate.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("delivery gate never opened");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return true;
        }
    }

    private static TestState newPlayer(UUID uuid) {
        var state = new TestState(uuid);
        state.markHandshakeComplete();
        state.setCapabilities(LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        return state;
    }

    private static TickSnapshot snapshotOf(UUID... uuids) {
        var dims = new HashMap<UUID, String>();
        for (var u : uuids) dims.put(u, DIM);
        return new TickSnapshot(dims, Map.of(), 0, false);
    }

    private static List<String> drainResponses(TestProcessor proc) {
        var out = new ArrayList<String>();
        proc.drainSendActions((state, types, positions, count) -> {
            for (int i = 0; i < count; i++) out.add(types[i] + "@" + positions[i]);
        });
        return out;
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for: " + what);
            Thread.sleep(10);
        }
    }

    @Test
    void invalidationAndDirtyClearSurviveSnapshotReplacementAndReopenThePosition() throws Exception {
        var uA = UUID.randomUUID();
        var uB = UUID.randomUUID();
        var a = newPlayer(uA);
        var b = newPlayer(uB);
        var players = new ConcurrentHashMap<UUID, TestState>();
        players.put(uA, a);
        players.put(uB, b);
        var proc = new TestProcessor(players);
        long served = PositionUtil.packPosition(5, 5);
        try {
            proc.start();

            // Serve (5,5) to A from a loaded-chunk probe: marks diskReadDone and stamps the
            // timestamp cache. The delivery blocks the processing thread mid-cycle.
            a.enqueue(new IncomingRequest(5, 5, 1L));
            var probes = new Long2ObjectOpenHashMap<LoadedColumnData>();
            probes.put(served, new LoadedColumnData(5, 5, new byte[]{1}, 1));
            Map<UUID, Long2ObjectMap<LoadedColumnData>> probesByPlayer = Map.of(uA, probes);
            var dimsA = new HashMap<UUID, String>();
            dimsA.put(uA, DIM);
            proc.postSnapshot(new TickSnapshot(dimsA, probesByPlayer, 0, false), List.of());
            assertTrue(proc.deliveryStarted.await(5, TimeUnit.SECONDS), "probe serve must start");

            // The chunk goes dirty. Then TWO snapshots: the second replaces the first while
            // unconsumed (the processing thread is still blocked in the serve cycle) — the
            // dirty events must not ride on the replaced snapshot and be lost with it.
            proc.invalidateTimestamps(DIM, new long[]{served});
            proc.clearDiskReadDone(uA, new long[]{served});
            // B first appears in the post-events snapshots, so B's admission proves a
            // post-events cycle ran (events are applied before routing within a cycle).
            b.enqueue(new IncomingRequest(6, 6, 1L));
            proc.postSnapshot(snapshotOf(uA, uB), List.of());
            proc.postSnapshot(snapshotOf(uA, uB), List.of());
            proc.deliveryGate.countDown();

            waitFor(() -> b.getHeldSyncSlots() == 1, "marker request admitted after events applied");

            // Re-request the served position with a resync timestamp newer than the cached
            // serve time: only a LOST invalidation can answer up-to-date and only a LOST
            // dirty-clear can answer duplicate. Correct behavior: admit and submit a read.
            long resyncTs = LSSConstants.epochSeconds() + 3600;
            a.enqueue(new IncomingRequest(5, 5, resyncTs));
            proc.postSnapshot(snapshotOf(uA, uB), List.of());
            waitFor(() -> a.getHeldSyncSlots() == 1 && proc.submittedReadPositions.contains(served),
                    "served position re-admitted to the disk route after the dirty events");

            assertEquals(List.of(served), List.copyOf(proc.deliveredPositions),
                    "only the original probe serve produced column data");
            assertEquals(List.of(), drainResponses(proc),
                    "the dirty re-request must trigger a read, not a duplicate/up-to-date answer");
        } finally {
            proc.deliveryGate.countDown();
            proc.shutdown();
        }
    }
}
