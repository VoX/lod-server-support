package dev.vox.lss.networking.client;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.util.Arrays;

/**
 * Tracks request/response counters and rolling rates for the LOD request manager.
 */
class RequestMetrics {
    // EWMA smoothing factor for rolling rates
    private static final double EWMA_SMOOTHING_FACTOR = 0.3;
    // RTT distribution: ring buffer of the most recent samples; stamp map bounded so a
    // server that never answers cannot grow it without limit
    private static final int RTT_SAMPLE_CAPACITY = 128;
    private static final int MAX_PENDING_RTT_STAMPS = 4096;

    // Send cycle counters
    private long totalSendCycles = 0;
    private long totalPositionsRequested = 0;

    // Response counters
    private long totalColumnsReceived = 0;
    private long totalUpToDate = 0;
    private long totalNotGenerated = 0;
    private long totalIngestFailures = 0;

    // Rolling rate tracking (EWMA, updated every second)
    private long lastRateUpdateMs = 0;
    private int columnsReceivedInWindow = 0;
    private int positionsRequestedInWindow = 0;
    private double receiveRate = 0;
    private double requestRate = 0;

    // RTT send stamps keyed by packed position (absent = fastutil default 0; epoch ms is
    // never 0) and a ring buffer of the most recent round-trip samples
    private final Long2LongOpenHashMap rttSendStamps = new Long2LongOpenHashMap();
    private final long[] rttSamplesMs = new long[RTT_SAMPLE_CAPACITY];
    private int rttSampleCount = 0;
    private int rttSampleIndex = 0;

    void recordSendCycle(int positionCount) {
        this.totalSendCycles++;
        this.totalPositionsRequested += positionCount;
        this.positionsRequestedInWindow += positionCount;
    }

    void recordColumnReceived() {
        this.totalColumnsReceived++;
        this.columnsReceivedInWindow++;
    }

    /**
     * Stamp one position's request-send time for RTT measurement. A re-send of the same
     * position overwrites the stamp, so the eventual sample measures the latest attempt.
     */
    void recordRequestSent(long packedPosition, long nowMs) {
        if (this.rttSendStamps.size() >= MAX_PENDING_RTT_STAMPS
                && !this.rttSendStamps.containsKey(packedPosition)) {
            return;
        }
        this.rttSendStamps.put(packedPosition, nowMs);
    }

    /**
     * Position-keyed receive: records the column counters AND, when a send stamp exists
     * for the position, one RTT sample (receive minus send).
     */
    void recordColumnReceived(long packedPosition, long nowMs) {
        long sentMs = this.rttSendStamps.remove(packedPosition);
        if (sentMs != 0 && nowMs >= sentMs) {
            this.rttSamplesMs[this.rttSampleIndex] = nowMs - sentMs;
            this.rttSampleIndex = (this.rttSampleIndex + 1) % RTT_SAMPLE_CAPACITY;
            if (this.rttSampleCount < RTT_SAMPLE_CAPACITY) this.rttSampleCount++;
        }
        recordColumnReceived();
    }

    /**
     * Drop a position's pending RTT send-stamp without recording a sample. Terminal answers
     * that are not column data (up-to-date, not-generated) mean the awaited column will never
     * arrive, so the stamp is dead; without this it lingers until the MAX_PENDING_RTT_STAMPS
     * cap fills and silences ALL future RTT sampling for the session.
     */
    void discardRttStamp(long packedPosition) {
        this.rttSendStamps.remove(packedPosition);
    }

    /** Movement prune (mirrors InFlightTracker.pruneOutOfRange): a pruned request never gets
     *  a tracked answer, so its stamp would orphan toward the sampling cap. */
    void pruneRttStampsOutOfRange(int playerCx, int playerCz, int maxDistance) {
        var iter = this.rttSendStamps.long2LongEntrySet().fastIterator();
        while (iter.hasNext()) {
            long packed = iter.next().getLongKey();
            if (dev.vox.lss.common.PositionUtil.chebyshevDistance(
                    dev.vox.lss.common.PositionUtil.unpackX(packed),
                    dev.vox.lss.common.PositionUtil.unpackZ(packed),
                    playerCx, playerCz) > maxDistance) {
                iter.remove();
            }
        }
    }

    void recordUpToDate() {
        this.totalUpToDate++;
    }

    void recordNotGenerated() {
        this.totalNotGenerated++;
    }

    void recordIngestFailure() {
        this.totalIngestFailures++;
    }

    /**
     * Updates EWMA rates. Called once per tick.
     */
    void updateRollingRates() {
        long now = System.currentTimeMillis();
        long elapsed = now - this.lastRateUpdateMs;
        if (elapsed >= 1000) {
            double seconds = elapsed / 1000.0;
            double instantReceiveRate = this.columnsReceivedInWindow / seconds;
            double instantRequestRate = this.positionsRequestedInWindow / seconds;
            this.receiveRate = this.receiveRate * (1 - EWMA_SMOOTHING_FACTOR) + instantReceiveRate * EWMA_SMOOTHING_FACTOR;
            this.requestRate = this.requestRate * (1 - EWMA_SMOOTHING_FACTOR) + instantRequestRate * EWMA_SMOOTHING_FACTOR;
            this.columnsReceivedInWindow = 0;
            this.positionsRequestedInWindow = 0;
            this.lastRateUpdateMs = now;
        }
    }

    void reset() {
        this.lastRateUpdateMs = 0;
        this.columnsReceivedInWindow = 0;
        this.positionsRequestedInWindow = 0;
        this.receiveRate = 0;
        this.requestRate = 0;
        this.rttSendStamps.clear();
        this.rttSampleCount = 0;
        this.rttSampleIndex = 0;
    }

    /** Percentile (nearest-rank) over the retained RTT samples; -1 when no samples exist. */
    private double rttPercentileMs(double percentile) {
        int count = this.rttSampleCount;
        if (count == 0) return -1.0;
        long[] sorted = Arrays.copyOf(this.rttSamplesMs, count);
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(percentile * count) - 1;
        return sorted[Math.max(0, Math.min(count - 1, rank))];
    }

    // --- Getters ---
    long getTotalSendCycles() { return this.totalSendCycles; }
    long getTotalPositionsRequested() { return this.totalPositionsRequested; }
    long getTotalColumnsReceived() { return this.totalColumnsReceived; }
    long getTotalUpToDate() { return this.totalUpToDate; }
    long getTotalNotGenerated() { return this.totalNotGenerated; }
    long getTotalIngestFailures() { return this.totalIngestFailures; }
    double getReceiveRate() { return this.receiveRate; }
    double getRequestRate() { return this.requestRate; }
    double getRttP50Ms() { return rttPercentileMs(0.50); }
    double getRttP95Ms() { return rttPercentileMs(0.95); }
}
