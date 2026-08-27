package dev.vox.lss.neoforge;

import com.mojang.brigadier.Command;
import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.compat.ModCompat;
import dev.vox.lss.networking.client.ClientCommandActions;
import dev.vox.lss.networking.client.ClientNetGlue;
import dev.vox.lss.platform.NeoForgeClientLoaderServices;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.function.Consumer;

/**
 * NeoForge client half (N-3) — the fabric {@code LSSClient} twin, loaded
 * reflectively by {@code LSSNeoMod} behind the dist gate (this class touches
 * client-only types). Wires the loader-neutral {@link ClientNetGlue} lifecycle
 * to NeoForge's client events, upgrades the LoaderServices install to the
 * client-capable impl, registers the /lss client command tree over the shared
 * {@link ClientCommandActions} bodies, and boots the Voxy bridge
 * ({@code ModCompat} — the graceful-degrade ladder IS the contract; on 26.2 no
 * community Voxy build exists yet, so the client half runs compiled-and-inert:
 * no consumer → no capability bit → no LOD session, by construction).
 */
public final class LSSNeoClientBootstrap {

    private LSSNeoClientBootstrap() {
    }

    /** Invoked reflectively from {@code LSSNeoMod} (client dist only). */
    public static void init() {
        // Upgrade the common LoaderServices install to the client-capable impl (adds
        // sendToServer) before any client networking can run.
        NeoForgeClientLoaderServices.installProductionClient();

        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class,
                e -> ClientNetGlue.onJoin());
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class,
                e -> ClientNetGlue.onDisconnect());
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class,
                e -> ClientNetGlue.onEndClientTick());
        // The Xaero bridge's per-frame rebuild slice (plan §17); fires every frame,
        // cheap no-op without owed rebuilds.
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Post.class,
                e -> ClientNetGlue.onRenderFrame());
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class,
                LSSNeoClientBootstrap::registerClientCommands);

        ModCompat.init();

        // Far players, the recorded N-3 render-path cut (plan §N-3 pre-authorized form;
        // decisions log): the tracker, wire channels, and capability arm term are all
        // live — ONLY rendering is absent on NeoForge v1. Announced once per launch so
        // nobody chases the gap as a bug.
        LSSLogger.info(Brand.shortName() + " far players on NeoForge v1: positions are"
                + " tracked but the render path is not implemented (best-effort tier)");
    }

    private static void registerClientCommands(RegisterClientCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal(Brand.clientCommand())
                .then(Commands.literal("clearcache")
                        .executes(context -> {
                            ClientCommandActions.clearCache(feedback(context.getSource()));
                            return Command.SINGLE_SUCCESS;
                        })
                )
                // Issue #4: the reset subtree (incl. `voxy-force`) is BUILT IN
                // xplat and shared with Fabric — do not re-hand-roll it here.
                .then(ClientCommandActions.resetSubtree(
                        Commands::literal, LSSNeoClientBootstrap::feedback))
                .then(Commands.literal("diag")
                        .executes(context -> {
                            ClientCommandActions.showDiagnostics(feedback(context.getSource()));
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(Commands.literal("trace")
                        .executes(context -> {
                            ClientCommandActions.toggleTrace(feedback(context.getSource()));
                            return Command.SINGLE_SUCCESS;
                        })
                )
        );
    }

    /** The source adapter (plan §1.1 item 5): NeoForge client commands dispatch over
     *  CommandSourceStack; the shared bodies take a bare Component sink. */
    private static Consumer<Component> feedback(CommandSourceStack source) {
        return component -> source.sendSuccess(() -> component, false);
    }
}
