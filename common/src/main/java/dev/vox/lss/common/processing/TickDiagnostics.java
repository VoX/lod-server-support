package dev.vox.lss.common.processing;

import dev.vox.lss.common.DiagnosticsFormatter;
import dev.vox.lss.common.LSSConstants;

/**
 * Tracks per-tick diagnostic counters for RequestProcessingService.
 * Maintains a "last tick" snapshot and a "current tick" accumulator.
 * Call {@link #reset(ProcessingDiagnostics)} at the start of each tick to snapshot
 * the current tick values and reset accumulators.
 */
public class TickDiagnostics {
    // Last-tick snapshot — volatile like the cumulative totals below: format() renders
    // these on the invoking player's Folia region thread via /lsslod stats|diag, the
    // same command call that reads the totals (three-lens review — H3 originally covered
    // only the totals, leaving half the command's fields non-visible).
    private volatile int lastTickSectionsSent;
    private volatile int lastTickDiskQueued;
    private volatile int lastTickDiskDrained;
    private volatile int lastTickGenDrained;
    private volatile int lastTickInMemorySerialized;
    private volatile int lastTickBytesFlushed;
    private volatile int lastTickQueuePeak;
    private volatile int lastTickSkippedDuplicate;
    private volatile int lastTickUpToDate;

    // Current-tick accumulators (written during tick processing)
    private int curTickSectionsSent;
    private int curTickBytesFlushed;
    private int curTickQueuePeak;

    // Cumulative send counters — service-scoped, so they survive the per-player state
    // teardown on kick and dimension change. Single-writer (main/pump thread), volatile
    // because /lsslod stats|diag reads them from the invoking player's REGION thread on
    // Folia (2026-08-05 review H3 — the PaperChunkGenerationService house rule: counters
    // a command renders must be JMM-visible off the writer thread).
    private volatile long totalSectionsSent;
    private volatile long totalCorrectiveColumnsSent;
    private volatile long totalBytesSent;
    private volatile long totalWireBytesSent;

    // Sliding window bandwidth rate (~5s at 20 TPS). The scalars are volatile for the
    // same Folia command-thread reads as the totals above; the ring ARRAYS stay plain, so
    // a cross-thread getWindowBytesPerSecond() is best-effort — a mid-reset read can
    // misreport one command's rate (diag-only, bounded, self-corrects next invocation).
    private static final int WINDOW_TICKS = 100;
    private final int[] byteRing = new int[WINDOW_TICKS];
    private final long[] nanosRing = new long[WINDOW_TICKS];
    private volatile long windowByteSum;
    private volatile int ringPos;
    private volatile int ringCount;

    /**
     * Snapshot current tick values into last-tick fields, pull off-thread counters,
     * and reset current tick accumulators.
     */
    public void reset(ProcessingDiagnostics diag) {
        // Push current tick into sliding window before resetting
        windowByteSum -= byteRing[ringPos];
        byteRing[ringPos] = curTickBytesFlushed;
        nanosRing[ringPos] = System.nanoTime();
        windowByteSum += curTickBytesFlushed;
        ringPos = (ringPos + 1) % WINDOW_TICKS;
        if (ringCount < WINDOW_TICKS) ringCount++;

        this.lastTickSectionsSent = this.curTickSectionsSent;
        this.lastTickDiskQueued = diag.getLastDiskQueued();
        this.lastTickDiskDrained = diag.getLastDiskDrained();
        this.lastTickGenDrained = diag.getLastGenDrained();
        this.lastTickInMemorySerialized = diag.getLastInMemory();
        this.lastTickBytesFlushed = this.curTickBytesFlushed;
        this.lastTickQueuePeak = this.curTickQueuePeak;
        this.lastTickSkippedDuplicate = diag.getLastSkippedDuplicate();
        this.lastTickUpToDate = diag.getLastUpToDate();
        this.curTickSectionsSent = 0;
        this.curTickBytesFlushed = 0;
        this.curTickQueuePeak = 0;
    }

