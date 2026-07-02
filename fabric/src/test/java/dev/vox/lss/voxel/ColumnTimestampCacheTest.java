package dev.vox.lss.voxel;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.voxel.ColumnTimestampCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ColumnTimestampCacheTest {
    private static final int DEFAULT_MAX = ColumnTimestampCache.mbToEntries(32);

    private ColumnTimestampCache cache;
    private long now;

    @BeforeEach
    void setUp() {
        cache = new ColumnTimestampCache(DEFAULT_MAX);
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
    void invalidateReturnsRemovedCount() {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 100L, now);
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 2L, 200L, now);
        assertEquals(2, cache.invalidate(LSSConstants.DIM_STR_OVERWORLD, new long[]{1L, 2L, 3L}),
                "returns the count actually removed (3L was never cached)");
        assertEquals(0, cache.invalidate(LSSConstants.DIM_STR_OVERWORLD, new long[]{1L}),
                "already-removed positions count zero — the debounced save trips only on a real removal");
        assertEquals(0, cache.invalidate("lss_test:absent", new long[]{1L}), "unknown dimension removes nothing");
    }

    @Test
    void invalidateCleansInsertionTimeToo() {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 100L, now);
        cache.invalidate(LSSConstants.DIM_STR_OVERWORLD, new long[]{1L});
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

    // ---- mbToEntries ----

    @Test
    void mbToEntriesConversion() {
        // 1 MB = 1048576 bytes / 64 bytes LIVE HEAP per entry = 16384. The divisor models
        // real heap (two Long2LongOpenHashMap slots at 0.75 load factor + pow2 overshoot),
        // not the 16-byte disk record — the old divisor made the configured MB cap mean
        // 3-5x more RAM than the admin asked for.
        assertEquals(16384, ColumnTimestampCache.mbToEntries(1));
        assertEquals(16384 * 32, ColumnTimestampCache.mbToEntries(32));
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
        // Use a small cache to actually test eviction
        var smallCache = new ColumnTimestampCache(5);
        for (int i = 0; i < 8; i++) {
            smallCache.put(LSSConstants.DIM_STR_OVERWORLD, i, i * 100L, now + i);
        }
        assertEquals(8, smallCache.size());

        int evicted = smallCache.evictIfOversized();
        assertEquals(3, evicted);
        assertEquals(5, smallCache.size());

        // Oldest entries (inserted at now+0, now+1, now+2) should be evicted
        assertEquals(0L, smallCache.get(LSSConstants.DIM_STR_OVERWORLD, 0));
        assertEquals(0L, smallCache.get(LSSConstants.DIM_STR_OVERWORLD, 1));
        assertEquals(0L, smallCache.get(LSSConstants.DIM_STR_OVERWORLD, 2));
        // Newer entries should remain
        assertEquals(700L, smallCache.get(LSSConstants.DIM_STR_OVERWORLD, 7));
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

        var loaded = new ColumnTimestampCache(DEFAULT_MAX);
        loaded.load(tempDir);

        assertEquals(3, loaded.size());
        assertEquals(100L, loaded.get(LSSConstants.DIM_STR_OVERWORLD, 1L));
        assertEquals(200L, loaded.get(LSSConstants.DIM_STR_OVERWORLD, 2L));
        assertEquals(300L, loaded.get(LSSConstants.DIM_STR_THE_NETHER, 3L));
    }

    @Test
    void loadFromNonexistentDirDoesNothing(@TempDir Path tempDir) {
        var loaded = new ColumnTimestampCache(DEFAULT_MAX);
        loaded.load(tempDir.resolve("nonexistent"));
        assertEquals(0, loaded.size());
    }

    @Test
    void loadFromEmptyDirDoesNothing(@TempDir Path tempDir) {
        var loaded = new ColumnTimestampCache(DEFAULT_MAX);
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
        var cache2 = new ColumnTimestampCache(DEFAULT_MAX);
        cache2.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 999L, now);
        cache2.put(LSSConstants.DIM_STR_OVERWORLD, 5L, 500L, now);
        cache2.save(tempDir);

        var loaded = new ColumnTimestampCache(DEFAULT_MAX);
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
        var loaded = new ColumnTimestampCache(DEFAULT_MAX);
        loaded.put(LSSConstants.DIM_STR_OVERWORLD, 99L, 999L, now);
        loaded.load(tempDir);

        assertEquals(2, loaded.size());
        assertEquals(100L, loaded.get(LSSConstants.DIM_STR_OVERWORLD, 1L));
        assertEquals(999L, loaded.get(LSSConstants.DIM_STR_OVERWORLD, 99L));
    }

    // ---- Corrupt-file load guards ----
    // load() runs in the OffThreadProcessor constructor at server boot: a corrupt
    // lss-timestamps.bin (power-loss truncation, disk garbage) must never throw —
    // it may only degrade to a cold cache that rebuilds itself.

    private static DataOutputStream cacheFileOut(Path tempDir) throws IOException {
        return new DataOutputStream(Files.newOutputStream(tempDir.resolve("lss-timestamps.bin")));
    }

    @Test
    void loadTruncatedFileKeepsCompleteEntriesAndStaysUsable(@TempDir Path tempDir) throws IOException {
        try (var out = cacheFileOut(tempDir)) {
            out.writeInt(1); // FORMAT_VERSION
            out.writeInt(1); // one dimension
            out.writeUTF(LSSConstants.DIM_STR_OVERWORLD);
            out.writeInt(4); // declares 4 entries...
            out.writeLong(11L); out.writeLong(100L);
            out.writeLong(22L); out.writeLong(200L);
            out.writeLong(33L); // ...but power loss truncated mid-entry
        }

        var loaded = new ColumnTimestampCache(DEFAULT_MAX);
        assertDoesNotThrow(() -> loaded.load(tempDir));
        assertEquals(100L, loaded.get(LSSConstants.DIM_STR_OVERWORLD, 11L));
        assertEquals(200L, loaded.get(LSSConstants.DIM_STR_OVERWORLD, 22L));
        assertEquals(0L, loaded.get(LSSConstants.DIM_STR_OVERWORLD, 33L),
                "the truncated entry must not be loaded");
        // The cache must remain fully usable after the failed load
        loaded.put(LSSConstants.DIM_STR_OVERWORLD, 33L, 300L, now);
        assertEquals(300L, loaded.get(LSSConstants.DIM_STR_OVERWORLD, 33L));
    }

    @Test
    void loadGarbageFileShorterThanHeaderIsDiscarded(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve("lss-timestamps.bin"),
                new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE}); // EOF inside the version int

        var loaded = new ColumnTimestampCache(DEFAULT_MAX);
        assertDoesNotThrow(() -> loaded.load(tempDir));
        assertEquals(0, loaded.size());
        loaded.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 100L, now);
        assertEquals(100L, loaded.get(LSSConstants.DIM_STR_OVERWORLD, 1L));
    }

    @Test
    void loadUnsupportedVersionIsDiscarded(@TempDir Path tempDir) throws IOException {
        try (var out = cacheFileOut(tempDir)) {
            out.writeInt(99); // future/corrupt format version
            out.writeInt(1);
            out.writeUTF(LSSConstants.DIM_STR_OVERWORLD);
            out.writeInt(1);
            out.writeLong(1L); out.writeLong(100L);
        }

        var loaded = new ColumnTimestampCache(DEFAULT_MAX);
        assertDoesNotThrow(() -> loaded.load(tempDir));
        assertEquals(0, loaded.size(), "a foreign format version must not be interpreted");
    }

    @Test
    void loadNegativeEntryCountStopsCleanly(@TempDir Path tempDir) throws IOException {
        try (var out = cacheFileOut(tempDir)) {
            out.writeInt(1);
            out.writeInt(1);
            out.writeUTF(LSSConstants.DIM_STR_OVERWORLD);
            out.writeInt(-5); // corrupt count: stream can no longer be positioned
        }

        var loaded = new ColumnTimestampCache(DEFAULT_MAX);
        assertDoesNotThrow(() -> loaded.load(tempDir));
        assertEquals(0, loaded.size());
    }

    @Test
    void loadOverCapButFileValidCountLoadsThenIsEvictable(@TempDir Path tempDir) throws IOException {
        // 6 real entries with a cap of 5: this is a LEGITIMATE overshoot (save()/snapshotForSave
        // never evict, so a file can hold more than the cap). It must load — not be discarded as
        // corrupt — and the normal eviction pass then trims it back to the cap.
        try (var out = cacheFileOut(tempDir)) {
            out.writeInt(1);
            out.writeInt(1);
            out.writeUTF(LSSConstants.DIM_STR_OVERWORLD);
            out.writeInt(6);
            for (int i = 0; i < 6; i++) {
                out.writeLong(i); out.writeLong(i * 100L);
            }
        }

        var small = new ColumnTimestampCache(5);
        assertDoesNotThrow(() -> small.load(tempDir));
        assertEquals(6, small.size(), "a valid over-cap count must load, not be discarded");
        assertEquals(1, small.evictIfOversized(), "the next eviction pass trims the overshoot");
        assertEquals(5, small.size());
    }

    @Test
    void loadEntryCountBeyondFileContentsIsDiscarded(@TempDir Path tempDir) throws IOException {
        // A count larger than the file can physically hold is real corruption (the stream would
        // desync into the next dimension), so the rest is discarded.
        try (var out = cacheFileOut(tempDir)) {
            out.writeInt(1);
            out.writeInt(1);
            out.writeUTF(LSSConstants.DIM_STR_OVERWORLD);
            out.writeInt(1_000_000); // file holds only a handful of entries
            out.writeLong(1L); out.writeLong(100L);
        }

        var loaded = new ColumnTimestampCache(DEFAULT_MAX);
        assertDoesNotThrow(() -> loaded.load(tempDir));
        assertEquals(0, loaded.size(), "an impossible count must not allocate/load");
    }

    @Test
    void loadBadCountInSecondDimensionKeepsFirstDimension(@TempDir Path tempDir) throws IOException {
        try (var out = cacheFileOut(tempDir)) {
            out.writeInt(1);
            out.writeInt(2); // two dimensions
            out.writeUTF(LSSConstants.DIM_STR_OVERWORLD);
            out.writeInt(1);
            out.writeLong(7L); out.writeLong(70L);
            out.writeUTF(LSSConstants.DIM_STR_THE_NETHER);
            out.writeInt(-1); // corrupt second dimension: discard the rest, keep what loaded
        }

        var loaded = new ColumnTimestampCache(DEFAULT_MAX);
        assertDoesNotThrow(() -> loaded.load(tempDir));
        assertEquals(70L, loaded.get(LSSConstants.DIM_STR_OVERWORLD, 7L));
        assertEquals(1, loaded.size());
    }

    // ---- SP-051: per-dimension isolation (same packed position, two dimensions) ----

    @Test
    void putGetInvalidateAreDimensionScoped() {
        long p = 42L;
        cache.put(LSSConstants.DIM_STR_OVERWORLD, p, 100L, now);
        cache.put(LSSConstants.DIM_STR_THE_NETHER, p, 200L, now);
        assertEquals(100L, cache.get(LSSConstants.DIM_STR_OVERWORLD, p));
        assertEquals(200L, cache.get(LSSConstants.DIM_STR_THE_NETHER, p));

        cache.invalidate(LSSConstants.DIM_STR_OVERWORLD, new long[]{p});
        assertEquals(0L, cache.get(LSSConstants.DIM_STR_OVERWORLD, p), "invalidate is dimension-scoped");
        assertEquals(200L, cache.get(LSSConstants.DIM_STR_THE_NETHER, p),
                "the same packed position in another dimension survives");
    }

    // ---- SP-052: eviction refreshes age on re-put and evicts per dimension ----

    @Test
    void rePutRefreshesInsertionAgeSoTheReputEntrySurvivesEviction() {
        var small = new ColumnTimestampCache(3);
        small.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 10L, 1L);
        small.put(LSSConstants.DIM_STR_OVERWORLD, 2L, 20L, 2L);
        small.put(LSSConstants.DIM_STR_OVERWORLD, 3L, 30L, 3L);
        small.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 11L, 10L); // re-put p1: now the NEWEST
        small.put(LSSConstants.DIM_STR_OVERWORLD, 4L, 40L, 11L); // 4 entries, cap is 3
        assertEquals(1, small.evictIfOversized(), "one excess entry evicted");
        assertEquals(11L, small.get(LSSConstants.DIM_STR_OVERWORLD, 1L),
                "the re-put refreshed p1's insertion age, so it survives");
        assertEquals(0L, small.get(LSSConstants.DIM_STR_OVERWORLD, 2L),
                "the genuinely-oldest entry (p2) is the one evicted");
    }

    @Test
    void evictionIsScopedToTheOversizedDimension() {
        var small = new ColumnTimestampCache(2);
        small.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 10L, 1L);
        small.put(LSSConstants.DIM_STR_OVERWORLD, 2L, 20L, 2L);
        small.put(LSSConstants.DIM_STR_OVERWORLD, 3L, 30L, 3L); // overworld over its cap
        small.put(LSSConstants.DIM_STR_THE_NETHER, 9L, 90L, 1L); // nether within cap
        small.evictIfOversized();
        assertEquals(90L, small.get(LSSConstants.DIM_STR_THE_NETHER, 9L),
                "a within-cap dimension is untouched by another dimension's eviction");
    }

    // ---- SP-054: save fails gracefully; snapshotForSave is isolated from later mutation ----

    @Test
    void saveToAPathThatIsAFileFailsGracefullyLeavingNoTempFile(@TempDir Path tempDir) throws IOException {
        var blocker = tempDir.resolve("blocker"); // a regular file where save expects a directory
        Files.writeString(blocker, "x");
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 100L, now);
        assertDoesNotThrow(() -> cache.save(blocker), "the IO failure is swallowed, not thrown");
        try (var files = Files.list(tempDir)) {
            assertTrue(files.noneMatch(f -> f.getFileName().toString().startsWith("lss-timestamps.bin.tmp")),
                    "the half-written temp file is cleaned up / never created");
        }
    }

    @Test
    void snapshotForSaveIsUnaffectedByLaterMutation() {
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 1L, 100L, now);
        var snap = cache.snapshotForSave();
        cache.invalidate(LSSConstants.DIM_STR_OVERWORLD, new long[]{1L}); // mutate the original after snapshotting
        cache.put(LSSConstants.DIM_STR_OVERWORLD, 2L, 200L, now);
        assertEquals(100L, snap.get(LSSConstants.DIM_STR_OVERWORLD, 1L),
                "the snapshot keeps the entry the original invalidated");
        assertEquals(0L, snap.get(LSSConstants.DIM_STR_OVERWORLD, 2L),
                "the snapshot excludes a put made after it was taken");
    }
}
