package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the submit/triage envelope of the shared disk reader base: every submit resolves
 * to exactly one result in the submitting player's queue across all six outcomes
 * (data / all-air / not-found / Exception / Error / pool saturation), the diagnostics
 * counters partition exactly while firing live, and a shut-down reader is silent.
 * A stranded submit means a leaked admission slot, an orphaned dedup group, and a
 * permanent hole in the LOD terrain — this envelope is the conservation root behind the
 * soak checker's request/disk accounting laws.
 */
class AbstractChunkDiskReaderTest {

    private static final String DIM = "minecraft:overworld";

    private static final class TestDiskReader extends AbstractChunkDiskReader {
        TestDiskReader() { super(1); }

        void submit(UUID player, int cx, int cz, long order, ReadOperation op) {
            submitRead(player, cx, cz, DIM, order, op);
        }
    }

    private final UUID player = UUID.randomUUID();
    private TestDiskReader reader;

    @BeforeEach
    void setUp() {
        reader = new TestDiskReader();
        reader.registerPlayer(player);
    }

    @AfterEach
    void tearDown() {
        reader.shutdown();
    }

    /** Drain exactly {@code expected} results from the player's queue, failing on timeout. */
    private List<ChunkReadResult> awaitResults(int expected) throws InterruptedException {
        var queue = reader.getPlayerQueue(player);
        var out = new ArrayList<ChunkReadResult>(expected);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (out.size() < expected) {
            if (System.nanoTime() > deadline) {
                fail("timed out: got " + out.size() + " of " + expected + " results");
            }
            var r = queue.poll();
            if (r != null) out.add(r);
            else Thread.sleep(5);
        }
        return out;
    }

    /**
     * Submit the operation under test, then an all-air barrier read behind it on the
     * single reader thread. Exactly two results must arrive in FIFO order — a stranded
     * first submit times out and a double-delivered one changes the count — and the
     * identity fields must survive triage. Returns the first (tested) result.
     */
    private ChunkReadResult runWithBarrier(int cx, int cz, long order,
                                           AbstractChunkDiskReader.ReadOperation op)
            throws InterruptedException {
        reader.submit(player, cx, cz, order, op);
        reader.submit(player, 999, 999, Long.MAX_VALUE, () -> new byte[0]);
        var results = awaitResults(2);
        assertNull(reader.getPlayerQueue(player).poll(), "exactly one result per submit");
        assertEquals(999, results.get(1).chunkX(), "barrier result must arrive second (FIFO)");
        var first = results.get(0);
        assertEquals(player, first.playerUuid());
        assertEquals(cx, first.chunkX());
        assertEquals(cz, first.chunkZ());
        assertEquals(DIM, first.dimension());
        assertEquals(order, first.submissionOrder());
        return first;
    }

    @Test
    void successfulReadDeliversBytesWithTimestampAndOverhead() throws Exception {
        byte[] bytes = {10, 20, 30};
        long before = LSSConstants.epochSeconds();
        var r = runWithBarrier(5, -7, 42L, () -> bytes);

        assertFalse(r.notFound());
        assertFalse(r.saturated());
        assertArrayEquals(bytes, r.sectionBytes());
        assertEquals(bytes.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES, r.estimatedBytes());
        assertTrue(r.columnTimestamp() >= before, "data result must carry a serve timestamp");
        assertEquals(1, reader.getDiag().getSuccessfulReadCount());
        assertEquals(1, reader.getDiag().getAllAirCount()); // the barrier read
        assertEquals(2, reader.getDiag().getCompletedCount());
    }

    @Test
    void allAirChunkResolvesAsFoundWithoutBytes() throws Exception {
        var r = runWithBarrier(1, 2, 7L, () -> new byte[0]);

        assertFalse(r.notFound(),
                "all-air is found, not missing — treating it as missing re-generates void chunks forever");
        assertFalse(r.saturated());
        assertNull(r.sectionBytes());
        assertEquals(0, r.estimatedBytes());
        assertTrue(r.columnTimestamp() > 0, "all-air still stamps a serve timestamp");
        assertEquals(2, reader.getDiag().getAllAirCount()); // tested read + barrier
        assertEquals(0, reader.getDiag().getSuccessfulReadCount());
    }