    public long getWindowBytesPerSecond() {
        if (ringCount < 2) return 0;
        int newestIdx = (ringPos - 1 + WINDOW_TICKS) % WINDOW_TICKS;
        int oldestIdx = ringCount < WINDOW_TICKS ? 0 : ringPos;
        long elapsedNanos = nanosRing[newestIdx] - nanosRing[oldestIdx];
        if (elapsedNanos <= 0) return 0;
        // N samples span N-1 intervals: the oldest bucket's bytes flushed during the tick
        // ENDING at its own stamp — before the measured span began — so it is excluded
        // from the numerator (including it inflated the rate ~N/(N-1): +100% at
        // ringCount 2, ~+1% at the full window). Cross-thread reads are best-effort (see
        // the field comments): clamp at 0 so a mid-reset torn read can never render a
        // negative rate in /lsslod stats.
        long spanBytes = Math.max(0L, windowByteSum - byteRing[oldestIdx]);
        return spanBytes * LSSConstants.NANOS_PER_SECOND / elapsedNanos;
    }

    public void recordSectionSent(int estimatedBytes) {
        this.curTickSectionsSent++;
        this.curTickBytesFlushed += estimatedBytes;
        this.totalSectionsSent++;
        this.totalBytesSent += estimatedBytes;
    }

    /** Shipped payload size at send success (frame for codec-1 columns) — the counted
     *  wire volume that matches observed bandwidth, next to the raw-denominated
     *  {@link #getTotalBytesSent} the limiter charges (compressed-columns plan §4). */
    public void recordWireSent(int wireBytes) {
        this.totalWireBytesSent += wireBytes;
    }

    /** Subset of actual successful column sends, never enqueue/drop counts. */
    public void recordCorrectiveColumnSent() { this.totalCorrectiveColumnsSent++; }
    public long getTotalCorrectiveColumnsSent() { return this.totalCorrectiveColumnsSent; }

    public long getTotalSectionsSent() { return this.totalSectionsSent; }
    public long getTotalBytesSent() { return this.totalBytesSent; }

    // ---- Transport yield (lodYieldsToVanillaTransport; yield plan §5, A-7) ----
    // SERVICE-scoped so the log-archive signal survives per-player state teardown
    // (the R2-9 lesson: live-state sums collapse on every rejoin/dimension change).
    // Volatile per the H3 pin the totals above carry: /lsslod diag renders on the
    // invoking player's REGION thread on Folia while the pump writes.
    private volatile long yieldTicksTotal = 0;
    private volatile long yieldBytesWithheldTotal = 0;

    /** One withheld flush tick; {@code queuedBytes} is that tick's held queue bytes
     *  (a byte-tick pressure integral, never a delivered-bytes count). */
    public void recordYieldedTick(long queuedBytes) {
        this.yieldTicksTotal++;
        this.yieldBytesWithheldTotal += queuedBytes;
    }

    private volatile long pacedTicksTotal = 0;

    /** One budget-stopped partial flush tick (send-pacing-plan.md v3) — the
     *  service-scoped twin of the per-player paced= counter, so soak recordings and
     *  armed-then-departed sessions keep their evidence (the yield counters' rule). */
    public void recordPacedTick() {
        this.pacedTicksTotal++;
    }

    public long getPacedTicksTotal() { return this.pacedTicksTotal; }

    public long getYieldTicksTotal() { return this.yieldTicksTotal; }
    public long getYieldBytesWithheldTotal() { return this.yieldBytesWithheldTotal; }
    public long getTotalWireBytesSent() { return this.totalWireBytesSent; }

    public void updateQueuePeak(int queueSize) {
        this.curTickQueuePeak = Math.max(this.curTickQueuePeak, queueSize);
    }

    public String format(int maxSendQueueSize) {
        return String.format("sent=%d, disk=%d/%d, utd=%d, gen=%d, in_mem=%d, skipped=%d, bytes=%s, qpeak=%d/%d",
                lastTickSectionsSent, lastTickDiskDrained, lastTickDiskQueued,
                lastTickUpToDate, lastTickGenDrained,
                lastTickInMemorySerialized, lastTickSkippedDuplicate,
                DiagnosticsFormatter.formatBytes(lastTickBytesFlushed),
                lastTickQueuePeak, maxSendQueueSize);
    }

    public String formatSummary(long bwRate, long maxBytesPerSecondGlobal) {
        return String.format("sent=%d/tick, disk=%d/%d, utd=%d, bw=%s/%s",
                lastTickSectionsSent, lastTickDiskDrained, lastTickDiskQueued,
                lastTickUpToDate,
                DiagnosticsFormatter.formatBytes(bwRate), DiagnosticsFormatter.formatBytes(maxBytesPerSecondGlobal));
    }

}
