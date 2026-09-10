package dev.vox.lssfixture.elytra;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records a completed real LSS proxy submission; never changes entity or renderer state. */
@Mixin(targets="dev.vox.lss.networking.client.FarPlayerRenderer", remap=false)
public abstract class ProxySubmitMixin {
    @Inject(method="submitProxy",at=@At("RETURN"),require=1)
    private static void observe(EntityRenderDispatcher dispatcher, @Coerce Object proxy,
            float partialTick, boolean fullBright, double distance, Vec3 position,
            Vec3 camera, PoseStack poses, SubmitNodeCollector collector,
            CameraRenderState cameraState, CallbackInfoReturnable<Integer> result) {
        Probe.submitted(proxy,distance,position);
    }
}
