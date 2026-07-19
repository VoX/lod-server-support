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
        /** Seeds one want-set batch carrying a single request (the pre-v17 per-request
         *  enqueue has no equivalent — each declaration REPLACES the backlog). Every call
         *  site here is separated from the next by a routing cycle, so nothing is superseded. */
        void enqueue(IncomingRequest r) { offerIncomingBatch(new IncomingBatch(new IncomingRequest[]{r})); }
    }

    /** Real (but never-used) reader: flips diskReadingAvailable so requests take the
     *  disk-first route; the submit override keeps results from ever being produced. */
    private static final class StubDiskReader extends AbstractChunkDiskReader {
        StubDiskReader() { super(1); }
    }

    private static class TestProcessor extends OffThreadProcessor<TestState> {
        TestProcessor(Map<UUID, TestState> players) {
            super(players, new StubDiskReader(), false, null, 1, 0);  // memo off: these rigs pin the ttl=0 (pre-memo) read path
        }
        @Override
        protected boolean submitDiskRead(UUID playerUuid, String dimension, int cx, int cz, long order) {
            return true; // returning false would unwind the dedup group test 2 depends on
        }
        @Override
        protected boolean buildAndEnqueueColumnPayload(TestState state, int cx, int cz, String dimension,
                                                     long columnTimestamp, long submissionOrder,
                                                     byte[] sectionBytes, int estimatedBytes, byte source) {
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
            proc.feedGenerationFailure(uuid, 2, 0, DIM, 2L, false);
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

    // ---- SP-042: a buffered removal must not kill a re-registered same-UUID session ----

    @Test
    void aBufferedRemovalDoesNotKillAReRegisteredSameUuidSession() throws Exception {
        var uuid = UUID.randomUUID();
        var oldState = new TestState(uuid);
        oldState.markHandshakeComplete();
        oldState.setCapabilities(LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        var players = new ConcurrentHashMap<UUID, TestState>();
        players.put(uuid, oldState);
        var proc = new TestProcessor(players);
        try {
            proc.start();

            // The old session admits a request, creating a dedup group for (5,5).
            oldState.enqueue(new IncomingRequest(5, 5, 1L));
            proc.postSnapshot(snapshot(uuid), List.of());
            waitFor(() -> oldState.getHeldSyncSlots() == 1, "old session admitted");

            // In ONE take: buffer the old session's removal AND register a fresh same-UUID
            // session with its own request. The phase-1 removal tears down the old UUID's dedup
            // groups; phase-4 routing then serves the fresh session — the removal must not reach
            // forward and clobber it.
            proc.notifyPlayerRemoved(uuid);
            var newState = new TestState(uuid);
            newState.markHandshakeComplete();
            newState.setCapabilities(LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            players.put(uuid, newState); // replaces oldState under the same UUID
            newState.enqueue(new IncomingRequest(9, 9, 1L));
            proc.postSnapshot(snapshot(uuid), List.of());

            waitFor(() -> newState.getHeldSyncSlots() == 1,
                    "the fresh same-UUID session's request is admitted despite the buffered removal");
        } finally {
            proc.shutdown();
        }
    }

    // ---- A processing-cycle Throwable must not lose the take's lossless events ----

    @Test
    void losslessEventIsRetriedAfterAProcessingCycleThrows() throws Exception {
        var uuid = UUID.randomUUID();
        var state = new TestState(uuid);
        state.markHandshakeComplete();
        var players = new ConcurrentHashMap<UUID, TestState>();
        players.put(uuid, state);

        // buildAndEnqueueColumnPayload throws on its first call, succeeds after. The first
        // cycle that processes the generation-success outcome therefore throws mid-cycle; the
        // outcome must be re-queued and delivered on a later cycle, not silently dropped.
        var throwsLeft = new java.util.concurrent.atomic.AtomicInteger(1);
        var delivered = java.util.concurrent.ConcurrentHashMap.<Long>newKeySet();
        var proc = new TestProcessor(players) {
            @Override
            protected boolean buildAndEnqueueColumnPayload(TestState s, int cx, int cz, String dim,
                                                           long ts, long order, byte[] bytes, int est,
                                                           byte source) {
                if (throwsLeft.getAndDecrement() > 0) {
                    throw new RuntimeException("injected cycle failure");
                }
                delivered.add(PositionUtil.packPosition(cx, cz)); // only a retry ever reaches here
                return true;
            }
        };
        try {
            proc.start();
            var column = new LoadedColumnData(4, 2, new byte[]{1, 2, 3}, 3);
            proc.postSnapshot(snapshot(uuid), new ArrayList<>(List.of(
                    new TickSnapshot.GenerationReadyData(uuid, 4, 2, DIM, column, 123L, 1L))));

            long deadline = System.nanoTime() + 5_000_000_000L;
            while (delivered.isEmpty() && System.nanoTime() < deadline) {
                proc.postSnapshot(snapshot(uuid), List.of()); // keep the loop cycling for the retry
                Thread.sleep(10);
            }
            assertEquals(Set.of(PositionUtil.packPosition(4, 2)), delivered,
                    "the generation outcome dropped by the throwing cycle must be retried, not lost");
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
