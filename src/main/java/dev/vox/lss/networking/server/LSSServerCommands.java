package dev.vox.lss.networking.server;

import dev.vox.lss.LSSLogger;
import dev.vox.lss.config.LSSServerConfig;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

public class LSSServerCommands {
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
                    "%s: handshake=%s, sent=%d sections (%s), batches=%d, pending_disk=%d, send_queue=%d, requests=%d, rejected=%d",
                    player.getName().getString(),
                    state.hasCompletedHandshake() ? "yes" : "no",
                    state.getTotalSectionsSent(),
                    LSSLogger.formatBytes(state.getTotalBytesSent()),
                    state.getPendingBatchCount(),
                    state.getPendingDiskReadCount(),
                    state.getSendQueueSize(),
                    state.getTotalRequestsReceived(),
                    state.getTotalRequestsRejected()
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
        source.sendSuccess(() -> Component.literal("=== LSS LOD Diagnostics ==="), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "Config: enabled=%s, diskReading=%s, lodDist=%d, maxSections/tick=%d, maxBytes/s/player=%d, globalBytes/s=%d, maxBatch=%d, maxPending=%d",
                config.enabled, config.enableDiskReading, config.lodDistanceChunks,
                config.maxSectionsPerTickPerPlayer, config.maxBytesPerSecondPerPlayer,
                config.maxBytesPerSecondGlobal, config.maxRequestsPerBatch, config.maxPendingRequestsPerPlayer
        )), false);

        var diskReader = service.getDiskReader();
        if (diskReader != null) {
            source.sendSuccess(() -> Component.literal("DiskReader: " + diskReader.getDiagnostics()), false);
        } else {
            source.sendSuccess(() -> Component.literal("DiskReader: disabled"), false);
        }

        var genService = service.getGenerationService();
        if (genService != null) {
            source.sendSuccess(() -> Component.literal("Generation: " + genService.getDiagnostics()), false);
        } else {
            source.sendSuccess(() -> Component.literal("Generation: disabled"), false);
        }

        source.sendSuccess(() -> Component.literal("Tick: " + service.getTickDiagnostics()), false);

        for (var entry : service.getPlayers().entrySet()) {
            var state = entry.getValue();
            source.sendSuccess(() -> Component.literal(String.format("  %s: send_queue=%d, pending_disk=%d, pending_gen=%d, batches=%d, total_sent=%d (%s)",
                    state.getPlayer().getName().getString(),
                    state.getSendQueueSize(),
                    state.getPendingDiskReadCount(),
                    state.getPendingGenerationCount(),
                    state.getPendingBatchCount(),
                    state.getTotalSectionsSent(),
                    LSSLogger.formatBytes(state.getTotalBytesSent())
            )), false);
        }

        return 1;
    }
}
