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
    void bytesPerSecondLimitPerPlayerClamped() {
        var c = serverConfig();
        c.bytesPerSecondLimitPerPlayer = 100;
        c.validate();
        assertEquals(1024, c.bytesPerSecondLimitPerPlayer);

        c.bytesPerSecondLimitPerPlayer = 200_000_000;
        c.validate();
        assertEquals(104_857_600, c.bytesPerSecondLimitPerPlayer);
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
    void sendQueueLimitPerPlayerClamped() {
        var c = serverConfig();
        c.sendQueueLimitPerPlayer = 0;
        c.validate();
        assertEquals(1, c.sendQueueLimitPerPlayer);

        c.sendQueueLimitPerPlayer = 999999;
        c.validate();
        assertEquals(100_000, c.sendQueueLimitPerPlayer);
    }

    @Test
    void bytesPerSecondLimitGlobalClamped() {
        var c = serverConfig();
        c.bytesPerSecondLimitGlobal = 100;
        c.validate();
        assertEquals(1024, c.bytesPerSecondLimitGlobal);

        c.bytesPerSecondLimitGlobal = 2_000_000_000;
        c.validate();
        assertEquals(1_073_741_824, c.bytesPerSecondLimitGlobal);
    }

    @Test
    void generationConcurrencyLimitGlobalClamped() {
        var c = serverConfig();
        c.generationConcurrencyLimitGlobal = 0;
        c.validate();
        assertEquals(1, c.generationConcurrencyLimitGlobal);

        c.generationConcurrencyLimitGlobal = 999;
        c.validate();
        assertEquals(256, c.generationConcurrencyLimitGlobal);
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

    @Test
    void syncOnLoadRateLimitPerPlayerClamped() {
        var c = serverConfig();
        c.syncOnLoadRateLimitPerPlayer = 0;
        c.validate();
        assertEquals(1, c.syncOnLoadRateLimitPerPlayer);

        c.syncOnLoadRateLimitPerPlayer = 9999;
        c.validate();
        assertEquals(1000, c.syncOnLoadRateLimitPerPlayer);
    }

    @Test
    void syncOnLoadConcurrencyLimitPerPlayerClamped() {
        var c = serverConfig();
        c.syncOnLoadConcurrencyLimitPerPlayer = 0;
        c.validate();
        assertEquals(1, c.syncOnLoadConcurrencyLimitPerPlayer);

        c.syncOnLoadConcurrencyLimitPerPlayer = 9999;
        c.validate();
        assertEquals(1000, c.syncOnLoadConcurrencyLimitPerPlayer);
    }

    @Test
    void generationRateLimitPerPlayerClamped() {
        var c = serverConfig();
        c.generationRateLimitPerPlayer = 0;
        c.validate();
        assertEquals(1, c.generationRateLimitPerPlayer);

        c.generationRateLimitPerPlayer = 9999;
        c.validate();
        assertEquals(1000, c.generationRateLimitPerPlayer);
    }

    @Test
    void generationConcurrencyLimitPerPlayerClamped() {
        var c = serverConfig();
        c.generationConcurrencyLimitPerPlayer = 0;
        c.validate();
        assertEquals(1, c.generationConcurrencyLimitPerPlayer);

        c.generationConcurrencyLimitPerPlayer = 9999;
        c.validate();
        assertEquals(1000, c.generationConcurrencyLimitPerPlayer);
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

}
