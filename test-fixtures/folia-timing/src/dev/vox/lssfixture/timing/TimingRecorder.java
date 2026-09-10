package dev.vox.lssfixture.timing;

import java.nio.file.*;
import java.io.*;
import java.util.concurrent.*;

public final class TimingRecorder {
    private static final ArrayBlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(65536);
    private static final ThreadLocal<long[]> SPAN = ThreadLocal.withInitial(() -> new long[2]);
    private static volatile boolean running, overflow;
    private static Thread writer;
    public static void start() {
        String run = System.getProperty("lss.rig.runId", "");
        Path root = Path.of(System.getProperty("lss.rig.evidence", ""));
        if (!run.matches("[A-Za-z0-9_-]+") || !root.isAbsolute() || Files.isSymbolicLink(root))
            throw new IllegalStateException("explicit owned run and evidence required");
        running = true;
        writer = new Thread(() -> {
            try (BufferedWriter stream = Files.newBufferedWriter(root.resolve("tick-events.jsonl"))) {
                while (running || !QUEUE.isEmpty()) {
                    String row = QUEUE.poll(200,TimeUnit.MILLISECONDS);
                    if (row != null) { stream.write(row); stream.newLine(); stream.flush(); }
                }
                stream.write("{\"event\":\"timing_closed\",\"overflow\":" + overflow + "}\n");
            } catch (Exception e) { overflow = true; }
        },"LSS-RigTickEvidence");
        writer.setDaemon(true);writer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            try { writer.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
        emit("{\"event\":\"timing_ready\",\"run_id\":\""+run+"\"}");
    }
    private static void emit(String row) { if (!QUEUE.offer(row)) overflow = true; }
    public static void failure(String code) { emit("{\"event\":\"timing_failure\",\"code\":\""+code+"\"}"); }
    public static void applied(String sha) { emit("{\"event\":\"timing_applied\",\"class_sha256\":\""+sha+"\"}"); }
    public static void begin(long region) { long[] span=SPAN.get();span[0]=region;span[1]=System.nanoTime(); }
    public static void end(boolean failed) {
        long end=System.nanoTime();long[] span=SPAN.get();
        emit("{\"event\":\"owning_tick\",\"region_identity\":\""+span[0]+"\",\"start_ns\":"+span[1]+",\"end_ns\":"+end+",\"failed\":"+failed+"}");
    }
    public static void metric(long region,long scheduled,long start,long end) {
        emit("{\"event\":\"tick_metric\",\"region_identity\":\""+region+"\",\"scheduled_ns\":"+scheduled+",\"start_ns\":"+start+",\"end_ns\":"+end+"}");
    }
}
