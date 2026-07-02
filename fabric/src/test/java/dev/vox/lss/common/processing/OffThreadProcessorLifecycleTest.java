package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * Lifecycle behavior of the processing loop: a poisoned cycle (an Error escaping
 * processCycle) must not kill the processing thread, and the timestamp cache must
 * survive a shutdown-save → constructor-load restart so a warm resync request
 * short-circuits to up-to-date without touching the disk.
 */
class OffThreadProcessorLifecycleTest {

    private static final String DIM = LSSConstants.DIM_STR_OVERWORLD;
    private static final long COLUMN_TS = 1_700_000_000L;

    private static final class TestState extends AbstractPlayerRequestState<Object> {
        TestState(UUID uuid) { super(uuid, 4, 4); }
        @Override public String getPlayerName() { return "test"; }
        void enqueue(IncomingRequest r) { enqueueIncomingRequest(r); }
    }

    /** No abstract members — subclassing only unlocks instantiation; tests bypass the
     *  executor entirely and inject results via the public per-player queue. */
    private static final class StubDiskReader extends AbstractChunkDiskReader {
        StubDiskReader() { super(1); }
    }

    private static final class TestProcessor extends OffThreadProcessor<TestState> {
        record EnqueuedColumn(int cx, int cz, long columnTimestamp, byte[] bytes) {}

        final ConcurrentLinkedQueue<EnqueuedColumn> enqueuedColumns = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Long> diskSubmits = new ConcurrentLinkedQueue<>();
        final AtomicInteger poisonFired = new AtomicInteger();
        volatile long poisonPacked = Long.MIN_VALUE;

        TestProcessor(Map<UUID, TestState> players, AbstractChunkDiskReader reader, Path dataDir) {
            super(players, reader, false, dataDir, 1);
        }

        @Override
        protected boolean submitDiskRead(UUID playerUuid, String dimension, int cx, int cz, long order) {
            diskSubmits.add(PositionUtil.packPosition(cx, cz));
            return true;
        }

        @Override
        protected boolean buildAndEnqueueColumnPayload(TestState state, int cx, int cz, String dimension,
                                                     long columnTimestamp, long submissionOrder,
                                                     byte[] sectionBytes, int estimatedBytes) {
            if (PositionUtil.packPosition(cx, cz) == poisonPacked) {
                poisonFired.incrementAndGet();
                // An Error, not an Exception: pins that processingLoop catches Throwable.
                // Narrowing that catch would let one malformed column kill the LOD
                // pipeline for every player.
                throw new LinkageError("poisoned column " + cx + "," + cz);
            }
            enqueuedColumns.add(new EnqueuedColumn(cx, cz, columnTimestamp, sectionBytes));
            return true;
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

    private static ChunkReadResult dataResult(UUID u, int cx, int cz, byte[] bytes, long ts, long order) {
        return new ChunkReadResult(u, cx, cz, bytes, DIM,
                bytes.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES, ts, false, false, order);
    }

    private record Response(byte type, long packed) {}

    /** Poll drainSendActions until {@code done} holds over everything drained so far. */
    private static List<Response> drainUntil(TestProcessor proc, Predicate<List<Response>> done)
            throws InterruptedException {
        var collected = new ArrayList<Response>();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!done.test(collected)) {
            if (System.nanoTime() > deadline) fail("timed out draining responses; got " + collected);
            proc.drainSendActions((state, types, positions, count) -> {
                for (int i = 0; i < count; i++) {
                    collected.add(new Response(types[i], positions[i]));
                }
            });
            Thread.sleep(10);
        }
        return collected;
    }

    private static Predicate<List<Response>> received(byte type, long packed) {
        return rs -> rs.stream().anyMatch(r -> r.type() == type && r.packed() == packed);
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for: " + what);
            Thread.sleep(10);
        }
    }

