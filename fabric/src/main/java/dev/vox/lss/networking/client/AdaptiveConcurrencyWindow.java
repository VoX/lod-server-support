package dev.vox.lss.networking.client;

/**
 * AIMD congestion window for the client's in-flight sync-request ceiling.
 *
 * <p>Replaces the static per-player sync cap: it starts at the server-advertised cap and
 * self-tunes to the server's actual sustainable throughput — a multiplicative decrease when
 * the client sees sustained rate-limiting, and an additive increase while it is saturating the
 * window without pushback (the TCP "cwnd-limited" rule: grow only when more capacity would
 * actually be used). Evaluated once per second by the caller.
 *
 * <p>On a healthy server (no rate-limiting) the window sits pinned at the cap, so steady-state
 * behavior is identical to the old static ceiling. Constants derive from the cap so raising the
 * server cap needs no client retune; all are validated/tuned by the soak harness.
 *
 * <p>Not thread-safe — client-tick-thread only.
 */
final class AdaptiveConcurrencyWindow {

    /** Multiplicative decrease factor on a congestion cycle (gentle: high utilization, low oscillation). */
    private static final float DECREASE_FACTOR = 0.7f;

    private final int cap;
    private final int min;
    private final int increaseStep;

    private int window;
    private int rateLimitedThisCycle;
    private boolean windowLimitedThisCycle;

    AdaptiveConcurrencyWindow(int cap) {
        this.cap = Math.max(1, cap);
        // Floor scales with the cap but can never exceed it (cap may be as low as 1).
        this.min = Math.min(this.cap, Math.max(4, this.cap / 16));
        this.increaseStep = Math.max(8, this.cap / 8);
        this.window = this.cap;
    }

    /** Current in-flight sync ceiling. */
    int current() {
        return this.window;
    }

    /** Signal: a sync request was rate-limited this cycle (congestion). */
    void onRateLimited() {
        this.rateLimitedThisCycle++;
    }

    /** Signal: the drain gate blocked a send because the window was full (saturation). */
    void noteWindowLimited() {
        this.windowLimitedThisCycle = true;
    }

    /** Apply one AIMD step and reset the per-cycle signals. Call once per second. */
    void update() {
        // Dead-band: ignore a stray bounce or two (the rate-limited signal aliases benign
        // cross-player dedup bounces) but react to sustained congestion. Scales with the window
        // so a large cap isn't twitchy; floors at 2 so a small window still reacts.
        int threshold = Math.max(2, this.window / 16);
        if (this.rateLimitedThisCycle >= threshold) {
            this.window = Math.max(this.min, Math.round(this.window * DECREASE_FACTOR));
        } else if (this.windowLimitedThisCycle) {
            this.window = Math.min(this.cap, this.window + this.increaseStep);
        }
        this.rateLimitedThisCycle = 0;
        this.windowLimitedThisCycle = false;
    }

    /** Reset to the full cap (fresh session / dimension change / cache flush). */
    void reset() {
        this.window = this.cap;
        this.rateLimitedThisCycle = 0;
        this.windowLimitedThisCycle = false;
    }

    // Test/diagnostics accessors
    int cap() { return this.cap; }
    int min() { return this.min; }
}
