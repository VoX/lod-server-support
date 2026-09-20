package dev.vox.lssfixture.export.mixin;
import dev.vox.lssfixture.export.ExportProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(targets="dev.vox.lss.common.diagnostics.DiagnosticExport",remap=false) public abstract class WriteMixin {
@Inject(method="writeCaptured(Ljava/nio/file/Path;[BLjava/lang/String;)Ljava/nio/file/Path;",at=@At("HEAD"),require=1,remap=false)
private static void before(java.nio.file.Path dir,byte[] bytes,String summary,CallbackInfoReturnable<java.nio.file.Path> ci){ExportProbe.beforeWrite();}
@Inject(method="writeCaptured(Ljava/nio/file/Path;[BLjava/lang/String;)Ljava/nio/file/Path;",at=@At("RETURN"),require=1,remap=false)
private static void after(java.nio.file.Path dir,byte[] bytes,String summary,CallbackInfoReturnable<java.nio.file.Path> ci){ExportProbe.afterWrite(ci.getReturnValue(),bytes);}
}