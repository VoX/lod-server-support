package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.LSSUtil;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.SharedBandwidthLimiter;
import io.netty.buffer.Unpooled;
import net.minecraft.core.SectionPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core orchestrator for per-player LOD request processing on Paper.
 * Adapted from Fabric's RequestProcessingService with Plugin Messaging send calls.
 */
public class PaperRequestProcessingService {
    private final Map<UUID, PaperPlayerRequestState> players = new ConcurrentHashMap<>();
    private final MinecraftServer server;
    private final PaperChunkDiskReader diskReader;
    private final PaperChunkGenerationService generationService;
    private final SharedBandwidthLimiter bandwidthLimiter;
    private final Plugin plugin;
    private final PaperConfig config;

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

    public PaperRequestProcessingService(MinecraftServer server, Plugin plugin, PaperConfig config) {
        this.server = server;
        this.plugin = plugin;
        this.config = config;
        if (config.enableDiskReading) {
            this.diskReader = new PaperChunkDiskReader(config.diskReaderThreads, config.sendLightData);
        } else {
            this.diskReader = null;
        }
        if (config.enableChunkGeneration) {
            this.generationService = new PaperChunkGenerationService(config);
        } else {
            this.generationService = null;
        }
        this.bandwidthLimiter = new SharedBandwidthLimiter(config.maxBytesPerSecondGlobal);
    }

    public PaperPlayerRequestState registerPlayer(ServerPlayer player) {
        var state = this.players.computeIfAbsent(player.getUUID(), uuid -> new PaperPlayerRequestState(player));
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

    public void handleRequestBatch(ServerPlayer player, Player bukkitPlayer,
                                     int batchId, long[] positions, long[] timestamps) {
        var state = this.players.get(player.getUUID());
        if (state == null || !state.hasCompletedHandshake()) return;

        if (state.getPendingBatchCount() >= this.config.maxPendingRequestsPerPlayer) {
            LSSLogger.warn("Player " + player.getName().getString() + " exceeded max pending requests, rejecting batch " + batchId);
            try {
                PaperPayloadHandler.sendRequestComplete(bukkitPlayer, batchId, LSSConstants.STATUS_REJECTED);
            } catch (Exception e) {
                LSSLogger.error("Failed to send rejection to " + player.getName().getString(), e);
            }
            return;
        }

        int playerCx = player.blockPosition().getX() >> 4;
        int playerCz = player.blockPosition().getZ() >> 4;
        state.addBatch(batchId, positions, timestamps, this.config.lodDistanceChunks,
                playerCx, playerCz, this.config.maxRequestsPerBatch);
    }

    public void handleCancelRequest(ServerPlayer player, int[] batchIds) {
        var state = this.players.get(player.getUUID());
        if (state == null || !state.hasCompletedHandshake()) return;
        state.cancelBatches(batchIds);
    }

    public void tick() {
        if (!this.config.enabled) return;

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
        long perPlayerCap = Math.min(perPlayerAllocation, this.config.maxBytesPerSecondPerPlayer);

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
            this.flushSendQueue(state, perPlayerCap);
            this.processRequestBatches(state);
            this.drainCompletedBatches(state);
        }
        if (toRemove != null) {
            for (UUID uuid : toRemove) this.removePlayer(uuid);
        }
        // Dirty column tracking omitted in Paper v1 — clients resync via timer
    }

    private void drainDiskResultsToQueues() {
        for (var state : this.players.values()) {
            if (!state.hasCompletedHandshake()) continue;
            var queue = this.diskReader.getPlayerQueue(state.getPlayer().getUUID());
            if (queue == null) continue;

            PaperChunkDiskReader.SimpleReadResult result;
            while ((result = queue.peek()) != null) {
                int cx = result.chunkX();
                int cz = result.chunkZ();

                boolean routeToGen = false;
                if (result.notFound() && this.generationService != null) {
                    var player = state.getPlayer();
                    int playerCx = player.blockPosition().getX() >> 4;
                    int playerCz = player.blockPosition().getZ() >> 4;
                    int dist = Math.max(Math.abs(cx - playerCx), Math.abs(cz - playerCz));
                    if (dist <= this.config.generationDistanceChunks) {
                        if (state.getPendingGenerationCount() >= this.config.maxConcurrentGenerationsPerPlayer * 2) {
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
                            var utdBytes = java.nio.ByteBuffer.allocate(8).putInt(cx).putInt(cz).array();
                            state.getSendQueue().add(new PaperPlayerRequestState.QueuedPayload(
                                    utdBytes, LSSConstants.CHANNEL_COLUMN_UP_TO_DATE,
                                    result.batchId(), 8, result.submissionOrder()));
                        } else {
                            for (var section : result.sections()) {
                                state.getSendQueue().add(new PaperPlayerRequestState.QueuedPayload(
                                        section.encoded(), LSSConstants.CHANNEL_CHUNK_SECTION,
                                        result.batchId(), section.estimatedBytes(), result.submissionOrder()));
                            }
                        }
                    }
                }
                this.curTickDiskDrained++;
            }
        }
    }

