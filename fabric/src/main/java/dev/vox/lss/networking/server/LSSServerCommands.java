package dev.vox.lss.networking.server;

import dev.vox.lss.common.DiagnosticsFormatter;
import dev.vox.lss.config.LSSServerConfig;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

import java.util.ArrayList;

class LSSServerCommands {
    public static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                    Commands.literal("lsslod")
                            .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                            .then(Commands.literal("stats")
                                    .executes(ctx -> showStats(ctx.getSource()))
                            )
                            .then(Commands.literal("diag")
                                    .executes(ctx -> showDiagnostics(ctx.getSource()))
                            )
            );
        });
    }

    private static int showStats(CommandSourceStack source) {
        var service = LSSServerNetworking.getRequestService();
        if (service == null) {
            source.sendFailure(Component.literal("LSS LOD request processing is not active"));
            return 0;
        }

        var players = service.getPlayers();
        if (players.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No players connected with LSS"), false);
            return 1;
        }

        source.sendSuccess(() -> Component.literal("=== LSS LOD Request Stats ==="), false);
        for (var entry : players.entrySet()) {
            var state = entry.getValue();
            var player = state.getPlayer();

            String line = String.format(
                    "%s: handshake=%s, sent=%d sections (%s), pending_sync=%d, pending_gen=%d, send_queue=%d, requests=%d",
                    player.getName().getString(),
                    state.hasCompletedHandshake() ? "yes" : "no",
                    state.getTotalSectionsSent(),
                    DiagnosticsFormatter.formatBytes(state.getTotalBytesSent()),
                    state.getPendingSyncCount(),
                    state.getPendingGenerationCount(),
                    state.getSendQueueSize(),
                    state.getTotalRequestsReceived()
            );
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int showDiagnostics(CommandSourceStack source) {
        var service = LSSServerNetworking.getRequestService();
        if (service == null) {
            source.sendFailure(Component.literal("LSS LOD request processing is not active"));
            return 0;
        }

        var config = LSSServerConfig.CONFIG;
        long uptimeSec = service.getUptimeSeconds();
        var diag = service.getOffThreadProcessor().getDiagnostics();
        var diskReader = service.getDiskReader();
        long diskCompleted = diskReader != null ? diskReader.getDiag().getSuccessfulReadCount() : 0;
        var genService = service.getGenerationService();
        var bwLimiter = service.getBandwidthLimiter();

        long totalSent = 0;
        long totalBytes = 0;
        var players = new ArrayList<DiagnosticsFormatter.PlayerDiag>();
        for (var entry : service.getPlayers().entrySet()) {
            var state = entry.getValue();
            totalSent += state.getTotalSectionsSent();
            totalBytes += state.getTotalBytesSent();
            players.add(new DiagnosticsFormatter.PlayerDiag(
                    state.getPlayer().getName().getString(),
                    state.getSendQueueSize(), config.sendQueueLimitPerPlayer,
                    state.getPendingSyncCount(), state.getPendingGenerationCount(),
                    state.getTotalSectionsSent(), state.getTotalBytesSent()
            ));
        }

        var data = new DiagnosticsFormatter.DiagData(
                config.enabled, config.lodDistanceChunks,
                config.bytesPerSecondLimitPerPlayer, config.bytesPerSecondLimitGlobal,
                uptimeSec, totalSent, totalBytes,
                diag.getTotalInMemory(), diag.getTotalUpToDate(), diag.getTotalGenDrained(),
                diskCompleted,
                service.getTickDiagnostics(),
                diskReader != null ? diskReader.getDiagnostics() : "N/A",
                genService != null ? genService.getDiagnostics() : null, genService != null,
                bwLimiter.getTotalBytesSent(),
                service.getWindowBandwidthRate(),
                players
        );

        for (var line : DiagnosticsFormatter.formatDiagnostics(data)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }
}
