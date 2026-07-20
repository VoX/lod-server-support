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
 *
 * <p>{@code authoritativeMiss} distinguishes WHY {@code notFound} is set: {@code true}
 * means storage positively answered "no SERVABLE chunk" — the region lookup returned
 * empty, or the chunk exists but cannot serve LOD data (non-FULL proto-chunk, FULL with
 * no sections, a corrupt chunk MC's own read resolves null). All of these may seed the
 * miss memo: generation is the correct disposition for each (it completes/regenerates
 * the chunk, and every generation outcome clears the memo); {@code false} with
 * {@code notFound} means an error/timeout was TRIAGED down the not-found ladder (law A5's
 * {@code disk.errors} fold) — it says nothing about existence and must never be memoized,
 * or an existing chunk's reads would be suppressed for the memo TTL.
 */
public record ChunkReadResult(UUID playerUuid, int chunkX, int chunkZ,
                              byte[] sectionBytes, String dimension, int estimatedBytes,
                              long columnTimestamp,
                              boolean notFound, boolean saturated,
                              boolean authoritativeMiss,
                              long submissionOrder) {

    /** An authoritative miss: storage positively answered "no such chunk". */
    public static ChunkReadResult notFoundAuthoritative(UUID playerUuid, int chunkX, int chunkZ,
                                                        String dimension, long submissionOrder) {
        return new ChunkReadResult(playerUuid, chunkX, chunkZ, null, dimension, 0, 0L,
                true, false, true, submissionOrder);
    }

    /** An error/timeout triaged as not-found: resolves down the same ladder, never memoized. */
    public static ChunkReadResult notFoundFromError(UUID playerUuid, int chunkX, int chunkZ,
                                                    String dimension, long submissionOrder) {
        return new ChunkReadResult(playerUuid, chunkX, chunkZ, null, dimension, 0, 0L,
                true, false, false, submissionOrder);
    }

    public static ChunkReadResult saturated(UUID playerUuid, int chunkX, int chunkZ, String dimension, long submissionOrder) {
        return new ChunkReadResult(playerUuid, chunkX, chunkZ, null, dimension, 0, 0L, false, true, false, submissionOrder);
    }
}
