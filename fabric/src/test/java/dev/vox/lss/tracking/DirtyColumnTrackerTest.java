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
}
