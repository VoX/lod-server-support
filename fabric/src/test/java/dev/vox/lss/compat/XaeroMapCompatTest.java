package dev.vox.lss.compat;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTileChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Xaero map bridge against real-package-name stubs (xaero-map-bridge-plan.md §3;
 * the {@code MoonriseReadCompatTest} stub discipline — the stubs under
 * {@code fabric/src/test/java/xaero/map} mirror exactly the public surface
 * {@code XaeroMapCompat.Handles} resolves, and their state accessors ENFORCE the
 * native monitor discipline via holdsLock checks, so dropping a synchronized block
 * fails here rather than racing a live client). Pins:
 * <ul>
 *   <li>resolve is all-or-nothing and fail-soft; a resolve failure renders
 *       {@code state=unavailable} in diag (a drifted Xaero must be visible);</li>
 *   <li>the pump mirrors the native writer's gate ladder — every not-ready gate
 *       DEFERS (entries retained), a stale-dimension entry DROPS, a loaded chunk
 *       SKIPS; the region-level save-race gate defers too;</li>
 *   <li>the decompiled commit sequence order, incl. setChanged(true) before
 *       setTile, worldInterpretationVersion before setTile, NO setToUpdateBuffers
 *       flag ever (Xaero's sweep consumes it outside isResting — the
 *       cache-not-prepared crash, plan §15) with the texture rebuild coalesced
 *       into the pump's flush phase under the writer gates, the faithful
 *       prepare→overlays→write per-pixel order (the stub's prepareForWriting
 *       clears overlays like the real one, so a wrong order wipes them), and
 *       {@code setBeingWritten} set-and-NEVER-cleared;</li>
 *   <li>the region load dance: beingWritten TRUE at request time (STATE-recorded
 *       by the stub — event order was vacuous, the commit probe also sets it),
 *       the memoryless outstanding window (in-flight recognized from Xaero's own
 *       canRequestReload, the loader's dead ends self-heal, cache-parked regions
 *       revive 3→4), Xaero's shared pacing surface untouched in BOTH directions,
 *       largest cluster issued LAST for the LIFO drain, awaiting-load exempt
 *       from the deferral cap;</li>
 *   <li>queue policy: latest-wins with DISTINCT tiles, oldest-first eviction,
 *       count AND byte bounds, cross-dimension replacement, config-off clear,
 *       no-session drop;</li>
 *   <li>the death latches (commit-side and extraction-side): 5 CONSECUTIVE
 *       failures latch — across pumps, not reset by a clean ladder pass — a
 *       success resets, and {@code onSessionEnd} re-arms (session-scoped);</li>
 *   <li>registration lifecycle: add-only while live, deregistration only at
 *       session end (mid-session deregistration would put every column through
 *       the no-consumer ingest-failure re-serve path);</li>
 *   <li>the consumer contract: a throwing extraction NEVER escapes, and
 *       {@code pendingIngestBacklog} IS overridden with the §12 backpressure
 *       report (the anonymous-class requirement — a lambda keeps the -1
 *       default and the taper never engages).</li>
 * </ul>
 */
class XaeroMapCompatTest {

    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld"));
    private static final ResourceKey<Level> NETHER =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:the_nether"));

    private MapProcessor processor;
    private final Object worldToken = new Object();
    private final Set<Long> loadedChunks = new HashSet<>();
    private boolean enabled = true;
    private boolean sessionActive = true;
    private boolean backpressureEnabled = true;
    private long clockMillis = 1_000_000L;
    private final List<Object[]> reports = new ArrayList<>();
    private final List<dev.vox.lss.api.VoxelColumnConsumer> registered = new ArrayList<>();
    private XaeroMapCompat bridge;

    private ResourceKey<Level> clientDimension = OVERWORLD;

    private final XaeroMapCompat.LevelOps fakeLevelOps = new XaeroMapCompat.LevelOps() {
        @Override
        public Object dimension(Object world) {
            return clientDimension;
        }

        @Override
        public boolean isChunkLoaded(Object world, int chunkX, int chunkZ) {
            return loadedChunks.contains(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
        }
    };

    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() throws Exception {
        XaeroStubEvents.clear();
        this.processor = new MapProcessor();
        this.processor.world = this.worldToken;
        this.processor.mainWorld = this.worldToken;
        this.processor.mapWorld.currentDimensionId = OVERWORLD;
        var session = new WorldMapSession();
        session.processor = this.processor;
        WorldMapSession.current = session;
        this.loadedChunks.clear();
        this.enabled = true;
        this.sessionActive = true;
        this.registered.clear();
        this.backpressureEnabled = true;
        this.reports.clear();
        this.bridge = new XaeroMapCompat(
                XaeroMapCompat.Handles.resolve(Class::forName),
                this.fakeLevelOps,
                () -> this.enabled,
                () -> this.sessionActive,
                this.registered::add,
                this.registered::remove,
                () -> this.backpressureEnabled,
                (dimension, chunkX, chunkZ) ->
                        this.reports.add(new Object[]{dimension, chunkX, chunkZ}));
        this.bridge.pumpNanosBudget = Long.MAX_VALUE; // neutralize MethodHandle warmup
        this.bridge.updateNanosBudget = Long.MAX_VALUE;
        this.clockMillis = 1_000_000L;                // deterministic: the 1 s watchdog
        this.bridge.bpClock = () -> this.clockMillis; // must never race a slow test JVM
        this.bridge.maybeRegister();
    }

    @AfterEach
    void tearDownStubStatics() {
        WorldMapSession.current = null;
        xaero.map.WorldMap.crashHandler.crashedBy = null;
        xaero.map.common.config.option.WorldMapProfiledConfigOptions.LOAD_NEW_CHUNKS.value = true;
        xaero.map.common.config.option.WorldMapProfiledConfigOptions.UPDATE_CHUNKS.value = true;
        xaero.map.region.MapTile.CURRENT_WORLD_INTERPRETATION_VERSION = 1;
        xaero.map.WorldMap.INSTANCE.configs.manager.override = null;
        xaero.map.WorldMap.INSTANCE.configs.manager.throwing = false;
        XaeroStubEvents.clear();
        XaeroMapCompat.resetFacadeForTest();
    }

    @SuppressWarnings("unchecked")
    private XaeroTileExtractor.PreparedTile tile(int chunkX, int chunkZ) {
        var floor = new BlockState[256];
        var biome = (ResourceKey<Biome>[]) new ResourceKey[256];
        return new XaeroTileExtractor.PreparedTile(chunkX, chunkZ, -64,
                floor, new short[256], new short[256], biome, new byte[256],
                new boolean[256], new XaeroTileExtractor.OverlayRun[256][]);
    }

    private void offer(int chunkX, int chunkZ) {
        this.bridge.offerPrepared(OVERWORLD, tile(chunkX, chunkZ));
    }

    // ---- resolve / facade ----

    @Test
    void resolveFailsSoftWhenAClassIsMissing() {
        assertThrows(ClassNotFoundException.class, () -> XaeroMapCompat.Handles.resolve(name -> {
            if (name.equals("xaero.map.region.MapTile")) throw new ClassNotFoundException(name);
            return Class.forName(name);
        }));
    }

    @Test
    void resolveFailsSoftWhenAMemberIsMissing() {
        // A class of the wrong SHAPE (right name, no members) must fail resolution —
        // the all-or-nothing rule that keeps a drifted Xaero from a half-bound bridge.
        assertThrows(ReflectiveOperationException.class,
                () -> XaeroMapCompat.Handles.resolve(name -> {
                    if (name.equals("xaero.map.region.MapTile")) return Object.class;
                    return Class.forName(name);
                }));
    }

    @Test
    void facadeIsNullSafeAndInitRegistersTheConsumer() {
        XaeroMapCompat.resetFacadeForTest();
        assertDoesNotThrow(XaeroMapCompat::clientTick);
        assertDoesNotThrow(XaeroMapCompat::onDisconnect);
        org.junit.jupiter.api.Assertions.assertNull(XaeroMapCompat.diagLine(),
                "no Xaero detected → no diag line");
        var cfg = dev.vox.lss.config.LSSClientConfig.CONFIG;
        boolean old = cfg.enableXaeroMapBridge;
        try {
            cfg.enableXaeroMapBridge = true;
            assertTrue(XaeroMapCompat.init(), "init must succeed against the stubs");
            assertTrue(dev.vox.lss.api.LSSApi.hasVoxelConsumers(),
                    "init must register the column consumer");
            assertNotNull(XaeroMapCompat.diagLine());
        } finally {
            // Deregister the production consumer: session end with the toggle off — the
            // registration settle is the MAIN-THREAD half (sweep C), i.e. the next tick.
            cfg.enableXaeroMapBridge = false;
            XaeroMapCompat.onDisconnect();
            XaeroMapCompat.clientTick();
            cfg.enableXaeroMapBridge = old;
            XaeroMapCompat.resetFacadeForTest();
            assertFalse(dev.vox.lss.api.LSSApi.hasVoxelConsumers(),
                    "the production consumer must not leak into other suites");
        }
    }

    @Test
    void aResolveFailureIsVisibleAsUnavailableInDiag() {
        // The drift case (plan §7.1's top risk) must be distinguishable from "not
        // installed": init fails → no instance → but the diag line still renders.
        XaeroMapCompat.resetFacadeForTest();
        org.junit.jupiter.api.Assertions.assertNull(XaeroMapCompat.diagLine());
        assertFalse(XaeroMapCompat.initWith(name -> {
            throw new ClassNotFoundException(name);
        }));
        var line = XaeroMapCompat.diagLine();
        assertNotNull(line, "resolve-failed must render a diag line");
        assertTrue(line.contains("state=unavailable"), line);
    }

    // ---- registration lifecycle ----

    @Test
    void registrationIsAddOnlyMidSessionAndSettlesAtSessionEnd() {
        assertEquals(1, this.registered.size(), "enabled at init registers the consumer");
        this.enabled = false;
        this.bridge.pump();
        assertEquals(1, this.registered.size(),
                "mid-session disable must NOT deregister — the no-consumer path would"
                        + " report every arriving column as an ingest failure (re-serve"
                        + " churn for a map problem)");
        this.bridge.onSessionEnd();
        this.bridge.pump(); // the registration settle is the main-thread half (sweep C)
        assertTrue(this.registered.isEmpty(),
                "session end releases the capability bit for the next handshake");
        this.enabled = true;
        this.bridge.pump();
        assertEquals(1, this.registered.size(), "re-enabling re-registers");
    }

    @Test
    void sessionEndClearsQueueAndReArmsTheDeathLatch() {
        latchTheBridgeDead();
        assertTrue(this.bridge.deadForTest(), "premise: the latch fired");
        this.bridge.onSessionEnd();
        assertFalse(this.bridge.deadForTest(),
                "the latch is SESSION-scoped — one bad session must not disable the"
                        + " feature until restart");
        assertEquals(0, this.bridge.queuedForTest());
        assertEquals(1, this.registered.size(), "enabled bridge stays registered for next session");
    }

    @Test
    void offersOutsideALiveSessionAreDropped() {
        this.sessionActive = false;
        this.bridge.offerColumn(OVERWORLD, 3, 3, -64, 320,
                new dev.vox.lss.api.VoxelColumnData(
                        new dev.vox.lss.api.VoxelColumnData.SectionData[0], 1L));
        assertEquals(0, this.bridge.queuedForTest(),
                "the disconnect-drain race must not carry a stale tile into the next"
                        + " server's (or a singleplayer world's) persistent map");
    }

    @Test
    void disabledPumpClearsTheQueue() {
        offer(100, 100);
        assertEquals(1, this.bridge.queuedForTest());
        this.enabled = false;
        this.bridge.pump();
        assertEquals(0, this.bridge.queuedForTest());
    }

    // ---- queue policy ----

    @Test
    void latestWinsKeepsTheNewerDistinctTile() {
        var first = tile(5, 5);
        var second = tile(5, 5);
        second.floorState()[0] = Blocks.STONE.defaultBlockState();
        this.bridge.offerPrepared(OVERWORLD, first);
        this.bridge.offerPrepared(OVERWORLD, second);
        assertEquals(1, this.bridge.queuedForTest(), "same position coalesces");
        this.bridge.pump();
        var region = this.processor.regions.values().iterator().next();
        var block = region.getChunk(1, 1).getTile(1, 1).blocks[0][0];
        assertEquals(Blocks.STONE.defaultBlockState(), block.state,
                "the SECOND tile's content must win (latest-wins, not first-wins)");
    }

    @Test
    void boundedOverflowDropsTheOldestEntry() {
        offer(9999, 9999); // the oldest — must be the one evicted
        for (int i = 0; i < XaeroMapCompat.MAX_QUEUE; i++) {
            offer(1000 + i, 0);
        }
        assertEquals(XaeroMapCompat.MAX_QUEUE, this.bridge.queuedForTest());
        assertTrue(this.bridge.counterForTest("dropped_overflow") >= 1);
        assertFalse(this.bridge.hasQueuedForTest(9999, 9999),
                "eviction must take the OLDEST entry, not an arbitrary one");
        assertTrue(this.bridge.hasQueuedForTest(1000 + XaeroMapCompat.MAX_QUEUE - 1, 0),
                "the newest entry must survive");
    }

    @Test
    void theByteGaugeBoundsOverlayHeavyTiles() {
        // Max-overlay tiles are ~87 KB by the gauge's estimate; the 48 MB budget
        // admits ~550 of them — far below the 8192 count cap.
        var runs = new XaeroTileExtractor.OverlayRun[256][];
        for (int i = 0; i < 256; i++) {
            runs[i] = new XaeroTileExtractor.OverlayRun[XaeroTileExtractor.MAX_OVERLAYS];
            for (int r = 0; r < runs[i].length; r++) {
                runs[i][r] = new XaeroTileExtractor.OverlayRun(
                        Blocks.WATER.defaultBlockState(), (byte) 0, false, 1);
            }
        }
        for (int i = 0; i < 700; i++) {
            var heavy = tile(i * 4, 0);
            var withRuns = new XaeroTileExtractor.PreparedTile(heavy.chunkX(), heavy.chunkZ(),
                    heavy.worldBottomY(), heavy.floorState(), heavy.floorY(), heavy.topY(),
                    heavy.biome(), heavy.light(), heavy.glowing(), runs);
            this.bridge.offerPrepared(OVERWORLD, withRuns);
        }
        assertTrue(this.bridge.queuedForTest() < 700,
                "the byte gauge must evict before the count cap on overlay-heavy tiles"
                        + " (queued=" + this.bridge.queuedForTest() + ")");
        assertTrue(this.bridge.queuedBytesForTest() <= XaeroMapCompat.MAX_QUEUE_BYTES);
        assertTrue(this.bridge.counterForTest("dropped_overflow") > 0);
    }

    @Test
    void aCrossDimensionServeReplacesTheStaleEntry() {
        this.bridge.offerPrepared(NETHER, tile(3, 3));
        this.bridge.offerPrepared(OVERWORLD, tile(3, 3));
        assertEquals(1, this.bridge.queuedForTest());
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"),
                "the replacement (current-dimension) entry must commit");
        assertEquals(1, this.bridge.counterForTest("dropped_stale"),
                "§12 review m1: the replaced foreign entry is COUNTED (it used to"
                        + " vanish silently) — and reported (the stale route)");
    }

    // ---- the gate ladder (each not-ready gate defers: entry retained, no events) ----

