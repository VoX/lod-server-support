package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins cross-player disk read deduplication end-to-end through the processor: concurrent
 * requests for the same position submit exactly one read whose result fans out to every
 * requester, groups are keyed per dimension, and an attached player's disconnect leaves
 * the group serving the primary. The soak suite runs a single client, so none of these
 * multi-player paths ever fire there — a regression here silently degrades real servers.
 */
class DedupFanoutTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String END = "minecraft:the_end";

    private static final class TestState extends AbstractPlayerRequestState<Object> {
        TestState(UUID uuid) { super(uuid, 4, 4); }
        @Override public String getPlayerName() { return "test"; }
        void enqueue(IncomingRequest r) { enqueueIncomingRequest(r); }
    }

    /** Real reader on one thread; reads block on {@code gate} so dedup groups stay pending. */
    private static final class GatedDiskReader extends AbstractChunkDiskReader {
        final CountDownLatch gate = new CountDownLatch(1);
        final AtomicInteger readsExecuted = new AtomicInteger();

        GatedDiskReader() { super(1); }

        void submitGated(UUID playerUuid, int cx, int cz, String dimension, long order, byte[] bytes) {
            submitRead(playerUuid, cx, cz, dimension, order, () -> {
                if (!this.gate.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("gate never opened");
                }
                this.readsExecuted.incrementAndGet();
                return bytes;
            });
        }
    }

    private static final class TestProcessor extends OffThreadProcessor<TestState> {
        record Delivery(UUID playerUuid, AbstractPlayerRequestState<?> state, int cx, int cz,
                        String dimension, byte[] bytes) {}

        final GatedDiskReader reader;
        final Map<String, byte[]> bytesByDimension;
        final AtomicInteger diskSubmits = new AtomicInteger();
        final ConcurrentLinkedQueue<Delivery> deliveries = new ConcurrentLinkedQueue<>();

        TestProcessor(Map<UUID, TestState> players, GatedDiskReader reader,
                      Map<String, byte[]> bytesByDimension) {
            super(players, reader, false, null, 1);
            this.reader = reader;
            this.bytesByDimension = bytesByDimension;
        }

        @Override
        protected boolean submitDiskRead(UUID playerUuid, String dimension, int cx, int cz, long order) {
            this.diskSubmits.incrementAndGet();
            this.reader.submitGated(playerUuid, cx, cz, dimension, order,
                    this.bytesByDimension.get(dimension));
            return true;
        }

        @Override
        protected boolean buildAndEnqueueColumnPayload(TestState state, int cx, int cz, String dimension,
                                                     long columnTimestamp, long submissionOrder,
                                                     byte[] sectionBytes, int estimatedBytes) {
            this.deliveries.add(new Delivery(state.getPlayerUUID(), state, cx, cz, dimension, sectionBytes));
            return true;
        }
    }

    private static TestState newPlayer(UUID uuid) {
        var state = new TestState(uuid);
        state.markHandshakeComplete();
        state.setCapabilities(LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        return state;
    }

    /** Post a fresh snapshot each poll so the processing thread keeps cycling. */
    private static void pumpUntil(TestProcessor proc, Map<UUID, String> dims,
                                  java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for: " + what);
            proc.postSnapshot(new TickSnapshot(new HashMap<>(dims), Map.of(), 0, false), List.of());
            Thread.sleep(10);
        }
    }

    /**
     * Submit a read at an unused position with the gate already open and wait for its
     * delivery. The reader thread and delivery queue are FIFO, so once the barrier's
     * delivery is visible every delivery of earlier reads is too — this turns "no extra
     * fan-out delivery happened" into a deterministic assertion.
     */
    private static void runBarrierRead(TestProcessor proc, Map<UUID, String> dims, TestState state,
                                       int cx, int cz) throws InterruptedException {
        state.enqueue(new IncomingRequest(cx, cz, 1L));
        pumpUntil(proc, dims,
                () -> proc.deliveries.stream().anyMatch(d -> d.cx() == cx && d.cz() == cz),
                "barrier read at (" + cx + "," + cz + ") delivered");
    }

    @Test
    void secondRequesterAttachesToGroupAndResultFansOutToBoth() throws Exception {
        var u1 = UUID.randomUUID();
        var u2 = UUID.randomUUID();
        var p1 = newPlayer(u1);
        var p2 = newPlayer(u2);
        var players = new ConcurrentHashMap<UUID, TestState>();
        players.put(u1, p1);
        players.put(u2, p2);
        var reader = new GatedDiskReader();
        reader.registerPlayer(u1);
        reader.registerPlayer(u2);
        var proc = new TestProcessor(players, reader, Map.of(OVERWORLD, new byte[]{1, 2, 3}));
        var dims = Map.of(u1, OVERWORLD, u2, OVERWORLD);
        try {
            proc.start();

            p1.enqueue(new IncomingRequest(7, 7, 1L));
            pumpUntil(proc, dims, () -> p1.getHeldSyncSlots() == 1 && proc.diskSubmits.get() == 1,
                    "p1 admitted as dedup primary with one read submitted");

            p2.enqueue(new IncomingRequest(7, 7, 1L));
            pumpUntil(proc, dims, () -> p2.getHeldSyncSlots() == 1, "p2 admitted for the same position");

            reader.gate.countDown();
            pumpUntil(proc, dims, () -> p1.getHeldSyncSlots() == 0 && p2.getHeldSyncSlots() == 0,
                    "one result resolves both players' pending entries");

            assertEquals(1, proc.diskSubmits.get(),
                    "second requester must attach to the existing group, not submit a second read");
            assertEquals(1, reader.readsExecuted.get(), "the dedup group performs exactly one disk read");

            runBarrierRead(proc, dims, p1, 100, 100);
            var fanout = proc.deliveries.stream().filter(d -> d.cx() == 7 && d.cz() == 7).toList();
            assertEquals(2, fanout.size(), "exactly one delivery per requester");
            assertEquals(Set.of(u1, u2),
                    fanout.stream().map(TestProcessor.Delivery::playerUuid).collect(Collectors.toSet()),
                    "the single read's result must reach both the primary and the attached player");
            for (var d : fanout) {
                assertEquals(OVERWORLD, d.dimension());
                assertArrayEquals(new byte[]{1, 2, 3}, d.bytes(),
                        "attached player must receive the primary read's bytes");
            }
        } finally {
            reader.gate.countDown();
            proc.shutdown();
            reader.shutdown();
        }
    }

    @Test
    void samePositionInDifferentDimensionsDoesNotShareAGroup() throws Exception {
        var u1 = UUID.randomUUID();
        var u2 = UUID.randomUUID();
        var p1 = newPlayer(u1);
        var p2 = newPlayer(u2);
        var players = new ConcurrentHashMap<UUID, TestState>();
        players.put(u1, p1);
        players.put(u2, p2);
        var reader = new GatedDiskReader();
        reader.registerPlayer(u1);
        reader.registerPlayer(u2);
        var proc = new TestProcessor(players, reader,
                Map.of(OVERWORLD, new byte[]{1}, END, new byte[]{2}));
        var dims = Map.of(u1, OVERWORLD, u2, END);
        try {
            proc.start();

            p1.enqueue(new IncomingRequest(7, 7, 1L));
            pumpUntil(proc, dims, () -> p1.getHeldSyncSlots() == 1 && proc.diskSubmits.get() == 1,
                    "overworld read submitted");

            p2.enqueue(new IncomingRequest(7, 7, 1L));
            pumpUntil(proc, dims, () -> p2.getHeldSyncSlots() == 1 && proc.diskSubmits.get() == 2,
                    "same packed position in another dimension must submit its own read");

            reader.gate.countDown();
            pumpUntil(proc, dims, () -> proc.deliveries.size() == 2
                            && p1.getHeldSyncSlots() == 0 && p2.getHeldSyncSlots() == 0,
                    "both dimension reads delivered independently");

            assertEquals(2, reader.readsExecuted.get());
            var byPlayer = proc.deliveries.stream()
                    .collect(Collectors.toMap(TestProcessor.Delivery::playerUuid, d -> d));
            assertEquals(Set.of(u1, u2), byPlayer.keySet());
            var d1 = byPlayer.get(u1);
            assertEquals(7, d1.cx());
            assertEquals(7, d1.cz());
            assertEquals(OVERWORLD, d1.dimension());
            assertArrayEquals(new byte[]{1}, d1.bytes(), "p1 must get the overworld read");
            var d2 = byPlayer.get(u2);
            assertEquals(7, d2.cx());
            assertEquals(7, d2.cz());
            assertEquals(END, d2.dimension());
            assertArrayEquals(new byte[]{2}, d2.bytes(),
                    "p2 must get the End read, never the overworld group's data");
        } finally {
            reader.gate.countDown();
            proc.shutdown();
            reader.shutdown();
        }
    }

    @Test
    void attachedPlayerDisconnectLeavesGroupServingPrimaryAndRejoinerAttachesFresh() throws Exception {
        var u1 = UUID.randomUUID();
        var u2 = UUID.randomUUID();
        var p1 = newPlayer(u1);
        var p2 = newPlayer(u2);
        var players = new ConcurrentHashMap<UUID, TestState>();
        players.put(u1, p1);
        players.put(u2, p2);
        var reader = new GatedDiskReader();
        reader.registerPlayer(u1);
        reader.registerPlayer(u2);
        var proc = new TestProcessor(players, reader, Map.of(OVERWORLD, new byte[]{1, 2, 3}));
        var dims = Map.of(u1, OVERWORLD, u2, OVERWORLD);
        try {
            proc.start();

            p1.enqueue(new IncomingRequest(7, 7, 1L));
            pumpUntil(proc, dims, () -> p1.getHeldSyncSlots() == 1 && proc.diskSubmits.get() == 1,
                    "p1 admitted as dedup primary");
            p2.enqueue(new IncomingRequest(7, 7, 1L));
            pumpUntil(proc, dims, () -> p2.getHeldSyncSlots() == 1, "p2 attached");

            // Barrier scaffolding: a second gated group at an unused position with the roles
            // flipped (p2 primary, p1 attached). applyEvents' cleanup of a removed player's
            // PRIMARY groups releases the attached players' pending entries, so p1's slot
            // count dropping is the only externally observable proof that the processing
            // thread has applied the removal.
            p2.enqueue(new IncomingRequest(9, 9, 1L));
            pumpUntil(proc, dims, () -> p2.getHeldSyncSlots() == 2 && proc.diskSubmits.get() == 2,
                    "p2 admitted as primary of the barrier group");
            p1.enqueue(new IncomingRequest(9, 9, 1L));
            pumpUntil(proc, dims, () -> p1.getHeldSyncSlots() == 2, "p1 attached to the barrier group");

            // p2 disconnects and rejoins with a fresh state while the read is still in flight
            // (production removePlayer + registerPlayer: same UUID, new state object).
            players.remove(u2);
            proc.notifyPlayerRemoved(u2);
            reader.removePlayerResults(u2);
            var p2b = newPlayer(u2);
            players.put(u2, p2b);
            reader.registerPlayer(u2);

            // Barrier: the removal is only mailbox-buffered; a cycle whose take predates
            // notifyPlayerRemoved can still be mid-flight and would route a request enqueued
            // now BEFORE the removal applies — the removal would then strip the rejoiner's
            // fresh attachment. Wait for the removal's observable effect (the barrier group's
            // cleanup releasing p1's attached slot) before the re-request exists.
            pumpUntil(proc, dims, () -> p1.getHeldSyncSlots() == 1,
                    "removal applied (barrier group cleanup released p1's attachment)");

            // The rejoined session re-requests the same position: the group must have survived
            // the attachee's removal (still keyed by the primary), so it attaches without a
            // second read.
            p2b.enqueue(new IncomingRequest(7, 7, 1L));
            pumpUntil(proc, dims, () -> p2b.getHeldSyncSlots() == 1, "rejoined p2 attached");

            reader.gate.countDown();
            pumpUntil(proc, dims, () -> p1.getHeldSyncSlots() == 0 && p2b.getHeldSyncSlots() == 0,
                    "group resolves the primary and the rejoined attachment");
            assertEquals(2, proc.diskSubmits.get(),
                    "rejoiner must attach to the surviving group, not trigger a third read"
                            + " (2 = the (7,7) group + the barrier group)");
            assertEquals(2, reader.readsExecuted.get());

            runBarrierRead(proc, dims, p1, 100, 100);
            var fanout = proc.deliveries.stream().filter(d -> d.cx() == 7 && d.cz() == 7).toList();
            assertEquals(2, fanout.size(),
                    "exactly one delivery per live session — the dead attachment must not double-deliver");
            var byPlayer = fanout.stream()
                    .collect(Collectors.groupingBy(TestProcessor.Delivery::playerUuid));
            assertEquals(Set.of(u1, u2), byPlayer.keySet());
            assertEquals(1, byPlayer.get(u1).size());
            assertEquals(1, byPlayer.get(u2).size());
            assertSame(p2b, byPlayer.get(u2).get(0).state(),
                    "the delivery must target the live rejoined session, not the removed one");
            assertArrayEquals(new byte[]{1, 2, 3}, byPlayer.get(u1).get(0).bytes());
            assertArrayEquals(new byte[]{1, 2, 3}, byPlayer.get(u2).get(0).bytes());
        } finally {
            reader.gate.countDown();
            proc.shutdown();
            reader.shutdown();
        }
    }
}
