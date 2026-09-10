package dev.vox.lss.compat;

import dev.vox.lss.common.LSSLogger;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.state.BlockState;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

/**
 * Resolve-once handle set. All-or-nothing: any missing member throws and the
 * bridge stays off. Xaero-typed members resolve with exact types from the
 * resolved classes; the three {@code ClientLevel}-typed members
 * ({@code getWorld}, {@code mainWorld}, {@code ignoreWorld}) resolve by
 * name-scan (the {@code MoonriseReadCompat} shape-scan precedent) and are
 * handled as Objects behind {@link XaeroSession.LevelOps}, because tests cannot construct
 * a {@code ClientLevel}.
 */
final class XaeroBindings {
    final MethodHandle getCurrentSession;
    final MethodHandle sessionIsUsable;
    final MethodHandle getMapProcessor;
    final MethodHandle renderThreadPauseSync;
    final MethodHandle mainStuffSync;
    final MethodHandle mainWorld;
    final MethodHandle isWritingPaused;
    final MethodHandle isWaitingForWorldUpdate;
    final MethodHandle isCurrentMapLocked;
    final MethodHandle isCurrentMultiworldWritable;
    final MethodHandle getCurrentWorldId;
    final MethodHandle getCurrentDimension;
    final MethodHandle getWorld;
    final MethodHandle ignoreWorld;
    final MethodHandle getMapWorld;
    final MethodHandle getMapSaveLoad;
    final MethodHandle getLeafMapRegion;
    final MethodHandle getTilePool;
    final MethodHandle getOverlayManager;
    final MethodHandle getBlockStateShortShapeCache;
    final MethodHandle getMapRegionHighlightsPreparer;
    final MethodHandle getCaveModeDepthConfig;
    final MethodHandle isCacheOnlyMode;
    final MethodHandle getCurrentDimensionId;
    final MethodHandle isRegionDetectionComplete;
    final MethodHandle requestLoad;
    final MethodHandle writerThreadPauseSync;
    final MethodHandle regionIsWritingPaused;
    final MethodHandle getLoadState;
    final MethodHandle setLoadState;
    final MethodHandle isResting;
    final MethodHandle registerVisit;
    final MethodHandle setBeingWritten;
    final MethodHandle canRequestReload;
    final MethodHandle setAllCachePrepared;
    final MethodHandle regionGetChunk;
    final MethodHandle regionSetChunk;
    final MethodHandle newMapTileChunk;
    final MethodHandle tileChunkGetLoadState;
    final MethodHandle tileChunkSetLoadState;
    // ---- OPTIONAL surface (plan §16, the compatibility sweep): each group resolves
    // best-effort and is null when this Xaero lacks it — a miss never raises the
    // 1.42.0 floor, it just leaves that gate open (the pre-§16 behavior). ----
    /** {@code WorldMap.crashHandler} + {@code CrashHandler.getCrashedBy()}. */
    record CrashGate(MethodHandle crashHandler, MethodHandle getCrashedBy) {}
    /** The native ladder's settings read: {@code WorldMap.INSTANCE.getConfigs()
     *  .getClientConfigManager().getEffective(WorldMapProfiledConfigOptions.X)} —
     *  the channel/manager classes live in the jarjar'd xaerolib, bound by
     *  name+arity because its version differs per line. */
    record SettingsGate(MethodHandle instance, MethodHandle getConfigs,
                        MethodHandle getClientConfigManager, MethodHandle getEffective,
                        MethodHandle loadNewChunks, MethodHandle updateChunks,
                        MethodHandle getCurrentDimension, MethodHandle isUsingWorldSave) {}
    final CrashGate crashGate;
    final SettingsGate settingsGate;
    /** {@code MapProcessor.getCurrentCaveLayer()} — the layer the map RENDERS
     *  (Integer.MAX_VALUE = the surface). The bridge only ever writes the surface
     *  layer; while Xaero shows a cave layer (auto cave mode underground, the Nether
     *  by default) a write would go to a layer nobody renders yet still create
     *  regions, request loads and force saves (sweeps B m1 + C N1). Null = unbound. */
    final MethodHandle getCurrentCaveLayer;
    /** {@code MapTile.CURRENT_WORLD_INTERPRETATION_VERSION}, read live (a javac literal
     *  would silently lag a bump); 1 when unreadable. */
    final int interpretationVersion;
    /** Which optional groups did not bind, for the diag line; null = all bound. */
    final String optionalMissing;

