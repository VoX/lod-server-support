package dev.vox.lssfixture.smoke.mixin;
import dev.vox.lssfixture.smoke.SmokeClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public abstract class TickMixin {
    @Inject(method="tick",at=@At("HEAD"),require=1)
    private void rigTick(CallbackInfo ci){SmokeClient.tick();}
}
