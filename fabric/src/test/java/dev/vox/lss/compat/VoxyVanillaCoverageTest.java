package dev.vox.lss.compat;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Loaded-border recovery without server approximation, generation, repeated writes or stale worlds. */
public class VoxyVanillaCoverageTest {
    /** X-6: plain Voxy users (no native or negotiated DH terrain session) never get the border pump. */
    @Test void theBorderPumpIsIdleWithoutATerrainSession() {
        assertNull(dev.vox.lss.networking.client.ClientNetGlue.getRequestManager());
        assertFalse(dev.vox.lss.networking.client.DhClientSession.terrainSessionActive());
        assertFalse(VoxyVanillaCoverage.terrainSessionActive());
    }
    @Test void unrelatedLightWorkAtTwentyOrThirtyFpsCannotStarveReadyMovingChunks() {
        for(int fps:new int[]{20,30}) {
            var backend=new Backend();var coverage=new VoxyVanillaCoverage(backend);var world=new World();
            // 1.21.11 line: ClientLevel has no light-queue probe; readiness is per section only.
            var unready=world.add(9,1);
            when(world.light.lightOnInColumn(net.minecraft.core.SectionPos.getZeroNode(net.minecraft.core.SectionPos.of(unready.getPos(),0).asLong()))).thenReturn(false);
            coverage.loaded(world.level,unready);
            var expected=new ArrayList<LevelChunk>();
            for(int frame=0;frame<40;frame++) {
                var chunk=world.add(9+frame,0);expected.add(chunk);coverage.loaded(world.level,chunk);
                coverage.tick(world.level,frame*16+.5,.5,8,frame*(1_000_000_000L/fps));
            }
            assertTrue(backend.calls.containsAll(expected));assertFalse(backend.calls.contains(unready));
            world.dispose();
        }
    }
    @Test void renderEndRecoversAfterLightingWithoutSpendingAnotherTickBudget() {
        var backend=new Backend();var coverage=new VoxyVanillaCoverage(backend);var world=new World();var chunk=world.add(9,0);coverage.loaded(world.level,chunk);
        when(world.light.lightOnInColumn(anyLong())).thenReturn(false);
        coverage.tick(world.level,.5,.5,8,0);assertTrue(backend.calls.isEmpty());
        when(world.light.lightOnInColumn(anyLong())).thenReturn(true);
        for(int frame=0;frame<30;frame++)coverage.pump(world.level,.5,.5,8,frame*1_000_000L);
        assertEquals(1,backend.calls.size());
        assertTrue(backend.calls.size()<=VoxyVanillaCoverage.SUBMISSIONS_PER_TICK);
    }
    @BeforeAll static void boot() {SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    static final class Backend implements VoxyVanillaCoverage.Access {
        Object destination=new Object();
        int backlog;
        java.util.function.Predicate<LevelChunk> behavior=chunk->true;
        final List<LevelChunk> calls=new ArrayList<>();
        public Object destination(ClientLevel level) {return destination;}
        public int backlog() {return backlog;}
        public boolean ingest(LevelChunk chunk) {calls.add(chunk);return behavior.test(chunk);}
    }
    static final class World {
        final ClientLevel level=mock(ClientLevel.class);
        final ClientChunkCache chunks=mock(ClientChunkCache.class);
        final net.minecraft.world.level.lighting.LevelLightEngine light=mock(net.minecraft.world.level.lighting.LevelLightEngine.class);
        final net.minecraft.world.level.chunk.LevelChunkSection section=mock(net.minecraft.world.level.chunk.LevelChunkSection.class);
        final Map<Long,LevelChunk> data=new HashMap<>();
        /** The sky-light reader Voxy uses: a layer for every section unless a test says otherwise. */
        final net.minecraft.world.level.lighting.LayerLightEventListener sky=mock(net.minecraft.world.level.lighting.LayerLightEventListener.class);
        final net.minecraft.world.level.dimension.DimensionType dimensionType=mock(net.minecraft.world.level.dimension.DimensionType.class);
        World() {
            when(light.getLayerListener(net.minecraft.world.level.LightLayer.SKY)).thenReturn(sky);
            when(sky.getDataLayerData(any())).thenReturn(new net.minecraft.world.level.chunk.DataLayer());
            when(level.getChunkSource()).thenReturn(chunks);
            when(level.getLightEngine()).thenReturn(light);when(light.lightOnInColumn(anyLong())).thenReturn(true);
            when(light.getDebugSectionType(any(),any())).thenReturn(net.minecraft.world.level.lighting.LayerLightSectionStorage.SectionType.LIGHT_AND_DATA);
            when(dimensionType.hasSkyLight()).thenReturn(true);when(level.dimensionType()).thenReturn(dimensionType);
            when(chunks.getChunk(anyInt(),anyInt(),eq(ChunkStatus.FULL),eq(false)))
                    .thenAnswer(call->data.get(ChunkPos.asLong(call.getArgument(0),call.getArgument(1))));
        }
        LevelChunk add(int x,int z) {var chunk=mock(LevelChunk.class,withSettings().stubOnly());when(chunk.getPos()).thenReturn(new ChunkPos(x,z));when(chunk.getSections()).thenReturn(new net.minecraft.world.level.chunk.LevelChunkSection[]{section});data.put(ChunkPos.asLong(x,z),chunk);return chunk;}
        void dispose() {
            // Inline Mockito keeps handlers for these thousands of chunk doubles.
            // Release each finished scenario rather than raising the suite's heap.
            for(var chunk:data.values())framework().clearInlineMock(chunk);
            framework().clearInlineMock(chunks);framework().clearInlineMock(level);data.clear();
        }
    }
    // ---- Issue #429: the sky light Voxy is about to read must be there ----

    /** Every section says "light and data", and yet the sky-light reader returns null (what
     *  ScalableLux 0.1.0.1 does between attaching a packet's light and the client's light
     *  pass). Handing the chunk over then stores sky light 0 in Voxy for good: black strips.
     *  The chunk is held back, submitted once the layer exists, and never twice. */
    @Test void aChunkWhoseSkyLightCannotBeReadYetIsHeldBackUntilItCan() {
        VoxyLightStats.reset();
        var backend=new Backend();var coverage=new VoxyVanillaCoverage(backend);var world=new World();
        var chunk=world.add(9,0);var other=world.add(-9,0);
        var held=net.minecraft.core.SectionPos.of(chunk.getPos(),0);
        when(world.sky.getDataLayerData(held)).thenReturn(null);
        assertEquals(VoxyVanillaCoverage.SKY_UNREADABLE,VoxyVanillaCoverage.lightState(world.level,chunk));
        assertFalse(VoxyVanillaCoverage.lightReady(world.level,chunk));

        sweep(coverage,world,0,100);
        assertEquals(List.of(other),backend.calls,"the unreadable chunk is not handed over; its neighbour is");
        assertEquals(1,coverage.skyWaitingForTest());
        assertEquals(1,VoxyLightStats.borderHolds(),"one hold however often the chunk was looked at");
        assertEquals(1,VoxyLightStats.borderWaiting());
        assertTrue(VoxyLightStats.borderOldestWaitMillis(60_000_000_000L)>=55_000,"its age is the time since it was first seen so");

        when(world.sky.getDataLayerData(held)).thenReturn(new net.minecraft.world.level.chunk.DataLayer());
        sweep(coverage,world,10_000_000_000L,100);
        assertEquals(1,backend.calls.stream().filter(c->c==chunk).count(),"handed over once its sky light can be read");
        assertEquals(0,coverage.skyWaitingForTest());
        assertEquals(0,VoxyLightStats.borderWaiting());
        assertEquals(0,VoxyLightStats.borderOldestWaitMillis(99_000_000_000L));
        assertEquals(1,VoxyLightStats.borderHolds(),"the count of holds stays");
        sweep(coverage,world,20_000_000_000L,100);
        assertEquals(2,backend.calls.size(),"and never a second time");
        world.dispose();
    }
    /** An empty layer is light that was read (all dark); only null is "not there yet". */
    @Test void anAllDarkSkyLayerIsReadableAndAnAllAirSectionNeedsNone() {
        VoxyLightStats.reset();
        var backend=new Backend();var coverage=new VoxyVanillaCoverage(backend);var world=new World();
        var chunk=world.add(9,0);
        when(world.sky.getDataLayerData(any())).thenReturn(new net.minecraft.world.level.chunk.DataLayer());
        assertEquals(VoxyVanillaCoverage.LIGHT_READY,VoxyVanillaCoverage.lightState(world.level,chunk));
        // an all-air section above the terrain legitimately has no layer
        when(world.section.hasOnlyAir()).thenReturn(true);
        when(world.sky.getDataLayerData(any())).thenReturn(null);
        assertEquals(VoxyVanillaCoverage.LIGHT_READY,VoxyVanillaCoverage.lightState(world.level,chunk));
        sweep(coverage,world,0,100);
        assertEquals(0,VoxyLightStats.borderHolds());
        world.dispose();
    }
    /** The Nether and the End have no sky light: their reader always returns null, and
     *  waiting for it would hold every chunk back for ever. */
    @Test void aDimensionWithoutSkyLightIsNeverHeldBack() {
        VoxyLightStats.reset();
        var backend=new Backend();var coverage=new VoxyVanillaCoverage(backend);var world=new World();
        when(world.dimensionType.hasSkyLight()).thenReturn(false);
        when(world.sky.getDataLayerData(any())).thenReturn(null);
        var chunk=world.add(9,0);
        assertEquals(VoxyVanillaCoverage.LIGHT_READY,VoxyVanillaCoverage.lightState(world.level,chunk));
        sweep(coverage,world,0,100);
        assertEquals(List.of(chunk),backend.calls);
        assertEquals(0,VoxyLightStats.borderHolds());
        verify(world.sky,never()).getDataLayerData(any());
        world.dispose();
    }
    /** The older reasons still read as "pending", not as the sky-light case. */
    @Test void lightThatIsNotSwitchedOnYetIsPendingNotUnreadable() {
        var world=new World();var chunk=world.add(9,0);
        when(world.light.lightOnInColumn(anyLong())).thenReturn(false);
        assertEquals(VoxyVanillaCoverage.LIGHT_PENDING,VoxyVanillaCoverage.lightState(world.level,chunk));
        verify(world.sky,never()).getDataLayerData(any());
        world.dispose();
    }
    /** A chunk Voxy refused is not probed again before its retry is due: the probe clones a
     *  layer per section on some light engines, and it would run every frame. */
    @Test void aRefusedChunkIsNotProbedBeforeItsRetryIsDue() {
        var backend=new Backend();var coverage=new VoxyVanillaCoverage(backend);var world=new World();
        var chunk=world.add(9,0);
        backend.behavior=c->false;
        coverage.loaded(world.level,chunk);
        coverage.tick(world.level,.5,.5,8,0);
        assertEquals(1,backend.calls.size(),"submitted once and refused");
        clearInvocations(world.sky,world.light);
        for(int frame=1;frame<=20;frame++)coverage.pump(world.level,.5,.5,8,frame*10_000_000L);
        verify(world.sky,never()).getDataLayerData(any());
        verify(world.light,never()).getDebugSectionType(any(),any());
        coverage.tick(world.level,.5,.5,8,VoxyVanillaCoverage.RETRY_INTERVAL+1);
        assertEquals(2,backend.calls.size(),"and again once the retry is due");
        world.dispose();
    }
    /** A waiting chunk that unloads or falls out of the square is no longer waiting. */
    @Test void aWaitingChunkThatLeavesIsForgotten() {
        VoxyLightStats.reset();
        var backend=new Backend();var coverage=new VoxyVanillaCoverage(backend);var world=new World();
        world.add(9,0);
        when(world.sky.getDataLayerData(any())).thenReturn(null);
        sweep(coverage,world,0,100);
        assertEquals(1,coverage.skyWaitingForTest());
        // the camera moves far away: the square no longer covers the chunk
        for(int n=0;n<100;n++)coverage.tick(world.level,5000.5,.5,8,10_000_000_000L+n*50_000_000L);
        assertEquals(0,coverage.skyWaitingForTest());
        assertEquals(0,VoxyLightStats.borderWaiting());
        // and a world change clears the list but keeps the count
        world.add(9,0);
        for(int n=0;n<100;n++)coverage.tick(world.level,.5,.5,8,30_000_000_000L+n*50_000_000L);
        assertEquals(1,coverage.skyWaitingForTest());
        coverage.clear();
        assertEquals(0,coverage.skyWaitingForTest());
        assertEquals(0,VoxyLightStats.borderWaiting());
        assertTrue(VoxyLightStats.borderHolds()>=1);
        world.dispose();
    }
    /** The player moves on and the held chunk is no longer at the border: nothing probes it
     *  again, so it must leave the gauge, which exists to show a hold that does not end. */
    @Test void aWaitingChunkThePlayerHasPassedIsNoLongerWaiting() {
        VoxyLightStats.reset();
        var backend=new Backend();var coverage=new VoxyVanillaCoverage(backend);var world=new World();
        world.add(9,0);
        when(world.sky.getDataLayerData(any())).thenReturn(null);
        sweep(coverage,world,0,100);
        assertEquals(1,coverage.skyWaitingForTest());
        assertEquals(1,VoxyLightStats.borderWaiting());
        assertFalse(VoxyVanillaCoverage.atBorder(9,0,152.5,.5,8),"premise: under the player it is not at the border");
        // the player now stands in that chunk: still inside the square, no longer at its border
        coverage.tick(world.level,152.5,.5,8,10_000_000_000L);
        assertEquals(0,coverage.skyWaitingForTest());
        assertEquals(0,VoxyLightStats.borderWaiting());
        assertEquals(0,VoxyLightStats.borderOldestWaitMillis(99_000_000_000L));
        // and a backlog that stops the pump early still reports what waits
        when(world.sky.getDataLayerData(any())).thenReturn(null);
        var other=new World();other.add(9,0);when(other.sky.getDataLayerData(any())).thenReturn(null);
        var gated=new VoxyVanillaCoverage(backend);
        sweep(gated,other,20_000_000_000L,100);
        assertEquals(1,VoxyLightStats.borderWaiting());
        backend.backlog=VoxyVanillaCoverage.BACKLOG_GATE+1;
        gated.tick(other.level,152.5,.5,8,40_000_000_000L);
        assertEquals(0,VoxyLightStats.borderWaiting(),"the gate returns early, the gauge is still brought up to date");
        world.dispose();other.dispose();
    }
    @Test void theDiagLineIsAbsentAtZeroAndNamesWhatWaits() {
        assertNull(VoxyLightStats.diagLineOrNull(0,0,0,0));
        assertEquals("Voxy light: sections_without_sky=3, border_fill_held=0",VoxyLightStats.diagLineOrNull(3,0,0,0));
        assertEquals("Voxy light: sections_without_sky=0, border_fill_held=12",VoxyLightStats.diagLineOrNull(0,12,0,5000));
        assertEquals("Voxy light: sections_without_sky=0, border_fill_held=12, waiting=2 (oldest 41 s)",
                VoxyLightStats.diagLineOrNull(0,12,2,41_900));
    }
    static void sweep(VoxyVanillaCoverage coverage,World world,long start,int ticks) {
        for(int n=0;n<ticks;n++)coverage.tick(world.level,.5,.5,8,start+n*50_000_000L);
    }
    @Test void boundaryIncludesLoadedUnrenderedCornersAndAnInwardOverlap() {
        assertTrue(VoxyVanillaCoverage.atBorder(7,6,.5,.5,8));
        assertTrue(VoxyVanillaCoverage.atBorder(-7,-6,.5,.5,8));
        assertTrue(VoxyVanillaCoverage.atBorder(8,0,8,8,8));
        assertFalse(VoxyVanillaCoverage.atBorder(0,0,8,8,8));
        assertFalse(VoxyVanillaCoverage.atBorder(6,0,8,8,8));
    }
    @Test void stationaryColdBorderUsesActualVanillaChunksOnceAndNeverRequestsMissingChunks() {
        var backend=new Backend();var coverage=new VoxyVanillaCoverage(backend);var world=new World();
        var corner=world.add(7,6);var edge=world.add(9,0);var center=world.add(0,0);
        sweep(coverage,world,0,100);
        assertEquals(2,backend.calls.size());assertTrue(backend.calls.containsAll(List.of(corner,edge)));assertFalse(backend.calls.contains(center));
        sweep(coverage,world,100_000_000_000L,100);
        assertEquals(2,backend.calls.size(),"elapsed time does not resubmit accepted vanilla chunks");
        verify(world.chunks,never()).getChunk(anyInt(),anyInt(),any(),eq(true));
    }
    @Test void earlyLightingRejectionIsRetriedWithoutBlockingOtherReadyColumns() {
        var backend=new Backend();var coverage=new VoxyVanillaCoverage(backend);var world=new World();
        var corner=world.add(-7,-6);var edge=world.add(9,0);
        backend.behavior=chunk->chunk!=corner;
        sweep(coverage,world,0,40);
        assertTrue(backend.calls.contains(corner));assertTrue(backend.calls.contains(edge));
        long failures=backend.calls.stream().filter(c->c==corner).count();assertTrue(failures>=1 && failures<=2);
        backend.behavior=chunk->true;sweep(coverage,world,2_000_000_000L,80);
        long after=backend.calls.stream().filter(c->c==corner).count();assertEquals(failures+1,after);
        assertEquals(1,backend.calls.stream().filter(c->c==edge).count());
    }
    @Test void backpressureAndMissingDestinationDeferAllSubmissions() {
        var backend=new Backend();var coverage=new VoxyVanillaCoverage(backend);var world=new World();world.add(9,0);
        backend.backlog=VoxyVanillaCoverage.BACKLOG_GATE+1;sweep(coverage,world,0,100);assertTrue(backend.calls.isEmpty());
        assertTrue(VoxyVanillaCoverage.BACKLOG_GATE>dev.vox.lss.networking.client.DhClientSession.INGEST_GATE,"Vanilla border terrain outranks far DH approximations");
        backend.backlog=0;backend.destination=null;sweep(coverage,world,5_000_000_000L,100);assertTrue(backend.calls.isEmpty());
        backend.destination=new Object();sweep(coverage,world,10_000_000_000L,100);assertEquals(1,backend.calls.size());
    }
    @Test void aReloadedColumnUsesTheCurrentChunkRatherThanItsOldQueuedReference() {
        var backend=new Backend();var coverage=new VoxyVanillaCoverage(backend);var world=new World();var old=world.add(9,0);
        sweep(coverage,world,0,100);var replacement=world.add(9,0);sweep(coverage,world,5_000_000_000L,100);
        assertEquals(List.of(old,replacement),backend.calls);
    }
    @Test void worldReplacementAndDestinationResetReleaseAcceptance() {
        var backend=new Backend();var coverage=new VoxyVanillaCoverage(backend);var first=new World();var a=first.add(9,0);
        sweep(coverage,first,0,100);var second=new World();var b=second.add(9,0);sweep(coverage,second,5_000_000_000L,100);
        backend.destination=new Object();sweep(coverage,second,10_000_000_000L,100);
        assertEquals(List.of(a,b,b),backend.calls);
        coverage.tick(null,0,0,8,15_000_000_000L);assertEquals(0,coverage.tracked());
    }
    @Test void movementPrunesThePreviousBorderWithoutHoldingUnloadedChunkObjects() {
        var backend=new Backend();var coverage=new VoxyVanillaCoverage(backend);var world=new World();world.add(9,0);
        sweep(coverage,world,0,100);assertEquals(1,coverage.tracked());
        coverage.tick(world.level,1600,1600,8,5_000_000_000L);assertEquals(0,coverage.tracked());
    }
    @Test void optionalAbiFailureStopsOnlyTheSupplementalRetry() {
        var backend=new Backend();var coverage=new VoxyVanillaCoverage(backend);var world=new World();world.add(9,0);
        backend.behavior=chunk->{throw new NoSuchMethodError("test-only incompatible Voxy");};
        sweep(coverage,world,0,100);assertEquals(1,backend.calls.size());assertEquals(0,coverage.tracked());
        sweep(coverage,world,5_000_000_000L,100);assertEquals(1,backend.calls.size());
    }
    @Test void transientDestinationFailureDoesNotDisableTheNextWorldOrReset() {
        var backend=new Backend();var coverage=new VoxyVanillaCoverage(backend);var world=new World();world.add(9,0);
        backend.behavior=c->{throw new IllegalStateException("WorldEngine that was not alive");};
        sweep(coverage,world,0,10);backend.behavior=c->true;coverage.clear();
        sweep(coverage,world,1_000_000_000L,100);assertTrue(coverage.tracked()>0);
    }
    @Test void theMovingFrontierIsBoundedOnAllFourSides() {
        for(int distance:new int[]{12,32})for(double speed:new double[]{5.6,30})for(int[] direction:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            var backend=new Backend();var coverage=new VoxyVanillaCoverage(backend);var world=new World();
            var admitted=java.util.Collections.newSetFromMap(new IdentityHashMap<LevelChunk,Boolean>());
            backend.behavior=chunk->{admitted.add(chunk);return true;};
            var firstSeen=new IdentityHashMap<LevelChunk,Integer>();
            for(int tick=0;tick<400;tick++) {
                double x=direction[0]*speed*tick*.05+.5,z=direction[1]*speed*tick*.05+.5;int cx=(int)Math.floor(x/16),cz=(int)Math.floor(z/16);
                for(int dx=-distance;dx<=distance;dx++)for(int dz=-distance;dz<=distance;dz++) {
                    long position=ChunkPos.asLong(cx+dx,cz+dz);
                    if(!world.data.containsKey(position))coverage.loaded(world.level,world.add(cx+dx,cz+dz));
                }
                int before=backend.calls.size();coverage.tick(world.level,x,z,distance,tick*50_000_000L);
                assertTrue(backend.calls.size()-before<=VoxyVanillaCoverage.SUBMISSIONS_PER_TICK);
                for(var entry:world.data.entrySet()) {
                    int bx=(int)(long)entry.getKey(),bz=(int)(entry.getKey()>>32);
                    if(Math.abs(bx-cx)>distance || Math.abs(bz-cz)>distance || !VoxyVanillaCoverage.atBorder(bx,bz,x,z,distance)) {firstSeen.remove(entry.getValue());continue;}
                    int seen=firstSeen.getOrDefault(entry.getValue(),tick);firstSeen.putIfAbsent(entry.getValue(),tick);
                    if(tick>240 && tick-seen>60)assertTrue(admitted.contains(entry.getValue()),"moving border starved at "+bx+","+bz+" RD="+distance+" speed="+speed);
                }
            }
            verify(world.chunks,never()).getChunk(anyInt(),anyInt(),any(),eq(true));
            world.dispose();backend.calls.clear();admitted.clear();firstSeen.clear();coverage.clear();
        }
    }
    @Test void unresolvedOptionalSurfaceLeavesTheOrdinaryBridgeAvailable() {
        assertNull(VoxyVanillaCoverage.resolve(name->{throw new ClassNotFoundException(name);}));
        assertNull(VoxyVanillaCoverage.resolve(Class::forName),"older ingest-only test stub has no optional whole-chunk API");
    }
    public static final class Identifier {
        static Object engine;
        public static Identifier of(net.minecraft.world.level.Level level) {return new Identifier();}
        public Object getNullable() {return engine;}
    }
    public static final class Service {
        static final List<LevelChunk> chunks=new ArrayList<>();
        public static boolean tryAutoIngestChunk(LevelChunk chunk) {chunks.add(chunk);return true;}
    }
    public static final class Instance {
        static boolean enabled=true;
        public boolean isIngestEnabled(Identifier id) {return enabled;}
    }
    public static final class Common {
        static Instance instance=new Instance();
        public static Instance getInstance() {return instance;}
    }
    @Test void optionalReflectionUsesTheLiveDestinationAndTheWholeVanillaChunk() {
        Identifier.engine=null;Service.chunks.clear();
        var coverage=VoxyVanillaCoverage.resolve(name->switch(name) {
            case "me.cortex.voxy.commonImpl.WorldIdentifier" -> Identifier.class;
            case "me.cortex.voxy.common.world.service.VoxelIngestService" -> Service.class;
            case "me.cortex.voxy.commonImpl.VoxyInstance" -> Instance.class;
            case "me.cortex.voxy.commonImpl.VoxyCommon" -> Common.class;
            default -> throw new ClassNotFoundException(name);
        });
        assertNotNull(coverage);var world=new World();var chunk=world.add(9,0);
        sweep(coverage,world,0,100);assertTrue(Service.chunks.isEmpty());
        Identifier.engine=new Object();sweep(coverage,world,5_000_000_000L,100);
        assertEquals(List.of(chunk),Service.chunks);
        Identifier.engine=new Object();sweep(coverage,world,10_000_000_000L,100);
        assertEquals(List.of(chunk,chunk),Service.chunks);
        Instance.enabled=false;sweep(coverage,world,15_000_000_000L,100);assertEquals(2,Service.chunks.size());
        Instance.enabled=true;sweep(coverage,world,20_000_000_000L,100);assertEquals(List.of(chunk,chunk,chunk),Service.chunks);
        Identifier.engine=null;Service.chunks.clear();
    }
    @Test void anEmptyColumnClearReportedFalseByVoxyDoesNotLoop() {
        var backend=new Backend();var coverage=new VoxyVanillaCoverage(backend);var world=new World();var chunk=world.add(9,0);
        var air=mock(net.minecraft.world.level.chunk.LevelChunkSection.class);when(air.hasOnlyAir()).thenReturn(true);
        when(chunk.getSections()).thenReturn(new net.minecraft.world.level.chunk.LevelChunkSection[]{air});backend.behavior=c->false;
        sweep(coverage,world,0,100);sweep(coverage,world,100_000_000_000L,100);
        assertEquals(List.of(chunk),backend.calls);
        backend.destination=new Object();sweep(coverage,world,200_000_000_000L,100);
        assertEquals(List.of(chunk,chunk),backend.calls);
    }
}
