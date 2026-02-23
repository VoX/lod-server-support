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
    public int dirtyBroadcastIntervalSeconds = 15;

    @Override
    protected String getFileName() {
        return FILE_NAME;
    }

    @Override
    protected void validate() {
        lodDistanceChunks = clamp(lodDistanceChunks, 1, 512);
        maxSectionsPerTickPerPlayer = clamp(maxSectionsPerTickPerPlayer, 1, 10000);
        maxBytesPerSecondPerPlayer = clamp(maxBytesPerSecondPerPlayer, 1024, 104_857_600);
        maxConcurrentDiskReads = clamp(maxConcurrentDiskReads, 1, 512);
        diskReaderThreads = clamp(diskReaderThreads, 1, 64);
        maxSendQueueSize = clamp(maxSendQueueSize, 1, 100_000);
        maxBytesPerSecondGlobal = clamp(maxBytesPerSecondGlobal, 1024, 1_073_741_824);
        maxRequestsPerBatch = clamp(maxRequestsPerBatch, 1, 1024);
        maxPendingRequestsPerPlayer = clamp(maxPendingRequestsPerPlayer, 1, 4096);
        generationDistanceChunks = clamp(generationDistanceChunks, 1, 512);
        maxConcurrentGenerations = clamp(maxConcurrentGenerations, 1, 256);
        maxConcurrentGenerationsPerPlayer = clamp(maxConcurrentGenerationsPerPlayer, 1, 64);
        generationTimeoutSeconds = clamp(generationTimeoutSeconds, 1, 600);
        dirtyBroadcastIntervalSeconds = clamp(dirtyBroadcastIntervalSeconds, 1, 300);
    }
}
