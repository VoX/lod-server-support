package dev.vox.lss.networking.client;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.ExtraCommandRootGuard;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * Fabric client command registration. The tree and the bodies live in the source-neutral
 * {@link ClientCommandActions} (xplat, shared with NeoForge); this class registers that tree
 * under every name the build answers to, against Fabric's client source.
 */
public class LSSClientCommands {
    /** Fabric merges client commands into the server's command list object, so the extra
     *  name's "does the server have this command?" is answered once per list. */
    private static final ExtraCommandRootGuard EXTRA_ROOT = new ExtraCommandRootGuard();

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // The names are branded (lss / vss); they are LOCAL client commands and never
            // cross the wire, so they do not affect LSS<->VSS compatibility.
            for (String root : Brand.clientCommands()) {
                var tree = ClientCommandActions.<FabricClientCommandSource>tree(root, ClientCommands::literal,
                        source -> source::sendFeedback);
                // 0.23.0 plan §4: the Voxy Server Side client's extra /lss. A client command
                // wins over a server command of the same name, so the extra name steps aside
                // while the server has one: a root that fails its requirement reads as unknown
                // here and the command goes to the server.
                if (!root.equals(Brand.clientCommand())) {
                    tree.requires(source -> EXTRA_ROOT.allows(ClientCommandActions.commandList(),
                            ClientCommandActions.commandListHas(root)));
                }
                dispatcher.register(tree);
            }
        });
    }
}
