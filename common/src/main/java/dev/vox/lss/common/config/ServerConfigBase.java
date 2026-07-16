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
    public int syncOnLoadConcurrencyLimitPerPlayer = 200;
    public int generationConcurrencyLimitPerPlayer = 16;
    public int perDimensionTimestampCacheSizeMB = 32;
    /**
     * When true (default), LSS disk reads yield to vanilla/gameplay chunk loading: Fabric
     * schedules them at IOWorker BACKGROUND priority; Paper/Folia route them through Moonrise
     * at Priority.LOW. Set false to restore FOREGROUND reads (the pre-0.7 behavior) as a
     * rollback. No clamp: a boolean has no out-of-range value.
     */
    public boolean useBackgroundReadPriority = true;

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
        syncOnLoadConcurrencyLimitPerPlayer = Math.clamp(syncOnLoadConcurrencyLimitPerPlayer, LSSConstants.MIN_CONCURRENCY_LIMIT, LSSConstants.MAX_CONCURRENCY_LIMIT);
        generationConcurrencyLimitPerPlayer = Math.clamp(generationConcurrencyLimitPerPlayer, LSSConstants.MIN_CONCURRENCY_LIMIT, LSSConstants.MAX_CONCURRENCY_LIMIT);
        perDimensionTimestampCacheSizeMB = Math.clamp(perDimensionTimestampCacheSizeMB, LSSConstants.MIN_TIMESTAMP_CACHE_SIZE_MB, LSSConstants.MAX_TIMESTAMP_CACHE_SIZE_MB);

        // Global Constraint #28: the want-set cap must dominate the slot caps, or frontier discovery
        // starves. The client re-declares every in-flight position each scan, so the in-flight set
        // (syncCap sync slots + genCap * SCAN_BUDGET_MULTIPLIER gen entries) competes for the same
        // per-batch budget (MAX_BATCH_CHUNK_REQUESTS) as newly discovered frontier positions. Left
        // unclamped, the legal per-field maxima (syncCap=1000 alone → budget clamps to 1024 with
        // ~1000 in-flight) collapse frontier headroom below zero and the server drains the batch
        // then starves for the rest of the second. Reserve WANT_SET_FRONTIER_RESERVE positions for
        // the frontier, sync (the primary discovery path) taking priority over the gen hold-back.
        int mult = LSSConstants.SCAN_BUDGET_MULTIPLIER;
        int reserve = LSSConstants.WANT_SET_FRONTIER_RESERVE;
        // Sync alone must leave room for the minimum gen hold-back (MIN_CONCURRENCY_LIMIT * mult)
        // plus the frontier reserve — a fixed ceiling independent of the configured gen cap.
        int maxSync = LSSConstants.MAX_BATCH_CHUNK_REQUESTS - reserve - LSSConstants.MIN_CONCURRENCY_LIMIT * mult;
        syncOnLoadConcurrencyLimitPerPlayer = Math.min(syncOnLoadConcurrencyLimitPerPlayer, maxSync);
        // Gen frontier gets whatever budget remains after sync + reserve.
        int maxGen = Math.max(LSSConstants.MIN_CONCURRENCY_LIMIT,
                (LSSConstants.MAX_BATCH_CHUNK_REQUESTS - syncOnLoadConcurrencyLimitPerPlayer - reserve) / mult);
        generationConcurrencyLimitPerPlayer = Math.min(generationConcurrencyLimitPerPlayer, maxGen);
    }
}
