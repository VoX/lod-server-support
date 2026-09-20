package dev.vox.lss.common.processing;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure gate semantics (disk-read-concurrency-gate-plan.md): capacity enforcement, CAS
 * correctness under concurrent acquirers, and the R-2 volatile-capacity shape — stage B
 * ships the boot-configured gate, stage C the runtime mutation, so the
 * lower-while-permits-held contract is pinned HERE, before any command can reach it.
 * The reader-seam accounting (gated counter, saturated-flavor bounce, store-hit
 * exemption) lives in {@link AbstractChunkDiskReaderTest}.
 */
class DiskReadGateTest {

    @Test
    void capacityBoundsAcquiresAndReleaseReopens() {
        var gate = new DiskReadGate(2);
        assertTrue(gate.tryAcquire());
        assertTrue(gate.tryAcquire());
        assertEquals(2, gate.inUse());
        assertFalse(gate.tryAcquire(), "a full gate must refuse without blocking");
        assertEquals(2, gate.inUse(), "a refused acquire must not count as held");
        gate.release();
        assertEquals(1, gate.inUse());
        assertTrue(gate.tryAcquire(), "a released permit is immediately reusable");
    }

    @Test
    void concurrentAcquirersNeverExceedCapacity() throws Exception {
        final int capacity = 3;
        final int threads = 12;
        var gate = new DiskReadGate(capacity);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var held = new AtomicInteger();
        var maxHeld = new AtomicInteger();
        var acquired = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int round = 0; round < 500; round++) {
                        if (gate.tryAcquire()) {
                            acquired.incrementAndGet();
                            int now = held.incrementAndGet();
                            maxHeld.accumulateAndGet(now, Math::max);
                            held.decrementAndGet();
                            gate.release();
                        }
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(maxHeld.get() <= capacity,
                "held permits exceeded capacity under contention: " + maxHeld.get());
        assertTrue(acquired.get() > 0, "the race must actually exercise acquires");
        assertEquals(0, gate.inUse(), "every acquire was paired with a release");
    }

    /** R-2's lowering semantics, pinned at stage B (before the stage-C command exists):
     *  in-use may transiently exceed a lowered capacity until permits drain — new
     *  acquisitions refuse meanwhile, release never goes negative, no permit sticks. */
    @Test
    void loweringCapacityBelowInUseRefusesNewAcquiresUntilPermitsDrain() {
        var gate = new DiskReadGate(3);
        assertTrue(gate.tryAcquire());
        assertTrue(gate.tryAcquire());
        assertTrue(gate.tryAcquire());

        gate.updateCapacity(1);
        assertEquals(3, gate.inUse(), "held permits survive the lowering — nothing is revoked");
        assertFalse(gate.tryAcquire(), "above the new K: new acquisitions refuse");

        gate.release();
        gate.release();
        assertEquals(1, gate.inUse());
        assertFalse(gate.tryAcquire(), "still AT the new K — the transient has drained to it");
        gate.release();
        assertEquals(0, gate.inUse(), "the counter drains to zero, never negative");
        assertTrue(gate.tryAcquire(), "below the new K the gate reopens — no permit stuck");
        gate.release();

        // Raising binds immediately.
        gate.updateCapacity(2);
        assertTrue(gate.tryAcquire());
        assertTrue(gate.tryAcquire());
        assertFalse(gate.tryAcquire());
    }

    @Test
    void releaseFloorGuardsAgainstDoubleReleaseWedgingTheCounter() {
        var gate = new DiskReadGate(1);
        gate.release(); // unpaired — a bug upstream, but must not wedge the gate shut
        assertEquals(0, gate.inUse(), "the floor guard keeps the counter at zero");
        assertTrue(gate.tryAcquire(), "the gate stays usable after an unpaired release");
        assertFalse(gate.tryAcquire(), "capacity still enforced exactly");
    }
}
