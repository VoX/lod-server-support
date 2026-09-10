package dev.vox.lssfixture.xaeromap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(targets="dev.vox.lss.networking.client.ClientColumnProcessor",remap=false)
public abstract class WireMixin {
 @Inject(method="offer(Ldev/vox/lss/networking/payloads/VoxelColumnS2CPayload;ZLdev/vox/lss/networking/client/ColumnDelivery;)V",at=@At("HEAD"),remap=false)
 private void offered(@Coerce Object payload,boolean resync,@Coerce Object delivery,CallbackInfo ci){Probe.wire(payload);}
}
