package dev.vox.lss.common.processing;

import dev.vox.lss.common.store.LodStoreDiagnostics;
import dev.vox.lss.common.store.LodStoreMode;
import dev.vox.lss.common.store.LodStoreService;
import dev.vox.lss.common.store.StoreCodec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The frame-form store rung (compressed-columns plan §3): with {@code serveStoreFrames}
 * the reader consults {@code getFrame} EXCLUSIVELY — a frame hit delivers the frame
 * verbatim in the result (zero decompress on the reader pool), all-air keeps the raw
 * rung's shape, a getFrame miss falls to region IO (never a second get() of the same
 * row), and the flag OFF keeps the raw rung bit-identical. Rung contract (hits excluded
 * from disk.* and the throttle EWMA) unchanged in both forms.
 */
class StoreFrameServingRungTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final String DIM = "minecraft:overworld";
    private static StoreCodec codec;

    @BeforeAll
    static void probe() {
        codec = StoreCodec.zstdOrNull();
        assumeTrue(codec != null, "zstd native unavailable");
    }

    private static class StubStore implements LodStoreService {
        final LodStoreDiagnostics diag = new LodStoreDiagnostics();
        FrameHit frameAnswer;
        StoreHit rawAnswer;
        int getFrameCalls;
        int getCalls;

        @Override public LodStoreMode mode() { return LodStoreMode.FULL; }
        @Override public StoreHit get(String dimension, long packed) {
            this.getCalls++;
            return this.rawAnswer;
        }
        @Override public FrameHit getFrame(String dimension, long packed) {
            this.getFrameCalls++;
            return this.frameAnswer;
        }
        @Override public boolean deposit(String d, long p, byte[] b, long ts, long acq) { return true; }
        @Override public void invalidate(String d, long[] p) {}
        @Override public void delete(String d, long p) {}
        @Override public LodStoreDiagnostics diagnostics() { return this.diag; }
        @Override public void shutdown() {}
    }

    private static AbstractChunkDiskReader reader(boolean frames) {
        var r = new AbstractChunkDiskReader(1) {};
        r.registerPlayer(PLAYER);
        r.setServeStoreFrames(frames);
        return r;
    }

    private static ChunkReadResult awaitResult(AbstractChunkDiskReader r) {
        ConcurrentLinkedQueue<ChunkReadResult> q = r.getPlayerQueue(PLAYER);
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            var result = q.poll();
            if (result != null) return result;
            Thread.onSpinWait();
        }
        throw new AssertionError("no result within 2s");
    }

    @Test
    void frameHitDeliversTheFrameVerbatimWithZeroDiskCounters() {
        var r = reader(true);
        var store = new StubStore();
        byte[] raw = new byte[4096];
        byte[] frame = codec.compress(raw);
        store.frameAnswer = new LodStoreService.FrameHit(frame, raw.length, 777L);
        r.attachStore(store);

        r.submitRead(PLAYER, 3, -4, DIM, 1L, () -> {
            throw new AssertionError("the NBT operation must not run on a frame hit");
        });
        var result = awaitResult(r);

        assertTrue(result.fromStore());
        assertNull(result.sectionBytes(), "frame hits carry NO raw bytes");
        assertSame(frame, result.frameBytes(), "the stored frame, verbatim — no copy");
        assertEquals(raw.length, result.frameRawSize());
        assertEquals(777L, result.columnTimestamp());
        assertEquals(raw.length + dev.vox.lss.common.LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                result.estimatedBytes(), "estimate stays RAW-denominated");
        assertEquals(0, store.getCalls, "exactly one read form per submit — never get() too");
        assertEquals(1, store.diag.getHits());
        assertEquals(0, r.getDiag().getSubmittedCount(), "rung contract unchanged");
        r.shutdown();
    }

    // ---- C4: pre-migration wirefmt=19 rows translate at the rung ----

    @Test
    void nineteenRowFrameHitDeliversTheTranslatedRawBody() {
        // raw()==v20 is a C2 pipeline invariant: a 19-row's native body must translate
        // HERE and deliver as RAW bytes (recompressed per recipient downstream), never
        // ship the stored frame verbatim.
        var r = reader(true);
        var store = new StubStore();
        byte[] nativeRaw = new byte[2048];
        for (int i = 0; i < nativeRaw.length; i++) nativeRaw[i] = (byte) i;
        store.frameAnswer = new LodStoreService.FrameHit(
                codec.compress(nativeRaw), nativeRaw.length, 777L, 19);
        byte[] marker = new byte[]{0x42};
        r.setStoreLegacyTranslator(raw -> {
            byte[] out = new byte[raw.length + 1];
            out[0] = marker[0];
            System.arraycopy(raw, 0, out, 1, raw.length);
            return out;
        });
        r.attachStore(store);

        r.submitRead(PLAYER, 3, -4, DIM, 1L, () -> {
            throw new AssertionError("the NBT operation must not run on a 19-row hit");
        });
        var result = awaitResult(r);

        assertTrue(result.fromStore());
        assertNull(result.frameBytes(), "a translated 19-row delivers RAW, never the stored frame");
        assertEquals(nativeRaw.length + 1, result.sectionBytes().length);
        assertEquals(0x42, result.sectionBytes()[0], "the translated body, not the native one");
        assertEquals(777L, result.columnTimestamp());
        assertEquals(result.sectionBytes().length
                        + dev.vox.lss.common.LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                result.estimatedBytes(),
                "the charge re-derives from the TRANSLATED body (law A2's denomination)");
        r.shutdown();
    }

    @Test
    void nineteenRowWithoutAWiredTranslatorReadsAsAnErroredMiss() {
        // Never leak native bytes into the v20 pipeline: unwired translator (test rigs,
        // a broken platform wire-up) = contained errored miss, the NBT ladder serves.
        var r = reader(true);
        var store = new StubStore();
        byte[] nativeRaw = new byte[512];
        store.frameAnswer = new LodStoreService.FrameHit(
                codec.compress(nativeRaw), nativeRaw.length, 5L, 19);
        r.attachStore(store);

        r.submitRead(PLAYER, 1, 1, DIM, 1L, () -> new byte[]{9});
        var result = awaitResult(r);
        assertFalse(result.fromStore(), "the 19-row must not serve untranslated");
        assertArrayEquals(new byte[]{9}, result.sectionBytes(), "the NBT ladder serves truth");
        assertTrue(store.diag.getErrors() > 0, "counted store.errors");
        assertEquals(0, store.diag.getHits(),
                "a failed translation is an errored miss, never ALSO a hit (review m17)");
        r.shutdown();
    }

    @Test
    void nineteenRowRawHitDeliversTheTranslatedBody() {
        // The raw rung (serveStoreFrames=false) has the same translation contract as
        // the frame rung — it shipped review-untested (C4 review #6).
        var r = reader(false);
        var store = new StubStore();
        byte[] nativeRaw = new byte[1024];
        for (int i = 0; i < nativeRaw.length; i++) nativeRaw[i] = (byte) (i * 7);
        store.rawAnswer = new LodStoreService.StoreHit(nativeRaw, 321L, 19);
        r.setStoreLegacyTranslator(raw -> {
            byte[] out = new byte[raw.length + 2];
            out[0] = 0x42;
            System.arraycopy(raw, 0, out, 2, raw.length);
            return out;
        });
        r.attachStore(store);

        r.submitRead(PLAYER, 5, 6, DIM, 1L, () -> {
            throw new AssertionError("the NBT operation must not run on a 19-row hit");
        });
        var result = awaitResult(r);

        assertTrue(result.fromStore());
        assertEquals(nativeRaw.length + 2, result.sectionBytes().length);
        assertEquals(0x42, result.sectionBytes()[0], "the translated body, not the native one");
        assertEquals(321L, result.columnTimestamp());
        assertEquals(result.sectionBytes().length
                        + dev.vox.lss.common.LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                result.estimatedBytes(),
                "the charge re-derives from the TRANSLATED body");
        assertEquals(1, store.diag.getHits());
        assertEquals(0, store.getFrameCalls, "raw rung never consults getFrame");
        r.shutdown();
    }

    @Test
    void nineteenRowRawHitWithoutAWiredTranslatorReadsAsAnErroredMiss() {
        var r = reader(false);
        var store = new StubStore();
        store.rawAnswer = new LodStoreService.StoreHit(new byte[256], 5L, 19);
        r.attachStore(store);

        r.submitRead(PLAYER, 2, 2, DIM, 1L, () -> new byte[]{8});
        var result = awaitResult(r);
        assertFalse(result.fromStore(), "the 19-row must not serve untranslated");
        assertArrayEquals(new byte[]{8}, result.sectionBytes(), "the NBT ladder serves truth");
        assertTrue(store.diag.getErrors() > 0, "counted store.errors");
        assertEquals(0, store.diag.getHits(),
                "a failed translation is an errored miss, never ALSO a hit (review m17)");
        r.shutdown();
    }

    @Test
    void frameAllAirKeepsTheRawRungShape() {
        var r = reader(true);
        var store = new StubStore();
        store.frameAnswer = new LodStoreService.FrameHit(new byte[0], 0, 55L);
        r.attachStore(store);
        r.submitRead(PLAYER, 0, 0, DIM, 1L, () -> {
            throw new AssertionError("no NBT read on an all-air frame hit");
        });
        var result = awaitResult(r);
        assertTrue(result.fromStore());
        assertNull(result.sectionBytes());
        assertNull(result.frameBytes(), "all-air is the null-bytes shape, never a frame");
        assertFalse(result.notFound(), "all-air must never read as an authoritative miss");
        r.shutdown();
    }

    @Test
    void frameMissFallsToRegionIoWithoutASecondStoreRead() {
        var r = reader(true);
        var store = new StubStore(); // frameAnswer null = miss
        store.rawAnswer = new LodStoreService.StoreHit(new byte[]{1}, 9L); // must NOT be consulted
        r.attachStore(store);
        r.submitRead(PLAYER, 1, 2, DIM, 1L, () -> new byte[]{4, 2});
        var result = awaitResult(r);
        assertFalse(result.fromStore());
        assertArrayEquals(new byte[]{4, 2}, result.sectionBytes());
        assertEquals(1, store.getFrameCalls);
        assertEquals(0, store.getCalls, "a frame miss never retries via get()");
        assertEquals(1, store.diag.getMisses());
        r.shutdown();
    }

    @Test
    void flagOffKeepsTheRawRungAndNeverCallsGetFrame() {
        var r = reader(false);
        var store = new StubStore();
        store.rawAnswer = new LodStoreService.StoreHit(new byte[]{5, 6}, 88L);
        r.attachStore(store);
        r.submitRead(PLAYER, 1, 2, DIM, 1L, () -> {
            throw new AssertionError("store hit expected");
        });
        var result = awaitResult(r);
        assertTrue(result.fromStore());
        assertArrayEquals(new byte[]{5, 6}, result.sectionBytes());
        assertNull(result.frameBytes());
        assertEquals(0, store.getFrameCalls, "compression off = the pre-19 rung, bit-identical");
        r.shutdown();
    }

    @Test
    void throwingGetFrameIsContainedAsMiss() {
        var r = reader(true);
        var store = new StubStore() {
            @Override public FrameHit getFrame(String dimension, long packed) {
                throw new RuntimeException("boom");
            }
        };
        r.attachStore(store);
        r.submitRead(PLAYER, 1, 2, DIM, 1L, () -> new byte[]{7});
        var result = awaitResult(r);
        assertFalse(result.fromStore(), "an escaped store throw reads as a miss (belt)");
        assertArrayEquals(new byte[]{7}, result.sectionBytes());
        assertEquals(1, store.diag.getErrors());
        r.shutdown();
    }
}
