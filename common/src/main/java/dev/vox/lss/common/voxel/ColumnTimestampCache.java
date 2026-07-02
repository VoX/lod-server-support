package dev.vox.lss.common.voxel;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-dimension timestamp cache for served columns.
 * Stores (packedXZ → timestamp) and (packedXZ → insertionTime) for columns that have been served.
 * Used for up-to-date checks without disk IO.
 *
 * <p>Persistent: saved to disk periodically and on shutdown, loaded on startup.
 *
 * <p>Single-threaded — must only be called from the processing thread.
 */
public class ColumnTimestampCache {

    private static final int FORMAT_VERSION = 1;
    private static final String FILE_NAME = "lss-timestamps.bin";
    /** On-disk record size (8 bytes packed position + 8 bytes timestamp) — load sanity bound. */
    private static final int DISK_BYTES_PER_ENTRY = 16;
    /** Approximate LIVE heap per entry: each entry occupies a slot in TWO Long2LongOpenHashMaps
     *  (timestamps + insertionTimes), each 16 bytes/slot at a 0.75 load factor with pow2
     *  capacity overshoot — ~43 bytes typical, ~85 worst right after a resize. 64 is the honest
     *  midpoint; the old value (16, the disk record size) under-modeled heap by 3-5x, so the
     *  configured perDimensionTimestampCacheSizeMB silently meant several times more RAM. */
    private static final int HEAP_BYTES_PER_ENTRY = 64;

    private record DimensionCache(Long2LongOpenHashMap timestamps, Long2LongOpenHashMap insertionTimes) {
        DimensionCache() { this(new Long2LongOpenHashMap(), new Long2LongOpenHashMap()); }
    }

    private final Map<String, DimensionCache> caches = new HashMap<>();
    private final int maxEntriesPerDimension;

    // Cross-thread observability for the soak/benchmark exporters (the cache itself is
    // processing-thread-only): liveSizes mirrors each dimension's current entry count
    // after every mutation, evictionCount accumulates evictIfOversized removals.
    private final Map<String, Integer> liveSizes = new ConcurrentHashMap<>();
    private final AtomicLong evictionCount = new AtomicLong();

    public ColumnTimestampCache(int maxEntriesPerDimension) {
        this.maxEntriesPerDimension = maxEntriesPerDimension;
    }

    /** Converts an approximate MB size (of live heap, not disk) to an entry count. */
    public static int mbToEntries(int mb) {
        return mb * (1024 * 1024 / HEAP_BYTES_PER_ENTRY);
    }

    public void put(String dimension, long packed, long timestamp, long now) {
        var cache = caches.computeIfAbsent(dimension, k -> new DimensionCache());
        cache.timestamps.put(packed, timestamp);
        cache.insertionTimes.put(packed, now);
        liveSizes.put(dimension, cache.timestamps.size());
    }

    /**
     * Returns the stored timestamp, or 0 if absent.
     */
    public long get(String dimension, long packed) {
        var cache = caches.get(dimension);
        if (cache == null) return 0;
        return cache.timestamps.getOrDefault(packed, 0L);
    }

    /** Removes cached timestamps for the given positions. Returns the count actually removed. */
    public int invalidate(String dimension, long[] positions) {
        var cache = caches.get(dimension);
        if (cache == null) return 0;
        int removed = 0;
        for (long pos : positions) {
            if (cache.timestamps.containsKey(pos)) {
                cache.timestamps.remove(pos);
                cache.insertionTimes.remove(pos);
                removed++;
            }
        }
        if (removed > 0) liveSizes.put(dimension, cache.timestamps.size());
        return removed;
    }

    /**
     * Evicts oldest-inserted entries when a dimension exceeds the configured per-dimension cap.
     * Returns the total number of entries evicted across all dimensions.
     */
    public int evictIfOversized() {
        int evicted = 0;
        for (var entry : caches.entrySet()) {
            var cache = entry.getValue();
            int excess = cache.timestamps.size() - this.maxEntriesPerDimension;
            if (excess <= 0) continue;
            int removed = evictOldest(cache, excess);
            evicted += removed;
            liveSizes.put(entry.getKey(), cache.timestamps.size());
        }
        if (evicted > 0) evictionCount.addAndGet(evicted);
        return evicted;
    }

    private int evictOldest(DimensionCache cache, int count) {
        // O(n log n) threshold selection. The old selection loop rescanned the whole
        // eviction-candidate array for every entry past the first `count` — O((n-count)*count),
        // which froze the processing thread for seconds every eviction cycle once a dimension
        // sat at cap, and for minutes after a config shrink (count ~ n).
        int n = cache.insertionTimes.size();
        if (count >= n) {
            cache.timestamps.clear();
            cache.insertionTimes.clear();
            return n;
        }
        long[] times = new long[n];
        int idx = 0;
        for (var it = cache.insertionTimes.values().iterator(); it.hasNext(); ) {
            times[idx++] = it.nextLong();
        }
        java.util.Arrays.sort(times);
        long threshold = times[count - 1]; // newest insertion time still inside the eviction set

        long[] keys = new long[count];
        int found = 0;
        // Strictly-older entries first, then entries AT the threshold up to the exact count
        // (removing every threshold tie would over-evict).
        for (var e : cache.insertionTimes.long2LongEntrySet()) {
            if (found == count) break;
            if (e.getLongValue() < threshold) keys[found++] = e.getLongKey();
        }
        for (var e : cache.insertionTimes.long2LongEntrySet()) {
            if (found == count) break;
            if (e.getLongValue() == threshold) keys[found++] = e.getLongKey();
        }

        for (int i = 0; i < found; i++) {
            cache.timestamps.remove(keys[i]);
            cache.insertionTimes.remove(keys[i]);
        }
        return found;
    }