    final MethodHandle tileChunkSetChanged;
    final MethodHandle tileChunkWasChanged;
    final MethodHandle tileChunkUpdateBuffers;
    final MethodHandle getWorldBlockTintProvider;
    final MethodHandle newMapUpdateFastConfig;
    final MethodHandle setHasHadTerrain;
    final MethodHandle includeInSave;
    final MethodHandle getLeafTexture;
    final MethodHandle shouldDownloadFromPBO;
    final MethodHandle getTile;
    final MethodHandle setTile;
    final MethodHandle poolGet;
    final MethodHandle setBlock;
    final MethodHandle getBlock;
    final MethodHandle tileIsLoaded;
    final MethodHandle setSlopeUnknown;
    final MethodHandle setWorldInterpretationVersion;
    final MethodHandle setWrittenCave;
    final MethodHandle setWrittenOnce;
    final MethodHandle setLoaded;
    final MethodHandle newMapBlock;
    final MethodHandle prepareForWriting;
    final MethodHandle blockWrite;
    final MethodHandle addOverlay;
    final MethodHandle newOverlay;
    final MethodHandle increaseOpacity;
    final MethodHandle getOriginal;
    final MethodHandle highlightsPrepare;

    static XaeroBindings resolve(XaeroSession.ClassResolver resolver) throws ClassNotFoundException,
            NoSuchMethodException, NoSuchFieldException, IllegalAccessException {
        return new XaeroBindings(resolver, MethodHandles.lookup());
    }

