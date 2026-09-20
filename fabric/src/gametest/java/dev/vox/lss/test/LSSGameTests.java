package dev.vox.lss.test;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.server.LSSServerNetworking;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;

public class LSSGameTests {

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void serviceStartsOnDedicatedServer(GameTestHelper helper) {
        Gt.assertTrue(helper, 
                LSSServerNetworking.getRequestService() != null,
                "RequestProcessingService should be active"
        );
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void noPlayersInitially(GameTestHelper helper) {
        var service = LSSServerNetworking.getRequestService();
        Gt.assertTrue(helper, service != null, "RequestProcessingService should be active");
        Gt.assertTrue(helper, 
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
        Gt.assertTrue(helper, 
                result.getExceptions().isEmpty(),
                "lsslod diag command should parse without errors"
        );
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void diskReaderAlwaysCreated(GameTestHelper helper) {
        var service = LSSServerNetworking.getRequestService();
        Gt.assertTrue(helper, service != null, "Service should be active");
        Gt.assertTrue(helper, service.getDiskReader() != null,
                "DiskReader should always be created");
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void generationServiceCreatedWhenEnabled(GameTestHelper helper) {
        var service = LSSServerNetworking.getRequestService();
        Gt.assertTrue(helper, service != null, "Service should be active");
        Gt.assertTrue(helper, service.getGenerationService() != null,
                "GenerationService should be created when enableChunkGeneration=true");
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void bandwidthUsageZeroInitially(GameTestHelper helper) {
        var service = LSSServerNetworking.getRequestService();
        Gt.assertTrue(helper, service != null, "Service should be active");
        Gt.assertTrue(helper, service.getBandwidthLimiter().getTotalBytesSent() == 0,
                "Bandwidth usage should be zero with no players");
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void dirtyTrackerDrainClearsState(GameTestHelper helper) {
        var service = LSSServerNetworking.getRequestService();
        Gt.assertTrue(helper, service != null, "Service should be active");
        var tracker = service.getDirtyTracker();
        // First drain may return data (chunks marked dirty during startup) — that's fine
        tracker.drainDirty(LSSConstants.DIM_STR_OVERWORLD);
        // Second drain should be empty since drainDirty clears the set
        long[] second = tracker.drainDirty(LSSConstants.DIM_STR_OVERWORLD);
        Gt.assertTrue(helper, second == null || second.length == 0,
                "Dirty tracker should be empty after drain");
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void diagnosticsContainAllFields(GameTestHelper helper) {
        var service = LSSServerNetworking.getRequestService();
        Gt.assertTrue(helper, service != null, "Service should be active");
        String diag = service.getTickDiagnostics();
        Gt.assertTrue(helper, diag.contains("sent="), "Should contain sent=");
        Gt.assertTrue(helper, diag.contains("disk="), "Should contain disk=");
        Gt.assertTrue(helper, diag.contains("utd="), "Should contain utd=");
        Gt.assertTrue(helper, diag.contains("gen="), "Should contain gen=");
        helper.succeed();
    }

    /**
     * WP-024: the VoxelColumn wire format writes each sectionY as a single signed byte
     * ({@code buf.writeByte(sectionY)} in both serializers). Every registered dimension
     * type's section range must fit [-128, 127] — a future world-height bump past that
     * range would alias section coordinates silently on the wire; this fails loudly first.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void registeredDimensionTypeSectionRangesFitTheWireByte(GameTestHelper helper) {
        var registry = helper.getLevel().getServer().registryAccess()
                .lookupOrThrow(Registries.DIMENSION_TYPE);
        int checked = 0;
        for (var entry : registry.entrySet()) {
            var type = entry.getValue();
            int minSection = SectionPos.blockToSectionCoord(type.minY());
            int maxSection = SectionPos.blockToSectionCoord(type.minY() + type.height() - 1);
            Gt.assertTrue(helper, minSection >= Byte.MIN_VALUE && maxSection <= Byte.MAX_VALUE,
                    entry.getKey().location() + " section range [" + minSection + ".."
                            + maxSection + "] no longer fits the single-byte sectionY wire "
                            + "field — the protocol needs a wider encoding before this "
                            + "dimension can ship");
            checked++;
        }
        Gt.assertTrue(helper, checked >= 3,
                "premise: vanilla's three dimension types must be registered, saw " + checked);
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void allConfigFieldsInValidRange(GameTestHelper helper) {
        var c = LSSServerConfig.CONFIG;
        // The bandwidth pair reads through the resolved accessors (2026-08-08 rename):
        // the raw fields are a file-format concern and sit at the -1 sentinel post-validate.
        Gt.assertTrue(helper, c.bytesPerSecondPerPlayer() >= LSSConstants.MIN_BYTES_PER_SECOND && c.bytesPerSecondPerPlayer() <= LSSConstants.MAX_BYTES_PER_SECOND_PER_PLAYER, "bytesPerSecondPerPlayer");
        Gt.assertTrue(helper, c.sendQueueLimitPerPlayer >= LSSConstants.MIN_SEND_QUEUE_SIZE && c.sendQueueLimitPerPlayer <= LSSConstants.MAX_SEND_QUEUE_SIZE, "sendQueueLimitPerPlayer");
        Gt.assertTrue(helper, c.bytesPerSecondGlobal() >= LSSConstants.MIN_BYTES_PER_SECOND && c.bytesPerSecondGlobal() <= LSSConstants.MAX_BYTES_PER_SECOND_GLOBAL_LIMIT, "bytesPerSecondGlobal");
        Gt.assertTrue(helper, c.generationConcurrencyLimitGlobal >= LSSConstants.MIN_CONCURRENT_GENERATIONS && c.generationConcurrencyLimitGlobal <= LSSConstants.MAX_CONCURRENT_GENERATIONS, "generationConcurrencyLimitGlobal");
        Gt.assertTrue(helper, c.generationTimeoutSeconds >= LSSConstants.MIN_GENERATION_TIMEOUT && c.generationTimeoutSeconds <= LSSConstants.MAX_GENERATION_TIMEOUT, "generationTimeoutSeconds");
        // 0 = dirty pushes disabled (v0.11.0) — a first-class value beside the sending band.
        Gt.assertTrue(helper, c.dirtyBroadcastIntervalSeconds == 0 || (c.dirtyBroadcastIntervalSeconds >= LSSConstants.MIN_DIRTY_BROADCAST_INTERVAL && c.dirtyBroadcastIntervalSeconds <= LSSConstants.MAX_DIRTY_BROADCAST_INTERVAL), "dirtyBroadcastIntervalSeconds");
        // The real clamp semantic (R-2 / config review 9.1): per-player is bounded by the
        // configured GLOBAL cap, not a protocol constant (MAX_CONCURRENCY_LIMIT is deleted).
        Gt.assertTrue(helper, c.generationConcurrencyLimitPerPlayer >= LSSConstants.MIN_CONCURRENCY_LIMIT && c.generationConcurrencyLimitPerPlayer <= c.generationConcurrencyLimitGlobal, "generationConcurrencyLimitPerPlayer");
        helper.succeed();
    }

    /** Real engine wing state, on an entity deliberately never added/ticked in the world. */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void proxyWingStateAdvancesOnlyOnNewAnimationTicks(GameTestHelper helper) {
        var entity = new WingPoseEntity(helper.getLevel());
        var state = new net.minecraft.world.entity.ElytraAnimationState(entity);
        entity.gliding = true;
        dev.vox.lss.networking.client.FarPlayerWingAnimation.advance(state, true);
        float firstX = state.getRotX(1), firstZ = state.getRotZ(1);
        Gt.assertTrue(helper, firstX > 0 && firstZ < 0, "zero-motion glide must spread wings");
        dev.vox.lss.networking.client.FarPlayerWingAnimation.advance(state, false);
        Gt.assertTrue(helper, firstX == state.getRotX(1) && firstZ == state.getRotZ(1),
                "a second draw in the same tick must not advance interpolation");
        for (int n = 0; n < 40; n++) dev.vox.lss.networking.client.FarPlayerWingAnimation.advance(state, true);
        Gt.assertTrue(helper, Math.abs(state.getRotZ(1) + Math.PI / 2) < 0.001, "glide converges to full spread");
        entity.gliding = false;
        for (int n = 0; n < 40; n++) dev.vox.lss.networking.client.FarPlayerWingAnimation.advance(state, true);
        Gt.assertTrue(helper, Math.abs(state.getRotZ(1) + Math.PI / 12) < 0.001, "standing folds the wings");
        entity.crouching = true;
        for (int n = 0; n < 40; n++) dev.vox.lss.networking.client.FarPlayerWingAnimation.advance(state, true);
        Gt.assertTrue(helper, Math.abs(state.getRotZ(1) + Math.PI / 4) < 0.001, "crouching changes the wing pose");
        helper.succeed();
    }

    private static final class WingPoseEntity extends net.minecraft.world.entity.decoration.ArmorStand {
        boolean gliding, crouching;
        WingPoseEntity(net.minecraft.world.level.Level level) { super(level, 0, 0, 0); }
        @Override public boolean isFallFlying() { return gliding; }
        @Override public boolean isCrouching() { return crouching; }
    }
}
