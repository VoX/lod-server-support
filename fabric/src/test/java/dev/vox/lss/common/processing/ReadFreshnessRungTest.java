package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.region.RegionStampTable;
import dev.vox.lss.common.store.LodStoreDiagnostics;
import dev.vox.lss.common.store.LodStoreMode;
import dev.vox.lss.common.store.LodStoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the P1 read-path freshness rungs (region-summary-sync-plan.md §3).
 *
 * <p>Reader side (the header rung in {@code AbstractChunkDiskReader.readAndDeliver}):
 * a ts&gt;0 submission whose client stamp STRICTLY exceeds the region header's save
 * second skips the read entirely — no operation run, no {@code disk.submitted}, one
 * {@code disk.header_hits} — while every doubt shape (same-second stamp, ts&le;0,
 * absent chunk, missing file, live save mark at/above the stamp) falls through to the
 * real read. Never answer from doubt.
 *
 * <p>Delivery side ({@code OffThreadProcessor.deliverDiskResult}): a headerFresh
 * result answers {@code up_to_date} per recipient whose pending stamp beats the proven
 * second (ghosts, stale-against-edit, and older-stamped dedup members drop as the
 * standard transient), refreshes the tscache at stamp+1 (strict margin preserved
 * through the non-strict tscache compare), and never deposits; a store hit whose STORED
 * stamp is at/below the recipient's stamp converts to {@code up_to_date} instead of
 * re-sending bytes (the non-strict tscache-doctrine compare), and serves bytes in every
 * other shape.
 */
class ReadFreshnessRungTest {

    private static final String DIM = LSSConstants.DIM_STR_OVERWORLD;
    private static final long NOW = System.currentTimeMillis() / 1000L;
    private static final long HEADER_SECOND = NOW - 100;

    // ---- reader-side rig ----

    private static final class TestDiskReader extends AbstractChunkDiskReader {
        TestDiskReader() { super(1); }
        void submit(UUID player, int cx, int cz, long order, long clientTs, ReadOperation op) {
            submitRead(player, cx, cz, DIM, order, clientTs, op);
        }
        void submitLegacy(UUID player, int cx, int cz, long order, ReadOperation op) {
            submitRead(player, cx, cz, DIM, order, op);
        }
    }

    @TempDir
    Path regionDir;

    private final UUID player = UUID.randomUUID();
    private TestDiskReader reader;

    private TestDiskReader reader() {
        if (this.reader == null) {
            this.reader = new TestDiskReader();
            this.reader.registerPlayer(this.player);
            this.reader.attachRegionStamps(new RegionStampTable(
                    d -> DIM.equals(d) ? this.regionDir : null));
        }
        return this.reader;
    }

    @AfterEach
    void tearDown() {
        if (this.reader != null) this.reader.shutdown();
    }

    private void writeRegion(int cx, int cz, long saveSecond) throws Exception {
        Path mca = this.regionDir.resolve("r." + (cx >> 5) + "." + (cz >> 5) + ".mca");
        var buf = ByteBuffer.allocate(8192);
        int idx = (cx & 31) + ((cz & 31) << 5);
        buf.putInt(idx * 4, 0x0000_0201);
        buf.putInt(4096 + idx * 4, (int) saveSecond);
        Files.write(mca, buf.array());
    }

    private ChunkReadResult awaitOneResult() throws InterruptedException {
        var queue = reader().getPlayerQueue(this.player);
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (true) {
            var r = queue.poll();
            if (r != null) return r;
            if (System.nanoTime() > deadline) fail("timed out waiting for a reader result");
            Thread.sleep(5);
        }
    }

    private static final long MARGIN = AbstractChunkDiskReader.HEADER_FRESH_MARGIN_SECONDS;

    @Test
    void marginClearingClientStampSkipsTheRead() throws Exception {
        writeRegion(3, 4, HEADER_SECOND);
        var opRuns = new AtomicInteger();
        reader().submit(this.player, 3, 4, 1L, HEADER_SECOND + MARGIN + 1, () -> {
            opRuns.incrementAndGet();
            return new byte[]{1};
        });
        var result = awaitOneResult();
        assertTrue(result.headerFresh(), "must be the header rung's answer");
        assertEquals(HEADER_SECOND + MARGIN, result.columnTimestamp(),
                "carries the MARGINED bound — delivery compare and tscache refresh inherit it");
        assertNull(result.sectionBytes());
        assertEquals(0, opRuns.get(), "the read must be skipped");
        assertEquals(1, reader().getDiag().getHeaderHitsCount());
        assertEquals(0, reader().getDiag().getSubmittedCount(),
                "a header hit never enters the disk.submitted/completed partition");
        // The operator's live receipt renders on the disk diag line.
        assertTrue(reader().getDiagnostics().contains("header_hits=1"),
                "the header_hits= token must render: " + reader().getDiagnostics());
    }

