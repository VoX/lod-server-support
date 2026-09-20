package dev.vox.lss.common.farplayers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The E2 motion math (FARP §3.3): declared-cadence lerp window with the measured
 * correction clamped at 2x declared (the delta-suppression armor), velocity
 * extrapolation capped at 1.5 windows, shortest-path angle lerp. Pure math — this
 * suite IS the "Tier 1 tracker math" gate; rendering itself is live-gated.
 */
class FarPlayerMotionTest {

    private static FarPlayerWire.UpdateEntry entry(double x, double y, double z,
                                                   double velXPerSec) {
        return new FarPlayerWire.UpdateEntry(0,
                FarPlayerWire.quantizePos(x), FarPlayerWire.quantizePos(y),
                FarPlayerWire.quantizePos(z),
                (byte) 0, (byte) 0, (byte) 0, (byte) 0,
                FarPlayerWire.velocityToShort(velXPerSec),
                FarPlayerWire.velocityToShort(0),
                FarPlayerWire.velocityToShort(0),
                null, null, null, null);
    }

    private static FarPlayerWire.UpdateEntry angled(float yaw, float headYaw, float pitch) {
        return new FarPlayerWire.UpdateEntry(0, 0, 0, 0,
                FarPlayerWire.angleToByte(yaw), FarPlayerWire.angleToByte(headYaw),
                FarPlayerWire.angleToByte(pitch), (byte) 0,
                (short) 0, (short) 0, (short) 0, null, null, null, null);
    }

    @Test
    void firstSnapshotHoldsStillAndLaterFramesLerpFromTheSampledState() {
        var m = new FarPlayerMotion(entry(100, 64, 0, 0), 10, 1_000);
        var s0 = m.sample(1_100);
        assertEquals(100, s0.x(), 1e-9, "a single snapshot holds (from == to)");

        // Frame 2 at the declared cadence (10 ticks = 500 ms): lerp from current.
        m.apply(entry(110, 64, 0, 0), 10, 1_500);
        // Declared window = 500 * 1.2 = 600 ms; midpoint at +300.
        assertEquals(600, m.windowMillis());
        var mid = m.sample(1_800);
        assertEquals(105, mid.x(), 1e-6, "midpoint of the lerp window");
        var end = m.sample(2_100);
        assertEquals(110, end.x(), 1e-6, "window end reaches the target");
    }

    @Test
    void measuredGapCorrectsTheWindowUpButNeverPastTwiceDeclared() {
        var m = new FarPlayerMotion(entry(0, 64, 0, 0), 10, 1_000);
        // A half-rate tier target: frames measure ~1000 ms against declared 500.
        m.apply(entry(10, 64, 0, 0), 10, 2_000);
        assertEquals(1_000, m.windowMillis(),
                "the measured tier gap corrects the window (within the 2x cap)");

        // A delta-suppressed stationary player whose first movement lands after 30 s:
        // the window must clamp at 2x declared, never glide over the measured gap.
        m.apply(entry(20, 64, 0, 0), 10, 32_000);
        assertEquals(1_000, m.windowMillis(),
                "the suppressed-stationary gap clamps at MEASURED_WINDOW_CAP x declared");
    }

    @Test
    void midLerpFrameStartsFromTheSampledPositionNeverSnaps() {
        var m = new FarPlayerMotion(entry(0, 64, 0, 0), 10, 1_000);
        m.apply(entry(10, 64, 0, 0), 10, 1_500); // window 600
        // A third frame lands halfway through the lerp (sample there = 5.0).
        m.apply(entry(30, 64, 0, 0), 10, 1_800);
        var atStart = m.sample(1_800);
        assertEquals(5, atStart.x(), 1e-6, "the new lerp origin is the SAMPLED state");
    }

    @Test
    void pastTheWindowVelocityExtrapolatesAndCapsAtOneAndAHalfWindows() {
        var m = new FarPlayerMotion(entry(0, 64, 0, 0), 10, 1_000);
        // Elytra: 40 blocks/s hint, window 600 ms after this frame.
        m.apply(entry(20, 64, 0, 0), 10, 1_500);
        // 300 ms past the window: 40 b/s * 0.3 s = 12 blocks beyond the target.
        var extra = m.sample(1_500 + 600 + 300);
        assertEquals(20, extra.x(), 1e-6, "zero-velocity target holds at the target");

        var m2 = new FarPlayerMotion(entry(0, 64, 0, 0), 10, 1_000);
        m2.apply(entry(20, 64, 0, 40), 10, 1_500);
        var e2 = m2.sample(1_500 + 600 + 300);
        assertEquals(20 + 12, e2.x(), 1e-4, "dead-reckoning from the velocity hint");
        // The cap: extrapolation never exceeds 1.5 windows (= 900 ms at 40 b/s = 36).
        var far = m2.sample(1_500 + 600 + 60_000);
        assertEquals(20 + 36, far.x(), 1e-4, "extrapolation caps at 1.5 windows");
    }

