package dev.vox.lssfixture.xaeromap.mixin;
import dev.vox.lssfixture.xaeromap.Probe;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Pseudo @Mixin(targets="xaero.map.region.MapTileChunk",remap=false)
public abstract class NativeTileMixin {
 @Inject(method="getTile",at=@At("RETURN"),require=1,remap=false)
 private void observed(int x,int z,CallbackInfoReturnable<Object> ci){Probe.nativeTile(this,x,z);}
 @Inject(method="wasChanged",at=@At("RETURN"),require=1,remap=false)
 private void checked(CallbackInfoReturnable<Boolean> ci){Probe.nativeGroupChecked(this,ci.getReturnValue());}
}

