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
 * Bukkit command handler for /lsslod stats and /lsslod diag.
 *
 * <p>On Folia, command dispatch is region-threaded (player senders) or global-threaded
 * (console), so these handlers read pump-owned state cross-thread. Every read on this path
 * is a concurrent structure (the players CHM) or a stale-tolerable primitive (volatile
 * counters, plain int/long gauges like the generation active-count and the
 * TickDiagnostics/SharedBandwidthLimiter fields, and the plain DOUBLE config fields
 * mbPerSecondLimit* — JLS §17.7 permits a torn 64-bit read there, garbling at worst one
 * diag number concurrent with a /lsslod set; accepted, Folia review 2026-08-27) —
 * audited 2026-07-02; nothing iterates a non-concurrent collection off the pump.
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
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            // Bare /lsslod = help (v0.11.0 stage C) — shared builder, so Fabric/Paper
            // render identical lines; backfill verbs stay Fabric-only.
            for (var line : dev.vox.lss.common.CommandHelp.lines(label, false)) {
                sender.sendMessage(line);
            }
            return true;
        }

        var service = this.serviceSupplier.get();
        if (service == null) {
            sender.sendMessage(Brand.shortName() + " LOD request processing is not active");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "stats" -> showStats(sender, service);
            case "diag" -> showDiagnostics(sender, service);
            case "store" -> storeCommand(sender, label, service, args);
            case "set" -> setCommand(sender, label, service, args);
            default -> sender.sendMessage("Usage: /" + label + " <stats|diag|store|set|help>");
        }

        return true;
    }

    /** The /lsslod set apply path (v0.11.0 stage C). Unknown-key and usage errors reply
     *  inline from the command thread; VALUE-parse failures reply from inside the pump
     *  task (the parse happens in applyAndPersist). The MUTATION (per-key clamp →
     *  assign once → validate → save → reply, plus the lodDistance re-push) is
     *  marshaled through the pump via enqueueRuntimeTask — Folia dispatches commands on
     *  region threads, and the re-push must enumerate dialects only AFTER the lifecycle
     *  mailbox drain (the SET review's ordering MAJOR: a flip-pending player must never
     *  be pushed a v20 config). Command-block senders on Folia may see the reply land
     *  after the block's tick window (the message is delivered, possibly dropped to
     *  the void for an unloaded block) — accepted: admins drive this from console. */
    private void setCommand(CommandSender sender, String label,
                            PaperRequestProcessingService service, String[] args) {
        var config = this.configSupplier.get();
        if (args.length == 1) {
            sender.sendMessage("Runtime-settable keys (applied + persisted to "
                    + dev.vox.lss.common.Brand.lowerShortName() + "-server-config.json):");
            for (var line : dev.vox.lss.common.config.RuntimeSettings.listLines(config)) {
                sender.sendMessage("  " + line);
            }
            return;
        }
        var key = dev.vox.lss.common.config.RuntimeSettings.byName(args[1]);
        if (key == null) {
            sender.sendMessage("Unknown key '" + args[1] + "'. Settable: "
                    + String.join(", ", dev.vox.lss.common.config.RuntimeSettings.keyNames()));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("Usage: /" + label + " set " + key.name() + " <value>");
            return;
        }
        String rawValue = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        service.enqueueRuntimeTask(() -> {
            String before = key.current().apply(config);
            String effective;
            try {
                effective = dev.vox.lss.common.config.RuntimeSettings
                        .applyAndPersist(config, key, rawValue);
            } catch (IllegalArgumentException e) {
                sender.sendMessage(key.name() + ": " + e.getMessage());
                return;
            }
            String repushNote = "";
            if (key.name().equals("lodDistanceChunks") && !effective.equals(before)) {
                int[] counts = service.repushSessionConfig();
                repushNote = "; re-pushed to " + counts[0] + " client(s)"
                        + (counts[1] > 0 ? " (" + counts[1] + " legacy update on rejoin)" : "");
            }
            sender.sendMessage(key.name() + " = " + effective
                    + dev.vox.lss.common.config.RuntimeSettings.clampedSuffix(effective, rawValue)
                    + " — " + key.applyNote() + repushNote);
        });
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
                config.enabled, config.lodDistanceChunks,
                config.bytesPerSecondPerPlayer(), config.bytesPerSecondGlobal(),
                config.sendQueueLimitPerPlayer,
                service.getUptimeSeconds(), service.getTickDiagnostics(), service.getWindowBandwidthRate(),
                service.getTickDiag().getTotalSectionsSent(), service.getTickDiag().getTotalBytesSent(),
                service.getTickDiag().getTotalWireBytesSent(),
                service.getOffThreadProcessor().getDiagnostics(), service.getDiskReader(),
                service.getBandwidthLimiter(),
                genService != null ? genService.getDiagnostics() : null,
                // LIVE store mode, not the config's ask (review MINOR-3): a codec-probe
                // degrade renders store=unavailable, never a lying store=memory h=0.
                // enabled=false is an OFF store, not a degraded one — without that term
                // a disabled server rendered store=unavailable, which reads as the
                // degraded-boot state (codec or SQLite-init failure), sending admins
                // after a zstd problem that does not exist (v0.9.0 final review).
                !config.enabled
                        || dev.vox.lss.common.store.LodStoreMode.normalize(config.lodStore)
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
                        config.lodYieldsToVanillaTransport, service.getTickDiag()))
                .withXrayLine(xrayDiagLine())
                .withGateLine(!config.requireServicePermission ? null
                        : "Gate: requireServicePermission=on denied="
                                + service.getServiceGateState().deniedCount()
                                + " provider=bukkit");

        for (var line : DiagnosticsFormatter.formatDiagnostics(data)) {
            sender.sendMessage(line);
        }
    }

    private static String xrayDiagLine() {
        var manager = PaperXrayMaskManager.current();
        return manager != null ? manager.diagLine() : "Xray: active=off, masked_sections=0";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("stats", "diag", "store", "set", "help").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("store")) {
            return List.of("status", "invalidate").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            // Registry-derived so completion cannot drift from the settable set.
            return dev.vox.lss.common.config.RuntimeSettings.keyNames().stream()
                    .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
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
        return fp.subscriberCount() > 0 || fp.rosterFramesSent() > 0 ? fp.diagLine() : null;
    }
}
