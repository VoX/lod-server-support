package dev.vox.lss.common.region;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the summary diag line's INERT contract (null until the first request — soak and
 * benchmark diag output stays byte-unchanged on non-opt-in runs) and its rendered
 * shape, including the no_region disposition (P2 review H-m3: "everything clean" and
 * "no region files at all" must be distinguishable in the one instrument the plan
 * nominates for attributability).
 */
class RegionSummaryDiagnosticsTest {

    @Test
    void lineIsNullUntilTheFirstRequest() {
        var diag = new RegionSummaryDiagnostics();
        assertNull(diag.diagLineOrNull(), "untouched feature = no diag line");
        // Assembly/tile activity alone (no request) keeps it null too — requests are
        // the ingress; nothing else can move first in production.
        diag.recordTiles(1, 2, 3);
        diag.recordRefreshMillis(5);
        assertNull(diag.diagLineOrNull());
        diag.recordRequest();
        assertNotNull(diag.diagLineOrNull());
    }

    @Test
    void lineRendersEveryDispositionIncludingNoRegion() {
        var diag = new RegionSummaryDiagnostics();
        diag.recordRequest();
        diag.recordRequest();
        diag.recordRangeFiltered();
        diag.recordTiles(100, 3, 17);
        diag.recordFrameSent(4200);
        diag.recordFrameSent(1);
        diag.recordRefreshMillis(12);
        diag.recordRefreshMillis(4); // gauge keeps the high-water
        // Asymmetric stamps counts (final panel): entries/frames MUST render in that
        // order — a 0/0 pin could not catch a transposed format string.
        diag.recordStampsFrame(37, 400);
        diag.recordStampsFrame(5, 60);
        assertEquals("Summary: reqs=2, frames=2, tiles known=100 never_clean=3"
                        + " no_region=17, range_filtered=1, bytes=4201, refresh_ms_max=12,"
                        + " stamps=42/2",
                diag.diagLineOrNull());
    }
}
