package dev.vox.lss.paper;

import dev.vox.lss.common.DiagnosticsFormatter;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.SharedBandwidthLimiter;
import dev.vox.lss.common.processing.LoadedColumnData;
import dev.vox.lss.common.processing.OffThreadProcessor;
import dev.vox.lss.common.processing.TickDiagnostics;
import dev.vox.lss.common.processing.TickSnapshot;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core orchestrator for per-player LOD request processing on Paper.
 * Adapted from Fabric's RequestProcessingService with Plugin Messaging send
 * calls.
 */
public class PaperRequestProcessingService {
    private final Map<UUID, PaperPlayerRequestState> players;
    private final MinecraftServer server;
    private final PaperChunkDiskReader diskReader;
    private final PaperChunkGenerationService generationService;
    private final SharedBandwidthLimiter bandwidthLimiter;
    private final PaperConfig config;
    private final PaperOffThreadProcessor offThreadProcessor;
    private final DirtyColumnTracker dirtyTracker;
    private final PaperDirtyColumnBroadcaster dirtyBroadcaster;

    private final long startTimeNanos = System.nanoTime();
    // Keyed by the lightweight ResourceKey (not ServerLevel): a ServerLevel key strongly
    // retains every world an LSS player ever visited — including unloaded ones on
    // world-cycling Paper servers (Multiverse/minigames). The dimension string is derivable
    // from the key.
    private final Map<ResourceKey<Level>, String> dimensionStringCache = new HashMap<>();

    private int diagLogCounter = 0;

    private final TickDiagnostics diag = new TickDiagnostics();



    private static final int DIAG_LOG_INTERVAL_TICKS = 100;
    private static final int MAX_PROBES_PER_TICK_PER_PLAYER = 512;

    /** Test seam: puts one encoded voxel-column frame on the wire. Production default is the
     *  raw NMS payload send; tests inject recording/throwing senders. */
    @FunctionalInterface
    interface ColumnPayloadSender {
        void send(PaperPlayerRequestState state, byte[] data) throws Exception;
    }

    /** Test seam: resolves a loaded chunk into pre-serialized column data, or null when the
     *  chunk is not loaded. Production default probes the chunk source on the main thread. */
    @FunctionalInterface
    interface LoadedColumnProbe {
        LoadedColumnData probe(ServerLevel level, int cx, int cz);
    }

    private ColumnPayloadSender columnPayloadSender = (state, data) ->
            PaperPayloadHandler.sendRawNmsPayload(state.getPlayer().getBukkitEntity(),
                    PaperPayloadHandler.ID_VOXEL_COLUMN, data);

    private LoadedColumnProbe loadedColumnProbe = (level, cx, cz) -> {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        return chunk != null ? PaperSectionSerializer.serializeColumn(level, chunk, cx, cz) : null;
    };

    void setColumnPayloadSender(ColumnPayloadSender sender) {
        this.columnPayloadSender = sender;
    }

    void setLoadedColumnProbe(LoadedColumnProbe probe) {
        this.loadedColumnProbe = probe;
    }

    /** Collaborator set for the package-private constructor. Tests build it over recording
     *  collaborators; production wiring lives in {@link #productionWiring} only. */
    record Wiring(Map<UUID, PaperPlayerRequestState> players,
                  PaperChunkDiskReader diskReader,
                  PaperChunkGenerationService generationService,
                  PaperOffThreadProcessor offThreadProcessor,
                  DirtyColumnTracker dirtyTracker,
                  PaperDirtyColumnBroadcaster dirtyBroadcaster) {}

    public PaperRequestProcessingService(MinecraftServer server, Plugin plugin, PaperConfig config) {
        this(server, config, productionWiring(server, plugin, config));
    }

    /** Test seam: same field wiring as production, collaborators injected. */
    PaperRequestProcessingService(MinecraftServer server, PaperConfig config, Wiring wiring) {
        this.server = server;
        this.config = config;
        this.players = wiring.players();
        this.diskReader = wiring.diskReader();
        this.generationService = wiring.generationService();
        this.bandwidthLimiter = new SharedBandwidthLimiter(config.bytesPerSecondLimitGlobal);
        this.offThreadProcessor = wiring.offThreadProcessor();
        this.dirtyTracker = wiring.dirtyTracker();
        this.dirtyBroadcaster = wiring.dirtyBroadcaster();
    }

