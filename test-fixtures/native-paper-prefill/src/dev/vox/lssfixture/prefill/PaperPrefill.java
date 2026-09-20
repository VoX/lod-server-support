package dev.vox.lssfixture.prefill;

import java.nio.file.*;
import java.io.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Disposable native Paper world construction; no LSS extension or client exists. */
public final class PaperPrefill extends JavaPlugin {
    private final ArrayBlockingQueue<String> output=new ArrayBlockingQueue<>(32768);
    private volatile boolean running=true,overflow;
    private Thread writer;
    private World world;
    private String run;
    private int offered,completed,pending;
    private long started;
    private boolean done,failed;
    private static final int SIDE=69,COUNT=4*SIDE*SIDE,MAX_PENDING=8;
    @Override public void onEnable(){
        run=System.getProperty("lss.rig.runId","");Path evidence=Path.of(System.getProperty("lss.rig.evidence",""));
        if(!run.matches("[A-Za-z0-9_-]+")||!evidence.isAbsolute()||Files.isSymbolicLink(evidence)||Bukkit.getOnlineMode())throw new IllegalStateException("owned offline prefill context required");
        if(Bukkit.getPluginManager().getPlugin("LodServerSupport")!=null)throw new IllegalStateException("prefill excludes LSS extension/store writes");
        try{Class.forName("io.papermc.paper.threadedregions.RegionizedServer");throw new IllegalStateException("native Paper prefill only");}catch(ClassNotFoundException expected){}
        world=Bukkit.getWorlds().getFirst();started=System.nanoTime();
        writer=new Thread(()->{
            try(var stream=Files.newBufferedWriter(evidence.resolve("prefill-events.jsonl"))){
                while(running||!output.isEmpty()){
                    String row=output.poll(100,TimeUnit.MILLISECONDS);
                    if(row!=null){stream.write(row);stream.newLine();stream.flush();}
                }
                stream.write("{\"event\":\"prefill_closed\",\"run_id\":\""+run+"\",\"overflow\":"+overflow+"}\n");
            }catch(Exception failure){overflow=true;}
        },"LSS-RigNativePrefillEvidence");writer.setDaemon(true);writer.start();
        emit("\"event\":\"prefill_started\",\"expected\":"+COUNT+",\"max_pending\":"+MAX_PENDING);
        Bukkit.getScheduler().runTaskTimer(this,task->{
            if(done||failed)return;
            if(!Bukkit.getOnlinePlayers().isEmpty()||System.nanoTime()-started>300_000_000_000L){fail("client-or-deadline");return;}
            while(pending<MAX_PENDING&&offered<COUNT){
                int item=offered++,subject=item/(SIDE*SIDE),cell=item%(SIDE*SIDE);
                int x=subject*256+(cell/SIDE)-34,z=(cell%SIDE)-34;pending++;
                world.getChunkAtAsync(x,z,true).whenComplete((chunk,error)->Bukkit.getScheduler().runTask(this,ignored->{
                    pending--;
                    if(failed)return;
                    if(error!=null||chunk==null||chunk.getX()!=x||chunk.getZ()!=z||!chunk.isLoaded()){fail("native-full-load");return;}
                    completed++;emit("\"event\":\"prefill_full\",\"chunk_x\":"+x+",\"chunk_z\":"+z);
                    world.unloadChunkRequest(x,z);
                    if(completed==COUNT&&pending==0){
                        world.save();done=true;
                        emit("\"event\":\"prefill_complete\",\"completed\":"+completed);
                        System.out.println("LSS_RIG_PREFILL_READY");
                    }
                }));
            }
        },1,1);
    }
    private void emit(String fields){if(!output.offer("{\"run_id\":\""+run+"\",\"time_ns\":"+System.nanoTime()+","+fields+"}"))overflow=true;}
    private void fail(String code){if(!failed){failed=true;emit("\"event\":\"prefill_failed\",\"code\":\""+code+"\"");}}
    @Override public void onDisable(){running=false;if(writer!=null)try{writer.join(3000);}catch(InterruptedException error){Thread.currentThread().interrupt();}}
}
