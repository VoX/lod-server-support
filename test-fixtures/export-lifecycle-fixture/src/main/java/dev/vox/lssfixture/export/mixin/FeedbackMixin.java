package dev.vox.lssfixture.export.mixin;
import dev.vox.lssfixture.export.ExportProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(targets="dev.vox.lss.networking.client.ClientStatusScreen",remap=false) public abstract class FeedbackMixin {
@Inject(method="feedback",at=@At("HEAD"),require=1,remap=false)
private void feedback(net.minecraft.network.chat.Component message,CallbackInfo ci){ExportProbe.screenFeedback(message.getString());}
}