package dev.vox.lss.networking.client;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Real acquisition owner for composed consumer tests in the compat package. */
public final class ColumnDeliveryFixture {
    private final LodRequestManager manager = new LodRequestManager();
    private final ResourceKey<Level> dimension;
    private final long packed;
    private final int x, z;

    public ColumnDeliveryFixture(ResourceKey<Level> dimension, int x, int z) {
        this.dimension = dimension;
        this.x = x;
        this.z = z;
        this.packed = PositionUtil.packPosition(x, z);
        this.manager.onSessionConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, 256, true), "xaero-receipt");
        this.manager.setLastDimensionForTest(dimension);
        this.manager.markCacheLoadedForTest();
        this.manager.deliveryExecutor = Runnable::run;
    }

    public void dispatch(long timestamp, Runnable consumer) {
        long prior = this.manager.contentStampBeforeDelivery(this.packed);
        if (!this.manager.onColumnReceived(this.packed, timestamp, this.dimension)) throw new AssertionError("out of range");
        var receipt = this.manager.trackDelivery(this.dimension, this.packed, prior);
        LSSApi.withIngestFailureHandle(this.dimension, this.x, this.z, receipt, consumer);
        receipt.complete();
    }

    public void retire() { this.manager.retireAcquisition(); }
    public long timestamp() { return this.manager.columnsForTest().timestampFor(this.packed); }
    public long failures() { return this.manager.getTotalIngestFailures(); }
}
