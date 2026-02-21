package dev.vox.lss.compat;

import dev.vox.lss.LSSLogger;
import dev.vox.lss.api.LSSApi;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Voxy integration bridge using MethodHandles to avoid compile-time dependency.
 * <p>
 * Registers a {@link dev.vox.lss.api.ChunkSectionConsumer} that feeds received
 * chunk sections into Voxy's VoxelIngestService.rawIngest() pipeline.
 */
class VoxyCompat {
    private static MethodHandle worldIdentifierOf;
    private static MethodHandle rawIngest;

    static boolean init() {
        try {
            var lookup = MethodHandles.lookup();

            Class<?> worldIdClass = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier");

            // WorldIdentifier.of(Level) -> WorldIdentifier
            worldIdentifierOf = lookup.findStatic(worldIdClass, "of",
                    MethodType.methodType(worldIdClass, Level.class));

            // VoxelIngestService.rawIngest(WorldIdentifier, LevelChunkSection, int, int, int, DataLayer, DataLayer) -> boolean
            Class<?> ingestClass = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService");
            rawIngest = lookup.findStatic(ingestClass, "rawIngest",
                    MethodType.methodType(boolean.class, worldIdClass, LevelChunkSection.class,
                            int.class, int.class, int.class, DataLayer.class, DataLayer.class));

            LSSApi.registerConsumer((level, dimension, section, chunkX, sectionY, chunkZ, blockLight, skyLight) -> {
                try {
                    Object worldId = worldIdentifierOf.invoke(level);
                    if (worldId != null) {
                        rawIngest.invoke(worldId, section, chunkX, sectionY, chunkZ, blockLight, skyLight);
                    }
                } catch (Throwable e) {
                    LSSLogger.error("Voxy ingest bridge failed", e);
                }
            });

            LSSLogger.info("Voxy detected — registered ingest bridge");
            return true;
        } catch (ClassNotFoundException e) {
            // Expected — Voxy is not installed
            return false;
        } catch (Exception e) {
            LSSLogger.error("Failed to initialize Voxy compat", e);
            return false;
        }
    }
}
