package dev.vox.lss.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.platform.SectionConstruction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.region.MapBlock;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTile;

/**
 * Phase B-I of the cave-layers plan against the Tier-1 stubs: every column writes the surface
 * plus the layer Xaero shows, and every key the bridge holds carries the layer (§B5) — the
 * queue, the drain's region buckets, waiting regions and load grants, the owed set and its
 * release lookup, the awaiting-region classifier, the open-region window and the rebuild key.
 * Plus the receipts (one lease per reliable tile, one report per column — handle and
 * null-handle paths), the layer-switch commit, the natively-writable skip by layer and
 * distance, world-save mode, the missing-binding fallback and the dimension-mismatch band.
 */
class XaeroCaveLayersTest {
    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld"));
    private static final ResourceKey<Level> NETHER =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:the_nether"));
    private static final XaeroTileExtractor.LevelFacts NETHER_FACTS =
            new XaeroTileExtractor.LevelFacts(0, 256, false, true, 128);
    private static final int SURFACE = Integer.MAX_VALUE;
    private static final int FULL = Integer.MIN_VALUE;

    // LINE FLAVOR (1.21.1): no palette factory type on this MC — the SectionConstruction
    // seam's construction parameter is the biome Registry (see that class's javadoc).
    private static net.minecraft.core.Registry<Biome> FACTORY;
    private static VoxelColumnData NETHER_COLUMN;

