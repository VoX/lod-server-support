package dev.vox.lss.networking.client;

import net.minecraft.world.entity.ElytraAnimationState;

/** Render-only players do not receive LivingEntity.tick; advance its wing input
 *  only when the caller's animation tick changes, after applying the current pose. */
public final class FarPlayerWingAnimation {
    private FarPlayerWingAnimation() {}

    public static void advance(ElytraAnimationState state, boolean newAnimationTick) {
        if (newAnimationTick) state.tick();
    }
}
