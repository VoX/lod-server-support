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
import java.util.function.LongPredicate;

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

    private static SpiralScanner scanner(int lodDistance, int syncLimit, int genLimit) {
        var s = new SpiralScanner();
        s.setConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true,
                lodDistance, syncLimit, genLimit, true));
        return s;
    }

    /** Drive maybeScan until the 20-tick cadence fires; returns the queued count. */
    private static int fireScan(SpiralScanner s, int viewDistance, ColumnStateMap columns,
                                RequestQueue queue) {
        return fireScan(s, viewDistance, 0, 1000, 0, columns, queue);
    }

    /** fireScan with explicit budget-scale inputs (column queue fill, missing vanilla chunks). */
    private static int fireScan(SpiralScanner s, int viewDistance, int columnQueueSize,
                                int columnQueueHaltThreshold, int missingVanilla,
                                ColumnStateMap columns, RequestQueue queue) {
        for (int i = 0; i < LSSConstants.TICKS_PER_SECOND + 1; i++) {
            int n = s.maybeScan(CX, CZ, viewDistance, columnQueueSize, columnQueueHaltThreshold,
                    () -> missingVanilla, columns, pos -> false, queue);
            if (n >= 0) return n;
        }
        throw new AssertionError("scan cadence never fired");
    }

    private static List<Long> drain(RequestQueue queue) {
        var out = new ArrayList<Long>();
        while (queue.hasNext()) {
            out.add(queue.peekPosition());
            queue.skip();
        }
        return out;
    }

    /** fireScan with full control of center, budget-scale inputs, and the in-flight predicate. */
    private static int fireScanFull(SpiralScanner s, int cx, int cz, int viewDistance,
                                    int columnQueueSize, int columnQueueHaltThreshold, int missingVanilla,
                                    ColumnStateMap columns, LongPredicate isInFlight, RequestQueue queue) {
        for (int i = 0; i < LSSConstants.TICKS_PER_SECOND + 1; i++) {
            int n = s.maybeScan(cx, cz, viewDistance, columnQueueSize, columnQueueHaltThreshold,
                    () -> missingVanilla, columns, isInFlight, queue);
            if (n >= 0) return n;
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
                columns.markSent(packed);
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
                if (columns.classify(PositionUtil.packPosition(c[0], c[1]), true) != ColumnStateMap.SATISFIED) {
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
    //  1. Euclidean-per-RING exclusion (`2*r*r <= R*R`, pre-683f67f): excluded a ring only when its
    //     far corner was within R, so it UNDER-excluded — square edge chunks (within vanilla's view,
    //     loaded and re-saving inhabitedTime every ~10s) stayed LOD-eligible → a permanent
    //     re-request/re-serve LOOP on them.
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
        var s = scanner(4, 100, 100);
        var queue = new RequestQueue();
        int queued = fireScan(s, 2, new ColumnStateMap(), queue);

        assertTrue(queued > 0);
        for (long packed : drain(queue)) {
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
        var s = scanner(6, 100, 100);
        var queue = new RequestQueue();
        int queued = fireScan(s, 4, new ColumnStateMap(), queue);
        var drained = new java.util.HashSet<>(drain(queue));
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
        var s = scanner(lod, 1000, 1000); // budget large enough that nothing is dropped for capacity
        var queue = new RequestQueue();
        int queued = fireScan(s, vd, new ColumnStateMap(), queue);
        var requested = new java.util.HashSet<>(drain(queue));
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
    void satisfiedOuterRingMustNotConfirmPastUnsatisfiedInnerRing() {
        // genCap = genLimit * 4 = 4: ring 3 (24 not-generated positions) can only queue 4
        // per scan — unsatisfied; rings 4-5 are fully satisfied. Confirmation must hold at
        // ring 3 rather than jumping to 6 and orphaning ring 3's remaining 20 positions.
        var columns = new ColumnStateMap();
        int[] c = new int[2];
        for (int r = 3; r <= 5; r++) {
            for (int i = 0; i < 8 * r; i++) {
                SpiralScanner.ringIndexToCoord(r, i, CX, CZ, c);
                long packed = PositionUtil.packPosition(c[0], c[1]);
                if (r == 3) {
                    columns.onNotGenerated(packed);          // ts == 0 → wants generation
                } else {
                    columns.onReceived(packed, 1000L);       // satisfied
                    columns.markSent(packed);
                    columns.onUpToDate(packed);
                }
            }
        }

        var s = scanner(5, 100, 1);
        var queue = new RequestQueue();
        int queued = fireScan(s, 2, columns, queue);
        assertEquals(4, queued, "gen cap (1*4) bounds the queued generation retries");
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
                columns.markSent(packed);
                columns.onUpToDate(packed);
            }
        }
        var s = scanner(4, 100, 100);
        var queue = new RequestQueue();
        assertEquals(0, fireScan(s, 2, columns, queue));
        assertEquals(5, s.getConfirmedRing(), "fully satisfied disc confirms past lodDistance");
    }

    @Test
    void rateLimitBackoffSkipsExactlyOneScan() {
        var s = scanner(4, 100, 100);
        var queue = new RequestQueue();
        s.noteRateLimited();
        assertEquals(0, fireScan(s, 2, new ColumnStateMap(), queue), "backoff scan queues nothing");
        assertTrue(fireScan(s, 2, new ColumnStateMap(), queue) > 0, "next scan proceeds normally");
    }

    @Test
    void budgetBoundsQueuedPositions() {
        // budget = syncLimit * 4 = 8 with a huge annulus
        var s = scanner(16, 2, 100);
        var queue = new RequestQueue();
        assertEquals(8, fireScan(s, 2, new ColumnStateMap(), queue));
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
                columns.markSent(packed);
                columns.onUpToDate(packed);
            }
        }
        var s = scanner(4, 100, 100);
        var queue = new RequestQueue();
        assertEquals(0, fireScan(s, 2, columns, queue));
        assertEquals(5, s.getConfirmedRing(), "precondition: disc confirmed past lodDistance");

        // A rate-limited bounce marks a ring-3 position for retry. The disc is already
        // confirmed past it, so the next scan must restart from ring 0 or the retry would
        // sit inside the skipped prefix and never be re-sent.
        SpiralScanner.ringIndexToCoord(3, 0, CX, CZ, c);
        long retried = PositionUtil.packPosition(c[0], c[1]);
        columns.markRetry(retried);

        assertEquals(1, fireScan(s, 2, columns, queue),
                "scan after a retry mark must re-walk the confirmed disc and queue the retry");
        assertEquals(List.of(retried), drain(queue));
        assertEquals(3, s.getConfirmedRing(), "confirmation holds at the unsatisfied retry ring");
    }

    @Test
    void queuePressureShrinksBudgetLinearlyWithFloorOne() {
        // base budget = 25 * 4 = 100; rings 3..16 hold 1064 candidates, far above any budget
        var s = scanner(16, 25, 100);
        var queue = new RequestQueue();
        assertEquals(75, fireScan(s, 2, 250, 1000, 0, new ColumnStateMap(), queue),
                "column queue at 25% of halt threshold scales the budget linearly to 75");

        // At the halt threshold the linear scale reaches 0 but the budget floors at 1
        s = scanner(16, 25, 100);
        queue = new RequestQueue();
        assertEquals(1, fireScan(s, 2, 1000, 1000, 0, new ColumnStateMap(), queue),
                "queue pressure floors the budget at 1, never 0");
    }

    @Test
    void missingVanillaShrinksBudgetQuadraticallyToZero() {
        // base = 100; viewDistance 2 → exclusion area 25; 15 missing → fraction 0.6,
        // quadratic scale 1 - 0.36 = 0.64 (a linear scale would give 40)
        var s = scanner(16, 25, 100);
        var queue = new RequestQueue();
        assertEquals(64, fireScan(s, 2, 0, 1000, 15, new ColumnStateMap(), queue),
                "vanilla-load scale is quadratic in the missing fraction");

        // All 25 exclusion chunks missing → scale 0 → no scan at all (no floor on this path)
        s = scanner(16, 25, 100);
        queue = new RequestQueue();
        assertEquals(0, fireScan(s, 2, 0, 1000, 25, new ColumnStateMap(), queue),
                "fully missing vanilla disc zeroes the budget");
    }

    @Test
    void backoffSurvivesMovementResetButNotDimensionChangeClear() {
        // Movement and dirty-broadcast paths call resetScanCounter() alone; it must
        // preserve a pending rate-limit backoff.
        var s = scanner(4, 100, 100);
        var queue = new RequestQueue();
        s.noteRateLimited();
        s.resetScanCounter();
        assertEquals(0, fireScan(s, 2, new ColumnStateMap(), queue),
                "backoff still pending after a movement-path reset");
        assertTrue(fireScan(s, 2, new ColumnStateMap(), queue) > 0, "next scan proceeds normally");

        // The dimension-change path additionally calls clearSkipNextScan() — the backoff
        // belonged to the old dimension's load and must be discarded.
        s = scanner(4, 100, 100);
        queue = new RequestQueue();
        s.noteRateLimited();
        s.resetScanCounter();
        s.clearSkipNextScan();
        assertTrue(fireScan(s, 2, new ColumnStateMap(), queue) > 0,
                "dimension change discards the pending backoff");
    }

    @Test
    void effectiveLodDistanceIsMinOfServerAndClientOverride() {
        int saved = LSSClientConfig.CONFIG.lodDistanceChunks;
        try {
            var s = scanner(10, 100, 100);
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
            var s = scanner(10, 100, 100);
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
        var s = scanner(4, 100, 100);
        var queue = new RequestQueue();

        // A fresh scanner is primed: the very FIRST cadence call must fire (join burst),
        // not the 20th — the fireScan helper used elsewhere cannot tell those apart.
        int first = s.maybeScan(CX, CZ, 2, 0, 1000, () -> 0, new ColumnStateMap(), pos -> false, queue);
        assertEquals(8 * 3 + 8 * 4, first, "first maybeScan on a fresh scanner must fire and queue the annulus");

        // reset() (new session / flushCache) re-primes: again a single call fires.
        s.reset();
        int afterReset = s.maybeScan(CX, CZ, 2, 0, 1000, () -> 0, new ColumnStateMap(), pos -> false, queue);
        assertEquals(8 * 3 + 8 * 4, afterReset, "first maybeScan after reset() must fire immediately");
    }

    // ---- zero-budget remainder preservation (CL-005) ----

    @Test
    void zeroBudgetScanPreservesCommittedRemainder() {
        var s = scanner(4, 100, 100);
        var queue = new RequestQueue();
        var columns = new ColumnStateMap();
        assertEquals(56, fireScan(s, 2, columns, queue), "precondition: a committed scan result");
        queue.skip(); // the manager drains a couple of entries...
        queue.skip(); // ...and the rest must keep draining on later ticks
        long expectedHead = queue.peekPosition();
        int expectedRemaining = queue.remaining();

        // Next cadence firing computes budget 0 (whole vanilla exclusion disc missing) and
        // must return before any queue write — a commit(0) here would wipe the remainder.
        assertEquals(0, fireScan(s, 2, 0, 1000, 25, columns, queue), "zero-budget scan queues nothing");

        assertEquals(expectedRemaining, queue.remaining(),
                "a zero-budget scan must not commit(0)-wipe the undrained remainder");
        assertEquals(expectedHead, queue.peekPosition(), "remainder content untouched");
    }

    // ---- degenerate exclusion coverage (CL-007) ----

    @Test
    void viewDistanceCoveringLodDistanceConfirmsWithoutRequests() {
        // vd comfortably exceeding lod: vanilla's rounded (buffered-Euclidean) view subsumes the
        // WHOLE lod disc — including its corners — so nothing is requested and it confirms to lod+1.
        // (NB at vd == lod the disc leaves the lod square's corners OUTSIDE the view; those are the
        // corner-fix annulus, pinned by renderSquareCornersBeyondVanillasRoundedViewAreRequested.
        // Here vd=5 > lod=4 so the corner (4,4) at buffered 3^2+3^2=18 < 5^2=25 is in view.)
        var s = scanner(4, 100, 100);
        var queue = new RequestQueue();
        assertEquals(0, fireScan(s, 5, new ColumnStateMap(), queue), "vanilla's rounded view covers the whole disc");
        assertEquals(5, s.getConfirmedRing(), "exclusion-skipped disc confirms to lodDistance+1");
        assertEquals(0, fireScan(s, 5, new ColumnStateMap(), queue), "no spin: stays settled");
        assertEquals(5, s.getConfirmedRing());

        // vd > lod: confirmation still caps at lod+1, never tracks the overshooting exclusion.
        var s2 = scanner(4, 100, 100);
        assertEquals(0, fireScan(s2, 6, new ColumnStateMap(), queue));
        assertEquals(5, s2.getConfirmedRing(), "confirmation caps at lodDistance+1 when vd overshoots");
    }

    // ---- backoff latch is level-triggered (CL-008) ----

    @Test
    void multipleRateLimitNoticesBeforeOneScanConsumeExactlyOneSkip() {
        var s = scanner(4, 100, 100);
        var queue = new RequestQueue();
        s.noteRateLimited();
        s.noteRateLimited();
        s.noteRateLimited();
        assertEquals(0, fireScan(s, 2, new ColumnStateMap(), queue), "one skipped scan");
        assertTrue(fireScan(s, 2, new ColumnStateMap(), queue) > 0,
                "level-triggered latch: N rate-limit notices cost exactly one skip, not N");
    }

    // ---- reset matrix: movement / dimension change / disconnect (CL-016) ----

    @Test
    void movementResetZeroesConfirmedRingRestartsCadenceAndKeepsMarks() {
        var columns = new ColumnStateMap();
        seedSatisfied(columns, 3, 4);
        var s = scanner(4, 100, 100);
        var queue = new RequestQueue();
        assertEquals(0, fireScan(s, 2, columns, queue));
        assertEquals(5, s.getConfirmedRing(), "precondition: disc confirmed");
        long retried = ringPos(4, 0);
        columns.markRetry(retried);
        s.noteRateLimited();

        s.resetScanCounter(); // the movement path (LodRequestManager.tick: prune + resetScanCounter)

        assertEquals(0, s.getConfirmedRing(),
                "movement reset must zero ring confirmation (SpiralScanner.resetScanCounter)");
        assertEquals(-1, s.maybeScan(CX, CZ, 2, 0, 1000, () -> 0, columns, p -> false, queue),
                "resetScanCounter restarts the full 20-tick cadence (a debounce, unlike reset())");
        assertTrue(columns.hasRetries(), "movement preserves in-range retry marks");
        assertEquals(0, fireScan(s, 2, columns, queue), "pending rate-limit backoff survives movement");
        assertEquals(1, fireScan(s, 2, columns, queue), "next scan re-walks the disc and queues the retry");
        assertEquals(List.of(retried), drain(queue));
    }

    @Test
    void dimensionChangeClearsMapMarksBackoffQueueAndRecomputesScanStats() {
        var columns = new ColumnStateMap();
        int[] c = new int[2];
        for (int i = 0; i < 8 * 3; i++) { // ring 3: not-generated stamps (gen candidates)
            SpiralScanner.ringIndexToCoord(3, i, CX, CZ, c);
            columns.onNotGenerated(PositionUtil.packPosition(c[0], c[1]));
        }
        seedSatisfied(columns, 4, 4);
        var s = scanner(4, 100, 1); // genCap 4
        var queue = new RequestQueue();
        assertEquals(4, fireScan(s, 2, columns, queue), "precondition: gen-capped scan");
        assertEquals(4, s.getLastGenQueued());
        columns.markRetry(ringPos(4, 0));
        s.noteRateLimited();

        // The production dimension-change sequence (LodRequestManager.onDimensionChange).
        columns.clear();
        queue.clear();
        s.resetScanCounter();
        s.clearSkipNextScan();

        assertEquals(0, s.getConfirmedRing(), "dimension change must zero ring confirmation");
        assertFalse(columns.hasRetries(), "map clear drops retry marks with the old dimension");
        assertFalse(queue.hasNext(), "the old dimension's queued requests are dropped");
        int n = fireScan(s, 2, columns, queue);
        assertEquals(8 * 3 + 8 * 4, n, "no backoff pending; the full annulus re-requests as unknown");
        assertEquals(0, s.getLastGenQueued(), "scan stats recomputed for the fresh scan, never stale");
        assertEquals(n, s.getLastSyncQueued(), "everything re-asks sync after the map clear");
        while (queue.hasNext()) {
            assertEquals(-1L, queue.peekTimestamp(), "cleared map re-requests with ts=-1, not stale stamps");
            queue.skip();
        }
    }

    @Test
    void disconnectRejoinFullResetPrimesImmediateScanAndDropsBackoff() {
        var columns = new ColumnStateMap();
        seedSatisfied(columns, 3, 4);
        var s = scanner(4, 100, 100);
        var queue = new RequestQueue();
        assertEquals(0, fireScan(s, 2, columns, queue));
        assertEquals(5, s.getConfirmedRing(), "precondition: disc confirmed");
        columns.markRetry(ringPos(3, 5));
        s.noteRateLimited();

        // Production disconnect→rejoin: disconnect() clears only the tracker; the new
        // session's onSessionConfig runs resetRequestState() (columns.clear) + scanner.reset().
        columns.clear();
        s.reset();

        assertEquals(0, s.getConfirmedRing(), "reset() zeroes ring confirmation");
        assertFalse(columns.hasRetries(), "session state cleared with the map");
        int first = s.maybeScan(CX, CZ, 2, 0, 1000, () -> 0, columns, p -> false, queue);
        assertEquals(8 * 3 + 8 * 4, first,
                "reset() primes the cadence: the rejoin scan fires on the FIRST call with the backoff discarded");
    }

    // ---- lod distance shrink/grow (CL-015) ----

    @Test
    void lodDistanceShrinkThenGrowRescansTheOuterBandWithoutStranding() {
        int saved = LSSClientConfig.CONFIG.lodDistanceChunks;
        try {
            LSSClientConfig.CONFIG.lodDistanceChunks = 0; // server distance (8) in effect
            var columns = new ColumnStateMap();
            seedSatisfied(columns, 3, 8);
            var s = scanner(8, 100, 100);
            var queue = new RequestQueue();
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
            assertEquals(2, fireScan(s, 2, columns, queue), "grown scan re-walks and re-queues the outer band");
            var requeued = new java.util.HashSet<Long>();
            while (queue.hasNext()) {
                requeued.add(queue.peekPosition());
                assertEquals(-1L, queue.peekTimestamp(), "unstamped positions re-ask as unknown");
                queue.skip();
            }
            assertEquals(java.util.Set.of(a, b), requeued, "exactly the stranded outer-band positions re-queue");

            columns.markSent(a);
            columns.onReceived(a, 9000L);
            columns.markSent(b);
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
        var s = scanner(4, 100, 100);
        var queue = new RequestQueue();
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
        int queued = fireScanFull(s, 1, 0, 2, 0, 1000, 0, columns, pos -> false, queue);
        assertTrue(queued > 0, "the un-covered scan must queue something");
        boolean foundCovered = false;
        while (queue.hasNext()) {
            if (queue.peekPosition() == covered) {
                foundCovered = true;
                assertEquals(4321L, queue.peekTimestamp(),
                        "the drained dirty re-request must carry the STORED timestamp (resync, not refetch)");
            }
            queue.skip();
        }
        assertTrue(foundCovered, "the parked dirty column must drain once the exclusion moves off");
        assertEquals(1, columns.dirtyCount(), "scanning queues it; only the actual send consumes the mark");
        columns.markSent(covered);
        assertEquals(0, columns.dirtyCount());
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
            var s = scanner(10, 100, 100);
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
            var s = scanner(10, 100, 100);
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
     * Property test over seeded-random interleavings of the four hazards that historically
     * orphaned positions (budget exhaustion, gen-cap skips, rate-limit backoff, timeout
     * eviction), driving the scanner exactly the way LodRequestManager does (drainQueue
     * skip rules, markSent, response handlers, sweepTimeouts marking retries). After any
     * chaos prefix, once responses flow normally every in-range position must be re-emitted
     * and converge: a single silently-orphaned position (e.g. ring confirmation advancing
     * past an unserved position) never converges and fails the scan bound.
     */
    @Test
    void anyChaosInterleavingLeavesNoPositionPermanentlyOrphaned() {
        final int vd = 2, lod = 6;
        for (long seed : new long[] {1L, 7L, 42L}) {
            var rng = new Random(seed);
            var columns = new ColumnStateMap();
            var queue = new RequestQueue();
            var s = scanner(lod, 10, 2); // budget 40, genCap 8
            var inFlight = new LongOpenHashSet();
            record Scheduled(long pos, int dueCycle, boolean evict) {}
            var scheduled = new ArrayList<Scheduled>();

            for (int cycle = 0; cycle < 30; cycle++) {
                for (var iter = scheduled.iterator(); iter.hasNext(); ) {
                    var ev = iter.next();
                    if (ev.dueCycle() > cycle) continue;
                    iter.remove();
                    inFlight.remove(ev.pos());
                    if (ev.evict()) columns.markRetry(ev.pos());       // sweepTimeouts eviction
                    else columns.onReceived(ev.pos(), 1_000L + cycle); // late response
                }
                fireScanFull(s, CX, CZ, vd, rng.nextInt(900), 1000, rng.nextInt(26),
                        columns, inFlight::contains, queue);
                int cyc = cycle;
                while (queue.hasNext()) {
                    long pos = queue.peekPosition();
                    queue.skip();
                    int cheb = Math.max(Math.abs(PositionUtil.unpackX(pos) - CX),
                            Math.abs(PositionUtil.unpackZ(pos) - CZ));
                    assertTrue(cheb > vd && cheb <= lod,
                            "seed " + seed + ": scan emitted out-of-range Chebyshev " + cheb);
                    if (inFlight.contains(pos)) continue;                                  // drainQueue skip
                    if (columns.classify(pos, true) == ColumnStateMap.SATISFIED) continue; // drainQueue skip
                    columns.markSent(pos);
                    inFlight.add(pos);
                    int roll = rng.nextInt(100);
                    if (roll < 30) { inFlight.remove(pos); columns.onReceived(pos, 1_000L + cyc); }
                    else if (roll < 45) { inFlight.remove(pos); columns.markRetry(pos); s.noteRateLimited(); }
                    else if (roll < 60) { inFlight.remove(pos); columns.onNotGenerated(pos); }
                    else if (roll < 70) { inFlight.remove(pos); columns.onUpToDate(pos); }
                    else if (roll < 85) { scheduled.add(new Scheduled(pos, cyc + 1 + rng.nextInt(3), false)); }
                    else { scheduled.add(new Scheduled(pos, cyc + 2, true)); }
                }
            }
            // Chaos over: flush still-in-flight requests as worst-case timeout evictions.
            for (var ev : scheduled) {
                inFlight.remove(ev.pos());
                columns.markRetry(ev.pos());
            }
            scheduled.clear();
            assertTrue(inFlight.isEmpty(), "seed " + seed + ": simulation leaked an in-flight position");

            // Convergence: responses now always succeed; every position must be re-emitted.
            boolean converged = false;
            for (int i = 0; i < 40 && !converged; i++) {
                int n = fireScanFull(s, CX, CZ, vd, 0, 1000, 0, columns, inFlight::contains, queue);
                while (queue.hasNext()) {
                    long pos = queue.peekPosition();
                    queue.skip();
                    if (columns.classify(pos, true) == ColumnStateMap.SATISFIED) continue;
                    columns.markSent(pos);
                    columns.onReceived(pos, 5_000L + i);
                }
                converged = n == 0 && s.getConfirmedRing() == lod + 1 && allSatisfied(columns, vd + 1, lod);
            }
            assertTrue(converged, "seed " + seed + ": chaos interleaving permanently orphaned a position"
                    + " (confirmedRing=" + s.getConfirmedRing() + ")");
        }
    }

    // ---- CL-014: a late not_generated below the confirmed ring is re-reached by a re-walk ----

    /** Validate every ring-1 position except the target (treated as in-flight in scan 1), then
     *  scan once so the ring confirms PAST the in-flight target. Returns the target's packed pos. */
    private static long stageRing1WithOneInFlightTarget(SpiralScanner s, ColumnStateMap columns,
                                                        RequestQueue queue) {
        int[] c = new int[2];
        long target = 0;
        for (int i = 0; i < 8; i++) {
            SpiralScanner.ringIndexToCoord(1, i, CX, CZ, c);
            long packed = PositionUtil.packPosition(c[0], c[1]);
            if (i == 0) { target = packed; continue; } // leave the target unvalidated (in-flight)
            columns.onReceived(packed, 5000L); // validated this session -> SATISFIED
        }
        final long t = target;
        fireScanFull(s, CX, CZ, 0, 0, 1000, 0, columns, pos -> pos == t, queue);
        drain(queue);
        assertTrue(s.getConfirmedRing() > 1, "premise: the ring confirmed past the in-flight target");
        return target;
    }

    @Test
    void lateNotGeneratedBelowConfirmedRingIsRereachedByResetConfirmedRing() {
        var s = scanner(1, 4, 4); // gen ENABLED
        var columns = new ColumnStateMap();
        var queue = new RequestQueue();
        long target = stageRing1WithOneInFlightTarget(s, columns, queue);

        // The late not_generated arrives: target no longer in-flight, stamped ts=0 (gen-retry-able).
        columns.onNotGenerated(target);

        // A normal scan starts at the confirmed ring (past the target) — it stays stranded.
        assertEquals(0, fireScanFull(s, CX, CZ, 0, 0, 1000, 0, columns, pos -> false, queue),
                "without the re-walk a below-ring ts=0 position is never rescanned (the CL-014 hole)");

        // The fix forces a re-walk from the innermost ring, re-reaching it.
        s.resetConfirmedRing();
        int recount = fireScanFull(s, CX, CZ, 0, 0, 1000, 0, columns, pos -> false, queue);
        var emitted = drain(queue);
        assertEquals(1, recount, "resetConfirmedRing re-walks and re-emits the stranded ts=0 position");
        assertEquals(target, emitted.get(0));
    }

    @Test
    void lateNotGeneratedStaysParkedWhenGenerationDisabled() {
        var s = new SpiralScanner();
        s.setConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, 1, 4, 4, false));
        var columns = new ColumnStateMap();
        var queue = new RequestQueue();
        long target = stageRing1WithOneInFlightTarget(s, columns, queue);

        columns.onNotGenerated(target);
        s.resetConfirmedRing();
        // classify parks a ts=0 position SATISFIED when generation is off, so even after the
        // re-walk it is NOT re-requested — the reset is gen-disabled-safe (no re-request loop).
        assertEquals(0, fireScanFull(s, CX, CZ, 0, 0, 1000, 0, columns, pos -> false, queue),
                "with generation disabled a not_generated position parks, it must not loop re-requests");
    }
}
