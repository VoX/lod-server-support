package dev.vox.lss.config;

public class LSSServerConfig extends JsonConfig {
    private static final String FILE_NAME = "lss-server-config.json";

    public static LSSServerConfig CONFIG = load(LSSServerConfig.class, FILE_NAME);

    public boolean enabled = true;
    public int lodDistanceChunks = 128;
    public int maxSectionsPerTickPerPlayer = 200;
    public int maxBytesPerSecondPerPlayer = 2_097_152;
    public boolean sendLightData = true;
    public boolean enableDiskReading = true;
    public int maxConcurrentDiskReads = 64;
    public int diskReaderThreads = 2;
    public int maxSendQueueSize = 4800;
    public int maxBytesPerSecondGlobal = 10_485_760;
    public int maxRequestsPerBatch = 256;
    public int maxPendingRequestsPerPlayer = 512;
    public boolean enableChunkGeneration = true;
    public int generationDistanceChunks = 64;
    public int maxConcurrentGenerations = 16;
    public int maxConcurrentGenerationsPerPlayer = 8;
    public int generationTimeoutSeconds = 60;
    public int maxWaitingGenerations = 128;
    public int maxWaitingGenerationsPerPlayer = 64;
    public int dirtyBroadcastIntervalSeconds = 15;

    @Override
    protected String getFileName() {
        return FILE_NAME;
    }

    @Override
    protected void validate() {
        lodDistanceChunks = Math.max(lodDistanceChunks, 1);
        lodDistanceChunks = Math.min(lodDistanceChunks, 512);
        maxSectionsPerTickPerPlayer = Math.max(maxSectionsPerTickPerPlayer, 1);
        maxSectionsPerTickPerPlayer = Math.min(maxSectionsPerTickPerPlayer, 10000);
        maxBytesPerSecondPerPlayer = Math.max(maxBytesPerSecondPerPlayer, 1024);
        maxBytesPerSecondPerPlayer = Math.min(maxBytesPerSecondPerPlayer, 104_857_600);
        maxConcurrentDiskReads = Math.max(maxConcurrentDiskReads, 1);
        maxConcurrentDiskReads = Math.min(maxConcurrentDiskReads, 512);
        diskReaderThreads = Math.max(diskReaderThreads, 1);
        diskReaderThreads = Math.min(diskReaderThreads, 64);
        maxSendQueueSize = Math.max(maxSendQueueSize, 1);
        maxSendQueueSize = Math.min(maxSendQueueSize, 100_000);
        maxBytesPerSecondGlobal = Math.max(maxBytesPerSecondGlobal, 1024);
        maxBytesPerSecondGlobal = Math.min(maxBytesPerSecondGlobal, 1_073_741_824);
        maxRequestsPerBatch = Math.max(maxRequestsPerBatch, 1);
        maxRequestsPerBatch = Math.min(maxRequestsPerBatch, 1024);
        maxPendingRequestsPerPlayer = Math.max(maxPendingRequestsPerPlayer, 1);
        maxPendingRequestsPerPlayer = Math.min(maxPendingRequestsPerPlayer, 4096);
        generationDistanceChunks = Math.max(generationDistanceChunks, 1);
        generationDistanceChunks = Math.min(generationDistanceChunks, 512);
        maxConcurrentGenerations = Math.max(maxConcurrentGenerations, 1);
        maxConcurrentGenerations = Math.min(maxConcurrentGenerations, 256);
        maxConcurrentGenerationsPerPlayer = Math.max(maxConcurrentGenerationsPerPlayer, 1);
        maxConcurrentGenerationsPerPlayer = Math.min(maxConcurrentGenerationsPerPlayer, 64);
        generationTimeoutSeconds = Math.max(generationTimeoutSeconds, 1);
        generationTimeoutSeconds = Math.min(generationTimeoutSeconds, 600);
        maxWaitingGenerations = Math.max(maxWaitingGenerations, 1);
        maxWaitingGenerations = Math.min(maxWaitingGenerations, 1024);
        maxWaitingGenerationsPerPlayer = Math.max(maxWaitingGenerationsPerPlayer, 1);
        maxWaitingGenerationsPerPlayer = Math.min(maxWaitingGenerationsPerPlayer, 256);
        dirtyBroadcastIntervalSeconds = Math.max(dirtyBroadcastIntervalSeconds, 1);
        dirtyBroadcastIntervalSeconds = Math.min(dirtyBroadcastIntervalSeconds, 300);
    }
}
