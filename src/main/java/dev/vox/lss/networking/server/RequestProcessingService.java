package dev.vox.lss.networking.server;

import dev.vox.lss.LSSLogger;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.payloads.CancelRequestC2SPayload;
import dev.vox.lss.networking.payloads.ChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.ChunkSectionS2CPayload;
import dev.vox.lss.networking.payloads.ColumnUpToDateS2CPayload;
import dev.vox.lss.networking.payloads.RequestCompleteS2CPayload;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.SectionPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RequestProcessingService {
    private final Map<UUID, PlayerRequestState> players = new ConcurrentHashMap<>();
    private final MinecraftServer server;
    private final ChunkDiskReader diskReader;
    private final ChunkGenerationService generationService;
    private final SharedBandwidthLimiter bandwidthLimiter;

    private record GenerationRetry(UUID playerUuid, int batchId, int cx, int cz) {}
    private GenerationRetry pendingGenRetry = null;

    private int lastTickSectionsSent = 0;
    private int lastTickDiskQueued = 0;
    private int lastTickDiskDrained = 0;
    private int lastTickBatchesCompleted = 0;
    private int lastTickSectionsSkipped = 0;
    private int lastTickGenDrained = 0;
    private int curTickSectionsSent = 0;
    private int curTickDiskQueued = 0;
    private int curTickDiskDrained = 0;
    private int curTickBatchesCompleted = 0;
    private int curTickSectionsSkipped = 0;
    private int curTickGenDrained = 0;

    public RequestProcessingService(MinecraftServer server) {
        this.server = server;
        var config = LSSServerConfig.CONFIG;
        if (config.enableDiskReading) {
            this.diskReader = new ChunkDiskReader(config.diskReaderThreads);
        } else {
            this.diskReader = null;
        }
        if (config.enableChunkGeneration) {
            this.generationService = new ChunkGenerationService(config);
        } else {
            this.generationService = null;
        }
        this.bandwidthLimiter = new SharedBandwidthLimiter(config.maxBytesPerSecondGlobal);
    }

    public PlayerRequestState registerPlayer(ServerPlayer player, int protocolVersion) {
        var state = this.players.computeIfAbsent(player.getUUID(), uuid -> new PlayerRequestState(player));
        state.markHandshakeComplete(protocolVersion);
        return state;
    }

    public void removePlayer(UUID uuid) {
        this.players.remove(uuid);
        if (this.pendingGenRetry != null && this.pendingGenRetry.playerUuid.equals(uuid)) {
            this.pendingGenRetry = null;
        }
        if (this.generationService != null) {
            this.generationService.removePlayer(uuid);
        }
    }

    public void handleRequestBatch(ServerPlayer player, ChunkRequestC2SPayload payload) {
        var state = this.players.get(player.getUUID());
        if (state == null || !state.hasCompletedHandshake()) return;

        var config = LSSServerConfig.CONFIG;

        if (state.getPendingBatchCount() >= config.maxPendingRequestsPerPlayer) {
            LSSLogger.warn("Player " + player.getName().getString() + " exceeded max pending requests, rejecting batch " + payload.batchId());
            try {
                ServerPlayNetworking.send(player, new RequestCompleteS2CPayload(
                        payload.batchId(), RequestCompleteS2CPayload.STATUS_REJECTED));
            } catch (Exception e) {
                LSSLogger.error("Failed to send rejection to " + player.getName().getString(), e);
            }
            return;
        }

        int playerCx = player.blockPosition().getX() >> 4;
        int playerCz = player.blockPosition().getZ() >> 4;
        state.addBatch(payload, config.lodDistanceChunks, playerCx, playerCz, config.maxRequestsPerBatch);
    }

    public void handleCancelRequest(ServerPlayer player, CancelRequestC2SPayload payload) {
        var state = this.players.get(player.getUUID());
        if (state == null || !state.hasCompletedHandshake()) return;
        state.cancelBatches(payload.batchIds());
    }

    public void tick() {
        if (!LSSServerConfig.CONFIG.enabled) return;

        this.resetTickDiagnostics();

        if (this.diskReader != null) {
            this.drainDiskResultsToQueues();
        }

        if (this.generationService != null) {
            this.generationService.tick(this.players);
            this.drainGenerationResultsToQueues();
        }

        int activeCount = 0;
        for (var state : this.players.values()) {
            if (state.hasCompletedHandshake()) activeCount++;
        }

        long perPlayerAllocation = this.bandwidthLimiter.getPerPlayerAllocation(activeCount);
        var config = LSSServerConfig.CONFIG;
        long perPlayerCap = Math.min(perPlayerAllocation, config.maxBytesPerSecondPerPlayer);

        for (var state : this.players.values()) {
            if (!state.hasCompletedHandshake()) continue;

            if (state.getPlayer().isRemoved()) {
                var current = this.server.getPlayerList().getPlayer(state.getPlayer().getUUID());
                if (current == null) {
                    this.removePlayer(state.getPlayer().getUUID());
                    continue;
                }
                state.updatePlayer(current);
            }

            if (state.checkDimensionChange()) {
                state.onDimensionChange();
                UUID playerUuid = state.getPlayer().getUUID();
                if (this.pendingGenRetry != null && this.pendingGenRetry.playerUuid.equals(playerUuid)) {
                    this.pendingGenRetry = null;
                }
                if (this.generationService != null) {
                    this.generationService.removePlayer(playerUuid);
                }
            }

            state.resetTickCounter();
            this.flushSendQueue(state, perPlayerCap, config);
            this.processRequestBatches(state, config);
            this.drainCompletedBatches(state);
        }
    }

    private void drainDiskResultsToQueues() {
        var config = LSSServerConfig.CONFIG;

        // Retry a previously failed generation submission before draining new results
        if (this.pendingGenRetry != null) {
            var retry = this.pendingGenRetry;
            var retryState = this.players.get(retry.playerUuid);
            if (retryState == null || !retryState.hasCompletedHandshake()) {
                this.pendingGenRetry = null;
            } else {
                var level = (ServerLevel) retryState.getPlayer().level();
                if (this.generationService.submitGeneration(
                        retry.playerUuid, retry.batchId, level, retry.cx, retry.cz)) {
                    retryState.clearPendingDiskRead(retry.cx, retry.cz);
                    retryState.markPendingGeneration(retry.cx, retry.cz);
                    this.pendingGenRetry = null;
                } else {
                    return; // Generation still full — keep backpressure on disk results
                }
            }
        }

        int processed = 0;
        ChunkDiskReader.ReadResult result;
        while (processed < 200) {
            result = this.diskReader.pollResult();
            if (result == null) break;
            processed++;

            var state = this.players.get(result.playerUuid());
            if (state == null || !state.hasCompletedHandshake()) continue;

            int cx = result.chunkX();
            int cz = result.chunkZ();

            if (result.notFound() && this.generationService != null) {
                var player = state.getPlayer();
                int playerCx = player.blockPosition().getX() >> 4;
                int playerCz = player.blockPosition().getZ() >> 4;
                int dist = Math.max(Math.abs(cx - playerCx), Math.abs(cz - playerCz));
                if (dist <= config.generationDistanceChunks
                        && state.getSendQueueSize() < config.maxSendQueueSize) {
                    var level = (ServerLevel) player.level();
                    if (this.generationService.submitGeneration(
                            result.playerUuid(), result.batchId(), level, cx, cz)) {
                        state.clearPendingDiskRead(cx, cz);
                        state.markPendingGeneration(cx, cz);
                    } else {
                        // Generation queue full — leave as pending disk read for
                        // backpressure and retry next tick
                        this.pendingGenRetry = new GenerationRetry(
                                result.playerUuid(), result.batchId(), cx, cz);
                        break;
                    }
                } else {
                    state.clearPendingDiskRead(cx, cz);
                    state.markDiskReadDone(cx, cz);
                }
            } else {
                state.clearPendingDiskRead(cx, cz);
                state.markDiskReadDone(cx, cz);

                if (!result.notFound()) {
                    if (result.upToDate()) {
                        state.getSendQueue().addLast(new PlayerRequestState.QueuedPayload(
                                new ColumnUpToDateS2CPayload(cx, cz),
                                result.batchId(), 8));
                    } else {
                        for (var payload : result.payloads()) {
                            state.getSendQueue().addLast(new PlayerRequestState.QueuedPayload(
                                    payload, result.batchId(), payload.sectionData().length + 20));
                        }
                    }
                }
            }
            this.curTickDiskDrained++;
        }
    }

    private void drainGenerationResultsToQueues() {
        int processed = 0;

        ChunkDiskReader.ReadResult result;
        while (processed < 200) {
            result = this.generationService.pollResult();
            if (result == null) break;
            processed++;

            var state = this.players.get(result.playerUuid());
            if (state == null || !state.hasCompletedHandshake()) continue;

            state.clearPendingGeneration(result.chunkX(), result.chunkZ());

            for (var payload : result.payloads()) {
                state.getSendQueue().addLast(new PlayerRequestState.QueuedPayload(
                        payload, result.batchId(), payload.sectionData().length + 20));
            }
            this.curTickGenDrained++;
        }
    }

    private void flushSendQueue(PlayerRequestState state, long allocationBytes, LSSServerConfig config) {
        var queue = state.getSendQueue();

        while (!queue.isEmpty()) {
            if (state.getSectionsSentThisTick() >= config.maxSectionsPerTickPerPlayer) return;
            if (!state.canSend(allocationBytes)) return;

            var queued = queue.pollFirst();
            try {
                ServerPlayNetworking.send(state.getPlayer(), queued.payload);
                state.recordSend(queued.estimatedBytes);
                this.bandwidthLimiter.recordSend(queued.estimatedBytes);
                this.curTickSectionsSent++;
            } catch (Exception e) {
                LSSLogger.error("Failed to send queued payload to " + state.getPlayer().getName().getString(), e);
            }
        }
    }

    private static final int MAX_WORK_PER_TICK_PER_PLAYER = 512;
    private static final int MAX_SERIALIZATIONS_PER_TICK = 1000;

    private int serializationsThisTick = 0;

    private void processRequestBatches(PlayerRequestState state, LSSServerConfig config) {
        int workDone = 0;

        while (workDone < MAX_WORK_PER_TICK_PER_PLAYER) {
            var batch = state.nextBatchToProcess();
            if (batch == null) break;

            if (state.getSectionsSentThisTick() >= config.maxSectionsPerTickPerPlayer) break;
            if (state.getSendQueueSize() >= config.maxSendQueueSize) break;

            var player = state.getPlayer();
            var level = (ServerLevel) player.level();
            int playerCx = player.blockPosition().getX() >> 4;
            int playerCz = player.blockPosition().getZ() >> 4;

            while (batch.nextPositionIndex < batch.positions.length && workDone < MAX_WORK_PER_TICK_PER_PLAYER) {
                if (state.getSectionsSentThisTick() >= config.maxSectionsPerTickPerPlayer) return;
                if (state.getSendQueueSize() >= config.maxSendQueueSize) return;
                if (this.serializationsThisTick >= MAX_SERIALIZATIONS_PER_TICK) return;

                int posIndex = batch.nextPositionIndex;
                long packed = batch.positions[posIndex];
                long clientTimestamp = batch.timestamps[posIndex];
                int cx = ChunkRequestC2SPayload.unpackX(packed);
                int cz = ChunkRequestC2SPayload.unpackZ(packed);
                batch.nextPositionIndex++;

                if (state.hasDiskReadDone(cx, cz) || state.isPendingDiskRead(cx, cz)
                        || state.isPendingGeneration(cx, cz)) {
                    continue;
                }

                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk != null) {
                    // Always serialize loaded chunks — isUnsaved() only tracks changes
                    // since last save, not since the client last received data. Loaded
                    // chunk serialization is cheap (no disk I/O) so the overhead is minimal.
                    long columnTimestamp = level.getGameTime();
                    this.sendLoadedChunkColumn(state, level, chunk, cx, cz, batch.batchId, columnTimestamp);
                    workDone++;
                } else if (this.diskReader != null && config.enableDiskReading) {
                    if (state.getPendingDiskReadCount() < config.maxConcurrentDiskReads
                            && state.getSendQueueSize() < config.maxSendQueueSize) {
                        state.markPendingDiskRead(cx, cz);
                        this.diskReader.submitRead(player.getUUID(), batch.batchId, level, cx, cz, clientTimestamp);
                        this.curTickDiskQueued++;
                        workDone++;
                    } else {
                        batch.nextPositionIndex--;
                        return;
                    }
                } else if (this.generationService != null) {
                    int dist = Math.max(Math.abs(cx - playerCx), Math.abs(cz - playerCz));
                    if (dist <= config.generationDistanceChunks
                            && state.getSendQueueSize() < config.maxSendQueueSize) {
                        if (this.generationService.submitGeneration(
                                player.getUUID(), batch.batchId, level, cx, cz)) {
                            state.markPendingGeneration(cx, cz);
                            workDone++;
                        } else {
                            batch.nextPositionIndex--;
                            return;
                        }
                    }
                }
            }
        }
    }

    private void sendLoadedChunkColumn(PlayerRequestState state, ServerLevel level,
                                        LevelChunk chunk, int cx, int cz, int batchId,
                                        long columnTimestamp) {
        int minSectionY = level.getMinSectionY();
        var sections = chunk.getSections();

        int minSurfaceSectionY = Integer.MIN_VALUE;
        if (LSSServerConfig.CONFIG.skipUndergroundSections && !level.dimensionType().hasCeiling()) {
            minSurfaceSectionY = computeMinSurfaceSectionY(chunk, LSSServerConfig.CONFIG.undergroundSkipMargin);
        }

        for (int i = 0; i < sections.length; i++) {
            int sectionY = minSectionY + i;
            var section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;
            if (sectionY < minSurfaceSectionY) {
                this.curTickSectionsSkipped++;
                continue;
            }

            var payload = serializeLoadedSection(level, section, cx, sectionY, cz, columnTimestamp);
            if (payload != null) {
                state.getSendQueue().addLast(new PlayerRequestState.QueuedPayload(
                        payload, batchId, payload.sectionData().length + 20));
                this.serializationsThisTick++;
            }
        }
    }

    static int computeMinSurfaceSectionY(LevelChunk chunk, int margin) {
        int minY = Integer.MAX_VALUE;
        int noSurface = chunk.getMinY() - 1;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int h = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
                if (h > noSurface) {
                    minY = Math.min(minY, h);
                }
            }
        }
        if (minY == Integer.MAX_VALUE) return Integer.MIN_VALUE;
        return (minY >> 4) - margin;
    }

    static ChunkSectionS2CPayload serializeLoadedSection(
            ServerLevel level, LevelChunkSection section, int cx, int sectionY, int cz,
            long columnTimestamp) {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(8192), level.registryAccess());
        try {
            section.write(buf);
            byte[] sectionData = new byte[buf.readableBytes()];
            buf.readBytes(sectionData);

            byte lightFlags = 0;
            byte[] blockLight = null;
            byte[] skyLight = null;

            if (LSSServerConfig.CONFIG.sendLightData) {
                var sectionPos = SectionPos.of(cx, sectionY, cz);
                var blLayer = level.getLightEngine().getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
                var slLayer = level.getLightEngine().getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos);

                if (blLayer != null && !blLayer.isEmpty()) {
                    lightFlags |= 1;
                    blockLight = blLayer.getData().clone();
                }
                if (slLayer != null && !slLayer.isEmpty()) {
                    lightFlags |= 2;
                    skyLight = slLayer.getData().clone();
                }
            }

            return new ChunkSectionS2CPayload(
                    cx, sectionY, cz, level.dimension(),
                    sectionData, lightFlags, blockLight, skyLight, columnTimestamp
            );
        } catch (Exception e) {
            LSSLogger.error("Failed to serialize section at " + cx + "," + sectionY + "," + cz, e);
            return null;
        } finally {
            buf.release();
        }
    }

    private void drainCompletedBatches(PlayerRequestState state) {
        var iter = state.drainCompletedBatches();
        while (iter.hasNext()) {
            var batch = iter.next();
            state.clearDiskReadDoneForBatch(batch.positions);
            int status = batch.cancelled
                    ? RequestCompleteS2CPayload.STATUS_CANCELLED
                    : RequestCompleteS2CPayload.STATUS_DONE;
            try {
                ServerPlayNetworking.send(state.getPlayer(),
                        new RequestCompleteS2CPayload(batch.batchId, status));
                this.curTickBatchesCompleted++;
            } catch (Exception e) {
                LSSLogger.error("Failed to send batch complete to " + state.getPlayer().getName().getString(), e);
            }
        }
    }

    private void resetTickDiagnostics() {
        this.lastTickSectionsSent = this.curTickSectionsSent;
        this.lastTickDiskQueued = this.curTickDiskQueued;
        this.lastTickDiskDrained = this.curTickDiskDrained;
        this.lastTickBatchesCompleted = this.curTickBatchesCompleted;
        this.lastTickSectionsSkipped = this.curTickSectionsSkipped;
        this.lastTickGenDrained = this.curTickGenDrained;
        this.curTickSectionsSent = 0;
        this.curTickDiskQueued = 0;
        this.curTickDiskDrained = 0;
        this.curTickBatchesCompleted = 0;
        this.curTickSectionsSkipped = 0;
        this.curTickGenDrained = 0;
        this.serializationsThisTick = 0;
    }

    public Map<UUID, PlayerRequestState> getPlayers() {
        return java.util.Collections.unmodifiableMap(this.players);
    }

    public ChunkDiskReader getDiskReader() {
        return this.diskReader;
    }

    public ChunkGenerationService getGenerationService() {
        return this.generationService;
    }

    public String getTickDiagnostics() {
        var sb = new StringBuilder();
        sb.append(String.format("last_tick: sections_sent=%d, sections_skipped=%d, disk_queued=%d, disk_drained=%d, batches_completed=%d",
                lastTickSectionsSent, lastTickSectionsSkipped, lastTickDiskQueued, lastTickDiskDrained, lastTickBatchesCompleted));
        if (this.generationService != null) {
            sb.append(String.format(", gen_drained=%d", lastTickGenDrained));
        }
        return sb.toString();
    }

    public void shutdown() {
        this.players.clear();
        if (this.diskReader != null) {
            this.diskReader.shutdown();
        }
        if (this.generationService != null) {
            this.generationService.shutdown();
        }
    }
}
