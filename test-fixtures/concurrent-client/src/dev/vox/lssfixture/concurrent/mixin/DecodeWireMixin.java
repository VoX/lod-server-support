package dev.vox.lssfixture.concurrent.mixin;
import dev.vox.lssfixture.concurrent.ClientProbe;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Observes the exact queued native payload; no coordinate/stamp lookup or routing change. */
@Mixin(targets="dev.vox.lss.networking.client.ClientColumnProcessor",remap=false)
public abstract class DecodeWireMixin {
    private static java.lang.reflect.Method lssRigPayloadAccessor;
    @Redirect(method="drainColumnQueue(Lnet/minecraft/resources/ResourceKey;IIZLnet/minecraft/world/level/chunk/PalettedContainerFactory;Ljava/util/function/UnaryOperator;Ldev/vox/lss/networking/client/ClientColumnProcessor$ColumnDispatcher;I)V",at=@At(value="INVOKE",target="Ldev/vox/lss/networking/client/ClientColumnProcessor$QueuedColumn;payload()Ldev/vox/lss/networking/payloads/VoxelColumnS2CPayload;"),require=1,expect=1)
    private VoxelColumnS2CPayload lssRigExactPayload(@Coerce Object queued){
        try{
            if(lssRigPayloadAccessor==null){lssRigPayloadAccessor=queued.getClass().getDeclaredMethod("payload");if(!lssRigPayloadAccessor.trySetAccessible())throw new IllegalStateException("native payload accessor unavailable");}
            VoxelColumnS2CPayload payload=(VoxelColumnS2CPayload)lssRigPayloadAccessor.invoke(queued);
            ClientProbe.decoding(payload);return payload;
        }catch(ReflectiveOperationException error){throw new IllegalStateException("exact native wire association failed",error);}
    }
    @Inject(method="drainColumnQueue(Lnet/minecraft/resources/ResourceKey;IIZLnet/minecraft/world/level/chunk/PalettedContainerFactory;Ljava/util/function/UnaryOperator;Ldev/vox/lss/networking/client/ClientColumnProcessor$ColumnDispatcher;I)V",at=@At("RETURN"),require=1)
    private void lssRigClearDecode(CallbackInfo ci){ClientProbe.decodingFinished();}
}
