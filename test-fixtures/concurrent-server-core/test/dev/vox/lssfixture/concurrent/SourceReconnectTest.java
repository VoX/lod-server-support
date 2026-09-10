package dev.vox.lssfixture.concurrent;
import java.nio.file.*;
import java.util.*;
import com.google.gson.Gson;
/** A successor native state may exist before its owner-bound oracle join. */
public final class SourceReconnectTest {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("lss-source-reconnect-");
        System.setProperty("lss.rig.runId","test-run");System.setProperty("lss.rig.evidence",root.toString());
        String subject="RigSubjectA",first="test-run-"+subject+"-1",second="test-run-"+subject+"-2";
        var gson=new Gson();
        try(var workload=new SourceWorkload(new SourcePreparationTest.Engine(),-64)) {
            var stage=SourceWorkload.class.getDeclaredField("stage");stage.setAccessible(true);stage.setInt(workload,3);
            var origin=SourceWorkload.class.getDeclaredField("origin");origin.setAccessible(true);origin.setLong(workload,System.nanoTime());
            var read=SourceWorkload.class.getDeclaredMethod("readAcknowledgements",Path.class);read.setAccessible(true);
            workload.join(subject);
            Set<String> ids=new HashSet<>();for(int source=0;source<4;source++)ids.add("test-run-"+subject+"-"+source+"-initial");
            Path ack=root.resolve("oracle-ack-"+subject+".json"),pending=root.resolve("ack.tmp");
            Files.writeString(pending,gson.toJson(Map.of("run_id","test-run","subject",subject,"connection_id",first,"targets",ids,"target_sequence",0)));
            Files.move(pending,ack,StandardCopyOption.ATOMIC_MOVE);read.invoke(workload,root);
            if(workload.denied(subject))throw new AssertionError("current session ack not admitted");
            var field=SourceWorkload.class.getDeclaredField("activeConnections");field.setAccessible(true);
            @SuppressWarnings("unchecked") var active=(Map<String,String>)field.get(workload);
            active.put(subject,"different-native-session");
            if(!workload.denied(subject))throw new AssertionError("stale ack ignored active connection identity");
            active.put(subject,first);
            workload.quit(subject);
            if(!workload.denied(subject))throw new AssertionError("old ack admits successor before oracle join");
            workload.join(subject);
            if(!workload.denied(subject))throw new AssertionError("old ack admits successor after oracle join");
            Files.writeString(pending,gson.toJson(Map.of("run_id","test-run","subject",subject,"connection_id",second,"targets",List.of(),"target_sequence",0)));
            Files.move(pending,ack,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);read.invoke(workload,root);
            if(workload.denied(subject))throw new AssertionError("successor's own ack not admitted");
        }
        try(var files=Files.list(root)){for(Path file:files.toList())Files.delete(file);}Files.delete(root);
        System.out.println("SourceReconnect: retired ack blocked before/after successor oracle join; current ack releases");
    }
}
