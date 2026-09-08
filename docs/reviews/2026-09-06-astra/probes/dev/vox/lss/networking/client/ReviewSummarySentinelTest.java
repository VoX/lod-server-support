package dev.vox.lss.networking.client;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.region.RegionSummaryWire;
import dev.vox.lss.config.LSSClientConfig;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** External regression probe: old-then-new summary with a doubt sentinel. */
class ReviewSummarySentinelTest {
    @BeforeAll static void boot() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @Test void neverCleanMustRevokeAnOlderSummaryProof() { check(RegionSummaryWire.STAMP_NEVER_CLEAN); }
    @Test void noRegionMustRevokeAnOlderSummaryProof() { check(RegionSummaryWire.STAMP_NO_REGION); }
    private void check(long freshSentinel) {
        boolean old = LSSClientConfig.CONFIG.enableRegionSummarySync;
        LSSClientConfig.CONFIG.enableRegionSummarySync = true;
        try {
            var manager = new LodRequestManager();
            ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse("lss_test:review_summary"));
            manager.markCacheLoadedForTest();
            manager.setLastDimensionForTest(dim);
            long p = PositionUtil.packPosition(10, 3);
            var saved = new Long2LongOpenHashMap();
            saved.put(p, 7000L);
            manager.columnsForTest().loadFrom(saved);
            manager.onRegionSummaryFrame(frame(dim, 1000L));
            assertEquals(ColumnStateMap.SATISFIED, manager.columnsForTest().classify(p));
            // Later frame describes changed/unverifiable terrain. This position's
            // proof came ONLY from the older summary, not a column response.
            manager.onRegionSummaryFrame(frame(dim, freshSentinel));
            assertEquals(7000L, manager.columnsForTest().classify(p),
                    "New doubt must retract old summary proof and make the cached column re-ask");
        } finally { LSSClientConfig.CONFIG.enableRegionSummarySync = old; }
    }
    private byte[] frame(ResourceKey<Level> dim, long stamp) {
        return RegionSummaryWire.encodeSummary(new RegionSummaryWire.Summary(
                dim.location().toString(), 0, 0, 0, new long[]{stamp}));
    }
}
