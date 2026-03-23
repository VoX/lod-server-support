package dev.vox.lss.common.voxel;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

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
    private static final int BYTES_PER_ENTRY = 16; // 8 bytes packed position + 8 bytes timestamp

    private record DimensionCache(Long2LongOpenHashMap timestamps, Long2LongOpenHashMap insertionTimes) {
        DimensionCache() { this(new Long2LongOpenHashMap(), new Long2LongOpenHashMap()); }
    }

    private final Map<String, DimensionCache> caches = new HashMap<>();
    private final int maxEntriesPerDimension;

    public ColumnTimestampCache(int maxEntriesPerDimension) {
        this.maxEntriesPerDimension = maxEntriesPerDimension;
    }

    /** Converts an approximate MB size to an entry count. */
    public static int mbToEntries(int mb) {
        return mb * (1024 * 1024 / BYTES_PER_ENTRY);
    }

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
     * Evicts oldest-inserted entries when a dimension exceeds {@link #MAX_ENTRIES_PER_DIMENSION}.
     * Returns the total number of entries evicted across all dimensions.
     */
    public int evictIfOversized() {
        int evicted = 0;
        for (var cache : caches.values()) {
            int excess = cache.timestamps.size() - this.maxEntriesPerDimension;
            if (excess <= 0) continue;
            evicted += evictOldest(cache, excess);
        }
        return evicted;
    }

    private int evictOldest(DimensionCache cache, int count) {
        // Find the N oldest entries by insertion time
        long[] keys = new long[count];
        long[] times = new long[count];
        int found = 0;

        for (var e : cache.insertionTimes.long2LongEntrySet()) {
            long t = e.getLongValue();
            if (found < count) {
                keys[found] = e.getLongKey();
                times[found] = t;
                found++;
            } else {
                // Replace the newest entry in our eviction set if this one is older
                int maxIdx = 0;
                for (int i = 1; i < count; i++) {
                    if (times[i] > times[maxIdx]) maxIdx = i;
                }
                if (t < times[maxIdx]) {
                    keys[maxIdx] = e.getLongKey();
                    times[maxIdx] = t;
                }
            }
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

    // ---- Persistence ----

    /**
     * Saves the cache to {@code <dataDir>/lss-timestamps.bin} using atomic write.
     * Format: version (int) + dimensionCount (int) + per-dimension: key (UTF) + count (int) + entries (long+long).
     */
    public void save(Path dataDir) {
        if (caches.isEmpty()) return;

        var file = dataDir.resolve(FILE_NAME);
        var tmpFile = file.resolveSibling(FILE_NAME + ".tmp");
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
            Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
                if (entryCount < 0 || entryCount > this.maxEntriesPerDimension) {
                    LSSLogger.warn("Timestamp cache dimension " + dimension + " has invalid count " + entryCount + ", skipping");
                    // Skip remaining bytes for this dimension
                    in.skipBytes(entryCount * 16);
                    continue;
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
