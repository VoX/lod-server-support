package dev.vox.lssfixture.xaeromap.mixin;
import dev.vox.lssfixture.xaeromap.Probe;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
/** Observes the native scan branch; never forces a tile or buffer mutation. */
@Pseudo @Mixin(targets="xaero.map.MapWriter",remap=false)
public abstract class NativeWriterMixin {
 @Inject(method="writeChunk",at=@At("HEAD"),require=1,expect=1,allow=1,remap=false)
 private void begin(CallbackInfoReturnable<Boolean> ci){Probe.nativeBegin(this);}
 @Inject(method="writeChunk",at=@At(value="INVOKE",target="Lxaero/lib/common/reflection/util/ReflectionUtils;setReflectFieldValue(Ljava/lang/Object;Ljava/lang/reflect/Field;Ljava/lang/Object;)V",shift=At.Shift.AFTER),require=1,expect=1,allow=1,remap=false)
 private void scanned(CallbackInfoReturnable<Boolean> ci){Probe.nativeScanned();}
 @Inject(method="writeChunk",at=@At("RETURN"),require=1,remap=false)
 private void end(CallbackInfoReturnable<Boolean> ci){Probe.nativeEnd();}
}
