package dev.vox.lss.common.processing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vanilla-ping backstop's control pins (adaptive-transfer-rate-plan.md, Mechanism
 * B — the control lens' m7/m8/m9 fixes). Pure unit suite: observe() takes every input.
 */
class PingBackstopTest {

    private static final long KB = 1024;
    private static final long CAP = 3 * 1024 * KB; // 3 MB/s per-player cap
    private static final long ADJ = PingBackstop.ADJUST_INTERVAL_MILLIS;

    /** Observe with a distinct ping each call so the changed-latency gate passes. */
    private static void adjust(PingBackstop b, long now, int ping, long sent) {
        b.observe(now, ping, sent, CAP);
    }

    @Test
    void zeroAndAbsentSamplesNeverSeedTheBaseline() {
        // Review m9: a ~0 anchor would read a distant player's natural ping as
        // permanent excess and cut them during early backfill. The natural ping here
        // sits ABOVE the 750 ms cut threshold (impl review M3: at 600 ms the original
        // pin stayed green even with the seeding guard removed — it verified the cut
        // threshold, not the seeding rule): a zero-anchored baseline WOULD cut this
        // player; a correctly-seeded one must not.
        var b = new PingBackstop();
        adjust(b, 0, 0, 0);       // absent
        adjust(b, ADJ, -1, 0);    // no signal
        // First NONZERO sample seeds; 1000 ms natural ping, excess ~0 → no cut even
        // with attributed send traffic.
        adjust(b, 2 * ADJ, 1_000, 10_000 * KB);
        adjust(b, 3 * ADJ, 1_001, 20_000 * KB);
        assertEquals(1.0, b.factor(), "a natural 1000 ms ping must never be cut");
    }

    @Test
    void recoveryProceedsOnAnUnchangedCalmPing() {
        // Impl review MAJOR-2: 26.2's integer smoothing (3·L+s)/4 is bit-stable for
        // samples in [L, L+3], so a calm link's reported ping stops changing — which
        // is exactly the post-congestion state. Recovery gated on a CHANGED ping
        // would freeze a cut factor below 1.0 for the rest of the session; only the
        // CUT branch carries the changed-ping gate.
        var b = new PingBackstop();
        adjust(b, 0, 50, 0);
        adjust(b, ADJ, 2_000, 2_560 * KB);
        double cut = b.factor();
        assertTrue(cut < 1.0);
        long now = ADJ;
        for (int i = 0; i < 3; i++) {
            b.observe(now += ADJ, 60, 2_560 * KB, CAP); // SAME calm value every time
        }
        assertEquals(cut * PingBackstop.RECOVER_MULTIPLIER, b.factor(), 1e-9,
                "three identical calm readings must still recover");
    }

    @Test
    void attributionGuardBlocksCutsOnIdleSessions() {
        // Never punish an LSS-idle session for someone else's congestion.
        var b = new PingBackstop();
        adjust(b, 0, 50, 0);
        adjust(b, ADJ, 2_000, 0); // excess ~1950 but nothing sent
        assertEquals(1.0, b.factor(), "no LSS traffic in the window = no cut");
    }

    @Test
    void firstCutBindsToTheObservedSendRate() {
        // Review m7: blind halvings from 1.0 need ~6 adjustments × keepalive cadence
        // (~90 s) before landing below a slow link. The first cut anchors to the
        // observed send rate instead.
        var b = new PingBackstop();
        adjust(b, 0, 50, 0);
        // 2.5 MB sent over the 5 s window = 500 KB/s observed; ping balloons.
        adjust(b, ADJ, 2_000, 2_560 * KB);
        // factor = min(0.5*1.0, 0.5 * 512K/3M) ≈ 0.0833 → effective ~256 KB/s.
        double expected = 0.5 * (2_560 * KB * 1000.0 / ADJ) / CAP;
        assertEquals(expected, b.factor(), 1e-9,
                "the first cut lands below the observed send rate at once");
        assertEquals((long) (CAP * expected), b.apply(CAP),
                "apply() multiplies the allocation");
    }

