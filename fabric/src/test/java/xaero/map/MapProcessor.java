package xaero.map;

import xaero.map.biome.BlockTintProvider;
import xaero.map.cache.BlockStateShortShapeCache;
import xaero.map.file.MapSaveLoad;
import xaero.map.highlight.MapRegionHighlightsPreparer;
import xaero.map.pool.MapTilePool;
import xaero.map.region.MapRegion;
import xaero.map.region.OverlayManager;
import xaero.map.world.MapWorld;

/** Tier-1 stub — the gate flags default to the ladder-ready state. getWorld/mainWorld
 *  are Object-typed (a real ClientLevel is unconstructible under fabric-loader-junit;
 *  the compat resolves these by name-scan and reads them behind LevelOps). */
public class MapProcessor {
    public final Object renderThreadPauseSync = new Object();
    public final Object mainStuffSync = new Object();
    public Object mainWorld;
    public Object world;
    public boolean writingPaused;
    public boolean waitingForWorldUpdate;
    public boolean currentMapLocked;
    public boolean multiworldWritable = true;
    public String currentWorldId = "stub-world";
    public boolean ignoreWorldResult;
    public MapWorld mapWorld = new MapWorld();
    public MapSaveLoad saveLoad = new MapSaveLoad();
    public MapTilePool tilePool = new MapTilePool();
    public OverlayManager overlayManager = new OverlayManager();
    public BlockStateShortShapeCache shapeCache = new BlockStateShortShapeCache();
    public BlockTintProvider tintProvider = new BlockTintProvider();
    public MapRegionHighlightsPreparer highlightsPreparer = new MapRegionHighlightsPreparer();
    public final java.util.Map<Long, MapRegion> regions = new java.util.HashMap<>();
    public boolean leafMapRegionReturnsNull;
    /** LoadState for regions getLeafMapRegion auto-creates. FAITHFUL default 0: the
     *  real detection creates fresh regions UNLOADED (panel fold, 2026-09-05 — a
     *  loaded-by-default stub let tests pass while the real jar answers awaiting).
     *  XaeroMapCompatTest's setUp sets 2 to keep plain commit tests short; tests of
     *  the load dance and the owed set set 0 explicitly. */
    public byte createdRegionLoadState = 0;

    public boolean isWritingPaused() {
        if (!Thread.holdsLock(this.renderThreadPauseSync)) {
            throw new IllegalStateException("isWritingPaused outside renderThreadPauseSync —"
                    + " the native onRender ladder's outer monitor");
        }
        return this.writingPaused;
    }

    public boolean isWaitingForWorldUpdate() { return this.waitingForWorldUpdate; }

    public boolean isCurrentMapLocked() { return this.currentMapLocked; }

    public boolean isCurrentMultiworldWritable() { return this.multiworldWritable; }

    public String getCurrentWorldId() { return this.currentWorldId; }

    public String getCurrentDimension() { return "placeholder"; }

    public Object getWorld() { return this.world; }

    public boolean ignoreWorld(Object world) { return this.ignoreWorldResult; }

    public MapWorld getMapWorld() { return this.mapWorld; }

    public MapSaveLoad getMapSaveLoad() { return this.saveLoad; }

    public MapRegion getLeafMapRegion(int caveLayer, int regX, int regZ, boolean create) {
        dev.vox.lss.compat.XaeroStubEvents.record(
                "processor.getLeafMapRegion layer=" + caveLayer + " " + regX + "," + regZ);
        if (this.leafMapRegionReturnsNull) return null;
        long key = ((long) regX << 32) | (regZ & 0xFFFFFFFFL);
        var existing = this.regions.get(key);
        if (existing == null && create) {
            existing = new MapRegion();
            existing.loadState = this.createdRegionLoadState;
            this.regions.put(key, existing);
        }
        return existing;
    }

    public MapTilePool getTilePool() { return this.tilePool; }

    public OverlayManager getOverlayManager() { return this.overlayManager; }

    public BlockStateShortShapeCache getBlockStateShortShapeCache() { return this.shapeCache; }

    public BlockTintProvider getWorldBlockTintProvider() { return this.tintProvider; }

    public MapRegionHighlightsPreparer getMapRegionHighlightsPreparer() { return this.highlightsPreparer; }

    public int getCaveModeDepthConfig() { return 30; }
    /** The rendered layer (Integer.MAX_VALUE = surface); a cave layer makes the bridge wait. */
    public int currentCaveLayer = Integer.MAX_VALUE;

    public int getCurrentCaveLayer() { return this.currentCaveLayer; }
}
