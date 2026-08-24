package dev.vox.lss.networking.client;

import dev.vox.lss.common.PositionUtil;

/**
 * Region-major want-set walk (docs/planning/region-scan-plan.md): a spiral of
 * 32×32-chunk REGIONS around the player's region, each region's still-wanted
 * positions emitted ring-ascending (a counting sort over the region's ≤31-ring
 * Chebyshev span), so the declared set is a COMPLETE PREFIX of the region order
 * plus at most one partial tail — the working-set collapse that fixes the far-radius
 * Xaero-bridge drop regime (plan §1/§6) and makes every downstream consumer
 * (Xaero regions, .mca files, the tscache's tiles, summary tiles) see one or two
 * active regions instead of ~95.
 *
 * <p>Extends {@link SpiralScanner} as the plan §2.4 "shared base" (implementer's
 * choice exercised as subclassing): ALL cadence/budget/latch machinery is inherited
 * verbatim — zero drift risk — while the walk ({@link #scan}), the two fast-fire
 * path rungs, and the invalidation surface are overridden. The inherited prefix/
 * reopen machinery is INERT by construction: {@link #reopenRing(int, int)} and
 * {@link #recenter(int)} are no-ops (every invalidation family already sets leaf
 * needs bits, and this walk consults everything each scan — statelessness is the
 * §2.2 design), so the reopened bitset stays empty and the legacy fields hold
 * their resets. The walk WRITES the inherited bookkeeping fields with the plan's
 * v1.1 semantics — {@code confirmedRing} = the minimum OBSERVED unresolved ring
 * (or lod+1 when converged; identically legacy's meaning, and what the soak law
 * {@code scan.confirmed > 24} reads), {@code scanRing} = max emitted ring,
 * {@code lastWalkTruncated} = the budget ended the walk — so every getter, the
 * governor's window-limited latch, diag, and the exporter work unchanged.
 *
 * <p>Fast-cadence policy (AS-BUILT, superseding plan §8's region-count rung — see
 * {@link #predictedWalkCost} for the full derivation): the inherited cost gate runs
 * over an overridden cost — the MOVEMENT WINDOW prices the whole disc with legacy's
 * from-zero formula (flight keeps the legacy 1 Hz policy above lod 127), stationary
 * prices 0 (the stateless walk genuinely is cheap at any frontier depth). The
 * POST-pressure retry rung is dropped: retry marks are ordinary needs here,
 * declared and therefore covered by the outstanding gate.
 *
 * <p>Main client thread only, like the base.
 */
class RegionScanner extends SpiralScanner {

    /** Regions the last walk actually emitted entries from (diag {@code region_span=};
     *  dense fill: ≤2 by the complete-prefix + one-partial-tail invariant). */
    private int lastRegionSpan;
    /** Needs-free region skips, session (diag {@code region_skips=}). */
    private long regionSkips;
    /** Audit-rung heals, session (diag {@code audit_heals=} — expected 0; nonzero
     *  means a mutation path forgot recomputeNeeds, plan §2.2 v1.1 A-6). */
    private long auditHeals;
    // Audit round-robin cursor over the region spiral (main thread only).
    private int auditRing;
    private int auditIdx;

    // Walk scratch (main client thread only; ~26 KB total).
    private final long[] scratchPos = new long[1024];
    private final long[] scratchTs = new long[1024];
    private final int[] scratchRing = new int[1024];
    private final int[] orderIdx = new int[1024];
    private final int[] ringCount = new int[33];
    private final int[] ringStart = new int[34];
    private final int[] placeCursor = new int[33];
    private final int[] regionCoords = new int[2];

    // ---- the inert legacy surface (plan §2.4 table) ----

    /** No-op: needs bits carry every invalidation; the walk sees them next scan. */
    @Override
    void reopenRing(int ring, int lod) {
        // deliberate no-op (region-scan-plan.md §2.2)
    }

    /** The walk is stateless over the live player position — recenter only opens the
     *  MOVEMENT WINDOW for the cadence gate (see {@link #predictedWalkCost}); no prefix
     *  state exists to shift. */
    @Override
    void recenter(int d) {
        this.recenteredSinceLastFire = true;
    }

    // ---- the cadence path rungs ----

