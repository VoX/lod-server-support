package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.SharedBandwidthLimiter;
import dev.vox.lss.common.voxel.ColumnTimestampCache;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the IncomingRequestRouter resolution pipeline through the real processing thread
 * (same harness as {@link OffThreadProcessorMailboxTest}): duplicate check → queue-full
 * break → timestamp ladder → loaded-probe → slot admission → disk/generation submit.
 *
 * <p>Every polled request must end in exactly one disposition: a batched answer
 * (rate-limited / up-to-date / not-generated), a column payload, an in-flight pending
 * entry (which IS the held slot), or a counted duplicate skip. Requests are processed in
 * FIFO order per player, so once the last request's disposition is observed, the whole
 * batch's accounting is final — a silently dropped or misrouted request shows up here as
 * a wrong exact count or a waitFor timeout, not just as a soak-law violation.
 */
class IncomingRequestRouterTest {

    private static final String DIM = "minecraft:overworld";

    private static final class TestState extends AbstractPlayerRequestState<Object> {
        TestState(UUID uuid, int syncCap, int genCap) { super(uuid, syncCap, genCap); }
        @Override public String getPlayerName() { return "test"; }
        void enqueue(IncomingRequest r) { enqueueIncomingRequest(r); }
    }

    private record CapturedSubmit(UUID playerUuid, String dimension, int cx, int cz) {}
    private record CapturedPayload(int cx, int cz, String dimension, long columnTimestamp,
                                   byte[] sectionBytes) {}
    private record Delivered(TestState state, byte type, long packed) {}

    private static final class TestProcessor extends OffThreadProcessor<TestState> {
        final ConcurrentLinkedQueue<CapturedSubmit> submits = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<CapturedPayload> payloads = new ConcurrentLinkedQueue<>();
        volatile boolean failSubmits;

        TestProcessor(Map<UUID, TestState> players, AbstractChunkDiskReader diskReader,
                      boolean generationAvailable, Path dataDir) {
            super(players, diskReader, generationAvailable, dataDir, 1);
        }

        @Override
        protected boolean submitDiskRead(UUID playerUuid, String dimension, int cx, int cz, long order) {
            this.submits.add(new CapturedSubmit(playerUuid, dimension, cx, cz));
            return !this.failSubmits;
        }

        @Override
        protected boolean buildAndEnqueueColumnPayload(TestState state, int cx, int cz, String dimension,
                                                     long columnTimestamp, long submissionOrder,
                                                     byte[] sectionBytes, int estimatedBytes) {
            this.payloads.add(new CapturedPayload(cx, cz, dimension, columnTimestamp, sectionBytes));
            return true;
        }
    }

    /** Non-null reader flips the processor's diskReadingAvailable; submits never reach it. */
    private static final class StubDiskReader extends AbstractChunkDiskReader {
        StubDiskReader() { super(1); }
    }

    // ---- Harness helpers ----

    private static long packed(int cx, int cz) {
        return PositionUtil.packPosition(cx, cz);
    }

