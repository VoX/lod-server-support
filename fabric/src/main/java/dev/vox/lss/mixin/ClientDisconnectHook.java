package dev.vox.lss.mixin;

import dev.vox.lss.networking.client.LSSClientNetworking;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client is closing its play connection: run the mod's disconnect cleanup here, on the
 * client thread, before the level is torn down.
 *
 * <p>Fabric's {@code ClientPlayConnectionEvents.DISCONNECT} cannot carry it. That event fires
 * from whichever comes first, netty's {@code channelInactive} or the client thread's own
 * disconnect handling, and netty wins whenever the socket closes first: every kick, every
 * server stop, and usually the player's own Disconnect button. The cleanup then ran on a
 * network thread while the client thread was still ticking and drawing with the same state
 * (the request manager's maps, the far-player proxies, the Distant Horizons session).
 *
 * <p>LINE-FACT (26.3, 26.2, 1.21.11, 1.21.1; read from each line's classes 2026-11-13):
 * {@code ClientPacketListener.close()} has one caller, {@code Minecraft.disconnect(...)}, which
 * every way out of a server goes through: the server closed the connection (the client thread
 * notices on its next tick), the player left, the game is quitting. It runs before the level
 * is cleared. NeoForge posts its logged-out event from that same method, so both loaders now
 * run the cleanup at the same point. {@code ClientDisconnectHookContractTest} pins the caller
 * per line. A server-initiated reconfiguration goes through {@code clearClientLevel} instead
 * and runs no cleanup on either loader (see {@code ClientNetGlue.onDisconnect}).
 */
@Mixin(ClientPacketListener.class)
public class ClientDisconnectHook {
    @Inject(method = "close()V", at = @At("HEAD"))
    private void lss$onClose(CallbackInfo ci) {
        LSSClientNetworking.onConnectionClosing();
    }
}
