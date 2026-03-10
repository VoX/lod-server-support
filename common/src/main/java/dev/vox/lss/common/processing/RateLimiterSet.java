package dev.vox.lss.common.processing;

/**
 * Holds the two per-player concurrency limiters (one for sync-on-load requests,
 * one for generation requests) and per-type rate limits derived from the configured
 * values.
 */
public class RateLimiterSet {
    private final ConcurrencyLimiter syncOnLoadLimiter;
    private final ConcurrencyLimiter generationLimiter;
    private final int syncRateLimit;
    private final int genRateLimit;

    public RateLimiterSet(int syncRateLimit, int syncConcurrency,
                          int genRateLimit, int genConcurrency) {
        this.syncOnLoadLimiter = new ConcurrencyLimiter(syncConcurrency);
        this.generationLimiter = new ConcurrencyLimiter(genConcurrency);
        this.syncRateLimit = syncRateLimit;
        this.genRateLimit = genRateLimit;
    }

    public ConcurrencyLimiter syncOnLoad() {
        return this.syncOnLoadLimiter;
    }

    public ConcurrencyLimiter generation() {
        return this.generationLimiter;
    }

    /**
     * Returns the appropriate limiter for a request based on the disk-first routing policy.
     * When disk reading is available, both SYNC and GENERATION requests go through the disk
     * reader first, so both use the syncOnLoad limiter. Only when disk reading is unavailable
     * do GENERATION requests use the generation limiter.
     */
    public ConcurrencyLimiter forRequest(RequestType type, boolean diskReadingAvailable) {
        return (type == RequestType.SYNC || diskReadingAvailable)
                ? this.syncOnLoadLimiter
                : this.generationLimiter;
    }

    public int syncRateLimit() {
        return this.syncRateLimit;
    }

    public int genRateLimit() {
        return this.genRateLimit;
    }
}
