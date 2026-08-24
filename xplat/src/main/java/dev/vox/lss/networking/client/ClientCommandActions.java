package dev.vox.lss.networking.client;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.DiagnosticsFormatter;
import dev.vox.lss.config.LSSClientConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * The /lss client-command BODIES (N-3, neoforge-support-plan.md §1.1 item 5 —
 * the client command source adapter): extracted VERBATIM from the Fabric
 * {@code LSSClientCommands}, source-neutral via a {@code Consumer<Component>}
 * feedback sink. Each loader keeps only its thin brigadier tree against its
 * own source type (Fabric's {@code FabricClientCommandSource.sendFeedback},
 * NeoForge's client command source).
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
     */
    public static void runReset(Consumer<Component> feedback, boolean confirmed) {
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
                () -> {
                    if (manager != null) manager.flushCache();
                },
                ColumnCacheStore::clearAll,
                FarPlayerClientSupport::resetAndResubscribe,
                line -> feedback.accept(Component.literal(line).withStyle(ChatFormatting.GOLD))),
                confirmed);
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

    /** /lss diag. */
    public static void showDiagnostics(Consumer<Component> feedback) {
        var manager = ClientNetGlue.getRequestManager();
        if (manager == null || !ClientNetGlue.isServerEnabled()) {
            feedback.accept(Component.literal(Brand.shortName() + " is not active on this server").withStyle(ChatFormatting.RED));
            return;
        }

        feedback.accept(Component.literal("=== " + Brand.shortName() + " Client Diagnostics ===").withStyle(ChatFormatting.GOLD));

        // Connection line
        int serverDist = ClientNetGlue.getServerLodDistance();
        int effectiveDist = manager.getEffectiveLodDistanceChunks();
        feedback.accept(Component.literal(String.format(
                "Connection: server_lod_dist=%d, effective_dist=%d",
                serverDist, effectiveDist
        )).withStyle(ChatFormatting.GRAY));

        // Throughput line
        long received = ClientNetGlue.getColumnsReceived();
        long bytes = ClientNetGlue.getBytesReceived();
        long dropped = ClientNetGlue.getColumnsDropped();
        long startMs = ClientNetGlue.getConnectionStartMs();
        long uptimeSec = startMs > 0 ? (System.currentTimeMillis() - startMs) / 1000 : 0;
        feedback.accept(Component.literal(String.format(
                "Throughput: received=%d (%s), dropped=%d, recv_rate=%s/s, req_rate=%s/s, uptime=%s",
                received, DiagnosticsFormatter.formatBytes(bytes), dropped,
                DiagnosticsFormatter.formatRate(manager.getReceiveRate()), DiagnosticsFormatter.formatRate(manager.getRequestRate()),
                DiagnosticsFormatter.formatUptime(uptimeSec)
        )).withStyle(ChatFormatting.GRAY));

        // Queue line
        int queued = ClientNetGlue.getQueuedColumnCount();
        feedback.accept(Component.literal(String.format(
                "Queue: queued=%d/%d",
                queued, ClientColumnProcessor.MAX_QUEUED_COLUMNS
        )).withStyle(ChatFormatting.GRAY));

        // Columns line
        int receivedCols = manager.getReceivedColumnCount();
        int empty = manager.getEmptyColumnCount();
        int dirty = manager.getDirtyColumnCount();
        feedback.accept(Component.literal(String.format(
                "Columns: received=%d, empty=%d, dirty=%d, ingest_failed=%d, ingest_parked=%d",
                receivedCols, empty, dirty, manager.getTotalIngestFailures(),
                manager.getIngestParkedCount()
        )).withStyle(ChatFormatting.GRAY));

        // Responses line
        feedback.accept(Component.literal(String.format(
                "Responses: columns=%d, up_to_date=%d, not_generated=%d",
                manager.getTotalColumnsReceived(), manager.getTotalUpToDate(),
                manager.getTotalNotGenerated()
        )).withStyle(ChatFormatting.GRAY));

        // Requests line
        feedback.accept(Component.literal(String.format(
                "Requests: send_cycles=%d, total_requested=%d",
                manager.getTotalSendCycles(), manager.getTotalPositionsRequested()
        )).withStyle(ChatFormatting.GRAY));

        // Scan line
        int confirmedRing = manager.getConfirmedRing();
        int scanRing = manager.getScanRing();
        int maxRing = manager.getEffectiveLodDistanceChunks();
        // ring_skips: rings the leaf fast path confirmed without a per-position walk
        // (the legacy arm's quadtree path, gated by enableQuadtreeScan — AND the
        // hybrid walk's phase-1 skips, which are gate-independent, §7).
        // (the quadtree plan's phase-0 B1 field measurement — nonzero on a busy server
        // means dirty broadcasts genuinely disperse past 64 rings).
        feedback.accept(Component.literal(String.format(
                "Scan: confirmed=%d, reopened=%d, scanning=%d/%d, missing_vanilla=%d, fast=%d, ring_skips=%d, valve=%d, region_span=%d, region_skips=%d, audit_heals=%d, near_rings=%d",
                confirmedRing, manager.getReopenedRingCount(), scanRing, maxRing,
                manager.getMissingVanillaChunks(), manager.getFastScans(),
                manager.getQuadRingSkips(), manager.getValveTrips(),
                manager.getRegionSpan(), manager.getRegionSkips(), manager.getAuditHeals(),
                manager.getNearRings()
        )).withStyle(ChatFormatting.GRAY));

        // Region summaries (§6 attributability): rendered once any summary applied —
        // why the want-set skipped revalidating the clean bulk after this rejoin.
        // Stamps counters included in the gate (final panel): a session whose summary
        // frame was lost but whose stamps flowed would otherwise hide its only
        // instrument for the applied/ignored counts.
        if (manager.getSummaryTilesClean() + manager.getSummaryTilesStale()
                + manager.getSummaryTilesUnknown() + manager.getSummaryTilesNoRegion()
                + manager.getSummaryStampsApplied() + manager.getSummaryStampsIgnored() > 0) {
            feedback.accept(Component.literal(String.format(
                    "Summary: tiles clean=%d stale=%d unknown=%d no_region=%d, columns_validated=%d, stamps applied=%d ignored=%d",
                    manager.getSummaryTilesClean(), manager.getSummaryTilesStale(),
                    manager.getSummaryTilesUnknown(), manager.getSummaryTilesNoRegion(),
                    manager.getSummaryColumnsValidated(), manager.getSummaryStampsApplied(),
                    manager.getSummaryStampsIgnored()
            )).withStyle(ChatFormatting.GRAY));
        }

        // Budget line (ingest_backlog: the consumer-reported pending sections driving the
        // #71 taper/halt; -1 = no consumer reports. rate_cap: the manual column-rate cap,
        // 0=off; rate_gated: TICKS the cap's spacing gate held a would-be fast fire back
        // (one delayed fire can count several) — nonzero means the knob is binding, the
        // discriminator for weak-client reports)
        int budget = manager.getLastBudget();
        int lastQueued = manager.getLastQueued();
        // Far players (E1, conditional slot — rendered once any far-player state
        // exists; inert sessions never see it).
        var farTracker = FarPlayerClientSupport.tracker();
        if (farTracker.rostersApplied() > 0 || farTracker.trackedCount() > 0) {
            feedback.accept(Component.literal(farTracker.diagLine())
                    .withStyle(ChatFormatting.GRAY));
        }

        feedback.accept(Component.literal(String.format(
                "Budget: used=%d/%d, ingest_backlog=%d, rate_cap=%d, governed=%s, rate_gated=%d",
                lastQueued, budget, manager.getLastIngestBacklog(),
                LSSClientConfig.CONFIG.lodColumnsPerSecondLimit,
                manager.getGovernedRateLabel(), manager.getRateGated()
        )).withStyle(ChatFormatting.GRAY));

        // Xaero map bridge (issue #223, conditional slot — present only when Xaero's
        // World Map was detected at init; the Summary-line precedent).
        var xaeroLine = dev.vox.lss.compat.ModCompat.xaeroDiagLine();
        if (xaeroLine != null) {
            feedback.accept(Component.literal(xaeroLine).withStyle(ChatFormatting.GRAY));
        }
    }
}
