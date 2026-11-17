package dev.vox.lss.sponge;

import com.google.inject.Inject;
import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.networking.server.LSSServerNetworking;
import dev.vox.lss.platform.LoaderServices;
import dev.vox.lss.platform.SpongeLoaderServices;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.api.Platform;
import org.spongepowered.api.Server;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.lifecycle.RegisterChannelEvent;
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent;
import org.spongepowered.api.event.lifecycle.StartedEngineEvent;
import org.spongepowered.api.event.lifecycle.StartingEngineEvent;
import org.spongepowered.api.event.lifecycle.StoppingEngineEvent;
import org.spongepowered.api.event.network.ServerSideConnectionEvent;
import org.spongepowered.plugin.PluginContainer;
import org.spongepowered.plugin.builtin.jvm.Plugin;

import java.nio.file.Path;

/**
 * Sponge entrypoint — the fabric {@code LSSMod} / NeoForge {@code LSSNeoMod} twin
 * (sponge-support-plan.md §1). Brand first, the LoaderServices seam second (before any
 * config touch), then the implementation guard, then channel/command/permission/lifecycle
 * wiring onto the shared xplat server half. The service tick is {@code ServerTickHook}
 * (tickServer TAIL), never Sponge's scheduler (which runs at tickServer HEAD).
 *
 * <p>The {@code @Plugin} value is not load-bearing: SpongeVanilla's java_plain loader
 * instantiates the metadata entrypoint, so the VSS jar (plugin id voxyserverside) runs
 * this same class.
 */
@Plugin("lodserversupport")
public class LSSSpongePlugin {
    /** The only implementation this plugin supports (plan §1.9). */
    static final String SUPPORTED_IMPLEMENTATION = "spongevanilla";

    private final PluginContainer container;
    private final boolean active;

    @Inject
    public LSSSpongePlugin(PluginContainer container, @ConfigDir(sharedRoot = true) Path configRoot) {
        this.container = container;
        Brand.load(LSSSpongePlugin.class.getClassLoader());
        // The SHARED config root — config/lss-server-config.yaml, where Fabric and NeoForge
        // keep it — so an LSS<->VSS jar swap keeps its config (the plugin ids differ, so a
        // per-plugin directory would fork it; plan §1.7).
        LoaderServices.install(new SpongeLoaderServices(configRoot, container));
        String implementation = Sponge.platform().container(Platform.Component.IMPLEMENTATION).metadata().id();
        this.active = SUPPORTED_IMPLEMENTATION.equals(implementation);
        if (!active) {
            // SpongeNeo: the LSS NeoForge mod is the supported route there; two services
            // on one server would double-serve every client. SpongeForge is unsupported
            // (no Forge jar). Plan §1.9.
            LSSLogger.error(Brand.displayName() + " (Sponge) supports SpongeVanilla only; this server runs '"
                    + implementation + "'. On SpongeNeo use the " + Brand.shortName()
                    + " NeoForge jar instead; SpongeForge is not supported. The plugin stays disabled.");
        }
    }

    public boolean active() {
        return active;
    }

    @Listener
    public void onRegisterChannels(RegisterChannelEvent event) {
        if (active) SpongeChannels.register(event);
    }

    @Listener
    public void onRegisterCommands(RegisterCommandEvent<Command.Raw> event) {
        if (active) SpongeCommands.register(event, container);
    }

    @Listener
    public void onServerStarting(StartingEngineEvent<Server> event) {
        if (active) SpongePermissions.register(event.engine().serviceProvider().permissionService(), container);
    }

    @Listener
    public void onServerStarted(StartedEngineEvent<Server> event) {
        if (!active) return;
        LSSServerNetworking.onServerStarted((MinecraftServer) event.engine());
        SpongeChannels.setReceiving(true);
        LSSLogger.info(Brand.displayName() + " (Sponge) enabled");
    }

    @Listener
    public void onServerStopping(StoppingEngineEvent<Server> event) {
        if (!active) return;
        // Stop dispatching before the teardown so no new frame races the shutdown.
        SpongeChannels.setReceiving(false);
        LSSServerNetworking.onServerStopping();
    }

    /**
     * A player who was in the world has left (a quit, a kick, a timeout or the server
     * stopping). SpongeVanilla posts {@code Leave} on the server thread, from the play
     * listener's {@code removePlayerFromWorld}, and only for a player who reached the world:
     * the point where NeoForge posts its logged-out event. Never {@code Disconnect}: it also
     * fires for a connection that never reached play, carrying that connection's profile,
     * and from Sponge's login thread when the drop lands during authentication, so a refused
     * second login of an account that is online would end the online player's session
     * (v0.23.0 plan §11). {@code SpongeConnectionEndContractTest} pins this listener.
     */
    @Listener
    public void onPlayerLeave(ServerSideConnectionEvent.Leave event) {
        if (active) LSSServerNetworking.onPlayerDisconnect(event.player().uniqueId());
    }
}
