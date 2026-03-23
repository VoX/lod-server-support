package dev.vox.lss.config;

import dev.vox.lss.common.LSSConstants;

public class LSSServerConfig extends JsonConfig {
    private static final String FILE_NAME = "lss-server-config.json";

    public static final LSSServerConfig CONFIG = load(LSSServerConfig.class, FILE_NAME);

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
    public int syncOnLoadRateLimitPerPlayer = 800;
    public int syncOnLoadConcurrencyLimitPerPlayer = 200;
    public int generationRateLimitPerPlayer = 80;
    public int generationConcurrencyLimitPerPlayer = 16;
    public int perDimensionTimestampCacheSizeMB = 32;

    @Override
    protected String getFileName() {
        return FILE_NAME;
    }

    @Override
    protected void validate() {
        lodDistanceChunks = Math.clamp(lodDistanceChunks, LSSConstants.MIN_LOD_DISTANCE, LSSConstants.MAX_LOD_DISTANCE);
        bytesPerSecondLimitPerPlayer = Math.clamp(bytesPerSecondLimitPerPlayer, LSSConstants.MIN_BYTES_PER_SECOND, LSSConstants.MAX_BYTES_PER_SECOND_PER_PLAYER);
        diskReaderThreads = Math.clamp(diskReaderThreads, LSSConstants.MIN_DISK_READER_THREADS, LSSConstants.MAX_DISK_READER_THREADS);
        sendQueueLimitPerPlayer = Math.clamp(sendQueueLimitPerPlayer, LSSConstants.MIN_SEND_QUEUE_SIZE, LSSConstants.MAX_SEND_QUEUE_SIZE);
        bytesPerSecondLimitGlobal = (int) Math.clamp((long) bytesPerSecondLimitGlobal, LSSConstants.MIN_BYTES_PER_SECOND, LSSConstants.MAX_BYTES_PER_SECOND_GLOBAL_LIMIT);
        generationConcurrencyLimitGlobal = Math.clamp(generationConcurrencyLimitGlobal, LSSConstants.MIN_CONCURRENT_GENERATIONS, LSSConstants.MAX_CONCURRENT_GENERATIONS);

        generationTimeoutSeconds = Math.clamp(generationTimeoutSeconds, LSSConstants.MIN_GENERATION_TIMEOUT, LSSConstants.MAX_GENERATION_TIMEOUT);
        dirtyBroadcastIntervalSeconds = Math.clamp(dirtyBroadcastIntervalSeconds, LSSConstants.MIN_DIRTY_BROADCAST_INTERVAL, LSSConstants.MAX_DIRTY_BROADCAST_INTERVAL);
        syncOnLoadRateLimitPerPlayer = Math.clamp(syncOnLoadRateLimitPerPlayer, LSSConstants.MIN_RATE_LIMIT, LSSConstants.MAX_RATE_LIMIT);
        syncOnLoadConcurrencyLimitPerPlayer = Math.clamp(syncOnLoadConcurrencyLimitPerPlayer, LSSConstants.MIN_CONCURRENCY_LIMIT, LSSConstants.MAX_CONCURRENCY_LIMIT);
        generationRateLimitPerPlayer = Math.clamp(generationRateLimitPerPlayer, LSSConstants.MIN_RATE_LIMIT, LSSConstants.MAX_RATE_LIMIT);
        generationConcurrencyLimitPerPlayer = Math.clamp(generationConcurrencyLimitPerPlayer, LSSConstants.MIN_CONCURRENCY_LIMIT, LSSConstants.MAX_CONCURRENCY_LIMIT);
        perDimensionTimestampCacheSizeMB = Math.clamp(perDimensionTimestampCacheSizeMB, LSSConstants.MIN_TIMESTAMP_CACHE_SIZE_MB, LSSConstants.MAX_TIMESTAMP_CACHE_SIZE_MB);
    }
}
