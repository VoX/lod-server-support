package dev.vox.lssfixture.export.mixin;
import dev.vox.lssfixture.export.ExportProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(targets="dev.vox.lss.networking.client.ClientStatus",remap=false) public abstract class CompletionMixin {
@Inject(method="completeExportFeedback",at=@At("RETURN"),require=1,remap=false)
private static void completed(@Coerce Object ticket,String message,CallbackInfo ci){ExportProbe.completed();}
}