    @Test
    void nullReadResolvesAsNotFound() throws Exception {
        var r = runWithBarrier(3, 4, 9L, () -> null);

        assertTrue(r.notFound());
        assertFalse(r.saturated());
        assertNull(r.sectionBytes());
        assertEquals(0L, r.columnTimestamp());
        assertEquals(1, reader.getDiag().getNotFoundCount());
        assertEquals(0, reader.getDiag().getErrorCount(), "a clean miss is not an error");
    }

    @Test
    void throwingReadStillDeliversExactlyOneResult() throws Exception {
        var r = runWithBarrier(-3, 8, 11L, () -> {
            throw new IOException("simulated corrupt region file");
        });

        assertTrue(r.notFound(), "errored read must answer like a miss so the requester is not stranded");
        assertFalse(r.saturated());
        assertEquals(1, reader.getDiag().getErrorCount());
        assertEquals(0, reader.getDiag().getNotFoundCount(),
                "errors must not masquerade as clean misses in diagnostics");
        assertEquals(2, reader.getDiag().getCompletedCount());
    }

    @Test
    void errorThrowingReadDeliversResultBeforeRethrowAndPoolSurvives() throws Exception {
        // An Error skips the Exception triage and reaches the outer Throwable catch, which
        // must still deliver a result before rethrowing. The barrier completing proves the
        // worker pool survived the rethrown Error.
        var r = runWithBarrier(2, 2, 13L, () -> {
            throw new NoClassDefFoundError("simulated serializer linkage failure");
        });

        assertTrue(r.notFound());
        assertFalse(r.saturated());
        assertEquals(1, reader.getDiag().getErrorCount());
        assertEquals(2, reader.getDiag().getCompletedCount(),
                "the Error-path submit must still count as completed");
    }

