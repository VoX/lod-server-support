package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.processing.AbstractChunkDiskReader;
import dev.vox.lss.common.processing.ReadResultAccess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * Async region file reader for Paper. Accesses ChunkMap directly via NMS
 * (Paper uses Mojang mappings, so no mixin accessor needed).
 */
public class PaperChunkDiskReader extends AbstractChunkDiskReader<PaperChunkDiskReader.SimpleReadResult> {
    public record SimpleReadResult(UUID playerUuid, int requestId, int chunkX, int chunkZ,
                                    byte[] sectionBytes, String dimension, int estimatedBytes,
                                    long columnTimestamp,
                                    boolean notFound, boolean saturated,
                                    long submissionOrder) implements ReadResultAccess {}

    static SimpleReadResult emptyResult(UUID playerUuid, int requestId, int chunkX, int chunkZ, long submissionOrder) {
        return new SimpleReadResult(playerUuid, requestId, chunkX, chunkZ,
                null, null, 0, 0L, true, false, submissionOrder);
    }

    static SimpleReadResult saturatedResult(UUID playerUuid, int requestId, int chunkX, int chunkZ, long submissionOrder) {
        return new SimpleReadResult(playerUuid, requestId, chunkX, chunkZ,
                null, null, 0, 0L, false, true, submissionOrder);
    }

    public PaperChunkDiskReader(int threadCount) {
        super(threadCount);
    }

    /**
     * Submit a disk read that skips timestamp and cache lookup (already done by initial probe).
     * Goes straight to NBT read → serialize.
     */
    public void submitReadDirect(UUID playerUuid, int requestId, ServerLevel level, int chunkX, int chunkZ,
                                  long submissionOrder) {
        if (isShutdown()) return;

        this.diag.recordSubmitted();
        var dimension = level.dimension();
        var registryAccess = level.registryAccess();
        var chunkMap = ((ServerChunkCache) level.getChunkSource()).chunkMap;
        try {
            this.executor.submit(() -> {
                if (isShutdown()) return;
                try {
                    this.readChunkNbtAndSerialize(playerUuid, requestId, chunkMap, chunkX, chunkZ,
                            dimension, registryAccess, submissionOrder);
                } catch (Exception e) {
                    LSSLogger.error("Failed to read chunk from disk at " + chunkX + ", " + chunkZ, e);
                    this.diag.recordError();
                    this.diag.recordCompleted(0);
                    addResult(playerUuid, emptyResult(playerUuid, requestId, chunkX, chunkZ, submissionOrder));
                }
            });
        } catch (RejectedExecutionException e) {
            LSSLogger.warn("Disk reader executor saturated, returning rate-limited for " + chunkX + "," + chunkZ);
            this.diag.recordSaturation();
            this.diag.recordCompleted(0);
            addResult(playerUuid, saturatedResult(playerUuid, requestId, chunkX, chunkZ, submissionOrder));
        }
    }

    /**
     * Read chunk NBT and serialize into MC-native format.
     */
    private void readChunkNbtAndSerialize(UUID playerUuid, int requestId, ChunkMap chunkMap,
                                          int chunkX, int chunkZ,
                                          ResourceKey<Level> dimension, RegistryAccess registryAccess,
                                          long submissionOrder) {
        if (isShutdown()) return;
        long startNs = System.nanoTime();

        try {
            byte[] serialized = PaperNbtSectionSerializer.readAndSerializeSections(
                    chunkMap, registryAccess, chunkX, chunkZ);

            if (serialized == null) {
                this.diag.recordEmpty();
                this.diag.recordCompleted(System.nanoTime() - startNs);
                addResult(playerUuid, emptyResult(playerUuid, requestId, chunkX, chunkZ, submissionOrder));
                return;
            }

            long columnTimestamp = LSSConstants.epochSeconds();
            String dimensionStr = dimension.identifier().toString();
            int estimatedBytes = serialized.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;

            this.diag.recordCompleted(System.nanoTime() - startNs);
            addResult(playerUuid, new SimpleReadResult(playerUuid, requestId, chunkX, chunkZ,
                    serialized, dimensionStr, estimatedBytes, columnTimestamp,
                    false, false, submissionOrder));
        } catch (Exception e) {
            LSSLogger.error("Failed to read/serialize chunk at " + chunkX + ", " + chunkZ, e);
            this.diag.recordError();
            this.diag.recordCompleted(System.nanoTime() - startNs);
            addResult(playerUuid, emptyResult(playerUuid, requestId, chunkX, chunkZ, submissionOrder));
        }
    }
}
