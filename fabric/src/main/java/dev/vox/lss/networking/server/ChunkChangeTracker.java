package dev.vox.lss.networking.server;

import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks chunk columns that were saved to disk, so the server can push
 * dirty-column notifications to clients. Both {@link #markDirty} and
 * {@link #drainDirty} are expected to run on the server thread, but
 * synchronized to guard against mods that save chunks off-thread.
 */
public class ChunkChangeTracker {
    private static final Map<ResourceKey<Level>, LongOpenHashSet> dirtyColumns = new HashMap<>();

    public static synchronized void markDirty(ResourceKey<Level> dimension, ChunkPos pos) {
        dirtyColumns.computeIfAbsent(dimension, k -> new LongOpenHashSet())
                .add(PositionUtil.packPosition(pos.x, pos.z));
    }

    public static synchronized long[] drainDirty(ResourceKey<Level> dimension) {
        var set = dirtyColumns.get(dimension);
        if (set == null || set.isEmpty()) return null;
        long[] result = set.toLongArray();
        set.clear();
        return result;
    }
}
