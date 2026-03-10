package dev.vox.lss.common.processing;

/**
 * Concurrency limiter that caps the number of in-flight requests.
 * {@link #tryAcquire()} succeeds only if the current in-flight count is below
 * the concurrency cap.
 *
 * <p><b>Thread safety:</b> This class is <b>not</b> thread-safe. All methods must be called
 * from a single thread (the processing thread in normal operation).</p>
 */
public class ConcurrencyLimiter {

    private final int maxConcurrency;
    private int currentConcurrency;

    public ConcurrencyLimiter(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public boolean tryAcquire() {
        if (this.currentConcurrency >= this.maxConcurrency) return false;
        this.currentConcurrency++;
        return true;
    }

    public void release() {
        if (this.currentConcurrency > 0) {
            this.currentConcurrency--;
        }
    }

    public int getCurrentConcurrency() {
        return this.currentConcurrency;
    }

    public int getMaxConcurrency() {
        return this.maxConcurrency;
    }
}
