package dev.vox.lss.api;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Consumer for raw MC primitive column data from the server.
 * <p>
 * Each column contains all non-air sections with raw block state IDs (4096 per section),
 * biome IDs (64 per section, 4x4x4 grid), and per-voxel light values (4096 bytes).
 * <p>
 * <b>Threading:</b> When {@code offThreadSectionProcessing} is enabled (default),
 * {@link #onVoxelColumnReceived} is called from a dedicated background thread
 * ({@code LSS-ColumnProcessor}), not the main client thread. The {@code level}
 * parameter is a snapshot captured at submission time and may no longer be the
 * active level. Implementations must be thread-safe.
 */
@FunctionalInterface
public interface VoxelColumnConsumer {
    void onVoxelColumnReceived(ClientLevel level, ResourceKey<Level> dimension,
                                int chunkX, int chunkZ, VoxelColumnData columnData);
}
