package dev.vox.lss.common;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limiter for a recurring log line: counts every event, but releases the accumulated
 * count for logging at most once per interval. The first event is released immediately so
 * the condition surfaces as soon as it starts; later events stay silent until the interval
 * elapses, then the next event carries the total suppressed since the last release.
 *
 * <p>Thread-safe. Callers supply the clock ({@code nowMs}) so the windowing is testable —
 * use a monotonic source (e.g. {@code System.nanoTime() / 1_000_000}), not the wall clock:
 * a backwards wall-clock step would silence releases until real time catches back up.
 */
public final class LogThrottle {

    private final long intervalMs;
    private final AtomicLong count = new AtomicLong();
    private final AtomicLong nextReleaseMs = new AtomicLong(Long.MIN_VALUE);

    public LogThrottle(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    /**
     * Records one event. Returns the number of events (this one included) since the last
     * release if the caller should log now, or 0 to stay silent.
     */
    public long recordAndTryAcquire(long nowMs) {
        this.count.incrementAndGet();
        long due = this.nextReleaseMs.get();
        if (nowMs >= due && this.nextReleaseMs.compareAndSet(due, nowMs + this.intervalMs)) {
            return this.count.getAndSet(0);
        }
        return 0;
    }
}
