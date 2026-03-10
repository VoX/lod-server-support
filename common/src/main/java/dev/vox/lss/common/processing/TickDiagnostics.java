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
    // Last-tick snapshot (read by diagnostics/logging)
    private int lastTickSectionsSent;
    private int lastTickDiskQueued;
    private int lastTickDiskDrained;
    private int lastTickGenDrained;
    private int lastTickInMemorySerialized;
    private int lastTickBytesFlushed;
    private int lastTickQueuePeak;
    private int lastTickSkippedDuplicate;
    private int lastTickUpToDate;

    // Current-tick accumulators (written during tick processing)
    private int curTickSectionsSent;
    private int curTickBytesFlushed;
    private int curTickQueuePeak;

    // Sliding window bandwidth rate (~5s at 20 TPS)
    private static final int WINDOW_TICKS = 100;
    private final int[] byteRing = new int[WINDOW_TICKS];
    private final long[] nanosRing = new long[WINDOW_TICKS];
    private long windowByteSum;
    private int ringPos;
    private int ringCount;

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
        return windowByteSum * LSSConstants.NANOS_PER_SECOND / elapsedNanos;
    }

    public void recordSectionSent(int estimatedBytes) {
        this.curTickSectionsSent++;
        this.curTickBytesFlushed += estimatedBytes;
    }

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
