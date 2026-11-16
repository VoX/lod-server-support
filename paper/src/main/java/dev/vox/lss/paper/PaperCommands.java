package dev.vox.lss.paper;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.DiagnosticsFormatter;
import dev.vox.lss.common.LSSConstants;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Bukkit diagnostics, operational store jobs and explicit YAML reload.
 * Settings publication and adoption feedback use the service owner on Paper/Folia;
 * display-only diagnostics read concurrent maps and stale-tolerant counters.
 */
public class PaperCommands implements CommandExecutor, TabCompleter {
    private LSSPaperPlugin plugin;
    private final Supplier<PaperRequestProcessingService> serviceSupplier;
    private final Supplier<PaperConfig> configSupplier;
    private dev.vox.lss.common.diagnostics.DiagnosticVersions diagnosticVersions = dev.vox.lss.common.diagnostics.DiagnosticVersions.unknown();

    public PaperCommands(LSSPaperPlugin plugin) {
        this(plugin::getRequestService, plugin::getLssConfig);
        this.plugin = plugin;
        diagnosticVersions = new dev.vox.lss.common.diagnostics.DiagnosticVersions(java.util.Map.of(
                dev.vox.lss.common.diagnostics.DiagnosticVersions.Component.LSS, plugin.getDescription().getVersion(),
                dev.vox.lss.common.diagnostics.DiagnosticVersions.Component.MINECRAFT, org.bukkit.Bukkit.getMinecraftVersion(),
                dev.vox.lss.common.diagnostics.DiagnosticVersions.Component.LOADER, "paper-" + org.bukkit.Bukkit.getBukkitVersion()));
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
        // A settings file that did not load at startup: every use of the command opens with
        // everything that is wrong in it (0.23.0 plan §1). Nothing when it loaded.
        var settings = configSupplier.get();
        if (settings != null && !settings.startupProblems().isEmpty())
            for (String line : dev.vox.lss.common.config.SettingsProblemText.serverBlock(
                    settings.settingsFileName(), settings.startupProblems())) sender.sendMessage(line);
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            // Bare /lsslod = help (v0.11.0 stage C) — shared builder, so Fabric/Paper
            // render identical lines; backfill verbs stay Fabric-only.
            for (var line : dev.vox.lss.common.CommandHelp.lines(label, false)) {
                sender.sendMessage(line);
            }
            return true;
        }

        var service = this.serviceSupplier.get();
        if (args[0].equalsIgnoreCase("reload")) {
            reload(sender, service);
            return true;
        }
        if (args[0].equalsIgnoreCase("diagnostics") && args.length == 2 && args[1].equalsIgnoreCase("export")) {
            if (service == null) exportDiagnostics(sender, null);
            else service.enqueueRuntimeTask(() -> exportDiagnostics(sender, service));
            return true;
        }
        if (service == null) {
            sender.sendMessage(Brand.shortName() + " LOD request processing is not active");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "stats" -> showStats(sender, service);
            case "diag" -> showDiagnostics(sender, service);
            case "store" -> storeCommand(sender, label, service, args);
            default -> sender.sendMessage("Usage: /" + label + " <stats|diag|diagnostics|store|reload|help>");
        }