    @Test
    void angleLerpTakesTheShortestPathAcrossTheWrap() {
        assertEquals(0f, ((FarPlayerMotion.rotLerp(0.5f, 350f, 10f) % 360f) + 360f) % 360f,
                1e-4, "350 -> 10 crosses the wrap, midpoint 0 (normalized)");
        assertEquals(175f, FarPlayerMotion.rotLerp(0.5f, 170f, 180f), 1e-4);
        var m = new FarPlayerMotion(angled(350, 350, 0), 10, 1_000);
        m.apply(angled(10, 10, 0), 10, 1_500);
        var mid = m.sample(1_800);
        // Quantized through the wire byte: ~1.4-degree resolution. Compare as a
        // CIRCULAR distance to 0 (359.3 is 0.7 degrees away, not 359).
        float norm = ((mid.yaw() % 360) + 360) % 360;
        float wrapDist = Math.min(norm, 360 - norm);
        assertEquals(0f, wrapDist, 2.0,
                "the wire-quantized wrap lerp lands near 0, never sweeps 180");
    }

    @Test
    void isMovingReflectsLerpProgressAndExtrapolationState() {
        var m = new FarPlayerMotion(entry(0, 64, 0, 0), 10, 1_000);
        assertFalse(m.isMoving(1_100), "a held snapshot is not moving");
        m.apply(entry(10, 64, 0, 0), 10, 1_500);
        assertTrue(m.isMoving(1_700), "mid-lerp is moving");
        assertFalse(m.isMoving(10_000), "past window + extrapolation cap = stopped");

        var m2 = new FarPlayerMotion(entry(0, 64, 0, 0), 10, 1_000);
        m2.apply(entry(10, 64, 0, 40), 10, 1_500);
        assertTrue(m2.isMoving(1_500 + 600 + 100), "extrapolating with a hint is moving");
    }

    @Test
    void vehicleMotionSeedsFromMountPositionAndRidesTheRiderVelocity() {
        // R-10 v1.3: the mount lerps its OWN wire positions but extrapolates with the
        // RIDER's velocity hint — separate hints shear visibly at horse/boat speeds.
        var m = new FarPlayerMotion(100.0, 64.0, 0.0, 90f, 10f, 20, 0, 0, 10, 1_000);
        var seed = m.sample(1_100);
        assertEquals(100, seed.x(), 1e-9, "inside the window the seed lerps in place");
        assertEquals(90f, seed.yaw(), 1e-4, "vehicle yaw mirrors into headYaw"
                + " (vehicles have no separate head)");
        assertEquals(90f, seed.headYaw(), 1e-4);
        // The seed carries the RIDER's velocity from creation (E3 review m3): past
        // the window it dead-reckons instead of parking a full window behind the
        // rider (window 600; +100 ms past it at 20 b/s = 2 blocks).
        assertEquals(102, m.sample(1_000 + 600 + 100).x(), 1e-4,
                "a mid-motion mount creation extrapolates immediately");

        // Rider velocity 20 b/s applied through applyRaw at the mount's new position.
        m.applyRaw(110, 64, 0, 90f, 90f, 10f, 20, 0, 0, 10, 1_500);
        assertEquals(600, m.windowMillis());
        assertEquals(105, m.sample(1_800).x(), 1e-6, "mount lerp midpoint");
        // 200 ms past the window: extrapolate with the RIDER's hint (20 b/s * 0.2 s).
        assertEquals(110 + 4, m.sample(1_500 + 600 + 200).x(), 1e-4,
                "the mount dead-reckons on the rider's velocity");
    }

    @Test
    void trackerAttachesOneMotionPerPlayerAndAppliesAcrossFrames() {
        var t = new FarPlayerClientTracker();
        var A = new java.util.UUID(0, 1);
        t.onRoster(new FarPlayerWire.Roster(1, true,
                java.util.List.of(new FarPlayerWire.RosterEntry(0, A, "Alice")), new int[0]));
        t.onUpdates(new FarPlayerWire.Updates(1, "minecraft:overworld", 10,
                java.util.List.of(entry(100, 64, 0, 0))), 1_000);
        var motion = t.snapshot().get(A).motion();
        assertNotNull(motion);
        t.onUpdates(new FarPlayerWire.Updates(1, "minecraft:overworld", 10,
                java.util.List.of(entry(110, 64, 0, 0))), 1_500);
        assertSame(motion, t.snapshot().get(A).motion(),
                "the interpolator survives frames (identity = continuity)");
        assertEquals(105, motion.sample(1_800).x(), 1e-6,
                "the tracker feeds frames into the SAME motion state");
    }
}
