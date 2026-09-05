package dev.vox.lss.networking.client;

import net.minecraft.world.entity.WalkAnimationState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural pin for the 1.21.1 walk-cycle stop (far-player-render-hardening-plan.md WI-6 fold
 * (c)): this MC has no {@code WalkAnimationState.stop()}, and {@code setSpeed(0)} alone leaves
 * {@code speedOld} at the last walking speed — the renderer's {@code speed(partialTick)} then
 * lerps it toward zero across every tick, a 20 Hz limb-swing sawtooth (the "super-speed walk"
 * past the animation cap). {@code update(0, 1)} copies the zero into {@code speedOld} and leaves
 * the phase untouched. A plain vanilla POJO — no registries, no bootstrap.
 */
class WalkAnimationStopTest {

    @Test
    void setSpeedAloneLeavesTheOldSpeedBehindAndUpdateZeroClearsIt() {
        var walk = new WalkAnimationState();
        walk.update(1.0f, 1.0f);           // one walking tick: speed 1, speedOld 0
        walk.update(1.0f, 1.0f);           // speedOld 1 as well
        float phase = walk.position();
        walk.setSpeed(0.0f);
        assertTrue(walk.speed(0.5f) > 0.0f,
                "the bug: setSpeed(0) leaves speedOld stale, so a mid-tick sample still swings");
        walk.update(0.0f, 1.0f);
        assertEquals(0.0f, walk.speed(0.5f), "the fix: update(0, 1) copies the zero into speedOld");
        assertEquals(0.0f, walk.speed(0.0f));
        assertEquals(phase, walk.position(), "the stop must not move the phase");
    }
}