    @Test
    void stampInsideTheMarginFallsThroughToTheRead() throws Exception {
        // The serve-latency margin (P1 review MAJOR): client stamps are issued at read
        // COMPLETION, so a stamp within one read duration of the save second may carry
        // PRE-save bytes — the whole margin, equality included, serves.
        writeRegion(3, 4, HEADER_SECOND);
        var opRuns = new AtomicInteger();
        reader().submit(this.player, 3, 4, 1L, HEADER_SECOND + MARGIN, () -> {
            opRuns.incrementAndGet();
            return new byte[]{1};
        });
        var result = awaitOneResult();
        assertFalse(result.headerFresh());
        assertEquals(1, opRuns.get());

        reader().submit(this.player, 3, 4, 2L, HEADER_SECOND + 1, () -> {
            opRuns.incrementAndGet();
            return new byte[]{1};
        });
        awaitOneResult();
        assertEquals(2, opRuns.get());
        assertEquals(0, reader().getDiag().getHeaderHitsCount());
    }

    @Test
    void rungIsInertWithoutATable() throws Exception {
        // Bare rigs never attach a table — a ts>0 submit must run the operation.
        var bare = new TestDiskReader();
        try {
            bare.registerPlayer(this.player);
            var opRuns = new AtomicInteger();
            bare.submit(this.player, 3, 4, 1L, HEADER_SECOND + MARGIN + 100, () -> {
                opRuns.incrementAndGet();
                return new byte[]{1};
            });
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (bare.getPlayerQueue(this.player).poll() == null) {
                if (System.nanoTime() > deadline) fail("timed out");
                Thread.sleep(5);
            }
            assertEquals(1, opRuns.get());
            assertEquals(0, bare.getDiag().getHeaderHitsCount());
        } finally {
            bare.shutdown();
        }
    }

    @Test
    void legacySubmitOverloadNeverConsultsTheHeader() throws Exception {
        // The 6-arg submitRead (no clientTimestamp) must pass ts=0, not smear the
        // submission order into the timestamp slot.
        writeRegion(3, 4, HEADER_SECOND);
        var opRuns = new AtomicInteger();
        reader().submitLegacy(this.player, 3, 4, Long.MAX_VALUE - 1, () -> {
            opRuns.incrementAndGet();
            return new byte[]{1};
        });
        awaitOneResult();
        assertEquals(1, opRuns.get(), "legacy overload is an acquisition-shaped submit");
        assertEquals(0, reader().getDiag().getHeaderHitsCount());
    }

    @Test
    void acquisitionAskNeverConsultsTheHeader() throws Exception {
        writeRegion(3, 4, HEADER_SECOND);
        var opRuns = new AtomicInteger();
        reader().submit(this.player, 3, 4, 1L, 0L, () -> {
            opRuns.incrementAndGet();
            return new byte[]{1};
        });
        awaitOneResult();
        assertEquals(1, opRuns.get(), "ts<=0 has nothing to validate — always read");
    }

    @Test
    void latchedRegionFallsThroughToTheRead() throws Exception {
        // A mark above the observed header (a change in flight to disk) voids every
        // claim for the region until a re-read proves the write landed — even a stamp
        // far beyond the margin must serve (the write-pending race, review MAJOR).
        writeRegion(3, 4, HEADER_SECOND);
        var table = new RegionStampTable(d -> DIM.equals(d) ? this.regionDir : null);
        reader();
        this.reader.attachRegionStamps(table);
        table.bumpLiveSaveMark(DIM, 3, 4, NOW - 50);
        var opRuns = new AtomicInteger();
        reader().submit(this.player, 3, 4, 1L, NOW + 3600, () -> {
            opRuns.incrementAndGet();
            return new byte[]{1};
        });
        awaitOneResult();
        assertEquals(1, opRuns.get());
        assertEquals(0, reader().getDiag().getHeaderHitsCount());
    }

