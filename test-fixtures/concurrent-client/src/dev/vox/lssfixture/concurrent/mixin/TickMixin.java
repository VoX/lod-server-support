package dev.vox.lssfixture.concurrent.mixin;
import dev.vox.lssfixture.concurrent.ClientProbe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public abstract class TickMixin {
    @Inject(method="tick",at=@At("HEAD"),require=1)
    private void rigTick(CallbackInfo ci){ClientProbe.tick();}
}
