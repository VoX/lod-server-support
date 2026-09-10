package dev.vox.lss.networking.client;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.DiagnosticsFormatter;
import dev.vox.lss.config.LSSClientConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The /lss client-command BODIES (N-3, neoforge-support-plan.md §1.1 item 5 —
 * the client command source adapter): extracted VERBATIM from the Fabric
 * {@code LSSClientCommands}, source-neutral via a {@code Consumer<Component>}
 * feedback sink. Each loader keeps only its thin brigadier tree against its
 * own source type (Fabric's {@code FabricClientCommandSource.sendFeedback},
 * NeoForge's client command source).
 *
 * <p>ONE subtree is shared rather than mirrored: {@link #resetSubtree} (issue #4). Once
 * {@code reset} grew a second confirm-gated form it became four nodes whose
 * (confirmed, force) pairs must agree across loaders, and a hand-copied tree on each
 * side is a drift waiting to happen — the same reason the gametest entrypoint and
 * plugin.yml carry contract tests. Sharing the builder makes the drift unrepresentable
 * instead of merely detectable.
 */
public final class ClientCommandActions {

    private ClientCommandActions() {
    }

    /** /lss clearcache. */
    public static void clearCache(Consumer<Component> feedback) {
        var manager = ClientNetGlue.getRequestManager();
        if (manager != null) {
            manager.flushCache();
            feedback.accept(Component.literal(
                    Brand.shortName() + " column cache cleared for current server. Chunks will be re-requested."));
        } else {
            ColumnCacheStore.clearAll();
            feedback.accept(Component.literal(
                    Brand.shortName() + " column cache cleared for all servers."));
        }
    }

    /**
     * The /lss reset production wiring (v0.11.0 stage D): the sequence itself lives in
     * {@link ResetCoordinator} (seam-injected, JUnit-pinned ordering); this method only
     * binds the live collaborators. Main client thread (client commands dispatch there —
     * the same thread Voxy's own command and login/disconnect mixins run on, so no
     * concurrent-lifecycle race).
     *
     * @param forceVoxyWipe issue #4's {@code voxy-force} form. It is two-stage like
     *                      {@code confirm}: unconfirmed it only shows the storage roots.
     */
    public static void runReset(Consumer<Component> feedback, boolean confirmed,
                                boolean forceVoxyWipe) {
        var manager = ClientNetGlue.getRequestManager();
        ResetCoordinator.run(new ResetCoordinator.Deps(
                manager != null,
                () -> {
                    if (manager != null) {
                        ClientNetGlue.reportUndispatchedColumns(manager);
                    }
                    // Await even without a manager (review n4): a reset typed right
                    // after a disconnect can race the disconnect drain's final dispatch
                    // into the wipe window.
                    if (!ClientNetGlue.awaitDecodeIdle(2_000)) {
                        dev.vox.lss.common.LSSLogger.warn(
                                "Reset: decode drain still busy after 2s — proceeding "
                                        + "(the wipe is IO-contained regardless)");
                    }
                },
                dev.vox.lss.compat.ModCompat::resetVoxyLods,
                dev.vox.lss.compat.ModCompat::probeVoxyStorage,
                () -> {
                    if (manager != null) manager.flushCache();
                },
                ColumnCacheStore::clearAll,
                FarPlayerClientSupport::resetAndResubscribe,
                line -> feedback.accept(Component.literal(line).withStyle(ChatFormatting.GOLD)),
                // The force grant's connection identity: the live ClientPacketListener
                // (null = the no-session sentinel — a no-session grant matches only a
                // still-no-session confirm).
                () -> (Object) net.minecraft.client.Minecraft.getInstance().getConnection(),
                System::nanoTime),
                confirmed, forceVoxyWipe);
    }

    /**
     * The whole {@code reset} subtree, built once for every loader:
     *
     * <pre>
     *   reset                        -> plain reset
     *   reset confirm                -> the destructive no-session form
     *   reset voxy-force             -> issue #4 stage 1: show the storage roots only
     *   reset voxy-force confirm     -> issue #4 stage 2: wipe the live root
     * </pre>
     *
     * <p>Generic over the loader's command-source type; the caller supplies its own
     * literal factory (Fabric's {@code ClientCommands::literal}, NeoForge's
     * {@code Commands::literal}) and its own source-to-feedback adapter, and gets back a
     * builder to hang on its own root node.
     */
    public static <S> LiteralArgumentBuilder<S> resetSubtree(
            Function<String, LiteralArgumentBuilder<S>> literal,
            Function<S, Consumer<Component>> feedback) {
        return resetSubtree(literal, feedback, ClientCommandActions::runReset);
    }

    /** The node→(confirmed, force) mapping — a dispatch seam so the contract test can
     *  EXECUTE all four forms against a recorder (executing the production sink would
     *  run a real reset). */
    @FunctionalInterface
    interface ResetDispatch {
        void reset(Consumer<Component> feedback, boolean confirmed, boolean forceVoxyWipe);
    }

    static <S> LiteralArgumentBuilder<S> resetSubtree(
            Function<String, LiteralArgumentBuilder<S>> literal,
            Function<S, Consumer<Component>> feedback,
            ResetDispatch dispatch) {
        return literal.apply("reset")
                .executes(context -> dispatchReset(context, feedback, dispatch, false, false))
                .then(literal.apply("confirm")
                        .executes(context -> dispatchReset(context, feedback, dispatch, true, false)))
                .then(literal.apply("voxy-force")
                        .executes(context -> dispatchReset(context, feedback, dispatch, false, true))
                        .then(literal.apply("confirm")
                                .executes(context ->
                                        dispatchReset(context, feedback, dispatch, true, true))));
    }

    private static <S> int dispatchReset(CommandContext<S> context,
                                         Function<S, Consumer<Component>> feedback,
                                         ResetDispatch dispatch,
                                         boolean confirmed, boolean forceVoxyWipe) {
        dispatch.reset(feedback.apply(context.getSource()), confirmed, forceVoxyWipe);
        return Command.SINGLE_SUCCESS;
    }

    /** /lss trace. */
    public static void toggleTrace(Consumer<Component> feedback) {
        var result = ClientTraceLog.toggle();
        if (result.path() != null) {
            feedback.accept(Component.literal(
                    Brand.shortName() + " trace STARTED: " + result.path()).withStyle(ChatFormatting.GOLD));
        } else if (result.failed()) {
            feedback.accept(Component.literal(
                    Brand.shortName() + " trace FAILED to start — see the log.").withStyle(ChatFormatting.RED));
        } else {
            feedback.accept(Component.literal(
                    Brand.shortName() + " trace stopped.").withStyle(ChatFormatting.GOLD));
        }
    }

    /** Standalone entry, independent of Sodium and request-manager creation. */
    public static void showStatus() {
        ClientStatus.requestOpen();
    }

    public static void exportDiagnostics(Consumer<Component> feedback) {
        var snapshot = ClientStatus.latest();
        if (snapshot == null) {
            feedback.accept(Component.literal("Waiting for a current-session snapshot; retry after the next tick."));
            return;
        }
        try {
            dev.vox.lss.common.diagnostics.DiagnosticExport.write(
                    dev.vox.lss.platform.LoaderServices.get().gameDir().resolve(Brand.lowerShortName() + "-diagnostics"), snapshot)
                    .whenComplete((path, error) -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
                        // Never disclose arbitrary exception text (paths/addresses can occur there).
                        feedback.accept(Component.literal(error == null ? "Diagnostics exported: " + path
                                : "Diagnostics export failed; check directory permissions and free space."));
                    }));
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            feedback.accept(Component.literal("Diagnostics exporter busy; retry after the current export."));
        }
    }

    public static <S> LiteralArgumentBuilder<S> presetSubtree(
            Function<String, LiteralArgumentBuilder<S>> literal,
            Function<S, Consumer<Component>> feedback) {
        var root = literal.apply("preset");
        for (String action : java.util.List.of("map-only", "map-only-xaero-writes", "apply", "undo")) {
            root.then(literal.apply(action).executes(context -> {
                try {
                    ClientPresets.command(action).forEach(line -> feedback.apply(context.getSource()).accept(Component.literal(line)));
                } catch (IllegalArgumentException | IllegalStateException failure) {
                    feedback.apply(context.getSource()).accept(Component.literal(failure.getMessage()));
                }
                return Command.SINGLE_SUCCESS;
            }));
        }
        return root;
    }

    public static <S> LiteralArgumentBuilder<S> statusSubtree(
            Function<String, LiteralArgumentBuilder<S>> literal) {
        return literal.apply("status").executes(context -> { showStatus(); return Command.SINGLE_SUCCESS; });
    }

    public static <S> LiteralArgumentBuilder<S> diagnosticsSubtree(
            Function<String, LiteralArgumentBuilder<S>> literal,
            Function<S, Consumer<Component>> feedback) {
        return literal.apply("diagnostics").then(literal.apply("export").executes(context -> {
            exportDiagnostics(feedback.apply(context.getSource()));
            return Command.SINGLE_SUCCESS;
        }));
    }

    /** /lss diag. */
    public static void showDiagnostics(Consumer<Component> feedback) {
        var snapshot = ClientStatus.latest();
        if (snapshot != null) snapshot.lines().forEach(line -> feedback.accept(Component.literal(line)));
        else feedback.accept(Component.literal("Waiting for a current-session status snapshot."));
        var details = snapshot == null ? null : snapshot.details();
        if (details == null) return;

        feedback.accept(Component.literal("=== " + Brand.shortName() + " Client Diagnostics ===").withStyle(ChatFormatting.GOLD));

        // Connection line
        int serverDist = details.getServerLodDistance();
        int effectiveDist = details.getEffectiveLodDistanceChunks();
        feedback.accept(Component.literal(String.format(
                "Connection: server_lod_dist=%d, effective_dist=%d",
                serverDist, effectiveDist
        )).withStyle(ChatFormatting.GRAY));

        // Throughput line
        long received = details.getColumnsReceived();
        long bytes = details.getBytesReceived();
        long dropped = details.getColumnsDropped();
        long startMs = details.getConnectionStartMs();
        long uptimeSec = startMs > 0 ? (snapshot.capturedAtMillis() - startMs) / 1000 : 0;
        feedback.accept(Component.literal(String.format(
                "Throughput: received=%d (%s), dropped=%d, recv_rate=%s/s, req_rate=%s/s, uptime=%s",
                received, DiagnosticsFormatter.formatBytes(bytes), dropped,
                DiagnosticsFormatter.formatRate(details.getReceiveRate()), DiagnosticsFormatter.formatRate(details.getRequestRate()),
                DiagnosticsFormatter.formatUptime(uptimeSec)
        )).withStyle(ChatFormatting.GRAY));

        // Queue line
        int queued = details.getQueuedColumnCount();
        feedback.accept(Component.literal(String.format(
                "Queue: queued=%d/%d",
                queued, ClientColumnProcessor.MAX_QUEUED_COLUMNS
        )).withStyle(ChatFormatting.GRAY));

        // Columns line
        int receivedCols = details.getReceivedColumnCount();
        int empty = details.getEmptyColumnCount();
        int dirty = details.getDirtyColumnCount();
        feedback.accept(Component.literal(String.format(
                "Columns: received=%d, empty=%d, dirty=%d, ingest_failed=%d, ingest_parked=%d",
                receivedCols, empty, dirty, details.getTotalIngestFailures(),
                details.getIngestParkedCount()
        )).withStyle(ChatFormatting.GRAY));

        // Responses line
        feedback.accept(Component.literal(String.format(
                "Responses: columns=%d, up_to_date=%d, not_generated=%d",
                details.getTotalColumnsReceived(), details.getTotalUpToDate(),
                details.getTotalNotGenerated()
        )).withStyle(ChatFormatting.GRAY));

        // Requests line
        feedback.accept(Component.literal(String.format(
                "Requests: send_cycles=%d, total_requested=%d",
                details.getTotalSendCycles(), details.getTotalPositionsRequested()
        )).withStyle(ChatFormatting.GRAY));

        // Scan line
        int confirmedRing = details.getConfirmedRing();
        int scanRing = details.getScanRing();
        int maxRing = details.getEffectiveLodDistanceChunks();
        // ring_skips: rings the leaf fast path confirmed without a per-position walk
        // (the legacy arm's quadtree path, gated by enableQuadtreeScan — AND the
        // hybrid walk's phase-1 skips, which are gate-independent and make this
        // unconditionally large on the hybrid arm: NOT a dirty-dispersion signal
        // there, §7). valve: reopened-ring valve overflows this session (the
        // quadtree plan's phase-0 B1 field measurement — on the LEGACY arm nonzero
        // on a busy server means dirty broadcasts genuinely disperse past 64 rings).
        // near_rings: hybrid phase-1 rings that emitted/observed needy work last
        // walk — the §7/§9 in-band instrument: active during near fill, ~0 at
        // convergence (always 0 on the legacy arm).
        feedback.accept(Component.literal(String.format(
                "Scan: confirmed=%d, reopened=%d, scanning=%d/%d, missing_vanilla=%d, fast=%d, ring_skips=%d, valve=%d, region_span=%d, region_skips=%d, audit_heals=%d, near_rings=%d",
                confirmedRing, details.getReopenedRingCount(), scanRing, maxRing,
                details.getMissingVanillaChunks(), details.getFastScans(),
                details.getQuadRingSkips(), details.getValveTrips(),
                details.getRegionSpan(), details.getRegionSkips(), details.getAuditHeals(),
                details.getNearRings()
        )).withStyle(ChatFormatting.GRAY));

        // Region summaries (§6 attributability): rendered once any summary applied —
        // why the want-set skipped revalidating the clean bulk after this rejoin.
        // Stamps counters included in the gate (final panel): a session whose summary
        // frame was lost but whose stamps flowed would otherwise hide its only
        // instrument for the applied/ignored counts.
        if (details.getSummaryTilesClean() + details.getSummaryTilesStale()
                + details.getSummaryTilesUnknown() + details.getSummaryTilesNoRegion()
                + details.getSummaryStampsApplied() + details.getSummaryStampsIgnored() > 0) {
            feedback.accept(Component.literal(String.format(
                    "Summary: tiles clean=%d stale=%d unknown=%d no_region=%d, columns_validated=%d, stamps applied=%d ignored=%d",
                    details.getSummaryTilesClean(), details.getSummaryTilesStale(),
                    details.getSummaryTilesUnknown(), details.getSummaryTilesNoRegion(),
                    details.getSummaryColumnsValidated(), details.getSummaryStampsApplied(),
                    details.getSummaryStampsIgnored()
            )).withStyle(ChatFormatting.GRAY));
        }

        // Budget line (ingest_backlog: the consumer-reported pending sections driving the
        // #71 taper/halt; -1 = no consumer reports. rate_cap: the manual column-rate cap,
        // 0=off; rate_gated: TICKS the cap's spacing gate held a would-be fast fire back
        // (one delayed fire can count several) — nonzero means the knob is binding, the
        // discriminator for weak-client reports)
        int budget = details.getLastBudget();
        int lastQueued = details.getLastQueued();
        // Far players (E1, conditional slot — rendered once any far-player state
        // exists; inert sessions never see it).
        if (details.farPlayersLine() != null) feedback.accept(Component.literal(details.farPlayersLine()).withStyle(ChatFormatting.GRAY));
        if (details.farRendererLine() != null) feedback.accept(Component.literal(details.farRendererLine()).withStyle(ChatFormatting.GRAY));

        feedback.accept(Component.literal(String.format(
                "Budget: used=%d/%d, ingest_backlog=%d, rate_cap=%d, governed=%s, rate_gated=%d",
                lastQueued, budget, details.getLastIngestBacklog(),
                details.rateCap(),
                details.getGovernedRateLabel(), details.getRateGated()
        )).withStyle(ChatFormatting.GRAY));

        // The two-axis cache key (cache-alias-keying-and-reset-override-plan.md §2.1):
        // both axes and the reason branch.
        var cacheLine = details.describeCacheKey();
        if (cacheLine != null) {
            feedback.accept(Component.literal("Cache: " + cacheLine)
                    .withStyle(ChatFormatting.GRAY));
        }
        // Xaero map bridge (issue #223, conditional slot — present only when Xaero's
        // World Map was detected at init; the Summary-line precedent).
        var xaeroLine = details.xaeroDiagLine();
        if (xaeroLine != null) {
            feedback.accept(Component.literal(xaeroLine).withStyle(ChatFormatting.GRAY));
        }
    }
}
