package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fast path's equivalence gate (quadtree-client-state-plan.md §5.1.1/§8): the
 * section-store ring fast path ({@code enableQuadtreeScan}) must be OBSERVATIONALLY
 * IDENTICAL to the per-position walk — same fire decisions tick by tick, same emissions
 * byte for byte (position AND timestamp order — the server's pacing gates anchor on
 * declaration order), same prefix/reopened/truncation/cadence state after every walk.
 * Two scanners (fast path on / off) run over ONE shared state map (the walk is read-only
 * on it) through scripted movement/dirty/teleport/shrink scenarios plus a seeded chaos
 * loop; the only permitted divergence is the {@code quadRingSkips} diagnostic itself.
 */
class QuadtreeWalkDifferentialTest {

    private static final int VD = 6;

    private static SpiralScanner scanner(int lodDistance, boolean quad) {
        var s = new SpiralScanner();
        s.setConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true,
                lodDistance, true));
        s.quadtreeScanEnabled = () -> quad;
        return s;
    }

    /** Both arms + the shared store, with per-arm send buffers. */
    private static final class Rig {
        final ColumnStateMap columns = new ColumnStateMap();
        final SpiralScanner quadArm;
        final SpiralScanner legacyArm;
        final long[] qPos, qTs, lPos, lTs;

        Rig(int lodDistance, int bufferSize) {
            this.quadArm = scanner(lodDistance, true);
            this.legacyArm = scanner(lodDistance, false);
            this.qPos = new long[bufferSize];
            this.qTs = new long[bufferSize];
            this.lPos = new long[bufferSize];
            this.lTs = new long[bufferSize];
        }

        /** One tick on both arms; asserts fire parity and (on fire) full walk parity.
         *  @return the fire count, -1 when neither fired */
        int tick(int cx, int cz, int viewDistance, String at) {
            int q = this.quadArm.maybeScan(cx, cz, viewDistance, 0, 1000, 0L, Long.MAX_VALUE,
                    -1, 1000, () -> 0, this.columns, this.qPos, this.qTs);
            int l = this.legacyArm.maybeScan(cx, cz, viewDistance, 0, 1000, 0L, Long.MAX_VALUE,
                    -1, 1000, () -> 0, this.columns, this.lPos, this.lTs);
            assertEquals(l, q, "fire/count divergence " + at);
            if (q >= 0) {
                for (int i = 0; i < q; i++) {
                    assertEquals(this.lPos[i], this.qPos[i], "position[" + i + "] " + at);
                    assertEquals(this.lTs[i], this.qTs[i], "timestamp[" + i + "] " + at);
                }
                assertWalkStateParity(at);
            }
            return q;
        }

        void assertWalkStateParity(String at) {
            assertEquals(this.legacyArm.getConfirmedRing(), this.quadArm.getConfirmedRing(),
                    "confirmedRing " + at);
            assertEquals(this.legacyArm.getScanRing(), this.quadArm.getScanRing(),
                    "scanRing " + at);
            assertEquals(this.legacyArm.getReopenedRingCount(), this.quadArm.getReopenedRingCount(),
                    "reopened count " + at);
            assertEquals(this.legacyArm.recenteredSinceLastFireForTest(),
                    this.quadArm.recenteredSinceLastFireForTest(), "movement window " + at);
            assertEquals(this.legacyArm.truncatedBelowPrefixForTest(),
                    this.quadArm.truncatedBelowPrefixForTest(), "truncatedBelowPrefix " + at);
            assertEquals(this.legacyArm.wasLastWalkTruncated(),
                    this.quadArm.wasLastWalkTruncated(), "lastWalkTruncated " + at);
            assertEquals(this.legacyArm.getValveTrips(), this.quadArm.getValveTrips(),
                    "valveTrips " + at);
        }

        /** Drive ticks until a fire happens on both (they always agree — tick asserts it). */
        int fire(int cx, int cz, int viewDistance, String at) {
            for (int i = 0; i <= LSSConstants.TICKS_PER_SECOND; i++) {
                int n = tick(cx, cz, viewDistance, at + " tick " + i);
                if (n >= 0) return n;
            }
            throw new AssertionError("cadence never fired " + at);
        }

        /** The manager's dirty flow, mirrored to both scanners over the one store. */
        void dirty(long packed, int cx, int cz) {
            if (this.columns.markDirtyIfKnown(packed)) {
                int ring = Math.max(Math.abs(PositionUtil.unpackX(packed) - cx),
                        Math.abs(PositionUtil.unpackZ(packed) - cz));
                this.quadArm.reopenRing(ring);
                this.legacyArm.reopenRing(ring);
            }
        }

        void recenter(int d) {
            this.quadArm.recenter(d);
            this.legacyArm.recenter(d);
        }

        /** Answer every position of the last fired walk as received (satisfies + validates). */
        void answerAll(int count, long ts) {
            for (int i = 0; i < count; i++) {
                this.columns.onReceived(this.qPos[i], ts);
            }
        }
    }

    @Test
    void coldBackfillWithBudgetTruncationMatchesExactly() {
        // Small buffers force the budget break every walk — the hoisted top-of-ring break
        // and the mid-ring break both exercise, cold frontier through convergence.
        var rig = new Rig(24, 96);
        int cx = 0, cz = 0;
        int walks = 0;
        while (walks < 80) {
            int n = rig.fire(cx, cz, VD, "cold walk " + walks);
            walks++;
            if (n == 0) break; // converged
            rig.answerAll(n, 1_000L + walks);
        }
        assertTrue(walks < 80, "cold backfill must converge");
        // Converged steady state: both arms fire empty walks with identical state.
        rig.fire(cx, cz, VD, "post-convergence");
        // NOTE deliberately no quadRingSkips premise here: a cold backfill never RE-walks
        // satisfied rings (the confirmed prefix already covers them), so the fast path
        // legitimately never engages — its moment is reset walks over converged discs
        // (valveOverflowResetMatchesOnBothArms asserts the engagement premise there).
    }

    @Test
    void movementDirtyTeleportAndShrinkScriptMatchesExactly() {
        var rig = new Rig(32, LSSConstants.MAX_BATCH_CHUNK_REQUESTS);
        int cx = 0, cz = 0;
        // Converge a warm disc first.
        for (int w = 0; w < 60; w++) {
            int n = rig.fire(cx, cz, VD, "warmup " + w);
            if (n == 0) break;
            rig.answerAll(n, 500L + w);
        }

        // Crossings (incl. diagonal), each followed by a walk + answers.
        int[][] moves = {{1, 0}, {1, 1}, {0, 1}, {1, 0}};
        for (int[] mv : moves) {
            cx += mv[0]; cz += mv[1];
            rig.recenter(Math.max(Math.abs(mv[0]), Math.abs(mv[1])));
            int n = rig.fire(cx, cz, VD, "crossing to " + cx + "," + cz);
            rig.answerAll(Math.max(n, 0), 900L);
        }

        // A dirty batch below the prefix (reopens single rings on both arms).
        rig.dirty(PositionUtil.packPosition(cx + 9, cz), cx, cz);
        rig.dirty(PositionUtil.packPosition(cx - 12, cz + 3), cx, cz);
        rig.dirty(PositionUtil.packPosition(cx, cz - 15), cx, cz);
        int n = rig.fire(cx, cz, VD, "post-dirty");
        rig.answerAll(Math.max(n, 0), 1_500L);

        // A wide dirty burst (stamp first so markDirtyIfKnown fires) — parity churn
        // across many reopened rings. (The 64-ring VALVE cannot trip at this lod — it
        // needs >64 distinct rings below the prefix; see valveOverflowResetMatchesOnBothArms.)
        for (int r = 2; r <= 24; r += 2) {
            long p = PositionUtil.packPosition(cx + r, cz - 1);
            rig.columns.onReceived(p, 1_800L);
            rig.dirty(p, cx, cz);
        }
        n = rig.fire(cx, cz, VD, "post-burst");
        rig.answerAll(Math.max(n, 0), 2_000L);

        // Teleport-scale crossing (full reset path on both arms).
        rig.recenter(SpiralScanner.RECENTER_FULL_RESET_DELTA);
        cx += 40;
        n = rig.fire(cx, cz, VD, "post-teleport");
        rig.answerAll(Math.max(n, 0), 2_500L);

        // Exclusion shrink (render distance lowered): the F1 reset on both arms.
        n = rig.fire(cx, cz, VD - 3, "post-exclusion-shrink");
        rig.answerAll(Math.max(n, 0), 3_000L);

        // LOD shrink + grow via a fresh SessionConfig on both arms (the lod-shrink rung).
        var shrunk = new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, 16, true);
        rig.quadArm.setConfig(shrunk);
        rig.legacyArm.setConfig(shrunk);
        n = rig.fire(cx, cz, VD, "post-lod-shrink");
        rig.answerAll(Math.max(n, 0), 3_500L);
        var grown = new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, 32, true);
        rig.quadArm.setConfig(grown);
        rig.legacyArm.setConfig(grown);
        n = rig.fire(cx, cz, VD, "post-lod-regrow");
        rig.answerAll(Math.max(n, 0), 4_000L);

        // Retry marks (an ingest failure) — the collect path reopens on both arms.
        long failed = PositionUtil.packPosition(cx + 10, cz + 2);
        rig.columns.onIngestFailed(failed); // stamped earlier by answerAll sweeps
        rig.fire(cx, cz, VD, "post-ingest-failure");
    }

    @Test
    void valveOverflowResetMatchesOnBothArms() {
        // The B1 shape: a converged deep disc, then dirty reopens dispersed across >64
        // distinct rings — the REOPENED_RING_VALVE fires (counted identically on both
        // arms), the prefix collapses, and the recovery walk — the measured 30-90 ms
        // full-disc event on the legacy arm — must produce identical output on the fast
        // arm, which skips its satisfied rings leaf-wise.
        var rig = new Rig(100, LSSConstants.MAX_BATCH_CHUNK_REQUESTS);
        int cx = 0, cz = 0;
        for (int w = 0; w < 200; w++) {
            int n = rig.fire(cx, cz, VD, "deep warmup " + w);
            if (n == 0) break;
            rig.answerAll(n, 700L + w);
        }
        assertTrue(rig.quadArm.getConfirmedRing() > SpiralScanner.REOPENED_RING_VALVE + 10,
                "premise: the prefix stands deep enough for >64 reopenable rings, got "
                        + rig.quadArm.getConfirmedRing());

        for (int r = 1; r <= SpiralScanner.REOPENED_RING_VALVE + 2; r++) {
            long p = PositionUtil.packPosition(cx + r, cz);
            rig.columns.onReceived(p, 5_000L); // known -> the dirty mark takes
            rig.dirty(p, cx, cz);
        }
        assertEquals(1, rig.quadArm.getValveTrips(), "the storm must trip the valve once");
        assertEquals(1, rig.legacyArm.getValveTrips(), "identically on the legacy arm");
        assertEquals(0, rig.quadArm.getConfirmedRing(), "valve = full prefix reset");

        int n = rig.fire(cx, cz, VD, "valve recovery walk");
        rig.answerAll(Math.max(n, 0), 6_000L);
        assertTrue(rig.quadArm.getQuadRingSkips() > 0,
                "premise: the recovery walk over the converged disc engaged the fast path"
                        + " (interior clean rings confirm leaf-wise)");
        for (int w = 0; w < 200; w++) {
            int m = rig.fire(cx, cz, VD, "valve re-convergence " + w);
            if (m == 0) return;
            rig.answerAll(m, 6_100L + w);
        }
        fail("the disc must re-converge after the valve reset");
    }

    @Test
    void midSessionFastPathFlipStaysEquivalent() {
        // The kill switch is stateless by design: flipping the fast path mid-session must
        // not perturb the walk — an arm that flips every fire still matches the always-off
        // arm exactly (there is no retained fast-path state to flush; contrast the
        // enableScanPrefixRetention flip, which needs the scan-head flush).
        var columns = new ColumnStateMap();
        var flipping = scanner(20, true);
        boolean[] quadNow = {true};
        flipping.quadtreeScanEnabled = () -> quadNow[0];
        var alwaysOff = scanner(20, false);

        long[] fPos = new long[256], fTs = new long[256], oPos = new long[256], oTs = new long[256];
        var rng = new Random(7L);
        int cx = 0, cz = 0;
        for (int step = 0; step < 400; step++) {
            if (step % 3 == 0) quadNow[0] = !quadNow[0];
            if (rng.nextInt(10) == 0) {
                int d = 1 + rng.nextInt(2);
                cx += d;
                flipping.recenter(d);
                alwaysOff.recenter(d);
            }
            int f = flipping.maybeScan(cx, cz, VD, 0, 1000, 0L, Long.MAX_VALUE, -1, 1000,
                    () -> 0, columns, fPos, fTs);
            int o = alwaysOff.maybeScan(cx, cz, VD, 0, 1000, 0L, Long.MAX_VALUE, -1, 1000,
                    () -> 0, columns, oPos, oTs);
            assertEquals(o, f, "fire divergence at step " + step);
            if (f > 0) {
                for (int i = 0; i < f; i++) {
                    assertEquals(oPos[i], fPos[i], "pos[" + i + "] at step " + step);
                    assertEquals(oTs[i], fTs[i], "ts[" + i + "] at step " + step);
                }
                // Answer a random prefix of the walk so the state evolves.
                int answered = rng.nextInt(f + 1);
                for (int i = 0; i < answered; i++) columns.onReceived(fPos[i], 100L + step);
            }
            assertEquals(alwaysOff.getConfirmedRing(), flipping.getConfirmedRing(),
                    "confirmedRing at step " + step);
            assertEquals(alwaysOff.getReopenedRingCount(), flipping.getReopenedRingCount(),
                    "reopened at step " + step);
        }
    }

    @Test
    void armedFastCadenceFireDecisionsMatch() {
        // Review round 2 MINOR-3: the other scenarios never arm the adaptive cadence, so
        // every fire is the 20-tick fallback. Here both arms are armed identically
        // (noteDeclared + a shared outstanding supplier), so the FAST-fire ladder —
        // predictedWalkCost over confirmedRing/scanRing/truncation/reopened state — makes
        // real decisions that must agree tick by tick.
        var rig = new Rig(24, LSSConstants.MAX_BATCH_CHUNK_REQUESTS);
        int[] outstanding = {0};
        rig.quadArm.setOutstandingSupplier(() -> outstanding[0]);
        rig.legacyArm.setOutstandingSupplier(() -> outstanding[0]);
        int cx = 0, cz = 0;
        var rng = new Random(11L);
        for (int step = 0; step < 900; step++) {
            if (rng.nextInt(25) == 0) {
                cx += 1;
                rig.recenter(1);
            }
            if (rng.nextInt(40) == 0) {
                long p = PositionUtil.packPosition(cx + rng.nextInt(31) - 15,
                        cz + rng.nextInt(31) - 15);
                rig.dirty(p, cx, cz);
            }
            int n = rig.tick(cx, cz, VD, "armed step " + step);
            if (n >= 0) {
                rig.quadArm.noteDeclared(n);
                rig.legacyArm.noteDeclared(n);
                outstanding[0] = n;
                // Answer most of the batch promptly (>=95% completion arms the fast
                // trigger), leaving an occasional straggler.
                int answered = n - (n > 20 && rng.nextInt(4) == 0 ? 1 : 0);
                for (int i = 0; i < answered; i++) {
                    rig.columns.onReceived(rig.qPos[i], 100L + step);
                    outstanding[0]--;
                }
            }
        }
        assertEquals(rig.legacyArm.getFastScans(), rig.quadArm.getFastScans(),
                "fast-fire counts must match");
        assertTrue(rig.quadArm.getFastScans() > 0,
                "premise: the armed cadence actually produced fast fires");
    }

    @Test
    void retentionOffQuadOnMatchesLegacy() {
        // Review round 2 MINOR-4: the 2×2 seam quadrant retention-OFF × quad-ON was never
        // compared arm-vs-arm. Both arms run retention OFF (every reset collapses the
        // prefix); the quad arm's fast path must still match the legacy walk exactly —
        // post-collapse recovery walks are precisely where it engages hardest.
        var rig = new Rig(28, LSSConstants.MAX_BATCH_CHUNK_REQUESTS);
        rig.quadArm.prefixRetentionEnabled = () -> false;
        rig.legacyArm.prefixRetentionEnabled = () -> false;
        int cx = 0, cz = 0;
        for (int w = 0; w < 80; w++) {
            int n = rig.fire(cx, cz, VD, "retention-off warmup " + w);
            if (n == 0) break;
            rig.answerAll(n, 400L + w);
        }
        // Crossings now collapse the prefix every time — the legacy-hitch regime.
        for (int mv = 0; mv < 5; mv++) {
            cx += 1;
            rig.recenter(1);
            int n = rig.fire(cx, cz, VD, "retention-off crossing " + mv);
            rig.answerAll(Math.max(n, 0), 800L + mv);
        }
        // A dirty mark (known position) — retention off routes it through the
        // prefix-collapse arm of reopenRing on both scanners.
        long p = PositionUtil.packPosition(cx + 9, cz);
        rig.columns.onReceived(p, 1_000L);
        rig.dirty(p, cx, cz);
        int n = rig.fire(cx, cz, VD, "retention-off post-dirty");
        rig.answerAll(Math.max(n, 0), 1_200L);
        assertTrue(rig.quadArm.getQuadRingSkips() > 0,
                "premise: the fast path engaged on the collapse-recovery walks");
    }

    @Test
    void seededChaosMatchesExactly() {
        for (long seed : new long[]{3L, 99L, 20260819L}) {
            var rig = new Rig(28, 160); // small buffer: truncation churn included
            var rng = new Random(seed);
            int cx = 0, cz = 0;
            for (int step = 0; step < 700; step++) {
                switch (rng.nextInt(10)) {
                    case 0 -> {
                        int d = 1 + rng.nextInt(3);
                        cx += rng.nextBoolean() ? d : -d;
                        cz += rng.nextInt(2 * d + 1) - d;
                        rig.recenter(d);
                    }
                    case 1 -> {
                        long p = PositionUtil.packPosition(cx + rng.nextInt(41) - 20,
                                cz + rng.nextInt(41) - 20);
                        rig.dirty(p, cx, cz);
                    }
                    case 2 -> {
                        long p = PositionUtil.packPosition(cx + rng.nextInt(41) - 20,
                                cz + rng.nextInt(41) - 20);
                        rig.columns.onUpToDate(p);
                    }
                    case 3 -> {
                        long p = PositionUtil.packPosition(cx + rng.nextInt(41) - 20,
                                cz + rng.nextInt(41) - 20);
                        rig.columns.onIngestFailed(p);
                    }
                    default -> { /* just tick */ }
                }
                int n = rig.tick(cx, cz, VD, "chaos seed " + seed + " step " + step);
                if (n > 0) {
                    int answered = rng.nextInt(n + 1);
                    for (int i = 0; i < answered; i++) {
                        rig.columns.onReceived(rig.qPos[i], 100L + step);
                    }
                }
            }
        }
    }
}
