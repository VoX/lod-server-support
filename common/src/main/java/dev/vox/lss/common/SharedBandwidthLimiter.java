package dev.vox.lss.common;

/**
 * Token bucket bandwidth limiter that divides a global budget fairly among active players.
 * Tokens refill proportionally to elapsed real time, preventing bursty traffic patterns.
 */
public class SharedBandwidthLimiter {
    private final long maxBytesPerSecond;
    private long availableTokens;
    private long lastRefillNanos;

    public SharedBandwidthLimiter(long maxBytesPerSecond) {
        this.maxBytesPerSecond = maxBytesPerSecond;
        this.availableTokens = maxBytesPerSecond;
        this.lastRefillNanos = System.nanoTime();
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - this.lastRefillNanos;
        if (elapsedNanos < 1_000_000L) return; // skip sub-millisecond refills
        this.lastRefillNanos = now;
        elapsedNanos = Math.min(elapsedNanos, 1_000_000_000L); // cap to 1s to prevent overflow
        long refill = elapsedNanos * this.maxBytesPerSecond / 1_000_000_000L;
        this.availableTokens = Math.min(this.availableTokens + refill, this.maxBytesPerSecond);
    }

    public long getPerPlayerAllocation(int activePlayerCount) {
        this.refill();
        if (this.availableTokens <= 0 || activePlayerCount <= 0) return 0;
        return this.availableTokens / activePlayerCount;
    }

    public void recordSend(int bytes) {
        this.availableTokens -= bytes;
    }

    public long getTotalBytesSentThisSecond() {
        this.refill();
        return Math.max(0, this.maxBytesPerSecond - this.availableTokens);
    }
}
