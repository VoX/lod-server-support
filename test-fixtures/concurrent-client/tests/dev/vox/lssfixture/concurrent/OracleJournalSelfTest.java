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
    private static String target(int sequence){return row(Map.of("event","target","id","id"+sequence,"connection_id","c1","target_sequence",sequence,"chunk_x",sequence,"chunk_z",2,"block_y",64,"expected_block","gold_block","offered_ns",1));}
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
            Files.writeString(path,session(2)+row(Map.of("event","session_transfer","old_connection","c1","connection_id","c2")),StandardOpenOption.APPEND);
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
            System.out.println("OracleJournal: partial append, cached poll, reconnect, pruning, gap, truncation and bounded read controls passed");
        }finally{Files.deleteIfExists(path);Files.deleteIfExists(root);}
    }
}
