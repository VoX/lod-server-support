package dev.vox.lss.networking.client;

import com.mojang.brigadier.Command;
import dev.vox.lss.common.Brand;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

/**
 * Fabric /lss client command TREE — since N-3 the bodies live in the
 * source-neutral {@link ClientCommandActions} (xplat, shared with NeoForge);
 * this class keeps only the brigadier tree against Fabric's client source.
 */
public class LSSClientCommands {
    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // Command literal is branded (lss / vss); it is a LOCAL client command and
            // never crosses the wire, so it does not affect LSS<->VSS compatibility.
            dispatcher.register(ClientCommands.literal(Brand.clientCommand())
                    .then(ClientCommands.literal("clearcache")
                            .executes(context -> {
                                ClientCommandActions.clearCache(context.getSource()::sendFeedback);
                                return Command.SINGLE_SUCCESS;
                            })
                    )
                    // Issue #4: the reset subtree (incl. `voxy-force`) is BUILT IN
                    // xplat and shared with NeoForge — do not re-hand-roll it here.
                    .then(ClientCommandActions.resetSubtree(
                            ClientCommands::literal, source -> source::sendFeedback))
                    .then(ClientCommands.literal("diag")
                            .executes(context -> {
                                ClientCommandActions.showDiagnostics(context.getSource()::sendFeedback);
                                return Command.SINGLE_SUCCESS;
                            })
                    )
                    .then(ClientCommands.literal("trace")
                            .executes(context -> {
                                ClientCommandActions.toggleTrace(context.getSource()::sendFeedback);
                                return Command.SINGLE_SUCCESS;
                            })
                    )
            );
        });
    }
}
