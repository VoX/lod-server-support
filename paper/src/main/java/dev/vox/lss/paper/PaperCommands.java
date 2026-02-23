package dev.vox.lss.paper;

import dev.vox.lss.common.LSSLogger;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Bukkit command handler for /lsslod stats and /lsslod diag.
 */
public class PaperCommands implements CommandExecutor, TabCompleter {
    private final LSSPaperPlugin plugin;

    public PaperCommands(LSSPaperPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /lsslod <stats|diag>");
            return true;
        }

        var service = this.plugin.getRequestService();
        if (service == null) {
            sender.sendMessage("LSS LOD request processing is not active");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "stats" -> showStats(sender, service);
            case "diag" -> showDiagnostics(sender, service);
            default -> sender.sendMessage("Usage: /lsslod <stats|diag>");
        }

        return true;
    }

    private void showStats(CommandSender sender, PaperRequestProcessingService service) {
        var players = service.getPlayers();
        if (players.isEmpty()) {
            sender.sendMessage("No players connected with LSS");
            return;
        }

        sender.sendMessage("=== LSS LOD Request Stats ===");
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
            sender.sendMessage(line);
        }
    }

    private void showDiagnostics(CommandSender sender, PaperRequestProcessingService service) {
        var config = this.plugin.getLssConfig();
        sender.sendMessage("=== LSS LOD Diagnostics ===");
        sender.sendMessage(String.format(
                "Config: enabled=%s, diskReading=%s, lodDist=%d, maxSections/tick=%d, maxBytes/s/player=%d, globalBytes/s=%d, maxBatch=%d, maxPending=%d",
                config.enabled, config.enableDiskReading, config.lodDistanceChunks,
                config.maxSectionsPerTickPerPlayer, config.maxBytesPerSecondPerPlayer,
                config.maxBytesPerSecondGlobal, config.maxRequestsPerBatch, config.maxPendingRequestsPerPlayer
        ));

        var diskReader = service.getDiskReader();
        if (diskReader != null) {
            sender.sendMessage("DiskReader: " + diskReader.getDiagnostics());
        } else {
            sender.sendMessage("DiskReader: disabled");
        }

        var genService = service.getGenerationService();
        if (genService != null) {
            sender.sendMessage("Generation: " + genService.getDiagnostics());
        } else {
            sender.sendMessage("Generation: disabled");
        }

        sender.sendMessage("Tick: " + service.getTickDiagnostics());

        for (var entry : service.getPlayers().entrySet()) {
            var state = entry.getValue();
            sender.sendMessage(String.format("  %s: send_queue=%d, pending_disk=%d, pending_gen=%d, batches=%d, total_sent=%d (%s)",
                    state.getPlayer().getName().getString(),
                    state.getSendQueueSize(),
                    state.getPendingDiskReadCount(),
                    state.getPendingGenerationCount(),
                    state.getPendingBatchCount(),
                    state.getTotalSectionsSent(),
                    LSSLogger.formatBytes(state.getTotalBytesSent())
            ));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("stats", "diag").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return Collections.emptyList();
    }
}
