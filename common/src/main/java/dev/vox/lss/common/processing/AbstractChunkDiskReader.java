package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.LogThrottle;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base class for async chunk disk readers. Provides executor setup, per-player result
 * queues, diagnostics, the full submit/triage envelope (saturation, errors, all-air,
 * not-found), and shutdown logic. Subclasses supply only the platform-specific
 * {@link ReadOperation} that produces serialized section bytes.
 */
public abstract class AbstractChunkDiskReader {

    /** Platform hook: read the chunk's NBT and serialize visible sections to wire bytes.
     *  Returns null for "not found"; an empty array for an all-air chunk. */
    @FunctionalInterface
    public interface ReadOperation {
        byte[] read() throws Exception;
    }

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();
    private static final int QUEUE_CAPACITY_PER_THREAD = 32;

    // Saturation is a normal, self-healing path: the result is dropped silently and the
    // client's next want-set re-declares the position (v17 — nothing is bounced back). Since
    // the router's headroom gate stops submits into a full pool, a rejection here is now a
    // residual (race/shutdown) rather than the steady state, but a burst can still reject many
    // reads per second, and one WARN per position floods the console (#32). Aggregate to at
    // most one warning per minute carrying the rejected count; per-delivery detail stays on
    // the debug path in OffThreadProcessor.
    private static final long SATURATION_WARN_INTERVAL_MS = 60_000;
    private final LogThrottle saturationWarn = new LogThrottle(SATURATION_WARN_INTERVAL_MS);

    private final ExecutorService executor;
    private final ArrayBlockingQueue<Runnable> workQueue;
    private final int threadCount;
    private final ConcurrentHashMap<UUID, ConcurrentLinkedQueue<ChunkReadResult>> playerResults = new ConcurrentHashMap<>();
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    // Adaptive read throttle (Approach B): null until a platform reader detects that its
    // background-priority path is incompatible (a chunk-IO-overhaul mod replaced vanilla IO) and
    // calls enableAdaptiveThrottleFallback(). Never set on a working-A server — A gives true
    // priority, so throttling would only cost LSS throughput for no gameplay benefit. Volatile:
    // enabled on the processing/submit thread, read by hasHeadroom() (submit thread) and fed by
    // recordRealCompletion() (pool threads).
    private volatile AdaptiveReadThrottle throttle;

    protected final DiskReaderDiagnostics diag = new DiskReaderDiagnostics();

