package dev.vox.lssfixture.concurrent;

import dev.vox.lss.paper.*;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.ChannelPressureProbe;
import dev.vox.lssfixture.metrics.RigPaperMetrics;
import dev.vox.lssfixture.concurrent.SourceWorkload.Target;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.*;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Snapshot-only Folia source adapter. No synchronous cross-region seed/save calls. */
public final class FoliaSourceProbe extends JavaPlugin implements Listener,SourceWorkload.Engine {
    private LSSPaperPlugin lss;
    private World world;
    private volatile SourceWorkload workload;
    private final OwnerRevisions revisions=new OwnerRevisions();
    private final Set<Object> instrumented=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> loaded=ConcurrentHashMap.newKeySet();
    private final Set<String> corridorLoaded=ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean corridorScheduled=new java.util.concurrent.atomic.AtomicBoolean();
    private final Map<String,java.util.concurrent.CopyOnWriteArrayList<Target>> retainedTargets=new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String,Map<String,Object>> ownerDiagnostics=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,OwnerObservation<org.bukkit.entity.Player>> owners=new ConcurrentHashMap<>();
    private final AtomicLong deniedReads=new AtomicLong();
    private volatile boolean checked,checking,initialChecking;
    private volatile List<Map<String,Object>> initialFacts;
    private volatile Throwable preparationFailure;
    private volatile List<Map<String,Object>> sourceFacts=List.of();
    @Override public void onEnable(){
        if(Bukkit.getOnlineMode()||!Boolean.getBoolean("lss.rig.regionOnly")||
                !System.getProperty("lss.rig.seedSnapshotDigest","").matches("[0-9a-f]{64}"))
            throw new IllegalStateException("owned offline region-only run with exact seeded snapshot required");
        lss=(LSSPaperPlugin)Bukkit.getPluginManager().getPlugin("LodServerSupport");
        world=Bukkit.getWorlds().getFirst();
        Bukkit.getPluginManager().registerEvents(this,this);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this,task->{
            if(lss.getRequestService()==null)return;
            if(workload==null)workload=new SourceWorkload(this,world.getMinHeight());
            installPressure();workload.tick();
        },1,1);
    }
    private void installPressure(){
        var service=lss.getRequestService();instrumented.retainAll(service.getPlayers().values());
        for(var state:service.getPlayers().values()){
            String subject=state.getPlayerName();
            var currentPlayer=Bukkit.getPlayerExact(subject);
            if(currentPlayer==null || ((org.bukkit.craftbukkit.entity.CraftPlayer)currentPlayer).getHandle()!=state.getPlayer())continue;
            workload.productRegistrationObserved(subject);
            if(!instrumented.add(state))continue;
            var actual=PaperChannelPressure.forPlayer(state.getPlayer());
            state.setChannelPressureProbe(new ChannelPressureProbe(){
                public long pendingOutboundBytes(){return snapshot().pendingBytes();}
                public Snapshot snapshot(){
                    if(workload.denied(subject)){
                        if(workload.faultDenied(subject))deniedReads.incrementAndGet();
                        return new Snapshot(131072,65536,Writability.NOT_WRITABLE);
                    }
                    return actual.snapshot();
                }
            });
        }
    }
    @EventHandler public void join(PlayerJoinEvent event){
        var player=event.getPlayer();String subject=player.getName();
        if(!subject.matches("RigSubject[ABCD]"))return;
        // The retained corridor joins each subject's positive-side loaded cells
        // into its real player region. Observe that fact on the actual owner,
        // and keep refreshing it before each later loaded mutation.
        var observation=new OwnerObservation<org.bukkit.entity.Player>();
        owners.put(subject,observation);
        player.getScheduler().runAtFixedRate(this,task->{
            SourceWorkload currentWorkload=workload;
            if(currentWorkload==null)return;
            if(owners.get(subject)!=observation)return; // Retired connection cannot replace its successor.
            int index=subject.charAt(subject.length()-1)-'A';
            DiagnosticGuard.observe(()->observeOwnerDiagnostic(subject,player,index),
                    failure->currentWorkload.diagnosticFailure("native-owner",failure));
            var position=player.getLocation();
            boolean anchor=position.getBlockX()==index*4096;
            var region=TickRegionScheduler.getCurrentRegion();
            var targets=retainedTargets.get(subject);
            boolean owns=region!=null && Bukkit.isOwnedByCurrentRegion(player) && targets!=null && !targets.isEmpty();
            if(owns)for(Target target:targets)if(!Bukkit.isOwnedByCurrentRegion(world,target.x(),target.z())){owns=false;break;}
            boolean firstJoin=observation.refresh(player,System.nanoTime(),region==null?"":String.valueOf(region.id),owns,anchor);
            DiagnosticGuard.observe(()->{
                var transition=observation.transition(anchor,position.getBlockX(),position.getBlockZ());
                if(transition!=null)currentWorkload.ownerTransition(subject,transition);
            },failure->currentWorkload.diagnosticFailure("owner-transition",failure));
            if(firstJoin)Bukkit.getGlobalRegionScheduler().run(this,ignored->{
                if(owners.get(subject)==observation && Bukkit.getPlayerExact(subject)==player)currentWorkload.join(subject);
            });
        },()->owners.remove(subject,observation),30,1);
    }
    @EventHandler public void quit(PlayerQuitEvent event){
        String subject=event.getPlayer().getName();
        Bukkit.getGlobalRegionScheduler().run(this,ignored->{if(workload!=null)workload.quit(subject);});
    }
    @Override public void seed(Target target){
        if(target.source()!=0)return;
        retainCorridors();
        retainedTargets.computeIfAbsent(target.subject(),ignored->new java.util.concurrent.CopyOnWriteArrayList<>()).add(target);
        world.getChunkAtAsync(target.x(),target.z(),false).thenAccept(chunk->
            Bukkit.getRegionScheduler().run(this,new Location(world,target.x()*16,target.y(),target.z()*16),ignored->{
                try{
                    if(chunk==null||!Bukkit.isOwnedByCurrentRegion(world,target.x(),target.z()))throw new IllegalStateException("loaded premise owner absent");
                    if(world.getBlockAt(target.x()*16,target.y(),target.z()*16).getType()!=Material.GOLD_BLOCK)
                        throw new IllegalStateException("seed snapshot loaded target mismatch");
                    world.addPluginChunkTicket(target.x(),target.z(),this);loaded.add(target.x()+":"+target.z());
                }catch(Throwable failure){preparationFailure=failure;}
            })).exceptionally(error->{preparationFailure=error;return null;});
    }
    private void retainCorridors(){
        if(!corridorScheduled.compareAndSet(false,true))return;
        for(int subject=0;subject<4;subject++){
            int origin=subject*256;
            for(int x=0;x<=12;x++)retainCorridor(origin+x,0);
            for(int z=1;z<=8;z++)retainCorridor(origin+8,z);
        }
    }
    private void retainCorridor(int x,int z){
        world.getChunkAtAsync(x,z,false).thenAccept(chunk->
            Bukkit.getRegionScheduler().run(this,new Location(world,x*16,64,z*16),ignored->{
                try{
                    if(chunk==null||!Bukkit.isOwnedByCurrentRegion(world,x,z))throw new IllegalStateException("native prefilled corridor chunk absent");
                    world.addPluginChunkTicket(x,z,this);corridorLoaded.add(x+":"+z);
                }catch(Throwable failure){preparationFailure=failure;}
            })).exceptionally(error->{preparationFailure=error;return null;});
    }
    @Override public SourceWorkload.Ownership loadedOwnership(Target target){
        var observation=owners.get(target.subject());
        var snapshot=observation==null?null:observation.snapshot();
        long now=System.nanoTime();
        if(!OwnerObservation.available(snapshot,Bukkit.getPlayerExact(target.subject()),now))return null;
        return new SourceWorkload.Ownership(Map.of("observed_ns",snapshot.time(),"region_identity",snapshot.region(),"owns_region",true,"owner_name",target.subject()),snapshot.player());
    }
    /** Opt-in native observation on the existing player owner, at most1Hz per subject. */
    private void observeOwnerDiagnostic(String subject,org.bukkit.entity.Player player,int index){
        if(!Boolean.getBoolean("lss.rig.sourceDiagnostics")||Boolean.getBoolean("lss.rig.measuredWorkload"))return;
        long now=System.nanoTime();var old=ownerDiagnostics.get(subject);
        if(old!=null&&now-((Number)old.get("time_ns")).longValue()<1_000_000_000L)return;
        var position=player.getLocation();var region=TickRegionScheduler.getCurrentRegion();
        Map<String,Object> value=new java.util.LinkedHashMap<>();value.put("time_ns",now);
        value.put("player_block_x",position.getBlockX());value.put("player_block_z",position.getBlockZ());
        value.put("anchor_x_matches",position.getBlockX()==index*4096);
        value.put("region_identity",region==null?"":String.valueOf(region.id));
        java.util.List<Map<String,Object>> cells=new java.util.ArrayList<>();
        var targets=retainedTargets.get(subject);
        if(targets!=null)for(Target target:targets){
            boolean owns=Bukkit.isOwnedByCurrentRegion(world,target.x(),target.z());
            Map<String,Object> cell=new java.util.LinkedHashMap<>();cell.put("chunk_x",target.x());cell.put("chunk_z",target.z());cell.put("owns_region",owns);
            if(owns){cell.put("loaded",world.isChunkLoaded(target.x(),target.z()));
                cell.put("fixture_ticket_present",world.getPluginChunkTickets(target.x(),target.z()).contains(this));}
            cells.add(Map.copyOf(cell));
        }
        value.put("targets",java.util.List.copyOf(cells));ownerDiagnostics.put(subject,Map.copyOf(value));
    }
    /** Read immutable owner snapshots; never read native chunk state on the global thread. */
    @Override public Map<String,Object> ownershipDiagnostic(Target target){
        var observation=owners.get(target.subject());
        var snapshot=observation==null?null:observation.snapshot();long now=System.nanoTime();
        Map<String,Object> row=new java.util.LinkedHashMap<>();
        row.put("observed_ns",now);row.put("chunk_x",target.x());row.put("chunk_z",target.z());
        String reason=snapshot==null?"missing_snapshot":!snapshot.ownsAll()?"target_set_not_owned":
                now-snapshot.time()>250_000_000L?"stale_snapshot":
                Bukkit.getPlayerExact(target.subject())!=snapshot.player()?"different_current_player":"owner_available";
        row.put("reason",reason);
        var nativeSample=ownerDiagnostics.get(target.subject());if(nativeSample!=null)row.put("latest_native_sample",nativeSample);
        if(snapshot!=null){row.put("snapshot_ns",snapshot.time());row.put("snapshot_age_ns",now-snapshot.time());
            row.put("region_identity",snapshot.region());row.put("owns_all",snapshot.ownsAll());
            row.put("current_player_matches",Bukkit.getPlayerExact(target.subject())==snapshot.player());}
        return row;
    }
    @Override public void save(){} // Clean snapshot save was witnessed before this run.
    @Override public void makeUnloadedSources(Target store,Target disk){} // Never load these corners.
    private List<Map<String,Object>> observeFacts(List<Target> targets){
        var level=((CraftWorld)world).getHandle();var store=lss.getRequestService().getLodStore();
        List<Map<String,Object>> facts=new ArrayList<>();
        for(Target target:targets){
            boolean isLoaded=level.getChunkSource().getChunkNow(target.x(),target.z())!=null;
            boolean any=level.moonrise$getAnyChunkIfLoaded(target.x(),target.z())!=null;
            Path file=world.getWorldFolder().toPath().resolve("region/r."+(target.x()>>5)+"."+(target.z()>>5)+".mca");
            boolean disk=false;
            if(Files.isRegularFile(file))try(var input=new RandomAccessFile(file.toFile(),"r")){
                input.seek(4L*((target.x()&31)+(target.z()&31)*32));disk=input.readInt()!=0;
            }catch(java.io.IOException failure){throw new IllegalStateException("native region premise unreadable",failure);}
            var frame=store.getFrame("minecraft:overworld",PositionUtil.packPosition(target.x(),target.z()));
            Map<String,Object> row=new LinkedHashMap<>(Map.of("subject",target.subject(),"chunk_x",target.x(),"chunk_z",target.z(),"block_y",target.y(),"source",target.source(),
                    "loaded",isLoaded,"retained",target.source()==0&&loaded.contains(target.x()+":"+target.z()),"store_present",frame!=null,"disk_present",disk,"expected_block",target.block()));
            row.put("in_memory_present",any);facts.add(row);
        }
        return List.copyOf(facts);
    }
    @Override public List<Map<String,Object>> initialGenerationFacts(List<Target> targets){
        if(preparationFailure!=null)throw new IllegalStateException("initial snapshot premise failed",preparationFailure);
        if(!initialChecking){
            initialChecking=true;
            Bukkit.getAsyncScheduler().runNow(this,task->{try{initialFacts=observeFacts(targets);}catch(Throwable failure){preparationFailure=failure;}});
        }
        return initialFacts;
    }
    @Override public boolean sourcesReady(List<Target> targets){
        if(preparationFailure!=null)throw new IllegalStateException("snapshot source premise failed",preparationFailure);
        if(corridorLoaded.size()!=84 || loaded.size()!=targets.stream().filter(target->target.source()==0).count())return false;
        if(!checking){
            checking=true;
            Bukkit.getAsyncScheduler().runNow(this,task->{
                try{
                    var facts=observeFacts(targets);
                    for(var fact:facts){
                        int source=(Integer)fact.get("source");
                        boolean isLoaded=(Boolean)fact.get("loaded"),disk=(Boolean)fact.get("disk_present"),store=(Boolean)fact.get("store_present"),any=(Boolean)fact.get("in_memory_present");
                        if(source!=0&&isLoaded || source==2&&(disk||store||any) || source==1&&(!disk||store) || source==3&&(!disk||!store))
                            throw new IllegalStateException("snapshot store/disk/generation premise mismatch");
                    }
                    sourceFacts=facts;checked=true;
                }catch(Throwable failure){preparationFailure=failure;}
            });
        }
        return checked;
    }
    @Override public List<Map<String,Object>> sourceFacts(List<Target> targets){
        if(!checked||sourceFacts.size()!=targets.size())throw new IllegalStateException("snapshot facts incomplete");
        return sourceFacts;
    }
    @Override public CompletionStage<SourceWorkload.EditOutcome> edit(Target target,SourceWorkload.Mutation expected,SourceWorkload.Ownership owner){
        CompletableFuture<SourceWorkload.EditOutcome> result=new CompletableFuture<>();
        Bukkit.getRegionScheduler().run(this,new Location(world,target.x()*16,target.y(),target.z()*16),ignored->{
            try{
                if(!Bukkit.isOwnedByCurrentRegion(world,target.x(),target.z()))throw new IllegalStateException("edit owner absent");
                var player=Bukkit.getPlayerExact(target.subject());var region=TickRegionScheduler.getCurrentRegion();
                // This result is emitted only before touching the revision ledger or world.
                // A successor cannot spend the old player's acknowledged authorization.
                if(!owner.isCurrent(player,System.nanoTime()) || region==null || !Bukkit.isOwnedByCurrentRegion(player)){
                    result.complete(SourceWorkload.OwnerUnavailableBeforeMutation.INSTANCE);return;
                }
                var applied=revisions.mutate(target,expected,String.valueOf(region.id),()->{
                    world.getBlockAt(target.x()*16,target.y(),target.z()*16).setType(target.block().equals("diamond_block")?Material.DIAMOND_BLOCK:Material.GOLD_BLOCK,false);return System.nanoTime();
                });
                lss.getRequestService().getDirtyTracker().markDirty("minecraft:overworld",target.x(),target.z());result.complete(applied);
            }catch(Throwable failure){result.completeExceptionally(failure);}
        });
        return result;
    }
    @Override public void kick(String subject){
        var player=Bukkit.getPlayerExact(subject);if(player!=null)player.getScheduler().run(this,task->
            player.kick(net.kyori.adventure.text.Component.text("Owned rig reconnect control")),()->{});
    }
    @Override public Map<String,Object> metrics(){
        var service=lss.getRequestService();RigPaperMetrics.sampleServerGauges(service);
        var result=RigPaperMetrics.buildServerMetrics(service);if(result!=null)result.put("adapter_denial_reads",deniedReads.get());return result;
    }
    @Override public void onDisable(){if(workload!=null)workload.close();}
}
