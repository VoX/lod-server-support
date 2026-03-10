package dev.vox.lss.compat;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.api.LSSApi;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.OptionalInt;

/**
 * Pure reflection bridge to Voxy — zero compile-time dependency.
 * Uses Voxy's {@code rawIngest} API to pass MC-native {@link net.minecraft.world.level.chunk.LevelChunkSection}
 * and {@link net.minecraft.world.level.chunk.DataLayer} objects directly, eliminating all
 * intermediate ID translation.
 */
class VoxyCompat {
    // WorldIdentifier.of(Level) → Object
    private static MethodHandle worldIdentifierOf;

    // VoxelIngestService.rawIngest(Object worldId, LevelChunkSection, int cx, int sy, int cz, DataLayer blockLight, DataLayer skyLight)
    private static MethodHandle rawIngest;

    // VoxyConfig — lazily initialized to avoid premature class loading
    private static volatile MethodHandle getVoxyConfig;
    private static volatile MethodHandle getSectionRenderDist;

    static boolean init() {
        try {
            var lookup = MethodHandles.lookup();

            Class<?> worldIdClass = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier");
            worldIdentifierOf = lookup.findStatic(worldIdClass, "of",
                    MethodType.methodType(worldIdClass, Level.class))
                    .asType(MethodType.methodType(Object.class, Level.class));

            Class<?> ingestClass = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService");
            rawIngest = lookup.findStatic(ingestClass, "rawIngest",
                    MethodType.methodType(boolean.class,
                            worldIdClass, LevelChunkSection.class,
                            int.class, int.class, int.class,
                            DataLayer.class, DataLayer.class));

            // Register column consumer
            LSSApi.registerColumnConsumer((level, dimension, chunkX, chunkZ, columnData) -> {
                try {
                    Object worldId = worldIdentifierOf.invoke(level);
                    if (worldId == null) return;
                    for (var s : columnData.sections()) {
                        rawIngest.invoke(worldId, s.section(),
                                chunkX, s.sectionY(), chunkZ,
                                s.blockLight(), s.skyLight());
                    }
                } catch (Throwable e) {
                    if (e instanceof Error && !(e instanceof LinkageError || e instanceof AssertionError)) throw (Error) e;
                    LSSLogger.error("Voxy raw ingest failed", e);
                }
            });

            LSSLogger.info("Voxy detected — registered raw ingest bridge");
            return true;
        } catch (ClassNotFoundException e) {
            LSSLogger.warn("Voxy compat: class not found — " + e.getMessage());
            return false;
        } catch (NoSuchMethodException e) {
            LSSLogger.warn("Voxy compat: method not found — " + e.getMessage());
            return false;
        } catch (Throwable e) {
            LSSLogger.error("Failed to initialize Voxy compat", e);
            return false;
        }
    }

    private static void initConfigHandles() throws Throwable {
        if (getVoxyConfig != null) return;
        var lookup = MethodHandles.lookup();
        Class<?> voxyConfigClass = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
        var configField = voxyConfigClass.getField("CONFIG");
        getSectionRenderDist = lookup.findGetter(voxyConfigClass, "sectionRenderDistance", float.class)
                .asType(MethodType.methodType(float.class, Object.class));
        // Assign getVoxyConfig last — it's the guard checked by callers
        getVoxyConfig = lookup.unreflectGetter(configField)
                .asType(MethodType.methodType(Object.class));
    }

    static OptionalInt getViewDistanceChunks() {
        try {
            initConfigHandles();
            Object config = (Object) getVoxyConfig.invokeExact();
            float sectionDist = (float) getSectionRenderDist.invokeExact(config);
            return OptionalInt.of(Math.round(sectionDist * 32));
        } catch (Throwable e) {
            return OptionalInt.empty();
        }
    }
}
