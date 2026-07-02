package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.OffThreadProcessor;
import dev.vox.lss.common.processing.QueuedPayload;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric-specific off-thread processor. Produces CustomPacketPayload objects
 * that will be sent via Fabric networking on the main thread.
 */
public class FabricOffThreadProcessor extends OffThreadProcessor<PlayerRequestState> {
    private final ChunkDiskReader diskReader;

    // Stored references for disk read submission. Grows but never prunes — acceptable because
    // vanilla only has 3 permanent dimensions, and the map is cleared on shutdown.
    private final ConcurrentHashMap<String, ServerLevel> dimensionLevelMap = new ConcurrentHashMap<>();

    // Cache parsed ResourceKeys to avoid Identifier.parse per column payload (3 entries for vanilla)
    private final ConcurrentHashMap<String, ResourceKey<Level>> dimensionKeyCache = new ConcurrentHashMap<>();

    public FabricOffThreadProcessor(Map<UUID, PlayerRequestState> players,
                                     ChunkDiskReader diskReader,
                                     boolean generationAvailable,
                                     Path dataDir, int perDimensionTimestampCacheSizeMB) {
        super(players, diskReader, generationAvailable, dataDir, perDimensionTimestampCacheSizeMB);
        this.diskReader = diskReader;
    }

    /** Register a dimension context for disk read submission (called from main thread). */
    public void updateDimensionContext(String dimension, ServerLevel level) {
        // put, not putIfAbsent: refresh to the current ServerLevel so a recreated dimension
        // (e.g. an integrated world re-published to LAN) doesn't leave a stale level cached.
        // Matches PaperOffThreadProcessor.updateDimensionContext so the twins can't drift.
        this.dimensionLevelMap.put(dimension, level);
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
    protected boolean buildAndEnqueueColumnPayload(PlayerRequestState state, int cx, int cz,
                                                    String dimension,
                                                    long columnTimestamp, long submissionOrder,
                                                    byte[] sectionBytes, int estimatedBytes) {
        if (sectionBytes.length > LSSConstants.MAX_SEND_SECTIONS_SIZE) {
            LSSLogger.warn("Dropping oversized column [" + cx + ", " + cz + "] in " + dimension
                    + ": " + sectionBytes.length + " bytes exceeds send limit "
                    + LSSConstants.MAX_SEND_SECTIONS_SIZE + " (netty frame cap would kill the connection)");
            return false;
        }
        if (dimension.length() > LSSConstants.MAX_DIMENSION_STRING_LENGTH) {
            // Drop just this column (like an oversized one) rather than letting writeUtf throw
            // at send time and nuke the whole send queue. No real dimension id is this long;
            // the !sent path answers the client up-to-date so it stops asking.
            LSSLogger.warn("Dropping column [" + cx + ", " + cz + "] with oversized dimension id ("
                    + dimension.length() + " chars > " + LSSConstants.MAX_DIMENSION_STRING_LENGTH + ")");
            return false;
        }
        var dimensionKey = this.dimensionKeyCache.computeIfAbsent(dimension,
                d -> ResourceKey.create(Registries.DIMENSION, new ResourceLocation(d)));
        var payload = new VoxelColumnS2CPayload(cx, cz, dimensionKey, columnTimestamp,
                sectionBytes);
        state.addReadyPayload(new QueuedPayload<>(payload, estimatedBytes, submissionOrder,
                PositionUtil.packPosition(cx, cz)));
        return true;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        this.dimensionLevelMap.clear();
    }
}