    @Test
    void poolSaturationBouncesTheSubmitWithASaturatedResult() throws Exception {
        // 1 reader thread, queue capacity 32 (threadCount * 32): with the worker pinned on
        // a gated read and the queue exactly full, the 34th submit must be rejected.
        var started = new CountDownLatch(1);
        var gate = new CountDownLatch(1);
        reader.submit(player, 0, 0, 1L, () -> {
            started.countDown();
            if (!gate.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("gate never opened");
            return new byte[0];
        });
        assertTrue(started.await(5, TimeUnit.SECONDS), "first read must occupy the worker");
        for (int i = 1; i <= 32; i++) {
            reader.submit(player, i, 0, 1L + i, () -> {
                if (!gate.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("gate never opened");
                return new byte[0];
            });
        }
        assertEquals(0, reader.getDiag().getSaturationCount(),
                "33 reads fit the pool (1 running + 32 queued)");

        reader.submit(player, 99, 88, 999L, () -> new byte[0]);

        // The bounce is synchronous, and every other read is still gate-blocked, so the
        // saturated result is deterministically the only one queued right now.
        var sat = reader.getPlayerQueue(player).poll();
        assertNotNull(sat, "rejected submit must still produce a result — never stranded in flight");
        assertTrue(sat.saturated());
        assertFalse(sat.notFound(), "saturation means retry-later, not a miss");
        assertEquals(99, sat.chunkX());
        assertEquals(88, sat.chunkZ());
        assertEquals(999L, sat.submissionOrder());
        assertNull(reader.getPlayerQueue(player).poll());
        assertEquals(1, reader.getDiag().getSaturationCount());

        gate.countDown();
        awaitResults(33);
        assertNull(reader.getPlayerQueue(player).poll(), "exactly one result per submit, 34 in total");
        var d = reader.getDiag();
        assertEquals(34, d.getSubmittedCount());
        assertEquals(34, d.getCompletedCount(), "the rejected submit counts as completed — nothing in flight");
        assertEquals(33, d.getAllAirCount());
        assertEquals(d.getCompletedCount(),
                d.getSuccessfulReadCount() + d.getNotFoundCount() + d.getAllAirCount()
                        + d.getErrorCount() + d.getSaturationCount());
    }

    @Test
    void completionPartitionIsExactAcrossLiveOutcomes() throws Exception {
        reader.submit(player, 0, 0, 1L, () -> new byte[]{1});
        reader.submit(player, 1, 0, 2L, () -> new byte[0]);
        reader.submit(player, 2, 0, 3L, () -> null);
        reader.submit(player, 3, 0, 4L, () -> { throw new IOException("simulated"); });
        reader.submit(player, 4, 0, 5L, () -> { throw new NoClassDefFoundError("simulated"); });

        var results = awaitResults(5);
        assertNull(reader.getPlayerQueue(player).poll());
        // FIFO on one thread: per-submit identity survives triage for every outcome
        assertEquals(List.of(0, 1, 2, 3, 4),
                results.stream().map(ChunkReadResult::chunkX).toList());

        var d = reader.getDiag();
        assertEquals(5, d.getSubmittedCount());
        assertEquals(5, d.getCompletedCount(), "every submit must complete");
        assertEquals(1, d.getSuccessfulReadCount());
        assertEquals(1, d.getAllAirCount());
        assertEquals(1, d.getNotFoundCount());
        assertEquals(2, d.getErrorCount());
        assertEquals(0, d.getSaturationCount());
        assertEquals(d.getCompletedCount(),
                d.getSuccessfulReadCount() + d.getNotFoundCount() + d.getAllAirCount()
                        + d.getErrorCount() + d.getSaturationCount());
    }

    @Test
    void shutDownReaderIsSilent() throws Exception {
        reader.submit(player, 1, 1, 1L, () -> new byte[0]);
        awaitResults(1);
        assertEquals(1, reader.getDiag().getSubmittedCount());

        reader.shutdown();
        reader.submit(player, 2, 2, 2L, () -> new byte[]{1});

        assertEquals(1, reader.getDiag().getSubmittedCount(),
                "a post-shutdown submit must not be recorded");
        assertEquals(1, reader.getDiag().getCompletedCount());
        assertNull(reader.getPlayerQueue(player),
                "player queues are cleared on shutdown — nothing can deliver");
    }

    // ---- SP-074: a result completing after the player was removed is dropped, not stored ----

    @Test
    void resultsDeliveredAfterPlayerRemovalAreSilentlyDropped() throws InterruptedException {
        var readStarted = new CountDownLatch(1);
        var holdRead = new CountDownLatch(1);
        reader.submit(player, 5, 5, 1L, () -> {
            readStarted.countDown();
            holdRead.await(); // block the read in flight until we've removed the player
            return new byte[]{1, 2, 3};
        });
        assertTrue(readStarted.await(5, TimeUnit.SECONDS), "the read started");

        reader.removePlayerResults(player); // the player's queue is gone before the read finishes
        holdRead.countDown();               // now the read completes and attempts delivery

        // Barrier behind the dropped delivery: the single reader thread is FIFO, so once a
        // second (registered) player's result lands, the first delivery attempt has happened.
        UUID other = UUID.randomUUID();
        reader.registerPlayer(other);
        reader.submit(other, 0, 0, 2L, () -> new byte[0]);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (reader.getPlayerQueue(other).isEmpty()) {
            if (System.nanoTime() > deadline) fail("barrier read never delivered");
            Thread.sleep(5);
        }

        assertNull(reader.getPlayerQueue(player), "a late delivery does not resurrect the removed queue");
        reader.registerPlayer(player);
        assertTrue(reader.getPlayerQueue(player).isEmpty(),
                "re-registering yields a fresh empty queue — the dropped result is gone for good");
    }

    // ---- SP-075: shutdown is bounded — it interrupts an in-flight read instead of hanging ----

    @Test
    void shutdownReturnsPromptlyByInterruptingAnInFlightRead() throws InterruptedException {
        var readStarted = new CountDownLatch(1);
        reader.submit(player, 1, 1, 1L, () -> {
            readStarted.countDown();
            Thread.sleep(60_000); // long but interruptible — shutdownNow must cut it short
            return new byte[0];
        });
        assertTrue(readStarted.await(5, TimeUnit.SECONDS), "the slow read started");

        long start = System.nanoTime();
        reader.shutdown();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 5_000,
                "shutdown is bounded — it interrupted the 60s read rather than awaiting it (" + elapsedMs + "ms)");
    }
}