    private MapProcessor processor;
    private final Object worldToken = new Object();
    private final Set<Long> loadedChunks = new HashSet<>();
    private final List<Object[]> reports = new ArrayList<>();
    private ResourceKey<Level> clientDimension = NETHER;
    private XaeroSession.PlayerPos player;
    private long clockMillis = 1_000_000L;
    private XaeroSession bridge;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var biomeLookup = VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        biomeLookup.listElements().forEach(ref -> biomes.register(ref.key(), ref.value(), RegistrationInfo.BUILT_IN));
        biomes.freeze();
        FACTORY = biomes;
        NETHER_COLUMN = netherColumn();
    }

    /** Bedrock at 0 and 127, a netherrack floor up to 40, a roof from 100. */
    private static VoxelColumnData netherColumn() {
        var sections = new ArrayList<VoxelColumnData.SectionData>();
        for (int sy = 0; sy < 8; sy++) {
            var section = SectionConstruction.empty(FACTORY);
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        int wy = (sy << 4) + y;
                        BlockState state = wy == 0 || wy == 127 ? Blocks.BEDROCK.defaultBlockState()
                                : wy <= 40 || wy >= 100 ? Blocks.NETHERRACK.defaultBlockState() : null;
                        if (state != null) section.setBlockState(x, y, z, state);
                    }
                }
            }
            sections.add(new VoxelColumnData.SectionData(sy, section, null, null));
        }
        return new VoxelColumnData(sections.toArray(new VoxelColumnData.SectionData[0]), 1L);
    }

    @BeforeEach
    void setUp() throws Exception {
        XaeroStubEvents.clear();
        this.processor = new MapProcessor();
        this.processor.createdRegionLoadState = 2;
        this.processor.world = this.worldToken;
        this.processor.mainWorld = this.worldToken;
        this.processor.mapWorld.currentDimensionId = NETHER;
        var session = new WorldMapSession();
        session.processor = this.processor;
        WorldMapSession.current = session;
        this.clientDimension = NETHER;
        this.player = null;
        this.bridge = newBridge(XaeroBindings.resolve(Class::forName));
    }

    private XaeroSession newBridge(XaeroBindings h) {
        var bridge = new XaeroSession(h, new XaeroSession.LevelOps() {
            @Override
            public Object dimension(Object world) {
                return clientDimension;
            }

            @Override
            public boolean isChunkLoaded(Object world, int chunkX, int chunkZ) {
                return loadedChunks.contains(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
            }
        }, () -> true, () -> true, c -> {}, c -> {}, () -> true,
                (dimension, chunkX, chunkZ) -> this.reports.add(new Object[]{dimension, chunkX, chunkZ}));
        bridge.pumpNanosBudget = Long.MAX_VALUE;
        bridge.updateNanosBudget = Long.MAX_VALUE;
        bridge.bpClock = () -> this.clockMillis;
        bridge.playerProbe = () -> this.player;
        return bridge;
    }

    @AfterEach
    void tearDown() {
        WorldMapSession.current = null;
        xaero.map.common.config.option.WorldMapProfiledConfigOptions.WRITING_DISTANCE.value = -1;
        XaeroStubEvents.clear();
    }

    // ---- helpers ----

    /** Xaero shows {@code layer}; one pump publishes it (and drains whatever is queued). */
    private void show(int layer) {
        this.processor.currentCaveLayer = layer;
        this.bridge.pump();
    }

    private void offer(int chunkX, int chunkZ) {
        this.bridge.offerColumn(NETHER, chunkX, chunkZ, NETHER_FACTS, NETHER_COLUMN);
    }

    private MapRegion caveRegion(int layer, int regionX, int regionZ) {
        var regions = this.processor.caveRegions.get(layer);
        return regions == null ? null : regions.get(((long) regionX << 32) | (regionZ & 0xFFFFFFFFL));
    }

    private MapRegion surfaceRegion(int regionX, int regionZ) {
        return this.processor.regions.get(((long) regionX << 32) | (regionZ & 0xFFFFFFFFL));
    }

    private static MapTile tileAt(MapRegion region, int chunkX, int chunkZ) {
        return region.getChunk((chunkX >> 2) & 7, (chunkZ >> 2) & 7).getTile(chunkX & 3, chunkZ & 3);
    }

    /** A delivery handle: counts leases, releases, reports and "the map has it". */
    private static final class Handle implements LSSApi.IngestFailureHandle {
        int leases;
        int released;
        int reports;
        int written;
        /** A "written" that came after every lease was released would arrive after the
         *  delivery ended, which is when the map's debt is decided. */
        boolean writtenTooLate;

        @Override
        public void report() {
            this.reports++;
        }

        @Override
        public void written() {
            this.written++;
            if (this.leases > 0 && this.released == this.leases) this.writtenTooLate = true;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public Runnable deferAcceptance() {
            this.leases++;
            var done = new AtomicBoolean();
            return () -> {
                if (done.compareAndSet(false, true)) this.released++;
            };
        }
    }

    private void offerWith(Handle handle, int chunkX, int chunkZ) {
        LSSApi.withIngestFailureHandle(NETHER, chunkX, chunkZ, handle, () -> offer(chunkX, chunkZ));
    }

    // ---- which layers a column writes ----

    @Test
    void aColumnWritesTheSurfaceAndTheBandXaeroShows() {
        show(3);
        offer(64, 64);
        assertTrue(this.bridge.hasQueuedForTest(SURFACE, 64, 64));
        assertTrue(this.bridge.hasQueuedForTest(3, 64, 64));
        assertEquals(2, this.bridge.queuedForTest(), "(layer, chunk) keys: two entries for one column");
        this.bridge.pump();
        assertEquals(2, this.bridge.counterForTest("written"));
        assertEquals(1, this.bridge.counterForTest("written_cave"));
        assertTrue(XaeroStubEvents.snapshot().contains("processor.getLeafMapRegion layer=3 2,2"),
                "the band tile fetched its own layer's region: " + XaeroStubEvents.snapshot());
        MapTile band = tileAt(caveRegion(3, 2, 2), 64, 64);
        assertEquals(63, band.writtenCaveStart, "setWrittenCave takes the band TOP, never the layer number");
        assertEquals(30, band.writtenCaveDepth);
        MapBlock bandPixel = band.getBlock(0, 0);
        assertTrue(bandPixel.cave, "the real cave flag reaches MapBlock.write");
        assertEquals(40, bandPixel.height, "the band window [34, 63] floors on the netherrack at 40");
        MapTile surface = tileAt(surfaceRegion(2, 2), 64, 64);
        assertEquals(SURFACE, surface.writtenCaveStart);
        assertFalse(surface.getBlock(0, 0).cave);
        assertEquals(127, surface.getBlock(0, 0).height, "the surface sees the bedrock roof");
        assertTrue(this.bridge.describe().contains(", written_cave=1, layer=3, mode=layered,"), this.bridge.describe());
    }

    @Test
    void fullModeWritesTheFullLayer() {
        show(FULL);
        offer(64, 64);
        assertTrue(this.bridge.hasQueuedForTest(FULL, 64, 64));
        this.bridge.pump();
        MapTile full = tileAt(caveRegion(FULL, 2, 2), 64, 64);
        assertEquals(FULL, full.writtenCaveStart);
        assertEquals(40, full.getBlock(3, 3).height, "Full: the bedrock roof is ground, the cave floor shows");
        assertTrue(this.bridge.describe().contains(", layer=full, mode=full,"), this.bridge.describe());
    }

    @Test
    void theSurfaceViewWritesOnlyTheSurface() {
        show(SURFACE);
        offer(64, 64);
        assertEquals(1, this.bridge.queuedForTest());
        this.bridge.pump();
        assertEquals(0, this.bridge.counterForTest("written_cave"));
        assertTrue(this.processor.caveRegions.isEmpty(), "no cave layer touched");
    }

    @Test
    void aTileCommitsTheDepthItWasExtractedWith() {
        this.processor.caveModeDepthConfig = 50;
        show(3);
        offer(64, 64);
        this.processor.caveModeDepthConfig = 20; // the config moves before the commit
        this.bridge.pump();
        assertEquals(50, tileAt(caveRegion(3, 2, 2), 64, 64).writtenCaveDepth);
        assertEquals(50, tileAt(surfaceRegion(2, 2), 64, 64).writtenCaveDepth);
        assertEquals(40, tileAt(caveRegion(3, 2, 2), 64, 64).getBlock(0, 0).height,
                "the depth-50 window [14, 63] floors at 40");
    }

    @Test
    void aQueuedBandTileCommitsToItsOwnLayerAfterTheViewMoves() {
        this.processor.currentMapLocked = true; // holds the drain; the plan still publishes
        show(3);
        offer(64, 64);
        this.processor.currentMapLocked = false;
        show(5); // the player climbed two bands before the drain ran
        assertEquals(2, this.bridge.counterForTest("written"));
        assertNotNull(caveRegion(3, 2, 2), "valid data: it commits to the layer it was extracted for");
        assertNull(caveRegion(5, 2, 2), "the newly shown band is not backfilled (§B2)");
        assertEquals(63, tileAt(caveRegion(3, 2, 2), 64, 64).writtenCaveStart);
    }

    // ---- keys carry the layer ----

    @Test
    void latestWinsPerLayer() {
        show(3);
        offer(64, 64);
        offer(64, 64);
        assertEquals(2, this.bridge.queuedForTest(), "each layer's entry is replaced in place");
        assertEquals(0, this.bridge.counterForTest("dropped_overflow"));
    }

    @Test
    void thePreGateLooksForEveryLayerKeyAndDropsBothTilesOnce() {
        show(3);
        this.bridge.maxQueue = 2;
        offer(64, 64); // fills the queue: surface + band
        offer(64, 64); // full, but its keys are queued: an in-place replacement, extracted
        assertEquals(0, this.bridge.counterForTest("dropped_overflow"));
        offer(65, 64); // full, none of its keys queued: both tiles drop before extraction
        assertEquals(2, this.bridge.counterForTest("dropped_overflow"), "counted per tile");
        assertEquals(1, this.reports.size(), "but one re-serve per column (the null-handle CAS)");
        assertEquals(65, this.reports.get(0)[1]);
    }

    @Test
    void theDrainBucketsGrantsAndWindowAreLayerKeyed() {
        this.processor.createdRegionLoadState = 0;
        this.bridge.openRegionCap = 4;
        show(3);
        offer(64, 64);
        this.bridge.pump();
        assertEquals(2, this.bridge.counterForTest("load_requests"), "one grant per (layer, region)");
        assertEquals(2, this.processor.saveLoad.loadRequests.size());
        var layers = new HashSet<Integer>();
        for (var r : this.processor.saveLoad.loadRequests) layers.add(r.caveLayer);
        assertEquals(Set.of(SURFACE, 3), layers, "each layer's own region was requested");
        assertEquals(Set.of(XaeroSession.surfaceRegion(XaeroSession.regionKeyOf(64, 64)),
                        new XaeroSession.RegionKey(3, XaeroSession.regionKeyOf(64, 64))),
                this.bridge.awaitingRegionsForTest(), "the evictor's classifier is layer-keyed");
        assertEquals(Set.of(XaeroSession.surfaceRegion(XaeroSession.regionKeyOf(64, 64)),
                        new XaeroSession.RegionKey(3, XaeroSession.regionKeyOf(64, 64))),
                this.bridge.openRegions.keySet(), "the window reserves a slot per (layer, region)");
    }

    @Test
    void theWindowCountsEachLayersRegion() {
        this.bridge.openRegionCap = 1;
        show(3);
        offer(64, 64);
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"), "the second layer's region waits for a slot");
        assertEquals(1, this.bridge.queuedForTest());
        assertTrue(this.bridge.windowWaits.get() > 0);
    }

    @Test
    void theRebuildKeyCarriesTheLayer() {
        show(3);
        offer(64, 64);
        this.bridge.updateIdlePumps = 1_000;
        this.bridge.pump();
        assertEquals(2, this.bridge.counterForTest("pending_updates"),
                "the surface and band tile chunks share coords but owe separate rebuilds");
    }

    @Test
    void theOwedSetIsLayerKeyedAndReportsOncePerColumn() {
        this.processor.createdRegionLoadState = 0;
        this.bridge.maxQueueBytes = 2 * 4800; // two overlay-free tiles: each enqueue evicts the oldest
        show(3);
        offer(64, 64);
        this.bridge.pump(); // both regions await their load: both layers classified awaiting
        this.processor.createdRegionLoadState = 2; // the evictors' own regions load at once, so
        // the queue drains below the halt occupancy (a release needs room to land in)
        offer(96, 64);      // evicts BOTH tiles of (64,64): each owed in its own layer's region
        assertEquals(2, this.bridge.counterForTest("owed"));
        assertEquals(2, this.bridge.counterForTest("owed_regions"));
        assertTrue(this.reports.isEmpty());
        // The band region loads first: its debt releases and reports the column...
        var band = caveRegion(3, 2, 2);
        band.loadState = 2;
        band.canRequestReload = true;
        for (int i = 0; i < 8; i++) this.bridge.pump();
        assertEquals(1, this.reports.size(), "the released band debt re-serves the column");
        // ...then the surface region: its debt is the same delivery — no second re-serve.
        var surface = surfaceRegion(2, 2);
        surface.loadState = 2;
        surface.canRequestReload = true;
        for (int i = 0; i < 8; i++) this.bridge.pump();
        assertEquals(1, this.reports.size(), "one re-serve per column, however many tiles dropped");
        assertEquals(1, this.bridge.counterForTest("drops_reported"));
    }

    @Test
    void aFreshTilePaysOnlyItsOwnLayersDebt() {
        show(3);
        this.bridge.oweExpired(NETHER, ((long) 64 << 32) | 64, XaeroSession.regionKeyOf(64, 64)); // surface debt
        assertEquals(1, this.bridge.counterForTest("owed"));
        this.processor.currentMapLocked = true; // keep the fresh tiles queued
        show(5);
        show(SURFACE);
        assertEquals(1, this.bridge.counterForTest("owed"));
        var h = this.bridge.acquisition;
        var band = XaeroTileExtractor.extract(64, 64, NETHER_FACTS, NETHER_COLUMN,
                List.of(XaeroTileExtractor.LayerTarget.band(3, 255, 30)),
                false).get(0);
        h.offerPrepared(NETHER, band);
        assertEquals(1, this.bridge.counterForTest("owed"), "a band tile does not pay the surface debt");
        offer(64, 64); // brings a fresh SURFACE tile
        assertEquals(0, this.bridge.counterForTest("owed"));
        this.processor.currentMapLocked = false;
    }

    // ---- receipts ----

    @Test
    void eachReliableTileTakesItsOwnLeaseAndTheColumnReportsOnce() {
        show(3);
        var committed = new Handle();
        offerWith(committed, 64, 64);
        assertEquals(2, committed.leases, "one deferAcceptance lease per reliable tile");
        assertEquals(0, committed.released);
        this.bridge.pump();
        assertEquals(2, committed.released, "each commit releases its own lease");

        var dropped = new Handle();
        offerWith(dropped, 68, 64);
        assertEquals(2, dropped.leases);
        this.processor.mapWorld.currentDimensionId = OVERWORLD; // the player left: both tiles stale
        this.clientDimension = OVERWORLD;
        this.bridge.pump();
        assertEquals(2, this.bridge.counterForTest("dropped_stale"));
        assertEquals(1, dropped.reports, "both tiles dropped, one re-serve");
        assertEquals(2, dropped.released, "and no lease is orphaned");
    }

    @Test
    void theNullHandlePathReportsOncePerColumnToo() {
        show(3);
        offer(64, 64);
        this.processor.mapWorld.currentDimensionId = OVERWORLD;
        this.clientDimension = OVERWORLD;
        this.bridge.pump();
        assertEquals(2, this.bridge.counterForTest("dropped_stale"));
        assertEquals(1, this.reports.size(), "the receipt's CAS dedupes the DropReporter path");
    }

    // ---- the map's debt (issue #380): which deliveries learn that the map has the column ----

    /** The surface tile is "the map has this column": said once per delivery however many
     *  layers were written, and before the delivery's last lease is released. */
    @Test
    void aWrittenSurfaceTellsItsDeliveryOnceAndInTime() {
        show(3);
        var handle = new Handle();
        offerWith(handle, 64, 64); // the surface and the band
        assertEquals(2, handle.leases, "premise: two tiles");
        assertEquals(0, handle.written, "nothing is said for a tile that is only queued");
        this.bridge.pump();
        assertEquals(2, this.bridge.counterForTest("written"), "premise: both committed");
        assertEquals(1, handle.written, "the surface tile says it, the band tile does not");
        assertFalse(handle.writtenTooLate);
        assertEquals(2, handle.released);
    }

    /** Chunks Xaero's own writer covers are on the map from the real chunks: not owed. */
    @Test
    void aSurfaceTheNativeWriterCoversCountsAsOnTheMap() {
        for (int x = 60; x <= 80; x++) {
            for (int z = 60; z <= 70; z++) this.loadedChunks.add(((long) x << 32) | (z & 0xFFFFFFFFL));
        }
        this.player = new XaeroSession.PlayerPos(NETHER, 64 * 16 + 8, 70, 64 * 16 + 8);
        show(SURFACE);
        var handle = new Handle();
        offerWith(handle, 64, 64);
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("skipped_native"), "premise: the native writer's");
        assertEquals(0, this.bridge.counterForTest("written"));
        assertEquals(1, handle.written);
        assertFalse(handle.writtenTooLate);
        assertEquals(1, handle.released);
    }

    /** A tile the bridge lets go says nothing: its delivery ends without "written", which
     *  is what leaves the map owed the column. Each of these was a silent hole in the map
     *  that nothing recorded. */
    @Test
    void aColumnTheBridgeLetsGoNeverSaysItIsOnTheMap() {
        show(SURFACE);
        var cleared = new Handle();
        offerWith(cleared, 68, 64);
        assertEquals(1, cleared.leases, "premise: queued");
        // Xaero's own two map-writing switches go off while the tile is queued.
        xaero.map.common.config.option.WorldMapProfiledConfigOptions.LOAD_NEW_CHUNKS.value = false;
        xaero.map.common.config.option.WorldMapProfiledConfigOptions.UPDATE_CHUNKS.value = false;
        try {
            this.bridge.pump(); // the ladder observes both switches off and clears the queue
            assertTrue(this.bridge.settingsWritesOff, "premise");
            assertEquals(0, this.bridge.queuedForTest(), "premise: the queue was cleared");
            assertEquals(0, this.bridge.counterForTest("written"));
            assertEquals(1, cleared.released);
            assertEquals(0, cleared.written);
            assertEquals(0, cleared.reports, "and it is not sent again by itself either");

            // While they are off, a column that arrives is let go as well.
            var refused = new Handle();
            offerWith(refused, 64, 64);
            this.bridge.pump();
            assertEquals(0, this.bridge.counterForTest("written"));
            assertEquals(refused.leases, refused.released);
            assertEquals(0, refused.written);
            assertEquals(0, refused.reports);
        } finally {
            xaero.map.common.config.option.WorldMapProfiledConfigOptions.LOAD_NEW_CHUNKS.value = true;
            xaero.map.common.config.option.WorldMapProfiledConfigOptions.UPDATE_CHUNKS.value = true;
        }
    }

    /** A tile dropped because the player left its dimension is reported for a re-serve, as
     *  before, and not as written. */
    @Test
    void aReportedDropIsNotOnTheMap() {
        show(SURFACE);
        var handle = new Handle();
        offerWith(handle, 64, 64);
        this.processor.mapWorld.currentDimensionId = OVERWORLD;
        this.clientDimension = OVERWORLD;
        this.bridge.pump();
        assertEquals(1, handle.reports, "premise: reported");
        assertEquals(0, handle.written);
        assertEquals(1, handle.released);
    }

    // ---- the natively-writable skip ----

    @Test
    void theNativeSkipAppliesOnlyToTheLayerXaeroWritesWithinItsReach() {
        for (int x = 60; x <= 90; x++) {
            for (int z = 60; z <= 70; z++) this.loadedChunks.add(((long) x << 32) | (z & 0xFFFFFFFFL));
        }
        this.player = new XaeroSession.PlayerPos(NETHER, 64 * 16 + 8, 70, 64 * 16 + 8);
        show(3);
        offer(64, 64); // loaded and surrounded, at the player
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("skipped_native"), "the band is the native writer's here");
        assertTrue(this.bridge.hasQueuedForTest(3, 64, 64) || caveRegion(3, 2, 2) == null
                || tileAt(caveRegion(3, 2, 2), 64, 64) == null, "not bridge-written");
        assertNotNull(tileAt(surfaceRegion(2, 2), 64, 64),
                "the surface is not the native writer's layer in a cave view: bridge-written");
        offer(84, 64); // 20 chunks out: beyond the native cave reach min(16, write distance)
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("skipped_native"));
        assertNotNull(tileAt(caveRegion(3, 2, 2), 84, 64), "beyond the native reach the bridge writes the band");
    }

    @Test
    void theSurfaceSkipHonorsTheWriteDistance() {
        for (int x = 60; x <= 80; x++) {
            for (int z = 60; z <= 70; z++) this.loadedChunks.add(((long) x << 32) | (z & 0xFFFFFFFFL));
        }
        this.player = new XaeroSession.PlayerPos(NETHER, 64 * 16 + 8, 70, 64 * 16 + 8);
        xaero.map.common.config.option.WorldMapProfiledConfigOptions.WRITING_DISTANCE.value = 4;
        show(SURFACE);
        offer(66, 64); // within 4: skipped
        offer(70, 64); // 6 out: written by the bridge
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("skipped_native"));
        assertEquals(1, this.bridge.counterForTest("written"));
        assertNotNull(tileAt(surfaceRegion(2, 2), 70, 64));
    }

    // ---- world-save mode, missing bindings, dimension mismatch ----

    @Test
    void worldSaveModeTurnsCaveWritesOff() {
        this.processor.mapWorld.currentDimension.usingWorldSave = true;
        show(3);
        offer(64, 64);
        assertEquals(1, this.bridge.queuedForTest(), "the surface only");
        assertTrue(this.bridge.describe().contains("mode=layered(cave_off:world_save)"), this.bridge.describe());
        // A cave tile queued before world-save mode came on is dropped at the drain.
        this.processor.mapWorld.currentDimension.usingWorldSave = false;
        this.processor.currentMapLocked = true;
        show(3);
        offer(68, 64);
        assertTrue(this.bridge.hasQueuedForTest(3, 68, 64));
        this.processor.mapWorld.currentDimension.usingWorldSave = true;
        this.processor.currentMapLocked = false;
        show(3);
        assertFalse(this.bridge.hasQueuedForTest(3, 68, 64));
        assertNull(caveRegion(3, 2, 2), "no cave layer written in world-save mode");
    }

    @Test
    void withoutTheCaveBindingsTheSurfaceWritesAsBefore() throws Exception {
        this.bridge = newBridge(XaeroBindings.resolve(name -> {
            if (name.equals("xaero.map.world.MapDimension")) throw new ClassNotFoundException(name);
            return Class.forName(name);
        }));
        assertNull(this.bridge.h.caveGate);
        show(3);
        offer(64, 64);
        assertEquals(1, this.bridge.queuedForTest(), "no cave target without the bindings");
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"), "the surface commits while Xaero shows a band");
        var line = this.bridge.describe();
        assertTrue(line.contains("mode=surface(cave_unbound)") && line.contains("optional_unbound=")
                && line.contains("cave-layers"), line);
    }

    @Test
    void aMismatchedPlanWritesOnlyTheSurface() {
        // Review fold O3-2: Xaero's plan has not named the Nether yet (the entry burst). No band
        // is guessed — Xaero's roof probe, its cave-mode switches and its height clamp decide
        // the layer; the store keeps the column and fill-on-switch writes the shown layer
        // (XaeroBandFillTest pins the heal).
        this.processor.mapWorld.currentDimensionId = OVERWORLD; // Xaero has not followed yet
        this.clientDimension = OVERWORLD;
        show(SURFACE);
        for (double y : new double[]{12.5, 70.4, 129.0, 254.0}) { // low, below the roof, on the roof, the top
            this.player = new XaeroSession.PlayerPos(NETHER, 0, y, 0);
            assertEquals(1, this.bridge.planTargets(NETHER, NETHER_FACTS).size(), "no guessed band at y=" + y);
        }
        offer(64, 64);
        assertEquals(1, this.bridge.queuedForTest());
        assertTrue(this.bridge.hasQueuedForTest(SURFACE, 64, 64), "the surface only");
        this.bridge.layerPlan = null; // before Xaero's first read: the same
        assertEquals(1, this.bridge.planTargets(NETHER, NETHER_FACTS).size());
        this.processor.mapWorld.currentDimensionId = NETHER; // Xaero follows: band 4 shown
        this.clientDimension = NETHER;
        show(4);
        offer(65, 64);
        assertTrue(this.bridge.hasQueuedForTest(4, 65, 64), "once the plan names the dimension, the shown band");
    }

    @Test
    void caveLayerWritesStayWithinTheFillBoundInEveryDimension() {
        var overworldFacts = new XaeroTileExtractor.LevelFacts(-64, 320, true, false, 384);
        this.processor.mapWorld.currentDimensionId = OVERWORLD;
        this.clientDimension = OVERWORLD;
        this.player = new XaeroSession.PlayerPos(OVERWORLD, 8, 70, 8); // chunk (0, 0), indoors
        show(3);
        this.bridge.offerColumn(OVERWORLD, 128, 0, overworldFacts, NETHER_COLUMN);
        assertTrue(this.bridge.hasQueuedForTest(3, 128, 0), "Chebyshev 128: inside the radius cap");
        this.bridge.offerColumn(OVERWORLD, 129, -129, overworldFacts, NETHER_COLUMN);
        assertFalse(this.bridge.hasQueuedForTest(3, 129, -129), "outside: no cave tile");
        assertTrue(this.bridge.hasQueuedForTest(SURFACE, 129, -129), "the surface is unbounded");
        assertEquals(1, this.bridge.counterForTest("cave_out_of_range"));
        this.bridge.serverLodDistance = () -> 32; // the server's LOD distance is the tighter bound
        this.bridge.offerColumn(OVERWORLD, 20, 32, overworldFacts, NETHER_COLUMN);
        assertTrue(this.bridge.hasQueuedForTest(3, 20, 32));
        this.bridge.offerColumn(OVERWORLD, 33, 0, overworldFacts, NETHER_COLUMN);
        assertFalse(this.bridge.hasQueuedForTest(3, 33, 0));
        show(FULL);
        this.bridge.offerColumn(OVERWORLD, 0, 40, overworldFacts, NETHER_COLUMN);
        assertFalse(this.bridge.hasQueuedForTest(FULL, 0, 40), "Full is a cave layer too");
        assertEquals(3, this.bridge.counterForTest("cave_out_of_range"));
        this.player = null; // an unknown position skips the bound
        this.bridge.offerColumn(OVERWORLD, 500, 0, overworldFacts, NETHER_COLUMN);
        assertTrue(this.bridge.hasQueuedForTest(FULL, 500, 0));
    }

    @Test
    void aLeftoverBandOfARegionThatNeverRestsExpiresSilently() {
        this.bridge.staleLayerPumps = 3;
        var busy = new MapRegion();
        busy.resting = false; // a region that never rests: the bucket would be held forever
        this.processor.caveRegions.computeIfAbsent(3, l -> new java.util.HashMap<>()).put((2L << 32) | 2L, busy);
        show(3);
        offer(64, 64);
        for (int i = 0; i < 6; i++) this.bridge.pump();
        assertTrue(this.bridge.hasQueuedForTest(3, 64, 64), "the layer Xaero shows is held, never expired");
        assertEquals(0, this.bridge.counterForTest("dropped_stale_layer"));
        show(5); // band 3 is a leftover now
        for (int i = 0; i < 2; i++) this.bridge.pump();
        assertTrue(this.bridge.hasQueuedForTest(3, 64, 64), "not before the bound");
        for (int i = 0; i < 2; i++) this.bridge.pump();
        assertFalse(this.bridge.hasQueuedForTest(3, 64, 64), "expired past the bound");
        assertEquals(1, this.bridge.counterForTest("dropped_stale_layer"));
        assertTrue(this.reports.isEmpty(), "silently: a map-only leftover, never a hole report");
        assertEquals(0, this.bridge.counterForTest("owed"));
    }

    @Test
    void theSurfaceIsNeverALeftoverAndABandExpiresOnlyPastSixHundredHeldPumps() {
        assertEquals(600, this.bridge.staleLayerPumps, "~30 s at 20 Hz");
        var busySurface = new MapRegion();
        busySurface.resting = false; // neither region ever rests: both buckets are held
        this.processor.regions.put((2L << 32) | 2L, busySurface);
        var busyBand = new MapRegion();
        busyBand.resting = false;
        this.processor.caveRegions.computeIfAbsent(3, l -> new java.util.HashMap<>()).put((2L << 32) | 2L, busyBand);
        show(3);
        offer(64, 64);
        show(5); // band 3 is a leftover from here (held pump 1); the surface is not shown either
        for (int i = 1; i < 600; i++) this.bridge.pump();
        assertTrue(this.bridge.hasQueuedForTest(3, 64, 64), "still queued after 600 held pumps");
        this.bridge.pump();
        assertFalse(this.bridge.hasQueuedForTest(3, 64, 64), "expired on the 601st");
        for (int i = 0; i < 100; i++) this.bridge.pump();
        assertTrue(this.bridge.hasQueuedForTest(SURFACE, 64, 64),
                "the surface is never a leftover: in the Nether its regions rarely rest, and expiring it would"
                        + " leave permanent holes in the roof map");
        assertEquals(1, this.bridge.counterForTest("dropped_stale_layer"));
    }

    // ---- review fold O3-1: a leftover layer never reports, owes or wins a load grant ----

    @Test
    void aLeftoverBucketNeverWinsALoadGrant() {
        this.processor.createdRegionLoadState = 0; // every region awaits its load
        this.processor.currentMapLocked = true;
        show(3);
        offer(64, 64); // surface + band 3, queued while the drain is held
        show(5); // band 3 is a leftover before its region was ever granted
        this.processor.currentMapLocked = false;
        this.bridge.pump();
        var layers = new HashSet<Integer>();
        for (var r : this.processor.saveLoad.loadRequests) layers.add(r.caveLayer);
        assertEquals(Set.of(SURFACE), layers, "the surface region is granted, the leftover band's is not");
        assertTrue(this.bridge.hasQueuedForTest(3, 64, 64), "the leftover still waits (and expires silently)");
    }

    @Test
    void aLeftoverDebtIsDroppedUnprobedAndUnreported() {
        show(3);
        this.bridge.acquisition.oweExpired(NETHER, 3, ((long) 64 << 32) | 64, XaeroSession.regionKeyOf(64, 64));
        assertEquals(1, this.bridge.counterForTest("owed"), "a debt of the band Xaero shows");
        show(5); // band 3 is a leftover now
        for (int i = 0; i < 8; i++) this.bridge.pump();
        assertEquals(0, this.bridge.counterForTest("owed"), "dropped");
        assertTrue(this.reports.isEmpty(), "never released into a re-serve");
        assertNull(caveRegion(3, 2, 2), "never probed (the probe's lookup creates the region)");
        assertTrue(this.processor.saveLoad.loadRequests.isEmpty(), "never granted a load");
    }

    @Test
    void aLeftoverTileDeferralExpiryOwesNothing() {
        this.bridge.deferCap = 1;
        this.bridge.owedProbesPerPump = 0; // judge the expiry path alone (the probe pass would drop such a debt too)
        show(3);
        offer(64, 64);
        this.bridge.pump(); // commits both, creating the band-3 tile chunk
        caveRegion(3, 2, 2).getChunk(0, 0).loadState = 0; // tile-scoped busy: DEFERRED_TILE
        offer(64, 64);
        show(5); // band 3 is a leftover now; its deferral burns 1
        this.bridge.pump(); // burns 2: past the cap
        assertEquals(1, this.bridge.counterForTest("dropped_expired"));
        assertEquals(0, this.bridge.counterForTest("owed"), "a leftover's expiry owes nothing");
        assertTrue(this.reports.isEmpty());
    }

    @Test
    void anEvictedLeftoverNeitherOwesNorReports() {
        this.processor.currentMapLocked = true; // nothing drains
        show(3);
        var band = XaeroTileExtractor.extract(64, 64, NETHER_FACTS, NETHER_COLUMN,
                List.of(XaeroTileExtractor.LayerTarget.band(3, 255, 30)), false).get(0);
        this.bridge.acquisition.offerPrepared(NETHER, band); // a live band-3 tile (null-handle reporter)
        show(5); // band 3 is a leftover now
        this.bridge.maxQueue = 1;
        var surface = XaeroTileExtractor.extract(70, 64, NETHER_FACTS, NETHER_COLUMN,
                List.of(XaeroTileExtractor.LayerTarget.surface(30)), false).get(0);
        this.bridge.acquisition.offerPrepared(NETHER, surface); // evicts the leftover
        assertFalse(this.bridge.hasQueuedForTest(3, 64, 64));
        assertEquals(1, this.bridge.counterForTest("dropped_overflow"));
        assertTrue(this.reports.isEmpty(), "an evicted leftover never reports");
        assertEquals(0, this.bridge.counterForTest("owed"));
        this.processor.currentMapLocked = false;
    }

    // ---- review fold O6 T1/T6/T7: fill tiles vs live tiles, the window probe ----

    @Test
    void aFillTileNeverReplacesAQueuedTile() {
        this.processor.currentMapLocked = true; // the live tiles wait (for their region load, say)
        show(5);
        var live = new Handle();
        offerWith(live, 64, 64); // surface + band 5, one lease each
        long bytes = this.bridge.queuedBytesForTest();
        var stored = XaeroTileExtractor.extract(64, 64, NETHER_FACTS, NETHER_COLUMN,
                List.of(XaeroTileExtractor.LayerTarget.band(5, 255, 30)), false).get(0);
        assertFalse(this.bridge.offerFill(NETHER, stored), "an older stored copy never takes a live tile's place");
        assertEquals(2, this.bridge.queuedForTest());
        assertEquals(bytes, this.bridge.queuedBytesForTest(), "no double count (it would throttle Voxy)");
        assertEquals(0, live.released, "the live tile's lease is untouched");
        assertEquals(0, this.bridge.counterForTest("fill_queued"));
        this.processor.currentMapLocked = false;
        this.bridge.pump();
        assertEquals(2, live.released, "each lease settles at its own commit");
        assertEquals(2, this.bridge.counterForTest("written"));
    }

    @Test
    void fillEntriesAreEvictedBeforeAnyLiveTile() {
        this.processor.currentMapLocked = true; // nothing drains
        show(5);
        this.bridge.maxQueue = 3;
        offer(64, 64); // live: surface + band 5
        var fill = XaeroTileExtractor.extract(70, 64, NETHER_FACTS, NETHER_COLUMN,
                List.of(XaeroTileExtractor.LayerTarget.band(5, 255, 30)), false).get(0);
        assertTrue(this.bridge.offerFill(NETHER, fill)); // the queue is full now
        var surface = XaeroTileExtractor.extract(65, 64, NETHER_FACTS, NETHER_COLUMN,
                List.of(XaeroTileExtractor.LayerTarget.surface(30)), false).get(0);
        this.bridge.acquisition.offerPrepared(NETHER, surface); // a live tile needs one slot
        assertEquals(0, this.bridge.counterForTest("fill_queued"), "the fill entry made room");
        assertTrue(this.bridge.hasQueuedForTest(SURFACE, 64, 64), "not the oldest live tile");
        assertTrue(this.bridge.hasQueuedForTest(5, 64, 64));
        assertTrue(this.bridge.hasQueuedForTest(SURFACE, 65, 64));
        assertEquals(0, this.bridge.counterForTest("dropped_overflow"), "no live tile was dropped");
        assertTrue(this.reports.isEmpty());
        this.processor.currentMapLocked = false;
    }

    @Test
    void theWindowProbesEachRegionInItsOwnLayer() {
        this.bridge.openRegionCap = 2;
        show(3);
        offer(64, 64); // surface + band 3 commit: both (layer, region)s join the window
        this.bridge.pump();
        assertEquals(2, this.bridge.openRegions.size());
        caveRegion(3, 2, 2).loadState = 3; // Xaero released the band region; the surface one stays loaded
        this.bridge.pump();
        assertEquals(Set.of(XaeroSession.surfaceRegion(XaeroSession.regionKeyOf(64, 64))),
                this.bridge.openRegions.keySet(), "the band region's slot is freed: it was probed in its own layer");
    }

    @Test
    void thePublishRespectsXaerosCrashShield() {
        xaero.map.WorldMap.crashHandler.crashedBy = new IllegalStateException("xaero worker died");
        try {
            show(3);
            assertNull(this.bridge.layerPlan, "a crashed Xaero is never read, not even for the plan");
        } finally {
            xaero.map.WorldMap.crashHandler.crashedBy = null;
        }
        show(3);
        assertEquals(3, this.bridge.layerPlan.currentLayer());
        assertEquals(NETHER, this.bridge.layerPlan.dimension(), "tagged with Xaero's dimension");
    }

    @Test
    void aStalePlanCannotLeakIntoTheNextSession() {
        show(3);
        assertNotNull(this.bridge.layerPlan);
        this.bridge.onSessionEnd();
        WorldMapSession.current = null;
        this.bridge.pump();
        assertNull(this.bridge.layerPlan);
    }
}
