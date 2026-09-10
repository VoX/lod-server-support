package dev.vox.lssfixture.wi6.mixin;

import dev.vox.lssfixture.wi6.Probe;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.UUID;

@Mixin(targets = "dev.vox.lss.networking.server.RequestProcessingService", remap = false)
public abstract class ServiceMixin {
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final private dev.vox.lss.common.farplayers.FarPlayerBroadcastService farPlayerService;
    @Inject(method = "tickFarPlayers", at = @At("HEAD"), require = 1)
    private void wi6TickStart(CallbackInfo ci) { dev.vox.lssfixture.wi6.AbruptCloseProbe.tick(server); Probe.tickStart(server); }
    @Inject(method = "tickFarPlayers", at = @At("RETURN"), require = 1)
    private void wi6TickEnd(CallbackInfo ci) { Probe.tickEnd(server, farPlayerService); }
    @Inject(method = "sendFarPlayerFrame", at = @At("HEAD"), cancellable = true, require = 1)
    private void wi6Admission(UUID viewer, String channel, byte[] body, CallbackInfoReturnable<Boolean> cir) {
        if (Probe.decline(viewer, channel, body)) cir.setReturnValue(false);
    }
    @Inject(method = "sendFarPlayerFrame", at = @At("RETURN"), require = 1)
    private void wi6ActualReturn(UUID viewer, String channel, byte[] body, CallbackInfoReturnable<Boolean> cir) {
        Probe.returned(viewer, channel, body, cir.getReturnValue());
    }
}
