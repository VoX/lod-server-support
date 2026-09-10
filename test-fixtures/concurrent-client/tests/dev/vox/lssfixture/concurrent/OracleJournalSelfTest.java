package dev.vox.lssfixture.concurrent;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Runs without Minecraft; exercises actual parser, acknowledgment and indexing logic. */
public final class OracleJournalSelfTest {
    private static final Gson JSON=new Gson();
    private static final String RUN="test-run",SUBJECT="RigSubjectA";
    private static String row(Map<String,Object> fields){var bound=new HashMap<>(fields);bound.put("run_id",RUN);bound.put("subject",SUBJECT);return JSON.toJson(bound)+"\n";}
    private static String session(int index){return row(Map.of("event","session","connection_id","c"+index,"connection_index",index));}
    private static String target(int sequence){return target(sequence,sequence,1,null,"gold_block");}
    private static String target(int sequence,int x,long revision,String predecessor,String block){
        var value=new HashMap<String,Object>();value.put("event","target");value.put("id","id"+sequence);value.put("connection_id","c1");value.put("target_sequence",sequence);
        value.put("chunk_x",x);value.put("chunk_z",2);value.put("block_y",64);value.put("expected_block",block);value.put("expected_source",0);value.put("offered_ns",sequence*10L);
        value.put("dimension","minecraft:overworld");value.put("world_generation","world-1");value.put("cell_revision",revision);value.put("predecessor_id",predecessor);return row(value);
    }
    private static String applied(int id,long revision,String predecessor,long time){
        var value=new HashMap<String,Object>();value.put("event","edit_applied");value.put("run_id",RUN);value.put("id","id"+id);value.put("time_ns",time);
        value.put("cell_revision",revision);value.put("predecessor_id",predecessor);value.put("dimension","minecraft:overworld");value.put("world_generation","world-1");
        return new GsonBuilder().serializeNulls().create().toJson(value)+"\n"; // Real historical application shape has no subject.
    }
    private static void require(boolean condition){if(!condition)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("lss-oracle-test");Path path=root.resolve("oracle.jsonl");
        try{
            OracleJournal journal=new OracleJournal(RUN,SUBJECT);Set<String> committed=new HashSet<>();
            String first=target(1);Files.writeString(path,session(1)+first.substring(0,first.length()-1));
            require(journal.poll(path,1,committed).targets().isEmpty());
            Files.writeString(path,"\n",StandardOpenOption.APPEND);
            var one=journal.poll(path,1,committed);require(one.targets().get(OracleJournal.position(1,2)).size()==1);
            require(journal.poll(path,1,committed)==one);
            require(JsonParser.parseString(one.acknowledgment()).getAsJsonObject().get("target_sequence").getAsInt()==1);
            Files.writeString(path,session(2)+row(Map.of("event","session_transfer","old_connection","c1","connection_id","c2","time_ns",50)),StandardOpenOption.APPEND);
            var successor=journal.poll(path,2,committed);
            require(successor.connection().equals("c2"));require(successor.targets().get(OracleJournal.position(1,2)).getFirst().connection().equals("c2"));
            committed.add("id1");require(journal.poll(path,2,committed).targets().isEmpty());
            Files.writeString(path,target(3),StandardOpenOption.APPEND);
            try{journal.poll(path,2,committed);throw new AssertionError("sequence gap accepted");}catch(java.io.IOException expected){}
            Files.writeString(path,"");
            try{journal.poll(path,2,committed);throw new AssertionError("truncation accepted");}catch(java.io.IOException expected){}
            journal=new OracleJournal(RUN,SUBJECT);StringBuilder large=new StringBuilder(session(1));
            for(int i=1;i<=2000;i++)large.append(target(i));Files.writeString(path,large);
            var partial=journal.poll(path,1,Set.of());require(partial.targets().size()<2000);
            OracleJournal.Snapshot complete=partial;
            for(int i=0;i<10&&complete.targets().size()<2000;i++)complete=journal.poll(path,1,Set.of());
            require(complete.targets().size()==2000);
            // Real append/partial-application handling and original revision closure.
            journal=new OracleJournal(RUN,SUBJECT);Files.writeString(path,session(1)+target(1));
            var unapplied=journal.poll(path,1,Set.of()).targets().get(OracleJournal.position(1,2)).getFirst();
            require(unapplied.source()==0&&unapplied.applied()==0);
            String owner=applied(1,1,null,15);Files.writeString(path,owner.substring(0,owner.length()-1),StandardOpenOption.APPEND);
            require(journal.poll(path,1,Set.of()).targets().get(OracleJournal.position(1,2)).getFirst().applied()==0);
            Files.writeString(path,"\n",StandardOpenOption.APPEND);
            var original=journal.poll(path,1,Set.of()).targets().get(OracleJournal.position(1,2)).getFirst();require(original.applied()==15);
            Files.writeString(path,target(2,1,2,"id1","diamond_block")+applied(2,2,"id1",30),StandardOpenOption.APPEND);
            var replaced=journal.poll(path,1,Set.of()).targets().get(OracleJournal.position(1,2));
            require(replaced.size()==2);require(replaced.stream().filter(t->t.id().equals("id1")).findFirst().orElseThrow().end()==30);
            require(original.end()==0); // Immutable old snapshot; independent checker remains required.
            require(journal.poll(path,1,Set.of("id1")).targets().get(OracleJournal.position(1,2)).size()==1);
            Files.writeString(path,target(3,1,3,"id2","gold_block")+applied(3,3,"id2",40),StandardOpenOption.APPEND);
            var repeated=journal.poll(path,1,Set.of()).targets().get(OracleJournal.position(1,2));
            require(repeated.size()==3); // Expired/missed obligations remain visible, never dropped to green.
            require(repeated.stream().filter(t->t.id().equals("id1")).findFirst().orElseThrow().end()==30);
            require(repeated.stream().filter(t->t.id().equals("id3")).findFirst().orElseThrow().applied()==40);
            Files.writeString(path,applied(3,3,"id2",40),StandardOpenOption.APPEND);
            try{journal.poll(path,1,Set.of());throw new AssertionError("duplicate application accepted");}catch(java.io.IOException expected){}
            journal=new OracleJournal(RUN,SUBJECT);Files.writeString(path,session(1)+target(1)+applied(1,2,null,15));
            try{journal.poll(path,1,Set.of());throw new AssertionError("wrong original revision accepted");}catch(java.io.IOException expected){}
            // Append completion order is not application order; original timestamps govern the interval.
            journal=new OracleJournal(RUN,SUBJECT);Files.writeString(path,session(1)+target(1)+target(2,1,2,"id1","diamond_block")
                    +applied(2,2,"id1",30)+applied(1,1,null,15));
            var reordered=journal.poll(path,1,Set.of()).targets().get(OracleJournal.position(1,2));
            var older=reordered.stream().filter(t->t.id().equals("id1")).findFirst().orElseThrow();require(older.applied()==15&&older.end()==30);
            journal=new OracleJournal(RUN,SUBJECT);Files.writeString(path,session(1)+target(1)+applied(999,1,null,15));
            require(journal.poll(path,1,Set.of()).targets().get(OracleJournal.position(1,2)).getFirst().applied()==0);
            JsonObject qualified=JsonParser.parseString(applied(1,1,null,15)).getAsJsonObject();qualified.addProperty("subject",SUBJECT);
            Files.writeString(path,qualified+"\n",StandardOpenOption.APPEND);
            require(journal.poll(path,1,Set.of()).targets().get(OracleJournal.position(1,2)).getFirst().applied()==15);
            journal=new OracleJournal(RUN,SUBJECT);qualified.addProperty("subject","RigSubjectB");Files.writeString(path,session(1)+target(1)+qualified+"\n");
            try{journal.poll(path,1,Set.of());throw new AssertionError("foreign subject applied own target");}catch(java.io.IOException expected){}
            JsonObject measuredTarget=JsonParser.parseString(target(1)).getAsJsonObject();
            measuredTarget.add("allowed_sources",JsonParser.parseString("[0,1,3]"));
            journal=new OracleJournal(RUN,SUBJECT);Files.writeString(path,session(1)+measuredTarget+"\n"+applied(1,1,null,15));
            var fallback=journal.poll(path,1,Set.of()).targets().get(OracleJournal.position(1,2)).getFirst();
            require(fallback.loadedUpdateFallback()&&fallback.facts().acceptsSource(1)&&fallback.facts().acceptsSource(3)&&!fallback.facts().acceptsSource(2));
            Files.writeString(path,session(2)+row(Map.of("event","session_transfer","old_connection","c1","connection_id","c2","time_ns",50)),StandardOpenOption.APPEND);
            require(journal.poll(path,2,Set.of()).targets().get(OracleJournal.position(1,2)).getFirst().loadedUpdateFallback());
            for(String invalid:new String[]{"[0]","[0,1,2,3]","[false,1,3]","[\"0\",1,3]","[0,1,3.0]","null"}) {
                measuredTarget.add("allowed_sources",JsonParser.parseString(invalid));
                Files.writeString(path,session(1)+measuredTarget+"\n");journal=new OracleJournal(RUN,SUBJECT);
                try{journal.poll(path,1,Set.of());throw new AssertionError("invalid source policy accepted: "+invalid);}catch(java.io.IOException expected){}
            }
            measuredTarget.add("allowed_sources",JsonParser.parseString("[0,1,3]"));
            for(String key:new String[]{"expected_source","target_sequence"}) {
                JsonElement originalValue=measuredTarget.get(key);
                for(String invalid:new String[]{key.equals("expected_source")?"\"0\"":"\"1\"",key.equals("expected_source")?"0.0":"1.0"}) {
                    measuredTarget.add(key,JsonParser.parseString(invalid));
                    Files.writeString(path,session(1)+measuredTarget+"\n");journal=new OracleJournal(RUN,SUBJECT);
                    try{journal.poll(path,1,Set.of());throw new AssertionError("coerced source discriminator accepted");}catch(java.io.IOException expected){}
                }
                measuredTarget.add(key,originalValue);
            }
            measuredTarget.remove("target_sequence");
            Files.writeString(path,session(1)+measuredTarget+"\n");journal=new OracleJournal(RUN,SUBJECT);
            try{journal.poll(path,1,Set.of());throw new AssertionError("initial route assertion weakened");}catch(java.io.IOException expected){}
            System.out.println("OracleJournal: partial append/application, cached poll, reconnect, bounded retained metadata, subjectless application, exact predecessor interval, repeated-block expiry, gap/truncation/bounded reads passed");
        }finally{Files.deleteIfExists(path);Files.deleteIfExists(root);}
    }
}
