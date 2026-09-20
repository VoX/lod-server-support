package dev.vox.lssfixture.concurrent;

import dev.vox.lssfixture.concurrent.SourceWorkload.Target;
import dev.vox.lssfixture.metrics.RigFabricMetrics;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.ChannelPressureProbe;
import dev.vox.lss.networking.server.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import java.io.RandomAccessFile;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Native 26.2 Fabric adapter for the same independent four-client target oracle. */
public final class FabricSourceProbe implements ModInitializer, SourceWorkload.Engine {
    private MinecraftServer server;
    private ServerLevel world;
    private SourceWorkload workload;
    private final dev.vox.lssfixture.concurrent.OwnerRevisions revisions=new dev.vox.lssfixture.concurrent.OwnerRevisions();
    private java.lang.reflect.Method visibleHolder;
    private final Set<Object> instrumented=Collections.newSetFromMap(new IdentityHashMap<>());
    private final AtomicLong denialReads=new AtomicLong();
    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(active->{
            if(active.usesAuthentication())throw new IllegalStateException("offline disposable workload only");
            server=active;world=server.overworld();
            try{
                visibleHolder=net.minecraft.server.level.ServerChunkCache.class.getDeclaredMethod("getVisibleChunkIfPresent",long.class);
                if(!visibleHolder.trySetAccessible())throw new IllegalAccessException("native holder lookup inaccessible");
            }catch(ReflectiveOperationException failure){throw new IllegalStateException("native nonloading holder lookup unavailable",failure);}
        });
        ServerTickEvents.END_SERVER_TICK.register(active->{
            if(server==null || LSSServerNetworking.getRequestService()==null)return;
            if(workload==null)workload=new SourceWorkload(this,world.getMinY());
            installPressure();workload.tick();
        });
        ServerPlayConnectionEvents.JOIN.register((handler,sender,active)->{
            String subject=handler.getPlayer().getName().getString();
            if(!subject.matches("RigSubject[ABCD]"))return;
            int index=subject.charAt(subject.length()-1)-'A';
            command("gamemode creative "+subject);
            command("tp "+subject+" "+(index*4096+.5)+" 100 .5");
            workload.join(subject);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler,active)->{if(workload!=null)workload.quit(handler.getPlayer().getName().getString());});
        ServerLifecycleEvents.SERVER_STOPPING.register(active->{if(workload!=null)workload.close();});
    }
    private void command(String command){server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),command);}
    private void installPressure() {
        var service=LSSServerNetworking.getRequestService();instrumented.retainAll(service.getPlayers().values());
        for(var state:service.getPlayers().values()) {
            String subject=state.getPlayerName();
            if(server.getPlayerList().getPlayerByName(subject)!=state.getPlayer())continue;
            workload.productRegistrationObserved(subject);
            if(!instrumented.add(state))continue;
            var actual=FabricChannelPressure.forPlayer(state.getPlayer());
            state.setChannelPressureProbe(new ChannelPressureProbe(){
                public long pendingOutboundBytes(){return snapshot().pendingBytes();}
                public Snapshot snapshot(){
                    if(workload.denied(subject)){if(workload.faultDenied(subject))denialReads.incrementAndGet();return new Snapshot(131072,65536,Writability.NOT_WRITABLE);}
                    return actual.snapshot();
                }
            });
        }
    }
    @Override public void seed(Target target) {
        world.getChunk(target.x(),target.z());
        if(target.source()==0)command("forceload add "+(target.x()*16)+" "+(target.z()*16));
        world.setBlock(new BlockPos(target.x()*16,target.y(),target.z()*16),Blocks.GOLD_BLOCK.defaultBlockState(),3);
    }
    @Override public void save(){command("save-all flush");}
    @Override public void makeUnloadedSources(Target store,Target disk) {
        long acquired=System.currentTimeMillis()/1000;
        var bytes=SectionSerializer.serializeColumn(world,world.getChunk(store.x(),store.z()),store.x(),store.z()).serializedSections();
        if(!LSSServerNetworking.getRequestService().getLodStore().deposit("minecraft:overworld",PositionUtil.packPosition(store.x(),store.z()),bytes,System.currentTimeMillis()/1000,acquired))
            throw new IllegalStateException("store preparation deposit refused");
        LSSServerNetworking.getRequestService().getLodStore().delete("minecraft:overworld",PositionUtil.packPosition(disk.x(),disk.z()));
        // No retained ticket was installed for these two chunks. Vanilla expires its
        // temporary acquisition ticket; readiness waits for getChunkNow to be null.
    }
    private boolean inMemory(Target target){
        try{
            var holder=(net.minecraft.server.level.ChunkHolder)visibleHolder.invoke(world.getChunkSource(),net.minecraft.world.level.ChunkPos.pack(target.x(),target.z()));
            return holder!=null && holder.getLatestChunk()!=null;
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("native holder observation failed",failure);}
    }
    private boolean diskPresent(Target target) {
        var path=net.minecraft.world.level.dimension.DimensionType.getStorageFolder(world.dimension(),server.getWorldPath(LevelResource.ROOT)).resolve("region/r."+(target.x()>>5)+"."+(target.z()>>5)+".mca");
        if(!java.nio.file.Files.exists(path))return false;
        try(var file=new RandomAccessFile(path.toFile(),"r")) {
            file.seek(4L*((target.x()&31)+(target.z()&31)*32));return file.readInt()!=0;
        }catch(java.io.IOException error){throw new IllegalStateException("region premise unreadable",error);}
    }
    @Override public boolean sourcesReady(List<Target> targets) {
        var store=LSSServerNetworking.getRequestService().getLodStore();
        for(Target target:targets) {
            boolean loaded=world.getChunkSource().getChunkNow(target.x(),target.z())!=null;
            if(target.source()==0){if(!loaded)return false;continue;}
            if(loaded)return false;
            boolean disk=diskPresent(target);
            var frame=store.getFrame("minecraft:overworld",PositionUtil.packPosition(target.x(),target.z()));
            if(target.source()==2 && (disk || frame!=null || inMemory(target)))throw new IllegalStateException("generation target was already materialized");
            if(target.source()==1 && (!disk || frame!=null))return false;
            if(target.source()==3 && (!disk || frame==null))return false;
        }
        return true;
    }
    @Override public List<Map<String,Object>> sourceFacts(List<Target> targets) {
        List<Map<String,Object>> facts=new java.util.ArrayList<>();
        var store=LSSServerNetworking.getRequestService().getLodStore();
        for(Target target:targets) {
            Map<String,Object> row=new java.util.LinkedHashMap<>();
            row.put("subject",target.subject());row.put("chunk_x",target.x());row.put("chunk_z",target.z());
            row.put("block_y",target.y());row.put("source",target.source());row.put("expected_block",target.block());
            row.put("loaded",world.getChunkSource().getChunkNow(target.x(),target.z())!=null);row.put("retained",world.getForceLoadedChunks().contains(net.minecraft.world.level.ChunkPos.pack(target.x(),target.z())));
            row.put("store_present",store.getFrame("minecraft:overworld",PositionUtil.packPosition(target.x(),target.z()))!=null);
            row.put("disk_present",diskPresent(target));row.put("in_memory_present",inMemory(target));facts.add(row);
        }
        return facts;
    }
    @Override public SourceWorkload.Ownership loadedOwnership(Target target){
        if(!server.isSameThread())throw new IllegalStateException("source ownership observation off native server thread");
        Thread owner=Thread.currentThread();
        return new SourceWorkload.Ownership(Map.of("owner_kind","server-thread","owns_thread",true,"observed_ns",System.nanoTime(),"owner_identity","server-thread:"+owner.threadId(),"thread_id",owner.threadId(),"thread_name",owner.getName(),"owner_name",target.subject()),owner);
    }
    @Override public java.util.concurrent.CompletionStage<SourceWorkload.EditOutcome> edit(Target target,SourceWorkload.Mutation expected,SourceWorkload.Ownership owner) {
        if(!server.isSameThread())throw new IllegalStateException("actual server mutation owner required");
        if(owner.identity()!=Thread.currentThread())throw new IllegalStateException("edit owner permit mismatch");
        var applied=revisions.mutate(target,expected,"server-thread:"+Thread.currentThread().threadId(),()->{world.setBlock(new BlockPos(target.x()*16,target.y(),target.z()*16),(target.block().equals("diamond_block")?Blocks.DIAMOND_BLOCK:Blocks.GOLD_BLOCK).defaultBlockState(),3);return System.nanoTime();});
        LSSServerNetworking.getRequestService().getDirtyTracker().markDirty("minecraft:overworld",target.x(),target.z());
        return java.util.concurrent.CompletableFuture.completedFuture(applied);
    }
    @Override public void kick(String subject){command("kick "+subject+" Owned rig reconnect control");}
    @Override public Map<String,Object> metrics(){RigFabricMetrics.sampleServerGauges();var result=RigFabricMetrics.buildServerMetrics();if(result!=null)result.put("adapter_denial_reads",denialReads.get());return result;}
}