    private static Wiring productionWiring(MinecraftServer server, Plugin plugin, PaperConfig config) {
        Map<UUID, PaperPlayerRequestState> players = new ConcurrentHashMap<>();
        var diskReader = new PaperChunkDiskReader(config.diskReaderThreads);
        PaperChunkGenerationService generationService = config.enableChunkGeneration
                ? new PaperChunkGenerationService(config, plugin) : null;

        var dataDir = server.getWorldPath(LevelResource.ROOT).resolve("data");
        var offThreadProcessor = new PaperOffThreadProcessor(
                players, diskReader, generationService != null, dataDir,
                config.perDimensionTimestampCacheSizeMB);
        offThreadProcessor.start();

        var dirtyTracker = new DirtyColumnTracker();
        var dirtyBroadcaster = new PaperDirtyColumnBroadcaster(
                server, players, dirtyTracker, offThreadProcessor);
        return new Wiring(players, diskReader, generationService, offThreadProcessor,
                dirtyTracker, dirtyBroadcaster);
    }

    public DirtyColumnTracker getDirtyTracker() {
        return this.dirtyTracker;
    }

    public PaperPlayerRequestState registerPlayer(ServerPlayer player, int capabilities) {
        var state = this.players.computeIfAbsent(player.getUUID(), uuid -> new PaperPlayerRequestState(
                player, this.config.syncOnLoadConcurrencyLimitPerPlayer, this.config.generationConcurrencyLimitPerPlayer));
        this.diskReader.registerPlayer(player.getUUID());
        state.setCapabilities(capabilities);
        state.markHandshakeComplete();
        return state;
    }

    public void removePlayer(UUID uuid) {
        this.players.remove(uuid);
        this.offThreadProcessor.notifyPlayerRemoved(uuid);
        cleanupPlayerServices(uuid);
    }

    private void cleanupPlayerServices(UUID uuid) {
        this.diskReader.removePlayerResults(uuid);
        if (this.generationService != null)
            this.generationService.removePlayer(uuid);
    }