    @Test
    void absentChunkAndMissingFileFallThrough() throws Exception {
        writeRegion(3, 4, HEADER_SECOND);
        var opRuns = new AtomicInteger();
        // Same region file, location 0 (absent): the miss ladder owns it.
        reader().submit(this.player, 5, 4, 1L, NOW, () -> {
            opRuns.incrementAndGet();
            return null;
        });
        awaitOneResult();
        // No region file at all.
        reader().submit(this.player, 200, 200, 2L, NOW, () -> {
            opRuns.incrementAndGet();
            return null;
        });
        awaitOneResult();
        assertEquals(2, opRuns.get());
        assertEquals(0, reader().getDiag().getHeaderHitsCount());
    }


    // ---- delivery-side rig (mirrors OffThreadProcessorDiskResultTest) ----

    private static final class RecordingStore implements LodStoreService {
        final LodStoreDiagnostics diag = new LodStoreDiagnostics();
        final ConcurrentLinkedQueue<Long> deposits = new ConcurrentLinkedQueue<>();
        @Override public LodStoreMode mode() { return LodStoreMode.FULL; }
        @Override public StoreHit get(String dimension, long packed) { return null; }
        @Override public boolean deposit(String d, long p, byte[] b, long ts, long acq) {
            this.deposits.add(p);
            return true;
        }
        @Override public void invalidate(String d, long[] p) {}
        @Override public void delete(String d, long p) {}
        @Override public LodStoreDiagnostics diagnostics() { return this.diag; }
        @Override public void shutdown() {}
    }

    private static final class TestState extends AbstractPlayerRequestState<Object> {
        TestState(UUID uuid) {
            super(uuid, 4, 4);
            setFrontierDampingForTest(0, System::nanoTime);
        }
        @Override public String getPlayerName() { return "freshness-test"; }
        void enqueue(IncomingRequest r) {
            offerIncomingBatch(new IncomingBatch(new IncomingRequest[]{r}));
        }
    }

    private static final class StubDiskReader extends AbstractChunkDiskReader {
        StubDiskReader() { super(1); }
    }

    private static final class TestProcessor extends OffThreadProcessor<TestState> {
        record EnqueuedColumn(UUID player, int cx, int cz, long ts, byte source, int rawSize) {}
        final ConcurrentLinkedQueue<EnqueuedColumn> enqueued = new ConcurrentLinkedQueue<>();
        // Clearing 0-section columns (the all-air ghost-terrain guard) land separately
        // so the pins can tell "bytes served" from "clear sent" apart.
        final ConcurrentLinkedQueue<EnqueuedColumn> emptiedColumns = new ConcurrentLinkedQueue<>();

        TestProcessor(Map<UUID, TestState> players, AbstractChunkDiskReader reader) {
            super(players, reader, false, null, 1, 0);
        }

        @Override
        protected boolean submitDiskRead(UUID playerUuid, RequestRegistration registration, String dimension, int cx, int cz,
                                         long order, long clientTimestamp) {
            return true; // tests inject results directly into the reader queue
        }

        @Override
        protected boolean buildAndEnqueueColumnPayload(TestState state, int cx, int cz,
                                                       String dimension, long columnTimestamp,
                                                       long submissionOrder, ColumnBytes bytes,
                                                       int estimatedBytes, byte source) {
            var column = new EnqueuedColumn(state.getPlayerUUID(), cx, cz,
                    columnTimestamp, source, bytes.raw().length);
            // The clearing column is the 2-byte v20 zero-section body.
            if (bytes.raw().length <= 2) {
                this.emptiedColumns.add(column);
            } else {
                this.enqueued.add(column);
            }
            return true;
        }
    }

    private static final class Rig {
        final UUID uuid = UUID.randomUUID();
        final ConcurrentHashMap<UUID, TestState> players = new ConcurrentHashMap<>();
        final StubDiskReader reader = new StubDiskReader();
        final RecordingStore store = new RecordingStore();
        final TestState state;
        final TestProcessor proc;

        Rig() {
            this.state = newPlayer(this.uuid);
            this.players.put(this.uuid, this.state);
            this.reader.registerPlayer(this.uuid, this.state.registration());
            this.proc = new TestProcessor(this.players, this.reader);
            this.proc.attachStore(this.store);
            this.proc.start();
        }

        TestState addPlayer(UUID u) {
            var s = newPlayer(u);
            this.players.put(u, s);
            this.reader.registerPlayer(u, s.registration());
            return s;
        }

