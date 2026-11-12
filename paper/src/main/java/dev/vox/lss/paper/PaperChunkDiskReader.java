package dev.vox.lss.paper;

import dev.vox.lss.common.processing.RequestRegistration;

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

    private volatile SerializationPolicy serializationPolicy;
    public void updateSerializationPolicy(boolean transcode, boolean selective) {
        this.serializationPolicy = new SerializationPolicy(transcode, selective);
    }

    /** Convenience for tests: production defaults for the serialize path
     *  (transcode ON — the {@code useNbtTranscode} default). */
    public PaperChunkDiskReader(int threadCount, boolean useBackgroundReadPriority) {
        this(threadCount, useBackgroundReadPriority, true);
    }

    public PaperChunkDiskReader(int threadCount, boolean useBackgroundReadPriority, boolean useNbtTranscode) {
        super(threadCount);
        this.useBackgroundReadPriority = useBackgroundReadPriority;
        this.serializationPolicy = new SerializationPolicy(useNbtTranscode, false);
    }

    void setReadOverride(PaperNbtSectionSerializer.ChunkNbtRead read) {
        this.readOverride = read;
    }

    public void submitReadDirect(UUID playerUuid, RequestRegistration registration, String dimension, ServerLevel level,
                                  int chunkX, int chunkZ, long submissionOrder,
                                  long clientTimestamp) {
        var readPolicy = this.serializationPolicy;
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
        // The mask entry is captured at submit time (the level is in hand here); the read
        // itself runs on the reader pool where only the dimension string survives.
        var maskEntry = PaperXrayMaskManager.entryForActive(level);
        int minSectionY = level.getMinSectionY();
        int maxSectionY = level.getMaxSectionY();
        // Whether this dimension has sky light at all — from its dimension type, captured
        // here like the facts above. The shared reader counts a lightless column only then.
        boolean skyLit = hasSkyLight(level);
        // The shared reader's per-read notes go through, so an undecodable column
        // resolves as the unservable flavor instead of a plain (generation-escalating) miss.
        submitRead(playerUuid, registration, chunkX, chunkZ, dimension, submissionOrder, clientTimestamp,
                skyLit,
                notes -> PaperNbtSectionSerializer.readAndSerializeSections(read, registryAccess, chunkX, chunkZ,
                        maskEntry, minSectionY, maxSectionY, readPolicy.transcode(), notes));
    }

    /** The level's dimension type decides, never a list of dimension names (The End has
     *  sky light on some Minecraft lines and not on others; datapacks add their own). A
     *  level without a dimension type exists only as a test double and reads as "no sky
     *  light", so nothing is counted for it. */
    private static boolean hasSkyLight(ServerLevel level) {
        var type = level.dimensionType();
        return type != null && type.hasSkyLight();
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
