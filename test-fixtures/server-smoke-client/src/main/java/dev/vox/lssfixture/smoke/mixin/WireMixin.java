package dev.vox.lssfixture.smoke.mixin;
import dev.vox.lssfixture.smoke.SmokeClient;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="dev.vox.lss.networking.client.ClientNetGlue",remap=false)
public abstract class WireMixin {
    @Inject(method="onVoxelColumnFrame",at=@At("HEAD"),require=1)
    private static void rigWire(VoxelColumnS2CPayload payload,CallbackInfo ci){SmokeClient.wire(payload);}
}
