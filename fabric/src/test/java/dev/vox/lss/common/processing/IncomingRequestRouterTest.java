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
import java.util.concurrent.atomic.AtomicReference;
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
        volatile boolean dropPayloads; // models the platform oversized-payload drop (payloads still records the attempt)

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
            return !this.dropPayloads;
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

    /** A successful disk read result, injected straight into the stub reader's queue. */
    private static ChunkReadResult dataResult(UUID u, int cx, int cz, byte[] bytes, long ts, long order) {
        return new ChunkReadResult(u, cx, cz, bytes, DIM,
                bytes.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES, ts, false, false, order);
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
            // diskQueued is counted only AFTER a successful submit (per-tick counter; it has
            // no cumulative twin) — the unwound request must not appear queued anywhere.
            assertEquals(0, proc.getDiagnostics().getLastDiskQueued(),
                    "a failed submit must never count as disk-queued");

            // The dedup group must be unwound too: the next request for the position must
            // create a fresh group and re-submit. A leaked (orphaned) group would swallow
            // it — attach, hold the slot forever, never submit, never answer — and this
            // waitFor would time out.
            proc.failSubmits = false;
            p1.enqueue(new IncomingRequest(9, 9, -1));
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> proc.submits.size() == 2, "re-request submitted after unwind");
            waitFor(() -> proc.getDiagnostics().getLastDiskQueued() == 1,
                    "the successful re-submit is the increment site for disk-queued");
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

    @Test
    void freshTimestampBeatsLoadedProbeInTheLadder(@TempDir Path tempDir) throws Exception {
        seedTimestamps(tempDir, 1000L, packed(90, 0));
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 4, 4);
        var proc = new TestProcessor(players, null, true, tempDir);
        try {
            proc.start();
            // Fresh cache entry AND a loaded probe for the same position: the timestamp
            // step sits BEFORE the probe step, so the request must resolve up-to-date
            // without serializing or shipping the probe's bytes.
            p1.enqueue(new IncomingRequest(90, 0, 2000));
            var probes = new Long2ObjectOpenHashMap<LoadedColumnData>();
            probes.put(packed(90, 0), new LoadedColumnData(90, 0, new byte[]{1}, 16));
            proc.postSnapshot(snapshot(Map.of(p1.getPlayerUUID(), probes), 0, p1), List.of());

            var delivered = drainUntil(proc, contains(LSSConstants.RESPONSE_UP_TO_DATE, packed(90, 0)));
            assertEquals(1, delivered.size(), "up-to-date is the only answer: " + delivered);
            assertEquals(0, proc.payloads.size(), "the loaded probe must never be served");
            assertEquals(0, proc.getDiagnostics().getTotalInMemory(),
                    "a timestamp resolution must not count as an in-memory serve");
            assertEquals(1, proc.getDiagnostics().getTotalUpToDate());
            assertTrue(p1.hasDiskReadDone(90, 0));
            assertEquals(0, p1.getHeldSyncSlots());
            assertFalse(p1.hasPendingRequest(90, 0));
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void crossingReAskCostsExactlyOneRedundantServeThenConverges() throws Exception {
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 4, 4);
        var reader = new StubDiskReader();
        reader.registerPlayer(p1.getPlayerUUID());
        var proc = new TestProcessor(players, reader, false, null);
        try {
            proc.start();
            // Serve and deliver: this processor never enqueues payloads, so after the
            // delivery nothing is left in the send pipeline (the payload "fully left").
            p1.enqueue(new IncomingRequest(70, 0, -1));
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> proc.submits.size() == 1, "initial disk submit");
            reader.getPlayerQueue(p1.getPlayerUUID())
                    .add(dataResult(p1.getPlayerUUID(), 70, 0, new byte[]{1}, 5000L, 1L));
            proc.postSnapshot(snapshot(p1), List.of());
            // The payload capture is the delivery's last effect: once visible, the
            // done-bit (written just before it) is too.
            waitFor(() -> proc.payloads.size() == 1, "first serve delivered");
            assertTrue(p1.hasDiskReadDone(70, 0));

            // The client's timeout re-ask crossed its own payload's delivery: it still
            // claims nothing (ts<=0) for a position the server resolved AND sent. The
            // documented race bound: this costs exactly one redundant re-serve.
            p1.enqueue(new IncomingRequest(70, 0, -1));
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> proc.submits.size() == 2, "honest re-resolution re-reads the disk");
            assertEquals(1, proc.getDiagnostics().getTotalReResolved());
            assertFalse(p1.hasDiskReadDone(70, 0), "re-resolution forgets the stale done-bit");
            assertTrue(p1.hasPendingRequest(70, 0));

            reader.getPlayerQueue(p1.getPlayerUUID())
                    .add(dataResult(p1.getPlayerUUID(), 70, 0, new byte[]{1}, 5005L, 2L));
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> proc.payloads.size() == 2, "redundant serve delivered");
            assertTrue(p1.hasDiskReadDone(70, 0));

            // Convergence: the client now holds the second payload's stamp; a ts>0 ask is
            // answered up-to-date from the done-bit with no third read or serve.
            p1.enqueue(new IncomingRequest(70, 0, 5005L));
            proc.postSnapshot(snapshot(p1), List.of());
            var delivered = drainUntil(proc, contains(LSSConstants.RESPONSE_UP_TO_DATE, packed(70, 0)));
            assertEquals(1, delivered.size(), "converged: " + delivered);
            assertEquals(2, proc.payloads.size(), "no serve beyond the single redundant one");
            assertEquals(2, proc.submits.size());
            assertEquals(1, proc.getDiagnostics().getTotalReResolved());
        } finally {
            proc.shutdown();
            reader.shutdown();
        }
    }

    @Test
    void duplicateSkipDoesNotAttachToTheDedupGroup() throws Exception {
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 4, 4);
        var reader = new StubDiskReader();
        reader.registerPlayer(p1.getPlayerUUID());
        var proc = new TestProcessor(players, reader, false, null);
        try {
            proc.start();
            p1.enqueue(new IncomingRequest(80, 0, -1));
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> proc.submits.size() == 1, "primary submit");

            // In-flight duplicate: counted skip, silent — and crucially NOT attached to
            // the position's dedup group.
            p1.enqueue(new IncomingRequest(80, 0, -1));
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> proc.getDiagnostics().getTotalDuplicateSkips() == 1, "duplicate skipped");

            // Deliver the read with a same-cycle marker request: routing runs after the
            // drain phase, so once the marker submits, the drain (including any erroneous
            // dedup fan-out) is final.
            reader.getPlayerQueue(p1.getPlayerUUID())
                    .add(dataResult(p1.getPlayerUUID(), 80, 0, new byte[]{1}, 5000L, 1L));
            p1.enqueue(new IncomingRequest(81, 0, -1));
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> submitPositions(proc).contains(packed(81, 0)), "marker routed after drain");

            assertEquals(1, proc.payloads.stream()
                            .filter(p -> packed(p.cx(), p.cz()) == packed(80, 0)).count(),
                    "a skip that attached to the dedup group would double-deliver the result");
            assertTrue(p1.hasDiskReadDone(80, 0));
            assertFalse(p1.hasPendingRequest(80, 0));
        } finally {
            proc.shutdown();
            reader.shutdown();
        }
    }

    @Test
    void queueFullGateSitsBetweenDoneBitAnswersAndTheTimestampLadder(@TempDir Path tempDir) throws Exception {
        seedTimestamps(tempDir, 1000L, packed(95, 0));
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 4, 1);
        var p2 = addPlayer(players, 4, 1);
        p1.addReadyPayload(new QueuedPayload<>(new Object(), 100, 0, packed(99, 99)));
        p1.flushSendQueue(0, new SharedBandwidthLimiter(1_000_000), new TickDiagnostics(),
                payload -> fail("zero allocation must not send"));
        assertEquals(1, p1.getSendQueueSize());
        p1.markDiskReadDone(94, 0);

        var proc = new TestProcessor(players, null, false, tempDir);
        try {
            proc.start();
            p1.enqueue(new IncomingRequest(94, 0, 500));  // done + ts>0: answered before the gate
            p1.enqueue(new IncomingRequest(95, 0, 2000)); // fresh cache hit: the gate consumes it first
            proc.postSnapshot(snapshot(Map.of(), 1, p1), List.of());
            waitFor(() -> proc.getDiagnostics().getTotalQueueFull() == 1, "queue-full break");

            // Cycle barrier: a p2-only snapshot proves cycle 1 fully finished.
            p2.enqueue(new IncomingRequest(50, 50, -1));
            proc.postSnapshot(snapshot(Map.of(), 1, p2), List.of());
            waitFor(() -> submitPositions(proc).contains(packed(50, 50)), "barrier player routed");

            var delivered = drainUntil(proc, contains(LSSConstants.RESPONSE_UP_TO_DATE, packed(94, 0)));
            assertEquals(1, delivered.size(),
                    "done+ts>0 answers up-to-date despite the full queue; the cache hit gets nothing: "
                            + delivered);
            // The timestamp ladder sits AFTER the gate: the fresh cache hit was consumed
            // by the break without resolving (no done-bit, no ladder up-to-date) — the
            // client retries it on a later scan.
            assertFalse(p1.hasDiskReadDone(95, 0),
                    "a request consumed by the queue-full break must not be marked served");
            assertEquals(0, proc.getDiagnostics().getTotalUpToDate(),
                    "done-bit answers are not ladder up-to-dates, and the cache hit never reached the ladder");
            assertEquals(1, proc.getDiagnostics().getTotalQueueFull());
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void queueCapZeroDisablesTheGateEvenWithANonzeroQueue() throws Exception {
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 4, 1);
        // A genuinely nonzero cross-thread queue-size snapshot (the flush publishes it)
        p1.addReadyPayload(new QueuedPayload<>(new Object(), 100, 0, packed(99, 99)));
        p1.flushSendQueue(0, new SharedBandwidthLimiter(1_000_000), new TickDiagnostics(),
                payload -> fail("zero allocation must not send"));
        assertEquals(1, p1.getSendQueueSize());

        var proc = new TestProcessor(players, null, false, null);
        try {
            proc.start();
            // maxSendQueueSize=0 is the "no limit" sentinel, not a literal capacity: a
            // regression reading it literally (queue 1 >= cap 0) would consume every
            // request unanswered and permanently starve clients of admins who disabled
            // the cap — this waitFor would time out.
            p1.enqueue(new IncomingRequest(97, 0, -1));
            p1.enqueue(new IncomingRequest(98, 0, -1));
            proc.postSnapshot(snapshot(Map.of(), 0, p1), List.of());
            waitFor(() -> proc.submits.size() == 2, "cap 0 must route everything");

            assertEquals(List.of(packed(97, 0), packed(98, 0)), submitPositions(proc));
            assertEquals(0, proc.getDiagnostics().getTotalQueueFull());
            assertEquals(2, p1.getHeldSyncSlots());
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void generationRequestWithNoDiskAndNoGenerationAnswersNotGenerated() throws Exception {
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 2, 1);
        var proc = new TestProcessor(players, null, false, null); // no reader, no generation
        try {
            proc.start();
            p1.enqueue(new IncomingRequest(99, 0, 0)); // GENERATION request, nothing can serve it
            proc.postSnapshot(snapshot(p1), List.of());

            var delivered = drainUntil(proc, contains(LSSConstants.RESPONSE_NOT_GENERATED, packed(99, 0)));
            assertEquals(1, delivered.size(), "exactly one not-generated answer: " + delivered);
            assertEquals(0, proc.submits.size(),
                    "a GENERATION request must not take the disk route without a reader");
            assertNull(proc.pollGenerationTicketRequest());
            assertEquals(0, p1.getHeldSyncSlots(), "the can't-serve branch must not hold a slot");
            assertEquals(0, p1.getHeldGenSlots());
            assertFalse(p1.hasPendingRequest(99, 0));
            assertFalse(p1.hasDiskReadDone(99, 0), "not-generated is not served");
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void clientTimestampExtremesRouteThroughTheLadder(@TempDir Path tempDir) throws Exception {
        seedTimestamps(tempDir, 1000L, packed(100, 0), packed(101, 0), packed(102, 0));
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 4, 4);
        var proc = new TestProcessor(players, null, true, tempDir);
        try {
            proc.start();
            p1.enqueue(new IncomingRequest(100, 0, Long.MIN_VALUE));
            p1.enqueue(new IncomingRequest(101, 0, -5));
            p1.enqueue(new IncomingRequest(102, 0, Long.MAX_VALUE));
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> proc.submits.size() == 2, "ts<-1 claims route to re-serve");

            // ts<=0 never consults the cache (there is no claim to compare) and ts!=0 is
            // never a generation request: Long.MIN_VALUE and -5 route SYNC, despite the
            // fresh cache entries for their positions.
            assertEquals(List.of(packed(100, 0), packed(101, 0)), submitPositions(proc));
            assertEquals(2, p1.getHeldSyncSlots());
            assertEquals(0, p1.getHeldGenSlots(), "ts<-1 must never be mistaken for a generation request");
            assertNull(proc.pollGenerationTicketRequest());

            var delivered = drainUntil(proc, contains(LSSConstants.RESPONSE_UP_TO_DATE, packed(102, 0)));
            assertEquals(1, delivered.size(),
                    "a Long.MAX_VALUE claim covers any cached stamp: " + delivered);
            assertEquals(1, proc.getDiagnostics().getTotalUpToDate());
            assertTrue(p1.hasDiskReadDone(102, 0));
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void twentyRemoveReRegisterCyclesWithInFlightWorkNeverErodeCapacity() throws Exception {
        var players = new ConcurrentHashMap<UUID, TestState>();
        var uuid = UUID.randomUUID();
        var proc = new TestProcessor(players, null, true, null);
        try {
            proc.start();
            for (int cycle = 1; cycle <= 20; cycle++) {
                var state = new TestState(uuid, 2, 1);
                state.markHandshakeComplete();
                state.setCapabilities(LSSConstants.CAPABILITY_VOXEL_COLUMNS);
                players.put(uuid, state);
                assertEquals(0, state.getHeldSyncSlots(), "slot counts die with the removed state");
                assertEquals(0, state.getHeldGenSlots());

                // Fill both slot types to cap at the SAME positions every cycle; the disk
                // reads never resolve, so each removal happens with work still in flight.
                state.enqueue(new IncomingRequest(1, 1, -1));
                state.enqueue(new IncomingRequest(1, 2, -1));
                state.enqueue(new IncomingRequest(1, 3, 0));
                proc.postSnapshot(snapshot(state), List.of());
                int expectedSubmits = cycle * 2;
                waitFor(() -> state.getHeldSyncSlots() == 2 && state.getHeldGenSlots() == 1
                                && proc.submits.size() == expectedSubmits,
                        "cycle " + cycle + " re-admits to full capacity with fresh submits");

                var ticket = awaitTicket(proc);
                assertEquals(packed(1, 3), packed(ticket.cx(), ticket.cz()), "cycle " + cycle + " ticket");
                assertNull(proc.pollGenerationTicketRequest(), "exactly one ticket per cycle");

                // Disconnect with everything still in flight
                players.remove(uuid);
                proc.notifyPlayerRemoved(uuid);
            }
            // Exact per-cycle submit counts already proved no erosion: a dedup group leaked
            // from any dead session would have swallowed a later cycle's request (attach,
            // no submit) and timed the loop out; a leaked pending would have read as a
            // duplicate. 20 cycles x 2 sync submits:
            assertEquals(40, proc.submits.size());
            assertEquals(0, proc.getDiagnostics().getTotalDuplicateSkips(),
                    "fresh sessions must never see a dead session's pendings");
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void quiescentFullLedgerBalancesEveryDispositionClass(@TempDir Path tempDir) throws Exception {
        seedTimestamps(tempDir, 1000L, packed(3, 0));
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 4, 1);
        var reader = new StubDiskReader();
        reader.registerPlayer(p1.getPlayerUUID());
        var proc = new TestProcessor(players, reader, true, tempDir);
        try {
            proc.start();
            // Cycle 1: two disk submits + one silent duplicate skip
            p1.enqueue(new IncomingRequest(1, 0, -1)); // SYNC -> disk, will resolve not-found
            p1.enqueue(new IncomingRequest(1, 0, -1)); // duplicate of the in-flight read
            p1.enqueue(new IncomingRequest(2, 0, 0));  // GENERATION -> disk-first, will escalate
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> proc.submits.size() == 2
                    && proc.getDiagnostics().getTotalDuplicateSkips() == 1, "cycle 1 routed");

            // Cycle 2: both reads come back not-found; ladder answers + a probe payload
            reader.getPlayerQueue(p1.getPlayerUUID())
                    .add(ChunkReadResult.empty(p1.getPlayerUUID(), 1, 0, DIM, 1L));
            reader.getPlayerQueue(p1.getPlayerUUID())
                    .add(ChunkReadResult.empty(p1.getPlayerUUID(), 2, 0, DIM, 2L));
            p1.enqueue(new IncomingRequest(3, 0, 2000)); // timestamp-ladder up-to-date (cached 1000)
            p1.enqueue(new IncomingRequest(3, 0, 3000)); // done-bit duplicate up-to-date
            p1.enqueue(new IncomingRequest(4, 0, -1));   // in-memory probe payload
            var probes = new Long2ObjectOpenHashMap<LoadedColumnData>();
            probes.put(packed(4, 0), new LoadedColumnData(4, 0, new byte[]{1}, 16));
            proc.postSnapshot(snapshot(Map.of(p1.getPlayerUUID(), probes), 0, p1), List.of());
            waitFor(() -> p1.getHeldGenSlots() == 1 && proc.payloads.size() == 1,
                    "cycle 2 drained and routed");

            // Cycle 3: a queue-full breaker is consumed unanswered
            p1.addReadyPayload(new QueuedPayload<>(new Object(), 100, 0, packed(99, 99)));
            p1.flushSendQueue(0, new SharedBandwidthLimiter(1_000_000), new TickDiagnostics(),
                    payload -> fail("zero allocation must not send"));
            p1.enqueue(new IncomingRequest(5, 0, -1));
            proc.postSnapshot(snapshot(Map.of(), 1, p1), List.of());
            waitFor(() -> proc.getDiagnostics().getTotalQueueFull() == 1, "queue-full break");

            var delivered = drainUntil(proc, d ->
                    count(d, LSSConstants.RESPONSE_UP_TO_DATE, packed(3, 0)) == 2
                            && count(d, LSSConstants.RESPONSE_NOT_GENERATED, packed(1, 0)) == 1);
            assertEquals(3, delivered.size(), "answers at rest: " + delivered);

            var ticket = proc.pollGenerationTicketRequest();
            assertNotNull(ticket, "the not-found GENERATION read escalates to a gen ticket");
            assertEquals(packed(2, 0), packed(ticket.cx(), ticket.cz()));
            assertNull(proc.pollGenerationTicketRequest());
            assertTrue(p1.hasPendingRequest(2, 0), "the escalated ticket is the only in-flight work");
            assertEquals(0, p1.getHeldSyncSlots());
            assertEquals(1, p1.getHeldGenSlots());

            var diag = proc.getDiagnostics();
            assertEquals(7, diag.getTotalRequestsRouted());
            assertEquals(1, diag.getTotalUpToDate(), "only the ladder answer counts as up-to-date");
            assertEquals(0, diag.getTotalSyncRateLimited());
            assertEquals(0, diag.getTotalGenRateLimited());
            assertEquals(0, diag.getTotalReResolved());

            // Full ledger at rest: every polled request ends in exactly one disposition —
            // batched answer, column payload, held in-flight slot, silent duplicate skip,
            // or a queue-full breaker (consumed unanswered; the client rescans it later).
            assertEquals(diag.getTotalRequestsRouted(),
                    delivered.size() + proc.payloads.size()
                            + p1.getHeldSyncSlots() + p1.getHeldGenSlots()
                            + diag.getTotalDuplicateSkips() + diag.getTotalQueueFull());
        } finally {
            proc.shutdown();
            reader.shutdown();
        }
    }

    @Test
    void epochSecondGranularityAndSkewedStampsFollowTheCacheVsClaimLadder(@TempDir Path tempDir) throws Exception {
        long future = LSSConstants.epochSeconds() + 7200;
        var seed = new ColumnTimestampCache(ColumnTimestampCache.mbToEntries(1));
        seed.put(DIM, packed(110, 0), 1000L, 0);
        seed.put(DIM, packed(111, 0), future, 0);
        seed.put(DIM, packed(112, 0), future, 0);
        seed.save(tempDir);
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 4, 4);
        var proc = new TestProcessor(players, null, false, tempDir);
        try {
            proc.start();
            // (110,0): claim equals the cached stamp. Epoch-SECOND granularity makes a
            // serve and a later save within the same second indistinguishable — equality
            // reads fresh BY DESIGN (the accepted one-second blind spot), even though the
            // cache stamp may postdate the client's copy by up to a second of content.
            p1.enqueue(new IncomingRequest(110, 0, 1000L));
            // (111,0): the server clock ran ahead when it stamped (cache in the client's
            // future); the honest lower claim must re-serve, never read up-to-date.
            p1.enqueue(new IncomingRequest(111, 0, 1000L));
            // (112,0): convergence under skew — the client stamps with the SERVER-sent
            // columnTimestamp verbatim (never its own clock), so its next claim equals
            // the future stamp and resolves up-to-date.
            p1.enqueue(new IncomingRequest(112, 0, future));
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> proc.submits.size() == 1, "skewed-behind claim re-serves");

            assertEquals(List.of(packed(111, 0)), submitPositions(proc));
            assertTrue(p1.hasPendingRequest(111, 0));
            var delivered = drainUntil(proc, d ->
                    count(d, LSSConstants.RESPONSE_UP_TO_DATE, packed(110, 0)) == 1
                            && count(d, LSSConstants.RESPONSE_UP_TO_DATE, packed(112, 0)) == 1);
            assertEquals(2, delivered.size(), delivered.toString());
            assertEquals(2, proc.getDiagnostics().getTotalUpToDate());
            assertTrue(p1.hasDiskReadDone(110, 0));
            assertTrue(p1.hasDiskReadDone(112, 0));
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void terminalUpToDateAfterDropStaysConvergentUnderTsZeroReAsks() throws Exception {
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 4, 4);
        var proc = new TestProcessor(players, null, false, null);
        proc.dropPayloads = true; // every serve attempt is an oversized-column drop
        try {
            proc.start();
            // Serve attempt: the probe column can't go on the wire -> terminal up-to-date
            p1.enqueue(new IncomingRequest(60, 0, -1));
            var probes = new Long2ObjectOpenHashMap<LoadedColumnData>();
            probes.put(packed(60, 0), new LoadedColumnData(60, 0, new byte[]{1}, 16));
            proc.postSnapshot(snapshot(Map.of(p1.getPlayerUUID(), probes), 0, p1), List.of());
            var delivered = drainUntil(proc, contains(LSSConstants.RESPONSE_UP_TO_DATE, packed(60, 0)));
            assertEquals(1, delivered.size(), delivered.toString());
            waitFor(() -> p1.hasDiskReadDone(60, 0), "terminal answer marks the position served");
            assertEquals(1, proc.payloads.size());

            // ts<=0 re-ask of the terminal answer: nothing is in the pipeline (the payload
            // was dropped), so honesty demands ONE bounded re-resolution — which drops and
            // goes terminal again instead of looping inside the router.
            p1.enqueue(new IncomingRequest(60, 0, -1));
            probes = new Long2ObjectOpenHashMap<>();
            probes.put(packed(60, 0), new LoadedColumnData(60, 0, new byte[]{1}, 16));
            proc.postSnapshot(snapshot(Map.of(p1.getPlayerUUID(), probes), 0, p1), List.of());
            delivered = drainUntil(proc, contains(LSSConstants.RESPONSE_UP_TO_DATE, packed(60, 0)));
            assertEquals(1, delivered.size(), "re-attempt answers terminally again: " + delivered);
            assertEquals(1, proc.getDiagnostics().getTotalReResolved());
            assertEquals(2, proc.payloads.size(), "each ts<=0 re-ask costs exactly one serve attempt");
            waitFor(() -> p1.hasDiskReadDone(60, 0), "the re-attempt goes terminal again");

            // The client stamps on up-to-date (its side of the contract, pinned in the
            // manager tests): any ts>0 follow-up converges from the done-bit with no
            // further serve attempt.
            p1.enqueue(new IncomingRequest(60, 0, 50L));
            proc.postSnapshot(snapshot(p1), List.of());
            delivered = drainUntil(proc, contains(LSSConstants.RESPONSE_UP_TO_DATE, packed(60, 0)));
            assertEquals(1, delivered.size(), delivered.toString());
            assertEquals(2, proc.payloads.size(), "converged: no third serve attempt");
            assertEquals(1, proc.getDiagnostics().getTotalReResolved());
            assertEquals(0, proc.submits.size());
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void emptyBatchAdmitsNothing() throws Exception {
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 2, 1); // empty incoming queue — a decoded {count=0} batch
        var marker = addPlayer(players, 2, 1);
        var proc = new TestProcessor(players, null, false, null);
        try {
            proc.start();
            marker.enqueue(new IncomingRequest(120, 0, -1));
            proc.postSnapshot(snapshot(p1, marker), List.of());
            waitFor(() -> submitPositions(proc).contains(packed(120, 0)), "marker routed");

            // Cycle barrier (existing pattern): once the second marker routes, cycle 1 —
            // including its visit to p1 — is provably complete.
            marker.enqueue(new IncomingRequest(121, 0, -1));
            proc.postSnapshot(snapshot(p1, marker), List.of());
            waitFor(() -> submitPositions(proc).contains(packed(121, 0)), "second marker routed");

            // Both cycles visited p1: zero requests must mean zero dispositions of any
            // kind (a count=0 batch frame is a no-op, not an error — idle clients send
            // them and must not be penalized).
            assertEquals(2, proc.getDiagnostics().getTotalRequestsRouted(),
                    "only the two markers were ever polled");
            assertEquals(0, p1.getHeldSyncSlots());
            assertEquals(0, p1.getHeldGenSlots());
            assertEquals(0, p1.getTotalRequestsReceived());
            proc.drainSendActions((state, types, positions, count) ->
                    fail("an empty batch must produce no batched answers"));
            assertEquals(2, proc.submits.size());
        } finally {
            proc.shutdown();
        }
    }
}