    private XaeroBindings(XaeroSession.ClassResolver resolver, MethodHandles.Lookup lookup)
            throws ClassNotFoundException, NoSuchMethodException, NoSuchFieldException,
            IllegalAccessException {
        Class<?> sessionClass = resolver.resolve("xaero.map.WorldMapSession");
        Class<?> processorClass = resolver.resolve("xaero.map.MapProcessor");
        Class<?> saveLoadClass = resolver.resolve("xaero.map.file.MapSaveLoad");
        Class<?> mapWorldClass = resolver.resolve("xaero.map.world.MapWorld");
        Class<?> regionClass = resolver.resolve("xaero.map.region.MapRegion");
        Class<?> tileChunkClass = resolver.resolve("xaero.map.region.MapTileChunk");
        Class<?> tileClass = resolver.resolve("xaero.map.region.MapTile");
        Class<?> blockClass = resolver.resolve("xaero.map.region.MapBlock");
        Class<?> overlayClass = resolver.resolve("xaero.map.region.Overlay");
        Class<?> overlayManagerClass = resolver.resolve("xaero.map.region.OverlayManager");
        Class<?> poolClass = resolver.resolve("xaero.map.pool.MapTilePool");
        Class<?> leafTextureClass = resolver.resolve("xaero.map.region.texture.LeafRegionTexture");
        Class<?> shapeCacheClass = resolver.resolve("xaero.map.cache.BlockStateShortShapeCache");
        Class<?> highlightsClass = resolver.resolve("xaero.map.highlight.MapRegionHighlightsPreparer");
        Class<?> tintProviderClass = resolver.resolve("xaero.map.biome.BlockTintProvider");
        Class<?> fastConfigClass = resolver.resolve("xaero.map.region.MapUpdateFastConfig");

        this.getCurrentSession = lookup.findStatic(sessionClass, "getCurrentSession",
                MethodType.methodType(sessionClass)).asType(MethodType.methodType(Object.class));
        this.sessionIsUsable = virtual(lookup, sessionClass, "isUsable",
                MethodType.methodType(boolean.class), boolean.class);
        this.getMapProcessor = virtual(lookup, sessionClass, "getMapProcessor",
                MethodType.methodType(processorClass), Object.class);

        this.renderThreadPauseSync = getter(lookup, processorClass, "renderThreadPauseSync");
        this.mainStuffSync = getter(lookup, processorClass, "mainStuffSync");
        this.mainWorld = getterByName(lookup, processorClass, "mainWorld");
        this.isWritingPaused = virtual(lookup, processorClass, "isWritingPaused",
                MethodType.methodType(boolean.class), boolean.class);
        this.isWaitingForWorldUpdate = virtual(lookup, processorClass, "isWaitingForWorldUpdate",
                MethodType.methodType(boolean.class), boolean.class);
        this.isCurrentMapLocked = virtual(lookup, processorClass, "isCurrentMapLocked",
                MethodType.methodType(boolean.class), boolean.class);
        this.isCurrentMultiworldWritable = virtual(lookup, processorClass,
                "isCurrentMultiworldWritable",
                MethodType.methodType(boolean.class), boolean.class);
        this.getCurrentWorldId = virtual(lookup, processorClass, "getCurrentWorldId",
                MethodType.methodType(String.class), Object.class);
        this.getCurrentDimension = virtual(lookup, processorClass, "getCurrentDimension",
                MethodType.methodType(String.class), String.class);
        this.getWorld = methodByName(lookup, processorClass, "getWorld", 0);
        this.ignoreWorld = methodByName(lookup, processorClass, "ignoreWorld", 1);
        this.getMapWorld = virtual(lookup, processorClass, "getMapWorld",
                MethodType.methodType(mapWorldClass), Object.class);
        this.getMapSaveLoad = virtual(lookup, processorClass, "getMapSaveLoad",
                MethodType.methodType(saveLoadClass), Object.class);
        this.getLeafMapRegion = lookup.findVirtual(processorClass, "getLeafMapRegion",
                        MethodType.methodType(regionClass, int.class, int.class, int.class, boolean.class))
                .asType(MethodType.methodType(Object.class, Object.class,
                        int.class, int.class, int.class, boolean.class));
        this.getTilePool = virtual(lookup, processorClass, "getTilePool",
                MethodType.methodType(poolClass), Object.class);
        this.getOverlayManager = virtual(lookup, processorClass, "getOverlayManager",
                MethodType.methodType(overlayManagerClass), Object.class);
        this.getBlockStateShortShapeCache = virtual(lookup, processorClass,
                "getBlockStateShortShapeCache",
                MethodType.methodType(shapeCacheClass), Object.class);
        this.getMapRegionHighlightsPreparer = virtual(lookup, processorClass,
                "getMapRegionHighlightsPreparer",
                MethodType.methodType(highlightsClass), Object.class);
        this.getCaveModeDepthConfig = virtual(lookup, processorClass, "getCaveModeDepthConfig",
                MethodType.methodType(int.class), int.class);
        this.getWorldBlockTintProvider = virtual(lookup, processorClass,
                "getWorldBlockTintProvider",
                MethodType.methodType(tintProviderClass), Object.class);
        this.newMapUpdateFastConfig = lookup.findConstructor(fastConfigClass,
                        MethodType.methodType(void.class, processorClass))
                .asType(MethodType.methodType(Object.class, Object.class));

        this.isCacheOnlyMode = virtual(lookup, mapWorldClass, "isCacheOnlyMode",
                MethodType.methodType(boolean.class), boolean.class);
        this.getCurrentDimensionId = virtual(lookup, mapWorldClass, "getCurrentDimensionId",
                MethodType.methodType(ResourceKey.class), Object.class);

        this.isRegionDetectionComplete = virtual(lookup, saveLoadClass, "isRegionDetectionComplete",
                MethodType.methodType(boolean.class), boolean.class);
        this.requestLoad = lookup.findVirtual(saveLoadClass, "requestLoad",
                        MethodType.methodType(void.class, regionClass, String.class))
                .asType(MethodType.methodType(void.class, Object.class, Object.class, String.class));

        this.writerThreadPauseSync = getter(lookup, regionClass, "writerThreadPauseSync");
        this.regionIsWritingPaused = virtual(lookup, regionClass, "isWritingPaused",
                MethodType.methodType(boolean.class), boolean.class);
        this.getLoadState = virtual(lookup, regionClass, "getLoadState",
                MethodType.methodType(byte.class), byte.class);
        this.setLoadState = lookup.findVirtual(regionClass, "setLoadState",
                        MethodType.methodType(void.class, byte.class))
                .asType(MethodType.methodType(void.class, Object.class, byte.class));
        this.isResting = virtual(lookup, regionClass, "isResting",
                MethodType.methodType(boolean.class), boolean.class);
        this.registerVisit = virtual(lookup, regionClass, "registerVisit",
                MethodType.methodType(void.class), void.class);
        this.setBeingWritten = lookup.findVirtual(regionClass, "setBeingWritten",
                        MethodType.methodType(void.class, boolean.class))
                .asType(MethodType.methodType(void.class, Object.class, boolean.class));
        this.canRequestReload = virtual(lookup, regionClass, "canRequestReload_unsynced",
                MethodType.methodType(boolean.class), boolean.class);
        this.setAllCachePrepared = lookup.findVirtual(regionClass, "setAllCachePrepared",
                        MethodType.methodType(void.class, boolean.class))
                .asType(MethodType.methodType(void.class, Object.class, boolean.class));
        this.regionGetChunk = lookup.findVirtual(regionClass, "getChunk",
                        MethodType.methodType(tileChunkClass, int.class, int.class))
                .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
        this.regionSetChunk = lookup.findVirtual(regionClass, "setChunk",
                        MethodType.methodType(void.class, int.class, int.class, tileChunkClass))
                .asType(MethodType.methodType(void.class, Object.class,
                        int.class, int.class, Object.class));

        this.newMapTileChunk = lookup.findConstructor(tileChunkClass,
                        MethodType.methodType(void.class, regionClass, int.class, int.class))
                .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
        this.tileChunkGetLoadState = virtual(lookup, tileChunkClass, "getLoadState",
                MethodType.methodType(int.class), int.class);
        this.tileChunkSetLoadState = lookup.findVirtual(tileChunkClass, "setLoadState",
                        MethodType.methodType(void.class, byte.class))
                .asType(MethodType.methodType(void.class, Object.class, byte.class));
        this.tileChunkSetChanged = lookup.findVirtual(tileChunkClass, "setChanged",
                        MethodType.methodType(void.class, boolean.class))
                .asType(MethodType.methodType(void.class, Object.class, boolean.class));
        this.tileChunkWasChanged = virtual(lookup, tileChunkClass, "wasChanged",
                MethodType.methodType(boolean.class), boolean.class);
        this.tileChunkUpdateBuffers = lookup.findVirtual(tileChunkClass, "updateBuffers",
                        MethodType.methodType(void.class, processorClass, tintProviderClass,
                                overlayManagerClass, boolean.class, shapeCacheClass,
                                fastConfigClass))
                .asType(MethodType.methodType(void.class, Object.class, Object.class,
                        Object.class, Object.class, boolean.class, Object.class, Object.class));
        this.setHasHadTerrain = virtual(lookup, tileChunkClass, "setHasHadTerrain",
                MethodType.methodType(void.class), void.class);
        this.includeInSave = virtual(lookup, tileChunkClass, "includeInSave",
                MethodType.methodType(boolean.class), boolean.class);
        this.getLeafTexture = virtual(lookup, tileChunkClass, "getLeafTexture",
                MethodType.methodType(leafTextureClass), Object.class);
        this.shouldDownloadFromPBO = virtual(lookup, leafTextureClass, "shouldDownloadFromPBO",
                MethodType.methodType(boolean.class), boolean.class);
        this.getTile = lookup.findVirtual(tileChunkClass, "getTile",
                        MethodType.methodType(tileClass, int.class, int.class))
                .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
        this.setTile = lookup.findVirtual(tileChunkClass, "setTile",
                        MethodType.methodType(void.class, int.class, int.class, tileClass,
                                shapeCacheClass, processorClass))
                .asType(MethodType.methodType(void.class, Object.class, int.class, int.class,
                        Object.class, Object.class, Object.class));

        this.poolGet = lookup.findVirtual(poolClass, "get",
                        MethodType.methodType(tileClass, String.class, int.class, int.class))
                .asType(MethodType.methodType(Object.class, Object.class,
                        String.class, int.class, int.class));
        this.getBlock = lookup.findVirtual(tileClass, "getBlock",
                        MethodType.methodType(blockClass, int.class, int.class))
                .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
        this.tileIsLoaded = virtual(lookup, tileClass, "isLoaded",
                MethodType.methodType(boolean.class), boolean.class);
        this.setSlopeUnknown = lookup.findVirtual(blockClass, "setSlopeUnknown",
                        MethodType.methodType(void.class, boolean.class))
                .asType(MethodType.methodType(void.class, Object.class, boolean.class));
        this.setBlock = lookup.findVirtual(tileClass, "setBlock",
                        MethodType.methodType(void.class, int.class, int.class, blockClass))
                .asType(MethodType.methodType(void.class, Object.class,
                        int.class, int.class, Object.class));
        this.setWorldInterpretationVersion = lookup.findVirtual(tileClass,
                        "setWorldInterpretationVersion",
                        MethodType.methodType(void.class, int.class))
                .asType(MethodType.methodType(void.class, Object.class, int.class));
        this.setWrittenCave = lookup.findVirtual(tileClass, "setWrittenCave",
                        MethodType.methodType(void.class, int.class, int.class))
                .asType(MethodType.methodType(void.class, Object.class, int.class, int.class));
        this.setWrittenOnce = lookup.findVirtual(tileClass, "setWrittenOnce",
                        MethodType.methodType(void.class, boolean.class))
                .asType(MethodType.methodType(void.class, Object.class, boolean.class));
        this.setLoaded = lookup.findVirtual(tileClass, "setLoaded",
                        MethodType.methodType(void.class, boolean.class))
                .asType(MethodType.methodType(void.class, Object.class, boolean.class));

        this.newMapBlock = lookup.findConstructor(blockClass, MethodType.methodType(void.class))
                .asType(MethodType.methodType(Object.class));
        this.prepareForWriting = lookup.findVirtual(blockClass, "prepareForWriting",
                        MethodType.methodType(void.class, int.class))
                .asType(MethodType.methodType(void.class, Object.class, int.class));
        this.blockWrite = lookup.findVirtual(blockClass, "write",
                        MethodType.methodType(void.class, BlockState.class, int.class, int.class,
                                ResourceKey.class, byte.class, boolean.class, boolean.class))
                .asType(MethodType.methodType(void.class, Object.class, BlockState.class,
                        int.class, int.class, ResourceKey.class, byte.class,
                        boolean.class, boolean.class));
        this.addOverlay = lookup.findVirtual(blockClass, "addOverlay",
                        MethodType.methodType(void.class, overlayClass))
                .asType(MethodType.methodType(void.class, Object.class, Object.class));

        this.newOverlay = lookup.findConstructor(overlayClass,
                        MethodType.methodType(void.class, BlockState.class, byte.class, boolean.class))
                .asType(MethodType.methodType(Object.class, BlockState.class,
                        byte.class, boolean.class));
        this.increaseOpacity = lookup.findVirtual(overlayClass, "increaseOpacity",
                        MethodType.methodType(void.class, int.class))
                .asType(MethodType.methodType(void.class, Object.class, int.class));
        this.getOriginal = lookup.findVirtual(overlayManagerClass, "getOriginal",
                        MethodType.methodType(overlayClass, overlayClass))
                .asType(MethodType.methodType(Object.class, Object.class, Object.class));
        this.highlightsPrepare = lookup.findVirtual(highlightsClass, "prepare",
                        MethodType.methodType(void.class, regionClass, int.class, int.class,
                                boolean.class))
                .asType(MethodType.methodType(void.class, Object.class, Object.class,
                        int.class, int.class, boolean.class));

        // ---- optional groups ----
        StringBuilder missing = new StringBuilder();
        CrashGate crash = null;
        try {
            Class<?> worldMapClass = resolver.resolve("xaero.map.WorldMap");
            Class<?> crashHandlerClass = resolver.resolve("xaero.map.CrashHandler");
            crash = new CrashGate(
                    lookup.unreflectGetter(worldMapClass.getField("crashHandler"))
                            .asType(MethodType.methodType(Object.class)),
                    methodByName(lookup, crashHandlerClass, "getCrashedBy", 0));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            missing.append("crash-gate ");
        }
        SettingsGate settings = null;
        try {
            Class<?> worldMapClass = resolver.resolve("xaero.map.WorldMap");
            Class<?> channelClass = resolver.resolve("xaero.lib.common.config.channel.ConfigChannel");
            Class<?> managerClass = resolver.resolve("xaero.lib.client.config.ClientConfigManager");
            Class<?> optionsClass = resolver.resolve("xaero.map.common.config.option.WorldMapProfiledConfigOptions");
            Class<?> dimensionClass = resolver.resolve("xaero.map.world.MapDimension");
            settings = new SettingsGate(
                    lookup.unreflectGetter(worldMapClass.getField("INSTANCE"))
                            .asType(MethodType.methodType(Object.class)),
                    methodByName(lookup, worldMapClass, "getConfigs", 0),
                    methodByName(lookup, channelClass, "getClientConfigManager", 0),
                    methodByName(lookup, managerClass, "getEffective", 1),
                    lookup.unreflectGetter(optionsClass.getField("LOAD_NEW_CHUNKS"))
                            .asType(MethodType.methodType(Object.class)),
                    lookup.unreflectGetter(optionsClass.getField("UPDATE_CHUNKS"))
                            .asType(MethodType.methodType(Object.class)),
                    virtual(lookup, mapWorldClass, "getCurrentDimension",
                            MethodType.methodType(dimensionClass), Object.class),
                    virtual(lookup, dimensionClass, "isUsingWorldSave",
                            MethodType.methodType(boolean.class), boolean.class));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            missing.append("settings-gate ");
        }
        int version = 1;
        try {
            version = tileClass.getField("CURRENT_WORLD_INTERPRETATION_VERSION").getInt(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            missing.append("interpretation-version ");
        }
        MethodHandle caveLayer = null;
        try {
            caveLayer = virtual(lookup, processorClass, "getCurrentCaveLayer",
                    MethodType.methodType(int.class), int.class);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            missing.append("cave-layer ");
        }
        this.getCurrentCaveLayer = caveLayer;
        this.crashGate = crash;
        this.settingsGate = settings;
        this.interpretationVersion = version;
        this.optionalMissing = missing.isEmpty() ? null : missing.toString().trim();
        if (this.optionalMissing != null) {
            LSSLogger.warn("Xaero map bridge: optional Xaero surface not bound on this version ("
                    + this.optionalMissing + ") — those gates stay open");
        }
    }

    /** Exact-typed no-arg virtual, adapted to an Object receiver. */
    private static MethodHandle virtual(MethodHandles.Lookup lookup, Class<?> owner,
                                        String name, MethodType type, Class<?> genericReturn)
            throws NoSuchMethodException, IllegalAccessException {
        return lookup.findVirtual(owner, name, type)
                .asType(MethodType.methodType(genericReturn, Object.class));
    }

    private static MethodHandle getter(MethodHandles.Lookup lookup, Class<?> owner, String name)
            throws NoSuchFieldException, IllegalAccessException {
        return lookup.findGetter(owner, name, Object.class)
                .asType(MethodType.methodType(Object.class, Object.class));
    }

    /** Field getter tolerant of the declared type (mainWorld is ClientLevel-typed). */
    private static MethodHandle getterByName(MethodHandles.Lookup lookup, Class<?> owner,
                                             String name)
            throws NoSuchFieldException, IllegalAccessException {
        var field = owner.getField(name);
        return lookup.unreflectGetter(field)
                .asType(MethodType.methodType(Object.class, Object.class));
    }

    /**
     * Name+arity scan for the ClientLevel-typed boundary methods — the exact
     * parameter/return types stay whatever the class declares, so the stub
     * classes can declare them as Object (tests cannot construct a ClientLevel).
     */
    private static MethodHandle methodByName(MethodHandles.Lookup lookup, Class<?> owner,
                                             String name, int paramCount)
            throws NoSuchMethodException, IllegalAccessException {
        Method found = null;
        for (Method m : owner.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount
                    && !m.isSynthetic() && !m.isBridge()) {
                found = m;
                break;
            }
        }
        if (found == null) {
            throw new NoSuchMethodException(owner.getName() + "." + name + "/" + paramCount);
        }
        var handle = lookup.unreflect(found);
        var generic = MethodType.genericMethodType(paramCount + 1);
        if (found.getReturnType() == boolean.class) {
            generic = generic.changeReturnType(boolean.class);
        }
        return handle.asType(generic);
    }
}
