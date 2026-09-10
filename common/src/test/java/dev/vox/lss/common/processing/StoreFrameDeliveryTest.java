package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.store.LodStoreDiagnostics;
import dev.vox.lss.common.store.LodStoreMode;
import dev.vox.lss.common.store.LodStoreService;
import dev.vox.lss.common.store.StoreCodec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Delivery-side frame plumbing (compressed-columns plan §3): a store-FRAME result flows
 * to the build as a frame-backed holder that never materializes raw for capable
 * recipients (verbatim end-to-end), and the deposit choke reuses a materialized wire
 * frame via {@code depositFrame} (compress-once-use-twice) with the caller-computed
 * usize/chash/fhash — falling back to the raw deposit for raw-only sessions.
 */
class StoreFrameDeliveryTest {

    private static final String DIM = "minecraft:overworld";
    private static final long TS = 1_750_000_000L; // post-TS_EPOCH_SECONDS: pre-epoch stamps clamp in the timestamp cache (tile redesign §2.2)
    private static StoreCodec codec;

    @BeforeAll
    static void probe() {
        codec = StoreCodec.zstdOrNull();
        assumeTrue(codec != null, "zstd native unavailable");
    }

    private static final class RecordingStore implements LodStoreService {
        record Dep(long packed, byte[] bytes, long ts) {}
        record FrameDep(long packed, byte[] frame, int usize, long chash, long fhash) {}
        final LodStoreDiagnostics diag = new LodStoreDiagnostics();
        final ConcurrentLinkedQueue<Dep> rawDeposits = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<FrameDep> frameDeposits = new ConcurrentLinkedQueue<>();

        @Override public LodStoreMode mode() { return LodStoreMode.FULL; }
        @Override public StoreHit get(String dimension, long packed) { return null; }
        @Override public boolean deposit(String d, long p, byte[] b, long ts, long acq) {
            this.rawDeposits.add(new Dep(p, b, ts));
            return true;
        }
        @Override public boolean depositFrame(String d, long p, byte[] frame, int usize,
                                              long chash, long fhash, long ts, long acq) {
            this.frameDeposits.add(new FrameDep(p, frame, usize, chash, fhash));
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
        @Override public String getPlayerName() { return "frame-test"; }
        void enqueue(IncomingRequest r) {
            offerIncomingBatch(new IncomingBatch(new IncomingRequest[]{r}));
        }
    }

    /** Build mimics the PLATFORM codec choice (frame() only for capable sessions) and
     *  records the holder itself so the tests can assert materialization. */
    private static final class TestProcessor extends OffThreadProcessor<TestState> {
        record Built(int cx, int cz, ColumnBytes bytes, byte source) {}
        final ConcurrentLinkedQueue<Built> built = new ConcurrentLinkedQueue<>();

        TestProcessor(Map<UUID, TestState> players, AbstractChunkDiskReader reader) {
            super(players, reader, false, null, 1, 0);
        }

        @Override
        protected boolean submitDiskRead(UUID playerUuid, RequestRegistration registration, String dimension, int cx, int cz, long order, long clientTimestamp) {
            return true;
        }

        @Override
        protected boolean buildAndEnqueueColumnPayload(TestState state, int cx, int cz,
                                                       String dimension, long columnTimestamp,
                                                       long submissionOrder, ColumnBytes bytes,
                                                       int estimatedBytes, byte source) {
            if (state.wantsCompressedColumns()) bytes.frame();
            else bytes.raw();
            this.built.add(new Built(cx, cz, bytes, source));
            return true;
        }
    }

    private static final class Rig {
        final UUID uuid = UUID.randomUUID();
        final ConcurrentHashMap<UUID, TestState> players = new ConcurrentHashMap<>();
        final AbstractChunkDiskReader reader = new AbstractChunkDiskReader(1) {};
        final RecordingStore store = new RecordingStore();
        final TestState state;
        final TestProcessor proc;

