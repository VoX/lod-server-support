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
    private volatile long totalDuplicateSkips;
    private volatile long totalRequestsRouted;
    private volatile long totalReResolved;
    // Cumulative (single-writer: processing thread — cross-thread producers accumulate in
    // per-player AtomicLongs and are drained here each cycle)
    private volatile long totalSuperseded;
    private volatile long totalRangeFiltered;
    private volatile long totalMissDropped;
    private volatile long totalGenOrderGated;
    private volatile long totalMemoHits;
    private volatile long totalGenCompletionInversions;

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

    /** One increment per backlog entry PERMANENTLY leaving the backlog with a disposition
     *  (answered, admitted, or duplicate-skipped). Retained entries are not counted;
     *  superseded entries are counted in {@code superseded} instead. */
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

    public void incrementQueueFull() {
        totalQueueFull++;
    }

    /** A ts&le;0 re-request cleared a stale diskReadDone entry and re-entered resolution. */
    public void incrementReResolved() {
        totalReResolved++;
    }

    /** Requests dropped without a wire response (mailbox overwrite, backlog replace, Folia
     *  held-batch loss, residual disk-saturation drop, dedup-primary-departure attached
     *  drop). Recoverable by re-declaration; balanced in soak law A1. */
    public void addSuperseded(long n) { if (n > 0) totalSuperseded += n; }

    /** Ingress entries dropped by the Chebyshev range guard (counted client-side in
     *  requested_total but never entering the backlog — the A1 term for the motion race). */
    public void addRangeFiltered(long n) { if (n > 0) totalRangeFiltered += n; }

    /** Disk misses that resolved into a TRANSIENT silent drop with no generation submission
     *  and no wire answer (gen slot full at the miss, capacity/removed-player reject, ghost
     *  delivery). The dedicated soak-law-A5 term: disk.not_found == generation.submitted +
     *  not_generated responses + miss_dropped. A subset of {@code superseded} events, but
     *  counted separately — backlog-replace supersession never touches disk, so folding the
     *  two would over-balance A5. */
    public void addMissDropped(long n) { if (n > 0) totalMissDropped += n; }

    /** Miss-memo rung hits (disk.memo_hits): a fresh memoized miss skipped the redundant
     *  disk re-read and went straight to the generation ladder. Law A5 counts these as
     *  VIRTUAL not-founds on its left-hand side — each hit is dispositioned exactly like a
     *  real miss (gen submit or miss_dropped), so the identity balances by inspection. */
    public void addMemoHit(long n) { if (n > 0) totalMemoHits += n; }

    /** Generation-ordering refusals (a subset of miss_dropped), aggregated across all
     *  three refusal sites in escalateMissToGeneration: the nearer-pending-SYNC hold, the
     *  generation cohort span (candidate beyond nearest-outstanding + span), and the
     *  frontier order-spread gate. Nonzero is the pacing actively converting admission
     *  disorder into drain order — expect plenty during backfill, and more on platforms
     *  whose scheduler completes non-FIFO (C2ME). */
    public void addGenOrderGated(long n) { if (n > 0) totalGenOrderGated += n; }

    /** Successful generation completions that arrived while a NEARER ticket was still
     *  outstanding — direct evidence the platform scheduler completes far-before-near.
     *  Diagnostics only; the order-spread gate bounds how bad this can look on screen. */
    public void addGenCompletionInversion(long n) { if (n > 0) totalGenCompletionInversions += n; }

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
    public long getTotalQueueFull() { return totalQueueFull; }
    public long getTotalDuplicateSkips() { return totalDuplicateSkips; }
    public long getTotalRequestsRouted() { return totalRequestsRouted; }
    public long getTotalReResolved() { return totalReResolved; }
    public long getTotalSuperseded() { return totalSuperseded; }
    public long getTotalRangeFiltered() { return totalRangeFiltered; }
    public long getTotalMissDropped() { return totalMissDropped; }
    public long getTotalMemoHits() { return totalMemoHits; }
    public long getTotalGenOrderGated() { return totalGenOrderGated; }
    public long getTotalGenCompletionInversions() { return totalGenCompletionInversions; }
}
