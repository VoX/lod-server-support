package dev.vox.lss.compat;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTileChunk;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic lifecycle interleavings through the real bridge and monitor-enforcing Xaero stubs. */
class XaeroAcquisitionLifecycleTest {
    private static final ResourceKey<Level> DIM = ResourceKey.create(Registries.DIMENSION,
            Identifier.parse("minecraft:overworld"));

    public static class DisconnectingTileChunk extends MapTileChunk {
        Runnable duringProbe;
        DisconnectingTileChunk(MapRegion region) { super(region, 0, 0); loadState = 2; }
        @Override public int getLoadState() {
            Runnable hook = duringProbe;
            duringProbe = null;
            if (hook != null) hook.run();
            return super.getLoadState();
        }
    }

    @Test void shedAfterDisconnectMustNotCarryOldDebtIntoTheNextServer() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        XaeroStubEvents.clear();
        var active = new AtomicBoolean(true);
        var classifierHook = new AtomicReference<Runnable>();
        var reports = new ArrayList<Long>();
        var processor = new MapProcessor();
        Object world = new Object();
        processor.world = world;
        processor.mainWorld = world;
        processor.mapWorld.currentDimensionId = DIM;
        processor.createdRegionLoadState = 0;
        var session = new WorldMapSession();
        session.processor = processor;
        WorldMapSession.current = session;
        try {
            var bridge = new XaeroMapCompat(XaeroBindings.resolve(Class::forName),
                    new XaeroMapCompat.LevelOps() {
                        public Object dimension(Object w) { return DIM; }
                        public boolean isChunkLoaded(Object w, int x, int z) { return false; }
                    }, () -> true, active::get, c -> {}, c -> {}, () -> {
                        Runnable hook = classifierHook.getAndSet(null);
                        if (hook != null) hook.run();
                        return true;
                    }, (d, x, z) -> reports.add(((long) x << 32) | (z & 0xffffffffL)));
            bridge.pumpNanosBudget = Long.MAX_VALUE;
            bridge.updateNanosBudget = Long.MAX_VALUE;
            bridge.bpClock = () -> 1_000_000L;
            bridge.maxQueue = 1;
            @SuppressWarnings("unchecked")
            var biomes = (ResourceKey<net.minecraft.world.level.biome.Biome>[]) new ResourceKey[256];
            bridge.offerPrepared(DIM, new XaeroTileExtractor.PreparedTile(128, 64, -64,
                    new net.minecraft.world.level.block.state.BlockState[256], new short[256],
                    new short[256], biomes, new byte[256], new boolean[256],
                    new XaeroTileExtractor.OverlayRun[256][]));
            bridge.pump(); // old-server region (4,2) now known awaiting
            assertTrue(bridge.awaitingRegionsForTest().contains(XaeroMapCompat.regionKeyOf(128,64)));
            classifierHook.set(() -> {
                var errors = new AtomicReference<Throwable>();
                var netty = new Thread(() -> {
                    try { active.set(false); bridge.onSessionEnd(); }
                    catch (Throwable t) { errors.set(t); }
                }, "review-netty-eviction-end");
                netty.start();
                try { netty.join(30_000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                assertFalse(netty.isAlive());
                assertNull(errors.get());
                assertEquals(0, bridge.counterForTest("owed"), "teardown itself cleared debt");
            });
            // Full count gate refuses BEFORE extraction. Classifier read is the first
            // backpressure read; disconnect overtakes the unlocked shed/report path.
            bridge.offerColumn(DIM,129,64,-64,320,null);
            assertNull(classifierHook.get(), "the normal overflow classifier consumed the hook");
            assertTrue(reports.isEmpty(), "debt is held, not yet reported");
            WorldMapSession.current = null;
            bridge.pump(); // title-screen settlement
            long survivingDebt = bridge.counterForTest("owed");
            // A new remote server uses the ordinary same dimension key and has loaded maps.
            var newProcessor = new MapProcessor();
            newProcessor.createdRegionLoadState = 2;
            Object newWorld = new Object();
            newProcessor.world = newWorld;
            newProcessor.mainWorld = newWorld;
            newProcessor.mapWorld.currentDimensionId = DIM;
            newProcessor.currentWorldId = "new-server";
            var newSession = new WorldMapSession();
            newSession.processor = newProcessor;
            WorldMapSession.current = newSession;
            active.set(true);
            for (int i = 0; i < 4; i++) bridge.pump();
            assertAll(
                    () -> assertEquals(0, survivingDebt, "old debt recreated after teardown survived title screen"),
                    () -> assertTrue(reports.isEmpty(), "old-server position was reported in the new active session: " + reports));
        } finally {
            WorldMapSession.current = null;
            XaeroStubEvents.clear();
        }
    }

    @Test void abruptDisconnectDuringOwedReleaseCannotRepublishDebtOrNegativeGauge() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        XaeroStubEvents.clear();
        var active = new AtomicBoolean(true);
        var errors = new AtomicReference<Throwable>();
        var reports = new ArrayList<Long>();
        var processor = new MapProcessor();
        Object world = new Object();
        processor.world = world;
        processor.mainWorld = world;
        processor.mapWorld.currentDimensionId = DIM;
        var session = new WorldMapSession();
        session.processor = processor;
        WorldMapSession.current = session;
        try {
            var bridge = new XaeroMapCompat(XaeroBindings.resolve(Class::forName),
                    new XaeroMapCompat.LevelOps() {
                        public Object dimension(Object w) { return DIM; }
                        public boolean isChunkLoaded(Object w, int x, int z) { return false; }
                    }, () -> true, active::get, c -> {}, c -> {}, () -> true,
                    (d, x, z) -> reports.add(((long) x << 32) | (z & 0xffffffffL)));
            bridge.pumpNanosBudget = Long.MAX_VALUE;
            bridge.updateNanosBudget = Long.MAX_VALUE;
            bridge.bpClock = () -> 1_000_000L;
            var region = new MapRegion();
            var tileChunk = new DisconnectingTileChunk(region);
            region.setChunk(0, 0, tileChunk);
            processor.regions.put(0L, region);
            // Same seam used by duplicateBusyDebtAndDualCategoryPayoffMatchSetMembership.
            var owe = XaeroMapCompat.class.getDeclaredMethod("oweExpired", Object.class,
                    long.class, long.class);
            owe.setAccessible(true);
            owe.invoke(bridge, DIM, 0L, 0L);
            assertEquals(1, bridge.counterForTest("owed"));
            tileChunk.duringProbe = () -> {
                var netty = new Thread(() -> {
                    try {
                        active.set(false); // session gate turns OFF before ModCompat teardown
                        bridge.onSessionEnd();
                    } catch (Throwable t) { errors.set(t); }
                }, "review-netty-session-end");
                netty.start();
                try { netty.join(30_000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                assertFalse(netty.isAlive(), "teardown must not need the held Xaero region monitor");
                assertNull(errors.get());
                assertEquals(0, bridge.counterForTest("owed"), "teardown cleared gauges");
            };
            bridge.pump(); // debt release resumes AFTER the real off-thread teardown
            bridge.pump(); // settleSessionEnd must preserve the zero-everything state too
            assertAll(
                    () -> assertEquals(0, bridge.counterForTest("owed"),
                            "release resumed against detached old record and decremented cleared gauge"),
                    () -> assertEquals(0, bridge.counterForTest("owed_regions")),
                    () -> assertTrue(reports.isEmpty(),
                            "an old-session debt was republished after onSessionEnd cleared it"));
        } finally {
            WorldMapSession.current = null;
            XaeroStubEvents.clear();
        }
    }
}
