package dev.vox.lss.networking.client;

/**
 * Tracks request/response counters and rolling rates for the LOD request manager.
 */
class RequestMetrics {
    // EWMA smoothing factor for rolling rates
    private static final double EWMA_SMOOTHING_FACTOR = 0.3;

    // Send cycle counters
    private long totalSendCycles = 0;
    private long totalPositionsRequested = 0;

    // Response counters
    private long totalColumnsReceived = 0;
    private long totalUpToDate = 0;
    private long totalNotGenerated = 0;
    private long totalRateLimited = 0;
    private long totalIngestFailures = 0;

    // Rolling rate tracking (EWMA, updated every second)
    private long lastRateUpdateMs = 0;
    private int columnsReceivedInWindow = 0;
    private int positionsRequestedInWindow = 0;
    private double receiveRate = 0;
    private double requestRate = 0;

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
    }

    // --- Getters ---
    long getTotalSendCycles() { return this.totalSendCycles; }
    long getTotalPositionsRequested() { return this.totalPositionsRequested; }
    long getTotalColumnsReceived() { return this.totalColumnsReceived; }
    long getTotalUpToDate() { return this.totalUpToDate; }
    long getTotalNotGenerated() { return this.totalNotGenerated; }
    long getTotalRateLimited() { return this.totalRateLimited; }
    long getTotalIngestFailures() { return this.totalIngestFailures; }
    double getReceiveRate() { return this.receiveRate; }
    double getRequestRate() { return this.requestRate; }
}