        Rig(boolean capableSession) {
            this.state = new TestState(this.uuid);
            this.state.markHandshakeComplete();
            this.state.setCapabilities(LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            this.state.setWantsCompressedColumns(capableSession);
            this.players.put(this.uuid, this.state);
            this.reader.registerPlayer(this.uuid, this.state.registration());
            this.proc = new TestProcessor(this.players, this.reader);
            this.proc.attachStore(this.store);
            this.proc.attachWireCodec(codec);
            this.proc.start();
        }

        void cycle() {
            var dims = new HashMap<UUID, String>();
            dims.put(this.uuid, DIM);
            this.proc.postSnapshot(new TickSnapshot(dims, Map.of(), 0, false), List.of());
        }

        void await(BooleanSupplier done, String what) {
            long deadline = System.currentTimeMillis() + 3000;
            while (!done.getAsBoolean()) {
                if (System.currentTimeMillis() > deadline) fail("timed out awaiting " + what);
                cycle();
                Thread.onSpinWait();
            }
        }

        void admit(int cx, int cz, long clientTs) {
            this.state.enqueue(new IncomingRequest(cx, cz, clientTs));
            int before = this.proc.routeCyclesForTest();
            cycle();
            await(() -> this.proc.routeCyclesForTest() > before, "routing cycle");
        }

        void shutdown() {
            this.proc.shutdown();
            this.reader.shutdown();
        }
    }

    private static byte[] compressibleRaw() {
        return new byte[4096];
    }

    @Test
    void storeFrameResultNeverMaterializesRawForACapableRecipient() {
        var rig = new Rig(true);
        try {
            rig.admit(3, 4, -1);
            byte[] raw = compressibleRaw();
            byte[] frame = codec.compress(raw);
            rig.reader.getPlayerQueue(rig.uuid).add(new ChunkReadResult(rig.uuid, 3, 4,
                    null, DIM, raw.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES, TS,
                    false, false, false, true, 1L, 0L, frame, raw.length));
            rig.await(() -> !rig.proc.built.isEmpty(), "delivery");
            var built = rig.proc.built.poll();
            assertEquals(LSSConstants.COLUMN_SOURCE_STORE, built.source());
            assertSame(frame, built.bytes().frame(), "verbatim: the stored frame ships as-is");
            assertFalse(built.bytes().rawMaterialized(),
                    "zero decompress anywhere for a capable recipient");
            assertTrue(rig.store.rawDeposits.isEmpty() && rig.store.frameDeposits.isEmpty(),
                    "store hits are never re-deposited");
        } finally {
            rig.shutdown();
        }
    }

    @Test
    void capableDeliveryReusesTheWireFrameForTheDeposit() {
        var rig = new Rig(true);
        try {
            rig.admit(5, 6, -1);
            byte[] raw = compressibleRaw();
            rig.reader.getPlayerQueue(rig.uuid).add(new ChunkReadResult(rig.uuid, 5, 6,
                    raw, DIM, raw.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES, TS,
                    false, false, false, 2L));
            rig.await(() -> !rig.store.frameDeposits.isEmpty(), "frame deposit");
            var dep = rig.store.frameDeposits.poll();
            assertEquals(PositionUtil.packPosition(5, 6), dep.packed());
            assertEquals(raw.length, dep.usize());
            assertEquals(LodStoreService.contentHash(raw), dep.chash());
            assertEquals(LodStoreService.contentHash(dep.frame()), dep.fhash());
            assertTrue(rig.store.rawDeposits.isEmpty(),
                    "compress once, use twice — never a second raw deposit");
        } finally {
            rig.shutdown();
        }
    }

    @Test
    void rawSessionDeliveryDepositsRawAsBefore() {
        var rig = new Rig(false);
        try {
            rig.admit(7, 8, -1);
            byte[] raw = compressibleRaw();
            rig.reader.getPlayerQueue(rig.uuid).add(new ChunkReadResult(rig.uuid, 7, 8,
                    raw, DIM, raw.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES, TS,
                    false, false, false, 3L));
            rig.await(() -> !rig.store.rawDeposits.isEmpty(), "raw deposit");
            var dep = rig.store.rawDeposits.poll();
            assertSame(raw, dep.bytes(), "no frame was built, the raw path is unchanged");
            assertTrue(rig.store.frameDeposits.isEmpty());
        } finally {
            rig.shutdown();
        }
    }
}
