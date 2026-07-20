package dev.vox.lss.paper;

import dev.vox.lss.common.DiagnosticsFormatter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Bukkit command handler for /lsslod stats and /lsslod diag.
 *
 * <p>On Folia, command dispatch is region-threaded (player senders) or global-threaded
 * (console), so these handlers read pump-owned state cross-thread. Every read on this path
 * is a concurrent structure (the players CHM) or a stale-tolerable primitive (volatile
 * counters, plain int/long gauges like the generation active-count and the
 * TickDiagnostics/SharedBandwidthLimiter fields) — audited 2026-07-02; nothing iterates a
 * non-concurrent collection off the pump.
 */
public class PaperCommands implements CommandExecutor, TabCompleter {
    private final Supplier<PaperRequestProcessingService> serviceSupplier;
    private final Supplier<PaperConfig> configSupplier;

    public PaperCommands(LSSPaperPlugin plugin) {
        this(plugin::getRequestService, plugin::getLssConfig);
    }

    // Package-visible seam: lets T1 tests drive the command paths without a JavaPlugin
    // instance. Suppliers preserve the late binding of plugin.getRequestService().
    PaperCommands(Supplier<PaperRequestProcessingService> serviceSupplier,
                  Supplier<PaperConfig> configSupplier) {
        this.serviceSupplier = serviceSupplier;
        this.configSupplier = configSupplier;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /lsslod <stats|diag>");
            return true;
        }

        var service = this.serviceSupplier.get();
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
        for (var state : players.values()) {
            sender.sendMessage(DiagnosticsFormatter.formatStatsLine(state));
        }
    }

    private void showDiagnostics(CommandSender sender, PaperRequestProcessingService service) {
        var config = this.configSupplier.get();
        var genService = service.getGenerationService();
        var data = DiagnosticsFormatter.collectDiagData(
                config.enabled, config.lodDistanceChunks,
                config.bytesPerSecondLimitPerPlayer, config.bytesPerSecondLimitGlobal,
                config.sendQueueLimitPerPlayer,
                service.getUptimeSeconds(), service.getTickDiagnostics(), service.getWindowBandwidthRate(),
                service.getOffThreadProcessor().getDiagnostics(), service.getDiskReader(),
                service.getBandwidthLimiter(),
                genService != null ? genService.getDiagnostics() : null,
                service.getPlayers().values()
        ).withV16Line(service.getV16CompatManager().diagLineOrNull());

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
