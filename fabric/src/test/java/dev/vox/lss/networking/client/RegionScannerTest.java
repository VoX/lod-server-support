package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct coverage of the region-major walk (region-scan-plan.md): region-spiral
 * emission order with within-region ring ascent, the complete-prefix + one-partial-tail
 * invariant, the per-position lod clamp on over-covering boundary regions, the v1.1
 * confirmed semantics (min observed unresolved ring; lod+1 converged), the needs-free
 * region skip, the audit rung's stranded-orphan heal, and the as-built cadence cost
 * policy (movement window prices the disc, stationary prices zero). Set-level parity
 * with the legacy walk is {@link RegionScanDifferentialTest}'s job.
 */
class RegionScannerTest {

    private static final int CX = 0;
    private static final int CZ = 0;

    private static RegionScanner scanner(int lodDistance) {
        var s = new RegionScanner();
        s.setConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true,
                lodDistance, true));
        return s;
    }

    /** Drive maybeScan until the cadence fires; returns the want-set size. */
    private static int fireScan(RegionScanner s, int cx, int cz, int viewDistance,
                                ColumnStateMap columns, long[] pos, long[] ts) {
        for (int i = 0; i < LSSConstants.TICKS_PER_SECOND + 1; i++) {
            int n = s.maybeScan(cx, cz, viewDistance, 0, 1000, 0L, Long.MAX_VALUE,
                    -1, 1000, () -> 0, columns, pos, ts);
            if (n >= 0) return n;
        }
        throw new AssertionError("scan cadence never fired");
    }

    private static long[] buf() {
        return new long[LSSConstants.MAX_BATCH_CHUNK_REQUESTS];
    }

    /** Satisfy every position of the square |cx|,|cz| <= radius except the given ones. */
    private static void satisfySquare(ColumnStateMap columns, int radius, long... except) {
        var skip = new HashSet<Long>();
        for (long e : except) skip.add(e);
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                long pk = PositionUtil.packPosition(x, z);
                if (skip.contains(pk)) continue;
                columns.onReceived(pk, 1000L);
                columns.onUpToDate(pk);
            }
        }
    }

    /** True when the position is part of the walk's want-set universe. */
    private static boolean wanted(int x, int z, int lod, int vd) {
        if (Math.abs(x - CX) > lod || Math.abs(z - CZ) > lod) return false;
        if (x == CX && z == CZ) return false; // ring-0 legacy parity
        return !SpiralScanner.isVanillaRendered(x, z, CX, CZ, vd);
    }

    /** Region-major order: each region a single contiguous block, blocks in
     *  non-decreasing region-ring order, positions ring-ascending within a block. */
    private static void assertRegionMajorOrder(long[] pos, int n) {
        var seen = new HashSet<Long>();
        long curRegion = Long.MIN_VALUE;
        int curRegionRing = -1;
        int lastPosRing = -1;
        for (int i = 0; i < n; i++) {
            int x = PositionUtil.unpackX(pos[i]);
            int z = PositionUtil.unpackZ(pos[i]);
            int rx = x >> 5;
            int rz = z >> 5;
            long rk = PositionUtil.packPosition(rx, rz);
            if (rk != curRegion) {
                assertTrue(seen.add(rk), "region (" + rx + "," + rz
                        + ") re-entered at index " + i + " — interleaved emission");
                int rr = Math.max(Math.abs(rx - (CX >> 5)), Math.abs(rz - (CZ >> 5)));
                assertTrue(rr >= curRegionRing,
                        "region ring regressed at index " + i + " (" + rr + " < " + curRegionRing + ")");
                curRegion = rk;
                curRegionRing = rr;
                lastPosRing = -1;
            }
            int ring = Math.max(Math.abs(x - CX), Math.abs(z - CZ));
            assertTrue(ring >= lastPosRing,
                    "within-region ring order regressed at index " + i);
            lastPosRing = ring;
        }
    }

    @Test
    void firstWalkDeclaresTheWholeAnnulusInRegionMajorOrder() {
        int lod = 24; // one 2x2 region block around the origin, fits the 800 budget? no:
        // (49^2 - excluded) > 800 — use the count check against the brute universe under
        // budget, and the order property on what WAS emitted.
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        int vd = 4;
        int n = fireScan(s, CX, CZ, vd, columns, pos, ts);

        int universe = 0;
        for (int x = -lod; x <= lod; x++) {
            for (int z = -lod; z <= lod; z++) {
                if (wanted(x, z, lod, vd)) universe++;
            }
        }
        assertEquals(Math.min(universe, LSSConstants.WANT_SET_BUDGET), n,
                "the walk fills the budget from the annulus (universe=" + universe + ")");
        assertRegionMajorOrder(pos, n);
        for (int i = 0; i < n; i++) {
            assertTrue(wanted(PositionUtil.unpackX(pos[i]), PositionUtil.unpackZ(pos[i]), lod, vd),
                    "emitted position outside the want universe at " + i);
            assertEquals(-1L, ts[i], "a never-seen position declares -1");
        }
    }

    @Test
    void emissionIsACompletePrefixPlusAtMostOnePartialTail() {
        int lod = 40; // spans regions [-2..1]^2 — budget 800 must truncate
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        int vd = 4;
        int n = fireScan(s, CX, CZ, vd, columns, pos, ts);
        assertEquals(LSSConstants.WANT_SET_BUDGET, n, "premise: the budget truncates");
        assertTrue(s.wasLastWalkTruncated(), "truncation is flagged");

        // Group the emission by region (contiguity is asserted separately) and check
        // every group except the LAST equals that region's full needy count.
        var groups = new java.util.LinkedHashMap<Long, Integer>();
        for (int i = 0; i < n; i++) {
            long rk = PositionUtil.packPosition(
                    PositionUtil.unpackX(pos[i]) >> 5, PositionUtil.unpackZ(pos[i]) >> 5);
            groups.merge(rk, 1, Integer::sum);
        }
        int gi = 0;
        for (var e : groups.entrySet()) {
            gi++;
            int rx = PositionUtil.unpackX(e.getKey());
            int rz = PositionUtil.unpackZ(e.getKey());
            int brute = 0;
            for (int x = rx << 5; x < (rx << 5) + 32; x++) {
                for (int z = rz << 5; z < (rz << 5) + 32; z++) {
                    if (wanted(x, z, lod, vd)) brute++;
                }
            }
            if (gi < groups.size()) {
                assertEquals(brute, e.getValue(),
                        "non-tail region (" + rx + "," + rz + ") must emit COMPLETELY");
            } else {
                assertTrue(e.getValue() <= brute, "the tail cannot over-emit");
            }
        }
        assertEquals(groups.size(), s.getRegionSpan(), "region_span = emitted groups");
        assertTrue(s.getRegionSpan() <= 2,
                "dense fill: complete prefix + one partial tail spans <= 2 regions, got "
                        + s.getRegionSpan());
        assertRegionMajorOrder(pos, n);
    }

    @Test
    void steadyFillKeepsSpanAtMostTwoAsTheFrontierAdvances() {
        int lod = 40;
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        int fires = 0;
        while (true) {
            int n = fireScan(s, CX, CZ, 4, columns, pos, ts);
            if (n == 0) break;
            // span <= 2 whenever the fire stays in FULL-size regions (>= 1024-position
            // budget geometry); once the frontier reaches the lod-boundary CLIPPED
            // regions (an 81-288-position sliver each at this lod), one 800-budget
            // fire legitimately spans several — the complete-prefix invariant, not a
            // fixed span, is the real pin there (asserted in its own test).
            boolean allFull = true;
            for (int i = 0; i < n; i++) {
                int rx = PositionUtil.unpackX(pos[i]) >> 5;
                int rz = PositionUtil.unpackZ(pos[i]) >> 5;
                if ((rx << 5) < -lod || (rx << 5) + 31 > lod
                        || (rz << 5) < -lod || (rz << 5) + 31 > lod) {
                    allFull = false;
                    break;
                }
            }
            assertTrue(s.getRegionSpan() <= (allFull ? 2 : 12),
                    "fire " + fires + ": span " + s.getRegionSpan()
                            + " (allFull=" + allFull + ")");
            for (int i = 0; i < n; i++) {
                columns.onReceived(pos[i], 1000L);
                columns.onUpToDate(pos[i]);
            }
            assertTrue(++fires < 40, "fill never converged");
        }
        assertEquals(lod + 1, s.getConfirmedRing(), "converged disc confirms to lod+1");
        assertFalse(s.wasLastWalkTruncated());
    }

    @Test
    void boundaryRegionsClampPerPositionToTheLodSquare() {
        int lod = 40; // region rx=1 covers cx 32..63 — only 32..40 in-lod
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        // Converge the whole disc, then poke one hole in the boundary region so the
        // walk must visit it without leaking beyond-lod positions.
        satisfySquare(columns, lod, PositionUtil.packPosition(38, 5));
        int n = fireScan(s, CX, CZ, 4, columns, pos, ts);
        assertEquals(1, n, "exactly the boundary hole re-declares");
        assertEquals(PositionUtil.packPosition(38, 5), pos[0]);
        for (int i = 0; i < n; i++) {
            assertTrue(Math.abs(PositionUtil.unpackX(pos[i])) <= lod
                            && Math.abs(PositionUtil.unpackZ(pos[i])) <= lod,
                    "beyond-lod emission from an over-covering boundary region");
        }
    }

    @Test
    void confirmedRingIsTheMinimumObservedUnresolvedRing() {
        int lod = 12;
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        long hole = PositionUtil.packPosition(10, 3); // ring 10, outside vd-4 exclusion
        satisfySquare(columns, lod, hole);
        int n = fireScan(s, CX, CZ, 4, columns, pos, ts);
        assertEquals(1, n);
        assertEquals(10, s.getConfirmedRing(),
                "confirmed = the hole's ring (v1.1 semantics: first unsatisfied ring index)");
        // A satisfied OUTER region must not confirm past an unsatisfied INNER ring:
        // the min is over OBSERVED unresolved rings, so satisfying the hole moves it.
        columns.onReceived(hole, 1000L);
        columns.onUpToDate(hole);
        assertEquals(0, fireScan(s, CX, CZ, 4, columns, pos, ts));
        assertEquals(lod + 1, s.getConfirmedRing(), "converged: lod+1 (soak law scan.confirmed > lod)");
    }

    @Test
    void satisfiedOuterRingsNeverConfirmPastAnUnsatisfiedInnerRing() {
        int lod = 12;
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        long inner = PositionUtil.packPosition(6, 2);    // ring 6, region (0,0)
        long outer = PositionUtil.packPosition(11, 11);  // ring 11, region (0,0)
        satisfySquare(columns, lod, inner, outer);
        int n = fireScan(s, CX, CZ, 4, columns, pos, ts);
        assertEquals(2, n);
        assertEquals(6, s.getConfirmedRing(), "the INNER hole bounds confirmation");
        // Within-region ring ascent puts the inner hole first (both region (0,0) —
        // a cross-region pair would order by the REGION spiral instead).
        assertEquals(inner, pos[0]);
    }

    @Test
    void needsFreeRegionsSkipWithoutAnEmitPass() {
        int lod = 40;
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        satisfySquare(columns, lod);
        assertEquals(0, fireScan(s, CX, CZ, 4, columns, pos, ts));
        // NINE regions skip ([-2..0]^2): the satisfied square's NEGATIVE edge (-40)
        // is 8-chunk-leaf-ALIGNED, so the rx/rz=-2 boundary regions' intersecting
        // leaves are exactly the satisfied ones — while the POSITIVE edge (+40) sits
        // inside leaf 40..47, whose beyond-edge holes read needs (the leaf-granular
        // conservative direction), so rx/rz=+1 regions take the emit pass (finding
        // nothing) instead of skipping.
        assertEquals(9, s.getRegionSkips(), "the nine leaf-aligned satisfied regions skip");
        assertEquals(0, fireScan(s, CX, CZ, 4, columns, pos, ts));
        assertEquals(18, s.getRegionSkips(), "the skip counter is cumulative per walk");
        assertEquals(0, s.getRegionSpan(), "nothing emitted = zero span");
    }

    @Test
    void auditRungHealsAStrandedOrphanWithinTwoFires() {
        // The A-6 hazard end to end: a needy position whose needs bit is corrupted OFF
        // inside an otherwise-clear region is invisible to the walk (the region skips)
        // — a PERMANENT orphan but for the audit rung, which re-derives one region per
        // periodic fire starting at the player's own region.
        int lod = 40;
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        long orphan = PositionUtil.packPosition(20, 20); // ring 20, region (0,0)
        satisfySquare(columns, lod, orphan);
        assertTrue(columns.needsBitForTest(orphan));
        columns.corruptNeedsBitForTest(orphan);

        int n1 = fireScan(s, CX, CZ, 4, columns, pos, ts);
        assertEquals(0, n1, "premise: the corrupted region skips — the orphan is invisible");
        assertEquals(lod + 1, s.getConfirmedRing(), "premise: FALSE convergence");
        assertEquals(1, s.getAuditHeals(), "the same fire's audit healed the leaf");
        assertTrue(columns.needsBitForTest(orphan), "the bit is restored");

        int n2 = fireScan(s, CX, CZ, 4, columns, pos, ts);
        assertEquals(1, n2, "the next walk declares the healed orphan");
        assertEquals(orphan, pos[0]);
        assertEquals(20, s.getConfirmedRing());
    }

    @Test
    void dirtyMarkRedeclaresThroughNeedsBitsAloneNoReopenNeeded() {
        // The manager's dirty flow on this arm: markDirtyIfKnown sets the needs bit,
        // reopenRing is a structural no-op — the next walk re-declares regardless.
        int lod = 12;
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        satisfySquare(columns, lod);
        assertEquals(0, fireScan(s, CX, CZ, 4, columns, pos, ts));
        long dirty = PositionUtil.packPosition(10, 3);
        assertTrue(columns.markDirtyIfKnown(dirty));
        s.reopenRing(10, lod); // the manager still calls it — must stay harmless
        assertEquals(0, s.getReopenedRingCount(), "reopenRing is a no-op on this arm");
        int n = fireScan(s, CX, CZ, 4, columns, pos, ts);
        assertEquals(1, n, "the dirty mark re-declares via its needs bit alone");
        assertEquals(dirty, pos[0]);
    }

    @Test
    void cachedStampsDeclareTheirResyncTimestamps() {
        int lod = 12;
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        // A cache-ADOPTED stamp (loadFrom) is a revalidation need until a summary
        // frame or per-column answer validates it — unlike onReceived, which is a
        // terminal server proof. The walk must declare it with its ts (resync).
        long stamped = PositionUtil.packPosition(9, -4);
        var loaded = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
        loaded.defaultReturnValue(-1L);
        loaded.put(stamped, 5000L);
        columns.loadFrom(loaded);
        satisfySquare(columns, lod, stamped);
        int n = fireScan(s, CX, CZ, 4, columns, pos, ts);
        boolean found = false;
        for (int i = 0; i < n; i++) {
            if (pos[i] == stamped) {
                assertEquals(5000L, ts[i], "a cached stamp declares ts>0 (resync)");
                found = true;
            }
        }
        assertTrue(found, "the unvalidated stamp re-declares");
    }

    @Test
    void movementWindowPricesTheDiscAndStationaryPricesZero() {
        // The AS-BUILT cadence policy (superseding plan §8's region-count rung — see
        // RegionScanner.predictedWalkCost): stationary the stateless walk is free at
        // ANY frontier depth (4 Hz warm backfill survives past ring 260, where the
        // inherited span formula would have refused); in the movement window the
        // whole disc is priced with legacy's from-zero formula, keeping the elytra
        // 1 Hz policy above lod 127.
        var deep = scanner(512);
        assertEquals(0, deep.predictedWalkCost(), "stationary: free at any depth");
        deep.recenter(1);
        assertTrue(deep.recenteredSinceLastFireForTest(), "recenter opens the window");
        assertEquals(4 * 512 * 513, deep.predictedWalkCost(),
                "movement window: the legacy from-zero disc price");

        var small = scanner(100);
        small.recenter(3);
        assertEquals(4 * 100 * 101, small.predictedWalkCost(),
                "a small disc stays under the fast-rescan cost cap even moving");

        assertEquals(Integer.MAX_VALUE, new RegionScanner().predictedWalkCost(),
                "no session config: fail closed like the base");
    }

    @Test
    void aFiredScanClosesTheMovementWindow() {
        var s = scanner(512);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        s.recenter(2);
        assertTrue(s.recenteredSinceLastFireForTest());
        fireScan(s, CX, CZ, 4, columns, pos, ts);
        assertFalse(s.recenteredSinceLastFireForTest(), "the fire path closes the window");
        assertEquals(0, s.predictedWalkCost(), "stationary again: free");
    }

    @Test
    void viewDistanceCoveringTheWholeDiscConfirmsWithoutRequests() {
        var s = scanner(4);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        assertEquals(0, fireScan(s, CX, CZ, 8, columns, pos, ts),
                "vanilla's buffered view covers the whole disc");
        assertEquals(5, s.getConfirmedRing(), "covered = converged (lod+1)");
        assertEquals(0, s.getRegionSpan());
    }

    @Test
    void resetClearsTheRegionCountersAndSurvivesReuse() {
        int lod = 40; // the leaf-aligned negative edge gives real skips (see the census test)
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        satisfySquare(columns, lod);
        fireScan(s, CX, CZ, 4, columns, pos, ts);
        assertTrue(s.getRegionSkips() > 0);
        s.reset();
        assertEquals(0, s.getRegionSkips());
        assertEquals(0, s.getRegionSpan());
        assertEquals(0, s.getAuditHeals());
        // And the scanner still walks correctly after the reset.
        s.setConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, lod, true));
        assertEquals(0, fireScan(s, CX, CZ, 4, columns, pos, ts));
        assertEquals(lod + 1, s.getConfirmedRing());
    }
}
