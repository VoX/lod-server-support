package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
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
                        String dimension, byte[] bytes, long submissionOrder) {}

        final GatedDiskReader reader;
        final Map<String, byte[]> bytesByDimension;
        final AtomicInteger diskSubmits = new AtomicInteger();
        final ConcurrentLinkedQueue<Delivery> deliveries = new ConcurrentLinkedQueue<>();

        TestProcessor(Map<UUID, TestState> players, GatedDiskReader reader,
                      Map<String, byte[]> bytesByDimension) {
            this(players, reader, bytesByDimension, false);
        }

        TestProcessor(Map<UUID, TestState> players, GatedDiskReader reader,
                      Map<String, byte[]> bytesByDimension, boolean generationAvailable) {
            super(players, reader, generationAvailable, null, 1);
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
            this.deliveries.add(new Delivery(state.getPlayerUUID(), state, cx, cz, dimension,
                    sectionBytes, submissionOrder));
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

    /** Post exactly one snapshot (one processing cycle) — per-tick counters stay readable
     *  afterwards because nothing resets them until the next post. */
    private static void postOnce(TestProcessor proc, Map<UUID, String> dims) {
        proc.postSnapshot(new TickSnapshot(new HashMap<>(dims), Map.of(), 0, false), List.of());
    }

    /** Wait without posting (the condition's cycle was already posted). */
    private static void waitFor(java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for: " + what);
            Thread.sleep(10);
        }
    }

    private record Response(UUID player, byte type, long packed) {}

    /** One non-blocking drain of the batched answers produced so far. */
    private static List<Response> drainResponses(TestProcessor proc) {
        var collected = new ArrayList<Response>();
        proc.drainSendActions((state, types, positions, count) -> {
            for (int i = 0; i < count; i++) {
                collected.add(new Response(state.getPlayerUUID(), types[i], positions[i]));
            }
        });
        return collected;
    }

    /** Accumulate drained batched answers until {@code done} or a deadline passes. */
    private static List<Response> drainUntilResponses(TestProcessor proc,
                                                      Predicate<List<Response>> done)
            throws InterruptedException {
        var collected = new ArrayList<Response>();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!done.test(collected)) {
            if (System.nanoTime() > deadline) fail("timed out draining responses; got " + collected);
            collected.addAll(drainResponses(proc));
            Thread.sleep(10);
        }
        return collected;
    }

    /** Poll until the processing thread publishes a generation ticket (race-free consume). */
    private static OffThreadProcessor.GenerationTicketRequest awaitTicket(TestProcessor proc)
            throws InterruptedException {
        var ref = new AtomicReference<OffThreadProcessor.GenerationTicketRequest>();
        waitFor(() -> {
            var t = proc.pollGenerationTicketRequest();
            if (t != null) ref.set(t);
            return ref.get() != null;
        }, "generation ticket");
        return ref.get();
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

    @Test
    void attachCountsDiskQueuedAndFanoutCarriesPerRecipientSubmissionOrders() throws Exception {
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
            postOnce(proc, dims);
            waitFor(() -> proc.diskSubmits.get() == 1
                            && proc.getDiagnostics().getLastDiskQueued() == 1,
                    "primary submit counted disk-queued");

            // The attach must count as disk-queued in ITS cycle (it entered the disk
            // pipeline) without a second submit — disk accounting balances per request,
            // not per physical read.
            p2.enqueue(new IncomingRequest(7, 7, 1L));
            postOnce(proc, dims);
            waitFor(() -> p2.getHeldSyncSlots() == 1
                            && proc.getDiagnostics().getLastDiskQueued() == 1,
                    "attach counted disk-queued in its own cycle");
            assertEquals(1, proc.diskSubmits.get(), "attaching must not submit a second read");

            reader.gate.countDown();
            waitFor(() -> !reader.getPlayerQueue(u1).isEmpty(), "read result landed");
            postOnce(proc, dims);
            waitFor(() -> proc.getDiagnostics().getLastDiskDrained() == 2,
                    "primary + fan-out drain as two deliveries in the same cycle");
            assertEquals(0, p1.getHeldSyncSlots());
            assertEquals(0, p2.getHeldSyncSlots());

            var fanout = proc.deliveries.stream().filter(d -> d.cx() == 7 && d.cz() == 7).toList();
            assertEquals(2, fanout.size());
            var orderByPlayer = fanout.stream().collect(
                    Collectors.toMap(TestProcessor.Delivery::playerUuid,
                            TestProcessor.Delivery::submissionOrder));
            assertEquals(Set.of(u1, u2), orderByPlayer.keySet());
            assertNotEquals(orderByPlayer.get(u1), orderByPlayer.get(u2),
                    "each recipient's payload must carry its OWN submission order, not the primary's");
            assertTrue(orderByPlayer.get(u1) < orderByPlayer.get(u2),
                    "orders follow request arrival: primary first, attachment second");

            // Group removed after fan-out: a ts<=0 re-ask re-resolves into a FRESH group
            // and a second read; a leaked group would swallow it (attach, never submit,
            // never answer) and this waitFor would time out.
            p1.enqueue(new IncomingRequest(7, 7, -1L));
            postOnce(proc, dims);
            waitFor(() -> proc.diskSubmits.get() == 2, "fresh group re-submits after fan-out");
            assertEquals(1, proc.getDiagnostics().getTotalReResolved());
        } finally {
            reader.gate.countDown();
            proc.shutdown();
            reader.shutdown();
        }
    }

    @Test
    void primaryDisconnectFreesAttachmentsSilentlyAndTheirReRequestIsServed() throws Exception {
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

            // The PRIMARY disconnects while the read is still in flight
            players.remove(u1);
            proc.notifyPlayerRemoved(u1);
            reader.removePlayerResults(u1);
            pumpUntil(proc, dims, () -> p2.getHeldSyncSlots() == 0,
                    "primary removal frees the attached pending");

            assertEquals(List.of(), drainResponses(proc),
                    "freeing an attachment is silent — no RateLimited/NotGenerated/UpToDate is owed;"
                            + " the client's own timeout re-request recovers the position");
            assertFalse(p2.hasDiskReadDone(7, 7), "never delivered, must not be marked served");

            // Follow-through: the attached client's timeout re-request is served by a
            // fresh group + fresh read (the dead primary's group died with it).
            p2.enqueue(new IncomingRequest(7, 7, 1L));
            pumpUntil(proc, dims, () -> p2.getHeldSyncSlots() == 1 && proc.diskSubmits.get() == 2,
                    "re-request submits its own read");
            reader.gate.countDown();
            // Pump on the delivery itself (the last effect of deliverDiskResult), so the
            // done-bit and slot asserts below cannot race the delivering cycle.
            pumpUntil(proc, dims,
                    () -> proc.deliveries.stream().anyMatch(d -> d.cx() == 7 && d.cz() == 7),
                    "re-request resolves");
            assertEquals(0, p2.getHeldSyncSlots());

            var served = proc.deliveries.stream().filter(d -> d.cx() == 7 && d.cz() == 7).toList();
            assertEquals(1, served.size(), "exactly one delivery — the dead primary gets nothing");
            assertEquals(u2, served.get(0).playerUuid());
            assertSame(p2, served.get(0).state());
            assertArrayEquals(new byte[]{1, 2, 3}, served.get(0).bytes());
            assertTrue(p2.hasDiskReadDone(7, 7));
            assertEquals(List.of(), drainResponses(proc),
                    "the served re-request produces no batched answer");
        } finally {
            reader.gate.countDown();
            proc.shutdown();
            reader.shutdown();
        }
    }

    @Test
    void saturatedResultFansOutRateLimitedToEveryRecipientUncounted() throws Exception {
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
        var proc = new TestProcessor(players, reader, Map.of(OVERWORLD, new byte[]{9}));
        var dims = Map.of(u1, OVERWORLD, u2, OVERWORLD);
        try {
            proc.start();
            p1.enqueue(new IncomingRequest(7, 7, 1L));
            pumpUntil(proc, dims, () -> p1.getHeldSyncSlots() == 1 && proc.diskSubmits.get() == 1,
                    "p1 admitted as dedup primary");
            p2.enqueue(new IncomingRequest(7, 7, 1L));
            pumpUntil(proc, dims, () -> p2.getHeldSyncSlots() == 1, "p2 attached");

            // The pool bounced the read — inject the saturated result exactly as
            // AbstractChunkDiskReader's RejectedExecutionException triage would deliver it
            // (the real gated read stays blocked behind the closed gate).
            reader.getPlayerQueue(u1).add(ChunkReadResult.saturated(u1, 7, 7, OVERWORLD, 0L));
            pumpUntil(proc, dims, () -> p1.getHeldSyncSlots() == 0 && p2.getHeldSyncSlots() == 0,
                    "saturated delivery frees both recipients' slots");

            long packed = PositionUtil.packPosition(7, 7);
            var responses = drainUntilResponses(proc, rs -> rs.size() >= 2);
            assertEquals(2, responses.size(), responses.toString());
            assertEquals(Set.of(u1, u2),
                    responses.stream().map(Response::player).collect(Collectors.toSet()),
                    "every recipient of the dedup group must be bounced retryable");
            for (var r : responses) {
                assertEquals(LSSConstants.RESPONSE_RATE_LIMITED, r.type());
                assertEquals(packed, r.packed());
            }
            assertFalse(p1.hasDiskReadDone(7, 7), "saturated is retryable, not served");
            assertFalse(p2.hasDiskReadDone(7, 7));
            // Pin: disk-saturation bounces are NOT rate-limit-counted — only router-level
            // slot bounces are. The soak rate-limit conservation law reads these counters
            // and accounts saturation separately via the disk-reader diagnostics.
            assertEquals(0, proc.getDiagnostics().getTotalSyncRateLimited());
            assertEquals(0, proc.getDiagnostics().getTotalGenRateLimited());

            // The failed group died with its result: a retry submits a fresh read.
            p1.enqueue(new IncomingRequest(7, 7, 1L));
            pumpUntil(proc, dims, () -> proc.diskSubmits.get() == 2, "retry creates a fresh group");
        } finally {
            reader.gate.countDown();
            proc.shutdown();
            reader.shutdown();
        }
    }

    @Test
    void notFoundFanoutAnswersSyncRecipientsAndEscalatesGenTypedAttachment() throws Exception {
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
        var proc = new TestProcessor(players, reader, Map.of(OVERWORLD, new byte[]{9}), true);
        var dims = Map.of(u1, OVERWORLD, u2, OVERWORLD);
        try {
            proc.start();
            p1.enqueue(new IncomingRequest(7, 7, 1L)); // SYNC primary
            pumpUntil(proc, dims, () -> p1.getHeldSyncSlots() == 1 && proc.diskSubmits.get() == 1,
                    "SYNC primary admitted");
            p2.enqueue(new IncomingRequest(7, 7, 0L)); // GENERATION-typed, disk-first attach
            pumpUntil(proc, dims, () -> p2.getHeldSyncSlots() == 1, "GEN-typed request attached");

            reader.getPlayerQueue(u1).add(ChunkReadResult.empty(u1, 7, 7, OVERWORLD, 0L));
            pumpUntil(proc, dims, () -> p2.getHeldGenSlots() == 1,
                    "GEN-typed attachment escalates into its own generation slot");

            assertEquals(0, p1.getHeldSyncSlots());
            assertEquals(0, p2.getHeldSyncSlots(),
                    "the disk-first sync slot is swapped for the generation slot");
            var ticket = awaitTicket(proc);
            assertEquals(u2, ticket.playerUuid(), "the ticket belongs to the attached requester");
            assertEquals(7, ticket.cx());
            assertEquals(7, ticket.cz());
            assertEquals(OVERWORLD, ticket.dimension());
            assertNull(proc.pollGenerationTicketRequest(), "the SYNC primary must not escalate");
            assertTrue(p2.hasPendingRequest(7, 7));

            var responses = drainUntilResponses(proc, rs -> !rs.isEmpty());
            assertEquals(List.of(new Response(u1, LSSConstants.RESPONSE_NOT_GENERATED,
                            PositionUtil.packPosition(7, 7))), responses,
                    "the SYNC recipient is told not-generated; the escalated recipient gets"
                            + " no batched answer (generation will answer it)");
            assertFalse(p1.hasDiskReadDone(7, 7));
            assertFalse(p2.hasDiskReadDone(7, 7));
        } finally {
            reader.gate.countDown();
            proc.shutdown();
            reader.shutdown();
        }
    }

    // ---- DedupTracker map hygiene (direct, same package) ----

    @Test
    void dedupTrackerPrunesEmptyDimensionMapsOnEveryRemovalPath() {
        var tracker = new DedupTracker();
        var u1 = UUID.randomUUID();
        var u2 = UUID.randomUUID();
        long pos1 = PositionUtil.packPosition(1, 1);
        long pos2 = PositionUtil.packPosition(2, 2);
        assertEquals(0, tracker.size());
        assertEquals(0, tracker.trackedDimensionCount());

        assertFalse(tracker.tryAttachOrCreate(pos1, OVERWORLD, u1, 1L));
        assertFalse(tracker.tryAttachOrCreate(pos2, OVERWORLD, u2, 2L));
        assertFalse(tracker.tryAttachOrCreate(pos1, END, u1, 3L));
        assertTrue(tracker.tryAttachOrCreate(pos1, END, u2, 4L), "second requester attaches");
        assertEquals(3, tracker.size(), "an attachment joins a group instead of adding one");
        assertEquals(2, tracker.trackedDimensionCount());

        // removeGroup prunes a dimension's map exactly when its last group leaves
        assertNotNull(tracker.removeGroup(pos1, OVERWORLD));
        assertEquals(2, tracker.size());
        assertEquals(2, tracker.trackedDimensionCount(), "overworld still holds a group");
        assertNotNull(tracker.removeGroup(pos2, OVERWORLD));
        assertEquals(1, tracker.size());
        assertEquals(1, tracker.trackedDimensionCount(),
                "an emptied dimension map must be pruned, not retained — datapack dimensions"
                        + " would accumulate dead maps for a server's whole uptime otherwise");
        assertNull(tracker.removeGroup(pos2, OVERWORLD), "a pruned dimension yields nothing");
        assertEquals(1, tracker.trackedDimensionCount());

        // removePlayer of an attachment keeps the group; of the primary, removes + prunes
        assertEquals(List.of(), tracker.removePlayer(u2),
                "an attached player's removal removes no group");
        assertEquals(1, tracker.size());
        var removedForU1 = tracker.removePlayer(u1);
        assertEquals(1, removedForU1.size());
        assertEquals(pos1, removedForU1.get(0).packed());
        assertTrue(removedForU1.get(0).group().attached().isEmpty(),
                "u2's attachment was already stripped from the surviving group");
        assertEquals(0, tracker.size());
        assertEquals(0, tracker.trackedDimensionCount(),
                "removePlayer must prune every dimension map it empties");
    }
}
