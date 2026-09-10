package dev.vox.lssfixture.elytra.mixin;
import dev.vox.lssfixture.elytra.Probe;
import net.minecraft.client.renderer.fog.FogRenderer;
import java.nio.ByteBuffer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Observes actual GPU fog-buffer update arguments; changes neither buffer nor fog. */
@Mixin(FogRenderer.class)
public abstract class FogBufferMixin {
 @Inject(method="updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V",at=@At("HEAD"),require=1,expect=1,allow=1)
 private void observe(ByteBuffer buffer,int offset,Vector4f color,float a,float b,float c,float d,float e,float f,CallbackInfo ci){Probe.fog(offset,color,a,b,c,d,e,f);}
}
