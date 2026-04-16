package dev.vox.lss.test;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.client.LSSClientNetworking;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
public class LSSClientGameTests implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        // Register a no-op consumer so the handshake includes CAPABILITY_VOXEL_COLUMNS.
        // Without a consumer, capabilities=0 and the server skips all request routing.
        LSSApi.registerColumnConsumer((level, dimension, chunkX, chunkZ, columnData) -> {});

        // Low values prevent llvmpipe from starving the integrated server on CI
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

            // Wait for scanning + server processing + response.
            // CI runners (2 vCPU, llvmpipe software rendering) are heavily starved —
            // the server can fall 2+ seconds behind during spawn prep, so we need
            // generous tick budgets for generation + voxelization + send cycles.
            context.waitTicks(400);

            // Test 2: Client receives columns
            if (LSSClientNetworking.getColumnsReceived() <= 0) {
                throw new AssertionError("Client should have received at least one column");
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
            if (manager.getTotalSendCycles() <= 0) {
                throw new AssertionError("Should have sent at least one send cycle");
            }

            // C3: Spiral scan made progress
            if (manager.getTotalPositionsRequested() <= 0) {
                throw new AssertionError("Should have requested at least one position");
            }

            // C4: Request/response lifecycle (v9 — individual responses, no batch completion)
            long totalResponses = manager.getTotalColumnsReceived()
                    + manager.getTotalUpToDate()
                    + manager.getTotalNotGenerated();
            if (totalResponses <= 0) {
                throw new AssertionError("Should have received at least one response: "
                        + "columns=" + manager.getTotalColumnsReceived()
                        + " upToDate=" + manager.getTotalUpToDate()
                        + " notGenerated=" + manager.getTotalNotGenerated());
            }

            // C5: Bandwidth is bounded
            long maxExpectedBytes = (long) LSSServerConfig.CONFIG.bytesPerSecondLimitPerPlayer * 15;
            if (LSSClientNetworking.getBytesReceived() > maxExpectedBytes) {
                throw new AssertionError("Bytes received exceeds bandwidth budget: "
                        + LSSClientNetworking.getBytesReceived() + " > " + maxExpectedBytes);
            }

            // C6: Columns received after settling
            context.waitTicks(200); // 600 total ticks

            if (manager.getTotalColumnsReceived() <= 0) {
                throw new AssertionError("Should have received columns after settling: "
                        + manager.getTotalColumnsReceived());
            }


        }
    }
}