    private void drainGenerationResultsToQueues() {
        for (var state : this.players.values()) {
            if (!state.hasCompletedHandshake()) continue;
            var queue = this.generationService.getPlayerQueue(state.getPlayer().getUUID());
            if (queue == null) continue;

            PaperChunkDiskReader.SimpleReadResult result;
            while ((result = queue.peek()) != null) {
                if (result.sections().length > 0 && state.getSendQueueSize() >= this.config.maxSendQueueSize) break;

                queue.poll();
                state.clearPendingGeneration(result.chunkX(), result.chunkZ());
                state.markDiskReadDone(result.chunkX(), result.chunkZ());
                for (var section : result.sections()) {
                    state.getSendQueue().add(new PaperPlayerRequestState.QueuedPayload(
                            section.encoded(), LSSConstants.CHANNEL_CHUNK_SECTION,
                            result.batchId(), section.estimatedBytes(), result.submissionOrder()));
                }
                this.curTickGenDrained++;
            }
        }
    }

    private void flushSendQueue(PaperPlayerRequestState state, long allocationBytes) {
        var queue = state.getSendQueue();

        while (!queue.isEmpty()) {
            if (state.getSectionsSentThisTick() >= this.config.maxSectionsPerTickPerPlayer) return;
            if (!state.canSend(allocationBytes)) return;

            var queued = queue.poll();
            if (state.isBatchCancelled(queued.batchId)) continue;
            try {
                var bukkitPlayer = state.getPlayer().getBukkitEntity();
                PaperPayloadHandler.sendRawNmsPayload(bukkitPlayer,
                        PaperPayloadHandler.channelId(queued.channel), queued.data);
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

    private void processRequestBatches(PaperPlayerRequestState state) {
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

            if (state.getSendQueueSize() >= this.config.maxSendQueueSize) break;

            while (batch.nextPositionIndex < batch.positions.length && workDone < MAX_WORK_PER_TICK_PER_PLAYER) {
                if (state.getSendQueueSize() >= this.config.maxSendQueueSize) return;
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
                } else if (this.diskReader != null && this.config.enableDiskReading) {
                    if (state.getPendingDiskReadCount() < this.config.maxConcurrentDiskReads
                            && state.getSendQueueSize() < this.config.maxSendQueueSize) {
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
                    if (dist <= this.config.generationDistanceChunks) {
                        if (state.getPendingGenerationCount() >= this.config.maxConcurrentGenerationsPerPlayer * 2) {
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

    private void sendLoadedChunkColumn(PaperPlayerRequestState state, ServerLevel level,
                                        LevelChunk chunk, int cx, int cz, int batchId,
                                        long columnTimestamp, long order) {
        int minSectionY = level.getMinSectionY();
        var sections = chunk.getSections();
        var dimension = level.dimension();

        for (int i = 0; i < sections.length; i++) {
            int sectionY = minSectionY + i;
            var section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;

            var encoded = serializeLoadedSection(level, section, cx, sectionY, cz, dimension, columnTimestamp, this.config.sendLightData);
            if (encoded != null) {
                state.getSendQueue().add(new PaperPlayerRequestState.QueuedPayload(
                        encoded.encoded(), LSSConstants.CHANNEL_CHUNK_SECTION,
                        batchId, encoded.estimatedBytes(), order));
                this.serializationsThisTick++;
            }
        }
    }

    static PaperChunkDiskReader.EncodedSection serializeLoadedSection(
            ServerLevel level, LevelChunkSection section, int cx, int sectionY, int cz,
            ResourceKey<Level> dimension, long columnTimestamp, boolean sendLightData) {
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

            if (sendLightData) {
                var sectionPos = SectionPos.of(cx, sectionY, cz);
                var blLayer = level.getLightEngine().getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
                var slLayer = level.getLightEngine().getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos);

                if (blLayer != null && !blLayer.isEmpty()) {
                    if (LSSUtil.isUniformArray(blLayer.getData())) {
                        lightFlags |= 0x04;
                        uniformBlockLight = (byte) blLayer.get(0, 0, 0);
                    } else {
                        lightFlags |= 0x01;
                        blockLight = blLayer.getData().clone();
                    }
                }
                if (slLayer != null && !slLayer.isEmpty()) {
                    if (LSSUtil.isUniformArray(slLayer.getData())) {
                        lightFlags |= 0x08;
                        uniformSkyLight = (byte) slLayer.get(0, 0, 0);
                    } else {
                        lightFlags |= 0x02;
                        skyLight = slLayer.getData().clone();
                    }
                }
            }

            byte[] encoded = PaperPayloadHandler.encodeChunkSection(
                    cx, sectionY, cz, dimension,
                    sectionData, lightFlags, blockLight, skyLight,
                    uniformBlockLight, uniformSkyLight, columnTimestamp);

            return new PaperChunkDiskReader.EncodedSection(encoded, cx, sectionY, cz, encoded.length);
        } catch (Exception e) {
            LSSLogger.error("Failed to serialize section at " + cx + "," + sectionY + "," + cz, e);
            return null;
        } finally {
            buf.release();
        }
    }

    private void drainCompletedBatches(PaperPlayerRequestState state) {
        var iter = state.drainCompletedBatches();
        while (iter.hasNext()) {
            var batch = iter.next();
            state.clearDiskReadDoneForBatch(batch.positions);
            int status = batch.cancelled ? LSSConstants.STATUS_CANCELLED : LSSConstants.STATUS_DONE;
            try {
                var bukkitPlayer = state.getPlayer().getBukkitEntity();
                PaperPayloadHandler.sendRequestComplete(bukkitPlayer, batch.batchId, status);
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

    public Map<UUID, PaperPlayerRequestState> getPlayers() {
        return java.util.Collections.unmodifiableMap(this.players);
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
