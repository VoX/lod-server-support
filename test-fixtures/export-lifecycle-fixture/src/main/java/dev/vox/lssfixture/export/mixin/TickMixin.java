package dev.vox.lssfixture.export.mixin;
import dev.vox.lssfixture.export.ExportProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(net.minecraft.client.Minecraft.class) public abstract class TickMixin {
@Inject(method="tick()V",at=@At("TAIL"),require=1) private void tick(CallbackInfo ci){ExportProbe.tick();}
}