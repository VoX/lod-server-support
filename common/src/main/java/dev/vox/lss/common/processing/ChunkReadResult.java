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
 * <p>{@code headerFresh} marks the header freshness rung's answer (P1, region-summary-
 * sync-plan.md): no bytes were read — {@code columnTimestamp} carries the proven
 * last-change second and delivery answers {@code up_to_date} per recipient whose stamp
 * strictly exceeds it (all other recipients take the standard transient drop).
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
 *
 * <p>{@code unservable} is the third reason {@code notFound} can be set (undecodable-chunks
 * plan, 2026-11-12): the chunk exists and is FULL, but every block its cells use failed
 * to decode (a removed mod's blocks, a chunk format this build cannot read), so nothing
 * can be served from it. It is a positive answer about the bytes on disk, like an
 * authoritative miss, with one difference that decides the disposition: generation is
 * the WRONG answer. Loading the chunk through Minecraft turns unknown blocks into air and
 * may save that, destroying a temporarily removed mod's blocks in a chunk no player
 * visited. So an unservable result never seeds the miss memo and never becomes a
 * generation ticket, whether generation is available or not: the requester gets
 * {@code ColumnNotGenerated}, the column's stamps are invalidated and any store row is
 * deleted (as for an authoritative miss), and nothing is deposited.
 * {@code authoritativeMiss} is deliberately {@code false} on this flavor, so the flag
 * keeps its one meaning ("may seed the memo, generation is correct").
 */
public record ChunkReadResult(UUID playerUuid, int chunkX, int chunkZ,
                              byte[] sectionBytes, String dimension, int estimatedBytes,
                              long columnTimestamp,
                              boolean notFound, boolean saturated,
                              boolean authoritativeMiss,
                              boolean fromStore,
                              long submissionOrder,
                              long srcStampSeconds,
                              byte[] frameBytes, int frameRawSize,
                              boolean headerFresh,
                              boolean unservable) {

    /** Pre-unservable full signature ({@code unservable = false}): every result that
     *  is not the undecodable-chunk flavor, and every rig written before it existed. */
    public ChunkReadResult(UUID playerUuid, int chunkX, int chunkZ,
                           byte[] sectionBytes, String dimension, int estimatedBytes,
                           long columnTimestamp, boolean notFound, boolean saturated,
                           boolean authoritativeMiss, boolean fromStore,
                           long submissionOrder, long srcStampSeconds,
                           byte[] frameBytes, int frameRawSize, boolean headerFresh) {
        this(playerUuid, chunkX, chunkZ, sectionBytes, dimension, estimatedBytes,
                columnTimestamp, notFound, saturated, authoritativeMiss, fromStore,
                submissionOrder, srcStampSeconds, frameBytes, frameRawSize, headerFresh,
                false);
    }

    /**
     * Pre-store signature (fromStore = false, srcStampSeconds = 0) — the shape every
     * test rig and non-data outcome uses. {@code fromStore = true} marks a LOD-store
     * hit: its {@code columnTimestamp} is the STORED stamp (delivery honesty — never
     * freshly fabricated), delivery attributes it {@code COLUMN_SOURCE_STORE}, and the
     * delivery path must NOT re-deposit it. {@code srcStampSeconds} is the epoch second
     * captured at READ START — the store deposit's freshness stamp (4-agent round
     * R1-M2: the sweep's {@code header >= src_stamp} argument needs a stamp no later
     * than byte acquisition; a save landing mid-read or in the read→deposit gap must
     * land at-or-after it). 0 = unknown (the store stamps at deposit-call, the
     * pre-review behavior).
     *
     * <p>{@code frameBytes}/{@code frameRawSize} (protocol-19 frame serving, plan §3):
     * a store-frame hit carries the validated zstd frame INSTEAD of raw bytes —
     * exactly one of {@code sectionBytes}/{@code frameBytes} is set on a data result;
     * {@code frameRawSize} is the store row's validated usize.
     */
    public ChunkReadResult(UUID playerUuid, int chunkX, int chunkZ,
                           byte[] sectionBytes, String dimension, int estimatedBytes,
                           long columnTimestamp, boolean notFound, boolean saturated,
                           boolean authoritativeMiss, long submissionOrder) {
        this(playerUuid, chunkX, chunkZ, sectionBytes, dimension, estimatedBytes,
                columnTimestamp, notFound, saturated, authoritativeMiss, false,
                submissionOrder, 0L, null, 0, false);
    }

    /** Pre-frame full signature (frameBytes = null) — the store-era rigs and every
     *  raw-bytes production path. */
    public ChunkReadResult(UUID playerUuid, int chunkX, int chunkZ,
                           byte[] sectionBytes, String dimension, int estimatedBytes,
                           long columnTimestamp, boolean notFound, boolean saturated,
                           boolean authoritativeMiss, boolean fromStore,
                           long submissionOrder, long srcStampSeconds) {
        this(playerUuid, chunkX, chunkZ, sectionBytes, dimension, estimatedBytes,
                columnTimestamp, notFound, saturated, authoritativeMiss, fromStore,
                submissionOrder, srcStampSeconds, null, 0, false);
    }

    /** Pre-headerFresh frame signature — the store-frame production path and its rigs. */
    public ChunkReadResult(UUID playerUuid, int chunkX, int chunkZ,
                           byte[] sectionBytes, String dimension, int estimatedBytes,
                           long columnTimestamp, boolean notFound, boolean saturated,
                           boolean authoritativeMiss, boolean fromStore,
                           long submissionOrder, long srcStampSeconds,
                           byte[] frameBytes, int frameRawSize) {
        this(playerUuid, chunkX, chunkZ, sectionBytes, dimension, estimatedBytes,
                columnTimestamp, notFound, saturated, authoritativeMiss, fromStore,
                submissionOrder, srcStampSeconds, frameBytes, frameRawSize, false);
    }

    /**
     * The header freshness rung's answer (region-summary-sync-plan.md P1): the region
     * header (with the live save mark as a LATCH — never max'd in: a marked-but-unlanded
     * write voids every claim for the region instead) proves this chunk's on-disk content last
     * changed at {@code stampSeconds}, which the submitting client's stamp strictly
     * exceeds — the read was skipped. {@code columnTimestamp} carries the stamp so the
     * delivery side can (a) re-verify per dedup recipient (an attached player's stamp
     * may be older — it takes the standard transient drop and re-declares) and (b)
     * refresh the timestamp cache at {@code stamp + 1}, preserving the strict margin
     * through the non-strict tscache compare.
     */
    public static ChunkReadResult headerFresh(UUID playerUuid, int chunkX, int chunkZ,
                                              String dimension, long submissionOrder,
                                              long stampSeconds) {
        return new ChunkReadResult(playerUuid, chunkX, chunkZ, null, dimension, 0,
                stampSeconds, false, false, false, false, submissionOrder, 0L, null, 0, true);
    }

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

    /**
     * The chunk exists but cannot be decoded (see the class javadoc): not-found shaped,
     * never memoized, never escalated to generation.
     */
    public static ChunkReadResult notFoundUnservable(UUID playerUuid, int chunkX, int chunkZ,
                                                     String dimension, long submissionOrder) {
        return new ChunkReadResult(playerUuid, chunkX, chunkZ, null, dimension, 0, 0L,
                true, false, false, false, submissionOrder, 0L, null, 0, false, true);
    }

    public static ChunkReadResult saturated(UUID playerUuid, int chunkX, int chunkZ, String dimension, long submissionOrder) {
        return new ChunkReadResult(playerUuid, chunkX, chunkZ, null, dimension, 0, 0L, false, true, false, submissionOrder);
    }
}
