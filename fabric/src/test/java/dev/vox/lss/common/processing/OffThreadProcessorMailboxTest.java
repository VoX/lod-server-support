package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the mailbox contract: snapshot state is latest-wins, events are lossless.
 * A regression that makes events latest-wins (e.g. assigning instead of addAll in
 * postSnapshot) would drop generation outcomes — leaving pending entries/slots held
 * forever per the admission invariant — and these tests would fail.
 */
class OffThreadProcessorMailboxTest {

    private static final class TestState extends AbstractPlayerRequestState<Object> {
        TestState(UUID uuid) { super(uuid, 4, 4); }
        @Override public String getPlayerName() { return "test"; }
        void enqueue(IncomingRequest r) { enqueueIncomingRequest(r); }
    }

    private static final class TestProcessor extends OffThreadProcessor<TestState> {
        TestProcessor(Map<UUID, TestState> players) {
            super(players, null, false, null, 1);
        }
        @Override
        protected boolean submitDiskRead(UUID playerUuid, String dimension, int cx, int cz, long order) {
            return true; // returning false would unwind the dedup group test 2 depends on
        }
        @Override
        protected boolean buildAndEnqueueColumnPayload(TestState state, int cx, int cz, String dimension,
                                                     long columnTimestamp, long submissionOrder,
                                                     byte[] sectionBytes, int estimatedBytes) {
            // no-op: tests only observe send actions and slot counts
            return true;
        }
    }

    private static final String DIM = "minecraft:overworld";

    private static TickSnapshot snapshot(UUID... uuids) {
        var dims = new java.util.HashMap<UUID, String>();
        for (var u : uuids) dims.put(u, DIM);
        return new TickSnapshot(dims, Map.of(), 0, false);
    }

    private static TickSnapshot.GenerationReadyData failure(UUID uuid, int cx, int cz, long order) {
        return new TickSnapshot.GenerationReadyData(uuid, cx, cz, DIM, null, 0L, order);
    }

    /** Poll drainSendActions until {@code expected} positions arrive or a deadline passes. */
    private static Set<Long> drainPositions(TestProcessor proc, int expected) throws InterruptedException {
        Set<Long> positions = new HashSet<>();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (positions.size() < expected && System.nanoTime() < deadline) {
            proc.drainSendActions((state, types, packed, count) -> {
                for (int i = 0; i < count; i++) positions.add(packed[i]);
            });
            Thread.sleep(10);
        }
        return positions;
    }

    @Test
    void eventsSurviveSnapshotReplacementLossless() throws Exception {
        var uuid = UUID.randomUUID();
        var state = new TestState(uuid);
        state.markHandshakeComplete();
        var players = new ConcurrentHashMap<UUID, TestState>();
        players.put(uuid, state);
        var proc = new TestProcessor(players);
        try {
            // All posted BEFORE start(): the second snapshot replaces the first (latest-wins),
            // but every generation outcome must survive (lossless events).
            proc.postSnapshot(snapshot(uuid), new ArrayList<>(List.of(failure(uuid, 1, 0, 1L))));
            proc.feedGenerationFailure(uuid, 2, 0, DIM, 2L);
            proc.postSnapshot(snapshot(uuid), new ArrayList<>(List.of(failure(uuid, 3, 0, 3L))));
            proc.start();

            var positions = drainPositions(proc, 3);
            assertEquals(Set.of(PositionUtil.packPosition(1, 0),
                            PositionUtil.packPosition(2, 0),
                            PositionUtil.packPosition(3, 0)),
                    positions, "all generation outcomes must survive snapshot replacement");
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void bufferedRemovalReleasesDedupAttachedPending() throws Exception {
        var u1 = UUID.randomUUID();
        var u2 = UUID.randomUUID();
        var p1 = new TestState(u1);
        var p2 = new TestState(u2);
        for (var s : List.of(p1, p2)) {
            s.markHandshakeComplete();
            s.setCapabilities(LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        }
        var players = new ConcurrentHashMap<UUID, TestState>();
        players.put(u1, p1);
        players.put(u2, p2);
        var proc = new TestProcessor(players);
        try {
            proc.start();

            // p1 becomes the dedup primary for (7,7); clientTimestamp=1 -> SYNC route.
            p1.enqueue(new IncomingRequest(7, 7, 1L));
            proc.postSnapshot(snapshot(u1), List.of());
            waitFor(() -> p1.getHeldSyncSlots() == 1, "p1 admitted");

            // p2 attaches to the same position's dedup group.
            p2.enqueue(new IncomingRequest(7, 7, 1L));
            proc.postSnapshot(snapshot(u1, u2), List.of());
            waitFor(() -> p2.getHeldSyncSlots() == 1, "p2 attached");

            // Remove the primary: the buffered removal must survive the snapshot replacement
            // and cleanupDedupGroups must free the attached player's pending entry.
            proc.notifyPlayerRemoved(u1);
            players.remove(u1);
            proc.postSnapshot(snapshot(u2), List.of());
            waitFor(() -> p2.getHeldSyncSlots() == 0, "attached pending released on primary removal");
        } finally {
            proc.shutdown();
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for: " + what);
            Thread.sleep(10);
        }
    }
}
