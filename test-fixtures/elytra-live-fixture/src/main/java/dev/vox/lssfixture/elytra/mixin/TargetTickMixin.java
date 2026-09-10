package dev.vox.lssfixture.elytra.mixin;
import dev.vox.lssfixture.elytra.Probe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public abstract class TargetTickMixin {
 @Inject(method="tick()V",at=@At("TAIL"),require=1,expect=1,allow=1)
 private void lssElytraObserveNativeTick(CallbackInfo ci){Probe.tick();}
}
