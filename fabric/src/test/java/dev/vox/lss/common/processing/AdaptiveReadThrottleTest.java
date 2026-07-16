package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the AIMD control law of {@link AdaptiveReadThrottle} with synthetic latencies (no IO):
 * below-target samples grow the effective limit additively to the ceiling, sustained
 * above-target samples shrink it multiplicatively to the floor, the EWMA absorbs single
 * spikes so they do not trigger a cut, the limit stays clamped at both ends, and the limit
 * recovers once congestion clears. This is the whole point of the throttle — a broken control
 * law would either never back off (defeating the feature) or never recover (permanently
 * starving LSS reads), and neither shows up in the disk-reader envelope tests.
 */
class AdaptiveReadThrottleTest {

    private static final long MS = LSSConstants.NANOS_PER_MS;
    private static final long TARGET = 20 * MS;

    /** A throttle with an explicit starting limit and the given ceiling; default AIMD coefficients. */
    private static AdaptiveReadThrottle throttle(int min, int max, double initial) {
        return new AdaptiveReadThrottle(min, max, initial, TARGET,
                AdaptiveReadThrottle.DEFAULT_EWMA_ALPHA,
                AdaptiveReadThrottle.DEFAULT_DECREASE_FACTOR,
                AdaptiveReadThrottle.DEFAULT_INCREASE_STEP);
    }

    private static void feed(AdaptiveReadThrottle t, long latencyNanos, int times) {
        for (int i = 0; i < times; i++) t.recordLatency(latencyNanos);
    }

    // ---- additive increase toward the ceiling ----

    @Test
    void belowTargetLatencyGrowsLimitAdditivelyToTheCeiling() {
        var t = throttle(1, 100, 1.0);
        // Each below-target sample adds DEFAULT_INCREASE_STEP (1). The first sample also seeds the
        // EWMA (at 1ms, well under the 20ms target) and still takes its step.
        feed(t, MS, 4);
        assertEquals(5, t.currentLimit(), "1 + 4 additive steps");

        // Keep feeding well past the ceiling — the limit saturates at max and never overshoots.
        feed(t, MS, 1000);
        assertEquals(100, t.currentLimit(), "additive growth clamps at maxLimit");
    }

    @Test
    void increaseIsAdditiveNotMultiplicative() {
        // Distinguishes AI from geometric growth: from 10, five good samples give exactly 15.
        var t = throttle(1, 1000, 10.0);
        feed(t, 2 * MS, 5);
        assertEquals(15, t.currentLimit());
    }

    // ---- multiplicative decrease toward the floor ----

    @Test
    void sustainedHighLatencyShrinksLimitMultiplicativelyToTheFloor() {
        var t = throttle(1, 100, 100.0);
        // First above-target sample: EWMA seeds at 100ms (> 20ms) so limit *= 0.7 -> 70.
        t.recordLatency(100 * MS);
        assertEquals(70, t.currentLimit(), "one 30% multiplicative cut");

        t.recordLatency(100 * MS);
        assertEquals(49, t.currentLimit(), "0.7 * 70");

        // Sustained congestion collapses the limit to the floor within a handful of samples.
        feed(t, 100 * MS, 50);
        assertEquals(1, t.currentLimit(), "multiplicative decrease clamps at minLimit");
    }

    @Test
    void decreaseIsMultiplicativeNotAdditive() {
        // From 100, a single over-target sample cuts by the factor (to 70), not by a fixed step.
        var t = throttle(1, 100, 100.0);
        t.recordLatency(200 * MS);
        assertEquals(70, t.currentLimit());
    }

    // ---- EWMA smoothing ----

    @Test
    void ewmaAbsorbsASingleSpikeWithoutCuttingTheLimit() {
        var t = throttle(1, 100, 100.0);
        // Settle the EWMA at 5ms (well below target) and the limit at the ceiling.
        feed(t, 5 * MS, 200);
        assertEquals(100, t.currentLimit());
        assertEquals(5.0 * MS, t.ewmaLatencyNanos(), 0.01 * MS, "EWMA converged to the steady 5ms input");

        // One 40ms spike is above the 20ms target, so a RAW signal would trigger a cut. Smoothed:
        // 0.8*5 + 0.2*40 = 12ms, still under target -> no decrease, the limit holds at the ceiling.
        t.recordLatency(40 * MS);
        assertEquals(100, t.currentLimit(),
                "a lone spike the EWMA keeps under target must not cut the limit");
        assertTrue(t.ewmaLatencyNanos() < TARGET, "smoothed latency stayed under target after one spike");
    }

