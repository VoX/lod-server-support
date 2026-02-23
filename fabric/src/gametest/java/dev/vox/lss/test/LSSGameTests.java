package dev.vox.lss.test;

import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.server.ChunkChangeTracker;
import dev.vox.lss.networking.server.LSSServerNetworking;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;

public class LSSGameTests {

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void serviceStartsOnDedicatedServer(GameTestHelper helper) {
        helper.assertTrue(
                LSSServerNetworking.getRequestService() != null,
                "RequestProcessingService should be active"
        );
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void diagnosticsReturnMetrics(GameTestHelper helper) {
        var service = LSSServerNetworking.getRequestService();
        helper.assertTrue(service != null, "RequestProcessingService should be active");
        String diag = service.getTickDiagnostics();
        helper.assertTrue(
                diag.contains("last_tick"),
                "Diagnostics should contain tick metrics"
        );
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void noPlayersInitially(GameTestHelper helper) {
        var service = LSSServerNetworking.getRequestService();
        helper.assertTrue(service != null, "RequestProcessingService should be active");
        helper.assertTrue(
                service.getPlayers().isEmpty(),
                "No players should be registered initially"
        );
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void lsslodCommandRegistered(GameTestHelper helper) {
        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        var result = dispatcher.parse(
                "lsslod diag",
                helper.getLevel().getServer().createCommandSourceStack()
        );
        helper.assertTrue(
                result.getExceptions().isEmpty(),
                "lsslod diag command should parse without errors"
        );
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void configLoadedWithValidValues(GameTestHelper helper) {
        var config = LSSServerConfig.CONFIG;
        helper.assertTrue(
                config.lodDistanceChunks >= 1 && config.lodDistanceChunks <= 512,
                "lodDistanceChunks should be in [1, 512]"
        );
        helper.assertTrue(
                config.diskReaderThreads >= 1 && config.diskReaderThreads <= 64,
                "diskReaderThreads should be in [1, 64]"
        );
        helper.succeed();
    }

    // --- New server gametests (S1-S6) ---

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void diskReaderCreatedWhenEnabled(GameTestHelper helper) {
        var service = LSSServerNetworking.getRequestService();
        helper.assertTrue(service != null, "Service should be active");
        helper.assertTrue(service.getDiskReader() != null,
                "DiskReader should be created when enableDiskReading=true");
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void generationServiceCreatedWhenEnabled(GameTestHelper helper) {
        var service = LSSServerNetworking.getRequestService();
        helper.assertTrue(service != null, "Service should be active");
        helper.assertTrue(service.getGenerationService() != null,
                "GenerationService should be created when enableChunkGeneration=true");
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void bandwidthUsageZeroInitially(GameTestHelper helper) {
        var service = LSSServerNetworking.getRequestService();
        helper.assertTrue(service != null, "Service should be active");
        helper.assertTrue(service.getBandwidthLimiter().getTotalBytesSentThisSecond() == 0,
                "Bandwidth usage should be zero with no players");
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void dirtyTrackerDrainClearsState(GameTestHelper helper) {
        // First drain may return data (chunks saved during startup) — that's fine
        ChunkChangeTracker.drainDirty(Level.OVERWORLD);
        // Second drain should be empty since drainDirty clears the set
        long[] second = ChunkChangeTracker.drainDirty(Level.OVERWORLD);
        helper.assertTrue(second == null || second.length == 0,
                "Dirty tracker should be empty after drain");
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void diagnosticsContainAllFields(GameTestHelper helper) {
        var service = LSSServerNetworking.getRequestService();
        helper.assertTrue(service != null, "Service should be active");
        String diag = service.getTickDiagnostics();
        helper.assertTrue(diag.contains("sections_sent"), "Should contain sections_sent");
        helper.assertTrue(diag.contains("disk_queued"), "Should contain disk_queued");
        helper.assertTrue(diag.contains("disk_drained"), "Should contain disk_drained");
        helper.assertTrue(diag.contains("batches_completed"), "Should contain batches_completed");
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void allConfigFieldsInValidRange(GameTestHelper helper) {
        var c = LSSServerConfig.CONFIG;
        helper.assertTrue(c.maxSectionsPerTickPerPlayer >= 1 && c.maxSectionsPerTickPerPlayer <= 10000, "maxSectionsPerTickPerPlayer");
        helper.assertTrue(c.maxBytesPerSecondPerPlayer >= 1024 && c.maxBytesPerSecondPerPlayer <= 104_857_600, "maxBytesPerSecondPerPlayer");
        helper.assertTrue(c.maxConcurrentDiskReads >= 1 && c.maxConcurrentDiskReads <= 512, "maxConcurrentDiskReads");
        helper.assertTrue(c.maxSendQueueSize >= 1 && c.maxSendQueueSize <= 100_000, "maxSendQueueSize");
        helper.assertTrue(c.maxBytesPerSecondGlobal >= 1024 && c.maxBytesPerSecondGlobal <= 1_073_741_824, "maxBytesPerSecondGlobal");
        helper.assertTrue(c.maxRequestsPerBatch >= 1 && c.maxRequestsPerBatch <= 1024, "maxRequestsPerBatch");
        helper.assertTrue(c.maxPendingRequestsPerPlayer >= 1 && c.maxPendingRequestsPerPlayer <= 4096, "maxPendingRequestsPerPlayer");
        helper.assertTrue(c.generationDistanceChunks >= 1 && c.generationDistanceChunks <= 512, "generationDistanceChunks");
        helper.assertTrue(c.maxConcurrentGenerations >= 1 && c.maxConcurrentGenerations <= 256, "maxConcurrentGenerations");
        helper.assertTrue(c.maxConcurrentGenerationsPerPlayer >= 1 && c.maxConcurrentGenerationsPerPlayer <= 64, "maxConcurrentGenerationsPerPlayer");
        helper.assertTrue(c.generationTimeoutSeconds >= 1 && c.generationTimeoutSeconds <= 600, "generationTimeoutSeconds");
        helper.assertTrue(c.dirtyBroadcastIntervalSeconds >= 1 && c.dirtyBroadcastIntervalSeconds <= 300, "dirtyBroadcastIntervalSeconds");
        helper.succeed();
    }
}
