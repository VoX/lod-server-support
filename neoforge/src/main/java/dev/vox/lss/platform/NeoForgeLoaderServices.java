package dev.vox.lss.platform;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Path;

/**
 * The common (both-physical-sides) NeoForge impl of {@link LoaderServices},
 * installed by {@code LSSNeoMod} (and resolvable via ServiceLoader — the
 * META-INF/services registration mirrors the Fabric module's). The client
 * subclass supplies {@link #sendToServer} on physical clients.
 *
 * <p><b>The sendIfListening contract (plan §1.2):</b> NeoForge THROWS on a
 * send to a peer that has not negotiated the channel, where Fabric silently
 * no-ops — and the C2S handshake is an unprompted FIRST send, so a NeoForge
 * client joining a vanilla/LSS-less server would crash without containment.
 * Both send methods contain exactly that failure shape ({@code hasChannel}
 * pre-check + the throw caught as a silent drop); connection-level failures
 * still propagate to the callers' send-failure ladders.
 */
public class NeoForgeLoaderServices implements LoaderServices {

    public static void installProduction() {
        LoaderServices.install(new NeoForgeLoaderServices());
    }

    @Override
    public boolean checkPermission(net.minecraft.server.level.ServerPlayer player, String node,
                                   boolean defaultValue) {
        // The native PermissionAPI rung (service-permission-gate-plan.md §2.1).
        return dev.vox.lss.neoforge.LSSNeoPermissions.check(player, node, defaultValue);
    }

    @Override
    public String permissionProviderToken() {
        return "neoforge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (!player.connection.hasChannel(payload.type())) {
            return; // Fabric-parity silent no-op: the peer would ignore the packet.
        }
        try {
            PacketDistributor.sendToPlayer(player, payload);
        } catch (UnsupportedOperationException e) {
            // The negotiation race between the pre-check and the send — same
            // Fabric-parity no-op (plan §1.2; the pre-check makes this rare).
        }
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        throw new IllegalStateException("sendToServer on the dedicated-server LoaderServices impl"
                + " — the client bootstrap installs the client-capable impl");
    }
}
