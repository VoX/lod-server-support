package dev.vox.lss.networking.client;

import dev.vox.lss.common.PositionUtil;

/**
 * HYBRID want-set walk (hybrid-scan-plan.md §2, layered on the region round):
 * phase 1 walks Chebyshev rings r₀+1..{@link #HYBRID_NEAR_RADIUS} in LEGACY
 * ring-major order (concentric near fill — the §1 region-grid artifact dies by
 * the 3×3-containment proof), phase 2 the region spiral over the lod-clamped
 * RESIDUE {@code (region ∩ lod) \ near} (≤4 rectangles; empty residue = ignored
 * outright, so lod ≤ 64 degenerates to a pure stateless legacy-order walk).
 * Complete-prefix, hybrid form: at most ONE partial group across BOTH phases.
 * The pre-hybrid region-major walk below this paragraph remains the phase-2
 * description:
 *
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
 * {@link #predictedWalkCost} for the full derivation and the review-panel record in
 * plan §14): the inherited cost gate runs over an overridden cost — the MOVEMENT
 * WINDOW prices legacy's from-zero formula over the TRUNCATED FRONTIER
 * (4·s(s+1), s = scanRing when truncated else lod — preserving the pinned elytra
 * unlock below ring ~128 and the 1 Hz flight policy above it), and STATIONARY
 * prices the LAST walk's measured observe work (the region-probe floor + the
 * clamped emit passes) — dense fill and deep warm backfill stay far under the cap
 * at any frontier depth, while a mass dirty scatter across dozens of regions
 * (WorldEdit-scale broadcasts, revocation residue) prices past it and holds 1 Hz,
 * the §8 behavior row restored. The POST-pressure retry rung is dropped: retry
 * marks are ordinary needs here, declared and therefore covered by the outstanding
 * gate.
 *
 * <p>Main client thread only, like the base.
 */
class RegionScanner extends SpiralScanner {

    /** The hybrid near radius (hybrid-scan-plan.md §2.1/§10): rings ≤ N walk in
     *  LEGACY ring-major order (concentric near fill — the §1 artifact dies by the
     *  3×3-containment proof, N ≥ 63); regions beyond walk region-major over the
     *  lod-clamped RESIDUE. N = min of this and the lod distance; at lod ≤ 64 the
     *  whole walk degenerates to the stateless legacy order. */
    static final int HYBRID_NEAR_RADIUS = 64;
    int hybridNearRadius = HYBRID_NEAR_RADIUS;

