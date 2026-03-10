package dev.vox.lss.paper;

import dev.vox.lss.common.DiagnosticsFormatter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
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
            sender.sendMessage(line);
        }
    }

    private void showDiagnostics(CommandSender sender, PaperRequestProcessingService service) {
        var config = this.plugin.getLssConfig();
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
            sender.sendMessage(line);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("stats", "diag").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return Collections.emptyList();
    }
}
