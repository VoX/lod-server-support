package dev.vox.lss.compat;

import dev.vox.lss.api.VoxelColumnConsumer;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;

import java.util.ArrayList;
import java.util.List;

/** Test door onto the real {@link VoxyCompat} bridge running against the stub Voxy classes,
 *  for end-to-end tests in other packages. Call {@link #reset()} after use. */
public final class VoxyBridgeTestAccess {
    private VoxyBridgeTestAccess() {}

    /** Initialises the bridge and returns the column consumer it registered. A report
     *  from the bridge fails the test. */
    public static VoxelColumnConsumer init() {
        reset();
        List<VoxelColumnConsumer> registered = new ArrayList<>();
        VoxyCompat.reportSink = (dimension, chunkX, chunkZ) -> {
            throw new AssertionError("unexpected ingest-failure report");
        };
        VoxyCompat.consumerRegistrar = registered::add;
        if (!VoxyCompat.init() || registered.size() != 1) {
            throw new AssertionError("VoxyCompat.init did not register the bridge");
        }
        return registered.get(0);
    }

    public static void reset() {
        VoxyCompat.resetSeams();
        WorldIdentifier.reset();
        VoxelIngestService.reset();
        VoxyConfig.reset();
        VoxyCommon.reset();
    }
}
