package dev.vox.lss.api;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Consumer interface for received chunk section data.
 * <p>
 * Register implementations via {@link LSSApi#registerConsumer(ChunkSectionConsumer)}
 * to receive deserialized chunk sections from the server.
 * <p>
 * Implementations are called on the client main thread after deserialization.
 */
@FunctionalInterface
public interface ChunkSectionConsumer {
    /**
     * Called when a chunk section is received and deserialized from the server.
     *
     * @param level      the current client level
     * @param dimension  the dimension this section belongs to
     * @param section    the deserialized chunk section (blocks + biomes)
     * @param chunkX     chunk X coordinate
     * @param sectionY   section Y coordinate
     * @param chunkZ     chunk Z coordinate
     * @param blockLight block light data layer, or null if not sent
     * @param skyLight   sky light data layer, or null if not sent
     */
    void onSectionReceived(
            ClientLevel level,
            ResourceKey<Level> dimension,
            LevelChunkSection section,
            int chunkX,
            int sectionY,
            int chunkZ,
            DataLayer blockLight,
            DataLayer skyLight
    );
}