    @Test
    void ladderNotReadyStatesDeferWithoutTouchingXaero() {
        offer(64, 64);
        record CaseSetter(String name, Runnable arm, Runnable disarm) {}
        var cases = List.of(
                new CaseSetter("no session", () -> WorldMapSession.current = null,
                        () -> { var s = new WorldMapSession(); s.processor = this.processor; WorldMapSession.current = s; }),
                new CaseSetter("unusable session", () -> WorldMapSession.current.usable = false,
                        () -> WorldMapSession.current.usable = true),
                new CaseSetter("null processor", () -> WorldMapSession.current.processor = null,
                        () -> WorldMapSession.current.processor = this.processor),
                new CaseSetter("writing paused", () -> this.processor.writingPaused = true,
                        () -> this.processor.writingPaused = false),
                new CaseSetter("waiting for world update", () -> this.processor.waitingForWorldUpdate = true,
                        () -> this.processor.waitingForWorldUpdate = false),
                new CaseSetter("detection incomplete", () -> this.processor.saveLoad.regionDetectionComplete = false,
                        () -> this.processor.saveLoad.regionDetectionComplete = true),
                new CaseSetter("multiworld unwritable", () -> this.processor.multiworldWritable = false,
                        () -> this.processor.multiworldWritable = true),
                new CaseSetter("no world", () -> this.processor.world = null,
                        () -> this.processor.world = this.worldToken),
                new CaseSetter("map locked", () -> this.processor.currentMapLocked = true,
                        () -> this.processor.currentMapLocked = false),
                new CaseSetter("cache-only mode", () -> this.processor.mapWorld.cacheOnlyMode = true,
                        () -> this.processor.mapWorld.cacheOnlyMode = false),
                new CaseSetter("no world id", () -> this.processor.currentWorldId = null,
                        () -> this.processor.currentWorldId = "stub-world"),
                new CaseSetter("ignored world", () -> this.processor.ignoreWorldResult = true,
                        () -> this.processor.ignoreWorldResult = false),
                new CaseSetter("mainWorld mismatch", () -> this.processor.mainWorld = new Object(),
                        () -> this.processor.mainWorld = this.worldToken),
                new CaseSetter("dimension browsing", () -> this.processor.mapWorld.currentDimensionId = NETHER,
                        () -> this.processor.mapWorld.currentDimensionId = OVERWORLD));
        for (var c : cases) {
            c.arm().run();
            XaeroStubEvents.clear();
            this.bridge.pump();
            assertEquals(1, this.bridge.queuedForTest(), c.name() + ": entry must be RETAINED");
            assertTrue(XaeroStubEvents.snapshot().stream().noneMatch(e -> e.startsWith("region.")
                            || e.startsWith("tileChunk.") || e.startsWith("tile.")),
                    c.name() + ": a not-ready ladder must not touch region/tile state");
            c.disarm().run();
        }
        this.bridge.pump();
        assertEquals(0, this.bridge.queuedForTest(), "ladder ready again: the entry commits");
        assertEquals(1, this.bridge.counterForTest("written"));
    }

    @Test
    void theRegionSaveRaceGateDefers() {
        offer(8, 8);
        var region = new MapRegion();
        region.writingPaused = true; // MapSaveLoad is saving this region (pushWriterPause)
        this.processor.regions.put(0L, region);
        this.bridge.pump();
        assertEquals(1, this.bridge.queuedForTest(),
                "a region being saved must DEFER — committing would race the save");
        assertTrue(this.bridge.counterForTest("defer_events") >= 1);
        region.writingPaused = false;
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
    }

    @Test
    void staleDimensionEntriesDropAtThePump() {
        this.bridge.offerPrepared(NETHER, tile(3, 3));
        this.bridge.pump();
        assertEquals(0, this.bridge.queuedForTest());
        assertEquals(1, this.bridge.counterForTest("dropped_stale"));
        assertEquals(0, this.bridge.counterForTest("written"));
    }

    private void loadChunk(int chunkX, int chunkZ) {
        this.loadedChunks.add(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
    }

    @Test
    void fullySurroundedLoadedChunksAreSkippedNotWritten() {
        offer(7, 9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                loadChunk(7 + dx, 9 + dz);
            }
        }
        this.bridge.pump();
        assertEquals(0, this.bridge.queuedForTest());
        assertEquals(1, this.bridge.counterForTest("skipped_native"));
        assertEquals(0, this.bridge.counterForTest("written"));
    }

