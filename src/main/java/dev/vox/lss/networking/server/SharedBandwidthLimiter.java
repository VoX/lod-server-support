package dev.vox.lss.networking.server;

/**
 * Divides a global bandwidth budget fairly among active players per tick.
 */
public class SharedBandwidthLimiter {
    private final long maxBytesPerSecond;
    private long totalBytesSentThisSecond;
    private long lastSecondNanos;

    public SharedBandwidthLimiter(long maxBytesPerSecond) {
        this.maxBytesPerSecond = maxBytesPerSecond;
        this.lastSecondNanos = System.nanoTime();
    }

    public long getPerPlayerAllocation(int activePlayerCount) {
        long now = System.nanoTime();
        if (now - this.lastSecondNanos > 1_000_000_000L) {
            this.totalBytesSentThisSecond = 0;
            this.lastSecondNanos = now;
        }
        long remaining = this.maxBytesPerSecond - this.totalBytesSentThisSecond;
        if (remaining <= 0 || activePlayerCount <= 0) return 0;
        return remaining / activePlayerCount;
    }

    public void recordSend(int bytes) {
        this.totalBytesSentThisSecond += bytes;
    }

    public long getTotalBytesSentThisSecond() {
        return this.totalBytesSentThisSecond;
    }
}
