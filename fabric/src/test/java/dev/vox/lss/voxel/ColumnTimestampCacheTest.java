package dev.vox.lss.voxel;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.voxel.ColumnTimestampCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ColumnTimestampCacheTest {
    private ColumnTimestampCache cache;
    private long now;

    @BeforeEach
    void setUp() {
        cache = new ColumnTimestampCache();
        now = LSSConstants.epochSeconds();
    }

    @Test
    void putAndGet() {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 100L, now);
        assertEquals(100L, cache.get(LSSConstants.DIM_STR_OVERWORLD, 1L));
    }

    @Test
    void getMissingReturnsZero() {
        assertEquals(0L, cache.get(LSSConstants.DIM_STR_OVERWORLD, 999L));
    }

    @Test
    void getMissingDimensionReturnsZero() {
        assertEquals(0L, cache.get(LSSConstants.DIM_STR_THE_NETHER, 1L));
    }

    @Test
    void invalidateRemovesEntries() {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 100L, now);
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 2L, 200L, now);
        cache.invalidate(LSSConstants.DIM_STR_OVERWORLD, new long[]{1L});
        assertEquals(0L, cache.get(LSSConstants.DIM_STR_OVERWORLD, 1L));
        assertEquals(200L, cache.get(LSSConstants.DIM_STR_OVERWORLD, 2L));
    }

    @Test
    void invalidateCleansInsertionTimeToo() {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 100L, now);
        cache.invalidate(LSSConstants.DIM_STR_OVERWORLD, new long[]{1L});
        assertEquals(1, cache.size() == 0 ? 1 : 0, "size should be 0 after invalidation");
        assertEquals(0, cache.size());
        // Re-insert — should work cleanly with no stale insertion time
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 200L, now);
        assertEquals(200L, cache.get(LSSConstants.DIM_STR_OVERWORLD, 1L));
        assertEquals(1, cache.size());
    }

    @Test
    void sizeCountsAcrossDimensions() {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 100L, now);
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 2L, 200L, now);
        cache.put(LSSConstants.DIM_STR_THE_NETHER, 3L, 300L, now);
        assertEquals(3, cache.size());
    }

    @Test
    void evictOlderThanRemovesStaleEntries() {
        // Insert entries — they'll all get "now" as insertion time
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 100L, now);
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 2L, 200L, now);
        cache.put(LSSConstants.DIM_STR_THE_NETHER, 3L, 300L, now);

        // Evicting with maxAge=0 should remove everything (all entries are >= 0 seconds old)
        int evicted = cache.evictOlderThan(0);
        assertEquals(3, evicted);
        assertEquals(0, cache.size());
        assertEquals(0L, cache.get(LSSConstants.DIM_STR_OVERWORLD, 1L));
        assertEquals(0L, cache.get(LSSConstants.DIM_STR_OVERWORLD, 2L));
        assertEquals(0L, cache.get(LSSConstants.DIM_STR_THE_NETHER, 3L));
    }

    @Test
    void evictOlderThanKeepsRecentEntries() {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 100L, now);
        // With a generous TTL, nothing should be evicted since entries were just inserted
        int evicted = cache.evictOlderThan(3600);
        assertEquals(0, evicted);
        assertEquals(1, cache.size());
        assertEquals(100L, cache.get(LSSConstants.DIM_STR_OVERWORLD, 1L));
    }

    @Test
    void evictOnEmptyCacheReturnsZero() {
        assertEquals(0, cache.evictOlderThan(0));
    }
}
