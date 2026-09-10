package dev.vox.lssfixture.concurrent.mixin;

import dev.vox.lssfixture.concurrent.ClientProbe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The pinned 26.2 engine's real frame entry. No render-thread I/O or waiting. */
@Mixin(Minecraft.class)
public abstract class FrameMixin {
    @Inject(method="runTick(Z)V",at=@At("HEAD"),require=1)
    private void lssRigFrame(boolean renderLevel,CallbackInfo callback){ClientProbe.frame();}
}
