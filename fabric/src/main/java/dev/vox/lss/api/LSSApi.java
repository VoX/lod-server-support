package dev.vox.lss.api;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.networking.client.LSSClientNetworking;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Public API for Voxel Server Support.
 * <p>
 * LOD rendering mods register a {@link VoxelColumnConsumer}
 * to receive pre-voxelized column data from the server.
 */
public final class LSSApi {
    private static final List<VoxelColumnConsumer> columnConsumers = new CopyOnWriteArrayList<>();

    private LSSApi() {}

    /**
     * Register a consumer to receive pre-voxelized column data from the server.
     * Call this during mod initialization.
     */
    public static void registerColumnConsumer(VoxelColumnConsumer consumer) {
        columnConsumers.add(consumer);
        LSSLogger.info("Registered voxel column consumer: " + consumer.getClass().getName());
    }

    /**
     * Remove a previously registered column consumer.
     */
    public static void removeColumnConsumer(VoxelColumnConsumer consumer) {
        columnConsumers.remove(consumer);
    }

    /**
     * Check whether any voxel consumers are registered.
     */
    public static boolean hasVoxelConsumers() {
        return !columnConsumers.isEmpty();
    }

    /**
     * Check whether the connected server has LOD distribution enabled.
     */
    public static boolean isServerEnabled() {
        return LSSClientNetworking.isServerEnabled();
    }

    /**
     * Get the server's configured LOD distance in chunks, or 0 if not connected.
     */
    public static int getServerLodDistance() {
        return LSSClientNetworking.getServerLodDistance();
    }

    /**
     * Internal dispatch method — not part of the public API.
     * Called by the client networking layer to fan out column data to consumers.
     * @hidden
     */
    public static void dispatchColumn(ClientLevel level, ResourceKey<Level> dimension,
                                       int chunkX, int chunkZ, VoxelColumnData columnData) {
        for (var consumer : columnConsumers) {
            try {
                consumer.onVoxelColumnReceived(level, dimension, chunkX, chunkZ, columnData);
            } catch (Exception e) {
                LSSLogger.error("Voxel column consumer threw exception", e);
            }
        }
    }
}
