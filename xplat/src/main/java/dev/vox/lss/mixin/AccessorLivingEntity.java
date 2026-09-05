package dev.vox.lss.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Writes the two private swim-amount fields that only {@code LivingEntity.tick()} advances
 * ({@code updateSwimAmount} is private). The far-player proxies are render-only entities
 * whose tick never runs, so without this a swimming proxy stood bolt upright:
 * {@code PlayerRenderer.setupRotations} (the -90° body roll) and {@code HumanoidModel} (the
 * stroke) both read {@code getSwimAmount(partialTick)}, which stayed 0 forever. Same class of
 * defect as the walk cycle — tick-only render state faked from the render pass
 * (far-player-render-hardening-plan.md WI-6 fold (d)). Client-only listing in both loaders'
 * mixin configs.
 *
 * <p>Methods are {@code lss$}-prefixed because mixin adds them to the target class: unprefixed
 * names could collide with vanilla methods.
 */
@Mixin(LivingEntity.class)
public interface AccessorLivingEntity {
    @Accessor("swimAmount")
    float lss$getSwimAmount();

    @Accessor("swimAmount")
    void lss$setSwimAmount(float value);

    @Accessor("swimAmountO")
    void lss$setSwimAmountO(float value);
}
