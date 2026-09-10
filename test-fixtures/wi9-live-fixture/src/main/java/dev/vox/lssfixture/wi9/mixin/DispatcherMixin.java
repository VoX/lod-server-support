package dev.vox.lssfixture.wi9.mixin;

import dev.vox.lssfixture.wi9.Probe;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public abstract class DispatcherMixin {
    private static final String DRAW = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V";
    @Inject(method = DRAW, at = @At("HEAD"), require = 1)
    private void wi9Head(Entity entity, double x, double y, double z, float yaw, float delta,
                         PoseStack poses, MultiBufferSource buffers, int light, CallbackInfo ci) {
        Probe.dispatcherHead(entity, poses);
    }
    @Inject(method = DRAW, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V",
            ordinal = 0, shift = At.Shift.AFTER), require = 1)
    private void wi9Fault(Entity entity, double x, double y, double z, float yaw, float delta,
                          PoseStack poses, MultiBufferSource buffers, int light, CallbackInfo ci) {
        Probe.afterFirstTranslate(entity, poses);
    }
    @Inject(method = DRAW, at = @At("RETURN"), require = 1)
    private void wi9Returned(Entity entity, double x, double y, double z, float yaw, float delta,
                            PoseStack poses, MultiBufferSource buffers, int light, CallbackInfo ci) {
        Probe.dispatcherReturn(entity);
    }
}
