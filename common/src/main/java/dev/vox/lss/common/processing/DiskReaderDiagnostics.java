package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;

import java.util.concurrent.atomic.AtomicLong;

public class DiskReaderDiagnostics {
    private final AtomicLong submittedCount = new AtomicLong();
    private final AtomicLong completedCount = new AtomicLong();
    private final AtomicLong notFoundCount = new AtomicLong();
    private final AtomicLong allAirCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final AtomicLong saturationCount = new AtomicLong();
    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong gatedCount = new AtomicLong();
    private final AtomicLong gateStopsCount = new AtomicLong();
    private final AtomicLong headerHitsCount = new AtomicLong();
    private final AtomicLong undecodableCount = new AtomicLong();
    private final AtomicLong lightlessCount = new AtomicLong();
    private final AtomicLong totalReadTimeNanos = new AtomicLong();

    public void recordSubmitted() { this.submittedCount.incrementAndGet(); }
    public void recordCompleted(long readTimeNanos) {
        this.totalReadTimeNanos.addAndGet(readTimeNanos);
        this.completedCount.incrementAndGet();
    }
    public void recordNotFound() { this.notFoundCount.incrementAndGet(); }
    public void recordAllAir() { this.allAirCount.incrementAndGet(); }
    public void recordError() { this.errorCount.incrementAndGet(); }
    public void recordSaturation() { this.saturationCount.incrementAndGet(); }
    public void recordSuccess() { this.successCount.incrementAndGet(); }
    /** A read refused by the DiskReadGate with the park list FULL (the overflow bounce
     *  — permit-less refusals normally park and run on release, see the reader's
     *  gateParked comment). NEVER counted into submitted/completed — the store-hit
     *  exclusion precedent: law A5's second clause derives successful from the
     *  completed-minus-outcomes partition, and a bounced read ran no read at all.
     *  Distinct from {@code saturated}, which is recorded at the pool-rejection SUBMIT
     *  site — the two share the silent-drop ChunkReadResult flavor but never a counter. */
    public void recordGated() { this.gatedCount.incrementAndGet(); }
    /** A router pass stopped by gate saturation (Amendment 2 retention — one increment
     *  per stopped PLAYER-pass, so with N players it can grow ~N x 20 Hz while the gate
     *  stays saturated; it is a pass counter, never a held-reads count). Monotonic,
     *  goes still at convergence (SERVER_MONOTONIC member). The retained entries carry
     *  no disposition — they stay in the backlog and are replaced wholesale by the
     *  next declaration (counted superseded there, law A1's queue_full precedent).
     *  Returns the new total (the reader's once-per-session WARN latch reads it). */
    public long recordGateStop() { return this.gateStopsCount.incrementAndGet(); }
    /** A ts&gt;0 read answered by the header freshness rung (P1, region-summary-sync-
     *  plan.md §3): the region header proved the client's copy current, so the read was
     *  skipped. NEVER counted into submitted/completed (the store-hit exclusion
     *  precedent — law A5's partitions see no read here) and never fed to the throttle
     *  EWMA (no IO was measured). A mechanism counter like {@code memo_hits}. */
    public void recordHeaderHit() { this.headerHitsCount.incrementAndGet(); }
    /** A FULL chunk whose cells use only blocks that failed to decode (undecodable-chunks
     *  plan): resolved as the UNSERVABLE not-found flavor. A SUBSET of {@code not_found}
     *  by construction: this bumps both (not_found first, so a concurrent reader never
     *  sees {@code undecodable > not_found}), which keeps the at-rest completion partition
     *  and soak law A5 exact with no new term (the miss is answered {@code not_generated},
     *  or miss-dropped for a ghost, like a generation-off miss). Call INSTEAD of
     *  {@link #recordNotFound()}, never in addition. */
    public void recordUndecodable() {
        this.notFoundCount.incrementAndGet();
        this.undecodableCount.incrementAndGet();
    }
    /** A column served from disk that has blocks but was saved without sky light, in a
     *  dimension that has sky light (lightless-chunks plan, fold item 7). An OBSERVATION
     *  beside the read's ordinary outcome, never an outcome itself: the read is counted
     *  successful as always, nothing is counted under {@code not_found}, and no law or
     *  partition includes this counter. */
    public void recordLightless() { this.lightlessCount.incrementAndGet(); }

    public String formatDiagnostics(int pendingCount) {
        long completed = this.completedCount.get();
        double avgMs = completed > 0 ? (this.totalReadTimeNanos.get() / (double) completed) / LSSConstants.NANOS_PER_MS : 0;
        long saturated = this.saturationCount.get();
        return String.format("submitted=%d, completed=%d, not_found=%d, all_air=%d, errors=%d, saturated=%d, header_hits=%d, avg_read=%.1fms, pending=%d",
                this.submittedCount.get(), completed, this.notFoundCount.get(), this.allAirCount.get(),
                this.errorCount.get(), saturated, this.headerHitsCount.get(), avgMs, pendingCount);
    }

    /**
     * Count of disk reads that successfully produced section data. An explicit counter,
     * not derived from the others: outcome and completion counters increment in two steps,
     * so a derived value read mid-update can transiently DECREASE between snapshots and
     * trip monotonicity checks. At rest, completed == success + notFound + allAir + errors
     * + saturated still holds (unit-tested).
     */
    public long getSuccessfulReadCount() {
        return this.successCount.get();
    }

    public long getGatedCount() { return this.gatedCount.get(); }
    public long getGateStopsCount() { return this.gateStopsCount.get(); }
    public long getHeaderHitsCount() { return this.headerHitsCount.get(); }
    public long getUndecodableCount() { return this.undecodableCount.get(); }
    public long getLightlessCount() { return this.lightlessCount.get(); }
    public long getSubmittedCount() { return this.submittedCount.get(); }
    public long getCompletedCount() { return this.completedCount.get(); }
    public long getNotFoundCount() { return this.notFoundCount.get(); }
    public long getAllAirCount() { return this.allAirCount.get(); }
    public long getErrorCount() { return this.errorCount.get(); }
    public long getSaturationCount() { return this.saturationCount.get(); }
    public long getTotalReadTimeNanos() { return this.totalReadTimeNanos.get(); }

}