        return true;
    }

    /** Owner-only immutable capture; package seam keeps export I/O out of policy-state tests. */
    dev.vox.lss.common.diagnostics.ServerStatusSnapshot captureDiagnostics(PaperRequestProcessingService service) {
        var config = configSupplier.get();
        return new dev.vox.lss.common.diagnostics.ServerStatusSnapshot(1, System.currentTimeMillis(),
                service != null, config.enabled(), config.enabled() && service != null
                        && service.getGenerationService() != null && service.getGenerationService().isAdmissionEnabled(),
                config.generationConfiguredForRestart(), config.lodDistanceChunks(),
                service == null ? 0 : service.getUptimeSeconds(),
                service == null ? 0 : service.getTickDiag().getTotalSectionsSent(),
                service == null ? 0 : service.getTickDiag().getTotalBytesSent(),
                service == null ? 0 : service.getTickDiag().getTotalWireBytesSent(),
                service == null ? 0 : service.getWindowBandwidthRate(), diagnosticVersions);
    }

    private void exportDiagnostics(CommandSender sender, PaperRequestProcessingService service) {
        var snapshot = captureDiagnostics(service);
        try {
            var job = dev.vox.lss.common.diagnostics.DiagnosticExport.submitServer(
                    java.nio.file.Path.of(Brand.lowerShortName() + "-diagnostics"), snapshot);
            sender.sendMessage("Diagnostics export queued: " + job.target()
                    + "; completion is reported in the server log.");
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            sender.sendMessage("Diagnostics exporter busy; retry after the current export.");
        }
    }

    private void reload(CommandSender sender, PaperRequestProcessingService service) {
        var config = configSupplier.get();
        dev.vox.lss.common.config.SettingsReload.Owner owner = action -> {
            if (service != null) return service.submitSettingsControl(() -> { action.run(); return null; });
            return controlWithoutService(action);
        };
        sender.sendMessage("Reading " + config.settingsPath() + "…");
        config.reloadOwned(owner, (previous, next, revision) -> service == null
                ? java.util.concurrent.CompletableFuture.completedFuture(null)
                : service.reconcileSettings(previous, next, revision)).whenComplete((result, failure) -> {
            var lines = failure == null
                    ? dev.vox.lss.common.config.ReloadFeedback.lines(config.settingsPath(), result)
                    : java.util.List.of("Reload failed: " + dev.vox.lss.common.config.SettingsReload.headline(failure));
            var report = new java.util.ArrayList<>(lines);
            // Everything wrong with the file, so it is fixed once (0.23.0 plan §1).
            if (failure != null)
                for (String line : dev.vox.lss.common.config.SettingsReload.problemLines(failure)) report.add("  " + line);
            if (failure == null && service != null
                    && result.status() != dev.vox.lss.common.config.SettingsReload.Status.UNCHANGED) {
                // This immutable receipt was captured on the pump before reconciliation
                // completed. Reading it cannot add a new, unbounded reply task at shutdown.
                var feedback = service.settingsFeedback();
                if (feedback != null && feedback.revision() == result.revision()) {
                    report.removeIf(line -> line.startsWith("Existing legacy clients may"));
                    if (feedback.legacyReconnects() > 0)
                        report.add(feedback.legacyReconnects() + " legacy client(s) need to reconnect for the new distance or generation policy.");
                    dev.vox.lss.common.config.ReloadFeedback.applyMapPolicyOutcome(report,
                            feedback.mapPolicyArmed(), feedback.mapPolicyRevoked());
                    report.addAll(feedback.draining());
                }
            }
            report.forEach(dev.vox.lss.common.LSSLogger::info);
            reply(sender, report);
        });
    }

    private java.util.concurrent.CompletableFuture<Void> controlWithoutService(Runnable action) {
        var result = new java.util.concurrent.CompletableFuture<Void>();
        if (plugin == null) {
            try { action.run(); result.complete(null); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        } else if (!plugin.isEnabled()) result.completeExceptionally(new IllegalStateException("Plugin disabled"));
        else {
            try {
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                    if (!plugin.isEnabled()) { result.completeExceptionally(new IllegalStateException("Plugin disabled")); return; }
                    try { action.run(); result.complete(null); }
                    catch (Throwable failure) { result.completeExceptionally(failure); }
                });
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        }
        return result;
    }

    private void reply(CommandSender sender, java.util.List<String> lines) {
        Runnable send = () -> lines.forEach(sender::sendMessage);
        if (plugin == null) { send.run(); return; }
        if (!plugin.isEnabled()) return; // the durable server log still contains the terminal result
        try {
            if (sender instanceof org.bukkit.entity.Player player)
                player.getScheduler().run(plugin, task -> send.run(), () -> {});
            else plugin.getServer().getGlobalRegionScheduler().execute(plugin, send);
        } catch (RuntimeException stopped) {
            dev.vox.lss.common.LSSLogger.warn("Reload reply retained in server log: " + stopped.getMessage());
        }
    }

    /** The store ops verbs (4-agent round R3: Paper shipped the store with no ops
     *  surface at all — and Paper is the platform whose staleness bound is the
     *  periodic resweep, so it needs the remediation lever MOST). Backfill verbs stay
     *  Fabric-only for now (recorded deferral: no Paper backfill wiring). Thread-safe
     *  from Folia's region-threaded dispatch: diagnostics reads are volatile gauges,
     *  invalidate-all is tombstones + a control-queue offer. */
    private void storeCommand(CommandSender sender, String label,
                              PaperRequestProcessingService service, String[] args) {
        var store = service.getLodStore();
        if (args.length >= 2 && args[1].equalsIgnoreCase("status")) {
            if (store == null) {
                sender.sendMessage("LOD store: off/unavailable");
                return;
            }
            sender.sendMessage("LOD store: " + store.diagnostics().formatToken(store.mode())
                    // Review B1: a latched store must LOOK dead in the triage tool —
                    // "latched" / "sweeping" / "ok", never a healthy token with frozen
                    // counters.
                    + " state=" + store.stateToken()
                    + (store instanceof dev.vox.lss.common.store.SqliteLodStore
                       ? " driver=private/" + dev.vox.lss.common.store.SqliteDriverRuntime.VERSION : "")
                    + " db=" + (store.diagnostics().getDbBytes() >> 20) + "MB wal="
                    + (store.diagnostics().getWalBytes() >> 20) + "MB sweep_drops="
                    + store.diagnostics().getSweepDrops()
                    // The one-shot cap log (§2) points here — the ongoing capped
                    // steady-state must stay diagnosable without any log line.
                    + " evicted=" + store.diagnostics().getSqlEvictions()
                    // C4: background-migration progress (empty once every row is v20).
                    + store.migrationStatusToken());
        } else if (args.length >= 3 && args[1].equalsIgnoreCase("invalidate")
                && args[2].equalsIgnoreCase("all")) {
            if (store == null) {
                sender.sendMessage("LOD store not active");
                return;
            }
            if (!service.invalidateStoreAllDimensions()) {
                // Unreachable since the in-memory tier's deletion (a non-null store is
                // always SQLite); defensive armor with an honest message.
                sender.sendMessage("Invalidate-all requires the persistent SQLite store engine");
                return;
            }
            sender.sendMessage("LOD store: dropping all rows (background) — re-warms from serves");
        } else {
            sender.sendMessage("Usage: /" + label + " store <status|invalidate all>");
        }
    }

    private void showStats(CommandSender sender, PaperRequestProcessingService service) {
        var players = service.getPlayers();
        if (players.isEmpty()) {
            sender.sendMessage("No players connected with " + Brand.shortName());
            return;
        }

        sender.sendMessage("=== " + Brand.shortName() + " LOD Request Stats ===");
        for (var state : players.values()) {
            sender.sendMessage(DiagnosticsFormatter.formatStatsLine(state));
        }
    }

    private void showDiagnostics(CommandSender sender, PaperRequestProcessingService service) {
        var config = this.configSupplier.get();
        var genService = service.getGenerationService();
        var data = DiagnosticsFormatter.collectDiagData(
                config.enabled(), config.lodDistanceChunks(),
                config.bytesPerSecondPerPlayer(), config.bytesPerSecondGlobal(),
                config.sendQueueLimitPerPlayer(),
                service.getUptimeSeconds(), service.getTickDiagnostics(), service.getWindowBandwidthRate(),
                service.getTickDiag().getTotalSectionsSent(), service.getTickDiag().getTotalBytesSent(),
                service.getTickDiag().getTotalWireBytesSent(),
                service.getOffThreadProcessor().getDiagnostics(), service.getDiskReader(),
                service.getBandwidthLimiter(),
                config.enableChunkGeneration() && genService != null ? genService.getDiagnostics() : null,
                // LIVE store mode, not the config's ask (review MINOR-3): a codec-probe
                // degrade renders store=unavailable, never a lying store=memory h=0.
                // enabled=false is an OFF store, not a degraded one — without that term
                // a disabled server rendered store=unavailable, which reads as the
                // degraded-boot state (codec or SQLite-init failure), sending admins
                // after a zstd problem that does not exist (v0.9.0 final review).
                !config.enabled()
                        || dev.vox.lss.common.store.LodStoreMode.normalize(config.lodStore())
                                == dev.vox.lss.common.store.LodStoreMode.OFF
                        ? dev.vox.lss.common.store.LodStoreMode.OFF
                        : (service.getLodStore() != null ? service.getLodStore().mode() : null),
                service.getOffThreadProcessor().getStoreDiagnostics(),
                service.getPlayers().values()
        ).withV16Line(service.getV16CompatManager().diagLineOrNull())
                .withV18Line(service.getDialectTracker().diagLine())
                .withFarPlayersLine(farPlayersDiagLineOrNull(service))
                .withSummaryLine(service.getRegionSummaries() == null ? null
                        : service.getRegionSummaries().diagnostics().diagLineOrNull())
                .withYieldLine(DiagnosticsFormatter.yieldDiagLineOrNull(
                        config.lodYieldsToVanillaTransport(), service.getTickDiag()))
                .withXrayLine(xrayDiagLine())
                // denied= counts PERMISSION memo entries only; map-policy refusals have
                // their own MapPolicy: line (map-policy plan A3.5).
                .withGateLine(!config.requireServicePermission() ? null
                        : "Gate: requireServicePermission=on denied="
                                + service.getServiceGateState().deniedCount(
                                        dev.vox.lss.common.ServiceGateState.REASON_PERMISSION)
                                + " provider=bukkit")
                // exempt= is the sweep's cached count (map-ingestion permission plan §3.5).
                .withMapPolicyLine(!config.requestNoMapIngestion() ? null
                        : dev.vox.lss.common.MapPolicy.diagLineOrNull(true,
                                service.getServiceGateState().deniedCount(
                                        dev.vox.lss.common.ServiceGateState.REASON_MAP_POLICY),
                                service.getServiceGateState().mapPolicyExemptCount(), null));

        for (var line : DiagnosticsFormatter.formatDiagnostics(data)) {
            sender.sendMessage(line);
        }
        String offLine = service.unservedWorldsDiagLineOrNull();
        if (offLine != null) sender.sendMessage(offLine);
        // Failures the mod survived and counted. Absent when there were none.
        String recovered = dev.vox.lss.common.FaultCounters.global().diagLine();
        if (!recovered.isEmpty()) sender.sendMessage(recovered);
    }

    private static String xrayDiagLine() {
        var manager = PaperXrayMaskManager.current();
        return manager != null ? manager.diagLine() : "Xray: active=off, masked_sections=0";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("stats", "diag", "diagnostics", "store", "reload", "help").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("store")) {
            return List.of("status", "invalidate").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("store")
                && args[1].equalsIgnoreCase("invalidate")) {
            return List.of("all");
        }
        return Collections.emptyList();
    }

    /** Present ONLY once far players have been touched (a subscriber exists or frames
     *  were ever sent) — inert servers render nothing, so soak/benchmark diag output is
     *  byte-unchanged (E1 baseline neutrality). */
    private static String farPlayersDiagLineOrNull(
            PaperRequestProcessingService service) {
        var fp = service.getFarPlayerService();
        if (fp == null) return null; // partial rigs (mocked service seams)
        return fp.subscriberCount() > 0 || fp.rosterFramesSent() > 0 || fp.optedOut() > 0 ? fp.diagLine() : null;
    }
}
