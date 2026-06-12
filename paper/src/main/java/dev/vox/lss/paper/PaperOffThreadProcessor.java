package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.OffThreadProcessor;
import dev.vox.lss.common.processing.QueuedPayload;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paper-specific off-thread processor. Produces encoded byte[] payloads
 * that will be sent via Plugin Messaging on the main thread.
 */
public class PaperOffThreadProcessor extends OffThreadProcessor<PaperPlayerRequestState> {
    private final PaperChunkDiskReader diskReader;

    // Stored dimension strings for disk read submission. Grows but never prunes — acceptable because
    // vanilla only has 3 permanent dimensions, and the map is cleared on shutdown.
    private final ConcurrentHashMap<String, ServerLevel> dimensionLevelMap = new ConcurrentHashMap<>();

    public PaperOffThreadProcessor(Map<UUID, PaperPlayerRequestState> players,
                                    PaperChunkDiskReader diskReader,
                                    boolean generationAvailable,
                                    Path dataDir, int perDimensionTimestampCacheSizeMB) {
        super(players,
                diskReader, generationAvailable, dataDir, perDimensionTimestampCacheSizeMB);
        this.diskReader = diskReader;
    }

    public void updateDimensionContext(String dimension, ServerLevel level) {
        this.dimensionLevelMap.putIfAbsent(dimension, level);
    }

    @Override
    protected boolean submitDiskRead(UUID playerUuid, String dimension,
                                    int cx, int cz,
                                    long submissionOrder) {
        if (this.diskReader == null) return false;
        var level = this.dimensionLevelMap.get(dimension);
        if (level == null) {
            LSSLogger.debug("No dimension context for " + dimension + ", skipping disk read for " + cx + "," + cz);
            return false;
        }
        this.diskReader.submitReadDirect(playerUuid, dimension, level,
                cx, cz, submissionOrder);
        return true;
    }

    @Override
    protected boolean buildAndEnqueueColumnPayload(PaperPlayerRequestState state, int cx, int cz,
                                                    String dimension,
                                                    long columnTimestamp, long submissionOrder,
                                                    byte[] sectionBytes, int estimatedBytes) {
        if (sectionBytes.length > LSSConstants.MAX_SECTIONS_SIZE) {
            LSSLogger.warn("Dropping oversized column [" + cx + ", " + cz + "] in " + dimension
                    + ": " + sectionBytes.length + " bytes exceeds wire limit "
                    + LSSConstants.MAX_SECTIONS_SIZE + " (client decoder would reject it)");
            return false;
        }
        byte[] encoded = PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                cx, cz, dimension, columnTimestamp, sectionBytes);
        state.addReadyPayload(new QueuedPayload<>(encoded, estimatedBytes, submissionOrder,
                PositionUtil.packPosition(cx, cz)));
        return true;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        this.dimensionLevelMap.clear();
    }
}