        void inject(ChunkReadResult result) {
            this.reader.getPlayerQueue(result.playerUuid()).add(result);
        }
    }

    private static TestState newPlayer(UUID uuid) {
        var s = new TestState(uuid);
        s.markHandshakeComplete();
        s.setCapabilities(LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        return s;
    }

    private static TickSnapshot snapshot(UUID... uuids) {
        var dims = new HashMap<UUID, String>();
        for (var u : uuids) dims.put(u, DIM);
        return new TickSnapshot(dims, Map.of(), 0, false);
    }

    private record Response(UUID player, byte type, long packed) {}

    private static final long POLL_DEADLINE_NANOS = 30_000_000_000L;

    private static List<Response> drainUntil(TestProcessor proc, Predicate<List<Response>> done)
            throws InterruptedException {
        var collected = new ArrayList<Response>();
        long deadline = System.nanoTime() + POLL_DEADLINE_NANOS;
        while (!done.test(collected)) {
            if (System.nanoTime() > deadline) fail("timed out draining responses; got " + collected);
            proc.drainSendActions((state, types, positions, count) -> {
                for (int i = 0; i < count; i++) {
                    collected.add(new Response(state.getPlayerUUID(), types[i], positions[i]));
                }
            });
            Thread.sleep(10);
        }
        return collected;
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + POLL_DEADLINE_NANOS;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for: " + what);
            Thread.sleep(10);
        }
    }

    /** Declare (cx, cz) at the given stamp and wait for its pending entry (the routed
     *  admission whose PendingRequest carries the stamp the delivery rungs compare). */
    private static void declareAndAwaitPending(Rig rig, TestState state, int cx, int cz, long ts)
            throws InterruptedException {
        state.enqueue(new IncomingRequest(cx, cz, ts));
        rig.proc.postSnapshot(snapshot(rig.players.keySet().toArray(UUID[]::new)), List.of());
        waitFor(() -> state.hasPendingRequest(cx, cz), "pending admission for " + cx + "," + cz);
    }

