package dev.vox.lss.networking.client;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.common.diagnostics.ClientStatusSnapshot;
import dev.vox.lss.common.diagnostics.StatusCache;
import dev.vox.lss.config.LSSClientConfig;
import net.minecraft.client.Minecraft;

/** Collection is client-tick owned. UI reads only the cached immutable snapshot. */
public final class ClientStatus {
    private static final boolean COLLECTION_ENABLED = !Boolean.getBoolean("lss.test.disableStatusCollection");
    private static final StatusCache<ClientStatusSnapshot> CACHE = new StatusCache<>();
    // Same monitor owns world/connection identity and pending callback references.
    // StatusCache never calls back into this monitor (no reverse lock order).
    private static final LifecycleFeedback<net.minecraft.network.chat.Component> EXPORT_FEEDBACK =
            new LifecycleFeedback<>(2);
    private static Object world;
    private static Object connection;
    private static long baselineLifecycle = -1;
    private static long baseColumns, baseBytes, baseFailures, lastRateGated;
    private static boolean openRequested;

    public static void requestOpen() { openRequested = true; }

    public static void invalidate() {
        synchronized (EXPORT_FEEDBACK) {
            CACHE.invalidate();
            EXPORT_FEEDBACK.clear();
            world = null;
            connection = null;
        }
    }
    private static long checkLifecycle() {
        synchronized (EXPORT_FEEDBACK) {
            var mc = Minecraft.getInstance();
            if (world != mc.level || connection != mc.getConnection()) {
                CACHE.invalidate();
                EXPORT_FEEDBACK.clear();
                world = mc.level;
                connection = mc.getConnection();
            }
            return CACHE.lifecycle();
        }
    }
    static LifecycleFeedback.Ticket reserveExportFeedback(
            long lifecycle, java.util.function.Consumer<net.minecraft.network.chat.Component> callback) {
        synchronized (EXPORT_FEEDBACK) {
            return EXPORT_FEEDBACK.reserve(lifecycle, checkLifecycle(), callback);
        }
    }
    static void releaseExportFeedback(LifecycleFeedback.Ticket ticket) {
        EXPORT_FEEDBACK.release(ticket);
    }
    static void completeExportFeedback(LifecycleFeedback.Ticket ticket,
            String message) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("export feedback requires client owner");
        java.util.function.Consumer<net.minecraft.network.chat.Component> callback;
        synchronized (EXPORT_FEEDBACK) {
            // Taking is the delivery linearization point. Queued old completions
            // lose their callback on invalidation, including same-dimension swaps.
            callback = EXPORT_FEEDBACK.take(ticket, checkLifecycle());
        }
        // Never invoke chat/UI code while holding the lifecycle monitor.
        if (callback != null) callback.accept(net.minecraft.network.chat.Component.literal(message));
    }
    public static ClientStatusSnapshot latest() {
        // Identity comparison only: same-dimension replacement invalidates immediately,
        // including when a screen is rendered before the next client tick.
        checkLifecycle();
        return CACHE.latest();
    }
    public static void tick() {
        if (openRequested) {
            openRequested = false;
            var client = Minecraft.getInstance();
            client.setScreen(new ClientStatusScreen(null));
        }
        long lifecycle = checkLifecycle();
        if (!COLLECTION_ENABLED) return;
        if (!CACHE.due(System.nanoTime())) return;
        var mc = Minecraft.getInstance();
        var cfg = LSSClientConfig.CONFIG;
        var manager = ClientNetGlue.getRequestManager();
        boolean active = manager != null && mc.level != null && ClientNetGlue.isServerEnabled();
        if (baselineLifecycle != lifecycle) {
            baselineLifecycle = lifecycle;
            baseColumns = ClientNetGlue.getColumnsReceived();
            baseBytes = ClientNetGlue.getBytesReceived();
            baseFailures = active ? manager.getTotalIngestFailures() : 0;
            lastRateGated = active ? manager.getRateGated() : 0;
        }
        long rateGated = active ? manager.getRateGated() : 0;
        var xaero = dev.vox.lss.compat.ModCompat.cachedXaeroStatus();
        boolean serverAllowsMaps = ClientNetGlue.serverAllowsMapIngestion();
        var availability = mapAvailability(xaero.failed(), xaero.resolution(),
                cfg.mapIngestionEnabled(), serverAllowsMaps);
        var journeyMap = dev.vox.lss.compat.ModCompat.cachedJourneyMapStatus();
        var journeyMapAvailability = mapAvailability(journeyMap.failed(), journeyMap.resolution(),
                cfg.mapIngestionEnabled(), serverAllowsMaps);
        if (DhClientSession.selected()) {
            // DH mode: native fields describe the (idle) native path truthfully; DH
            // acquisition lives in the dh block. Nothing is faked.
            CACHE.publish(lifecycle, new ClientStatusSnapshot(ClientStatusSnapshot.SCHEMA_VERSION, lifecycle, System.currentTimeMillis(),
                    mc.getConnection() != null, ClientNetGlue.hasReceivedSessionConfig(), cfg.receiveServerLods(),
                    ClientNetGlue.isServerEnabled(), LSSApi.hasVoxelConsumers(), FarPlayerRenderer.RENDER_AVAILABLE,
                    ClientNetGlue.getSessionVersion(), ClientNetGlue.getServerLodDistance(), 0, 0, 0, 0,
                    dev.vox.lss.compat.DhVoxyBridge.backlog(), 0, cfg.lodColumnsPerSecondLimit(), 0,
                    xaero.pendingRebuilds(), ClientNetGlue.discoveryStatus(), availability, null,
                    dev.vox.lss.platform.LoaderServices.get().diagnosticVersions(), cfg.diagnosticSettings(),
                    DhClientSession.diagnostics(), journeyMapAvailability, 0));
            return;
        }
        var snapshot = new ClientStatusSnapshot(ClientStatusSnapshot.SCHEMA_VERSION, lifecycle, System.currentTimeMillis(),
                mc.getConnection() != null, ClientNetGlue.hasReceivedSessionConfig(),
                cfg.receiveServerLods(), ClientNetGlue.isServerEnabled(), LSSApi.hasVoxelConsumers(),
                FarPlayerRenderer.RENDER_AVAILABLE, ClientNetGlue.getSessionVersion(),
                ClientNetGlue.getServerLodDistance(), active ? manager.getEffectiveLodDistanceChunks() : 0,
                active ? Math.max(0, ClientNetGlue.getColumnsReceived() - baseColumns) : 0,
                active ? Math.max(0, ClientNetGlue.getBytesReceived() - baseBytes) : 0,
                active ? ClientNetGlue.getQueuedColumnCount() : 0,
                active ? manager.getLastIngestBacklog() : -1,
                active ? Math.max(0, manager.getTotalIngestFailures() - baseFailures) : 0,
                cfg.lodColumnsPerSecondLimit(), Math.max(0, rateGated - lastRateGated),
                xaero.pendingRebuilds(), ClientNetGlue.discoveryStatus(), availability,
                active ? captureDetails(manager) : null,
                dev.vox.lss.platform.LoaderServices.get().diagnosticVersions(), cfg.diagnosticSettings(),DhClientSession.fallbackEnabled()?DhClientSession.diagnostics():null,
                journeyMapAvailability, journeyMap.pendingColumns(),
                // How many far players the server is sending right now (a count only): 0 means
                // the server sends none, so the cause is on the server side or the other player's.
                FarPlayerClientSupport.tracker().trackedCount());
        lastRateGated = rateGated;
        CACHE.publish(lifecycle, snapshot);
    }
    /** A map backend's availability: integration facts first, then the local setting, then
     *  the server's map-ingestion request (map-policy plan A4 — the local setting is
     *  untouched by a server deny). */
    static ClientStatusSnapshot.Availability mapAvailability(boolean failed, String resolution,
                                                             boolean locallyEnabled,
                                                             boolean serverAllows) {
        return failed ? ClientStatusSnapshot.Availability.FAILED
                : "unknown".equals(resolution) ? ClientStatusSnapshot.Availability.UNKNOWN
                : "absent".equals(resolution) ? ClientStatusSnapshot.Availability.ABSENT
                : "unavailable".equals(resolution) ? ClientStatusSnapshot.Availability.UNAVAILABLE
                : !locallyEnabled ? ClientStatusSnapshot.Availability.DISABLED
                : !serverAllows ? ClientStatusSnapshot.Availability.SERVER_BLOCKED
                : ClientStatusSnapshot.Availability.AVAILABLE;
    }

    private static dev.vox.lss.common.diagnostics.ClientDiagnosticSnapshot captureDetails(LodRequestManager manager) {
        var tracker = FarPlayerClientSupport.tracker();
        boolean farActive = tracker.rostersApplied() > 0 || tracker.trackedCount() > 0;
        return new dev.vox.lss.common.diagnostics.ClientDiagnosticSnapshot(
                manager.getAuditHeals(),
                manager.getConfirmedRing(),
                manager.getDirtyColumnCount(),
                manager.getEffectiveLodDistanceChunks(),
                manager.getEmptyColumnCount(),
                manager.getFastScans(),
                manager.getGovernedRateLabel(),
                manager.getIngestParkedCount(),
                manager.getLastBudget(),
                manager.getLastIngestBacklog(),
                manager.getLastQueued(),
                manager.getMissingVanillaChunks(),
                manager.getNearRings(),
                manager.getQuadRingSkips(),
                manager.getRateGated(),
                manager.getReceiveRate(),
                manager.getReceivedColumnCount(),
                manager.getRegionSkips(),
                manager.getRegionSpan(),
                manager.getReopenedRingCount(),
                manager.getRequestRate(),
                manager.getScanRing(),
                manager.getSummaryColumnsValidated(),
                manager.getSummaryStampsApplied(),
                manager.getSummaryStampsIgnored(),
                manager.getSummaryTilesClean(),
                manager.getSummaryTilesNoRegion(),
                manager.getSummaryTilesStale(),
                manager.getSummaryTilesUnknown(),
                manager.getTotalColumnsReceived(),
                manager.getTotalIngestFailures(),
                manager.getTotalNotGenerated(),
                manager.getTotalPositionsRequested(),
                manager.getTotalSendCycles(),
                manager.getTotalUpToDate(),
                manager.getValveTrips(),
                ClientNetGlue.getServerLodDistance(),
                ClientNetGlue.getColumnsReceived(),
                ClientNetGlue.getBytesReceived(),
                ClientNetGlue.getColumnsDropped(),
                ClientNetGlue.getConnectionStartMs(),
                ClientNetGlue.getQueuedColumnCount(),
                LSSClientConfig.CONFIG.lodColumnsPerSecondLimit(),
                manager.describeCacheKey(),
                farActive ? tracker.diagLine() : null,
                farActive && FarPlayerRenderer.RENDER_AVAILABLE ? FarPlayerRenderer.diagLine() : null,
                dev.vox.lss.compat.ModCompat.xaeroDiagLine(),
                dev.vox.lss.compat.ModCompat.journeyMapDiagLine());
    }
    private ClientStatus() {}
}
