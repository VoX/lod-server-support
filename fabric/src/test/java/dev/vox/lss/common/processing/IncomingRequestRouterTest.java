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
        /** Seeds one want-set batch carrying a single request (the pre-v17 per-request
         *  enqueue has no equivalent — each declaration REPLACES the backlog). Every call
         *  site here is separated from the next by a routing cycle, so nothing is superseded. */
        void enqueue(IncomingRequest r) { offerIncomingBatch(new IncomingBatch(new IncomingRequest[]{r})); }
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
            this(players, diskReader, generationAvailable, dataDir, 0);  // memo off (ttl=0): kills only the memo — the pacing rules are ttl-independent
        }

        TestProcessor(Map<UUID, TestState> players, AbstractChunkDiskReader diskReader,
                      boolean generationAvailable, Path dataDir, int missMemoTtlSeconds) {
            super(players, diskReader, generationAvailable, dataDir, 1, missMemoTtlSeconds);
        }

        @Override
        protected boolean submitDiskRead(UUID playerUuid, String dimension, int cx, int cz, long order) {
            this.submits.add(new CapturedSubmit(playerUuid, dimension, cx, cz));
            return !this.failSubmits;
        }

        @Override
        protected boolean buildAndEnqueueColumnPayload(TestState state, int cx, int cz, String dimension,
                                                     long columnTimestamp, long submissionOrder,
                                                     byte[] sectionBytes, int estimatedBytes, byte source) {
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
        var seed = new ColumnTimestampCache(ColumnTimestampCache.mbToEntries(1), 0);
        for (long pos : positions) seed.put(DIM, pos, timestamp, 0);
        seed.save(dataDir);
    }

    /** A successful disk read result, injected straight into the stub reader's queue. */
    private static ChunkReadResult dataResult(UUID u, int cx, int cz, byte[] bytes, long ts, long order) {
        return new ChunkReadResult(u, cx, cz, bytes, DIM,
                bytes.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES, ts, false, false, false, order);
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

    /** Declare a complete want-set batch (the v17 wire shape). Each call REPLACES the
     *  player's backlog — that is the protocol, not a test shortcut. */
    private static void offer(TestState state, IncomingRequest... reqs) {
        state.offerIncomingBatch(new IncomingBatch(reqs));
    }

    /** The un-taken mailbox contents (empty when the processing thread has taken the batch). */
    private static List<IncomingRequest> pendingBatch(TestState state) {
        var b = state.peekIncomingBatch();
        return b == null ? List.of() : List.of(b.requests());
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

    /** One non-blocking drain of the answers produced so far (for "nothing was sent" pins). */
    private static List<Delivered> drainSendActions(TestProcessor proc) {
        var delivered = new ArrayList<Delivered>();
        proc.drainSendActions((state, types, positions, count) -> {
            for (int i = 0; i < count; i++) delivered.add(new Delivered(state, types[i], positions[i]));
        });
        return delivered;
    }

    // ---- Tests ----

    @Test
    void mixedBatchEveryRequestGetsExactlyOneDisposition(@TempDir Path tempDir) throws Exception {
        seedTimestamps(tempDir, 1000L, packed(4, 0));
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 2, 1);
        var proc = new TestProcessor(players, new StubDiskReader(), true, tempDir);
        try {
            proc.start();
            // One 9-entry want-set, backlog order; every disposition the router can produce
            // appears at least once (a generation ticket is no longer a ROUTER disposition —
            // it is the delivery phase's disk-miss escalation, pinned in
            // OffThreadProcessorDiskResultTest). The slot-full entries are RETAINED, not
            // bounced — and the entries BEHIND them still route (SLOT_FULL
            // retains-and-continues). The barrier is the last entry, which must resolve
            // terminally.
            offer(p1,
                    new IncomingRequest(1, 0, -1),   // disk submit (sync slot 1/2)
                    new IncomingRequest(1, 0, -1),   // duplicate skip (pending), silent
                    new IncomingRequest(2, 0, 0),    // retired-0 shape: disk submit (sync slot 2/2)
                    new IncomingRequest(3, 0, 0),    // sync slot full -> RETAINED, pass continues
                    new IncomingRequest(5, 0, -1),   // in-memory probe payload, no slot
                    new IncomingRequest(6, 0, -1),   // sync slot full -> RETAINED, pass continues
                    new IncomingRequest(7, 0, -1),   // sync slot full -> RETAINED, pass continues
                    new IncomingRequest(4, 0, 2000), // up-to-date (cached 1000 <= 2000)
                    new IncomingRequest(4, 0, 3000));// ts>0 duplicate answered — batch marker
            var probes = new Long2ObjectOpenHashMap<LoadedColumnData>();
            probes.put(packed(5, 0), new LoadedColumnData(5, 0, new byte[]{1, 2, 3}, 48));
            proc.postSnapshot(snapshot(Map.of(p1.getPlayerUUID(), probes), 0, p1), List.of());

            var delivered = drainUntil(proc,
                    d -> count(d, LSSConstants.RESPONSE_UP_TO_DATE, packed(4, 0)) == 2);
            // restoreBacklog runs after the pass: wait on it before reading the ledger.
            waitFor(() -> p1.getBacklogSize() == 3, "all three slot-full entries retained in order");

            // Answered now: only the two (4,0) up-to-dates. A full slot is NOT an answer.
            assertEquals(2, delivered.size(), "exactly two batched answers: " + delivered);
            assertEquals(2, count(delivered, LSSConstants.RESPONSE_UP_TO_DATE, packed(4, 0)));

            // Served from memory: only the probe position, with the probe's bytes and a real timestamp
            assertEquals(1, proc.payloads.size());
            var payload = proc.payloads.peek();
            assertEquals(packed(5, 0), packed(payload.cx(), payload.cz()));
            assertEquals(DIM, payload.dimension());
            assertArrayEquals(new byte[]{1, 2, 3}, payload.sectionBytes());
            assertTrue(payload.columnTimestamp() > 0,
                    "probe-served columns must carry the cycle timestamp for up-to-date checks");

            // In-flight: the slot IS the pending entry, one per submit; entries behind the
            // full slot (the probe hit and the two ladder answers) still routed.
            assertEquals(List.of(packed(1, 0), packed(2, 0)), submitPositions(proc));
            assertNull(proc.pollGenerationTicketRequest(),
                    "no router path emits a generation ticket — that is the disk-miss escalation");
            assertEquals(2, p1.getHeldSyncSlots());
            assertEquals(0, p1.getHeldGenSlots(), "no request shape takes a gen slot at admission");
            for (int cx : new int[]{1, 2}) assertTrue(p1.hasPendingRequest(cx, 0), "in-flight " + cx);
            for (int cx : new int[]{3, 4, 5, 6, 7}) assertFalse(p1.hasPendingRequest(cx, 0), "not in-flight " + cx);

            var diag = proc.getDiagnostics();
            assertEquals(6, diag.getTotalRequestsRouted(),
                    "routed counts DISPOSITIONS only — the three retained entries are still owed");
            assertEquals(1, diag.getTotalDuplicateSkips());
            assertEquals(1, diag.getTotalUpToDate());
            assertEquals(1, diag.getTotalInMemory());
            assertEquals(0, diag.getTotalQueueFull());
            assertEquals(0, diag.getTotalSuperseded(), "no replace happened, so nothing is superseded");
            assertEquals(0, diag.getTotalRangeFiltered());

            // Conservation over the WHOLE declaration: every received entry is answered,
            // served a payload, admitted (in-flight), duplicate-skipped, or still queued.
            assertEquals(p1.getTotalRequestsReceived(),
                    delivered.size() + proc.payloads.size()
                            + p1.getHeldSyncSlots() + p1.getHeldGenSlots()
                            + diag.getTotalDuplicateSkips() + p1.getBacklogSize()
                            + diag.getTotalSuperseded() + diag.getTotalRangeFiltered());

            // Order preservation: restoreBacklog prepends the retained entries in their
            // declared order. Safe to drain from the test thread — the processing thread is
            // parked waiting for a snapshot and no further one is posted.
            assertEquals(new IncomingRequest(3, 0, 0), p1.pollBacklog(), "first retained entry");
            assertEquals(new IncomingRequest(6, 0, -1), p1.pollBacklog(), "second retained entry");
            assertEquals(new IncomingRequest(7, 0, -1), p1.pollBacklog(), "third retained entry");
            assertNull(p1.pollBacklog(), "nothing else was retained");
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
        var proc = new TestProcessor(players, new StubDiskReader(), false, null);
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
            assertEquals(List.of(new IncomingRequest(1, 1, -1)), pendingBatch(noCaps),
                    "skip must leave the declared batch un-taken (the capability check runs "
                            + "in routeAll, before takeIncomingBatch)");
            assertEquals(0, noCaps.getBacklogSize(), "and it never reaches the backlog");
            assertEquals(2, proc.getDiagnostics().getTotalRequestsRouted(),
                    "skipped requests are never polled, so never counted as routed");

            // The skip preserves the backlog: once the capability arrives, it routes normally
            noCaps.setCapabilities(LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            proc.postSnapshot(snapshot(noCaps, marker), List.of());
            waitFor(() -> submitPositions(proc).contains(packed(1, 1)), "batch routed after capability set");
            assertEquals(List.of(), pendingBatch(noCaps), "the batch is taken once it may route");
            assertEquals(1, noCaps.getHeldSyncSlots());
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void staleSnapshotDimensionDoesNotRouteAReRegisteredSession() throws Exception {
        // A dimension change replaces the state after a snapshot was built (a multi-tick
        // processing cycle): routing the NEW session's requests under the OLD snapshot
        // dimension submits disk reads whose results get dimension-skipped — the pending
        // slots leak for the whole session. The registered-dimension guard defers routing
        // to the next cycle's fresh snapshot instead.
        var players = new ConcurrentHashMap<UUID, TestState>();
        var state = addPlayer(players, 4, 4);
        state.setRegisteredDimension("minecraft:the_end"); // the session's real dimension
        var marker = addPlayer(players, 4, 4); // barrier: proves the stale cycle actually ran
        var proc = new TestProcessor(players, new StubDiskReader(), false, null);
        try {
            proc.start();
            state.enqueue(new IncomingRequest(3, 3, -1));
            marker.enqueue(new IncomingRequest(40, 40, -1));
            proc.postSnapshot(snapshot(state, marker), List.of()); // stale snapshot claims DIM (overworld)
            waitFor(() -> submitPositions(proc).contains(packed(40, 40)), "stale cycle ran");
            assertEquals(List.of(new IncomingRequest(3, 3, -1)), pendingBatch(state),
                    "the guard defers by leaving the batch un-taken — a taken-then-dropped "
                            + "batch would lose the session's whole want-set");
            assertEquals(0, state.getBacklogSize());

            var endDims = new HashMap<UUID, String>();
            endDims.put(state.getPlayerUUID(), "minecraft:the_end");
            proc.postSnapshot(new TickSnapshot(endDims, Map.of(), 0, false), List.of());

            waitFor(() -> submitPositions(proc).contains(packed(3, 3)),
                    "routed under the session's dimension");
            var submit = proc.submits.stream().filter(c -> c.cx() == 3 && c.cz() == 3).findFirst()
                    .orElseThrow();
            assertEquals("minecraft:the_end", submit.dimension(),
                    "the stale overworld snapshot must never route the End session's requests");
            assertEquals(1, proc.submits.stream().filter(c -> c.cx() == 3).count(),
                    "the request routes exactly once");
            assertEquals(1, state.getHeldSyncSlots());
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
            offer(p1,
                    new IncomingRequest(10, 0, -1),         // unknown: cached ts must be ignored
                    new IncomingRequest(11, 0, cached),     // boundary: cachedTs == clientTs is fresh
                    new IncomingRequest(12, 0, cached - 1), // stale client copy: re-serve
                    new IncomingRequest(13, 0, 500),        // never served (no cache entry): re-serve
                    new IncomingRequest(14, 0, 0));         // generation request, reader present
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
    void retiredZeroTimestampRoutesExactlyLikeMinusOne(@TempDir Path tempDir) throws Exception {
        // ts=0 is the RETIRED client generation classification: it now means "no data",
        // identical to -1 — disk-first, SYNC slot, no generation ticket (the server decides
        // generation on the disk MISS, never on the request shape; the wire pin's twin is
        // the retired byte 0 staying inert client-side). It must also bypass the timestamp
        // ladder: a cached stamp must not answer up_to_date to a no-data declaration.
        seedTimestamps(tempDir, 1000L, packed(20, 0));
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 2, 1);
        var proc = new TestProcessor(players, new StubDiskReader(), true, tempDir);
        try {
            proc.start();
            // ONE want-set carries both: two back-to-back declarations would supersede each
            // other (latest-wins mailbox), which is the protocol, not a seeding accident.
            offer(p1,
                    new IncomingRequest(20, 0, 0),   // retired-0 shape: "I have nothing"
                    new IncomingRequest(21, 0, -1)); // -1 shape: "I have nothing"
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> submitPositions(proc).size() == 2, "both no-data shapes submitted");

            assertEquals(List.of(packed(20, 0), packed(21, 0)), submitPositions(proc),
                    "ts=0 takes the identical disk-first route as ts=-1");
            assertNull(proc.pollGenerationTicketRequest(),
                    "no request shape can force a generation ticket anymore");
            assertEquals(2, p1.getHeldSyncSlots());
            assertEquals(0, p1.getHeldGenSlots());
            assertTrue(p1.hasPendingRequest(20, 0));
            assertTrue(p1.hasPendingRequest(21, 0));
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void submitFailureUnwindsSlotAndDedupGroupAndDropsSilently() throws Exception {
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 2, 1);
        var proc = new TestProcessor(players, new StubDiskReader(), false, null);
        try {
            proc.start();
            proc.failSubmits = true;
            p1.enqueue(new IncomingRequest(9, 9, -1));
            proc.postSnapshot(snapshot(p1), List.of());

            // A submit no-op (level not registered yet) is TRANSIENT: under permanent
            // NOT_GENERATED semantics it must never answer the wire — silent drop, counted
            // superseded, healed by the next want-set declaration.
            waitFor(() -> proc.getDiagnostics().getTotalSuperseded() == 1,
                    "the no-op submit is booked as superseded");
            assertTrue(drainSendActions(proc).isEmpty(),
                    "a failed submit answers NOTHING: no not-generated, no bounce");
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
        var proc = new TestProcessor(players, new StubDiskReader(), true, tempDir);
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
            // and the client kept its data (its re-declare carries ts>0).
            p1.markDiskReadDone(31, 0);
            p1.addReadyPayload(new QueuedPayload<>(new Object(), 10, 1, packed(31, 0)));
            p1.markDiskReadDone(32, 0);
            p1.addReadyPayload(new QueuedPayload<>(new Object(), 10, 2, packed(32, 0)));
            p1.markDiskReadDone(33, 0);

            // Cycle 2: the four re-declaration flavors. (30,0)'s payload was "lost" after the
            // send (nothing enqueued): the old router answered up-to-date and sealed a
            // permanent invisible hole — it must re-resolve instead. THE PERMANENT-INVISIBLE-
            // HOLE INVARIANT IS THE POINT OF THIS TEST and is unchanged by v17; only the
            // response to an in-pipeline ts<=0 re-ask moves (bounce -> silent skip).
            offer(p1,
                    new IncomingRequest(30, 0, -1), // lost after send: re-resolve to disk
                    new IncomingRequest(31, 0, -1), // still in pipeline: silent skip (sync)
                    new IncomingRequest(32, 0, 0),  // still in pipeline: silent skip (gen)
                    new IncomingRequest(33, 0, 50));// client has data: up-to-date is honest
            proc.postSnapshot(snapshot(p1), List.of());

            var delivered = drainUntil(proc, contains(LSSConstants.RESPONSE_UP_TO_DATE, packed(33, 0)));
            assertEquals(1, count(delivered, LSSConstants.RESPONSE_UP_TO_DATE, packed(33, 0)));
            assertEquals(1, delivered.size(),
                    "the two in-pipeline re-asks are answered SILENTLY (the column is already "
                            + "coming; the client re-declares until it lands) and the lost "
                            + "position is not answered at all this cycle: " + delivered);

            // The lost position re-entered resolution: disk submit with a pending slot
            assertEquals(List.of(packed(30, 0)), submitPositions(proc));
            assertTrue(p1.hasPendingRequest(30, 0));
            // Silently-skipped and ts>0 positions keep the done-bit. Keeping it while the
            // column is enqueued is what stops a redundant re-read of data about to arrive.
            assertTrue(p1.hasDiskReadDone(31, 0));
            assertTrue(p1.hasDiskReadDone(32, 0));
            assertTrue(p1.hasDiskReadDone(33, 0));

            var diag = proc.getDiagnostics();
            assertEquals(1, diag.getTotalReResolved());
            assertEquals(2, diag.getTotalDuplicateSkips(),
                    "both in-pipeline re-asks are counted as duplicate skips — the silent "
                            + "disposition that replaces the v16 rate-limited bounce");
            assertEquals(0, diag.getTotalUpToDate(),
                    "diskReadDone answers are not timestamp-ladder up-to-dates");
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void sendQueueFullRetainsTheBacklogInOrderUntilAReplaceSupersedesIt() throws Exception {
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 4, 1);
        var p2 = addPlayer(players, 4, 1);
        // One stuck payload + maxSendQueueSize=1 makes p1's send queue read as full
        p1.addReadyPayload(new QueuedPayload<>(new Object(), 100, 0, packed(5, 5)));
        p1.flushSendQueue(0, new SharedBandwidthLimiter(1_000_000), new TickDiagnostics(),
                payload -> fail("zero allocation must not send"));
        assertEquals(1, p1.getSendQueueSize());
        p1.markDiskReadDone(5, 5); // makes the first queued request a known duplicate

        var proc = new TestProcessor(players, new StubDiskReader(), false, null);
        try {
            proc.start();
            offer(p1,
                    new IncomingRequest(5, 5, 50), // ts>0 duplicate: ANSWERED despite the full queue
                    new IncomingRequest(6, 6, -1), // hits the full queue: retained, breaks the pass
                    new IncomingRequest(7, 7, -1));// behind the break: never even polled
            proc.postSnapshot(snapshot(Map.of(), 1, p1), List.of());
            waitFor(() -> proc.getDiagnostics().getTotalQueueFull() == 1, "queue-full break");

            // Cycle barrier: a p2-only snapshot proves cycle 1 finished without touching p1
            // again — the break-to-continue regression would drain p1's whole backlog.
            p2.enqueue(new IncomingRequest(50, 50, -1));
            proc.postSnapshot(snapshot(Map.of(), 1, p2), List.of());
            waitFor(() -> submitPositions(proc).contains(packed(50, 50)), "barrier player routed");

            assertEquals(2, p1.getBacklogSize(),
                    "the breaker is RETAINED (not bounced, not dropped) ahead of the entry "
                            + "behind it — a full send queue is backpressure, not an answer");
            var delivered = drainUntil(proc, contains(LSSConstants.RESPONSE_UP_TO_DATE, packed(5, 5)));
            assertEquals(1, delivered.size(),
                    "the done-bit answer resolves BEFORE the queue-full gate; the breaker "
                            + "gets nothing: " + delivered);
            assertSame(p1, delivered.get(0).state());
            assertTrue(p1.hasDiskReadDone(5, 5));
            assertEquals(List.of(packed(50, 50)), submitPositions(proc),
                    "no submit for the breaker or the backlog");
            assertEquals(0, p1.getHeldSyncSlots());
            assertFalse(p1.hasPendingRequest(6, 6));
            assertEquals(1, proc.getDiagnostics().getTotalQueueFull(),
                    "queue_full stays a pure event counter — it is no longer a law A1 term");
            assertEquals(2, proc.getDiagnostics().getTotalRequestsRouted(),
                    "only the duplicate and the barrier reached a disposition");

            // The retained entries survive INTO the next cycle... (posting both snapshots
            // back-to-back would drop p1's: the snapshot mailbox is latest-wins, so each
            // cycle must be awaited before the next is posted.)
            proc.postSnapshot(snapshot(Map.of(), 1, p1), List.of());
            waitFor(() -> proc.getDiagnostics().getTotalQueueFull() >= 2, "second queue-full break");
            p2.enqueue(new IncomingRequest(51, 51, -1));
            proc.postSnapshot(snapshot(Map.of(), 1, p2), List.of());
            waitFor(() -> submitPositions(proc).contains(packed(51, 51)), "second barrier player routed");
            assertEquals(2, p1.getBacklogSize(), "still owed, still in order");
            assertEquals(0, proc.getDiagnostics().getTotalSuperseded());

            // ...until the client's next declaration replaces them. Retention is bounded by
            // re-declaration, never by a timeout: a want the client dropped dies here.
            offer(p1, new IncomingRequest(8, 8, -1));
            proc.postSnapshot(snapshot(Map.of(), 0, p1), List.of()); // cap 0: gate disabled
            waitFor(() -> submitPositions(proc).contains(packed(8, 8)), "the replacement routes");
            assertEquals(2, proc.getDiagnostics().getTotalSuperseded(),
                    "both retained entries were superseded by the newer want-set");
            assertEquals(0, p1.getBacklogSize());
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void freshTimestampBeatsLoadedProbeInTheLadder(@TempDir Path tempDir) throws Exception {
        seedTimestamps(tempDir, 1000L, packed(90, 0));
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 4, 4);
        var proc = new TestProcessor(players, new StubDiskReader(), true, tempDir);
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

        var proc = new TestProcessor(players, new StubDiskReader(), false, tempDir);
        try {
            proc.start();
            offer(p1,
                    new IncomingRequest(94, 0, 500),  // done + ts>0: answered before the gate
                    new IncomingRequest(95, 0, 2000));// fresh cache hit: the gate retains it first
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
            // The timestamp ladder sits AFTER the gate: the fresh cache hit was RETAINED
            // by the break without resolving (no done-bit, no ladder up-to-date) — it stays
            // queued for a later cycle instead of being answered.
            assertFalse(p1.hasDiskReadDone(95, 0),
                    "a request retained by the queue-full break must not be marked served");
            assertEquals(1, p1.getBacklogSize(),
                    "the breaker is retained, not dropped — no re-declare is needed to recover it");
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

        var proc = new TestProcessor(players, new StubDiskReader(), false, null);
        try {
            proc.start();
            // maxSendQueueSize=0 is the "no limit" sentinel, not a literal capacity: a
            // regression reading it literally (queue 1 >= cap 0) would consume every
            // request unanswered and permanently starve clients of admins who disabled
            // the cap — this waitFor would time out.
            offer(p1, new IncomingRequest(97, 0, -1), new IncomingRequest(98, 0, -1));
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
        var proc = new TestProcessor(players, new StubDiskReader(), true, tempDir);
        try {
            proc.start();
            offer(p1,
                    new IncomingRequest(100, 0, Long.MIN_VALUE),
                    new IncomingRequest(101, 0, -5),
                    new IncomingRequest(102, 0, Long.MAX_VALUE));
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
        var proc = new TestProcessor(players, new StubDiskReader(), true, null);
        try {
            proc.start();
            for (int cycle = 1; cycle <= 20; cycle++) {
                var state = new TestState(uuid, 2, 1);
                state.markHandshakeComplete();
                state.setCapabilities(LSSConstants.CAPABILITY_VOXEL_COLUMNS);
                players.put(uuid, state);
                assertEquals(0, state.getHeldSyncSlots(), "slot counts die with the removed state");
                assertEquals(0, state.getHeldGenSlots());

                // Fill the sync slots to cap at the SAME positions every cycle; the disk
                // reads never resolve, so each removal happens with work still in flight.
                // (The third ask is SLOT_FULL-retained — its backlog entry dies with the
                // state too; gen-escalation removal hygiene is pinned by the
                // OffThreadProcessorDiskResultTest sweep tests.)
                offer(state,
                        new IncomingRequest(1, 1, -1),
                        new IncomingRequest(1, 2, -1),
                        new IncomingRequest(1, 3, 0));
                proc.postSnapshot(snapshot(state), List.of());
                int expectedSubmits = cycle * 2;
                waitFor(() -> state.getHeldSyncSlots() == 2
                                && proc.submits.size() == expectedSubmits,
                        "cycle " + cycle + " re-admits to full capacity with fresh submits");
                assertEquals(0, state.getHeldGenSlots(),
                        "no request shape takes a gen slot at admission anymore");
                assertNull(proc.pollGenerationTicketRequest(), "no ticket without a disk miss");

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
            offer(p1,
                    new IncomingRequest(1, 0, -1), // SYNC -> disk, will miss and escalate
                    new IncomingRequest(1, 0, -1), // duplicate of the in-flight read
                    new IncomingRequest(2, 0, 0)); // retired-0 ts -> disk-first, same as any miss
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> proc.submits.size() == 2
                    && proc.getDiagnostics().getTotalDuplicateSkips() == 1, "cycle 1 routed");

            // Cycle 2: both reads come back not-found. Server-owned generation: the FIRST
            // miss in delivery order (1,0) takes the single gen slot; the second (2,0) finds
            // the cap full — a TRANSIENT silent drop counted superseded, never a wire answer.
            reader.getPlayerQueue(p1.getPlayerUUID())
                    .add(ChunkReadResult.notFoundAuthoritative(p1.getPlayerUUID(), 1, 0, DIM, 1L));
            reader.getPlayerQueue(p1.getPlayerUUID())
                    .add(ChunkReadResult.notFoundAuthoritative(p1.getPlayerUUID(), 2, 0, DIM, 2L));
            offer(p1,
                    new IncomingRequest(3, 0, 2000), // timestamp-ladder up-to-date (cached 1000)
                    new IncomingRequest(3, 0, 3000), // done-bit duplicate up-to-date
                    new IncomingRequest(4, 0, -1));  // in-memory probe payload
            var probes = new Long2ObjectOpenHashMap<LoadedColumnData>();
            probes.put(packed(4, 0), new LoadedColumnData(4, 0, new byte[]{1}, 16));
            proc.postSnapshot(snapshot(Map.of(p1.getPlayerUUID(), probes), 0, p1), List.of());
            waitFor(() -> p1.getHeldGenSlots() == 1 && proc.payloads.size() == 1
                            && proc.getDiagnostics().getTotalSuperseded() == 1,
                    "cycle 2 drained and routed (one escalation + one cap-full drop)");

            // Cycle 3: a queue-full breaker is retained unanswered
            p1.addReadyPayload(new QueuedPayload<>(new Object(), 100, 0, packed(99, 99)));
            p1.flushSendQueue(0, new SharedBandwidthLimiter(1_000_000), new TickDiagnostics(),
                    payload -> fail("zero allocation must not send"));
            p1.enqueue(new IncomingRequest(5, 0, -1));
            proc.postSnapshot(snapshot(Map.of(), 1, p1), List.of());
            waitFor(() -> proc.getDiagnostics().getTotalQueueFull() == 1, "queue-full break");

            var delivered = drainUntil(proc, d ->
                    count(d, LSSConstants.RESPONSE_UP_TO_DATE, packed(3, 0)) == 2);
            assertEquals(2, delivered.size(),
                    "answers at rest (no NOT_GENERATED: the cap-full miss dropped silently): "
                            + delivered);

            var ticket = proc.pollGenerationTicketRequest();
            assertNotNull(ticket, "the first not-found in delivery order escalates to a gen ticket");
            assertEquals(packed(1, 0), packed(ticket.cx(), ticket.cz()));
            assertNull(proc.pollGenerationTicketRequest());
            assertTrue(p1.hasPendingRequest(1, 0), "the escalated ticket is the only in-flight work");
            assertEquals(0, p1.getHeldSyncSlots());
            assertEquals(1, p1.getHeldGenSlots());

            waitFor(() -> p1.getBacklogSize() == 1, "the queue-full breaker is retained");

            var diag = proc.getDiagnostics();
            assertEquals(6, diag.getTotalRequestsRouted(),
                    "the retained breaker has no disposition yet, so it is not routed");
            assertEquals(1, diag.getTotalUpToDate(), "only the ladder answer counts as up-to-date");
            assertEquals(0, diag.getTotalReResolved());

            // Full ledger at rest, closed over RECEIVED (not routed): every received entry is
            // answered, served a payload, admitted (in-flight), duplicate-skipped, superseded,
            // range-filtered, or still queued in the backlog. No silent leak class exists —
            // and unlike v16 there is no "consumed unanswered" class at all: the queue-full
            // breaker is retained, so queue_full leaves the ledger entirely.
            long answered = delivered.size() + proc.payloads.size();
            assertEquals(p1.getTotalRequestsReceived(),
                    answered + diag.getTotalDuplicateSkips() + diag.getTotalSuperseded()
                            + diag.getTotalRangeFiltered()
                            + p1.getHeldSyncSlots() + p1.getHeldGenSlots()
                            + p1.getBacklogSize(),
                    "every received entry is answered, skipped, superseded, range-filtered, "
                            + "admitted, or still queued — no silent leak class exists");
        } finally {
            proc.shutdown();
            reader.shutdown();
        }
    }

    @Test
    void epochSecondGranularityAndSkewedStampsFollowTheCacheVsClaimLadder(@TempDir Path tempDir) throws Exception {
        long future = LSSConstants.epochSeconds() + 7200;
        var seed = new ColumnTimestampCache(ColumnTimestampCache.mbToEntries(1), 0);
        seed.put(DIM, packed(110, 0), 1000L, 0);
        seed.put(DIM, packed(111, 0), future, 0);
        seed.put(DIM, packed(112, 0), future, 0);
        seed.save(tempDir);
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 4, 4);
        var proc = new TestProcessor(players, new StubDiskReader(), false, tempDir);
        try {
            proc.start();
            // (110,0): claim equals the cached stamp. Epoch-SECOND granularity makes a
            // serve and a later save within the same second indistinguishable — equality
            // reads fresh BY DESIGN (the accepted one-second blind spot), even though the
            // cache stamp may postdate the client's copy by up to a second of content.
            var extremes = new ArrayList<IncomingRequest>();
            extremes.add(new IncomingRequest(110, 0, 1000L));
            // (111,0): the server clock ran ahead when it stamped (cache in the client's
            // future); the honest lower claim must re-serve, never read up-to-date.
            extremes.add(new IncomingRequest(111, 0, 1000L));
            // (112,0): convergence under skew — the client stamps with the SERVER-sent
            // columnTimestamp verbatim (never its own clock), so its next claim equals
            // the future stamp and resolves up-to-date.
            extremes.add(new IncomingRequest(112, 0, future));
            offer(p1, extremes.toArray(new IncomingRequest[0]));
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
        var proc = new TestProcessor(players, new StubDiskReader(), false, null);
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
    void emptyBatchClearsTheBacklog() throws Exception {
        // The empty batch is the client's ONE explicit "want nothing" — sent edge-triggered
        // when it enters its decode-queue backpressure halt. It must replace the backlog with
        // nothing, so the server stops working on wants the client has abandoned.
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 1, 1); // sync cap 1: the second entry is retained
        var marker = addPlayer(players, 2, 1);
        var proc = new TestProcessor(players, new StubDiskReader(), false, null);
        try {
            proc.start();
            offer(p1,
                    new IncomingRequest(120, 0, -1), // admitted, fills the only sync slot
                    new IncomingRequest(121, 0, -1), // SLOT_FULL -> retained
                    new IncomingRequest(122, 0, -1));// SLOT_FULL -> retained
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> submitPositions(proc).contains(packed(120, 0)), "p1's first entry admitted");
            // Cycle barrier: getBacklogSize() is a LIVE gauge (pollBacklog updates it mid-pass),
            // so it reads 2 transiently before the first entry is even admitted. Only a LATER
            // cycle proves the pass ran to restoreBacklog. A marker-ONLY snapshot is the barrier:
            // the processing thread runs one cycle at a time, so the marker's submit means p1's
            // cycle is complete (a shared snapshot would not — routeAll's player order is a
            // HashMap iteration, not a sequence). The wait above is required before posting it:
            // the snapshot mailbox is latest-wins, so an un-awaited p1 snapshot would be dropped.
            marker.enqueue(new IncomingRequest(199, 0, -1));
            proc.postSnapshot(snapshot(marker), List.of());
            waitFor(() -> submitPositions(proc).contains(packed(199, 0)), "p1's retaining pass finished");
            assertEquals(2, p1.getBacklogSize(), "two entries retained behind the full slot");
            assertEquals(1, p1.getHeldSyncSlots());
            assertEquals(0, proc.getDiagnostics().getTotalSuperseded());

            // The explicit clear
            offer(p1);
            marker.enqueue(new IncomingRequest(200, 0, -1));
            proc.postSnapshot(snapshot(p1, marker), List.of());
            waitFor(() -> submitPositions(proc).contains(packed(200, 0)), "the clearing cycle ran");

            assertEquals(0, p1.getBacklogSize(), "an empty batch clears the backlog");
            assertEquals(2, proc.getDiagnostics().getTotalSuperseded(),
                    "the cleared entries are counted superseded — the ledger stays closed "
                            + "even though nothing went on the wire");
            assertNull(p1.peekWantSet(),
                    "and nothing is left published for the main-thread probe to work on");

            // Admitted work is NEVER cancelled by a clear: the in-flight read still owns its
            // slot and will deliver. The owner's requirement, structurally.
            assertEquals(1, p1.getHeldSyncSlots(), "the clear must not cancel in-flight work");
            assertTrue(p1.hasPendingRequest(120, 0));
            assertEquals(List.of(packed(120, 0), packed(199, 0), packed(200, 0)), submitPositions(proc));
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void replaceSupersedesUnadmittedButNeverInFlight() throws Exception {
        // The owner's core requirement: "we never cancel in-progress requests". Admit one
        // request (slot held, dedup group live), retain one (slot cap 1), then replace with a
        // disjoint batch: the retained entry is superseded, the admitted one survives to
        // deliver, and its dedup/stale-guard bookkeeping is untouched.
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 1, 1);
        var reader = new StubDiskReader();
        reader.registerPlayer(p1.getPlayerUUID());
        var proc = new TestProcessor(players, reader, false, null);
        try {
            proc.start();
            offer(p1,
                    new IncomingRequest(70, 0, -1),  // admitted: slot + dedup group
                    new IncomingRequest(71, 0, -1)); // SLOT_FULL -> retained, never admitted
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> p1.getBacklogSize() == 1 && proc.submits.size() == 1, "one admitted, one retained");
            assertTrue(p1.hasPendingRequest(70, 0));

            // A disjoint re-declaration: the client no longer wants (71,0)
            offer(p1, new IncomingRequest(72, 0, -1));
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> proc.getDiagnostics().getTotalSuperseded() == 1, "the retained want is superseded");

            // The un-admitted drop needs zero teardown: no pending, no dedup group, no
            // stale-guard entry ever existed for it.
            assertFalse(p1.hasPendingRequest(71, 0));
            assertEquals(List.of(packed(70, 0)), submitPositions(proc),
                    "(71,0) never reached the reader, and (72,0) is behind the still-full slot");

            // The IN-FLIGHT read is untouched by the replace and still delivers.
            assertTrue(p1.hasPendingRequest(70, 0), "a replace must never cancel admitted work");
            assertEquals(1, p1.getHeldSyncSlots());
            reader.getPlayerQueue(p1.getPlayerUUID())
                    .add(dataResult(p1.getPlayerUUID(), 70, 0, new byte[]{1}, 5000L, 1L));
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> proc.payloads.size() == 1, "the admitted read delivers normally");
            assertTrue(p1.hasDiskReadDone(70, 0));

            // ...and once its slot frees, the surviving want routes.
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> submitPositions(proc).contains(packed(72, 0)),
                    "the still-declared want routes once the slot frees");
        } finally {
            proc.shutdown();
            reader.shutdown();
        }
    }

    // ---- Miss memo: ladder-ordering pins (docs/planning/miss-memo-design.md) ----

    @Test
    void loadedProbeBeatsAFreshMissMemo() throws Exception {
        // The probe rung runs BEFORE the memo rung by drain order, so a chunk loaded by
        // walk-in play serves from memory even while a stale memo entry exists — this is
        // what closes the default-config Paper walk-in case (no dirty mark fires there).
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 4, 0); // gen cap 0: the miss drops, leaving no pending
        var reader = new StubDiskReader();
        reader.registerPlayer(p1.getPlayerUUID());
        var proc = new TestProcessor(players, reader, true, null, 30);
        try {
            proc.start();
            p1.enqueue(new IncomingRequest(9, 0, -1));
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> proc.submits.size() == 1, "cold read");
            reader.getPlayerQueue(p1.getPlayerUUID())
                    .add(ChunkReadResult.notFoundAuthoritative(p1.getPlayerUUID(), 9, 0, DIM, 1L));
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> proc.getDiagnostics().getTotalMissDropped() == 1,
                    "miss drops on the zero gen cap (memo written)");

            // The chunk is now LOADED (walk-in): the probe must serve it, memo notwithstanding.
            var probes = new Long2ObjectOpenHashMap<LoadedColumnData>();
            probes.put(packed(9, 0), new LoadedColumnData(9, 0, new byte[]{4, 2}, 32));
            p1.enqueue(new IncomingRequest(9, 0, -1));
            proc.postSnapshot(snapshot(Map.of(p1.getPlayerUUID(), probes), 0, p1), List.of());
            waitFor(() -> proc.payloads.size() == 1, "probe serve");
            assertArrayEquals(new byte[]{4, 2}, proc.payloads.peek().sectionBytes());
            assertEquals(0, proc.getDiagnostics().getTotalMemoHits(),
                    "the probe rung resolved the entry before the memo rung could");
            assertEquals(1, proc.submits.size(), "and no re-read either");
        } finally {
            proc.shutdown();
            reader.shutdown();
        }
    }

    @Test
    void generationDisabledNeverConsultsTheMemo() throws Exception {
        // With generation unavailable a memo hit could only produce NOT_GENERATED — a
        // session-permanent wire answer off cached knowledge. The rung is gated on
        // generationAvailable, so a gen-disabled server always re-reads (and there is no
        // churn to save there anyway: the first miss's NOT_GENERATED parks the client).
        var players = new ConcurrentHashMap<UUID, TestState>();
        var p1 = addPlayer(players, 4, 4);
        var reader = new StubDiskReader();
        reader.registerPlayer(p1.getPlayerUUID());
        var proc = new TestProcessor(players, reader, false, null, 30);
        try {
            proc.start();
            p1.enqueue(new IncomingRequest(9, 0, -1));
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> proc.submits.size() == 1, "first read");
            reader.getPlayerQueue(p1.getPlayerUUID())
                    .add(ChunkReadResult.notFoundAuthoritative(p1.getPlayerUUID(), 9, 0, DIM, 1L));
            proc.postSnapshot(snapshot(p1), List.of());
            drainUntil(proc, contains(LSSConstants.RESPONSE_NOT_GENERATED, packed(9, 0)));

            p1.enqueue(new IncomingRequest(9, 0, -1)); // an untracked re-ask (rig client never parks)
            proc.postSnapshot(snapshot(p1), List.of());
            waitFor(() -> proc.submits.size() == 2,
                    "gen-disabled re-ask READS — the memo rung never fires without generation");
            assertEquals(0, proc.getDiagnostics().getTotalMemoHits());
        } finally {
            proc.shutdown();
            reader.shutdown();
        }
    }
}
