package dev.vox.lss.common.voxel;

import dev.vox.lss.common.LSSConstants;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-dimension timestamp cache for served columns.
 * Stores (packedXZ → timestamp) and (packedXZ → insertionTime) for columns that have been served.
 * Used for up-to-date checks without disk IO.
 *
 * <p>Single-threaded — must only be called from the processing thread.
 */
public class ColumnTimestampCache {

    private record DimensionCache(Long2LongOpenHashMap timestamps, Long2LongOpenHashMap insertionTimes) {
        DimensionCache() { this(new Long2LongOpenHashMap(), new Long2LongOpenHashMap()); }
    }

    private final Map<String, DimensionCache> caches = new HashMap<>();

    public void put(String dimension, long packed, long timestamp, long now) {
        var cache = caches.computeIfAbsent(dimension, k -> new DimensionCache());
        cache.timestamps.put(packed, timestamp);
        cache.insertionTimes.put(packed, now);
    }

    /**
     * Returns the stored timestamp, or 0 if absent.
     */
    public long get(String dimension, long packed) {
        var cache = caches.get(dimension);
        if (cache == null) return 0;
        return cache.timestamps.getOrDefault(packed, 0L);
    }

    public void invalidate(String dimension, long[] positions) {
        var cache = caches.get(dimension);
        if (cache == null) return;
        for (long pos : positions) {
            cache.timestamps.remove(pos);
            cache.insertionTimes.remove(pos);
        }
    }

    /**
     * Removes all entries older than {@code maxAgeSeconds} based on insertion time.
     * Returns the number of entries evicted.
     */
    public int evictOlderThan(long maxAgeSeconds) {
        long now = LSSConstants.epochSeconds();
        long cutoff = now - maxAgeSeconds;
        int evicted = 0;
        for (var cache : caches.values()) {
            var iter = cache.insertionTimes.long2LongEntrySet().iterator();
            while (iter.hasNext()) {
                var e = iter.next();
                long key = e.getLongKey();
                if (e.getLongValue() <= cutoff) {
                    iter.remove();
                    cache.timestamps.remove(key);
                    evicted++;
                }
            }
        }
        return evicted;
    }

    /** Total entries across all dimensions. */
    public int size() {
        int total = 0;
        for (var cache : caches.values()) {
            total += cache.timestamps.size();
        }
        return total;
    }
}
