package dev.vox.lss.api;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Consumer for raw MC primitive column data from the server.
 * <p>
 * Each column contains all non-air sections with raw block state IDs (4096 per section),
 * biome IDs (64 per section, 4x4x4 grid), and per-voxel light values (4096 bytes).
 * <p>
 * <b>Threading:</b> {@link #onVoxelColumnReceived} is always called from a dedicated
 * background thread ({@code LSS-ColumnProcessor}), never the main client thread.
 * The {@code level} parameter is a snapshot captured at submission time and may no
 * longer be the active level. Implementations must be thread-safe.
 */
@FunctionalInterface
public interface VoxelColumnConsumer {
    void onVoxelColumnReceived(ClientLevel level, ResourceKey<Level> dimension,
                                int chunkX, int chunkZ, VoxelColumnData columnData);

    /**
     * The consumer's pending ingest backlog, in SECTION units: sections accepted (from
     * {@link #onVoxelColumnReceived} or any other source) but not yet durably ingested.
     * LSS scales its request rate down as this grows and halts requesting entirely at a
     * threshold, so a consumer that reports honestly is never fed faster than it can
     * absorb — the fix for weak clients drowning in an unbounded ingest queue (issue #71).
     *
     * <p>Return -1 (the default) to report nothing; LSS then paces only on its own
     * decode queue, exactly as before this method existed. Any negative value means
     * "no signal"; 0 is a real report meaning "empty".
     *
     * <p><b>Sanctioned divergence:</b> a consumer whose capacity is not naturally
     * section-denominated may report a GOVERNOR SIGNAL scaled into this domain —
     * a bounded-queue consumer maps its fill fraction onto {@code [0, HALT]} so
     * that "queue effectively full" reports the halt threshold (the first-party
     * Xaero map bridge does exactly this; hybrid-scan-plan.md §12.2). The
     * aggregator takes the max across consumers, so scaled and literal reports
     * compose: whichever consumer is proportionally closest to its own limit
     * governs.
     *
     * <p><b>Threading — note this differs from {@link #onVoxelColumnReceived}:</b> polled
     * from the MAIN CLIENT THREAD, up to 20 times per second. Implementations must be
     * fast, non-blocking, and thread-safe — return a cached or atomic gauge (a queue
     * size, a semaphore permit count), never compute or take locks.
     */
    default int pendingIngestBacklog() { return -1; }
}
