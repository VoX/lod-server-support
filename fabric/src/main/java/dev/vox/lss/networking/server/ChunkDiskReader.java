package dev.vox.lss.networking.server;

import dev.vox.lss.common.processing.AbstractChunkDiskReader;
import dev.vox.lss.mixin.AccessorIOWorker;
import dev.vox.lss.mixin.AccessorServerChunkCache;
import dev.vox.lss.mixin.AccessorSimpleRegionStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Async region file reader for Fabric. The shared base owns the executor, result queues,
 * and error triage; this class only captures the MC handles and serializes via
 * {@link NbtSectionSerializer}.
 */
public class ChunkDiskReader extends AbstractChunkDiskReader {

    // IOWorker$Priority is package-private and cannot be named here (and Fabric's intermediary
    // remapping rules out resolving it reflectively), so the ordinal is pinned. Verified against
    // the 26.2 jar: FOREGROUND(0), BACKGROUND(1), SHUTDOWN(2) — scheduleWithResult takes the
    // priority as an int, which is how vanilla passes it too. Scheduling LOD reads at
    // BACKGROUND(1) makes them yield to vanilla's foreground chunk loads on the shared executor.
    private static final int IOWORKER_PRIORITY_BACKGROUND = 1;

    private final boolean useBackgroundReadPriority;

    public ChunkDiskReader(int threadCount, boolean useBackgroundReadPriority) {
        super(threadCount);
        this.useBackgroundReadPriority = useBackgroundReadPriority;
    }

    public void submitReadDirect(UUID playerUuid, String dimension, ServerLevel level,
                                  int chunkX, int chunkZ, long submissionOrder) {
        var registryAccess = level.registryAccess();
        var chunkMap = ((AccessorServerChunkCache) level.getChunkSource()).getChunkMap();
        NbtSectionSerializer.ChunkNbtRead read = this.useBackgroundReadPriority
                ? backgroundReader(chunkMap)
                : (cx, cz) -> chunkMap.read(new ChunkPos(cx, cz));
        submitRead(playerUuid, chunkX, chunkZ, dimension, submissionOrder,
                () -> NbtSectionSerializer.readAndSerializeSections(read, registryAccess, chunkX, chunkZ));
    }

    /**
     * BACKGROUND-priority reader: schedules on the IOWorker's own single-threaded executor below
     * vanilla's FOREGROUND reads, reading straight from RegionFileStorage (the thread-safe target
     * of IOWorker's own reads).
     *
     * <p>Going straight to storage skips IOWorker's pendingWrites, so this path gives up
     * read-your-writes: a chunk saved but not yet flushed reads as its previous on-disk state.
     * Acceptable for LOD — a just-edited column is covered by a dirty-broadcast re-request, so a
     * momentarily stale read self-heals. (Paper has no such gap; Moonrise serves pending writes.)
     */
    private NbtSectionSerializer.ChunkNbtRead backgroundReader(ChunkMap chunkMap) {
        var worker = (AccessorIOWorker) ((AccessorSimpleRegionStorage) chunkMap).lss$getWorker();
        var executor = worker.lss$getConsecutiveExecutor();
        var storage = worker.lss$getStorage();
        return (cx, cz) -> {
            var pos = new ChunkPos(cx, cz);
            return executor.scheduleWithResult(IOWORKER_PRIORITY_BACKGROUND,
                    (CompletableFuture<Optional<CompoundTag>> f) -> {
                        try {
                            f.complete(Optional.ofNullable(storage.read(pos)));
                        } catch (IOException e) {
                            f.completeExceptionally(e);
                        }
                    });
        };
    }
}
