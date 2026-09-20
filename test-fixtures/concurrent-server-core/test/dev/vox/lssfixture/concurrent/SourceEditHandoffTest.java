package dev.vox.lssfixture.concurrent;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;
import com.google.gson.*;

/** Real SourceWorkload dispatch/poll controls, with native write represented by a counter. */
public final class SourceEditHandoffTest {
    static void check(boolean condition){if(!condition)throw new AssertionError();}
    static final class Engine implements SourceWorkload.Engine {
        Object current=new Object();boolean owns=true;int writes;Runnable beforePoll;
        record Call(SourceWorkload.Target target,SourceWorkload.Mutation mutation,SourceWorkload.Ownership owner,
                    CompletableFuture<SourceWorkload.EditOutcome> result){}
        final List<Call> calls=new ArrayList<>();
        public SourceWorkload.Ownership loadedOwnership(SourceWorkload.Target target){
            return owns && current!=null?new SourceWorkload.Ownership(Map.of("observed_ns",System.nanoTime()),current):null;
        }
        public CompletionStage<SourceWorkload.EditOutcome> edit(SourceWorkload.Target target,SourceWorkload.Mutation mutation,SourceWorkload.Ownership owner){
            var result=new CompletableFuture<SourceWorkload.EditOutcome>(){
                @Override public boolean isDone(){if(beforePoll!=null){Runnable action=beforePoll;beforePoll=null;action.run();}return super.isDone();}
            };calls.add(new Call(target,mutation,owner,result));return result;
        }
        void finish(int index,boolean failAfterWrite){
            Call call=calls.get(index);
            if(!owns||!call.owner.isCurrent(current,System.nanoTime())){call.result.complete(SourceWorkload.OwnerUnavailableBeforeMutation.INSTANCE);return;}
            writes++;
            if(failAfterWrite)call.result.completeExceptionally(new IllegalStateException("dirty mark failed after write"));
            else call.result.complete(new SourceWorkload.Applied(System.nanoTime(),call.mutation.revision(),call.mutation.predecessor(),"test-owner"));
        }
        public void seed(SourceWorkload.Target target){}
        public void save(){}
        public void makeUnloadedSources(SourceWorkload.Target a,SourceWorkload.Target b){}
        public boolean sourcesReady(List<SourceWorkload.Target> targets){return true;}
        public List<Map<String,Object>> sourceFacts(List<SourceWorkload.Target> targets){return List.of();}
        public void kick(String subject){}
        public Map<String,Object> metrics(){return null;}
    }
    static Object field(Object value,String name)throws Exception{var f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    static final class Harness implements AutoCloseable {
        final Path root=Files.createTempDirectory("lss-edit-handoff-");final Engine engine=new Engine();final SourceWorkload work;
        Harness()throws Exception{
            System.setProperty("lss.rig.runId","test-run");System.setProperty("lss.rig.evidence",root.toString());
            work=new SourceWorkload(engine,-64);var stage=SourceWorkload.class.getDeclaredField("stage");stage.setAccessible(true);stage.setInt(work,3);
            work.join("RigSubjectA");ack(1);work.tick();check(engine.calls.size()==1);check(pending()==1&&flights()==1);
        }
        void ack(int connection)throws Exception{
            Set<String> ids=new HashSet<>();for(int source=0;source<4;source++)ids.add("test-run-RigSubjectA-"+source+"-initial");
            Path temp=root.resolve("ack.tmp"),dest=root.resolve("oracle-ack-RigSubjectA.json");
            Files.writeString(temp,new Gson().toJson(Map.of("run_id","test-run","subject","RigSubjectA","connection_id","test-run-RigSubjectA-"+connection,"targets",ids,"target_sequence",0)));
            Files.move(temp,dest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            var read=SourceWorkload.class.getDeclaredMethod("readAcknowledgements",Path.class);read.setAccessible(true);read.invoke(work,root);
        }
        int pending()throws Exception{return ((List<?>)field(work,"pendingEdits")).size();}
        int flights()throws Exception{return ((Map<?,?>)field(work,"flights")).size();}
        Object pendingObject()throws Exception{return ((List<?>)field(work,"pendingEdits")).getFirst();}
        @SuppressWarnings({"unchecked","rawtypes"}) void expire()throws Exception{
            List list=(List)field(work,"pendingEdits");Object old=list.getFirst();var parts=old.getClass().getRecordComponents();Object[] args=new Object[parts.length];Class<?>[] types=new Class<?>[parts.length];
            for(int i=0;i<parts.length;i++){types[i]=parts[i].getType();var getter=parts[i].getAccessor();getter.setAccessible(true);args[i]=parts[i].getName().equals("offeredAt")?System.nanoTime()-121_000_000_000L:getter.invoke(old);}
            var ctor=old.getClass().getDeclaredConstructor(types);ctor.setAccessible(true);Object expired=ctor.newInstance(args);list.set(0,expired);Map flights=(Map)field(work,"flights");Object flight=flights.remove(old);if(flight!=null)flights.put(expired,flight);
        }
        public void close()throws Exception{work.close();try(var files=Files.list(root)){for(Path p:files.toList())Files.delete(p);}Files.delete(root);}
    }
    public static void main(String[] ignored)throws Exception{
        try(Harness h=new Harness()) {
            var offer=SourceWorkload.class.getDeclaredMethod("offer",SourceWorkload.Target.class,String.class,boolean.class,long.class);
            offer.setAccessible(true);
            offer.invoke(h.work,new SourceWorkload.Target("RigSubjectA",12,0,64,0,"diamond_block"),"measured-1",true,1L);
            h.work.close();
            var rows=Files.readAllLines(h.root.resolve("oracle.jsonl")).stream().map(x->JsonParser.parseString(x).getAsJsonObject())
                    .filter(x->x.get("event").getAsString().equals("target")).toList();
            check(rows.size()==5);Set<Integer> initialSources=new HashSet<>();
            for(var row:rows) {
                if(!row.has("target_sequence")){check(!row.has("allowed_sources"));initialSources.add(row.get("expected_source").getAsInt());}
                else {check(row.get("expected_source").getAsInt()==0);check(row.get("allowed_sources").equals(JsonParser.parseString("[0,1,3]")));}
            }
            check(initialSources.equals(Set.of(0,1,2,3)));
        }
        try(Harness h=new Harness()){
            Object original=h.pendingObject();var mutation=h.engine.calls.getFirst().mutation();
            h.work.quit("RigSubjectA");h.engine.current=null;h.engine.finish(0,false);
            h.work.tick();check(h.pending()==1&&h.flights()==0&&h.engine.writes==0&&h.pendingObject()==original);
            h.engine.current=new Object();h.work.join("RigSubjectA");h.work.tick();check(h.engine.calls.size()==1); // Old ACK cannot authorize successor.
            h.ack(2);h.engine.owns=false;h.work.tick();check(h.engine.calls.size()==1); // Fresh ACK alone insufficient.
            h.engine.owns=true;h.work.tick();check(h.engine.calls.size()==2&&h.pending()==1&&h.flights()==1);
            check(h.engine.calls.get(1).mutation()==mutation&&h.pendingObject()==original);
            h.engine.finish(1,false);h.work.tick();h.work.tick();check(h.engine.writes==1&&h.pending()==0&&h.flights()==0);
            h.work.close();var rows=Files.readAllLines(h.root.resolve("oracle.jsonl")).stream().map(x->JsonParser.parseString(x).getAsJsonObject()).toList();
            var acknowledgments=rows.stream().filter(x->x.get("event").getAsString().equals("target_acknowledged")).toList();
            var applied=rows.stream().filter(x->x.get("event").getAsString().equals("edit_applied")).findFirst().orElseThrow();
            var owned=rows.stream().filter(x->x.get("event").getAsString().equals("target_owner_precondition")).findFirst().orElseThrow();
            check(acknowledgments.size()==1);long ackAt=acknowledgments.getFirst().get("time_ns").getAsLong();
            check(owned.get("observed_ns").getAsLong()<=ackAt&&ackAt<=applied.get("time_ns").getAsLong());
            check(owned.get("connection_id").getAsString().endsWith("-2"));
        }
        try(Harness h=new Harness()){
            h.engine.current=new Object();h.engine.finish(0,false);h.work.tick();check(h.engine.writes==0&&h.pending()==1); // Replacement rejects old permit even if owned.
        }
        try(Harness h=new Harness()){
            h.engine.finish(0,true);h.work.tick();h.work.tick();check(h.engine.writes==1&&h.engine.calls.size()==1&&h.pending()==0&&h.flights()==0); // Never retry post-write failure.
            h.work.close();var failure=Files.readAllLines(h.root.resolve("oracle.jsonl")).stream().map(x->JsonParser.parseString(x).getAsJsonObject()).filter(x->x.get("event").getAsString().equals("edit_failed")).findFirst().orElseThrow();
            check(failure.get("reason").getAsString().equals("mutation_exception"));check(failure.get("exception_type").getAsString().equals("java.lang.IllegalStateException"));
        }
        try(Harness h=new Harness()){
            h.engine.finish(0,false);h.work.tick();h.work.tick();check(h.engine.writes==1&&h.engine.calls.size()==1); // Executed mutation never repeats.
        }
        try(Harness h=new Harness()){
            h.engine.beforePoll=()->h.engine.finish(0,false); // Completion can race after global tick's initial clock read.
            h.work.tick();check(h.engine.writes==1&&h.pending()==0);
            h.work.close();check(Files.readString(h.root.resolve("oracle.jsonl")).contains("\"event\":\"edit_applied\""));
        }
        Object token=new Object();var permit=new SourceWorkload.Ownership(Map.of("observed_ns",1_000_000_000L),token);
        check(permit.isCurrent(token,1_250_000_000L));check(!permit.isCurrent(token,1_250_000_001L));check(!permit.isCurrent(new Object(),1_000_000_000L));check(!permit.isCurrent(null,1_000_000_000L));
        for(boolean deferred:List.of(false,true))try(Harness h=new Harness()){
            if(deferred)h.engine.calls.getFirst().result().complete(SourceWorkload.OwnerUnavailableBeforeMutation.INSTANCE);
            h.expire();try{h.work.tick();throw new AssertionError("original deadline lost");}catch(IllegalStateException expected){}
            check(h.pending()==1&&h.flights()==(deferred?0:1)&&h.engine.writes==0);
        }
        try(Harness h=new Harness()){
            var offer=SourceWorkload.class.getDeclaredMethod("offer",SourceWorkload.Target.class,String.class,boolean.class);offer.setAccessible(true);
            var target=h.engine.calls.getFirst().target();
            for(int i=0;i<127;i++)offer.invoke(h.work,target,"bound-"+i,true);
            check(h.pending()==128&&h.flights()==1);
            try{offer.invoke(h.work,target,"overflow",true);throw new AssertionError("inflight escaped128 bound");}catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalStateException);}
            check(h.pending()==128&&h.flights()==1);
        }
        System.out.println("SourceEditHandoff: original identity/deadline, successor ACK, ownership, terminal mutation, and128-slot controls passed");
    }
}
