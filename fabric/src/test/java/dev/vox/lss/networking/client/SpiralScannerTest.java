package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.compat.ModCompat;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.config.VoxyConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct coverage of the scan policy rewritten this release: Chebyshev exclusion (matching
 * vanilla's loaded-chunk square), budgeted ring walking, gen-cap skips, and — regression for
 * a release-review finding — contiguous-prefix ring confirmation: a satisfied OUTER ring
 * must never confirm past an unsatisfied INNER ring, or a stationary player gets a
 * permanent LOD hole.
 */
class SpiralScannerTest {

    private static final int CX = 0;
    private static final int CZ = 0;

    private static SpiralScanner scanner(int lodDistance) {
        var s = new SpiralScanner();
        s.setConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true,
                lodDistance, true));
        return s;
    }

    /**
     * Test sink for the want-set the scanner writes. The scanner now writes straight into the
     * caller's send buffers (LodRequestManager's), so tests own the arrays.
     */
    static final class Sink {
        final long[] pos = new long[LSSConstants.MAX_BATCH_CHUNK_REQUESTS];
        final long[] ts = new long[LSSConstants.MAX_BATCH_CHUNK_REQUESTS];
        /** Want-set size of the last fired scan (fireScan records it). */
        int count;

        List<Long> positions() {
            return positions(this.count);
        }

        List<Long> positions(int n) {
            var out = new ArrayList<Long>();
            for (int i = 0; i < n; i++) out.add(this.pos[i]);
            return out;
        }

        /** The old RequestQueue could hold an undrained remainder that survived across ticks; a
         *  want-set cannot — each fired scan writes the whole set and the manager ships it in the
         *  same tick. These two model the dimension-change drop of a not-yet-sent set. */
        void clear() { this.count = 0; }

        boolean hasNext() { return this.count > 0; }
    }

    /** Drive maybeScan until the 20-tick cadence fires; returns the want-set size. */
    private static int fireScan(SpiralScanner s, int viewDistance, ColumnStateMap columns,
                                Sink queue) {
        return fireScan(s, viewDistance, 0, 1000, 0, columns, queue);
    }

    /** fireScan with explicit budget-scale inputs (column queue fill, missing vanilla chunks). */
    private static int fireScan(SpiralScanner s, int viewDistance, int columnQueueSize,
                                int columnQueueHaltThreshold, int missingVanilla,
                                ColumnStateMap columns, Sink queue) {
        for (int i = 0; i < LSSConstants.TICKS_PER_SECOND + 1; i++) {
            int n = s.maybeScan(CX, CZ, viewDistance, columnQueueSize, columnQueueHaltThreshold,
                    () -> missingVanilla, columns, queue.pos, queue.ts);
            if (n >= 0) { queue.count = Math.max(n, 0); return n; }
        }
        throw new AssertionError("scan cadence never fired");
    }

    /**
     * fireScan with full control of center and budget-scale inputs. The in-flight predicate is
     * gone: re-declaration is load-bearing, so the scanner no longer suppresses awaited positions.
     */
    private static int fireScanFull(SpiralScanner s, int cx, int cz, int viewDistance,
                                    int columnQueueSize, int columnQueueHaltThreshold, int missingVanilla,
                                    ColumnStateMap columns, Sink queue) {
        for (int i = 0; i < LSSConstants.TICKS_PER_SECOND + 1; i++) {
            int n = s.maybeScan(cx, cz, viewDistance, columnQueueSize, columnQueueHaltThreshold,
                    () -> missingVanilla, columns, queue.pos, queue.ts);
            if (n >= 0) { queue.count = Math.max(n, 0); return n; }
        }
        throw new AssertionError("scan cadence never fired");
    }

    /** Seed every position in rings rFrom..rTo around (CX, CZ) as received + validated. */
    private static void seedSatisfied(ColumnStateMap columns, int rFrom, int rTo) {
        int[] c = new int[2];
        for (int r = rFrom; r <= rTo; r++) {
            for (int i = 0; i < 8 * r; i++) {
                SpiralScanner.ringIndexToCoord(r, i, CX, CZ, c);
                long packed = PositionUtil.packPosition(c[0], c[1]);
                columns.onReceived(packed, 1000L);
                columns.onUpToDate(packed);
            }
        }
    }

    private static long ringPos(int r, int i) {
        int[] c = new int[2];
        SpiralScanner.ringIndexToCoord(r, i, CX, CZ, c);
        return PositionUtil.packPosition(c[0], c[1]);
    }

    private static boolean allSatisfied(ColumnStateMap columns, int rFrom, int rTo) {
        int[] c = new int[2];
        for (int r = rFrom; r <= rTo; r++) {
            for (int i = 0; i < 8 * r; i++) {
                SpiralScanner.ringIndexToCoord(r, i, CX, CZ, c);
                if (columns.classify(PositionUtil.packPosition(c[0], c[1])) != ColumnStateMap.SATISFIED) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Flips the private ModCompat gate the scanner's voxy-distance query checks first. */
    private static void setVoxyLoaded(boolean value) throws Exception {
        var field = ModCompat.class.getDeclaredField("voxyLoaded");
        field.setAccessible(true);
        field.setBoolean(null, value);
    }

    /**
     * Drives a full invocation-based staleness window (20 calls) so the returned value
     * reflects the live VoxyConfig regardless of the counter's current phase.
     */
    private static int refreshedEffectiveDistance(SpiralScanner s) {
        int last = -1;
        for (int i = 0; i < LSSConstants.TICKS_PER_SECOND; i++) {
            last = s.getEffectiveLodDistance();
        }
        return last;
    }

    // ─── Render-square corner exclusion — full history of this bug (read before editing the
    //     SpiralScanner.scan() exclusion). LOD must cover EXACTLY what vanilla does not render;
    //     the trap is that vanilla's render boundary is a rounded disc, not a square. ───
    //
    //  1. Un-buffered per-position Euclidean exclusion (`dx^2+dz^2 <= R^2`, with a `2*r*r <= R^2`
    //     whole-ring fast-path, pre-683f67f): an un-buffered circle excludes LESS near the axes than
    //     vanilla's 1-chunk-buffered view, so axis-edge chunks within vanilla's view (loaded and
    //     re-saving inhabitedTime every ~10s) stayed LOD-eligible → a permanent re-request/re-serve
    //     LOOP on them.
    //  2. Chebyshev SQUARE exclusion (`max(|dx|,|dz|) <= R`, 683f67f): killed the loop by excluding
    //     the whole square — but vanilla's view is a rounded disc, so the square's 4 corners (which
    //     vanilla never renders) were now excluded from LOD too → 4 blank corner chunks on a
    //     stationary join (they filled once the player moved and the square shifted off them). Only
    //     visible at vd >= 4; at vd <= 3 the 1-chunk buffer makes the disc subsume the square.
    //  3. hasChunk coverage-aware exclusion (first attempt here): WRONG signal — the client STORES
    //     chunks out to renderDistance+3 (ClientChunkCache.calculateStorageRange), so the corners are
    //     received (hasChunk=true) but unrendered; the exclusion skipped them and they stayed blank.
    //  4. Buffered-Euclidean exclusion (current): replicate vanilla's OWN view test verbatim —
    //     ChunkTrackingView.isInViewDistance = `max(0,|dx|-1)^2 + max(0,|dz|-1)^2 < R^2`. LOD now
    //     covers exactly the complement (corners + beyond): no gap, no edge over-request. The step-1
    //     loop does NOT return — DirtyContentFilter (also 683f67f) independently suppresses the
    //     metadata-only re-saves, so a served corner never re-loops (see DirtyContentFilterTest
    //     #metadataOnlyResaveOfAServedCornerIsSuppressed_noReloadLoop).
    //
    // The four tests below pin all four lessons: small-vd subsumption, corners requested (step-2
    // regression), the exact formula incl. edges-excluded (step-1 / over-request regression).

    @Test
    void smallViewDistanceRoundedViewSubsumesTheWholeSquare_noReloadLoop() {
        // Vanilla's view (ChunkTrackingView.isInViewDistance) is a 1-chunk-buffered Euclidean
        // radius; at small viewDistance it subsumes the WHOLE Chebyshev square — at vd=2 even the
        // corner (2,2) is buffered-distance 1^2+1^2=2 < 2^2=4 — so the scanner requests ONLY the LOD
        // annulus beyond it, never an in-view chunk. Client side of the reload guard: in-view chunks
        // (which vanilla re-saves) are never LOD-requested, so they cannot drive a re-request loop.
        // (Server side — suppressing their metadata-only re-saves — is in DirtyContentFilterTest.)
        var s = scanner(4);
        var queue = new Sink();
        int queued = fireScan(s, 2, new ColumnStateMap(), queue);

        assertTrue(queued > 0);
        for (long packed : queue.positions(queued)) {
            int cheb = Math.max(Math.abs(PositionUtil.unpackX(packed) - CX),
                    Math.abs(PositionUtil.unpackZ(packed) - CZ));
            assertTrue(cheb > 2 && cheb <= 4,
                    "an in-view chunk must never be requested; cheb=" + cheb + " violates exclusion(2)/lod(4)");
        }
        assertEquals(8 * 3 + 8 * 4, queued); // full annulus: rings 3 and 4
    }

    @Test
    void renderSquareCornersBeyondVanillasRoundedViewAreRequested() {
        // THE FIX. Once viewDistance >= 4 the render SQUARE's corners fall OUTSIDE vanilla's
        // 1-chunk-buffered Euclidean view, so vanilla never renders them and they must get LOD.
        // At vd=4: corner (4,4) -> buffered 3^2+3^2=18 >= 16 (requested); axis edge (4,0) -> 3^2=9
        // < 16 (in view, excluded); (4,3) -> 9+4=13 < 16 (in view, excluded). The old Chebyshev
        // exclusion (max(|dx|,|dz|) <= vd) left these corners blank until the player moved.
        var s = scanner(6);
        var queue = new Sink();
        int queued = fireScan(s, 4, new ColumnStateMap(), queue);
        var drained = new java.util.HashSet<>(queue.positions(queued));
        assertTrue(queued > 0);

        for (int sx : new int[]{-4, 4}) {
            for (int sz : new int[]{-4, 4}) {
                assertTrue(drained.contains(PositionUtil.packPosition(CX + sx, CZ + sz)),
                        "render-square corner (" + sx + "," + sz + ") is outside vanilla's rounded view and must be LOD-requested");
            }
        }
        assertFalse(drained.contains(PositionUtil.packPosition(CX + 4, CZ)),
                "axis edge (4,0) is within vanilla's rounded view and must NOT be requested");
        assertFalse(drained.contains(PositionUtil.packPosition(CX + 4, CZ + 3)),
                "(4,3) is within vanilla's rounded view (9+4=13 < 16) and must NOT be requested");
    }

    @Test
    void exclusionReplicatesVanillaBufferedEuclideanViewExactly() {
        // Strongest guard against geometry drift: for EVERY chunk in the lod square, LOD must
        // request it IFF vanilla does NOT consider it in view — the verbatim ChunkTrackingView
        // formula `max(0,|dx|-1)^2 + max(0,|dz|-1)^2 < vd^2`. Fails if the exclusion ever reverts to
        // a square (corners read in-view here → blank-corner gap) or drops the 1-chunk buffer (the
        // boundary ring shifts → edge over-request, reviving the step-1 re-save loop). vd well below
        // lod so the whole rounded boundary lies inside the scanned square.
        int vd = 8, lod = 12;
        var s = scanner(lod); // the 800 budget dwarfs this disc — nothing is dropped for capacity
        var queue = new Sink();
        int queued = fireScan(s, vd, new ColumnStateMap(), queue);
        var requested = new java.util.HashSet<>(queue.positions(queued));
        long vd2 = (long) vd * vd;

        int mismatches = 0;
        for (int dx = -lod; dx <= lod; dx++) {
            for (int dz = -lod; dz <= lod; dz++) {
                int adx = Math.max(0, Math.abs(dx) - 1), adz = Math.max(0, Math.abs(dz) - 1);
                boolean inView = (long) adx * adx + (long) adz * adz < vd2;
                boolean req = requested.contains(PositionUtil.packPosition(CX + dx, CZ + dz));
                // out-of-view ⇒ requested, in-view ⇒ excluded; the player's own (0,0) is in-view and
                // never emitted by the ring walk (both false), which is a match (inView != req).
                if (inView == req) mismatches++;
            }
        }
        assertEquals(0, mismatches,
                "every chunk must be LOD-requested IFF it is OUTSIDE vanilla's buffered-Euclidean view");
        assertTrue(queued > 0, "the corner + annulus complement is non-empty");
    }

    @Test
    void confirmationAdvancesThroughAPartiallyExcludedRingOnceCornersAreServed() {
        // At vd=4 the buffered-Euclidean boundary STRADDLES ring 4 (the 4 corners are out of view,
        // the edges in), so ring 4 is the one place a single ring is partially excluded. The
        // load-bearing contract: an in-view EDGE skips without breaking ring confirmation, while an
        // out-of-view CORNER blocks it — so the ring confirms only once the corners are SERVED, and
        // the scan then settles (no spin re-walking the in-view edges).
        int vd = 4, lod = 5;
        var s = scanner(lod);
        var columns = new ColumnStateMap();
        var queue = new Sink();

        int queued = fireScan(s, vd, columns, queue);
        var requested = queue.positions(queued);
        assertTrue(queued > 0);
        assertEquals(4, s.getConfirmedRing(),
                "confirmation holds at ring 4 while its out-of-view corners are unserved");

        for (long packed : requested) { // serve + validate every out-of-view position
            columns.onReceived(packed, 1000L);
            columns.onUpToDate(packed);
        }

        assertEquals(0, fireScan(s, vd, columns, queue),
                "with every out-of-view chunk served, the scan settles — no spin on the in-view edges");
        assertEquals(lod + 1, s.getConfirmedRing(),
                "confirmation now advances THROUGH the partially-excluded ring to lodDistance+1");
    }

    @Test
    void satisfiedOuterRingMustNotConfirmPastUnsatisfiedInnerRing() {
        // Queue pressure shrinks the budget to 4 (800 * (1 - 995/1000), rounded, floor 1):
        // ring 3 (24 unknown positions) declares only 4 this scan — unsatisfied; rings 4-5
        // are fully satisfied. Confirmation must hold at ring 3 rather than jumping to 6
        // and orphaning ring 3's remaining 20 positions.
        var columns = new ColumnStateMap();
        int[] c = new int[2];
        for (int r = 4; r <= 5; r++) {
            for (int i = 0; i < 8 * r; i++) {
                SpiralScanner.ringIndexToCoord(r, i, CX, CZ, c);
                long packed = PositionUtil.packPosition(c[0], c[1]);
                columns.onReceived(packed, 1000L);       // satisfied
                columns.onUpToDate(packed);
            }
        }

        var s = scanner(5);
        var queue = new Sink();
        int queued = fireScan(s, 2, 995, 1000, 0, columns, queue);
        assertEquals(4, queued, "queue pressure bounds the scan to 4 declared positions");
        assertTrue(s.getConfirmedRing() <= 3,
                "confirmed ring " + s.getConfirmedRing()
                        + " jumped past unsatisfied ring 3 — permanent LOD hole");
    }

    @Test
    void fullySatisfiedDiscConfirmsToLodDistancePlusOne() {
        var columns = new ColumnStateMap();
        int[] c = new int[2];
        for (int r = 3; r <= 4; r++) {
            for (int i = 0; i < 8 * r; i++) {
                SpiralScanner.ringIndexToCoord(r, i, CX, CZ, c);
                long packed = PositionUtil.packPosition(c[0], c[1]);
                columns.onReceived(packed, 1000L);
                columns.onUpToDate(packed);
            }
        }
        var s = scanner(4);
        var queue = new Sink();
        assertEquals(0, fireScan(s, 2, columns, queue));
        assertEquals(5, s.getConfirmedRing(), "fully satisfied disc confirms past lodDistance");
    }

    @Test
    void budgetBoundsQueuedPositions() {
        // The constant want-set budget (800) with a larger annulus (rings 3..16 = 1064)
        var s = scanner(16);
        var queue = new Sink();
        assertEquals(LSSConstants.WANT_SET_BUDGET, fireScan(s, 2, new ColumnStateMap(), queue));
    }

    @Test
    void retryMarkInsideConfirmedDiscForcesRescanFromRingZero() {
        var columns = new ColumnStateMap();
        int[] c = new int[2];
        for (int r = 3; r <= 4; r++) {
            for (int i = 0; i < 8 * r; i++) {
                SpiralScanner.ringIndexToCoord(r, i, CX, CZ, c);
                long packed = PositionUtil.packPosition(c[0], c[1]);
                columns.onReceived(packed, 1000L);
                columns.onUpToDate(packed);
            }
        }
        var s = scanner(4);
        var queue = new Sink();
        assertEquals(0, fireScan(s, 2, columns, queue));
        assertEquals(5, s.getConfirmedRing(), "precondition: disc confirmed past lodDistance");

        // An ingest failure (the consumer rejected the column) retry-marks a ring-3 position.
        // (Pre-want-set this same rung was driven by a rate-limited bounce; the bounce is gone,
        // the retry mark and its invariant are not.) The disc is already confirmed past it, so
        // the next scan must restart from ring 0 or the retry would sit inside the skipped
        // prefix and never be re-declared.
        SpiralScanner.ringIndexToCoord(3, 0, CX, CZ, c);
        long retried = PositionUtil.packPosition(c[0], c[1]);
        columns.onIngestFailed(retried);

        assertEquals(1, fireScan(s, 2, columns, queue),
                "scan after a retry mark must re-walk the confirmed disc and re-declare the retry");
        assertEquals(List.of(retried), queue.positions());
        assertEquals(3, s.getConfirmedRing(), "confirmation holds at the unsatisfied retry ring");
    }

    @Test
    void queuePressureShrinksBudgetLinearlyWithFloorOne() {
        // base budget = WANT_SET_BUDGET (800); rings 3..16 hold 1064 candidates, above it
        var s = scanner(16);
        var queue = new Sink();
        assertEquals(600, fireScan(s, 2, 250, 1000, 0, new ColumnStateMap(), queue),
                "column queue at 25% of halt threshold scales the budget linearly to 600");

        // At the halt threshold the linear scale reaches 0 but the budget floors at 1
        s = scanner(16);
        queue = new Sink();
        assertEquals(1, fireScan(s, 2, 1000, 1000, 0, new ColumnStateMap(), queue),
                "queue pressure floors the budget at 1, never 0");
    }

    @Test
    void missingVanillaShrinksBudgetQuadraticallyToZero() {
        // base = 800; viewDistance 2 → exclusion area 25; 15 missing → fraction 0.6,
        // quadratic scale 1 - 0.36 = 0.64 → 512 (a linear scale would give 320)
        var s = scanner(16);
        var queue = new Sink();
        assertEquals(512, fireScan(s, 2, 0, 1000, 15, new ColumnStateMap(), queue),
                "vanilla-load scale is quadratic in the missing fraction");

        // All 25 exclusion chunks missing → scale 0 → no walk at all (no floor on this path).
        // A budget-0 cadence firing returns -1 forever, so it cannot be observed through fireScan
        // ("loop until >= 0"): normalize the cadence with a real scan, then step exactly one window.
        s = scanner(16);
        queue = new Sink();
        var columns = new ColumnStateMap();
        assertEquals(800, fireScan(s, 2, columns, queue), "premise: a normal scan zeroes the counter");
        int last = 0;
        for (int i = 0; i < LSSConstants.TICKS_PER_SECOND; i++) {
            last = s.maybeScan(CX, CZ, 2, 0, 1000, () -> 25, columns, queue.pos, queue.ts);
        }
        assertEquals(-1, last,
                "fully missing vanilla disc zeroes the budget: NO walk happened (-1), which is"
                        + " distinct from a walk that found nothing (0) — only the latter replaces"
                        + " the awaiting set, and only the latter is convergence");
    }

    @Test
    void effectiveLodDistanceIsMinOfServerAndClientOverride() {
        int saved = LSSClientConfig.CONFIG.lodDistanceChunks;
        try {
            var s = scanner(10);
            LSSClientConfig.CONFIG.lodDistanceChunks = 0; // 0 = override disabled, server wins
            assertEquals(10, s.getEffectiveLodDistance());
            LSSClientConfig.CONFIG.lodDistanceChunks = 6; // client below server clamps down
            assertEquals(6, s.getEffectiveLodDistance());
            LSSClientConfig.CONFIG.lodDistanceChunks = 15; // client above server has no effect
            assertEquals(10, s.getEffectiveLodDistance());
        } finally {
            LSSClientConfig.CONFIG.lodDistanceChunks = saved;
        }
    }

    @Test
    void pruneDistanceBuffersTheEffectiveLodDistance() {
        int saved = LSSClientConfig.CONFIG.lodDistanceChunks;
        try {
            LSSClientConfig.CONFIG.lodDistanceChunks = 6;
            var s = scanner(10);
            // Buffer applies to the client-clamped effective distance (6), not the server's 10
            assertEquals(6 + LSSConstants.LOD_DISTANCE_BUFFER, s.getPruneDistance());
        } finally {
            LSSClientConfig.CONFIG.lodDistanceChunks = saved;
        }
    }

    @Test
    void ringIndexToCoordCoversEachRingExactlyOnce() {
        int[] c = new int[2];
        for (int r = 1; r <= 5; r++) {
            var seen = new java.util.HashSet<Long>();
            for (int i = 0; i < 8 * r; i++) {
                SpiralScanner.ringIndexToCoord(r, i, 7, -3, c);
                assertEquals(r, Math.max(Math.abs(c[0] - 7), Math.abs(c[1] + 3)),
                        "ring " + r + " index " + i + " not at Chebyshev distance r");
                assertTrue(seen.add(PositionUtil.packPosition(c[0], c[1])),
                        "duplicate position in ring " + r);
            }
            assertEquals(8 * r, seen.size());
        }
    }

    // ---- cadence priming (CL-001) ----

    @Test
    void firstMaybeScanAfterResetFiresImmediately() {
        var s = scanner(4);
        var queue = new Sink();

        // A fresh scanner is primed: the very FIRST cadence call must fire (join burst),
        // not the 20th — the fireScan helper used elsewhere cannot tell those apart.
        int first = s.maybeScan(CX, CZ, 2, 0, 1000, () -> 0, new ColumnStateMap(), queue.pos, queue.ts);
        assertEquals(8 * 3 + 8 * 4, first, "first maybeScan on a fresh scanner must fire and queue the annulus");

        // reset() (new session / flushCache) re-primes: again a single call fires.
        s.reset();
        int afterReset = s.maybeScan(CX, CZ, 2, 0, 1000, () -> 0, new ColumnStateMap(), queue.pos, queue.ts);
        assertEquals(8 * 3 + 8 * 4, afterReset, "first maybeScan after reset() must fire immediately");
    }

    // ---- zero-budget remainder preservation (CL-005) — DELETED with the drip-feed ----
    //
    // zeroBudgetScanPreservesCommittedRemainder pinned that a budget-0 scan must not commit(0)
    // over a RequestQueue's undrained remainder. Under want-set semantics there is no remainder:
    // every fired scan writes the COMPLETE want-set and the manager ships it in the same tick,
    // so there is nothing a later scan could wipe. The surviving half of the invariant — a
    // budget-0 tick must not be mistaken for convergence — moved to the -1 return contract
    // (missingVanillaShrinksBudgetQuadraticallyToZero above) and to the manager's
    // noWalkTickNeitherSendsNorReplacesTheAwaitingSet.

    // ---- degenerate exclusion coverage (CL-007) ----

    @Test
    void viewDistanceCoveringLodDistanceConfirmsWithoutRequests() {
        // vd comfortably exceeding lod: vanilla's rounded (buffered-Euclidean) view subsumes the
        // WHOLE lod disc — including its corners — so nothing is requested and it confirms to lod+1.
        // (NB at vd == lod the disc leaves the lod square's corners OUTSIDE the view; those are the
        // corner-fix annulus, pinned by renderSquareCornersBeyondVanillasRoundedViewAreRequested.
        // Here vd=5 > lod=4 so the corner (4,4) at buffered 3^2+3^2=18 < 5^2=25 is in view.)
        var s = scanner(4);
        var queue = new Sink();
        assertEquals(0, fireScan(s, 5, new ColumnStateMap(), queue), "vanilla's rounded view covers the whole disc");
        assertEquals(5, s.getConfirmedRing(), "exclusion-skipped disc confirms to lodDistance+1");
        assertEquals(0, fireScan(s, 5, new ColumnStateMap(), queue), "no spin: stays settled");
        assertEquals(5, s.getConfirmedRing());

        // vd > lod: confirmation still caps at lod+1, never tracks the overshooting exclusion.
        var s2 = scanner(4);
        assertEquals(0, fireScan(s2, 6, new ColumnStateMap(), queue));
        assertEquals(5, s2.getConfirmedRing(), "confirmation caps at lodDistance+1 when vd overshoots");
    }

    // ---- reset matrix: movement / dimension change / disconnect (CL-016) ----

    @Test
    void movementRecenterZeroesConfirmedRingKeepsCadenceAndMarks() {
        var columns = new ColumnStateMap();
        seedSatisfied(columns, 3, 4);
        var s = scanner(4);
        var queue = new Sink();
        assertEquals(0, fireScan(s, 2, columns, queue));
        assertEquals(5, s.getConfirmedRing(), "precondition: disc confirmed");
        long retried = ringPos(4, 0);
        columns.markRetry(retried);

        // Burn 19 of the 20 cadence ticks, then take the movement path (LodRequestManager
        // .tick: prune + recenter). The 20th tick must STILL fire: recenter leaves the
        // cadence alone — the old debounce restarted it here, which starved scanning (and
        // re-declaration with it) for as long as crossings outpaced the window.
        for (int i = 0; i < LSSConstants.TICKS_PER_SECOND - 1; i++) {
            assertEquals(-1, s.maybeScan(CX, CZ, 2, 0, 1000, () -> 0, columns, queue.pos, queue.ts));
        }
        s.recenter();

        assertEquals(0, s.getConfirmedRing(),
                "movement must zero ring confirmation (the confirmed prefix belonged to the old center)");
        assertTrue(columns.hasRetries(), "movement preserves in-range retry marks");
        queue.clear();
        int n = s.maybeScan(CX, CZ, 2, 0, 1000, () -> 0, columns, queue.pos, queue.ts);
        assertEquals(1, n, "the in-progress cadence window completes ON SCHEDULE through a"
                + " recenter and the scan re-walks the disc, declaring the retry");
        assertEquals(List.of(retried), queue.positions(n));
    }

    @Test
    void dimensionChangeClearsMapMarksAndRecomputesScanStats() {
        var columns = new ColumnStateMap();
        int[] c = new int[2];
        for (int i = 0; i < 8 * 3; i++) { // ring 3: NOT_GENERATED answers — permanent session-satisfy
            SpiralScanner.ringIndexToCoord(3, i, CX, CZ, c);
            columns.onNotGenerated(PositionUtil.packPosition(c[0], c[1]));
        }
        seedSatisfied(columns, 4, 4);
        var s = scanner(4);
        var queue = new Sink();
        assertEquals(0, fireScan(s, 2, columns, queue),
                "precondition: NOT_GENERATED parks permanently — the settled disc declares nothing");
        columns.markRetry(ringPos(4, 0));

        // The production dimension-change sequence (LodRequestManager.onDimensionChange).
        columns.clear();
        queue.clear();
        s.resetScanCounter();

        assertEquals(0, s.getConfirmedRing(), "dimension change must zero ring confirmation");
        assertFalse(columns.hasRetries(), "map clear drops retry marks with the old dimension");
        assertFalse(queue.hasNext(), "the old dimension's want-set is dropped");
        int n = fireScan(s, 2, columns, queue);
        assertEquals(8 * 3 + 8 * 4, n, "the full annulus re-declares as unknown");
        assertEquals(n, s.getLastQueued(), "scan stats recomputed for the fresh scan, never stale");
        for (int i = 0; i < n; i++) {
            assertEquals(-1L, queue.ts[i], "cleared map re-requests with ts=-1, not stale stamps");
        }
    }

    @Test
    void disconnectRejoinFullResetPrimesImmediateScan() {
        var columns = new ColumnStateMap();
        seedSatisfied(columns, 3, 4);
        var s = scanner(4);
        var queue = new Sink();
        assertEquals(0, fireScan(s, 2, columns, queue));
        assertEquals(5, s.getConfirmedRing(), "precondition: disc confirmed");
        columns.markRetry(ringPos(3, 5));

        // Production disconnect→rejoin: disconnect() clears only the tracker; the new
        // session's onSessionConfig runs resetRequestState() (columns.clear) + scanner.reset().
        columns.clear();
        s.reset();

        assertEquals(0, s.getConfirmedRing(), "reset() zeroes ring confirmation");
        assertFalse(columns.hasRetries(), "session state cleared with the map");
        int first = s.maybeScan(CX, CZ, 2, 0, 1000, () -> 0, columns, queue.pos, queue.ts);
        assertEquals(8 * 3 + 8 * 4, first,
                "reset() primes the cadence: the rejoin scan fires on the FIRST call");
    }

    // ---- lod distance shrink/grow (CL-015) ----

    @Test
    void lodDistanceShrinkThenGrowRescansTheOuterBandWithoutStranding() {
        int saved = LSSClientConfig.CONFIG.lodDistanceChunks;
        try {
            LSSClientConfig.CONFIG.lodDistanceChunks = 0; // server distance (8) in effect
            var columns = new ColumnStateMap();
            seedSatisfied(columns, 3, 8);
            var s = scanner(8);
            var queue = new Sink();
            assertEquals(0, fireScan(s, 2, columns, queue));
            assertEquals(9, s.getConfirmedRing(), "precondition: confirmed at d=8");

            LSSClientConfig.CONFIG.lodDistanceChunks = 4; // shrink
            assertEquals(0, fireScan(s, 2, columns, queue), "shrunk scan is a clean no-op");
            assertEquals(9, s.getConfirmedRing(), "confirmation may sit beyond the shrunk distance");

            // Two ring-7 columns get ingest-rejected while shrunk: unstamped + retry-marked,
            // but ring 7 sits beyond the shrunk distance, so scans cannot reach them yet —
            // the exact "stranded past the old confirmed radius" candidate.
            long a = ringPos(7, 0);
            long b = ringPos(7, 21);
            columns.onIngestFailed(a);
            columns.onIngestFailed(b);
            assertEquals(0, fireScan(s, 2, columns, queue), "retries beyond the shrunk lod are not requested");
            assertTrue(columns.hasRetries(), "...but must not be lost while out of scan range");

            LSSClientConfig.CONFIG.lodDistanceChunks = 0; // grow back to the server's 8
            int grown = fireScan(s, 2, columns, queue);
            assertEquals(2, grown, "grown scan re-walks and re-declares the outer band");
            var requeued = new java.util.HashSet<Long>();
            for (int i = 0; i < grown; i++) {
                requeued.add(queue.pos[i]);
                assertEquals(-1L, queue.ts[i], "unstamped positions re-ask as unknown");
            }
            assertEquals(java.util.Set.of(a, b), requeued, "exactly the stranded outer-band positions re-declare");
            columns.onReceived(a, 9000L);
            columns.onReceived(b, 9000L);
            assertEquals(0, fireScan(s, 2, columns, queue));
            assertEquals(9, s.getConfirmedRing(), "nothing strands past the old confirmed radius");
        } finally {
            LSSClientConfig.CONFIG.lodDistanceChunks = saved;
        }
    }

    // ---- dirty under the vanilla exclusion (CL-018) ----

    @Test
    void dirtyUnderVanillaExclusionParksUntilTheExclusionMovesOff() {
        var columns = new ColumnStateMap();
        long covered = ringPos(2, 0); // inside the viewDistance-2 exclusion square
        columns.onReceived(covered, 4321L);
        seedSatisfied(columns, 3, 4);
        var s = scanner(4);
        var queue = new Sink();
        assertEquals(0, fireScan(s, 2, columns, queue));

        assertTrue(columns.markDirtyIfKnown(covered));
        s.resetScanCounter(); // production dirty-broadcast path (LodRequestManager.onDirtyColumns)

        assertEquals(0, fireScan(s, 2, columns, queue),
                "a dirty column under vanilla coverage must not be re-requested (vanilla renders it live)");
        assertEquals(1, columns.dirtyCount(), "the mark parks instead of being consumed or dropped");
        assertEquals(0, fireScan(s, 2, columns, queue), "stays parked while covered");
        assertEquals(1, columns.dirtyCount());

        // The player moves +1 chunk: the exclusion square moves off the dirty column.
        columns.pruneOutOfRange(1, 0, 64); // production movement order: prune...
        s.resetScanCounter();              // ...then resetScanCounter (LodRequestManager.tick)
        int queued = fireScanFull(s, 1, 0, 2, 0, 1000, 0, columns, queue);
        assertTrue(queued > 0, "the un-covered scan must declare something");
        boolean foundCovered = false;
        for (int i = 0; i < queued; i++) {
            if (queue.pos[i] == covered) {
                foundCovered = true;
                assertEquals(4321L, queue.ts[i],
                        "the declared dirty re-request must carry the STORED timestamp (resync, not refetch)");
            }
        }
        assertTrue(foundCovered, "the parked dirty column must be declared once the exclusion moves off");
        assertEquals(1, columns.dirtyCount(),
                "declaring it does NOT consume the mark: under re-declaration a send-consumed mark"
                        + " would stop the re-declares while the answer was still in flight");
        columns.onReceived(covered, 5000L); // the ANSWER is the mark's only consumer now
        assertEquals(0, columns.dirtyCount(), "the terminal answer consumes the mark");
    }

    // ---- voxy distance arm (CL-011, CL-012) ----

    @Test
    void voxyDistanceRefreshIsInvocationCountBasedNotTickBased() throws Exception {
        // Pins the staleness counter at SpiralScanner.getCachedVoxyDistance as INVOCATION
        // based: the 20th getEffectiveLodDistance call refreshes, regardless of game ticks
        // (the "rechecked once per second" comment holds only while exactly one caller
        // queries once per scan). ModCompat's gate is flipped reflectively and MUST be
        // restored — a leak makes VoxyCompatTest#modCompatStaysInertWithoutVoxyMod and
        // #effectiveLodDistanceIsMinOfServerAndClientOverride order-dependent.
        int savedClient = LSSClientConfig.CONFIG.lodDistanceChunks;
        setVoxyLoaded(true);
        try {
            LSSClientConfig.CONFIG.lodDistanceChunks = 0;
            VoxyConfig.reset();
            VoxyConfig.CONFIG.sectionRenderDistance = 0.25f; // 8 chunks
            var s = scanner(10);
            for (int call = 1; call < LSSConstants.TICKS_PER_SECOND; call++) {
                assertEquals(10, s.getEffectiveLodDistance(),
                        "call " + call + " still serves the stale not-present cache");
            }
            assertEquals(8, s.getEffectiveLodDistance(), "the 20th invocation refreshes from VoxyConfig");

            VoxyConfig.CONFIG.sectionRenderDistance = 0.125f; // 4 chunks — invisible until the next window
            for (int call = 1; call < LSSConstants.TICKS_PER_SECOND; call++) {
                assertEquals(8, s.getEffectiveLodDistance(),
                        "call " + call + " of the next window serves the cached 8");
            }
            assertEquals(4, s.getEffectiveLodDistance(), "refresh window is a fresh 20 invocations");
        } finally {
            setVoxyLoaded(false);
            VoxyConfig.reset();
            LSSClientConfig.CONFIG.lodDistanceChunks = savedClient;
        }
    }

    @Test
    void voxyDistanceParticipatesInMinLadderOnlyWhenPositive() throws Exception {
        int savedClient = LSSClientConfig.CONFIG.lodDistanceChunks;
        setVoxyLoaded(true);
        try {
            LSSClientConfig.CONFIG.lodDistanceChunks = 0;
            VoxyConfig.reset(); // sectionRenderDistance 0 → voxy distance 0
            var s = scanner(10);
            assertEquals(10, refreshedEffectiveDistance(s), "voxy distance 0 is ignored (not-configured sentinel)");

            VoxyConfig.CONFIG.sectionRenderDistance = 0.25f; // 8 chunks
            assertEquals(8, refreshedEffectiveDistance(s), "voxy>0 below the server distance clamps it down");

            VoxyConfig.CONFIG.sectionRenderDistance = 1.0f; // 32 chunks
            assertEquals(10, refreshedEffectiveDistance(s), "voxy above the effective distance has no effect");
        } finally {
            setVoxyLoaded(false);
            VoxyConfig.reset();
            LSSClientConfig.CONFIG.lodDistanceChunks = savedClient;
        }
    }

    // ---- orphan-freedom property (CL-014) ----

    /**
     * Property test over seeded-random interleavings of the hazards that can orphan positions,
     * driving the scanner exactly the way LodRequestManager does (whole want-set declared per
     * scan, answer-time mark consumption, response handlers). After any chaos prefix, once
     * responses flow normally every in-range position must be re-declared and converge: a single
     * silently-orphaned position (e.g. ring confirmation advancing past an unserved position)
     * never converges and fails the scan bound.
     *
     * <p>The alphabet moved with the protocol. GONE: rate-limit backoff (no bounce exists) and
     * the in-flight drain skip (re-declaration is load-bearing — the scanner must NOT suppress an
     * awaited position). NEW and central: <b>superseded server-side</b> — the server silently
     * drops a not-yet-admitted ask (mailbox overwrite / backlog replace), so the answer simply
     * NEVER arrives and nothing on the client changes. That move is the whole reason
     * re-declaration exists: the position must reappear in a later want-set under its own steam.
     * If the scanner ever regains an in-flight skip, or a ring confirms past a superseded
     * position, this test fails to converge — which is exactly a permanent LOD hole.
     */
    @Test
    void anyChaosInterleavingLeavesNoPositionPermanentlyOrphaned() {
        final int vd = 2, lod = 6;
        for (long seed : new long[] {1L, 7L, 42L}) {
            var rng = new Random(seed);
            var columns = new ColumnStateMap();
            var queue = new Sink();
            var s = scanner(lod); // the constant 800 budget covers the whole disc
            record Scheduled(long pos, int dueCycle) {}
            var scheduled = new ArrayList<Scheduled>();
            var awaitingLate = new LongOpenHashSet(); // positions with a late answer already booked
            int supersededCount = 0;

            for (int cycle = 0; cycle < 30; cycle++) {
                for (var iter = scheduled.iterator(); iter.hasNext(); ) {
                    var ev = iter.next();
                    if (ev.dueCycle() > cycle) continue;
                    iter.remove();
                    awaitingLate.remove(ev.pos());
                    columns.onReceived(ev.pos(), 1_000L + cycle); // late response
                }
                // missingVanilla is capped at 24 of the 25-chunk exclusion area: at 25 the budget
                // zeroes and the scan reports "no walk" (-1) forever, which fireScanFull cannot
                // observe. That path is pinned by missingVanillaShrinksBudgetQuadraticallyToZero
                // and by the manager's noWalkTickNeitherSendsNorReplacesTheAwaitingSet; here we
                // want heavy throttling (24/25 -> budget 3) without stopping the walk.
                int n = fireScanFull(s, CX, CZ, vd, rng.nextInt(900), 1000, rng.nextInt(25),
                        columns, queue);
                int cyc = cycle;
                for (int i = 0; i < n; i++) {
                    long pos = queue.pos[i];
                    int cheb = Math.max(Math.abs(PositionUtil.unpackX(pos) - CX),
                            Math.abs(PositionUtil.unpackZ(pos) - CZ));
                    assertTrue(cheb > vd && cheb <= lod,
                            "seed " + seed + ": scan emitted out-of-range Chebyshev " + cheb);
                    // A booked late answer is already coming; the re-declare is a duplicate the
                    // server absorbs (duplicate_skip). Everything else draws a fresh disposition.
                    if (awaitingLate.contains(pos)) continue;
                    int roll = rng.nextInt(100);
                    if (roll < 30) columns.onReceived(pos, 1_000L + cyc);
                    else if (roll < 45) columns.markRetry(pos);   // ingest-failure retry mark
                    else if (roll < 60) columns.onNotGenerated(pos);
                    else if (roll < 70) columns.onUpToDate(pos);
                    else if (roll < 85) { scheduled.add(new Scheduled(pos, cyc + 1 + rng.nextInt(3)));
                                          awaitingLate.add(pos); }
                    else supersededCount++; // SUPERSEDED: no answer, ever. Only a re-declare saves it.
                }
            }
            assertTrue(supersededCount > 0,
                    "seed " + seed + ": the chaos never exercised a server-side supersession");
            // Chaos over: the booked late answers are ALSO dropped — i.e. superseded too. Nothing
            // re-marks them; only re-declaration can bring them back. (Pre-want-set this loop
            // marked them retry to force reachability; the want-set must not need that crutch.)
            scheduled.clear();
            awaitingLate.clear();

            // Convergence: responses now always succeed; every position must be re-declared.
            boolean converged = false;
            for (int i = 0; i < 40 && !converged; i++) {
                int n = fireScanFull(s, CX, CZ, vd, 0, 1000, 0, columns, queue);
                for (int j = 0; j < n; j++) {
                    columns.onReceived(queue.pos[j], 5_000L + i);
                }
                converged = n == 0 && s.getConfirmedRing() == lod + 1 && allSatisfied(columns, vd + 1, lod);
            }
            assertTrue(converged, "seed " + seed + ": chaos interleaving permanently orphaned a position"
                    + " (confirmedRing=" + s.getConfirmedRing() + ")");
        }
    }

    // ---- CL-014: a ts=0 position below the confirmed ring is re-reached by a re-walk ----
    //
    // The STAGING moved with the want-set; the invariant did not. Pre-want-set the scanner
    // suppressed in-flight positions WITHOUT breaking ring confirmation, so a ring could confirm
    // past a position whose answer was still outstanding — a late not_generated then stranded it
    // below the confirmed prefix forever. Under re-declaration that trigger is structurally gone:
    // an awaited position is an ordinary unsatisfied want-set member, so it blocks its ring's
    // confirmation until data lands (awaitedPositionsAreReDeclaredAndBlockRingConfirmation pins
    // exactly that). resetConfirmedRing's CONTRACT still has to hold — production still calls it
    // from onColumnNotGenerated and consumeStaleCrossing as defence-in-depth — so these two tests
    // now stage the stranded state directly instead of through the retired in-flight-skip.

    /** Seed ring 1 fully satisfied and scan once so the ring confirms PAST it. Returns ring-1[0]. */
    private static long stageRing1Confirmed(SpiralScanner s, ColumnStateMap columns, Sink queue) {
        int[] c = new int[2];
        long target = 0;
        for (int i = 0; i < 8; i++) {
            SpiralScanner.ringIndexToCoord(1, i, CX, CZ, c);
            long packed = PositionUtil.packPosition(c[0], c[1]);
            columns.onReceived(packed, 5000L); // validated this session -> SATISFIED
            if (i == 0) target = packed;
        }
        assertEquals(0, fireScanFull(s, CX, CZ, 0, 0, 1000, 0, columns, queue),
                "premise: a fully satisfied disc declares nothing");
        assertTrue(s.getConfirmedRing() > 1, "premise: the ring confirmed past the target");
        return target;
    }

    @Test
    void dirtiedPositionBelowConfirmedRingIsRereachedByResetConfirmedRing() {
        var s = scanner(1);
        var columns = new ColumnStateMap();
        var queue = new Sink();
        long target = stageRing1Confirmed(s, columns, queue);

        // A dirty broadcast re-opens the below-ring position (the stale-crossing shape:
        // consumeStaleCrossing marks dirty at a terminal outcome, below the confirmed ring).
        columns.markDirtyIfKnown(target);

        // A normal scan starts at the confirmed ring (past the target) — it stays stranded.
        assertEquals(0, fireScanFull(s, CX, CZ, 0, 0, 1000, 0, columns, queue),
                "without the re-walk a below-ring re-opened position is never rescanned (CL-014)");

        // The fix forces a re-walk from the innermost ring, re-reaching it.
        s.resetConfirmedRing();
        int recount = fireScanFull(s, CX, CZ, 0, 0, 1000, 0, columns, queue);
        assertEquals(1, recount, "resetConfirmedRing re-walks and re-emits the re-opened position");
        assertEquals(List.of(target), queue.positions());
    }

    @Test
    void notGeneratedPositionStaysParkedThroughAConfirmedRingReset() {
        var s = new SpiralScanner();
        s.setConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, 1, false));
        var columns = new ColumnStateMap();
        var queue = new Sink();
        long target = stageRing1Confirmed(s, columns, queue);

        columns.onNotGenerated(target);
        s.resetConfirmedRing();
        // A NOT_GENERATED position is permanently session-satisfied, so even a full re-walk
        // must NOT re-request it — only a dirty broadcast revives it (no re-request loop).
        assertEquals(0, fireScanFull(s, CX, CZ, 0, 0, 1000, 0, columns, queue),
                "a NOT_GENERATED position is parked for the session — a re-walk must not re-ask it");
    }

    // ---- re-declaration (the load-bearing want-set invariant) ----

    @Test
    void awaitedPositionsAreReDeclaredAndBlockRingConfirmation() {
        // Re-declaration is load-bearing, not an optimisation: the server may silently supersede
        // any not-yet-admitted ask, and the 1 Hz re-declare is the ONLY thing that heals it. So an
        // unanswered position must appear in EVERY scan's want-set, and its ring must not confirm
        // past it until data actually arrives. (Suppressing it — the pre-want-set behaviour — plus
        // a server-side silent drop is a 10s-class stall, or with the sweep gone, permanent.)
        var s = scanner(1);
        var columns = new ColumnStateMap();
        var queue = new Sink();
        int[] c = new int[2];
        long target = 0;
        for (int i = 0; i < 8; i++) {
            SpiralScanner.ringIndexToCoord(1, i, CX, CZ, c);
            long packed = PositionUtil.packPosition(c[0], c[1]);
            if (i == 0) { target = packed; continue; } // the one unanswered position
            columns.onReceived(packed, 5000L);
        }

        assertEquals(1, fireScanFull(s, CX, CZ, 0, 0, 1000, 0, columns, queue),
                "scan 1 declares the unsatisfied position");
        assertEquals(List.of(target), queue.positions());
        assertEquals(1, s.getConfirmedRing(),
                "an awaited position blocks confirmation OF ITS OWN ring: ring 0 is empty so it"
                        + " always confirms, and the walk must still start at ring 1 — never past it");

        // No answer arrives. The scanner has no in-flight predicate any more: it must re-declare.
        assertEquals(1, fireScanFull(s, CX, CZ, 0, 0, 1000, 0, columns, queue),
                "scan 2 must RE-DECLARE the still-unanswered position, not suppress it");
        assertEquals(List.of(target), queue.positions());
        assertEquals(1, s.getConfirmedRing(), "still unanswered: confirmation still must not pass it");

        // The data lands: only now does it drop out of the want-set and release the ring.
        columns.onReceived(target, 6000L);
        assertEquals(0, fireScanFull(s, CX, CZ, 0, 0, 1000, 0, columns, queue),
                "answered: the position leaves the want-set");
        assertEquals(2, s.getConfirmedRing(), "answered: the ring confirms past it (lodDistance+1)");
    }
}
