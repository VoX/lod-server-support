package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.SharedBandwidthLimiter;
import dev.vox.lss.common.processing.LoadedColumnData;
import dev.vox.lss.common.processing.OffThreadProcessor;
import dev.vox.lss.common.processing.SendAction;
import dev.vox.lss.common.processing.SendActionBatcher;
import dev.vox.lss.common.processing.TickDiagnostics;
import dev.vox.lss.common.processing.TickSnapshot;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
    private final Map<UUID, PaperPlayerRequestState> players = new ConcurrentHashMap<>();
    private final MinecraftServer server;
    private final PaperChunkDiskReader diskReader;
    private final PaperChunkGenerationService generationService;
    private final SharedBandwidthLimiter bandwidthLimiter;
    private final PaperConfig config;
    private final PaperOffThreadProcessor offThreadProcessor;
    private final DirtyColumnTracker dirtyTracker;
    private final PaperDirtyColumnBroadcaster dirtyBroadcaster;

    private final long startTimeNanos = System.nanoTime();
    private final Map<ServerLevel, String> dimensionStringCache = new HashMap<>();

    private int diagLogCounter = 0;

    private final TickDiagnostics diag = new TickDiagnostics();

    // Reused per-tick maps (single-threaded on server thread)
    private final Map<UUID, TickSnapshot.PlayerTickData> reusablePlayerTickData = new HashMap<>();
    private final Map<UUID, Long2ObjectMap<LoadedColumnData>> reusableLoadedChunkProbes = new HashMap<>();
    private final SendActionBatcher sendActionBatcher = new SendActionBatcher();

    private static final int DIAG_LOG_INTERVAL_TICKS = 100;
    private static final int MAX_PROBES_PER_TICK_PER_PLAYER = 512;

    public PaperRequestProcessingService(MinecraftServer server, Plugin plugin, PaperConfig config) {
        this.server = server;
        this.config = config;

        this.diskReader = new PaperChunkDiskReader(config.diskReaderThreads);
        if (config.enableChunkGeneration) {
            this.generationService = new PaperChunkGenerationService(config, plugin);
        } else {
            this.generationService = null;
        }
        this.bandwidthLimiter = new SharedBandwidthLimiter(config.bytesPerSecondLimitGlobal);

        var dataDir = server.getWorldPath(LevelResource.ROOT).resolve("data");
        this.offThreadProcessor = new PaperOffThreadProcessor(
                this.players,
                this.diskReader, this.generationService, dataDir);
        this.offThreadProcessor.start();

        this.dirtyTracker = new DirtyColumnTracker();
        this.dirtyBroadcaster = new PaperDirtyColumnBroadcaster(
                server, this.players, this.dirtyTracker, this.offThreadProcessor);
    }

    public DirtyColumnTracker getDirtyTracker() {
        return this.dirtyTracker;
    }

    public PaperPlayerRequestState registerPlayer(ServerPlayer player, int capabilities) {
        var state = this.players.computeIfAbsent(player.getUUID(), uuid -> new PaperPlayerRequestState(
                player, this.config.syncOnLoadRateLimitPerPlayer, this.config.syncOnLoadConcurrencyLimitPerPlayer,
                this.config.generationRateLimitPerPlayer, this.config.generationConcurrencyLimitPerPlayer));
        this.diskReader.registerPlayer(player.getUUID());
        if (this.generationService != null) this.generationService.registerPlayer(player.getUUID());
        state.setCapabilities(capabilities);
        state.markHandshakeComplete();
        return state;
    }

    public void removePlayer(UUID uuid) {
        this.players.remove(uuid);
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
            state.addRequest(batch.requestIds()[i], cx, cz, batch.clientTimestamps()[i]);
        }
    }

    public void handleCancel(ServerPlayer player, int requestId) {
        var state = this.players.get(player.getUUID());
        if (state == null || !state.hasCompletedHandshake())
            return;
        state.addCancel(requestId);
    }

    public void handleBandwidthUpdate(ServerPlayer player, long desiredRate) {
        var state = this.players.get(player.getUUID());
        if (state == null || !state.hasCompletedHandshake())
            return;
        state.setDesiredBandwidth(desiredRate);
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
            Map<UUID, TickSnapshot.PlayerTickData> playerTickData,
            Map<UUID, Long2ObjectMap<LoadedColumnData>> loadedChunkProbes,
            int activeCount,
            List<UUID> toRemove) {
    }

    private LifecycleResult processPlayerLifecycle(
            List<TickSnapshot.GenerationReadyData> generationReady) {
        this.reusablePlayerTickData.clear();
        this.reusableLoadedChunkProbes.clear();
        Map<UUID, TickSnapshot.PlayerTickData> playerTickData = this.reusablePlayerTickData;
        Map<UUID, Long2ObjectMap<LoadedColumnData>> loadedChunkProbes = this.reusableLoadedChunkProbes;

        // Build per-player set of generation-ready packed positions to skip in probeLoadedChunks
        Map<UUID, LongOpenHashSet> genReadyPositions = null;
        if (!generationReady.isEmpty()) {
            genReadyPositions = new HashMap<>();
            for (var genData : generationReady) {
                genReadyPositions.computeIfAbsent(genData.playerUuid(),
                        k -> new LongOpenHashSet())
                        .add(PositionUtil.packPosition(genData.columnData().cx(), genData.columnData().cz()));
            }
        }

        int activeCount = 0;
        List<UUID> toRemove = null;
        for (var state : this.players.values()) {
            if (!state.hasCompletedHandshake())
                continue;
            activeCount++;
            this.diag.updateQueuePeak(state.getSendQueueSize());

            boolean removed = false;
            boolean dimensionChanged = false;

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

            if (!removed && state.checkDimensionChange()) {
                state.onDimensionChange();
                cleanupPlayerServices(state.getPlayer().getUUID());
                // Re-create result queues so disk reads and generation results
                // are not silently dropped after dimension change
                this.diskReader.registerPlayer(state.getPlayer().getUUID());
                if (this.generationService != null) this.generationService.registerPlayer(state.getPlayer().getUUID());
                dimensionChanged = true;
            }

            if (removed)
                continue;

            var player = state.getPlayer();
            var level = (ServerLevel) player.level();
            String dimension = this.dimensionStringCache.computeIfAbsent(level,
                    l -> l.dimension().identifier().toString());

            this.offThreadProcessor.updateDimensionContext(dimension, level);

            playerTickData.put(player.getUUID(), new TickSnapshot.PlayerTickData(
                    dimension, dimensionChanged));

            if (!dimensionChanged) {
                var skipPositions = genReadyPositions != null
                        ? genReadyPositions.get(player.getUUID()) : null;
                var probes = this.probeLoadedChunks(state, level, skipPositions);
                if (!probes.isEmpty()) {
                    loadedChunkProbes.put(player.getUUID(), probes);
                }
            }
        }

        return new LifecycleResult(playerTickData, loadedChunkProbes, activeCount, toRemove);
    }

    private void postSnapshot(LifecycleResult lifecycle,
            List<TickSnapshot.GenerationReadyData> generationReady) {
        var removed = lifecycle.toRemove != null ? lifecycle.toRemove : List.<UUID>of();
        var snapshot = new TickSnapshot(
                lifecycle.playerTickData, lifecycle.loadedChunkProbes, generationReady,
                removed, this.config.sendQueueLimitPerPlayer, false);
        this.offThreadProcessor.postSnapshot(snapshot);
    }

    private void flushSendQueues(int activeCount) {
        long perPlayerAllocation = this.bandwidthLimiter.getPerPlayerAllocation(activeCount);
        long perPlayerCap = Math.min(perPlayerAllocation, this.config.bytesPerSecondLimitPerPlayer);

        for (var state : this.players.values()) {
            if (!state.hasCompletedHandshake())
                continue;
            long effective = Math.min(perPlayerCap, Math.max(1, state.getDesiredBandwidth()));
            this.flushSendQueue(state, effective);
        }
    }

    private void tickDiagnosticsLog() {
        if (++this.diagLogCounter >= DIAG_LOG_INTERVAL_TICKS) {
            this.diagLogCounter = 0;
            if (LSSLogger.isDebugEnabled()) {
                long uptimeSec = this.getUptimeSeconds();
                long bwRate = uptimeSec > 0 ? this.bandwidthLimiter.getTotalBytesSent() / uptimeSec : 0;
                LSSLogger.debug(this.diag.formatSummary(bwRate, this.config.bytesPerSecondLimitGlobal));
                for (var state : this.players.values()) {
                    if (!state.hasCompletedHandshake()) continue;
                    var rl = state.getRateLimiters();
                    LSSLogger.debug(String.format("  %s: sq=%d, psync=%d, pgen=%d, syncCC=%d/%d, genCC=%d/%d, wq=%d",
                            state.getPlayer().getName().getString(), state.getSendQueueSize(),
                            state.getPendingSyncCount(), state.getPendingGenerationCount(),
                            rl.syncOnLoad().getCurrentConcurrency(), rl.syncOnLoad().getMaxConcurrency(),
                            rl.generation().getCurrentConcurrency(), rl.generation().getMaxConcurrency(),
                            state.getWaitingQueueSize()));
                }
            }
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

            LevelChunk chunk = level.getChunkSource().getChunkNow(req.cx(), req.cz());
            if (chunk != null) {
                probes.put(packed, PaperSectionSerializer.serializeColumn(level, chunk, req.cx(), req.cz()));
            }
            probed++;
        }

        return probes;
    }

    private void drainSendActions() {
        this.sendActionBatcher.clear();

        SendAction action;
        while ((action = this.offThreadProcessor.pollSendAction()) != null) {
            var state = this.players.get(action.playerUuid());
            if (state == null || !state.hasCompletedHandshake()) continue;
            this.sendActionBatcher.add(action.playerUuid(), action.responseType(), action.requestId());
        }

        if (this.sendActionBatcher.isEmpty()) return;

        this.sendActionBatcher.forEach((uuid, types, ids, count) -> {
            var state = this.players.get(uuid);
            if (state == null || !state.hasCompletedHandshake()) return;
            try {
                var bukkitPlayer = state.getPlayer().getBukkitEntity();
                PaperPayloadHandler.sendBatchResponse(bukkitPlayer, types, ids, count);
            } catch (Exception e) {
                LSSLogger.error("Failed to send batch response to " + state.getPlayer().getName().getString(), e);
            }
        });
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
            if (player.isRemoved())
                continue;
            var level = (ServerLevel) player.level();
            boolean accepted = this.generationService.submitGeneration(
                    req.playerUuid(), req.requestId(), level, req.cx(), req.cz(),
                    req.submissionOrder());
            if (!accepted) {
                this.generationService.addResult(req.playerUuid(), PaperChunkDiskReader.emptyResult(
                        req.playerUuid(), req.requestId(), req.cx(), req.cz(), req.submissionOrder()));
            }
        }
    }

    private void flushSendQueue(PaperPlayerRequestState state, long allocationBytes) {
        state.drainReadyPayloads();
        var queue = state.getSendQueue();

        while (!queue.isEmpty()) {
            if (!state.canSend(allocationBytes))
                return;

            var queued = queue.peek();
            try {
                var bukkitPlayer = state.getPlayer().getBukkitEntity();
                PaperPayloadHandler.sendRawNmsPayload(bukkitPlayer,
                        PaperPayloadHandler.ID_VOXEL_COLUMN, queued.data());
                queue.poll();
                state.recordSend(queued.estimatedBytes());
                this.bandwidthLimiter.recordSend(queued.estimatedBytes());
                this.diag.recordSectionSent(queued.estimatedBytes());
            } catch (Exception e) {
                LSSLogger.error("Failed to send queued payload to " + state.getPlayer().getName().getString()
                        + ", dropping remaining queue (" + queue.size() + " entries)", e);
                queue.clear();
                return;
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

    public long getWindowBandwidthRate() {
        return this.diag.getWindowBytesPerSecond();
    }

    public long getUptimeSeconds() {
        return (System.nanoTime() - this.startTimeNanos) / LSSConstants.NANOS_PER_SECOND;
    }

    public OffThreadProcessor<?, ?> getOffThreadProcessor() {
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
