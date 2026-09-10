package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.voxel.ColumnTimestampCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The latest-wins save coalescing (issue #62): due saves publish to a single slot and the
 * executor queue holds at most one drain task per slot-fill, so a save slower than the
 * ~2 s invalidation-debounce cadence coalesces instead of backlogging ~42 MB snapshot
 * closures without bound (the reporter's heap dump: ~42 queued tasks, 1.76 GB retained).
 *
 * <p>Unit tests drive the scheduler against a recording executor; the wiring pin at the
 * bottom drives the REAL processor — the unit tests all stay green if processCycle
 * silently reverts to raw {@code saveExecutor.execute(...)} (e.g. in a support-line
 * conflict resolution), and the wiring pin is what makes that revert red.
 */
class TimestampSaveSchedulerTest {

    private static final String DIM = LSSConstants.DIM_STR_OVERWORLD;

    // ---- Unit tests: recording executor ----

    private static final class RecordingExecutor extends AbstractExecutorService {
        final ArrayDeque<Runnable> queued = new ArrayDeque<>();
        int executeCalls;
        boolean rejectAll;

        @Override public void execute(Runnable r) {
            executeCalls++;
            if (rejectAll) throw new RejectedExecutionException("test rejection");
            queued.add(r);
        }
        void runNext() { queued.remove().run(); }
        @Override public void shutdown() {}
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    /** Fixture stamps are epoch-relative: the tile cache clamps pre-epoch stamps up to
     *  {@code TS_EPOCH_SECONDS + 1} (timestamp-cache-tile-redesign.md §2.2), so a raw
     *  100L would read back as base+1. The helpers shift on the way in and out; 0 stays
     *  the absent sentinel. */
    private static final long B = ColumnTimestampCache.TS_EPOCH_SECONDS;

    private static ColumnTimestampCache cacheWith(long pos, long ts) {
        var c = new ColumnTimestampCache(1024, 0);
        c.put(DIM, pos, B + ts, 0L);
        return c;
    }

    private static long loadedGet(Path dir, long pos) {
        var c = new ColumnTimestampCache(1024, 0);
        c.load(dir);
        long ts = c.get(DIM, pos);
        return ts == 0 ? 0 : ts - B;
    }

    @Test
    void latestWinsAcrossABusyWorker(@TempDir Path tempDir) {
        var executor = new RecordingExecutor();
        var scheduler = new TimestampSaveScheduler(executor, tempDir);

        scheduler.schedule(cacheWith(1L, 100L));
        assertEquals(1, executor.executeCalls);
        executor.runNext();
        assertEquals(100L, loadedGet(tempDir, 1L), "an uncontended schedule saves its snapshot");

        // Worker "busy": two schedules land before the next drain runs.
        scheduler.schedule(cacheWith(2L, 200L));
        scheduler.schedule(cacheWith(3L, 300L));
        // This executeCalls assertion is the load-bearing pin: under the old per-save-closure
        // shape it reads 3. The file checks below stay green either way (save() whole-file
        // replaces, so sequential writes also end at S3) — do not delete this one for them.
        assertEquals(2, executor.executeCalls,
                "the second schedule found the slot full — no third drain task");
        executor.runNext();
        assertEquals(300L, loadedGet(tempDir, 3L), "the newest snapshot is the one written");
        assertEquals(0L, loadedGet(tempDir, 2L), "the overwritten snapshot is dropped, never written");
        assertTrue(executor.queued.isEmpty());
    }

    @Test
    void queueIsBoundedToOneDrainNoMatterHowManySavesFallDue(@TempDir Path tempDir) {
        var executor = new RecordingExecutor();
        var scheduler = new TimestampSaveScheduler(executor, tempDir);

        for (long i = 1; i <= 10; i++) {
            scheduler.schedule(cacheWith(i, i * 100));
        }
        assertEquals(1, executor.executeCalls, "ten due saves, one drain task");
        assertEquals(1, executor.queued.size());
        executor.runNext();
        assertEquals(1000L, loadedGet(tempDir, 10L), "the tenth (newest) snapshot is written");
        assertEquals(0L, loadedGet(tempDir, 5L));
    }

    @Test
    void shutdownRejectionIsSwallowedAndTheSlotClears(@TempDir Path tempDir) {
        var executor = new RecordingExecutor();
        var scheduler = new TimestampSaveScheduler(executor, tempDir);

        executor.rejectAll = true;
        assertDoesNotThrow(() -> scheduler.schedule(cacheWith(1L, 100L)),
                "a schedule racing executor shutdown must not throw into the processing loop");

        // The slot was cleared on rejection: the next schedule is an empty→full transition
        // again and attempts a fresh drain (rather than silently parking forever).
        executor.rejectAll = false;
        scheduler.schedule(cacheWith(2L, 200L));
        assertEquals(2, executor.executeCalls, "rejection cleared the slot — a new drain is queued");
        executor.runNext();
        assertEquals(200L, loadedGet(tempDir, 2L));
        assertEquals(0L, loadedGet(tempDir, 1L), "the rejected snapshot died with the rejection");
    }

    @Test
    void discardPendingMakesAQueuedDrainANoOp(@TempDir Path tempDir) {
        var executor = new RecordingExecutor();
        var scheduler = new TimestampSaveScheduler(executor, tempDir);

        scheduler.schedule(cacheWith(1L, 100L));
        scheduler.discardPending(); // shutdown: the final save supersedes the slot
        executor.runNext();
        assertFalse(Files.exists(tempDir.resolve("lss-timestamps.bin")),
                "the discarded snapshot must not be written by the stale drain");
    }

    // ---- Wiring pin: the REAL processor must route due saves through the scheduler ----

    private static final class TestState extends AbstractPlayerRequestState<Object> {
        TestState(UUID uuid) { super(uuid, 4, 4); }
        @Override public String getPlayerName() { return "test"; }
    }

    private static final class StubDiskReader extends AbstractChunkDiskReader {
        StubDiskReader() { super(1); }
    }

    private static final class TestProcessor extends OffThreadProcessor<TestState> {
        TestProcessor(Map<UUID, TestState> players, Path dataDir) {
            super(players, new StubDiskReader(), false, dataDir, 1, 0);
        }
        @Override
        protected boolean submitDiskRead(UUID playerUuid, RequestRegistration registration, String dimension, int cx, int cz, long order, long clientTimestamp) {
            return true;
        }
        @Override
        protected boolean buildAndEnqueueColumnPayload(TestState state, int cx, int cz, String dimension,
                                                        long columnTimestamp, long submissionOrder,
                                                        ColumnBytes bytes, int estimatedBytes, byte source) {
            return true;
        }
    }

    private static TickSnapshot emptySnapshot() {
        return new TickSnapshot(new HashMap<>(), Map.of(), 0, false);
    }

    /** Posts snapshots (each consumed post is one processing cycle) until the condition holds. */
    private static void driveCyclesUntil(OffThreadProcessor<TestState> proc, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            // A raw saveExecutor.execute(...) revert reds HERE (scheduledForTest never moves),
            // not at the queue assertions below — name the wiring so the timeout is diagnosable.
            if (System.nanoTime() > deadline) fail("condition not reached within 10s of cycling — "
                    + "is processCycle still routing due saves through TimestampSaveScheduler?");
            proc.postSnapshot(emptySnapshot(), List.of());
            Thread.sleep(2);
        }
    }

    @Test
    void processorRoutesDueSavesThroughTheCoalescingSlot(@TempDir Path tempDir) throws Exception {
        var proc = new TestProcessor(new ConcurrentHashMap<>(), tempDir);
        try {
            // Seed pre-start (no concurrency yet; Thread.start() publishes it).
            var cache = proc.timestampCacheForTest();
            cache.put(DIM, 1L, B + 100, 1000L);
            cache.put(DIM, 2L, B + 200, 1000L);
            cache.put(DIM, 3L, B + 300, 1000L);

            // Occupy the save worker so every scheduled drain stays observable in the queue.
            // First-ever task on a corePoolSize-1 executor: handed straight to the new
            // worker thread, never queued.
            var executor = proc.saveExecutorForTest();
            var blocker = new CountDownLatch(1);
            executor.execute(() -> {
                try { blocker.await(); } catch (InterruptedException ignored) { }
            });

            proc.start();
            var scheduler = proc.saveSchedulerForTest();

            // First due save: an invalidation removal arms the debounce; ~40 cycles later
            // the save falls due and must queue exactly one drain.
            proc.invalidateTimestamps(DIM, new long[]{1L});
            driveCyclesUntil(proc, () -> scheduler.scheduledForTest() >= 1);
            assertEquals(1, executor.getQueue().size(), "first due save queued one drain task");

            // Second due save while the first drain is still queued behind the blocked
            // worker: it must land in the slot, NOT grow the queue. This is the assertion
            // a raw saveExecutor.execute(...) revert fails.
            proc.invalidateTimestamps(DIM, new long[]{2L});
            driveCyclesUntil(proc, () -> scheduler.scheduledForTest() >= 2);
            assertEquals(1, executor.getQueue().size(),
                    "second due save coalesced — the executor queue must not grow (issue #62)");

            blocker.countDown();
            // FIFO marker: when it completes, the drain before it has fully run.
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);

            var loaded = new ColumnTimestampCache(1024, 0);
            loaded.load(tempDir);
            assertEquals(B + 300, loaded.get(DIM, 3L), "the surviving entry is on disk");
            assertEquals(0L, loaded.get(DIM, 2L),
                    "the drain wrote the NEWEST snapshot (post-second-invalidation), not the first");
            assertEquals(0L, loaded.get(DIM, 1L));
        } finally {
            proc.shutdown();
        }
    }
}
