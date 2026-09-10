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
    public record Target(String id,String connection,int x,int z,int y,String block,long offered,String dimension,String worldGeneration,long revision,String predecessor,int source,long applied,long end,long authoritySince) {
        public AcceptancePolicy.Facts facts(){return new AcceptancePolicy.Facts(source,offered,applied,end,authoritySince);}
        Target withApplication(long at){return new Target(id,connection,x,z,y,block,offered,dimension,worldGeneration,revision,predecessor,source,at,end,authoritySince);}
        Target withEnd(long at){return new Target(id,connection,x,z,y,block,offered,dimension,worldGeneration,revision,predecessor,source,applied,at,authoritySince);}
        Target rebound(String destination,long since){return new Target(id,destination,x,z,y,block,offered,dimension,worldGeneration,revision,predecessor,source,applied,end,since);}
    }
    public record Snapshot(long generation,String connection,Map<Long,List<Target>> targets,
                           long stallStart,long stallEnd,String acknowledgment) {}
    private static final int MAX_POLL_BYTES=262144,MAX_LINE_BYTES=65536,MAX_TARGETS=8192;
    private static final long MAX_JOURNAL_BYTES=64L*1024*1024;
    private final String run,subject;
    private final Map<String,Target> indexed=new HashMap<>();
    private final Set<String> seen=new HashSet<>(),legacyAcknowledged=new TreeSet<>();
    private record Transfer(String destination,long at) {}
    private final Map<String,Transfer> transfers=new HashMap<>();
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
        // Retain bounded original metadata even after commit: successor application closes its interval.
        String connection=connections.get(generation);
        Map<Long,List<Target>> index=new HashMap<>();
        for(Target target:indexed.values()){
            if(committed.contains(target.id()))continue;
            long authoritySince=target.authoritySince();
            String destination=target.connection();Set<String> visited=new HashSet<>();
            while(transfers.containsKey(destination)){
                if(!visited.add(destination))throw new IOException("cyclic oracle session transfer");
                Transfer transfer=transfers.get(destination);authoritySince=Math.max(authoritySince,transfer.at());destination=transfer.destination();
            }
            if(!Objects.equals(destination,connection))continue;
            Target rebound=target.rebound(destination,authoritySince);
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
        String event=row.get("event").getAsString();
        // Owner application rows historically lack subject. Resolve only already indexed own IDs.
        if(event.equals("edit_applied")||event.equals("target_ready")){
            apply(row);return;
        }
        if(!row.has("subject")||!subject.equals(row.get("subject").getAsString()))return;
        switch(event){
            case "session" -> {
                if(connections.size()>=16)throw new IOException("too many oracle sessions");
                if(connections.putIfAbsent(row.get("connection_index").getAsLong(),row.get("connection_id").getAsString())!=null)
                    throw new IOException("duplicate oracle session index");
            }
            case "session_transfer" -> {
                if(transfers.size()>=16||transfers.putIfAbsent(row.get("old_connection").getAsString(),new Transfer(row.get("connection_id").getAsString(),row.get("time_ns").getAsLong()))!=null)
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
                indexed.put(id,new Target(id,row.get("connection_id").getAsString(),row.get("chunk_x").getAsInt(),
                        row.get("chunk_z").getAsInt(),row.get("block_y").getAsInt(),row.get("expected_block").getAsString(),row.get("offered_ns").getAsLong(),
                        row.has("dimension")?row.get("dimension").getAsString():"minecraft:overworld",row.has("world_generation")?row.get("world_generation").getAsString():"legacy",
                        row.has("cell_revision")?row.get("cell_revision").getAsLong():0,row.has("predecessor_id")&&!row.get("predecessor_id").isJsonNull()?row.get("predecessor_id").getAsString():null,
                        row.get("expected_source").getAsInt(),0,0,0));
            }
            default -> {} // Full original-revision/fault evidence remains independently checked after collection.
        }
    }
    private void apply(JsonObject row) throws IOException {
        String id=row.get("id").getAsString();Target target=indexed.get(id);
        if(target==null){
            if(row.has("subject")&&subject.equals(row.get("subject").getAsString()))throw new IOException("own application lacks indexed target");
            return; // Other subjects' records share this journal; do not allocate unknown-ID state.
        }
        if(row.has("subject")&&!subject.equals(row.get("subject").getAsString()))throw new IOException("foreign application subject for own target");
        String predecessor=row.has("predecessor_id")&&!row.get("predecessor_id").isJsonNull()?row.get("predecessor_id").getAsString():null;
        long at=row.get("time_ns").getAsLong();
        if(target.applied()!=0||at<target.offered()||at<=0||(target.end()>0&&at>=target.end())||row.get("cell_revision").getAsLong()!=target.revision()
                ||!Objects.equals(predecessor,target.predecessor())||!row.get("dimension").getAsString().equals(target.dimension())
                ||!row.get("world_generation").getAsString().equals(target.worldGeneration()))throw new IOException("application differs from original target");
        Target previous=predecessor==null?null:indexed.get(predecessor);
        if(predecessor!=null&&(previous==null||previous.revision()+1!=target.revision()
                ||previous.end()!=0||(previous.applied()>0&&at<=previous.applied())||previous.x()!=target.x()||previous.z()!=target.z()||previous.y()!=target.y()
                ||!previous.dimension().equals(target.dimension())||!previous.worldGeneration().equals(target.worldGeneration())))
            throw new IOException("application has invalid original predecessor interval");
        if(predecessor==null&&target.revision()!=1)throw new IOException("first application revision is not one");
        if(previous!=null)indexed.put(previous.id(),previous.withEnd(at));
        indexed.put(id,target.withApplication(at));
    }

}
