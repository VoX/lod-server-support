package dev.vox.lss.mixin;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Client-only accessor behind the far-player armor depth-lift's TIERS
 * (far-player-render-hardening-plan.md WI-6 fold (e2), ported to the extract/submit lines):
 * the render types the humanoid armor layer will request for a piece come from the
 * client-side {@code EquipmentClientInfo} of its {@code Equippable} asset, and the
 * {@code EquipmentAssetManager} holding those lives ONLY in the dispatcher's private field
 * (no getter on {@code Minecraft} or the dispatcher on this line; the renderer-provider
 * context is not reachable after registration). Listed under {@code client} in BOTH loaders'
 * mixin configs; the renderer reads it through an {@code instanceof} so an unapplied mixin
 * degrades to the pre-(e2) single armor tier, never a throw.
 */
@Mixin(EntityRenderDispatcher.class)
public interface AccessorEntityRenderDispatcher {

    @Accessor("equipmentAssets")
    EquipmentAssetManager lss$getEquipmentAssets();
}