    /**
     * AS-BUILT deviation from plan §8's region-count rung, forced by two facts the
     * review round did not surface: a small LOD disc INHERENTLY spans up to 4 regions
     * (the 2×2 around any player near a region corner), so an active-region-count rung
     * refuses fast fires FOREVER at lod ≤ 32 (the cadence suites caught it); and the
     * inherited legacy cost formula over the v1.1 confirmed/scanRing fields prices a
     * dense far-frontier fill at ~8·c·31 — refusing the 4 Hz warm backfill past ring
     * ~260, the feature's own headline case. The honest region translation of the
     * legacy gate's POLICY is therefore: in the MOVEMENT WINDOW (a crossing with no
     * fire since — {@code recenter} opens it, the base's fire path closes it) price
     * the whole disc exactly as legacy's from-zero formula does — flight keeps the
     * legacy 1 Hz policy above lod 127, the elytra-wall line — and STATIONARY price 0:
     * the stateless region walk genuinely costs O(regions×16 + emitted) at any
     * frontier depth, so the cost gate has nothing to refuse (legacy also admits
     * stationary deep fills — its span c..s is narrow there; this extends the same
     * admission to the movement-free deep-fill case the region walk makes cheap).
     * The 5-agent implementation review adjudicates this deviation; plan §14 records it.
     */
    @Override
    int predictedWalkCost() {
        if (this.sessionConfig == null) return Integer.MAX_VALUE; // fail closed, like base
        if (this.recenteredSinceLastFire) {
            long lod = getEffectiveLodDistance();
            long cost = 4L * lod * (lod + 1);
            return (int) Math.min(cost, Integer.MAX_VALUE);
        }
        return 0;
    }

    @Override
    protected boolean postPressureFastRefusal(int playerCx, int playerCz, int viewDistance,
                                              ColumnStateMap columns) {
        return false; // retry marks are ordinary declared needs on this path (plan §2.3)
    }

    // ---- resets ----

    @Override
    void reset() {
        super.reset();
        this.lastRegionSpan = 0;
        this.regionSkips = 0;
        this.auditHeals = 0;
        this.auditRing = 0;
        this.auditIdx = 0;
    }

    @Override
    void resetScanCounter() {
        super.resetScanCounter(); // keeps the deliberate post-dimension 20-tick wait + disarm
        this.lastRegionSpan = 0;
    }

    // ---- the walk ----

