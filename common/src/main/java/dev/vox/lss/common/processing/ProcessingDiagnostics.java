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

    // Cumulative counters — never reset
    private volatile long totalQueueFull;
    private volatile long totalInMemory;
    private volatile long totalUpToDate;
    private volatile long totalGenDrained;
    private volatile long totalSyncRateLimited;
    private volatile long totalGenRateLimited;
    private volatile long totalDuplicateSkips;
    private volatile long totalRequestsRouted;
    private volatile long totalReResolved;

    public void resetTickCounters() {
        procTickDiskQueued = 0;
        procTickDiskDrained = 0;
        procTickGenDrained = 0;
        procTickInMemory = 0;
        procTickSkippedDuplicate = 0;
        procTickUpToDate = 0;
    }

    // Per-tick increment methods
    public void incrementDiskQueued() { procTickDiskQueued++; }
    public void incrementDiskDrained() { procTickDiskDrained++; }

    public void incrementSkippedDuplicate() {
        procTickSkippedDuplicate++;
        totalDuplicateSkips++;
    }

    /** One increment per request polled off a player's incoming queue (whether answered or dropped). */
    public void incrementRequestRouted() { totalRequestsRouted++; }

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
        totalSyncRateLimited++;
    }

    public void incrementGenRateLimited() {
        totalGenRateLimited++;
    }

    public void incrementQueueFull() {
        totalQueueFull++;
    }

    public void incrementRateLimited(RequestType type) {
        if (type == RequestType.SYNC) {
            incrementSyncRateLimited();
        } else {
            incrementGenRateLimited();
        }
    }

    /** A ts&le;0 re-request cleared a stale diskReadDone entry and re-entered resolution. */
    public void incrementReResolved() {
        totalReResolved++;
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
    public long getTotalDuplicateSkips() { return totalDuplicateSkips; }
    public long getTotalRequestsRouted() { return totalRequestsRouted; }
    public long getTotalReResolved() { return totalReResolved; }
}
