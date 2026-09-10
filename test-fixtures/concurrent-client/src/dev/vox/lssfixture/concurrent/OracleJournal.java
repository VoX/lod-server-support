package dev.vox.lssfixture.concurrent;

import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bounded append-only reader; parsing and indexing never run on a game thread. */
public final class OracleJournal {
    public record Target(String id,String connection,int x,int z,int y,String block,long offered,String dimension,String worldGeneration,long revision,String predecessor) {}
    public record Snapshot(long generation,String connection,Map<Long,List<Target>> targets,
                           long stallStart,long stallEnd,String acknowledgment) {}
    private static final int MAX_POLL_BYTES=262144,MAX_LINE_BYTES=65536,MAX_TARGETS=8192;
    private static final long MAX_JOURNAL_BYTES=64L*1024*1024;
    private final String run,subject;
    private final Map<String,Target> pending=new HashMap<>();
    private final Set<String> seen=new HashSet<>(),legacyAcknowledged=new TreeSet<>();
    private final Map<String,String> transfers=new HashMap<>();
    private final Map<Long,String> connections=new HashMap<>();
    private final ByteArrayOutputStream partial=new ByteArrayOutputStream();
    private long offset,sequence,stallStart,stallEnd,lastGeneration=-1;
    private int lastCommitted=-1;
    private Snapshot cached;
    public OracleJournal(String run,String subject){this.run=run;this.subject=subject;}
    public static long position(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    public Snapshot poll(Path path,long generation,Set<String> committed) throws IOException {
        if(Files.isSymbolicLink(path))throw new IOException("symlink oracle");
        if(!Files.isRegularFile(path))return empty(generation);
        long size=Files.size(path);
        if(size<offset||size>MAX_JOURNAL_BYTES)throw new IOException("oracle truncated or exceeds bound");
        boolean changed=size>offset;
        if(changed)try(FileChannel channel=FileChannel.open(path,StandardOpenOption.READ)){
            channel.position(offset);ByteBuffer buffer=ByteBuffer.allocate(65536);int remaining=MAX_POLL_BYTES;
            while(remaining>0){
                buffer.clear();buffer.limit(Math.min(buffer.capacity(),remaining));int count=channel.read(buffer);
                if(count<=0)break;
                offset+=count;remaining-=count;buffer.flip();
                while(buffer.hasRemaining()){
                    byte value=buffer.get();
                    if(value=='\n'){
                        String line=partial.toString(StandardCharsets.UTF_8);partial.reset();
                        if(!line.isBlank())parse(line);
                    }else{
                        if(partial.size()>=MAX_LINE_BYTES)throw new IOException("oracle record exceeds bound");
                        partial.write(value);
                    }
                }
            }
        }
        if(!changed&&cached!=null&&lastGeneration==generation&&lastCommitted==committed.size())return cached;
        pending.keySet().removeIf(committed::contains);
        String connection=connections.get(generation);
        Map<Long,List<Target>> index=new HashMap<>();
        for(Target target:pending.values()){
            String destination=target.connection();Set<String> visited=new HashSet<>();
            while(transfers.containsKey(destination)){
                if(!visited.add(destination))throw new IOException("cyclic oracle session transfer");
                destination=transfers.get(destination);
            }
            if(!Objects.equals(destination,connection))continue;
            Target rebound=new Target(target.id(),destination,target.x(),target.z(),target.y(),target.block(),target.offered(),target.dimension(),target.worldGeneration(),target.revision(),target.predecessor());
            index.computeIfAbsent(position(target.x(),target.z()),ignored->new ArrayList<>()).add(rebound);
        }
        index.replaceAll((key,value)->List.copyOf(value));
        String acknowledgment=connection==null?null:new Gson().toJson(Map.of("run_id",run,"subject",subject,
                "connection_id",connection,"target_sequence",sequence,"targets",legacyAcknowledged));
        cached=new Snapshot(generation,connection,Map.copyOf(index),stallStart,stallEnd,acknowledgment);
        lastGeneration=generation;lastCommitted=committed.size();return cached;
    }
    private Snapshot empty(long generation){return new Snapshot(generation,null,Map.of(),0,0,null);}
    private void parse(String line) throws IOException {
        final JsonObject row;
        try{row=JsonParser.parseString(line).getAsJsonObject();}
        catch(RuntimeException failure){throw new IOException("malformed complete oracle record",failure);}
        if(row.has("run_id")&&!run.equals(row.get("run_id").getAsString()))throw new IOException("foreign oracle run");
        if(!row.has("subject")||!subject.equals(row.get("subject").getAsString()))return;
        String event=row.get("event").getAsString();
        switch(event){
            case "session" -> {
                if(connections.size()>=16)throw new IOException("too many oracle sessions");
                if(connections.putIfAbsent(row.get("connection_index").getAsLong(),row.get("connection_id").getAsString())!=null)
                    throw new IOException("duplicate oracle session index");
            }
            case "session_transfer" -> {
                if(transfers.size()>=16||transfers.putIfAbsent(row.get("old_connection").getAsString(),row.get("connection_id").getAsString())!=null)
                    throw new IOException("duplicate/excessive oracle transfer");
            }
            case "slow_consumer" -> {stallStart=row.get("start_ns").getAsLong();stallEnd=row.get("end_ns").getAsLong();}
            case "target" -> {
                String id=row.get("id").getAsString();
                if(seen.size()>=MAX_TARGETS||!seen.add(id))throw new IOException("duplicate/excessive oracle target");
                if(row.has("target_sequence")){
                    long next=row.get("target_sequence").getAsLong();
                    if(next!=sequence+1)throw new IOException("noncontiguous oracle target sequence");sequence=next;
                }else legacyAcknowledged.add(id);
                pending.put(id,new Target(id,row.get("connection_id").getAsString(),row.get("chunk_x").getAsInt(),
                        row.get("chunk_z").getAsInt(),row.get("block_y").getAsInt(),row.get("expected_block").getAsString(),row.get("offered_ns").getAsLong(),
                        row.has("dimension")?row.get("dimension").getAsString():"minecraft:overworld",row.has("world_generation")?row.get("world_generation").getAsString():"legacy",
                        row.has("cell_revision")?row.get("cell_revision").getAsLong():0,row.has("predecessor_id")&&!row.get("predecessor_id").isJsonNull()?row.get("predecessor_id").getAsString():null));
            }
            default -> {} // Edit/fault evidence is checked independently after collection.
        }
    }
}
