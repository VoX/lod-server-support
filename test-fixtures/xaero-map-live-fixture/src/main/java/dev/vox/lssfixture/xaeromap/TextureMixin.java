package dev.vox.lssfixture.xaeromap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Pseudo @Mixin(targets="xaero.map.region.MapTileChunk",remap=false)
public abstract class TextureMixin {
 @Inject(method="updateBuffers",at=@At("RETURN"),remap=false)
 private void built(@Coerce Object processor,@Coerce Object tint,@Coerce Object overlay,boolean reload,@Coerce Object shapes,@Coerce Object config,CallbackInfo ci){Probe.texture(this,processor);}
}