    /** Regions the last walk actually emitted entries from (diag {@code region_span=};
     *  dense far fill: ≤2 by the complete-prefix + one-partial-tail invariant —
     *  legitimately wider in the lod-edge AND near-edge clipped slivers). */
    private int lastRegionSpan;
    /** Phase-1 rings that EMITTED (or observed needy work) last scan — diag
     *  {@code near_rings=} (§7: emitted rings, NOT walked — the never-skippable
     *  exclusion band walks forever and would make the "then ~0" live signature
     *  unsatisfiable). */
    private int lastNearRings;
    /** Phase-2 probe/emit-pass entries, SESSION-CUMULATIVE (test seam — a
     *  package-private field, no getter; reset with the session/dimension
     *  counters, never per walk — the census pin doubles it across two walks). */
    int phase2Probes;
    /** Walks that ENTERED phase 2, session-cumulative (test seam): the lod ≤ N
     *  degeneracy pin proves the phase never RAN — {@code phase2Probes} alone
     *  cannot (every in-near region residue is empty, so a wrongly-entered
     *  phase 2 would still probe nothing). */
    int phase2Rounds;
    /** Needs-free region skips, session (diag {@code region_skips=}). */
    private long regionSkips;
    /** Audit-rung heals, session (diag {@code audit_heals=} — expected 0; nonzero
     *  means a mutation path forgot recomputeNeeds, plan §2.2 v1.1 A-6). */
    private long auditHeals;
    // Audit round-robin cursor over the region spiral (main thread only).
    private int auditRing;
    private int auditIdx;
    /** Consecutive fast fires since the audit last ran — the rung must not starve
     *  under sustained 4 Hz (review fold: it used to run on periodic fires only,
     *  which a stationary deep backfill never produces). */
    private int fastFiresSinceAudit;
    /** Test seam: the differential suite disables the audit so a genuine needs-mask
     *  divergence in its shared fixture cannot be silently healed mid-compare. */
    boolean auditEnabled = true;
    /** The last walk's measured observe work (region-probe floor + clamped emit-pass
     *  areas) — the stationary fast-fire price (see {@link #predictedWalkCost}). */
    private int lastWalkObserveCost;

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
     * AS-BUILT deviation from plan §8's region-count rung, ADJUDICATED AND REPRICED by
     * the implementation review panel (plan §14). The §8 rung was refuted: a small LOD
     * disc inherently spans up to a 3×3 region block around a region corner, so an
     * active-region-count rung inverts the legacy policy at small lod (where legacy
     * admits freely) — and §2.3's mandate that the cost rung become constant-permissive
     * was unachievable by inheritance, because the region walk's v1.1
     * confirmed/scanRing semantics make the inherited formula price a dense
     * far-frontier fill at 256c+3968 (one-region span — refusal from frontier ring
     * ~241) to 520c+16640 (two-region span — refusal from ~95), killing the 4 Hz warm
     * backfill. As repriced:
     *
     * <p><b>Movement window</b> ({@code recenter} opens it, the base fire path closes
     * it on every fired walk): legacy's from-zero formula over the TRUNCATED FRONTIER —
     * {@code 4·s(s+1)} with {@code s = lastWalkTruncated ? scanRing : lod}. This is the
     * exact legacy branch (SpiralScanner's movement pricing), preserving the pinned
     * elytra unlock: a moving client fast-fires while the fill frontier is below ring
     * ~96-128 (region-major scanRing runs up to one region span past the ring-major
     * frontier, so the cliff lands slightly EARLIER — the conservative direction) and
     * rides 1 Hz above it. An untruncated walk prices the whole disc, so sustained
     * flight over warm terrain at lod ≥ 128 stays 1 Hz — the elytra-wall line.
     *
     * <p><b>Stationary</b>: the LAST walk's measured observe cost (region-probe floor
     * + clamped emit-pass areas, metered in {@link #scan}) — a MEMORY, deviating from
     * the legacy gate's prediction doctrine, which exists because a crossing
     * invalidates last-walk knowledge; here every crossing is priced by the window
     * branch instead, and stationary state evolves incrementally, so the last walk is
     * an honest predictor of the next. Dense fill (~2 emitting regions + the
     * never-skippable near-player/boundary floor) prices ~10-45k — under the 65,536
     * cap at every shipped lod, so 4 Hz warm backfill survives at ANY frontier depth.
     * A mass dirty scatter across dozens of regions prices past the cap and holds
     * 1 Hz — the §8 "sparse scatter → 1 Hz" row, restored (integration-review MAJOR:
     * each needy region costs an emit pass, so the walk is NOT free in that regime).
     */
    @Override
    int predictedWalkCost() {
        if (this.sessionConfig == null) return Integer.MAX_VALUE; // fail closed, like base
        if (this.recenteredSinceLastFire) {
            long s = this.lastWalkTruncated ? this.scanRing : getEffectiveLodDistance();
            long cost = 4L * s * (s + 1);
            return (int) Math.min(cost, Integer.MAX_VALUE);
        }
        return this.lastWalkObserveCost;
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
        this.lastNearRings = 0;
        this.phase2Probes = 0;
        this.phase2Rounds = 0;
        this.lastWalkObserveCost = 0;
        this.fastFiresSinceAudit = 0;
        this.regionSkips = 0;
        this.auditHeals = 0;
        this.auditRing = 0;
        this.auditIdx = 0;
    }

    @Override
    void resetScanCounter() {
        super.resetScanCounter(); // keeps the deliberate post-dimension 20-tick wait + disarm
        this.lastRegionSpan = 0;
        this.lastNearRings = 0;
        this.phase2Probes = 0;
        this.phase2Rounds = 0;
    }

    // ---- the walk (hybrid-scan-plan.md §2.1: phase 1 near rings, phase 2 far residue) ----

    /** Largest ring wholly inside the 1-buffered Euclidean exclusion (corner test:
     *  ring r is fully rendered iff 2·(r−1)² < vd²) — phase 1 starts at r₀+1, the
     *  first ring with an unrendered cell (final-review-verified; brute-pinned). */
    static int wholeExcludedRings(int viewDistance) {
        if (viewDistance <= 0) return 0;
        long vd2 = (long) viewDistance * viewDistance;
        int r = (int) (viewDistance / Math.sqrt(2.0)) + 1;
        while (r > 0 && 2L * (r - 1) * (r - 1) >= vd2) r--;
        while (2L * r * r < vd2) r++;
        return r;
    }

    /**
     * Residue decomposition (§2.1): {@code (clipped region rect) \ (near square)} as
     * ≤4 axis-aligned rectangles written into {@code out[i] = {x0, z0, x1, z1}}.
     * Returns the rectangle count — 0 means the region is wholly inside the near
     * square (phase 1 owns it: no probe, no pass, no counter).
     */
    static int residueRects(int ax0, int az0, int ax1, int az1,
                            int nx0, int nz0, int nx1, int nz1, int[][] out) {
        if (nx0 > ax1 || nx1 < ax0 || nz0 > az1 || nz1 < az0) {
            out[0][0] = ax0; out[0][1] = az0; out[0][2] = ax1; out[0][3] = az1;
            return 1; // disjoint: the whole clipped rect is residue
        }
        int n = 0;
        if (ax0 < nx0) { out[n][0] = ax0; out[n][1] = az0; out[n][2] = nx0 - 1; out[n][3] = az1; n++; }
        if (ax1 > nx1) { out[n][0] = nx1 + 1; out[n][1] = az0; out[n][2] = ax1; out[n][3] = az1; n++; }
        int mx0 = Math.max(ax0, nx0);
        int mx1 = Math.min(ax1, nx1);
        if (mx0 <= mx1) {
            if (az0 < nz0) { out[n][0] = mx0; out[n][1] = az0; out[n][2] = mx1; out[n][3] = nz0 - 1; n++; }
            if (az1 > nz1) { out[n][0] = mx0; out[n][1] = nz1 + 1; out[n][2] = mx1; out[n][3] = az1; n++; }
        }
        return n;
    }

    private final int[][] residueScratch = new int[4][4];

    @Override
    protected int scan(int playerCx, int playerCz, int viewDistance,
                       ColumnStateMap columns,
                       long[] posOut, long[] tsOut, int budget) {
        // Hoisted ONCE (§2.1 n11): getEffectiveLodDistance is a cached Voxy query —
        // a mid-scan shrink would double-emit the band (N_new, N_old].
        int lodDistance = getEffectiveLodDistance();
        int near = Math.min(this.hybridNearRadius, lodDistance);

        int count = 0;
        int minUnresolved = Integer.MAX_VALUE;
        int emittedSpan = 0;
        int maxEmittedRing = -1;
        boolean truncated = false;
        long observeCost = 0;
        int nearRings = 0;

        // ---- PHASE 1: near rings r₀+1..N, legacy ring-major order, stateless ----
        // Per ring: the leaf probe FIRST (gate-independent — enableQuadtreeScan
        // gates the LEGACY arm only); needy rings pay the 8r pass. The first
        // past-budget UNRESOLVED position breaks the whole walk (§2.3: truncated
        // ⟺ needy work provably remains — a per-position classify observation,
        // never a probe verdict). There is deliberately NO emit-free observation
        // sweep of the remaining rings: §3's O(8r·remaining) post-budget charge
        // does not exist as-built (plan §13), which is what keeps a cold-fill
        // phase-1 break inside the 4 Hz meter — do not "restore" it.
        // Ring 0 is never enumerated (r₀ ≥ 0).
        int r0 = wholeExcludedRings(viewDistance);
        boolean phase1Broke = false;
        phase1:
        for (int r = r0 + 1; r <= near; r++) {
            if (columns.ringNeedsFree(playerCx, playerCz, r)) {
                this.quadRingSkips++; // phase-1 skips feed the legacy counter (§7)
                observeCost += r + 4L; // ringNeedsFree's perimeter is ~r+4 lookups (§3: charge = actual)
                continue;
            }
            observeCost += 8L * r;
            boolean sawUnresolved = false;
            for (int i = 0; i < 8 * r; i++) {
                ringIndexToCoord(r, i, playerCx, playerCz, this.regionCoords);
                int cx = this.regionCoords[0];
                int cz = this.regionCoords[1];
                if (isVanillaRendered(cx, cz, playerCx, playerCz, viewDistance)) continue;
                long packed = PositionUtil.packPosition(cx, cz);
                long ts = columns.classify(packed);
                if (ts == ColumnStateMap.SATISFIED) continue;
                sawUnresolved = true;
                if (r < minUnresolved) minUnresolved = r;
                if (count < budget) {
                    posOut[count] = packed;
                    tsOut[count] = ts;
                    count++;
                    if (r > maxEmittedRing) maxEmittedRing = r;
                } else {
                    truncated = true; // needy work remains — and phase 2 must not run
                    phase1Broke = true;
                    nearRings++;
                    break phase1;
                }
            }
            if (sawUnresolved) nearRings++;
        }

        // ---- PHASE 2: far regions over the lod-clamped RESIDUE (§2.1) ----
        // A phase-1 budget break means phase 2 contributes nothing (the hybrid
        // complete-prefix form: at most ONE partial group across BOTH phases).
        if (!phase1Broke && near < lodDistance) {
            this.phase2Rounds++;
            int playerRx = playerCx >> 5;
            int playerRz = playerCz >> 5;
            int maxRegionRing = ((lodDistance + 31) >> 5) + 1;
            int nx0 = playerCx - near, nx1 = playerCx + near;
            int nz0 = playerCz - near, nz1 = playerCz + near;

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
                    // Clip to the lod square; fully outside skips without probing.
                    int ax0 = Math.max(cx0, playerCx - lodDistance);
                    int ax1 = Math.min(cx0 + 31, playerCx + lodDistance);
                    int az0 = Math.max(cz0, playerCz - lodDistance);
                    int az1 = Math.min(cz0 + 31, playerCz + lodDistance);
                    if (ax0 > ax1 || az0 > az1) continue;
                    int nRects = residueRects(ax0, az0, ax1, az1, nx0, nz0, nx1, nz1,
                            this.residueScratch);
                    if (nRects == 0) continue; // phase 1 owns it: nothing, not even a counter
                    this.phase2Probes++;
                    boolean free = true;
                    for (int k = 0; k < nRects && free; k++) {
                        int[] rect = this.residueScratch[k];
                        // Charge the rect's leaf-bbox (§3: charge = actual lookups —
                        // straddling rects legitimately re-visit shared leaves).
                        observeCost += (long) ((rect[2] >> 3) - (rect[0] >> 3) + 1)
                                * ((rect[3] >> 3) - (rect[1] >> 3) + 1);
                        free = columns.rectNeedsFree(rect[0], rect[1], rect[2], rect[3]);
                    }
                    if (free) {
                        this.regionSkips++;
                        continue;
                    }
                    // Emit pass over the residue rectangles ONLY — their bounds ARE
                    // the near+lod clamp (no per-cell near test, no double-observation
                    // of phase-1 territory). The dx==0&&dz==0 ring-0 belt is
                    // unreachable here (ring 0 sits inside the near square) — kept
                    // in spirit by the residue bounds themselves.
                    int n = 0;
                    int minRing = Integer.MAX_VALUE;
                    for (int k = 0; k < nRects; k++) {
                        int[] rect = this.residueScratch[k];
                        observeCost += (long) (rect[2] - rect[0] + 1) * (rect[3] - rect[1] + 1);
                        for (int cz = rect[1]; cz <= rect[3]; cz++) {
                            int dz = Math.abs(cz - playerCz);
                            for (int cx = rect[0]; cx <= rect[2]; cx++) {
                                int dx = Math.abs(cx - playerCx);
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
                    }
                    if (n == 0) continue; // complete under observation (excluded/satisfied)
                    if (count >= budget) {
                        // A needy region past a full budget. Pricing note (§13): this
                        // shape leaves maxEmittedRing ≤ N, so the MOVEMENT window
                        // prices the near frontier while the next walk re-sweeps the
                        // region spiral — accepted: the sweep is ~1 ms of leaf probes
                        // and the STATIONARY branch meters it honestly.
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
        }

        // Audit rung: EVERY fired walk, regardless of which phase the budget broke
        // in (§3 — the natural early-return would starve it during cold near fill),
        // one region per periodic fire — or per four consecutive fast fires.
        if (this.auditEnabled
                && (!wasLastScanFast() || ++this.fastFiresSinceAudit >= 4)) {
            this.fastFiresSinceAudit = 0;
            auditOneRegion(columns, playerCx >> 5, playerCz >> 5, playerCx, playerCz,
                    lodDistance, ((lodDistance + 31) >> 5) + 1);
        }

        // v1.1 confirmed semantics: min observed unresolved ring; lod+1 when
        // converged. Phase 1 observes rings ASCENDING, so the near zone's
        // contribution is exact (the region arm's truncation approximation now
        // applies only beyond N).
        this.confirmedRing = minUnresolved == Integer.MAX_VALUE
                ? lodDistance + 1 : minUnresolved;
        this.scanRing = maxEmittedRing >= 0 ? maxEmittedRing : this.confirmedRing;
        this.lastWalkTruncated = truncated;
        this.lastBudget = budget;
        this.lastQueued = count;
        this.lastRegionSpan = emittedSpan;
        this.lastNearRings = nearRings;
        this.lastWalkObserveCost = (int) Math.min(observeCost, Integer.MAX_VALUE);
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

    @Override
    int getNearRings() { return this.lastNearRings; }

}
