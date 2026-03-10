package dev.vox.lss.common.processing;

/**
 * Consolidates all per-tick and cumulative diagnostic counters for the processing thread.
 *
 * <p><b>Thread safety:</b> Single-writer (processing thread), multi-reader (main thread).
 * Uses {@code volatile} fields for cross-thread visibility without the overhead of
 * atomic CAS operations, since only the processing thread ever writes.
 */
public class ProcessingDiagnostics {
    // Per-tick counters — reset at the start of each processing cycle
    private volatile int procTickDiskQueued;
    private volatile int procTickDiskDrained;
    private volatile int procTickGenDrained;
    private volatile int procTickInMemory;
    private volatile int procTickSkippedDuplicate;
    private volatile int procTickUpToDate;
    private volatile int procTickSyncRateLimited;
    private volatile int procTickGenRateLimited;
    private volatile int procTickQueueFull;
    private volatile int procTickQueued;

    // Cumulative counters — never reset
    private volatile long totalQueueFull;
    private volatile long totalQueued;
    private volatile long totalInMemory;
    private volatile long totalUpToDate;
    private volatile long totalGenDrained;
    private volatile long totalSyncRateLimited;
    private volatile long totalGenRateLimited;

    public void resetTickCounters() {
        procTickDiskQueued = 0;
        procTickDiskDrained = 0;
        procTickGenDrained = 0;
        procTickInMemory = 0;
        procTickSkippedDuplicate = 0;
        procTickUpToDate = 0;
        procTickSyncRateLimited = 0;
        procTickGenRateLimited = 0;
        procTickQueueFull = 0;
        procTickQueued = 0;
    }

    // Per-tick increment methods
    public void incrementDiskQueued() { procTickDiskQueued++; }
    public void incrementDiskDrained() { procTickDiskDrained++; }
    public void incrementSkippedDuplicate() { procTickSkippedDuplicate++; }

    public void incrementGenDrained() {
        procTickGenDrained++;
        totalGenDrained++;
    }

    public void incrementInMemory() {
        procTickInMemory++;
        totalInMemory++;
    }

    public void incrementUpToDate() {
        procTickUpToDate++;
        totalUpToDate++;
    }

    public void incrementSyncRateLimited() {
        procTickSyncRateLimited++;
        totalSyncRateLimited++;
    }

    public void incrementGenRateLimited() {
        procTickGenRateLimited++;
        totalGenRateLimited++;
    }

    public void incrementQueueFull() {
        procTickQueueFull++;
        totalQueueFull++;
    }

    public void incrementQueued() {
        procTickQueued++;
        totalQueued++;
    }

    public void incrementRateLimited(RequestType type) {
        if (type == RequestType.SYNC) {
            incrementSyncRateLimited();
        } else {
            incrementGenRateLimited();
        }
    }

    // Per-tick getters (read by main thread)
    public int getLastDiskQueued() { return procTickDiskQueued; }
    public int getLastDiskDrained() { return procTickDiskDrained; }
    public int getLastGenDrained() { return procTickGenDrained; }
    public int getLastInMemory() { return procTickInMemory; }
    public int getLastSkippedDuplicate() { return procTickSkippedDuplicate; }
    public int getLastUpToDate() { return procTickUpToDate; }
    // Cumulative getters
    public long getTotalInMemory() { return totalInMemory; }
    public long getTotalUpToDate() { return totalUpToDate; }
    public long getTotalGenDrained() { return totalGenDrained; }
    public long getTotalSyncRateLimited() { return totalSyncRateLimited; }
    public long getTotalGenRateLimited() { return totalGenRateLimited; }
    public long getTotalQueueFull() { return totalQueueFull; }
    public long getTotalQueued() { return totalQueued; }
}
