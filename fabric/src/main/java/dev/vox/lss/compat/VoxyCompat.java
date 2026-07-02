package dev.vox.lss.compat;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnConsumer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.OptionalInt;
import java.util.function.Consumer;

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

    /** Resolves the reflected Voxy class names — test seam, see {@link #resetSeams()}. */
    @FunctionalInterface
    interface ClassResolver {
        Class<?> resolve(String name) throws ClassNotFoundException;
    }

    /** Receives the bridge's ingest-failure reports — test seam, see {@link #resetSeams()}. */
    @FunctionalInterface
    interface ReportSink {
        void report(ResourceKey<Level> dimension, int chunkX, int chunkZ);
    }

    // Test seams: default-wired to production by resetSeams() (zero behavior change);
    // VoxyCompatTest swaps them to drive the bridge's failure ladder against stub classes.
    static ClassResolver classResolver;
    static ReportSink reportSink;
    static Consumer<VoxelColumnConsumer> consumerRegistrar;

    static {
        resetSeams();
    }

    /** Restores production wiring for the test seams. */
    static void resetSeams() {
        classResolver = Class::forName;
        reportSink = LSSApi::reportIngestFailure;
        consumerRegistrar = LSSApi::registerColumnConsumer;
    }

    static boolean init() {
        try {
            var lookup = MethodHandles.lookup();

            Class<?> worldIdClass = classResolver.resolve("me.cortex.voxy.commonImpl.WorldIdentifier");
            worldIdentifierOf = lookup.findStatic(worldIdClass, "of",
                    MethodType.methodType(worldIdClass, Level.class))
                    .asType(MethodType.methodType(Object.class, Level.class));

            Class<?> ingestClass = classResolver.resolve("me.cortex.voxy.common.world.service.VoxelIngestService");
            rawIngest = lookup.findStatic(ingestClass, "rawIngest",
                    MethodType.methodType(boolean.class,
                            worldIdClass, LevelChunkSection.class,
                            int.class, int.class, int.class,
                            DataLayer.class, DataLayer.class));

            // Register column consumer
            var bridgeDead = new java.util.concurrent.atomic.AtomicBoolean();
            VoxelColumnConsumer consumer = (level, dimension, chunkX, chunkZ, columnData) -> {
                if (bridgeDead.get()) return;
                try {
                    Object worldId = worldIdentifierOf.invoke(level);
                    if (worldId == null) {
                        // Voxy has no storage for this world yet (e.g. mid open-to-LAN
                        // transition) — without the report the column is silently lost
                        // and never re-requested.
                        reportSink.report(dimension, chunkX, chunkZ);
                        return;
                    }
                    boolean allAccepted = true;
                    for (var s : columnData.sections()) {
                        // H-12 guard: LSS ships "absent light layer = null" (the universal
                        // "absent means all-zero" wire default). Voxy renders a null sky-light
                        // layer as full daylight, so a no-sky dimension (nether/end,
                        // hasSkyLight()==false) — where MC never stores sky light, so every
                        // section arrives null — would light its topmost surfaces as if skylit
                        // until vanilla loaded the chunk. Hand Voxy an explicit all-zero
                        // (present, non-empty) DataLayer so those surfaces render dark. Overworld
                        // skylit surfaces ship a real non-null layer and are unaffected.
                        DataLayer skyLight = s.skyLight() != null ? s.skyLight() : new DataLayer(new byte[2048]);
                        allAccepted &= (boolean) rawIngest.invoke(worldId, s.section(),
                                chunkX, s.sectionY(), chunkZ,
                                s.blockLight(), skyLight);
                    }
                    if (!allAccepted) {
                        reportSink.report(dimension, chunkX, chunkZ);
                    }
                } catch (LinkageError e) {
                    // Incompatible Voxy: this will never succeed for any column. Kill the
                    // bridge instead of reporting — a report would re-serve the column and
                    // re-fail it (capped per position, but pure waste at session scale).
                    bridgeDead.set(true);
                    LSSLogger.error("Voxy raw ingest is incompatible with this Voxy version — "
                            + "disabling the bridge for this session", e);
                } catch (Throwable e) {
                    if (e instanceof Error && !(e instanceof AssertionError)) throw (Error) e;
                    LSSLogger.error("Voxy raw ingest failed", e);
                    reportSink.report(dimension, chunkX, chunkZ);
                }
            };
            consumerRegistrar.accept(consumer);

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
        Class<?> voxyConfigClass = classResolver.resolve("me.cortex.voxy.client.config.VoxyConfig");
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