    /** Total entries across all dimensions. */
    public int size() {
        int total = 0;
        for (var cache : caches.values()) {
            total += cache.timestamps.size();
        }
        return total;
    }

    /**
     * Cumulative entries removed by {@link #evictIfOversized()} over this instance's
     * lifetime. Safe to read from any thread (exporter observability).
     */
    public long getEvictionCount() {
        return evictionCount.get();
    }

    /**
     * Current entry count per dimension, as of the last mutation. Safe to read from any
     * thread (exporter observability) — the returned map is an immutable copy.
     */
    public Map<String, Integer> sizesPerDimension() {
        return Map.copyOf(liveSizes);
    }

    // ---- Persistence ----

    /**
     * Saves the cache to {@code <dataDir>/lss-timestamps.bin} using atomic write.
     * Format: version (int) + dimensionCount (int) + per-dimension: key (UTF) + count (int) + entries (long+long).
     */
    public void save(Path dataDir) {
        if (caches.isEmpty()) return;

        var file = dataDir.resolve(FILE_NAME);
        // Unique tmp name: two writers overlapping across a Paper /reload (old instance's
        // final save vs new instance's periodic save) must not interleave into one tmp file
        // and publish a torn cache.
        var tmpFile = file.resolveSibling(FILE_NAME + ".tmp." + Long.toHexString(System.nanoTime()));
        try {
            Files.createDirectories(dataDir);
            try (var out = new DataOutputStream(Files.newOutputStream(tmpFile))) {
                out.writeInt(FORMAT_VERSION);
                out.writeInt(caches.size());
                for (var entry : caches.entrySet()) {
                    out.writeUTF(entry.getKey());
                    var dimCache = entry.getValue();
                    out.writeInt(dimCache.timestamps.size());
                    for (var e : dimCache.timestamps.long2LongEntrySet()) {
                        out.writeLong(e.getLongKey());
                        out.writeLong(e.getLongValue());
                    }
                }
            }
            try {
                Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Filesystems without atomic rename (some network mounts — same fallback as
                // JsonConfig.save): a torn file on crash is tolerable, the loader discards it.
                Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
            LSSLogger.info("Saved " + size() + " timestamp cache entries to " + file);
        } catch (IOException e) {
            LSSLogger.warn("Failed to save timestamp cache to " + file, e);
            try { Files.deleteIfExists(tmpFile); } catch (IOException e2) {
                LSSLogger.warn("Failed to clean up temporary timestamp cache file " + tmpFile, e2);
            }
        }
    }

    /**
     * Loads the cache from {@code <dataDir>/lss-timestamps.bin}.
     * Existing entries are preserved (loaded entries are added/overwritten).
     */
    public void load(Path dataDir) {
        var file = dataDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return;

        long now = LSSConstants.epochSeconds();
        int totalLoaded = 0;

        // Sanity-bound each declared entry count by what the file could physically hold
        // (>= 16 bytes per on-disk entry), NOT by maxEntriesPerDimension. save()/snapshotForSave
        // never evict, so a file legitimately holds more than the cap after puts overshoot
        // between eviction cycles, and a config shrink (smaller perDimensionTimestampCacheSizeMB)
        // does the same — rejecting those discarded a valid cache and forced a cold, disk-heavy
        // resync. Over-cap dimensions load here and the next evictIfOversized cycle trims them.
        long maxPlausibleEntries;
        try {
            maxPlausibleEntries = Files.size(file) / DISK_BYTES_PER_ENTRY;
        } catch (IOException e) {
            LSSLogger.warn("Failed to stat timestamp cache " + file, e);
            return;
        }

        try (var in = new DataInputStream(Files.newInputStream(file))) {
            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                LSSLogger.warn("Timestamp cache " + file + " has unsupported version " + version + ", discarding");
                return;
            }
            int dimCount = in.readInt();
            for (int d = 0; d < dimCount; d++) {
                String dimension = in.readUTF();
                int entryCount = in.readInt();
                if (entryCount < 0 || entryCount > maxPlausibleEntries) {
                    // A count larger than the file can hold (or negative) is real corruption:
                    // the byte stream can no longer be reliably positioned, so stop loading.
                    // The cache is only an optimization and rebuilds itself.
                    LSSLogger.warn("Timestamp cache " + file + " has invalid entry count " + entryCount
                            + " for dimension " + dimension + ", discarding rest");
                    return;
                }
                var cache = caches.computeIfAbsent(dimension, k -> new DimensionCache());
                cache.timestamps.ensureCapacity(entryCount);
                cache.insertionTimes.ensureCapacity(entryCount);
                for (int i = 0; i < entryCount; i++) {
                    long packed = in.readLong();
                    long timestamp = in.readLong();
                    cache.timestamps.put(packed, timestamp);
                    cache.insertionTimes.put(packed, now);
                }
                liveSizes.put(dimension, cache.timestamps.size());
                totalLoaded += entryCount;
            }
            LSSLogger.info("Loaded " + totalLoaded + " timestamp cache entries from " + file);
        } catch (IOException e) {
            LSSLogger.warn("Failed to load timestamp cache from " + file, e);
        }
    }

    /**
     * Creates a defensive copy of the timestamp data for async saving.
     * The returned cache contains only timestamps (no insertion times) and should only be used for {@link #save}.
     */
    public ColumnTimestampCache snapshotForSave() {
        var snapshot = new ColumnTimestampCache(this.maxEntriesPerDimension);
        for (var entry : caches.entrySet()) {
            var dimCache = entry.getValue();
            var copy = new DimensionCache(
                    new Long2LongOpenHashMap(dimCache.timestamps),
                    new Long2LongOpenHashMap() // empty — not needed for save
            );
            snapshot.caches.put(entry.getKey(), copy);
        }
        return snapshot;
    }
}