    public void handleBatchRequest(ServerPlayer player, PaperPayloadHandler.DecodedBatchChunkRequest batch) {
        var state = this.players.get(player.getUUID());
        if (state == null || !state.hasCompletedHandshake()) return;

        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        int maxDist = this.config.lodDistanceChunks + LSSConstants.LOD_DISTANCE_BUFFER;

        for (int i = 0; i < batch.count(); i++) {
            long packedPosition = batch.packedPositions()[i];
            int cx = PositionUtil.unpackX(packedPosition);
            int cz = PositionUtil.unpackZ(packedPosition);
            if (PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) > maxDist) continue;
            state.addRequest(cx, cz, batch.clientTimestamps()[i]);
        }
    }

    public void tick() {
        if (!this.config.enabled)
            return;

        this.diag.reset(this.offThreadProcessor.getDiagnostics());

        var generationReady = tickGenerationService();
        var lifecycle = processPlayerLifecycle(generationReady);

        if (lifecycle.toRemove != null) {
            for (UUID uuid : lifecycle.toRemove)
                this.removePlayer(uuid);
        }

        postSnapshot(lifecycle, generationReady);
        this.drainSendActions();
        this.drainGenerationTicketRequests();
        flushSendQueues(lifecycle.activeCount);
        this.dirtyBroadcaster.tick(this.config);
        tickDiagnosticsLog();
    }

    private List<TickSnapshot.GenerationReadyData> tickGenerationService() {
        if (this.generationService == null)
            return List.of();
        return this.generationService.tick();
    }

    private record LifecycleResult(
            Map<UUID, String> playerDimensions,
            Map<UUID, Long2ObjectMap<LoadedColumnData>> loadedChunkProbes,
            int activeCount,
            List<UUID> toRemove) {
    }

    private LifecycleResult processPlayerLifecycle(
            List<TickSnapshot.GenerationReadyData> generationReady) {
        // Allocated fresh each tick: the snapshot owns these maps after postSnapshot, so the
        // processing thread can iterate them without racing the next tick's lifecycle pass.
        Map<UUID, String> playerDimensions = new HashMap<>();
        Map<UUID, Long2ObjectMap<LoadedColumnData>> loadedChunkProbes = new HashMap<>();

        // Per-player set of generation-outcome positions to skip in probeLoadedChunks
        Map<UUID, LongOpenHashSet> genReadyPositions = TickSnapshot.groupPositionsByPlayer(generationReady);

        int activeCount = 0;
        List<UUID> toRemove = null;
        for (var state : this.players.values()) {
            if (!state.hasCompletedHandshake())
                continue;
            activeCount++;
            this.diag.updateQueuePeak(state.getSendQueueSize());

            boolean removed = false;

            if (state.getPlayer().isRemoved()) {
                var current = this.server.getPlayerList().getPlayer(state.getPlayer().getUUID());
                if (current == null) {
                    if (toRemove == null)
                        toRemove = new ArrayList<>();
                    toRemove.add(state.getPlayer().getUUID());
                    removed = true;
                } else {
                    state.updatePlayer(current);
                }
            }

            if (removed)
                continue;

            if (state.checkDimensionChange()) {
                // A dimension change abandons all in-flight work. Reuse the (well-tested)
                // disconnect teardown + a fresh registration instead of a second, hand-rolled
                // partial-reset protocol: the processing thread unwinds the old state's dedup
                // groups via the removal event, and the fresh state starts clean next tick.
                var changed = state.getPlayer();
                int capabilities = state.getCapabilities();
                removePlayer(changed.getUUID());
                registerPlayer(changed, capabilities);
                continue;
            }

            var player = state.getPlayer();
            var level = player.level();
            String dimension = this.dimensionStringCache.computeIfAbsent(level.dimension(),
                    k -> k.identifier().toString());

            this.offThreadProcessor.updateDimensionContext(dimension, level);

            playerDimensions.put(player.getUUID(), dimension);

            var skipPositions = genReadyPositions != null
                    ? genReadyPositions.get(player.getUUID()) : null;
            var probes = this.probeLoadedChunks(state, level, skipPositions);
            if (!probes.isEmpty()) {
                loadedChunkProbes.put(player.getUUID(), probes);
            }
        }

        return new LifecycleResult(playerDimensions, loadedChunkProbes, activeCount, toRemove);
    }

    private void postSnapshot(LifecycleResult lifecycle,
            List<TickSnapshot.GenerationReadyData> generationReady) {
        var snapshot = new TickSnapshot(
                lifecycle.playerDimensions, lifecycle.loadedChunkProbes,
                this.config.sendQueueLimitPerPlayer, false);
        this.offThreadProcessor.postSnapshot(snapshot, generationReady);
    }

    private void flushSendQueues(int activeCount) {
        long perPlayerAllocation = this.bandwidthLimiter.getPerPlayerAllocation(activeCount);
        long perPlayerCap = Math.min(perPlayerAllocation, this.config.bytesPerSecondLimitPerPlayer);

        for (var state : this.players.values()) {
            if (!state.hasCompletedHandshake())
                continue;
            long[] dropped = state.flushSendQueue(perPlayerCap, this.bandwidthLimiter, this.diag,
                    data -> this.columnPayloadSender.send(state, data));
            if (dropped.length > 0) {
                // A send failure discarded resolved-but-undelivered columns: clear their
                // done-bits so the client's re-requests re-resolve instead of being
                // answered up-to-date for data that never arrived.
                this.offThreadProcessor.clearDiskReadDone(state.getPlayerUUID(), dropped);
            }
        }
    }

    private void tickDiagnosticsLog() {
        if (++this.diagLogCounter >= DIAG_LOG_INTERVAL_TICKS) {
            this.diagLogCounter = 0;
            DiagnosticsFormatter.logDebugSummary(this.diag, this.getUptimeSeconds(),
                    this.config.bytesPerSecondLimitGlobal, this.bandwidthLimiter, this.players.values());
        }
    }

    private Long2ObjectMap<LoadedColumnData> probeLoadedChunks(
            PaperPlayerRequestState state, ServerLevel level,
            LongOpenHashSet skipPositions) {
        var probes = new Long2ObjectOpenHashMap<LoadedColumnData>();
        int probed = 0;

        for (var req : state.getIncomingRequests()) {
            if (probed >= MAX_PROBES_PER_TICK_PER_PLAYER)
                break;
            long packed = PositionUtil.packPosition(req.cx(), req.cz());
            if (probes.containsKey(packed))
                continue;
            if (skipPositions != null && skipPositions.contains(packed))
                continue;

            var column = this.loadedColumnProbe.probe(level, req.cx(), req.cz());
            if (column != null) {
                probes.put(packed, column);
            }
            probed++;
        }

        return probes;
    }

    private void drainSendActions() {
        this.offThreadProcessor.drainSendActions((state, types, positions, count) ->
                PaperPayloadHandler.sendBatchResponse(state.getPlayer().getBukkitEntity(),
                        types, positions, count));
    }

    private void drainGenerationTicketRequests() {
        if (this.generationService == null)
            return;

        OffThreadProcessor.GenerationTicketRequest req;
        while ((req = this.offThreadProcessor.pollGenerationTicketRequest()) != null) {
            var state = this.players.get(req.playerUuid());
            if (state == null || !state.hasCompletedHandshake())
                continue;

            var player = state.getPlayer();
            var level = player.level();
            String dimension = this.dimensionStringCache.computeIfAbsent(level.dimension(),
                    k -> k.identifier().toString());
            // Ticket queued before a dimension change targets the old dimension's coordinates.
            // The admitting state was discarded by removePlayer+registerPlayer, so dropping
            // the stale ticket leaks nothing.
            if (!dimension.equals(req.dimension())) continue;
            boolean accepted = !player.isRemoved() && this.generationService.submitGeneration(
                    req.playerUuid(), level, req.cx(), req.cz(),
                    req.submissionOrder());
            if (!accepted) {
                // Capacity rejection or removed player: feed a failure outcome so the
                // processing thread frees the pending slot and tells the client ColumnNotGenerated.
                this.offThreadProcessor.feedGenerationFailure(
                        req.playerUuid(), req.cx(), req.cz(), dimension, req.submissionOrder());
            }
        }
    }

    public Map<UUID, PaperPlayerRequestState> getPlayers() {
        return Collections.unmodifiableMap(this.players);
    }

    public PaperChunkDiskReader getDiskReader() {
        return this.diskReader;
    }

    public PaperChunkGenerationService getGenerationService() {
        return this.generationService;
    }

    public SharedBandwidthLimiter getBandwidthLimiter() {
        return this.bandwidthLimiter;
    }

    public String getTickDiagnostics() {
        return this.diag.format(this.config.sendQueueLimitPerPlayer);
    }

    public TickDiagnostics getTickDiag() {
        return this.diag;
    }

    public long getWindowBandwidthRate() {
        return this.diag.getWindowBytesPerSecond();
    }

    public long getUptimeSeconds() {
        return (System.nanoTime() - this.startTimeNanos) / LSSConstants.NANOS_PER_SECOND;
    }

    public OffThreadProcessor<?> getOffThreadProcessor() {
        return this.offThreadProcessor;
    }

    public PaperConfig getConfig() {
        return this.config;
    }

    public void shutdown() {
        try {
            this.offThreadProcessor.shutdown();
        } catch (Exception e) {
            LSSLogger.error("Error shutting down off-thread processor", e);
        }
        this.players.clear();
        try {
            this.diskReader.shutdown();
        } catch (Exception e) {
            LSSLogger.error("Error shutting down disk reader", e);
        }
        try {
            if (this.generationService != null) {
                this.generationService.shutdown();
            }
        } catch (Exception e) {
            LSSLogger.error("Error shutting down generation service", e);
        }
    }
}
