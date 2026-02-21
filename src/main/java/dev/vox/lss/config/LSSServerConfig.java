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

    @Override
    protected String getFileName() {
        return FILE_NAME;
    }

    @Override
    protected void validate() {
        lodDistanceChunks = Math.max(lodDistanceChunks, 1);
        maxSectionsPerTickPerPlayer = Math.max(maxSectionsPerTickPerPlayer, 1);
        maxBytesPerSecondPerPlayer = Math.max(maxBytesPerSecondPerPlayer, 1024);
        maxConcurrentDiskReads = Math.max(maxConcurrentDiskReads, 1);
        diskReaderThreads = Math.max(diskReaderThreads, 1);
        maxSendQueueSize = Math.max(maxSendQueueSize, 1);
        maxBytesPerSecondGlobal = Math.max(maxBytesPerSecondGlobal, 1024);
        maxRequestsPerBatch = Math.max(maxRequestsPerBatch, 1);
        maxPendingRequestsPerPlayer = Math.max(maxPendingRequestsPerPlayer, 1);
        generationDistanceChunks = Math.max(generationDistanceChunks, 1);
        maxConcurrentGenerations = Math.max(maxConcurrentGenerations, 1);
        maxConcurrentGenerationsPerPlayer = Math.max(maxConcurrentGenerationsPerPlayer, 1);
        generationTimeoutSeconds = Math.max(generationTimeoutSeconds, 1);
        maxWaitingGenerations = Math.max(maxWaitingGenerations, 1);
        maxWaitingGenerationsPerPlayer = Math.max(maxWaitingGenerationsPerPlayer, 1);
    }
}
