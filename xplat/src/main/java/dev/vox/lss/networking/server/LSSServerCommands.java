package dev.vox.lss.networking.server;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.DiagnosticsFormatter;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.config.LSSServerConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;


/** The shared /lsslod tree + handlers (xplat since N-2): each loader registers it
 *  through its own command event — Fabric via CommandRegistrationCallback in
 *  LSSServerNetworking.init(), NeoForge via RegisterCommandsEvent. Handlers read the
 *  per-loader {@code LSSServerNetworking.getRequestService()} holder (same-FQN twin
 *  contract, plan §1.1). */
public class LSSServerCommands {
    public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
            dispatcher.register(
                    Commands.literal(Brand.serverCommand())
                            .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                            // Bare root = help (v0.11.0 stage C; a bare /lsslod used to
                            // be a Brigadier parse error). Review-verified safe: the
                            // .requires gate sits on THIS root literal, so a
                            // permissionless parse still consumes zero nodes.
                            .executes(ctx -> showHelp(ctx.getSource()))
                            .then(Commands.literal("help")
                                    .executes(ctx -> showHelp(ctx.getSource()))
                            )
                            .then(Commands.literal("stats")
                                    .executes(ctx -> showStats(ctx.getSource()))
                            )
                            .then(Commands.literal("diag")
                                    .executes(ctx -> showDiagnostics(ctx.getSource()))
                            )
                            .then(Commands.literal("set")
                                    .executes(ctx -> listSettings(ctx.getSource()))
                                    .then(Commands.argument("key",
                                                    com.mojang.brigadier.arguments.StringArgumentType.word())
                                            .suggests((c, b) -> {
                                                for (var name : dev.vox.lss.common.config.RuntimeSettings.keyNames()) {
                                                    if (name.toLowerCase().startsWith(b.getRemainingLowerCase())) {
                                                        b.suggest(name);
                                                    }
                                                }
                                                return b.buildFuture();
                                            })
                                            .then(Commands.argument("value",
                                                            com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                                    .executes(ctx -> setSetting(ctx.getSource(),
                                                            com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "key"),
                                                            com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "value")))))
                            )
                            .then(Commands.literal("store")
                                    .then(Commands.literal("status")
                                            .executes(ctx -> storeStatus(ctx.getSource())))
                                    .then(Commands.literal("invalidate")
                                            .then(Commands.literal("all")
                                                    .executes(ctx -> storeInvalidateAll(ctx.getSource()))))
                                    .then(Commands.literal("backfill")
                                            .then(Commands.literal("start")
                                                    .executes(ctx -> backfill(ctx.getSource(), "start")))
                                            .then(Commands.literal("stop")
                                                    .executes(ctx -> backfill(ctx.getSource(), "stop")))
                                            .then(Commands.literal("status")
                                                    .executes(ctx -> backfill(ctx.getSource(), "status")))
                                    )
                            )
            );
    }

    private static int showHelp(CommandSourceStack source) {
        for (var line : dev.vox.lss.common.CommandHelp.lines(Brand.serverCommand(), true)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int listSettings(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "Runtime-settable keys (applied + persisted to "
                        + dev.vox.lss.common.Brand.lowerShortName() + "-server-config.json):"), false);
        for (var line : dev.vox.lss.common.config.RuntimeSettings.listLines(LSSServerConfig.CONFIG)) {
            source.sendSuccess(() -> Component.literal("  " + line), false);
        }
        return 1;
    }

    /** The /lsslod set apply path (v0.11.0 stage C): parse → per-key clamp → assign once
     *  → validate() → save() → reply. Fabric commands run on the SERVER thread (= tick
     *  thread), so the mutation and the re-push are direct — the tick-poll consumers
     *  pick the change up at the next tick. */
    private static int setSetting(CommandSourceStack source, String keyName, String rawValue) {
        var key = dev.vox.lss.common.config.RuntimeSettings.byName(keyName);
        if (key == null) {
            source.sendFailure(Component.literal("Unknown key '" + keyName + "'. Settable: "
                    + String.join(", ", dev.vox.lss.common.config.RuntimeSettings.keyNames())));
            return 0;
        }
        var config = LSSServerConfig.CONFIG;
        dev.vox.lss.common.config.RuntimeSettings.ApplyResult result;
        try {
            result = dev.vox.lss.common.config.RuntimeSettings
                    .applyAndPersist(config, key, rawValue);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(keyName + ": " + e.getMessage()));
            return 0;
        }
        String repushNote = "";
        // Re-push keyed on the MUTATION, not a reply-string diff: a per-world set leaves
        // the scalar unchanged, so a string compare would miss it (and never re-push).
        if (result.repush()) {
            var service = LSSServerNetworking.getRequestService();
            if (service != null) {
                int[] counts = service.repushSessionConfig();
                repushNote = "; re-pushed to " + counts[0] + " client(s)"
                        + (counts[1] > 0 ? " (" + counts[1] + " legacy update on rejoin)" : "");
            }
        }
        String reply = keyName + " = "
                + dev.vox.lss.common.config.RuntimeSettings.renderReplyValue(key, result, rawValue)
                + " — " + key.applyNote() + repushNote;
        source.sendSuccess(() -> Component.literal(reply), true);
        return 1;
    }

    /** Phase 5 ops: one-line store health for "are LODs stale?" triage. */
    private static int storeStatus(CommandSourceStack source) {
        var service = LSSServerNetworking.getRequestService();
        if (service == null) {
            source.sendFailure(Component.literal(Brand.shortName() + " LOD request processing is not active"));
            return 0;
        }
        var store = service.getLodStore();
        if (store == null) {
            source.sendSuccess(() -> Component.literal("LOD store: off/unavailable"), false);
            return 1;
        }
        String line = "LOD store: " + store.diagnostics().formatToken(store.mode())
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
                + store.migrationStatusToken();
        var backfill = service.getStoreBackfill();
        String bf = backfill == null ? "" : " | backfill: " + backfill.statusLine();
        source.sendSuccess(() -> Component.literal(line + bf), false);
        return 1;
    }

    /** Phase 5 ops: the admin remediation lever for "LODs look stale" — tombstones and
     *  deletes every stored row in every dimension the service knows (backfill
     *  done-marks reset with them); the store re-warms from serves (and the backfill,
     *  if enabled). The timestamp cache is deliberately NOT touched — its stamps
     *  describe REGION truth, not store contents (see
     *  RequestProcessingService.invalidateStoreAllDimensions). */
    private static int storeInvalidateAll(CommandSourceStack source) {
        var service = LSSServerNetworking.getRequestService();
        if (service == null || service.getLodStore() == null) {
            source.sendFailure(Component.literal("LOD store not active"));
            return 0;
        }
        if (!service.invalidateStoreAllDimensions()) {
            // Unreachable since the in-memory tier's deletion (a non-null store is always
            // SQLite); kept as defensive armor with an honest message.
            source.sendFailure(Component.literal(
                    "Invalidate-all requires the persistent SQLite store engine"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "LOD store: dropping all rows (background) — re-warms from serves"), true);
        return 1;
    }

    /** The Phase 4 backfill verbs. Requires lodStore=full with a live SQLite store
     *  (the backfill's only target — a memory store's work would evaporate at restart). */
    private static int backfill(CommandSourceStack source, String verb) {
        var service = LSSServerNetworking.getRequestService();
        if (service == null) {
            source.sendFailure(Component.literal(Brand.shortName() + " LOD request processing is not active"));
            return 0;
        }
        var backfill = service.getStoreBackfill();
        if (backfill == null) {
            source.sendFailure(Component.literal(
                    "Store backfill unavailable — requires lodStore=full with a running SQLite store"));
            return 0;
        }
        switch (verb) {
            case "start" -> {
                boolean started = backfill.start();
                source.sendSuccess(() -> Component.literal(started
                        ? "Store backfill started (background, yields to players)"
                        : "Store backfill already running"), true);
            }
            case "stop" -> {
                boolean stopped = backfill.stop();
                source.sendSuccess(() -> Component.literal(stopped
                        ? "Store backfill stop requested (finishes the current column)"
                        : "Store backfill is not running"), true);
            }
            default -> source.sendSuccess(() -> Component.literal(
                    "Store backfill: " + backfill.statusLine()), false);
        }
        return 1;
    }

    private static int showStats(CommandSourceStack source) {
        var service = LSSServerNetworking.getRequestService();
        if (service == null) {
            source.sendFailure(Component.literal(Brand.shortName() + " LOD request processing is not active"));
            return 0;
        }

        var players = service.getPlayers();
        if (players.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No players connected with " + Brand.shortName()), false);
            return 1;
        }

        source.sendSuccess(() -> Component.literal("=== " + Brand.shortName() + " LOD Request Stats ==="), false);
        for (var state : players.values()) {
            String line = DiagnosticsFormatter.formatStatsLine(state);
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int showDiagnostics(CommandSourceStack source) {
        var service = LSSServerNetworking.getRequestService();
        if (service == null) {
            source.sendFailure(Component.literal(Brand.shortName() + " LOD request processing is not active"));
            return 0;
        }

        var config = LSSServerConfig.CONFIG;
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
                // after a problem that does not exist (v0.9.0 final review).
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
                .withSummaryLine(summaryDiagLineOrNull(service))
                .withXrayLine(xrayDiagLine())
                .withMoveTraceLine(moveTraceDiagLineOrNull())
                .withYieldLine(DiagnosticsFormatter.yieldDiagLineOrNull(
                        config.lodYieldsToVanillaTransport, service.getTickDiag()))
                .withGateLine(serviceGateDiagLineOrNull(config, service));

        for (var line : DiagnosticsFormatter.formatDiagnostics(data)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /** The service-gate one-liner (plan §2.5): present only while the key is armed;
     *  the provider token is the LOADER's fact (the LoaderServices seam — "none" when
     *  no backend resolved, the armed-gate-serves-everyone shape an admin must see). */
    private static String serviceGateDiagLineOrNull(LSSServerConfig config,
                                                    RequestProcessingService service) {
        if (!config.requireServicePermission) return null;
        return "Gate: requireServicePermission=on denied="
                + service.getServiceGateState().deniedCount()
                + " provider=" + dev.vox.lss.platform.LoaderServices.get().permissionProviderToken();
    }

    private static String xrayDiagLine() {
        var manager = XrayMaskManager.current();
        return manager != null ? manager.diagLine() : "Xray: active=off, masked_sections=0";
    }

    /** Present ONLY while the tracer is active (move-desync-tracer-plan.md §2) — it is the
     *  post-deploy rung verification and the silent-rejection rate in one RCON call. */
    private static String moveTraceDiagLineOrNull() {
        var tracer = dev.vox.lss.trace.MoveDesyncTracer.active();
        return tracer != null ? tracer.diagLine() : null;
    }

    /** Present ONLY once far players have been touched (a subscriber exists or frames
     *  were ever sent) — inert servers render nothing, so soak/benchmark diag output is
     *  byte-unchanged (E1 baseline neutrality). */
    private static String farPlayersDiagLineOrNull(
            RequestProcessingService service) {
        var fp = service.getFarPlayerService();
        if (fp == null) return null; // partial rigs (mocked service seams)
        return fp.subscriberCount() > 0 || fp.rosterFramesSent() > 0 ? fp.diagLine() : null;
    }

    /** Region summaries (P2 §6 attributability): present only once a request arrived. */
    private static String summaryDiagLineOrNull(RequestProcessingService service) {
        var rs = service.getRegionSummaries();
        return rs == null ? null : rs.diagnostics().diagLineOrNull();
    }
}
