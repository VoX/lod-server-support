package dev.vox.lssfixture.concurrent;

import dev.vox.lss.api.*;
import dev.vox.lssfixture.concurrent.OracleJournal.Target;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.google.gson.*;

/** External independent target consumer. Uses existing LSSApi receipt ownership. */
public final class ClientProbe implements ClientModInitializer, VoxelColumnConsumer {
    private record Wire(long captureId,int x,int z,long timestamp,long session,int bytes,int source,long received,String dimension,String connectionId,Object nativeConnection) {}
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private static final RejectionTelemetry REJECTIONS=new RejectionTelemetry(Boolean.getBoolean("lss.rig.rejectionDiagnostics"));
    private static final AtomicLong BODY_SEQUENCE=new AtomicLong(),WIRE_SEQUENCE=new AtomicLong();
    private static String lastAcknowledgment="";
    private static OracleJournal journal;
    private static BridgeObservation bridge;
    private static final ArrayBlockingQueue<String> OUTPUT=new ArrayBlockingQueue<>(8192);
    private static final ArrayBlockingQueue<PendingAcceptance> HELD=new ArrayBlockingQueue<>(128);
    private static final Semaphore HELD_SLOTS=new Semaphore(128);
    private static final PendingAcceptance.Admission HELD_ADMISSION=new PendingAcceptance.Admission(HELD_SLOTS);
    private static final IdentityCaptures<Wire> WIRES=new IdentityCaptures<>(8192);
    private static final ThreadLocal<Wire> DECODING=new ThreadLocal<>();
    private static final Set<String> COMMITTED=ConcurrentHashMap.newKeySet();
    private record Oracle(long generation,String connection,Map<Long,List<Target>> targets) {}
    private static volatile Oracle oracle=new Oracle(0,null,Map.of());
    private static volatile ClientLevel level;
    private static volatile Object connection;
    private static volatile long session, connectionIndex;
    private static long reconnectAt;
    private static boolean reconnected;
    private static long previousFrame;
    private static boolean gpuObserved;
    private static long lastGcSample;
    private static volatile boolean running,overflow;
    private static volatile long stallStart,stallUntil;
    private static final PendingAcceptance.SparseFault SPARSE_FAULT=new PendingAcceptance.SparseFault();
    private static boolean stalled(){long now=System.nanoTime();return SPARSE_FAULT.arm()!=null?SPARSE_FAULT.stalled(now):now>=stallStart&&now<stallUntil;}
    private static Path root;
    private static String subject,run;
    @Override public void onInitializeClient() {
        run=System.getProperty("lss.rig.runId","");subject=System.getProperty("lss.rig.subject","");
        root=Path.of(System.getProperty("lss.rig.evidence",""));
        if(!run.matches("[A-Za-z0-9_-]+")||!subject.matches("RigSubject[ABCD]")||!root.isAbsolute()||Files.isSymbolicLink(root))throw new IllegalStateException("explicit owned fixture context required");
        journal=new OracleJournal(run,subject);
        if(Boolean.getBoolean("lss.rig.requireXaero"))try{bridge=new BridgeObservation();}
        catch(ReflectiveOperationException failure){throw new IllegalStateException("required actual Xaero observation unavailable",failure);}
        running=true;
        Thread writer=new Thread(()->{
            try(BufferedWriter stream=Files.newBufferedWriter(root.resolve("consumer-"+subject+".jsonl"))){
                while(running||!OUTPUT.isEmpty()||HELD_SLOTS.availablePermits()!=128){
                    String row=OUTPUT.poll(100,TimeUnit.MILLISECONDS);
                    if(row!=null){stream.write(row);stream.newLine();stream.flush();}
                    readOracle();
                    long sampleTime=System.nanoTime();
                    if(sampleTime-lastGcSample>=1_000_000_000L){
                        if(bridge!=null)emit(bridge.sample(sampleTime));
                        lastGcSample=sampleTime;long count=0,millis=0;boolean countKnown=true,timeKnown=true;
                        for(var bean:java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()){
                            long c=bean.getCollectionCount(),t=bean.getCollectionTime();
                            if(c<0)countKnown=false;else count+=c;
                            if(t<0)timeKnown=false;else millis+=t;
                        }
                        emit(Map.of("event","jvm_gc","time_ns",sampleTime,"gc_count",countKnown?count:-1,"gc_time_ms",timeKnown?millis:-1));
                    }
                    int batch=HELD.size();
                    for(int i=0;i<batch;i++){
                        PendingAcceptance held=HELD.poll();if(held==null)break;
                        if(!held.poll(System.nanoTime(),stalled())&&!HELD.offer(held))held.reject();
                    }
                }
                Map<String,Object> closed=new HashMap<>(Map.of("event","consumer_closed","run_id",run,"subject",subject,"overflow",overflow,"held",128-HELD_SLOTS.availablePermits()));
                closed.put("held_admission_refusals",HELD_ADMISSION.refusals());
                closed.put("held_admission_report_errors",HELD_ADMISSION.reportErrors());
                closed.put("held_admission_release_errors",HELD_ADMISSION.releaseErrors());
                closed.putAll(REJECTIONS.status());stream.write(JSON.toJson(closed));stream.newLine();
            }catch(Exception e){overflow=true;}finally{synchronized(HELD){running=false;PendingAcceptance held;while((held=HELD.poll())!=null)held.reject();}}
        },"LSS-RigConsumerEvidence");writer.setDaemon(true);writer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{synchronized(HELD){running=false;PendingAcceptance held;while((held=HELD.poll())!=null)held.cancel();}try{writer.join(3000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}));
        LSSApi.registerColumnConsumer(this);
        emit(Map.of("event","consumer_ready","run_id",run,"subject",subject));
    }
    private static void emit(Map<String,?> row){
        Map<String,Object> bound=new HashMap<>(row);bound.put("run_id",run);bound.put("subject",subject);
        if(!OUTPUT.offer(JSON.toJson(bound)))overflow=true;
    }
    private static void emitRejection(Map<String,?> row){
        Map<String,Object> bound=new HashMap<>(row);bound.put("run_id",run);bound.put("subject",subject);
        if(!OUTPUT.offer(JSON.toJson(bound))){overflow=true;throw new IllegalStateException("existing diagnostic queue full");}
    }
    private static void readOracle() throws IOException {
        var next=journal.poll(root.resolve("oracle.jsonl"),connectionIndex,COMMITTED);
        SPARSE_FAULT.arm(next.sparseArm()); // Publish arm before its target becomes callback-visible.
        oracle=new Oracle(next.generation(),next.connection(),next.targets());
        stallStart=next.stallStart();stallUntil=next.stallEnd();
        if(SPARSE_FAULT.arm()!=null&&!java.util.Objects.equals(SPARSE_FAULT.arm().connection(),next.connection()))SPARSE_FAULT.retire();
        if(SPARSE_FAULT.expired(System.nanoTime()))throw new IOException("sparse consumer trigger deadline exceeded");
        String acknowledgment=next.acknowledgment();
        if(acknowledgment!=null&&SPARSE_FAULT.trigger()!=null){
            var ack=JsonParser.parseString(acknowledgment).getAsJsonObject();
            ack.add("sparse_consumer_trigger",JSON.toJsonTree(SPARSE_FAULT.trigger()));acknowledgment=JSON.toJson(ack);
        }
        if(acknowledgment!=null&&!acknowledgment.equals(lastAcknowledgment)){
            Path pending=root.resolve("oracle-ack-"+subject+".tmp"),destination=root.resolve("oracle-ack-"+subject+".json");
            Files.writeString(pending,acknowledgment);
            Files.move(pending,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            lastAcknowledgment=acknowledgment;
        }
    }
    public static void tick(){
        if(!running)return;
        Minecraft mc=Minecraft.getInstance();
        if(level!=mc.level||connection!=mc.getConnection()){
            if (subject.equals("RigSubjectB") && connection!=null && mc.getConnection()==null && !reconnected)
                reconnectAt=System.nanoTime()+5_000_000_000L;
            if(connection!=mc.getConnection() && mc.getConnection()!=null)connectionIndex++;
            level=mc.level;connection=mc.getConnection();session++;oracle=new Oracle(connectionIndex,null,Map.of());WIRES.clear();
            PendingAcceptance held;while((held=HELD.poll())!=null)held.cancel();
            emit(Map.of("event","client_session","session",session,"connected",connection!=null,"time_ns",System.nanoTime()));
        }
        if (reconnectAt!=0 && !reconnected && connection==null && System.nanoTime()>=reconnectAt) {
            String endpoint=System.getProperty("lss.rig.endpoint","");
            if (!endpoint.matches("127\\.0\\.0\\.1:[0-9]{1,5}")) throw new IllegalStateException("owned loopback reconnect endpoint required");
            reconnected=true;
            emit(Map.of("event","reconnect_requested","time_ns",System.nanoTime()));
            net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new net.minecraft.client.gui.screens.TitleScreen(),mc,
                net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(endpoint),
                new net.minecraft.client.multiplayer.ServerData("Owned rig",endpoint,net.minecraft.client.multiplayer.ServerData.Type.OTHER),false,null);
        }
    }
    /** Real frame-entry interval including pacing, called only by runTick(Z)V. */
    public static void frame(){
        if(!running)return;
        if(!gpuObserved){
            gpuObserved=true;
            String renderer=org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
            String vendor=org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR);
            String version=org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION);
            boolean current=renderer!=null&&vendor!=null&&version!=null;
            if(!current)overflow=true;
            emit(Map.of("event","client_gpu","time_ns",System.nanoTime(),"context_current",current,
                    "renderer",String.valueOf(renderer),"vendor",String.valueOf(vendor),"version",String.valueOf(version)));
        }
        long now=System.nanoTime(),previous=previousFrame;previousFrame=now;
        if(previous!=0)emit(Map.of("event","frame","start_ns",previous,"end_ns",now,"duration_ns",now-previous));
    }
    public static void wire(VoxelColumnS2CPayload payload){
        if(!running)return;
        long now=System.nanoTime(),captureId=WIRE_SEQUENCE.incrementAndGet(),capturedSession=session;
        Object nativeConnection=Minecraft.getInstance().getConnection();Oracle current=oracle;
        String bound=capturedSession==session && current.generation()==connectionIndex && level!=null && nativeConnection==connection?current.connection():null;
        String dimension=payload.dimension().identifier().toString();
        Wire wire=new Wire(captureId,payload.chunkX(),payload.chunkZ(),payload.columnTimestamp(),capturedSession,payload.shippedSections().length,payload.source(),now,dimension,bound,nativeConnection);
        try{WIRES.put(payload,wire);}catch(IllegalStateException failure){overflow=true;throw failure;}
        Map<String,Object> row=new HashMap<>();row.put("event","wire_capture");row.put("wire_capture_id",captureId);row.put("connection_id",bound);row.put("local_session",capturedSession);row.put("chunk_x",wire.x());row.put("chunk_z",wire.z());row.put("column_timestamp",wire.timestamp());row.put("source",wire.source());row.put("body_bytes",wire.bytes());row.put("arrival_ns",now);row.put("dimension",dimension);emit(row);
    }
    public static void decoding(VoxelColumnS2CPayload payload){
        Wire wire=WIRES.take(payload);if(wire==null)DECODING.remove();else DECODING.set(wire);
    }
    public static void decodingFinished(){DECODING.remove();}
    @Override public int pendingIngestBacklog(){return HELD.size()>=64?8192:HELD.size();}
    @Override public void onVoxelColumnReceived(ClientLevel delivery,ResourceKey<Level> dimension,int x,int z,VoxelColumnData data){
        if(!running)return;
        var receipt=LSSApi.captureIngestFailureHandle();
        Wire wire=DECODING.get();DECODING.remove();
        long bodyId=BODY_SEQUENCE.incrementAndGet();
        if(dimension!=Level.OVERWORLD||receipt==null||!receipt.isActive())return;
        if(delivery!=level)return;
        if(!hasPendingDemand(oracle,x,z))return;
        Set<String> awaiting=new HashSet<>();
        OracleJournal.Target sparseTarget=null;
        if(wire!=null&&wire.session()==session&&wire.nativeConnection()==connection
                &&Objects.equals(wire.connectionId(),oracle.connection())&&wire.x()==x&&wire.z()==z&&wire.timestamp()==data.columnTimestamp()){
            for(var target:oracle.targets().getOrDefault(OracleJournal.position(x,z),List.of()))
                if(!COMMITTED.contains(target.id())&&(target.end()==0||System.nanoTime()<target.end())
                        &&wire.dimension().equals(target.dimension())&&wire.source()==target.source()&&SPARSE_FAULT.eligible(oracle.connection(),target.id(),target.applied(),wire.received(),System.nanoTime(),wire.captureId())){sparseTarget=target;break;}
        }
        boolean forced=stalled()||sparseTarget!=null;
        try {if(!forced&&accept(delivery,x,z,data,wire,receipt,bodyId,awaiting))return;}
        catch(RuntimeException failure){fixtureFailure(bodyId,"callback-observation-failed");return;}
        synchronized(HELD){
        if(!running||!receipt.isActive()||delivery!=level||(wire!=null&&(wire.session()!=session||wire.nativeConnection()!=connection)))return;
        Runnable release=receipt.deferAcceptance();
        var admission=HELD_ADMISSION.acquireOrRefuse(receipt::report,release);
        if(admission!=PendingAcceptance.Admission.Result.ADMITTED){
            if(admission==PendingAcceptance.Admission.Result.REFUSAL_FAILED)overflow=true;
            return;
        }
        long deferredAt=System.nanoTime();
        synchronized(SPARSE_FAULT){
        if(sparseTarget!=null&&!COMMITTED.contains(sparseTarget.id())&&SPARSE_FAULT.eligible(oracle.connection(),sparseTarget.id(),sparseTarget.applied(),wire.received(),deferredAt,wire.captureId())){
            var trigger=SPARSE_FAULT.begin(oracle.connection(),sparseTarget.id(),sparseTarget.applied(),wire.received(),deferredAt,bodyId,wire.captureId());
            if(trigger!=null){var row=new HashMap<String,Object>(trigger);row.put("event","slow_consumer_triggered");emit(row);}
        }
        }
        long wireId=wire==null?0:wire.captureId();
        PendingAcceptance held=new PendingAcceptance(
                ()->running&&receipt.isActive()&&delivery==level&&(wire==null||wire.session()==session&&wire.nativeConnection()==connection),
                ()->accept(delivery,x,z,data,wire,receipt,bodyId,awaiting),
                ()->unknownDeadline(oracle,x,z,awaiting),
                ()->{long releasedAt=System.nanoTime();try{release.run();}finally{HELD_SLOTS.release();emit(Map.of("event","acceptance_released","held",HELD.size(),"time_ns",releasedAt,"body_id",bodyId,"wire_capture_id",wireId));}},
                ()->overflow=true);
        if(!HELD.offer(held))held.reject();
        else emit(Map.of("event","acceptance_deferred","held",HELD.size(),"time_ns",deferredAt,"body_id",bodyId,"wire_capture_id",wireId));
        }
    }
    private static long unknownDeadline(Oracle current,int x,int z,Set<String> awaiting){
        long deadline=Long.MAX_VALUE;
        for(Target target:current.targets().getOrDefault(OracleJournal.position(x,z),List.of()))
            if(awaiting.contains(target.id())&&!COMMITTED.contains(target.id())&&target.applied()==0)
                deadline=Math.min(deadline,target.offered()+120_000_000_000L);
        return deadline;
    }
    private static boolean accept(ClientLevel delivery,int x,int z,VoxelColumnData data,Wire wire,LSSApi.IngestFailureHandle receipt,long bodyId,Set<String> awaiting){
        if(SPARSE_FAULT.arm()==null)return observe(delivery,x,z,data,wire,receipt,bodyId,awaiting);
        // Sparse-only timer/observation ordering; no total event-writer ordering is assumed.
        synchronized(SPARSE_FAULT){
            if(SPARSE_FAULT.stalled(System.nanoTime()))return false;
            return observe(delivery,x,z,data,wire,receipt,bodyId,awaiting);
        }
    }
    private static boolean observe(ClientLevel delivery,int x,int z,VoxelColumnData data,Wire wire,LSSApi.IngestFailureHandle receipt,long bodyId,Set<String> awaiting){
        if(!running||!receipt.isActive()){
            emit(Map.of("event","stale_acceptance_discarded","local_session",session,"time_ns",System.nanoTime()));return true;
        }
        if(delivery!=level)return true;
        Oracle current=oracle;
        if(wire!=null&&(wire.session()!=session||wire.nativeConnection()!=connection))return true;
        var targets=current.targets().getOrDefault(OracleJournal.position(x,z),List.of());
        long checkedAt=System.nanoTime();
        for(String id:awaiting){
            if(COMMITTED.contains(id))continue;
            Target target=targets.stream().filter(t->t.id().equals(id)).findFirst().orElse(null);
            if(target==null||target.end()>0&&checkedAt>=target.end())
                throw new IllegalStateException("unknown application hold lost its original target interval");
        }
        if(!hasPendingDemand(current,x,z))return true;
        if(wire==null||wire.connectionId()==null||!wire.connectionId().equals(current.connection())||wire.x()!=x||wire.z()!=z
                ||wire.timestamp()!=data.columnTimestamp()||!wire.dimension().equals("minecraft:overworld")){
            fixtureFailure(bodyId,"invalid-wire-association");return true;
        }
        if(checkedAt<wire.received()){fixtureFailure(bodyId,"invalid-body-clock");return true;}
        boolean unknown=false;
        for(Target target:targets){
            if(!target.connection().equals(current.connection())||COMMITTED.contains(target.id())||wire==null
                    ||!Objects.equals(wire.connectionId(),current.connection())||wire.x()!=x||wire.z()!=z
                    ||wire.timestamp()!=data.columnTimestamp()||!wire.dimension().equals(target.dimension()))continue;
            if(AcceptancePolicy.decide(target.facts(),receipt.isActive(),running&&delivery==level&&wire.session()==session&&wire.nativeConnection()==connection,
                    wire.received(),checkedAt,wire.source(),true)==AcceptancePolicy.Decision.AWAIT_APPLICATION){awaiting.add(target.id());unknown=true;}
        }
        if(unknown)return false;
        awaiting.clear();
        for(Target target:targets){
            if(!target.connection().equals(current.connection())||target.x()!=x||target.z()!=z||COMMITTED.contains(target.id()))continue;
            long resolved=System.nanoTime();
            if(target.end()>0&&resolved>=target.end())continue;
            if(wire==null||wire.connectionId()==null||!wire.connectionId().equals(current.connection())||wire.x()!=x||wire.z()!=z
                    ||wire.timestamp()!=data.columnTimestamp()||!wire.dimension().equals(target.dimension())){fixtureFailure(bodyId,"invalid-target-association");return true;}
            boolean matchingBlock=false;
            for(var section:data.sections()){
                if(section.sectionY()!=Math.floorDiv(target.y(),16))continue;
                var expected=switch(target.block()){
                    case "gold_block" -> Blocks.GOLD_BLOCK;
                    case "diamond_block" -> Blocks.DIAMOND_BLOCK;
                    case "bedrock" -> Blocks.BEDROCK;
                    default -> throw new IllegalStateException("unknown independent oracle block");
                };
                matchingBlock=section.section().getBlockState(0,Math.floorMod(target.y(),16),0).is(expected);
                break;
            }
            resolved=System.nanoTime();
            boolean active=receipt.isActive(),nativeAuthority=running&&delivery==level&&wire.session()==session&&wire.nativeConnection()==connection;
            var decision=AcceptancePolicy.decide(target.facts(),active,nativeAuthority,
                    wire.received(),resolved,wire.source(),matchingBlock);
            if(decision==AcceptancePolicy.Decision.FAILURE){fixtureFailure(bodyId,"invalid-body-clock");return true;}
            if(decision==AcceptancePolicy.Decision.OBSERVE){
                final boolean matched=matchingBlock;final long decidedAt=resolved;
                if(REJECTIONS.enabled())REJECTIONS.observe(RejectionTelemetry.bucket(target.applied(),wire.received(),decidedAt,target.facts().acceptsSource(wire.source()),matched),()->{
                    Map<String,Object> row=new HashMap<>();row.put("event","acceptance_rejection_diagnostic");
                    row.put("id",target.id());row.put("cell_revision",target.revision());
                    row.put("wire_capture_id",wire.captureId());row.put("body_id",bodyId);
                    row.put("source",wire.source());row.put("expected_source",target.source());row.put("matching_block",matched);
                    row.put("allowed_sources",target.loadedUpdateFallback()?List.of(0,1,3):List.of(target.source()));
                    row.put("body_received_ns",wire.received());row.put("applied_ns",target.applied());
                    row.put("end_ns",target.end());row.put("resolved_ns",decidedAt);
                    row.put("lease_active",active);row.put("native_authority",nativeAuthority);
                    row.put("connection_id",target.connection());row.put("local_session",wire.session());
                    return row;
                },ClientProbe::emitRejection);
                continue;
            }
            if(decision==AcceptancePolicy.Decision.COMMIT){
                if(COMMITTED.add(target.id())){
                    Map<String,Object> committed=new HashMap<>(Map.of("event","target_committed","id",target.id(),"subject",subject,"connection_id",target.connection(),"local_session",session,"resolved_ns",resolved,"body_bytes",wire!=null&&wire.session()==session?wire.bytes():-1,"source",wire!=null&&wire.session()==session?wire.source():-1,"expected_block",target.block(),"lease_active",true));
                    committed.put("cell_revision",target.revision());committed.put("predecessor_id",target.predecessor());committed.put("world_generation",target.worldGeneration());
                    committed.put("wire_capture_id",wire.captureId());committed.put("wire_association","exact");committed.put("dimension",wire.dimension());
                    committed.put("body_received_ns",wire.received());committed.put("body_id",bodyId);committed.put("chunk_x",x);committed.put("chunk_z",z);committed.put("column_timestamp",data.columnTimestamp());
                    emit(committed);
                }
            }
        }
        return true;
    }
    private static boolean hasPendingDemand(Oracle current,int x,int z){
        if(current.generation()!=connectionIndex||current.connection()==null)return false;
        long now=System.nanoTime();
        for(Target target:current.targets().getOrDefault(OracleJournal.position(x,z),List.of()))
            if(target.connection().equals(current.connection())&&!COMMITTED.contains(target.id())&&(target.end()==0||now<target.end()))return true;
        return false;
    }
    private static void fixtureFailure(long bodyId,String reason){
        overflow=true;
        emit(Map.of("event","observer_failure","body_id",bodyId,"reason",reason,"time_ns",System.nanoTime()));
    }
}
