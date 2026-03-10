package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;

/**
 * Per-player token bucket that meters outgoing bandwidth.
 *
 * <p><b>Thread safety:</b> Not thread-safe. All methods must be called from the
 * main server thread (tick loop).
 */
public class PlayerBandwidthTracker {
    private static final int BURST_DIVISOR = 4; // 250ms burst window (~5 ticks at 20 TPS)

    private long availableTokens = 0;
    private long lastRefillNanos = System.nanoTime();
    private long totalSectionsSent = 0;
    private long totalBytesSent = 0;

    public boolean canSend(long allocationBytes) {
        if (allocationBytes <= 0) return false;
        long now = System.nanoTime();
        long elapsedNanos = now - this.lastRefillNanos;
        if (elapsedNanos >= LSSConstants.NANOS_PER_MS) { // skip sub-millisecond refills
            this.lastRefillNanos = now;
            // Cap to 1 second to prevent overflow: elapsedNanos * allocationBytes can exceed
            // Long.MAX_VALUE on first call when lastRefillNanos is 0
            elapsedNanos = Math.min(elapsedNanos, LSSConstants.NANOS_PER_SECOND);
            long refill = elapsedNanos * allocationBytes / LSSConstants.NANOS_PER_SECOND;
            long burstCap = allocationBytes / BURST_DIVISOR;
            this.availableTokens = Math.min(this.availableTokens + refill, burstCap);
        }
        return this.availableTokens > 0;
    }

    public void recordSend(int bytes) {
        this.availableTokens = Math.max(0, this.availableTokens - bytes);
        this.totalSectionsSent++;
        this.totalBytesSent += bytes;
    }

    public long getTotalSectionsSent() { return this.totalSectionsSent; }
    public long getTotalBytesSent() { return this.totalBytesSent; }
}
