package dev.vox.lss.common.processing;

import java.util.UUID;

/**
 * Result of an async chunk disk read (or a generation outcome routed through the disk
 * pipeline). Pure-Java — shared verbatim by both platforms.
 *
 * <p>{@code saturated} means the disk reader pool rejected the task. It must never be
 * treated as "not found": the position is simply left unanswered (dropped silently and
 * counted superseded), and the client's next want-set re-declares it. {@code sectionBytes
 * == null} with {@code !notFound} is an all-air chunk (exists on disk, nothing visible).
 */
public record ChunkReadResult(UUID playerUuid, int chunkX, int chunkZ,
                              byte[] sectionBytes, String dimension, int estimatedBytes,
                              long columnTimestamp,
                              boolean notFound, boolean saturated,
                              long submissionOrder) {

    public static ChunkReadResult empty(UUID playerUuid, int chunkX, int chunkZ, String dimension, long submissionOrder) {
        return new ChunkReadResult(playerUuid, chunkX, chunkZ, null, dimension, 0, 0L, true, false, submissionOrder);
    }

    public static ChunkReadResult saturated(UUID playerUuid, int chunkX, int chunkZ, String dimension, long submissionOrder) {
        return new ChunkReadResult(playerUuid, chunkX, chunkZ, null, dimension, 0, 0L, false, true, submissionOrder);
    }
}
