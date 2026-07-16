package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.processing.AbstractChunkDiskReader;
import dev.vox.lss.mixin.AccessorIOWorker;
import dev.vox.lss.mixin.AccessorServerChunkCache;
import dev.vox.lss.mixin.AccessorSimpleRegionStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.PriorityConsecutiveExecutor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Async region file reader for Fabric. The shared base owns the executor, result queues,
 * and error triage; this class only captures the MC handles and serializes via
 * {@link NbtSectionSerializer}.
 */
public class ChunkDiskReader extends AbstractChunkDiskReader {

    // IOWorker$Priority is package-private and cannot be named here (and Fabric's intermediary
    // remapping rules out resolving it reflectively), so the ordinal is pinned. Verified against
    // the 26.2 jar: FOREGROUND(0), BACKGROUND(1), SHUTDOWN(2) — scheduleWithResult takes the
    // priority as an int, which is how vanilla passes it too.
    //
    // Where this lands us on the shared per-dimension executor: vanilla's chunk loads
    // (loadAsync -> submitTask) run FOREGROUND, and vanilla's chunk saves (storePendingChunk) run
    // BACKGROUND. So LOD reads at BACKGROUND sit strictly below the loads players wait on, and
    // tie with saves — an improvement for both, since chunkMap.read used to put LOD reads at
    // FOREGROUND, level with vanilla's loads and ahead of its saves.
    private static final int IOWORKER_PRIORITY_BACKGROUND = 1;

    private final boolean useBackgroundReadPriority;
    // One-shot guard for the "background priority unavailable" warning (a chunk-IO-overhaul mod
    // replaced vanilla's IOWorker executor — see backgroundReader). Warn once, not per read.
    private final AtomicBoolean backgroundUnavailableWarned = new AtomicBoolean();

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
     * The window is narrow — a save already queued ahead of us flushes first (same priority, FIFO)
     * — but it is real when the save is queued behind our read. Acceptable for LOD: the save that
     * we raced is itself what marks the column dirty, so the dirty broadcast re-request heals it.
     * (Paper has no such gap; Moonrise serves pending writes.)
     *
     * <p><b>Fail-safe.</b> A chunk-IO-overhaul mod (C2ME's chunkio rewrite, and structurally
     * similar mods) replaces vanilla's single-threaded IOWorker with its own concurrent IO system,
     * leaving {@code consecutiveExecutor}/{@code storage} null on the vanilla shell. Scheduling a
     * read there would NPE on every request. When either handle is null we fall back to the
     * vanilla {@code chunkMap.read} path — which those mods implement and prioritize themselves —
     * so LSS loses only its own background scheduling, not correctness. Warned once.
     */
    private NbtSectionSerializer.ChunkNbtRead backgroundReader(ChunkMap chunkMap) {
        var worker = (AccessorIOWorker) ((AccessorSimpleRegionStorage) chunkMap).lss$getWorker();
        PriorityConsecutiveExecutor executor = worker.lss$getConsecutiveExecutor();
        RegionFileStorage storage = worker.lss$getStorage();
        if (backgroundReadUnavailable(executor, storage)) {
            if (this.backgroundUnavailableWarned.compareAndSet(false, true)) {
                LSSLogger.warn("Background-priority disk reads unavailable — vanilla's IOWorker"
                        + " executor is absent, which a chunk-IO-overhaul mod (e.g. C2ME) causes by"
                        + " replacing vanilla chunk IO. Falling back to standard reads; that mod"
                        + " prioritizes IO itself. Set useBackgroundReadPriority=false to silence this.");
            }
            return (cx, cz) -> chunkMap.read(new ChunkPos(cx, cz));
        }
        return backgroundRead(executor, storage);
    }

    /** True when the vanilla IOWorker executor path is unusable — either handle absent because a
     *  chunk-IO-overhaul mod replaced vanilla chunk IO. Package-private + typed as Object so the
     *  fallback CONDITION (either handle null → fall back) is unit-testable without an MC IOWorker,
     *  which no test environment can give a null executor. */
    static boolean backgroundReadUnavailable(Object executor, Object storage) {
        return executor == null || storage == null;
    }

    private NbtSectionSerializer.ChunkNbtRead backgroundRead(PriorityConsecutiveExecutor executor,
                                                             RegionFileStorage storage) {
        return (cx, cz) -> {
            var pos = new ChunkPos(cx, cz);
            return executor.scheduleWithResult(IOWORKER_PRIORITY_BACKGROUND,
                    (CompletableFuture<Optional<CompoundTag>> f) -> {
                        try {
                            f.complete(Optional.ofNullable(storage.read(pos)));
                        } catch (Exception e) {
                            // As broad as vanilla's own read task (submitThrowingTask catches
                            // Exception): a corrupt region can fail unchecked, and anything that
                            // escapes here leaves the future uncompleted, stalling a reader thread
                            // until DISK_READ_TIMEOUT_SECONDS instead of triaging immediately.
                            f.completeExceptionally(e);
                        }
                    });
        };
    }
}
