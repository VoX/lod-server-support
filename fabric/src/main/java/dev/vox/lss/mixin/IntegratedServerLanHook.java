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
    // 1.21.8's IntegratedServer#publishServer is (GameType, boolean, int) — the single pre-26.2
    // overload — so a bare method selector is unambiguous. (26.2 added a leading MultiplayerScope
    // parameter and a second overload; that adaptation lives on main, not this support line.)
    @Inject(method = "publishServer", at = @At("RETURN"))
    private void lss$onLanPublished(GameType gameType, boolean allowCheats, int port,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            LSSServerNetworking.startServiceForLan((IntegratedServer) (Object) this);
        }
    }
}