    @Test
    void factorFloorsAtTheMinimumEffectiveRate() {
        var b = new PingBackstop();
        adjust(b, 0, 50, 0);
        // A near-idle-but-attributed window: sends just over the guard at a crawl.
        adjust(b, ADJ, 2_000, 65 * KB);
        double floor = (double) PingBackstop.FLOOR_BYTES_PER_SEC / CAP;
        assertEquals(floor, b.factor(), 1e-9, "the factor floors at 64 KB/s effective");
    }

    @Test
    void recoveryNeedsThreeCalmAdjustmentsAndStepsUpByQuarter() {
        var b = new PingBackstop();
        adjust(b, 0, 50, 0);
        adjust(b, ADJ, 2_000, 2_560 * KB);
        double cut = b.factor();
        assertTrue(cut < 1.0);
        // Two calm adjustments: nothing yet.
        adjust(b, 2 * ADJ, 60, 2_560 * KB);
        adjust(b, 3 * ADJ, 61, 2_560 * KB);
        assertEquals(cut, b.factor(), 1e-9, "recovery waits for the third calm adjustment");
        adjust(b, 4 * ADJ, 62, 2_560 * KB);
        assertEquals(cut * PingBackstop.RECOVER_MULTIPLIER, b.factor(), 1e-9,
                "the third calm adjustment recovers ×1.25");
    }

    @Test
    void recoveryCapsAtFullAllocation() {
        var b = new PingBackstop();
        adjust(b, 0, 50, 0);
        adjust(b, ADJ, 2_000, 2_560 * KB);
        long now = ADJ;
        int ping = 60;
        for (int i = 0; i < 60; i++) {
            adjust(b, now += ADJ, ping++, 2_560 * KB);
        }
        assertEquals(1.0, b.factor(), "recovery never overshoots 1.0");
        assertEquals(CAP, b.apply(CAP), "a recovered factor applies the full allocation");
    }

    @Test
    void adjustmentsAreRateLimitedAndRequireAChangedPing() {
        var b = new PingBackstop();
        adjust(b, 0, 50, 0);
        // Inside the 5 s window: ignored even with a huge excess.
        b.observe(ADJ - 1, 2_000, 2_560 * KB, CAP);
        assertEquals(1.0, b.factor(), "adjustments run at most once per interval");
        // Past the window but the SAME smoothed ping as the last adjustment: ignored
        // (the keepalive updates ~every 15 s; re-adjusting on a stale value would
        // triple the loop gain).
        adjust(b, ADJ, 2_000, 2_560 * KB); // cut happens here (ping changed 50→2000)
        double cut = b.factor();
        b.observe(2 * ADJ, 2_000, 5_120 * KB, CAP);
        assertEquals(cut, b.factor(), 1e-9, "an unchanged ping must not re-adjust");
    }

    @Test
    void baselineDriftsUpwardSoARouteChangeRebaselines() {
        var b = new PingBackstop();
        adjust(b, 0, 50, 0);
        // 10 minutes later the baseline has drifted +600 ms: a permanent shift to
        // 700 ms ping reads as ~50 excess, not 650 — no cut.
        adjust(b, 600_000, 700, 2_560 * KB);
        assertEquals(1.0, b.factor(), "the drifted baseline absorbs a route change");
    }

    @Test
    void resetFactorClearsALiveCut() {
        // The kill switch's hygiene: a disabled backstop must not leave a stale cut.
        var b = new PingBackstop();
        adjust(b, 0, 50, 0);
        adjust(b, ADJ, 2_000, 2_560 * KB);
        assertTrue(b.factor() < 1.0);
        b.resetFactor();
        assertEquals(1.0, b.factor());
        assertEquals(CAP, b.apply(CAP));
    }
}
