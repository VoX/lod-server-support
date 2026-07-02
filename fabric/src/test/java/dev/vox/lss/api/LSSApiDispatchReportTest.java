package dev.vox.lss.api;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the implicit-report COUNT in {@link LSSApi#dispatchColumn}: a throwing consumer is
 * treated as exactly one {@code reportIngestFailure} per failing consumer per delivery
 * (the {@link LSSApi#reportIngestFailure} javadoc contract). The package-private report
 * sink records here instead of reaching {@code LSSClientNetworking} (whose Minecraft
 * instance is null in unit tests, so reports would vanish unasserted). Consumer isolation
 * itself is pinned by {@code LSSApiDispatchTest}; the end-to-end re-serve effect by the
 * client gametest's ingest-failure loop.
 */
class LSSApiDispatchReportTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private record Report(ResourceKey<Level> dimension, int chunkX, int chunkZ) {}

    private static ResourceKey<Level> dim() {
        return ResourceKey.create(Registries.DIMENSION, new ResourceLocation("lss_test:dispatch_report"));
    }

    @Test
    void eachFailingConsumerReportsExactlyOncePerDelivery() {
        var dim = dim();
        var columnData = new VoxelColumnData(new VoxelColumnData.SectionData[0], 7L);
        List<Report> reports = new ArrayList<>();
        List<String> healthyDeliveries = new ArrayList<>();
        VoxelColumnConsumer healthy = (level, d, cx, cz, data) -> healthyDeliveries.add(cx + "," + cz);
        VoxelColumnConsumer throwsException = (level, d, cx, cz, data) -> {
            throw new IllegalStateException("storage not ready");
        };
        VoxelColumnConsumer throwsLinkageError = (level, d, cx, cz, data) -> {
            throw new LinkageError("incompatible LOD mod");
        };

        LSSApi.reportSink = (d, cx, cz) -> reports.add(new Report(d, cx, cz));
        LSSApi.registerColumnConsumer(healthy);
        LSSApi.registerColumnConsumer(throwsException);
        LSSApi.registerColumnConsumer(throwsLinkageError);
        try {
            LSSApi.dispatchColumn(null, dim, 12, -34, columnData);

            assertEquals(2, reports.size(),
                    "exactly one implicit ingest-failure report per failing consumer per delivery"
                            + " — fewer leaves a permanent hole, more burns the per-position failure cap early");
            for (var report : reports) {
                assertEquals(new Report(dim, 12, -34), report,
                        "each report must carry the dispatched dimension and chunk coordinates");
            }
            assertEquals(List.of("12,-34"), healthyDeliveries,
                    "the healthy consumer receives the column once and contributes no report");
        } finally {
            LSSApi.removeColumnConsumer(healthy);
            LSSApi.removeColumnConsumer(throwsException);
            LSSApi.removeColumnConsumer(throwsLinkageError);
            LSSApi.resetReportSink();
        }
    }

    @Test
    void fullyHealthyDeliveryReportsNothing() {
        List<Report> reports = new ArrayList<>();
        VoxelColumnConsumer healthy = (level, d, cx, cz, data) -> {};

        LSSApi.reportSink = (d, cx, cz) -> reports.add(new Report(d, cx, cz));
        LSSApi.registerColumnConsumer(healthy);
        try {
            LSSApi.dispatchColumn(null, dim(), 1, 2,
                    new VoxelColumnData(new VoxelColumnData.SectionData[0], 1L));

            assertEquals(List.of(), reports,
                    "an accepted delivery must not report — a spurious report would unstamp"
                            + " and re-download a column that was ingested fine");
        } finally {
            LSSApi.removeColumnConsumer(healthy);
            LSSApi.resetReportSink();
        }
    }
}
