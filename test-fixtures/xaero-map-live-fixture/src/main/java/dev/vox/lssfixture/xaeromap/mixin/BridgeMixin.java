package dev.vox.lssfixture.xaeromap.mixin;
import dev.vox.lssfixture.xaeromap.Probe;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(targets="dev.vox.lss.compat.XaeroTileWriter",remap=false)
public abstract class BridgeMixin {
 @Inject(method="commitEntry",at=@At("RETURN"),remap=false)
 private void result(Object processor,Object dimension,@Coerce Object tile,boolean load,boolean update,CallbackInfoReturnable<Object> ci){Probe.bridge(tile,ci.getReturnValue());}
}
