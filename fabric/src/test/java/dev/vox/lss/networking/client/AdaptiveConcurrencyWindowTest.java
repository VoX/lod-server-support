package dev.vox.lss.networking.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the AIMD control law of the client's adaptive sync in-flight window: starts at cap,
 * holds in the dead-band, grows only when saturated without pushback, shrinks on sustained
 * congestion, and clamps to [min, cap]. A healthy server (no bounces) must keep the window at
 * cap so steady-state behavior equals the old static ceiling.
 */
class AdaptiveConcurrencyWindowTest {

    private static final int CAP = 200; // the server default advertised via SessionConfig

    private static void cycles(AdaptiveConcurrencyWindow w, int n, int bouncesPerCycle, boolean saturated) {
        for (int i = 0; i < n; i++) {
            for (int b = 0; b < bouncesPerCycle; b++) w.onRateLimited();
            if (saturated) w.noteWindowLimited();
            w.update();
        }
    }

    @Test
    void startsAtCap() {
        var w = new AdaptiveConcurrencyWindow(CAP);
        assertEquals(CAP, w.current());
    }

    @Test
    void healthyServerHoldsAtCap() {
        var w = new AdaptiveConcurrencyWindow(CAP);
        // Saturated every cycle but never bounced → already at cap, additive increase clamps.
        cycles(w, 20, 0, true);
        assertEquals(CAP, w.current(), "no rate-limiting → window never leaves the cap");
    }

    @Test
    void deadBandHoldsOnStrayBounces() {
        var w = new AdaptiveConcurrencyWindow(CAP);
        // threshold at cap 200 = max(2, 12) = 12; a single stray bounce/cycle must not shrink.
        cycles(w, 20, 1, true);
        assertEquals(CAP, w.current(), "sub-threshold stray bounces stay in the dead-band");
    }

    @Test
    void sustainedCongestionShrinksMultiplicativelyToFloor() {
        var w = new AdaptiveConcurrencyWindow(CAP);
        int before = w.current();
        cycles(w, 1, 50, true); // well over threshold
        assertEquals(Math.round(before * 0.7f), w.current(), "one congestion cycle = one ×0.7 cut");
        // Drive to the floor and confirm it clamps, never zero.
        cycles(w, 50, 50, true);
        assertEquals(w.min(), w.current(), "shrinks to min and clamps there");
        assertTrue(w.min() >= 1, "floor is never zero — always a trickle");
    }

    @Test
    void growsAdditivelyOnlyWhenSaturatedWithoutPushback() {
        var w = new AdaptiveConcurrencyWindow(CAP);
        cycles(w, 50, 50, true);              // crush to floor
        int floor = w.current();
        cycles(w, 1, 0, true);               // one clean, saturated cycle
        assertEquals(floor + Math.max(8, CAP / 8), w.current(), "additive increase of one step");
        assertTrue(w.current() > floor);
    }

    @Test
    void doesNotGrowWhenNotWindowLimited() {
        var w = new AdaptiveConcurrencyWindow(CAP);
        cycles(w, 50, 50, true);   // to floor
        int floor = w.current();
        cycles(w, 5, 0, false);    // clean cycles but NOT saturating the window
        assertEquals(floor, w.current(), "idle (not window-limited) → no growth, no drift up to cap");
    }

    @Test
    void recoversToCapOverSeveralCleanSaturatedCycles() {
        var w = new AdaptiveConcurrencyWindow(CAP);
        cycles(w, 50, 50, true);   // to floor
        assertTrue(w.current() < CAP);
        cycles(w, 100, 0, true);   // sustained clean saturation
        assertEquals(CAP, w.current(), "recovers to and clamps at cap");
    }

    @Test
    void signalsResetEachCycle() {
        var w = new AdaptiveConcurrencyWindow(CAP);
        // Bounces below threshold in a cycle must not accumulate across cycles into a cut.
        for (int i = 0; i < 100; i++) {
            w.onRateLimited(); // 1 per cycle, below threshold
            w.update();
        }
        assertEquals(CAP, w.current(), "per-cycle bounce counter resets — sub-threshold never accumulates");
    }

    @Test
    void resetReturnsToCap() {
        var w = new AdaptiveConcurrencyWindow(CAP);
        cycles(w, 50, 50, true);
        assertTrue(w.current() < CAP);
        w.reset();
        assertEquals(CAP, w.current());
    }

    @Test
    void tinyCapClampsMinToCap() {
        var w = new AdaptiveConcurrencyWindow(1); // MIN_CONCURRENCY_LIMIT
        assertEquals(1, w.current());
        assertEquals(1, w.min(), "min never exceeds a tiny cap");
        cycles(w, 10, 50, true);
        assertEquals(1, w.current(), "window stays at 1 — can't go below a cap of 1");
    }
}