    @Override
    protected int scan(int playerCx, int playerCz, int viewDistance,
                       ColumnStateMap columns,
                       long[] posOut, long[] tsOut, int budget) {
        int lodDistance = getEffectiveLodDistance();
        int playerRx = playerCx >> 5;
        int playerRz = playerCz >> 5;
        int maxRegionRing = ((lodDistance + 31) >> 5) + 1;

        int count = 0;
        int minUnresolved = Integer.MAX_VALUE;
        int emittedSpan = 0;
        int maxEmittedRing = -1;
        boolean truncated = false;

        outer:
        for (int rr = 0; rr <= maxRegionRing; rr++) {
            int ringSize = rr == 0 ? 1 : 8 * rr;
            for (int i = 0; i < ringSize; i++) {
                if (rr == 0) {
                    this.regionCoords[0] = playerRx;
                    this.regionCoords[1] = playerRz;
                } else {
                    ringIndexToCoord(rr, i, playerRx, playerRz, this.regionCoords);
                }
                int rx = this.regionCoords[0];
                int rz = this.regionCoords[1];
                int cx0 = rx << 5;
                int cz0 = rz << 5;
                // Region fully outside the lod square: skip without probing.
                if (cx0 > playerCx + lodDistance || cx0 + 31 < playerCx - lodDistance
                        || cz0 > playerCz + lodDistance || cz0 + 31 < playerCz - lodDistance) {
                    continue;
                }
                if (columns.regionNeedsFree(rx, rz, playerCx, playerCz, lodDistance)) {
                    this.regionSkips++;
                    continue;
                }
                // Emit pass: observe every in-lod, non-excluded, unsatisfied position.
                // The per-position lod clamp is load-bearing (plan v1.1 A-7): boundary
                // regions over-cover, and their beyond-lod absent leaves must not emit.
                int n = 0;
                int minRing = Integer.MAX_VALUE;
                for (int lz = 0; lz < 32; lz++) {
                    int cz = cz0 + lz;
                    int dz = Math.abs(cz - playerCz);
                    if (dz > lodDistance) continue;
                    for (int lx = 0; lx < 32; lx++) {
                        int cx = cx0 + lx;
                        int dx = Math.abs(cx - playerCx);
                        if (dx > lodDistance) continue;
                        if (dx == 0 && dz == 0) continue; // legacy parity: ring 0 is
                        // structurally EMPTY in the ring enumeration (8·0 positions), so the
                        // legacy walk never declares the player's own chunk. Production-
                        // irrelevant (always vanilla-rendered at real view distances);
                        // matched for the differential pin and the annulus-count tests.
                        if (isVanillaRendered(cx, cz, playerCx, playerCz, viewDistance)) continue;
                        long packed = PositionUtil.packPosition(cx, cz);
                        long ts = columns.classify(packed);
                        if (ts == ColumnStateMap.SATISFIED) continue;
                        int ring = Math.max(dx, dz);
                        if (ring < minUnresolved) minUnresolved = ring;
                        if (ring < minRing) minRing = ring;
                        this.scratchPos[n] = packed;
                        this.scratchTs[n] = ts;
                        this.scratchRing[n] = ring;
                        n++;
                    }
                }
                if (n == 0) continue; // complete under observation (excluded/satisfied)
                if (count >= budget) {
                    // A needy region past a full budget: unemitted work remains.
                    truncated = true;
                    break outer;
                }
                emittedSpan++;
                // Counting sort by ring (Chebyshev span across a 32×32 region ≤ 31 —
                // 1-Lipschitz; the 33rd bucket is belt).
                java.util.Arrays.fill(this.ringCount, 0);
                for (int k = 0; k < n; k++) {
                    this.ringCount[Math.min(this.scratchRing[k] - minRing, 32)]++;
                }
                this.ringStart[0] = 0;
                for (int b = 0; b < 33; b++) {
                    this.ringStart[b + 1] = this.ringStart[b] + this.ringCount[b];
                }
                java.util.Arrays.fill(this.placeCursor, 0);
                for (int k = 0; k < n; k++) {
                    int b = Math.min(this.scratchRing[k] - minRing, 32);
                    this.orderIdx[this.ringStart[b] + this.placeCursor[b]++] = k;
                }
                for (int o = 0; o < n; o++) {
                    if (count >= budget) {
                        truncated = true; // entries of THIS region remain
                        break outer;
                    }
                    int k = this.orderIdx[o];
                    posOut[count] = this.scratchPos[k];
                    tsOut[count] = this.scratchTs[k];
                    count++;
                    if (this.scratchRing[k] > maxEmittedRing) {
                        maxEmittedRing = this.scratchRing[k];
                    }
                }
            }
        }

        // Audit rung (plan §2.2 v1.1 A-6): one region per PERIODIC fire, round-robin.
        if (!wasLastScanFast()) {
            auditOneRegion(columns, playerRx, playerRz, playerCx, playerCz,
                    lodDistance, maxRegionRing);
        }

        // v1.1 confirmed semantics: min observed unresolved ring; lod+1 when converged
        // (matches legacy's confirmedRing = lod+1 on a converged disc — the
        // fresh-backfill soak law reads 25 > 24 at lod 24). Approximate only under
        // truncation (unwalked farther regions), exact at convergence.
        this.confirmedRing = minUnresolved == Integer.MAX_VALUE
                ? lodDistance + 1 : minUnresolved;
        this.scanRing = maxEmittedRing >= 0 ? maxEmittedRing : this.confirmedRing;
        this.lastWalkTruncated = truncated;
        this.lastBudget = budget;
        this.lastQueued = count;
        this.lastRegionSpan = emittedSpan;
        return count;
    }

    /** Advance the audit cursor one region along the spiral and re-derive its leaves'
     *  needs masks; a changed mask is a healed divergence (counted — expected 0). */
    private void auditOneRegion(ColumnStateMap columns, int playerRx, int playerRz,
                                int playerCx, int playerCz, int lodDistance,
                                int maxRegionRing) {
        int ringSize = this.auditRing == 0 ? 1 : 8 * this.auditRing;
        if (this.auditIdx >= ringSize) {
            this.auditIdx = 0;
            this.auditRing = this.auditRing >= maxRegionRing ? 0 : this.auditRing + 1;
            ringSize = this.auditRing == 0 ? 1 : 8 * this.auditRing;
        }
        int rx;
        int rz;
        if (this.auditRing == 0) {
            rx = playerRx;
            rz = playerRz;
        } else {
            ringIndexToCoord(this.auditRing, this.auditIdx, playerRx, playerRz, this.regionCoords);
            rx = this.regionCoords[0];
            rz = this.regionCoords[1];
        }
        this.auditIdx++;
        int cx0 = rx << 5;
        int cz0 = rz << 5;
        if (cx0 > playerCx + lodDistance || cx0 + 31 < playerCx - lodDistance
                || cz0 > playerCz + lodDistance || cz0 + 31 < playerCz - lodDistance) {
            return; // outside the disc — advance only
        }
        int healed = columns.auditRegionNeeds(rx, rz);
        if (healed > 0) this.auditHeals += healed;
    }

    // ---- diag getters (base defaults are 0 on the legacy path) ----

    @Override
    int getRegionSpan() { return this.lastRegionSpan; }

    @Override
    long getRegionSkips() { return this.regionSkips; }

    @Override
    long getAuditHeals() { return this.auditHeals; }

}