    private static TestState addPlayer(Map<UUID, TestState> players, int syncCap, int genCap) {
        var state = new TestState(UUID.randomUUID(), syncCap, genCap);
        state.markHandshakeComplete();
        state.setCapabilities(LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        players.put(state.getPlayerUUID(), state);
        return state;
    }

    private static TickSnapshot snapshot(Map<UUID, Long2ObjectMap<LoadedColumnData>> probes,
                                          int maxSendQueueSize, TestState... states) {
        var dims = new HashMap<UUID, String>();
        for (var s : states) dims.put(s.getPlayerUUID(), DIM);
        return new TickSnapshot(dims, probes, maxSendQueueSize, false);
    }

    private static TickSnapshot snapshot(TestState... states) {
        return snapshot(Map.of(), 0, states);
    }

    /** Pre-populate the processor's timestamp cache through its production load path. */
    private static void seedTimestamps(Path dataDir, long timestamp, long... positions) {
        var seed = new ColumnTimestampCache(ColumnTimestampCache.mbToEntries(1));
        for (long pos : positions) seed.put(DIM, pos, timestamp, 0);
        seed.save(dataDir);
    }

    private static List<Long> submitPositions(TestProcessor proc) {
        return proc.submits.stream().map(s -> packed(s.cx(), s.cz())).toList();
    }

    private static List<IncomingRequest> incoming(TestState state) {
        var list = new ArrayList<IncomingRequest>();
        state.getIncomingRequests().forEach(list::add);
        return list;
    }

    private static Predicate<List<Delivered>> contains(byte type, long packedPos) {
        return list -> list.stream().anyMatch(d -> d.type() == type && d.packed() == packedPos);
    }

    private static long count(List<Delivered> list, byte type, long packedPos) {
        return list.stream().filter(d -> d.type() == type && d.packed() == packedPos).count();
    }

    /** Accumulate drained batch responses until {@code done} or a deadline passes. */
    private static List<Delivered> drainUntil(TestProcessor proc, Predicate<List<Delivered>> done)
            throws InterruptedException {
        var delivered = new ArrayList<Delivered>();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!done.test(delivered) && System.nanoTime() < deadline) {
            proc.drainSendActions((state, types, positions, count) -> {
                for (int i = 0; i < count; i++) delivered.add(new Delivered(state, types[i], positions[i]));
            });
            Thread.sleep(10);
        }
        return delivered;
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for: " + what);
            Thread.sleep(10);
        }
    }

    // ---- Tests ----

    @Test
    void mixedBatchEveryRequestGetsExactlyOneDisposition(@TempDir Path tempDir) throws Exception {
        seedTimestamps(tempDir, 1000L, packed(4, 0));
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 2, 1);
        var proc = new TestProcessor(players, null, true, tempDir);
        try {
            proc.start();
            // 9 requests, FIFO; every disposition the router can produce appears at least once
            p1.enqueue(new IncomingRequest(1, 0, -1));   // disk submit (sync slot 1/2)
            p1.enqueue(new IncomingRequest(1, 0, -1));   // duplicate skip (pending), silent
            p1.enqueue(new IncomingRequest(2, 0, 0));    // generation ticket (gen slot 1/1)
            p1.enqueue(new IncomingRequest(3, 0, 0));    // gen rate-limited (gen slot full)
            p1.enqueue(new IncomingRequest(4, 0, 2000)); // up-to-date (cached 1000 <= 2000)
            p1.enqueue(new IncomingRequest(4, 0, 3000)); // ts>0 duplicate answered (diskReadDone)
            p1.enqueue(new IncomingRequest(5, 0, -1));   // in-memory probe payload, no slot
            p1.enqueue(new IncomingRequest(6, 0, -1));   // disk submit (sync slot 2/2)
            p1.enqueue(new IncomingRequest(7, 0, -1));   // sync rate-limited — batch marker
            var probes = new Long2ObjectOpenHashMap<LoadedColumnData>();
            probes.put(packed(5, 0), new LoadedColumnData(5, 0, new byte[]{1, 2, 3}, 48));
            proc.postSnapshot(snapshot(Map.of(p1.getPlayerUUID(), probes), 0, p1), List.of());

            var delivered = drainUntil(proc,
                    contains(LSSConstants.RESPONSE_RATE_LIMITED, packed(7, 0)));

            // Answered now: gen bounce, the two (4,0) up-to-dates, sync bounce — nothing else
            assertEquals(4, delivered.size(), "exactly four batched answers: " + delivered);
            assertEquals(1, count(delivered, LSSConstants.RESPONSE_RATE_LIMITED, packed(3, 0)));
            assertEquals(2, count(delivered, LSSConstants.RESPONSE_UP_TO_DATE, packed(4, 0)));
            assertEquals(1, count(delivered, LSSConstants.RESPONSE_RATE_LIMITED, packed(7, 0)));
            assertEquals(1, proc.getDiagnostics().getTotalGenRateLimited());
            assertEquals(1, proc.getDiagnostics().getTotalSyncRateLimited());

            // Served from memory: only the probe position, with the probe's bytes and a real timestamp
            assertEquals(1, proc.payloads.size());
            var payload = proc.payloads.peek();
            assertEquals(packed(5, 0), packed(payload.cx(), payload.cz()));
            assertEquals(DIM, payload.dimension());
            assertArrayEquals(new byte[]{1, 2, 3}, payload.sectionBytes());
            assertTrue(payload.columnTimestamp() > 0,
                    "probe-served columns must carry the cycle timestamp for up-to-date checks");

            // In-flight: the slot IS the pending entry, one per submit/ticket
            assertEquals(List.of(packed(1, 0), packed(6, 0)), submitPositions(proc));
            var ticket = proc.pollGenerationTicketRequest();
            assertNotNull(ticket);
            assertEquals(2, ticket.cx());
            assertEquals(0, ticket.cz());
            assertEquals(DIM, ticket.dimension());
            assertEquals(p1.getPlayerUUID(), ticket.playerUuid());
            assertNull(proc.pollGenerationTicketRequest());
            assertEquals(2, p1.getHeldSyncSlots());
            assertEquals(1, p1.getHeldGenSlots());
            for (int cx : new int[]{1, 2, 6}) assertTrue(p1.hasPendingRequest(cx, 0), "in-flight " + cx);
            for (int cx : new int[]{3, 4, 5, 7}) assertFalse(p1.hasPendingRequest(cx, 0), "resolved " + cx);

            var diag = proc.getDiagnostics();
            assertEquals(9, diag.getTotalRequestsRouted());
            assertEquals(1, diag.getTotalDuplicateSkips());
            assertEquals(1, diag.getTotalUpToDate());
            assertEquals(1, diag.getTotalInMemory());
            assertEquals(0, diag.getTotalQueueFull());

            // Conservation: routed == answered + payloads + in-flight + silent duplicate skips
            assertEquals(diag.getTotalRequestsRouted(),
                    delivered.size() + proc.payloads.size()
                            + p1.getHeldSyncSlots() + p1.getHeldGenSlots()
                            + diag.getTotalDuplicateSkips());
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void playerWithoutVoxelCapabilityIsSkippedWithBacklogIntact() throws Exception {
        var players = new ConcurrentHashMap<UUID, TestState>();
        var noCaps = new TestState(UUID.randomUUID(), 2, 1);
        noCaps.markHandshakeComplete(); // handshake done but CAPABILITY_VOXEL_COLUMNS unset
        players.put(noCaps.getPlayerUUID(), noCaps);
        var marker = addPlayer(players, 2, 1);
        var proc = new TestProcessor(players, null, false, null);
        try {
            proc.start();
            noCaps.enqueue(new IncomingRequest(1, 1, -1));
            marker.enqueue(new IncomingRequest(2, 2, -1));
            proc.postSnapshot(snapshot(noCaps, marker), List.of());
            waitFor(() -> submitPositions(proc).contains(packed(2, 2)), "marker routed");

            assertEquals(0, noCaps.getHeldSyncSlots());

            // Cycle barrier: by the time the second marker routes, cycle 1 is fully done,
            // so a regression that polled the capability-less queue would already show.
            marker.enqueue(new IncomingRequest(3, 3, -1));
            proc.postSnapshot(snapshot(noCaps, marker), List.of());
            waitFor(() -> submitPositions(proc).contains(packed(3, 3)), "second marker routed");
            assertEquals(List.of(new IncomingRequest(1, 1, -1)), incoming(noCaps),
                    "skip must never consume the capability-less player's queue");
            assertEquals(2, proc.getDiagnostics().getTotalRequestsRouted(),
                    "skipped requests are never polled, so never counted as routed");

            // The skip preserves the backlog: once the capability arrives, it routes normally
            noCaps.setCapabilities(LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            proc.postSnapshot(snapshot(noCaps, marker), List.of());
            waitFor(() -> submitPositions(proc).contains(packed(1, 1)), "backlog routed after capability set");
            assertEquals(List.of(), incoming(noCaps));
            assertEquals(1, noCaps.getHeldSyncSlots());
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void clientTimestampLadderDecidesUpToDateVsReserve(@TempDir Path tempDir) throws Exception {
        long cached = 1000L;
        seedTimestamps(tempDir, cached, packed(10, 0), packed(11, 0), packed(12, 0), packed(14, 0));
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 8, 8);
        var reader = new StubDiskReader();
        var proc = new TestProcessor(players, reader, true, tempDir);
        try {
            proc.start();
            p1.enqueue(new IncomingRequest(10, 0, -1));         // unknown: cached ts must be ignored
            p1.enqueue(new IncomingRequest(11, 0, cached));     // boundary: cachedTs == clientTs is fresh
            p1.enqueue(new IncomingRequest(12, 0, cached - 1)); // stale client copy: re-serve
            p1.enqueue(new IncomingRequest(13, 0, 500));        // never served (no cache entry): re-serve
            p1.enqueue(new IncomingRequest(14, 0, 0));          // generation request, reader present
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> submitPositions(proc).contains(packed(14, 0)), "last request submitted");

            assertEquals(List.of(packed(10, 0), packed(12, 0), packed(13, 0), packed(14, 0)),
                    submitPositions(proc),
                    "exactly the non-fresh positions reach the disk reader, in request order");
            var delivered = drainUntil(proc, contains(LSSConstants.RESPONSE_UP_TO_DATE, packed(11, 0)));
            assertEquals(1, delivered.size(), "the fresh position is the only answered request");
            assertEquals(1, proc.getDiagnostics().getTotalUpToDate());
            assertTrue(p1.hasDiskReadDone(11, 0), "up-to-date marks the position served");
            assertFalse(p1.hasPendingRequest(11, 0));
            assertEquals(4, p1.getHeldSyncSlots(),
                    "ts=0 takes the disk-first sync slot when a reader exists");
            assertEquals(0, p1.getHeldGenSlots());
            assertNull(proc.pollGenerationTicketRequest(),
                    "disk-first: no generation ticket while the reader can answer");
        } finally {
            proc.shutdown();
            reader.shutdown();
        }
    }

    @Test
    void generationTimestampRoutesToGenerationSlotWithoutDiskReader(@TempDir Path tempDir) throws Exception {
        // Seed the requested position: ts=0 must bypass the timestamp ladder entirely
        seedTimestamps(tempDir, 1000L, packed(20, 0));
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 2, 1);
        var proc = new TestProcessor(players, null, true, tempDir);
        try {
            proc.start();
            p1.enqueue(new IncomingRequest(20, 0, 0));  // generation request, no reader
            p1.enqueue(new IncomingRequest(21, 0, -1)); // SYNC takes the disk route even without a reader
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> submitPositions(proc).contains(packed(21, 0)), "sync request submitted");

            var ticket = proc.pollGenerationTicketRequest();
            assertNotNull(ticket, "ts=0 without a reader must queue a generation ticket");
            assertEquals(20, ticket.cx());
            assertEquals(0, ticket.cz());
            assertEquals(DIM, ticket.dimension());
            assertEquals(p1.getPlayerUUID(), ticket.playerUuid());
            assertNull(proc.pollGenerationTicketRequest());
            assertEquals(List.of(packed(21, 0)), submitPositions(proc),
                    "the generation request must not reach the disk path");
            assertEquals(1, p1.getHeldGenSlots());
            assertEquals(1, p1.getHeldSyncSlots());
            assertTrue(p1.hasPendingRequest(20, 0));
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void submitFailureUnwindsSlotAndDedupGroupAndAnswersClient() throws Exception {
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 2, 1);
        var proc = new TestProcessor(players, null, false, null);
        try {
            proc.start();
            proc.failSubmits = true;
            p1.enqueue(new IncomingRequest(9, 9, -1));
            proc.postSnapshot(snapshot(p1), List.of());

            var delivered = drainUntil(proc,
                    contains(LSSConstants.RESPONSE_NOT_GENERATED, packed(9, 9)));
            assertEquals(1, delivered.size(),
                    "failed submit must answer exactly one not-generated: " + delivered);
            assertEquals(List.of(packed(9, 9)), submitPositions(proc));
            assertFalse(p1.hasPendingRequest(9, 9), "pending entry must be unwound");
            assertEquals(0, p1.getHeldSyncSlots(), "slot must be freed");

            // The dedup group must be unwound too: the next request for the position must
            // create a fresh group and re-submit. A leaked (orphaned) group would swallow
            // it — attach, hold the slot forever, never submit, never answer — and this
            // waitFor would time out.
            proc.failSubmits = false;
            p1.enqueue(new IncomingRequest(9, 9, -1));
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> proc.submits.size() == 2, "re-request submitted after unwind");
            assertEquals(List.of(packed(9, 9), packed(9, 9)), submitPositions(proc));
            assertTrue(p1.hasPendingRequest(9, 9));
            assertEquals(1, p1.getHeldSyncSlots());
            assertEquals(0, proc.getDiagnostics().getTotalDuplicateSkips(),
                    "unwound entry must not look like an in-flight duplicate");
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void servedPositionReRequestsFollowTheHonestReResolutionLadder(@TempDir Path tempDir) throws Exception {
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 8, 2);
        var proc = new TestProcessor(players, null, true, tempDir);
        try {
            proc.start();
            // Cycle 1: probe-serve (30,0) — resolution marks diskReadDone before delivery
            p1.enqueue(new IncomingRequest(30, 0, -1));
            var probes = new Long2ObjectOpenHashMap<LoadedColumnData>();
            probes.put(packed(30, 0), new LoadedColumnData(30, 0, new byte[]{7}, 16));
            proc.postSnapshot(snapshot(Map.of(p1.getPlayerUUID(), probes), 0, p1), List.of());
            waitFor(() -> p1.hasDiskReadDone(30, 0), "probe served");
            assertEquals(1, proc.payloads.size());

            // (31,0)/(32,0): served, payload still in the send pipeline. (33,0): served
            // and the client kept its data (its re-ask carries ts>0).
            p1.markDiskReadDone(31, 0);
            p1.addReadyPayload(new QueuedPayload<>(new Object(), 10, 1, packed(31, 0)));
            p1.markDiskReadDone(32, 0);
            p1.addReadyPayload(new QueuedPayload<>(new Object(), 10, 2, packed(32, 0)));
            p1.markDiskReadDone(33, 0);

            // Cycle 2: the four re-request flavors. (30,0)'s payload was "lost" after the
            // send (nothing enqueued): the old router answered up-to-date and sealed a
            // permanent invisible hole — it must re-resolve instead.
            p1.enqueue(new IncomingRequest(30, 0, -1)); // lost after send: re-resolve to disk
            p1.enqueue(new IncomingRequest(31, 0, -1)); // still in pipeline: rate-limited (sync)
            p1.enqueue(new IncomingRequest(32, 0, 0));  // still in pipeline: rate-limited (gen)
            p1.enqueue(new IncomingRequest(33, 0, 50)); // client has data: up-to-date is honest
            proc.postSnapshot(snapshot(p1), List.of());

            var delivered = drainUntil(proc, contains(LSSConstants.RESPONSE_UP_TO_DATE, packed(33, 0)));
            assertEquals(1, count(delivered, LSSConstants.RESPONSE_RATE_LIMITED, packed(31, 0)));
            assertEquals(1, count(delivered, LSSConstants.RESPONSE_RATE_LIMITED, packed(32, 0)));
            assertEquals(1, count(delivered, LSSConstants.RESPONSE_UP_TO_DATE, packed(33, 0)));
            assertEquals(3, delivered.size(),
                    "the lost position must not be answered at all this cycle: " + delivered);

            // The lost position re-entered resolution: disk submit with a pending slot
            assertEquals(List.of(packed(30, 0)), submitPositions(proc));
            assertTrue(p1.hasPendingRequest(30, 0));
            // Bounced and ts>0 positions keep the done-bit
            assertTrue(p1.hasDiskReadDone(31, 0));
            assertTrue(p1.hasDiskReadDone(32, 0));
            assertTrue(p1.hasDiskReadDone(33, 0));

            var diag = proc.getDiagnostics();
            assertEquals(1, diag.getTotalReResolved());
            assertEquals(1, diag.getTotalSyncRateLimited(),
                    "sync bounce counted for rate-limit conservation (soak law B1)");
            assertEquals(1, diag.getTotalGenRateLimited(),
                    "gen bounce counted for rate-limit conservation (soak law B1)");
            assertEquals(0, diag.getTotalUpToDate(),
                    "diskReadDone answers are not timestamp-ladder up-to-dates");
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void sendQueueFullBreaksRoutingButAnswersDuplicatesAndKeepsBacklog() throws Exception {
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 4, 1);
        var p2 = addPlayer(players, 4, 1);
        // One stuck payload + maxSendQueueSize=1 makes p1's send queue read as full
        p1.addReadyPayload(new QueuedPayload<>(new Object(), 100, 0, packed(5, 5)));
        p1.flushSendQueue(0, new SharedBandwidthLimiter(1_000_000), new TickDiagnostics(),
                payload -> fail("zero allocation must not send"));
        assertEquals(1, p1.getSendQueueSize());
        p1.markDiskReadDone(5, 5); // makes the first queued request a known duplicate

        var proc = new TestProcessor(players, null, false, null);
        try {
            proc.start();
            p1.enqueue(new IncomingRequest(5, 5, -1)); // duplicate (payload enqueued): bounced despite the full queue
            p1.enqueue(new IncomingRequest(6, 6, -1)); // hits the full queue: consumed, breaks the loop
            p1.enqueue(new IncomingRequest(7, 7, -1)); // behind the break: must stay queued
            proc.postSnapshot(snapshot(Map.of(), 1, p1), List.of());
            waitFor(() -> proc.getDiagnostics().getTotalQueueFull() == 1, "queue-full break");

            // Cycle barrier: a p2-only snapshot proves cycle 1 finished without touching p1
            // again — the break-to-continue regression would consume p1's whole backlog.
            p2.enqueue(new IncomingRequest(50, 50, -1));
            proc.postSnapshot(snapshot(Map.of(), 1, p2), List.of());
            waitFor(() -> submitPositions(proc).contains(packed(50, 50)), "barrier player routed");

            assertEquals(List.of(new IncomingRequest(7, 7, -1)), incoming(p1),
                    "break must leave the rest of the backlog for the next cycle");
            var delivered = drainUntil(proc, contains(LSSConstants.RESPONSE_RATE_LIMITED, packed(5, 5)));
            assertEquals(1, delivered.size(),
                    "duplicate answered before the queue-full check; the breaker gets nothing: " + delivered);
            assertSame(p1, delivered.get(0).state());
            assertTrue(p1.hasDiskReadDone(5, 5),
                    "an enqueued-bounce keeps the done-bit — the payload is still coming");
            assertEquals(1, proc.getDiagnostics().getTotalSyncRateLimited(),
                    "the bounce must be counted for rate-limit conservation (soak law B1)");
            assertEquals(List.of(packed(50, 50)), submitPositions(proc),
                    "no submit for the breaker or the backlog");
            assertEquals(0, p1.getHeldSyncSlots());
            assertFalse(p1.hasPendingRequest(6, 6));
            assertEquals(1, proc.getDiagnostics().getTotalQueueFull());
            assertEquals(3, proc.getDiagnostics().getTotalRequestsRouted(),
                    "only the duplicate, the breaker, and the barrier are ever polled");
        } finally {
            proc.shutdown();
        }
    }
}