    protected AbstractChunkDiskReader(int threadCount) {
        this.threadCount = threadCount;
        int queueCapacity = threadCount * QUEUE_CAPACITY_PER_THREAD;
        var workQueue = new ArrayBlockingQueue<Runnable>(queueCapacity);
        this.workQueue = workQueue;
        this.executor = new ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS,
                workQueue, r -> {
            var thread = new Thread(r, "LSS Disk Reader #" + THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    /**
     * True when a submit will not be rejected. Single-submitter contract: only the
     * processing thread submits, and pool workers only DRAIN the queue, so a true result
     * cannot turn into a rejection before the same thread's next submit (the race is
     * pessimistic-only). This is what keeps disk saturation out of the client-visible
     * protocol: the router leaves the entry in the backlog instead of submitting into a
     * full pool.
     */
    public boolean hasHeadroom() {
        if (this.workQueue.remainingCapacity() <= 0) return false;   // pool queue full (unchanged)
        var t = this.throttle;                                       // null on the working-A path
        if (t != null) {
            // Approach B expressed as a headroom modifier: when the adaptive limit is reached the
            // router leaves the read in the want-set backlog (NO_DISK_HEADROOM) and the client
            // re-declares it — no bounce, no rate_limited (retired in v17). in-flight = submitted -
            // completed, the same measure the pool bounds.
            int inFlight = (int) Math.max(0, this.diag.getSubmittedCount() - this.diag.getCompletedCount());
            if (!t.canSubmit(inFlight)) return false;
        }
        return true;
    }

    /**
     * Idempotently enable the adaptive-throttle fallback (a platform reader's background-priority
     * path reported itself incompatible — a chunk-IO-overhaul mod replaced vanilla IO). Safe from
     * any thread; the first caller wins. The throttle starts at the pool's full depth (optimistic),
     * so enabling it does not restrict until measured read latency actually rises.
     */
    protected final void enableAdaptiveThrottleFallback() {
        if (this.throttle == null) {
            synchronized (this) {
                if (this.throttle == null) {
                    this.throttle = AdaptiveReadThrottle.forPool(this.threadCount,
                            QUEUE_CAPACITY_PER_THREAD, LSSConstants.ADAPTIVE_READ_TARGET_LATENCY_MS);
                }
            }
        }
    }

    /** The adaptive throttle's current effective concurrency limit, or -1 when it is not engaged
     *  (the normal working-A path). For {@code /lsslod diag}. */
    public int adaptiveThrottleLimitOrDisabled() {
        var t = this.throttle;
        return t == null ? -1 : t.currentLimit();
    }

    /** Package-private test seam: the live throttle instance (null until engaged), so the
     *  in-package wiring test can drive its AIMD limit with synthetic latency samples without
     *  occupying a pool thread. Production reads throttle state via
     *  {@link #adaptiveThrottleLimitOrDisabled()}. */
    AdaptiveReadThrottle throttleForTest() {
        return this.throttle;
    }

    protected boolean isShutdown() {
        return this.isShutdown.get();
    }

    /**
     * Submit a read: the operation runs on the reader pool and its outcome is triaged into
     * the player's result queue (data / all-air / not-found / saturated). An {@link Error}
     * still produces a result first — otherwise the request would be stranded in flight
     * forever (leaked admission slot + orphaned dedup group) — and is then rethrown.
     */
    protected final void submitRead(UUID playerUuid, int chunkX, int chunkZ, String dimension,
                                     long submissionOrder, ReadOperation operation) {
        if (isShutdown()) return;

        this.diag.recordSubmitted();
        try {
            this.executor.submit(() -> {
                if (isShutdown()) return;
                try {
                    readAndDeliver(playerUuid, chunkX, chunkZ, dimension, submissionOrder, operation);
                } catch (Throwable t) {
                    LSSLogger.error("Failed to read chunk from disk at " + chunkX + ", " + chunkZ, t);
                    this.diag.recordError();
                    this.diag.recordCompleted(0);
                    addResult(playerUuid, ChunkReadResult.empty(playerUuid, chunkX, chunkZ, dimension, submissionOrder));
                    if (t instanceof Error err) throw err;
                }
            });
        } catch (RejectedExecutionException e) {
            // nanoTime, not currentTimeMillis: the wall clock can step backwards (NTP), which
            // would silence the aggregate warning exactly while the pool is behind demand.
            long rejected = this.saturationWarn.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (rejected > 0) {
                LSSLogger.warn("Disk reader saturated: " + rejected + " chunk read(s) dropped"
                        + " since the last warning — clients re-request automatically; raise"
                        + " diskReaderThreads in lss-server-config.json if this persists");
            }
            this.diag.recordSaturation();
            this.diag.recordCompleted(0);
            addResult(playerUuid, ChunkReadResult.saturated(playerUuid, chunkX, chunkZ, dimension, submissionOrder));
        }
    }

    /** Record a REAL read completion: the diagnostics count plus, when the adaptive throttle is
     *  engaged, the measured submit->result latency (the 0-latency bounce/error-before-IO paths
     *  are NOT fed — they measured no IO and would poison the EWMA). */
    private void recordRealCompletion(long elapsedNanos) {
        this.diag.recordCompleted(elapsedNanos);
        var t = this.throttle;
        if (t != null) t.recordLatency(elapsedNanos);
    }

    private void readAndDeliver(UUID playerUuid, int chunkX, int chunkZ, String dimension,
                                 long submissionOrder, ReadOperation operation) {
        if (isShutdown()) return;
        long startNs = System.nanoTime();

        byte[] serializedSections;
        try {
            serializedSections = operation.read();
        } catch (Exception e) {
            LSSLogger.error("Failed to read chunk NBT from disk at " + chunkX + ", " + chunkZ, e);
            this.diag.recordError();
            recordRealCompletion(System.nanoTime() - startNs);
            addResult(playerUuid, ChunkReadResult.empty(playerUuid, chunkX, chunkZ, dimension, submissionOrder));
            return;
        }

        if (serializedSections == null) {
            this.diag.recordNotFound();
            recordRealCompletion(System.nanoTime() - startNs);
            addResult(playerUuid, ChunkReadResult.empty(playerUuid, chunkX, chunkZ, dimension, submissionOrder));
            return;
        }

        long columnTimestamp = LSSConstants.epochSeconds();

        if (serializedSections.length == 0) {
            // Chunk exists on disk (FULL status) but is all air — resolve as found, not "not found"
            this.diag.recordAllAir();
            recordRealCompletion(System.nanoTime() - startNs);
            addResult(playerUuid, new ChunkReadResult(playerUuid, chunkX, chunkZ,
                    null, dimension, 0, columnTimestamp, false, false, submissionOrder));
            return;
        }

        int estimatedBytes = serializedSections.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;

        this.diag.recordSuccess();
        recordRealCompletion(System.nanoTime() - startNs);
        addResult(playerUuid, new ChunkReadResult(playerUuid, chunkX, chunkZ,
                serializedSections, dimension, estimatedBytes, columnTimestamp,
                false, false, submissionOrder));
    }

    public void registerPlayer(UUID playerUuid) {
        this.playerResults.computeIfAbsent(playerUuid, k -> new ConcurrentLinkedQueue<>());
    }

    private void addResult(UUID playerUuid, ChunkReadResult result) {
        var queue = this.playerResults.get(playerUuid);
        if (queue != null) {
            queue.add(result);
        }
    }

    public ConcurrentLinkedQueue<ChunkReadResult> getPlayerQueue(UUID playerUuid) {
        return this.playerResults.get(playerUuid);
    }

    public void removePlayerResults(UUID playerUuid) {
        this.playerResults.remove(playerUuid);
    }

    public String getDiagnostics() {
        return this.diag.formatDiagnostics(getPendingResultCount());
    }

    /** Read results delivered but not yet drained by the processing thread, across all players. */
    public int getPendingResultCount() {
        int pending = 0;
        for (var queue : this.playerResults.values()) {
            pending += queue.size();
        }
        return pending;
    }

    public DiskReaderDiagnostics getDiag() { return this.diag; }

    public void shutdown() {
        this.isShutdown.set(true);
        this.executor.shutdownNow();
        try {
            if (!this.executor.awaitTermination(5, TimeUnit.SECONDS)) {
                LSSLogger.warn("Disk reader threads did not terminate within 5 seconds");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        this.playerResults.clear();
    }
}
