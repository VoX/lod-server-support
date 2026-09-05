package dev.vox.lss.networking.client;

import net.minecraft.world.entity.WalkAnimationState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Behavioural pin for this line's walk-cycle stop (far-player-render-hardening-plan.md WI-6 fold
 * (c)): the far-player proxy relies on vanilla's {@code WalkAnimationState.stop()} zeroing
 * {@code speedOld} as well as {@code speed} — verified at the port, and the reason the 1.21.1
 * {@code setSpeed(0)+update(0,1)} helper was NOT carried here. Should an MC bump turn stop() into
 * a bare setSpeed(0), the renderer's {@code speed(partialTick)} lerp would sawtooth the limb swing
 * at 20 Hz past the animation cap (the 1.21.1 live-rig shape) — this reds Tier 1 first. A plain
 * vanilla POJO — no registries, no bootstrap.
 */
class WalkAnimationStopTest {

    @Test
    void stopZeroesTheOldSpeedSoAMidTickSampleIsStill() {
        var walk = new WalkAnimationState();
        walk.update(1.0f, 1.0f, 1.0f);     // one walking tick: speed 1, speedOld 0
        walk.update(1.0f, 1.0f, 1.0f);     // speedOld 1 as well
        walk.stop();
        assertEquals(0.0f, walk.speed(0.5f), "stop() must zero speedOld — a mid-tick sample still swung otherwise");
        assertEquals(0.0f, walk.speed(0.0f));
        assertEquals(0.0f, walk.speed(1.0f));
        assertFalse(walk.isMoving());
    }
}
