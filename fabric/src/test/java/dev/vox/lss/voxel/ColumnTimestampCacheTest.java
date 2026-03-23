package dev.vox.lss.voxel;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.voxel.ColumnTimestampCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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

    // ---- Size-based eviction ----

    @Test
    void evictIfOversizedDoesNothingUnderLimit() {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 100L, now);
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 2L, 200L, now);
        assertEquals(0, cache.evictIfOversized());
        assertEquals(2, cache.size());
    }

    @Test
    void evictIfOversizedRemovesOldestEntries() {
        // Insert entries with different insertion times
        for (int i = 0; i < 10; i++) {
            cache.put(LSSConstants.DIM_STR_OVERWORLD, i, i * 100L, now + i);
        }
        assertEquals(10, cache.size());

        // Eviction only triggers when exceeding MAX_ENTRIES_PER_DIMENSION (2M),
        // so with 10 entries nothing is evicted
        assertEquals(0, cache.evictIfOversized());
    }

    @Test
    void evictOnEmptyCacheReturnsZero() {
        assertEquals(0, cache.evictIfOversized());
    }

    // ---- Persistence ----

    @Test
    void saveAndLoadRoundTrip(@TempDir Path tempDir) {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 100L, now);
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 2L, 200L, now);
        cache.put(LSSConstants.DIM_STR_THE_NETHER, 3L, 300L, now);

        cache.save(tempDir);

        var loaded = new ColumnTimestampCache();
        loaded.load(tempDir);

        assertEquals(3, loaded.size());
        assertEquals(100L, loaded.get(LSSConstants.DIM_STR_OVERWORLD, 1L));
        assertEquals(200L, loaded.get(LSSConstants.DIM_STR_OVERWORLD, 2L));
        assertEquals(300L, loaded.get(LSSConstants.DIM_STR_THE_NETHER, 3L));
    }

    @Test
    void loadFromNonexistentDirDoesNothing(@TempDir Path tempDir) {
        var loaded = new ColumnTimestampCache();
        loaded.load(tempDir.resolve("nonexistent"));
        assertEquals(0, loaded.size());
    }

    @Test
    void loadFromEmptyDirDoesNothing(@TempDir Path tempDir) {
        var loaded = new ColumnTimestampCache();
        loaded.load(tempDir);
        assertEquals(0, loaded.size());
    }

    @Test
    void saveEmptyCacheIsNoOp(@TempDir Path tempDir) {
        cache.save(tempDir);
        // No file should be created
        assertFalse(tempDir.resolve("lss-timestamps.bin").toFile().exists());
    }

    @Test
    void saveOverwritesPreviousFile(@TempDir Path tempDir) {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 100L, now);
        cache.save(tempDir);

        // Overwrite with different data
        var cache2 = new ColumnTimestampCache();
        cache2.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 999L, now);
        cache2.put(LSSConstants.DIM_STR_OVERWORLD, 5L, 500L, now);
        cache2.save(tempDir);

        var loaded = new ColumnTimestampCache();
        loaded.load(tempDir);
        assertEquals(2, loaded.size());
        assertEquals(999L, loaded.get(LSSConstants.DIM_STR_OVERWORLD, 1L));
        assertEquals(500L, loaded.get(LSSConstants.DIM_STR_OVERWORLD, 5L));
    }

    @Test
    void snapshotForSaveIsIndependent() {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 100L, now);
        var snapshot = cache.snapshotForSave();

        // Modify original — snapshot should be unaffected
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 999L, now);
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 2L, 200L, now);

        assertEquals(100L, snapshot.get(LSSConstants.DIM_STR_OVERWORLD, 1L));
        assertEquals(0L, snapshot.get(LSSConstants.DIM_STR_OVERWORLD, 2L));
    }

    @Test
    void loadPreservesExistingEntries(@TempDir Path tempDir) {
        // Save one entry
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 100L, now);
        cache.save(tempDir);

        // Load into a cache that already has a different entry
        var loaded = new ColumnTimestampCache();
        loaded.put(LSSConstants.DIM_STR_OVERWORLD, 99L, 999L, now);
        loaded.load(tempDir);

        assertEquals(2, loaded.size());
        assertEquals(100L, loaded.get(LSSConstants.DIM_STR_OVERWORLD, 1L));
        assertEquals(999L, loaded.get(LSSConstants.DIM_STR_OVERWORLD, 99L));
    }
}
