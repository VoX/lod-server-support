package dev.vox.lss.mixin;

import dev.vox.lss.networking.client.LSSClientNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client is leaving its server or world: run the mod's disconnect cleanup here, on the
 * client thread, before the level is torn down.
 *
 * <p>Fabric's {@code ClientPlayConnectionEvents.DISCONNECT} cannot carry it. That event fires
 * from whichever comes first, netty's {@code channelInactive} or the client thread's own
 * disconnect handling, and netty wins whenever the socket closes first: every kick, every
 * server stop, and usually the player's own Disconnect button. The cleanup then ran on a
 * network thread while the client thread was still ticking and drawing with the same state
 * (the request manager's maps, the far-player proxies, the Distant Horizons session).
 *
 * <p>{@code Minecraft.disconnect} is where every way out ends: the server closed the connection
 * (the client thread notices on its next tick), the player left, the game is quitting, a kick
 * while a proxy was moving the player. NeoForge posts its logged-out event from this same
 * method, for every call, so both loaders clean up at the same point; like that event, this
 * hook also fires when there is nothing to clean up, and the cleanup is a no-op then.
 *
 * <p>LINE-FACT(26.3): the overloads funnel into {@code disconnect(Screen, boolean, boolean)},
 * the one that closes the play listener and clears the level. 26.2 and 1.21.11 are the same;
 * on 1.21.1 it is {@code disconnect(Screen, boolean)}. {@code ClientDisconnectHookContractTest}
 * reads the line's own class: the descriptor here must be the overload the others reach.
 */
@Mixin(Minecraft.class)
public class ClientDisconnectHook {
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
    private void lss$onDisconnect(Screen screen, boolean keepResourcePacks, boolean stopSound, CallbackInfo ci) {
        LSSClientNetworking.onConnectionClosing();
    }
}
