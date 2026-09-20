package dev.vox.lssfixture.regions;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.Collections;
import dev.vox.lss.paper.LSSPaperPlugin;
import dev.vox.lss.paper.PaperChannelPressure;
import dev.vox.lss.common.processing.ChannelPressureProbe;
import dev.vox.lssfixture.metrics.RigPaperMetrics;
import com.google.gson.Gson;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.threadedregions.TickRegionScheduler;

/** Test-only bounded owning-region hash workload. No tick barriers or cross-region reads.
 * Folia26.2 Region.id is a constructor-assigned AtomicLong sequence, so split/merge
 * replacement objects get distinct lifetime IDs. getCurrentRegion is sampled
 * inside the entity scheduler callback and paired with Bukkit's ownership check.
 * Callback duration is NOT claimed to be full tick duration; separate tick probes
 * are needed for the performance gate. Queue overflow makes evidence incomplete.
 */
public final class RegionProbe extends JavaPlugin implements Listener {
    private final ArrayBlockingQueue<String> output = new ArrayBlockingQueue<>(32768);
    private final Map<UUID, String> sessions = new ConcurrentHashMap<>();
    private final Map<UUID,Object> joinedHandles=new ConcurrentHashMap<>();
    private final Map<Object,String> observedRegistrations=new IdentityHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, AtomicLong> connectionIndexes = new ConcurrentHashMap<>();
    private final Set<UUID> workloadStarted=ConcurrentHashMap.newKeySet();
    private final Map<UUID,String> previousConnections=new ConcurrentHashMap<>();
    private final Set<UUID> prepared=ConcurrentHashMap.newKeySet();
    private final AtomicLong workloadOrigin=new AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean churnIssued=new java.util.concurrent.atomic.AtomicBoolean();
    private final ArrayBlockingQueue<String> oracle = new ArrayBlockingQueue<>(8192);
    private record PendingEdit(String id,String subject,long offered,Runnable apply) {}
    private final ArrayBlockingQueue<PendingEdit> pendingEdits=new ArrayBlockingQueue<>(1024);
    private volatile boolean running, overflow;
    private Thread writer;
    private String runId;
    private LSSPaperPlugin lssPlugin;
    private long origin;
    private final Set<Object> instrumented=Collections.newSetFromMap(new IdentityHashMap<>());
    private final AtomicLong admissionReads=new AtomicLong();