    @Test
    void aLoadedEdgeChunkIsBridgeWritten() {
        // The boundary-ring regression (field-tested 2026-08-23): the native writer's
        // edge rule refuses any chunk without all 8 neighbors loaded, so the OUTERMOST
        // ring of loaded vanilla chunks is never natively written — skipping it here
        // too left a 1-chunk black circle at the vanilla/LOD boundary around every
        // join point. A loaded-but-edge chunk must be bridge-written; the native
        // writer reclaims it on its clean-flag once fully surrounded.
        offer(7, 9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                loadChunk(7 + dx, 9 + dz);
            }
        }
        this.loadedChunks.remove(((long) 8 << 32) | 10L); // one missing neighbor → edge
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"),
                "an edge chunk (native-unwritable) must be bridge-written");
        assertEquals(0, this.bridge.counterForTest("skipped_native"));
    }

    // ---- deferral flavors (the dead-knob branches) ----

    @Test
    void aNullLeafRegionDefers() {
        offer(2, 2);
        this.processor.leafMapRegionReturnsNull = true; // detection-completeness race
        this.bridge.pump();
        assertEquals(1, this.bridge.queuedForTest());
        this.processor.leafMapRegionReturnsNull = false;
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
    }

    @Test
    void aPboDownloadingTileChunkDefers() {
        offer(4, 4);
        var region = new MapRegion();
        var tileChunk = new MapTileChunk(region, 1, 1);
        tileChunk.loadState = 2;
        tileChunk.leafTexture.downloadFromPBO = true;
        region.setChunk(1, 1, tileChunk);
        this.processor.regions.put(0L, region);
        this.bridge.pump();
        assertEquals(1, this.bridge.queuedForTest(), "PBO download in flight → defer");
        tileChunk.leafTexture.downloadFromPBO = false;
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
    }

    @Test
    void anUnloadedTileChunkDefers() {
        offer(4, 4);
        var region = new MapRegion();
        var tileChunk = new MapTileChunk(region, 1, 1); // loadState 0
        region.setChunk(1, 1, tileChunk);
        this.processor.regions.put(0L, region);
        this.bridge.pump();
        assertEquals(1, this.bridge.queuedForTest(), "tile chunk not at loadState 2 → defer");
        tileChunk.loadState = 2;
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
    }

    // ---- the region load dance ----

    @Test
    void unloadedRegionIsLoadRequestedWithBeingWrittenFirstAndNeverReRequested() {
        offer(64, 64); // region (2,2), fresh
        var region = new MapRegion();
        region.loadState = 0;
        this.processor.regions.put((2L << 32) | 2L, region);
        this.bridge.pump();
        assertEquals(1, this.processor.saveLoad.loadRequests.size(),
                "an unloaded region must be load-REQUESTED (fresh regions never self-promote)");
        assertEquals(1, this.bridge.counterForTest("load_requests"));
        assertEquals(1, this.bridge.regionsWaitingForTest());
        var events = XaeroStubEvents.snapshot();
        assertTrue(events.contains("saveLoad.requestLoad lss-xaero-bridge beingWritten"),
                "beingWritten must already be TRUE at request time — it stops the load"
                        + " drain demoting an empty fresh region (STATE-recorded by the"
                        + " stub; event order was vacuous, the commit probe also sets it): "
                        + events);
        assertFalse(events.contains("saveLoad.setNextToLoadByViewing"),
                "the native consumers' pacing token must never be repointed: " + events);
        assertEquals(1, this.bridge.queuedForTest(), "awaiting-load entries stay queued");

        // The memoryless window: once requested, Xaero's own canRequestReload
        // answers false (the stub flips it like the real reloadHasBeenRequested),
        // so every further pump reads IN-FLIGHT and issues NO re-request…
        for (int i = 0; i < XaeroMapCompat.DEFER_CAP + 50; i++) {
            this.bridge.pump();
        }
        assertEquals(1, this.processor.saveLoad.loadRequests.size(),
                "one request per region until its load lands");
        // …and awaiting-load deferrals are EXEMPT from the deferral cap.
        assertEquals(1, this.bridge.queuedForTest(),
                "an entry awaiting a region load must never be dropped by the deferral cap");

        // The load lands: the next pump commits and nothing waits.
        region.loadState = 2;
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
        assertEquals(0, this.bridge.queuedForTest());
        assertEquals(0, this.bridge.regionsWaitingForTest());
    }

    @Test
    void theSharedPacingSurfaceIsNeverTouched() {
        // BOTH directions of Xaero's load-pacing surface stay untouched (plan §14):
        // shouldAllowAnotherRegionToLoad synchronizes on its own (possibly BRANCH)
        // region — a lock-order inversion against Xaero's parent-then-leaf loader
        // thread (a real client deadlock) — and setNextToLoadByViewing is purely
        // the four native consumers' pacing token (the loader never reads it);
        // repointing it at a far bridge region vetoed writer/minimap/GUI/reloader
        // for multi-second stretches after each granted region's save.
        offer(64, 64);
        var gauge = new MapRegion(); // park a "previous" region in the pacing slot
        this.processor.saveLoad.nextToLoadByViewing = gauge;
        var region = new MapRegion();
        region.loadState = 0;
        this.processor.regions.put((2L << 32) | 2L, region);
        this.bridge.pump();
        var events = XaeroStubEvents.snapshot();
        assertFalse(events.contains("pacing.shouldAllowAnotherRegionToLoad"),
                "the shared gauge must never be consulted (deadlock-class removal): " + events);
        assertFalse(events.contains("saveLoad.setNextToLoadByViewing"),
                "the pacing token must never be repointed (native-consumer veto): " + events);
        assertTrue(this.processor.saveLoad.nextToLoadByViewing == gauge,
                "the pacing token must be left exactly as found");
        assertEquals(1, this.processor.saveLoad.loadRequests.size(),
                "the memoryless window still grants the load");
        // Structural: no reflective handle for the pacing surface may even exist.
        for (var f : XaeroMapCompat.Handles.class.getDeclaredFields()) {
            var n = f.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(n.contains("shouldallow") || n.contains("nexttoload"),
                    "no handle for the pacing surface may exist: " + f.getName());
        }
    }

    @Test
    void grantsGoToTheLargestPendingRegionsUpToTheWindow() {
        // 10 pending regions; region (2,2) holds THREE tiles, the rest one each —
        // and the big cluster is offered LAST, so insertion order cannot masquerade
        // as size order (3-Opus fold: the old arrangement made the sort vacuous).
        // The grant phase spends up to MAX_OUTSTANDING_LOADS requests per pump on
        // the largest clusters, ISSUED smallest-first: the loader drains
        // toLoad.get(0) against our priority front-inserts (LIFO), so the
        // LAST-issued (largest) region is the one drained FIRST.
        for (int i = 1; i <= 9; i++) {
            offer(64 + i * 32, 320); // regions (2+i, 10), one tile each
        }
        offer(64, 64);
        offer(68, 64);
        offer(72, 64); // three tiles in region (2,2) — offered LAST
        var bigRegion = unloadedRegion();
        this.processor.regions.put((2L << 32) | 2L, bigRegion);
        for (int i = 1; i <= 9; i++) {
            this.processor.regions.put(((long) (2 + i) << 32) | 10L, unloadedRegion());
        }
        this.bridge.pump();
        var requests = this.processor.saveLoad.loadRequests;
        assertEquals(XaeroMapCompat.MAX_OUTSTANDING_LOADS, requests.size(),
                "one pump must batch a full window of load requests");
        assertTrue(requests.get(requests.size() - 1) == bigRegion,
                "the region holding the most queued tiles must be issued LAST — the"
                        + " final front-insert is what the LIFO drain serves FIRST");
        assertEquals(10, this.bridge.regionsWaitingForTest());
        assertEquals(0, this.bridge.counterForTest("commit_failures"));
    }

    @Test
    void theWindowRefillsAsLoadsLand() {
        // 12 waiting regions, window 8: pump 1 requests 8 (the stub flips their
        // canRequestReload — honestly in flight). Landing 3 frees 3 slots: the
        // next pump commits the landed tiles AND requests exactly 3 more.
        for (int i = 0; i < 12; i++) {
            offer(64 + i * 32, 320); // regions (2..13, 10)
            this.processor.regions.put(((long) (2 + i) << 32) | 10L, unloadedRegion());
        }
        this.bridge.pump();
        assertEquals(8, this.processor.saveLoad.loadRequests.size());
        this.bridge.pump();
        assertEquals(8, this.processor.saveLoad.loadRequests.size(),
                "window full, no slot free — no new grants");
        for (int i = 0; i < 3; i++) {
            this.processor.saveLoad.loadRequests.get(i).loadState = 2; // lands
        }
        this.bridge.pump();
        assertEquals(3, this.bridge.counterForTest("written"), "landed tiles commit");
        assertEquals(11, this.processor.saveLoad.loadRequests.size(),
                "the 3 freed slots must refill with exactly 3 new grants");
    }

    @Test
    void aRemovedDeadEndRegionIsReRequestedOnAFreshObject() {
        // Two of the loader's three dead ends END IN removeMapRegion (failed read →
        // loadState 4 + remove; empty load → remove): the granted region OBJECT
        // disappears without ever reaching loadState 2. A slot-tracking window
        // would leak that slot forever (3-Opus fold MAJOR); the memoryless window
        // self-heals — the next probe's getLeafMapRegion(create=true) hands back a
        // fresh unloaded region that reads requestable again.
        offer(64, 64);
        long key = (2L << 32) | 2L;
        this.processor.regions.put(key, unloadedRegion());
        this.bridge.pump();
        assertEquals(1, this.processor.saveLoad.loadRequests.size());
        this.bridge.pump();
        assertEquals(1, this.processor.saveLoad.loadRequests.size(), "in flight: no re-request");
        var deadEnded = this.processor.regions.remove(key); // Xaero's removeMapRegion
        this.processor.createdRegionLoadState = 0;          // detection creates UNLOADED
        this.bridge.pump();
        assertEquals(2, this.processor.saveLoad.loadRequests.size(),
                "the dead-ended region must be re-requested…");
        assertTrue(this.processor.saveLoad.loadRequests.get(1) != deadEnded
                        && this.processor.saveLoad.loadRequests.get(1)
                                == this.processor.regions.get(key),
                "…on the FRESH region object the probe re-created");
    }

    @Test
    void aCacheParkedRegionIsRevivedViaTheNativeThreeToFourTransition() {
        // The loader's third dead end (3-Opus fold MAJOR): a cache-only load parks
        // the region at loadState 3, where isResting AND canRequestReload are both
        // false FOREVER — without revival the bucket waits until session end.
        // Xaero's own clearRegion idiom (3→4) makes it requestable again.
        offer(64, 64);
        var region = new MapRegion();
        region.loadState = 3;
        this.processor.regions.put((2L << 32) | 2L, region);
        this.bridge.pump();
        assertEquals(1, this.processor.saveLoad.loadRequests.size(),
                "a cache-parked region must be revived and requested");
        assertTrue(XaeroStubEvents.snapshot().contains("region.setLoadState 4"),
                "the revival must be Xaero's own 3→4 transition");
        region.loadState = 2; // the load lands
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
        assertEquals(0, this.bridge.queuedForTest());
    }

    @Test
    void aParkedRegionWithPendingNativeWorkIsLeftAlone() {
        // The revival must RESTORE loadState 3 when the guards still refuse (a
        // pending native recache/refresh owns the region) — and never mark
        // beingWritten on the way out.
        offer(64, 64);
        var region = new MapRegion();
        region.loadState = 3;
        region.canRequestReload = false; // pending native work
        this.processor.regions.put((2L << 32) | 2L, region);
        this.bridge.pump();
        assertTrue(this.processor.saveLoad.loadRequests.isEmpty());
        assertEquals(3, region.loadState, "the failed revival must restore loadState 3");
        org.junit.jupiter.api.Assertions.assertNull(region.beingWritten,
                "a refused request must not mark beingWritten");
        assertEquals(1, this.bridge.queuedForTest(), "the bucket keeps waiting");
    }

    @Test
    void aBucketOfManyEntriesCountsOneDeferEventPerPump() {
        // The bucketed drain probes each region ONCE per pump — at large radius a
        // ring crosses ~r/4 regions, and per-entry probing burned the whole budget
        // on identical awaiting-load answers (the throughput round's motivation).
        for (int i = 0; i < 20; i++) {
            offer(64 + i, 64); // 20 tiles, all in region (2,2) — chunkX 64..83
        }
        this.processor.regions.put((2L << 32) | 2L, unloadedRegion());
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("defer_events"),
                "one defer event per BUCKET per pump, not per entry");
        assertEquals(20, this.bridge.queuedForTest());
        long lookups = XaeroStubEvents.snapshot().stream()
                .filter(e -> e.startsWith("processor.getLeafMapRegion")).count();
        assertEquals(2, lookups,
                "20 same-region entries must cost exactly TWO region lookups — one"
                        + " commit probe (bucket short-circuit) + one grant request");
    }

    @Test
    void aRegionThatCannotRequestReloadAwaitsWithoutARequest() {
        offer(64, 64);
        var region = new MapRegion();
        region.loadState = 0;
        region.canRequestReload = false;
        this.processor.regions.put((2L << 32) | 2L, region);
        for (int i = 0; i < XaeroMapCompat.DEFER_CAP + 10; i++) {
            this.bridge.pump();
        }
        assertTrue(this.processor.saveLoad.loadRequests.isEmpty(), "no request possible");
        assertEquals(1, this.bridge.queuedForTest(),
                "awaiting-load (even requestless) is exempt from the deferral cap");
    }

    @Test
    void twoUnloadedRegionsAreGrantedInTheSamePump() {
        offer(64, 64);   // region (2,2)
        offer(320, 320); // region (10,10)
        this.processor.regions.put((2L << 32) | 2L, unloadedRegion());
        this.processor.regions.put((10L << 32) | 10L, unloadedRegion());
        this.bridge.pump();
        assertEquals(2, this.processor.saveLoad.loadRequests.size(),
                "both fit the outstanding window — one pump grants both");
    }

    private MapRegion unloadedRegion() {
        var r = new MapRegion();
        r.loadState = 0;
        return r;
    }

    // ---- the commit sequence ----

    @Test
    void commitMirrorsTheDecompiledSequence() {
        offer(64, 65); // region (2,2), tileChunk (16,16) local (0,0), inside (0,1)
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
        var events = XaeroStubEvents.snapshot();

        // The region lookup targets the SURFACE layer (a cave-layer regression would
        // write LODs into Xaero's cave map invisibly).
        assertTrue(events.contains("processor.getLeafMapRegion layer=" + Integer.MAX_VALUE + " 2,2"),
                "surface-layer region lookup: " + events);

        // setBeingWritten is set and NEVER cleared by the bridge — the save path owns
        // the reset; a false here means tiles silently never persist.
        assertTrue(events.contains("region.setBeingWritten true"));
        assertFalse(events.contains("region.setBeingWritten false"),
                "the bridge must NEVER clear setBeingWritten: " + events);

        // Created tile chunk: ctor gets WORLD tile-chunk coords, loadState 2 + region
        // cache invalidated + terrain marked + highlights prepared.
        assertTrue(events.contains("tileChunk.new 16,16"), events.toString());
        assertTrue(events.contains("tileChunk.setLoadState 2"));
        assertTrue(events.contains("region.setAllCachePrepared false"));
        assertTrue(events.contains("tileChunk.setHasHadTerrain"));
        assertTrue(events.contains("highlights.prepare"));

        // Order: setChanged(true) precedes setTile (the native new-tile mark), then
        // worldInterpretationVersion → writtenCave → setTile → writtenOnce → loaded
        // (setTile's tileWasLoadedWithTopHeightValues branch reads the version).
        int changedTrue = events.indexOf("tileChunk.setChanged true");
        int version = events.indexOf("tile.setWorldInterpretationVersion 1");
        int cave = events.indexOf("tile.setWrittenCave");
        int setTile = events.indexOf("tileChunk.setTile 0,1");
        int writtenOnce = events.indexOf("tile.setWrittenOnce true");
        int loaded = events.indexOf("tile.setLoaded true");
        assertTrue(changedTrue >= 0 && changedTrue < setTile,
                "setChanged(true) must precede setTile: " + events);
        assertTrue(version >= 0 && cave > version && setTile > cave
                        && writtenOnce > setTile && loaded > writtenOnce,
                "commit order must mirror the decompiled writeChunk: " + events);

        // Buffers: NO setToUpdateBuffers flag (Xaero's preUpload sweep consumes it with
        // no isResting check — the "cache not prepared" saver crash, plan §15) and no
        // rebuild at commit either: the change stays MARKED for the coalesced rebuild
        // phase, which consumes it (pinned in the rebuild-phase tests below).
        assertFalse(events.stream().anyMatch(e -> e.startsWith("tileChunk.setToUpdateBuffers")),
                "the flag must never be set: " + events);
        assertFalse(events.stream().anyMatch(e -> e.startsWith("tileChunk.updateBuffers")),
                "no rebuild inside the commit (coalesced per tile chunk): " + events);
        assertFalse(events.contains("tileChunk.setChanged false"),
                "the change is consumed by the rebuild, not the commit: " + events);
    }

    // ---- the rebuild phase (plan §15: the cache-not-prepared crash) ----

    private void pumpIdleWindow() {
        for (int i = 0; i <= this.bridge.updateIdlePumps; i++) {
            this.bridge.pump();
        }
    }

    // ---- the frame slice (plan §17 + the §17.1 review fold — the stutter round) ----

    @Test
    void rebuildsRunOnTheFrameAndTheTickFallbackStandsDown() {
        this.bridge.updateIdlePumps = 1;
        offer(64, 64);
        this.bridge.pump(); // commit; the rebuild is owed
        this.bridge.frameFlush(); // not due yet: the cheap fast-out arms the marker
        assertEquals(0, this.bridge.counterForTest("frame_flushes"),
                "a nothing-due frame skips the reflective ladder");
        this.bridge.pump(); // due now, but a frame ran since the last pump
        assertEquals(0, this.bridge.counterForTest("buffer_updates"),
                "the tick fallback must stand down while frames are flushing");
        assertEquals(1, this.bridge.counterForTest("pending_updates"));
        this.bridge.frameFlush(); // the frame does the rebuild
        assertEquals(1, this.bridge.counterForTest("frame_flushes"));
        assertEquals(1, this.bridge.counterForTest("buffer_updates"));
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
        this.bridge.pump(); // marker consumed + nothing owed: no double rebuild
        assertEquals(1, this.bridge.counterForTest("buffer_updates"));
        assertTrue(this.bridge.describe().contains("frame_flushes=1"),
                "the live diag must show the frame scheduler firing");
        assertTrue(this.bridge.counterForTest("rebuild_nanos_total")
                        >= this.bridge.counterForTest("rebuild_nanos_max"),
                "the stutter instruments meter the recolor");
    }

    @Test
    void aFrameRebuildsAtMostOneTileChunkWithoutPressure() {
        this.bridge.updateIdlePumps = 1;
        offer(64, 64);
        offer(96, 64); // a second tile chunk in a second region
        this.bridge.pump(); // both commit
        this.bridge.frameFlush(); // arms the marker; nothing due yet
        this.bridge.pump(); // ages both to due; the tick stands down
        assertEquals(0, this.bridge.counterForTest("buffer_updates"));
        this.bridge.frameFlush();
        assertEquals(1, this.bridge.counterForTest("buffer_updates"),
                "one recolor per frame without backlog pressure");
        assertEquals(1, this.bridge.counterForTest("pending_updates"));
        this.bridge.frameFlush();
        assertEquals(2, this.bridge.counterForTest("buffer_updates"));
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
    }

    @Test
    void thePerFrameCapRisesWithBacklogPressureWhileFramesAreScarce() {
        this.bridge.updateIdlePumps = 1;
        this.bridge.pendingUpdatesSoftCap = 1;
        this.bridge.pendingUpdatesHardCap = 4;
        offer(64, 64);
        offer(96, 64);
        offer(128, 64); // three tile chunks in three regions
        this.bridge.pump(); // all commit; pending 3 > soft cap AND > hardCap/2
        this.bridge.frameFlush(); // overflow past the soft cap makes the oldest two due
        assertEquals(2, this.bridge.counterForTest("buffer_updates"),
                "cap 3 under scarce-frame pressure: both due entries recolor in ONE frame");
        this.bridge.pump(); // stands down; the third becomes idle-due
        this.bridge.frameFlush();
        assertEquals(3, this.bridge.counterForTest("buffer_updates"),
                "pressure gone (pending 1 <= soft cap): back to one per frame");
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
    }

    @Test
    void theAllowanceStopsFrameRebuildsUntilTheNextPumpRearmsIt() {
        this.bridge.updateIdlePumps = 1;
        this.bridge.updateNanosBudget = 0; // degenerate interval allowance
        this.bridge.updateBorrowNanos = 0;
        offer(64, 64);
        offer(96, 64);
        this.bridge.pump(); // both commit
        this.bridge.frameFlush(); // not due: fast-out arms the marker
        this.bridge.pump(); // stands down; both due; the interval allowance re-arms
        this.bridge.frameFlush(); // spent==0 must fall through the allowance gate
        assertEquals(1, this.bridge.counterForTest("buffer_updates"),
                "the first recolor of an interval always runs (the always-drains rule)");
        this.bridge.frameFlush(); // spent > 0 and >= the zero allowance: fast-out
        assertEquals(1, this.bridge.counterForTest("buffer_updates"),
                "past the allowance the frame slice stops recoloring");
        this.bridge.pump(); // stands down (the fast-out armed the marker); re-arms
        this.bridge.frameFlush();
        assertEquals(2, this.bridge.counterForTest("buffer_updates"),
                "the next pump re-arms the interval allowance");
    }

    @Test
    void theTickFallbackReengagesOnePumpAfterFramesStop() {
        this.bridge.updateIdlePumps = 1;
        offer(64, 64);
        offer(96, 64);
        this.bridge.pump();
        this.bridge.frameFlush(); // arms
        this.bridge.pump(); // stands down; both due
        this.bridge.frameFlush(); // rebuild #1 on the frame
        assertEquals(1, this.bridge.counterForTest("buffer_updates"));
        this.bridge.pump(); // marker from that frame: still stands down
        assertEquals(1, this.bridge.counterForTest("buffer_updates"));
        this.bridge.pump(); // NO frame since the last pump: the fallback rebuilds
        assertEquals(2, this.bridge.counterForTest("buffer_updates"),
                "frames stopped (loading screen, hidden window): the tick must take over");
    }

    @Test
    void theFrameSliceRespectsTheGateEnvelopeAndNeverVisitsRegions() {
        this.bridge.updateIdlePumps = 1;
        offer(64, 64);
        offer(68, 64); // same region, two owed tile chunks
        this.bridge.pump(); // commit
        this.bridge.frameFlush(); // arms (nothing due)
        this.bridge.pump(); // stands down; both due
        var region = theRegion();
        int visits = region.visits;
        this.bridge.frameFlush(); // rebuild #1
        assertEquals(1, this.bridge.counterForTest("frame_flushes"));
        assertEquals(1, this.bridge.counterForTest("buffer_updates"));
        assertEquals(0, region.visits - visits,
                "the park guard is tick-side: frames must not add region visits");
        this.bridge.pump(); // consumes that frame's marker
        // A crashed Xaero is never touched — and a GATED frame must NOT arm the marker.
        xaero.map.WorldMap.crashHandler.crashedBy = new RuntimeException("armed");
        this.bridge.frameFlush();
        assertEquals(1, this.bridge.counterForTest("frame_flushes"),
                "gated: the ladder defers before flushing");
        assertEquals(1, this.bridge.counterForTest("buffer_updates"));
        xaero.map.WorldMap.crashHandler.crashedBy = null;
        this.bridge.pump(); // no armed marker from the gated frame: the fallback rebuilds
        assertEquals(2, this.bridge.counterForTest("buffer_updates"),
                "a gated frame leaves the tick fallback armed — else closed gates wedge the set");
        assertEquals(0, this.bridge.counterForTest("commit_failures"), "gates defer, never fail");
    }

    // ---- §12 ingest backpressure (hybrid-scan-plan.md §12; replaces the §18
    // ledger heal — the immediate reporter is KEPT and pinned below) ----

    private int halt() {
        return dev.vox.lss.networking.client.LodRequestManager.INGEST_BACKLOG_HALT_SECTIONS;
    }

    /** Drive the pump into a drainable state once so the latch/watchdog are live. */
    private void primeDrainablePump() {
        this.bridge.pump();
    }

    @Test
    void theConsumerOverridesThePendingIngestBacklogDefault() {
        // The lambda trap (review MAJOR): a lambda cannot override the default
        // method, silently keeping -1 forever — the whole taper then never engages.
        assertEquals(1, this.registered.size());
        offer(64, 64); // a non-empty queue forces the ladder to actually run
        primeDrainablePump();
        assertTrue(this.registered.get(0).pendingIngestBacklog() >= 0,
                "a live bridge reports a REAL value, not the -1 interface default"
                        + " — proving the anonymous-class override exists");
    }

    @Test
    void theReportScalesOccupancyIntoTheHaltDomainAheadOfTheDropPoint() {
        this.bridge.maxQueue = 8;
        this.bridge.maxQueueBytes = Long.MAX_VALUE; // count-dominant for exact fractions
        offer(64, 64);
        primeDrainablePump(); // committed: queue empty again, latch live
        assertEquals(0, this.bridge.reportBackpressure(), "empty + draining = a real 0");
        this.processor.createdRegionLoadState = 0; // stop commits: entries await loads
        offer(64, 65);
        offer(65, 65);
        offer(66, 65); // 3/8 = 37.5% occupancy -> 6144 * (0.375/0.75) = 3072
        assertEquals(3072, this.bridge.reportBackpressure(),
                "the report is the occupancy scaled into the halt domain");
        offer(67, 65);
        offer(68, 65);
        offer(69, 65); // 6/8 = 75% — the halt fires HERE, ahead of the 100% drop point
        assertEquals(halt(), this.bridge.reportBackpressure(),
                "75% occupancy = the full halt (the decode-queue doctrine: halt"
                        + " BEFORE the first drop, ~25% landing room)");
        offer(70, 65);
        offer(71, 65); // 8/8 — clamped, still exactly the halt
        assertEquals(halt(), this.bridge.reportBackpressure());
        assertEquals(0, this.bridge.counterForTest("dropped_overflow"),
                "the halt PRECEDED the first drop — the ordering pin");
    }

    @Test
    void byteOccupancyDominatesWhenItIsTheBindingCap() {
        offer(64, 64);
        primeDrainablePump();
        this.bridge.maxQueue = 8192; // count fraction ~0
        this.bridge.maxQueueBytes = XaeroMapCompat.approxBytes(tile(64, 64)) * 2L;
        this.processor.createdRegionLoadState = 0;
        offer(64, 65); // bytes ~half the cap
        int report = this.bridge.reportBackpressure();
        assertTrue(report >= halt() / 2 && report < halt(),
                "the byte fraction (~50% -> ~2/3 of halt) governs when it binds: " + report);
    }

    @Test
    void aBlockedPumpKeepsGoverningOffTheQueueAndAcceptsOffers() {
        // §12.8 (the live 56k-refusal finding): a gate-blocked pump neither goes
        // -1 nor refuses — the report stays occupancy-driven and offers keep
        // landing in the queue (which is what makes the occupancy signal REAL
        // during exactly the contention episodes the old doctrine went dark for).
        this.bridge.bpPausePumps = 3;
        offer(64, 64);
        primeDrainablePump();
        offer(64, 65);
        this.processor.writingPaused = true; // any pumpLadder early return — the latch
        this.bridge.pump();
        this.bridge.pump();
        assertTrue(this.bridge.reportBackpressure() >= 0,
                "below the hysteresis threshold the report still governs (flap armor)");
        this.bridge.pump(); // third consecutive undrainable pump: the latch flips
        assertTrue(this.bridge.reportBackpressure() >= 0,
                "§12.8: a blocked pump with a live watchdog still GOVERNS off the queue");
        assertFalse(this.bridge.drainableForTest(), "premise: the latch is down");
        int before = this.bridge.queuedForTest();
        this.bridge.offerColumn(OVERWORLD, 90, 90, -64, 320,
                new dev.vox.lss.api.VoxelColumnData(
                        new dev.vox.lss.api.VoxelColumnData.SectionData[0], 1L));
        assertEquals(before + 1, this.bridge.queuedForTest(),
                "§12.8: offers are ACCEPTED while blocked — the queue is the burst buffer");
    }

    @Test
    void aBlockedPumpWithAFullQueueReportsTheHaltThenTheTimeBoxReleases() {
        // §12.8 DELIBERATELY INVERTS the old negative pin ("paused + full queue =
        // -1, never the halt") — that doctrine is what let the first live session
        // stream 731/s into 56k silent refusals. Blocked + full queue now IS the
        // halt; the wedge time-box (not the -1) bounds a long structural pause,
        // and wedge-degraded drops stay silent (doctrine (d)).
        this.bridge.maxQueue = 2;
        this.bridge.bpPausePumps = 1;
        offer(64, 64);
        primeDrainablePump();
        this.processor.createdRegionLoadState = 0;
        offer(64, 65);
        offer(65, 65); // full
        this.processor.writingPaused = true;
        this.bridge.pump();
        assertEquals(halt(), this.bridge.reportBackpressure(),
                "§12.8: blocked + full queue = the halt — the burst is throttled");
        this.clockMillis += this.bridge.bpHaltWedgeMillis + 1;
        this.bridge.pump(); // watchdog stays fresh; still no progress
        assertEquals(-1, this.bridge.reportBackpressure(),
                "no progress across the whole window: the time-box releases the fill");
        assertTrue(this.bridge.describe().contains(", bp=-1(wedged)"), this.bridge.describe());
        int reportsBefore = this.reports.size();
        this.bridge.offerColumn(OVERWORLD, 96, 96, -64, 320,
                new dev.vox.lss.api.VoxelColumnData(
                        new dev.vox.lss.api.VoxelColumnData.SectionData[0], 1L));
        assertEquals(reportsBefore, this.reports.size(),
                "wedge-degraded overflow stays SILENT — reporting would churn re-serves"
                        + " into the same drop (doctrine (d))");
    }

    @Test
    void resumeThroughTheLadderClearsTheBlockEvenWithAnEmptyQueue() {
        // The deadlock guard (review MAJOR-adjacent, rekeyed by §12.8; §12.9
        // de-vacuumed it — the old shape's pendingUpdates were never empty, so
        // the guarded fast-out was unreachable and deleting the guard passed):
        // flush the owed rebuild FIRST, then reach the genuinely-idle blocked
        // state and prove (a) the recovery ladder still runs — at the §12.9
        // ~1 Hz throttle, not 20 Hz — and (b) it clears the latch.
        this.bridge.bpPausePumps = 1;
        this.bridge.updateIdlePumps = 0;
        offer(64, 64);
        primeDrainablePump();
        this.bridge.frameFlush(); // the owed rebuild flushes: pendingUpdates empty
        this.processor.writingPaused = true;
        offer(64, 65);
        this.bridge.pump(); // the queued entry forces the ladder: latch
        assertFalse(this.bridge.drainableForTest(), "premise: the latch is down");
        this.bridge.clearQueue(); // now blocked + genuinely idle
        assertEquals(0, this.bridge.reportBackpressure(),
                "§12.8: blocked + empty queue reports the honest occupancy (0 — which"
                        + " the manager reads as no signal, but it is never a lie)");
        int chain = this.bridge.undrainablePumpsForTest();
        for (int i = 0; i < 10; i++) this.bridge.pump();
        assertTrue(this.bridge.undrainablePumpsForTest() - chain <= 1,
                "§12.9: the blocked-idle recovery ladder runs ~1-in-20 pumps, not 20 Hz"
                        + " (each run takes Xaero's renderPause/mainStuff monitors)");
        this.processor.writingPaused = false;
        for (int i = 0; i < 25 && !this.bridge.drainableForTest(); i++) {
            this.bridge.pump(); // the throttled recovery ladder MUST still run
        }
        assertTrue(this.bridge.drainableForTest(),
                "drainable again: the block clears through the (throttled) ladder");
        assertEquals(0, this.bridge.reportBackpressure());
        offer(64, 66);
        this.bridge.pump();
        assertEquals(2, this.bridge.counterForTest("written"), "offers flow again");
    }

    @Test
    void theLatchWaitsOutTheFlapHysteresis() {
        // §12.9 (tests-lens MINOR: unpinned after the rewrite): the latch must NOT
        // flip before bpPausePumps consecutive undrainable pumps — flap armor for
        // per-pump reflective gates.
        this.bridge.bpPausePumps = 3;
        offer(64, 64);
        primeDrainablePump();
        this.processor.writingPaused = true;
        this.bridge.pump();
        this.bridge.pump();
        assertTrue(this.bridge.drainableForTest(),
                "two undrainable pumps under a 3-threshold: the latch holds");
        this.bridge.pump();
        assertFalse(this.bridge.drainableForTest(), "the third flips it");
    }

    @Test
    void theWatchdogReportsNoSignalWhenThePumpGoesStale() {
        offer(64, 64);
        primeDrainablePump();
        assertEquals(0, this.bridge.reportBackpressure());
        this.clockMillis += this.bridge.bpPumpStaleMillis + 1; // no pump inside the window
        assertEquals(-1, this.bridge.reportBackpressure(),
                "a frozen mirror must never read as live backlog (the watchdog)");
        this.bridge.pump();
        assertEquals(0, this.bridge.reportBackpressure(), "a fresh pump re-arms it");
    }

    @Test
    void theHaltTimeBoxDegradesAWedgedWriterAndReArmsOnDrain() {
        this.bridge.maxQueue = 2;
        this.bridge.maxQueueBytes = Long.MAX_VALUE;
        offer(64, 64);
        primeDrainablePump(); // latch live, one commit (region (2,2) now loaded)
        this.processor.createdRegionLoadState = 0; // loads never land: commits impossible
        offer(128, 64);
        offer(129, 64); // region (4,2), unloadable — 100% count occupancy -> halt
        this.bridge.pump(); // still drainable (reaches drainEntries; entries AWAIT their load)
        assertEquals(halt(), this.bridge.reportBackpressure(), "halt starts the time-box");
        this.clockMillis += this.bridge.bpHaltWedgeMillis + 1;
        this.bridge.pump();
        assertEquals(-1, this.bridge.reportBackpressure(),
                "zero commits through the whole window: the bridge may PACE the"
                        + " stream, never STOP it — degrade to no-signal");
        // The wedge re-arms only below the re-arm occupancy.
        this.bridge.clearQueue();
        this.bridge.pump();
        assertTrue(this.bridge.reportBackpressure() >= 0,
                "drained below the re-arm point: governance resumes");
    }

    @Test
    void governedEvictionsReportAndUngovernedStaySilent() {
        this.bridge.maxQueue = 1;
        offer(64, 64);
        this.bridge.pump();
        this.processor.createdRegionLoadState = 0;
        offer(64, 65);
        offer(70, 65); // evicts (64,65) — governed: reported for its bounded re-serve
        assertEquals(1, this.bridge.counterForTest("dropped_overflow"));
        assertEquals(1, this.reports.size(), "a governed drop self-heals via the reporter");
        assertEquals(64, this.reports.get(0)[1]);
        assertEquals(65, this.reports.get(0)[2]);
        this.backpressureEnabled = false;
        offer(75, 65); // evicts (70,65) — ungoverned: silent (the pre-amendment behavior)
        assertEquals(2, this.bridge.counterForTest("dropped_overflow"));
        assertEquals(1, this.reports.size(),
                "kill switch off = pre-amendment behavior: drops stay silent");
    }

    @Test
    void negativeCoordinatesRoundTripThroughTheEvictionReport() {
        this.bridge.maxQueue = 1;
        this.processor.createdRegionLoadState = 0;
        offer(-33, -1);
        offer(-34, -1); // evicts (-33,-1) — reported immediately (governed)
        assertEquals(1, this.reports.size());
        assertEquals(-33, this.reports.get(0)[1], "chunkX must round-trip for negatives");
        assertEquals(-1, this.reports.get(0)[2], "chunkZ must round-trip for negatives");
    }

    @Test
    void aStaleDimensionDropReportsRegardlessOfTheSwitch() {
        this.backpressureEnabled = false; // the reporter is CORRECTNESS, not governance
        offer(64, 64);
        this.bridge.offerPrepared(net.minecraft.world.level.Level.NETHER, tile(70, 64));
        this.bridge.pump(); // (70,64) belongs to another dimension: dropped stale + reported now
        assertEquals(1, this.bridge.counterForTest("dropped_stale"));
        assertEquals(1, this.bridge.counterForTest("drops_reported"));
        assertEquals(1, this.reports.size());
        org.junit.jupiter.api.Assertions.assertSame(
                net.minecraft.world.level.Level.NETHER, this.reports.get(0)[0]);
        assertEquals(1, this.bridge.counterForTest("written"), "the current dimension's tile commits");
    }

    @Test
    void theWorldIdChangeClearReportsEveryEntry() {
        // §12.1(c): the queued tiles belong to a previous world and the player may
        // return — the stamps must be forgotten or the return never re-declares.
        offer(64, 64);
        this.bridge.pump(); // records lastWorldId ("stub-world"), commits (64,64)
        this.processor.createdRegionLoadState = 0;
        offer(65, 64);
        offer(66, 64);
        this.processor.currentWorldId = "another-world";
        this.bridge.pump();
        assertEquals(2, this.bridge.counterForTest("dropped_stale"),
                "the old world's tiles are cleared");
        assertEquals(2, this.reports.size(),
                "world-id clears REPORT (dimension-switch self-heal, §12.1(c))");
    }

    @Test
    void theSettingsOffClearDoesNotReportAndDropsOffersPreExtraction() {
        // A settings-off clear must not report (the map is off by the USER's
        // choice, not dropped work) — and §12.9 restores the deleted refusal's
        // one legitimate job for exactly this state: once the ladder observes both
        // switches off, offers drop PRE-extraction (no 256-pixel tax per column
        // for tiles the next pump's clear would discard), counted skipped_settings.
        try {
            xaero.map.common.config.option.WorldMapProfiledConfigOptions.LOAD_NEW_CHUNKS.value = false;
            xaero.map.common.config.option.WorldMapProfiledConfigOptions.UPDATE_CHUNKS.value = false;
            offer(64, 64);
            offer(65, 64);
            this.bridge.pump();
            assertEquals(2, this.bridge.counterForTest("skipped_settings"));
            assertTrue(this.reports.isEmpty(), "a settings-off clear never reports");
            this.bridge.offerColumn(OVERWORLD, 66, 64, -64, 320,
                    new dev.vox.lss.api.VoxelColumnData(
                            new dev.vox.lss.api.VoxelColumnData.SectionData[0], 1L));
            assertEquals(0, this.bridge.queuedForTest(),
                    "the flagged offer drops pre-extraction — the queue never grows");
            assertEquals(3, this.bridge.counterForTest("skipped_settings"));
            xaero.map.common.config.option.WorldMapProfiledConfigOptions.LOAD_NEW_CHUNKS.value = true;
            xaero.map.common.config.option.WorldMapProfiledConfigOptions.UPDATE_CHUNKS.value = true;
            offer(67, 64); // pre-flag-clear offers still route through the queue path?
            this.bridge.pump(); // the ladder re-observes the switches and clears the flag
            this.bridge.offerColumn(OVERWORLD, 68, 64, -64, 320,
                    new dev.vox.lss.api.VoxelColumnData(
                            new dev.vox.lss.api.VoxelColumnData.SectionData[0], 1L));
            assertEquals(1, this.bridge.queuedForTest(),
                    "switches back on: the flag clears through the ladder and offers land");
        } finally {
            xaero.map.common.config.option.WorldMapProfiledConfigOptions.LOAD_NEW_CHUNKS.value = true;
            xaero.map.common.config.option.WorldMapProfiledConfigOptions.UPDATE_CHUNKS.value = true;
        }
    }

    @Test
    void regionDeferralsAreCapExemptAndCommitWhenTheRegionRests() {
        // §12 review MAJOR: region-scoped DEFERRED is retained like AWAITING_* —
        // the ledger's hold-until-committable semantic via the queue itself; a
        // stuck region flows into the wedge machinery instead of burning ingest
        // strikes at one per DEFER_CAP interval.
        this.bridge.deferCap = 1;
        var region = new MapRegion();
        region.resting = false; // region-scoped busy: the whole bucket defers
        this.processor.regions.put((2L << 32) | 2L, region);
        offer(64, 64);
        this.bridge.pump();
        this.bridge.pump();
        this.bridge.pump(); // far past the old cap
        assertEquals(0, this.bridge.counterForTest("dropped_expired"),
                "region deferrals never expire — retained until the region rests");
        assertEquals(1, this.bridge.queuedForTest());
        assertTrue(this.reports.isEmpty(), "and never report");
        region.resting = true;
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"),
                "the retained entry commits when the region rests");
    }

    @Test
    void aForeignEntryBehindADeferredSiblingExitsWhenTheRegionRests() {
        // The DEFERRED continue skips the rest of the bucket, so a foreign entry
        // BEHIND the deferred sibling is retained with it (deferral, not deletion)
        // and exits via the stale filter once the region rests.
        this.bridge.deferCap = 1;
        var region = new MapRegion();
        region.resting = false;
        this.processor.regions.put((2L << 32) | 2L, region);
        offer(64, 64);
        this.bridge.offerPrepared(net.minecraft.world.level.Level.NETHER, tile(68, 64));
        this.bridge.pump();
        assertEquals(2, this.bridge.queuedForTest(), "both retained while the region is busy");
        assertTrue(this.reports.isEmpty());
        region.resting = true;
        this.bridge.pump(); // ours commits; the foreign one hits the stale filter
        assertEquals(1, this.bridge.counterForTest("written"));
        assertEquals(1, this.bridge.counterForTest("dropped_stale"));
        assertEquals(1, this.reports.size(), "the foreign entry reports (deferred drain)");
        org.junit.jupiter.api.Assertions.assertSame(
                net.minecraft.world.level.Level.NETHER, this.reports.get(0)[0]);
    }

    @Test
    void aTileDeferralExpiryDropsSilently() {
        // DEFERRED_TILE keeps the cap (a tile-scoped wedge must not pin its entry
        // forever) but the expiry is SILENT — counted, never reported (§12 review:
        // a report would burn ingest strikes against a stalled resource).
        this.bridge.deferCap = 1;
        offer(64, 64);
        this.bridge.pump(); // creates region+tileChunk, commits
        var region = theRegion();
        var tileChunk = region.getChunk(0, 0);
        tileChunk.loadState = 0; // tile-scoped busy: DEFERRED_TILE
        offer(64, 64);
        this.bridge.pump(); // burn 1
        this.bridge.pump(); // burn 2 — past the cap: silent drop
        assertEquals(1, this.bridge.counterForTest("dropped_expired"));
        assertTrue(this.reports.isEmpty(), "tile expiry never reports");
    }

    @Test
    void theKillSwitchDisablesReportAndToken() {
        // §12.4: bridge-on + switch off ⇒ pre-amendment behavior — report -1,
        // silent drops, bp=off (review MAJOR: deleting the switch check silently
        // re-enables the taper for a user who turned it off; the production
        // supplier composes the GLOBAL #71 switch into this same boolean, so
        // this pin covers both keys' off states).
        this.bridge.bpPausePumps = 1;
        offer(64, 64);
        primeDrainablePump();
        this.backpressureEnabled = false;
        assertEquals(-1, this.bridge.reportBackpressure(), "switch off = no signal, always");
        this.processor.writingPaused = true;
        this.bridge.pump(); // blocked latch engages...
        int before = this.bridge.queuedForTest();
        this.bridge.offerColumn(OVERWORLD, 91, 91, -64, 320,
                new dev.vox.lss.api.VoxelColumnData(
                        new dev.vox.lss.api.VoxelColumnData.SectionData[0], 1L));
        assertEquals(before + 1, this.bridge.queuedForTest(),
                "switch off: offers are accepted while blocked (as everywhere)");
        assertTrue(this.bridge.describe().contains(", bp=off"), this.bridge.describe());
    }

    @Test
    void aBlockedPumpReportScalesWithTheAbsorbedBurst() {
        // §12.8's engine: during a movement-burst block the queue absorbs the
        // arrivals and the report ESCALATES with the occupancy — taper first,
        // the halt at 75% — so the stream throttles exactly while the writer is
        // gate-blocked (the aliasing hole: the old -1 sampled only the calm
        // between bursts).
        this.bridge.bpPausePumps = 2;
        this.bridge.maxQueue = 4;
        this.bridge.maxQueueBytes = Long.MAX_VALUE;
        offer(64, 64);
        primeDrainablePump();
        this.processor.createdRegionLoadState = 0;
        offer(64, 65); // keeps the queue non-empty so the ladder runs while blocked
        this.processor.writingPaused = true;
        this.bridge.pump();
        this.bridge.pump(); // the latch flips
        assertFalse(this.bridge.drainableForTest(), "premise: blocked");
        assertEquals(Math.round(halt() * (0.25 / 0.75)), this.bridge.reportBackpressure(),
                "1/4 occupancy while blocked: the taper fraction, not -1");
        offer(70, 64);
        offer(71, 64); // 3/4 = the halt occupancy
        assertEquals(3, this.bridge.queuedForTest(), "offers absorbed into the queue");
        assertEquals(halt(), this.bridge.reportBackpressure(),
                "the burst escalates to the HALT while still blocked");
    }

    @Test
    void aRecedingQueueReBasesTheHaltWindowAndATrickleCommitDoesNot() {
        // §12.9 (both panels' trickle MAJOR): "progress" = the queue RECEDING from
        // its in-window peak by >= BP_HALT_PROGRESS_EPS — never a `written` delta.
        // The old predicate let a 1-commit-per-<7s trickle (a parked DEFERRED
        // region + the drain rotation's strays) hold the LOD fill at a dead stop
        // for hours; a commit offset by an arrival is NOT progress.
        this.bridge.maxQueue = 20;
        this.bridge.maxQueueBytes = Long.MAX_VALUE;
        offer(64, 64);
        primeDrainablePump(); // region (2,2) loaded
        this.processor.createdRegionLoadState = 0;
        for (int i = 0; i < 15; i++) offer(128 + i, 64); // region (4,2): unloadable
        offer(65, 64);
        offer(66, 64);
        offer(67, 64); // region (2,2), loaded: drainable backlog — 18/20 = 0.9
        assertEquals(halt(), this.bridge.reportBackpressure(), "the time-box opens at 0.9");
        this.clockMillis += this.bridge.bpHaltWedgeMillis - 1000;
        this.bridge.pump(); // drains the 3 loaded entries: 18/20 -> 15/20 (recession 0.15)
        assertEquals(4, this.bridge.counterForTest("written"));
        assertEquals(halt(), this.bridge.reportBackpressure(),
                "a RECEDING queue re-bases the window — still halted, not wedged");
        this.clockMillis += this.bridge.bpHaltWedgeMillis - 1000;
        this.bridge.pump(); // nothing drains (only the unloadable region remains)
        assertEquals(halt(), this.bridge.reportBackpressure(),
                "inside the re-based window: still no wedge (the false-positive arm)");
        // The trickle arm: a commit whose slot an arrival refills — written ticks,
        // occupancy does not recede — must NOT re-base.
        offer(70, 64); // loaded region: 16/20 transiently
        this.bridge.pump(); // commits it: back to 15/20 — zero net recession
        assertEquals(5, this.bridge.counterForTest("written"));
        this.clockMillis += 2001; // past the re-based window with no recession
        this.bridge.pump();
        assertEquals(-1, this.bridge.reportBackpressure(),
                "a trickle commit offset by an arrival is NOT progress: the wedge fires");
    }

    @Test
    void theWedgeReArmsOnTheDutyCycleClockUnderSustainedArrival() {
        // §12.9 (control-loop MAJOR: the one-way exit): with arrival >= drain the
        // occupancy re-arm floor (0.5) is unreachable — the wedge must ALSO re-arm
        // on the clock, turning a persistent structural pause into a bounded duty
        // cycle instead of a session-long ungoverned latch.
        this.bridge.maxQueue = 2;
        this.bridge.bpPausePumps = 1;
        offer(64, 64);
        primeDrainablePump();
        this.processor.createdRegionLoadState = 0;
        offer(64, 65);
        offer(65, 65); // full — occupancy pinned at 1.0
        this.processor.writingPaused = true;
        this.bridge.pump();
        assertEquals(halt(), this.bridge.reportBackpressure(), "the window opens");
        this.clockMillis += this.bridge.bpHaltWedgeMillis + 1;
        this.bridge.pump();
        assertEquals(-1, this.bridge.reportBackpressure(), "the wedge fires");
        this.clockMillis += 5000; // inside the re-arm window, occupancy still 1.0
        this.bridge.pump();
        assertEquals(-1, this.bridge.reportBackpressure(), "still wedged inside the cycle");
        this.clockMillis += XaeroMapCompat.BP_WEDGE_REARM_MILLIS - 5000 + 1;
        this.bridge.pump();
        assertEquals(halt(), this.bridge.reportBackpressure(),
                "the clock re-arms governance at occupancy 1.0 — the halt re-engages"
                        + " and the writer gets a silenced stream to drain against");
    }

    @Test
    void aNoSignalIntervalClearsTheHaltWindow() {
        // Review MAJOR (rekeyed by §12.8): the timer surviving a -1 interval fired
        // a FALSE wedge on the first governing poll back. Blocked no longer
        // produces -1, so the surviving -1 interval class is the staleness
        // watchdog (a frozen pump) — the window must re-open fresh on revival,
        // never inherit the stale interval's age. (A long BLOCKED interval at a
        // full report now WEDGES by design — that is the inverted pin above.)
        this.bridge.bpPausePumps = 1;
        this.bridge.maxQueue = 2;
        this.bridge.maxQueueBytes = Long.MAX_VALUE;
        offer(64, 64);
        primeDrainablePump();
        this.processor.createdRegionLoadState = 0;
        offer(128, 64);
        offer(129, 64); // region (4,2): unloadable — halt
        this.bridge.pump();
        assertEquals(halt(), this.bridge.reportBackpressure(), "the time-box opens");
        this.clockMillis += this.bridge.bpHaltWedgeMillis * 4; // the pump FREEZES (no pump calls)
        assertEquals(-1, this.bridge.reportBackpressure(),
                "stale watchdog = no signal — and the -1 exit CLEARS the timer");
        this.bridge.pump(); // revival: watchdog fresh again
        assertEquals(halt(), this.bridge.reportBackpressure(),
                "the first governing poll RE-OPENS the window — never a false wedge");
    }

    @Test
    void bpTokenIsTriState() {
        this.bridge.bpPausePumps = 1;
        offer(64, 64);
        primeDrainablePump();
        assertTrue(this.bridge.describe().contains(", bp=0.00"),
                "governing at empty queue: the fraction — " + this.bridge.describe());
        assertFalse(this.bridge.describe().contains("(blocked)"),
                "drainable: no suffix — " + this.bridge.describe());
        this.processor.createdRegionLoadState = 0;
        offer(65, 64); // keep the ladder running while blocked
        this.processor.writingPaused = true;
        this.bridge.pump();
        assertTrue(this.bridge.describe().contains(", bp=0.00(blocked)"),
                "§12.8: blocked shows the governing FRACTION + suffix (exact — the"
                        + " render is the live instrument) — " + this.bridge.describe());
        this.backpressureEnabled = false;
        assertTrue(this.bridge.describe().contains(", bp=off"), this.bridge.describe());
    }

    @Test
    void aBlockedNotWedgedOverflowIsReportedForReServe() {
        // §12.8 dropped reportDroppedIfGoverned's pumpDrainable conjunct: a
        // blocked-not-wedged overflow reports, and the halt the blocked pump is
        // simultaneously reporting defers the re-declaration — the re-serve lands
        // in a draining queue after the burst instead of a churn loop. (11.8k of
        // the first live session's 12.9k overflow drops were silenced by the old
        // conjunct and became permanent holes.)
        this.bridge.maxQueue = 1;
        this.bridge.bpPausePumps = 1;
        offer(64, 64);
        primeDrainablePump();
        this.processor.createdRegionLoadState = 0;
        offer(64, 65); // fills the 1-slot queue
        this.processor.writingPaused = true;
        this.bridge.pump(); // blocked
        assertFalse(this.bridge.drainableForTest(), "premise: blocked, not wedged");
        int before = this.reports.size();
        this.bridge.offerColumn(OVERWORLD, 95, 95, -64, 320,
                new dev.vox.lss.api.VoxelColumnData(
                        new dev.vox.lss.api.VoxelColumnData.SectionData[0], 1L));
        assertEquals(before + 1, this.reports.size(),
                "a blocked-not-wedged overflow REPORTS — the drop heals after the burst");
        assertEquals(95, this.reports.get(before)[1]);
    }

    @Test
    void theOfferColumnPreGateOverflowReportsTheArrivingPosition() {
        // The pre-gate drops the ARRIVING position (the evict loop drops the
        // OLDEST — the recorded asymmetry); governed, so it reports.
        this.bridge.maxQueue = 1;
        this.processor.createdRegionLoadState = 0;
        offer(64, 64); // fills the 1-slot queue
        this.bridge.offerColumn(OVERWORLD, 93, 93, -64, 320,
                new dev.vox.lss.api.VoxelColumnData(
                        new dev.vox.lss.api.VoxelColumnData.SectionData[0], 1L));
        assertEquals(1, this.bridge.counterForTest("dropped_overflow"));
        assertEquals(1, this.reports.size());
        assertEquals(93, this.reports.get(0)[1]);
        assertEquals(93, this.reports.get(0)[2]);
    }

    @Test
    void aStaleDimensionReplacementCountsAndReports() {
        // Review m1: the in-place foreign-dimension replacement was a silent,
        // uncounted drop — it now takes the stale route like every other one.
        offer(64, 64); // OVERWORLD queued
        this.bridge.offerPrepared(net.minecraft.world.level.Level.NETHER, tile(64, 64));
        assertEquals(1, this.bridge.counterForTest("dropped_stale"));
        assertEquals(1, this.reports.size());
        org.junit.jupiter.api.Assertions.assertSame(OVERWORLD, this.reports.get(0)[0],
                "the REPLACED (overworld) entry is the reported one");
    }

    @Test
    void sessionTeardownResetsTheBackpressureState() {
        // §12.9 de-vacuumed (tests-lens MINOR: the old final leg's pump took the
        // idle fast-out, so nothing proved the main-thread counter reset): the
        // hysteresis chain is built to 3 under a 5-threshold, then the settle
        // pump must zero it — deleting settleSessionEnd's undrainablePumps reset
        // reds the last assert directly.
        this.bridge.bpPausePumps = 5;
        offer(64, 64);
        primeDrainablePump();
        this.processor.writingPaused = true;
        this.bridge.pump();
        this.bridge.pump();
        this.bridge.pump();
        assertEquals(3, this.bridge.undrainablePumpsForTest(), "premise: a standing chain");
        assertTrue(this.bridge.drainableForTest(), "below the threshold: not yet latched");
        this.bridge.onSessionEnd();
        this.bridge.pump(); // the main-thread half settles at the pump top
        assertEquals(0, this.bridge.undrainablePumpsForTest(),
                "the settle half zeroes the consecutive chain — a new session cannot"
                        + " inherit the old one's count");
        assertTrue(this.bridge.drainableForTest());
    }

    private MapRegion theRegion() {
        assertEquals(1, this.processor.regions.size(), "one region in play");
        return this.processor.regions.values().iterator().next();
    }

    private static long count(List<String> events, String event) {
        return events.stream().filter(event::equals).count();
    }

    @Test
    void theRebuildRunsUnderTheWriterGatesAfterTheIdleWindowAndReArmsBeingWritten() {
        this.bridge.updateIdlePumps = 3;
        offer(64, 64);
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
        assertEquals(1, this.bridge.counterForTest("pending_updates"));
        var region = theRegion();
        MapTileChunk tileChunk = region.getChunk(0, 0);
        assertTrue(tileChunk.wasChanged(), "the native transient: changed, unflagged");
        this.bridge.pump();
        assertFalse(XaeroStubEvents.snapshot().contains("tileChunk.updateBuffers 16,16"),
                "inside the coalescing window nothing rebuilds yet");

        // The saver reset beingWritten between the commit and the rebuild.
        region.beingWritten = false;
        pumpIdleWindow();
        var events = XaeroStubEvents.snapshot();
        int rebuilt = events.lastIndexOf("tileChunk.updateBuffers 16,16");
        int consumed = events.lastIndexOf("tileChunk.setChanged false");
        assertTrue(rebuilt >= 0 && consumed > rebuilt,
                "rebuild (under both region monitors — the stub enforces), then the change"
                        + " is consumed: " + events);
        assertEquals(Boolean.TRUE, region.beingWritten,
                "re-armed before the rebuild: the rebuilt texture must reach the cache, and"
                        + " the save path is what requests it");
        assertFalse(events.contains("region.setBeingWritten false"));
        assertFalse(tileChunk.wasChanged());
        assertFalse(region.allCachePrepared,
                "the rebuild un-prepares the region — the flip the saver races on");
        assertEquals(1, this.bridge.counterForTest("buffer_updates"));
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
        assertEquals(1, tileChunk.bufferUpdates);
        assertTrue(events.contains("fastConfig.new"), "the native per-pass config snapshot");
        // The exact native argument list — every parameter is Object-erased behind the
        // handle, so a transposition compiles and only surfaces live (review B).
        assertTrue(tileChunk.lastUpdateProcessor == this.processor);
        assertTrue(tileChunk.lastUpdateTint == this.processor.tintProvider);
        assertTrue(tileChunk.lastUpdateOverlay == this.processor.overlayManager);
        assertTrue(tileChunk.lastUpdateCache == this.processor.shapeCache);
        assertNotNull(tileChunk.lastUpdateConfig);
        assertFalse(tileChunk.lastUpdateDebug, "the writer's detailed-debug flag is log-only: false");
        assertFalse(events.stream().anyMatch(e -> e.startsWith("tileChunk.setToUpdateBuffers")),
                "the rebuild phase never sets the flag either (the §15 invariant in both phases)");
    }

    @Test
    void owedRegionsAreKeptVisitedSoXaeroCannotParkThem() {
        // LeafRegionTexture.postUpload parks a region (loadState 3, tiles released) once it
        // is not being written, 1 s passed since its last visit and no tile chunk carries
        // the flag — the flag WAS the park guard (review A MAJOR); the visit is ours now.
        this.bridge.updateIdlePumps = 1000;
        offer(64, 64);
        offer(68, 64);
        this.bridge.pump();
        var region = theRegion();
        int visits = region.visits;
        this.bridge.pump();
        this.bridge.pump();
        assertEquals(2, region.visits - visits, "one visit per owed REGION per pump, not per entry");
        region.loadState = 0; // unloaded: never visit a region that is not loaded
        visits = region.visits;
        this.bridge.pump();
        assertEquals(0, region.visits - visits);
    }

    @Test
    void theFlushBorrowsTheCommitBudgetOnlyWhenTheQueueIsEmptyOrTheSetIsPastTheSoftCap() {
        this.bridge.updateIdlePumps = 1;
        this.bridge.updateNanosBudget = 0;
        this.bridge.updateBorrowNanos = Long.MAX_VALUE;
        this.bridge.pendingUpdatesSoftCap = 100;
        offer(64, 64);
        offer(68, 64);
        offer(72, 64);
        this.bridge.pump();
        offer(96, 64); // a queued entry the drain will NOT reach: its region is busy
        var other = new MapRegion();
        other.resting = false;
        this.processor.regions.put((3L << 32) | 2L, other);
        this.bridge.pump(); // queue non-empty, set under the soft cap: budget 0 → one rebuild
        assertEquals(1, this.bridge.counterForTest("buffer_updates"));
        this.bridge.pendingUpdatesSoftCap = 1; // past the soft cap: half the borrow → all
        this.bridge.pump();
        assertEquals(3, this.bridge.counterForTest("buffer_updates"),
                "past the soft cap the flush borrows half the commit budget (Long.MAX_VALUE/2):"
                        + " both remaining entries rebuild in one pump");
        offer(76, 64);
        this.bridge.pendingUpdatesSoftCap = 100;
        this.bridge.pump(); // commits (19,16)
        this.bridge.clearQueue();
        this.bridge.pump(); // queue EMPTY: the whole borrow → everything due rebuilds
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
    }

    @Test
    void aZeroRebuildBudgetStillRebuildsOnePerPumpAndResumes() {
        this.bridge.updateIdlePumps = 1;
        this.bridge.updateNanosBudget = 0;
        this.bridge.updateBorrowNanos = 0; // pin truncation: no borrowing
        offer(64, 64);
        offer(68, 64);
        this.bridge.pump();
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("buffer_updates"),
                "the first removing outcome is exempt from the budget — exactly one per pump");
        assertEquals(1, this.bridge.counterForTest("pending_updates"));
        this.bridge.pump();
        assertEquals(2, this.bridge.counterForTest("buffer_updates"), "and the rest resumes next pump");
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
    }

    @Test
    void aNotReadyRegionIsProbedOncePerFlushAndConsumesNoProgress() {
        this.bridge.updateIdlePumps = 2;
        this.bridge.updateNanosBudget = 0;
        this.bridge.updateBorrowNanos = 0; // pin truncation: no borrowing
        offer(64, 64);
        offer(68, 64);
        offer(72, 64); // three tile chunks of region (2,2)
        this.bridge.pump(); // 1
        offer(96, 64); // region (3,2), touched later — sits AFTER the three in touch order
        this.bridge.pump(); // 2
        var busy = this.processor.regions.get((2L << 32) | 2L);
        var ready = this.processor.regions.get((3L << 32) | 2L);
        assertNotNull(busy);
        assertNotNull(ready);
        busy.resting = false;
        this.bridge.pump(); // 3: the three are due (stalled); (3,2) is not yet
        assertEquals(0, this.bridge.counterForTest("buffer_updates"));
        int probesBefore = busy.gateProbes;
        this.bridge.pump(); // 4: all four due
        assertEquals(1, busy.gateProbes - probesBefore,
                "a not-ready region is probed ONCE per flush, not once per owed tile chunk"
                        + " (the per-entry-probe pattern plan §14 removed)");
        assertEquals(1, ready.getChunk(0, 0).bufferUpdates,
                "not-ready verdicts consume no budget: the ready region behind them still"
                        + " rebuilds in the same pump");
        assertEquals(3, this.bridge.counterForTest("pending_updates"));
    }

    @Test
    void aReTouchUnderBudgetTruncationResetsTheStallClock() {
        // The only reachable re-touch of a STALLED entry: its region turns resting in a
        // pump whose flush is truncated (budget) before reaching it, and the drain then
        // commits into its tile chunk. The reset makes it wait a FRESH idle window.
        this.bridge.updateIdlePumps = 3;
        this.bridge.updateNanosBudget = 0; // one removing outcome per pump
        this.bridge.updateBorrowNanos = 0; // pin truncation: no borrowing
        offer(96, 64);            // F: tile chunk (24,16), region (3,2) — the OLDER entry
        this.bridge.pump();       // pump 1
        offer(64, 64);            // E: tile chunk (16,16), region (2,2)
        this.bridge.pump();       // pump 2
        var rF = this.processor.regions.get((3L << 32) | 2L);
        var rE = this.processor.regions.get((2L << 32) | 2L);
        assertNotNull(rF);
        assertNotNull(rE);
        rF.resting = false;
        rE.resting = false;
        this.bridge.pump();       // 3
        this.bridge.pump();       // 4: F due → stalled
        this.bridge.pump();       // 5: E due → stalled
        assertEquals(0, this.bridge.counterForTest("buffer_updates"));
        assertEquals(2, this.bridge.counterForTest("pending_updates"));
        rF.resting = true;
        rE.resting = true;
        offer(65, 64);            // a second tile of E's tile chunk, for this pump's drain
        this.bridge.pump();       // 6: flush rebuilds F (older) and stops; drain re-touches E
        assertEquals(1, this.bridge.counterForTest("buffer_updates"));
        assertEquals(1, this.bridge.counterForTest("pending_updates"));
        this.bridge.pump();       // 7
        this.bridge.pump();       // 8
        assertEquals(1, this.bridge.counterForTest("buffer_updates"),
                "the re-touch reset E's stall clock: it waits a fresh idle window instead of"
                        + " rebuilding at once as a still-stalled entry would");
        this.bridge.pump();       // 9: three pumps since the touch at 6
        assertEquals(2, this.bridge.counterForTest("buffer_updates"));
    }

    @Test
    void rebuildsCoalescePerTileChunkAcrossPumps() {
        this.bridge.updateIdlePumps = 3;
        offer(64, 64);
        offer(65, 64);
        this.bridge.pump();
        offer(66, 65);
        offer(67, 67); // tile chunk (16,16) touched again — its window restarts
        this.bridge.pump();
        offer(68, 64); // tile chunk (17,16)
        this.bridge.pump();
        assertEquals(5, this.bridge.counterForTest("written"));
        assertEquals(2, this.bridge.counterForTest("pending_updates"));
        pumpIdleWindow();
        var events = XaeroStubEvents.snapshot();
        assertEquals(1, count(events, "tileChunk.updateBuffers 16,16"),
                "four tiles of one tile chunk → ONE rebuild: " + events);
        assertEquals(1, count(events, "tileChunk.updateBuffers 17,16"));
        assertEquals(2, this.bridge.counterForTest("buffer_updates"));
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
    }

    @Test
    void aRebuildWaitsForARestingRegionAndAPermanentStallDrops() {
        this.bridge.updateIdlePumps = 1;
        this.bridge.updateMaxStallPumps = 3;
        offer(64, 64);
        this.bridge.pump();
        var region = theRegion();
        region.resting = false; // recache requested / being saved — the crash window
        this.bridge.pump();
        this.bridge.pump();
        assertFalse(XaeroStubEvents.snapshot().contains("tileChunk.updateBuffers 16,16"),
                "never un-prepare a region that may be queued for caching");
        assertEquals(1, this.bridge.counterForTest("pending_updates"));
        region.resting = true;
        this.bridge.pump();
        assertTrue(XaeroStubEvents.snapshot().contains("tileChunk.updateBuffers 16,16"));
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
        assertEquals(0, this.bridge.counterForTest("dropped_updates"));

        offer(68, 64);
        this.bridge.pump();
        region.resting = false;
        for (int i = 0; i < 6; i++) {
            this.bridge.pump();
        }
        assertFalse(XaeroStubEvents.snapshot().contains("tileChunk.updateBuffers 17,16"));
        assertEquals(1, this.bridge.counterForTest("dropped_updates"),
                "a region that never rests drops its owed rebuild, counted (its texture"
                        + " self-heals on reload)");
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
    }

    @Test
    void theHardCapPausesCommitsUntilRebuildsDrain() {
        this.bridge.updateIdlePumps = 1;
        this.bridge.pendingUpdatesHardCap = 2;
        offer(64, 64);
        offer(68, 64);
        this.bridge.pump();
        assertEquals(2, this.bridge.counterForTest("written"));
        var region = theRegion();
        region.resting = false;
        offer(72, 64);
        this.bridge.pump();
        assertEquals(2, this.bridge.counterForTest("written"),
                "at the hard cap commits pause — the owed set must never grow unbounded");
        assertEquals(1, this.bridge.queuedForTest(), "the entry stays queued, not dropped");
        assertEquals(0, this.bridge.counterForTest("dropped_expired"));
        region.resting = true;
        this.bridge.pump();
        assertEquals(3, this.bridge.counterForTest("written"),
                "the flush runs before the drain: drained rebuilds free the commit");
        assertEquals(2, this.bridge.counterForTest("buffer_updates"));
    }

    @Test
    void theSoftCapMakesTheOldestOwedRebuildDueAtOnce() {
        this.bridge.updateIdlePumps = 1000;
        this.bridge.pendingUpdatesSoftCap = 1;
        offer(64, 64);
        this.bridge.pump();
        this.bridge.pump();
        assertFalse(XaeroStubEvents.snapshot().contains("tileChunk.updateBuffers 16,16"),
                "at the cap nothing is over it");
        offer(68, 64);
        this.bridge.pump(); // commits (17,16) — the flush ran before, saw 1 pending
        this.bridge.pump(); // now 2 pending → the oldest is over the cap
        var events = XaeroStubEvents.snapshot();
        assertTrue(events.contains("tileChunk.updateBuffers 16,16"), events.toString());
        assertFalse(events.contains("tileChunk.updateBuffers 17,16"),
                "only the overflow is forced; the youngest keeps its window");
        assertEquals(1, this.bridge.counterForTest("pending_updates"));
    }

    @Test
    void anUnloadedOrReplacedTileChunkDropsItsOwedRebuild() {
        this.bridge.updateIdlePumps = 1;
        offer(64, 64);
        this.bridge.pump();
        var region = theRegion();
        region.setChunk(0, 0, new MapTileChunk(region, 16, 16)); // replaced
        this.bridge.pump();
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("dropped_unloaded"));
        offer(68, 64);
        this.bridge.pump();
        region.loadState = 0; // unloaded
        this.bridge.pump();
        this.bridge.pump();
        assertEquals(2, this.bridge.counterForTest("dropped_unloaded"));
        region.loadState = 2;
        offer(72, 64);
        this.bridge.pump();
        region.getChunk(2, 0).loadState = 0; // a tile-chunk-only teardown (deleteTexturesAndBuffers)
        this.bridge.pump();
        this.bridge.pump();
        assertEquals(3, this.bridge.counterForTest("dropped_unloaded"));
        assertEquals(0, this.bridge.counterForTest("dropped_updates"), "its own counter — a parking race must be tellable apart");
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
        assertFalse(XaeroStubEvents.snapshot().stream().anyMatch(e -> e.startsWith("tileChunk.updateBuffers")),
                "a reload rebuilds its own textures — never touch a foreign tile chunk");
    }

    @Test
    void aChangeTheNativeWriterAlreadyConsumedNeedsNoRebuild() {
        this.bridge.updateIdlePumps = 1;
        offer(64, 64);
        this.bridge.pump();
        theRegion().getChunk(0, 0).changed = false; // native bottom-neighbor consumption
        pumpIdleWindow();
        assertFalse(XaeroStubEvents.snapshot().stream().anyMatch(e -> e.startsWith("tileChunk.updateBuffers")));
        assertEquals(0, this.bridge.counterForTest("buffer_updates"));
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
    }

    @Test
    void owedRebuildsFlushEvenAfterTheBridgeIsDisabledOrTheQueueEmpties() {
        this.bridge.updateIdlePumps = 1;
        offer(64, 64);
        this.bridge.pump();
        offer(68, 64); // queued, never committed
        this.enabled = false;
        this.bridge.pump();
        this.bridge.pump();
        assertEquals(0, this.bridge.queuedForTest(), "the live toggle still drops the backlog");
        assertTrue(XaeroStubEvents.snapshot().contains("tileChunk.updateBuffers 16,16"),
                "a rebuild owed to an already-written tile chunk still runs — dropping it"
                        + " would leave written tiles invisible until a reload");
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
    }

    @Test
    void sessionEndClearsOwedRebuilds() {
        this.bridge.updateIdlePumps = 1;
        offer(64, 64);
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("pending_updates"));
        this.bridge.onSessionEnd();
        this.bridge.pump(); // the owed-set drop is the main-thread half (sweep C)
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
        assertEquals(1, this.bridge.counterForTest("dropped_updates"), "lost, counted");
        pumpIdleWindow();
        assertFalse(XaeroStubEvents.snapshot().stream().anyMatch(e -> e.startsWith("tileChunk.updateBuffers")),
                "the old world's tile chunks are never touched again");
    }

    @Test
    void anotherDimensionsOwedRebuildsWaitForTheReturnAndDropAfterTheStallWindow() {
        this.bridge.updateIdlePumps = 1;
        this.bridge.updateMaxStallPumps = 4;
        offer(64, 64);
        this.bridge.pump();
        this.processor.mapWorld.currentDimensionId = NETHER;
        this.clientDimension = NETHER;
        this.bridge.pump();
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("pending_updates"),
                "the pixel recipe reads the CURRENT dimension's shading — wait, don't rebuild");
        assertEquals(0, this.bridge.counterForTest("dropped_updates"));
        assertFalse(XaeroStubEvents.snapshot().stream().anyMatch(e -> e.startsWith("tileChunk.updateBuffers")));
        // Back in time: the rebuild runs on return.
        this.processor.mapWorld.currentDimensionId = OVERWORLD;
        this.clientDimension = OVERWORLD;
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("buffer_updates"));
        // Away past the stall window: dropped (counted) — the accepted residual.
        offer(68, 64);
        this.bridge.pump();
        this.processor.mapWorld.currentDimensionId = NETHER;
        this.clientDimension = NETHER;
        for (int i = 0; i < 7; i++) this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("dropped_updates"));
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
        assertEquals(1, this.bridge.counterForTest("buffer_updates"));
    }

    @Test
    void aPreviousXaeroSessionsOwedRebuildsDropOnProcessorOrWorldIdentity() {
        // A server-initiated reconfiguration skips the disconnect event (ClientNetGlue's
        // documented residual); a ResourceKey alone is identity-stable across servers.
        this.bridge.updateIdlePumps = 1;
        offer(64, 64);
        this.bridge.pump();
        this.processor.currentWorldId = "another-server";
        pumpIdleWindow();
        assertEquals(1, this.bridge.counterForTest("dropped_updates"));
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
        this.processor.currentWorldId = "stub-world";
        offer(68, 64);
        this.bridge.pump(); // the world-id change itself drops this queued tile (reviewer 1 #8)...
        assertEquals(0, this.bridge.counterForTest("written") - 1, "premise: dropped as stale, not written");
        offer(68, 64);
        this.bridge.pump(); // ...and the next offer commits
        assertEquals(2, this.bridge.counterForTest("written"));
        var fresh = new MapProcessor();
        fresh.world = this.worldToken;
        fresh.mainWorld = this.worldToken;
        fresh.mapWorld.currentDimensionId = OVERWORLD;
        WorldMapSession.current.processor = fresh;
        pumpIdleWindow();
        assertEquals(2, this.bridge.counterForTest("dropped_updates"),
                "a new MapProcessor = a new Xaero session: the old objects are never touched");
        assertFalse(XaeroStubEvents.snapshot().stream().anyMatch(e -> e.startsWith("tileChunk.updateBuffers")));
    }

    @Test
    void aReplacedTileChunkGetsAFreshOwedEntry() {
        this.bridge.updateIdlePumps = 2;
        offer(64, 64);
        this.bridge.pump();
        var region = theRegion();
        MapTileChunk old = region.getChunk(0, 0);
        var replacement = new MapTileChunk(region, 16, 16); // Xaero reloaded the region
        replacement.loadState = 2;
        region.setChunk(0, 0, replacement);
        offer(65, 64);
        this.bridge.pump();
        pumpIdleWindow();
        assertEquals(1, replacement.bufferUpdates, "the replacement is rebuilt");
        assertEquals(0, old.bufferUpdates);
        assertEquals(0, this.bridge.counterForTest("dropped_updates"));
        assertEquals(1, this.bridge.counterForTest("dropped_unloaded"),
                "the old tile chunk's owed rebuild is counted where it goes: a reload rebuilds its own");
    }

    @Test
    void aContinuousTrickleStillRebuildsAtTheAgeCeiling() {
        this.bridge.updateIdlePumps = 3;
        this.bridge.updateMaxDeferPumps = 6;
        for (int i = 0; i < 8; i++) {
            offer(64 + (i & 3), 64 + (i >> 2)); // a new tile of tile chunk (16,16) every pump
            this.bridge.pump();
            if (i < 5) {
                assertEquals(0, this.bridge.counterForTest("buffer_updates"),
                        "re-touched inside the idle window: coalescing holds (pump " + (i + 1) + ")");
            }
        }
        assertTrue(this.bridge.counterForTest("buffer_updates") >= 1,
                "the age ceiling forces the rebuild — a written tile chunk must never stay blank");
    }

    @Test
    void theDeathLatchReleasesOwedRebuilds() {
        this.bridge.updateIdlePumps = 1000;
        offer(64, 64);
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("pending_updates"));
        latchTheBridgeDead();
        assertTrue(this.bridge.deadForTest(), "premise");
        this.bridge.pump();
        assertEquals(0, this.bridge.counterForTest("pending_updates"),
                "a dead bridge must not pin Xaero's regions/tile chunks (direct texture buffers)"
                        + " for the rest of the session");
    }

    @Test
    void rebuildFailuresCountTowardTheDeathLatch() {
        this.bridge.updateIdlePumps = 1;
        for (int i = 0; i < XaeroMapCompat.THROW_LATCH; i++) {
            offer(64 + 4 * i, 64); // five tile chunks of region (2,2)
        }
        this.bridge.pump();
        assertEquals(XaeroMapCompat.THROW_LATCH, this.bridge.counterForTest("written"));
        var region = theRegion();
        for (int i = 0; i < XaeroMapCompat.THROW_LATCH; i++) {
            region.getChunk(i, 0).updateBuffersThrows = true;
        }
        this.bridge.pump();
        this.bridge.pump();
        assertTrue(this.bridge.deadForTest(), "a throwing rebuild is a commit-side failure"
                + " (commit_failures=" + this.bridge.counterForTest("commit_failures")
                + " dropped_updates=" + this.bridge.counterForTest("dropped_updates")
                + " dropped_unloaded=" + this.bridge.counterForTest("dropped_unloaded")
                + " pending=" + this.bridge.counterForTest("pending_updates")
                + " buffer_updates=" + this.bridge.counterForTest("buffer_updates") + ")");
        assertTrue(this.bridge.counterForTest("commit_failures") >= XaeroMapCompat.THROW_LATCH);
        assertEquals(XaeroMapCompat.THROW_LATCH, this.bridge.counterForTest("dropped_updates"),
                "owed and never rebuilt: buffer_updates + dropped_updates accounts for every entry");
        this.bridge.pump();
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
    }

    @Test
    void committedPixelsCarryTheTileInputs() {
        var prepared = tile(4, 4);
        prepared.floorState()[0] = Blocks.GLOWSTONE.defaultBlockState();
        prepared.floorY()[0] = 63;
        prepared.topY()[0] = 66;
        prepared.light()[0] = 7;
        prepared.glowing()[0] = true;
        prepared.biome()[0] = Biomes.DESERT;
        prepared.overlays()[0] = new XaeroTileExtractor.OverlayRun[]{
                new XaeroTileExtractor.OverlayRun(
                        Blocks.WATER.defaultBlockState(), (byte) 3, false, 3)};
        this.bridge.offerPrepared(OVERWORLD, prepared);
        this.bridge.pump();
        var region = this.processor.regions.values().iterator().next();
        MapTileChunk tileChunk = region.getChunk(1, 1); // tileChunk (1,1) for chunk (4,4)
        assertNotNull(tileChunk);
        var mapTile = tileChunk.getTile(0, 0);
        assertNotNull(mapTile);
        var block = mapTile.blocks[0][0];
        assertEquals(Blocks.GLOWSTONE.defaultBlockState(), block.state);
        assertEquals(63, block.height);
        assertEquals(66, block.topHeight);
        assertEquals(7, block.light);
        assertTrue(block.glowing, "glowing must pass through to MapBlock.write");
        assertEquals(Biomes.DESERT, block.biome, "biome must pass through to MapBlock.write");
        assertEquals(-64, block.preparedBottomY, "prepareForWriting must run (and first)");
        assertFalse(block.cave, "surface layer writes cave=false");
        // The faithful stub CLEARS overlays in prepareForWriting, so a surviving
        // overlay is a REAL prepare→addOverlay→write order pin (review MAJOR: the
        // old no-op stub made this vacuous).
        assertEquals(1, block.overlays.size(),
                "the overlay must survive — prepareForWriting after addOverlay would wipe it");
        assertEquals(3, block.overlays.get(0).opacity);
        assertTrue(this.processor.overlayManager.internCalls >= 1,
                "overlays are interned through OverlayManager.getOriginal");
        assertEquals(Integer.MAX_VALUE, mapTile.writtenCaveStart, "surface cave sentinel");
        // Pixel (0,1) had no data in the helper tile: write(null biome/state) keeps
        // the prepared reset values (a REAL extractor tile never ships null states —
        // voidColumnIsTheEraseShape pins the actual erase shape).
        var voidBlock = mapTile.blocks[0][1];
        org.junit.jupiter.api.Assertions.assertNull(voidBlock.state);
    }

    @Test
    void anExistingTileChunkSkipsTheCreatedBlock() {
        // The setHasHadTerrain/highlights work belongs to the native createdTileChunk
        // branch ONLY — running it on every commit would churn Xaero's highlight
        // preparer and re-mark terrain per tile.
        offer(4, 4);
        var region = new MapRegion();
        var tileChunk = new MapTileChunk(region, 1, 1);
        tileChunk.loadState = 2;
        region.setChunk(1, 1, tileChunk);
        this.processor.regions.put(0L, region);
        XaeroStubEvents.clear();
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
        var events = XaeroStubEvents.snapshot();
        assertFalse(events.contains("tileChunk.setHasHadTerrain"),
                "existing tile chunk: no created-block work — " + events);
        assertFalse(events.contains("highlights.prepare"),
                "existing tile chunk: no highlight prepare — " + events);
        assertFalse(tileChunk.hasHadTerrain);
    }

    @Test
    void budgetStopsAfterMaxCommitsPerPump() {
        for (int i = 0; i < XaeroMapCompat.MAX_COMMITS_PER_PUMP + 2; i++) {
            offer(i * 4, 0); // distinct tile chunks
        }
        this.bridge.pump();
        assertEquals(XaeroMapCompat.MAX_COMMITS_PER_PUMP, this.bridge.counterForTest("written"));
        assertEquals(2, this.bridge.queuedForTest(), "over-budget entries wait for the next pump");
        this.bridge.pump();
        assertEquals(0, this.bridge.queuedForTest());
    }

    @Test
    void aZeroBudgetPumpStillMakesProgressEveryPump() {
        // The live-lock MAJOR (3-Opus fold): the old pre-walk ran OUTSIDE the nanos
        // budget, so a broke pump could do literally nothing while retaining the
        // whole queue, forever. The budget check is now skipped until one unit of
        // progress (a drop or a commit attempt) — a zero budget commits exactly
        // ONE entry per pump, still grants probed waiting regions, and the queue
        // always drains.
        offer(0, 0);   // committable bucket (auto-created loaded region)
        offer(4, 0);   // same bucket
        offer(64, 64); // waiting bucket — region (2,2)
        this.processor.regions.put((2L << 32) | 2L, unloadedRegion());
        this.bridge.pumpNanosBudget = 0;
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"),
                "a zero budget must still commit exactly one entry (progress guarantee)");
        for (int i = 0; i < 8 && this.bridge.queuedForTest() > 1; i++) {
            this.bridge.pump();
        }
        assertEquals(2, this.bridge.counterForTest("written"),
                "over pumps the zero-budget drain must empty the committable bucket");
        assertEquals(1, this.bridge.queuedForTest(), "only the awaiting entry remains");
        assertEquals(1, this.processor.saveLoad.loadRequests.size(),
                "the waiting region must be granted its load despite the broke budget");
    }

    @Test
    void aBusyTileChunkDefersOnlyItsOwnEntriesAndSiblingsCommit() {
        // Three entries in ONE region, distinct tile chunks; the first entry's tile
        // chunk is PBO-downloading. The old region-wide deferral starved (and after
        // DEFER_CAP pumps EXPIRED) whole buckets over one busy tile chunk (3-Opus
        // fold MAJOR); tile-chunk-scoped deferral lets the siblings commit now,
        // and the busy entry expires ALONE at its own cap.
        offer(0, 0);  // tileChunk (0,0) — armed busy
        offer(4, 0);  // tileChunk (1,0)
        offer(8, 0);  // tileChunk (2,0)
        var region = new MapRegion();
        var busy = new MapTileChunk(region, 0, 0);
        busy.loadState = 2;
        busy.leafTexture.downloadFromPBO = true;
        region.setChunk(0, 0, busy);
        this.processor.regions.put(0L, region);
        this.bridge.pump();
        assertEquals(2, this.bridge.counterForTest("written"),
                "siblings in other tile chunks must commit past the busy one");
        assertEquals(1, this.bridge.queuedForTest());
        for (int i = 0; i < XaeroMapCompat.DEFER_CAP + 2; i++) {
            this.bridge.pump();
        }
        assertEquals(0, this.bridge.queuedForTest(),
                "the busy entry expires at its own deferral cap");
        assertEquals(1, this.bridge.counterForTest("dropped_expired"),
                "…counted exactly once (removal-guarded counting)");
    }

    // ---- failure containment ----

    /** Queue THROW_LATCH+3 entries whose commits all throw, and pump until dead. */
    private void latchTheBridgeDead() {
        var region = new MapRegion();
        for (int i = 0; i < XaeroMapCompat.THROW_LATCH + 3; i++) {
            offer(i * 4, 64);
            int tcX = (i * 4) >> 2;
            var tileChunk = new MapTileChunk(region, tcX, 16);
            tileChunk.loadState = 2;
            tileChunk.setTileThrows = true;
            region.setChunk(tcX & 7, 16 & 7, tileChunk);
        }
        this.processor.regions.put(2L, region);
        for (int i = 0; i < 3 && !this.bridge.deadForTest(); i++) {
            this.bridge.pump();
        }
    }

    @Test
    void fiveConsecutiveCommitFailuresLatchTheBridgeDead() {
        // All eight entries land in region (0,2): pre-create it with an ARMED (throwing)
        // tile chunk at every entry's local slot, so each commit attempt fails.
        for (int i = 0; i < XaeroMapCompat.THROW_LATCH + 3; i++) {
            offer(i * 4, 64);
        }
        var region = new MapRegion();
        for (int i = 0; i < XaeroMapCompat.THROW_LATCH + 3; i++) {
            int tcX = (i * 4) >> 2;
            var tileChunk = new MapTileChunk(region, tcX, 16);
            tileChunk.loadState = 2;
            tileChunk.setTileThrows = true;
            region.setChunk(tcX & 7, 16 & 7, tileChunk);
        }
        this.processor.regions.put(2L, region); // key (regionX=0)<<32 | regionZ=2
        for (int i = 0; i < 3 && !this.bridge.deadForTest(); i++) {
            this.bridge.pump();
        }
        assertTrue(this.bridge.deadForTest(),
                "consecutive commit failures must latch the bridge dead");
        assertEquals(0, this.bridge.queuedForTest(), "death clears the queue");
        assertTrue(this.bridge.counterForTest("commit_failures") >= XaeroMapCompat.THROW_LATCH);
    }

    @Test
    void failuresSpreadAcrossPumpsStillLatch() {
        // One armed entry per pump, five pumps: a regression that resets the count on
        // a clean ladder pass (or at pump start) would never latch (review MAJOR —
        // the original latch test armed everything in one pump and could not tell).
        for (int i = 0; i < XaeroMapCompat.THROW_LATCH; i++) {
            offer(i * 4, 64);
            var region = this.processor.regions.computeIfAbsent(2L, k -> new MapRegion());
            int tcX = (i * 4) >> 2;
            var tileChunk = new MapTileChunk(region, tcX, 16);
            tileChunk.loadState = 2;
            tileChunk.setTileThrows = true;
            region.setChunk(tcX & 7, 0, tileChunk);
            this.bridge.pump();
        }
        assertTrue(this.bridge.deadForTest(),
                "5 consecutive failures across 5 pumps must latch (no per-pump reset)");
    }

    @Test
    void aSuccessBetweenFailuresResetsTheLatchCount() {
        // Alternate failing and healthy entries: the latch must never fire because
        // every successful commit resets the consecutive count.
        var region = this.processor.regions.computeIfAbsent(2L, k -> new MapRegion());
        for (int round = 0; round < XaeroMapCompat.THROW_LATCH + 2; round++) {
            int failX = round * 8;       // tileChunk (2i, 16) armed
            int okX = round * 8 + 4;     // tileChunk (2i+1, 16) healthy
            offer(failX, 64);
            int tcX = failX >> 2;
            var armed = new MapTileChunk(region, tcX, 16);
            armed.loadState = 2;
            armed.setTileThrows = true;
            region.setChunk(tcX & 7, 0, armed);
            this.bridge.pump();
            offer(okX, 64);
            this.bridge.pump();
            assertFalse(this.bridge.deadForTest(),
                    "round " + round + ": a success between failures must reset the latch");
        }
    }

    @Test
    void repeatedExtractionFailuresLatchTheBridge() {
        var consumer = this.registered.get(0);
        for (int i = 0; i < XaeroMapCompat.THROW_LATCH; i++) {
            // Null column data NPEs inside extraction — swallowed, counted.
            assertDoesNotThrow(() -> consumer.onVoxelColumnReceived(null, OVERWORLD, 0, 0, null));
        }
        assertTrue(this.bridge.deadForTest(),
                "a permanently-throwing extractor must not burn the decode thread forever");
    }

    // ---- diag ----

    // ---- the compatibility sweep (plan §16): the native ladder's other gates ----

    @Test
    void anOffThreadSessionEndOnlyTouchesThreadSafeStateAndSettlesOnTheNextPump() throws Exception {
        // Fabric fires DISCONNECT from netty's channelInactive on an abrupt close while the
        // main thread may be inside pump() (sweep C MAJOR): the owed-rebuild map and the
        // registration flag are main-thread-only, so they settle at the next pump.
        this.bridge.updateIdlePumps = 1000;
        offer(64, 64);
        offer(65, 64); // same tile chunk
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("pending_updates"));
        this.enabled = false;
        var t = new Thread(this.bridge::onSessionEnd, "netty-ish");
        t.start();
        t.join();
        assertEquals(0, this.bridge.queuedForTest(), "the queue clear is lock-protected: immediate");
        assertEquals(1, this.bridge.counterForTest("pending_updates"), "the owed set waits for the main thread");
        assertEquals(1, this.registered.size(), "so does the registration settle");
        this.bridge.pump();
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
        assertEquals(1, this.bridge.counterForTest("dropped_updates"));
        assertTrue(this.registered.isEmpty(), "settled on the main thread");
    }

    @Test
    void aTileExtractedAcrossTheSessionEndCannotEnterTheQueue() {
        // Decode thread passed offerColumn's gate, the session ended (gate off + queue
        // cleared), the extraction finishes: the enqueue re-checks under the lock.
        this.sessionActive = false;
        this.bridge.offerPrepared(OVERWORLD, tile(64, 64));
        assertEquals(0, this.bridge.queuedForTest(),
                "one tile of the previous server must never reach the next server's map");
        this.sessionActive = true;
        this.enabled = false;
        this.bridge.offerPrepared(OVERWORLD, tile(64, 64));
        assertEquals(0, this.bridge.queuedForTest());
    }

    @Test
    void negativeCoordinatesMapToTheRightTileChunkRegionAndInsidePosition() {
        // Xaero's own convention is arithmetic shift (writeChunk >>3, writeMap &7); a
        // "cleanup" to /4 or floorMod would pass every positive-quadrant test.
        offer(-61, -3); // chunk (-61,-3): tile chunk (-16,-1), inside (3,1), region (-2,-1), local (0,7)
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
        var events = XaeroStubEvents.snapshot();
        assertTrue(events.contains("processor.getLeafMapRegion layer=" + Integer.MAX_VALUE + " -2,-1"), events.toString());
        assertTrue(events.contains("tileChunk.new -16,-1"), events.toString());
        assertTrue(events.contains("tileChunk.setTile 3,1"), events.toString());
        var region = this.processor.regions.get((-2L << 32) | (-1L & 0xFFFFFFFFL));
        assertNotNull(region, "region key packs the negative z like PositionUtil");
        assertNotNull(region.getChunk(0, 7));
    }

    @Test
    void aCaveLayerViewMakesTheBridgeWaitButOwedRebuildsStillRun() {
        this.bridge.updateIdlePumps = 1;
        offer(64, 64);
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("pending_updates"));
        this.processor.currentCaveLayer = 3; // auto cave mode / the Nether by default
        offer(68, 64);
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"), "surface writes wait while a cave layer renders");
        assertEquals(1, this.bridge.queuedForTest(), "retained, not dropped");
        assertEquals(1, this.bridge.counterForTest("cave_layer_waits"));
        assertEquals(1, this.bridge.counterForTest("buffer_updates"), "the owed rebuild still ran");
        this.processor.currentCaveLayer = Integer.MAX_VALUE;
        this.bridge.pump();
        assertEquals(2, this.bridge.counterForTest("written"));
    }

    @Test
    void aRefusedNewTileRollsItsCreatedTileChunkBack() {
        xaero.map.common.config.option.WorldMapProfiledConfigOptions.LOAD_NEW_CHUNKS.value = false;
        offer(64, 64);
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("skipped_settings"));
        assertNull(theRegion().getChunk(0, 0),
                "the native rollback (writeChunk pc 1526-1537): no empty tile chunk left installed");
        assertTrue(XaeroStubEvents.snapshot().contains("region.setChunk 0,0 null locked"),
                "the rollback runs under the region monitor (the bridge's tightening): " + XaeroStubEvents.snapshot());
        xaero.map.common.config.option.WorldMapProfiledConfigOptions.LOAD_NEW_CHUNKS.value = true;
        offer(64, 64);
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
        assertNotNull(theRegion().getChunk(0, 0).getTile(0, 0));
    }

    @Test
    void owedRebuildsAreKeyedByDimensionToo() {
        this.bridge.updateIdlePumps = 1000;
        offer(64, 64);
        this.bridge.pump();
        this.processor.mapWorld.currentDimensionId = NETHER;
        this.clientDimension = NETHER;
        this.bridge.offerPrepared(NETHER, tile(64, 64)); // same tile-chunk coords, other dimension
        this.bridge.pump();
        assertEquals(2, this.bridge.counterForTest("written"));
        assertEquals(2, this.bridge.counterForTest("pending_updates"),
                "the Overworld entry must survive the Nether's same-coords commit");
        assertEquals(0, this.bridge.counterForTest("dropped_unloaded"));
    }

    @Test
    void aCrashedXaeroIsNeverTouched() {
        offer(64, 64);
        xaero.map.WorldMap.crashHandler.crashedBy = new RuntimeException("Xaero latched");
        this.bridge.pump();
        assertEquals(0, this.bridge.counterForTest("written"));
        assertEquals(1, this.bridge.queuedForTest(), "retained, not dropped");
        assertTrue(this.processor.regions.isEmpty(), "no region lookup, no monitors: " + XaeroStubEvents.snapshot());
        assertEquals(1, this.bridge.counterForTest("xaero_crashed"));
        assertTrue(this.bridge.describe().contains("xaero_crashed=true"));
        xaero.map.WorldMap.crashHandler.crashedBy = null;
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
        assertEquals(0, this.bridge.counterForTest("xaero_crashed"));
    }

    @Test
    void loadNewChunksOffRefusesNewTilesAndUpdateChunksOffRefusesRewrites() {
        // A new tile needs "Load New Chunks".
        xaero.map.common.config.option.WorldMapProfiledConfigOptions.LOAD_NEW_CHUNKS.value = false;
        offer(64, 64);
        this.bridge.pump();
        assertEquals(0, this.bridge.counterForTest("written"));
        assertEquals(1, this.bridge.counterForTest("skipped_settings"));
        assertEquals(0, this.bridge.queuedForTest(), "refused entries drop (a re-serve refills them)");
        var region = theRegion();
        assertNull(region.getChunk(0, 0), "the created tile chunk is rolled back (sweep B m3)");
        assertEquals(0, this.bridge.counterForTest("pending_updates"), "nothing owed for a refused tile");
        // Switched back on: the tile writes.
        xaero.map.common.config.option.WorldMapProfiledConfigOptions.LOAD_NEW_CHUNKS.value = true;
        offer(64, 64);
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
        // An EXISTING tile needs "Update Chunks" — Load New Chunks alone does not allow a rewrite.
        xaero.map.common.config.option.WorldMapProfiledConfigOptions.UPDATE_CHUNKS.value = false;
        offer(64, 64);
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
        assertEquals(2, this.bridge.counterForTest("skipped_settings"));
        // ...while a new tile still writes under Load New Chunks.
        offer(65, 64);
        this.bridge.pump();
        assertEquals(2, this.bridge.counterForTest("written"));
    }

    @Test
    void aForeignSwitchValueOrAThrowingReadLeavesBothSwitchesOpen() {
        var manager = xaero.map.WorldMap.INSTANCE.configs.manager;
        manager.override = "not a boolean";
        offer(64, 64);
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"), "an unknown value shape reads as ON (never fail closed)");
        manager.override = null;
        manager.throwing = true;
        offer(68, 64);
        this.bridge.pump();
        assertEquals(2, this.bridge.counterForTest("written"), "a throwing read is contained: both ON");
        assertEquals(0, this.bridge.counterForTest("commit_failures"), "and it is NOT a commit failure — it must never latch the bridge dead");
        assertTrue(this.bridge.describe().contains("settings_gate=broken"));
        manager.throwing = false;
        xaero.map.common.config.option.WorldMapProfiledConfigOptions.LOAD_NEW_CHUNKS.value = false;
        offer(72, 64);
        this.bridge.pump();
        assertEquals(3, this.bridge.counterForTest("written"), "broken stays broken for the session: the switch is not re-read");
        this.bridge.onSessionEnd();
        this.bridge.pump();
        assertFalse(this.bridge.describe().contains("settings_gate=broken"), "session-scoped");
    }

    @Test
    void worldSaveModeOpensBothSwitchesLikeNative() {
        // onRender pc 679-733: loadNew |= isUsingWorldSave(), update |= isUsingWorldSave()
        // (singleplayer — reached through the LAN hook); the both-off return excludes it.
        xaero.map.common.config.option.WorldMapProfiledConfigOptions.LOAD_NEW_CHUNKS.value = false;
        xaero.map.common.config.option.WorldMapProfiledConfigOptions.UPDATE_CHUNKS.value = false;
        this.processor.mapWorld.currentDimension.usingWorldSave = true;
        offer(64, 64);
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
        assertEquals(0, this.bridge.counterForTest("skipped_settings"));
    }

    @Test
    void aLatchedCrashSkipsTheOwedRebuildFlushToo() {
        this.bridge.updateIdlePumps = 1;
        offer(64, 64);
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("pending_updates"));
        xaero.map.WorldMap.crashHandler.crashedBy = new RuntimeException("latched");
        this.bridge.pump();
        this.bridge.pump();
        assertEquals(0, this.bridge.counterForTest("buffer_updates"), "touch NOTHING — the flush is behind the gate too");
        assertEquals(1, this.bridge.counterForTest("pending_updates"));
        xaero.map.WorldMap.crashHandler.crashedBy = null;
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("buffer_updates"));
    }

    @Test
    void aWorldIdChangeUnderALiveSessionDropsTheQueuedTiles() {
        // The reconfiguration residual: neither loader fires its disconnect event, the
        // LSS session stays live, Xaero moves to another world.
        offer(64, 64);
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"));
        this.processor.leafMapRegionReturnsNull = true; // keep the next entries queued
        offer(68, 64);
        offer(72, 64);
        this.bridge.pump();
        assertEquals(2, this.bridge.queuedForTest());
        long staleBefore = this.bridge.counterForTest("dropped_stale");
        this.processor.currentWorldId = "another-world";
        this.bridge.pump();
        assertEquals(0, this.bridge.queuedForTest(), "the queued tiles were the OLD world's");
        assertEquals(staleBefore + 2, this.bridge.counterForTest("dropped_stale"));
    }

    @Test
    void bothSwitchesOffDropTheBacklogButStillFlushOwedRebuilds() {
        this.bridge.updateIdlePumps = 1;
        offer(64, 64);
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("pending_updates"));
        xaero.map.common.config.option.WorldMapProfiledConfigOptions.LOAD_NEW_CHUNKS.value = false;
        xaero.map.common.config.option.WorldMapProfiledConfigOptions.UPDATE_CHUNKS.value = false;
        offer(68, 64);
        offer(72, 64);
        this.bridge.pump();
        assertEquals(0, this.bridge.queuedForTest(), "the native ladder returns: our backlog drops");
        assertEquals(2, this.bridge.counterForTest("skipped_settings"));
        assertEquals(1, this.bridge.counterForTest("written"));
        assertEquals(1, this.bridge.counterForTest("buffer_updates"),
                "a rebuild already owed to a written tile chunk still runs — never a blank tile");
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
    }

    @Test
    void theOptionalSurfaceIsOptional() throws Exception {
        XaeroMapCompat.ClassResolver withoutOptional = name -> {
            if (name.startsWith("xaero.lib.") || name.equals("xaero.map.WorldMap")
                    || name.equals("xaero.map.CrashHandler")) {
                throw new ClassNotFoundException(name);
            }
            return Class.forName(name);
        };
        var handles = XaeroMapCompat.Handles.resolve(withoutOptional);
        assertNull(handles.crashGate);
        assertNull(handles.settingsGate);
        // (interpretation-version and cave-layer resolve off classes the mandatory surface
        // needs, so their unbound paths are Tier-1-unreachable through a ClassResolver —
        // covered only by the owed live `optional_unbound` diag check.)
        assertEquals("crash-gate settings-gate", handles.optionalMissing);
        var reduced = new XaeroMapCompat(handles, this.fakeLevelOps, () -> this.enabled,
                () -> this.sessionActive, this.registered::add, this.registered::remove,
                () -> this.backpressureEnabled, (d, x, z) -> this.reports.add(new Object[]{d, x, z}));
        reduced.pumpNanosBudget = Long.MAX_VALUE;
        reduced.updateNanosBudget = Long.MAX_VALUE;
        reduced.maybeRegister();
        // Both switches off AND a latched crash: the reduced bridge cannot see either and
        // keeps the pre-§16 behavior — it writes (the floor is unchanged).
        xaero.map.common.config.option.WorldMapProfiledConfigOptions.LOAD_NEW_CHUNKS.value = false;
        xaero.map.common.config.option.WorldMapProfiledConfigOptions.UPDATE_CHUNKS.value = false;
        xaero.map.WorldMap.crashHandler.crashedBy = new RuntimeException("latched");
        reduced.offerPrepared(OVERWORLD, tile(64, 64));
        reduced.pump();
        assertEquals(1, reduced.counterForTest("written"));
        assertTrue(reduced.describe().contains("optional_unbound=crash-gate settings-gate"), reduced.describe());
    }

    @Test
    void theInterpretationVersionIsReadFromXaeroNotALiteral() throws Exception {
        xaero.map.region.MapTile.CURRENT_WORLD_INTERPRETATION_VERSION = 7;
        var handles = XaeroMapCompat.Handles.resolve(Class::forName);
        assertEquals(7, handles.interpretationVersion);
        var bridge7 = new XaeroMapCompat(handles, this.fakeLevelOps, () -> this.enabled,
                () -> this.sessionActive, this.registered::add, this.registered::remove,
                () -> this.backpressureEnabled, (d, x, z) -> this.reports.add(new Object[]{d, x, z}));
        bridge7.pumpNanosBudget = Long.MAX_VALUE;
        bridge7.updateNanosBudget = Long.MAX_VALUE;
        bridge7.maybeRegister();
        bridge7.offerPrepared(OVERWORLD, tile(64, 64));
        bridge7.pump();
        assertTrue(XaeroStubEvents.snapshot().contains("tile.setWorldInterpretationVersion 7"),
                "a Xaero bump must reach the tiles: " + XaeroStubEvents.snapshot());
    }

    @Test
    void describeRendersTheHouseStyle() {
        var line = this.bridge.describe();
        assertTrue(line.startsWith("XaeroMap: state=active, queued="), line);
        assertTrue(line.contains(", written=") && line.contains(", defer_events=")
                && line.contains(", dropped=") && line.contains(", commit_failures=")
                && line.contains(", regions_waiting=") && line.contains(", buffer_updates=")
                && line.contains(", pending_updates=") && line.contains(", dropped_updates=")
                && line.contains(", dropped_unloaded=") && line.contains(", skipped_settings=")
                && line.contains(", cave_layer_waits=") && line.contains(", frame_flushes=")
                && line.contains(", rebuild_ms=") && line.contains(", rebuild_max_us=")
                && line.contains(", dropped_overflow=") && line.contains(", dropped_expired=")
                && !line.contains(", refused_paused=") && line.contains(", drops_reported=")
                && line.contains(", bp="), line);
        assertFalse(line.contains("heal_"), "the §18 heal tokens die with the ledger (§12.1)");
        assertFalse(line.contains("xaero_crashed"), "the crash token appears only while latched");
        assertFalse(line.contains("optional_unbound"), "every optional group binds against the stubs");
        this.enabled = false;
        assertTrue(this.bridge.describe().contains("state=disabled"));
    }
}
