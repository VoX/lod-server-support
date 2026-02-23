package dev.vox.lss.test;

import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.client.LSSClientNetworking;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
public class LSSClientGameTests implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        // Reduce render and simulation distance to prevent llvmpipe software
        // rendering from starving the integrated server on CI's 2-vCPU runners.
        // Spawn preparation is hardcoded to ~529 chunks regardless of view distance,
        // but lower settings reduce client-side rendering work so the chunk worker
        // threads get enough CPU time to complete generation within the 60s timeout.
        context.runOnClient(client -> {
            client.options.renderDistance().set(2);
            client.options.simulationDistance().set(2);
        });

        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            // Wait for join -> handshake -> session config -> LodRequestManager creation
            context.waitTicks(40);

            // Test 1: LSS activates on join
            if (!LSSClientNetworking.isServerEnabled()) {
                throw new AssertionError("LSS should be enabled after handshake");
            }
            if (LSSClientNetworking.getServerLodDistance() <= 0) {
                throw new AssertionError("Server LOD distance should be set");
            }
            if (LSSClientNetworking.getRequestManager() == null) {
                throw new AssertionError("LodRequestManager should be created");
            }

            // C1: Session config LOD distance propagation
            if (LSSClientNetworking.getServerLodDistance() != LSSServerConfig.CONFIG.lodDistanceChunks) {
                throw new AssertionError("Server LOD distance should match config: expected "
                        + LSSServerConfig.CONFIG.lodDistanceChunks + ", got " + LSSClientNetworking.getServerLodDistance());
            }

            // C2: Effective LOD distance calculation
            var manager = LSSClientNetworking.getRequestManager();
            if (manager.getEffectiveLodDistanceChunks() <= 0) {
                throw new AssertionError("Effective LOD distance should be positive");
            }

            // Wait for scanning + server processing + response
            context.waitTicks(200);

            // Test 2: Client receives sections
            if (LSSClientNetworking.getSectionsReceived() <= 0) {
                throw new AssertionError("Client should have received at least one section");
            }
            if (LSSClientNetworking.getBytesReceived() <= 0) {
                throw new AssertionError("Client should have received bytes");
            }

            // Test 3: Request manager tracks state
            manager = LSSClientNetworking.getRequestManager();
            if (manager == null) {
                throw new AssertionError("No request manager");
            }
            if (manager.getReceivedColumnCount() <= 0) {
                throw new AssertionError("Should have received column timestamps");
            }
            if (manager.getTotalBatchesSent() <= 0) {
                throw new AssertionError("Should have sent at least one batch");
            }

            // C3: Spiral scan made progress
            if (manager.getTotalBatchesSent() <= 0) {
                throw new AssertionError("Should have sent at least one batch");
            }
            if (manager.getTotalPositionsRequested() <= 0) {
                throw new AssertionError("Should have requested at least one position");
            }

            // C4: Batch lifecycle completion
            if (manager.getActiveBatchCount() >= manager.getTotalBatchesSent()) {
                throw new AssertionError("Some batches should have completed: active="
                        + manager.getActiveBatchCount() + " total=" + manager.getTotalBatchesSent());
            }

            // C5: Timestamp tracking (already asserted via getReceivedColumnCount above)
            if (manager.getReceivedColumnCount() <= 0) {
                throw new AssertionError("Should have received column timestamps");
            }

            // C6: Bandwidth is bounded
            long maxExpectedBytes = (long) LSSServerConfig.CONFIG.maxBytesPerSecondPerPlayer * 15;
            if (LSSClientNetworking.getBytesReceived() > maxExpectedBytes) {
                throw new AssertionError("Bytes received exceeds bandwidth budget: "
                        + LSSClientNetworking.getBytesReceived() + " > " + maxExpectedBytes);
            }

            // C7: No pending column leaks — wait for in-flight to settle
            context.waitTicks(100); // 300 total ticks

            if (manager.getPendingColumnCount() > manager.getTotalPositionsRequested() / 2) {
                throw new AssertionError("Too many pending columns after settling: "
                        + manager.getPendingColumnCount() + " of " + manager.getTotalPositionsRequested());
            }

            // C8: Generation budget propagated
            if (LSSServerConfig.CONFIG.enableChunkGeneration && manager.getGenerationBudget() <= 0) {
                throw new AssertionError("Generation budget should be set when generation is enabled");
            }

        }
    }
}