    @Override public void onEnable() {
        runId = System.getProperty("lss.rig.runId", "");
        if (!runId.matches("[A-Za-z0-9_-]+")) throw new IllegalStateException("explicit run identity required");
        origin = System.nanoTime();
        Path destination = Path.of(System.getProperty("lss.rig.evidence", ""));
        if (!destination.isAbsolute() || Files.isSymbolicLink(destination)) throw new IllegalStateException("owned absolute evidence directory required");
        running = true;
        writer = new Thread(() -> {
            try (BufferedWriter stream = Files.newBufferedWriter(destination.resolve("region-events.jsonl"));
                 BufferedWriter targets = Files.newBufferedWriter(destination.resolve(Boolean.getBoolean("lss.rig.regionOnly")?"region-oracle-unused.jsonl":"oracle.jsonl"))) {
                while (running || !output.isEmpty() || !oracle.isEmpty()) {
                    String row = output.poll(200, TimeUnit.MILLISECONDS);
                    if (row != null) { stream.write(row); stream.newLine(); stream.flush(); }
                    String target; while ((target = oracle.poll()) != null) { targets.write(target); targets.newLine(); }
                    targets.flush();
                    if(running)applyAcknowledged(destination);
                }
                stream.write("{\"event\":\"writer_closed\",\"overflow\":" + overflow + "}\n");
            } catch (IOException | InterruptedException e) { overflow = true; }
        }, "LSS-RigEvidence");
        writer.setDaemon(true); writer.start();
        Bukkit.getPluginManager().registerEvents(this, this);
        lssPlugin=(LSSPaperPlugin)Bukkit.getPluginManager().getPlugin("LodServerSupport");
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this,task->{
            var service=lssPlugin.getRequestService();if(service==null)return;
            observedRegistrations.keySet().retainAll(service.getPlayers().values());
            for(var state:service.getPlayers().values()){
                String connection=sessions.get(state.getPlayerUUID());
                if(connection==null||state.getPlayer()!=joinedHandles.get(state.getPlayerUUID())||connection.equals(observedRegistrations.get(state)))continue;
                observedRegistrations.put(state,connection);
                emit("{\"event\":\"product_registration_observed\",\"subject\":\""+state.getPlayerName()+"\",\"connection_id\":\""+connection+"\",\"time_ns\":"+now()+"}");
            }
        },1,1);
        if (Boolean.getBoolean("lss.rig.workload")) {
            if (Bukkit.getOnlineMode()) throw new IllegalStateException("offline disposable workload only");
            var lss=(LSSPaperPlugin)Bukkit.getPluginManager().getPlugin("LodServerSupport");
            lssPlugin=lss;
            Gson json=new Gson();
            UUID denied=UUID.nameUUIDFromBytes("OfflinePlayer:RigSubjectC".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
                var service=lss.getRequestService();if(service==null)return;
                if(prepared.size()==4 && service.getPlayers().values().stream().filter(state->state.getPlayerName().matches("RigSubject[ABCD]")).count()==4 && workloadOrigin.compareAndSet(0,now())){
                    long start=workloadOrigin.get();
                    offerOracle("{\"event\":\"workload_start\",\"time_ns\":"+start+"}");
                    long denialStart=start+Long.getLong("lss.rig.admissionAfterSeconds",200L)*1_000_000_000L;
                    offerOracle("{\"event\":\"send_admission\",\"subject\":\"RigSubjectC\",\"start_ns\":"+denialStart+",\"end_ns\":"+(denialStart+20_000_000_000L)+"}");
                    long slowStart=start+Long.getLong("lss.rig.slowAfterSeconds",160L)*1_000_000_000L;
                    offerOracle("{\"event\":\"slow_consumer\",\"subject\":\"RigSubjectD\",\"start_ns\":"+slowStart+",\"end_ns\":"+(slowStart+20_000_000_000L)+"}");
                    getLogger().info("LSS_RIG_WORKLOAD_READY");
                }
                instrumented.retainAll(service.getPlayers().values());
                for(var state:service.getPlayers().values()) {
                    if(!state.getPlayerUUID().equals(denied)||!instrumented.add(state))continue;
                    var actual=PaperChannelPressure.forPlayer(state.getPlayer());
                    state.setChannelPressureProbe(new ChannelPressureProbe(){
                        public long pendingOutboundBytes(){return snapshot().pendingBytes();}
                        public Snapshot snapshot(){
                            long now=System.nanoTime();
                            long start=workloadOrigin.get(),denialStart=start+Long.getLong("lss.rig.admissionAfterSeconds",200L)*1_000_000_000L;
                            if(start!=0&&now>=denialStart&&now<denialStart+20_000_000_000L){admissionReads.incrementAndGet();return new Snapshot(131072,65536,Writability.NOT_WRITABLE);}
                            return actual.snapshot();
                        }
                    });
                }
                var metrics=RigPaperMetrics.buildServerMetrics(service);
                if(metrics!=null){metrics.put("event","product_metrics");metrics.put("time_ns",now());metrics.put("adapter_denial_reads",admissionReads.get());emit(json.toJson(metrics));}
            },20,20);
        }
        emit("{\"event\":\"ready\",\"run_id\":\"" + runId + "\"}");
    }
    private String bind(String row){return row.contains("\"run_id\"")?row:"{\"run_id\":\""+runId+"\","+row.substring(1);}
    private void emit(String value) { if (!output.offer(bind(value))) overflow = true; }
    private long now() { return System.nanoTime(); }
    @EventHandler public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Offline test subjects are selected explicitly; an ordinary player is untouched.
        if (!player.getName().matches("RigSubject[ABCD]")) return;
        long connectionIndex = connectionIndexes.computeIfAbsent(player.getName(), ignored -> new AtomicLong()).incrementAndGet();
        String connection = Boolean.getBoolean("lss.rig.regionOnly") ? runId+"-"+player.getName()+"-"+connectionIndex : runId + "-" + sequence.incrementAndGet();
        sessions.put(player.getUniqueId(), connection);
        joinedHandles.put(player.getUniqueId(),((org.bukkit.craftbukkit.entity.CraftPlayer)player).getHandle());
        String previous=previousConnections.put(player.getUniqueId(),connection);
        if(previous!=null)offerOracle("{\"event\":\"session_transfer\",\"subject\":\""+player.getName()+"\",\"old_connection\":\""+previous+"\",\"connection_id\":\""+connection+"\",\"time_ns\":"+now()+"}");
        offerOracle("{\"event\":\"session\",\"subject\":\""+player.getName()+"\",\"connection_id\":\""+connection+"\",\"connection_index\":"+connectionIndex+"}");
        emit("{\"event\":\"join\",\"subject\":\"" + player.getName() + "\",\"connection_id\":\"" + connection + "\",\"time_ns\":" + now() + "}");
        int index = player.getName().charAt(player.getName().length()-1) - 'A';
        boolean oneRegion = Boolean.getBoolean("lss.rig.oneRegionControl");
        Location target = new Location(player.getWorld(), oneRegion ? 0.5 : index * 4096 + 0.5, 100, 0.5);
        player.getScheduler().runDelayed(this, ignored -> { player.setGameMode(org.bukkit.GameMode.CREATIVE); player.teleportAsync(target).thenAccept(success -> {
            if (!success) { emit("{\"event\":\"teleport_failed\"}"); return; }
            prepared.add(player.getUniqueId());
            player.getScheduler().runAtFixedRate(this, task -> sample(player, connection), () -> {}, 1, 1);
            if (Boolean.getBoolean("lss.rig.workload") && workloadStarted.add(player.getUniqueId())) startWorkload(player, connection);
        }); }, () -> {}, 20);
    }
    private void offerOracle(String row) { if (!oracle.offer(bind(row))) overflow = true; }
    /** Evidence-worker I/O only; world mutation remains in its legal owner callback. */
    private void applyAcknowledged(Path destination) throws IOException {
        Set<String> acknowledged=new java.util.HashSet<>();
        for(char letter='A';letter<='D';letter++){
            String subject="RigSubject"+letter;
            Path path=destination.resolve("oracle-ack-"+subject+".json");
            if(!Files.isRegularFile(path)||Files.isSymbolicLink(path))continue;
            if(Files.size(path)>262144)throw new IOException("oversized oracle acknowledgment");
            var row=com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if(!runId.equals(row.get("run_id").getAsString())||!subject.equals(row.get("subject").getAsString()))throw new IOException("foreign oracle acknowledgment");
            UUID uuid=UUID.nameUUIDFromBytes(("OfflinePlayer:"+subject).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if(!row.get("connection_id").getAsString().equals(sessions.get(uuid)))continue;
            for(var id:row.getAsJsonArray("targets"))acknowledged.add(subject+":"+id.getAsString());
        }
        int count=pendingEdits.size();
        while(count-->0){
            PendingEdit edit=pendingEdits.poll();if(edit==null)break;
            if(acknowledged.contains(edit.subject()+":"+edit.id())){
                offerOracle("{\"event\":\"target_acknowledged\",\"id\":\""+edit.id()+"\",\"time_ns\":"+now()+"}");
                edit.apply().run();
            }else if(now()-edit.offered()>30_000_000_000L){
                emit("{\"event\":\"target_ack_timeout\",\"id\":\""+edit.id()+"\"}");overflow=true;
            }else if(!pendingEdits.offer(edit))overflow=true;
        }
    }
    private void startWorkload(Player player, String connection) {
        UUID playerId=player.getUniqueId();String subject=player.getName();
        var world=player.getWorld();int baseX=(subject.charAt(subject.length()-1)-'A')*256;
        int[] offered = {0};
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            long start=workloadOrigin.get();if(start==0)return;
            if(subject.equals("RigSubjectB")&&now()-start>=Long.getLong("lss.rig.churnAfterSeconds",360L)*1_000_000_000L&&churnIssued.compareAndSet(false,true)){
                player.getScheduler().run(this,owned->{
                    offerOracle("{\"event\":\"disconnect\",\"subject\":\"RigSubjectB\",\"connection_id\":\""+connection+"\",\"time_ns\":"+now()+"}");
                    player.kick(net.kyori.adventure.text.Component.text("Owned rig reconnect control"));
                },()->{});
            }
            int sequence = offered[0]++;
            if (sequence >= 420 || now()-start >= Long.getLong("lss.rig.offerSeconds",720L)*1_000_000_000L) { task.cancel(); return; }
            int x = baseX + 8 + sequence % 16;
            int z = (sequence / 16) % 4;
            int y = 64;
            String targetConnection=previousConnections.get(playerId);
            String id = connection + "-target-" + sequence;
            Material block = (sequence / 64) % 2 == 0 ? Material.GOLD_BLOCK : Material.DIAMOND_BLOCK;
            String expected = block == Material.GOLD_BLOCK ? "gold_block" : "diamond_block";
            long offeredAt = now();
            offerOracle("{\"event\":\"target\",\"id\":\""+id+"\",\"subject\":\""+subject+"\",\"connection_id\":\""+targetConnection+"\",\"chunk_x\":"+x+",\"chunk_z\":"+z+",\"block_y\":"+y+",\"expected_source\":0,\"requires_ack\":true,\"expected_block\":\""+expected+"\",\"offered_ns\":"+offeredAt+"}");
            if(!pendingEdits.offer(new PendingEdit(id,subject,offeredAt,()->world.getChunkAtAsync(x, z, true).thenAccept(chunk ->
                Bukkit.getRegionScheduler().run(this, new Location(world, x*16, y, z*16), ignored -> {
                    if (!Bukkit.isOwnedByCurrentRegion(world, x, z)) { emit("{\"event\":\"ownership_failed\"}"); return; }
                    world.addPluginChunkTicket(x,z,this);
                    world.getBlockAt(x*16, y, z*16).setType(block, false);
                    long mutationTime=now();
                    // Plugin mutations do not fire Bukkit placement events. Use
                    // the existing integration boundary explicitly, rather than
                    // claiming an event occurred or waiting for unrelated saves.
                    lssPlugin.getRequestService().getDirtyTracker().markDirty(world.getKey().toString(),x,z);
                    offerOracle("{\"event\":\"edit_applied\",\"id\":\""+id+"\",\"dirty_notified\":true,\"retained_loaded\":true,\"time_ns\":"+mutationTime+"}");
                })).exceptionally(error -> { emit("{\"event\":\"target_edit_failed\"}"); return null; }))))overflow=true;
        }, 20, 40);
    }
    private void sample(Player player, String connection) {
        if (!connection.equals(sessions.get(player.getUniqueId()))) return;
        long start = now();
        boolean owns = Bukkit.isOwnedByCurrentRegion(player);
        var region = TickRegionScheduler.getCurrentRegion();
        if (!owns || region == null) {
            emit("{\"event\":\"ownership_failed\"}"); return;
        }
        Location location = player.getLocation();
        // Hash only the occupied chunk (legal owner access); no remote chunk loads.
        int bx = location.getBlockX() & ~15, bz = location.getBlockZ() & ~15;
        long hash = 1;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 60; y < 76; y++)
            hash = hash * 31 + player.getWorld().getBlockAt(bx + x, y, bz + z).getType().ordinal();
        long end = now();
        emit("{\"event\":\"owning_work\",\"subject\":\"" + player.getName()
            + "\",\"connection_id\":\"" + connection + "\",\"context\":\"owning-region\",\"owns_region\":true,\"region_identity\":\""
            + region.id + "\",\"start_ns\":" + start + ",\"end_ns\":" + end + ",\"hash\":" + hash + "}");
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        String connection = sessions.remove(event.getPlayer().getUniqueId());
        joinedHandles.remove(event.getPlayer().getUniqueId());
        if (connection != null) emit("{\"event\":\"quit\",\"subject\":\"" + event.getPlayer().getName()
            + "\",\"connection_id\":\"" + connection + "\",\"time_ns\":" + now() + "}");
    }
    @Override public void onDisable() {
        for (var session : sessions.entrySet()) emit("{\"event\":\"session_end\",\"connection_id\":\"" + session.getValue() + "\",\"time_ns\":" + now() + "}");
        if(!pendingEdits.isEmpty())overflow=true;
        running = false;
        try { if (writer != null) writer.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
