package dev.vox.lssfixture.wi5.mixin;

import dev.vox.lssfixture.wi5.Probe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class ClientTickMixin {
    @Inject(method = "tick()V", at = @At("HEAD"), require = 1)
    private void wi5Tick(CallbackInfo ci) { Probe.tick(); dev.vox.lssfixture.wi5.ReplacementProbe.tick(); }
}
