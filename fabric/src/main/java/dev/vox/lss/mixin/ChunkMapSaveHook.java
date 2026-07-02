package dev.vox.lss.mixin;

import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.server.LSSServerNetworking;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public class ChunkMapSaveHook {
    @Unique
    private String lss$cachedDimension;

    @Inject(method = "save", at = @At("RETURN"))
    private void lss$onChunkSaved(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        // Only FULL chunks: a ProtoChunk save during generation has no LOD-servable
        // content yet (re-requesting it reads "not found" and escalates to generation),
        // and the completed chunk reaches clients through the generation serve path.
        if (cir.getReturnValue() && chunk instanceof LevelChunk levelChunk) {
            var service = LSSServerNetworking.getRequestService();
            // enabled=false also gates here: the service tick (and so the dirty-broadcast
            // drain) is disabled, so marking would grow the tracker without bound — and
            // the content hash serializes the column on every save for nothing.
            if (service != null && LSSServerConfig.CONFIG.enabled) {
                ServerLevel level = ((AccessorChunkMap) (Object) this).getLevel();
                if (this.lss$cachedDimension == null) {
                    this.lss$cachedDimension = level.dimension().location().toString();
                }
                // Vanilla re-saves loaded chunks for metadata alone (inhabitedTime), so a
                // save is not evidence of change — only hash-confirmed content edits count.
                if (service.getDirtyContentFilter().contentChanged(level, levelChunk, this.lss$cachedDimension)) {
                    service.getDirtyTracker().markDirty(this.lss$cachedDimension, chunk.getPos().x, chunk.getPos().z);
                }
            }
        }
    }
}
