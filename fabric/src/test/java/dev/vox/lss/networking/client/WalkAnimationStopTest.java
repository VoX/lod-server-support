package dev.vox.lss.networking.client;

import net.minecraft.world.entity.WalkAnimationState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural pin for this line's walk-cycle stop (far-player-render-hardening-plan.md WI-6 fold
 * (c) + §11): the proxy stops its walk cycle with vanilla's {@code WalkAnimationState.stop()},
 * which on 26.1 zeroes {@code speedOld} as well as {@code speed} (and the phase). If an MC
 * bump ever turns {@code stop()} into a {@code setSpeed(0)} — leaving {@code speedOld} at the last
 * walking speed, which the renderer's {@code speed(partialTick)} lerps toward zero across every
 * tick, the 20 Hz limb-swing sawtooth 1.21.1 shipped past the animation cap — this reds Tier 1
 * instead of the live rig. A plain vanilla POJO — no registries, no bootstrap.
 */
class WalkAnimationStopTest {

    @Test
    void stopZeroesTheOldSpeedAsWellAsTheCurrentOne() {
        var walk = new WalkAnimationState();
        walk.update(1.0f, 1.0f, 1.0f);      // one walking tick: speed 1, speedOld 0
        walk.update(1.0f, 1.0f, 1.0f);      // speedOld 1 as well
        assertTrue(walk.speed(0.5f) > 0.0f, "premise: the cycle is swinging before the stop");
        walk.stop();
        assertEquals(0.0f, walk.speed(0.5f), "stop() must zero speedOld — a mid-tick sample must not swing");
        assertEquals(0.0f, walk.speed(0.0f), "stop() must zero the old speed");
        assertEquals(0.0f, walk.speed(1.0f), "stop() must zero the current speed");
        assertEquals(0.0f, walk.position(), "this line's stop() also zeroes the phase (recorded fact)");
    }
}