    @Test
    void repeatedSpikesEventuallyCrossTargetAndCut() {
        // The counterpart to the smoothing test: if the "spikes" are actually sustained, the EWMA
        // ramps past the target and the controller does back off — smoothing delays, never blocks.
        var t = throttle(1, 100, 100.0);
        feed(t, 5 * MS, 200);
        assertEquals(100, t.currentLimit());

        feed(t, 200 * MS, 50);
        assertEquals(1, t.currentLimit(), "sustained high latency still reaches the floor");
    }

    @Test
    void ewmaSeedsOnFirstSampleInsteadOfBlendingFromZero() {
        var t = throttle(1, 100, 50.0);
        assertEquals(0.0, t.ewmaLatencyNanos(), "no samples yet");
        t.recordLatency(8 * MS);
        // Seeded directly at the sample, not 0.2*8ms (which would be a spurious near-zero estimate).
        assertEquals(8.0 * MS, t.ewmaLatencyNanos(), 0.0001);
    }

    // ---- clamping ----

    @Test
    void initialLimitIsClampedIntoRange() {
        assertEquals(100, throttle(1, 100, 999.0).currentLimit(), "above max clamps to max");
        assertEquals(5, throttle(5, 100, 2.0).currentLimit(), "below min clamps to min");
    }

    @Test
    void limitNeverLeavesTheConfiguredBounds() {
        var t = throttle(3, 20, 10.0);
        // Hammer both directions well past the bounds; the limit must stay inside [3, 20].
        feed(t, MS, 500);
        assertEquals(20, t.currentLimit());
        feed(t, 500 * MS, 500);
        assertEquals(3, t.currentLimit());
    }

    // ---- recovery ----

    @Test
    void limitRecoversAfterCongestionClears() {
        var t = throttle(1, 100, 100.0);
        feed(t, 200 * MS, 50);
        assertEquals(1, t.currentLimit(), "driven to the floor by congestion");

        // Congestion clears: low-latency reads pull the EWMA back under target and the limit
        // climbs additively again (proving the floor keeps a live signal that enables recovery).
        // The EWMA must first decay from ~200ms back under 20ms before growth resumes.
        feed(t, MS, 500);
        assertEquals(100, t.currentLimit(), "recovered back to the ceiling once latency dropped");
    }

    // ---- canSubmit gate ----

    @Test
    void canSubmitAdmitsBelowTheLimitAndBlocksAtOrAboveIt() {
        var t = throttle(1, 100, 5.0);
        assertTrue(t.canSubmit(0));
        assertTrue(t.canSubmit(4), "4 in flight < limit 5");
        assertFalse(t.canSubmit(5), "limit is an inclusive ceiling on concurrent reads");
        assertFalse(t.canSubmit(9));
    }

    @Test
    void currentLimitFloorsToInt() {
        // 0.7 * 10 = 7.0 exactly here, but verify the floor semantics with a fractional limit.
        var t = throttle(1, 100, 10.0);
        t.recordLatency(50 * MS); // 10 -> 7.0
        t.recordLatency(50 * MS); // 7.0 -> 4.9  => floor 4
        assertEquals(4, t.currentLimit(), "fractional limit floors, never rounds up");
    }

    // ---- factory + argument validation ----

    @Test
    void forPoolDerivesCeilingFromPoolDepthAndTargetFromMs() {
        // Ceiling = threads * (1 + queuePerThread) = the pool's full running+queued depth.
        var t = AdaptiveReadThrottle.forPool(5, 32, 20);
        assertEquals(5 * (1 + 32), t.maxLimit());
        assertEquals(20 * MS, t.targetLatencyNanos());
        assertEquals(t.maxLimit(), t.currentLimit(), "starts optimistic at the ceiling");
        assertEquals(AdaptiveReadThrottle.DEFAULT_MIN_LIMIT, t.minLimit());
    }

    @Test
    void constructorRejectsIllegalCoefficients() {
        assertThrows(IllegalArgumentException.class, () -> throttle(0, 100, 10.0), "minLimit < 1");
        assertThrows(IllegalArgumentException.class, () -> throttle(10, 5, 10.0), "max < min");
        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveReadThrottle(1, 10, 5, TARGET, 0.0, 0.7, 1.0), "alpha 0");
        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveReadThrottle(1, 10, 5, TARGET, 1.5, 0.7, 1.0), "alpha > 1");
        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveReadThrottle(1, 10, 5, TARGET, 0.2, 1.0, 1.0), "decreaseFactor >= 1");
        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveReadThrottle(1, 10, 5, TARGET, 0.2, 0.7, 0.0), "increaseStep 0");
    }
}
