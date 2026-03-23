package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.processing.AbstractChunkDiskReader;
import dev.vox.lss.common.processing.ReadResultAccess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

public class ChunkDiskReader extends AbstractChunkDiskReader<ChunkDiskReader.ReadResult> {

    public record ReadResult(UUID playerUuid, int requestId, int chunkX, int chunkZ,
                                byte[] sectionBytes, String dimension, int estimatedBytes,
                                long columnTimestamp,
                                boolean notFound, boolean saturated,
                                long submissionOrder) implements ReadResultAccess {}

    static ReadResult emptyResult(UUID playerUuid, int requestId, int chunkX, int chunkZ, long submissionOrder) {
        return new ReadResult(playerUuid, requestId, chunkX, chunkZ, null, null, 0, 0L, true, false, submissionOrder);
    }

    static ReadResult saturatedResult(UUID playerUuid, int requestId, int chunkX, int chunkZ, long submissionOrder) {
        return new ReadResult(playerUuid, requestId, chunkX, chunkZ, null, null, 0, 0L, false, true, submissionOrder);
    }

    public ChunkDiskReader(int threadCount) {
        super(threadCount);
    }

    public void submitReadDirect(UUID playerUuid, int requestId, ServerLevel level, int chunkX, int chunkZ,
                                  long submissionOrder) {
        if (isShutdown()) return;

        this.diag.recordSubmitted();
        var dimension = level.dimension();
        var registryAccess = level.registryAccess();
        var chunkMap = ((dev.vox.lss.mixin.AccessorServerChunkCache) level.getChunkSource()).getChunkMap();
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

    private void readChunkNbtAndSerialize(UUID playerUuid, int requestId, ChunkMap chunkMap,
                                           int chunkX, int chunkZ,
                                           ResourceKey<Level> dimension, RegistryAccess registryAccess,
                                           long submissionOrder) {
        if (isShutdown()) return;
        long startNs = System.nanoTime();

        byte[] serializedSections;
        try {
            serializedSections = NbtSectionSerializer.readAndSerializeSections(chunkMap, registryAccess, chunkX, chunkZ);
        } catch (Exception e) {
            LSSLogger.error("Failed to read chunk NBT from disk at " + chunkX + ", " + chunkZ, e);
            this.diag.recordError();
            this.diag.recordCompleted(System.nanoTime() - startNs);
            addResult(playerUuid, emptyResult(playerUuid, requestId, chunkX, chunkZ, submissionOrder));
            return;
        }

        if (serializedSections == null) {
            this.diag.recordEmpty();
            this.diag.recordCompleted(System.nanoTime() - startNs);
            addResult(playerUuid, emptyResult(playerUuid, requestId, chunkX, chunkZ, submissionOrder));
            return;
        }

        if (serializedSections.length == 0) {
            // Chunk exists on disk (FULL status) but is all air — resolve as found, not "not found"
            long columnTimestamp = LSSConstants.epochSeconds();
            String dimensionStr = dimension.identifier().toString();
            this.diag.recordEmpty();
            this.diag.recordCompleted(System.nanoTime() - startNs);
            addResult(playerUuid, new ReadResult(playerUuid, requestId, chunkX, chunkZ,
                    null, dimensionStr, 0, columnTimestamp, false, false, submissionOrder));
            return;
        }

        long columnTimestamp = LSSConstants.epochSeconds();
        String dimensionStr = dimension.identifier().toString();
        int estimatedBytes = serializedSections.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;

        this.diag.recordCompleted(System.nanoTime() - startNs);
        addResult(playerUuid, new ReadResult(playerUuid, requestId, chunkX, chunkZ,
                serializedSections, dimensionStr, estimatedBytes, columnTimestamp,
                false, false, submissionOrder));
    }
}
