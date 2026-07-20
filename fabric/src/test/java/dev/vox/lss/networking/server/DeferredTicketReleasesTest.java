package dev.vox.lss.networking.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the disconnect-sweep stagger (the 2026-07-16 C2ME watchdog freeze): a departing
 * player's generation-ticket releases must drain a few per tick in FIFO order — never all
 * in one call — and a fresh admission for a still-pending key must be able to CANCEL the
 * release and reuse the held ticket, keeping the add/remove books 1:1. The service glue
 * (removePlayer defers, tick drains, submitGeneration cancels, shutdown flushes) is
 * exercised live by the Tier 2 dimension-change gametests; this pins the queue's own
 * contract, which no MC type can reach.
 */
class DeferredTicketReleasesTest {

    private record Key(int cx, int cz) {}

    @Test
    void drainsFifoAtMostMaxPerCall() {
        var q = new DeferredTicketReleases();
        var released = new ArrayList<Integer>();
        for (int i = 0; i < 10; i++) {
            int n = i;
            q.defer(new Key(n, 0), () -> released.add(n));
        }

        assertEquals(4, q.drain(4), "first tick releases exactly the cap");
        assertEquals(List.of(0, 1, 2, 3), released, "FIFO — oldest sweep entries first");
        assertEquals(6, q.size());

        assertEquals(4, q.drain(4));
        assertEquals(2, q.drain(4), "final partial drain");
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), released);
        assertEquals(0, q.drain(4), "empty queue drains nothing");
    }

    @Test
    void cancelReusesTheHeldTicketAndSkipsTheRelease() {
        var q = new DeferredTicketReleases();
        var released = new ArrayList<Integer>();
        q.defer(new Key(1, 1), () -> released.add(1));
        q.defer(new Key(2, 2), () -> released.add(2));

        assertTrue(q.cancel(new Key(1, 1)),
                "a fresh admission for a pending key cancels the release (ticket reused — "
                        + "the caller must skip its own add so the books stay 1:1)");
        assertFalse(q.cancel(new Key(1, 1)), "a key cancels at most once");
        assertFalse(q.cancel(new Key(9, 9)), "unknown keys have nothing to cancel");

        q.flush();
        assertEquals(List.of(2), released, "the cancelled release never runs");
    }

    @Test
    void flushReleasesEverythingImmediately() {
        var q = new DeferredTicketReleases();
        var released = new ArrayList<Integer>();
        for (int i = 0; i < 7; i++) {
            int n = i;
            q.defer(new Key(n, 0), () -> released.add(n));
        }

        q.flush();

        assertEquals(7, released.size(), "shutdown must never strand a force-load ticket");
        assertEquals(0, q.size());
    }

    @Test
    void reDeferringTheSameKeyReplacesNotDuplicates() {
        var q = new DeferredTicketReleases();
        var released = new ArrayList<Integer>();
        q.defer(new Key(1, 1), () -> released.add(1));
        q.defer(new Key(1, 1), () -> released.add(100));

        q.flush();

        assertEquals(List.of(100), released,
                "one live ticket per key — a re-defer replaces, it must not double-release");
    }
}
