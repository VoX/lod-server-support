package dev.vox.lssfixture.wi5.mixin;

import dev.vox.lssfixture.wi5.ReplacementProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "dev.vox.lss.compat.XaeroAcquisitionQueue", remap = false)
public abstract class ReplacementMixin {
    @Inject(method = "offerPrepared(Ljava/lang/Object;Ldev/vox/lss/compat/XaeroTileExtractor$PreparedTile;Ldev/vox/lss/compat/XaeroSession$Origin;)V", at = @At("HEAD"), require = 1, remap = false)
    private void before(Object dimension, @Coerce Object tile, @Coerce Object origin, CallbackInfo ci) {
        ReplacementProbe.beforeOffer(this, tile, origin);
    }
    @Inject(method = "offerPrepared(Ljava/lang/Object;Ldev/vox/lss/compat/XaeroTileExtractor$PreparedTile;Ldev/vox/lss/compat/XaeroSession$Origin;)V", at = @At("RETURN"), require = 1, remap = false)
    private void after(Object dimension, @Coerce Object tile, @Coerce Object origin, CallbackInfo ci) {
        ReplacementProbe.afterOffer(this, tile, origin);
    }
}
