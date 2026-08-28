package dev.vox.lss.mixin;

import dev.vox.lss.networking.server.LSSServerNetworking;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IntegratedServer.class)
public class IntegratedServerLanHook {
    // 1.21.1-LINE FLAVOR: this line's IntegratedServer declares exactly one publishServer
    // overload — (GameType, boolean, int) — so the bare selector would also work; the full
    // descriptor is pinned anyway so a future overload (26.2 added the MultiplayerScope
    // split, where the LAN screen calls a DIFFERENT 2-arg overload — the M2 retarget) fails
    // loudly at apply time instead of matching ambiguously. The descriptor-vs-real-overload
    // agreement is enforced by LanHookContractTest (this line's single-overload flavor).
    @Inject(method = "publishServer(Lnet/minecraft/world/level/GameType;ZI)Z", at = @At("RETURN"))
    private void lss$onLanPublished(GameType gameType, boolean allowCheats, int port,
                                     CallbackInfoReturnable<Boolean> cir) {
        // getReturnValue() is read HERE, in the callback frame (false for already-published
        // and listener IOException — no spurious starts); only the start itself is deferred
        // (see startServiceForLan's server-thread hop).
        if (cir.getReturnValue()) {
            LSSServerNetworking.startServiceForLan((IntegratedServer) (Object) this);
        }
    }
}
