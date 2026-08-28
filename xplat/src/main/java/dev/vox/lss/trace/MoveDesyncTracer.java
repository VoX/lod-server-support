package dev.vox.lss.trace;

import dev.vox.lss.common.LSSLogger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The move-desync tracer's writer (move-desync-tracer-plan.md §1.1): a single daemon
 * thread draining a bounded queue of pre-rendered JSONL rows into
 * {@code logs/lss-move-trace.jsonl}. Game threads only ever {@code offer()} — drops are
 * counted, never waited on. Ships in the release jar but is fully inert unless activated
 * (§0: {@code -Dlss.moveTrace=true} or the {@code config/lss-move-trace.enable} marker
 * file, read once at SERVER_STARTING by {@link MoveTraceBootstrap}).
 *
 * <p>Rotation, not truncation (review U-13): at {@link #DEFAULT_ROTATE_BYTES} the file
 * rotates once to {@code lss-move-trace.1.jsonl}; a second rotation point is the final
 * cap — warned in the server log (where the operator greps), then rows drop. Appends
 * across restarts; every row carries {@code bootId} + schema version.
 */
public final class MoveDesyncTracer {

    /** Bumped on any row-schema change; {@code check_move_trace.py} keys on it. */
    public static final int SCHEMA_VERSION = 1;
    static final long DEFAULT_ROTATE_BYTES = 128L << 20;
    static final int QUEUE_CAPACITY = 4096;

    /** The active instance, or null when the tracer is off — the §0 static gate. Every
     *  hook body checks this exactly once before doing any work. */
    private static volatile MoveDesyncTracer active;

    private final Path file;
    private final Path rotatedFile;
    private final long rotateBytes;
    private final String bootId;
    private final ArrayBlockingQueue<String> queue;
    private final Thread writerThread;
    private final Consumer<String> testSink;

    private final AtomicLong rowsWritten = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong tooQuickly = new AtomicLong();
    private final AtomicLong wrongly = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong silentRejected = new AtomicLong();

    private volatile boolean capped;
    private volatile boolean closed;
    private volatile String rung = "none";

    /** Production writer. */
    MoveDesyncTracer(Path file, long rotateBytes) {
        this(file, rotateBytes, QUEUE_CAPACITY, true);
    }

    /** Test-injectable flavor: bounded queue size and an unstarted writer make the
     *  overflow-drop accounting deterministic. */
    MoveDesyncTracer(Path file, long rotateBytes, int queueCapacity, boolean startWriter) {
        this.file = file;
        this.rotatedFile = file.resolveSibling(rotatedName(file));
        this.rotateBytes = rotateBytes;
        this.bootId = newBootId();
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.testSink = null;
        this.writerThread = new Thread(this::writerLoop, dev.vox.lss.common.Brand.shortName() + "-MoveTrace");
        this.writerThread.setDaemon(true);
        if (startWriter) {
            this.writerThread.start();
        }
    }

    /** Test seam: start a writer constructed with {@code startWriter=false}. */
    void startWriterForTest() {
        this.writerThread.start();
    }

    /** Test sink variant: no thread, no file — rows go straight to the consumer. */
    MoveDesyncTracer(Consumer<String> sink) {
        this.file = null;
        this.rotatedFile = null;
        this.rotateBytes = Long.MAX_VALUE;
        this.bootId = newBootId();
        this.queue = null;
        this.testSink = sink;
        this.writerThread = null;
    }

    private static String rotatedName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name + ".1" : name.substring(0, dot) + ".1" + name.substring(dot);
    }

    private static String newBootId() {
        return Long.toHexString(ThreadLocalRandom.current().nextLong() | (1L << 62));
    }

    // ---- static gate ----

    /** The one check every hook body makes before any work. */
    public static boolean enabled() {
        return active != null;
    }

    /** The active tracer, or null when off. */
    public static MoveDesyncTracer active() {
        return active;
    }

    static void activate(MoveDesyncTracer tracer) {
        // A stop->start in one JVM (integrated relaunch, gametest server) must never
        // leave two writers appending to the same file (review A-8).
        deactivate();
        active = tracer;
    }

    static void deactivate() {
        var t = active;
        active = null;
        if (t != null) t.close();
    }

    /** Tier 2 seam — the gametest JVM does not set the property (§3). */
    public static MoveDesyncTracer enableForTest(Consumer<String> sink) {
        var tracer = new MoveDesyncTracer(sink);
        active = tracer;
        return tracer;
    }

    /** Tier 2 seam teardown. */
    public static void disableForTest() {
        active = null;
    }

    // ---- emission ----

    /** Non-blocking; called from game threads with a fully rendered row. A null row is
     *  a failed render (MoveRow contains it) — counted dropped, never thrown (A-6).
     *  {@code rows=} counts WRITTEN rows (incremented by the writer thread after the
     *  write), so the diag line cannot report rows the cap or an IO failure discarded
     *  (review A-5). */
    void emit(String jsonRow) {
        if (jsonRow == null || closed || capped) {
            dropped.incrementAndGet();
            return;
        }
        if (testSink != null) {
            testSink.accept(jsonRow);
            rowsWritten.incrementAndGet();
            return;
        }
        if (!queue.offer(jsonRow)) {
            dropped.incrementAndGet();
        }
    }

    String bootId() {
        return bootId;
    }

    long droppedCount() {
        return dropped.get();
    }

    long rowCount() {
        return rowsWritten.get();
    }

    void setRung(String rung) {
        this.rung = rung;
    }

    String rung() {
        return rung;
    }

    void countEvent(String type, boolean loggedWrongly) {
        switch (type) {
            case MoveRow.TYPE_TOO_QUICKLY -> tooQuickly.incrementAndGet();
            case MoveRow.TYPE_WRONGLY -> wrongly.incrementAndGet();
            case MoveRow.TYPE_REJECTED -> {
                rejected.incrementAndGet();
                if (!loggedWrongly) silentRejected.incrementAndGet();
            }
            default -> { }
        }
    }

    /** The active-only diag line (§2): post-deploy rung verification + the silent-rejection
     *  rate the investigation was missing, in one RCON call. */
    public String diagLine() {
        return "MoveTrace: rung=" + rung
                + " rows=" + rowsWritten.get()
                + " drops=" + dropped.get()
                + " tooquick=" + tooQuickly.get()
                + " wrongly=" + wrongly.get()
                + " rejected=" + rejected.get()
                + " silent=" + silentRejected.get();
    }

    // ---- writer thread ----

    private void writerLoop() {
        BufferedWriter out = null;
        long bytesWritten = 0;
        // Rotation is once PER BOOT, over any stale .1 (review B-5): an operator who
        // collected and deleted only the live file must not inherit a cap latch from a
        // previous boot's leftover .1 — U-13's silent-stop, one restart later. Total
        // disk stays bounded at 2x rotateBytes regardless.
        boolean rotatedThisBoot = false;
        try {
            Files.createDirectories(file.getParent());
            bytesWritten = Files.exists(file) ? Files.size(file) : 0;
            out = open();
            while (true) {
                String row;
                try {
                    row = queue.poll(250, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    // A close() escalation or a stray interrupt: latch + drain so emit
                    // callers and the diag counters stay honest (review A-5) — the
                    // alternative is a silently dead writer under a healthy diag line.
                    capped = true;
                    drainRemainderAsDrops();
                    break;
                }
                if (row == null) {
                    if (closed) break;
                    continue;
                }
                if (bytesWritten >= rotateBytes) {
                    if (rotatedThisBoot) {
                        // Second crossing this boot = the 2x total cap. latest.log is
                        // where the operator greps — a sentinel row inside a file nobody
                        // is watching is not an alert (U-13).
                        capped = true;
                        LSSLogger.warn("Move trace hit its total size cap ("
                                + (rotateBytes * 2 / (1 << 20)) + " MiB across " + file.getFileName()
                                + " + " + rotatedFile.getFileName() + ") — tracing stopped, rows now drop");
                        out.close();
                        out = null;
                        dropped.incrementAndGet(); // the row that hit the cap
                        drainRemainderAsDrops();
                        break;
                    }
                    out.close();
                    Files.deleteIfExists(rotatedFile);
                    Files.move(file, rotatedFile);
                    rotatedThisBoot = true;
                    LSSLogger.warn("Move trace rotated " + file.getFileName() + " -> "
                            + rotatedFile.getFileName() + " at " + (rotateBytes / (1 << 20))
                            + " MiB; one rotation remains before the cap");
                    out = open();
                    bytesWritten = 0;
                }
                byte[] bytes = (row + "\n").getBytes(StandardCharsets.UTF_8);
                out.write(row);
                out.write('\n');
                // Flush per row: rows are sparse, and a crash must not lose its own event.
                out.flush();
                bytesWritten += bytes.length;
                rowsWritten.incrementAndGet();
            }
            if (out != null && closed) {
                String row;
                while ((row = queue.poll()) != null) {
                    out.write(row);
                    out.write('\n');
                    rowsWritten.incrementAndGet();
                }
                out.flush();
            }
        } catch (IOException e) {
            LSSLogger.warn("Move trace writer failed (" + e + ") — tracing stopped, rows now drop");
            capped = true;
            drainRemainderAsDrops();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private BufferedWriter open() throws IOException {
        return Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void drainRemainderAsDrops() {
        while (queue.poll() != null) {
            dropped.incrementAndGet();
        }
    }

    /** SERVER_STOPPING: drain and close (§1.1). Escalates to an interrupt if the
     *  writer is wedged past the bounded join, so a relaunch in the same JVM can never
     *  find two live writers on one file (review A-8). */
    void close() {
        closed = true;
        if (writerThread != null) {
            try {
                writerThread.join(2000);
                if (writerThread.isAlive()) {
                    writerThread.interrupt();
                    writerThread.join(1000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
