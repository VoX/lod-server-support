package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The region walk's equivalence gate (region-scan-plan.md §10): at an unbounded
 * budget the region-major walk must declare EXACTLY the legacy walk's want-SET —
 * same positions, same timestamps, same confirmed ring — over any column state.
 * Emission ORDER is the deliberate difference (region-major vs global ring-major),
 * so the comparison is set-level; order properties live in {@link RegionScannerTest}.
 * The legacy arm is a FRESH scanner per comparison (no prefix retention), i.e. the
 * from-scratch walk both arms must agree on — pinned to BOTH quadtree variants so the
 * control arm cannot silently depend on a gitignored local config (review fold).
 * TRUNCATED emission is deliberately out of scope: under a binding budget the
 * per-fire SETS differ by design (region-major prefix vs ring-major prefix) — the
 * cross-fire contract there is the union, pinned by the chaos convergence tests in
 * {@link RegionScannerTest}. The region arm's audit rung is disabled here so a
 * genuine needs-mask divergence in the shared fixture cannot be healed mid-compare.
 */
class RegionScanDifferentialTest {

    private static final int BUF = 16384;
    private static final int BUDGET = 1_000_000;

    private static SpiralScanner legacy(int lod, boolean quad) {
        var s = new SpiralScanner();
        s.setConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, lod, true));
        s.quadtreeScanEnabled = () -> quad;     // pin the control arm explicitly
        s.prefixRetentionEnabled = () -> true;  // (fresh per compare — prefix-free anyway)
        return s;
    }

    private static RegionScanner region(int lod) {
        var s = new RegionScanner();
        s.setConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, lod, true));
        s.auditEnabled = false; // never heal the shared fixture mid-compare
        return s;
    }

    private static void assertSameWantSet(ColumnStateMap columns, int lod, int cx, int cz,
                                          int vd, String at) {
        var r = region(lod);
        long[] rPos = new long[BUF], rTs = new long[BUF];
        int rn = r.scan(cx, cz, vd, columns, rPos, rTs, BUDGET);
        var rSet = new TreeMap<Long, Long>();
        for (int i = 0; i < rn; i++) {
            assertNull(rSet.put(rPos[i], rTs[i]), "region duplicate emission " + at);
        }
        for (boolean quad : new boolean[]{true, false}) {
            var l = legacy(lod, quad);
            long[] lPos = new long[BUF], lTs = new long[BUF];
            int ln = l.scan(cx, cz, vd, columns, lPos, lTs, BUDGET);
            String lat = at + " (legacy quad=" + quad + ")";
            assertEquals(ln, rn, "want-set size " + lat);
            var lSet = new TreeMap<Long, Long>();
            for (int i = 0; i < ln; i++) {
                assertNull(lSet.put(lPos[i], lTs[i]), "legacy duplicate emission " + lat);
            }
            assertEquals(lSet, rSet, "want-set content (positions+timestamps) " + lat);
            assertEquals(l.getConfirmedRing(), r.getConfirmedRing(), "confirmedRing " + lat);
            // NOTE deliberately NOT compared: wasLastWalkTruncated — trivially false on
            // both at this budget, and the arms genuinely diverge in the exact-fill
            // corner (pinned in RegionScannerTest.exactFillEndingInSatisfiedTail...).
        }
    }

    @Test
    void emptyAndConvergedStatesAgreeAcrossGeometries() {
        for (int lod : new int[]{2, 8, 24, 33, 40}) {
            for (int[] c : new int[][]{{0, 0}, {31, 31}, {32, 32}, {-1, -17}, {100, -256}}) {
                var empty = new ColumnStateMap();
                assertSameWantSet(empty, lod, c[0], c[1], 4,
                        "(empty, lod " + lod + ", center " + c[0] + "," + c[1] + ")");
                var full = new ColumnStateMap();
                for (int x = c[0] - lod; x <= c[0] + lod; x++) {
                    for (int z = c[1] - lod; z <= c[1] + lod; z++) {
                        long pk = PositionUtil.packPosition(x, z);
                        full.onReceived(pk, 1000L);
                        full.onUpToDate(pk);
                    }
                }
                assertSameWantSet(full, lod, c[0], c[1], 4,
                        "(converged, lod " + lod + ", center " + c[0] + "," + c[1] + ")");
            }
        }
    }

    @Test
    void seededChaosStatesAgree() {
        for (long seed : new long[]{1L, 42L, 20260824L, -13L}) {
            var rng = new Random(seed);
            var columns = new ColumnStateMap();
            int lod = 24 + rng.nextInt(20); // always crosses region boundaries
            int cx = rng.nextInt(80) - 40;
            int cz = rng.nextInt(80) - 40;
            for (int round = 0; round < 12; round++) {
                for (int op = 0; op < 300; op++) {
                    int x = cx + rng.nextInt(2 * lod + 1) - lod;
                    int z = cz + rng.nextInt(2 * lod + 1) - lod;
                    long pk = PositionUtil.packPosition(x, z);
                    switch (rng.nextInt(8)) {
                        case 0, 1, 2 -> {
                            columns.onReceived(pk, 1 + rng.nextInt(5000));
                            columns.onUpToDate(pk);
                        }
                        case 3 -> columns.onReceived(pk, 1 + rng.nextInt(5000)); // unvalidated
                        case 4 -> columns.markDirtyIfKnown(pk);
                        case 5 -> columns.markRetry(pk);
                        case 6 -> columns.onNotGenerated(pk);
                        case 7 -> columns.markSessionSatisfied(pk);
                    }
                }
                int vd = 2 + rng.nextInt(8);
                assertSameWantSet(columns, lod, cx, cz, vd,
                        "(seed " + seed + ", round " + round + ", vd " + vd + ")");
            }
        }
    }

    @Test
    void movedCentersAgreeFromScratch() {
        // Movement on the region arm is stateless; the legacy comparison arm is fresh
        // per compare, so a moved center is just another geometry — both must agree.
        var columns = new ColumnStateMap();
        var rng = new Random(7L);
        for (int i = 0; i < 400; i++) {
            long pk = PositionUtil.packPosition(rng.nextInt(120) - 60, rng.nextInt(120) - 60);
            columns.onReceived(pk, 1 + rng.nextInt(3000));
            if (rng.nextBoolean()) columns.onUpToDate(pk);
        }
        int cx = 0, cz = 0;
        for (int step = 0; step < 24; step++) {
            cx += rng.nextInt(9) - 4;
            cz += rng.nextInt(9) - 4;
            assertSameWantSet(columns, 24, cx, cz, 4, "(walk step " + step + ")");
        }
    }
}
