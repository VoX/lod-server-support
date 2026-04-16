package dev.vox.lss.networking.client;

import com.mojang.brigadier.Command;
import dev.vox.lss.common.DiagnosticsFormatter;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public class LSSClientCommands {
    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("lss")
                    .then(ClientCommands.literal("clearcache")
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
                    .then(ClientCommands.literal("diag")
                            .executes(context -> {
                                showDiagnostics(context.getSource());
                                return Command.SINGLE_SUCCESS;
                            })
                    )
            );
        });
    }

    private static void showDiagnostics(FabricClientCommandSource source) {
        var manager = LSSClientNetworking.getRequestManager();
        if (manager == null || !LSSClientNetworking.isServerEnabled()) {
            source.sendFeedback(Component.literal("LSS is not active on this server").withStyle(ChatFormatting.RED));
            return;
        }

        source.sendFeedback(Component.literal("=== LSS Client Diagnostics ===").withStyle(ChatFormatting.GOLD));

        // Connection line
        int serverDist = LSSClientNetworking.getServerLodDistance();
        int effectiveDist = manager.getEffectiveLodDistanceChunks();
        source.sendFeedback(Component.literal(String.format(
                "Connection: server_lod_dist=%d, effective_dist=%d",
                serverDist, effectiveDist
        )).withStyle(ChatFormatting.GRAY));

        // Throughput line
        long received = LSSClientNetworking.getColumnsReceived();
        long bytes = LSSClientNetworking.getBytesReceived();
        long dropped = LSSClientNetworking.getColumnsDropped();
        long startMs = LSSClientNetworking.getConnectionStartMs();
        long uptimeSec = startMs > 0 ? (System.currentTimeMillis() - startMs) / 1000 : 0;
        source.sendFeedback(Component.literal(String.format(
                "Throughput: received=%d (%s), dropped=%d, recv_rate=%s/s, req_rate=%s/s, uptime=%s",
                received, DiagnosticsFormatter.formatBytes(bytes), dropped,
                DiagnosticsFormatter.formatRate(manager.getReceiveRate()), DiagnosticsFormatter.formatRate(manager.getRequestRate()),
                DiagnosticsFormatter.formatUptime(uptimeSec)
        )).withStyle(ChatFormatting.GRAY));

        // Queue line
        int queued = LSSClientNetworking.getQueuedColumnCount();
        source.sendFeedback(Component.literal(String.format(
                "Queue: queued=%d/%d",
                queued, ClientColumnProcessor.MAX_QUEUED_COLUMNS
        )).withStyle(ChatFormatting.GRAY));

        // Columns line
        int receivedCols = manager.getReceivedColumnCount();
        int empty = manager.getEmptyColumnCount();
        int dirty = manager.getDirtyColumnCount();
        source.sendFeedback(Component.literal(String.format(
                "Columns: received=%d, empty=%d, dirty=%d",
                receivedCols, empty, dirty
        )).withStyle(ChatFormatting.GRAY));

        // Responses line
        source.sendFeedback(Component.literal(String.format(
                "Responses: columns=%d, up_to_date=%d, not_generated=%d, rate_limited=%d",
                manager.getTotalColumnsReceived(), manager.getTotalUpToDate(),
                manager.getTotalNotGenerated(), manager.getTotalRateLimited()
        )).withStyle(ChatFormatting.GRAY));

        // Requests line
        source.sendFeedback(Component.literal(String.format(
                "Requests: send_cycles=%d, total_requested=%d",
                manager.getTotalSendCycles(), manager.getTotalPositionsRequested()
        )).withStyle(ChatFormatting.GRAY));

        // Scan line
        int confirmedRing = manager.getConfirmedRing();
        int scanRing = manager.getScanRing();
        int maxRing = manager.getEffectiveLodDistanceChunks();
        source.sendFeedback(Component.literal(String.format(
                "Scan: confirmed=%d, scanning=%d/%d, missing_vanilla=%d",
                confirmedRing, scanRing, maxRing, manager.getMissingVanillaChunks()
        )).withStyle(ChatFormatting.GRAY));

        // Budget line
        int budget = manager.getLastBudget();
        int syncQueued = manager.getLastSyncQueued();
        int genQueued = manager.getLastGenQueued();
        source.sendFeedback(Component.literal(String.format(
                "Budget: used=%d/%d (sync=%d, gen=%d), queue=%d",
                syncQueued + genQueued, budget, syncQueued, genQueued,
                manager.getQueueRemaining()
        )).withStyle(ChatFormatting.GRAY));
    }
}
