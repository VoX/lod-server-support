package dev.vox.lss.common.processing;

import dev.vox.lss.common.store.LodStoreDiagnostics;
import dev.vox.lss.common.store.LodStoreMode;
import dev.vox.lss.common.store.LodStoreService;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store rung inside {@link AbstractChunkDiskReader#submitRead} (plan §1 rung
 * contract): a hit serves STORED bytes + STORED timestamp tagged {@code fromStore} and
 * touches NO {@code disk.*} counter (which also proves the throttle EWMA exclusion —
 * {@code recordRealCompletion} is the only latency feeder and it increments
 * {@code completed}); a miss counts {@code store.misses} and proceeds down the NBT path
 * with today's counters; a throwing store is contained as a miss; all-air hits deliver
 * the all-air result shape (never not-found — that would seed the miss memo).
 */
class DiskReaderStoreRungTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final String DIM = "minecraft:overworld";

    private static final class StubStore implements LodStoreService {
        final LodStoreDiagnostics diag = new LodStoreDiagnostics();
        StoreHit answer;
        RuntimeException throwOnGet;
        long lastGetPacked = Long.MIN_VALUE;

        @Override public LodStoreMode mode() { return LodStoreMode.FULL; }

        @Override public StoreHit get(String dimension, long packed) {
            this.lastGetPacked = packed;
            if (this.throwOnGet != null) throw this.throwOnGet;
            return this.answer;
        }

        @Override public boolean deposit(String d, long p, byte[] b, long ts, long acq) { return true; }
        @Override public void invalidate(String d, long[] p) {}
        @Override public void delete(String d, long p) {}
        @Override public LodStoreDiagnostics diagnostics() { return this.diag; }
        @Override public void shutdown() {}
    }

    private static AbstractChunkDiskReader reader() {
        var r = new AbstractChunkDiskReader(1) {};
        r.registerPlayer(PLAYER);
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
    void hitServesStoredBytesAndTimestampWithZeroDiskCounters() {
        var r = reader();
        var store = new StubStore();
        byte[] stored = {5, 6, 7, 8};
        store.answer = new LodStoreService.StoreHit(stored, 777L);
        r.attachStore(store);

        r.submitRead(PLAYER, 3, -4, DIM, 1L, () -> {
            throw new AssertionError("the NBT operation must not run on a store hit");
        });
        var result = awaitResult(r);

        assertTrue(result.fromStore());
        assertArrayEquals(stored, result.sectionBytes());
        assertEquals(777L, result.columnTimestamp(), "STORED stamp — never epochSeconds()");
        assertFalse(result.notFound());
        assertEquals(1, store.diag.getHits());
        assertEquals(0, store.diag.getMisses());
        assertEquals(0, r.getDiag().getSubmittedCount(), "hits are excluded from disk.submitted");
        assertEquals(0, r.getDiag().getCompletedCount(),
                "hits never reach recordRealCompletion — which is also the throttle-EWMA exclusion");
        r.shutdown();
    }

    @Test
    void allAirHitDeliversAllAirShapeNeverNotFound() {
        var r = reader();
        var store = new StubStore();
        store.answer = new LodStoreService.StoreHit(new byte[0], 500L);
        r.attachStore(store);

        r.submitRead(PLAYER, 0, 0, DIM, 1L, () -> {
            throw new AssertionError("must not reach the NBT path");
        });
        var result = awaitResult(r);

        assertTrue(result.fromStore());
        assertNull(result.sectionBytes(), "all-air result shape (null section bytes)");
        assertFalse(result.notFound(),
                "byte[0] from the store is all-air, NEVER a miss — a not-found here would "
                        + "seed the miss memo with a false absence");
        assertEquals(500L, result.columnTimestamp());
        r.shutdown();
    }

    @Test
    void missCountsStoreMissAndRunsTheNbtPathWithTodaysCounters() {
        var r = reader();
        var store = new StubStore(); // answer = null -> miss
        r.attachStore(store);

        byte[] nbtBytes = {1, 2, 3};
        r.submitRead(PLAYER, 7, 9, DIM, 1L, () -> nbtBytes);
        var result = awaitResult(r);

        assertFalse(result.fromStore());
        assertArrayEquals(nbtBytes, result.sectionBytes());
        assertEquals(1, store.diag.getMisses());
        assertEquals(0, store.diag.getHits());
        assertEquals(1, r.getDiag().getSubmittedCount());
        assertEquals(1, r.getDiag().getCompletedCount());
        assertEquals(1, r.getDiag().getSuccessfulReadCount());
        assertEquals(dev.vox.lss.common.PositionUtil.packPosition(7, 9), store.lastGetPacked);
        r.shutdown();
    }

    @Test
    void throwingStoreIsContainedAsMissAndCountsStoreError() {
        var r = reader();
        var store = new StubStore();
        store.throwOnGet = new IllegalStateException("boom");
        r.attachStore(store);

        r.submitRead(PLAYER, 1, 1, DIM, 1L, () -> new byte[]{9});
        var result = awaitResult(r);

        assertFalse(result.fromStore());
        assertNotNull(result.sectionBytes(), "the NBT path served despite the store throw");
        assertEquals(1, store.diag.getErrors());
        assertEquals(1, store.diag.getMisses(), "a contained throw reads as a miss");
        assertEquals(1, r.getDiag().getSubmittedCount());
        r.shutdown();
    }

    @Test
    void noStoreAttachedBehavesExactlyAsBefore() {
        var r = reader();
        r.submitRead(PLAYER, 2, 2, DIM, 1L, () -> new byte[]{4, 4});
        var result = awaitResult(r);
        assertFalse(result.fromStore());
        assertEquals(1, r.getDiag().getSubmittedCount());
        assertEquals(1, r.getDiag().getCompletedCount());
        r.shutdown();
    }

    @Test
    void saturationBounceCountsTheFullTripleTogether() throws Exception {
        // Fill the single-thread pool + queue with blocking tasks, then submit past
        // capacity: the bounce must record submitted+saturated+completed together so the
        // at-rest identity holds under the moved recordSubmitted.
        var r = reader();
        var started = new java.util.concurrent.CountDownLatch(1);
        var latch = new java.util.concurrent.CountDownLatch(1);
        int capacity = 1 + 32; // one running + queue (32 per thread)
        for (int i = 0; i < capacity; i++) {
            r.submitRead(PLAYER, i, 0, DIM, i, () -> {
                started.countDown();
                latch.await();
                return null;
            });
        }
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS),
                "the first blocked task must be running (it counts one submitted)");
        int extra = 3;
        for (int i = 0; i < extra; i++) {
            r.submitRead(PLAYER, 100 + i, 0, DIM, 100 + i, () -> null);
        }
        assertEquals(extra, r.getDiag().getSaturationCount());
        assertEquals(extra + 1, r.getDiag().getSubmittedCount(),
                "each bounce records submitted; +1 is the running blocked task (queued "
                        + "tasks haven't reached the NBT path yet)");
        assertEquals(extra, r.getDiag().getCompletedCount());
        latch.countDown();
        r.shutdown();
    }
}
