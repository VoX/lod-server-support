package dev.vox.lss.common.tracking;

import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks chunk columns confirmed as dirty (content hash changed).
 * Platform-agnostic — uses String dimension keys, no MC types.
 * Thread-safe via synchronized blocks.
 */
public class DirtyColumnTracker {
    private final Map<String, LongOpenHashSet> dirtyColumns = new HashMap<>();
    private long totalDrained;
    private long totalMarked;

    public synchronized void markDirty(String dimension, int cx, int cz) {
        long packed = PositionUtil.packPosition(cx, cz);
        if (dirtyColumns.computeIfAbsent(dimension, k -> new LongOpenHashSet()).add(packed)) {
            totalMarked++;
        }
    }

    public synchronized long[] drainDirty(String dimension) {
        var set = dirtyColumns.get(dimension);
        if (set == null || set.isEmpty()) return null;
        long[] result = set.toLongArray();
        set.clear();
        totalDrained += result.length;
        return result;
    }

    /**
     * Drain every dimension's pending dirty positions at once. Shutdown path: marks
     * accumulated since the last broadcast interval must still invalidate the timestamp
     * cache before its final save, or the persisted stamps answer false up_to_date for
     * edited columns across the restart.
     */
    public synchronized Map<String, long[]> drainAll() {
        Map<String, long[]> out = new HashMap<>();
        for (var entry : dirtyColumns.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            long[] positions = entry.getValue().toLongArray();
            entry.getValue().clear();
            totalDrained += positions.length;
            out.put(entry.getKey(), positions);
        }
        return out;
    }

    /** Dirty positions accumulated and not yet drained, across all dimensions. */
    public synchronized int pendingCount() {
        int total = 0;
        for (var set : dirtyColumns.values()) total += set.size();
        return total;
    }

    /** Cumulative count of positions handed to broadcasters across the tracker's lifetime. */
    public synchronized long getTotalDrained() { return totalDrained; }

    /**
     * Cumulative count of net-new dirty marks (re-marking a still-pending position does not
     * count). Closes mark/drain conservation: at any observation point,
     * {@code getTotalMarked() == getTotalDrained() + pendingCount()}.
     */
    public synchronized long getTotalMarked() { return totalMarked; }
}
