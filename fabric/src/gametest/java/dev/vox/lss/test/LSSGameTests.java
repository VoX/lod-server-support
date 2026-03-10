package dev.vox.lss.test;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.server.LSSServerNetworking;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

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
    public void diskReaderAlwaysCreated(GameTestHelper helper) {
        var service = LSSServerNetworking.getRequestService();
        helper.assertTrue(service != null, "Service should be active");
        helper.assertTrue(service.getDiskReader() != null,
                "DiskReader should always be created");
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
        helper.assertTrue(service.getBandwidthLimiter().getTotalBytesSent() == 0,
                "Bandwidth usage should be zero with no players");
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void dirtyTrackerDrainClearsState(GameTestHelper helper) {
        var service = LSSServerNetworking.getRequestService();
        helper.assertTrue(service != null, "Service should be active");
        var tracker = service.getDirtyTracker();
        // First drain may return data (chunks marked dirty during startup) — that's fine
        tracker.drainDirty(LSSConstants.DIM_STR_OVERWORLD);
        // Second drain should be empty since drainDirty clears the set
        long[] second = tracker.drainDirty(LSSConstants.DIM_STR_OVERWORLD);
        helper.assertTrue(second == null || second.length == 0,
                "Dirty tracker should be empty after drain");
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void diagnosticsContainAllFields(GameTestHelper helper) {
        var service = LSSServerNetworking.getRequestService();
        helper.assertTrue(service != null, "Service should be active");
        String diag = service.getTickDiagnostics();
        helper.assertTrue(diag.contains("sent="), "Should contain sent=");
        helper.assertTrue(diag.contains("disk="), "Should contain disk=");
        helper.assertTrue(diag.contains("utd="), "Should contain utd=");
        helper.assertTrue(diag.contains("gen="), "Should contain gen=");
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void allConfigFieldsInValidRange(GameTestHelper helper) {
        var c = LSSServerConfig.CONFIG;
        helper.assertTrue(c.bytesPerSecondLimitPerPlayer >= LSSConstants.MIN_BYTES_PER_SECOND && c.bytesPerSecondLimitPerPlayer <= LSSConstants.MAX_BYTES_PER_SECOND_PER_PLAYER, "bytesPerSecondLimitPerPlayer");
        helper.assertTrue(c.sendQueueLimitPerPlayer >= LSSConstants.MIN_SEND_QUEUE_SIZE && c.sendQueueLimitPerPlayer <= LSSConstants.MAX_SEND_QUEUE_SIZE, "sendQueueLimitPerPlayer");
        helper.assertTrue(c.bytesPerSecondLimitGlobal >= LSSConstants.MIN_BYTES_PER_SECOND && c.bytesPerSecondLimitGlobal <= LSSConstants.MAX_BYTES_PER_SECOND_GLOBAL_LIMIT, "bytesPerSecondLimitGlobal");
        helper.assertTrue(c.generationConcurrencyLimitGlobal >= LSSConstants.MIN_CONCURRENT_GENERATIONS && c.generationConcurrencyLimitGlobal <= LSSConstants.MAX_CONCURRENT_GENERATIONS, "generationConcurrencyLimitGlobal");
        helper.assertTrue(c.generationTimeoutSeconds >= LSSConstants.MIN_GENERATION_TIMEOUT && c.generationTimeoutSeconds <= LSSConstants.MAX_GENERATION_TIMEOUT, "generationTimeoutSeconds");
        helper.assertTrue(c.dirtyBroadcastIntervalSeconds >= LSSConstants.MIN_DIRTY_BROADCAST_INTERVAL && c.dirtyBroadcastIntervalSeconds <= LSSConstants.MAX_DIRTY_BROADCAST_INTERVAL, "dirtyBroadcastIntervalSeconds");
        helper.assertTrue(c.syncOnLoadRateLimitPerPlayer >= LSSConstants.MIN_RATE_LIMIT && c.syncOnLoadRateLimitPerPlayer <= LSSConstants.MAX_RATE_LIMIT, "syncOnLoadRateLimitPerPlayer");
        helper.assertTrue(c.syncOnLoadConcurrencyLimitPerPlayer >= LSSConstants.MIN_CONCURRENCY_LIMIT && c.syncOnLoadConcurrencyLimitPerPlayer <= LSSConstants.MAX_CONCURRENCY_LIMIT, "syncOnLoadConcurrencyLimitPerPlayer");
        helper.assertTrue(c.generationRateLimitPerPlayer >= LSSConstants.MIN_RATE_LIMIT && c.generationRateLimitPerPlayer <= LSSConstants.MAX_RATE_LIMIT, "generationRateLimitPerPlayer");
        helper.assertTrue(c.generationConcurrencyLimitPerPlayer >= LSSConstants.MIN_CONCURRENCY_LIMIT && c.generationConcurrencyLimitPerPlayer <= LSSConstants.MAX_CONCURRENCY_LIMIT, "generationConcurrencyLimitPerPlayer");
        helper.succeed();
    }
}
