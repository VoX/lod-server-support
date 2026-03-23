package dev.vox.lss.paper;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.processing.OffThreadProcessor;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paper-specific off-thread processor. Produces encoded byte[] payloads
 * that will be sent via Plugin Messaging on the main thread.
 */
public class PaperOffThreadProcessor extends OffThreadProcessor<PaperPlayerRequestState, PaperChunkDiskReader.SimpleReadResult> {
    private final PaperChunkDiskReader diskReader;
    private final PaperChunkGenerationService generationService;

    // Stored dimension strings for disk read submission. Grows but never prunes — acceptable because
    // vanilla only has 3 permanent dimensions, and the map is cleared on shutdown.
    private final ConcurrentHashMap<String, ServerLevel> dimensionLevelMap = new ConcurrentHashMap<>();

    public PaperOffThreadProcessor(Map<UUID, PaperPlayerRequestState> players,
                                    PaperChunkDiskReader diskReader,
                                    PaperChunkGenerationService generationService,
                                    Path dataDir) {
        super(players,
                diskReader != null, generationService != null, dataDir);
        this.diskReader = diskReader;
        this.generationService = generationService;
    }

    public void updateDimensionContext(String dimension, ServerLevel level) {
        this.dimensionLevelMap.putIfAbsent(dimension, level);
    }

    @Override
    protected PaperChunkDiskReader.SimpleReadResult pollDiskResult(PaperPlayerRequestState state) {
        if (this.diskReader == null) return null;
        var queue = this.diskReader.getPlayerQueue(state.getPlayerUUID());
        if (queue == null) return null;
        return queue.poll();
    }

    @Override
    protected PaperChunkDiskReader.SimpleReadResult pollGenerationResult(PaperPlayerRequestState state) {
        if (this.generationService == null) return null;
        var queue = this.generationService.getPlayerQueue(state.getPlayerUUID());
        if (queue == null) return null;
        return queue.poll();
    }

    @Override
    protected void enqueueResultPayloads(PaperPlayerRequestState state, PaperChunkDiskReader.SimpleReadResult result) {
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
        if (level == null) {
            LSSLogger.debug("No dimension context for " + dimension + ", skipping disk read for " + cx + "," + cz);
            return;
        }
        this.diskReader.submitReadDirect(playerUuid, requestId, level,
                cx, cz, submissionOrder);
    }

    @Override
    protected void buildAndEnqueueColumnPayload(PaperPlayerRequestState state, int cx, int cz,
                                                 String dimension, int requestId,
                                                 long columnTimestamp, long submissionOrder,
                                                 byte[] sectionBytes, int estimatedBytes) {
        byte[] encoded = PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                requestId, cx, cz, dimension, columnTimestamp, sectionBytes);
        state.addReadyPayload(new PaperPlayerRequestState.QueuedPayload(
                encoded, requestId, estimatedBytes, submissionOrder));
    }

    @Override
    public void shutdown() {
        super.shutdown();
        this.dimensionLevelMap.clear();
    }
}
