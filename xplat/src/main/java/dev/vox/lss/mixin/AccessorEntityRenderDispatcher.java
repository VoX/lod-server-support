package dev.vox.lss.mixin;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Client-only accessor for the far-player renderer's armor depth-lift tiers
 * (far-player-render-hardening-plan.md fold (e2), 26.x port): the tier per render type is
 * derived from the SAME equipment-asset layers the armor layer renders, and on this line the
 * {@code EquipmentAssetManager} behind them is a private final of the dispatcher (the
 * {@code EntityRendererProvider.Context} that exposes it is transient at reload). Listed under
 * {@code client} in BOTH loaders' mixin configs (the {@code AccessorLivingEntity} discipline —
 * {@code AccessorLivingEntityContractTest} pins the listing and the target field).
 */
@Mixin(EntityRenderDispatcher.class)
public interface AccessorEntityRenderDispatcher {
    @Accessor("equipmentAssets")
    EquipmentAssetManager lss$getEquipmentAssets();
}
