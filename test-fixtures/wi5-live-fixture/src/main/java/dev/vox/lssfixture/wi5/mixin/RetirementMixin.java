package dev.vox.lssfixture.wi5.mixin;
import dev.vox.lssfixture.wi5.ReplacementProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets = "dev.vox.lss.compat.XaeroSession", remap = false)
public abstract class RetirementMixin {
    @Inject(method = "onSessionEnd()V", at = @At("HEAD"), require = 1, remap = false)
    private void before(CallbackInfo ci) { ReplacementProbe.beforeRetire(this); }
}
