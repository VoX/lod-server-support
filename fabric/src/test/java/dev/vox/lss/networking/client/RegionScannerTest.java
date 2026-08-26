package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct coverage of the HYBRID walk (region-scan-plan.md + hybrid-scan-plan.md):
 * phase-1 legacy ring order out to N=min(64,lod) with gate-independent ring probes,
 * phase-2 region-spiral residue emission with within-region ring ascent, the
 * complete-prefix + at-most-one-partial-group invariant across BOTH phases, the §2.3
 * truncation convention (phase-1 break skips phase 2), the residue-rect geometry
 * brutes, the v1.1 confirmed semantics (min observed unresolved ring; lod+1
 * converged), the needs-free skips, the audit rung's stranded-orphan heal, and the
 * §3 cadence cost policy. Set-level parity with the legacy walk is
 * {@link RegionScanDifferentialTest}'s job.
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

    /** True when the position is phase-2 (far) universe: wanted AND beyond the near square. */
    private static boolean wantedFar(int x, int z, int lod, int vd, int near) {
        return wanted(x, z, lod, vd) && Math.max(Math.abs(x - CX), Math.abs(z - CZ)) > near;
    }

    /** Satisfy the whole near disc (radius {@code near}) so phase 2 is the walk. */
    private static void satisfyNear(ColumnStateMap columns, int near) {
        for (int x = -near; x <= near; x++) {
            for (int z = -near; z <= near; z++) {
                long pk = PositionUtil.packPosition(x, z);
                columns.onReceived(pk, 1000L);
                columns.onUpToDate(pk);
            }
        }
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
    void smallLodFirstWalkDegeneratesToLegacyRingOrderWithZeroPhase2() {
        // The hybrid degeneracy pin (plan §2.2/§8.4/§8.9): at lod ≤ 64 the whole
        // walk is phase 1 — legacy ring-ASCENDING order, and phase 2 performs
        // literally nothing (the probe-count seam, not a counter reading).
        int lod = 24;
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
        int lastRing = 0;
        for (int i = 0; i < n; i++) {
            int x = PositionUtil.unpackX(pos[i]);
            int z = PositionUtil.unpackZ(pos[i]);
            assertTrue(wanted(x, z, lod, vd), "emitted position outside the want universe at " + i);
            assertEquals(-1L, ts[i], "a never-seen position declares -1");
            int ring = Math.max(Math.abs(x - CX), Math.abs(z - CZ));
            assertTrue(ring >= lastRing, "ring order regressed at index " + i);
            lastRing = ring;
        }
        assertEquals(0, s.phase2Rounds,
                "lod ≤ 64: phase 2 must never RUN (the near < lod gate — probes alone"
                        + " cannot see a wrongly-entered empty-residue spiral)");
        assertEquals(0, s.phase2Probes,
                "lod ≤ 64: phase 2 performs ZERO probes and zero emit passes");
        assertEquals(0, s.getRegionSpan());
    }

    @Test
    void farFillDeclaresTheResidueInRegionMajorOrder() {
        // The far half's order property, isolated: near pre-satisfied so every
        // emission is phase 2 — region-major blocks over the residue.
        int lod = 96;
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        int vd = 4;
        satisfyNear(columns, 64);
        int n = fireScan(s, CX, CZ, vd, columns, pos, ts);
        assertEquals(LSSConstants.WANT_SET_BUDGET, n, "the far annulus out-holds the budget");
        assertRegionMajorOrder(pos, n);
        for (int i = 0; i < n; i++) {
            assertTrue(wantedFar(PositionUtil.unpackX(pos[i]), PositionUtil.unpackZ(pos[i]),
                            lod, vd, 64),
                    "phase 2 emitted inside the near square at " + i);
        }
        assertTrue(s.phase2Probes > 0);
        assertEquals(0, s.getNearRings(), "a satisfied near disc emits no near rings");
    }

    @Test
    void hybridEmitsPhase1RingsBeforeAnyPhase2Region() {
        // The two-phase ORDER seam: with needy work in BOTH zones, every phase-1
        // (ring ≤ 64) emission precedes every phase-2 one.
        int lod = 96;
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        satisfyNear(columns, 64);
        long nearHole = PositionUtil.packPosition(40, 12); // ring 40 — phase 1
        columns.markDirtyIfKnown(nearHole);
        int n = fireScan(s, CX, CZ, 4, columns, pos, ts);
        assertTrue(n > 1);
        assertEquals(nearHole, pos[0], "the near hole emits FIRST (phase 1 before phase 2)");
        for (int i = 1; i < n; i++) {
            assertTrue(Math.max(Math.abs(PositionUtil.unpackX(pos[i])),
                            Math.abs(PositionUtil.unpackZ(pos[i]))) > 64,
                    "everything after phase 1 is far");
        }
        assertEquals(1, s.getNearRings(), "one needy near ring observed");
    }

    @Test
    void emissionIsACompletePrefixPlusAtMostOnePartialTail() {
        // Hybrid re-home (plan §8): the far walk's complete-prefix property needs
        // BOTH phases live — near pre-satisfied (or phase 1 alone eats the budget
        // and phase 2 never emits on a first walk), needy far spanning ≥ 2 groups.
        // The player sits OFF-center (16,16): an origin-centered player's first far
        // sliver is 992 cells ≥ the 800 budget (one group — the dead-branch trap);
        // off-center the slivers are 480-512 and the walk provably spans groups.
        int lod = 96, px = 16, pz = 16;
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        int vd = 4;
        for (int x = px - 64; x <= px + 64; x++) {
            for (int z = pz - 64; z <= pz + 64; z++) {
                long pk = PositionUtil.packPosition(x, z);
                columns.onReceived(pk, 1000L);
                columns.onUpToDate(pk);
            }
        }
        int n = fireScan(s, px, pz, vd, columns, pos, ts);
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
        assertTrue(groups.size() >= 2,
                "premise: the walk must span >= 2 regions or the completeness branch is dead");
        int gi = 0;
        for (var e : groups.entrySet()) {
            gi++;
            int rx = PositionUtil.unpackX(e.getKey());
            int rz = PositionUtil.unpackZ(e.getKey());
            int brute = 0;
            for (int x = rx << 5; x < (rx << 5) + 32; x++) {
                for (int z = rz << 5; z < (rz << 5) + 32; z++) {
                    if (Math.abs(x - px) <= lod && Math.abs(z - pz) <= lod
                            && Math.max(Math.abs(x - px), Math.abs(z - pz)) > 64
                            && !SpiralScanner.isVanillaRendered(x, z, px, pz, vd)) {
                        brute++;
                    }
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
        int lod = 128; // > 2*N: full-residue regions exist (re-homed, impl-review M3)
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        int near = 64;
        int fires = 0;
        boolean sawFullSpanFire = false;
        while (true) {
            int n = fireScan(s, CX, CZ, 4, columns, pos, ts);
            if (n == 0) break;
            // span <= 2 whenever the fire stays in FULL-RESIDUE regions: wholly
            // inside the lod square AND wholly outside the near square (impl-review
            // M3 re-home — a near-straddling region's residue is a clipped sliver,
            // span-exempt like the lod-edge ones; the +lod edge clips to 1-wide
            // slivers because 2*lod+1 is not 32-aligned, so a sliver-band fire
            // legitimately spans many). The complete-prefix invariant, not a fixed
            // span, is the real pin on clipped fires (asserted in its own test);
            // this pin's teeth are the allFull arm.
            boolean allFull = n > 0;
            for (int i = 0; i < n; i++) {
                int rx = PositionUtil.unpackX(pos[i]) >> 5;
                int rz = PositionUtil.unpackZ(pos[i]) >> 5;
                int x0 = rx << 5, z0 = rz << 5;
                boolean inLod = x0 >= -lod && x0 + 31 <= lod && z0 >= -lod && z0 + 31 <= lod;
                boolean outsideNear = x0 > near || x0 + 31 < -near
                        || z0 > near || z0 + 31 < -near;
                if (!inLod || !outsideNear) {
                    allFull = false;
                    break;
                }
            }
            if (allFull && s.getRegionSpan() >= 1) sawFullSpanFire = true;
            assertTrue(s.getRegionSpan() <= (allFull ? 2 : 32),
                    "fire " + fires + ": span " + s.getRegionSpan()
                            + " (allFull=" + allFull + ")");
            for (int i = 0; i < n; i++) {
                columns.onReceived(pos[i], 1000L);
                columns.onUpToDate(pos[i]);
            }
            assertTrue(++fires < 100, "fill never converged");
        }
        assertTrue(sawFullSpanFire,
                "vacuity guard (impl-review M3): at least one fire must emit purely "
                        + "from full-residue regions and exercise the <= 2 arm");
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
        // Ring ascent puts the inner hole first (lod 12 is pure phase 1 on the
        // hybrid arm — legacy ring order; the phase-2 within-region ascent has its
        // own ordering pins at lod > 64).
        assertEquals(inner, pos[0]);
    }

    @Test
    void needsFreeRegionsSkipWithoutAnEmitPass() {
        // Hybrid re-home (plan §8/§7): at a lod > 64 geometry the far phase's probe
        // skips are pinned STRUCTURALLY (probe-skip-only counting; residue-empty
        // regions are silently ignored and never counted) — the exact census is an
        // alignment artifact of TWO boundaries now (near AND lod) and is not the pin.
        int lod = 96;
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        satisfySquare(columns, lod);
        assertEquals(0, fireScan(s, CX, CZ, 4, columns, pos, ts));
        long skips = s.getRegionSkips();
        int probes = s.phase2Probes;
        assertTrue(skips > 0, "a satisfied far annulus probe-skips (got " + skips + ")");
        assertTrue(probes >= skips, "skips are a subset of probed regions");
        assertEquals(0, s.getRegionSpan(), "nothing emitted = zero span");
        assertEquals(0, fireScan(s, CX, CZ, 4, columns, pos, ts));
        assertEquals(skips * 2, s.getRegionSkips(), "the skip counter is cumulative per walk");
        assertEquals(probes * 2, s.phase2Probes, "and so is the probe seam");
    }

    @Test
    void auditRungHealsAStrandedOrphanWithinTwoFires() {
        // The A-6 hazard end to end: a needy position whose needs bit is corrupted OFF
        // inside an otherwise-clear region is invisible to the walk (at this lod it is
        // phase 1's RING probe that skips it; the far phase's rect probe is the same
        // hazard) — a PERMANENT orphan but for the audit rung, which re-derives one
        // region per periodic fire starting at the player's own region.
        int lod = 40;
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        long orphan = PositionUtil.packPosition(20, 20); // ring 20, region (0,0)
        satisfySquare(columns, lod, orphan);
        assertTrue(columns.needsBitForTest(orphan));
        columns.corruptNeedsBitForTest(orphan);

        int n1 = fireScan(s, CX, CZ, 4, columns, pos, ts);
        assertEquals(0, n1, "premise: the corrupted ring probe-skips — the orphan is invisible");
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
    void movementWindowPricesTheTruncatedFrontierAndStationaryPricesObservedWork() {
        // The REPRICED cadence policy (review-panel fold — cadence-lens MAJOR +
        // integration-lens MAJOR; see RegionScanner.predictedWalkCost): the movement
        // window prices legacy's from-zero formula over the TRUNCATED FRONTIER
        // (s = scanRing when the last walk truncated, else the whole disc), and
        // stationary prices the LAST walk's measured observe work — never a flat 0.
        var fresh = scanner(512);
        assertEquals(0, fresh.predictedWalkCost(),
                "no walk yet: nothing observed, nothing to refuse");
        fresh.recenter(1); // no walk happened -> untruncated -> whole-disc price
        assertTrue(fresh.recenteredSinceLastFireForTest(), "recenter opens the window");
        assertEquals(4 * 512 * 513, fresh.predictedWalkCost(),
                "movement + untruncated: the whole disc is priced (1 Hz flight at big lod)");

        var small = scanner(100);
        small.recenter(3);
        assertEquals(4 * 100 * 101, small.predictedWalkCost(),
                "a small disc stays under the fast-rescan cost cap even moving");

        assertEquals(Integer.MAX_VALUE, new RegionScanner().predictedWalkCost(),
                "no session config: fail closed like the base");
    }

    @Test
    void movingTruncatedFillPricesTheFrontierNotTheLod() {
        // The elytra unlock, region-arm edition (cadence-lens MAJOR: pricing the LOD
        // here reverts the pinned legacy behavior at every shipped lod >= 128): a
        // truncated fill walk prices 4*scanRing*(scanRing+1), so a moving client
        // keeps fast fires while the frontier is shallow even at lod 200.
        int lod = 200;
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        satisfySquare(columns, 63); // frontier at ring 64
        int n = fireScan(s, CX, CZ, 4, columns, pos, ts);
        assertEquals(LSSConstants.WANT_SET_BUDGET, n, "premise: the fill truncates");
        assertTrue(s.wasLastWalkTruncated());
        s.recenter(1);
        int cost = s.predictedWalkCost();
        assertEquals(4 * s.getScanRing() * (s.getScanRing() + 1), cost,
                "movement + truncated: the frontier is priced, not the lod");
        assertTrue(cost <= SpiralScanner.FAST_RESCAN_MAX_WALK_COST,
                "the shallow-frontier moving fill MUST stay fast-admissible (cost="
                        + cost + ", scanRing=" + s.getScanRing() + ")");
    }

    @Test
    void massDirtyScatterPricesPastTheCapAndDenseFillDoesNot() {
        // Integration-lens MAJOR end to end: each needy region costs an emit pass, so
        // a WorldEdit-scale scatter is NOT a cheap walk and must fall back to 1 Hz;
        // the dense fill's 1-2 emitting regions stay far under the cap.
        int lod = 160; // hybrid re-derivation (plan §8 said 128; re-derived to 160 as
        // built): phase 1 absorbs the near regions into cheaper ring walks, so the
        // scatter needs the wider far field ([-5..4]^2 at lod 160, ~84+ far regions x
        // ~1040 cells) to price past the cap. N-COUPLED: raising HYBRID_NEAR_RADIUS
        // past ~96 absorbs more of the scatter band into phase-1 ring passes and this
        // premise re-prices BELOW the cap — re-derive the field on any N change.
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        // Converge the disc (drive fills to completion).
        int guard = 0;
        while (true) {
            int n = fireScan(s, CX, CZ, 4, columns, pos, ts);
            assertTrue(s.predictedWalkCost() < SpiralScanner.FAST_RESCAN_MAX_WALK_COST,
                    "dense fill walks stay fast-admissible (cost=" + s.predictedWalkCost() + ")");
            if (n == 0) break;
            for (int i = 0; i < n; i++) {
                columns.onReceived(pos[i], 1000L);
                columns.onUpToDate(pos[i]);
            }
            assertTrue(++guard < 300, "fill never converged");
        }
        int marked = 0;
        for (int rx = -5; rx <= 4; rx++) {
            for (int rz = -5; rz <= 4; rz++) {
                for (int k = 0; k < 2; k++) {
                    long pk = PositionUtil.packPosition((rx << 5) + 12 + k, (rz << 5) + 15);
                    if (columns.markDirtyIfKnown(pk)) marked++;
                }
            }
        }
        assertTrue(marked >= 120, "premise: a broad scatter marked (" + marked + ")");
        // The next walk pays the scatter's observe passes and meters them...
        fireScan(s, CX, CZ, 4, columns, pos, ts);
        assertTrue(s.predictedWalkCost() > SpiralScanner.FAST_RESCAN_MAX_WALK_COST,
                "a mass scatter walk must price past the fast cap (cost="
                        + s.predictedWalkCost() + ") — the plan §8 1 Hz row");
    }

    @Test
    void exactFillEndingInSatisfiedTailIsNotTruncatedOnThisArm() {
        // Deliberate divergence from legacy, pinned (walk-lens review): legacy flags
        // truncated whenever budget fills with ANY rings left, even fully satisfied
        // ones; the region arm flags it only when NEEDY work remains — the stricter,
        // correct-er reading for the governor's window-limited latch. Recorded §14.
        int lod = 40;
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = new long[2048], ts = new long[2048];
        satisfySquare(columns, lod);
        // Re-open exactly 768 cells, all inside region (0,0), outside the exclusion.
        int reopened = 0;
        for (int x = 8; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                if (columns.markDirtyIfKnown(PositionUtil.packPosition(x, z))) reopened++;
            }
        }
        assertEquals(768, reopened);
        int n = s.scan(CX, CZ, 4, columns, pos, ts, 768);
        assertEquals(768, n, "the budget exactly fills on the last needy cell");
        assertFalse(s.wasLastWalkTruncated(),
                "no needy region remained — not truncated on this arm (legacy says true)");
    }

    @Test
    void lodShrinkPruneThenGrowRedeclaresTheAnnulusThroughAbsentLeaves() {
        // The F1-class differential (plan §7): shrink prunes the outer annulus's
        // STATE; on the region arm the re-grown annulus is absent leaves = all-needs,
        // so the stateless walk re-declares every position — the "permanently blank
        // annulus" class is structurally impossible. No prefix rung involved.
        int lod = 24;
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        int guard = 0;
        while (true) {
            int n = fireScan(s, CX, CZ, 4, columns, pos, ts);
            if (n == 0) break;
            for (int i = 0; i < n; i++) {
                columns.onReceived(pos[i], 1000L);
                columns.onUpToDate(pos[i]);
            }
            assertTrue(++guard < 40, "fill never converged");
        }
        // The manager's shrink flow: new session distance + the range prune.
        s.setConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, 8, true));
        columns.pruneOutOfRange(CX, CZ, 8);
        assertEquals(0, fireScan(s, CX, CZ, 4, columns, pos, ts),
                "the shrunk disc is still satisfied");
        // Grow back: the pruned annulus (rings 9..24) must fully re-declare.
        s.setConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, lod, true));
        var redeclared = new HashSet<Long>();
        guard = 0;
        while (true) {
            int n = fireScan(s, CX, CZ, 4, columns, pos, ts);
            if (n == 0) break;
            for (int i = 0; i < n; i++) {
                redeclared.add(pos[i]);
                columns.onReceived(pos[i], 2000L);
                columns.onUpToDate(pos[i]);
            }
            assertTrue(++guard < 40, "re-grow never converged");
        }
        for (int dx = -lod; dx <= lod; dx++) {
            for (int dz = -lod; dz <= lod; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) < 9) continue; // kept by the prune
                if (!wanted(dx, dz, lod, 4)) continue;
                assertTrue(redeclared.contains(PositionUtil.packPosition(dx, dz)),
                        "pruned annulus position (" + dx + "," + dz + ") never re-declared");
            }
        }
        assertEquals(lod + 1, s.getConfirmedRing());
    }

    @Test
    void phase1MatchesTheFreshLegacyWalkOrderUnderBothQuadVariants() {
        // §8 pin 1 (the near-order pin): the hybrid's first emissions are EXACTLY
        // the fresh legacy walk's ring order up to N — pinned against BOTH legacy
        // quad variants (the local-config hazard class: the control arm must not
        // depend on a gitignored file).
        int lod = 96;
        var columns = new ColumnStateMap();
        var rng = new java.util.Random(11L);
        for (int i = 0; i < 500; i++) { // sparse random satisfaction for a mixed near field
            long pk = PositionUtil.packPosition(rng.nextInt(129) - 64, rng.nextInt(129) - 64);
            columns.onReceived(pk, 1 + rng.nextInt(3000));
            columns.onUpToDate(pk);
        }
        var h = scanner(lod);
        h.auditEnabled = false; // never heal the shared fixture mid-compare
        long[] hPos = buf(), hTs = buf();
        int hn = h.scan(CX, CZ, 4, columns, hPos, hTs, LSSConstants.WANT_SET_BUDGET);
        assertEquals(LSSConstants.WANT_SET_BUDGET, hn, "premise: the near disc out-holds the budget");
        // The gate-independence pin (impl-review n4): the HYBRID arm's phase-1 probe
        // must not grow an enableQuadtreeScan check — quad-off hybrid is order-identical.
        var hOff = scanner(lod);
        hOff.auditEnabled = false;
        hOff.quadtreeScanEnabled = () -> false;
        long[] oPos = buf(), oTs = buf();
        int on = hOff.scan(CX, CZ, 4, columns, oPos, oTs, LSSConstants.WANT_SET_BUDGET);
        assertEquals(hn, on, "hybrid quad-off emission count");
        for (int i = 0; i < hn; i++) {
            assertEquals(hPos[i], oPos[i], "hybrid quad-off position[" + i + "]");
            assertEquals(hTs[i], oTs[i], "hybrid quad-off timestamp[" + i + "]");
        }
        for (boolean quad : new boolean[]{true, false}) {
            var l = new SpiralScanner();
            l.setConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, lod, true));
            l.quadtreeScanEnabled = () -> quad;
            long[] lPos = buf(), lTs = buf();
            int ln = l.scan(CX, CZ, 4, columns, lPos, lTs, LSSConstants.WANT_SET_BUDGET);
            assertEquals(hn, ln, "same emission count (quad=" + quad + ")");
            for (int i = 0; i < hn; i++) {
                assertEquals(lPos[i], hPos[i], "position[" + i + "] (quad=" + quad + ")");
                assertEquals(lTs[i], hTs[i], "timestamp[" + i + "] (quad=" + quad + ")");
            }
        }
    }

    @Test
    void aPhase1BudgetBreakSetsTruncatedAndPhase2NeverRuns() {
        // §8 pin 6 (the §2.3 truncation convention + the hybrid complete-prefix
        // form): a mid-phase-1 break is honest truncation, scanRing stays ≤ N
        // (the movement gate then prices ≤ 16,640 — fast fires during near fill),
        // and phase 2 contributes NOTHING.
        int lod = 96;
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        int n = s.scan(CX, CZ, 4, columns, pos, ts, 100);
        assertEquals(100, n);
        assertTrue(s.wasLastWalkTruncated(), "a mid-emission break is needy by definition");
        assertTrue(s.getScanRing() <= 64, "phase-1 truncation caps scanRing at N");
        assertEquals(0, s.phase2Rounds, "a phase-1 break means phase 2 never runs");
        assertEquals(0, s.phase2Probes, "a phase-1 break means phase 2 contributes nothing");
        s.recenter(1);
        assertTrue(s.predictedWalkCost() <= SpiralScanner.FAST_RESCAN_MAX_WALK_COST,
                "moving near fill stays fast-admissible (the plan's stated consequence)");
    }

    @Test
    void observeCostMetersAtLeastTheEmittedWork() {
        // §8 pin 7 (the under-metering hazard): the meter covers the WALKED ring
        // cells, not merely the emitted ones (impl-review m5 — an implementation
        // charging `count` instead of 8r passed the old emitted-only form). A
        // sparse-needy scatter keeps walked >> emitted: satisfy everything except
        // every-5th-cell holes, then require the stationary price to cover the full
        // 8r pass of every needy (non-probe-skipped) ring.
        int lod = 40; // pure phase 1
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        for (int x = -lod; x <= lod; x++) {
            for (int z = -lod; z <= lod; z++) {
                if (x % 5 == 0 && z % 5 == 0) continue; // the needy holes
                long pk = PositionUtil.packPosition(x, z);
                columns.onReceived(pk, 1000L);
                columns.onUpToDate(pk);
            }
        }
        int[] c = new int[2];
        long walked = 0;
        for (int r = RegionScanner.wholeExcludedRings(4) + 1; r <= lod; r++) {
            boolean needy = false;
            for (int i = 0; i < 8 * r && !needy; i++) {
                SpiralScanner.ringIndexToCoord(r, i, CX, CZ, c);
                if (SpiralScanner.isVanillaRendered(c[0], c[1], CX, CZ, 4)) continue;
                needy = columns.classify(PositionUtil.packPosition(c[0], c[1]))
                        != ColumnStateMap.SATISFIED;
            }
            if (needy) walked += 8L * r;
        }
        int n = fireScan(s, CX, CZ, 4, columns, pos, ts);
        assertTrue(n > 0 && n < LSSConstants.WANT_SET_BUDGET,
                "premise: a full, untruncated walk (emitted=" + n + ")");
        assertTrue(walked > 4L * n, "premise: walked dominates emitted (walked="
                + walked + ", emitted=" + n + ")");
        assertTrue(s.predictedWalkCost() >= walked, // stationary read = lastWalkObserveCost
                "the stationary price must cover the walked ring cells (cost="
                        + s.predictedWalkCost() + ", walked=" + walked
                        + ", emitted=" + n + ")");
    }

    @Test
    void movingTruncatedAtADeepPhase2FrontierRefusesFast() {
        // §8 pin 8 arm 2 (impl-review m2): truncation at a DEEP phase-2 frontier
        // must REFUSE the movement-window fast path — only the shallow (near)
        // truncation keeps the elytra unlock; a bug pricing every truncated walk
        // at the near frontier would hold a deep-frontier flyer at 4 Hz forever.
        int lod = 200;
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = buf(), ts = buf();
        satisfySquare(columns, 150); // frontier at ring 151 — deep in phase 2
        int n = fireScan(s, CX, CZ, 4, columns, pos, ts);
        assertEquals(LSSConstants.WANT_SET_BUDGET, n, "premise: the far fill truncates");
        assertTrue(s.wasLastWalkTruncated());
        assertTrue(s.getScanRing() > 64, "premise: the frontier is past N (scanRing="
                + s.getScanRing() + ")");
        s.recenter(1);
        assertTrue(s.predictedWalkCost() > SpiralScanner.FAST_RESCAN_MAX_WALK_COST,
                "a deep-frontier moving truncation must refuse fast (cost="
                        + s.predictedWalkCost() + ")");
    }

    @Test
    void convergedBigLodMovementPricesPastTheCapOnBothArms() {
        // §8 pin 8 arm 3 (impl-review m2): converged big-lod movement prices the
        // whole disc identically on BOTH arms — the delta pin against the legacy
        // scanner (untruncated + recentered ⇒ 4·lod·(lod+1) either way).
        var h = scanner(512);
        var l = new SpiralScanner();
        l.setConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true,
                512, true));
        h.recenter(1);
        l.recenter(1);
        assertEquals(l.predictedWalkCost(), h.predictedWalkCost(),
                "delta pin: both arms price the moving converged disc identically");
        assertTrue(h.predictedWalkCost() > SpiralScanner.FAST_RESCAN_MAX_WALK_COST,
                "and both refuse fast at lod 512");
    }

    @Test
    void exactFillEndingInSatisfiedPhase2RegionsIsNotTruncated() {
        // The §2.3 carry into PHASE 2 (impl-review m8 — the lod-40 twin above now
        // exercises the phase-1 break only): trailing conservatively-probed but
        // observation-complete residue regions after an exactly-consumed budget are
        // NOT truncation (the n == 0 continue precedes the budget check). The +lod
        // edge slivers at lod 96 supply the shape naturally: their straddling
        // leaves' beyond-lod cells keep needs bits (probe false), while the 1-wide
        // in-lod residue itself is fully satisfied (emit pass observes nothing).
        int lod = 96;
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        long[] pos = new long[4096], ts = new long[4096];
        satisfySquare(columns, lod);
        int reopened = 0;
        for (int x = 65; x <= 80; x++) {
            for (int z = 0; z <= 15; z++) { // region (2,0)'s residue, region-ring 2
                if (columns.markDirtyIfKnown(PositionUtil.packPosition(x, z))) reopened++;
            }
        }
        assertEquals(256, reopened, "premise: exactly one region's worth of needy work");
        int n = s.scan(CX, CZ, 4, columns, pos, ts, 256); // budget == the needy work
        assertEquals(256, n, "the walk emits exactly the reopened residue");
        assertFalse(s.wasLastWalkTruncated(),
                "satisfied/observation-complete regions past an exactly-consumed "
                        + "budget are not truncation");
        assertEquals(1, s.getRegionSpan(), "one emitting region");
        assertEquals(1, s.phase2Rounds);
    }

    @Test
    void wholeExcludedRingsMatchesTheRealExclusionShape() {
        // The r₀ formula's brute pin (the walk MISSES rings ≤ r₀ — an overshoot
        // would silently hole the near field): for every vd, every position of
        // every ring ≤ r₀ is vanilla-rendered, and ring r₀+1 has an unrendered cell.
        int[] c = new int[2];
        for (int vd = 0; vd <= 80; vd++) { // past any realistic render-distance-mod vd
            int r0 = RegionScanner.wholeExcludedRings(vd);
            for (int r = 1; r <= r0; r++) {
                for (int i = 0; i < 8 * r; i++) {
                    SpiralScanner.ringIndexToCoord(r, i, 0, 0, c);
                    assertTrue(SpiralScanner.isVanillaRendered(c[0], c[1], 0, 0, vd),
                            "vd " + vd + ": ring " + r + " ≤ r₀=" + r0 + " must be wholly rendered");
                }
            }
            boolean anyUnrendered = false;
            int rNext = r0 + 1;
            for (int i = 0; i < 8 * rNext && !anyUnrendered; i++) {
                SpiralScanner.ringIndexToCoord(rNext, i, 0, 0, c);
                anyUnrendered = !SpiralScanner.isVanillaRendered(c[0], c[1], 0, 0, vd);
            }
            assertTrue(anyUnrendered, "vd " + vd + ": ring r₀+1=" + rNext + " must have an unrendered cell");
        }
    }

    @Test
    void residueRectsDecomposeExactlyAndDisjointly() {
        // The §2.1 residue decomposition brute: union == (clipped \\ near), rects
        // pairwise disjoint, ≤4 — over randomized geometries incl. containment,
        // disjointness, and every straddle shape.
        var rng = new java.util.Random(7L);
        int[][] out = new int[4][4];
        for (int trial = 0; trial < 2000; trial++) {
            int ax0 = rng.nextInt(100) - 50, az0 = rng.nextInt(100) - 50;
            int ax1 = ax0 + rng.nextInt(40), az1 = az0 + rng.nextInt(40);
            int nx0 = rng.nextInt(100) - 50, nz0 = rng.nextInt(100) - 50;
            int nx1 = nx0 + rng.nextInt(60), nz1 = nz0 + rng.nextInt(60);
            int nRects = RegionScanner.residueRects(ax0, az0, ax1, az1, nx0, nz0, nx1, nz1, out);
            assertTrue(nRects <= 4);
            var seen = new HashSet<Long>();
            for (int k = 0; k < nRects; k++) {
                int[] r = out[k];
                assertTrue(r[0] <= r[2] && r[1] <= r[3], "degenerate rect emitted");
                for (int x = r[0]; x <= r[2]; x++) {
                    for (int z = r[1]; z <= r[3]; z++) {
                        assertTrue(seen.add(PositionUtil.packPosition(x, z)),
                                "rects overlap at " + x + "," + z + " (trial " + trial + ")");
                    }
                }
            }
            for (int x = ax0; x <= ax1; x++) {
                for (int z = az0; z <= az1; z++) {
                    boolean inNear = x >= nx0 && x <= nx1 && z >= nz0 && z <= nz1;
                    assertEquals(!inNear, seen.contains(PositionUtil.packPosition(x, z)),
                            "coverage mismatch at " + x + "," + z + " (trial " + trial + ")");
                }
            }
        }
    }

    // ---- the chaos/orphan ports (plan §10 — 'the load-bearing pins') ----

    /** fireScan with queue-pressure inputs, for the chaos loops. */
    private static int fireScanP(RegionScanner s, int cx, int cz, int viewDistance,
                                 int queueSize, int queueHalt,
                                 ColumnStateMap columns, long[] pos, long[] ts) {
        for (int i = 0; i < LSSConstants.TICKS_PER_SECOND + 1; i++) {
            int n = s.maybeScan(cx, cz, viewDistance, queueSize, queueHalt, 0L, Long.MAX_VALUE,
                    -1, 1000, () -> 0, columns, pos, ts);
            if (n >= 0) return n;
        }
        throw new AssertionError("scan cadence never fired");
    }

    private static boolean allSatisfiedAround(ColumnStateMap columns, int cx, int cz,
                                              int vd, int lod) {
        for (int dx = -lod; dx <= lod; dx++) {
            for (int dz = -lod; dz <= lod; dz++) {
                if (dx == 0 && dz == 0) continue; // ring-0 parity: never declared
                if (SpiralScanner.isVanillaRendered(cx + dx, cz + dz, cx, cz, vd)) continue;
                if (columns.classify(PositionUtil.packPosition(cx + dx, cz + dz))
                        != ColumnStateMap.SATISFIED) {
                    return false;
                }
            }
        }
        return true;
    }

    @Test
    void anyChaosInterleavingLeavesNoPositionPermanentlyOrphaned() {
        // The region port of the legacy load-bearing pin, with budget-truncated
        // fires (tests-lens MAJOR-2: the differential's single-shot unbounded
        // compares cannot see prefix-advance failures under truncation). Lod 40 is
        // single-phase (phase 1) on the hybrid arm; the lod-96 seed crosses the
        // N=64 seam so truncation interleaves BOTH phases (§8 chaos requirement).
        // Alphabet: answers, retry marks, not-generated, up-to-date, LATE answers,
        // and silent supersession — no answer, ever; only re-declaration saves it.
        final int vd = 2;
        long[] seeds = {1L, 7L, 42L, 5L};
        int[] lods = {40, 40, 40, 96};
        for (int si = 0; si < seeds.length; si++) {
            long seed = seeds[si];
            int lod = lods[si];
            var rng = new java.util.Random(seed);
            var columns = new ColumnStateMap();
            long[] pos = buf(), ts = buf();
            var s = scanner(lod);
            record Scheduled(long pos, int dueCycle) {}
            var scheduled = new java.util.ArrayList<Scheduled>();
            var awaitingLate = new java.util.HashSet<Long>();
            int supersededCount = 0;

            for (int cycle = 0; cycle < 30; cycle++) {
                for (var iter = scheduled.iterator(); iter.hasNext(); ) {
                    var ev = iter.next();
                    if (ev.dueCycle() > cycle) continue;
                    iter.remove();
                    awaitingLate.remove(ev.pos());
                    columns.onReceived(ev.pos(), 1_000L + cycle);
                }
                int n = fireScanP(s, CX, CZ, vd, rng.nextInt(900), 1000, columns, pos, ts);
                for (int i = 0; i < n; i++) {
                    long pk = pos[i];
                    if (awaitingLate.contains(pk)) continue;
                    int roll = rng.nextInt(100);
                    if (roll < 30) columns.onReceived(pk, 1_000L + cycle);
                    else if (roll < 45) columns.markRetry(pk);
                    else if (roll < 60) columns.onNotGenerated(pk);
                    else if (roll < 70) columns.onUpToDate(pk);
                    else if (roll < 85) {
                        scheduled.add(new Scheduled(pk, cycle + 1 + rng.nextInt(3)));
                        awaitingLate.add(pk);
                    } else supersededCount++;
                }
            }
            assertTrue(supersededCount > 0,
                    "seed " + seed + ": the chaos never exercised a supersession");
            scheduled.clear();
            awaitingLate.clear(); // the booked late answers are dropped too

            boolean converged = false;
            for (int i = 0; i < 90 && !converged; i++) {
                int n = fireScanP(s, CX, CZ, vd, 0, 1000, columns, pos, ts);
                for (int j = 0; j < n; j++) {
                    columns.onReceived(pos[j], 5_000L + i);
                    columns.onUpToDate(pos[j]);
                }
                converged = n == 0 && s.getConfirmedRing() == lod + 1
                        && allSatisfiedAround(columns, CX, CZ, vd, lod);
            }
            assertTrue(converged, "seed " + seed + ": chaos interleaving permanently "
                    + "orphaned a position (confirmedRing=" + s.getConfirmedRing() + ")");
        }
    }

    @Test
    void movementChaosLeavesNoPositionPermanentlyOrphaned() {
        // The movement half of the orphan property on the stateless walk: random
        // crossings (the region arm has no prefix/crescent geometry to corrupt, but
        // the emit windows shift and the movement window gates cadence), dirty marks
        // via the production shape (markDirtyIfKnown + the no-op reopenRing), retries,
        // supersessions — then convergence at the final center.
        final int vd = 16;
        // lod 24 = pure phase 1; lod 96 = BOTH phases + the region-corner boundary
        // (the §8 chaos seed: the interleave must cross the N=64 seam).
        int[] lods = {24, 24, 24, 96};
        long[] seeds = {3L, 11L, 77L, 5L};
        for (int si = 0; si < seeds.length; si++) {
            long seed = seeds[si];
            int lod = lods[si];
        {
            var rng = new java.util.Random(seed);
            var columns = new ColumnStateMap();
            long[] pos = buf(), ts = buf();
            var s = scanner(lod);
            int cx = 0, cz = 0;

            for (int cycle = 0; cycle < 25; cycle++) {
                if (rng.nextInt(100) < 60) {
                    int d = 1 + rng.nextInt(6);
                    int dir = rng.nextBoolean() ? 1 : -1;
                    switch (rng.nextInt(3)) {
                        case 0 -> cx += dir * d;
                        case 1 -> cz += dir * d;
                        default -> { cx += dir * d; cz += (rng.nextBoolean() ? 1 : -1) * d; }
                    }
                    s.recenter(d);
                }
                int n = fireScanP(s, cx, cz, vd, 0, 1000, columns, pos, ts);
                for (int i = 0; i < n; i++) {
                    long pk = pos[i];
                    int roll = rng.nextInt(100);
                    if (roll < 40) { columns.onReceived(pk, 1_000L + cycle); columns.onUpToDate(pk); }
                    else if (roll < 50) columns.markRetry(pk);
                    else if (roll < 60) columns.onUpToDate(pk);
                    // else superseded: no answer, ever
                }
                if (rng.nextInt(100) < 40 && columns.receivedCount() > 0) {
                    int dx = rng.nextInt(2 * lod + 1) - lod, dz = rng.nextInt(2 * lod + 1) - lod;
                    long dirtyPos = PositionUtil.packPosition(cx + dx, cz + dz);
                    if (columns.markDirtyIfKnown(dirtyPos)) {
                        s.reopenRing(Math.max(Math.abs(dx), Math.abs(dz)), lod); // no-op arm
                    }
                }
            }

            boolean converged = false;
            for (int i = 0; i < 90 && !converged; i++) {
                int n = fireScanP(s, cx, cz, vd, 0, 1000, columns, pos, ts);
                for (int j = 0; j < n; j++) {
                    columns.onReceived(pos[j], 50_000L + i);
                    columns.onUpToDate(pos[j]);
                }
                converged = n == 0 && s.getConfirmedRing() == lod + 1;
            }
            assertTrue(converged, "seed " + seed + ": movement chaos never converged"
                    + " (confirmedRing=" + s.getConfirmedRing() + ")");
            assertTrue(allSatisfiedAround(columns, cx, cz, vd, lod),
                    "seed " + seed + ": a position near the final center was orphaned");
        }
        }
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
        int cost = s.predictedWalkCost();
        assertTrue(cost > 0 && cost < SpiralScanner.FAST_RESCAN_MAX_WALK_COST,
                "stationary again: the OBSERVE METER prices the last walk (got " + cost
                        + ") — cheap, fast-admissible, never a flat 0");
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
        int lod = 96; // > 64: the far phase produces real probe-skips (see the census test)
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
