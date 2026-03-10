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

    public synchronized void markDirty(String dimension, int cx, int cz) {
        long packed = PositionUtil.packPosition(cx, cz);
        dirtyColumns.computeIfAbsent(dimension, k -> new LongOpenHashSet()).add(packed);
    }

    public synchronized long[] drainDirty(String dimension) {
        var set = dirtyColumns.get(dimension);
        if (set == null || set.isEmpty()) return null;
        long[] result = set.toLongArray();
        set.clear();
        return result;
    }
}
