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
        return fireScan(s, viewDistance, columnQueueSize, columnQueueHaltThreshold,
                -1, 1000, missingVanilla, columns, queue);
    }

    /** fireScan with full pressure inputs, incl. the consumer-reported ingest backlog (issue #71). */
    private static int fireScan(SpiralScanner s, int viewDistance, int columnQueueSize,
                                int columnQueueHaltThreshold, int ingestBacklog, int ingestBacklogHalt,
                                int missingVanilla,
                                ColumnStateMap columns, Sink queue) {
        for (int i = 0; i < LSSConstants.TICKS_PER_SECOND + 1; i++) {
            int n = s.maybeScan(CX, CZ, viewDistance, columnQueueSize, columnQueueHaltThreshold,
                    0L, Long.MAX_VALUE,
                    ingestBacklog, ingestBacklogHalt,
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
                    0L, Long.MAX_VALUE, -1, 1000,
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

    // ---- consumer ingest-backlog budget scaling (issue #71) ----

    @Test
    void ingestBacklogTapersTheBudgetLinearly() {
        // Backlog at half the halt threshold halves the budget — the proportional controller
        // whose equilibrium matches the ask rate to the consumer's real drain rate.
        var s = scanner(16);
        var queue = new Sink();
        int n = fireScan(s, 2, 0, 1000, 3072, 6144, 0, new ColumnStateMap(), queue);
        assertEquals(Math.round(LSSConstants.WANT_SET_BUDGET * 0.5f), n,
                "a half-full consumer ingest backlog must halve the want-set budget");
    }

    @Test
    void noSignalIngestBacklogLeavesTheBudgetUntouched() {
        // -1 = no consumer reports (soak, gametests, non-reporting mods, kill switch) and
        // 0 = an EMPTY reported backlog: both must be bit-identical to the pre-#71 budget.
        var s = scanner(16);
        var queue = new Sink();
        assertEquals(LSSConstants.WANT_SET_BUDGET,
                fireScan(s, 2, 0, 1000, -1, 6144, 0, new ColumnStateMap(), queue),
                "-1 (no signal) must not taper");
        assertEquals(LSSConstants.WANT_SET_BUDGET,
                fireScan(s, 2, 0, 1000, 0, 6144, 0, new ColumnStateMap(), queue),
                "0 (empty backlog) must not taper");
    }

    @Test
    void pressureFactorsComposeByMinNotMultiplication() {
        // Both factors gauge the same downstream pipe; multiplying would double-count.
        var s = scanner(16);
        var queue = new Sink();
        // queue factor 0.9, ingest factor 0.25 → the ingest factor wins alone (200, not 180)
        int ingestDominates = fireScan(s, 2, 100, 1000, 4608, 6144, 0, new ColumnStateMap(), queue);
        assertEquals(Math.round(LSSConstants.WANT_SET_BUDGET * 0.25f), ingestDominates,
                "min composition: the tighter (ingest) factor alone must scale the budget");
        // queue factor 0.25, ingest factor 0.9 → the queue factor wins alone
        int queueDominates = fireScan(s, 2, 750, 1000, 615, 6144, 0, new ColumnStateMap(), queue);
        assertEquals(Math.round(LSSConstants.WANT_SET_BUDGET * 0.25f), queueDominates,
                "min composition: the tighter (queue) factor alone must scale the budget");
    }

    @Test
    void ingestBacklogAtOrPastTheHaltFloorsTheBudgetAtOne() {
        // The actual halt lives in tickWithContext (this scanner call is then unreached);
        // if the scanner IS driven at/past the halt anyway, the taper floors at 1 — never 0,
        // never a skipped scan (maybeScan's budget<=0 return stays dead defense).
        var s = scanner(16);
        var queue = new Sink();
        assertEquals(1, fireScan(s, 2, 0, 1000, 6144, 6144, 0, new ColumnStateMap(), queue),
                "backlog at the halt threshold floors the budget at 1");
        assertEquals(1, fireScan(s, 2, 0, 1000, 100_000, 6144, 0, new ColumnStateMap(), queue),
                "an absurd backlog still floors at 1, never negative/zero");
    }

    @Test
    void retryMarkInsideConfirmedDiscReopensTheMarkRing() {
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
        // the retry mark and its invariant are not.) The disc is already confirmed past it.
        // 2026-08-18 (scanner-reopened-rings-plan.md): the actionable mark now reopens exactly
        // ITS ring instead of collapsing the prefix to 0 — the full-disc re-walk per scan was
        // the measured render-thread hitch at large distances.
        SpiralScanner.ringIndexToCoord(3, 0, CX, CZ, c);
        long retried = PositionUtil.packPosition(c[0], c[1]);
        columns.onIngestFailed(retried);

        assertEquals(1, fireScan(s, 2, columns, queue),
                "scan after a retry mark must re-walk the mark's ring and re-declare the retry");
        assertEquals(List.of(retried), queue.positions());
        assertEquals(5, s.getConfirmedRing(),
                "the confirmed prefix SURVIVES — only the mark's ring reopened");
        assertEquals(1, s.getReopenedRingCount(),
                "the unsatisfied reopened ring keeps its bit (the mark is consumed by the"
                        + " ANSWER, so the ring must re-walk until one lands)");

        // The answer consumes the mark and satisfies the position; the next walk finds the
        // ring clean and clears the bit.
        columns.onReceived(retried, 2000L);
        assertEquals(0, fireScan(s, 2, columns, queue));
        assertEquals(0, s.getReopenedRingCount(), "a re-confirmed ring clears its bit");
        assertEquals(5, s.getConfirmedRing());
    }

    @Test
    void retryMarkUnderVanillaExclusionDoesNotResetConfirmedRing() {
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

        // The mark's position sits INSIDE the vanilla-view exclusion (vd 2): the walk skips
        // excluded positions without declaring them, so no terminal answer can ever consume
        // this mark — resetting the ring for it re-walked the full distance EVERY scan for
        // as long as the player lingered (a render-thread hitch per scan at big distances).
        long excluded = PositionUtil.packPosition(CX + 1, CZ);
        columns.onReceived(excluded, 1000L);
        columns.onIngestFailed(excluded);
        assertTrue(columns.hasRetries(), "the mark exists (parked) — it just isn't actionable");

        assertEquals(0, fireScan(s, 2, columns, queue), "nothing declarable");
        assertEquals(5, s.getConfirmedRing(),
                "an unconsumable (vanilla-excluded) retry mark must not reset the confirmed ring");

        assertEquals(0, s.getReopenedRingCount(),
                "a PARKED (unconsumable) mark must not hold a reopened ring — bounded walks"
                        + " (the F1 pin strengthened: no per-scan below-prefix re-walk either)");

        // Heal path: the exclusion is anchored on the player, so once the player moves off,
        // the same mark becomes actionable — the per-scan actionable recomputation reopens
        // (or frontier-covers) its ring at the new center. Predicate level first:
        assertTrue(columns.hasActionableRetries(CX + 10, CZ, 2),
                "the parked mark becomes actionable from a position whose exclusion misses it");
        assertFalse(columns.hasActionableRetries(CX, CZ, 2),
                "still parked from the original center");

        // End-to-end: a +4 crossing (retention path, below the full-reset delta) moves the
        // exclusion off the mark; the next scan declares it.
        s.recenter(4);
        queue.clear();
        int healed = fireScanFull(s, CX + 4, CZ, 2, 0, 1000, 0, columns, queue);
        assertTrue(queue.positions(healed).contains(excluded),
                "the un-parked mark is declared once the exclusion moves off it");
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
    void missingVanillaNoLongerTouchesTheBudget() {
        // The vanilla-load scale is RETIRED (server-side priority/throttling owns that
        // protection under v17; the scale's only observable effect was silently stopping
        // LOD during fast travel). Even a fully missing vanilla disc must not shrink the
        // budget or suppress the walk — the count survives as a diagnostic only.
        var s = scanner(16);
        var queue = new Sink();
        assertEquals(800, fireScan(s, 2, 0, 1000, 25, new ColumnStateMap(), queue),
                "a fully missing vanilla disc declares the full want-set budget");
        assertEquals(25, s.getMissingVanillaChunks(), "the diagnostic count still updates");
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
        int first = s.maybeScan(CX, CZ, 2, 0, 1000, 0L, Long.MAX_VALUE, -1, 1000, () -> 0, new ColumnStateMap(), queue.pos, queue.ts);
        assertEquals(8 * 3 + 8 * 4, first, "first maybeScan on a fresh scanner must fire and queue the annulus");

        // reset() (new session / flushCache) re-primes: again a single call fires.
        s.reset();
        int afterReset = s.maybeScan(CX, CZ, 2, 0, 1000, 0L, Long.MAX_VALUE, -1, 1000, () -> 0, new ColumnStateMap(), queue.pos, queue.ts);
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
    void movementRecenterDecrementsPrefixReopensCrescentKeepsCadenceAndMarks() {
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
            assertEquals(-1, s.maybeScan(CX, CZ, 2, 0, 1000, 0L, Long.MAX_VALUE, -1, 1000, () -> 0, columns, queue.pos, queue.ts));
        }
        s.recenter(1);

        // 2026-08-18 (scanner-reopened-rings-plan.md): a d=1 crossing DECREMENTS the prefix
        // (a ring confirmed at the old center is conservatively confirmed within [r-1, r+1]
        // at the new one) and reopens the view-exclusion crescent band — the full zeroing
        // (and its full-disc re-walk, the measured render-thread hitch) is retired.
        assertEquals(4, s.getConfirmedRing(),
                "movement decrements ring confirmation by the crossing delta, never zeroes it");
        assertEquals(4, s.getReopenedRingCount(),
                "the view-exclusion crescent band [0, viewR+d] reopens below the prefix");
        assertTrue(columns.hasRetries(), "movement preserves in-range retry marks");
        queue.clear();
        int n = s.maybeScan(CX, CZ, 2, 0, 1000, 0L, Long.MAX_VALUE, -1, 1000, () -> 0, columns, queue.pos, queue.ts);
        assertEquals(1, n, "the in-progress cadence window completes ON SCHEDULE through a"
                + " recenter and the scan re-walks the reopened band + frontier, declaring the retry");
        assertEquals(List.of(retried), queue.positions(n));
        assertEquals(0, s.getReopenedRingCount(),
                "the clean crescent band re-confirms and clears in the same walk");
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
        s.reopenRing(3); // stale retained state that must not survive the dimension change
        columns.clear();
        queue.clear();
        s.resetScanCounter();

        assertEquals(0, s.getConfirmedRing(), "dimension change must zero ring confirmation");
        assertEquals(0, s.getReopenedRingCount(),
                "the fresh dimension shares nothing with the old one's ring geometry");
        assertFalse(s.recenteredSinceLastFireForTest(), "retention flags reset with the session state");
        assertFalse(s.truncatedBelowPrefixForTest(), "the truncation flag resets with the session too");
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
        int first = s.maybeScan(CX, CZ, 2, 0, 1000, 0L, Long.MAX_VALUE, -1, 1000, () -> 0, columns, queue.pos, queue.ts);
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
            // 2026-08-18 (the lod-shrink rung, review round): the shrink now RESETS the
            // prefix once and re-anchors it INTO the shrunk regime — a prefix parked
            // beyond the distance walked nothing forever (the stranded-annulus MAJOR;
            // see lodDistanceShrinkResetsThePrefixSoTheOuterAnnulusHeals).
            assertEquals(5, s.getConfirmedRing(), "the shrink re-anchors confirmation inside the new distance");

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

    /**
     * 2026-08-05 review F1: vanilla-excluded (in-view) positions confirm their ring without
     * ever being declared, and nothing used to reset the prefix when the exclusion radius
     * SHRANK — a stationary player dropping render distance left the newly LOD-needing
     * band below the confirmed prefix, structurally unreachable until movement. The walk
     * now resets confirmedRing once per shrink; a grow still resets nothing.
     */
    @Test
    void exclusionRadiusShrinkRescansTheNewlyExposedBand() {
        var columns = new ColumnStateMap();
        var s = scanner(8);
        var queue = new Sink();

        // Satisfy every position OUTSIDE the view-4 exclusion; the in-view ones stay
        // unknown (vanilla renders them, LSS never declared them).
        int[] c = new int[2];
        for (int r = 1; r <= 8; r++) {
            for (int i = 0; i < 8 * r; i++) {
                SpiralScanner.ringIndexToCoord(r, i, CX, CZ, c);
                if (SpiralScanner.isVanillaRendered(c[0], c[1], CX, CZ, 4)) continue;
                long packed = PositionUtil.packPosition(c[0], c[1]);
                columns.onReceived(packed, 1000L);
                columns.onUpToDate(packed);
            }
        }
        assertEquals(0, fireScan(s, 4, columns, queue));
        assertEquals(9, s.getConfirmedRing(), "precondition: full disc confirmed at view 4");

        // A GROW declares nothing and keeps the prefix (newly excluded positions skip free).
        assertEquals(0, fireScan(s, 5, columns, queue), "a grown exclusion declares nothing");
        assertEquals(9, s.getConfirmedRing(), "...and does not reset the prefix");

        // The SHRINK: positions inside the old exclusion but outside the new one are newly
        // LOD-needing and sit below the confirmed prefix — the reset makes them reachable.
        s.reopenRing(7); // a stale reopened bit rides into the shrink to prove the clear
        int declared = fireScan(s, 2, columns, queue);
        assertTrue(declared > 0, "shrunk exclusion re-walks and declares the newly exposed band");
        assertEquals(0, s.getReopenedRingCount(),
                "the shrink's full from-0 re-walk covers every reopened ring — the bitset clears with it");
        for (int i = 0; i < declared; i++) {
            int cx = PositionUtil.unpackX(queue.pos[i]);
            int cz = PositionUtil.unpackZ(queue.pos[i]);
            assertFalse(SpiralScanner.isVanillaRendered(cx, cz, CX, CZ, 2),
                    "declared positions are outside the NEW exclusion");
            assertTrue(SpiralScanner.isVanillaRendered(cx, cz, CX, CZ, 5),
                    "...and were hidden inside the OLD one");
            assertEquals(-1L, queue.ts[i], "never-served band re-asks as unknown");
        }

        // Serve the band; the disc re-converges and a repeat scan at the same radius is a
        // clean no-op (the reset fires once per change, not per scan).
        for (int i = 0; i < declared; i++) {
            columns.onReceived(queue.pos[i], 2000L);
            columns.onUpToDate(queue.pos[i]);
        }
        assertEquals(0, fireScan(s, 2, columns, queue), "band served: converged again");
        assertEquals(9, s.getConfirmedRing());
        assertEquals(0, fireScan(s, 2, columns, queue), "steady radius: no repeated reset churn");
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
        s.reopenRing(2); // production dirty-broadcast path (LodRequestManager.onDirtyColumns
                         // reopens the position's own ring — cheb 2 from the center)

        assertEquals(0, fireScan(s, 2, columns, queue),
                "a dirty column under vanilla coverage must not be re-requested (vanilla renders it live)");
        assertEquals(1, columns.dirtyCount(), "the mark parks instead of being consumed or dropped");
        assertEquals(0, fireScan(s, 2, columns, queue), "stays parked while covered");
        assertEquals(1, columns.dirtyCount());

        // The player moves +1 chunk: the exclusion square moves off the dirty column.
        columns.pruneOutOfRange(1, 0, 64); // production movement order: prune...
        s.recenter(1);                     // ...then the movement recenter (crescent band
                                           // reopens the rings that exited the view circle)
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
                // missingVanilla no longer affects the budget (the vanilla-load scale is
                // retired); the randomized value here now only exercises the diagnostic
                // counter while queue pressure supplies the budget variation.
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
    // exactly that). The below-prefix reachability CONTRACT still has to hold — production
    // reaches it via reopenRing from consumeStaleCrossing and the dirty batch handler
    // (2026-08-18, scanner-reopened-rings-plan.md: resetConfirmedRing is retired; the ring
    // reopen replaces the full prefix collapse) — so these two tests stage the stranded state
    // directly instead of through the retired in-flight-skip.

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
    void dirtiedPositionBelowConfirmedRingIsRereachedByReopenRing() {
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

        // The fix re-walks the position's OWN ring below the prefix, re-reaching it
        // (2026-08-18: reopenRing replaces the retired full prefix collapse).
        s.reopenRing(1);
        assertEquals(1, s.getReopenedRingCount(), "the ring bit is set below the prefix");
        int recount = fireScanFull(s, CX, CZ, 0, 0, 1000, 0, columns, queue);
        assertEquals(1, recount, "reopenRing re-walks and re-emits the re-opened position");
        assertEquals(List.of(target), queue.positions());
        assertTrue(s.getConfirmedRing() > 1,
                "the confirmed prefix survives the below-prefix re-walk");
    }

    @Test
    void notGeneratedPositionStaysParkedThroughARingReopen() {
        var s = new SpiralScanner();
        s.setConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, 1, false));
        var columns = new ColumnStateMap();
        var queue = new Sink();
        long target = stageRing1Confirmed(s, columns, queue);

        columns.onNotGenerated(target);
        s.reopenRing(1);
        // A NOT_GENERATED position is permanently session-satisfied, so even a full re-walk
        // of its ring must NOT re-request it — only a dirty broadcast revives it (no
        // re-request loop).
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

    // ---- adaptive cadence: the completion-triggered fast re-scan (fastRescanDue) ----
    // docs/planning/adaptive-scan-cadence-design.md. Every rig above stays bit-identical:
    // bare scanners have no outstanding supplier, so the fast path is structurally off there.

    /**
     * Adaptive rig: the supplier + kill-switch seams wired, arming performed manually (in
     * production it is {@code tickScanPhase}'s job, beside the tracker replace — a scanner
     * driven without it, like this file's other rigs, never arms).
     */
    private static final class AdaptiveRig {
        final SpiralScanner s;
        final Sink q = new Sink();
        final ColumnStateMap columns = new ColumnStateMap();
        int outstanding;
        int missingVanillaCalls;

        AdaptiveRig() { this(scanner(4)); }

        AdaptiveRig(SpiralScanner scanner) {
            this.s = scanner;
            this.s.adaptiveCadenceEnabled = () -> true;
            this.s.setOutstandingSupplier(() -> this.outstanding);
        }

        /** First periodic fire (declares the lod-4/vd-2 annulus, 56) + arm with its count. */
        int primeAndArm() {
            int declared = fireScan(this.s, 2, this.columns, this.q);
            this.s.noteDeclared(declared);
            return declared;
        }

        int tickOnce() { return tickOnce(0, 1000, 0L, 1L << 30, -1, 1000); }

        int tickOnce(int queueSize, int queueHalt, long queueBytes, long queueByteHalt,
                     int ingest, int ingestHalt) {
            int n = this.s.maybeScan(CX, CZ, 2, queueSize, queueHalt, queueBytes, queueByteHalt,
                    ingest, ingestHalt,
                    () -> { this.missingVanillaCalls++; return 0; },
                    this.columns, this.q.pos, this.q.ts);
            if (n >= 0) this.q.count = Math.max(n, 0);
            return n;
        }

        /** Drives until the next fire; returns the 1-based tick it fired on (fails past 20). */
        int ticksToFire() { return ticksToFire(0, 1000, 0L, 1L << 30, -1, 1000); }

        int ticksToFire(int queueSize, int queueHalt, long queueBytes, long queueByteHalt,
                        int ingest, int ingestHalt) {
            for (int t = 1; t <= LSSConstants.TICKS_PER_SECOND; t++) {
                if (tickOnce(queueSize, queueHalt, queueBytes, queueByteHalt, ingest, ingestHalt) >= 0) return t;
            }
            throw new AssertionError("cadence never fired within a fallback window");
        }
    }

    @Test
    void fastRescanFiresAtTheFloorOnceTheBatchIsNearlyAnswered() {
        var rig = new AdaptiveRig();
        assertTrue(rig.primeAndArm() > 0);
        assertFalse(rig.s.wasLastScanFast(), "the primed first fire is periodic");
        rig.outstanding = 0; // everything answered

        for (int t = 1; t < SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS; t++) {
            assertEquals(-1, rig.tickOnce(), "no fire below the floor (tick " + t + ")");
        }
        assertTrue(rig.tickOnce() >= 0, "fast fire exactly at the floor");
        assertTrue(rig.s.wasLastScanFast(), "...and it reports as fast");
        assertEquals(1, rig.s.getFastScans());
    }

    @Test
    void outstandingAboveThresholdKeepsThePeriodicCadence() {
        var rig = new AdaptiveRig();
        rig.outstanding = rig.primeAndArm(); // nothing answered
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(),
                "an unanswered batch never fast-fires — the fallback owns the cadence"
                        + " (this is also why churny soak phases see today's exact 1 Hz:"
                        + " silent server drops never remove positions from the awaiting set)");
        assertFalse(rig.s.wasLastScanFast());
        assertEquals(0, rig.s.getFastScans());
    }

    @Test
    void thresholdIsFivePercentOfTheDeclaredCount() {
        var rig = new AdaptiveRig();
        rig.primeAndArm();
        rig.s.noteDeclared(800);
        rig.outstanding = 41;
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(),
                "41 of 800 outstanding is above 5% — periodic");
        rig.s.noteDeclared(800);
        rig.outstanding = 40;
        assertEquals(SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS, rig.ticksToFire(),
                "40 of 800 is exactly 5% — fast");
    }

    @Test
    void tinyDeclaresDegenerateToStrictZeroOutstanding() {
        var rig = new AdaptiveRig();
        rig.primeAndArm();
        rig.s.noteDeclared(SpiralScanner.FAST_RESCAN_OUTSTANDING_DIVISOR - 1); // threshold 0
        rig.outstanding = 1;
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(),
                "integer threshold: a sub-20 declare requires FULL drain");
        rig.s.noteDeclared(SpiralScanner.FAST_RESCAN_OUTSTANDING_DIVISOR - 1);
        rig.outstanding = 0;
        assertEquals(SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS, rig.ticksToFire());
    }

    @Test
    void geometricTighteningShrinksTheThresholdWithTheDeclare() {
        // The anti-chatter property: a fast walk that re-declares only ~30 stragglers
        // shrinks the next threshold to 1 — sustained 4 Hz needs a genuinely refilling
        // frontier, never a parked straggler set.
        var rig = new AdaptiveRig();
        rig.primeAndArm();
        rig.s.noteDeclared(30);
        rig.outstanding = 2;
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(), "2 > 30/20 — periodic");
        rig.s.noteDeclared(30);
        rig.outstanding = 1;
        assertEquals(SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS, rig.ticksToFire(), "1 <= 30/20 — fast");
    }

    @Test
    void decodeQueueGateBlocksAtAQuarterOfItsHaltThreshold() {
        // Proportional, deliberately NOT strict zero: received columns enter the decode
        // queue in the same handler that empties the awaiting set, so a zero gate would
        // suppress the fast path in exactly the data-bearing warm backfill it exists for.
        var rig = new AdaptiveRig();
        rig.primeAndArm();
        rig.outstanding = 0;
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(250, 1000, 0L, 1L << 30, -1, 1000),
                "queue at exactly 1/4 of its halt threshold blocks the fast fire");
        rig.s.noteDeclared(rig.q.count);
        rig.outstanding = 0;
        assertEquals(SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS,
                rig.ticksToFire(249, 1000, 0L, 1L << 30, -1, 1000),
                "just below the 1/4 line the fast fire proceeds");
    }

    @Test
    void byteQueueGateBlocksAtAQuarterOfItsHaltThreshold() {
        // The byte pipe is the halt term that actually BINDS for real terrain columns
        // (columns > ~32 KiB hit the byte halt before the count halt), so the fast gate
        // must cover it too — a count-only gate left the fast path open at 74% of the
        // binding halt (implementation-review finding).
        var rig = new AdaptiveRig();
        rig.primeAndArm();
        rig.outstanding = 0;
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(0, 1000, 250L, 1000L, -1, 1000),
                "queued bytes at exactly 1/4 of the byte-halt threshold block the fast fire");
        rig.s.noteDeclared(rig.q.count);
        rig.outstanding = 0;
        assertEquals(SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS,
                rig.ticksToFire(0, 1000, 249L, 1000L, -1, 1000),
                "just below the 1/4 line the fast fire proceeds");
    }

    @Test
    void ingestBacklogGateBlocksAtAQuarterOfItsHaltThreshold() {
        var rig = new AdaptiveRig();
        rig.primeAndArm();
        rig.outstanding = 0;
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(0, 1000, 0L, 1L << 30, 250, 1000),
                "consumer ingest backlog at 1/4 of its halt threshold blocks the fast fire");
        rig.s.noteDeclared(rig.q.count);
        rig.outstanding = 0;
        assertEquals(SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS,
                rig.ticksToFire(0, 1000, 0L, 1L << 30, 249, 1000),
                "below the line it proceeds (and -1 no-signal always passes — every other test here)");
    }

    @Test
    void convergedWalkDisarmsTheFastCadence() {
        // THE safety property: without the disarm a converged client would re-walk the
        // full spiral every 250 ms forever (and the walk-fires would still send nothing,
        // but burn the render thread). The manager notes the 0-count declare; after it,
        // only the 1 Hz fallback walks.
        var rig = new AdaptiveRig();
        rig.primeAndArm();
        seedSatisfied(rig.columns, 3, 4); // the whole declarable annulus answered
        rig.outstanding = 0;
        assertEquals(SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS, rig.ticksToFire(),
                "the fast fire itself still happens...");
        assertEquals(0, rig.q.count, "...but the converged walk declares nothing");
        rig.s.noteDeclared(0); // the manager's converged disarm (tickScanPhase)
        rig.outstanding = 0;
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(),
                "disarmed: a converged client re-walks at the fallback cadence only");
    }

    @Test
    void anUnarmedScannerNeverFastFires() {
        var rig = new AdaptiveRig();
        fireScan(rig.s, 2, rig.columns, rig.q); // a walk ran, but nothing was ever declared to us
        rig.outstanding = 0;
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(),
                "no noted declare = nothing to complete = periodic only");
    }

    @Test
    void killSwitchRestoresThePeriodicCadence() {
        var rig = new AdaptiveRig();
        rig.primeAndArm();
        rig.s.adaptiveCadenceEnabled = () -> false;
        rig.outstanding = 0;
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(),
                "enableAdaptiveScanCadence=false is bit-identical pre-change behavior");
    }

    @Test
    void v16SessionsNeverFastFire() {
        // Non-optional: besides the legacy server's real rate limiter, Tier B's
        // NOT_GENERATED handling removes gen-slot-bounced positions from the awaiting set,
        // so a v16 session would fast-fire on exactly that churn loop and hammer the old
        // server's generation slots at 4 Hz.
        var legacy = new SpiralScanner();
        legacy.setConfig(new SessionConfigS2CPayload(
                LSSConstants.V16_COMPAT_PROTOCOL_VERSION, true, 4, true));
        var rig = new AdaptiveRig(legacy);
        rig.primeAndArm();
        rig.outstanding = 0;
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(),
                "a legacy (protocol-16) session keeps the 1 Hz cadence bit-identically");
    }

    @Test
    void nullSessionConfigTicksAreSafeWithTheFastPredicateArmed() {
        // The fast predicate runs on ticks 5..19, which never touched sessionConfig before;
        // production sets the config before any scan, but the guard must hold regardless.
        var rig = new AdaptiveRig(new SpiralScanner()); // setConfig never called
        rig.s.resetScanCounter(); // un-prime: a fresh scanner's first tick would fire a WALK (NPE pre-config)
        rig.s.noteDeclared(56);   // (re-)arm after the reset's disarm
        rig.outstanding = 0;
        for (int t = 1; t < LSSConstants.TICKS_PER_SECOND; t++) {
            assertEquals(-1, rig.tickOnce(), "pre-config ticks must neither NPE nor fast-fire");
        }
    }

    @Test
    void cheapPrefixInvalidationNoLongerHoldsTheFastPath() {
        // THE regression the walk-cost gate rewrite exists to fix (elytra-chunk-wall
        // investigation §8.6.3). recenter() zeroes the confirmed prefix on EVERY chunk
        // crossing, and the old `confirmedRing > 0` term read that as "expensive walk".
        // At 33 blocks/s crossings run ~2.76 Hz against 1 Hz scans, so a moving client
        // never fast-fired at all — measured as exactly 1.000 s gaps for 23 consecutive
        // seconds of flight. A small disc's re-walk costs ~80 iterations; cost decides now,
        // so it fires at the floor.
        var rig = new AdaptiveRig();
        rig.primeAndArm();
        rig.outstanding = 0;
        rig.s.recenter(1); // movement (a one-chunk crossing)
        assertEquals(SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS, rig.ticksToFire(),
                "post-movement: a CHEAP re-walk fast-fires (was pinned to 1 Hz — the bug)");

        rig.s.reopenRing(3); // dirty re-open, same shape (2026-08-18: per-ring, not prefix collapse)
        rig.outstanding = 0;
        assertEquals(SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS, rig.ticksToFire(),
                "post-dirty-reopen: same — the COST decides, not the fact of invalidation");
    }

    @Test
    void expensivePrefixInvalidationStillRidesTheFallback() {
        // The half of the old gate worth keeping: a from-ring-0 re-walk across a disc that
        // is satisfied far out is the render-thread-hitch shape, and must stay at 1 Hz.
        // Deliberate policy — such a disc is both expensive to walk AND has little left to
        // fetch, so the fallback is the right cadence there.
        var rig = new AdaptiveRig(scanner(200));
        // Ring 141 alone (1128 positions) overruns the budget, so the walk TRUNCATES there:
        // scanRing = confirmedRing = 141, and the post-recenter prediction is 4*141*142.
        seedSatisfiedDisc(rig.columns, 140);
        rig.primeAndArm();
        rig.outstanding = 0;
        assertTrue(rig.s.predictedWalkCost() <= SpiralScanner.FAST_RESCAN_MAX_WALK_COST,
                "premise: with the prefix intact the frontier-only walk is cheap");

        rig.s.recenter(1);
        // 2026-08-18 (scanner-reopened-rings-plan.md §3): in the movement window
        // (recenteredSinceLastFire) the prediction is the bare from-zero formula — the
        // retained prefix must not LOWER the movement-window prediction, or fast fires
        // would walk back toward the elytra wall's throughput regime.
        assertTrue(rig.s.predictedWalkCost() > SpiralScanner.FAST_RESCAN_MAX_WALK_COST,
                "premise: the movement window predicts the expensive from-zero re-walk");
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(),
                "an expensive from-ring-0 re-walk still rides the 1 Hz fallback");
    }

    @Test
    void walkCostIsPredictedForTheNextWalkNotRememberedFromTheLast() {
        // Plan review round 1: a REMEMBERED cost gates the next walk with the last walk's
        // price, and those differ exactly here — after recenter() the next walk restarts at
        // ring 0 while the last one started at the frontier. A remembered gate would admit
        // one full-price walk after every prefix collapse. The prediction must jump the
        // moment the prefix is zeroed, before any walk has run.
        var rig = new AdaptiveRig(scanner(200));
        seedSatisfiedDisc(rig.columns, 140);
        rig.primeAndArm();
        int frontierWalk = rig.s.predictedWalkCost();

        rig.s.recenter(1); // no walk has happened yet
        assertTrue(rig.s.predictedWalkCost() > frontierWalk,
                "the prediction reflects the re-walk immediately, with no walk in between");
    }

    @Test
    void untruncatedWalkPredictsToTheLodDistanceNotTheLastQueuedRing() {
        // Implementation-review MAJOR A-1. scan()'s ONLY early exit is the budget break;
        // otherwise it iterates every ring out to lodDistance, while scanRing records merely
        // the outermost ring that QUEUED something. On a warm disc — the shipped server's
        // regime — a moving client finds work only in the trailing view-edge crescents near
        // ring ~viewDistance, so scanRing stays tiny while the walk still examines the whole
        // disc. Predicting off scanRing there under-reports by orders of magnitude and admits
        // exactly the expensive walk this gate exists to refuse.
        var rig = new AdaptiveRig(scanner(150));
        seedSatisfiedDiscExceptRing(rig.columns, 150, 12); // full disc warm, one crescent ring open
        rig.primeAndArm();
        rig.outstanding = 0;

        assertTrue(rig.s.getConfirmedRing() <= 12,
                "premise: the prefix stops at the open crescent ring");
        // The walk queued only ~96 positions — far under budget — so it ran to ring 150.
        // Predicting off scanRing (~12) would give ~96 and open the gate.
        assertTrue(rig.s.predictedWalkCost() > SpiralScanner.FAST_RESCAN_MAX_WALK_COST,
                "an UNtruncated walk must be predicted against the LOD distance it actually"
                        + " iterates (4*150*151 = 90,600), not the last queued ring");
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(),
                "...so a full warm-disc sweep still rides the 1 Hz fallback");
    }

    @Test
    void walkCostCalibrationAdmitsTheMeasuredFlightWalkAndRefusesTheWarmFullDisc() {
        // The constant's entire rationale, pinned. Its javadoc cites both numbers; nothing
        // evaluated either, and at the rings the rest of the suite exercises the correct
        // 4R(R+1) and the WRONG 4R^2 agree on the verdict — so the arithmetic error that
        // inverted the first draft's threshold (262,144 refusing the live server's own
        // lod-256 walk at 263,168) was invisible to tests.
        var flight = new AdaptiveRig(scanner(200));
        // Rings 0..73 satisfied; ring 74 (592 positions) fits inside the 800 budget and
        // ring 75 overruns it, so the walk truncates IN ring 75 => scanRing = 75, the
        // frontier the elytra trace measured.
        seedSatisfiedDisc(flight.columns, 73);
        flight.primeAndArm();
        flight.s.recenter(1);
        assertEquals(4 * 75 * 76, flight.s.predictedWalkCost(),
                "the measured elytra flight walk is exactly 4R(R+1) at frontier ring 75");
        assertTrue(flight.s.predictedWalkCost() <= SpiralScanner.FAST_RESCAN_MAX_WALK_COST,
                "...and it MUST be admitted — unlocking it is the whole point of the change");

        var warm = new AdaptiveRig(scanner(256));
        assertTrue(4 * 256 * 257 > SpiralScanner.FAST_RESCAN_MAX_WALK_COST,
                "a full walk at the default 256 LOD distance (263,168) must be refused;"
                        + " 4R^2 would give 262,144 and sit on the wrong side of the constant");
        seedSatisfiedDiscExceptRing(warm.columns, 256, 12);
        warm.primeAndArm();
        warm.outstanding = 0;
        assertEquals(LSSConstants.TICKS_PER_SECOND, warm.ticksToFire(),
                "a warm full-disc sweep rides the 1 Hz fallback");
    }

    /** Seeds a satisfied square of {@code radius} but leaves one Chebyshev ring open — the
     *  trailing view-edge crescent shape a moving client sees on warm terrain. */
    private static void seedSatisfiedDiscExceptRing(ColumnStateMap columns, int radius, int openRing) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == openRing) continue;
                columns.onReceived(PositionUtil.packPosition(CX + dx, CZ + dz), 5000L);
            }
        }
    }

    /** Marks every column in a square of {@code radius} around the rig centre as received
     *  (SATISFIED), so a walk must traverse them before it finds work. */
    private static void seedSatisfiedDisc(ColumnStateMap columns, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                columns.onReceived(PositionUtil.packPosition(CX + dx, CZ + dz), 5000L);
            }
        }
    }

    @Test
    void resetScanCounterDisarmsAndKeepsTheDimensionChangeWait() {
        var rig = new AdaptiveRig();
        rig.primeAndArm();
        rig.s.resetScanCounter(); // dimension change
        rig.outstanding = 0; // the fresh dimension's awaiting set is empty — trivially "drained"
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(),
                "the deliberate post-dimension-change 20-tick wait cannot be bypassed by a"
                        + " stale armed count over the new dimension's empty awaiting set");
        // The fire above re-derived the prefix (ring 0 confirms trivially), so the
        // walk-cost gate is now OPEN — only the disarm can be holding the fast path.
        // Without re-arming, the next window must still be periodic: this half is what
        // makes the DISARM assertion non-vacuous (the first window was held by both).
        rig.outstanding = 0;
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(),
                "resetScanCounter() disarmed: no fast fire until the manager arms a fresh declare");
    }

    @Test
    void actionableRetryMarksHoldFastFiresLikeAnyPrefixInvalidation() {
        // The IN-WALK prefix reset: an actionable retry mark zeroes the prefix inside
        // scan() — after the gate's confirmedRing read — and every walk re-derives
        // confirmedRing >= 1, so the field alone can never see it (all three
        // implementation reviewers' shared MAJOR). The gate consults hasActionableRetries
        // directly: retry-forced from-ring-0 re-walks ride the 1 Hz fallback, exactly
        // like movement and dirty re-opens.
        var rig = new AdaptiveRig();
        rig.primeAndArm();
        rig.outstanding = 0;
        long target = PositionUtil.packPosition(3, 0); // in the declared annulus (outside vd 2)
        rig.columns.onReceived(target, 5000L);
        rig.columns.onIngestFailed(target); // consumer rejection: unstamp + retry mark
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(),
                "an actionable retry mark holds the fast cadence (its walk re-walks from ring 0)");
        // The mark is answer-consumed, so it survives the walk — the hold persists...
        rig.s.noteDeclared(rig.q.count);
        rig.outstanding = 0;
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(),
                "still held while the mark is unconsumed");
        // ...until the re-serve lands and consumes it.
        rig.columns.onReceived(target, 6000L);
        rig.s.noteDeclared(rig.q.count);
        rig.outstanding = 0;
        assertEquals(SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS, rig.ticksToFire(),
                "mark consumed: the fast cadence resumes");
    }

    @Test
    void adaptiveCadenceConstantsArePinned() {
        // Numeric pins (implementation-review M4): every behavioral test references these
        // symbolically, so a constant drift (e.g. floor 5 -> 1 = a 20 Hz / 5x-upstream
        // ceiling) would pass the whole suite green without this.
        assertEquals(5, SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS,
                "250 ms floor — the 4 Hz ceiling every cost bound (C2S upstream, walk rate) assumes");
        assertEquals(20, SpiralScanner.FAST_RESCAN_OUTSTANDING_DIVISOR, "the 5% completion threshold");
        assertEquals(4, SpiralScanner.FAST_RESCAN_PRESSURE_DIVISOR,
                "fast fires only below 1/4 of each halt threshold (count, bytes, ingest)");
        assertEquals(65_536, SpiralScanner.FAST_RESCAN_MAX_WALK_COST,
                "walk-cost ceiling — calibrated from the measured 22,800-iteration flight walk"
                        + " (4R(R+1) at frontier ring 75); a drift upward re-admits the"
                        + " full-256-disc walk (263,168) at 4 Hz on the render thread");
    }

    @Test
    void resetRestoresTheWalkCostPredictionWithTheRestOfTheSessionState() {
        // reset() zeroes confirmedRing AND scanRing, so the prediction must come back as a
        // fresh-session zero rather than carrying the old dimension's frontier.
        var rig = new AdaptiveRig(scanner(200));
        seedSatisfiedDisc(rig.columns, 140);
        rig.primeAndArm();
        rig.s.recenter(1);
        assertTrue(rig.s.predictedWalkCost() > SpiralScanner.FAST_RESCAN_MAX_WALK_COST,
                "premise: an expensive prediction is standing");
        rig.s.reset();
        assertTrue(rig.s.predictedWalkCost() > SpiralScanner.FAST_RESCAN_MAX_WALK_COST,
                "a fresh session has never walked, so it must predict the FULL disc and fail"
                        + " closed — never inherit a cheap stale frontier");
        assertEquals(0, rig.s.getReopenedRingCount(),
                "retained rings die with the session (2026-08-18 retention state)");
        assertFalse(rig.s.recenteredSinceLastFireForTest(), "reset() closes the movement window");
        assertFalse(rig.s.truncatedBelowPrefixForTest(), "reset() clears the truncation flag");
    }

    @Test
    void resetDisarmsAndZeroesTheSessionCounters() {
        var rig = new AdaptiveRig();
        rig.primeAndArm();
        rig.outstanding = 0;
        assertEquals(SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS, rig.ticksToFire(), "armed: fast");
        assertEquals(1, rig.s.getFastScans());

        rig.s.reset(); // new session
        assertEquals(0, rig.s.getFastScans(), "session counter zeroed");
        assertFalse(rig.s.wasLastScanFast());
        rig.outstanding = 0;
        assertEquals(1, rig.ticksToFire(), "reset() keeps the primed immediate first scan...");
        assertFalse(rig.s.wasLastScanFast(), "...which is periodic");
        rig.outstanding = 0;
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(),
                "reset() disarmed: no fast fire until the manager arms a fresh declare");
    }

    @Test
    void aFastFireRestartsTheFallbackWindow() {
        var rig = new AdaptiveRig();
        rig.primeAndArm();
        rig.outstanding = 0;
        assertEquals(SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS, rig.ticksToFire(), "fast fire");
        rig.s.noteDeclared(rig.q.count); // the fast walk re-declared the unsatisfied annulus
        rig.outstanding = rig.q.count;   // and this time nothing gets answered
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(),
                "the fallback measures from the LAST fire: 20 ticks after the fast one, not 15"
                        + " — 'more than 1 s since the last batch' is the fallback's contract");
    }

    @Test
    void fastFiresSkipTheMissingVanillaProbe() {
        // The probe is an O((2*vd+1)^2) hasChunk sweep feeding a diagnostic-only field —
        // fast fires keep the last periodic value instead of 4x-ing it.
        var rig = new AdaptiveRig();
        assertEquals(1, rig.ticksToFire(), "the primed first fire lands on tick 1 (periodic)");
        assertEquals(1, rig.missingVanillaCalls, "the periodic fire evaluates the probe");
        rig.s.noteDeclared(rig.q.count);
        rig.outstanding = 0;
        assertEquals(SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS, rig.ticksToFire());
        assertEquals(1, rig.missingVanillaCalls, "the fast fire kept the last value");
    }

    // ---- manual column-rate cap (docs/planning/client-column-rate-cap-design.md) ----
    // The dial is columns/SEC, not batch size: budget clamp bounds the burst, the
    // size-weighted fast-fire spacing bounds the sustained rate, and the 1 Hz fallback —
    // the want-set's only self-heal — is never gated. Default (cap 0) is bit-identical:
    // every rig above runs with the seam at its production default reading the test
    // config's 0.

    @Test
    void capZeroIsBitIdenticalToNoCap() {
        // Explicit cap-0 seam: full batch armed, everything answered → fast fire at the
        // floor, exactly the un-capped fastRescanFiresAtTheFloor... behavior, and the
        // budget stays the constant.
        var rig = new AdaptiveRig(scanner(16));
        rig.s.columnRateCap = () -> 0;
        assertEquals(LSSConstants.WANT_SET_BUDGET, rig.primeAndArm(),
                "cap 0 must not touch the budget");
        rig.outstanding = 0;
        assertEquals(SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS, rig.ticksToFire(),
                "cap 0 must not space fast fires");
        assertEquals(0, rig.s.getRateGated());
    }

    @Test
    void capClampsTheBudgetAndTaperScalesTheCappedBase() {
        // Burst half: budget = min(WANT_SET_BUDGET, cap), and the #71 taper MIN-composes by
        // scaling the already-clamped base (cap 600 x queue-scale 0.5 = 300 — not 400, not
        // scale-then-min).
        var s = scanner(16);
        s.columnRateCap = () -> 300;
        var queue = new Sink();
        assertEquals(300, fireScan(s, 2, new ColumnStateMap(), queue),
                "cap below the constant budget must win");

        var s2 = scanner(16);
        s2.columnRateCap = () -> 600;
        assertEquals(300, fireScan(s2, 2, 500, 1000, 0, new ColumnStateMap(), queue),
                "the pressure scale applies AFTER the cap clamp: 0.5 x min(800, 600) = 300");
    }

    @Test
    void spacingGateChargesTheLastBatchAgainstTheCap() {
        // Sustained half: after declaring N, the next FAST fire waits ceil(20*N/R) ticks.
        // N=800 at R=1600 → 10 ticks; the boundary is exact (10*1600 == 20*800 admits).
        // rateGated counts exactly the ticks where the cap was the binding refusal:
        // ticks 1-4 are floor-gated (no increment), 5-9 rate-gated.
        var rig = new AdaptiveRig(scanner(16));
        rig.s.columnRateCap = () -> 1600;
        rig.primeAndArm();
        rig.s.noteDeclared(800);
        rig.outstanding = 0;
        assertEquals(10, rig.ticksToFire(),
                "800 declared at cap 1600/s must space the fast fire to 10 ticks");
        assertTrue(rig.s.wasLastScanFast(), "...and it is still a FAST fire, just spaced");
        assertEquals(5, rig.s.getRateGated(),
                "ticks 5-9 were refused by the spacing gate alone");
    }

    @Test
    void governedSeamSplitEquilibratesAtFourHertz() {
        // The governed shape (plan review M2 + impl review M2's missing pin): the
        // BURST cap ceil(R/4) at the budget site with R at the spacing site prices a
        // declared quarter-batch to exactly the 5-tick floor — 4 Hz quarter-batches.
        // The single-supplier revert this pin exists to red (same R at both sites)
        // spaces to 20 ticks: 1 Hz full-second bursts, the shape that grazes
        // Mechanism B's 750 ms threshold and consumes the A/B separation margin.
        var rig = new AdaptiveRig(scanner(16));
        rig.s.columnRateCap = () -> 160;   // sustained site: R
        rig.s.columnBurstCap = () -> 40;   // budget site: ceil(R/4)
        assertEquals(40, rig.primeAndArm(),
                "the budget clamp reads the BURST cap, not the sustained rate");
        rig.s.noteDeclared(40);
        rig.outstanding = 0;
        assertEquals(SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS, rig.ticksToFire(),
                "40 declared at R=160 prices to exactly the floor — 4 Hz quarter-batches");
    }

    @Test
    void smallTailBatchesStillRideTheFloor() {
        // The converging-tail sparkle survives: N=10 at R=100 prices to 2 ticks → the
        // 5-tick floor binds, and the floor ticks never count as rate-gated.
        var rig = new AdaptiveRig();
        rig.s.columnRateCap = () -> 100;
        rig.primeAndArm();
        rig.s.noteDeclared(10);
        rig.outstanding = 0;
        assertEquals(SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS, rig.ticksToFire(),
                "a tail batch under the cap's price stays at the 250 ms floor");
        assertEquals(0, rig.s.getRateGated());
    }

    @Test
    void capThirtyTwoHundredIsTheContinuityPoint() {
        // Today's max sustained is 800 x 4 Hz = 3200/s: a full batch at R=3200 spaces to
        // exactly the existing floor, so current behavior IS the R=3200 point of this
        // family (and the Sodium slider's top is honest).
        var rig = new AdaptiveRig(scanner(16));
        rig.s.columnRateCap = () -> 3200;
        rig.primeAndArm();
        rig.s.noteDeclared(800);
        rig.outstanding = 0;
        assertEquals(SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS, rig.ticksToFire(),
                "20*800/3200 = 5 = the floor: R=3200 must change nothing");
        assertEquals(0, rig.s.getRateGated(), "the boundary tick admits (== is not <)");
    }

    @Test
    void theFallbackIsNeverGatedByTheCap() {
        // THE safety property: re-declaration is the sole self-heal for silent server-side
        // drops, so the 20-tick fallback must fire regardless of any cap/batch combination.
        // N=800 at R=50 prices a fast fire at 320 ticks — the fallback still fires at 20,
        // and as a PERIODIC fire.
        var rig = new AdaptiveRig(scanner(16));
        rig.s.columnRateCap = () -> 50;
        rig.primeAndArm();
        rig.s.noteDeclared(800);
        rig.outstanding = 0;
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(),
                "the fallback fires on schedule no matter how far the cap spaced the fast path");
        assertFalse(rig.s.wasLastScanFast(), "...and it is the periodic fire, not a late fast one");
    }

    @Test
    void convergedDisarmStillHoldsUnderACap() {
        // The disarm ladder is orthogonal to the cap: a 0-count declaration (the manager's
        // converged disarm) keeps the 1 Hz fallback even though the spacing gate would
        // price a 0-batch at 0 ticks.
        var rig = new AdaptiveRig();
        rig.s.columnRateCap = () -> 100;
        rig.primeAndArm();
        rig.s.noteDeclared(0);
        rig.outstanding = 0;
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(),
                "disarmed stays disarmed — the cap must not create a fast path");
    }

    @Test
    void theDefaultSeamReadsTheProductionConfigField() {
        // Review finding 1: every other cap test overrides the seam, so a typo'd field
        // read (or a dropped lambda) in the default would keep the whole suite green while
        // the live knob went dead. Same save/restore pattern as the adaptive kill switch's
        // production-binding pin in LodRequestManagerTickTest.
        int old = LSSClientConfig.CONFIG.lodColumnsPerSecondLimit;
        LSSClientConfig.CONFIG.lodColumnsPerSecondLimit = 300;
        try {
            var s = scanner(16); // default seam untouched
            var queue = new Sink();
            assertEquals(300, fireScan(s, 2, new ColumnStateMap(), queue),
                    "a fresh scanner must bind the cap from the production config field");
        } finally {
            LSSClientConfig.CONFIG.lodColumnsPerSecondLimit = old;
        }
    }

    @Test
    void rateGatedDoesNotCountTicksWhereOutstandingWasTheRefusal() {
        // Review finding 2: the cap gate sits LAST in the ladder so rateGated counts
        // exactly the ticks where the cap was the BINDING refusal. A reorder ahead of the
        // outstanding check would corrupt the diag discriminator ("nonzero = the knob is
        // binding") by counting ticks the 5% threshold was already refusing — here, ticks
        // 5-9 have unpaid spacing (t*1600 < 20*800) but an unanswered batch, and must not
        // count.
        var rig = new AdaptiveRig(scanner(16));
        rig.s.columnRateCap = () -> 1600;
        rig.primeAndArm();
        rig.s.noteDeclared(800);
        rig.outstanding = 800; // nothing answered: the completion threshold refuses first
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(),
                "an unanswered batch keeps the fallback cadence, cap or no cap");
        assertEquals(0, rig.s.getRateGated(),
                "unpaid spacing behind an outstanding refusal must not count as rate-gated");
    }

    @Test
    void deepTaperUnderACapStillFloorsTheBudgetAtOne() {
        // The max(1, ...) floor applies to the CAPPED base: cap + at-halt backlog must
        // yield 1, never 0 (a skipped scan) — the capped twin of
        // ingestBacklogAtOrPastTheHaltFloorsTheBudgetAtOne.
        var s = scanner(16);
        s.columnRateCap = () -> 300;
        var queue = new Sink();
        assertEquals(1, fireScan(s, 2, 0, 1000, 6144, 6144, 0, new ColumnStateMap(), queue),
                "backlog at the halt threshold floors the capped budget at 1");
    }

    @Test
    void v16SessionsGetTheBudgetClampButNeverTheFastPath() {
        // Design §6: on a legacy session only the budget clamp applies. The cap must not
        // create a fast path where the v16 exclusion forbids one, and the exclusion sits
        // before the cap gate, so none of the refusals count as rate-gated.
        var legacy = new SpiralScanner();
        legacy.setConfig(new SessionConfigS2CPayload(
                LSSConstants.V16_COMPAT_PROTOCOL_VERSION, true, 16, true));
        legacy.columnRateCap = () -> 300;
        var rig = new AdaptiveRig(legacy);
        assertEquals(300, rig.primeAndArm(), "the budget clamp applies on a v16 session");
        rig.outstanding = 0;
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(),
                "...and the cadence stays 1 Hz — the cap adds no fast path");
        assertEquals(0, rig.s.getRateGated(), "the v16 exclusion refuses before the cap gate");
    }

    @Test
    void rateGatedResetsWithTheSessionState() {
        var rig = new AdaptiveRig(scanner(16));
        rig.s.columnRateCap = () -> 1600;
        rig.primeAndArm();
        rig.s.noteDeclared(800);
        rig.outstanding = 0;
        rig.ticksToFire();
        assertTrue(rig.s.getRateGated() > 0, "premise: refusals were counted");
        rig.s.reset();
        assertEquals(0, rig.s.getRateGated(), "reset() zeroes rateGated with fastScans");
    }

    // ═══ Scan prefix retention (docs/planning/scanner-reopened-rings-plan.md, 2026-08-18) ═══
    //
    // The measured defect (spark profile 6HZTTXT5pn): every chunk crossing / dirty re-open
    // collapsed the confirmed prefix to 0, and the next walk classified the ENTIRE disc on
    // the render thread — ~1.05M probes at distance 512, 30-90 ms per hitch, every 2-3 s at
    // sprint speed. Retention keeps the prefix (decremented by the crossing delta) and
    // re-walks only a reopened-ring bitset: the crescent band that exited the view circle,
    // dirty/retry rings, nothing else.

    /** Classify-counting seam: walk cost becomes ASSERTABLE, not inferred. */
    static final class CountingColumnStateMap extends ColumnStateMap {
        int classifyCalls;
        @Override
        long classify(long packed) {
            this.classifyCalls++;
            return super.classify(packed);
        }
    }

    /** Seed every position of the radius-{@code radius} square around (cx, cz) that vanilla
     *  does NOT render at {@code vd} as satisfied (received + validated). */
    private static void seedNonExcludedSquare(ColumnStateMap columns, int cx, int cz,
                                              int radius, int vd) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (SpiralScanner.isVanillaRendered(cx + dx, cz + dz, cx, cz, vd)) continue;
                long packed = PositionUtil.packPosition(cx + dx, cz + dz);
                columns.onReceived(packed, 1000L);
                columns.onUpToDate(packed);
            }
        }
    }

    @Test
    void warmDiscChunkCrossingWalkIsBoundedAndDeclaresTheTrailingCrescent() {
        // THE hitch pin — the plan's test 1. Converged warm disc at the 2048-capable
        // distance 512, one chunk crossing: the next walk must be bounded (crescent band +
        // frontier ring only — orders of magnitude under the 1.05M full-disc count) and the
        // trailing crescent (positions that exited the view circle behind the player, known
        // to vanilla but never declared to LSS) must be declared.
        final int lod = 512, vd = 16;
        var columns = new CountingColumnStateMap();
        seedNonExcludedSquare(columns, CX, CZ, lod, vd);
        var s = scanner(lod);
        // Quad fast path OFF: this pin isolates RETENTION's walk-cost bound by classify
        // COUNT — with the ring fast path on, a collapsed prefix would fast-skip the
        // satisfied rings uncounted and the count could no longer detect the collapse
        // (review round 2 MINOR-1: the pin must stay the retention arm's control).
        s.quadtreeScanEnabled = () -> false;
        var queue = new Sink();
        assertEquals(0, fireScan(s, vd, columns, queue), "premise: converged warm disc");
        assertEquals(lod + 1, s.getConfirmedRing());

        s.recenter(1); // the crossing: (0,0) -> (1,0)
        columns.classifyCalls = 0;
        int declared = fireScanFull(s, CX + 1, CZ, vd, 0, 1000, 0, columns, queue);

        // Measured ~5k by construction (band + one frontier ring); 12k keeps slack while
        // catching a partial prefix collapse by COUNT, not only by a missing crescent
        // position (review round tightened this from 32k).
        assertTrue(columns.classifyCalls < 12_000,
                "the post-crossing walk must be bounded (crescent + frontier), got "
                        + columns.classifyCalls + " classify probes vs ~1.05M for the full disc");
        // Every trailing-crescent position (rendered from the old center, not from the new
        // one, inside the lod range) must be in the declared set.
        var declaredSet = new LongOpenHashSet();
        for (int i = 0; i < declared; i++) declaredSet.add(queue.pos[i]);
        int crescent = 0;
        for (int dx = -vd - 2; dx <= vd + 2; dx++) {
            for (int dz = -vd - 2; dz <= vd + 2; dz++) {
                boolean oldRendered = SpiralScanner.isVanillaRendered(CX + dx, CZ + dz, CX, CZ, vd);
                boolean newRendered = SpiralScanner.isVanillaRendered(CX + dx, CZ + dz, CX + 1, CZ, vd);
                if (oldRendered && !newRendered) {
                    crescent++;
                    assertTrue(declaredSet.contains(PositionUtil.packPosition(CX + dx, CZ + dz)),
                            "trailing-crescent position (" + dx + "," + dz + ") must be declared"
                                    + " — a miss here is a permanent LOD hole behind the player");
                }
            }
        }
        assertTrue(crescent > 0, "premise: the crossing produced a trailing crescent");

        // Second crossing, DIAGONAL ((1,0) -> (2,1), Chebyshev d=1): the crescent's
        // Euclidean low edge exists for exactly this exit shape (plan v1.1 MAJOR-1).
        s.recenter(1);
        columns.classifyCalls = 0;
        int declared2 = fireScanFull(s, CX + 2, CZ + 1, vd, 0, 1000, 0, columns, queue);
        assertTrue(columns.classifyCalls < 12_000,
                "the diagonal-crossing walk stays bounded, got " + columns.classifyCalls);
        var declaredSet2 = new LongOpenHashSet();
        for (int i = 0; i < declared2; i++) declaredSet2.add(queue.pos[i]);
        for (int dx = -vd - 3; dx <= vd + 3; dx++) {
            for (int dz = -vd - 3; dz <= vd + 3; dz++) {
                boolean prevRendered = SpiralScanner.isVanillaRendered(CX + 1 + dx, CZ + dz, CX + 1, CZ, vd);
                boolean nowRendered = SpiralScanner.isVanillaRendered(CX + 1 + dx, CZ + dz, CX + 2, CZ + 1, vd);
                long packedPos = PositionUtil.packPosition(CX + 1 + dx, CZ + dz);
                // Positions that were outside the ORIGINAL (0,0) view were seeded
                // satisfied — a satisfied exit correctly stays silent; the pin is the
                // UNSATISFIED (never-declared-under-exclusion) exits.
                if (prevRendered && !nowRendered
                        && columns.classify(packedPos) != ColumnStateMap.SATISFIED) {
                    assertTrue(declaredSet2.contains(packedPos),
                            "diagonal-exit crescent position must be declared — the Chebyshev"
                                    + " band flavor missed exactly these");
                }
            }
        }
    }

    @Test
    void steadyStateConvergedWalkVisitsNothing() {
        // The plan's test 10: bit-clearing regressions surface as count creep. A converged
        // disc with no events must walk NOTHING (empty frontier interval, empty bitset).
        var columns = new CountingColumnStateMap();
        seedNonExcludedSquare(columns, CX, CZ, 32, 4);
        var s = scanner(32);
        // Quad OFF for the same reason as the warm-disc pin above: the zero-visit
        // assertion must measure the legacy walk (retention's own steady state).
        s.quadtreeScanEnabled = () -> false;
        var queue = new Sink();
        assertEquals(0, fireScan(s, 4, columns, queue));
        assertEquals(33, s.getConfirmedRing());

        columns.classifyCalls = 0;
        assertEquals(0, fireScan(s, 4, columns, queue));
        assertEquals(0, columns.classifyCalls,
                "a converged steady-state walk visits zero positions (prefix past lod, no bits)");
    }

    @Test
    void crescentLostAnswerIsRedeclaredNextScan() {
        // The plan's test 4: the crescent declaration rides the same silent-drop reality as
        // everything else — an unanswered (superseded) crescent position keeps its ring
        // reopened and is re-declared next scan under its own steam.
        var columns = new ColumnStateMap();
        seedNonExcludedSquare(columns, CX, CZ, 16, 4);
        var s = scanner(16);
        var queue = new Sink();
        assertEquals(0, fireScan(s, 4, columns, queue));

        s.recenter(1);
        int first = fireScanFull(s, CX + 1, CZ, 4, 0, 1000, 0, columns, queue);
        assertTrue(first > 0, "premise: the crossing declared the trailing crescent");
        var firstSet = new LongOpenHashSet();
        for (int i = 0; i < first; i++) firstSet.add(queue.pos[i]);
        assertTrue(s.getReopenedRingCount() > 0,
                "unanswered crescent rings keep their bits");

        // NO answers arrive (server-side supersession). The next scan must re-declare them.
        queue.clear();
        int second = fireScanFull(s, CX + 1, CZ, 4, 0, 1000, 0, columns, queue);
        var secondSet = new LongOpenHashSet();
        for (int i = 0; i < second; i++) secondSet.add(queue.pos[i]);
        for (long p : firstSet) {
            assertTrue(secondSet.contains(p),
                    "a lost crescent answer must be re-declared next scan (self-heal)");
        }
    }

    @Test
    void movementWindowPredictionIgnoresRetainedStateUntilTheNextFire() {
        // The plan's §3 in isolation: between a recenter and the next actual walk,
        // predictedWalkCost must reproduce the pre-retention from-zero value (the elytra
        // regime — retention must not LOWER the movement-window prediction), and the first
        // fired walk closes the window so the retained prefix prices the walk again.
        var rig = new AdaptiveRig(scanner(200));
        seedSatisfiedDisc(rig.columns, 140);
        rig.primeAndArm(); // truncates in ring 141: prefix 141, scanRing 141
        int retained = rig.s.predictedWalkCost();
        assertTrue(retained <= SpiralScanner.FAST_RESCAN_MAX_WALK_COST,
                "premise: the retained-prefix prediction is cheap");

        rig.s.recenter(1);
        assertTrue(rig.s.recenteredSinceLastFireForTest(),
                "a crossing must open the movement window (recenter sets the flag)");
        assertEquals(4 * 141 * 142, rig.s.predictedWalkCost(),
                "the movement window predicts the bare from-zero cost despite the retained"
                        + " prefix (141 -> 140) standing");

        // The 1 Hz fallback fires (the expensive prediction refuses the fast path), the
        // walk runs, and the window closes: the prediction returns to the retained form.
        rig.outstanding = 0;
        assertEquals(LSSConstants.TICKS_PER_SECOND, rig.ticksToFire(),
                "the movement-window prediction holds the fast path off");
        assertFalse(rig.s.recenteredSinceLastFireForTest(), "a fired walk closes the window");
        assertTrue(rig.s.predictedWalkCost() <= SpiralScanner.FAST_RESCAN_MAX_WALK_COST,
                "post-walk the retained prefix prices the next walk again");
    }

    @Test
    void reopenValveFallsBackToFullReset() {
        // The plan's test 7: past REOPENED_RING_VALVE distinct rings the retained state is
        // no longer cheaper than a full re-walk — fall back to today's semantics wholesale.
        var columns = new ColumnStateMap();
        seedNonExcludedSquare(columns, CX, CZ, 100, 2);
        var s = scanner(100);
        var queue = new Sink();
        assertEquals(0, fireScan(s, 2, columns, queue));
        assertEquals(101, s.getConfirmedRing());

        for (int r = 1; r <= SpiralScanner.REOPENED_RING_VALVE; r++) {
            s.reopenRing(r);
        }
        assertEquals(SpiralScanner.REOPENED_RING_VALVE, s.getReopenedRingCount());
        assertEquals(101, s.getConfirmedRing(), "at the valve: retained state still standing");

        s.reopenRing(SpiralScanner.REOPENED_RING_VALVE + 1); // the 65th distinct ring
        assertEquals(0, s.getReopenedRingCount(), "valve trip clears the bitset...");
        assertEquals(0, s.getConfirmedRing(), "...and falls back to the full prefix reset");
    }

    @Test
    void budgetTruncationInsideAReopenedRingKeepsTheBitAndFailsClosed() {
        // The plan's test 9 + v1.1 MAJOR-2: a budget break INSIDE the reopened interval
        // keeps the bit (the ring re-walks next scan) and flags truncatedBelowPrefix so the
        // walk-cost prediction fails closed over the full span (scanRing alone would be
        // blind to the frontier interval the next walk also iterates).
        var columns = new ColumnStateMap();
        seedNonExcludedSquare(columns, CX, CZ, 50, 2);
        var s = scanner(50);
        var queue = new Sink();
        assertEquals(0, fireScan(s, 2, columns, queue));
        assertEquals(51, s.getConfirmedRing());

        // Dirty the whole of rings 30 and 40 (the production dirty path: mark + reopen).
        int[] c = new int[2];
        for (int r : new int[] {30, 40}) {
            for (int i = 0; i < 8 * r; i++) {
                SpiralScanner.ringIndexToCoord(r, i, CX, CZ, c);
                assertTrue(columns.markDirtyIfKnown(PositionUtil.packPosition(c[0], c[1])));
            }
            s.reopenRing(r);
        }
        assertEquals(2, s.getReopenedRingCount());

        // Deep queue pressure tapers the budget to 8 — the walk truncates inside ring 30.
        int declared = fireScan(s, 2, 990, 1000, 0, columns, queue);
        assertEquals(8, declared, "premise: the tapered budget truncated inside ring 30");
        assertEquals(2, s.getReopenedRingCount(),
                "a truncated reopened ring keeps its bit (and ring 40 was never reached)");
        assertTrue(s.truncatedBelowPrefixForTest(),
                "the below-prefix truncation is flagged for the prediction");
        assertEquals(51, s.getConfirmedRing(), "the prefix survives throughout");
        // Fail-closed prediction: full span from the lowest reopened ring to the LOD
        // distance — NOT the truncated scanRing (30), which would hide the frontier
        // interval. 4*(50*51 - 30*29) = 6720.
        assertEquals(4 * (50 * 51 - 30 * 29), s.predictedWalkCost());

        // Full budget: both rings drain over the following scans and the bits clear.
        int drained = 0;
        for (int i = 0; i < 4; i++) {
            int n = fireScan(s, 2, columns, queue);
            drained += n;
            for (int j = 0; j < n; j++) columns.onReceived(queue.pos[j], 9000L + i);
        }
        // 560 = both full rings: the 8 declared by the truncated scan were never ANSWERED,
        // so they re-declare (declaration does not consume a dirty mark).
        assertEquals(8 * 30 + 8 * 40, drained, "every dirty position is eventually served");
        assertEquals(0, fireScan(s, 2, columns, queue));
        assertEquals(0, s.getReopenedRingCount(), "served rings cleared their bits");
    }

    @Test
    void sustainedCrossingsKeepBitPopulationUnderTheValve() {
        // The plan's test 11: the flight regime. Sixty 1-chunk crossings with a scan after
        // each — the crescent bands must keep draining (re-confirming rings clear their
        // bits) so the population never creeps toward the 64-bit valve; a silent valve trip
        // would revert to full-disc hitches invisibly.
        final int vd = 16, lod = 64;
        var columns = new ColumnStateMap();
        var s = scanner(lod);
        var queue = new Sink();
        // Converge at the origin first (answer everything declared; ~15.5k positions at
        // 800/scan needs ~20 walks).
        for (int i = 0; i < 30; i++) {
            int n = fireScanFull(s, CX, CZ, vd, 0, 1000, 0, columns, queue);
            for (int j = 0; j < n; j++) {
                columns.onReceived(queue.pos[j], 1000L + i);
                columns.onUpToDate(queue.pos[j]);
            }
            if (n == 0) break;
        }
        assertEquals(lod + 1, s.getConfirmedRing(), "premise: converged at the origin");

        for (int step = 1; step <= 60; step++) {
            s.recenter(1);
            int n = fireScanFull(s, CX + step, CZ, vd, 0, 1000, 0, columns, queue);
            for (int j = 0; j < n; j++) {
                columns.onReceived(queue.pos[j], 5000L + step);
                columns.onUpToDate(queue.pos[j]);
            }
            // Measured steady population is ~10 (band ~9 rings + shift churn); 24 leaves
            // real headroom while surfacing creep long BEFORE a silent valve trip — a
            // <valve bound could not fail for any realistic regression (review round).
            assertTrue(s.getReopenedRingCount() <= 24,
                    "step " + step + ": bit population must stay well under the valve, got "
                            + s.getReopenedRingCount());
            assertTrue(s.getConfirmedRing() > 0,
                    "step " + step + ": the valve (or a full reset) must never fire in"
                            + " ordinary sustained flight");
        }
    }

    @Test
    void movementChaosLeavesNoPositionPermanentlyOrphaned() {
        // The §9 amendment to the CL-014 orphan property: MOVEMENT chaos. Random
        // interleavings of crossings (retention-path and full-reset-scale), dirty re-opens
        // (production shape: mark + reopenRing at the position's CURRENT ring), retry
        // marks, answers and silent supersessions — then convergence at the final center.
        // A single silently-orphaned position (a hole in the crescent geometry, a bit lost
        // to a shift, a stranded below-prefix want) never converges. The fixed-center form
        // lives in anyChaosInterleavingLeavesNoPositionPermanentlyOrphaned above.
        final int vd = 16, lod = 24;
        for (long seed : new long[] {3L, 11L, 77L}) {
            var rng = new Random(seed);
            var columns = new ColumnStateMap();
            var queue = new Sink();
            var s = scanner(lod);
            int cx = 0, cz = 0;

            for (int cycle = 0; cycle < 25; cycle++) {
                // Random movement: mostly small retention-path crossings, occasionally a
                // teleport-scale hop (exercises the full-reset rung).
                if (rng.nextInt(100) < 60) {
                    int d = rng.nextInt(100) < 90 ? 1 + rng.nextInt(3)
                            : SpiralScanner.RECENTER_FULL_RESET_DELTA + rng.nextInt(4);
                    int dir = rng.nextBoolean() ? 1 : -1;
                    // Axis AND diagonal crossings (plan §2's mandate — the diagonal exit
                    // is exactly the case the naive Chebyshev band missed): a both-axes
                    // move of d is still a Chebyshev-d crossing.
                    switch (rng.nextInt(3)) {
                        case 0 -> cx += dir * d;
                        case 1 -> cz += dir * d;
                        default -> { cx += dir * d; cz += (rng.nextBoolean() ? 1 : -1) * d; }
                    }
                    s.recenter(d);
                }
                int n = fireScanFull(s, cx, cz, vd, 0, 1000, 0, columns, queue);
                for (int i = 0; i < n; i++) {
                    long pos = queue.pos[i];
                    int roll = rng.nextInt(100);
                    if (roll < 40) { columns.onReceived(pos, 1_000L + cycle); columns.onUpToDate(pos); }
                    else if (roll < 50) columns.markRetry(pos);
                    else if (roll < 60) columns.onUpToDate(pos);
                    // else: superseded — no answer, ever; only re-declaration saves it.
                }
                // A dirty broadcast on a random known position (production: mark + reopen
                // at the position's ring from the CURRENT center).
                if (rng.nextInt(100) < 40 && columns.receivedCount() > 0) {
                    int dx = rng.nextInt(2 * lod + 1) - lod, dz = rng.nextInt(2 * lod + 1) - lod;
                    long dirtyPos = PositionUtil.packPosition(cx + dx, cz + dz);
                    if (columns.markDirtyIfKnown(dirtyPos)) {
                        s.reopenRing(Math.max(Math.abs(dx), Math.abs(dz)));
                    }
                }
            }

            // Convergence at the final center: answers now always succeed.
            boolean converged = false;
            for (int i = 0; i < 40 && !converged; i++) {
                int n = fireScanFull(s, cx, cz, vd, 0, 1000, 0, columns, queue);
                for (int j = 0; j < n; j++) {
                    columns.onReceived(queue.pos[j], 50_000L + i);
                    columns.onUpToDate(queue.pos[j]);
                }
                converged = n == 0 && s.getConfirmedRing() == lod + 1;
            }
            assertTrue(converged, "seed " + seed + ": movement chaos never converged"
                    + " (confirmedRing=" + s.getConfirmedRing() + ")");
            // The oracle: nothing in range is left unsatisfied (the orphan-proof).
            for (int dx = -lod; dx <= lod; dx++) {
                for (int dz = -lod; dz <= lod; dz++) {
                    if (SpiralScanner.isVanillaRendered(cx + dx, cz + dz, cx, cz, vd)) continue;
                    long p = PositionUtil.packPosition(cx + dx, cz + dz);
                    assertEquals(ColumnStateMap.SATISFIED, columns.classify(p),
                            "seed " + seed + ": position (" + dx + "," + dz + ") relative to"
                                    + " the final center was permanently orphaned");
                }
            }
        }
    }

    @Test
    void killSwitchRestoresLegacyResetSemantics() {
        // The plan's test 12: enableScanPrefixRetention=false is DELEGATION to today's
        // semantics, not a parallel implementation — every reset path collapses the prefix
        // to 0 and no bit is ever set. The field A/B lever and the silent-orphan safety net.
        var columns = new ColumnStateMap();
        seedNonExcludedSquare(columns, CX, CZ, 8, 2);
        var s = scanner(8);
        s.prefixRetentionEnabled = () -> false;
        var queue = new Sink();
        assertEquals(0, fireScan(s, 2, columns, queue));
        assertEquals(9, s.getConfirmedRing());

        s.recenter(1);
        assertEquals(0, s.getConfirmedRing(), "kill switch: movement zeroes the prefix (legacy)");
        assertEquals(0, s.getReopenedRingCount(), "kill switch: no bits, ever");

        assertEquals(0, fireScan(s, 2, columns, queue)); // reconverge
        assertEquals(9, s.getConfirmedRing());

        s.reopenRing(3);
        assertEquals(0, s.getConfirmedRing(), "kill switch: a dirty re-open zeroes the prefix (legacy)");
        assertEquals(0, s.getReopenedRingCount());

        // The legacy retry-reset rung: an actionable mark zeroes the prefix inside scan().
        assertEquals(0, fireScan(s, 2, columns, queue));
        long marked = ringPos(5, 0);
        columns.onIngestFailed(marked);
        int n = fireScan(s, 2, columns, queue);
        assertEquals(1, n, "kill switch: the legacy from-0 re-walk declares the retry");
        assertEquals(List.of(marked), queue.positions());
    }

    @Test
    void midSessionKillSwitchFlipFlushesRetainedBits() {
        // The off arm is the field A/B's CONTROL arm — bits set while retention was ON
        // must not survive the flip, or the off arm walks (and prices the cadence gate)
        // as "legacy plus leftovers" instead of legacy (review round MINOR).
        var retention = new java.util.concurrent.atomic.AtomicBoolean(true);
        var columns = new ColumnStateMap();
        seedNonExcludedSquare(columns, CX, CZ, 8, 2);
        var s = scanner(8);
        s.prefixRetentionEnabled = retention::get;
        var queue = new Sink();
        assertEquals(0, fireScan(s, 2, columns, queue));
        assertEquals(9, s.getConfirmedRing());
        long dirtied = ringPos(4, 0);
        assertTrue(columns.markDirtyIfKnown(dirtied));
        s.reopenRing(4);
        assertEquals(1, s.getReopenedRingCount(), "premise: a retained bit is standing");

        retention.set(false); // the config flip, mid-session
        assertEquals(1, fireScan(s, 2, columns, queue),
                "the flipped scan still declares the dirty position (legacy reachability"
                        + " comes from the retry/dirty ladders, not the bit)");
        assertEquals(0, s.getReopenedRingCount(),
                "the scan-head flush drops retained bits the moment retention is off");
    }

    @Test
    void reopenedRingFollowsTheCrossingSoItsPositionStaysCovered() {
        // Mutation pin (review round MAJOR: removing shiftReopenedBits survived the whole
        // suite): a dirty ring reopened below the prefix must FOLLOW the player across a
        // crossing — an unshifted bit leaves the position at ring r±d with its bit at r,
        // silently orphaned below the prefix until a prune or full reset.
        final int vd = 16, lod = 40;
        var columns = new ColumnStateMap();
        seedNonExcludedSquare(columns, CX, CZ, lod, vd);
        var s = scanner(lod);
        var queue = new Sink();
        assertEquals(0, fireScan(s, vd, columns, queue), "premise: converged warm disc");
        assertEquals(lod + 1, s.getConfirmedRing());

        long dirty = PositionUtil.packPosition(CX + 25, CZ); // ring 25 -> ring 24 after the crossing
        assertTrue(columns.markDirtyIfKnown(dirty));
        s.reopenRing(25);   // production dirty path
        s.recenter(1);      // the crossing: (0,0) -> (1,0)

        int n = fireScanFull(s, CX + 1, CZ, vd, 0, 1000, 0, columns, queue);
        assertTrue(queue.positions(n).contains(dirty),
                "a reopened ring must FOLLOW the crossing ([r-d, r+d]) — an unshifted bit"
                        + " silently orphans its position below the prefix");
    }

    @Test
    void shiftedBitAtOrAboveTheNewPrefixIsDroppedNotStranded() {
        // Mutation pin (review round MAJOR: removing the post-shift invariant sweep
        // survived): a bit shifted to/past the decremented prefix is unreachable forever
        // (nextWalkRing stops the reopened interval at the prefix), never clears, and
        // creeps toward the overflow valve — whose trip is the full-disc walk this change
        // exists to remove.
        var columns = new ColumnStateMap();
        seedNonExcludedSquare(columns, CX, CZ, 20, 2);
        var s = scanner(20);
        var queue = new Sink();
        assertEquals(0, fireScan(s, 2, columns, queue));
        assertEquals(21, s.getConfirmedRing());

        s.reopenRing(20); // one ring below the prefix
        assertEquals(1, s.getReopenedRingCount());

        s.recenter(1); // shift -> {19,20,21}; prefix 21 -> 20; crescent band [0,3]
        assertEquals(20, s.getConfirmedRing());
        assertEquals(5, s.getReopenedRingCount(),
                "4 crescent rings + the ONE shifted bit still below the prefix: bits at or"
                        + " above it are unreachable forever and would creep into the valve");
    }

    @Test
    void movementWindowPredictionIgnoresTheReopenedLowerBoundToo() {
        // Mutation pin (review round MAJOR: dropping the recenteredSinceLastFire branch
        // survived — every other rig runs vd 2, where the crescent band's low edge is
        // ring 0 and c_eff coincides with the bare formula). At vd 64 the band's lowest
        // bit sits at ~ring 43, so the two mechanisms genuinely diverge here.
        final int vd = 64, lod = 100;
        var columns = new ColumnStateMap();
        seedNonExcludedSquare(columns, CX, CZ, lod, vd);
        var s = scanner(lod);
        var queue = new Sink();
        assertEquals(0, fireScan(s, vd, columns, queue), "premise: converged warm disc");
        assertEquals(lod + 1, s.getConfirmedRing());

        s.recenter(1);
        assertTrue(s.getReopenedRingCount() > 0,
                "premise: the crescent band's lowest bit sits far above ring 0 at vd 64");
        assertEquals(4 * lod * (lod + 1), s.predictedWalkCost(),
                "the movement window prices the BARE from-zero walk — neither the retained"
                        + " prefix nor the reopened lower bound may lower it (elytra regime)");
    }

    @Test
    void frontierBreakRightAfterAReopenedRingStillFailsClosed() {
        // Review round MAJOR (validated 1038x under-price PoC): when the budget fills at a
        // reopened ring's LAST index, the break lands on the frontier's FIRST ring —
        // reopenedBelowPrefix is false there, so truncatedBelowPrefix does not fire, yet
        // scanRing (the outermost QUEUEING ring) is the low reopened ring and the whole
        // frontier interval is unpriced. The scanRing >= confirmedRing guard must fail
        // closed to the full span.
        var columns = new ColumnStateMap();
        // Seed the whole non-excluded disc EXCEPT ring 30 — the prefix parks there.
        for (int dx = -200; dx <= 200; dx++) {
            for (int dz = -200; dz <= 200; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == 30) continue;
                if (SpiralScanner.isVanillaRendered(CX + dx, CZ + dz, CX, CZ, 2)) continue;
                columns.onReceived(PositionUtil.packPosition(CX + dx, CZ + dz), 1000L);
                columns.onUpToDate(PositionUtil.packPosition(CX + dx, CZ + dz));
            }
        }
        var s = scanner(200);
        var queue = new Sink();
        int first = fireScan(s, 2, columns, queue);
        assertEquals(8 * 30, first, "premise: ring 30 declares whole, walk untruncated");
        assertEquals(30, s.getConfirmedRing(), "premise: the prefix parks at the open ring");

        // Dirty exactly ring 5's LAST walk index (39): with budget 1 it queues as the
        // ring loop ENDS, so the break lands at frontier ring 30's index 0.
        long dirtied = ringPos(5, 8 * 5 - 1);
        assertTrue(columns.markDirtyIfKnown(dirtied));
        s.reopenRing(5);
        assertEquals(1, fireScan(s, 2, 999, 1000, 0, columns, queue),
                "premise: the tapered budget (1) declares only the reopened dirty");
        assertEquals(5, s.getScanRing(), "premise: scanRing is the reopened ring...");
        assertEquals(30, s.getConfirmedRing(), "...below the surviving prefix");
        assertTrue(s.predictedWalkCost() > SpiralScanner.FAST_RESCAN_MAX_WALK_COST,
                "the prediction must fail closed over the full span (a scanRing-priced"
                        + " prediction of 40 would admit an 83k-probe walk at the fast floor)");
    }

    @Test
    void lodDistanceShrinkResetsThePrefixSoTheOuterAnnulusHeals() {
        // Review round MAJOR: a mid-session effective-distance shrink (the in-game slider)
        // used to strand the prefix ABOVE the new distance — the walk visited nothing, the
        // movement prune then deleted the outer annulus's state, dirty reopens above the
        // shrunk distance were dropped by the set-time clamp, and on grow-back a
        // stationary player kept a permanently blank annulus (retention deleted the
        // incidental crossing-collapse that used to heal this in one second). The
        // lod-shrink rung (the F1 exclusion rung's twin) full-resets once per shrink.
        int saved = LSSClientConfig.CONFIG.lodDistanceChunks;
        try {
            LSSClientConfig.CONFIG.lodDistanceChunks = 0; // server distance (60) in effect
            var columns = new ColumnStateMap();
            seedNonExcludedSquare(columns, CX, CZ, 60, 2);
            var s = scanner(60);
            var queue = new Sink();
            assertEquals(0, fireScan(s, 2, columns, queue));
            assertEquals(61, s.getConfirmedRing(), "premise: converged at distance 60");

            // The shrink; a dirty at ring 30 arrives while shrunk — its reopen is dropped
            // by the set-time lod clamp (30 > 10), which is safe ONLY because the rung
            // has already put ring 30 above the prefix.
            LSSClientConfig.CONFIG.lodDistanceChunks = 10;
            long dirtied = ringPos(30, 0);
            assertTrue(columns.markDirtyIfKnown(dirtied));
            s.reopenRing(30);
            assertEquals(0, fireScan(s, 2, columns, queue),
                    "the shrunk walk declares nothing (inner disc already satisfied)");
            assertEquals(11, s.getConfirmedRing(),
                    "the lod-shrink rung resets the prefix INTO the shrunk regime");
            assertEquals(0, s.getReopenedRingCount(),
                    "no bit above the shrunk distance survives");

            // The movement prune at the shrunk radius deletes the outer annulus's state.
            columns.pruneOutOfRange(CX, CZ, 10 + 32);

            // Grow back: the annulus sits ABOVE the prefix, so the frontier walk
            // re-declares both the pruned band and the dropped dirty position.
            LSSClientConfig.CONFIG.lodDistanceChunks = 0;
            int declared = fireScan(s, 2, columns, queue);
            assertTrue(declared > 0, "the regrown walk declares the stranded outer band");
            var set = new LongOpenHashSet();
            for (int i = 0; i < declared; i++) set.add(queue.pos[i]);
            assertTrue(set.contains(dirtied),
                    "the dropped dirty reopen heals via the frontier walk");
            assertTrue(set.contains(ringPos(43, 0)),
                    "the pruned annulus's first ring re-declares");
        } finally {
            LSSClientConfig.CONFIG.lodDistanceChunks = saved;
        }
    }

    @Test
    void teleportScaleCrossingFallsBackToTheFullReset() {
        // Pins RECENTER_FULL_RESET_DELTA's behavior (review round: removing the fallback
        // survived the suite — the shift is sound at large d too, so only this pins the
        // deliberate teleport policy) and its bound (orShifted's word math needs d < 64).
        assertTrue(SpiralScanner.RECENTER_FULL_RESET_DELTA < 64,
                "orShifted's cross-word math is only valid below one word");
        var columns = new ColumnStateMap();
        seedNonExcludedSquare(columns, CX, CZ, 30, 4);
        var s = scanner(30);
        var queue = new Sink();
        assertEquals(0, fireScan(s, 4, columns, queue));
        assertEquals(31, s.getConfirmedRing());
        s.reopenRing(5);
        s.recenter(SpiralScanner.RECENTER_FULL_RESET_DELTA);
        assertEquals(0, s.getConfirmedRing(),
                "a teleport-scale crossing gives up on shifting and full-resets");
        assertEquals(0, s.getReopenedRingCount(), "...dropping the retained bits with it");
    }

    @Test
    void parkedRetryMarkIsNotVisitedByTheRingCollect() {
        // The collect's parking ladder in isolation (review round: ignoring the exclusion
        // in collectActionableRetryRings survived — the post-walk bit assert cannot
        // distinguish "never set" from "set and cleared by the fully-excluded walk").
        var columns = new ColumnStateMap();
        long parked = PositionUtil.packPosition(CX + 1, CZ);     // inside the vd-2 exclusion
        long actionable = PositionUtil.packPosition(CX + 9, CZ); // outside it
        for (long pos : new long[]{parked, actionable}) {
            columns.onReceived(pos, 1000L);
            columns.onIngestFailed(pos);
        }
        var rings = new ArrayList<Integer>();
        columns.collectActionableRetryRings(CX, CZ, 2, rings::add);
        assertEquals(List.of(9), rings,
                "the collect applies the SAME parking ladder as hasActionableRetries — a"
                        + " vanilla-rendered (unconsumable) mark must not reopen its ring");
    }

    // ---- Window-limited latch provenance (ramp-window-limited-credit-plan.md §3.2) ----

    @Test
    void burstCapClampFlagTrueWhenTheGovernedCapBindsAndTheWalkTruncates() {
        var s = scanner(8); // lod-8 disc: hundreds of wanted positions
        s.columnBurstCap = () -> 10;
        var sink = new Sink();
        int n = fireScan(s, 0, new ColumnStateMap(), sink);
        assertEquals(10, n, "the burst cap is the binding budget");
        assertTrue(s.wasLastWalkTruncated(), "demand beyond the window truncates");
        assertTrue(s.wasLastBudgetCapClamped(),
                "the cap set the final budget — the provenance half reads true");
    }

    @Test
    void taperReducedBudgetClearsTheCapClampFlag() {
        // 1-Fable fold MAJOR-1(c): the CPU-weak client between 25% and halt gets a
        // taper-reduced budget — truncation there must NOT read as governed-window-
        // limited (the latch would walk an unproven pipe to OPEN on 1-column windows).
        var s = scanner(8);
        s.columnBurstCap = () -> 100;
        var sink = new Sink();
        // Queue at half of halt → scale 0.5 → budget 50 < the 100 cap.
        int n = fireScan(s, 0, 500, 1000, 0, new ColumnStateMap(), sink);
        assertEquals(50, n, "the taper reduced the budget below the cap");
        assertTrue(s.wasLastWalkTruncated(), "still truncated (demand > 50)");
        assertFalse(s.wasLastBudgetCapClamped(),
                "a taper-reduced budget is not the governed cap's doing");
    }

    @Test
    void marginalPressureKeepsTheCapClampFlag() {
        // Dynamics review MAJOR-1: exact equality disarmed the latch at ~9 queued
        // columns near the rig's ~350 cap — the fix would have been dead at exactly
        // its park point. The 25%-tolerance predicate must survive marginal pressure.
        var s = scanner(64);
        s.columnBurstCap = () -> 350;
        var sink = new Sink();
        int n = fireScan(s, 0, 9, 6000, 0, new ColumnStateMap(), sink);
        assertEquals(349, n, "round(350 x 0.9985) — the taper shaved one column");
        assertTrue(s.wasLastWalkTruncated());
        assertTrue(s.wasLastBudgetCapClamped(),
                "a one-column taper shave near the cap is still the cap's clamp");
    }

    @Test
    void constantBudgetBinderClearsTheCapClampFlag() {
        // A burst cap at/above WANT_SET_BUDGET never actually clamps — truncation
        // against the constant budget is the pre-governor shape and must not latch.
        var s = scanner(64); // > 800 wanted positions so the constant budget truncates
        s.columnBurstCap = () -> LSSConstants.WANT_SET_BUDGET + 100;
        var sink = new Sink();
        int n = fireScan(s, 0, new ColumnStateMap(), sink);
        assertEquals(LSSConstants.WANT_SET_BUDGET, n, "the constant budget binds");
        assertTrue(s.wasLastWalkTruncated());
        assertFalse(s.wasLastBudgetCapClamped(),
                "the constant WANT_SET_BUDGET was the binder, not the governed cap");
    }

    @Test
    void untruncatedCapClampedWalkReportsNoTruncation() {
        // Cap-clamped but the demand FIT the window (the converged/trickle shape):
        // the truncation half reads false, so the latch's conjunction cannot fire.
        var s = scanner(2); // 24-position annulus at vd 0
        s.columnBurstCap = () -> 100;
        var sink = new Sink();
        int n = fireScan(s, 0, new ColumnStateMap(), sink);
        assertEquals(24, n, "the whole annulus fits the capped budget");
        assertFalse(s.wasLastWalkTruncated(), "no demand beyond the window");
    }
}
