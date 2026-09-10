package dev.vox.lssfixture.wi9.mixin;

import dev.vox.lssfixture.wi9.Probe;

import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Same LSS method names on Fabric and NeoForge; callbacks intentionally omit event-typed args. */
@Mixin(targets = "dev.vox.lss.networking.client.FarPlayerRenderer", remap = false)
public abstract class RendererMixin {
    @Shadow private boolean crashLatched;
    @Inject(method = "render", at = @At("HEAD"), require = 1)
    private void wi9Begin(CallbackInfo ci) { Probe.begin(); }
    @Inject(method = "markPose", at = @At("HEAD"), require = 1)
    private static void wi9BeforeMark(PoseStack poses, CallbackInfoReturnable<PoseStack.Pose> cir) { Probe.beforeMark(poses); }
    @Inject(method = "markPose", at = @At("RETURN"), require = 1)
    private static void wi9AfterMark(PoseStack poses, CallbackInfoReturnable<PoseStack.Pose> cir) { Probe.afterMark(cir.getReturnValue()); }
    @Inject(method = "renderFarNameTag", at = @At("HEAD"), require = 1)
    private static void wi9TagHead(CallbackInfo ci) { Probe.tagHead(); }
    @Inject(method = "renderFarNameTag", at = @At("RETURN"), require = 1)
    private static void wi9TagReturn(CallbackInfo ci) { Probe.tagReturn(); }
    @Inject(method = "render", at = @At("RETURN"), require = 1)
    private void wi9End(CallbackInfo ci) { Probe.end(crashLatched); }
}
