package dev.vox.lss.networking.client;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.common.config.ClientSettings;
import dev.vox.lss.common.config.SettingsHandle;
import dev.vox.lss.common.config.SettingsSchema;
import dev.vox.lss.common.config.SettingsStore;
import dev.vox.lss.common.dh.*;
import dev.vox.lss.common.diagnostics.DhStatusSnapshot;
import dev.vox.lss.compat.DhVoxyBridge;
import dev.vox.lss.compat.ModCompat;
import dev.vox.lss.config.LSSClientConfig;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.objenesis.ObjenesisStd;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Exercises the actual private session and its worker, transport leases and main-thread
 * callbacks against real captured DH corpora. Pins the 2026-10-10 remediation: whole-tile
 * delivery in two client-thread hops, arrival-order push ordering (no timestamp fences),
 * loss epochs, scoped recovery, persisted receipts with a destination probe, no reconnect
 * trial, and the slim diagnostics.
 */
class DhClientSessionTest {
    private static final DhPosition POSITION=new DhPosition(6,10,-10);
    private static final String DIMENSION="minecraft:overworld";
    private static final Class<?> SESSION=inner(DhClientSession.class,"Session");
    private static final Class<?> PENDING=inner(SESSION,"Pending");
    private static MappedRegistry<Biome> biomes;
    @TempDir Path dir;
    private TestMinecraft minecraft;
    private ClientLevel level;
    private ClientPacketListener connection;
    private Object previousMinecraft,previousHandle,previousSender,previousUnavailable,previousNativeAvailability,previousChannelAvailability,previousTransportReason;
    private SettingsHandle<ClientSettings> settings;
    private final LinkedBlockingQueue<Runnable> mainTasks=new LinkedBlockingQueue<>();
    private final List<byte[]> sent=new ArrayList<>();
    private final List<Object> sessions=new ArrayList<>();
    private String cacheComponent;private Object previousCacheRoot,previousNotice;
    private final List<net.minecraft.network.chat.Component> notices=new ArrayList<>();

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var source=VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
        biomes=new MappedRegistry<>(Registries.BIOME,Lifecycle.stable());
        source.listElements().forEach(h->biomes.register(h.key(),h.value(),RegistrationInfo.BUILT_IN));
        biomes.freeze();
    }

    @BeforeEach void setup() throws Exception {
        previousMinecraft=read(null,Minecraft.class,"instance");
        previousCacheRoot=read(null,ColumnCacheStore.class,"cacheDir");write(null,ColumnCacheStore.class,"cacheDir",dir.resolve("column-cache"));
        previousHandle=read(LSSClientConfig.CONFIG,LSSClientConfig.class,"handle");
        previousSender=read(null,DhClientSession.class,"sender");
        previousUnavailable=read(null,DhClientSession.class,"unavailable");
        previousTransportReason=read(null,DhClientSession.class,"transportReason");
        previousNativeAvailability=read(null,DhClientSession.class,"nativeAvailable");
        previousChannelAvailability=read(null,DhClientSession.class,"channelAvailable");
        DhClientSession.installChannelAvailability(()->true);
        ((Map<?,?>)read(null,DhClientSession.class,"discoveredProfiles")).clear();
        ((Map<?,?>)read(null,DhClientSession.class,"notified")).clear();
        write(null,DhClientSession.class,"receptionPaused",false);
        previousNotice=read(null,DhClientSession.class,"notice");
        write(null,DhClientSession.class,"notice",(java.util.function.Consumer<net.minecraft.network.chat.Component>)notices::add);
        DhClientSession.installNativeAvailability(()->false);
        // Allocate without Minecraft's graphics constructor. The original
        // getConnection reads LocalPlayer.connection; only execute and the
        // remote-server accessor are replaced with test-owned state.
        minecraft=new ObjenesisStd().newInstance(TestMinecraft.class);
        minecraft.mainTasks=mainTasks;
        write(minecraft,Minecraft.class,"gameDirectory",dir.toFile());
        level=mcMock(ClientLevel.class);connection=mcMock(ClientPacketListener.class);
        doReturn(mcMock(net.minecraft.network.Connection.class)).when(connection).getConnection();
        minecraft.level=level;
        minecraft.player=new ObjenesisStd().newInstance(LocalPlayer.class);
        write(minecraft.player,LocalPlayer.class,"connection",connection);
        doReturn(Level.OVERWORLD).when(level).dimension();
        doReturn(new RegistryAccess.ImmutableRegistryAccess(List.of(biomes)).freeze()).when(level).registryAccess();
        String address="test-dh-session-"+UUID.randomUUID()+":25565";
        var server=mcMock(ServerData.class);server.ip=address;
        minecraft.server=server;
        cacheComponent=CacheKeyAliases.addressComponent(address);
        write(null,Minecraft.class,"instance",minecraft);
        DhClientSession.disconnect();DestinationEngine.observedIngest=false;mainTasks.clear();
        var store=new SettingsStore<>(dir,"lss",SettingsSchema.client());
        settings=new SettingsHandle<>(store);
        // No lod.source is written: Allow DH Server Terrain is ON by default (2026-10-10).
        settings.commit(settings.prepareReload());
        write(LSSClientConfig.CONFIG,LSSClientConfig.class,"handle",settings);
        DhClientSession.installTransport(bytes->sent.add(bytes.clone()),"test transport unavailable");
    }

    @AfterEach void cleanup() throws Exception {
        try {
            ClientTraceLog.stop();
            for(var s:sessions) invoke(s,"retire");
            DhClientSession.disconnect();
            // Stopping a session queues a forced receipt save on RECEIPT_IO; let it land before
            // JUnit deletes the @TempDir, or the deletion races the save's temp file (2026-10-10).
            awaitReceiptIo();
            mainTasks.clear();
            if(cacheComponent!=null) ColumnCacheStore.clearForServers(List.of(cacheComponent));
        } finally {
            if(settings!=null) settings.close();
            write(LSSClientConfig.CONFIG,LSSClientConfig.class,"handle",previousHandle);
            write(null,DhClientSession.class,"sender",previousSender);
            write(null,DhClientSession.class,"unavailable",previousUnavailable);
            write(null,DhClientSession.class,"transportReason",previousTransportReason);
            write(null,DhClientSession.class,"nativeAvailable",previousNativeAvailability);
            write(null,DhClientSession.class,"channelAvailable",previousChannelAvailability);
            ((Map<?,?>)read(null,DhClientSession.class,"discoveredProfiles")).clear();
            write(null,DhClientSession.class,"notice",previousNotice);
            write(null,Minecraft.class,"instance",previousMinecraft);
            write(null,ColumnCacheStore.class,"cacheDir",previousCacheRoot);
        }
    }

    private void setting(Map<String,Object> changes) throws Exception {
        settings.store().saveDraft(settings.store().read().hash(),changes);settings.commit(settings.prepareReload());
    }
    @Test void cancelledOfficialGenerationTimeoutsDoNotExhaustUnsafeSyncPermits() throws Exception {
        var s=updateSession();long now=System.nanoTime();
        for(int i=1;i<=3;i++){pending(s,i,true,now-DhLimits.DEFAULT.lifetimeNanos()-1);invoke(s,"expireRequests",new Class<?>[]{long.class},now);}
        assertEquals(0,read(s,SESSION,"unanswered"));assertTrue(active(s));
        pending(s,4,false,now-DhLimits.DEFAULT.lifetimeNanos()-1);invoke(s,"expireRequests",new Class<?>[]{long.class},now);
        assertEquals(1,read(s,SESSION,"unanswered"));
        pending(s,5,false,now);capture(codec(s).encode(new DhMessage.Refusal(5,0,"busy")),now).receive();
        pumpUntil(()->frameCount(s)==0,"refusal did not settle");assertEquals(0,read(s,SESSION,"unanswered"));
    }
    @Test void answeredReplyStaysProtectedAfterFrameCloseBeforeItsClientCallback() throws Exception {
        var s=updateSession();long now=System.nanoTime();pending(s,7,true,now-3_000_000_000L);
        capture(codec(s).encode(new DhMessage.Response(7,null,List.of())),now).receive();waitWorker(s);
        assertEquals(0,frameCount(s));assertTrue(pendingMap(s).containsKey(7));
        invoke(s,"pruneGeneration",new Class<?>[]{long.class,ChunkPos.class},now,new ChunkPos(1000,1000));
        invoke(s,"expireRequests",new Class<?>[]{long.class},now+DhLimits.DEFAULT.lifetimeNanos()+1);
        assertTrue(sent.isEmpty());assertEquals(0,read(s,SESSION,"unanswered"));assertTrue(pendingMap(s).containsKey(7));
        pumpUntil(()->pendingMap(s).isEmpty(),"client reply bookkeeping did not settle");
    }
    @Test void resetCapabilityRequiresLiveOwnedServiceAndDistinguishesPushOnly() throws Exception {
        var s=updateSession();assertTrue(DhClientSession.canAcquire());
        setting(Map.of("lod.dh.sync_on_load",false));assertFalse(DhClientSession.canAcquire());
        setting(Map.of("lod.dh.sync_on_load",true));
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,true,32,false,0,0,500));
        assertFalse(DhClientSession.canAcquire());
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.SURFACE_ONLY,32,0,0,0,1,false,0,false,0,0,500));
        assertTrue(DhClientSession.canAcquire());
        setting(Map.of("lod.receive",false));assertFalse(DhClientSession.canAcquire());
        setting(Map.of("lod.receive",true));DhClientSession.installChannelAvailability(()->false);assertFalse(DhClientSession.canAcquire());
    }
    @Test void receiveOffWithdrawsLiveUpdatesOnceWithoutChangingSavedPreferences() throws Exception {
        var s=updateSession();write(s,SESSION,"announced",true);setting(Map.of("lod.receive",false));
        DhClientSession.tick();DhClientSession.tick();
        var cfg=sent.stream().map(b->{try{return codec(s).decode(b);}catch(Exception e){throw new AssertionError(e);}})
                .filter(DhMessage.SessionConfig.class::isInstance).map(DhMessage.SessionConfig.class::cast).toList();
        assertEquals(1,cfg.size());assertFalse(cfg.getFirst().updates());assertFalse(cfg.getFirst().synchronize());
        assertTrue(settings.state().configured().lod().dh().realTimeUpdates());assertFalse(active(s));
    }
    /** X-13 wiring: reception OFF on a live connection makes the next session a resume. */
    @Test void receptionOffThenOnBuildsAResumedSession() throws Exception {
        var s=updateSession();setting(Map.of("lod.receive",false));DhClientSession.tick();
        assertFalse(active(s));assertTrue((boolean)read(null,DhClientSession.class,"receptionPaused"));
        setting(Map.of("lod.receive",true));DhClientSession.tick();
        var next=read(null,DhClientSession.class,"session");
        if(next!=null){sessions.add(next);assertTrue((boolean)read(next,SESSION,"resumed"));}
        else assertTrue((boolean)read(null,DhClientSession.class,"receptionPaused"),"the resume is not lost while the session waits to start");
    }
    /** D-30: an integrated (singleplayer) host never records vanilla authority. */
    @Test void anIntegratedHostRecordsNoVanillaAuthority() throws Exception {
        var state=(DhConnectionState)read(null,DhClientSession.class,"connectionState");
        setting(Map.of("lod.source","distant_horizons"));
        minecraft.singleplayer=mcMock(net.minecraft.client.server.IntegratedServer.class);
        try {DhClientSession.vanillaLoaded(level,3,3);assertTrue(state.policy(DIMENSION).eligible(3,3));}
        finally {minecraft.singleplayer=null;}
        DhClientSession.vanillaLoaded(level,3,3);assertFalse(state.policy(DIMENSION).eligible(3,3));
    }
    /** D-22: the user's own sync-off choice is named as such, not as a server limitation. */
    @Test void syncTurnedOffLocallyIsReportedAsALocalChoice() throws Exception {
        updateSession();setting(Map.of("lod.dh.sync_on_load",false));
        assertEquals(DhStatusSnapshot.Reason.SYNC_DISABLED_LOCALLY,DhClientSession.diagnostics().reason());
        setting(Map.of("lod.dh.sync_on_load",true));
        assertEquals(DhStatusSnapshot.Reason.NONE,DhClientSession.diagnostics().reason());
    }
    /** An "unchanged" reply only confirms the stamp it was asked about. */
    @Test void anUnchangedReplyForAStampThatMovedOnIsNotAccepted() throws Exception {
        var s=updateSession();long now=System.nanoTime();
        policy(s).accepted(POSITION,1,1,456,now); // the tile moved on (e.g. a newer delivery) after the ask
        pendingMap(s).put(41,newPending(s,POSITION,false,now,123L));assembly(s).expect(41,POSITION);
        capture(codec(s).encode(new DhMessage.Response(41,null,List.of())),System.nanoTime()).receive();
        pumpUntil(()->pendingMap(s).isEmpty()&&frameCount(s)==0,"unchanged reply did not settle");
        assertNotEquals(123,policy(s).syncTimestamp(POSITION),"the stale stamp is never re-accepted");
        assertTrue(policy(s).due(POSITION,System.nanoTime()+3_000_000_000L),"the tile is checked again");
    }
    /** With live updates off a stray recovery request touches nothing. */
    @Test void recoveryWithLiveUpdatesOffTouchesNothing() throws Exception {
        var s=updateSession();long now=System.nanoTime();var held=new DhPosition(6,0,0);
        policy(s).accepted(held,1,1,123,now);setting(Map.of("lod.dh.real_time_updates",false));
        ((java.util.concurrent.atomic.AtomicBoolean)read(s,SESSION,"pushRecovery")).set(true);
        worker(s).submit(()->{try{invoke(s,"recoverLoss");}catch(Exception e){throw new RuntimeException(e);}}).get(5,TimeUnit.SECONDS);
        pumpUntil(()->true,"");
        assertTrue(policy(s).hasSnapshot(held));assertFalse(policy(s).due(held,Long.MAX_VALUE));
    }
    @Test void nativeTakeoverWithdrawsUpdatesButReplacedListenerAndReconfigurationDoNotSendOldControls() throws Exception {
        var s=updateSession();write(s,SESSION,"announced",true);DhClientSession.nativeOffer();
        assertEquals(1,sent.size());assertFalse(((DhMessage.SessionConfig)codec(s).decode(sent.getFirst())).updates());
        DhClientSession.disconnect();sent.clear();
        var replaced=updateSession();write(replaced,SESSION,"announced",true);
        write(minecraft.player,LocalPlayer.class,"connection",mcMock(ClientPacketListener.class));invoke(replaced,"beginRetire");
        assertTrue(sent.isEmpty());write(minecraft.player,LocalPlayer.class,"connection",connection);
        var reconfigured=updateSession();write(reconfigured,SESSION,"announced",true);DhClientSession.onJoin();invoke(reconfigured,"retire");assertTrue(sent.isEmpty());
    }
    @Test void unrelatedPostDeadlineFramesDoNotRetainSilentRequestsAndBuffers() throws Exception {
        var s=updateSession();long now=System.nanoTime();
        pending(s,700,false,now-61_000_000_000L);
        assembly(s).accept(new DhMessage.Split(90,new byte[]{1,2,3},true),now-61_000_000_000L);
        var unrelated=codec(s).encode(new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,true,32,1,500));
        // Keep capture ownership nonempty while unrelated work continues to drain.
        try(var held=capture(unrelated,now)) {
            for(int i=0;i<3;i++) {
                capture(unrelated,now+i).receive();waitWorker(s);
                invoke(s,"expireRequests",new Class<?>[]{long.class},now+i);
                ((ThreadPoolExecutor)read(s,SESSION,"worker")).submit(()->invokeUnchecked(s,"expireAssembly")).get(10,TimeUnit.SECONDS);
            }
            assertTrue(pendingMap(s).isEmpty(),"unrelated captured traffic retained a silent sync slot");
            assertEquals(0,assembly(s).incompleteBuffers(),"unrelated traffic retained expired fragments");
            assertEquals(1,read(s,SESSION,"unanswered"));
            assertEquals(unrelated.length+256,budget(s).used(),"only the held control frame remains");
        }
        assertEquals(0,budget(s).used());assertTrue(active(s));
    }
    @Test void timelyCaptureIncludingProcessingProtectsOnlyUntilItDrains() throws Exception {
        var s=updateSession();long now=System.nanoTime();pending(s,700,false,now-59_000_000_000L);
        try(var frame=capture(codec(s).encode(new DhMessage.Response(700,null,List.of())),now)) {
            invoke(s,"expireRequests",new Class<?>[]{long.class},now+2_000_000_000L);
            assertEquals(1,pendingMap(s).size());
            write(frame,DhClientSession.Inbound.class,"processing",true);
            assertEquals(now,invoke(s,"arrivalCutoff",new Class<?>[]{long.class},now+2_000_000_000L));
            frame.receive();pumpUntil(()->frameCount(s)==0,"timely queued reply did not drain");
            assertTrue(pendingMap(s).isEmpty());assertEquals(0,read(s,SESSION,"unanswered"));
        }
        assertEquals(0,budget(s).used());
    }
    @Test void newCaptureCannotPublishAnOlderArrivalAfterCleanup() throws Exception {
        var s=updateSession();var frames=read(s,SESSION,"frames");long cutoff;
        var attempted=new CountDownLatch(1);FutureTask<DhClientSession.Inbound> task=new FutureTask<>(()-> {
            attempted.countDown();var buffer=new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[]{0}));
            try {return DhClientSession.capture(buffer);}finally {buffer.release();}
        });
        Thread capturer=new Thread(task,"test-capture-race");
        synchronized(frames) {
            capturer.start();assertTrue(attempted.await(5,TimeUnit.SECONDS));cutoff=System.nanoTime();
            invoke(s,"expireRequests",new Class<?>[]{long.class},cutoff);
        }
        try(var frame=task.get(5,TimeUnit.SECONDS)) {
            assertNotNull(frame);assertTrue((long)read(frame,DhClientSession.Inbound.class,"arrivalNanos")-cutoff>=0);
        }finally {capturer.join(5000);}
        assertEquals(0,budget(s).used());
    }
    private static void invokeUnchecked(Object s,String method) {
        try {invoke(s,method);}catch(Exception e) {throw new AssertionError(e);}
    }
    @Test void manualColumnCapIsSharedByBothServicesAndConditionalChecks() throws Exception {
        var s=updateSession();setting(Map.of("lod.download.max_columns_per_second",20));long now=System.nanoTime();
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.SURFACE_ONLY,32,0,0,0,20,false,0,true,32,50,500));
        write(s,SESSION,"lastRequest",now);((java.util.concurrent.atomic.AtomicLong)read(s,SESSION,"requested")).set(1);
        for(boolean generation:new boolean[]{false,true}) {
            assertEquals(1_000_000L,invoke(s,"requestWait",new Class<?>[]{boolean.class,long.class},generation,now+799_000_000L));
            assertEquals(0L,invoke(s,"requestWait",new Class<?>[]{boolean.class,long.class},generation,now+800_000_000L));
        }
        write(s,SESSION,"lastRequest",now+800_000_000L);
        assertEquals(800_000_000L,invoke(s,"requestWait",new Class<?>[]{boolean.class,long.class},false,now+800_000_000L));
    }
    @Test void syncConcurrencyAtRateOneKeepsTheIndependentGenerationSlotAvailable() throws Exception {
        var s=updateSession();long now=System.nanoTime();
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.SURFACE_ONLY,32,0,0,0,1,false,0,true,32,1,500));
        pending(s,700,false,now-2_000_000_000L);
        assertEquals(Long.MAX_VALUE,invoke(s,"requestWait",new Class<?>[]{boolean.class,long.class},false,now));
        assertEquals(0L,invoke(s,"requestWait",new Class<?>[]{boolean.class,long.class},true,now));
        pendingMap(s).clear();assertEquals(0L,invoke(s,"requestWait",new Class<?>[]{boolean.class,long.class},false,now));
    }
    @Test void rateRefusalPausesItsWholeServiceButNotGenerationOrPermanentRetryBudget() throws Exception {
        var s=updateSession();write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.SURFACE_ONLY,32,0,0,0,20,false,0,true,32,50,500));
        long now=System.nanoTime();pending(s,700,false,now);
        capture(codec(s).encode(new DhMessage.Refusal(700,0,"limited")),now).receive();
        pumpUntil(()->frameCount(s)==0&&pendingMap(s).isEmpty(),"refusal did not finish");now=System.nanoTime();
        assertTrue((long)invoke(s,"requestWait",new Class<?>[]{boolean.class,long.class},false,now)>0);
        assertEquals(0L,invoke(s,"requestWait",new Class<?>[]{boolean.class,long.class},true,now));
        assertEquals(0L,invoke(s,"requestWait",new Class<?>[]{boolean.class,long.class},false,now+2_000_000_000L));
        assertFalse(policy(s).retryExhausted(POSITION));
    }
    @Test void delayedRangeRefusalUsesTheRequestContextAndRetriesAtTheChangedReachableFrontier() throws Exception {
        var s=updateSession();long now=System.nanoTime();var position=new DhPosition(6,0,0);
        invoke(s,"request",new Class<?>[]{DhPosition.class,int.class,boolean.class,long.class},position,0,false,now);
        waitWorker(s);Runnable task;while((task=mainTasks.poll())!=null)task.run();var request=(DhMessage.Request)codec(s).decode(sent.getLast());
        write(s,SESSION,"rangeEpoch",1L);capture(codec(s).encode(new DhMessage.Refusal(request.requestId(),1,"out of range")),now).receive();
        pumpUntil(()->frameCount(s)==0&&pendingMap(s).isEmpty(),"range refusal did not finish");assertFalse(policy(s).retryExhausted(position));
        assertFalse(policy(s).due(position,Long.MAX_VALUE));sent.clear();
        assertNotNull(scan(s,now+3_000_000_000L));assertTrue(policy(s).due(position,now+3_000_000_000L));
    }
    @Test void rangeContextEpochsCannotCollideAcrossRetainedPolicySessions() throws Exception {
        var first=updateSession();long now=System.nanoTime();scan(first,now);long old=(long)read(first,SESSION,"rangeEpoch");
        var position=new DhPosition(6,0,0);policy(first).rangeRefused(position,old,false);invoke(first,"retire");sent.clear();
        var replacement=updateSession();assertSame(policy(first),policy(replacement));
        write(replacement,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,true,64,50,500));
        scan(replacement,now+3_000_000_000L);assertNotEquals(old,read(replacement,SESSION,"rangeEpoch"));
        assertTrue(policy(replacement).due(position,now+3_000_000_000L));
    }
    @Test void movementCancelsOnlyObsoleteOfficialGenerationAndLateFramesRemainContained() throws Exception {
        var s=updateSession();long now=System.nanoTime();pending(s,700,true,now-3_000_000_000L);pendingAt(s,701,new DhPosition(6,0,0),0);
        assembly(s).accept(new DhMessage.Split(90,new byte[]{1},true));
        invoke(s,"pruneGeneration",new Class<?>[]{long.class,ChunkPos.class},now,new ChunkPos(1000,1000));waitWorker(s);
        assertEquals(Set.of(701),pendingMap(s).keySet());assertEquals(1,sent.size());
        assertEquals(new DhMessage.Cancel(700),codec(s).decode(sent.getFirst()));assertFalse(policy(s).retryExhausted(POSITION));
        capture(codec(s).encode(new DhMessage.Response(700,90,List.of())),now).receive();
        pumpUntil(()->frameCount(s)==0,"obsolete response did not settle");assertTrue(active(s));assertEquals(0,budget(s).used());
        assertEquals(Set.of(701),pendingMap(s).keySet());
    }
    @Test void movementPruningPreservesAlreadyCapturedRepliesAndTheirLeases() throws Exception {
        var s=updateSession();long now=System.nanoTime();pending(s,700,true,now-3_000_000_000L);
        try(var frame=capture(codec(s).encode(new DhMessage.Response(700,null,List.of())),now)) {
            invoke(s,"pruneGeneration",new Class<?>[]{long.class,ChunkPos.class},now,new ChunkPos(1000,1000));
            assertTrue(sent.isEmpty());assertTrue(pendingMap(s).containsKey(700));assertTrue(budget(s).used()>0);
        }
        assertEquals(0,budget(s).used());
    }
    @Test void nearbyOrNewGenerationAndSupportGenerationAreNotMovementCancelled() throws Exception {
        var s=updateSession();long now=System.nanoTime();pending(s,700,true,now-3_000_000_000L);
        invoke(s,"pruneGeneration",new Class<?>[]{long.class,ChunkPos.class},now,new ChunkPos(42,-38));assertTrue(sent.isEmpty());
        pendingMap(s).clear();pending(s,701,true,now-1_000_000_000L);
        invoke(s,"pruneGeneration",new Class<?>[]{long.class,ChunkPos.class},now,new ChunkPos(1000,1000));assertTrue(sent.isEmpty());
        write(s,SESSION,"profile",DhProfile.SUPPORT_15);pendingMap(s).clear();pending(s,702,true,now-3_000_000_000L);
        invoke(s,"pruneGeneration",new Class<?>[]{long.class,ChunkPos.class},now,new ChunkPos(1000,1000));assertTrue(sent.isEmpty());
        write(s,SESSION,"profile",DhProfile.OFFICIAL_16);
    }
    @Test void conflictReasonIsTypedAndDoesNotExportRawTransportDetails() throws Exception {
        DhClientSession.installTransport(null,"private-path secret-value",dev.vox.lss.common.diagnostics.DhStatusSnapshot.Reason.CLIENT_MOD_CONFLICT);
        var d=DhClientSession.diagnostics();assertEquals(dev.vox.lss.common.diagnostics.DhStatusSnapshot.Reason.CLIENT_MOD_CONFLICT,d.reason());
        assertEquals(dev.vox.lss.common.diagnostics.DhStatusSnapshot.State.NEED_TRANSPORT,d.state());
        var json=new com.google.gson.Gson().toJson(d);assertTrue(json.contains("CLIENT_MOD_CONFLICT"));assertFalse(json.contains("secret-value"));
    }
    @Test void dhSlowStartUsesWirePreferencesAndSurvivesSessionReplacement() throws Exception {
        var s=updateSession();invoke(s,"sendPreferences");assertEquals(50,((DhMessage.SessionConfig)codec(s).decode(sent.getLast())).bandwidthKB());
        var ramp=(DhBandwidthRamp)read(s,SESSION,"bandwidthRamp");ramp.received(50_000,0);ramp.received(1,1_000_000_000L);
        assertEquals(100,invoke(s,"bandwidthLimit"));invoke(s,"retire");var next=updateSession();
        assertSame(ramp,read(next,SESSION,"bandwidthRamp"));assertEquals(100,invoke(next,"bandwidthLimit"));
        setting(Map.of("lod.download.slow_start_on_join",false));invoke(next,"sendPreferences");
        assertEquals(500,((DhMessage.SessionConfig)codec(next).decode(sent.getLast())).bandwidthKB());
        setting(Map.of("lod.download.slow_start_on_join",true));assertEquals(500,invoke(next,"bandwidthLimit"));
        assertEquals(60_000_000_000L,invoke(next,"requestLifetime"));
        assertEquals(120_000_000_000L,((DhLimits)read(next,SESSION,"sessionLimits")).lifetimeNanos());
    }
    @Test void supportUsesLocalJoinPacingAndOffOpensItImmediately() throws Exception {
        try(var bridge=new ReadyBridge()) {
        var s=updateSession();write(s,SESSION,"profile",DhProfile.SUPPORT_15);long now=System.nanoTime();
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.CHUNKS_ONLY,32,0,0,0,20,false,0,false,0,0,0));
        write(s,SESSION,"lastGenerationRequest",now);
        assertEquals(4_000_000_000L,invoke(s,"requestWait",new Class<?>[]{boolean.class,long.class},true,now));
        assertEquals(60_000_000_000L,invoke(s,"requestLifetime"));
        assertEquals(0L,invoke(s,"requestWait",new Class<?>[]{boolean.class,long.class},true,now+5_000_000_000L));
        setting(Map.of("lod.download.slow_start_on_join",false));
        assertEquals(1_000_000_000L,invoke(s,"requestWait",new Class<?>[]{boolean.class,long.class},true,now));
        write(s,SESSION,"profile",DhProfile.OFFICIAL_16);
        }
    }
    @Test void dhBandwidthOnlyPreferenceChangesDoNotRestartLoadSynchronization() throws Exception {
        var s=updateSession();write(s,SESSION,"announced",true);invoke(s,"sendPreferences");sent.clear();
        var marker=Set.of(POSITION);write(s,SESSION,"visible",marker);write(s,SESSION,"visibleCenter",new ChunkPos(2,2));
        ((DhConnectionState)read(null,DhClientSession.class,"connectionState")).loadedAt(DIMENSION).put(POSITION,System.nanoTime());
        var ramp=(DhBandwidthRamp)read(s,SESSION,"bandwidthRamp");ramp.received(50_000,0);ramp.received(1,1_000_000_000L);
        invoke(s,"tick");assertFalse(sent.isEmpty());assertSame(marker,read(s,SESSION,"visible"));
        assertEquals(1,((DhConnectionState)read(null,DhClientSession.class,"connectionState")).loadedAt(DIMENSION).size());
    }
    @Test void changingLiveUpdatesAlonePreservesLoadDebounceAndHeldReceipts() throws Exception {
        var s=updateSession();write(s,SESSION,"announced",true);invoke(s,"sendPreferences");
        var marker=Set.of(POSITION);write(s,SESSION,"visible",marker);write(s,SESSION,"visibleCenter",new ChunkPos(2,2));
        ((DhConnectionState)read(null,DhClientSession.class,"connectionState")).loadedAt(DIMENSION).put(POSITION,System.nanoTime());policy(s).accepted(POSITION,1,1,123,0);
        setting(Map.of("lod.dh.real_time_updates",false));invoke(s,"tick");
        assertSame(marker,read(s,SESSION,"visible"));assertFalse(policy(s).due(POSITION,Long.MAX_VALUE));
        assertEquals(1,((DhConnectionState)read(null,DhClientSession.class,"connectionState")).loadedAt(DIMENSION).size());
    }
    @Test void dhNetTraceQueueBytesExcludeOtherOwnedDecodeAndCacheMemory() throws Exception {
        var s=updateSession();byte[] bytes=codec(s).encode(new DhMessage.Response(700,null,List.of()));
        try(var extra=budget(s).reserve(1_048_576);var frame=capture(bytes,System.nanoTime())) {
            var trace=ClientTraceLog.toggle();DhClientSession.tick();ClientTraceLog.stop();
            var row=Files.readAllLines(trace.path()).stream().map(line->com.google.gson.JsonParser.parseString(line).getAsJsonObject())
                    .filter(r->r.get("t").getAsString().equals("net")).findFirst().orElseThrow();
            assertEquals(bytes.length,row.get("qb").getAsLong());
        }
    }
    @Test void dhNetTraceUsesItsOwnCountersAndResetsAcrossSourceAndWorldChanges() throws Exception {
        var s=updateSession();var trace=ClientTraceLog.toggle();DhClientSession.tick();
        assertEquals(ClientNetTrace.Source.DH,read(null,ClientNetTrace.class,"lastSource"));
        write(null,ClientNetTrace.class,"nextEmitMs",0L);write(null,ClientNetTrace.class,"lastSampleMs",System.currentTimeMillis()-1000);
        ClientNetTrace.maybeEmit(minecraft,minecraft.player,level,8,1,200,3,4,-1,-1,-1,-1,ClientNetTrace.Source.DH,s,5000,-1);
        ClientNetTrace.maybeEmit(minecraft,minecraft.player,level,8,1,200,3,4,0,0,0,0,ClientNetTrace.Source.NATIVE_LSS,connection,9000,30000);
        ClientTraceLog.stop();assertNull(read(null,ClientNetTrace.class,"lastOwner"));assertNull(read(null,ClientNetTrace.class,"lastLevel"));
        var rows=Files.readAllLines(trace.path()).stream().map(line->com.google.gson.JsonParser.parseString(line).getAsJsonObject())
                .filter(r->r.get("t").getAsString().equals("net")).toList();assertEquals(3,rows.size());
        assertEquals(-1,rows.getFirst().get("wire_bps").getAsLong());assertTrue(rows.get(1).get("wire_bps").getAsLong()>0);
        assertEquals(-1,rows.get(1).get("raw_bps").getAsLong());assertEquals(-1,rows.get(1).get("gov").getAsLong());
        assertEquals(-1,rows.getLast().get("wire_bps").getAsLong());
        for(String key:List.of("ping","dcpt","runway","runway_s","miss_view","loaded","fps","spd"))assertTrue(rows.getFirst().has(key));
        assertEquals(-1,ClientNetTrace.rate(-1,0,1000));assertEquals(-1,ClientNetTrace.rate(2,4,1000));
        verify(connection,times(3)).send(any(net.minecraft.network.protocol.Packet.class));
        ClientNetTrace.maybeEmit(minecraft,minecraft.player,level,8,0,0,0,0,0,0,0,0,ClientNetTrace.Source.DH,s,20000,-1);
        verify(connection,times(3)).send(any(net.minecraft.network.protocol.Packet.class));
    }

    @Test void traceStartsWithAFlushedRecordEvenWhenNoTerrainEventsArrive() throws Exception {
        var toggle=ClientTraceLog.toggle();assertNotNull(toggle.path());assertFalse(toggle.failed());
        var rows=Files.readAllLines(toggle.path());assertEquals(1,rows.size());
        var row=com.google.gson.JsonParser.parseString(rows.getFirst()).getAsJsonObject();
        assertEquals("trace_start",row.get("t").getAsString());assertEquals(0,row.get("ms").getAsLong());
        ClientTraceLog.stop();assertFalse(ClientTraceLog.enabled());
    }
    @Test void dhTraceSamplesWhileWaitingWithoutCreatingTheNativeRequestManager() throws Exception {
        var s=session();var trace=ClientTraceLog.toggle();
        DhClientSession.tick();ClientTraceLog.stop();
        var rows=Files.readAllLines(trace.path()).stream().map(line->com.google.gson.JsonParser.parseString(line).getAsJsonObject()).toList();
        var snapshot=rows.stream().filter(r->r.get("t").getAsString().equals("dh_status")).findFirst().orElseThrow();
        assertEquals("CONNECTING",snapshot.get("state").getAsString());assertEquals(0,snapshot.get("inflight").getAsInt());
        assertEquals("WAITING_SERVER",snapshot.get("activity").getAsString());assertFalse(snapshot.has("acquisition_gates"));
        assertTrue(snapshot.get("chunk").isJsonNull());assertFalse(snapshot.has("server"));assertFalse(snapshot.has("world_key"));
    }
    @Test void dhTraceCadenceResetsOnRestartAndStopsWhenDisabled() throws Exception {
        assertFalse(DhClientTrace.sampleDue(100));
        ClientTraceLog.toggle();
        assertTrue(DhClientTrace.sampleDue(100));assertFalse(DhClientTrace.sampleDue(999_999_999));
        assertTrue(DhClientTrace.sampleDue(1_000_000_100));
        ClientTraceLog.stop();assertFalse(DhClientTrace.sampleDue(9_000_000_000L));
        ClientTraceLog.toggle();assertTrue(DhClientTrace.sampleDue(1_000_000_101));
    }
    @Test void dhTraceRecordsActualSentRequestAndOwnedReplyWithMatchingIdentifiers() throws Exception {
        var s=session();configureFrontier(s);var trace=ClientTraceLog.toggle();
        invoke(s,"request",new Class<?>[]{DhPosition.class,int.class,boolean.class,long.class},POSITION,0,false,System.nanoTime());
        pumpUntil(()->!sent.isEmpty(),"trace request was not sent");
        var request=sent.stream().map(bytes->{try{return codec(s).decode(bytes);}catch(Exception e){throw new AssertionError(e);}})
                .filter(DhMessage.Request.class::isInstance).map(DhMessage.Request.class::cast).findFirst().orElseThrow();
        capture(codec(s).encode(new DhMessage.Response(request.requestId(),null,List.of())),System.nanoTime()).receive();
        pumpUntil(()->pendingMap(s).isEmpty()&&frameCount(s)==0,"trace response did not finish");ClientTraceLog.stop();
        var rows=Files.readAllLines(trace.path()).stream().map(line->com.google.gson.JsonParser.parseString(line).getAsJsonObject()).toList();
        var outgoing=rows.stream().filter(r->r.get("t").getAsString().equals("dh_request")).findFirst().orElseThrow();
        var incoming=rows.stream().filter(r->r.get("t").getAsString().equals("dh_reply")).findFirst().orElseThrow();
        assertEquals(request.requestId(),outgoing.get("id").getAsInt());assertEquals(outgoing.get("id"),incoming.get("id"));
        assertEquals(outgoing.get("tile"),incoming.get("tile"));assertFalse(incoming.get("payload").getAsBoolean());
        assertTrue(incoming.get("age_ms").getAsLong()>=0);
    }
    @Test void dhTraceReportsRawQueueLoss() throws Exception {
        var s=session();var trace=ClientTraceLog.toggle();var frames=new ArrayList<DhClientSession.Inbound>();
        try {
            for(int i=0;i<24;i++)frames.add(capture(codec(s).encode(new DhMessage.Split(i,new byte[8],true)),System.nanoTime()));
            assertNull(capture(codec(s).encode(new DhMessage.Split(100,new byte[8],true)),System.nanoTime()));
            ClientTraceLog.stop();
            var reasons=Files.readAllLines(trace.path()).stream().map(line->com.google.gson.JsonParser.parseString(line).getAsJsonObject())
                    .filter(r->r.get("t").getAsString().equals("dh_pressure")).map(r->r.get("reason").getAsString()).toList();
            assertEquals(List.of("INBOUND_QUEUE"),reasons);
        } finally {frames.forEach(DhClientSession.Inbound::close);}
    }
    @Test void dhTraceRefusalCannotWriteUntrustedServerText() throws Exception {
        var s=session();var trace=ClientTraceLog.toggle();
        pending(s,77,false,System.nanoTime());
        capture(codec(s).encode(new DhMessage.Refusal(77,0,"private-world-key\"\nspoofed-row")),System.nanoTime()).receive();
        pumpUntil(()->frameCount(s)==0&&pendingMap(s).isEmpty(),"refusal trace did not finish");ClientTraceLog.stop();
        var text=Files.readString(trace.path());assertFalse(text.contains("private-world-key"));assertFalse(text.contains("spoofed-row"));
        var row=Files.readAllLines(trace.path()).stream().map(line->com.google.gson.JsonParser.parseString(line).getAsJsonObject())
                .filter(r->r.get("t").getAsString().equals("dh_refusal")).findFirst().orElseThrow();
        assertEquals(77,row.get("id").getAsInt());assertEquals(0,row.get("reason").getAsInt());
    }

    @Test void nativeAdvertisementDoesNotLoseEarlierVanillaAuthorityWhileFallbackIsPermitted() throws Exception {
        DhClientSession.installNativeAvailability(()->true);DhClientSession.vanillaLoaded(level,40,-40);
        DhClientSession.installNativeAvailability(()->false);var owner=session();
        assertFalse(policy(owner).eligible(40,-40));
    }
    @Test void aCancelledQueuedRequestNeverRegistersAnOrphanAssemblyOwner() throws Exception {
        var owner=session();var hold=new CountDownLatch(1);var entered=new CountDownLatch(1);
        worker(owner).execute(()->{entered.countDown();try{hold.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        try {invoke(owner,"request",new Class<?>[]{DhPosition.class,int.class,boolean.class,long.class},POSITION,0,false,System.nanoTime());pendingMap(owner).clear();}
        finally {hold.countDown();}
        waitWorker(owner);while(!mainTasks.isEmpty())mainTasks.poll().run();
        assertTrue(((Map<?,?>)read(assembly(owner),DhAssembly.class,"requests")).isEmpty());assertTrue(sent.isEmpty());assertTrue(active(owner));
    }
    @Test void nativeAdvertisementWinsAndLateOfferRetiresDhWithoutTerrain() throws Exception {
        assertTrue(DhClientSession.selected());
        DhClientSession.installNativeAvailability(()->true);
        assertFalse(DhClientSession.selected());
        DhClientSession.installNativeAvailability(()->false);
        var owner=session();
        DhClientSession.nativeOffer();
        assertFalse(DhClientSession.selected());assertFalse(active(owner));
        assertNull(read(null,DhClientSession.class,"session"));
        assertEquals(0,budget(owner).used());assertTrue(sent.isEmpty());
        // A disabled or unsupported native offer must not be bypassed via DH.
        assertFalse(DhClientSession.selected());DhClientSession.tick();
        assertTrue(DhClientSession.status().contains("DH fallback suppressed"));
        DhClientSession.onJoin();assertTrue(DhClientSession.selected());
    }
    @Test void aFreshClientWithNoSourceSettingSelectsDhWhenNoNativeServiceIsOffered() throws Exception {
        assertFalse(Files.exists(dir.resolve("lss-client-experimental.yaml")),"no sidecar is written for the default");
        assertEquals("distant_horizons",settings.state().effective().lod().source());
        assertTrue(DhClientSession.fallbackEnabled());assertTrue(DhClientSession.selected());
        assertFalse(dev.vox.lss.config.menu.MenuContext.current().nativeTerrain(),"a DH-selected connection reads DH in the menu");
        var player=minecraft.player;minecraft.player=null;
        assertTrue(dev.vox.lss.config.menu.MenuContext.current().nativeTerrain(),"no connection (title screen) never claims DH is in use");
        minecraft.player=player;
        DhClientSession.installNativeAvailability(()->true);
        assertFalse(DhClientSession.selected(),"native LSS still wins with the default on");
        assertTrue(dev.vox.lss.config.menu.MenuContext.current().nativeTerrain());
        DhClientSession.installNativeAvailability(()->false);
        settings.store().saveDraft(settings.store().read().hash(),Map.of("lod.receive",false));
        settings.commit(settings.prepareReload());
        DhClientSession.tick();
        assertFalse(DhClientSession.running(),"reception off keeps the default DH source idle");assertTrue(sent.isEmpty());
    }
    @Test void disablingDhPermissionLeavesOnlyNativeDiscovery() throws Exception {
        settings.store().saveDraft(settings.store().read().hash(),Map.of("lod.source","lss"));
        settings.commit(settings.prepareReload());
        assertFalse(DhClientSession.fallbackEnabled());assertFalse(DhClientSession.selected());
        assertTrue(sent.isEmpty());
    }
    @Test void anUnavailableNativeCapabilityCheckDefersDhRatherThanChoosingIt() {
        DhClientSession.installNativeAvailability(()->{throw new IllegalStateException("not ready");});
        assertFalse(DhClientSession.selected());
    }
    @Test void capturedAndQueuedConfigurationSurvivesALateClientTick() throws Exception {
        var s=session();write(s,SESSION,"announced",true);write(s,SESSION,"sentAt",System.nanoTime()-16_000_000_000L);
        var cfg=new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,true,32,false,0,0,500);
        var inbound=capture(codec(s).encode(cfg),System.nanoTime());
        invoke(s,"tick");invoke(s,"tick");invoke(s,"tick");
        assertTrue(active(s),"A quiet server never schedules a reconnect or stops the session");
        inbound.receive();pumpUntil(()->read(s,SESSION,"config")!=null && frameCount(s)==0,"queued config not applied");
        assertTrue(active(s));assertSame(connection,minecraft.getConnection());
    }
    @Test void stationaryOfficialAbsenceStopsPollingWithRefreshOff() throws Exception { stationaryAbsence(0); }

    @Test void resetSweepFailureRetiresTheSessionAndReportsStoppedWithoutClearingReceipts() throws Exception {
        var s=session();policy(s).accepted(POSITION,1,1,123,System.nanoTime());
        var blocked=Files.createDirectories(ColumnCacheStore.cacheRoot().resolve(cacheComponent).resolve("foreign-child"));
        try {assertThrows(IllegalStateException.class,DhClientSession::drainForReset);}
        finally {Files.delete(blocked);Files.deleteIfExists(blocked.getParent());}
        assertFalse(active(s));assertNull(read(null,DhClientSession.class,"session"));
        assertEquals(123,policy(s).timestamp(POSITION));assertEquals(0,budget(s).used());
        assertTrue(DhClientSession.status().startsWith("DH reset failed:"));
        assertEquals(dev.vox.lss.common.diagnostics.DhStatusSnapshot.State.STOPPED,DhClientSession.diagnostics().state());
    }

    @Test void singlePlayerResetDoesNotRequireARemoteServerAddress() throws Exception {
        var s=session();minecraft.server=null;
        assertDoesNotThrow(DhClientSession::drainForReset);assertFalse(active(s));
        DhClientSession.resumeAfterReset();assertNull(read(null,DhClientSession.class,"session"));
    }








    @Test void acceptedRefreshesRequireServerSynchronizationBeforeSelection() throws Exception {
        long origin=System.nanoTime();var s=refreshFrontier(origin);
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,false,8,50,500));
        assertNull(scan(s,origin));
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,true,8,0,500));
        assertNull(scan(s,origin));
    }

    private Object refreshFrontier(long origin) throws Exception {
        settings.store().saveDraft(settings.store().read().hash(),Map.of("lod.dh.sync_on_load",true));settings.commit(settings.prepareReload());
        var s=session();configureFrontier(s);invoke(s,"tick");
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)
            { var p=new DhPosition(6,x,z);policy(s).accepted(p,1,1,123,origin);policy(s).revalidate(p,origin); }
        return s;
    }
    private void configureFrontier(Object s) {
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,true,8,50,500));
        write(s,SESSION,"levelKey","test-world");
    }
    private DhMessage.Request scan(Object s,long now) throws Exception {
        int before=sent.size();invoke(s,"requestNext",new Class<?>[]{long.class,ChunkPos.class},now,new ChunkPos(2,2));
        waitWorker(s);Runnable task;while((task=mainTasks.poll())!=null)task.run();
        return sent.size()==before?null:(DhMessage.Request)codec(s).decode(sent.getLast());
    }
    @Test void aConnectionAuthorityBlockReportsReconnectRequiredWithoutBlamingVoxy() {
        var state=(DhConnectionState)read(null,DhClientSession.class,"connectionState");
        for(int i=0;i<16;i++)state.policy("test:dimension_"+i);
        assertThrows(IllegalStateException.class,()->state.policy(DIMENSION));
        assertEquals(dev.vox.lss.common.diagnostics.DhStatusSnapshot.State.BLOCKED,DhClientSession.diagnostics().state());
    }

    @Test void stalledMainThreadAbortsOnlyItsTileAndNeverRunsTheLateHandoff() throws Exception {
        var s=session();var position=new DhPosition(6,-1,1);int id=2;
        doReturn(-64).when(level).getMinBuildHeight();doReturn(384).when(level).getHeight();
        var dimension=VanillaRegistries.createLookup().lookupOrThrow(Registries.DIMENSION_TYPE)
                .getOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD).value();
        doReturn(dimension).when(level).dimensionType();
        var ctor=PENDING.getDeclaredConstructor(DhPosition.class,boolean.class,long.class,long.class);ctor.setAccessible(true);
        pendingMap(s).put(id,ctor.newInstance(position,false,System.nanoTime(),0L));assembly(s).expect(id,position);
        for(int frame=4;frame<=6;frame++) {
            try(var bytes=getClass().getResourceAsStream(String.format("/dh-corpus/dh-official-3.3.3-neoforge-partial/received-%04d.bin",frame))) {
                assertNotNull(bytes);capture(bytes.readAllBytes(),System.nanoTime()).receive();
            }
        }
        var resetCounter=mainTasks.poll(10,TimeUnit.SECONDS);assertNotNull(resetCounter);resetCounter.run();
        var held=mainTasks.poll(10,TimeUnit.SECONDS);assertNotNull(held,"conversion did not reach its main-thread check");
        // Deliberately do not pump the client queue until the real five-second
        // conversion timeout has elapsed; the worker must survive this hitch.
        waitWorker(s);assertTrue(active(s));held.run();
        pumpUntil(()->pendingMap(s).isEmpty()&&frameCount(s)==0,"stalled tile was not cooled down");
        assertFalse(policy(s).due(position,System.nanoTime()));assertTrue(policy(s).due(position,System.nanoTime()+31_000_000_000L));
        assertEquals(0,((java.util.concurrent.atomic.AtomicLong)read(s,SESSION,"columns")).get());
        assertEquals(0,budget(s).used());assertTrue(active(s));
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,true,8,1,500));
        write(s,SESSION,"levelKey","test-world");
        for(int x=-3;x<=1;x++)for(int z=-1;z<=3;z++)if(!position.equals(new DhPosition(6,x,z)))
            policy(s).accepted(new DhPosition(6,x,z),1,1,1,System.nanoTime());
        invoke(s,"requestNext",new Class<?>[]{long.class,ChunkPos.class},System.nanoTime()+31_000_000_000L,new ChunkPos(-2,6));
        pumpUntil(()->!sent.isEmpty(),"stalled tile was not re-requested after cooldown");
        assertEquals(position,((DhMessage.Request)codec(s).decode(sent.getFirst())).position());
    }

    @Test void inboundQueueCapRetiresAndReleasesAllUnreceivedFrames() throws Exception {
        var s=session();var frames=new ArrayList<DhClientSession.Inbound>();
        for(int i=0;i<32;i++) frames.add(capture(new byte[]{0},System.nanoTime()));
        assertTrue(frames.stream().allMatch(Objects::nonNull));
        assertEquals(32L*257,budget(s).used());
        assertNull(capture(new byte[]{0},System.nanoTime()));
        assertFalse(active(s));
        pumpUntil(()->budget(s).used()==0,"terminal queue-cap retirement did not release its frames");
        assertEquals(0,frameCount(s));assertTrue(worker(s).isTerminated());
        for(var frame:frames) {frame.receive();frame.close();}
        assertEquals(0,budget(s).used(),"a delayed loader callback cannot reacquire a retired frame");
        assertSame(connection,minecraft.getConnection());assertSame(level,minecraft.level);
    }

    @Test void oversizedFrameIsConsumedWithoutAllocatingAnOwnedCopy() throws Exception {
        var s=session();var buffer=new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[DhLimits.DEFAULT.frameBytes()+1]));
        try {
            assertNull(DhClientSession.capture(buffer));assertEquals(0,buffer.readableBytes());
            assertEquals(0,budget(s).used());assertFalse(active(s));
        } finally {buffer.release();}
        pumpUntil(()->worker(s).isTerminated(),"oversized-frame retirement did not terminate");
    }

    @Test void retiredEpochCannotBindAnOldLevelInitAfterItsWorkerQueuedTheCallback() throws Exception {
        var old=session();
        capture(codec(old).encode(new DhMessage.LevelInit(DIMENSION,"old-server","old-world",0)),System.nanoTime()).receive();
        var obsolete=mainTasks.poll(10,TimeUnit.SECONDS);
        assertNotNull(obsolete,"old decode worker never queued its world-key bind");
        var replacement=session();
        assertTrue((boolean)invoke(old,"retire"));
        obsolete.run();
        assertEquals(0,budget(old).used());assertEquals(0,frameCount(old));
        assertNull(read(replacement,SESSION,"levelKey"));assertTrue(active(replacement));
        var state=(DhConnectionState)read(null,DhClientSession.class,"connectionState");
        assertTrue(((Map<?,?>)read(state,DhConnectionState.class,"worldKeys")).isEmpty(),
                "an obsolete epoch callback must not claim the replacement's logical world");
    }

    @Test void resetDrainsFragmentsAndQueuedFramesAndResetsReceiptsButRetainsVanillaAuthority() throws Exception {
        var s=session();var policy=policy(s);long now=System.nanoTime();
        DhClientSession.vanillaLoaded(level,40,-40);
        assertFalse(policy.eligible(40,-40));
        assertTrue(policy.accepted(POSITION,0xffff,0xffff,123,now));assertFalse(policy.due(POSITION,now));
        pending(s,7,false,now);assembly(s).accept(new DhMessage.Split(77,new byte[]{1,2,3},true),now);
        var queued=capture(new byte[]{0},now);assertNotNull(queued);
        var stamps=new Long2LongOpenHashMap();stamps.put(123,456);
        ColumnCacheStore.saveAsync(cacheComponent,Level.OVERWORLD,stamps);
        assertTrue(budget(s).used()>0);
        DhClientSession.drainForReset();
        assertEquals(0,budget(s).used());assertEquals(0,frameCount(s));
        assertEquals(0,assembly(s).incompleteBuffers());assertTrue(pendingMap(s).isEmpty());
        assertTrue(ColumnCacheStore.load(cacheComponent,Level.OVERWORLD).isEmpty(),
                "a successful reset must drain the queued native cache save before sweeping it");
        DhClientSession.resumeAfterReset();
        assertTrue(policy.due(POSITION,now));assertEquals(0,policy.timestamp(POSITION));
        var replacement=session();assertSame(policy,policy(replacement));
        assertFalse(policy(replacement).eligible(40,-40),"reset cannot let DH replace previously loaded vanilla terrain");
        queued.receive();queued.close();assertEquals(0,budget(s).used());
    }

    @Test void expiredResponseParksOnlyItsRequestWithoutFailingTheSession() throws Exception {
        transientResponse(true);
    }

    @Test void missingBufferResponseParksOnlyItsRequestWithoutFailingTheSession() throws Exception {
        transientResponse(false);
    }

    private void transientResponse(boolean expired) throws Exception {
        var s=session();long now=System.nanoTime();
        pending(s,1,false,now);pending(s,2,false,now);
        long arrival=expired?System.nanoTime()+DhLimits.DEFAULT.lifetimeNanos()+1:System.nanoTime();
        var response=new DhMessage.Response(1,expired?null:999,List.of());
        capture(codec(s).encode(response),arrival).receive();
        pumpUntil(()->!pendingMap(s).containsKey(1)&&frameCount(s)==0,"transient response was not parked");
        assertTrue(active(s));assertTrue(DhClientSession.running());assertEquals(1,pendingMap(s).size());
        assertTrue(pendingMap(s).containsKey(2));assertFalse(policy(s).due(POSITION,System.nanoTime()));
        assertEquals(0,budget(s).used());assertEquals(0,read(s,SESSION,"unanswered"));
        assertTrue(sent.isEmpty());
        var owners=(Map<?,?>)read(assembly(s),DhAssembly.class,"requests");
        assertFalse(owners.containsKey(1));assertTrue(owners.containsKey(2));
    }

    @Test void idleExpiredContinuationReleasesItsBufferAndFollowingResponseParksTheRequest() throws Exception {
        var s=session();long now=System.nanoTime();pending(s,1,false,now);
        capture(codec(s).encode(new DhMessage.Split(10,new byte[]{1,2,3},true)),now).receive();
        waitWorker(s);assertEquals(1,assembly(s).incompleteBuffers());assertTrue(budget(s).used()>0);
        long late=now+DhLimits.DEFAULT.idleNanos()+1;
        capture(codec(s).encode(new DhMessage.Split(10,new byte[]{4},false)),late).receive();
        waitWorker(s);assertTrue(active(s));assertEquals(0,assembly(s).incompleteBuffers());
        assertEquals(0,budget(s).used());assertTrue(pendingMap(s).containsKey(1));
        capture(codec(s).encode(new DhMessage.Response(1,10,List.of())),late+1).receive();
        pumpUntil(()->pendingMap(s).isEmpty()&&frameCount(s)==0,"missing expired buffer was not parked");
        assertTrue(active(s));assertFalse(policy(s).due(POSITION,System.nanoTime()));assertEquals(0,budget(s).used());
    }

    @Test void terminalFailureCancelsOfficialGenerationOnceOnTheCapturedConnection() throws Exception {
        var s=session();long now=System.nanoTime();pending(s,7,true,now);pending(s,8,false,now);
        invoke(s,"fail",new Class<?>[]{String.class,DhStatusSnapshot.Reason.class},"test terminal failure",DhStatusSnapshot.Reason.SOURCE_STOPPED);
        assertFalse(active(s));assertTrue(sent.isEmpty(),"cancel is sent by the scheduled retirement");
        pumpUntil(()->worker(s).isTerminated(),"terminal failure did not drain");
        assertEquals(1,sent.size());assertEquals(new DhMessage.Cancel(7),codec(s).decode(sent.getFirst()));
        assertTrue(pendingMap(s).isEmpty());assertEquals(0,budget(s).used());
        invoke(s,"retire");DhClientSession.disconnect();
        assertEquals(1,sent.size(),"repeat retirement and disconnect cannot duplicate the cancellation");
        assertSame(connection,minecraft.getConnection());assertSame(level,minecraft.level);
    }

    @Test void retirementDoesNotSendAnOldRequestCancelOnAReplacementConnection() throws Exception {
        var s=session();pending(s,7,true,System.nanoTime());
        var replacement=mcMock(ClientPacketListener.class);write(minecraft.player,LocalPlayer.class,"connection",replacement);
        invoke(s,"fail",new Class<?>[]{String.class,DhStatusSnapshot.Reason.class},"connection replacement",DhStatusSnapshot.Reason.SOURCE_STOPPED);
        pumpUntil(()->worker(s).isTerminated(),"replacement-connection retirement did not drain");
        assertTrue(sent.isEmpty());assertTrue(pendingMap(s).isEmpty());assertEquals(0,budget(s).used());
        assertSame(replacement,minecraft.getConnection());
    }

    @Test void readinessWaitsForWorldAndResolvedServiceButAllowsAnIngestOnlyAbi() throws Exception {
        Class<?> bridge=Class.forName("dev.vox.lss.compat.VoxyCompat");
        var fields=new LinkedHashMap<String,Object>();
        for(String name:List.of("classResolver","consumerRegistrar","worldIdentifierOf","rawIngest",
                "voxyGetInstance","getIngestService","getTaskCount")) fields.put(name,read(null,bridge,name));
        Object previousAvailable=read(null,ModCompat.class,"voxyLoaded");
        var dead=(java.util.concurrent.atomic.AtomicBoolean)read(null,bridge,"dhBridgeDead");
        boolean previousDead=dead.get();var previousWorld=WorldIdentifier.returned;var previousInstance=VoxyCommon.instance;
        try {
            Class<?> resolver=inner(bridge,"ClassResolver");
            write(null,bridge,"classResolver",Proxy.newProxyInstance(resolver.getClassLoader(),new Class<?>[]{resolver},
                    (proxy,method,args)->Class.forName((String)args[0])));
            write(null,bridge,"consumerRegistrar",(java.util.function.Consumer<Object>)consumer->{});
            var init=bridge.getDeclaredMethod("init");init.setAccessible(true);assertEquals(true,init.invoke(null));
            write(null,ModCompat.class,"voxyLoaded",true);dead.set(false);
            var s=session();
            write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.SURFACE_THEN_CHUNKS,
                    32,0,0,0,1,false,0,true,32,1,500));
            write(s,SESSION,"levelKey","test-world");write(s,SESSION,"announced",true);
            write(s,SESSION,"sentAt",System.nanoTime());
            var stamps=new Long2LongOpenHashMap();stamps.put(123,456);
            ColumnCacheStore.save(cacheComponent,Level.OVERWORLD,stamps);
            WorldIdentifier.returned=null;VoxyCommon.instance=new VoxyInstance();
            assertFalse(DhVoxyBridge.ready(level));invoke(s,"tick");
            assertEquals(false,read(s,SESSION,"cacheInvalidated"));
            assertEquals(456,ColumnCacheStore.load(cacheComponent,Level.OVERWORLD).get(123));
            WorldIdentifier.returned=new WorldIdentifier();VoxyCommon.instance=null;
            assertFalse(DhVoxyBridge.ready(level));
            VoxyCommon.instance=new VoxyInstance();VoxyCommon.instance.ingestService=null;
            assertFalse(DhVoxyBridge.ready(level));
            VoxyCommon.instance=new VoxyInstance();assertTrue(DhVoxyBridge.ready(level));
            minecraft.player=null; // readiness/cache gating runs before player acquisition
            invoke(s,"tick");assertEquals(true,read(s,SESSION,"cacheInvalidated"));
            assertTrue(ColumnCacheStore.load(cacheComponent,Level.OVERWORLD).isEmpty());
            // Resolve the actual optional-probe failure path, rather than pretending a
            // missing task-count signal means that the static rawIngest ABI is unusable.
            write(null,bridge,"classResolver",Proxy.newProxyInstance(resolver.getClassLoader(),new Class<?>[]{resolver},
                    (proxy,method,args)->{
                        String name=(String)args[0];
                        if(name.equals("me.cortex.voxy.commonImpl.VoxyCommon")) throw new ClassNotFoundException(name);
                        return Class.forName(name);
                    }));
            assertEquals(true,init.invoke(null));assertEquals(-1,DhVoxyBridge.backlog());
            assertTrue(DhVoxyBridge.ready(level),"an initialized ingest-only ABI uses conservative pacing");
            WorldIdentifier.returned=null;assertFalse(DhVoxyBridge.ready(level));
        } finally {
            for(var entry:fields.entrySet()) write(null,bridge,entry.getKey(),entry.getValue());
            write(null,ModCompat.class,"voxyLoaded",previousAvailable);dead.set(previousDead);
            WorldIdentifier.returned=previousWorld;VoxyCommon.instance=previousInstance;
        }
    }

    // Captured upstream terrain passes through the actual session worker,
    // converter, main-thread authority gates, Voxy bridge and receipt policy.
    // The stock port's boolean is queue acceptance, never a completion oracle.
    @Test void capturedCompleteTerrainRequiresAcceptanceOfEveryEligibleColumn() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=deliverySession(-128,512);
            replayContent(s,31,false);
            assertEquals(512,me.cortex.voxy.common.world.service.VoxelIngestService.calls.size());
            assertEquals(16,counter(s,"columns"));assertEquals(1,counter(s,"tiles"));
            assertTrue(policy(s).syncTimestamp(CONTENT_POSITION)>0);
            assertEquals(0,budget(s).used());assertTrue(active(s));
            var first=me.cortex.voxy.common.world.service.VoxelIngestService.calls.getFirst();
            assertEquals(-12,first.chunkX());assertEquals(0,first.chunkZ());assertEquals(-8,first.sectionY());
        }
    }

    @Test void oneRejectedColumnCannotCreateACompleteCapturedTileReceiptAndFullRetryRecovers() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=deliverySession(-128,512);
            me.cortex.voxy.common.world.service.VoxelIngestService.behavior=call->call.chunkX()!=-12||call.chunkZ()!=0;
            replayContent(s,31,false);
            assertEquals(15,counter(s,"columns"));assertEquals(1,counter(s,"failedColumns"));
            assertEquals(0,counter(s,"tiles"));assertEquals(0,policy(s).syncTimestamp(CONTENT_POSITION));
            assertFalse(policy(s).hasSnapshot(CONTENT_POSITION));
            assertEquals(0,budget(s).used());assertTrue(active(s));
            me.cortex.voxy.common.world.service.VoxelIngestService.behavior=call->true;
            replayContent(s,32,false);
            assertEquals(1,counter(s,"tiles"));assertTrue(policy(s).syncTimestamp(CONTENT_POSITION)>0);
            assertEquals(0,budget(s).used());
        }
    }

    @Test void failedRefreshCannotReuseItsPreviousConditionalTimestamp() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=deliverySession(-128,512);replayContent(s,31,false);
            long stamp=policy(s).syncTimestamp(CONTENT_POSITION);assertTrue(stamp>0);
            me.cortex.voxy.common.world.service.VoxelIngestService.behavior=call->call.sectionY()!=21;
            replayContent(s,32,true);
            assertEquals(stamp,policy(s).timestamp(CONTENT_POSITION));
            assertEquals(0,policy(s).syncTimestamp(CONTENT_POSITION));
            assertFalse(policy(s).hasSnapshot(CONTENT_POSITION),"A failed refresh cannot keep its old conditional receipt");
            assertEquals(1,counter(s,"tiles"));assertEquals(16,counter(s,"failedColumns"));
            assertEquals(0,budget(s).used());
        }
    }

    @Test void partialCapturedCoverageNeverCreatesACompleteReceipt() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=deliverySession(-64,384);var p=new DhPosition(6,-1,1);
            pendingAt(s,2,p,0);
            for(int frame=4;frame<=6;frame++)capture(resource("dh-official-3.3.3-neoforge-partial",String.format("received-%04d.bin",frame)),System.nanoTime()).receive();
            settleDelivery(s);
            assertEquals(8,counter(s,"columns"));assertEquals(0,counter(s,"tiles"));
            assertEquals(0,policy(s).syncTimestamp(p));assertEquals(0,budget(s).used());
        }
    }

    @Test void vanillaAuthorityBeforeExpansionSkipsOnlyItsColumn() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=deliverySession(-128,512);DhClientSession.vanillaLoaded(level,-12,0);
            replayContent(s,31,false);
            assertEquals(15,counter(s,"columns"));assertEquals(1,counter(s,"skipped"));
            assertEquals(1,counter(s,"tiles"));
            assertTrue(me.cortex.voxy.common.world.service.VoxelIngestService.calls.stream()
                    .noneMatch(call->call.chunkX()==-12&&call.chunkZ()==0));
            assertEquals(0,budget(s).used());
        }
    }

    @Test void duplicateCompletedResponseCannotEnqueueTheCapturedTerrainTwice() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=deliverySession(-128,512);replayContent(s,31,false);
            long columns=counter(s,"columns"),stamp=policy(s).syncTimestamp(CONTENT_POSITION);
            capture(codec(s).encode(contentResponse(s,31,false)),System.nanoTime()).receive();
            pumpUntil(()->frameCount(s)==0,"duplicate response did not release");
            assertEquals(columns,counter(s,"columns"));assertEquals(stamp,policy(s).syncTimestamp(CONTENT_POSITION));
            assertEquals(1,counter(s,"tiles"));assertEquals(0,budget(s).used());assertTrue(active(s));
        }
    }

    @Test void threeWhollyRejectedTilesStopDhWithoutDisconnectingMinecraft() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=deliverySession(-128,512);
            me.cortex.voxy.common.world.service.VoxelIngestService.behavior=call->false;
            for(int id=31;id<=33;id++)replayContent(s,id,false);
            pumpUntil(()->worker(s).isTerminated(),"rejection retirement did not drain");
            assertFalse(active(s));assertEquals(0,counter(s,"tiles"));assertEquals(0,budget(s).used());
            assertEquals(0,policy(s).syncTimestamp(CONTENT_POSITION));
            assertSame(connection,minecraft.getConnection());assertSame(level,minecraft.level);
        }
    }

    @Test void refusalReleasesOnlyItsOwnedRequestAndNeverAcceptsTerrain() throws Exception {
        var s=session();long now=System.nanoTime();pending(s,1,false,now);pending(s,2,false,now);
        capture(codec(s).encode(new DhMessage.Refusal(1,1,"fixture refusal")),now).receive();
        pumpUntil(()->!pendingMap(s).containsKey(1)&&frameCount(s)==0,"refusal was not released");
        assertTrue(pendingMap(s).containsKey(2));assertEquals(1,counter(s,"refused"));
        assertEquals(0,counter(s,"tiles"));assertEquals(0,budget(s).used());assertTrue(active(s));
    }

    @Test void changedWorldKeyStopsBeforeReusingReceiptsOnTheSameConnection() throws Exception {
        var s=session();write(s,SESSION,"levelKey","old-world");
        var state=(DhConnectionState)read(null,DhClientSession.class,"connectionState");assertTrue(state.bindWorld(DIMENSION,"old-world"));
        policy(s).accepted(POSITION,1,1,123,System.nanoTime());
        capture(codec(s).encode(new DhMessage.LevelInit(DIMENSION,"fixture","new-world",0)),System.nanoTime()).receive();
        pumpUntil(()->!active(s)&&budget(s).used()==0,"world replacement did not retire");
        assertEquals("old-world",read(s,SESSION,"levelKey"));assertSame(connection,minecraft.getConnection());
        assertEquals(123,policy(s).timestamp(POSITION));
    }

    @Test void slowRetiredWorkerPreventsResetFromClearingUnderItsLateWork() throws Exception {
        var old=session();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        worker(old).submit(()-> {entered.countDown();while(release.getCount()>0)try {release.await();}catch(InterruptedException ignored) {}});
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        try {
            assertEquals(false,invoke(old,"retire"));var replacement=session();
            assertThrows(IllegalStateException.class,DhClientSession::drainForReset);
            assertTrue(active(replacement),"Reset must abort before touching the new owner");
        } finally {release.countDown();assertTrue(worker(old).awaitTermination(5,TimeUnit.SECONDS));invoke(old,"retire");}
    }
    public static final class DestinationIdentifier {
        static final DestinationEngine engine=new DestinationEngine();
        public static DestinationIdentifier of(Level level){return new DestinationIdentifier();}
        public DestinationEngine getOrCreateEngine(){return engine;}
    }
    public static final class DestinationEngine {
        static boolean defaults,observedIngest;int refs;
        public void acquireRef(){refs++;}public void releaseRef(){refs--;}
        public DestinationMapper getMapper(){return new DestinationMapper();}
        public DestinationSection acquire(int l,int x,int y,int z){return new DestinationSection(x,y,z);}
    }
    public static final class DestinationSection {
        final int sx,sy,sz;
        DestinationSection(int x,int y,int z){sx=x;sy=y;sz=z;}
        public long[] copyData(){
            var data=new long[32768];Arrays.fill(data,DestinationEngine.defaults?15L<<56:1L<<27);
            if(DestinationEngine.observedIngest) {
                synchronized(me.cortex.voxy.common.world.service.VoxelIngestService.committed) {
                    for(var call:me.cortex.voxy.common.world.service.VoxelIngestService.committed) {
                        if((call.chunkX()>>1)!=sx || (call.sectionY()>>1)!=sy || (call.chunkZ()>>1)!=sz)continue;
                        for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(int y=0;y<16;y++) {
                            var state=call.section().getBlockState(x,y,z);
                            int block=state.isAir()?0:net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId(state);
                            int biome=biomes.getId(call.section().getNoiseBiome(x>>2,y>>2,z>>2).value());
                            int light=call.skyLight().get(x,y,z)|(call.blockLight().get(x,y,z)<<4);
                            data[(((call.sectionY()&1)*16+y)<<10)|(((call.chunkZ()&1)*16+z)<<5)|((call.chunkX()&1)*16+x)]
                                    =((long)block<<27)|((long)biome<<47)|((long)light<<56);
                        }
                    }
                }
            }
            return data;
        }
        public int release(){return 0;}
    }
    public static final class DestinationMapper {
        public DestinationState[] getStateEntries(){
            if(DestinationEngine.observedIngest)return java.util.stream.StreamSupport.stream(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.spliterator(),false).map(DestinationState::new).toArray(DestinationState[]::new);
            return new DestinationState[]{new DestinationState(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()),new DestinationState(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())};
        }
        public DestinationBiome[] getBiomeEntries(){
            if(DestinationEngine.observedIngest) {
                var entries=new DestinationBiome[biomes.size()];
                for(int i=0;i<entries.length;i++)entries[i]=new DestinationBiome(biomes.getKey(biomes.byId(i)).toString());
                return entries;
            }
            return new DestinationBiome[]{new DestinationBiome("minecraft:plains")};
        }
        public static int getBlockId(long value){return (int)(value>>>27&0xfffff);}
        public static int getBiomeId(long value){return (int)(value>>>47&511);}
        public static int getLightId(long value){return (int)(value>>>56&255);}
    }
    public static final class DestinationState {public final net.minecraft.world.level.block.state.BlockState state;DestinationState(net.minecraft.world.level.block.state.BlockState state){this.state=state;}}
    public static final class DestinationBiome {public final String biome;DestinationBiome(String biome){this.biome=biome;}}
    private static final DhPosition CONTENT_POSITION=new DhPosition(6,-3,0);
    private Object deliverySession(int minY,int height) throws Exception {
        level=mcMock(ClientLevel.class);
        minecraft.level=level;
        doReturn(Level.OVERWORLD).when(level).dimension();
        doReturn(new RegistryAccess.ImmutableRegistryAccess(List.of(biomes)).freeze()).when(level).registryAccess();
        doReturn(minY).when(level).getMinBuildHeight();doReturn(height).when(level).getHeight();
        var dimension=VanillaRegistries.createLookup().lookupOrThrow(Registries.DIMENSION_TYPE)
                .getOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD).value();
        doReturn(dimension).when(level).dimensionType();
        var cache=new ClientChunkCache(level,2);
        doReturn(cache).when(level).getChunkSource();
        var s=session();
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,true,12,1,500));
        write(s,SESSION,"levelKey","fixture-world");
        return s;
    }
    private void pendingAt(Object s,int id,DhPosition position,long stamp) throws Exception {
        pendingMap(s).put(id,newPending(s,position,false,System.nanoTime(),stamp));assembly(s).expect(id,position);
    }
    private static byte[] resource(String corpus,String name) throws Exception {
        try(var stream=DhClientSessionTest.class.getResourceAsStream("/dh-corpus/"+corpus+"/"+name)) {
            assertNotNull(stream);return stream.readAllBytes();
        }
    }
    private DhMessage.Response contentResponse(Object s,int id,boolean removal) throws Exception {
        var response=(DhMessage.Response)codec(s).decode(resource("dh-official-3.3.3-neoforge-content",removal?"target-0008-response.bin":"target-0003-response.bin"));
        return new DhMessage.Response(id,response.bufferId(),response.beacons());
    }
    private void startContent(Object s,int id,boolean removal) throws Exception {
        pendingAt(s,id,CONTENT_POSITION,removal?policy(s).syncTimestamp(CONTENT_POSITION):0);
        capture(resource("dh-official-3.3.3-neoforge-content",removal?"target-0007-split.bin":"target-0002-split.bin"),System.nanoTime()).receive();
        capture(codec(s).encode(contentResponse(s,id,removal)),System.nanoTime()).receive();
    }
    private void settleDelivery(Object s) throws Exception {
        pumpUntil(()->pendingMap(s).isEmpty()&&frameCount(s)==0,"captured session delivery did not settle");
    }
    private void replayContent(Object s,int id,boolean removal) throws Exception {startContent(s,id,removal);settleDelivery(s);}
    private Runnable takeMain() throws Exception {var task=mainTasks.poll(10,TimeUnit.SECONDS);assertNotNull(task);return task;}
    private static long counter(Object s,String name) {return ((java.util.concurrent.atomic.AtomicLong)read(s,SESSION,name)).get();}

    private final class ReadyBridge implements AutoCloseable {
        final DeliveryBridge ingest=new DeliveryBridge();
        final Map<String,Object> probes=new LinkedHashMap<>();
        final Object available=read(null,ModCompat.class,"voxyLoaded");
        final VoxyInstance instance=VoxyCommon.instance;
        ReadyBridge() throws Exception {
            for(String name:List.of("voxyGetInstance","getIngestService","getTaskCount"))probes.put(name,read(null,ingest.bridge,name));
            var lookup=MethodHandles.lookup();
            write(null,ModCompat.class,"voxyLoaded",true);VoxyCommon.instance=new VoxyInstance();
            write(null,ingest.bridge,"voxyGetInstance",lookup.findStatic(VoxyCommon.class,"getInstance",MethodType.methodType(VoxyInstance.class)).asType(MethodType.methodType(Object.class)));
            write(null,ingest.bridge,"getIngestService",lookup.findVirtual(VoxyInstance.class,"getIngestService",MethodType.methodType(me.cortex.voxy.common.world.service.VoxelIngestService.class)).asType(MethodType.methodType(Object.class,Object.class)));
            write(null,ingest.bridge,"getTaskCount",lookup.findVirtual(me.cortex.voxy.common.world.service.VoxelIngestService.class,"getTaskCount",MethodType.methodType(int.class)).asType(MethodType.methodType(int.class,Object.class)));
        }
        public void close() {
            probes.forEach((name,value)->write(null,ingest.bridge,name,value));
            VoxyCommon.instance=instance;write(null,ModCompat.class,"voxyLoaded",available);ingest.close();
        }
    }
    private final class DeliveryBridge implements AutoCloseable {
        final Class<?> bridge=Class.forName("dev.vox.lss.compat.VoxyCompat");
        final Map<String,Object> before=new LinkedHashMap<>();
        final WorldIdentifier world=WorldIdentifier.returned;
        final java.util.concurrent.atomic.AtomicBoolean dead=(java.util.concurrent.atomic.AtomicBoolean)read(null,bridge,"dhBridgeDead");
        final boolean wasDead=dead.get();
        DeliveryBridge() throws Exception {
            for(String name:List.of("worldIdentifierOf","rawIngest"))before.put(name,read(null,bridge,name));
            var lookup=MethodHandles.lookup();
            write(null,bridge,"worldIdentifierOf",lookup.findStatic(WorldIdentifier.class,"of",MethodType.methodType(WorldIdentifier.class,Level.class)));
            write(null,bridge,"rawIngest",lookup.findStatic(me.cortex.voxy.common.world.service.VoxelIngestService.class,"rawIngest",
                    MethodType.methodType(boolean.class,WorldIdentifier.class,net.minecraft.world.level.chunk.LevelChunkSection.class,
                            int.class,int.class,int.class,net.minecraft.world.level.chunk.DataLayer.class,net.minecraft.world.level.chunk.DataLayer.class)));
            dead.set(false);WorldIdentifier.returned=new WorldIdentifier();me.cortex.voxy.common.world.service.VoxelIngestService.reset();
        }
        public void close() {
            for(var entry:before.entrySet())write(null,bridge,entry.getKey(),entry.getValue());
            WorldIdentifier.returned=world;dead.set(wasDead);me.cortex.voxy.common.world.service.VoxelIngestService.reset();
        }
    }

    @Test void loadedTerrainIsCheckedOnEntryButTimeAloneNeverStartsAnotherRequest() throws Exception {
        var s=session();configureFrontier(s);long now=System.nanoTime();
        policy(s).accepted(new DhPosition(6,0,0),1,1,123,now);
        invoke(s,"observeLoaded",new Class<?>[]{long.class,ChunkPos.class},now,new ChunkPos(2,2));
        assertTrue(policy(s).due(new DhPosition(6,0,0),now));
        assertEquals(123,policy(s).syncTimestamp(new DhPosition(6,0,0)));assertTrue(policy(s).accepted(new DhPosition(6,0,0),1,1,123,now));
        invoke(s,"observeLoaded",new Class<?>[]{long.class,ChunkPos.class},now+30*60_000_000_000L,new ChunkPos(2,2));
        assertFalse(policy(s).due(new DhPosition(6,0,0),Long.MAX_VALUE),"Elapsed time must not poll terrain");
        invoke(s,"observeLoaded",new Class<?>[]{long.class,ChunkPos.class},now+30*60_000_000_000L,new ChunkPos(1000,1000));
        invoke(s,"observeLoaded",new Class<?>[]{long.class,ChunkPos.class},now+30*60_000_000_000L,new ChunkPos(2,2));
        assertTrue(policy(s).due(new DhPosition(6,0,0),Long.MAX_VALUE),"Re-entering terrain is a new load after DH's debounce window");
    }
    @Test void hotSynchronizationPreferencesNegotiateWithoutReplacingTheConnection() throws Exception {
        var s=session();configureFrontier(s);write(s,SESSION,"announced",true);
        settings.store().saveDraft(settings.store().read().hash(),Map.of("lod.dh.sync_on_load",false,"lod.dh.real_time_updates",false));
        settings.commit(settings.prepareReload());invoke(s,"tick");
        var preference=sent.stream().map(bytes->{try{return codec(s).decode(bytes);}catch(Exception e){throw new AssertionError(e);}})
                .filter(DhMessage.SessionConfig.class::isInstance).map(DhMessage.SessionConfig.class::cast).findFirst().orElseThrow();
        assertFalse(preference.synchronize());assertFalse(preference.updates());
        assertSame(s,read(null,DhClientSession.class,"session"));assertTrue(active(s));
    }
    @Test void aClientThreadTimeoutDuringAPushLeavesDhActiveAndSchedulesAcquisition() throws Exception {
        long previousTimeout=(long)read(null,DhClientSession.class,"handoffTimeoutMillis");
        write(null,DhClientSession.class,"handoffTimeoutMillis",300L);
        try(var bridge=new DeliveryBridge()) {
            var s=updateSession(-128,512);policy(s).accepted(CONTENT_POSITION,1,1,123,System.nanoTime());
            capture(resource("dh-official-3.3.3-neoforge-content","target-0007-split.bin"),System.nanoTime()).receive();
            var update=contentResponse(s,1,true);
            capture(codec(s).encode(new DhMessage.PartialUpdate("test-world",update.bufferId(),List.of())),System.nanoTime()).receive();
            // Leave the first client action queued past a shortened handoff deadline: the
            // worker must give up and release the frame without the client thread ever running.
            long deadline=System.nanoTime()+30_000_000_000L;
            while(frameCount(s)>0 && System.nanoTime()<deadline)Thread.sleep(25);
            assertEquals(0,frameCount(s),"the worker did not time out its client handoff");
            pumpUntil(()->true,"");
            assertTrue(active(s));assertTrue(policy(s).due(CONTENT_POSITION,System.nanoTime()));
            assertEquals(0,policy(s).timestamp(CONTENT_POSITION));
            assertEquals(0,budget(s).used(),"Captured and decoded terrain is released; no fence is retained");
        } finally {write(null,DhClientSession.class,"handoffTimeoutMillis",previousTimeout);}
    }
    @Test void unsolicitedPartialTerrainAppliesOnlyCompletelyDescribedColumnsWithoutAnOutstandingRequest() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=updateSession();
            var splitFiles=List.of("received-0004.bin","received-0005.bin");
            for(String f:splitFiles)capture(resource("dh-official-3.3.3-neoforge-partial",f),System.nanoTime()).receive();
            var response=(DhMessage.Response)codec(s).decode(resource("dh-official-3.3.3-neoforge-partial","received-0006.bin"));
            capture(codec(s).encode(new DhMessage.PartialUpdate("test-world",response.bufferId(),List.of())),System.nanoTime()).receive();
            pumpUntil(()->frameCount(s)==0,"server update did not settle");
            assertEquals(1,counter(s,"receivedUpdates"),()->"Update rejected: "+read(s,SESSION,"problem"));
            waitWorker(s);Runnable task;while((task=mainTasks.poll())!=null)task.run();
            assertTrue(counter(s,"columns")>0 && counter(s,"columns")<16,"Partial push must preserve unobserved MC columns");
            assertTrue(pendingMap(s).isEmpty());
            assertEquals(0,budget(s).used(),"No timestamp fence is retained after a push");
            invoke(s,"retire");assertEquals(0,budget(s).used());
        }
    }
    @Test void foreignWorldPushIsDiscardedWithoutDecodingOrDelivery() throws Exception {
        var s=updateSession();
        capture(resource("dh-official-3.3.3-neoforge-content","target-0002-split.bin"),System.nanoTime()).receive();
        var response=contentResponse(s,1,false);
        capture(codec(s).encode(new DhMessage.PartialUpdate("foreign-world",response.bufferId(),List.of())),System.nanoTime()).receive();
        pumpUntil(()->frameCount(s)==0,"foreign update did not settle");
        assertEquals(0,counter(s,"receivedUpdates"));assertEquals(0,counter(s,"columns"));assertEquals(0,budget(s).used());
    }
    @Test void aNewerServerPushRetiresAnOlderRequestSoItsLateReplyCannotOverwriteTheEdit() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=updateSession(-128,512);pendingAt(s,700,CONTENT_POSITION,0);
            capture(resource("dh-official-3.3.3-neoforge-content","target-0007-split.bin"),System.nanoTime()).receive();
            var update=contentResponse(s,1,true);
            capture(codec(s).encode(new DhMessage.PartialUpdate("test-world",update.bufferId(),List.of())),System.nanoTime()).receive();
            pumpUntil(()->frameCount(s)==0 && pendingMap(s).isEmpty(),"newer update did not retire its stale request");
            long delivered=counter(s,"columns");assertTrue(delivered>0,()->"No update delivery: "+read(s,SESSION,"problem"));
            capture(resource("dh-official-3.3.3-neoforge-content","target-0002-split.bin"),System.nanoTime()).receive();
            var old=contentResponse(s,700,false);
            capture(codec(s).encode(old),System.nanoTime()).receive();
            pumpUntil(()->frameCount(s)==0,"stale response did not settle");
            assertEquals(delivered,counter(s,"columns"));assertEquals(0,budget(s).used());
            invoke(s,"retire");assertEquals(0,budget(s).used());
        }
    }
    @Test void aLocallyDisabledLiveUpdateCannotModifyTerrainOrRetainItsBuffer() throws Exception {
        var s=updateSession();
        settings.store().saveDraft(settings.store().read().hash(),Map.of("lod.dh.real_time_updates",false));settings.commit(settings.prepareReload());
        capture(resource("dh-official-3.3.3-neoforge-content","target-0002-split.bin"),System.nanoTime()).receive();
        var update=contentResponse(s,1,false);
        capture(codec(s).encode(new DhMessage.PartialUpdate("test-world",update.bufferId(),List.of())),System.nanoTime()).receive();
        pumpUntil(()->frameCount(s)==0,"disabled update did not settle");
        assertEquals(0,counter(s,"receivedUpdates"));assertEquals(0,counter(s,"columns"));assertEquals(0,budget(s).used());
    }
    @Test void aNinthIncompleteBufferFreesIncompleteBuffersWithoutStoppingDhOrRecoveringHeldTiles() throws Exception {
        var s=updateSession();
        for(int i=0;i<9;i++) {
            var frame=capture(codec(s).encode(new DhMessage.Split(100+i,new byte[8],true)),System.nanoTime());assertNotNull(frame);frame.receive();
            pumpUntil(()->frameCount(s)==0,"buffer pressure frame did not settle");
        }
        pumpUntil(()->!(boolean)((java.util.concurrent.atomic.AtomicBoolean)read(s,SESSION,"pushRecovery")).get(),"resource recovery did not settle");
        // Slot pressure is not a lost push: no loss epoch, no scoped revalidation. A discarded
        // push buffer surfaces later as an UNOWNED update, which recovers by scope then.
        assertTrue(active(s));assertEquals(0,counter(s,"rawTerrainLosses"));assertEquals(0,assembly(s).incompleteBuffers());assertEquals(0,budget(s).used());
    }
    @Test void rateLimitedRefusalsNeverConsumePermanentRejectionStrikes() throws Exception {
        var s=updateSession();
        for(int i=1;i<=12;i++) {
            long now=System.nanoTime();pending(s,i,false,now);
            capture(codec(s).encode(new DhMessage.Refusal(i,0,"rate limit")),now).receive();
            pumpUntil(()->frameCount(s)==0,"rate limit did not settle");
            assertTrue(policy(s).due(POSITION,System.nanoTime()+3_000_000_000L));assertFalse(policy(s).retryExhausted(POSITION));
        }
        for(int i=20;i<24;i++) {
            long now=System.nanoTime();pending(s,i,false,now);
            capture(codec(s).encode(new DhMessage.Refusal(i,2,"rejected")),now).receive();
            pumpUntil(()->frameCount(s)==0,"permanent refusal did not settle");
        }
        assertTrue(active(s));assertTrue(policy(s).retryExhausted(POSITION));assertFalse(policy(s).due(POSITION,Long.MAX_VALUE));
    }
    @Test void aRecentSyncRequestDoesNotStarveReachableGenerationAtOneRequestPerSecond() throws Exception {
        var s=updateSession();long now=System.nanoTime();
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.SURFACE_ONLY,32,0,0,0,1,false,0,true,32,1,500));
        policy(s).absent(new DhPosition(6,0,0),now-3_000_000_000L,true);
        write(s,SESSION,"lastRequest",now);write(s,SESSION,"lastSyncRequest",now);write(s,SESSION,"lastGenerationRequest",0L);
        var request=scan(s,now);assertNotNull(request);assertEquals(new DhPosition(6,0,0),request.position());assertNull(request.timestamp());
    }
    @Test void liveUpdatesRemainUsableWhenBothSynchronizationAndGenerationAreDisabled() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=updateSession(-128,512);
            settings.store().saveDraft(settings.store().read().hash(),Map.of("lod.dh.sync_on_load",false));settings.commit(settings.prepareReload());
            write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,true,32,false,0,0,500));
            capture(resource("dh-official-3.3.3-neoforge-content","target-0007-split.bin"),System.nanoTime()).receive();
            var update=contentResponse(s,1,true);
            capture(codec(s).encode(new DhMessage.PartialUpdate("test-world",update.bufferId(),List.of())),System.nanoTime()).receive();
            pumpUntil(()->frameCount(s)==0,"push-only terrain did not settle");
            assertEquals(16,counter(s,"columns"));assertEquals(1,counter(s,"receivedUpdates"));
            assertTrue(sent.isEmpty(),"Push-only support must not issue a synchronization/generation request");
            invoke(s,"retire");assertEquals(0,budget(s).used());
        }
    }
    /** DH 3.3 sends a push before baking sky light; the push path bakes it, then refetches the merged tile. */
    @Test void aZeroSkyPushIsLitOnIngestAndSchedulesOneMergedTileRefetch() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=updateSession();var tile=new DhPosition(6,0,0);
            capture(codec(s).encode(new DhMessage.Split(4300,unbakedChunkPush(tile),true)),System.nanoTime()).receive();
            long pushed=System.nanoTime();
            capture(codec(s).encode(new DhMessage.PartialUpdate("test-world",4300,List.of())),System.nanoTime()).receive();
            pumpUntil(()->frameCount(s)==0,"zero-sky push did not settle");
            assertEquals(1,counter(s,"receivedUpdates"),()->"Update rejected: "+read(s,SESSION,"problem"));
            var floorFace=me.cortex.voxy.common.world.service.VoxelIngestService.calls.stream()
                    .filter(c->c.chunkX()==0 && c.chunkZ()==0 && c.sectionY()==0).findFirst().orElseThrow();
            assertEquals(15,floorFace.skyLight().get(0,0,0),"the air above the floor must be lit, not the push's zero");
            assertEquals(7,me.cortex.voxy.common.world.service.VoxelIngestService.calls.stream()
                    .filter(c->c.chunkX()==0 && c.chunkZ()==0 && c.sectionY()==-1).findFirst().orElseThrow()
                    .blockLight().get(0,15,0),"block light passes through");
            assertFalse(policy(s).hasSnapshot(tile));assertEquals(0,policy(s).timestamp(tile));
            assertFalse(policy(s).due(tile,pushed),"the refetch waits for the push burst to settle");
            assertTrue(policy(s).due(tile,System.nanoTime()+DhSessionPolicy.PUSH_REFETCH_DELAY_NANOS),"one full refetch is scheduled");
            invoke(s,"retire");assertEquals(0,budget(s).used());
        }
    }
    /** One chunk column (tile columns x,z 0..15) of AIR over stone, sky light 0 everywhere, stone block light 7. */
    private static byte[] unbakedChunkPush(DhPosition position) throws IOException {
        byte[][] blobs=new byte[8][];var raw=new ByteArrayOutputStream();var points=new DataOutputStream(raw);
        byte[] generation=new byte[4096];
        for(int c=0;c<4096;c++) {
            if(c/64>=16 || c%64>=16) {points.writeShort(0);continue;}
            generation[c]=9;points.writeShort(2);
            points.writeLong(1L|320L<<32|64L<<44);
            points.writeLong(64L<<32|7L<<60);
        }
        blobs[0]=raw.toByteArray();for(int i=1;i<=4;i++)blobs[i]=new byte[0];
        blobs[5]=generation;blobs[6]=new byte[4096];
        var map=new ByteArrayOutputStream();var strings=new DataOutputStream(map);strings.writeInt(2);
        strings.writeUTF("minecraft:plains_DH-BSW_minecraft:stone_STATE_");strings.writeUTF("minecraft:plains_DH-BSW_AIR");blobs[7]=map.toByteArray();
        var dto=new ByteArrayOutputStream();var out=new DataOutputStream(dto);
        out.writeLong(position.packed());out.writeInt(0);for(var blob:blobs){out.writeInt(blob.length);out.write(blob);}
        out.writeByte(1);out.writeByte(0);out.writeBoolean(false);out.writeBoolean(false);out.writeBoolean(false);out.writeLong(200);out.writeLong(100);
        return dto.toByteArray();
    }
    private Object updateSession() throws Exception { return updateSession(-64,384); }
    private Object updateSession(int minY,int height) throws Exception {
        minecraft.player=mcMock(LocalPlayer.class);write(minecraft.player,LocalPlayer.class,"connection",connection);
        minecraft.player=mcMock(LocalPlayer.class);write(minecraft.player,LocalPlayer.class,"connection",connection);
            doReturn(new ChunkPos(2,2)).when(minecraft.player).chunkPosition();
        var s=deliverySession(minY,height);write(s,SESSION,"levelKey","test-world");write(s,SESSION,"cacheInvalidated",true);
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,32,0,0,0,0,true,32,true,32,50,500));
        return s;
    }

    private Object session() throws Exception {
        var ctor=SESSION.getDeclaredConstructor(ClientLevel.class,boolean.class);ctor.setAccessible(true);
        Object s=ctor.newInstance(level,false);sessions.add(s);write(null,DhClientSession.class,"session",s);return s;
    }
    // Mockito's default maker, like every other double in this JVM. Naming a
    // second one here made later default-maker mocks of abstract types throw
    // by hash order (SingleMockMakerContractTest).
    private static <T> T mcMock(Class<T> type) {return mock(type);}
    private static final class TestMinecraft extends Minecraft {
        private LinkedBlockingQueue<Runnable> mainTasks;
        private ServerData server;
        private net.minecraft.client.server.IntegratedServer singleplayer;
        private TestMinecraft() {super(null);} // never invoked: no native window in unit tests
        @Override public void execute(Runnable task) {mainTasks.add(task);}
        @Override public net.minecraft.client.server.IntegratedServer getSingleplayerServer() {return singleplayer;}
        @Override public ServerData getCurrentServer() {return server;}
    }
    @SuppressWarnings("unchecked") private static ConcurrentMap<Integer,Object> pendingMap(Object s) {
        return (ConcurrentMap<Integer,Object>)read(s,SESSION,"pending");
    }
    private static void pending(Object s,int id,boolean generate,long started) throws Exception {
        pendingMap(s).put(id,newPending(s,POSITION,generate,started,0L));assembly(s).expect(id,POSITION);
    }
    private static Object newPending(Object s,DhPosition position,boolean generate,long started,long stamp) throws Exception {
        var ctor=PENDING.getDeclaredConstructor(DhPosition.class,boolean.class,long.class,long.class,long.class,long.class);ctor.setAccessible(true);
        return ctor.newInstance(position,generate,started,stamp,0L,DhLimits.DEFAULT.lifetimeNanos());
    }
    private static DhMemoryBudget budget(Object s) {return (DhMemoryBudget)read(s,SESSION,"budget");}
    private static DhAssembly assembly(Object s) {return (DhAssembly)read(s,SESSION,"assembly");}
    private static DhSessionPolicy policy(Object s) {return (DhSessionPolicy)read(s,SESSION,"policy");}
    private static DhProtocolCodec codec(Object s) {return (DhProtocolCodec)read(s,SESSION,"codec");}
    private static ThreadPoolExecutor worker(Object s) {return (ThreadPoolExecutor)read(s,SESSION,"worker");}
    private static boolean active(Object s) {return (boolean)read(s,SESSION,"active");}
    private static int frameCount(Object s) {
        var frames=(Set<?>)read(s,SESSION,"frames");synchronized(frames){return frames.size();}
    }
    private static DhClientSession.Inbound capture(byte[] bytes,long arrival) {
        var buffer=new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {return DhClientSession.capture(buffer,arrival);} finally {buffer.release();}
    }
    private void waitWorker(Object s) throws Exception {
        worker(s).submit(()->{}).get(10,TimeUnit.SECONDS);
    }
    private void pumpUntil(BooleanSupplier complete,String failure) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!complete.getAsBoolean()) {
            long left=deadline-System.nanoTime();assertTrue(left>0,failure);
            var task=mainTasks.poll(Math.min(left,TimeUnit.MILLISECONDS.toNanos(50)),TimeUnit.NANOSECONDS);
            if(task!=null) task.run();
        }
        for(Runnable task;(task=mainTasks.poll())!=null;) task.run();
    }
    private static Class<?> inner(Class<?> outer,String name) {
        return Arrays.stream(outer.getDeclaredClasses()).filter(c->c.getSimpleName().equals(name)).findFirst().orElseThrow();
    }
    private static Object read(Object object,Class<?> type,String name) {
        try {var f=type.getDeclaredField(name);f.setAccessible(true);return f.get(object);}
        catch(ReflectiveOperationException e) {throw new AssertionError(e);}
    }
    private static void write(Object object,Class<?> type,String name,Object value) {
        try {var f=type.getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
        catch(ReflectiveOperationException e) {throw new AssertionError(e);}
    }
    private static Object invoke(Object s,Class<?> type,String method,Class<?>[] signature,Object...args) throws Exception {
        var m=type.getDeclaredMethod(method,signature);m.setAccessible(true);return m.invoke(s,args);
    }
    private static Object invoke(Object s,String method) throws Exception {return invoke(s,method,new Class<?>[0]);}
    private static Object invoke(Object s,String method,Class<?>[] signature,Object... args) throws Exception {
        var m=SESSION.getDeclaredMethod(method,signature);m.setAccessible(true);
        try {return m.invoke(s,args);}
        catch(InvocationTargetException e) {
            if(e.getCause() instanceof Exception failure) throw failure;
            if(e.getCause() instanceof Error failure) throw failure;
            throw e;
        }
    }
    private final class DestinationBridge implements AutoCloseable {
        final Class<?> compat=Class.forName("dev.vox.lss.compat.VoxyCompat");
        final Object previous=read(null,compat,"classResolver");
        DestinationBridge(boolean defaults) throws Exception {
            var resolverType=compat.getDeclaredField("classResolver").getType();DestinationEngine.defaults=defaults;
            write(null,compat,"classResolver",java.lang.reflect.Proxy.newProxyInstance(resolverType.getClassLoader(),new Class<?>[]{resolverType},(proxy,method,args)->switch(((String)args[0]).substring(((String)args[0]).lastIndexOf('.')+1)) {
                case "WorldIdentifier"->DestinationIdentifier.class;case "WorldEngine"->DestinationEngine.class;
                case "WorldSection"->DestinationSection.class;case "Mapper"->DestinationMapper.class;default->throw new ClassNotFoundException((String)args[0]);
            }));
        }
        public void close(){write(null,compat,"classResolver",previous);}
    }
    // ---- 2026-10-10 remediation regressions ------------------------------------------

    /** Drive one scan; keep serving client-thread hops while the worker runs (a probe needs one). */
    private DhMessage.Request scanAt(Object s,long now,ChunkPos player) throws Exception {
        int before=sent.size();invoke(s,"requestNext",new Class<?>[]{long.class,ChunkPos.class},now,player);
        drain(s);
        return sent.size()==before?null:(DhMessage.Request)codec(s).decode(sent.getLast());
    }
    /** The worker is FIFO: a marker behind current work completes only after that work posted its client hops. */
    private void drain(Object s) throws Exception {
        Future<?> marker;
        try {marker=worker(s).submit(()->{});}
        catch(RejectedExecutionException retired) {pumpUntil(()->true,"");return;}
        pumpUntil(()->marker.isDone()&&mainTasks.isEmpty(),"session work did not drain");
    }
    private DhReceiptStore receiptStore(int minY,int height) {
        return new DhReceiptStore(ColumnCacheStore.cacheRoot(),new DhReceiptStore.Key(minecraft.server.ip,"server","fixture-world",
                DIMENSION,8,DhProfile.OFFICIAL_16,new DhTerrainTile.WorldShape(minY,height,true)));
    }
    private static void awaitReceiptIo() throws Exception {
        ((ExecutorService)read(null,DhClientSession.class,"RECEIPT_IO")).submit(()->{}).get(10,TimeUnit.SECONDS);
    }
    private void loadReceipts(Object s,DhReceiptStore store) throws Exception {
        invoke(s,"loadReceipts",new Class<?>[]{DhReceiptStore.class},store);awaitReceiptIo();
        pumpUntil(()->!(boolean)read(s,SESSION,"receiptsLoading"),"receipts did not load");
    }
    @SuppressWarnings("unchecked") private static Map<DhPosition,DhReceiptStore.Entry> stored(Object s) {
        return (Map<DhPosition,DhReceiptStore.Entry>)read(s,SESSION,"stored");
    }
    private byte[] official15Rejection() throws Exception {
        return new DhProtocolCodec(DhProfile.OFFICIAL_15,DhLimits.DEFAULT).encode(new DhMessage.CloseReason("Incompatible protocol version"));
    }
    private byte[] support15Rejection() throws Exception {
        var bytes=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(bytes);
        out.writeShort(15);out.writeShort(1);byte[] text="Incompatible protocol version".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(text.length);out.write(text);return bytes.toByteArray();
    }

    /** X-1: a whole tile is two client-thread hops (prefilter + batched handoff), not 35-53. */
    @Test void aWholeTileIsDeliveredInTwoClientThreadHops() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=deliverySession(-128,512);startContent(s,31,false);
            int tasks=0;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!(pendingMap(s).isEmpty()&&frameCount(s)==0)) {
                assertTrue(System.nanoTime()<deadline,"delivery did not settle");
                var task=mainTasks.poll(50,TimeUnit.MILLISECONDS);if(task!=null){task.run();tasks++;}
            }
            // Reply bookkeeping + prefilter + batched handoff + owner release.
            assertTrue(tasks<=4,"client-thread hops per tile: "+tasks);
            assertEquals(16,counter(s,"columns"));assertEquals(1,counter(s,"tiles"));assertEquals(0,budget(s).used());
        }
    }
    @Test void vanillaAuthorityBetweenConversionAndHandoffCannotEnqueueTheConvertedColumn() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=deliverySession(-128,512);startContent(s,31,false);
            takeMain().run(); // reply bookkeeping
            takeMain().run(); // prefilter: every column still eligible, conversion follows on the worker
            var handoff=takeMain();
            assertTrue(me.cortex.voxy.common.world.service.VoxelIngestService.calls.isEmpty());
            DhClientSession.vanillaLoaded(level,-12,0);handoff.run();settleDelivery(s);
            assertEquals(15,counter(s,"columns"));assertEquals(1,counter(s,"skipped"));
            assertTrue(me.cortex.voxy.common.world.service.VoxelIngestService.calls.stream()
                    .noneMatch(call->call.chunkX()==-12&&call.chunkZ()==0));
            assertTrue(policy(s).hasSnapshot(CONTENT_POSITION),"A vanilla-resolved column is accepted, not a failure");
            assertEquals(0,budget(s).used());
        }
    }
    @Test void retirementDuringConversionRejectsTheAlreadyQueuedHandoff() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=deliverySession(-128,512);startContent(s,31,false);
            takeMain().run();takeMain().run();var handoff=takeMain();
            assertTrue((boolean)invoke(s,"retire"));
            handoff.run();while(!mainTasks.isEmpty())takeMain().run();
            assertTrue(me.cortex.voxy.common.world.service.VoxelIngestService.calls.isEmpty());
            assertEquals(0,policy(s).syncTimestamp(CONTENT_POSITION));assertEquals(0,budget(s).used());
        }
    }

    /** D-1 / F2-1: an official push (timestamp 0) never freezes its tile; a newer reply still applies. */
    @Test void aTimestampZeroPushIsAppliedAndALaterNewerWholeReplyStillReplacesIt() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=updateSession(-128,512);replayContent(s,700,false);
            assertEquals(16,counter(s,"columns"));
            var split=(DhMessage.Split)codec(s).decode(resource("dh-official-3.3.3-neoforge-content","target-0007-split.bin"));
            byte[] bytes=split.bytes();java.nio.ByteBuffer.wrap(bytes).putLong(bytes.length-16,0L); // official pushes carry 0
            capture(codec(s).encode(new DhMessage.Split(split.bufferId(),bytes,true)),System.nanoTime()).receive();
            capture(codec(s).encode(new DhMessage.PartialUpdate("test-world",split.bufferId(),List.of())),System.nanoTime()).receive();
            pumpUntil(()->frameCount(s)==0,"timestamp-zero push did not settle");waitWorker(s);
            pumpUntil(()->true,"");
            long afterPush=counter(s,"columns");assertTrue(afterPush>16,()->"push not applied: "+read(s,SESSION,"problem"));
            assertEquals(1,counter(s,"receivedUpdates"));
            // The friend builds again; the player returns; a request sent AFTER the push gets a newer whole source.
            pendingAt(s,701,CONTENT_POSITION,0);
            capture(resource("dh-official-3.3.3-neoforge-content","target-0007-split.bin"),System.nanoTime()).receive();
            capture(codec(s).encode(contentResponse(s,701,true)),System.nanoTime()).receive();
            settleDelivery(s);
            assertEquals(afterPush+16,counter(s,"columns"),"The newer reply must ingest every column, never 'reuse' a frozen push");
            assertTrue(policy(s).syncTimestamp(CONTENT_POSITION)>0);
            invoke(s,"retire");assertEquals(0,budget(s).used());
        }
    }
    @Test void anOutOfInterestPushForAStoredButUnheldTileForgetsItsReceipt() throws Exception {
        var s=updateSession(-128,512);var store=receiptStore(-128,512);write(s,SESSION,"receiptStore",store);
        stored(s).put(CONTENT_POSITION,new DhReceiptStore.Entry(123,DhReceiptStore.CONVERSION_VERSION));
        doReturn(new ChunkPos(2000,2000)).when(minecraft.player).chunkPosition();
        capture(resource("dh-official-3.3.3-neoforge-content","target-0007-split.bin"),System.nanoTime()).receive();
        var update=contentResponse(s,1,true);
        capture(codec(s).encode(new DhMessage.PartialUpdate("test-world",update.bufferId(),List.of())),System.nanoTime()).receive();
        pumpUntil(()->frameCount(s)==0,"out-of-interest push did not settle");waitWorker(s);pumpUntil(()->true,"");
        assertFalse(stored(s).containsKey(CONTENT_POSITION),"A stale receipt would restore pre-edit terrain with sync off");
        assertEquals(1,counter(s,"deferredUpdates"));assertEquals(0,counter(s,"columns"));assertTrue(active(s));
    }
    @Test void voxyBackpressureAboveOneTileDefersAPushAndCancelsItsCompetingRequest() throws Exception {
        try(var ready=new ReadyBridge()) {
            me.cortex.voxy.common.world.service.VoxelIngestService.taskCount=DhClientSession.INGEST_GATE+1;
            var owner=updateSession(-128,512);policy(owner).accepted(CONTENT_POSITION,1,1,123,System.nanoTime());
            pendingAt(owner,31,CONTENT_POSITION,123);
            capture(resource("dh-official-3.3.3-neoforge-content","target-0007-split.bin"),System.nanoTime()).receive();
            var response=contentResponse(owner,1,true);
            capture(codec(owner).encode(new DhMessage.PartialUpdate("test-world",response.bufferId(),List.of())),System.nanoTime()).receive();
            pumpUntil(()->frameCount(owner)==0 && pendingMap(owner).isEmpty(),"deferred push did not release its competing request");
            assertTrue(active(owner));assertEquals(0,counter(owner,"columns"));assertEquals(1,counter(owner,"deferredUpdates"));
            assertEquals(0,policy(owner).timestamp(CONTENT_POSITION));assertTrue(policy(owner).due(CONTENT_POSITION,System.nanoTime()));
            me.cortex.voxy.common.world.service.VoxelIngestService.taskCount=384;
            assertTrue(DhVoxyBridge.backlog()<=DhClientSession.INGEST_GATE,"One delivered tile alone never trips the gate");
        } finally {me.cortex.voxy.common.world.service.VoxelIngestService.taskCount=0;}
    }

    /** Panel O1-1: a push deferred under pressure voids a reply already in flight for its tile. */
    @Test void aDeferredPushVoidsAnInFlightReplyForTheSameTile() throws Exception {
        try(var ready=new ReadyBridge()) {
            me.cortex.voxy.common.world.service.VoxelIngestService.taskCount=DhClientSession.INGEST_GATE+1;
            var owner=updateSession(-128,512);
            pendingAt(owner,1,CONTENT_POSITION,0);
            capture(resource("dh-official-3.3.3-neoforge-content","target-0002-split.bin"),System.nanoTime()).receive();
            capture(resource("dh-official-3.3.3-neoforge-content","target-0007-split.bin"),System.nanoTime()).receive();
            var push=contentResponse(owner,2,true);
            capture(codec(owner).encode(new DhMessage.PartialUpdate("test-world",push.bufferId(),List.of())),System.nanoTime()).receive();
            capture(codec(owner).encode(contentResponse(owner,1,false)),System.nanoTime()).receive();
            pumpUntil(()->frameCount(owner)==0 && pendingMap(owner).isEmpty(),"deferred push and stale reply did not settle");
            waitWorker(owner);pumpUntil(()->true,"");
            assertTrue(active(owner));assertEquals(1,counter(owner,"deferredUpdates"));
            assertEquals(0,counter(owner,"columns"),"The pre-push reply must not be written");
            assertFalse(policy(owner).hasSnapshot(CONTENT_POSITION),"...nor re-hold the tile");
            assertFalse(stored(owner).containsKey(CONTENT_POSITION));
        } finally {me.cortex.voxy.common.world.service.VoxelIngestService.taskCount=0;}
    }
    /** Panel O4-4: a push that fails to decode is scoped to the tile its header names. */
    @Test void aMalformedPushDefersOnlyTheTileItsHeaderNames() throws Exception {
        var s=updateSession();long now=System.nanoTime();
        var named=new DhPosition(6,0,0);var other=new DhPosition(6,1,0);
        policy(s).accepted(named,1,1,123,now);policy(s).accepted(other,1,1,456,now);
        byte[] payload=java.nio.ByteBuffer.allocate(12).putLong(named.packed()).putInt(7).array();
        capture(codec(s).encode(new DhMessage.Split(4242,payload,true)),System.nanoTime()).receive();
        capture(codec(s).encode(new DhMessage.PartialUpdate("test-world",4242,List.of())),System.nanoTime()).receive();
        pumpUntil(()->frameCount(s)==0,"malformed push did not settle");waitWorker(s);pumpUntil(()->true,"");
        assertTrue(active(s));assertEquals(1,counter(s,"deferredUpdates"));
        assertFalse(policy(s).hasSnapshot(named),"The named tile is reacquired");
        assertTrue(policy(s).hasSnapshot(other),"...and no window recovery touches its neighbours");
        assertFalse(((java.util.concurrent.atomic.AtomicBoolean)read(s,SESSION,"pushRecovery")).get());
    }
    /** D-2 / A4-1: fragments captured before a drop can never reassemble a gapped push. */
    @Test void fragmentsCapturedBeforeADropCannotReassembleAGappedPushAndDhStaysActive() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=updateSession();
            capture(resource("dh-official-3.3.3-neoforge-partial","received-0004.bin"),System.nanoTime()).receive();
            waitWorker(s);assertEquals(1,assembly(s).incompleteBuffers());
            var held=new ArrayList<DhClientSession.Inbound>();
            for(int i=0;i<24;i++)held.add(capture(codec(s).encode(new DhMessage.Split(5000+i,new byte[8],true)),System.nanoTime()));
            assertNull(capture(resource("dh-official-3.3.3-neoforge-partial","received-0005.bin"),System.nanoTime()),"the middle fragment is dropped");
            assertEquals(1,counter(s,"rawTerrainLosses"));
            held.forEach(DhClientSession.Inbound::close);
            var response=(DhMessage.Response)codec(s).decode(resource("dh-official-3.3.3-neoforge-partial","received-0006.bin"));
            capture(codec(s).encode(new DhMessage.PartialUpdate("test-world",response.bufferId(),List.of())),System.nanoTime()).receive();
            pumpUntil(()->frameCount(s)==0,"gapped push did not settle");waitWorker(s);pumpUntil(()->true,"");
            assertTrue(active(s),()->"DH stopped: "+read(s,SESSION,"problem"));
            assertEquals(0,counter(s,"columns"));assertEquals(0,assembly(s).incompleteBuffers());assertTrue(notices.isEmpty());
            assertEquals(0,counter(s,"malformedTiles"),"The loss epoch discards the buffer; it never decodes as a gapped payload");
            invoke(s,"retire");assertEquals(0,budget(s).used());
        }
    }
    /** D-3: a dropped push frame revalidates only nearby held tiles and never deletes persisted receipts. */
    @Test void aDroppedPushFrameRevalidatesOnlyNearbyHeldTilesAndKeepsReceipts() throws Exception {
        var s=updateSession();long now=System.nanoTime();var store=receiptStore(-64,384);write(s,SESSION,"receiptStore",store);
        var near=new DhPosition(6,0,0);var far=new DhPosition(6,60,60);
        policy(s).accepted(near,1,1,123,now);policy(s).accepted(far,1,1,456,now);
        stored(s).put(near,new DhReceiptStore.Entry(123,DhReceiptStore.CONVERSION_VERSION));
        stored(s).put(far,new DhReceiptStore.Entry(456,DhReceiptStore.CONVERSION_VERSION));
        ((java.util.concurrent.atomic.AtomicBoolean)read(s,SESSION,"pushRecovery")).set(true);
        worker(s).submit(()->{try{invoke(s,"recoverLoss");}catch(Exception e){throw new RuntimeException(e);}}).get(5,TimeUnit.SECONDS);
        pumpUntil(()->true,"");
        assertTrue(policy(s).due(near,System.nanoTime()),"A nearby held tile is revalidated");
        assertEquals(123,policy(s).syncTimestamp(near),"...with a conditional request, not a full download");
        assertFalse(policy(s).due(far,Long.MAX_VALUE),"Tiles beyond the update distance keep their receipts");
        assertEquals(2,stored(s).size(),"Transport faults never purge persisted receipts");
        assertTrue(active(s));
    }
    @Test void aTerrainBurstIsDroppedWithinTheBudgetAndRecoversWithoutStoppingTheSession() throws Exception {
        var s=updateSession();var owned=new ArrayList<DhClientSession.Inbound>();
        policy(s).accepted(POSITION,1,1,123,System.nanoTime());
        for(int i=0;i<40;i++) {
            var frame=capture(codec(s).encode(new DhMessage.Split(1000+i,new byte[8],true)),System.nanoTime());
            if(frame!=null)owned.add(frame);
        }
        assertTrue(active(s));assertEquals(24,owned.size());assertEquals(16,counter(s,"droppedUpdateFrames"));
        owned.forEach(DhClientSession.Inbound::close);
        worker(s).submit(()->{try{invoke(s,"recoverLoss");}catch(Exception e){throw new RuntimeException(e);}}).get(5,TimeUnit.SECONDS);
        pumpUntil(()->true,"");
        assertTrue(active(s));assertEquals(0,budget(s).used());assertFalse((boolean)((java.util.concurrent.atomic.AtomicBoolean)read(s,SESSION,"pushRecovery")).get());
    }
    /** Without sync an unidentifiable lost push never re-downloads the update window. */
    @Test void withoutSynchronizationAHeaderlessLostPushKeepsHeldTerrain() throws Exception {
        var s=updateSession();long now=System.nanoTime();var held=new DhPosition(6,0,0);
        setting(Map.of("lod.dh.sync_on_load",false));
        policy(s).accepted(held,1,1,123,now);
        ((java.util.concurrent.atomic.AtomicBoolean)read(s,SESSION,"pushRecovery")).set(true);
        worker(s).submit(()->{try{invoke(s,"recoverLoss");}catch(Exception e){throw new RuntimeException(e);}}).get(5,TimeUnit.SECONDS);
        pumpUntil(()->true,"");
        assertTrue(policy(s).hasSnapshot(held));assertFalse(policy(s).due(held,Long.MAX_VALUE));
        assertFalse(((java.util.concurrent.atomic.AtomicBoolean)read(s,SESSION,"pushRecovery")).get());
        assertTrue(String.valueOf(read(s,SESSION,"problem")).contains("sync off"),"the no-sync branch ran, not an early return");
    }
    /** Review 2026-10-10: a receipt held without sync is revalidated once sync is turned on. */
    @Test void aReceiptRestoredWithoutSyncIsRevalidatedWhenSyncTurnsOn() throws Exception {
        var s=updateSession();var tile=new DhPosition(6,0,0);setting(Map.of("lod.dh.sync_on_load",false));
        stored(s).put(tile,new DhReceiptStore.Entry(123,DhReceiptStore.CONVERSION_VERSION));
        @SuppressWarnings("unchecked") var unprobed=(Set<DhPosition>)read(s,SESSION,"unprobed");unprobed.add(tile);
        invoke(s,"applyProbe",new Class<?>[]{List.class,Map.class},List.of(tile),Map.of(tile,true));
        assertTrue(policy(s).hasSnapshot(tile));assertFalse(policy(s).due(tile,Long.MAX_VALUE));
        setting(Map.of("lod.dh.sync_on_load",true));
        invoke(s,"tick");
        long now=System.nanoTime();
        invoke(s,"observeLoaded",new Class<?>[]{long.class,ChunkPos.class},now,new ChunkPos(2,2));
        assertTrue(policy(s).due(tile,now),"the held tile gets one conditional recheck");
        assertEquals(123,policy(s).syncTimestamp(tile));
    }
    /** Review 2026-10-10: a tile the policy holds in another state (cooldown) is never re-probed. */
    @Test void aStoredTileOnCooldownIsNotProbedAgainAndAgain() throws Exception {
        var s=deliverySession(-128,512);write(s,SESSION,"receiptStore",receiptStore(-128,512));
        stored(s).put(CONTENT_POSITION,new DhReceiptStore.Entry(123,DhReceiptStore.CONVERSION_VERSION));
        policy(s).cooldown(CONTENT_POSITION,System.nanoTime());
        setting(Map.of("lod.distance_chunks",2));
        scanAt(s,System.nanoTime(),new ChunkPos(-10,2));
        assertFalse(((Set<?>)read(s,SESSION,"unprobed")).contains(CONTENT_POSITION));
        @SuppressWarnings("unchecked") var unprobed=(Set<DhPosition>)read(s,SESSION,"unprobed");unprobed.add(CONTENT_POSITION);
        invoke(s,"applyProbe",new Class<?>[]{List.class,Map.class},List.of(CONTENT_POSITION),Map.of(CONTENT_POSITION,true));
        assertEquals(0,counter(s,"restored"),"restore counts only real restores");
    }
    @Test void aMalformedTileTakesAStrikeWithoutStoppingTheSession() throws Exception {
        var s=updateSession();long now=System.nanoTime();pending(s,1,false,now);
        capture(codec(s).encode(new DhMessage.Split(77,new byte[64],true)),now).receive();
        capture(codec(s).encode(new DhMessage.Response(1,77,List.of())),now).receive();
        pumpUntil(()->frameCount(s)==0&&pendingMap(s).isEmpty(),"malformed tile did not settle");
        assertTrue(active(s));assertEquals(1,counter(s,"malformedTiles"));
        assertFalse(policy(s).due(POSITION,System.nanoTime()+2_000_000_000L),"A malformed tile backs off with a strike");
        assertFalse(policy(s).retryExhausted(POSITION));assertEquals(0,budget(s).used());assertTrue(notices.isEmpty());
    }
    @Test void unknownControlMessagesAndTrailingControlBytesAreIgnoredButTerrainStaysStrict() throws Exception {
        var s=updateSession();
        capture(new byte[]{0,16,0,77,1,2,3},System.nanoTime()).receive();
        var refusal=codec(s).encode(new DhMessage.Refusal(999,1,"x"));var trailing=Arrays.copyOf(refusal,refusal.length+3);
        capture(trailing,System.nanoTime()).receive();
        pumpUntil(()->frameCount(s)==0,"control frames did not settle");waitWorker(s);
        assertTrue(active(s),()->"stopped: "+read(s,SESSION,"problem"));assertEquals(1,counter(s,"unknownControls"));
        var split=codec(s).encode(new DhMessage.Split(9,new byte[4],true));
        capture(Arrays.copyOf(split,split.length+1),System.nanoTime()).receive();
        pumpUntil(()->!active(s),"Trailing bytes on a terrain message remain malformed");
    }

    /** X-4: a protocol-15 official server gets an update notice; nothing reconnects. */
    @Test void anOfficialProtocol15ServerStopsWithAnUpdateNoticeAndNeverReconnects() throws Exception {
        var s=session();
        capture(official15Rejection(),System.nanoTime()).receive();
        pumpUntil(()->!active(s),"protocol-15 advertisement was not handled");pumpUntil(()->!notices.isEmpty(),"no stop notice");
        assertEquals(DhStatusSnapshot.Reason.OLD_DH_PROTOCOL,read(s,SESSION,"reason"));
        assertSame(connection,minecraft.getConnection(),"Minecraft stays connected; there is no reconnect machinery");
        assertEquals(1,notices.size());assertTrue(notices.getFirst().getString().contains("lss.status.dh.notice.stopped")
                || notices.getFirst().getContents().toString().contains("notice.stopped"));
    }
    @Test void aDhSupportAdvertisementSwitchesFamiliesOnTheSameConnection() throws Exception {
        var s=session();
        capture(support15Rejection(),System.nanoTime()).receive();
        pumpUntil(()->!active(s),"support advertisement did not retire the probe session");
        assertEquals(DhProfile.SUPPORT_15,((Map<?,?>)read(null,DhClientSession.class,"discoveredProfiles")).get(connection));
        assertNull(read(null,DhClientSession.class,"session"));assertSame(connection,minecraft.getConnection());
        var next=session();assertEquals(DhProfile.SUPPORT_15,read(next,SESSION,"profile"));assertTrue(notices.isEmpty());
    }
    @Test void aSilentProtocol16ServerWaitsAsNoServiceAndALateConfigStillWorks() throws Exception {
        var s=session();long now=System.nanoTime();
        write(s,SESSION,"announced",true);write(s,SESSION,"sentAt",now-16_000_000_000L);
        invoke(s,"tick");
        var d=DhClientSession.diagnostics();
        assertEquals(DhStatusSnapshot.State.NO_SERVICE,d.state());assertEquals(DhStatusSnapshot.Reason.NO_DH_REPLY,d.reason());
        assertTrue(active(s));assertTrue(notices.isEmpty());
        capture(codec(s).encode(new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,true,32,10,500)),System.nanoTime()).receive();
        pumpUntil(()->read(s,SESSION,"config")!=null,"late config was refused");assertTrue(active(s));
    }
    @Test void aServerWithoutTheDhChannelIsNoServiceRatherThanAFailure() throws Exception {
        DhClientSession.installChannelAvailability(()->false);
        var s=session();write(s,SESSION,"started",System.nanoTime()-16_000_000_000L);
        invoke(s,"tick");
        var d=DhClientSession.diagnostics();
        assertEquals(DhStatusSnapshot.State.NO_SERVICE,d.state());assertEquals(DhStatusSnapshot.Reason.NO_DH_CHANNEL,d.reason());
        assertTrue(active(s));assertTrue(sent.isEmpty());assertTrue(notices.isEmpty());
    }
    @Test void anIntegratedHostAndAnOptedOutUserNeverRecordVanillaAuthority() throws Exception {
        var state=(DhConnectionState)read(null,DhClientSession.class,"connectionState");
        setting(Map.of("lod.source","lss"));
        DhClientSession.vanillaLoaded(level,1,1);
        assertTrue(state.policy(DIMENSION).eligible(1,1),"An opted-out user records nothing");
        setting(Map.of("lod.source","distant_horizons"));DhClientSession.vanillaLoaded(level,1,1);
        assertFalse(state.policy(DIMENSION).eligible(1,1));
    }

    /** X-5 / D-5 / D-6: persisted receipts, probed before trust, revalidated once. */
    @Test void aWholeTileWritesAPersistentReceiptThatANewConnectionRevalidatesWithoutDownloading() throws Exception {
        try(var voxy=new DeliveryBridge();var destination=new DestinationBridge(false)) {
            var store=receiptStore(-128,512);var s=deliverySession(-128,512);write(s,SESSION,"receiptStore",store);
            replayContent(s,1,false);long stamp=policy(s).syncTimestamp(CONTENT_POSITION);assertTrue(stamp>0);
            assertEquals(stamp,stored(s).get(CONTENT_POSITION).timestamp());
            invoke(s,"saveReceipts",new Class<?>[]{boolean.class},true);awaitReceiptIo();
            assertEquals(stamp,store.load().get(CONTENT_POSITION).timestamp());
            DhClientSession.disconnect();sent.clear();me.cortex.voxy.common.world.service.VoxelIngestService.reset();
            setting(Map.of("lod.distance_chunks",2));
            var next=deliverySession(-128,512);loadReceipts(next,store);
            assertTrue(((Set<?>)read(next,SESSION,"unprobed")).contains(CONTENT_POSITION));
            var player=new ChunkPos(-10,2);long now=System.nanoTime();
            assertNull(scanAt(next,now,player),"The stored tile waits for its destination probe");
            pumpUntil(()->!(boolean)read(next,SESSION,"probeInFlight"),"probe did not finish");
            var request=scanAt(next,System.nanoTime(),player);assertNotNull(request,"restored receipt was not revalidated");
            assertEquals(stamp,request.timestamp(),"A conditional request carries the persisted server timestamp");
            capture(codec(next).encode(new DhMessage.Response(request.requestId(),null,List.of())),System.nanoTime()).receive();
            settleDelivery(next);
            assertTrue(policy(next).hasSnapshot(CONTENT_POSITION));assertEquals(0,counter(next,"columns"),"Unchanged means no download");
            assertEquals(1,counter(next,"restored"));
        }
    }
    @Test void aWipedDestinationDropsItsReceiptAndRequestsFullData() throws Exception {
        try(var voxy=new DeliveryBridge();var destination=new DestinationBridge(true)) {
            var store=receiptStore(-128,512);
            store.save(Map.of(CONTENT_POSITION,new DhReceiptStore.Entry(123,DhReceiptStore.CONVERSION_VERSION)));
            setting(Map.of("lod.distance_chunks",2));
            var s=deliverySession(-128,512);loadReceipts(s,store);var player=new ChunkPos(-10,2);
            assertNull(scanAt(s,System.nanoTime(),player));
            pumpUntil(()->!(boolean)read(s,SESSION,"probeInFlight"),"probe did not finish");
            assertFalse(stored(s).containsKey(CONTENT_POSITION),"Voxy holds nothing: the receipt is not trusted");
            var request=scanAt(s,System.nanoTime(),player);
            assertNotNull(request,()->"problem="+read(s,SESSION,"problem")+" due="+policy(s).due(CONTENT_POSITION,System.nanoTime())
                    +" prio="+policy(s).acquisitionPriority(CONTENT_POSITION)+" pending="+pendingMap(s)+" unprobed="+read(s,SESSION,"unprobed")
                    +" range="+invokeQuietly(s,"effectiveRange")+" activity="+read(s,SESSION,"activity")+" active="+active(s));
            assertEquals(0,request.timestamp());
        }
    }
    @Test void anUnreadableDestinationTrustsReceiptsOnceAndNeverPolls() throws Exception {
        try(var voxy=new DeliveryBridge()) {
            var store=receiptStore(-128,512);
            store.save(Map.of(CONTENT_POSITION,new DhReceiptStore.Entry(123,DhReceiptStore.CONVERSION_VERSION)));
            setting(Map.of("lod.distance_chunks",2));
            var s=deliverySession(-128,512);loadReceipts(s,store);var player=new ChunkPos(-10,2);
            assertNull(DhVoxyBridge.openProbe(level),"This fixture ABI has ingest but no destination reader");
            scanAt(s,System.nanoTime(),player);
            pumpUntil(()->!(boolean)read(s,SESSION,"probeInFlight"),"probe did not finish");
            assertTrue((boolean)read(s,SESSION,"receiptsUnverified"));
            var request=scanAt(s,System.nanoTime(),player);assertNotNull(request);assertEquals(123,request.timestamp());
            capture(codec(s).encode(new DhMessage.Response(request.requestId(),null,List.of())),System.nanoTime()).receive();
            settleDelivery(s);
            int before=sent.size();
            for(int i=1;i<=6;i++)scanAt(s,System.nanoTime()+i*2_100_000_000L,player);
            assertEquals(before,sent.size(),"A stationary client sends nothing more for a held tile");
            assertEquals(DhStatusSnapshot.Reason.RECEIPTS_UNVERIFIED,DhClientSession.diagnostics().reason());
        }
    }
    @Test void receiptsOfAnOlderConversionAreDroppedSoTheTileIsDownloadedAgain() throws Exception {
        var store=receiptStore(-128,512);
        store.save(Map.of(CONTENT_POSITION,new DhReceiptStore.Entry(123,DhReceiptStore.CONVERSION_VERSION-1)));
        var s=deliverySession(-128,512);loadReceipts(s,store);
        assertTrue(stored(s).isEmpty());assertTrue((boolean)read(s,SESSION,"receiptsDirty"));
    }

    /** Panel O2-3: receipts the connection already holds survive a dimension round trip's next save. */
    @Test void heldReceiptsSurviveTheNextSaveAfterADimensionRoundTrip() throws Exception {
        var store=receiptStore(-128,512);var held=new DhPosition(6,1,1);var fresh=new DhPosition(6,2,2);
        store.save(Map.of(held,new DhReceiptStore.Entry(123,DhReceiptStore.CONVERSION_VERSION)));
        var s=deliverySession(-128,512);policy(s).accepted(held,1,1,123,System.nanoTime());
        loadReceipts(s,store);
        assertTrue(stored(s).containsKey(held));assertFalse(((Set<?>)read(s,SESSION,"unprobed")).contains(held),"Already held: no probe");
        invoke(s,"rememberReceipt",new Class<?>[]{DhPosition.class,long.class},fresh,456L);
        invoke(s,"saveReceipts",new Class<?>[]{boolean.class},true);awaitReceiptIo();
        assertEquals(Set.of(held,fresh),store.load().keySet());
    }
    /** User decision 2026-10-10: without sync, match the official DH client: keep receipts and
     *  restore them as held without rechecking (stale accepted, no full download). */
    @Test void withoutSynchronizationReceiptsAreKeptAndRestoredWithoutRechecking() throws Exception {
        var store=receiptStore(-128,512);var tile=new DhPosition(6,1,1);
        store.save(Map.of(tile,new DhReceiptStore.Entry(123,DhReceiptStore.CONVERSION_VERSION)));
        var s=deliverySession(-128,512);
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,false,0,0,500));
        loadReceipts(s,store);
        invoke(s,"applyProbe",new Class<?>[]{List.class,Map.class},List.of(tile),Map.of(tile,true));
        assertTrue(policy(s).hasSnapshot(tile),"The tile is held: no download");
        assertFalse(policy(s).due(tile,Long.MAX_VALUE),"...and never rechecked without sync");
        assertEquals(1,counter(s,"restored"));
        invoke(s,"rememberReceipt",new Class<?>[]{DhPosition.class,long.class},new DhPosition(6,2,2),456L);
        assertTrue(stored(s).containsKey(new DhPosition(6,2,2)));
    }
    /** Panel O1-3 / O2-5: a transient probe failure retries; it never trusts the batch. */
    @Test void aTransientProbeFailureRetriesAndRepeatedFailuresDropTheReceipts() throws Exception {
        var s=deliverySession(-128,512);var tile=new DhPosition(6,1,1);
        stored(s).put(tile,new DhReceiptStore.Entry(123,DhReceiptStore.CONVERSION_VERSION));
        @SuppressWarnings("unchecked") var unprobed=(Set<DhPosition>)read(s,SESSION,"unprobed");unprobed.add(tile);
        invoke(s,"probeFailed");
        assertTrue(unprobed.contains(tile));assertFalse(policy(s).hasSnapshot(tile));assertFalse((boolean)read(s,SESSION,"receiptsUnverified"));
        assertTrue((long)read(s,SESSION,"probeRetryAt")>System.nanoTime());
        for(int i=0;i<4;i++)invoke(s,"probeFailed");
        assertFalse(unprobed.contains(tile));assertFalse(stored(s).containsKey(tile),"Unverifiable receipts download again");
    }
    /** Panel F2-3: a persisted receipt the policy evicted on a long flight is probed again, not re-downloaded. */
    @Test void aReceiptEvictedFromThePolicyIsProbedAgainInsteadOfDownloaded() throws Exception {
        var s=deliverySession(-128,512);write(s,SESSION,"receiptStore",receiptStore(-128,512));
        stored(s).put(CONTENT_POSITION,new DhReceiptStore.Entry(123,DhReceiptStore.CONVERSION_VERSION));
        setting(Map.of("lod.distance_chunks",2));
        var player=new ChunkPos(-10,2);
        assertNull(scanAt(s,System.nanoTime(),player),"The stored tile is queued for a probe, never full-requested");
        assertTrue(((Set<?>)read(s,SESSION,"unprobed")).contains(CONTENT_POSITION));
        var next=scanAt(s,System.nanoTime(),player);
        assertTrue(next==null||next.timestamp()==123,"Only a conditional request may follow");
        pumpUntil(()->!(boolean)read(s,SESSION,"probeInFlight"),"probe did not finish");
        assertTrue(policy(s).hasSnapshot(CONTENT_POSITION),"The receipt is restored from the persisted layer");
    }
    /** Panel F2-6/F2-7/F2-8. */
    @Test void aSessionWaitingForAServerIsNotATerrainSessionForTheBorderPump() throws Exception {
        var s=session();assertTrue(DhClientSession.running());assertFalse(DhClientSession.terrainSessionActive());
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,true,32,true,32,50,500));
        assertTrue(DhClientSession.terrainSessionActive());
    }
    @Test void theRequestedUpdateDistanceFollowsTheClientLodDistance() throws Exception {
        setting(Map.of("lod.distance_chunks",32));assertEquals(32,(int)invoke(null,SESSION,"requestedUpdateDistance",new Class<?>[0]));
        setting(Map.of("lod.distance_chunks",0));assertEquals(128,(int)invoke(null,SESSION,"requestedUpdateDistance",new Class<?>[0]));
    }
    @Test void aDeliveredTileCountsAsLoadedSoTheFirstPortalReturnDoesNotRevalidateIt() throws Exception {
        try(var voxy=new DeliveryBridge()) {
            var s=deliverySession(-128,512);replayContent(s,1,false);
            var state=(DhConnectionState)read(null,DhClientSession.class,"connectionState");
            assertTrue(state.loadedAt(DIMENSION).containsKey(CONTENT_POSITION));
        }
    }
    /** D-7: a confirmed reset on a DH connection that is not acquiring still clears receipts and resumes. */
    @Test void aConfirmedResetWhileWaitingForConfigClearsReceiptsAndResumes() throws Exception {
        var store=new DhReceiptStore(ColumnCacheStore.cacheRoot(),new DhReceiptStore.Key(minecraft.server.ip,"server","world",DIMENSION,8,
                DhProfile.OFFICIAL_16,new DhTerrainTile.WorldShape(-64,384,true)));
        store.save(Map.of(POSITION,new DhReceiptStore.Entry(123,DhReceiptStore.CONVERSION_VERSION)));
        var s=session();assertFalse(DhClientSession.canAcquire());
        policy(s).accepted(POSITION,1,1,123,System.nanoTime());
        var feedback=new ArrayList<net.minecraft.network.chat.Component>();
        ClientCommandActions.runReset(feedback::add,true,false);
        var state=(DhConnectionState)read(null,DhClientSession.class,"connectionState");
        assertFalse(state.policy(DIMENSION).hasSnapshot(POSITION),"Resume clears this connection's in-memory receipts");
        assertFalse(active(s));assertNull(read(null,DhClientSession.class,"session"),"The next tick builds a fresh session");
        assertFalse((boolean)read(null,DhClientSession.class,"resetFailed"));
        assertTrue(store.load().isEmpty(),"Persisted receipts for this server are cleared");
    }
    /** Live rig 2026-10-10 (official 3.2.x): a session still waiting for a DH reply cannot refill Voxy. */
    @Test void anUnconfirmedResetWhileWaitingForADhReplyKeepsTheConfirmPrompt() throws Exception {
        var s=session();var feedback=new ArrayList<net.minecraft.network.chat.Component>();
        ClientCommandActions.runReset(feedback::add,false,false);
        assertTrue(active(s));assertSame(s,read(null,DhClientSession.class,"session"));
        assertTrue(feedback.getFirst().getString().contains("reset confirm"),feedback::toString);
    }
    @Test void aResetOnANegotiatedDhSessionNeedsNoConfirmationAndSaysDhRefills() throws Exception {
        var s=updateSession();var feedback=new ArrayList<net.minecraft.network.chat.Component>();
        ClientCommandActions.runReset(feedback::add,false,false);
        assertFalse(active(s));
        assertTrue(feedback.stream().anyMatch(c->c.getString().contains("DH receipts cleared")),feedback::toString);
    }
    /** Panel O4-3: DH merely being allowed on a server without the DH channel keeps the confirm prompt. */
    @Test void anUnconfirmedResetWithoutADhChannelPromptsAndPreservesTheSession() throws Exception {
        DhClientSession.installChannelAvailability(()->false);
        var s=session();var feedback=new ArrayList<net.minecraft.network.chat.Component>();
        ClientCommandActions.runReset(feedback::add,false,false);
        assertTrue(active(s));assertSame(s,read(null,DhClientSession.class,"session"));
        assertTrue(feedback.getFirst().getString().contains("reset confirm"),feedback::toString);
    }
    /** A DH terrain connection (the server negotiated DH) is this server for clearcache, as it is for reset. */
    @Test void clearCacheInDhModeClearsOnlyTheCurrentRemoteAddressReceipts() throws Exception {
        var shape=new DhTerrainTile.WorldShape(-64,384,true);String other="other-dh-"+UUID.randomUUID();
        var current=new DhReceiptStore(ColumnCacheStore.cacheRoot(),new DhReceiptStore.Key(minecraft.server.ip,"server","world",DIMENSION,8,DhProfile.OFFICIAL_16,shape));
        var retained=new DhReceiptStore(ColumnCacheStore.cacheRoot(),new DhReceiptStore.Key(other,"server","world",DIMENSION,8,DhProfile.OFFICIAL_16,shape));
        var entry=Map.of(POSITION,new DhReceiptStore.Entry(123,DhReceiptStore.CONVERSION_VERSION));
        current.save(entry);retained.save(entry);var s=updateSession();
        assertTrue(DhClientSession.dhServerConnection());assertTrue(DhClientSession.canAcquire());
        assertEquals(ClientCommandActions.ClearCacheScope.CURRENT_SERVER,ClientCommandActions.clearCacheScope());
        var feedback=new ArrayList<net.minecraft.network.chat.Component>();
        try {
            ClientCommandActions.clearCache(feedback::add);
            assertFalse(active(s));assertTrue(current.load().isEmpty());assertEquals(1,retained.load().size());
            assertEquals(1,feedback.size());
            String line=feedback.getFirst().getString();
            assertTrue(line.contains("current server")&&line.contains("Chunks will be re-requested"),line);
        } finally {ColumnCacheStore.clearDhReceipts(other);}
    }
    /** Review fold: a negotiated DH connection that cannot download right now must not promise a re-download.
     *  Every cause that leaves canAcquire() false is "not receiving": nothing is cleared, the session is left
     *  alone, and the command says so (the dialog and the toast follow the same scope). */
    @Test void clearCacheOnADhConnectionThatCannotDownloadClearsNothing() throws Exception {
        var current=new DhReceiptStore(ColumnCacheStore.cacheRoot(),new DhReceiptStore.Key(minecraft.server.ip,"server","world",DIMENSION,8,
                DhProfile.OFFICIAL_16,new DhTerrainTile.WorldShape(-64,384,true)));
        current.save(Map.of(POSITION,new DhReceiptStore.Entry(123,DhReceiptStore.CONVERSION_VERSION)));
        var s=updateSession();var working=read(s,SESSION,"config");
        var dead=(java.util.concurrent.atomic.AtomicBoolean)read(null,Class.forName("dev.vox.lss.compat.VoxyCompat"),"dhBridgeDead");
        boolean wasDead=dead.get();var held=minecraft.level;
        try {
            assertTrue(DhClientSession.canAcquire());
            // The server offers neither sync nor generation: real-time pushes only.
            write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,true,32,false,0,0,500));
            assertNothingCleared("push-only server config",s,current);
            write(s,SESSION,"config",working);
            // The same, chosen locally: sync on load is off in the client settings.
            setting(Map.of("lod.dh.sync_on_load",false));
            assertNothingCleared("sync on load off",s,current);
            setting(Map.of("lod.dh.sync_on_load",true));
            // The level is switching: the session still belongs to the level being left.
            minecraft.level=mcMock(ClientLevel.class);
            assertNothingCleared("level switch",s,current);
            minecraft.level=held;
            // The connection is switching.
            write(minecraft.player,LocalPlayer.class,"connection",mcMock(ClientPacketListener.class));
            assertNothingCleared("connection switch",s,current);
            write(minecraft.player,LocalPlayer.class,"connection",connection);
            // The Voxy bridge failed: nothing can take the terrain.
            dead.set(true);
            assertNothingCleared("Voxy bridge failed",s,current);
            dead.set(wasDead);
            // Downloading again: this server, as before.
            assertTrue(DhClientSession.canAcquire());
            assertEquals(ClientCommandActions.ClearCacheScope.CURRENT_SERVER,ClientCommandActions.clearCacheScope());
        } finally {
            dead.set(wasDead);minecraft.level=held;ColumnCacheStore.clearDhReceipts(minecraft.server.ip);
        }
    }
    private void assertNothingCleared(String why,Object s,DhReceiptStore receipts) throws Exception {
        assertTrue(DhClientSession.dhServerConnection(),why+": still a negotiated DH connection");
        assertFalse(DhClientSession.canAcquire(),why+": but nothing can be downloaded");
        assertEquals(ClientCommandActions.ClearCacheScope.NOT_RECEIVING,ClientCommandActions.clearCacheScope(),why);
        var feedback=new ArrayList<net.minecraft.network.chat.Component>();
        ClientCommandActions.clearCache(feedback::add);
        assertTrue(active(s),why+": the session is left alone");assertSame(s,read(null,DhClientSession.class,"session"),why);
        assertEquals(1,receipts.load().size(),why+": the saved receipts stay");
        assertEquals(1,feedback.size(),why);
        assertTrue(feedback.getFirst().getString().contains("Nothing was cleared"),why+": "+feedback.getFirst().getString());
    }
    /** A negotiated session that then stopped (config kept, no longer active) is not a download either. */
    @Test void clearCacheOnAStoppedDhSourceClearsNothing() throws Exception {
        var current=new DhReceiptStore(ColumnCacheStore.cacheRoot(),new DhReceiptStore.Key(minecraft.server.ip,"server","world",DIMENSION,8,
                DhProfile.OFFICIAL_16,new DhTerrainTile.WorldShape(-64,384,true)));
        current.save(Map.of(POSITION,new DhReceiptStore.Entry(123,DhReceiptStore.CONVERSION_VERSION)));
        var s=updateSession();
        invoke(s,"fail",new Class<?>[]{String.class,DhStatusSnapshot.Reason.class},"test stop",DhStatusSnapshot.Reason.SOURCE_STOPPED);
        try {
            assertFalse(active(s));assertNotNull(read(s,SESSION,"config"));
            assertEquals(DhStatusSnapshot.State.STOPPED,DhClientSession.diagnostics().state());
            assertEquals(ClientCommandActions.ClearCacheScope.NOT_RECEIVING,ClientCommandActions.clearCacheScope());
            var feedback=new ArrayList<net.minecraft.network.chat.Component>();
            ClientCommandActions.clearCache(feedback::add);
            assertSame(s,read(null,DhClientSession.class,"session"),"clearcache does not restart a stopped source");
            assertEquals(DhStatusSnapshot.State.STOPPED,DhClientSession.diagnostics().state());
            assertEquals(1,current.load().size(),"The saved receipts stay");
            assertEquals(1,feedback.size());assertTrue(feedback.getFirst().getString().contains("Nothing was cleared"),feedback::toString);
        } finally {ColumnCacheStore.clearDhReceipts(minecraft.server.ip);}
    }
    /** What the "stopped" status text tells the player to do. With a working Voxy bridge a stopped source
     *  stays stopped by itself (clearcache no longer restarts it either); turning Receive Server LODs off
     *  drops it, so turning it on again starts a fresh session, and so does reconnecting. */
    @Test void aStoppedDhSourceStaysStoppedUntilReceiveIsToggledOrThePlayerReconnects() throws Exception {
        Object voxy=read(null,ModCompat.class,"voxyLoaded");
        write(null,ModCompat.class,"voxyLoaded",true);
        try {
            var s=updateSession();
            invoke(s,"fail",new Class<?>[]{String.class,DhStatusSnapshot.Reason.class},"test stop",DhStatusSnapshot.Reason.SOURCE_STOPPED);
            DhClientSession.tick();DhClientSession.tick();
            assertSame(s,read(null,DhClientSession.class,"session"),"time alone does not restart a stopped source");
            assertEquals(DhStatusSnapshot.State.STOPPED,DhClientSession.diagnostics().state());

            setting(Map.of("lod.receive",false));DhClientSession.tick();
            assertNull(read(null,DhClientSession.class,"session"),"Receive off drops the stopped session");
            assertEquals(DhStatusSnapshot.State.PAUSED,DhClientSession.diagnostics().state());
            setting(Map.of("lod.receive",true));
            assertFalse((boolean)read(null,DhClientSession.class,"resetFailed"));
            assertEquals(DhStatusSnapshot.State.BETWEEN_SESSIONS,DhClientSession.diagnostics().state(),
                    "Receive on again: nothing is stopped or blocked, so the next tick starts a session");

            var again=updateSession();
            invoke(again,"fail",new Class<?>[]{String.class,DhStatusSnapshot.Reason.class},"test stop",DhStatusSnapshot.Reason.SOURCE_STOPPED);
            assertEquals(DhStatusSnapshot.State.STOPPED,DhClientSession.diagnostics().state());
            DhClientSession.disconnect();DhClientSession.onJoin();
            assertNull(read(null,DhClientSession.class,"session"),"a reconnect drops the stopped session");
            assertEquals(DhStatusSnapshot.State.BETWEEN_SESSIONS,DhClientSession.diagnostics().state(),
                    "after reconnecting the next tick starts a session");
        } finally {write(null,ModCompat.class,"voxyLoaded",voxy);}
    }

    /** Re-download plan, decision 2: a session still waiting for a DH reply (official 3.2.x, or the first
     *  moment after joining) cannot send the terrain again, so clearcache clears nothing and leaves it alone. */
    @Test void clearCacheWhileWaitingForADhReplyClearsNothingAndKeepsTheSession() throws Exception {
        var shape=new DhTerrainTile.WorldShape(-64,384,true);
        var current=new DhReceiptStore(ColumnCacheStore.cacheRoot(),new DhReceiptStore.Key(minecraft.server.ip,"server","world",DIMENSION,8,DhProfile.OFFICIAL_16,shape));
        current.save(Map.of(POSITION,new DhReceiptStore.Entry(123,DhReceiptStore.CONVERSION_VERSION)));
        var s=session();assertFalse(DhClientSession.dhServerConnection());
        assertEquals(ClientCommandActions.ClearCacheScope.NOT_RECEIVING,ClientCommandActions.clearCacheScope());
        var feedback=new ArrayList<net.minecraft.network.chat.Component>();
        try {
            ClientCommandActions.clearCache(feedback::add);
            assertTrue(active(s));assertSame(s,read(null,DhClientSession.class,"session"));
            assertEquals(1,current.load().size(),"The saved receipts stay");
            assertEquals(1,feedback.size());assertTrue(feedback.getFirst().getString().contains("Nothing was cleared"),feedback::toString);
        } finally {ColumnCacheStore.clearDhReceipts(minecraft.server.ip);}
    }

    /** X-12 / X-13: sync-on-load is debounced per connection; resuming reception is a load. */
    @Test void syncOnLoadDebounceSurvivesASessionRestartOnTheSameConnection() throws Exception {
        var s=session();configureFrontier(s);long now=System.nanoTime();var tile=new DhPosition(6,0,0);
        policy(s).accepted(tile,1,1,123,now);
        invoke(s,"observeLoaded",new Class<?>[]{long.class,ChunkPos.class},now,new ChunkPos(2,2));
        assertTrue(policy(s).due(tile,now));policy(s).accepted(tile,1,1,123,now);
        invoke(s,"retire");
        var next=session();configureFrontier(next);
        invoke(next,"observeLoaded",new Class<?>[]{long.class,ChunkPos.class},now+60_000_000_000L,new ChunkPos(2,2));
        assertFalse(policy(next).due(tile,Long.MAX_VALUE),"A dimension return within 20 minutes does not revalidate again");
    }
    @Test void resumingReceptionWithoutSynchronizationKeepsHeldTerrain() throws Exception {
        var s=session();configureFrontier(s);var tile=new DhPosition(6,0,0);long now=System.nanoTime();
        policy(s).accepted(tile,1,1,123,now);
        invoke(s,"observeLoaded",new Class<?>[]{long.class,ChunkPos.class},now,new ChunkPos(2,2));policy(s).accepted(tile,1,1,123,now);
        invoke(s,"retire");
        var ctor=SESSION.getDeclaredConstructor(ClientLevel.class,boolean.class);ctor.setAccessible(true);
        var resumed=ctor.newInstance(level,true);sessions.add(resumed);write(null,DhClientSession.class,"session",resumed);
        write(resumed,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,false,0,0,500));
        write(resumed,SESSION,"levelKey","test-world");write(resumed,SESSION,"announced",true);
        invoke(resumed,"tick");
        assertTrue((boolean)read(resumed,SESSION,"resumeApplied"),"the resume branch ran");
        assertTrue(policy(resumed).hasSnapshot(tile),"Without sync, held terrain is kept (official DH client behaviour)");
        assertFalse(policy(resumed).due(tile,Long.MAX_VALUE));
    }

    /** D-11 / X-15: no column cap; diagnostics name the real block reason. */
    @Test void theVanillaRegionLimitIsReportedAsSuchNotAsAWorldChange() throws Exception {
        var state=(DhConnectionState)read(null,DhClientSession.class,"connectionState");
        for(int i=0;i<65_536;i++)state.vanilla(DIMENSION,i*32,0);
        assertThrows(IllegalStateException.class,()->state.vanilla(DIMENSION,0,32));
        var d=DhClientSession.diagnostics();
        assertEquals(DhStatusSnapshot.State.BLOCKED,d.state());assertEquals(DhStatusSnapshot.Reason.VANILLA_LIMIT,d.reason());
    }
    @Test void diagnosticsReportOneActivityWithoutMasksOrEtas() throws Exception {
        try(var ready=new ReadyBridge()) {
        var s=updateSession();long now=System.nanoTime();
        for(var c:DhStatusSnapshot.class.getRecordComponents())
            assertFalse(c.getName().toLowerCase(Locale.ROOT).contains("gate")||c.getName().toLowerCase(Locale.ROOT).contains("wait"),c.getName());
        policy(s).absent(new DhPosition(6,0,0),now,false);
        setting(Map.of("lod.distance_chunks",4));
        for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)if(x<0||x>3||z<0||z>3)policy(s).vanilla(x,z);
        scanAt(s,now,new ChunkPos(2,2));
        assertEquals(DhStatusSnapshot.Activity.IDLE,DhClientSession.diagnostics().activity(),"Parked work reads as idle, never as an unknown wait");
        }
    }
    @Test void dhSupportPreferencesAreNotResentForABandwidthChangeAlone() throws Exception {
        ((Map<?,?>)read(null,DhClientSession.class,"discoveredProfiles")).clear();
        @SuppressWarnings("unchecked") var profiles=(Map<Object,DhProfile>)read(null,DhClientSession.class,"discoveredProfiles");
        profiles.put(connection,DhProfile.SUPPORT_15);
        var s=session();write(s,SESSION,"announced",true);write(s,SESSION,"sentBandwidth",50);
        int before=sent.size();invoke(s,"tick");
        assertEquals(before,sent.size(),"Each Support config resets its buffer ids; bandwidth alone never re-announces");
    }
    @Test void receiveOffWithdrawsWheneverPreferencesWereAnnouncedEvenBeforeConfig() throws Exception {
        var s=session();write(s,SESSION,"announced",true);
        invoke(s,"beginRetire");
        assertEquals(1,sent.size());var withdrawal=(DhMessage.SessionConfig)codec(s).decode(sent.getFirst());
        assertFalse(withdrawal.updates());assertFalse(withdrawal.synchronize());
    }
    private void stationaryAbsence(int refreshSeconds) throws Exception {
        var s=session();long origin=System.nanoTime();var player=new ChunkPos(2,2);
        minecraft.player=mcMock(LocalPlayer.class);write(minecraft.player,LocalPlayer.class,"connection",connection);
        doReturn(player).when(minecraft.player).chunkPosition();
        settings.store().saveDraft(settings.store().read().hash(),Map.of("lod.distance_chunks",48));
        settings.commit(settings.prepareReload());
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.SURFACE_THEN_CHUNKS,
                32,0,0,0,1,false,0,true,2048,50,500));
        write(s,SESSION,"levelKey","test-world");
        for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)
            policy(s).accepted(new DhPosition(6,x,z),0xffff,0xffff,123,origin);
        int absentRequests=0,refreshRequests=0;var absentTiles=new HashSet<DhPosition>();
        // Three stationary hours at the actual ten-second refresh ceiling. Drive
        // the production scanner, sender, empty-response handling and worker.
        for(int second=0;second<=10_800;second+=10) {
            int before=sent.size();invoke(s,"requestNext",new Class<?>[]{long.class,ChunkPos.class},origin+second*1_000_000_000L,player);
            if(pendingMap(s).isEmpty())continue;
            pumpUntil(()->sent.size()>before,"scanner did not send its owned request");
            var request=(DhMessage.Request)codec(s).decode(sent.getLast());
            if(request.timestamp()!=null && request.timestamp()>0)refreshRequests++;
            else {absentRequests++;assertTrue(absentTiles.add(request.position()),"Unreachable absence was re-polled");}
            capture(codec(s).encode(new DhMessage.Response(request.requestId(),null,List.of())),System.nanoTime()).receive();
            pumpUntil(()->pendingMap(s).isEmpty()&&frameCount(s)==0,"empty response did not settle");
        }
        assertEquals(625-289,absentRequests);assertEquals(0,refreshRequests);
        assertTrue(active(s));assertEquals(0,budget(s).used());
    }
    private static Object invokeQuietly(Object s,String m){try{return invoke(s,m);}catch(Exception e){return e;}}
}
