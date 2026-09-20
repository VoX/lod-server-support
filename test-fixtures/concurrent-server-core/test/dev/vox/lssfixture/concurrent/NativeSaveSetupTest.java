package dev.vox.lssfixture.concurrent;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Behavioral setup controls; no Minecraft or save operation. */
public final class NativeSaveSetupTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static NativeSaveSetup.Observation observation(int interval,int cap){return new NativeSaveSetup.Observation(interval,cap,
            "minecraft:overworld","01234567-89ab-cdef-0123-456789abcdef","world","owning-region","4",100L);}
    static void fails(Runnable action){try{action.run();throw new AssertionError("expected rejection");}catch(IllegalStateException expected){}}
    public static void main(String[] ignored)throws Exception {
        NativeSaveSetup off=new NativeSaveSetup(null);check(off.ready());
        off.observe(false,()->{throw new AssertionError("disabled native read");},row->{throw new AssertionError("disabled event");});
        for(String invalid:List.of("","true","100","0200","200.0","-1")){
            try{new NativeSaveSetup(invalid);throw new AssertionError("invalid option");}catch(IllegalArgumentException expected){}
        }
        NativeSaveSetup missing=new NativeSaveSetup("200");check(!missing.ready());
        fails(()->missing.observe(false,()->{throw new AssertionError("off-owner read");},row->{}));check(!missing.ready());
        missing.observe(true,()->{throw new AssertionError("failed owner silently retried");},row->{});
        for(int[] pair:new int[][]{{100,24},{6000,24},{200,23},{200,25}}){
            var bad=new NativeSaveSetup("200");fails(()->bad.observe(true,()->observation(pair[0],pair[1]),row->{throw new AssertionError("bad config published");}));check(!bad.ready());
        }
        var broken=new NativeSaveSetup("200");fails(()->broken.observe(true,()->observation(200,24),row->{throw new IllegalStateException("writer rejected");}));check(!broken.ready());
        var once=new NativeSaveSetup("200");AtomicInteger reads=new AtomicInteger(),writes=new AtomicInteger();List<Map<String,Object>> rows=new CopyOnWriteArrayList<>();List<Thread> threads=new ArrayList<>();
        for(int i=0;i<32;i++)threads.add(new Thread(()->once.observe(true,()->{reads.incrementAndGet();return observation(200,24);},row->{check(!once.ready());rows.add(row);writes.incrementAndGet();})));
        for(Thread t:threads)t.start();for(Thread t:threads)t.join();
        check(once.ready()&&reads.get()==1&&writes.get()==1&&rows.size()==1);
        check(rows.getFirst().get("owns_region").equals(true)&&rows.getFirst().get("observed_ns").equals(100L));
        var wrongWorld=new NativeSaveSetup("200");fails(()->wrongWorld.observe(true,()->new NativeSaveSetup.Observation(200,24,"minecraft:the_nether","bad","world","owning-region","1",1),row->{throw new AssertionError();}));
        // Stop the real writer to deterministically saturate its actual bounded queue.
        var evidence=java.nio.file.Files.createTempDirectory("lss-native-save-control-");
        System.setProperty("lss.rig.runId","native-save-control");System.setProperty("lss.rig.evidence",evidence.toString());
        var workload=new SourceWorkload(new SourcePreparationTest.Engine(),-64);workload.close();
        var field=SourceWorkload.class.getDeclaredField("events");field.setAccessible(true);
        @SuppressWarnings("unchecked") var queue=(ArrayBlockingQueue<String>)field.get(workload);
        while(queue.offer("{}")){}
        var full=new NativeSaveSetup("200");fails(()->full.observe(true,()->observation(200,24),workload::nativeSaveSetup));check(!full.ready());
        var overflow=SourceWorkload.class.getDeclaredField("overflow");overflow.setAccessible(true);check(overflow.getBoolean(workload));
        try(var files=java.nio.file.Files.list(evidence)){for(var file:files.toList())java.nio.file.Files.delete(file);}java.nio.file.Files.delete(evidence);
        System.out.println("NativeSaveSetupTest passed");
    }
}
