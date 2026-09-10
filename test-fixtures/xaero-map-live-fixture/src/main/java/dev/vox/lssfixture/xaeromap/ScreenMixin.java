package dev.vox.lssfixture.xaeromap;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Observe completed native frames; never opens or replaces a screen. */
@Mixin(Minecraft.class)
public abstract class ScreenMixin {
 @Inject(method="runTick",at=@At("RETURN"),require=1)
 private void rendered(boolean renderLevel,CallbackInfo ci){
  if(renderLevel)Probe.renderedScreen(Minecraft.getInstance().screen);
 }
}