    @Test
    void headerFreshDeliversUpToDateAndStampsStrictMargin() throws Exception {
        var rig = new Rig();
        try {
            long clientTs = HEADER_SECOND + 10;
            declareAndAwaitPending(rig, rig.state, 7, 7, clientTs);
            long packed = PositionUtil.packPosition(7, 7);
            rig.inject(ChunkReadResult.headerFresh(rig.uuid, 7, 7, DIM, 1L, HEADER_SECOND));
            rig.proc.postSnapshot(snapshot(rig.uuid), List.of());
            drainUntil(rig.proc, rs -> rs.stream().anyMatch(
                    r -> r.type() == LSSConstants.RESPONSE_UP_TO_DATE && r.packed() == packed));
            assertTrue(rig.state.hasDiskReadDone(7, 7), "resolution recorded");
            assertEquals(0, rig.proc.enqueued.size(), "no bytes were sent");
            assertTrue(rig.store.deposits.isEmpty(), "a header answer must never deposit");
            // stamp+1: the non-strict tscache compare (cached <= clientTs) then fires
            // exactly iff clientTs > HEADER_SECOND — the rung's own strict margin.
            assertEquals(HEADER_SECOND + 1,
                    rig.proc.timestampCacheForTest().get(DIM, packed));
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void headerFreshGhostDropsSilently() throws Exception {
        var rig = new Rig();
        try {
            long before = rig.proc.getDiagnostics().getTotalSuperseded();
            // No pending backs this delivery (raced/duplicate result).
            rig.inject(ChunkReadResult.headerFresh(rig.uuid, 9, 9, DIM, 1L, HEADER_SECOND));
            rig.proc.postSnapshot(snapshot(rig.uuid), List.of());
            waitFor(() -> rig.proc.getDiagnostics().getTotalSuperseded() > before,
                    "ghost header answer counted superseded");
            assertFalse(rig.state.hasDiskReadDone(9, 9));
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void headerFreshOvertakenByEditDropsSilently() throws Exception {
        var rig = new Rig();
        try {
            long clientTs = HEADER_SECOND + 10;
            declareAndAwaitPending(rig, rig.state, 8, 8, clientTs);
            long packed = PositionUtil.packPosition(8, 8);
            long before = rig.proc.getDiagnostics().getTotalSuperseded();
            // The invalidation applies at the top of the same cycle that drains the
            // result: the dedup group is live, so the in-flight answer is tainted —
            // its proof predates the edit and must not seal a stale up_to_date.
            rig.proc.invalidateTimestamps(DIM, new long[]{packed});
            rig.inject(ChunkReadResult.headerFresh(rig.uuid, 8, 8, DIM, 1L, HEADER_SECOND));
            rig.proc.postSnapshot(snapshot(rig.uuid), List.of());
            waitFor(() -> rig.proc.getDiagnostics().getTotalSuperseded() > before,
                    "tainted header answer counted superseded");
            assertFalse(rig.state.hasDiskReadDone(8, 8));
            assertEquals(0, rig.proc.timestampCacheForTest().get(DIM, packed),
                    "no stamp refresh from a tainted proof");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void headerFreshDedupSplitsPerRecipientStamp() throws Exception {
        var rig = new Rig();
        try {
            var other = rig.addPlayer(UUID.randomUUID());
            long packed = PositionUtil.packPosition(11, 11);
            // Primary's stamp beats the header second; the attached player's does not.
            declareAndAwaitPending(rig, rig.state, 11, 11, HEADER_SECOND + 10);
            declareAndAwaitPending(rig, other, 11, 11, HEADER_SECOND - 10);
            long before = rig.proc.getDiagnostics().getTotalSuperseded();
            rig.inject(ChunkReadResult.headerFresh(rig.uuid, 11, 11, DIM, 1L, HEADER_SECOND));
            rig.proc.postSnapshot(snapshot(rig.uuid, other.getPlayerUUID()), List.of());
            var responses = drainUntil(rig.proc, rs -> rs.stream().anyMatch(
                    r -> r.player().equals(rig.uuid)
                            && r.type() == LSSConstants.RESPONSE_UP_TO_DATE
                            && r.packed() == packed));
            assertTrue(responses.stream().noneMatch(r -> r.player().equals(other.getPlayerUUID())),
                    "the older-stamped member gets no answer — it re-declares");
            waitFor(() -> rig.proc.getDiagnostics().getTotalSuperseded() > before,
                    "the older-stamped member's drop counted superseded");
            assertTrue(rig.state.hasDiskReadDone(11, 11));
            assertFalse(other.hasDiskReadDone(11, 11),
                    "no unearned done-bit on the dropped member");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void storeHitAtOrBelowClientStampConvertsToUpToDate() throws Exception {
        var rig = new Rig();
        try {
            long clientTs = HEADER_SECOND; // EQUALITY converts (the tscache doctrine)
            declareAndAwaitPending(rig, rig.state, 13, 13, clientTs);
            long packed = PositionUtil.packPosition(13, 13);
            rig.inject(new ChunkReadResult(rig.uuid, 13, 13, new byte[]{1, 2, 3}, DIM,
                    64, HEADER_SECOND, false, false, false, true, 1L, 0L));
            rig.proc.postSnapshot(snapshot(rig.uuid), List.of());
            drainUntil(rig.proc, rs -> rs.stream().anyMatch(
                    r -> r.type() == LSSConstants.RESPONSE_UP_TO_DATE && r.packed() == packed));
            assertEquals(0, rig.proc.enqueued.size(),
                    "the stored bytes are redundant for this recipient");
            assertTrue(rig.state.hasDiskReadDone(13, 13));
            assertTrue(rig.store.deposits.isEmpty(), "store hits never re-deposit");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void storeHitNewerThanClientStampServesBytes() throws Exception {
        var rig = new Rig();
        try {
            declareAndAwaitPending(rig, rig.state, 14, 14, HEADER_SECOND - 1);
            rig.inject(new ChunkReadResult(rig.uuid, 14, 14, new byte[]{1, 2, 3}, DIM,
                    64, HEADER_SECOND, false, false, false, true, 1L, 0L));
            rig.proc.postSnapshot(snapshot(rig.uuid), List.of());
            waitFor(() -> rig.proc.enqueued.size() == 1, "stored bytes served");
            assertEquals(LSSConstants.COLUMN_SOURCE_STORE, rig.proc.enqueued.peek().source());
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void storeHitForAcquisitionAskServesBytes() throws Exception {
        var rig = new Rig();
        try {
            declareAndAwaitPending(rig, rig.state, 15, 15, -1L);
            rig.inject(new ChunkReadResult(rig.uuid, 15, 15, new byte[]{1, 2, 3}, DIM,
                    64, HEADER_SECOND, false, false, false, true, 1L, 0L));
            rig.proc.postSnapshot(snapshot(rig.uuid), List.of());
            waitFor(() -> rig.proc.enqueued.size() == 1,
                    "an acquisition ask always gets the bytes");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void headerFreshRefreshNeverLowersAnExistingStamp() throws Exception {
        // Monotonic max (P1 review MAJOR): put() overwrites unconditionally and a
        // LOWER cached value is strictly MORE permissive — a header-derived bound must
        // never replace a higher acquisition stamp the server actually observed (it
        // would persist to lss-timestamps.bin and bypass the rung's own 5 s
        // re-validation forever).
        var rig = new Rig();
        try {
            long clientTs = HEADER_SECOND + 100;
            declareAndAwaitPending(rig, rig.state, 21, 21, clientTs);
            long packed = PositionUtil.packPosition(21, 21);
            long seeded = HEADER_SECOND + 10_000;
            rig.proc.timestampCacheForTest().put(DIM, packed, seeded,
                    System.currentTimeMillis() / 1000L);
            rig.inject(ChunkReadResult.headerFresh(rig.uuid, 21, 21, DIM, 1L, HEADER_SECOND));
            rig.proc.postSnapshot(snapshot(rig.uuid), List.of());
            drainUntil(rig.proc, rs -> rs.stream().anyMatch(
                    r -> r.type() == LSSConstants.RESPONSE_UP_TO_DATE && r.packed() == packed));
            assertEquals(seeded, rig.proc.timestampCacheForTest().get(DIM, packed),
                    "the higher acquisition stamp must survive the header refresh");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void headerFreshAttachedMemberAtExactBoundDropsSilently() throws Exception {
        // Delivery-side margin at EQUALITY: an attached member whose stamp equals the
        // carried bound exactly is inside the margin — standard transient drop.
        var rig = new Rig();
        try {
            var other = rig.addPlayer(UUID.randomUUID());
            long packed = PositionUtil.packPosition(23, 23);
            declareAndAwaitPending(rig, rig.state, 23, 23, HEADER_SECOND + 10);
            declareAndAwaitPending(rig, other, 23, 23, HEADER_SECOND);
            long before = rig.proc.getDiagnostics().getTotalSuperseded();
            rig.inject(ChunkReadResult.headerFresh(rig.uuid, 23, 23, DIM, 1L, HEADER_SECOND));
            rig.proc.postSnapshot(snapshot(rig.uuid, other.getPlayerUUID()), List.of());
            drainUntil(rig.proc, rs -> rs.stream().anyMatch(
                    r -> r.player().equals(rig.uuid)
                            && r.type() == LSSConstants.RESPONSE_UP_TO_DATE
                            && r.packed() == packed));
            waitFor(() -> rig.proc.getDiagnostics().getTotalSuperseded() > before,
                    "the equal-stamped member's drop counted superseded");
            assertFalse(other.hasDiskReadDone(23, 23),
                    "equality must not earn a done-bit — the compare is strict");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void plainDiskReadNeverConvertsToUpToDate() throws Exception {
        // The fromStore guard (P1 review MAJOR): a plain NBT read's columnTimestamp is
        // acquisition-now, but a future-dated client stamp (clock rewind, hostile
        // ts=2^62) must NOT convert a real read into a permanent no-serve hole.
        var rig = new Rig();
        try {
            declareAndAwaitPending(rig, rig.state, 17, 17, HEADER_SECOND + 100_000);
            rig.inject(new ChunkReadResult(rig.uuid, 17, 17, new byte[]{1, 2, 3}, DIM,
                    64, HEADER_SECOND, false, false, false, false, 1L, 0L));
            rig.proc.postSnapshot(snapshot(rig.uuid), List.of());
            waitFor(() -> rig.proc.enqueued.size() == 1,
                    "a plain disk read always serves its bytes");
            assertEquals(LSSConstants.COLUMN_SOURCE_DISK, rig.proc.enqueued.peek().source());
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void allAirStoreHitStillClearsForADataClaimingClient() throws Exception {
        // The columnBytes != null guard (P1 review MAJOR): an all-air store hit for a
        // data-claiming client must send the authoritative clearing 0-section column,
        // never up_to_date — converting would seal ghost terrain over a now-empty
        // column permanently.
        var rig = new Rig();
        try {
            declareAndAwaitPending(rig, rig.state, 18, 18, HEADER_SECOND + 100);
            // All-air store hit: null section bytes, fromStore, stored stamp below the
            // client's — the conversion condition holds EXCEPT for the bytes guard.
            rig.inject(new ChunkReadResult(rig.uuid, 18, 18, null, DIM,
                    0, HEADER_SECOND, false, false, false, true, 1L, 0L));
            rig.proc.postSnapshot(snapshot(rig.uuid), List.of());
            waitFor(() -> rig.proc.emptiedColumns.size() == 1,
                    "the clearing 0-section column must be sent");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void ghostStoreHitDropsSilently() throws Exception {
        // No pending backs the delivery (raced/duplicate result): the conversion's
        // pending != null conjunct must hold or this NPEs out of deliverDiskResult.
        var rig = new Rig();
        try {
            long before = rig.proc.getDiagnostics().getTotalSuperseded();
            rig.inject(new ChunkReadResult(rig.uuid, 19, 19, new byte[]{1, 2, 3}, DIM,
                    64, HEADER_SECOND, false, false, false, true, 1L, 0L));
            rig.proc.postSnapshot(snapshot(rig.uuid), List.of());
            // A ghost data result is the documented silent shape: delivered with no
            // pending, it builds a payload but earns no done-bit... the pin here is
            // simply that nothing throws and the cycle survives — drive a second
            // request through to completion.
            declareAndAwaitPending(rig, rig.state, 20, 20, -1L);
            rig.inject(new ChunkReadResult(rig.uuid, 20, 20, new byte[]{1, 2, 3}, DIM,
                    64, HEADER_SECOND, false, false, false, true, 2L, 0L));
            rig.proc.postSnapshot(snapshot(rig.uuid), List.of());
            waitFor(() -> rig.proc.enqueued.stream().anyMatch(e -> e.cx() == 20),
                    "the pipeline survives a ghost store hit");
            assertTrue(rig.proc.getDiagnostics().getTotalSuperseded() >= before);
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void storeHitDedupSplitsPerRecipientStamp() throws Exception {
        // Two members of one dedup group: only the at-or-above-stamp member converts;
        // the older-stamped member still gets the bytes.
        var rig = new Rig();
        try {
            var other = rig.addPlayer(UUID.randomUUID());
            long packed = PositionUtil.packPosition(24, 24);
            declareAndAwaitPending(rig, rig.state, 24, 24, HEADER_SECOND);
            declareAndAwaitPending(rig, other, 24, 24, HEADER_SECOND - 10);
            rig.inject(new ChunkReadResult(rig.uuid, 24, 24, new byte[]{1, 2, 3}, DIM,
                    64, HEADER_SECOND, false, false, false, true, 1L, 0L));
            rig.proc.postSnapshot(snapshot(rig.uuid, other.getPlayerUUID()), List.of());
            drainUntil(rig.proc, rs -> rs.stream().anyMatch(
                    r -> r.player().equals(rig.uuid)
                            && r.type() == LSSConstants.RESPONSE_UP_TO_DATE
                            && r.packed() == packed));
            waitFor(() -> rig.proc.enqueued.stream().anyMatch(
                            e -> e.cx() == 24 && e.player().equals(other.getPlayerUUID())),
                    "the older-stamped member must receive the stored bytes");
            assertTrue(rig.proc.enqueued.stream().noneMatch(
                            e -> e.cx() == 24 && e.player().equals(rig.uuid)),
                    "the converted member must not also receive bytes");
        } finally {
            rig.proc.shutdown();
        }
    }

    @Test
    void storeHitOvertakenByEditServesBytesWithoutConversion() throws Exception {
        var rig = new Rig();
        try {
            declareAndAwaitPending(rig, rig.state, 16, 16, HEADER_SECOND + 10);
            long packed = PositionUtil.packPosition(16, 16);
            rig.proc.invalidateTimestamps(DIM, new long[]{packed});
            rig.inject(new ChunkReadResult(rig.uuid, 16, 16, new byte[]{1, 2, 3}, DIM,
                    64, HEADER_SECOND, false, false, false, true, 1L, 0L));
            rig.proc.postSnapshot(snapshot(rig.uuid), List.of());
            waitFor(() -> rig.proc.enqueued.size() == 1,
                    "a tainted stored stamp cannot claim currency — bytes serve");
            assertFalse(rig.state.hasDiskReadDone(16, 16),
                    "stale-against-edit delivery leaves no done-bit");
        } finally {
            rig.proc.shutdown();
        }
    }
}