    @Test
    void poisonedCycleDoesNotKillTheProcessingLoop() throws Exception {
        var u = UUID.randomUUID();
        var state = newPlayer(u);
        var players = new ConcurrentHashMap<UUID, TestState>();
        players.put(u, state);
        var reader = new StubDiskReader();
        reader.registerPlayer(u);
        var proc = new TestProcessor(players, reader, null);
        proc.poisonPacked = PositionUtil.packPosition(9, 9);
        try {
            proc.start();

            reader.getPlayerQueue(u).add(dataResult(u, 9, 9, new byte[]{1}, COLUMN_TS, 1L));
            proc.postSnapshot(snapshot(u), List.of());
            waitFor(() -> proc.poisonFired.get() >= 1, "poisoned cycle fired");

            // The loop must survive the Error and process the next cycle normally
            reader.getPlayerQueue(u).add(dataResult(u, 2, 2, new byte[]{2}, COLUMN_TS, 2L));
            proc.postSnapshot(snapshot(u), List.of());
            waitFor(() -> !proc.enqueuedColumns.isEmpty(),
                    "processing loop alive after poisoned cycle");

            var col = proc.enqueuedColumns.poll();
            assertEquals(2, col.cx());
            assertEquals(2, col.cz());
            assertEquals(1, proc.poisonFired.get(), "poison must have fired exactly once");
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void timestampCacheSurvivesRestartAndShortCircuitsWarmRequests(@TempDir Path dataDir)
            throws Exception {
        var u = UUID.randomUUID();
        long packed = PositionUtil.packPosition(10, 10);

        // --- Session 1: a disk read serves (10,10), stamping the timestamp cache ---
        var s1 = newPlayer(u);
        var players1 = new ConcurrentHashMap<UUID, TestState>();
        players1.put(u, s1);
        var reader1 = new StubDiskReader();
        reader1.registerPlayer(u);
        var proc1 = new TestProcessor(players1, reader1, dataDir);
        try {
            proc1.start();
            s1.enqueue(new IncomingRequest(10, 10, 1L));
            proc1.postSnapshot(snapshot(u), List.of());
            waitFor(() -> proc1.diskSubmits.size() == 1, "session-1 disk submit");

            reader1.getPlayerQueue(u).add(dataResult(u, 10, 10, new byte[]{1, 2}, COLUMN_TS, 1L));
            proc1.postSnapshot(snapshot(u), List.of());
            waitFor(() -> !proc1.enqueuedColumns.isEmpty(), "session-1 column served");
        } finally {
            proc1.shutdown(); // joins the processing thread, then saves the cache
        }
        assertTrue(Files.exists(dataDir.resolve("lss-timestamps.bin")),
                "shutdown must persist the timestamp cache");

        // --- Session 2: a fresh processor loads the cache in its constructor ---
        var s2 = newPlayer(u);
        var players2 = new ConcurrentHashMap<UUID, TestState>();
        players2.put(u, s2);
        var reader2 = new StubDiskReader();
        reader2.registerPlayer(u);
        var proc2 = new TestProcessor(players2, reader2, dataDir);
        try {
            proc2.start();
            // The reconnecting client resyncs with the timestamp it stored for the column
            s2.enqueue(new IncomingRequest(10, 10, COLUMN_TS));
            proc2.postSnapshot(snapshot(u), List.of());

            drainUntil(proc2, received(LSSConstants.RESPONSE_UP_TO_DATE, packed));
            assertTrue(proc2.diskSubmits.isEmpty(), "warm request must resolve without any disk read");
            assertEquals(0, s2.getHeldSyncSlots(), "up-to-date resolution must not consume a slot");
            assertTrue(s2.hasDiskReadDone(10, 10), "warm resolution marks the position served");
        } finally {
            proc2.shutdown();
        }
    }

    @Test
    void invalidationsQueuedAtShutdownStillReachTheFinalSave(@TempDir Path dataDir)
            throws Exception {
        var u = UUID.randomUUID();
        long packed = PositionUtil.packPosition(11, 11);

        // --- Session 1: serve (11,11), then an edit invalidates it right as the server stops.
        // The invalidation rides the shutdown sentinel take — it must still hit the cache
        // before the final save, or the persisted stamp answers false up_to_date forever.
        var s1 = newPlayer(u);
        var players1 = new ConcurrentHashMap<UUID, TestState>();
        players1.put(u, s1);
        var reader1 = new StubDiskReader();
        reader1.registerPlayer(u);
        var proc1 = new TestProcessor(players1, reader1, dataDir);
        try {
            proc1.start();
            s1.enqueue(new IncomingRequest(11, 11, 1L));
            proc1.postSnapshot(snapshot(u), List.of());
            waitFor(() -> proc1.diskSubmits.size() == 1, "session-1 disk submit");
            reader1.getPlayerQueue(u).add(dataResult(u, 11, 11, new byte[]{1, 2}, COLUMN_TS, 1L));
            proc1.postSnapshot(snapshot(u), List.of());
            waitFor(() -> !proc1.enqueuedColumns.isEmpty(), "session-1 column served");
        } finally {
            proc1.invalidateTimestamps(DIM, new long[]{packed}); // rides the sentinel
            proc1.shutdown();
        }

        // --- Session 2: the resync at the old stamp must RE-READ, not answer up_to_date ---
        var s2 = newPlayer(u);
        var players2 = new ConcurrentHashMap<UUID, TestState>();
        players2.put(u, s2);
        var reader2 = new StubDiskReader();
        reader2.registerPlayer(u);
        var proc2 = new TestProcessor(players2, reader2, dataDir);
        try {
            proc2.start();
            s2.enqueue(new IncomingRequest(11, 11, COLUMN_TS));
            proc2.postSnapshot(snapshot(u), List.of());
            waitFor(() -> proc2.diskSubmits.contains(packed),
                    "invalidated position must re-read the disk after restart");
        } finally {
            proc2.shutdown();
        }
    }

    @Test
    void invalidationTriggersADebouncedSaveWellBeforeThePeriodicInterval(@TempDir Path dataDir)
            throws Exception {
        var u = UUID.randomUUID();
        var s = newPlayer(u);
        var players = new ConcurrentHashMap<UUID, TestState>();
        players.put(u, s);
        var reader = new StubDiskReader();
        reader.registerPlayer(u);
        var proc = new TestProcessor(players, reader, dataDir);
        var cacheFile = dataDir.resolve("lss-timestamps.bin");
        try {
            proc.start();
            // Serve a disk read so the timestamp cache holds an entry for (10,10).
            s.enqueue(new IncomingRequest(10, 10, 1L));
            proc.postSnapshot(snapshot(u), List.of());
            waitFor(() -> proc.diskSubmits.size() == 1, "disk submit");
            reader.getPlayerQueue(u).add(dataResult(u, 10, 10, new byte[]{1, 2}, COLUMN_TS, 1L));
            proc.postSnapshot(snapshot(u), List.of());
            waitFor(() -> !proc.enqueuedColumns.isEmpty(), "column served (cache stamped)");
            assertFalse(Files.exists(cacheFile), "no periodic save yet (interval is ~5 min)");

            // A dirty-broadcast invalidation of the stamped position arms the debounced save;
            // it must land within seconds (well under SAVE_INTERVAL_CYCLES), not resurrect on a
            // crash inside the periodic window.
            proc.invalidateTimestamps(DIM, new long[]{PositionUtil.packPosition(10, 10)});
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!Files.exists(cacheFile) && System.nanoTime() < deadline) {
                proc.postSnapshot(snapshot(u), List.of());
                Thread.sleep(10);
            }
            assertTrue(Files.exists(cacheFile),
                    "an invalidation must trigger a debounced save within seconds, not only every ~5 min");
        } finally {
            proc.shutdown();
        }
    }
}
