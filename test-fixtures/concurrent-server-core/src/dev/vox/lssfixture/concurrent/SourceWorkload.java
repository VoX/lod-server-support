package dev.vox.lssfixture.concurrent;

import com.google.gson.Gson;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Test-only common schedule. Engine calls occur on its legal server owner thread.
 * Source preparation is a premise; only the independent client payload proves delivery.
 */
public final class SourceWorkload implements AutoCloseable {
    public record Target(String subject, int x, int z, int y, int source, String block) {}
    public record Mutation(String id,String dimension,String worldGeneration,long revision,String predecessor) {}
    public sealed interface EditOutcome permits Applied,OwnerUnavailableBeforeMutation {}
    public enum OwnerUnavailableBeforeMutation implements EditOutcome { INSTANCE }
    public record Applied(long time,long revision,String predecessor,String ownerRegion) implements EditOutcome {}
    /** Immutable facts for evidence plus an opaque native identity, never serialized. */
    public record Ownership(Map<String,Object> facts,Object identity) {
        public Ownership { facts=Map.copyOf(facts); }
        public boolean isCurrent(Object current,long now){
            return current!=null && identity==current && facts.get("observed_ns") instanceof Number observed
                    && now-observed.longValue()<=250_000_000L;
        }
    }
    public interface Engine {
        default List<Map<String,Object>> initialGenerationFacts(List<Target> targets){return sourceFacts(targets);}
        default Ownership loadedOwnership(Target target){return new Ownership(Map.of(),this);}
        default Map<String,Object> ownershipDiagnostic(Target target){return Map.of("reason","not_instrumented");}
        void seed(Target target);
        void save();
        void makeUnloadedSources(Target store, Target disk);
        boolean sourcesReady(List<Target> targets);
        List<Map<String,Object>> sourceFacts(List<Target> targets);
        java.util.concurrent.CompletionStage<EditOutcome> edit(Target target,Mutation mutation,Ownership owner);
        void kick(String subject);
        Map<String,Object> metrics();
    }
    private final Engine engine;
    private final Gson json = new com.google.gson.GsonBuilder().serializeNulls().create();
    private final ArrayBlockingQueue<String> events = new ArrayBlockingQueue<>(32768);
    private final ArrayBlockingQueue<String> oracle = new ArrayBlockingQueue<>(8192);
    private final Map<String,String> connections = new HashMap<>();
    private final Map<String,String> activeConnections=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,Integer> indexes = new HashMap<>();
    private final Set<String> observedRegistrations=new HashSet<>();
    private final Map<String,Long> joined = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<Target> targets = new ArrayList<>();
    private final List<Target> measuredTargets = new ArrayList<>();
    private final List<Target> diagnosticTargets = new ArrayList<>();
    private final Set<String> explicitEditIds=new HashSet<>();
    private final boolean measured=Boolean.getBoolean("lss.rig.measuredWorkload");
    private final MeasuredSchedule measuredSchedule=new MeasuredSchedule();
    private final PendingDiagnostics diagnostics=new PendingDiagnostics();
    private final Set<String> diagnosticFailures=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String,Long> targetSequences=new HashMap<>();
    private record Ack(String connection,Set<String> targets,long sequence) {}
    private record PendingEdit(Target target,String id,long offeredAt,long sequence,Mutation mutation) {}
    private final Map<String,Mutation> offeredRevisions=new HashMap<>();
    private volatile Map<String,Ack> acknowledgements=Map.of(), requirements=Map.of();
    private final List<PendingEdit> pendingEdits=new ArrayList<>();
    private record Flight(java.util.concurrent.CompletableFuture<EditOutcome> future,long submittedAt,
                          String connection,Ownership ownership) {}
    // Only the global workload thread mutates these structures. Each original edit occupies
    // one pending slot through waiting, in-flight completion, and safe owner deferral.
    private final Map<PendingEdit,Flight> flights=new HashMap<>();
    private final Set<PendingEdit> deferredObserved=new HashSet<>();
    private final Thread writer;
    private final String runId;
    private volatile boolean running = true, overflow;
    private int stage, editStep, seedCursor;
    private long stageAt, lastMetrics;
    private volatile long origin;
    private boolean kicked, closed, initialChecked, preparationRejected;
    public SourceWorkload(Engine engine, int minimumY) {
        this.engine=engine;
        runId=System.getProperty("lss.rig.runId", "");
        Path evidence=Path.of(System.getProperty("lss.rig.evidence", ""));
        if(!runId.matches("[A-Za-z0-9_-]+") || !evidence.isAbsolute() || Files.isSymbolicLink(evidence))
            throw new IllegalStateException("owned run identity/evidence required");
        for(int i=0;i<4;i++) {
            String name="RigSubject"+(char)('A'+i); int x=i*256;
            targets.add(new Target(name,x+8,8,64,0,"gold_block"));
            targets.add(new Target(name,x-8,8,64,3,"gold_block"));
            targets.add(new Target(name,x+8,-8,64,1,"gold_block"));
            targets.add(new Target(name,x-20,-20,minimumY,2,"bedrock"));
            if(!measured)for(int cell=0;cell<3;cell++)diagnosticTargets.add(new Target(name,x+12+cell,0,64,0,"gold_block"));
            if(measured)for(int cell=0;cell<MeasuredSchedule.CELLS;cell++)
                measuredTargets.add(new Target(name,x+MeasuredSchedule.chunkX(cell),MeasuredSchedule.chunkZ(cell),64,0,"gold_block"));
        }
        writer=new Thread(()-> {
            try(BufferedWriter stream=Files.newBufferedWriter(evidence.resolve("server-events.jsonl"));
                BufferedWriter target=Files.newBufferedWriter(evidence.resolve("oracle.jsonl"))) {
                while(running || !events.isEmpty() || !oracle.isEmpty()) {
                    String row=events.poll(100,TimeUnit.MILLISECONDS);
                    if(row!=null){stream.write(row);stream.newLine();stream.flush();}
                    while((row=oracle.poll())!=null){target.write(row);target.newLine();}
                    target.flush();
                    readAcknowledgements(evidence);
                }
                stream.write(json.toJson(Map.of("event","writer_closed","run_id",runId,"overflow",overflow))+"\n");
            } catch(Exception failure){overflow=true;}
        },"LSS-RigSourceEvidence");
        writer.setDaemon(true);writer.start();
    }
    /** Distinct bounded evidence only; never changes ACK/owner/timeout decisions. */
    public void diagnosticFailure(String component,Throwable failure){
        if(!diagnosticFailures.add(component))return;
        try { record(events,Map.of("event","source_diagnostic_failure","component",component,
                "exception_type",failure.getClass().getName(),"time_ns",System.nanoTime())); }
        catch(Throwable reportingFailure) {
            // Primitive fallback preserves an explicit marker if ordinary serialization fails.
            if(!events.offer("{\"event\":\"source_diagnostic_failure\",\"component\":\"reporting\",\"run_id\":\""+runId+"\"}"))overflow=true;
        }
    }
    private void record(ArrayBlockingQueue<String> queue, Map<String,?> row){Map<String,Object> bound=new LinkedHashMap<>(row);bound.put("run_id",runId);if(!queue.offer(json.toJson(bound)))overflow=true;}
    private void event(String event, long now){record(events,Map.of("event",event,"time_ns",now));}
    /** At most32 owner-state transitions plus one truncation marker per native connection.
     * Uses the existing bounded writer queue; no owner-thread I/O or required-evidence suppression.
     */
    public void ownerTransition(String subject,Map<String,Object> transition){
        Map<String,Object> row=new LinkedHashMap<>(transition);row.put("subject",subject);record(events,row);
    }
    public boolean ready(){return stage==3;}
    public boolean denied(String subject) {
        Ack required=requirements.get(subject);
        return origin==0 || required==null || !required.connection.equals(activeConnections.get(subject))
            || !acknowledged(subject,required.connection,required.targets)
            || faultDenied(subject);
    }
    public boolean faultDenied(String subject) {
        long now=System.nanoTime();
        return origin!=0 && subject.equals("RigSubjectC") && now>=origin+200_000_000_000L && now<origin+220_000_000_000L;
    }
    public void join(String subject) {
        if(!subject.matches("RigSubject[ABCD]"))return;
        if(!ready())throw new IllegalStateException("clients launched before source-ready marker");
        long now=System.nanoTime();String connection=runId+"-"+subject+"-"+indexes.merge(subject,1,Integer::sum);
        String old=connections.put(subject,connection); joined.put(subject,now);activeConnections.put(subject,connection);
        record(events,Map.of("event","join","subject",subject,"connection_id",connection,"time_ns",now));
        if(old!=null)record(oracle,Map.of("event","session_transfer","subject",subject,"old_connection",old,"connection_id",connection,"time_ns",now));
        record(oracle,Map.of("event","session","subject",subject,"connection_id",connection,"connection_index",indexes.get(subject),"time_ns",now));
        Set<String> needed=new HashSet<>();
        if(old==null)for(Target target:targets)if(target.subject.equals(subject)) {
            Target offered=target.source==0?new Target(target.subject,target.x,target.z,target.y,0,"diamond_block"):target;
            offer(offered,"initial",target.source==0);needed.add(targetId(target,"initial"));
        }
        var next=new HashMap<>(requirements);next.put(subject,new Ack(connection,Set.copyOf(needed),0));requirements=Map.copyOf(next);
    }
    public void quit(String subject) {
        if(!running)return;String connection=activeConnections.remove(subject);
        // A successor native registration can precede its delayed owner-bound
        // oracle join. Never let that state reuse the old connection's send ack.
        var remaining=new HashMap<>(requirements);remaining.remove(subject);requirements=Map.copyOf(remaining);
        if(connection!=null)record(events,Map.of("event","quit","subject",subject,"connection_id",connection,"time_ns",System.nanoTime()));
    }
    public void productRegistrationObserved(String subject) {
        String connection=activeConnections.get(subject);
        if(connection!=null && observedRegistrations.add(connection))record(events,Map.of("event","product_registration_observed","subject",subject,"connection_id",connection,"time_ns",System.nanoTime()));
    }
    private String targetId(Target target,String suffix){return runId+"-"+target.subject+"-"+target.source+"-"+suffix;}
    private boolean acknowledged(String subject,String connection,Set<String> ids) {
        Ack actual=acknowledgements.get(subject);
        return actual!=null && actual.connection.equals(connection) && actual.targets.containsAll(ids);
    }
    /** Only the bounded evidence writer calls this; owner and network threads read cached facts. */
    private void readAcknowledgements(Path evidence) {
        var next=new HashMap<String,Ack>();
        for(char letter='A';letter<='D';letter++) {
            String subject="RigSubject"+letter;Path path=evidence.resolve("oracle-ack-"+subject+".json");
            if(!Files.exists(path))continue;
            try {
                if(Files.isSymbolicLink(path) || !Files.isRegularFile(path) || Files.size(path)>65536)throw new IllegalStateException("invalid ack file");
                var row=json.fromJson(Files.readString(path),com.google.gson.JsonObject.class);
                if(!runId.equals(row.get("run_id").getAsString()) || !subject.equals(row.get("subject").getAsString()))throw new IllegalStateException("foreign ack identity");
                String connection=row.get("connection_id").getAsString();var ids=row.getAsJsonArray("targets");
                if(!connection.startsWith(runId+"-") || ids.size()>1024)throw new IllegalStateException("invalid ack bounds");
                Set<String> targets=new HashSet<>();
                for(var item:ids){String id=item.getAsString();if(id.length()>256 || !id.startsWith(runId+"-"))throw new IllegalStateException("foreign target ack");targets.add(id);}
                long sequence=0;
                if(row.has("target_sequence")) {
                    String value=row.get("target_sequence").getAsString();
                    if(!value.matches("0|[1-9][0-9]{0,3}"))throw new IllegalStateException("invalid target sequence ack");
                    sequence=Long.parseLong(value);
                    if(sequence>8192)throw new IllegalStateException("target sequence ack exceeds workload bound");
                }
                next.put(subject,new Ack(connection,Set.copyOf(targets),sequence));
            } catch(java.nio.file.NoSuchFileException transientRename) { /* next polling pass */ }
            catch(Exception invalid){overflow=true;}
        }
        acknowledgements=Map.copyOf(next);
    }
    private void offer(Target target,String suffix,boolean edit) {
        offer(target,suffix,edit,0);
    }
    private void offer(Target target,String suffix,boolean edit,long sequence) {
        long now=System.nanoTime();String id=targetId(target,suffix);
        String cell=target.subject+":"+target.x+":"+target.z+":"+target.y;
        Mutation previous=offeredRevisions.get(cell);
        Mutation mutation=new Mutation(id,"minecraft:overworld",runId+":overworld:1",previous==null?1:previous.revision+1,previous==null?null:previous.id);
        offeredRevisions.put(cell,mutation);
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("dimension",mutation.dimension);row.put("world_generation",mutation.worldGeneration);row.put("cell_revision",mutation.revision);row.put("predecessor_id",mutation.predecessor);
        row.put("event","target");row.put("id",id);row.put("subject",target.subject);
        row.put("connection_id",connections.get(target.subject));row.put("chunk_x",target.x);row.put("chunk_z",target.z);
        row.put("block_y",target.y);row.put("expected_source",target.source);row.put("expected_block",target.block);row.put("offered_ns",now);
        if(edit)row.put("requires_ack",true);
        if(sequence>0)row.put("target_sequence",sequence);
        record(oracle,row);
        if(edit) {
            if(sequence==0)explicitEditIds.add(id);
            if(pendingEdits.size()>=128)throw new IllegalStateException("pending edit bound exceeded");
            pendingEdits.add(new PendingEdit(target,id,now,sequence,mutation));
        } else {
            Map<String,Object> ready=new LinkedHashMap<>();ready.put("event","target_ready");ready.put("id",id);ready.put("time_ns",System.nanoTime());ready.put("cell_revision",mutation.revision);ready.put("predecessor_id",mutation.predecessor);ready.put("dimension",mutation.dimension);ready.put("world_generation",mutation.worldGeneration);record(oracle,ready);
        }
    }
    public void tick() {
        if(preparationRejected)return;
        try{tickOnce();}
        catch(RuntimeException|Error failure){
            if(!preparationRejected){preparationRejected=true;event(stage<3?"source_preparation_failed":"workload_failed",System.nanoTime());}
            throw failure;
        }
    }
    private void tickOnce() {
        long now=System.nanoTime();
        if(preparationRejected)return;
        if(stage==0) {
            if(!initialChecked){
                var initial=engine.initialGenerationFacts(targets.stream().filter(target->target.source==2).toList());
                if(initial==null)return;
                boolean absent=initial.size()==4;
                for(var fact:initial){
                    Map<String,Object> row=new LinkedHashMap<>(fact);
                    row.put("event","source_generation_initial_precondition");row.put("time_ns",System.nanoTime());record(events,row);
                    for(String key:List.of("loaded","in_memory_present","disk_present","store_present"))absent&=Boolean.FALSE.equals(fact.get(key));
                }
                if(!absent){preparationRejected=true;event("source_preparation_failed",System.nanoTime());throw new IllegalStateException("generation domain already exists before fixture seeding");}
                initialChecked=true;event("source_preparation_started",System.nanoTime());
            }
            // Bounded owner work during preparation, outside all measured windows.
            List<Target> seedTargets=new ArrayList<>(targets);seedTargets.addAll(measuredTargets);seedTargets.addAll(diagnosticTargets);
            for(int count=0;seedCursor<seedTargets.size() && count<4;count++) {
                Target target=seedTargets.get(seedCursor++);if(target.source!=2)engine.seed(target);
            }
            if(seedCursor<seedTargets.size())return;
            engine.save();stage=1;stageAt=now;return;
        }
        if(stage==1 && now-stageAt>=2_000_000_000L) {
            for(int i=0;i<4;i++)engine.makeUnloadedSources(targets.get(i*4+1),targets.get(i*4+2));
            stage=2;stageAt=now;return;
        }
        if(stage==2) {
            List<Target> allTargets=new ArrayList<>(targets);allTargets.addAll(measuredTargets);allTargets.addAll(diagnosticTargets);
            if(engine.sourcesReady(List.copyOf(allTargets))) {
                Set<String> originalPositions=new HashSet<>();
                for(Target target:targets)originalPositions.add(target.x+":"+target.z);
                for(var fact:engine.sourceFacts(List.copyOf(allTargets))) {
                    Map<String,Object> row=new LinkedHashMap<>(fact);
                    row.put("event",originalPositions.contains(fact.get("chunk_x")+":"+fact.get("chunk_z"))?"source_target_precondition":measured?"measured_loaded_precondition":"diagnostic_loaded_precondition");
                    row.put("time_ns",System.nanoTime());record(events,row);
                }
                stage=3;event("source_preconditions_ready",System.nanoTime());
                System.out.println("LSS_RIG_SOURCES_READY");
            } else if(now-stageAt>60_000_000_000L)throw new IllegalStateException("source preparation did not converge");
            return;
        }
        if(stage!=3)return;
        for(var entry:requirements.entrySet())if(!acknowledged(entry.getKey(),entry.getValue().connection,entry.getValue().targets)
                && now-joined.getOrDefault(entry.getKey(),now)>120_000_000_000L) {
            event("oracle_ack_timeout",now);throw new IllegalStateException("initial/session oracle acknowledgement timed out");
        }
        for(var iterator=pendingEdits.iterator();iterator.hasNext();) {
            PendingEdit pending=iterator.next();
            Flight flight=flights.get(pending);
            if(flight!=null){
                if(!flight.future.isDone()){
                    if(now-pending.offeredAt>120_000_000_000L){event("oracle_ack_timeout",now);throw new IllegalStateException("in-flight edit exceeded original deadline");}
                    continue;
                }
                EditOutcome outcome=null;Throwable failure=null;
                try{outcome=flight.future.join();}
                catch(java.util.concurrent.CompletionException error){failure=error.getCause()==null?error:error.getCause();}
                catch(RuntimeException error){failure=error;}
                long completedAt=System.nanoTime();
                flights.remove(pending);
                if(outcome==OwnerUnavailableBeforeMutation.INSTANCE && failure==null){
                    if(deferredObserved.add(pending))record(oracle,Map.of("event","edit_deferred","id",pending.id,
                            "subject",pending.target.subject,"time_ns",now,"reason","owner_unavailable_before_mutation"));
                    if(now-pending.offeredAt>120_000_000_000L){event("oracle_ack_timeout",now);throw new IllegalStateException("deferred edit exceeded original deadline");}
                    continue; // Next tick must re-check the successor's actual ACK and fresh owner.
                }
                if(failure==null && outcome instanceof Applied applied
                        && applied.time>=flight.submittedAt && applied.time<=completedAt
                        && applied.time-pending.offeredAt<=120_000_000_000L
                        && applied.revision==pending.mutation.revision
                        && Objects.equals(applied.predecessor,pending.mutation.predecessor)){
                    Map<String,Object> nativeOwnership=flight.ownership.facts();
                    if(!nativeOwnership.isEmpty()){
                        Map<String,Object> fact=new LinkedHashMap<>(nativeOwnership);fact.put("event","target_owner_precondition");fact.put("id",pending.id);fact.put("subject",pending.target.subject);fact.put("connection_id",flight.connection);fact.put("chunk_x",pending.target.x);fact.put("chunk_z",pending.target.z);fact.put("time_ns",flight.submittedAt);record(oracle,fact);
                    }
                    record(oracle,Map.of("event","target_acknowledged","id",pending.id,"subject",pending.target.subject,"time_ns",flight.submittedAt));
                    Map<String,Object> row=new LinkedHashMap<>();row.put("event","edit_applied");row.put("id",pending.id);row.put("subject",pending.target.subject);row.put("time_ns",applied.time);row.put("cell_revision",applied.revision);row.put("predecessor_id",applied.predecessor);row.put("dimension",pending.mutation.dimension);row.put("world_generation",pending.mutation.worldGeneration);row.put("owner_region_identity",applied.ownerRegion);row.put("owner_identity",applied.ownerRegion);row.put("owner_kind",nativeOwnership.getOrDefault("owner_kind","owning-region"));record(oracle,row);
                } else {
                    String type=failure==null?"invalid_outcome":failure.getClass().getName();
                    record(oracle,Map.of("event","edit_failed","id",pending.id,"subject",pending.target.subject,
                            "time_ns",now,"reason",failure==null?"invalid_applied_result":"mutation_exception",
                            "exception_type",type.substring(0,Math.min(type.length(),256))));
                }
                deferredObserved.remove(pending);iterator.remove();continue;
            }
            if(now-pending.offeredAt>120_000_000_000L){event("oracle_ack_timeout",now);throw new IllegalStateException("edit oracle acknowledgement timed out");}
            String connection=connections.get(pending.target.subject);
            Ack ack=acknowledgements.get(pending.target.subject);
            boolean accepted=connection!=null && connection.equals(activeConnections.get(pending.target.subject)) && (pending.sequence==0?acknowledged(pending.target.subject,connection,Set.of(pending.id)):
                ack!=null && ack.connection.equals(connection) && ack.sequence>=pending.sequence);
            Ownership nativeOwnership=accepted?engine.loadedOwnership(pending.target):null;
            // Diagnostic observation only: never changes accepted/ownership/timeout decisions.
            if(Boolean.getBoolean("lss.rig.sourceDiagnostics") && !measured) {
                DiagnosticGuard.observe(()->{
                    Map<String,Object> diagnostic=diagnostics.observe(pending.id,pending.target.subject,connection,
                            ack==null?null:ack.connection,accepted,nativeOwnership!=null,
                            pending.offeredAt,now,engine.ownershipDiagnostic(pending.target));
                    if(diagnostic!=null)record(events,diagnostic);
                },failure->diagnosticFailure("pending-edit",failure));
            }
            if(accepted && nativeOwnership!=null) {
                java.util.concurrent.CompletableFuture<EditOutcome> future;
                long submittedAt=System.nanoTime();
                try{future=engine.edit(pending.target,pending.mutation,nativeOwnership).toCompletableFuture();}
                catch(Throwable failure){future=java.util.concurrent.CompletableFuture.failedFuture(failure);}
                flights.put(pending,new Flight(future,submittedAt,connection,nativeOwnership));
            }
        }

        if(now-lastMetrics>=1_000_000_000L) {
            lastMetrics=now;Map<String,Object> metrics=engine.metrics();
            if(metrics!=null){
                metrics.put("event","product_metrics");metrics.put("time_ns",now);metrics.put("fixture_pending_edits",pendingEdits.size());record(events,metrics);
                if(origin==0 && connections.size()==4 && metrics.get("players") instanceof List<?> players) {
                    Set<String> registered=new HashSet<>();
                    for(Object player:players)if(player instanceof Map<?,?> fields && fields.get("name") instanceof String name)registered.add(name);
                    if(registered.equals(Set.of("RigSubjectA","RigSubjectB","RigSubjectC","RigSubjectD"))
                            && requirements.size()==4 && requirements.entrySet().stream().allMatch(entry->acknowledged(entry.getKey(),entry.getValue().connection,entry.getValue().targets))) {
                        for(var required:requirements.values())for(String id:required.targets)if(!explicitEditIds.contains(id))
                            record(oracle,Map.of("event","target_acknowledged","id",id,"time_ns",now));
                        origin=now;
                        record(oracle,Map.of("event","slow_consumer","subject","RigSubjectD","start_ns",origin+160_000_000_000L,"end_ns",origin+180_000_000_000L));
                        record(oracle,Map.of("event","send_admission","subject","RigSubjectC","start_ns",origin+200_000_000_000L,"end_ns",origin+220_000_000_000L));
                        event("workload_started",now);
                        System.out.println("LSS_RIG_SOURCE_WORKLOAD_ACTIVE");
                    }
                }
            }
        }
        if(origin==0)return;
        long elapsed=now-origin;
        if(measured) {
            int round;
            try{round=measuredSchedule.poll(elapsed);}
            catch(IllegalStateException failure){event("offer_schedule_failed",now);throw failure;}
            if(round>=0) {
                for(int subject=0;subject<4;subject++) {
                    Target target=measuredTargets.get(subject*MeasuredSchedule.CELLS+round%MeasuredSchedule.CELLS);
                    long sequence=targetSequences.merge(target.subject,1L,Long::sum);
                    String block=(round/MeasuredSchedule.CELLS)%2==0?"diamond_block":"gold_block";
                    offer(new Target(target.subject,target.x,target.z,target.y,0,block),"measured-"+sequence,true,sequence);
                }
            }
        } else {
            long[] edits={170,210,362};
            if(editStep<edits.length && elapsed>=edits[editStep]*1_000_000_000L) {
                for(int subject=0;subject<4;subject++){
                    Target target=diagnosticTargets.get(subject*3+editStep);
                    offer(new Target(target.subject,target.x,target.z,target.y,0,"diamond_block"),"edit-"+editStep,true);
                }
                editStep++;
            }
        }
        if(!kicked && elapsed>=360_000_000_000L) {
            kicked=true;record(oracle,Map.of("event","disconnect","subject","RigSubjectB","connection_id",connections.get("RigSubjectB"),"time_ns",now));engine.kick("RigSubjectB");
        }
        if(!closed && elapsed>=(measured?720_000_000_000L:420_000_000_000L)){
            if(measured)measuredSchedule.requireComplete();
            closed=true;event("offers_closed",now);
        }
    }
    @Override public void close(){
        for(var session:activeConnections.entrySet())record(events,Map.of("event","session_end","subject",session.getKey(),"connection_id",session.getValue(),"time_ns",System.nanoTime()));
        activeConnections.clear();running=false;
        try{writer.join(3000);}catch(InterruptedException error){Thread.currentThread().interrupt();}
    }
}
