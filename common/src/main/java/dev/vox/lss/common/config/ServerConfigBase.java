package dev.vox.lss.common.config;

import dev.vox.lss.common.LSSConstants;

/**
 * The server config shared verbatim by Fabric and Paper: same fields, same defaults,
 * same clamps, same file name. Platform subclasses add only platform-specific options
 * (Paper's updateEvents) and the config-directory resolution.
 */
public abstract class ServerConfigBase extends JsonConfig {
    protected static final String FILE_NAME = "lss-server-config.json";

    public boolean enabled = true;
    public int lodDistanceChunks = 256;
    public int bytesPerSecondLimitPerPlayer = 20_971_520;
    public int diskReaderThreads = 5;
    public int sendQueueLimitPerPlayer = 4000;
    public int bytesPerSecondLimitGlobal = 104_857_600;
    public boolean enableChunkGeneration = true;
    public int generationConcurrencyLimitGlobal = 32;
    public int generationTimeoutSeconds = 60;
    public int dirtyBroadcastIntervalSeconds = 10;
    // The per-player SYNC (disk-read) slot cap is NOT config anymore — see
    // LSSConstants.SYNC_ON_LOAD_SLOT_CAP (shadowed by the disk-pool headroom gate at the
    // default pool size; a fixed fairness ceiling above it). The generation caps stay
    // config: they are the real worldgen limiters, but they are server-internal (off the
    // wire since the server-owned-generation fold into v17).
    public int generationConcurrencyLimitPerPlayer = 16;
    public int perDimensionTimestampCacheSizeMB = 32;
    /**
     * When true (default), LSS disk reads yield to vanilla/gameplay chunk loading: Fabric
     * schedules them at IOWorker BACKGROUND priority; Paper/Folia route them through Moonrise
     * at Priority.LOW. Set false to restore FOREGROUND reads (the pre-0.7 behavior) as a
     * rollback. No clamp: a boolean has no out-of-range value.
     */
    public boolean useBackgroundReadPriority = true;
    /**
     * When true (default), clients running the legacy protocol-16 mod (v0.6.x) get a
     * translated LOD session through the v16 compat shim (docs/planning/v16-compat-design.md)
     * instead of the silent version-mismatch no-session. Inert for current-protocol clients;
     * set false as the kill switch to restore the strict version gate. No clamp: boolean.
     */
    public boolean enableV16Compat = true;

    @Override
    protected String getFileName() {
        return FILE_NAME;
    }

    @Override
    public void validate() {
        lodDistanceChunks = Math.clamp(lodDistanceChunks, LSSConstants.MIN_LOD_DISTANCE, LSSConstants.MAX_LOD_DISTANCE);
        bytesPerSecondLimitPerPlayer = Math.clamp(bytesPerSecondLimitPerPlayer, LSSConstants.MIN_BYTES_PER_SECOND, LSSConstants.MAX_BYTES_PER_SECOND_PER_PLAYER);
        diskReaderThreads = Math.clamp(diskReaderThreads, LSSConstants.MIN_DISK_READER_THREADS, LSSConstants.MAX_DISK_READER_THREADS);
        sendQueueLimitPerPlayer = Math.clamp(sendQueueLimitPerPlayer, LSSConstants.MIN_SEND_QUEUE_SIZE, LSSConstants.MAX_SEND_QUEUE_SIZE);
        bytesPerSecondLimitGlobal = (int) Math.clamp((long) bytesPerSecondLimitGlobal, LSSConstants.MIN_BYTES_PER_SECOND, LSSConstants.MAX_BYTES_PER_SECOND_GLOBAL_LIMIT);
        generationConcurrencyLimitGlobal = Math.clamp(generationConcurrencyLimitGlobal, LSSConstants.MIN_CONCURRENT_GENERATIONS, LSSConstants.MAX_CONCURRENT_GENERATIONS);
        generationTimeoutSeconds = Math.clamp(generationTimeoutSeconds, LSSConstants.MIN_GENERATION_TIMEOUT, LSSConstants.MAX_GENERATION_TIMEOUT);
        dirtyBroadcastIntervalSeconds = Math.clamp(dirtyBroadcastIntervalSeconds, LSSConstants.MIN_DIRTY_BROADCAST_INTERVAL, LSSConstants.MAX_DIRTY_BROADCAST_INTERVAL);
        generationConcurrencyLimitPerPlayer = Math.clamp(generationConcurrencyLimitPerPlayer, LSSConstants.MIN_CONCURRENCY_LIMIT, LSSConstants.MAX_CONCURRENCY_LIMIT);
        perDimensionTimestampCacheSizeMB = Math.clamp(perDimensionTimestampCacheSizeMB, LSSConstants.MIN_TIMESTAMP_CACHE_SIZE_MB, LSSConstants.MAX_TIMESTAMP_CACHE_SIZE_MB);

        // Global Constraint #28 is GONE: no client budget derives from any server cap under
        // server-owned generation, so there is nothing to cross-clamp against the wire batch.
        // Its successor is a static inequality between constants, pinned by
        // WantSetBudgetInvariantTest: SYNC_ON_LOAD_SLOT_CAP + MAX_CONCURRENT_GENERATIONS
        //   + WANT_SET_FRONTIER_RESERVE <= WANT_SET_BUDGET <= MAX_BATCH_CHUNK_REQUESTS.
    }
}
