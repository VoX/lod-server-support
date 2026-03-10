package dev.vox.lss.networking.server;

import dev.vox.lss.common.processing.OffThreadProcessor;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric-specific off-thread processor. Produces CustomPacketPayload objects
 * that will be sent via Fabric networking on the main thread.
 */
public class FabricOffThreadProcessor extends OffThreadProcessor<PlayerRequestState, ChunkDiskReader.ReadResult> {
    private final ChunkDiskReader diskReader;
    private final ChunkGenerationService generationService;

    // Stored references for disk read submission. Grows but never prunes — acceptable because
    // vanilla only has 3 permanent dimensions, and the map is cleared on shutdown.
    private final ConcurrentHashMap<String, ServerLevel> dimensionLevelMap = new ConcurrentHashMap<>();

    // Cache parsed ResourceKeys to avoid Identifier.parse per column payload (3 entries for vanilla)
    private final ConcurrentHashMap<String, ResourceKey<Level>> dimensionKeyCache = new ConcurrentHashMap<>();

    public FabricOffThreadProcessor(Map<UUID, PlayerRequestState> players,
                                     ChunkDiskReader diskReader,
                                     ChunkGenerationService generationService) {
        super(players, diskReader != null, generationService != null);
        this.diskReader = diskReader;
        this.generationService = generationService;
    }

    /** Register a dimension context for disk read submission (called from main thread). */
    public void updateDimensionContext(String dimension, ServerLevel level) {
        this.dimensionLevelMap.putIfAbsent(dimension, level);
    }

    @Override
    protected ChunkDiskReader.ReadResult pollDiskResult(PlayerRequestState state) {
        if (this.diskReader == null) return null;
        var queue = this.diskReader.getPlayerQueue(state.getPlayerUUID());
        if (queue == null) return null;
        return queue.poll();
    }

    @Override
    protected ChunkDiskReader.ReadResult pollGenerationResult(PlayerRequestState state) {
        if (this.generationService == null) return null;
        var queue = this.generationService.getPlayerQueue(state.getPlayerUUID());
        if (queue == null) return null;
        return queue.poll();
    }

    @Override
    protected void enqueueResultPayloads(PlayerRequestState state, ChunkDiskReader.ReadResult result) {
        if (result.sectionBytes() != null) {
            buildAndEnqueueColumnPayload(state, result.chunkX(), result.chunkZ(),
                    result.dimension(), result.requestId(), result.columnTimestamp(),
                    result.submissionOrder(), result.sectionBytes(), result.estimatedBytes());
        }
    }

    @Override
    protected void submitDiskRead(UUID playerUuid, int requestId, String dimension,
                                   int cx, int cz,
                                   long submissionOrder) {
        if (this.diskReader == null) return;
        var level = this.dimensionLevelMap.get(dimension);
        if (level == null) return;
        this.diskReader.submitReadDirect(playerUuid, requestId, level,
                cx, cz, submissionOrder);
    }

    @Override
    protected void buildAndEnqueueColumnPayload(PlayerRequestState state, int cx, int cz,
                                                 String dimension, int requestId,
                                                 long columnTimestamp, long submissionOrder,
                                                 byte[] sectionBytes, int estimatedBytes) {
        var dimensionKey = this.dimensionKeyCache.computeIfAbsent(dimension,
                d -> ResourceKey.create(Registries.DIMENSION, Identifier.parse(d)));
        var payload = new VoxelColumnS2CPayload(requestId, cx, cz, dimensionKey, columnTimestamp,
                sectionBytes);
        state.addReadyPayload(new PlayerRequestState.QueuedPayload(
                payload, requestId, estimatedBytes, submissionOrder));
    }

    @Override
    public void shutdown() {
        super.shutdown();
        this.dimensionLevelMap.clear();
    }
}
