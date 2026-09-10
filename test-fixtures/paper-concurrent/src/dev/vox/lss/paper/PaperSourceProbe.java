package dev.vox.lss.paper;

import dev.vox.lssfixture.concurrent.SourceWorkload;
import dev.vox.lssfixture.concurrent.SourceWorkload.Target;
import dev.vox.lssfixture.metrics.RigPaperMetrics;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.ChannelPressureProbe;
import java.io.RandomAccessFile;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.*;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Disposable Paper-only source/pressure adapter, deliberately not Folia-compatible. */
public final class PaperSourceProbe extends JavaPlugin implements Listener, SourceWorkload.Engine {
    private SourceWorkload workload;
    private final dev.vox.lssfixture.concurrent.OwnerRevisions revisions=new dev.vox.lssfixture.concurrent.OwnerRevisions();
    private LSSPaperPlugin lss;
    private World world;
    private java.lang.reflect.Method serializeColumn;
    private final Set<Object> instrumented=Collections.newSetFromMap(new IdentityHashMap<>());
    private final AtomicLong denialReads=new AtomicLong();
    @Override public void onEnable() {
        if(Bukkit.getOnlineMode())throw new IllegalStateException("offline disposable workload only");
        lss=(LSSPaperPlugin)Bukkit.getPluginManager().getPlugin("LodServerSupport");
        try{
            // Bukkit plugins have separate classloaders: equal package names do
            // not grant package-private access. This explicit test-only lookup
            // invokes the unchanged serializer in the actual candidate loader.
            Class<?> serializer=Class.forName("dev.vox.lss.paper.PaperSectionSerializer",true,lss.getClass().getClassLoader());
            serializeColumn=serializer.getDeclaredMethod("serializeColumn",net.minecraft.server.level.ServerLevel.class,
                    net.minecraft.world.level.chunk.LevelChunk.class,int.class,int.class);
            if(!serializeColumn.trySetAccessible())throw new IllegalStateException("candidate serializer inaccessible");
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("candidate serializer contract absent",failure);}
        world=Bukkit.getWorlds().getFirst();
        Bukkit.getPluginManager().registerEvents(this,this);
        Bukkit.getScheduler().runTaskTimer(this,()-> {
            if(lss.getRequestService()==null)return;
            if(workload==null)workload=new SourceWorkload(this,world.getMinHeight());
            installPressure();workload.tick();
        },1,1);
    }
    private void installPressure() {
        var service=lss.getRequestService();instrumented.retainAll(service.getPlayers().values());
        for(var state:service.getPlayers().values()) {
            String subject=state.getPlayerName();
            var currentPlayer=Bukkit.getPlayerExact(subject);
            if(currentPlayer==null || ((org.bukkit.craftbukkit.entity.CraftPlayer)currentPlayer).getHandle()!=state.getPlayer())continue;
            workload.productRegistrationObserved(subject);
            if(!instrumented.add(state))continue;
            var actual=PaperChannelPressure.forPlayer(state.getPlayer());
            state.setChannelPressureProbe(new ChannelPressureProbe(){
                public long pendingOutboundBytes(){return snapshot().pendingBytes();}
                public Snapshot snapshot(){
                    if(workload.denied(subject)){if(workload.faultDenied(subject))denialReads.incrementAndGet();return new Snapshot(131072,65536,Writability.NOT_WRITABLE);}
                    return actual.snapshot();
                }
            });
        }
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        var player=event.getPlayer();String subject=player.getName();
        if(!subject.matches("RigSubject[ABCD]"))return;
        int index=subject.charAt(subject.length()-1)-'A';
        player.setGameMode(GameMode.CREATIVE);
        player.teleport(new Location(world,index*4096+.5,100,.5));
        workload.join(subject);
    }
    @EventHandler public void quit(PlayerQuitEvent event){if(workload!=null)workload.quit(event.getPlayer().getName());}
    @Override public void seed(Target target) {
        world.getChunkAt(target.x(),target.z());
        if(target.source()==0)world.setChunkForceLoaded(target.x(),target.z(),true);
        world.getBlockAt(target.x()*16,target.y(),target.z()*16).setType(Material.GOLD_BLOCK,false);
    }
    @Override public void save(){world.save();}
    @Override public void makeUnloadedSources(Target store,Target disk) {
        var level=((CraftWorld)world).getHandle();
        long acquired=System.currentTimeMillis()/1000;
        byte[] bytes;
        try{
            var serialized=(dev.vox.lss.common.processing.LoadedColumnData)serializeColumn.invoke(null,level,level.getChunk(store.x(),store.z()),store.x(),store.z());
            bytes=serialized.serializedSections();
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("candidate serializer invocation failed",failure);}
        if(!lss.getRequestService().getLodStore().deposit("minecraft:overworld",PositionUtil.packPosition(store.x(),store.z()),bytes,System.currentTimeMillis()/1000,acquired))
            throw new IllegalStateException("store preparation deposit refused");
        lss.getRequestService().getLodStore().delete("minecraft:overworld",PositionUtil.packPosition(disk.x(),disk.z()));
        world.unloadChunk(store.x(),store.z(),false);world.unloadChunk(disk.x(),disk.z(),false);
    }
    private boolean diskPresent(Target target) {
        var path=world.getWorldFolder().toPath().resolve("region/r."+(target.x()>>5)+"."+(target.z()>>5)+".mca");
        if(!java.nio.file.Files.exists(path))return false;
        try(var file=new RandomAccessFile(path.toFile(),"r")) {
            file.seek(4L*((target.x()&31)+(target.z()&31)*32));return file.readInt()!=0;
        }catch(java.io.IOException error){throw new IllegalStateException("region premise unreadable",error);}
    }
    @Override public boolean sourcesReady(List<Target> targets) {
        var store=lss.getRequestService().getLodStore();
        for(Target target:targets) {
            if(target.source()==0){if(!world.isChunkForceLoaded(target.x(),target.z()))return false;continue;}
            if(world.isChunkLoaded(target.x(),target.z())){world.unloadChunk(target.x(),target.z(),false);return false;}
            boolean disk=diskPresent(target);
            var frame=store.getFrame("minecraft:overworld",PositionUtil.packPosition(target.x(),target.z()));
            if(target.source()==2 && (disk || frame!=null || ((CraftWorld)world).getHandle().moonrise$getAnyChunkIfLoaded(target.x(),target.z())!=null))throw new IllegalStateException("generation target was already materialized");
            if(target.source()==1 && (!disk || frame!=null))return false;
            if(target.source()==3 && (!disk || frame==null))return false;
        }
        return true;
    }
    @Override public List<Map<String,Object>> sourceFacts(List<Target> targets) {
        List<Map<String,Object>> facts=new java.util.ArrayList<>();
        var store=lss.getRequestService().getLodStore();
        for(Target target:targets) {
            Map<String,Object> row=new java.util.LinkedHashMap<>();
            row.put("subject",target.subject());row.put("chunk_x",target.x());row.put("chunk_z",target.z());
            row.put("block_y",target.y());row.put("source",target.source());row.put("expected_block",target.block());
            row.put("loaded",world.isChunkLoaded(target.x(),target.z()));row.put("retained",world.isChunkForceLoaded(target.x(),target.z()));
            row.put("store_present",store.getFrame("minecraft:overworld",PositionUtil.packPosition(target.x(),target.z()))!=null);
            row.put("disk_present",diskPresent(target));
            row.put("in_memory_present",((CraftWorld)world).getHandle().moonrise$getAnyChunkIfLoaded(target.x(),target.z())!=null);facts.add(row);
        }
        return facts;
    }
    @Override public Map<String,Object> loadedOwnership(Target target){
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("source ownership observation off native server thread");
        Thread owner=Thread.currentThread();
        return Map.of("owner_kind","server-thread","owns_thread",true,"observed_ns",System.nanoTime(),"owner_identity","server-thread:"+owner.threadId(),"thread_id",owner.threadId(),"thread_name",owner.getName(),"owner_name",target.subject());
    }
    @Override public java.util.concurrent.CompletionStage<SourceWorkload.Applied> edit(Target target,SourceWorkload.Mutation expected) {
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("actual server mutation owner required");
        var applied=revisions.mutate(target,expected,"server-thread:"+Thread.currentThread().threadId(),()->{world.getBlockAt(target.x()*16,target.y(),target.z()*16).setType(target.block().equals("diamond_block")?Material.DIAMOND_BLOCK:Material.GOLD_BLOCK,false);return System.nanoTime();});
        lss.getRequestService().getDirtyTracker().markDirty("minecraft:overworld",target.x(),target.z());
        return java.util.concurrent.CompletableFuture.completedFuture(applied);
    }
    @Override public void kick(String subject){var player=Bukkit.getPlayerExact(subject);if(player!=null)player.kick(net.kyori.adventure.text.Component.text("Owned rig reconnect control"));}
    @Override public Map<String,Object> metrics(){var service=lss.getRequestService();RigPaperMetrics.sampleServerGauges(service);var result=RigPaperMetrics.buildServerMetrics(service);if(result!=null)result.put("adapter_denial_reads",denialReads.get());return result;}
    @Override public void onDisable(){if(workload!=null)workload.close();}
}
