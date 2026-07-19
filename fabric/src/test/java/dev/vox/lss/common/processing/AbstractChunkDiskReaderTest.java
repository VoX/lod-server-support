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

        void enableThrottle() {
            enableAdaptiveThrottleFallback(); // protected in the base; exposed for the wiring test
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
        assertTrue(r.authoritativeMiss(),
                "a null read is storage answering 'no such chunk' — the memo-seeding flavor");
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
        assertFalse(r.authoritativeMiss(),
                "an exception says nothing about existence — it must never seed the miss memo");
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
        assertFalse(r.authoritativeMiss(),
                "an Error (SOE on corrupt NBT, OOM) may hit a chunk that EXISTS — an"
                        + " authoritative result here would memoize a false absence for the TTL");
        assertFalse(r.saturated());
        assertEquals(1, reader.getDiag().getErrorCount());
        assertEquals(2, reader.getDiag().getCompletedCount(),
                "the Error-path submit must still count as completed");
    }

    @Test
    void hasHeadroomIsFalseExactlyWhenTheNextSubmitWouldBeRejected() throws Exception {
        // hasHeadroom() is the router's pre-submit gate (protocol v17): it keeps disk
        // saturation out of the client-visible protocol by leaving the entry in the backlog
        // instead of submitting into a full pool. Its whole worth is that it agrees with the
        // pool's actual accept/reject decision — so assert against a REAL rejection, not the
        // gauge alone.
        assertTrue(reader.hasHeadroom(), "an idle pool has headroom");

        var started = new CountDownLatch(1);
        var gate = new CountDownLatch(1);
        reader.submit(player, 0, 0, 1L, () -> {
            started.countDown();
            if (!gate.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("gate never opened");
            return new byte[0];
        });
        assertTrue(started.await(5, TimeUnit.SECONDS), "first read must occupy the worker");
        for (int i = 1; i <= 31; i++) {
            reader.submit(player, i, 0, 1L + i, () -> {
                if (!gate.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("gate never opened");
                return new byte[0];
            });
        }
        assertTrue(reader.hasHeadroom(), "31 of 32 queue slots used: one submit still fits");

        reader.submit(player, 32, 0, 33L, () -> {
            if (!gate.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("gate never opened");
            return new byte[0];
        });
        assertFalse(reader.hasHeadroom(), "queue exactly full: the router must not submit");
        assertEquals(0, reader.getDiag().getSaturationCount(), "...and nothing has bounced yet");

        // The gate is honest: a submit made anyway is genuinely rejected.
        reader.submit(player, 99, 88, 999L, () -> new byte[0]);
        assertEquals(1, reader.getDiag().getSaturationCount(),
                "hasHeadroom()==false must mean the pool really would reject");

        gate.countDown();
        awaitResults(34); // 33 gated reads + the saturated bounce already queued
        assertTrue(reader.hasHeadroom(), "a drained pool has headroom again");
    }

    @Test
    void adaptiveThrottleNarrowsHasHeadroomWhenEngagedAndRecoversAtLowLatency() throws Exception {
        // Default (working-A path): the throttle is null, so hasHeadroom() is purely the queue
        // check — an idle pool with free slots always has headroom, and the diag reports "off".
        assertEquals(-1, reader.adaptiveThrottleLimitOrDisabled(), "throttle is off until A is found incompatible");
        assertTrue(reader.hasHeadroom(), "idle pool, no throttle: headroom");

        // A-incompatibility engages the fallback. The throttle starts optimistic at the pool's full
        // depth (1 thread * (1 + 32) = 33), so enabling it alone must NOT restrict admission — a
        // fresh fallback is exactly as permissive as the pool it wraps.
        reader.enableThrottle();
        var throttle = reader.throttleForTest();
        assertNotNull(throttle, "enable installs the throttle");
        assertEquals(33, reader.adaptiveThrottleLimitOrDisabled(), "throttle starts at the pool ceiling");
        assertTrue(reader.hasHeadroom(), "engaged but un-collapsed: still headroom on an idle pool");

        // Sustained over-target latency (50ms >> the 20ms setpoint) is the shared-IO-busy signal.
        // AIMD (*0.7 per sample) collapses the limit to its floor of 1 within a handful of samples.
        // Fed synthetically so the assertion does not depend on real read timing.
        for (int i = 0; i < 20; i++) throttle.recordLatency(50L * 1_000_000L);
        assertEquals(1, reader.adaptiveThrottleLimitOrDisabled(), "sustained congestion collapses to the floor");

        // With the limit at 1 and one read genuinely in flight, hasHeadroom() is false EVEN THOUGH the
        // pool queue still has 32 free slots — the throttle, not the pool, is retaining the read (the
        // want-set router's NO_DISK_HEADROOM path, healed by the client's 1 Hz re-declaration).
        var started = new CountDownLatch(1);
        var gate = new CountDownLatch(1);
        reader.submit(player, 0, 0, 1L, () -> {
            started.countDown();
            if (!gate.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("gate never opened");
            return new byte[0];
        });
        assertTrue(started.await(5, TimeUnit.SECONDS), "the single read occupies the worker");
        // Only one read was submitted and it is running on the single worker (not queued), so the
        // 32-slot queue is provably not full — any headroom denial here is the throttle's doing.
        assertFalse(reader.hasHeadroom(),
                "throttle limit 1, one read in flight: admission narrowed despite free queue slots");

        // Low-latency samples (1ms << 20ms) walk the smoothed signal back under target and the limit
        // climbs additively; once it exceeds the one in-flight read, admission reopens — same in-flight
        // count, only the limit changed. (The EWMA lag means several good samples are needed first.)
        for (int i = 0; i < 10; i++) throttle.recordLatency(1L * 1_000_000L);
        assertTrue(reader.adaptiveThrottleLimitOrDisabled() > 1, "low latency recovers the limit off the floor");
        assertTrue(reader.hasHeadroom(),
                "recovered limit now exceeds the one in-flight read: admission reopened");

        gate.countDown();
        awaitResults(1);
    }

    @Test
    void getDiagnosticsAppendsThrottleStateOnlyWhenEngaged() {
        // Working-A path: the throttle is null, so the DiskReader line is byte-identical to before
        // (goldens do not move) and carries no throttle marker.
        String off = reader.getDiagnostics();
        assertFalse(off.contains("read_throttle"), "no throttle marker on the working-A path: " + off);

        // Fallback engaged: the line gains a compact ENGAGED(limit/max) suffix so an operator (and
        // the Task 7 manual C2ME smoke test) can SEE the fallback — the fallback's only end-to-end
        // signal, since no automated test reaches the C2ME path.
        reader.enableThrottle();
        String on = reader.getDiagnostics();
        assertTrue(on.startsWith(off), "the engaged line only appends to the base diagnostics: " + on);
        assertTrue(on.contains("read_throttle=ENGAGED(33/33)"),
                "engaged line shows the current/max limit (fresh throttle starts at the pool ceiling): " + on);
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
