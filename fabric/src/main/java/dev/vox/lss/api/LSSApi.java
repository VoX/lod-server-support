package dev.vox.lss.api;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.networking.client.LSSClientNetworking;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Public API for Voxel Server Support.
 * <p>
 * LOD rendering mods register a {@link ChunkSectionConsumer}
 * to receive deserialized chunk section data from the server.
 */
public final class LSSApi {
    private static final List<ChunkSectionConsumer> consumers = new CopyOnWriteArrayList<>();

    private LSSApi() {}

    /**
     * Register a consumer to receive chunk sections from the server.
     * Call this during mod initialization.
     */
    public static void registerConsumer(ChunkSectionConsumer consumer) {
        consumers.add(consumer);
        LSSLogger.info("Registered chunk section consumer: " + consumer.getClass().getName());
    }

    /**
     * Remove a previously registered consumer.
     */
    public static void removeConsumer(ChunkSectionConsumer consumer) {
        consumers.remove(consumer);
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

    // --- Internal dispatch (not part of public API) ---

    public static void dispatch(ClientLevel level, ResourceKey<Level> dimension,
                                LevelChunkSection section, int chunkX, int sectionY, int chunkZ,
                                DataLayer blockLight, DataLayer skyLight) {
        for (var consumer : consumers) {
            try {
                consumer.onSectionReceived(level, dimension, section, chunkX, sectionY, chunkZ, blockLight, skyLight);
            } catch (Exception e) {
                LSSLogger.error("Consumer threw exception", e);
            }
        }
    }

    public static boolean hasConsumers() {
        return !consumers.isEmpty();
    }
}
