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
    // 26.1-LINE FLAVOR (D3 re-port): this line's IntegratedServer declares exactly ONE
    // publishServer overload — (GameType, boolean, int) — so the M2 two-overload trap that
    // bit 26.2 (the GUI calling a 2-arg MultiplayerScope overload while the hook pinned the
    // delegating 4-arg wrapper) cannot arise here. The full descriptor is pinned anyway so
    // a future overload fails loudly at apply time instead of matching ambiguously; the
    // descriptor-vs-real-overload agreement is enforced by LanHookContractTest (26.1 flavor).
    @Inject(method = "publishServer(Lnet/minecraft/world/level/GameType;ZI)Z", at = @At("RETURN"))
    private void lss$onLanPublished(GameType gameType, boolean allowCheats, int port,
                                     CallbackInfoReturnable<Boolean> cir) {
        // getReturnValue() is read HERE, in the callback frame. 26.1: false comes ONLY
        // from the listener-IOException handler — this line's publishServer has NO
        // already-published guard (javap-verified, review 2026-08-15), so double-publish
        // idempotency rests on startServiceForLanOnServerThread's requestService
        // null-check (pinned by the Tier-2 idempotency gametest), not on this flag.
        // Only the start itself is deferred (see startServiceForLan's server-thread hop).
        if (cir.getReturnValue()) {
            LSSServerNetworking.startServiceForLan((IntegratedServer) (Object) this);
        }
    }
}
