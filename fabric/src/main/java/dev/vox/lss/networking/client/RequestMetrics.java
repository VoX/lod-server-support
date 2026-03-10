package dev.vox.lss.networking.client;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/**
 * Tracks request/response counters and rolling rates for the LOD request manager.
 */
class RequestMetrics {
    // EWMA smoothing factor for rolling rates
    private static final double EWMA_SMOOTHING_FACTOR = 0.3;

    // Column timestamp counters
    private int receivedCount = 0;
    private int emptyCount = 0;

    // Send cycle counters
    private long totalSendCycles = 0;
    private long totalPositionsRequested = 0;

    // Response counters
    private long totalColumnsReceived = 0;
    private long totalUpToDate = 0;
    private long totalNotGenerated = 0;
    private long totalRateLimited = 0;

    // Rolling rate tracking (EWMA, updated every second)
    private long lastRateUpdateMs = 0;
    private int columnsReceivedInWindow = 0;
    private int positionsRequestedInWindow = 0;
    private double receiveRate = 0;
    private double requestRate = 0;

    /**
     * Adjusts received/empty counters when a timestamp is replaced.
     * @param oldTimestamp the previous value (-1 if absent)
     * @param newTimestamp the new value being stored
     */
    void adjustCounters(long oldTimestamp, long newTimestamp) {
        if (oldTimestamp >= 0) {
            if (oldTimestamp > 0) this.receivedCount--;
            else this.emptyCount--;
        }
        if (newTimestamp > 0) this.receivedCount++;
        else if (newTimestamp == 0) this.emptyCount++;
    }

    void recordSendCycle(int positionCount) {
        this.totalSendCycles++;
        this.totalPositionsRequested += positionCount;
        this.positionsRequestedInWindow += positionCount;
    }

    void recordColumnReceived() {
        this.totalColumnsReceived++;
        this.columnsReceivedInWindow++;
    }

    void recordUpToDate() {
        this.totalUpToDate++;
    }

    void recordNotGenerated() {
        this.totalNotGenerated++;
    }

    void recordRateLimited() {
        this.totalRateLimited++;
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
        this.receivedCount = 0;
        this.emptyCount = 0;
        this.lastRateUpdateMs = 0;
        this.columnsReceivedInWindow = 0;
        this.positionsRequestedInWindow = 0;
        this.receiveRate = 0;
        this.requestRate = 0;
    }

    /**
     * Recount received/empty from the full timestamp map after a bulk load.
     */
    void bulkRecount(Long2LongOpenHashMap timestamps) {
        this.receivedCount = 0;
        this.emptyCount = 0;
        for (long ts : timestamps.values()) {
            if (ts > 0) this.receivedCount++;
            else if (ts == 0) this.emptyCount++;
        }
    }

    /**
     * Called during pruning - directly decrements based on removed timestamp value.
     */
    void onTimestampRemoved(long timestamp) {
        if (timestamp > 0) this.receivedCount--;
        else if (timestamp == 0) this.emptyCount--;
    }

    // --- Getters ---
    int getReceivedCount() { return this.receivedCount; }
    int getEmptyCount() { return this.emptyCount; }
    long getTotalSendCycles() { return this.totalSendCycles; }
    long getTotalPositionsRequested() { return this.totalPositionsRequested; }
    long getTotalColumnsReceived() { return this.totalColumnsReceived; }
    long getTotalUpToDate() { return this.totalUpToDate; }
    long getTotalNotGenerated() { return this.totalNotGenerated; }
    long getTotalRateLimited() { return this.totalRateLimited; }
    double getReceiveRate() { return this.receiveRate; }
    double getRequestRate() { return this.requestRate; }
}
