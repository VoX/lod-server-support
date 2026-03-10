package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSLogger;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base class for async chunk disk readers. Provides executor setup, per-player
 * result queues, diagnostics, and shutdown logic. Subclasses implement the
 * platform-specific disk read and serialization.
 *
 * @param <R> the read result type (must implement {@link ReadResultAccess})
 */
public abstract class AbstractChunkDiskReader<R extends ReadResultAccess> {

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();
    private static final int QUEUE_CAPACITY_PER_THREAD = 32;

    protected final ExecutorService executor;
    private final ConcurrentHashMap<UUID, ConcurrentLinkedQueue<R>> playerResults = new ConcurrentHashMap<>();
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    protected final DiskReaderDiagnostics diag = new DiskReaderDiagnostics();

    protected AbstractChunkDiskReader(int threadCount) {
        int queueCapacity = threadCount * QUEUE_CAPACITY_PER_THREAD;
        this.executor = new ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), r -> {
            var thread = new Thread(r, "LSS Disk Reader #" + THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    protected boolean isShutdown() {
        return this.isShutdown.get();
    }

    public void registerPlayer(UUID playerUuid) {
        this.playerResults.computeIfAbsent(playerUuid, k -> new ConcurrentLinkedQueue<>());
    }

    protected void addResult(UUID playerUuid, R result) {
        var queue = this.playerResults.get(playerUuid);
        if (queue != null) {
            queue.add(result);
        }
    }

    public ConcurrentLinkedQueue<R> getPlayerQueue(UUID playerUuid) {
        return this.playerResults.get(playerUuid);
    }

    public void removePlayerResults(UUID playerUuid) {
        this.playerResults.remove(playerUuid);
    }

    public String getDiagnostics() {
        int pending = 0;
        for (var queue : this.playerResults.values()) {
            pending += queue.size();
        }
        return this.diag.formatDiagnostics(pending);
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
