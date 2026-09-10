package dev.vox.lssfixture.xaeromap.mixin;
import dev.vox.lssfixture.xaeromap.Probe;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Pseudo @Mixin(targets="xaero.map.file.MapSaveLoad",remap=false)
public abstract class SaveMixin {
 @Inject(method="saveRegion",at=@At("HEAD"),remap=false)
 private void begin(@Coerce Object region,boolean resave,int version,CallbackInfoReturnable<Boolean> ci){Probe.saveBegin(region);}
 @Inject(method="saveRegion",at=@At("RETURN"),remap=false)
 private void end(@Coerce Object region,boolean resave,int version,CallbackInfoReturnable<Boolean> ci){Probe.saveEnd(region,ci.getReturnValue());}
}
