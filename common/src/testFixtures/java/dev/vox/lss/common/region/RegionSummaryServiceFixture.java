package dev.vox.lss.common.region;

import java.util.function.LongSupplier;

/** Access to the existing clock seam for composed server/client lifecycle fixtures. */
public final class RegionSummaryServiceFixture {
    private RegionSummaryServiceFixture() {}

    public static RegionSummaryService create(RegionSummaryService.TileStampSource source,
                                               LongSupplier clock) {
        return new RegionSummaryService(source, () -> 12, clock);
    }
}
