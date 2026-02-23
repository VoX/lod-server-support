package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.LSSUtil;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.SharedBandwidthLimiter;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.payloads.CancelRequestC2SPayload;
import dev.vox.lss.networking.payloads.ChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.ChunkSectionS2CPayload;
import dev.vox.lss.networking.payloads.ColumnUpToDateS2CPayload;
import dev.vox.lss.networking.payloads.DirtyColumnsS2CPayload;
import dev.vox.lss.networking.payloads.RequestCompleteS2CPayload;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.SectionPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.ArrayList;
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

    private int dirtyBroadcastCounter = 0;

    private int lastTickSectionsSent = 0;
    private int lastTickDiskQueued = 0;
    private int lastTickDiskDrained = 0;
    private int lastTickBatchesCompleted = 0;
    private int lastTickGenDrained = 0;
    private int curTickSectionsSent = 0;
    private int curTickDiskQueued = 0;
    private int curTickDiskDrained = 0;
    private int curTickBatchesCompleted = 0;
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

    public PlayerRequestState registerPlayer(ServerPlayer player) {
        var state = this.players.computeIfAbsent(player.getUUID(), uuid -> new PlayerRequestState(player));
        state.markHandshakeComplete();
        return state;
    }

    public void removePlayer(UUID uuid) {
        this.players.remove(uuid);
        if (this.diskReader != null) {
            this.diskReader.removePlayerResults(uuid);
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

        List<UUID> toRemove = null;
        for (var state : this.players.values()) {
            if (!state.hasCompletedHandshake()) continue;

            if (state.getPlayer().isRemoved()) {
                var current = this.server.getPlayerList().getPlayer(state.getPlayer().getUUID());
                if (current == null) {
                    if (toRemove == null) toRemove = new ArrayList<>();
                    toRemove.add(state.getPlayer().getUUID());
                    continue;
                }
                state.updatePlayer(current);
            }

            if (state.checkDimensionChange()) {
                state.onDimensionChange();
                UUID playerUuid = state.getPlayer().getUUID();
                if (this.generationService != null) {
                    this.generationService.removePlayer(playerUuid);
                }
                if (this.diskReader != null) {
                    this.diskReader.removePlayerResults(playerUuid);
                }
            }

            state.resetTickCounter();
            this.flushSendQueue(state, perPlayerCap, config);
            this.processRequestBatches(state, config);
            this.drainCompletedBatches(state);
        }
        if (toRemove != null) {
            for (UUID uuid : toRemove) this.removePlayer(uuid);
        }

        int intervalTicks = config.dirtyBroadcastIntervalSeconds * 20;
        if (++this.dirtyBroadcastCounter >= intervalTicks) {
            this.dirtyBroadcastCounter = 0;
            this.broadcastDirtyColumns(config);
        }
    }

    private void broadcastDirtyColumns(LSSServerConfig config) {
        long[] filterBuf = null;

        for (var level : this.server.getAllLevels()) {
            var dimension = level.dimension();
            long[] dirty = ChunkChangeTracker.drainDirty(dimension);
            if (dirty == null || dirty.length == 0) continue;

            int bufLen = Math.min(dirty.length, DirtyColumnsS2CPayload.MAX_POSITIONS);
            if (filterBuf == null || filterBuf.length < bufLen) {
                filterBuf = new long[bufLen];
            }

            for (var state : this.players.values()) {
                if (!state.hasCompletedHandshake()) continue;
                if (!state.getLastDimension().equals(dimension)) continue;

                var player = state.getPlayer();
                if (player.isRemoved()) continue;
                int playerCx = player.blockPosition().getX() >> 4;
                int playerCz = player.blockPosition().getZ() >> 4;
                int lodDist = config.lodDistanceChunks;

                int count = 0;
                for (long packed : dirty) {
                    int cx = PositionUtil.unpackX(packed);
                    int cz = PositionUtil.unpackZ(packed);
                    if (Math.max(Math.abs(cx - playerCx), Math.abs(cz - playerCz)) <= lodDist) {
                        filterBuf[count++] = packed;
                        if (count >= DirtyColumnsS2CPayload.MAX_POSITIONS) break;
                    }
                }

                if (count > 0) {
                    long[] result = new long[count];
                    System.arraycopy(filterBuf, 0, result, 0, count);
                    try {
                        ServerPlayNetworking.send(player, new DirtyColumnsS2CPayload(result));
                    } catch (Exception e) {
                        LSSLogger.error("Failed to send dirty columns to " + player.getName().getString(), e);
                    }
                }
            }
        }
    }

    private void drainDiskResultsToQueues() {
        var config = LSSServerConfig.CONFIG;

        for (var state : this.players.values()) {
            if (!state.hasCompletedHandshake()) continue;
            var queue = this.diskReader.getPlayerQueue(state.getPlayer().getUUID());
            if (queue == null) continue;

            ChunkDiskReader.ReadResult result;
            while ((result = queue.peek()) != null) {
                int cx = result.chunkX();
                int cz = result.chunkZ();

                boolean routeToGen = false;
                if (result.notFound() && this.generationService != null) {
                    var player = state.getPlayer();
                    int playerCx = player.blockPosition().getX() >> 4;
                    int playerCz = player.blockPosition().getZ() >> 4;
                    int dist = Math.max(Math.abs(cx - playerCx), Math.abs(cz - playerCz));
                    if (dist <= config.generationDistanceChunks) {
                        if (state.getPendingGenerationCount() >= config.maxConcurrentGenerationsPerPlayer * 2) {
                            break; // generation full, retry next tick
                        }
                        routeToGen = true;
                    }
                }

                queue.poll();

                if (routeToGen) {
                    var level = (ServerLevel) state.getPlayer().level();
                    this.generationService.submitGeneration(
                            result.playerUuid(), result.batchId(), level, cx, cz, result.submissionOrder());
                    state.clearPendingDiskRead(cx, cz);
                    state.markPendingGeneration(cx, cz);
                } else {
                    state.clearPendingDiskRead(cx, cz);
                    state.markDiskReadDone(cx, cz);

                    if (!result.notFound()) {
                        if (result.upToDate()) {
                            state.getSendQueue().add(new PlayerRequestState.QueuedPayload(
                                    new ColumnUpToDateS2CPayload(cx, cz),
                                    result.batchId(), 8, result.submissionOrder()));
                        } else {
                            for (var payload : result.payloads()) {
                                state.getSendQueue().add(new PlayerRequestState.QueuedPayload(
                                        payload, result.batchId(), payload.sectionData().length + 20, result.submissionOrder()));
                            }
                        }
                    }
                }
                this.curTickDiskDrained++;
            }
        }
    }

    private void drainGenerationResultsToQueues() {
        var config = LSSServerConfig.CONFIG;

        for (var state : this.players.values()) {
            if (!state.hasCompletedHandshake()) continue;
            var queue = this.generationService.getPlayerQueue(state.getPlayer().getUUID());
            if (queue == null) continue;

            ChunkDiskReader.ReadResult result;
            while ((result = queue.peek()) != null) {
                if (result.payloads().length > 0 && state.getSendQueueSize() >= config.maxSendQueueSize) break;

                queue.poll();
                state.clearPendingGeneration(result.chunkX(), result.chunkZ());
                state.markDiskReadDone(result.chunkX(), result.chunkZ());
                for (var payload : result.payloads()) {
                    state.getSendQueue().add(new PlayerRequestState.QueuedPayload(
                            payload, result.batchId(), payload.sectionData().length + 20, result.submissionOrder()));
                }
                this.curTickGenDrained++;
            }
        }
    }

    private void flushSendQueue(PlayerRequestState state, long allocationBytes, LSSServerConfig config) {
        var queue = state.getSendQueue();

        while (!queue.isEmpty()) {
            if (state.getSectionsSentThisTick() >= config.maxSectionsPerTickPerPlayer) return;
            if (!state.canSend(allocationBytes)) return;

            var queued = queue.poll();
            if (state.isBatchCancelled(queued.batchId)) continue;
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
    private long chunkSequence = 0;

    private void processRequestBatches(PlayerRequestState state, LSSServerConfig config) {
        int workDone = 0;

        var player = state.getPlayer();
        var level = (ServerLevel) player.level();
        int playerCx = player.blockPosition().getX() >> 4;
        int playerCz = player.blockPosition().getZ() >> 4;
        Path regionDir = DimensionType.getStorageFolder(level.dimension(),
                this.server.getWorldPath(LevelResource.ROOT)).resolve("region");

        while (workDone < MAX_WORK_PER_TICK_PER_PLAYER) {
            var batch = state.nextBatchToProcess();
            if (batch == null) break;

            if (state.getSendQueueSize() >= config.maxSendQueueSize) break;

            while (batch.nextPositionIndex < batch.positions.length && workDone < MAX_WORK_PER_TICK_PER_PLAYER) {
                if (state.getSendQueueSize() >= config.maxSendQueueSize) return;
                if (this.serializationsThisTick >= MAX_SERIALIZATIONS_PER_TICK) return;

                int posIndex = batch.nextPositionIndex;
                long packed = batch.positions[posIndex];
                long clientTimestamp = batch.timestamps[posIndex];
                int cx = PositionUtil.unpackX(packed);
                int cz = PositionUtil.unpackZ(packed);
                batch.nextPositionIndex++;

                if (state.hasDiskReadDone(cx, cz) || state.isPendingDiskRead(cx, cz)
                        || state.isPendingGeneration(cx, cz)) {
                    continue;
                }

                long order = this.chunkSequence++;

                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk != null) {
                    long columnTimestamp = System.currentTimeMillis() / 1000;
                    this.sendLoadedChunkColumn(state, level, chunk, cx, cz, batch.batchId, columnTimestamp, order);
                    workDone++;
                } else if (this.diskReader != null && config.enableDiskReading) {
                    if (state.getPendingDiskReadCount() < config.maxConcurrentDiskReads
                            && state.getSendQueueSize() < config.maxSendQueueSize) {
                        state.markPendingDiskRead(cx, cz);
                        this.diskReader.submitRead(player.getUUID(), batch.batchId, level, cx, cz,
                                clientTimestamp, regionDir, order);
                        this.curTickDiskQueued++;
                        workDone++;
                    } else {
                        batch.nextPositionIndex--;
                        return;
                    }
                } else if (this.generationService != null) {
                    int dist = Math.max(Math.abs(cx - playerCx), Math.abs(cz - playerCz));
                    if (dist <= config.generationDistanceChunks) {
                        if (state.getPendingGenerationCount() >= config.maxConcurrentGenerationsPerPlayer * 2) {
                            batch.nextPositionIndex--;
                            return;
                        }
                        this.generationService.submitGeneration(
                                player.getUUID(), batch.batchId, level, cx, cz, order);
                        state.markPendingGeneration(cx, cz);
                        workDone++;
                    }
                }
            }
        }
    }

    private void sendLoadedChunkColumn(PlayerRequestState state, ServerLevel level,
                                        LevelChunk chunk, int cx, int cz, int batchId,
                                        long columnTimestamp, long order) {
        int minSectionY = level.getMinSectionY();
        var sections = chunk.getSections();

        for (int i = 0; i < sections.length; i++) {
            int sectionY = minSectionY + i;
            var section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;

            var payload = serializeLoadedSection(level, section, cx, sectionY, cz, columnTimestamp);
            if (payload != null) {
                state.getSendQueue().add(new PlayerRequestState.QueuedPayload(
                        payload, batchId, payload.sectionData().length + 20, order));
                this.serializationsThisTick++;
            }
        }
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
            byte uniformBlockLight = 0;
            byte uniformSkyLight = 0;

            if (LSSServerConfig.CONFIG.sendLightData) {
                var sectionPos = SectionPos.of(cx, sectionY, cz);
                var blLayer = level.getLightEngine().getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
                var slLayer = level.getLightEngine().getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos);

                if (blLayer != null && !blLayer.isEmpty()) {
                    if (isUniformLayer(blLayer)) {
                        lightFlags |= 0x04;
                        uniformBlockLight = (byte) blLayer.get(0, 0, 0);
                    } else {
                        lightFlags |= 0x01;
                        blockLight = blLayer.getData().clone();
                    }
                }
                if (slLayer != null && !slLayer.isEmpty()) {
                    if (isUniformLayer(slLayer)) {
                        lightFlags |= 0x08;
                        uniformSkyLight = (byte) slLayer.get(0, 0, 0);
                    } else {
                        lightFlags |= 0x02;
                        skyLight = slLayer.getData().clone();
                    }
                }
            }

            return new ChunkSectionS2CPayload(
                    cx, sectionY, cz, level.dimension(),
                    sectionData, lightFlags, blockLight, skyLight,
                    uniformBlockLight, uniformSkyLight, columnTimestamp
            );
        } catch (Exception e) {
            LSSLogger.error("Failed to serialize section at " + cx + "," + sectionY + "," + cz, e);
            return null;
        } finally {
            buf.release();
        }
    }

    private static boolean isUniformLayer(DataLayer layer) {
        return LSSUtil.isUniformArray(layer.getData());
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
        this.lastTickGenDrained = this.curTickGenDrained;
        this.curTickSectionsSent = 0;
        this.curTickDiskQueued = 0;
        this.curTickDiskDrained = 0;
        this.curTickBatchesCompleted = 0;
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

    public SharedBandwidthLimiter getBandwidthLimiter() {
        return this.bandwidthLimiter;
    }

    public String getTickDiagnostics() {
        var sb = new StringBuilder();
        sb.append(String.format("last_tick: sections_sent=%d, disk_queued=%d, disk_drained=%d, batches_completed=%d",
                lastTickSectionsSent, lastTickDiskQueued, lastTickDiskDrained, lastTickBatchesCompleted));
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
