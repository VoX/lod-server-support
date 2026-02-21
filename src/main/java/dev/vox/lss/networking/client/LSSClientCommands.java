package dev.vox.lss.networking.client;

import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.Component;

public class LSSClientCommands {
    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("lss")
                    .then(ClientCommandManager.literal("clearcache")
                            .executes(context -> {
                                var manager = LSSClientNetworking.getRequestManager();
                                if (manager != null) {
                                    manager.flushCache();
                                    context.getSource().sendFeedback(Component.literal(
                                            "LSS column cache cleared for current server. Chunks will be re-requested."));
                                } else {
                                    ColumnCacheStore.clearAll();
                                    context.getSource().sendFeedback(Component.literal(
                                            "LSS column cache cleared for all servers."));
                                }
                                return Command.SINGLE_SUCCESS;
                            })
                    )
            );
        });
    }
}
