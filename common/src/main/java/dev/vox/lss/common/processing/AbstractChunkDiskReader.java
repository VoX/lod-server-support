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

    // Saturation is a normal, self-healing protocol path (the bounced request comes back as
    // rate-limited and the client retries on a later scan), but while the pool is behind
    // demand it can reject many reads per second — one WARN per position floods the console
    // (#32). Aggregate to at most one warning per minute carrying the rejected count;
    // per-delivery detail stays on the debug path in OffThreadProcessor.
    private static final long SATURATION_WARN_INTERVAL_MS = 60_000;
    private final LogThrottle saturationWarn = new LogThrottle(SATURATION_WARN_INTERVAL_MS);

    private final ExecutorService executor;
    private final ArrayBlockingQueue<Runnable> workQueue;
    private final ConcurrentHashMap<UUID, ConcurrentLinkedQueue<ChunkReadResult>> playerResults = new ConcurrentHashMap<>();
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    protected final DiskReaderDiagnostics diag = new DiskReaderDiagnostics();

    protected AbstractChunkDiskReader(int threadCount) {
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
        return this.workQueue.remainingCapacity() > 0;
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
                LSSLogger.warn("Disk reader saturated: " + rejected + " chunk read(s) bounced as rate-limited"
                        + " since the last warning — clients retry automatically; raise diskReaderThreads"
                        + " in lss-server-config.json if this persists");
            }
            this.diag.recordSaturation();
            this.diag.recordCompleted(0);
            addResult(playerUuid, ChunkReadResult.saturated(playerUuid, chunkX, chunkZ, dimension, submissionOrder));
        }
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
            this.diag.recordCompleted(System.nanoTime() - startNs);
            addResult(playerUuid, ChunkReadResult.empty(playerUuid, chunkX, chunkZ, dimension, submissionOrder));
            return;
        }

        if (serializedSections == null) {
            this.diag.recordNotFound();
            this.diag.recordCompleted(System.nanoTime() - startNs);
            addResult(playerUuid, ChunkReadResult.empty(playerUuid, chunkX, chunkZ, dimension, submissionOrder));
            return;
        }

        long columnTimestamp = LSSConstants.epochSeconds();

        if (serializedSections.length == 0) {
            // Chunk exists on disk (FULL status) but is all air — resolve as found, not "not found"
            this.diag.recordAllAir();
            this.diag.recordCompleted(System.nanoTime() - startNs);
            addResult(playerUuid, new ChunkReadResult(playerUuid, chunkX, chunkZ,
                    null, dimension, 0, columnTimestamp, false, false, submissionOrder));
            return;
        }

        int estimatedBytes = serializedSections.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;

        this.diag.recordSuccess();
        this.diag.recordCompleted(System.nanoTime() - startNs);
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
