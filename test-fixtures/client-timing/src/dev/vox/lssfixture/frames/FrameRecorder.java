package dev.vox.lssfixture.frames;

import java.nio.file.*;
import java.io.*;
import java.util.concurrent.*;

public final class FrameRecorder {
    private static final ArrayBlockingQueue<String> QUEUE=new ArrayBlockingQueue<>(8192);
    private static volatile boolean running,overflow;
    private static long previous;
    public static void start() throws IOException {
        String subject=System.getProperty("lss.rig.subject","");
        String run=System.getProperty("lss.rig.runId","");
        Path root=Path.of(System.getProperty("lss.rig.evidence",""));
        if(!subject.matches("[A-Za-z0-9_-]+")||!run.matches("[A-Za-z0-9_-]+")||!root.isAbsolute()||Files.isSymbolicLink(root)) throw new IllegalArgumentException("owned subject/run/evidence required");
        running=true;
        Thread writer=new Thread(()->{
            try(BufferedWriter stream=Files.newBufferedWriter(root.resolve("frames-"+subject+".jsonl"))){
                long nextJvm=System.nanoTime();
                while(running||!QUEUE.isEmpty()){
                    String row=QUEUE.poll(200,TimeUnit.MILLISECONDS);
                    if(row!=null){stream.write(row);stream.newLine();stream.flush();}
                    long now=System.nanoTime();
                    if(now>=nextJvm){
                        long count=0,millis=0;boolean available=false;
                        for(var collector:java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()){
                            long c=collector.getCollectionCount(),m=collector.getCollectionTime();
                            if(c>=0&&m>=0){count+=c;millis+=m;available=true;}
                        }
                        long heap=java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                        stream.write("{\"event\":\"client_jvm\",\"time_ns\":"+now+",\"gc_available\":"+available+",\"gc_count\":"+(available?count:-1)+",\"gc_time_ms\":"+(available?millis:-1)+",\"heap_used_bytes\":"+heap+"}\n");
                        nextJvm=now+1_000_000_000L;
                    }
                }
                stream.write("{\"event\":\"frames_closed\",\"overflow\":"+overflow+"}\n");
            }catch(Exception e){overflow=true;}
        },"LSS-RigFrameEvidence");writer.setDaemon(true);writer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{running=false;try{writer.join(3000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}));
        emit("{\"event\":\"frames_ready\",\"run_id\":\""+run+"\",\"subject\":\""+subject+"\"}");
    }
    private static void emit(String row){if(!QUEUE.offer(row))overflow=true;}
    public static void event(String event,String hash){emit("{\"event\":\""+event+"\",\"class_sha256\":\""+hash+"\"}");}
    public static void frame(){
        long now=System.nanoTime();
        if(previous!=0)emit("{\"event\":\"frame\",\"start_ns\":"+previous+",\"end_ns\":"+now+",\"duration_ns\":"+(now-previous)+"}");
        previous=now;
    }
}
