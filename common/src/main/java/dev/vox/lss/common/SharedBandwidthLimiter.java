package dev.vox.lss.common;

/**
 * Token bucket bandwidth limiter that divides a global budget fairly among active players.
 * Tokens refill proportionally to elapsed real time, preventing bursty traffic patterns.
 *
 * <p><b>Thread safety:</b> This class is <b>not</b> thread-safe. All methods must be called
 * from the server tick thread only. Calling from multiple threads will silently corrupt
 * internal counters.</p>
 */
public class SharedBandwidthLimiter {
    private final long maxBytesPerSecond;
    private long availableTokens;
    private long lastRefillNanos;

    private long totalBytesSent;

    public SharedBandwidthLimiter(long maxBytesPerSecond) {
        this.maxBytesPerSecond = maxBytesPerSecond;
        this.availableTokens = maxBytesPerSecond;
        this.lastRefillNanos = System.nanoTime();
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - this.lastRefillNanos;
        if (elapsedNanos < LSSConstants.NANOS_PER_MS) return; // skip sub-millisecond refills
        this.lastRefillNanos = now;
        elapsedNanos = Math.min(elapsedNanos, LSSConstants.NANOS_PER_SECOND); // cap to 1s to prevent overflow
        long refill = elapsedNanos * this.maxBytesPerSecond / LSSConstants.NANOS_PER_SECOND;
        this.availableTokens = Math.min(this.availableTokens + refill, this.maxBytesPerSecond);
    }

    public long getPerPlayerAllocation(int activePlayerCount) {
        this.refill();
        if (this.availableTokens <= 0 || activePlayerCount <= 0) return 0;
        return this.availableTokens / activePlayerCount;
    }

    public void recordSend(int bytes) {
        this.availableTokens = Math.max(0, this.availableTokens - bytes);
        this.totalBytesSent += bytes;
    }

    public long getTotalBytesSent() {
        return this.totalBytesSent;
    }
}
