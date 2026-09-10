package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.store.LodStoreDiagnostics;
import dev.vox.lss.common.store.LodStoreMode;
import dev.vox.lss.common.store.LodStoreService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The delivery-path half of the store contract (plan §1), pinned at the processor:
 * deposits happen ONCE per drained result at the choke point AFTER the stale guard
 * (never for edit-overtaken results — the poison the review moved deposits off the
 * reader to prevent), never for store hits (no re-deposit), for generation outcomes
 * under the same genStale condition; the authoritative-not-found ghost guard DELETES the
 * store row while an error-triaged not-found keeps it; dirty invalidations fan out to
 * the store; and a store-hit delivery is attributed {@code COLUMN_SOURCE_STORE}.
 */
class OffThreadProcessorStoreTest {

    private static final String DIM = LSSConstants.DIM_STR_OVERWORLD;
    private static final long TS = 1_750_000_000L; // post-TS_EPOCH_SECONDS: pre-epoch stamps clamp in the timestamp cache (tile redesign §2.2)

    private static final class RecordingStore implements LodStoreService {
        record Dep(String dim, long packed, byte[] bytes, long ts, long acq) {}
        final LodStoreDiagnostics diag = new LodStoreDiagnostics();
        final ConcurrentLinkedQueue<Dep> deposits = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Long> deletes = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<long[]> invalidations = new ConcurrentLinkedQueue<>();

        @Override public LodStoreMode mode() { return LodStoreMode.FULL; }
        @Override public StoreHit get(String dimension, long packed) { return null; }
        @Override public boolean deposit(String d, long p, byte[] b, long ts, long acq) {
            this.deposits.add(new Dep(d, p, b, ts, acq));
            return true;
        }
        @Override public void invalidate(String d, long[] p) { this.invalidations.add(p); }
        @Override public void delete(String d, long p) { this.deletes.add(p); }
        @Override public LodStoreDiagnostics diagnostics() { return this.diag; }
        @Override public void shutdown() {}
    }

    private static final class TestState extends AbstractPlayerRequestState<Object> {
        TestState(UUID uuid) {
            super(uuid, 4, 4);
            setFrontierDampingForTest(0, System::nanoTime);
        }
        @Override public String getPlayerName() { return "store-test"; }
        void enqueue(IncomingRequest r) {
            offerIncomingBatch(new IncomingBatch(new IncomingRequest[]{r}));
        }
    }

    private static final class StubDiskReader extends AbstractChunkDiskReader {
        StubDiskReader() { super(1); }
    }

    private static final class TestProcessor extends OffThreadProcessor<TestState> {
        record EnqueuedColumn(int cx, int cz, long ts, byte[] bytes, byte source) {}
        final ConcurrentLinkedQueue<EnqueuedColumn> enqueued = new ConcurrentLinkedQueue<>();

        TestProcessor(Map<UUID, TestState> players, AbstractChunkDiskReader reader,
                      boolean generationAvailable) {
            super(players, reader, generationAvailable, null, 1, 0);
        }

        @Override
        protected boolean submitDiskRead(UUID playerUuid, RequestRegistration registration, String dimension, int cx, int cz, long order, long clientTimestamp) {
            return true; // the tests inject results directly into the reader queue
        }

