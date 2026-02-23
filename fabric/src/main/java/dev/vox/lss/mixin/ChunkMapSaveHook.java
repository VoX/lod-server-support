package dev.vox.lss.mixin;

import dev.vox.lss.networking.server.ChunkChangeTracker;
import dev.vox.lss.networking.server.LSSServerNetworking;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public class ChunkMapSaveHook {
    @Inject(method = "save", at = @At("RETURN"))
    private void lss$onChunkSaved(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        var service = LSSServerNetworking.getRequestService();
        if (cir.getReturnValue() && service != null) {
            ServerLevel level = ((AccessorChunkMap) (Object) this).getLevel();
            ChunkChangeTracker.markDirty(level.dimension(), chunk.getPos());
        }
    }
}
