package dev.vox.lss.platform;

import dev.vox.lss.common.diagnostics.DiagnosticVersions;
import dev.vox.lss.common.diagnostics.DiagnosticVersions.Component;
import dev.vox.lss.sponge.SpongeChannels;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.api.Platform;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.util.Tristate;
import org.spongepowered.plugin.PluginContainer;

import java.nio.file.Path;
import java.util.EnumMap;

/**
 * The Sponge impl of {@link LoaderServices} (sponge-support-plan.md §1.1), installed by
 * {@code LSSSpongePlugin} before any config touch. Plugin facts come from the INJECTED
 * {@link PluginContainer} — never a lookup by id, because the VSS jar carries a different
 * plugin id. Sends go through Sponge raw data channels ({@link SpongeChannels});
 * permissions read the Sponge subject, UNDEFINED answering the caller's default (the
 * seam's never-throw contract); vanish is the native {@code VANISH_STATE}.
 */
public class SpongeLoaderServices implements LoaderServices {

    private final Path configDir;
    private final PluginContainer container;
    private final DiagnosticVersions diagnosticVersions;
    private final VanishVisibility vanish = new SpongeVanishVisibility();

    public SpongeLoaderServices(Path configDir, PluginContainer container) {
        this.configDir = configDir;
        this.container = container;
        this.diagnosticVersions = captureDiagnosticVersions(container);
    }

    @Override public DiagnosticVersions diagnosticVersions() { return diagnosticVersions; }

    private static DiagnosticVersions captureDiagnosticVersions(PluginContainer container) {
        var values = new EnumMap<Component, String>(Component.class);
        values.put(Component.LSS, container.metadata().version().toString());
        values.put(Component.MINECRAFT, Sponge.platform().minecraftVersion().name());
        // "sponge-" prefix (the Paper "paper-" precedent): /lsslod diag must tell a Sponge
        // server from a Fabric one at a glance.
        values.put(Component.LOADER, "sponge-" + Sponge.platform().container(Platform.Component.IMPLEMENTATION)
                .metadata().version());
        return new DiagnosticVersions(values);
    }

    @Override
    public boolean isModLoaded(String modId) {
        return Sponge.pluginManager().plugin(modId).isPresent();
    }

    @Override
    public Path configDir() {
        return configDir;
    }

    @Override
    public Path gameDir() {
        return Sponge.game().gameDirectory();
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        SpongeChannels.send(player, payload);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        throw new IllegalStateException("sendToServer on the Sponge (dedicated-server) LoaderServices impl");
    }

    /** The Sponge command is gated on the build's admin node (SpongeCommands.canExecute). */
    @Override
    public boolean mayRunServerCommand(ServerPlayer player, boolean permissionLevelAllows) {
        try {
            return ((org.spongepowered.api.entity.living.player.server.ServerPlayer) (Object) player)
                    .hasPermission(dev.vox.lss.common.Brand.lowerShortName() + ".admin");
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean checkPermission(ServerPlayer player, String node, boolean defaultValue) {
        try {
            // permissionValue (never hasPermission): an UNSET node must answer the CALLER's
            // default — the service gate passes TRUE, the far-player hide nodes FALSE.
            var value = ((org.spongepowered.api.entity.living.player.server.ServerPlayer) (Object) player)
                    .permissionValue(node);
            return value == Tristate.UNDEFINED ? defaultValue : value.asBoolean();
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

    @Override
    public String storePlatform() {
        return "sponge";
    }

    @Override
    public String permissionProviderToken() {
        return "sponge";
    }

    @Override
    public VanishVisibility vanishVisibility() {
        return vanish;
    }

    /** The injected plugin container (commands, scheduler). */
    public PluginContainer container() {
        return container;
    }
}
