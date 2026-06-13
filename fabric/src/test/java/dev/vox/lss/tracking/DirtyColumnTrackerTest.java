package dev.vox.lss.tracking;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DirtyColumnTrackerTest {
    private DirtyColumnTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new DirtyColumnTracker();
    }

    @Test
    void markThenDrainReturnsPositions() {
        tracker.markDirty(LSSConstants.DIM_STR_OVERWORLD, 10, 20);
        long[] result = tracker.drainDirty(LSSConstants.DIM_STR_OVERWORLD);
        assertNotNull(result);
        assertEquals(1, result.length);
    }

    @Test
    void secondDrainReturnsNull() {
        tracker.markDirty(LSSConstants.DIM_STR_OVERWORLD, 1, 2);
        tracker.drainDirty(LSSConstants.DIM_STR_OVERWORLD);
        assertNull(tracker.drainDirty(LSSConstants.DIM_STR_OVERWORLD));
    }

    @Test
    void unknownDimensionReturnsNull() {
        assertNull(tracker.drainDirty(LSSConstants.DIM_STR_THE_NETHER));
    }

    @Test
    void multiplePositionsAllReturned() {
        tracker.markDirty(LSSConstants.DIM_STR_OVERWORLD, 1, 1);
        tracker.markDirty(LSSConstants.DIM_STR_OVERWORLD, 2, 2);
        tracker.markDirty(LSSConstants.DIM_STR_OVERWORLD, 3, 3);
        long[] result = tracker.drainDirty(LSSConstants.DIM_STR_OVERWORLD);
        assertNotNull(result);
        assertEquals(3, result.length);
    }

    @Test
    void separateDimensionsIsolated() {
        tracker.markDirty(LSSConstants.DIM_STR_OVERWORLD, 5, 5);
        tracker.markDirty(LSSConstants.DIM_STR_THE_NETHER, 6, 6);
        long[] a = tracker.drainDirty(LSSConstants.DIM_STR_OVERWORLD);
        long[] b = tracker.drainDirty(LSSConstants.DIM_STR_THE_NETHER);
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(1, a.length);
        assertEquals(1, b.length);
    }

    @Test
    void duplicatePositionDeduped() {
        tracker.markDirty(LSSConstants.DIM_STR_OVERWORLD, 5, 5);
        tracker.markDirty(LSSConstants.DIM_STR_OVERWORLD, 5, 5);
        long[] result = tracker.drainDirty(LSSConstants.DIM_STR_OVERWORLD);
        assertNotNull(result);
        assertEquals(1, result.length);
    }

    // ---- SP-064: a mark racing a drain lands in this drain or the next, never lost ----

    @Test
    void concurrentMarkDuringDrainNeverLosesAPositionOrThrows() throws InterruptedException {
        final int n = 4000;
        var drained = new java.util.concurrent.atomic.AtomicInteger();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();

        var marker = new Thread(() -> {
            try {
                for (int i = 0; i < n; i++) tracker.markDirty(LSSConstants.DIM_STR_OVERWORLD, i, 0);
            } catch (Throwable t) { failure.set(t); }
        });
        var drainer = new Thread(() -> {
            try {
                for (int i = 0; i < n * 2; i++) {
                    long[] d = tracker.drainDirty(LSSConstants.DIM_STR_OVERWORLD);
                    if (d != null) drained.addAndGet(d.length);
                }
            } catch (Throwable t) { failure.set(t); }
        });

        marker.start();
        drainer.start();
        marker.join();
        drainer.join();
        // Final drain absorbs anything marked after the drainer's last poll.
        long[] tail = tracker.drainDirty(LSSConstants.DIM_STR_OVERWORLD);
        if (tail != null) drained.addAndGet(tail.length);

        assertNull(failure.get(), "neither thread threw under the race");
        assertEquals(n, drained.get(), "every distinct marked position is drained exactly once");
        assertEquals(n, tracker.getTotalMarked());
        assertEquals(n, tracker.getTotalDrained());
    }
}
