package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSLogger;

import java.util.LinkedHashMap;

/**
 * Staggers MC ticket releases across ticks. A departing player's generation sweep used to
 * remove every outstanding ticket in ONE main-thread call — after fast flight that is up to
 * the per-player cap's worth of NON-overlapping level-propagation cones released
 * simultaneously, on top of the disconnect's own view-distance teardown. Removals are the
 * expensive direction in vanilla's {@code DynamicGraphMinFixedPoint} (a removed source
 * forces invalidate-and-recompute cascades), and C2ME's {@code consolidateSchedules}
 * batches the whole wave into one fixpoint run — measured at 60 s on a live server
 * (watchdog kill, 2026-07-16). Vanilla gameplay never removes dozens of spread-out tickets
 * in one tick; LSS must not either.
 *
 * <p>Entries drain FIFO, {@code max} per call. A pending release can be CANCELLED when a
 * fresh admission wants the same key — the ticket was never removed, so the new entry
 * simply reuses it (its eventual completion still removes exactly once; the add/remove
 * books stay 1:1). Main thread only.
 */
final class DeferredTicketReleases {

    /** Releases executed per drain call (one server tick). 4/tick clears a full 40-ticket
     *  sweep in half a second; a force-load ticket lingering that long is harmless. */
    static final int MAX_RELEASES_PER_TICK = 4;

    private final LinkedHashMap<Object, Runnable> pending = new LinkedHashMap<>();

    /** Queue a release. The same key must not be deferred twice (the caller owns at most
     *  one live ticket per key); a duplicate replaces the previous runnable harmlessly. */
    void defer(Object key, Runnable release) {
        this.pending.put(key, release);
    }

    /** Cancel a pending release because a fresh admission reuses the still-held ticket.
     *  Returns true when there was one to cancel (the caller must then skip its own add). */
    boolean cancel(Object key) {
        return this.pending.remove(key) != null;
    }

    /** Run up to {@code max} queued releases in FIFO order. Returns how many ran. Each
     *  release is containment-wrapped: this runs inside END_SERVER_TICK, and a throwing
     *  {@code removeTicketWithRadius} (the C2ME distance graph is exactly the fragile
     *  substrate this class exists for) must cost one leaked ticket, not the server. */
    int drain(int max) {
        int ran = 0;
        var iter = this.pending.entrySet().iterator();
        while (ran < max && iter.hasNext()) {
            var entry = iter.next();
            iter.remove();
            try {
                entry.getValue().run();
            } catch (Exception e) {
                LSSLogger.error("Deferred ticket release failed for " + entry.getKey()
                        + " — ticket may remain held", e);
            }
            ran++;
        }
        return ran;
    }

    /** Release everything immediately (shutdown — correctness over smoothness). */
    void flush() {
        drain(Integer.MAX_VALUE);
    }

    int size() {
        return this.pending.size();
    }
}
