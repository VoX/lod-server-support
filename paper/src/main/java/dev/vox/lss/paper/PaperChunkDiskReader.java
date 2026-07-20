package dev.vox.lss.paper;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import dev.vox.lss.common.processing.AbstractChunkDiskReader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Async region file reader for Paper. The shared base owns the executor, result queues,
 * and error triage; this class only captures the NMS handles (Paper uses Mojang mappings,
 * so no mixin accessor needed) and serializes via {@link PaperNbtSectionSerializer}.
 */
public class PaperChunkDiskReader extends AbstractChunkDiskReader {

    // Test seam: when set, replaces the NMS read (always null in production).
    // Volatile: set on the test thread, read on the submitting thread.
    private volatile PaperNbtSectionSerializer.ChunkNbtRead readOverride;

    private final boolean useBackgroundReadPriority;

    public PaperChunkDiskReader(int threadCount, boolean useBackgroundReadPriority) {
        super(threadCount);
        this.useBackgroundReadPriority = useBackgroundReadPriority;
    }

    void setReadOverride(PaperNbtSectionSerializer.ChunkNbtRead read) {
        this.readOverride = read;
    }

    public void submitReadDirect(UUID playerUuid, String dimension, ServerLevel level,
                                  int chunkX, int chunkZ, long submissionOrder) {
        var registryAccess = level.registryAccess();
        var override = this.readOverride;
        PaperNbtSectionSerializer.ChunkNbtRead read;
        if (override != null) {
            read = override;
        } else if (this.useBackgroundReadPriority) {
            read = moonriseReader(level);
        } else {
            var chunkMap = level.getChunkSource().chunkMap;
            read = (cx, cz) -> chunkMap.read(new ChunkPos(cx, cz));
        }
        submitRead(playerUuid, chunkX, chunkZ, dimension, submissionOrder,
                () -> PaperNbtSectionSerializer.readAndSerializeSections(read, registryAccess, chunkX, chunkZ));
    }

    /**
     * Route reads through Moonrise's prioritised IO pool at LOW priority so they defer to
     * gameplay's NORMAL reads. This is the Paper/Folia counterpart of Fabric's IOWorker BACKGROUND
     * scheduling: Paper's gameplay chunk loads run on Moonrise's own pool rather than the vanilla
     * IOWorker, so IOWorker priority would be a no-op here.
     *
     * <p>{@code loadDataAsync} serves from in-progress writes, so unlike Fabric's path this keeps
     * read-your-writes. The callback only completes our future — no chunk-system reentrancy — and
     * it may fire synchronously or on an IO thread; the blocking wait and the section serialization
     * then happen on the LSS reader-pool thread, off Moonrise's IO threads.
     *
     * <p>{@code intendingToBlock = false}: we block on the returned future from our own throwaway
     * pool thread, and want no blocking-priority escalation quietly overriding LOW.
     *
     * <p><b>No adaptive-throttle fallback here.</b> The shared base exposes
     * {@code enableAdaptiveThrottleFallback()} (Approach B), which Fabric engages when a chunk-IO
     * mod nulls out vanilla's IOWorker. This Moonrise reference is a direct compile-time call to a
     * class that is <em>always present</em> on supported Paper/Folia — a missing Moonrise fails
     * loudly at class-load, never as a silent null — so Paper never detects incompatibility and
     * never calls the hook. Paper's read protection is Moonrise {@code Priority.LOW}, not the throttle.
     */
    private PaperNbtSectionSerializer.ChunkNbtRead moonriseReader(ServerLevel level) {
        return (cx, cz) -> {
            var future = new CompletableFuture<Optional<CompoundTag>>();
            MoonriseRegionFileIO.loadDataAsync(level, cx, cz,
                    MoonriseRegionFileIO.RegionFileType.CHUNK_DATA,
                    (tag, err) -> {
                        if (err != null) future.completeExceptionally(err);
                        else future.complete(Optional.ofNullable(tag));
                    },
                    /* intendingToBlock = */ false, Priority.LOW);
            return future;
        };
    }
}
