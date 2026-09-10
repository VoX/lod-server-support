package dev.vox.lssfixture.concurrent;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.*;

/** Actual schedule preconditions tested without a Minecraft engine or world mutation. */
public final class SourcePreparationTest {
    static final class Engine implements SourceWorkload.Engine {
        boolean pending,proto;int seeds;
        public List<Map<String,Object>> initialGenerationFacts(List<SourceWorkload.Target> targets){
            if(pending)return null;
            if(targets.size()!=4)throw new AssertionError();
            for(int i=0;i<4;i++)if(targets.get(i).x()!=i*256-20||targets.get(i).z()!=-20)throw new AssertionError("generation geometry");
            return sourceFacts(targets);
        }
        public List<Map<String,Object>> sourceFacts(List<SourceWorkload.Target> targets){
            var facts=new ArrayList<Map<String,Object>>();
            for(var target:targets){var row=new HashMap<String,Object>();row.put("subject",target.subject());row.put("source",2);row.put("chunk_x",target.x());row.put("chunk_z",target.z());row.put("block_y",target.y());row.put("expected_block",target.block());
                for(String key:List.of("loaded","retained","disk_present","store_present","in_memory_present"))row.put(key,false);
                row.put("in_memory_present",proto);facts.add(row);}
            return facts;
        }
        public void seed(SourceWorkload.Target target){seeds++;}
        public void save(){}
        public void makeUnloadedSources(SourceWorkload.Target a,SourceWorkload.Target b){}
        public boolean sourcesReady(List<SourceWorkload.Target> targets){return false;}
        public CompletionStage<SourceWorkload.EditOutcome> edit(SourceWorkload.Target target,SourceWorkload.Mutation mutation,SourceWorkload.Ownership owner){throw new AssertionError();}
        public void kick(String subject){throw new AssertionError();}
        public Map<String,Object> metrics(){return Map.of();}
    }
    public static void main(String[] args)throws Exception{
        for(String mode:List.of("pending","proto","absent")){
            Path root=Files.createTempDirectory("lss-source-premise-");System.setProperty("lss.rig.runId","test-run");System.setProperty("lss.rig.evidence",root.toString());
            Engine engine=new Engine();engine.pending=mode.equals("pending");engine.proto=mode.equals("proto");
            try(var workload=new SourceWorkload(engine,-64)){
                if(engine.proto){try{workload.tick();throw new AssertionError("proto accepted");}catch(IllegalStateException expected){}workload.tick();}
                else workload.tick();
            }
            List<JsonObject> rows=Files.readAllLines(root.resolve("server-events.jsonl")).stream().map(s->JsonParser.parseString(s).getAsJsonObject()).toList();
            long started=rows.stream().filter(row->row.get("event").getAsString().equals("source_preparation_started")).count();
            if(mode.equals("absent")){if(engine.seeds==0||started!=1)throw new AssertionError("valid absence did not release seed");}
            else if(engine.seeds!=0||started!=0)throw new AssertionError("seed escaped initial prerequisite");
            try(var files=Files.list(root)){for(Path file:files.toList())Files.delete(file);}Files.delete(root);
        }
        System.out.println("SourcePreparation: pending lookup, native proto rejection, and checked absence controls passed");
    }
}
