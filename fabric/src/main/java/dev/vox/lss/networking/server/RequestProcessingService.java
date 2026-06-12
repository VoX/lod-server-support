package dev.vox.lss.networking.server;

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
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.BatchResponseS2CPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;

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
    private final DirtyContentFilter dirtyContentFilter = new DirtyContentFilter();

    private final long startTimeNanos = System.nanoTime();

    private final DirtyColumnBroadcaster dirtyBroadcaster;
    private final Map<ServerLevel, String> dimensionStringCache = new HashMap<>();
    private int diagLogCounter = 0;

    private final TickDiagnostics diag = new TickDiagnostics();

    private static final int DIAG_LOG_INTERVAL_TICKS = 100;
    private static final int MAX_PROBES_PER_TICK_PER_PLAYER = 512;

    public RequestProcessingService(MinecraftServer server) {
        this.server = server;
        var config = LSSServerConfig.CONFIG;

        this.dirtyTracker = new DirtyColumnTracker();

        this.diskReader = new ChunkDiskReader(config.diskReaderThreads);
        if (config.enableChunkGeneration) {
            this.generationService = new ChunkGenerationService(config);
            this.generationService.setDirtyContentFilter(this.dirtyContentFilter);
        } else {
            this.generationService = null;
        }
        this.bandwidthLimiter = new SharedBandwidthLimiter(config.bytesPerSecondLimitGlobal);

        var dataDir = server.getWorldPath(LevelResource.ROOT).resolve("data");
        this.offThreadProcessor = new FabricOffThreadProcessor(
                this.players,
                this.diskReader, this.generationService != null, dataDir,
                config.perDimensionTimestampCacheSizeMB);
        this.offThreadProcessor.start();

        this.dirtyBroadcaster = new DirtyColumnBroadcaster(
                server, this.players, this.offThreadProcessor, this.dirtyTracker);
    }

    public PlayerRequestState registerPlayer(ServerPlayer player, int capabilities) {
        var config = LSSServerConfig.CONFIG;
        var state = this.players.computeIfAbsent(player.getUUID(), uuid -> new PlayerRequestState(
                player, config.syncOnLoadConcurrencyLimitPerPlayer, config.generationConcurrencyLimitPerPlayer));
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
            state.addRequest(packedPosition, payload.clientTimestamps()[i]);
        }
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
            Map<UUID, String> playerDimensions,
            Map<UUID, Long2ObjectMap<LoadedColumnData>> loadedChunkProbes,
            int activeCount,
            List<UUID> toRemove
    ) {}

    private LifecycleResult processPlayerLifecycle(LSSServerConfig config,
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
            if (!state.hasCompletedHandshake()) continue;
            activeCount++;
            this.diag.updateQueuePeak(state.getSendQueueSize());

            boolean removed = false;

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
                // A dimension change abandons all in-flight work. Reuse the (well-tested)
                // disconnect teardown + a fresh registration instead of a second, hand-rolled
                // partial-reset protocol: the processing thread unwinds the old state's dedup
                // groups via the removal event, and the fresh state starts clean next tick.
                var player = state.getPlayer();
                int capabilities = state.getCapabilities();
                removePlayer(player.getUUID());
                registerPlayer(player, capabilities);
                continue;
            }

            var player = state.getPlayer();
            var level = player.level();
            String dimension = this.dimensionStringCache.computeIfAbsent(level,
                    l -> l.dimension().identifier().toString());

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
                               List<TickSnapshot.GenerationReadyData> generationReady,
                               LSSServerConfig config) {
        var snapshot = new TickSnapshot(
                lifecycle.playerDimensions, lifecycle.loadedChunkProbes,
                config.sendQueueLimitPerPlayer, false);
        this.offThreadProcessor.postSnapshot(snapshot, generationReady);
    }

    private void flushSendQueues(int activeCount, LSSServerConfig config) {
        long perPlayerAllocation = this.bandwidthLimiter.getPerPlayerAllocation(activeCount);
        long perPlayerCap = Math.min(perPlayerAllocation, config.bytesPerSecondLimitPerPlayer);

        for (var state : this.players.values()) {
            if (!state.hasCompletedHandshake()) continue;
            long[] dropped = state.flushSendQueue(perPlayerCap, this.bandwidthLimiter, this.diag,
                    payload -> ServerPlayNetworking.send(state.getPlayer(), payload));
            if (dropped.length > 0) {
                // A send failure discarded resolved-but-undelivered columns: clear their
                // done-bits so the client's re-requests re-resolve instead of being
                // answered up-to-date for data that never arrived.
                this.offThreadProcessor.clearDiskReadDone(state.getPlayerUUID(), dropped);
            }
        }
    }

    private void tickDirtyBroadcast(LSSServerConfig config) {
        this.dirtyBroadcaster.tick(config);
    }

    private void tickDiagnosticsLog(LSSServerConfig config) {
        if (++this.diagLogCounter >= DIAG_LOG_INTERVAL_TICKS) {
            this.diagLogCounter = 0;
            DiagnosticsFormatter.logDebugSummary(this.diag, this.getUptimeSeconds(),
                    config.bytesPerSecondLimitGlobal, this.bandwidthLimiter, this.players.values());
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
                // Deliberately NOT seeded into the DirtyContentFilter: a probe serve can land
                // between another player's edit and the chunk's cooldown save, and a seed here
                // would make that save hash equal — silencing the dirty broadcast every OTHER
                // client holding the old column needs. Only generation serves seed (freshly
                // generated content cannot be stale-held by anyone).
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
            var level = player.level();
            String dimension = this.dimensionStringCache.computeIfAbsent(level,
                    l -> l.dimension().identifier().toString());
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

    private void drainSendActions() {
        this.offThreadProcessor.drainSendActions((state, types, positions, count) ->
                ServerPlayNetworking.send(state.getPlayer(),
                        new BatchResponseS2CPayload(types, positions, count)));
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

    public OffThreadProcessor<?> getOffThreadProcessor() {
        return this.offThreadProcessor;
    }

    public DirtyColumnTracker getDirtyTracker() {
        return this.dirtyTracker;
    }

    public DirtyContentFilter getDirtyContentFilter() {
        return this.dirtyContentFilter;
    }

    public String getTickDiagnostics() {
        return this.diag.format(LSSServerConfig.CONFIG.sendQueueLimitPerPlayer);
    }

    public TickDiagnostics getTickDiag() {
        return this.diag;
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
