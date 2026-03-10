package dev.vox.lss.networking.server;

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
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.BatchResponseS2CPayload;
import dev.vox.lss.networking.payloads.BandwidthUpdateC2SPayload;
import dev.vox.lss.networking.payloads.CancelRequestC2SPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RequestProcessingService {
    private final Map<UUID, PlayerRequestState> players = new ConcurrentHashMap<>();
    private final MinecraftServer server;
    private final ChunkDiskReader diskReader;
    private final ChunkGenerationService generationService;
    private final SharedBandwidthLimiter bandwidthLimiter;
    private final FabricOffThreadProcessor offThreadProcessor;
    private final DirtyColumnTracker dirtyTracker;

    private final long startTimeNanos = System.nanoTime();

    private final DirtyColumnBroadcaster dirtyBroadcaster;
    private final Map<ServerLevel, String> dimensionStringCache = new HashMap<>();
    private int diagLogCounter = 0;

    private final TickDiagnostics diag = new TickDiagnostics();

    // Reused per-tick maps (single-threaded on server thread)
    private final Map<UUID, TickSnapshot.PlayerTickData> reusablePlayerTickData = new HashMap<>();
    private final Map<UUID, Long2ObjectMap<LoadedColumnData>> reusableLoadedChunkProbes = new HashMap<>();
    private final SendActionBatcher sendActionBatcher = new SendActionBatcher();

    private static final int DIAG_LOG_INTERVAL_TICKS = 100;
    private static final int MAX_PROBES_PER_TICK_PER_PLAYER = 512;

    public RequestProcessingService(MinecraftServer server) {
        this.server = server;
        var config = LSSServerConfig.CONFIG;

        this.dirtyTracker = new DirtyColumnTracker();

        this.diskReader = new ChunkDiskReader(config.diskReaderThreads);
        if (config.enableChunkGeneration) {
            this.generationService = new ChunkGenerationService(config);
        } else {
            this.generationService = null;
        }
        this.bandwidthLimiter = new SharedBandwidthLimiter(config.bytesPerSecondLimitGlobal);

        this.offThreadProcessor = new FabricOffThreadProcessor(
                this.players,
                this.diskReader, this.generationService);
        this.offThreadProcessor.start();

        this.dirtyBroadcaster = new DirtyColumnBroadcaster(
                server, this.players, this.offThreadProcessor, this.dirtyTracker);
    }

    public PlayerRequestState registerPlayer(ServerPlayer player, int capabilities) {
        var config = LSSServerConfig.CONFIG;
        var state = this.players.computeIfAbsent(player.getUUID(), uuid -> new PlayerRequestState(
                player, config.syncOnLoadRateLimitPerPlayer, config.syncOnLoadConcurrencyLimitPerPlayer,
                config.generationRateLimitPerPlayer, config.generationConcurrencyLimitPerPlayer));
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
        if (this.generationService != null) this.generationService.removePlayer(uuid);
    }

    public void handleBatchRequest(ServerPlayer player, BatchChunkRequestC2SPayload payload) {
        var state = this.players.get(player.getUUID());
        if (state == null || !state.hasCompletedHandshake()) return;

        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        int maxDist = LSSServerConfig.CONFIG.lodDistanceChunks + LSSConstants.LOD_DISTANCE_BUFFER;

        for (int i = 0; i < payload.count(); i++) {
            long packedPosition = payload.packedPositions()[i];
            int cx = PositionUtil.unpackX(packedPosition);
            int cz = PositionUtil.unpackZ(packedPosition);
            if (PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) > maxDist) continue;
            state.addRequest(payload.requestIds()[i], packedPosition, payload.clientTimestamps()[i]);
        }
    }

    public void handleCancel(ServerPlayer player, CancelRequestC2SPayload payload) {
        var state = this.players.get(player.getUUID());
        if (state == null || !state.hasCompletedHandshake()) return;
        state.addCancel(payload.requestId());
    }

    public void handleBandwidthUpdate(ServerPlayer player, BandwidthUpdateC2SPayload payload) {
        var state = this.players.get(player.getUUID());
        if (state == null || !state.hasCompletedHandshake()) return;
        state.setDesiredBandwidth(payload.desiredRate());
    }

    public void tick() {
        if (!LSSServerConfig.CONFIG.enabled) return;

        this.diag.reset(this.offThreadProcessor.getDiagnostics());

        var config = LSSServerConfig.CONFIG;
        var generationReady = tickGenerationService();
        var lifecycle = processPlayerLifecycle(config, generationReady);

        if (lifecycle.toRemove != null) {
            for (UUID uuid : lifecycle.toRemove) this.removePlayer(uuid);
        }

        postSnapshot(lifecycle, generationReady, config);
        this.drainSendActions();
        this.drainGenerationTicketRequests();
        flushSendQueues(lifecycle.activeCount, config);
        tickDirtyBroadcast(config);
        tickDiagnosticsLog(config);
    }

    private List<TickSnapshot.GenerationReadyData> tickGenerationService() {
        if (this.generationService == null) return List.of();
        return this.generationService.tick();
    }

    private record LifecycleResult(
            Map<UUID, TickSnapshot.PlayerTickData> playerTickData,
            Map<UUID, Long2ObjectMap<LoadedColumnData>> loadedChunkProbes,
            int activeCount,
            List<UUID> toRemove
    ) {}

    private LifecycleResult processPlayerLifecycle(LSSServerConfig config,
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
            if (!state.hasCompletedHandshake()) continue;
            activeCount++;
            this.diag.updateQueuePeak(state.getSendQueueSize());

            boolean removed = false;
            boolean dimensionChanged = false;

            if (state.getPlayer().isRemoved()) {
                var current = this.server.getPlayerList().getPlayer(state.getPlayer().getUUID());
                if (current == null) {
                    if (toRemove == null) toRemove = new ArrayList<>();
                    toRemove.add(state.getPlayer().getUUID());
                    removed = true;
                } else {
                    state.updatePlayer(current);
                }
            }

            if (removed) continue;

            if (state.checkDimensionChange()) {
                state.onDimensionChange();
                cleanupPlayerServices(state.getPlayer().getUUID());
                dimensionChanged = true;
            }

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
                               List<TickSnapshot.GenerationReadyData> generationReady,
                               LSSServerConfig config) {
        var removed = lifecycle.toRemove != null ? lifecycle.toRemove : List.<UUID>of();
        var snapshot = new TickSnapshot(
                lifecycle.playerTickData, lifecycle.loadedChunkProbes, generationReady,
                removed, config.sendQueueLimitPerPlayer, false);
        this.offThreadProcessor.postSnapshot(snapshot);
    }

    private void flushSendQueues(int activeCount, LSSServerConfig config) {
        long perPlayerAllocation = this.bandwidthLimiter.getPerPlayerAllocation(activeCount);
        long perPlayerCap = Math.min(perPlayerAllocation, config.bytesPerSecondLimitPerPlayer);

        for (var state : this.players.values()) {
            if (!state.hasCompletedHandshake()) continue;
            long effective = Math.min(perPlayerCap, Math.max(1, state.getDesiredBandwidth()));
            this.flushSendQueue(state, effective);
        }
    }

    private void tickDirtyBroadcast(LSSServerConfig config) {
        this.dirtyBroadcaster.tick(config);
    }

    private void tickDiagnosticsLog(LSSServerConfig config) {
        if (++this.diagLogCounter >= DIAG_LOG_INTERVAL_TICKS) {
            this.diagLogCounter = 0;
            if (LSSLogger.isDebugEnabled()) {
                long uptimeSec = this.getUptimeSeconds();
                long bwRate = uptimeSec > 0 ? this.bandwidthLimiter.getTotalBytesSent() / uptimeSec : 0;
                LSSLogger.debug(this.diag.formatSummary(bwRate, config.bytesPerSecondLimitGlobal));
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

    /**
     * Probe loaded chunks for positions in the player's incoming requests.
     * Called on the main thread. Serializes loaded chunks so the processing thread
     * can compress and send without touching MC world state.
     *
     * @param skipPositions packed positions already extracted by the generation service (may be null)
     */
    private Long2ObjectMap<LoadedColumnData> probeLoadedChunks(
            PlayerRequestState state, ServerLevel level,
            LongOpenHashSet skipPositions) {
        var probes = new Long2ObjectOpenHashMap<LoadedColumnData>();
        int probed = 0;

        for (var req : state.getIncomingRequests()) {
            if (probed >= MAX_PROBES_PER_TICK_PER_PLAYER) break;
            long packed = PositionUtil.packPosition(req.cx(), req.cz());
            if (probes.containsKey(packed)) continue;
            if (skipPositions != null && skipPositions.contains(packed)) continue;

            LevelChunk chunk = level.getChunkSource().getChunkNow(req.cx(), req.cz());
            if (chunk != null) {
                probes.put(packed, SectionSerializer.serializeColumn(level, chunk, req.cx(), req.cz()));
            }
            probed++;
        }

        return probes;
    }

    /**
     * Drain generation ticket requests from the processing thread and submit MC tickets.
     * Must run on main thread (ticket management requires MC world state).
     */
    private void drainGenerationTicketRequests() {
        if (this.generationService == null) return;

        OffThreadProcessor.GenerationTicketRequest req;
        while ((req = this.offThreadProcessor.pollGenerationTicketRequest()) != null) {
            var state = this.players.get(req.playerUuid());
            if (state == null || !state.hasCompletedHandshake()) continue;

            var player = state.getPlayer();
            if (player.isRemoved()) continue;
            var level = (ServerLevel) player.level();
            boolean accepted = this.generationService.submitGeneration(
                    req.playerUuid(), req.requestId(), level, req.cx(), req.cz(),
                    req.submissionOrder());
            if (!accepted) {
                this.generationService.addResult(req.playerUuid(), ChunkDiskReader.emptyResult(
                        req.playerUuid(), req.requestId(), req.cx(), req.cz(), req.submissionOrder()));
            }
        }
    }

    /**
     * Drain send actions produced by the processing thread and batch into
     * one {@link BatchResponseS2CPayload} per player per tick.
     */
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
                ServerPlayNetworking.send(state.getPlayer(),
                        new BatchResponseS2CPayload(types, ids, count));
            } catch (Exception e) {
                LSSLogger.error("Failed to send batch response to " + state.getPlayer().getName().getString(), e);
            }
        });
    }

    private void flushSendQueue(PlayerRequestState state, long allocationBytes) {
        state.drainReadyPayloads();
        var queue = state.getSendQueue();

        while (!queue.isEmpty()) {
            if (!state.canSend(allocationBytes)) return;

            var queued = queue.peek();
            try {
                ServerPlayNetworking.send(state.getPlayer(), queued.payload());
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


    public Map<UUID, PlayerRequestState> getPlayers() {
        return Collections.unmodifiableMap(this.players);
    }

    public ChunkDiskReader getDiskReader() {
        return this.diskReader;
    }

    public ChunkGenerationService getGenerationService() {
        return this.generationService;
    }

    public SharedBandwidthLimiter getBandwidthLimiter() {
        return this.bandwidthLimiter;
    }

    public long getUptimeSeconds() {
        return (System.nanoTime() - this.startTimeNanos) / LSSConstants.NANOS_PER_SECOND;
    }

    public OffThreadProcessor<?, ?> getOffThreadProcessor() {
        return this.offThreadProcessor;
    }

    public DirtyColumnTracker getDirtyTracker() {
        return this.dirtyTracker;
    }

    public String getTickDiagnostics() {
        return this.diag.format(LSSServerConfig.CONFIG.sendQueueLimitPerPlayer);
    }

    public long getWindowBandwidthRate() {
        return this.diag.getWindowBytesPerSecond();
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
