package dev.vox.lss.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigValidationTest {

    // --- LSSServerConfig ---

    private LSSServerConfig serverConfig() {
        return new LSSServerConfig();
    }

    @Test
    void lodDistanceChunksClamped() {
        var c = serverConfig();
        c.lodDistanceChunks = 0;
        c.validate();
        assertEquals(1, c.lodDistanceChunks);

        c.lodDistanceChunks = 999;
        c.validate();
        assertEquals(512, c.lodDistanceChunks);
    }

    @Test
    void maxSectionsPerTickPerPlayerClamped() {
        var c = serverConfig();
        c.maxSectionsPerTickPerPlayer = 0;
        c.validate();
        assertEquals(1, c.maxSectionsPerTickPerPlayer);

        c.maxSectionsPerTickPerPlayer = 99999;
        c.validate();
        assertEquals(10000, c.maxSectionsPerTickPerPlayer);
    }

    @Test
    void maxBytesPerSecondPerPlayerClamped() {
        var c = serverConfig();
        c.maxBytesPerSecondPerPlayer = 100;
        c.validate();
        assertEquals(1024, c.maxBytesPerSecondPerPlayer);

        c.maxBytesPerSecondPerPlayer = 200_000_000;
        c.validate();
        assertEquals(104_857_600, c.maxBytesPerSecondPerPlayer);
    }

    @Test
    void maxConcurrentDiskReadsClamped() {
        var c = serverConfig();
        c.maxConcurrentDiskReads = 0;
        c.validate();
        assertEquals(1, c.maxConcurrentDiskReads);

        c.maxConcurrentDiskReads = 1000;
        c.validate();
        assertEquals(512, c.maxConcurrentDiskReads);
    }

    @Test
    void diskReaderThreadsClamped() {
        var c = serverConfig();
        c.diskReaderThreads = 0;
        c.validate();
        assertEquals(1, c.diskReaderThreads);

        c.diskReaderThreads = 100;
        c.validate();
        assertEquals(64, c.diskReaderThreads);
    }

    @Test
    void maxSendQueueSizeClamped() {
        var c = serverConfig();
        c.maxSendQueueSize = 0;
        c.validate();
        assertEquals(1, c.maxSendQueueSize);

        c.maxSendQueueSize = 999999;
        c.validate();
        assertEquals(100_000, c.maxSendQueueSize);
    }

    @Test
    void maxBytesPerSecondGlobalClamped() {
        var c = serverConfig();
        c.maxBytesPerSecondGlobal = 100;
        c.validate();
        assertEquals(1024, c.maxBytesPerSecondGlobal);

        c.maxBytesPerSecondGlobal = 2_000_000_000;
        c.validate();
        assertEquals(1_073_741_824, c.maxBytesPerSecondGlobal);
    }

    @Test
    void maxRequestsPerBatchClamped() {
        var c = serverConfig();
        c.maxRequestsPerBatch = 0;
        c.validate();
        assertEquals(1, c.maxRequestsPerBatch);

        c.maxRequestsPerBatch = 9999;
        c.validate();
        assertEquals(1024, c.maxRequestsPerBatch);
    }

    @Test
    void maxPendingRequestsPerPlayerClamped() {
        var c = serverConfig();
        c.maxPendingRequestsPerPlayer = 0;
        c.validate();
        assertEquals(1, c.maxPendingRequestsPerPlayer);

        c.maxPendingRequestsPerPlayer = 99999;
        c.validate();
        assertEquals(4096, c.maxPendingRequestsPerPlayer);
    }

    @Test
    void generationDistanceChunksClamped() {
        var c = serverConfig();
        c.generationDistanceChunks = 0;
        c.validate();
        assertEquals(1, c.generationDistanceChunks);

        c.generationDistanceChunks = 999;
        c.validate();
        assertEquals(512, c.generationDistanceChunks);
    }

    @Test
    void maxConcurrentGenerationsClamped() {
        var c = serverConfig();
        c.maxConcurrentGenerations = 0;
        c.validate();
        assertEquals(1, c.maxConcurrentGenerations);

        c.maxConcurrentGenerations = 999;
        c.validate();
        assertEquals(256, c.maxConcurrentGenerations);
    }

    @Test
    void maxConcurrentGenerationsPerPlayerClamped() {
        var c = serverConfig();
        c.maxConcurrentGenerationsPerPlayer = 0;
        c.validate();
        assertEquals(1, c.maxConcurrentGenerationsPerPlayer);

        c.maxConcurrentGenerationsPerPlayer = 999;
        c.validate();
        assertEquals(64, c.maxConcurrentGenerationsPerPlayer);
    }

    @Test
    void generationTimeoutSecondsClamped() {
        var c = serverConfig();
        c.generationTimeoutSeconds = 0;
        c.validate();
        assertEquals(1, c.generationTimeoutSeconds);

        c.generationTimeoutSeconds = 9999;
        c.validate();
        assertEquals(600, c.generationTimeoutSeconds);
    }

    @Test
    void dirtyBroadcastIntervalSecondsClamped() {
        var c = serverConfig();
        c.dirtyBroadcastIntervalSeconds = 0;
        c.validate();
        assertEquals(1, c.dirtyBroadcastIntervalSeconds);

        c.dirtyBroadcastIntervalSeconds = 9999;
        c.validate();
        assertEquals(300, c.dirtyBroadcastIntervalSeconds);
    }

    // --- LSSClientConfig ---

    private LSSClientConfig clientConfig() {
        return new LSSClientConfig();
    }

    @Test
    void clientLodDistanceChunksClamped() {
        var c = clientConfig();
        c.lodDistanceChunks = -1;
        c.validate();
        assertEquals(0, c.lodDistanceChunks);

        c.lodDistanceChunks = 999;
        c.validate();
        assertEquals(512, c.lodDistanceChunks);
    }

    @Test
    void clientResyncBatchSizeClamped() {
        var c = clientConfig();
        c.resyncBatchSize = 0;
        c.validate();
        assertEquals(1, c.resyncBatchSize);

        c.resyncBatchSize = 999;
        c.validate();
        assertEquals(256, c.resyncBatchSize);
    }
}