        @Override
        protected boolean buildAndEnqueueColumnPayload(TestState state, int cx, int cz,
                                                       String dimension, long columnTimestamp,
                                                       long submissionOrder, ColumnBytes bytes,
                                                       int estimatedBytes, byte source) {
            this.enqueued.add(new EnqueuedColumn(cx, cz, columnTimestamp, bytes.raw(), source));
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

        Rig(boolean generationAvailable) {
            this.state = new TestState(this.uuid);
            this.state.markHandshakeComplete();
            this.state.setCapabilities(LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            this.players.put(this.uuid, this.state);
            this.reader.registerPlayer(this.uuid, this.state.registration());
            this.proc = new TestProcessor(this.players, this.reader, generationAvailable);
            this.proc.attachStore(this.store);
            this.proc.start();
        }

        void cycle() {
            var dims = new HashMap<UUID, String>();
            dims.put(this.uuid, DIM);
            this.proc.postSnapshot(new TickSnapshot(dims, Map.of(), 0, false), List.of());
        }

        void cycleWithGen(TickSnapshot.GenerationReadyData outcome) {
            var dims = new HashMap<UUID, String>();
            dims.put(this.uuid, DIM);
            this.proc.postSnapshot(new TickSnapshot(dims, Map.of(), 0, false), List.of(outcome));
        }

        void await(BooleanSupplier done, String what) {
            long deadline = System.currentTimeMillis() + 3000;
            while (!done.getAsBoolean()) {
                if (System.currentTimeMillis() > deadline) fail("timed out awaiting " + what);
                cycle(); // keep cycles flowing (snapshot is latest-wins)
                Thread.onSpinWait();
            }
        }

        void shutdown() {
            this.proc.shutdown();
            this.reader.shutdown();
        }

        /** Route one request to a live pending entry + dedup group (so a later
         *  invalidation can taint the in-flight read, and deliveries find a pending). */
        void admit(int cx, int cz, long clientTs) {
            this.state.enqueue(new IncomingRequest(cx, cz, clientTs));
            int before = this.proc.routeCyclesForTest();
            cycle();
            await(() -> this.proc.routeCyclesForTest() > before, "routing cycle");
        }
    }

    private static ChunkReadResult data(UUID u, int cx, int cz, byte[] bytes, long order) {
        return new ChunkReadResult(u, cx, cz, bytes, DIM,
                bytes.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES, TS,
                false, false, false, order);
    }

    @Test
    void dataResultDepositsOnceWithBytesAndStoredTs() {
        var rig = new Rig(false);
        rig.admit(3, 4, -1);
        byte[] bytes = {1, 2, 3, 4};
        rig.reader.getPlayerQueue(rig.uuid).add(data(rig.uuid, 3, 4, bytes, 1));
        rig.await(() -> !rig.store.deposits.isEmpty(), "deposit");
        var dep = rig.store.deposits.poll();
        assertEquals(PositionUtil.packPosition(3, 4), dep.packed());
        assertEquals(TS, dep.ts(), "the deposit carries the result's columnTimestamp");
        assertEquals(4, dep.bytes().length);
        assertNull(rig.store.deposits.poll(), "exactly one deposit per drained result");
        rig.shutdown();
    }

    /** C2 (review-fixes round): the processor passes the result's ACQUISITION stamp
     *  through to the deposit verbatim — never re-stamping at deposit time (R1-M2: a
     *  save landing in the acquisition→deposit gap must stay sweep-visible). The
     *  plumbing was previously unpinned: the test double DISCARDED the 5th arg. */
    @Test
    void depositCarriesTheResultsAcquisitionStampVerbatim() {
        var rig = new Rig(false);
        rig.admit(9, 9, -1);
        long acquired = 1_234_567L; // a distinctive stamp no clock could produce now
        rig.reader.getPlayerQueue(rig.uuid).add(new ChunkReadResult(rig.uuid, 9, 9,
                new byte[]{1, 2}, DIM, 2 + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                TS, false, false, false, false, 7, acquired));
        rig.await(() -> !rig.store.deposits.isEmpty(), "deposit");
        var dep = rig.store.deposits.poll();
        assertEquals(acquired, dep.acq(),
                "the deposit's srcStampSeconds must be the result's acquisition stamp,"
                        + " untouched — re-stamping re-opens the sweep-invisible gap");
        rig.shutdown();
    }

    @Test
    void allAirResultDepositsTheAllAirRow() {
        var rig = new Rig(false);
        rig.admit(5, 5, -1);
        rig.reader.getPlayerQueue(rig.uuid).add(new ChunkReadResult(rig.uuid, 5, 5, null, DIM,
                0, TS, false, false, false, 2));
        rig.await(() -> !rig.store.deposits.isEmpty(), "all-air deposit");
        var dep = rig.store.deposits.poll();
        assertNull(dep.bytes(), "all-air passes null; the store boundary normalizes to byte[0]");
        assertEquals(TS, dep.ts());
        rig.shutdown();
    }

    @Test
    void editOvertakenResultSkipsTheDeposit() {
        var rig = new Rig(false);
        rig.admit(7, 8, -1); // live pending + dedup group
        long packed = PositionUtil.packPosition(7, 8);
        // The edit lands while the read is in flight: the invalidation taints the dedup
        // group, so the later delivery is stale-against-edit.
        rig.proc.invalidateTimestamps(DIM, new long[]{packed});
        rig.await(() -> !rig.store.invalidations.isEmpty(), "invalidation fan-out");
        rig.reader.getPlayerQueue(rig.uuid).add(data(rig.uuid, 7, 8, new byte[]{9, 9}, 3));
        // The column is still delivered (better than nothing)...
        rig.await(() -> !rig.proc.enqueued.isEmpty(), "stale delivery");
        // ...but never deposited: pre-edit bytes in the store would seal stale terrain.
        assertTrue(rig.store.deposits.isEmpty(),
                "an edit-overtaken result must NOT deposit (the §1 poison)");
        rig.shutdown();
    }

    @Test
    void storeHitResultIsAttributedAndNeverRedeposited() {
        var rig = new Rig(false);
        rig.admit(1, 2, -1);
        byte[] bytes = {5, 5, 5};
        rig.reader.getPlayerQueue(rig.uuid).add(new ChunkReadResult(rig.uuid, 1, 2, bytes, DIM,
                bytes.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES, TS,
                false, false, false, /* fromStore */ true, 4, 0L));
        rig.await(() -> !rig.proc.enqueued.isEmpty(), "store-hit delivery");
        var col = rig.proc.enqueued.poll();
        assertEquals(LSSConstants.COLUMN_SOURCE_STORE, col.source());
        assertEquals(TS, col.ts());
        assertTrue(rig.store.deposits.isEmpty(), "a store hit is never re-deposited");
        rig.shutdown();
    }

    @Test
    void authoritativeNotFoundDeletesTheStoreRowButErrorTriageDoesNot() {
        var rig = new Rig(false);
        rig.admit(10, 10, -1);
        rig.reader.getPlayerQueue(rig.uuid).add(
                ChunkReadResult.notFoundAuthoritative(rig.uuid, 10, 10, DIM, 5));
        rig.await(() -> !rig.store.deletes.isEmpty(), "ghost-guard delete");
        assertEquals(PositionUtil.packPosition(10, 10), rig.store.deletes.poll());

        rig.admit(11, 11, -1);
        rig.reader.getPlayerQueue(rig.uuid).add(
                ChunkReadResult.notFoundFromError(rig.uuid, 11, 11, DIM, 6));
        // Await the delivery (its pending entry resolves); the error-triaged miss must
        // never delete (a transient read timeout would otherwise drop a good store row).
        rig.await(() -> !rig.state.hasPendingRequest(11, 11), "error-triage drain");
        assertTrue(rig.store.deletes.isEmpty(),
                "error-triaged not-found says nothing about existence — row kept");
        rig.shutdown();
    }

    @Test
    void generationOutcomeDepositsAfterTheStaleGuard() {
        var rig = new Rig(true);
        long packed = PositionUtil.packPosition(20, 20);
        byte[] genBytes = {7, 7, 7, 7};
        rig.state.tryAdmit(new PendingRequest(20, 20, SlotType.GENERATION, 0L));
        rig.proc.addGenerationInFlight(rig.state.registration(), DIM, packed);
        rig.cycleWithGen(new TickSnapshot.GenerationReadyData(rig.uuid, rig.state.registration(), 20, 20, DIM,
                new LoadedColumnData(20, 20, genBytes, genBytes.length), TS, 7, false, false));
        rig.await(() -> !rig.store.deposits.isEmpty(), "generation deposit");
        var dep = rig.store.deposits.poll();
        assertEquals(packed, dep.packed());
        assertEquals(TS, dep.ts());
        rig.shutdown();
    }

    @Test
    void staleGenerationOutcomeSkipsTheDeposit() {
        var rig = new Rig(true);
        long packed = PositionUtil.packPosition(21, 21);
        rig.state.tryAdmit(new PendingRequest(21, 21, SlotType.GENERATION, 0L));
        rig.proc.addGenerationInFlight(rig.state.registration(), DIM, packed);
        // The edit overtakes the buffered outcome...
        rig.proc.invalidateTimestamps(DIM, new long[]{packed});
        rig.await(() -> !rig.store.invalidations.isEmpty(), "invalidation fan-out");
        rig.cycleWithGen(new TickSnapshot.GenerationReadyData(rig.uuid, rig.state.registration(), 21, 21, DIM,
                new LoadedColumnData(21, 21, new byte[]{8, 8}, 2), TS, 8, false, false));
        rig.await(() -> !rig.proc.enqueued.isEmpty(), "stale gen delivery");
        assertTrue(rig.store.deposits.isEmpty(),
                "a genStale outcome carries pre-edit bytes — never deposited");
        rig.shutdown();
    }

    @Test
    void invalidationsFanOutToTheStoreBeforeAnythingElse() {
        var rig = new Rig(false);
        long[] positions = {PositionUtil.packPosition(1, 1), PositionUtil.packPosition(2, 2)};
        rig.proc.invalidateTimestamps(DIM, positions);
        rig.await(() -> !rig.store.invalidations.isEmpty(), "store invalidation");
        assertEquals(2, rig.store.invalidations.poll().length);
        rig.shutdown();
    }

    @Test
    void shutdownFlushFansQueuedInvalidationsIntoTheStore() {
        // Invalidations still queued when shutdown lands ride the sentinel take (or the
        // exit flush) into the store — moot for the memory tier, load-bearing the day a
        // disk tier persists rows across the restart (review MINOR-5). No cycle runs
        // between the post and the shutdown, so whichever exit path wins must fan out.
        var rig = new Rig(false);
        rig.proc.invalidateTimestamps(DIM, new long[]{PositionUtil.packPosition(9, 9)});
        rig.proc.shutdown();
        assertTrue(!rig.store.invalidations.isEmpty(),
                "queued invalidations must reach the store on the shutdown path");
        rig.reader.shutdown();
    }
}
