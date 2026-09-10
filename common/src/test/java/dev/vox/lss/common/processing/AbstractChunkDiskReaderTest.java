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

        TestDiskReader(int threads) { super(threads); }

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
    void errorThrowingReadDeliversExactlyOneResultAndPoolSurvives() throws Exception {
        // An Error is contained by the op-region catch(Throwable) with full bookkeeping
        // and NO re-throw (a re-throw would reach the submit lambda's last-resort catch
        // and deliver a second result — the one-result-per-submit envelope forbids it).
        // The barrier completing proves the worker pool survived.
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

    // ---- Disk-read concurrency gate (disk-read-concurrency-gate-plan.md) ----

    /** Minimal store stub for the gate seam tests: answers what it is told to. */
    private static final class GateStubStore implements dev.vox.lss.common.store.LodStoreService {
        final dev.vox.lss.common.store.LodStoreDiagnostics sdiag =
                new dev.vox.lss.common.store.LodStoreDiagnostics();
        volatile dev.vox.lss.common.store.LodStoreService.StoreHit answer;

        @Override public dev.vox.lss.common.store.LodStoreMode mode() {
            return dev.vox.lss.common.store.LodStoreMode.FULL;
        }
        @Override public StoreHit get(String dimension, long packed) { return this.answer; }
        @Override public boolean deposit(String d, long p, byte[] b, long ts, long acq) { return true; }
        @Override public void invalidate(String d, long[] p) {}
        @Override public void delete(String d, long p) {}
        @Override public dev.vox.lss.common.store.LodStoreDiagnostics diagnostics() { return this.sdiag; }
        @Override public void shutdown() {}
    }

    /**
     * The load-bearing seam property: with the gate pegged (K=1 held by a blocked read),
     * store HITS keep flowing (they never consume a permit) while store MISSES PARK —
     * no result until the permit releases, then the parked read RUNS (real data, no
     * drop). Counted into {@code submitted} only when it actually runs; {@code gated}
     * stays 0 (it counts park-OVERFLOW bounces only, covered by the next test).
     */
    @Test
    void peggedGateParksMissesWhileStoreHitsKeepFlowingAndDrainsOnRelease() throws Exception {
        var gated = new TestDiskReader(2);
        gated.registerPlayer(player);
        try {
            gated.configureReadGate(1);
            var store = new GateStubStore(); // answer=null: every lookup is a miss
            gated.attachStore(store);

            var opStarted = new CountDownLatch(1);
            var holdOpen = new CountDownLatch(1);
            gated.submit(player, 0, 0, 1L, () -> {
                opStarted.countDown();
                if (!holdOpen.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
                return new byte[]{1};
            });
            assertTrue(opStarted.await(5, TimeUnit.SECONDS), "the permit-holding read must start");

            // Store miss while pegged: PARKS (delivers nothing yet).
            store.answer = null;
            var parkedRan = new CountDownLatch(1);
            gated.submit(player, 2, 0, 3L, () -> {
                parkedRan.countDown();
                return new byte[]{7};
            });
            var q = gated.getPlayerQueue(player);
            assertFalse(parkedRan.await(300, TimeUnit.MILLISECONDS),
                    "the parked miss must NOT run while the permit is held");
            assertNull(q.poll(), "a parked read delivers no result while parked");

            // Store hit while pegged AND with a read parked: still served immediately.
            store.answer = new dev.vox.lss.common.store.LodStoreService.StoreHit(new byte[]{9}, 42L);
            gated.submit(player, 1, 0, 2L, () -> {
                throw new AssertionError("a store hit must not run the NBT op");
            });
            ChunkReadResult hit = null;
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (hit == null && System.nanoTime() < deadline) { hit = q.poll(); }
            assertNotNull(hit, "the store hit must be served while the gate is pegged");
            assertTrue(hit.fromStore());
            assertEquals(0, gated.getDiag().getGatedCount(),
                    "parking is not a bounce — gated counts overflow only");

            // Release: the parked read drains on the freed permit and delivers REAL data.
            holdOpen.countDown();
            assertTrue(parkedRan.await(5, TimeUnit.SECONDS),
                    "the release must feed the parked read to the freed permit");
            var rest = new ArrayList<ChunkReadResult>();
            deadline = System.nanoTime() + 5_000_000_000L;
            while (rest.size() < 2 && System.nanoTime() < deadline) {
                var r = q.poll();
                if (r != null) rest.add(r);
            }
            assertEquals(2, rest.size(), "the held read AND the parked read both deliver");
            assertTrue(rest.stream().noneMatch(ChunkReadResult::saturated),
                    "neither is a drop — the parked read produced real data");
            assertEquals(2, gated.getDiag().getSubmittedCount(),
                    "both expensive reads entered the NBT path exactly once");
            assertEquals(0, gated.getDiag().getSaturationCount());
            assertEquals(0, gated.getDiag().getGatedCount());
        } finally {
            gated.shutdown();
        }
    }

    /**
     * Park OVERFLOW is the RACE-ARMOR bounce (Amendment 2 demoted it from the primary
     * pressure valve — the router's retention conjunct now holds sustained pressure
     * upstream, and this direct-submit-past-a-pegged-permit shape is one the router
     * prevents by construction; it remains reachable by in-flight races): with the
     * permit held and the park list full (threads×32 = 64 for this reader), the next
     * miss delivers the saturated flavor — counted {@code gated}, never {@code
     * submitted}/{@code saturated} — which the processor's existing silent-superseded
     * routing consumes.
     */
    @Test
    void parkOverflowBouncesAsSaturatedFlavorAndCountsGated() throws Exception {
        var gated = new TestDiskReader(2);
        gated.registerPlayer(player);
        try {
            gated.configureReadGate(1);
            var store = new GateStubStore(); // every lookup is a miss
            gated.attachStore(store);

            var opStarted = new CountDownLatch(1);
            var holdOpen = new CountDownLatch(1);
            gated.submit(player, 0, 0, 1L, () -> {
                opStarted.countDown();
                if (!holdOpen.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
                return new byte[]{1};
            });
            assertTrue(opStarted.await(5, TimeUnit.SECONDS));

            // Fill the park list to capacity (64), then one more: the 65th bounces.
            // Spin on pool headroom between submits — the free worker parks each task in
            // µs, but a starved test runner could otherwise outrun it into the 64-slot
            // pool queue and trip a SUBMIT-site saturation instead of the gate's bounce.
            int parkCapacity = 2 * 32;
            for (int i = 1; i <= parkCapacity + 1; i++) {
                long spinDeadline = System.nanoTime() + 5_000_000_000L;
                while (!gated.hasHeadroom() && System.nanoTime() < spinDeadline) {
                    Thread.onSpinWait();
                }
                gated.submit(player, i, 1, 10L + i, () -> new byte[]{2});
            }
            var q = gated.getPlayerQueue(player);
            ChunkReadResult bounce = null;
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (bounce == null && System.nanoTime() < deadline) { bounce = q.poll(); }
            assertNotNull(bounce, "the overflow miss must deliver the bounce");
            assertTrue(bounce.saturated(), "the bounce reuses the saturated flavor");
            assertEquals(1, gated.getDiag().getGatedCount(), "exactly the overflow counted gated");
            assertEquals(1, gated.getDiag().getSubmittedCount(),
                    "only the permit holder entered the NBT path so far");
            assertEquals(0, gated.getDiag().getSaturationCount(),
                    "disk.saturated is the SUBMIT-site pool bounce, never the gate's");

            // Release: all 64 parked reads drain and deliver real data.
            holdOpen.countDown();
            int expected = 1 + parkCapacity; // the held read + every parked read
            var results = new ArrayList<ChunkReadResult>();
            deadline = System.nanoTime() + 10_000_000_000L;
            while (results.size() < expected && System.nanoTime() < deadline) {
                var r = q.poll();
                if (r != null) results.add(r);
            }
            assertEquals(expected, results.size(), "every parked read drains after release");
            assertTrue(results.stream().noneMatch(ChunkReadResult::saturated));
            assertEquals(1, gated.getDiag().getGatedCount(), "no further bounces during the drain");
        } finally {
            gated.shutdown();
        }
    }

    /**
     * Concurrent park/drain hammer (review B-6 — the class of race the poll-null
     * `continue` fix closes): 4 workers race 1 permit over a few thousand store-miss
     * submits from concurrent submitters. Conservation envelope: EXACTLY one result per
     * submit (a stranded parked entry times the await out; a double-drained one
     * overshoots), and the gate is fully at rest afterwards (no held permit, no parked
     * entry). Ops are trivially fast so park/drain interleavings churn maximally.
     */
    @Test
    void concurrentParkDrainHammerDeliversExactlyOneResultPerSubmit() throws Exception {
        var gated = new TestDiskReader(4);
        gated.registerPlayer(player);
        try {
            gated.configureReadGate(1);
            var store = new GateStubStore(); // every lookup is a miss
            gated.attachStore(store);

            final int perSubmitter = 600;
            final int submitters = 3;
            var submittersDone = new CountDownLatch(submitters);
            for (int s = 0; s < submitters; s++) {
                final int base = s * perSubmitter;
                new Thread(() -> {
                    try {
                        for (int i = 0; i < perSubmitter; i++) {
                            long spin = System.nanoTime() + 10_000_000_000L;
                            while (!gated.hasHeadroom() && System.nanoTime() < spin) {
                                Thread.onSpinWait();
                            }
                            gated.submit(player, base + i, 7, base + i, () -> new byte[]{3});
                        }
                    } finally {
                        submittersDone.countDown();
                    }
                }).start();
            }
            assertTrue(submittersDone.await(30, TimeUnit.SECONDS), "submitters finished");

            int expected = submitters * perSubmitter;
            var q = gated.getPlayerQueue(player);
            int got = 0;
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (got < expected && System.nanoTime() < deadline) {
                if (q.poll() != null) got++;
                else Thread.onSpinWait();
            }
            assertEquals(expected, got, "exactly one result per submit — no strand, no double");
            // Let any trailing drain step settle, then assert the gate is at rest.
            deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline
                    && !gated.getDiagnostics().contains("read_gate=0/1, gate_parked=0")) {
                Thread.onSpinWait();
            }
            assertTrue(gated.getDiagnostics().contains("read_gate=0/1, gate_parked=0"),
                    "gate at rest after the hammer: " + gated.getDiagnostics());
            assertNull(q.poll(), "no extra results after the count");
        } finally {
            gated.shutdown();
        }
    }

    /** Release-at-triage: a read that THROWS TimeoutException (the future.get shape)
     *  releases its permit at error triage — the next read must acquire it, even though
     *  a real orphaned fetch would still be running downstream OUTSIDE the permit. */
    @Test
    void timeoutTriageReleasesThePermitSoTheNextReadProceeds() throws Exception {
        var gated = new TestDiskReader(1);
        gated.registerPlayer(player);
        try {
            gated.configureReadGate(1);
            gated.submit(player, 5, 5, 1L, () -> {
                throw new java.util.concurrent.TimeoutException("simulated 10s expiry");
            });
            var q = gated.getPlayerQueue(player);
            ChunkReadResult timedOut = null;
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (timedOut == null && System.nanoTime() < deadline) { timedOut = q.poll(); }
            assertNotNull(timedOut);
            assertTrue(timedOut.notFound(), "timeout triages down the not-found ladder");

            gated.submit(player, 6, 6, 2L, () -> new byte[]{1, 2});
            ChunkReadResult next = null;
            deadline = System.nanoTime() + 5_000_000_000L;
            while (next == null && System.nanoTime() < deadline) { next = q.poll(); }
            assertNotNull(next, "the permit was released at triage — the next read runs");
            assertFalse(next.notFound());
            assertEquals(0, gated.getDiag().getGatedCount(),
                    "no bounce occurred — both reads acquired the single permit in turn");
        } finally {
            gated.shutdown();
        }
    }

    /** The read_gate diag token is ALWAYS rendered (the live-deploy log's config-era
     *  receipt): in-use/K, the park gauge, the retention-stop counter (Amendment 2 —
     *  mechanism before armor), then the monotonic overflow counter last. */
    @Test
    void getDiagnosticsRendersTheReadGateToken() {
        reader.configureReadGate(3);
        String line = reader.getDiagnostics();
        assertTrue(line.contains(", read_gate=0/3, gate_parked=0, gate_stops=0, gated=0"),
                "the gate token must render even while the gate is a no-op: " + line);
    }

    /**
     * The saturation predicate's live truth table (Amendment 2): pegged permits ALONE
     * are not saturation (a permit holder in flight contributes nothing permit-less);
     * a FULL park with the permit held reads saturated; the drain clears it, every
     * parked read delivers real data, and the overflow counter never moves — retention
     * has no drop tier of its own.
     */
    @Test
    void gateSaturationPredicateBindsAtParkFullAndClearsOnDrain() throws Exception {
        var gated = new TestDiskReader(2);
        gated.registerPlayer(player);
        try {
            gated.configureReadGate(1);
            var store = new GateStubStore(); // answer=null: every lookup is a miss
            gated.attachStore(store);
            assertFalse(gated.gateSaturated(), "an idle gate is never saturated");

            var opStarted = new CountDownLatch(1);
            var holdOpen = new CountDownLatch(1);
            gated.submit(player, 0, 0, 1L, () -> {
                opStarted.countDown();
                if (!holdOpen.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
                return new byte[]{1};
            });
            assertTrue(opStarted.await(5, TimeUnit.SECONDS), "the permit-holding read must start");
            assertFalse(gated.gateSaturated(),
                    "pegged permits with an empty park are NOT saturation — the holder "
                            + "is not permit-less work");

            // Fill the park exactly to capacity (threads*32 = 64): each miss parks.
            for (int i = 1; i <= 64; i++) {
                gated.submit(player, i, 7, 100L + i, () -> new byte[]{2});
            }
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (!gated.gateSaturated() && System.nanoTime() < deadline) Thread.sleep(2);
            assertTrue(gated.gateSaturated(),
                    "permit held + park at capacity must read saturated");
            assertEquals(0, gated.getDiag().getGatedCount(),
                    "an exactly-full park has overflowed nothing");
            assertNull(gated.getPlayerQueue(player).poll(),
                    "parked reads deliver nothing while parked — no drops either");

            // Release: the park drains on the freed permit; saturation self-clears.
            holdOpen.countDown();
            var results = new ArrayList<ChunkReadResult>();
            deadline = System.nanoTime() + 30_000_000_000L;
            while (results.size() < 65 && System.nanoTime() < deadline) {
                var r = gated.getPlayerQueue(player).poll();
                if (r != null) results.add(r); else Thread.sleep(2);
            }
            assertEquals(65, results.size(), "the holder AND all 64 parked reads deliver");
            assertTrue(results.stream().noneMatch(ChunkReadResult::saturated),
                    "retention has no drop tier — every parked read produced real data");
            assertFalse(gated.gateSaturated(), "a drained park clears saturation");
            assertEquals(0, gated.getDiag().getGatedCount());
        } finally {
            gated.shutdown();
        }
    }

    /**
     * The K = pool structural-false pin (Amendment 2 revision — the carrier of every
     * no-op scenario baseline): with the gate at pool size, queued pool work must
     * never read saturated, because the permit-less term counts only work BEYOND the
     * permit holders and the park stays pigeonhole-empty (a classifying thread always
     * finds a permit). A bare tasksInFlight term would flip this true at
     * queue-nearly-full and shift the disk-saturation baseline.
     */
    @Test
    void gateSaturationIsStructurallyFalseAtPoolK() throws Exception {
        var r = new TestDiskReader(1); // K = pool = 1 (the ctor default), no store
        r.registerPlayer(player);
        try {
            var opStarted = new CountDownLatch(1);
            var holdOpen = new CountDownLatch(1);
            r.submit(player, 0, 0, 1L, () -> {
                opStarted.countDown();
                if (!holdOpen.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
                return new byte[]{1};
            });
            assertTrue(opStarted.await(5, TimeUnit.SECONDS));

            // Queue 31 more (capacity 32): one slot free, so hasHeadroom still passes —
            // exactly the state the router would consult the gate in.
            for (int i = 1; i <= 31; i++) {
                r.submit(player, i, 9, 200L + i, () -> new byte[]{3});
            }
            assertTrue(r.hasHeadroom(), "premise: the router would reach the gate check");
            assertFalse(r.gateSaturated(),
                    "queued work at K = pool must NEVER read saturated (the permit-less "
                            + "term excludes the holder; the park is pigeonhole-empty)");

            holdOpen.countDown();
            var results = new ArrayList<ChunkReadResult>();
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (results.size() < 32 && System.nanoTime() < deadline) {
                var res = r.getPlayerQueue(player).poll();
                if (res != null) results.add(res); else Thread.sleep(2);
            }
            assertEquals(32, results.size(), "everything drains normally");
            assertEquals(0, r.getDiag().getGatedCount());
        } finally {
            r.shutdown();
        }
    }

    /**
     * The multi-thread K = pool discriminator (3-Opus round MINOR-4): all four pool
     * threads blocked HOLDING permits (inUse == cap) with the queue deep — 124 of 128
     * — so {@code hasHeadroom()} still passes. The permit-less term (tasksInFlight −
     * inUse = 124 queued) stays below the park bound (128) → never saturated. A bare
     * {@code tasksInFlight} term reads 128 ≥ 128 here and flips TRUE — the exact
     * regression that would shift the disk-saturation baseline.
     */
    @Test
    void gateSaturationStaysFalseAtPoolKWithAllPermitsHeldAndADeepQueue() throws Exception {
        var r = new TestDiskReader(4); // K = pool = 4 (ctor default)
        r.registerPlayer(player);
        try {
            var started = new CountDownLatch(4);
            var holdOpen = new CountDownLatch(1);
            for (int i = 0; i < 4; i++) {
                r.submit(player, i, 0, 1L + i, () -> {
                    started.countDown();
                    if (!holdOpen.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
                    return new byte[]{1};
                });
            }
            assertTrue(started.await(5, TimeUnit.SECONDS), "all four holders must start");
            for (int i = 10; i < 134; i++) { // 124 queued behind the blocked pool
                r.submit(player, i, 3, 100L + i, () -> new byte[]{2});
            }
            assertTrue(r.hasHeadroom(), "premise: 124/128 queued — the router would consult the gate");
            assertFalse(r.gateSaturated(),
                    "K = pool with every permit held and a deep queue must stay unsaturated "
                            + "— only permit-LESS work may count toward the park bound");

            holdOpen.countDown();
            var results = new ArrayList<ChunkReadResult>();
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (results.size() < 128 && System.nanoTime() < deadline) {
                var res = r.getPlayerQueue(player).poll();
                if (res != null) results.add(res); else Thread.sleep(2);
            }
            assertEquals(128, results.size(), "everything drains normally");
            assertEquals(0, r.getDiag().getGatedCount());
        } finally {
            r.shutdown();
        }
    }

    /**
     * The gate-stop plumbing + episode-detector pins (3-Opus round: the router test
     * stubs {@code recordGateStop}, so without this the real chain — processor →
     * reader → diag → diag token — and the WARN latch had zero Tier 1 coverage; a
     * broken chain would print gate_stops=0 forever while the soak red pointed at
     * the scenario config).
     */
    @Test
    void recordGateStopFeedsDiagAndLatchesTheWarnOnlyOnASustainedEpisode() {
        reader.recordGateStop();
        assertEquals(1, reader.getDiag().getGateStopsCount(),
                "a recorded stop lands in the reader's diagnostics");
        assertTrue(reader.getDiagnostics().contains("gate_stops=1"),
                "and renders in the always-on diag token");
        assertFalse(reader.gateStopWarnLatchedForTest(), "one stop never latches");

        // Drive the episode detector through the clock seam. A dense sub-second BLIP
        // (the 10-player 2-tick shape that falsely latched the cumulative counter)
        // must not latch, however many stops it books.
        long t = System.nanoTime() + 10_000_000_000L; // fresh episode (>1 s after the real stop)
        for (int i = 0; i < 50; i++) {
            reader.recordGateStop(t);
            t += 10_000_000L; // 10 ms apart — 0.5 s span
        }
        assertFalse(reader.gateStopWarnLatchedForTest(),
                "a dense sub-second blip must NOT latch the sustained-load WARN");

        // A >1 s quiet gap ends the episode; the next one must start from zero.
        t += 5_000_000_000L;
        for (int i = 0; i < 25; i++) {
            reader.recordGateStop(t);
            t += 100_000_000L; // 100 ms apart — crosses 3 s highly sustained
        }
        assertFalse(reader.gateStopWarnLatchedForTest(),
                "2.4 s of sustained stops is still below the 3 s episode bar");
        for (int i = 0; i < 10; i++) {
            reader.recordGateStop(t);
            t += 100_000_000L;
        }
        assertTrue(reader.gateStopWarnLatchedForTest(),
                "stops spanning >= 3 s with <= 1 s gaps latch the once-per-session WARN");
        assertEquals(86, reader.getDiag().getGateStopsCount(),
                "every stop counted regardless of the latch");
    }

    /** Minimal concrete config for the reapply seam (only maxConcurrentDiskReads and
     *  the store-conditional AUTO resolver are consulted). */
    public static class GateReapplyConfig extends dev.vox.lss.common.config.ServerConfigBase {}

    /** v0.11.0 stage C (review F8): the tick-poll hop `/lsslod set maxConcurrentDiskReads`
     *  rides — reapplyGateCapacity(config) must move the LIVE gate's capacity, resolve
     *  0=AUTO against the reader's OWN pool + post-degrade store fact (store attached →
     *  ceil(pool/2), store-less → pool), and no-op on an unchanged K. */
    @Test
    void reapplyGateCapacityResolvesConfigAgainstPoolAndStoreState() {
        var r = new TestDiskReader(4);
        try {
            var config = new GateReapplyConfig();
            config.maxConcurrentDiskReads = 2;
            r.reapplyGateCapacity(config);
            assertEquals(2, r.readGateCapacity(), "an explicit K reaches the live gate");

            config.maxConcurrentDiskReads = 0; // AUTO, no store attached -> whole pool
            r.reapplyGateCapacity(config);
            assertEquals(4, r.readGateCapacity(), "AUTO without a store = the whole pool");

            r.attachStore(new GateStubStore());
            r.reapplyGateCapacity(config); // AUTO, store attached -> ceil(4/2)
            assertEquals(2, r.readGateCapacity(), "AUTO with a store = half the pool");

            config.maxConcurrentDiskReads = 64; // above pool: resolver clamps to pool
            r.reapplyGateCapacity(config);
            assertEquals(4, r.readGateCapacity(), "K never exceeds the pool");
        } finally {
            r.shutdown();
        }
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
