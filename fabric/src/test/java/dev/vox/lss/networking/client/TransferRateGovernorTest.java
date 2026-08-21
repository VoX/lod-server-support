package dev.vox.lss.networking.client;

import dev.vox.lss.common.processing.PingBackstop;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client transfer governor's control-loop pins (adaptive-transfer-rate-plan.md,
 * Mechanism A — both plan-review lenses' fix list plus the implementation review's
 * MAJOR-1 offer-backing and MINOR-2/3 amendments). Pure unit suite: the governor's
 * inputs all arrive through {@code tick(...)} and the injectable clock.
 */
class TransferRateGovernorTest {

    private static final long KB = 1024;
    private static final long STEP = TransferRateGovernor.STEP_BYTES_PER_SEC;
    private static final long INTERVAL = TransferRateGovernor.INTERVAL_MILLIS;
    private static final int CONGESTED_PING = 2_000; // baseline 50 → excess ~1950
    private static final int NORMAL_PING = 50;

    /** Full-signature tick with a generously offer-backed declared count (declared
     *  rides the columns counter ×100, so every interval's offer dwarfs the governed
     *  rate). Tests that pin the offer-backing term pass declared explicitly. */
    private static void tick(TransferRateGovernor g, long now, long bytes, long cols,
                             int awaiting, boolean halted, int ping) {
        // declared rides cols x100 (generously offer-backed); answered rides cols x100
        // as well so the pre-existing pins keep their byte-denominated semantics (an
        // answered ratio of 1.0 — tests that pin the answered rung pass it explicitly).
        g.tick(now, bytes, cols, cols * 100, cols * 100, awaiting, halted, ping, true);
    }

    /** Seeds a 50 ms ping baseline, then TWO consecutive congested slow intervals →
     *  engaged (the round-5 debounce: one interval is a transient, never an
     *  engagement). Cumulative bytes double so measured stays bytesPerInterval/2 s
     *  per interval; callers continuing the timeline resume from 2×bytesPerInterval. */
    private long engageAt(TransferRateGovernor g, long t, long bytesPerInterval) {
        tick(g, t, 0, 0, 1, false, NORMAL_PING);
        tick(g, t + INTERVAL, bytesPerInterval, 10, 1, false, CONGESTED_PING);
        assertFalse(g.isEngaged(), "one congested interval is a transient, not congestion");
        tick(g, t + 2 * INTERVAL, 2 * bytesPerInterval, 20, 1, false, CONGESTED_PING);
        assertTrue(g.isEngaged(), "the second consecutive congested interval engages");
        return t + 2 * INTERVAL;
    }

    // ---- Engagement gate ----

    @Test
    void demandLimitedShortfallWithNormalPingNeverEngages() {
        // Review M1's headline: measured rate equals the demand/serve rate whenever the
        // link is not the bottleneck — without the congestion conjunct every walking
        // player on a healthy link would engage. 100 KB/s with normal ping, forever.
        var g = new TransferRateGovernor();
        long t = 0;
        long bytes = 0;
        for (int i = 0; i < 50; i++) {
            tick(g, t, bytes, i * 5L, 1, false, NORMAL_PING);
            t += INTERVAL;
            bytes += 200 * KB;
        }
        assertFalse(g.isEngaged(), "normal ping must never engage, whatever the rate");
        assertEquals(0, g.sustainedColumnsPerSecond(), "no governed cap while unengaged");
    }

    @Test
    void congestedShortfallEngagesAtMeasuredMinusHalfStep() {
        var g = new TransferRateGovernor();
        tick(g, 0, 0, 0, 1, false, NORMAL_PING);
        // 800 KB over 2 s = 400 KB/s measured, ping excess ~1950 ms — twice (debounce).
        tick(g, INTERVAL, 800 * KB, 100, 1, false, CONGESTED_PING);
        tick(g, 2 * INTERVAL, 1600 * KB, 200, 1, false, CONGESTED_PING);
        assertTrue(g.isEngaged());
        assertEquals(400 * KB - STEP / 2, g.getDesiredBytesPerSec(),
                "bootstrap-by-shortfall: desired = measured − STEP/2");
    }

    @Test
    void engageRequiresTwoConsecutiveCongestedIntervals() {
        // Round-5 review M1: the ping input is a raw 1 Hz probe sample now — a single
        // GC pause / WiFi burst crossing 250 ms must not buy a sticky engagement
        // (there is no ping-normal escape anymore). A normal interval between two
        // congested ones resets the pending streak.
        var g = new TransferRateGovernor();
        tick(g, 0, 0, 0, 1, false, NORMAL_PING);
        tick(g, INTERVAL, 200 * KB, 20, 1, false, CONGESTED_PING);
        assertFalse(g.isEngaged(), "one congested interval never engages");
        tick(g, 2 * INTERVAL, 400 * KB, 40, 1, false, NORMAL_PING); // transient over
        tick(g, 3 * INTERVAL, 600 * KB, 60, 1, false, CONGESTED_PING);
        assertFalse(g.isEngaged(), "a broken streak restarts the debounce");
        tick(g, 4 * INTERVAL, 800 * KB, 80, 1, false, CONGESTED_PING);
        assertTrue(g.isEngaged(), "two consecutive congested intervals engage");
    }

    @Test
    void idleIntervalNeverAdjusts() {
        // The DH idle-collapse fix: no demand (awaiting empty) → non-qualifying even
        // with congested ping and bytes flowing (a trailing delivery).
        var g = new TransferRateGovernor();
        tick(g, 0, 0, 0, 0, false, NORMAL_PING);
        tick(g, INTERVAL, 100 * KB, 10, 0, false, CONGESTED_PING);
        assertFalse(g.isEngaged(), "an idle interval must never engage");
    }

    @Test
    void haltOverlappedIntervalIsNonQualifying() {
        // Review m1 (integration): a #71 halt keeps the awaiting set populated while
        // the client deliberately stops ingesting — the depressed tail rate is not a
        // link measurement.
        var g = new TransferRateGovernor();
        tick(g, 0, 0, 0, 1, false, NORMAL_PING); // seeds interval + 50 ms baseline
        tick(g, INTERVAL / 2, 0, 0, 1, true, CONGESTED_PING); // halt mid-interval
        tick(g, INTERVAL, 100 * KB, 10, 1, false, CONGESTED_PING);
        assertFalse(g.isEngaged(), "a halt-overlapped interval must not engage");
        // The same shape WITHOUT the halt engages (two clean congested intervals —
        // the debounce) — proves the halt was the gate.
        tick(g, 2 * INTERVAL, 200 * KB, 20, 1, false, CONGESTED_PING);
        tick(g, 3 * INTERVAL, 300 * KB, 30, 1, false, CONGESTED_PING);
        assertTrue(g.isEngaged());
    }

    @Test
    void negativeCounterDeltaIsNonQualifyingAndReseeds() {
        // Review m3: a session reset zeroes the gate counters mid-interval.
        var g = new TransferRateGovernor();
        tick(g, 0, 500 * KB, 50, 1, false, NORMAL_PING);
        tick(g, INTERVAL, 100 * KB, 10, 1, false, CONGESTED_PING); // ran backwards
        assertFalse(g.isEngaged(), "a reset-spanning interval must not engage");
        // The next TWO intervals measure cleanly from the reseeded start (debounce).
        tick(g, 2 * INTERVAL, 300 * KB, 30, 1, false, CONGESTED_PING);
        tick(g, 3 * INTERVAL, 500 * KB, 50, 1, false, CONGESTED_PING);
        assertTrue(g.isEngaged(), "the clean intervals after the reseed engage");
    }

    @Test
    void fastMeasuredRateNeverEngages() {
        var g = new TransferRateGovernor();
        tick(g, 0, 0, 0, 1, false, NORMAL_PING);
        // 8 MB/s per interval — above ENGAGE_BELOW even with congested ping, twice
        // (so the debounce is provably not what held it back).
        tick(g, INTERVAL, 16_384 * KB, 500, 1, false, CONGESTED_PING);
        tick(g, 2 * INTERVAL, 32_768 * KB, 1000, 1, false, CONGESTED_PING);
        assertFalse(g.isEngaged(), "sessions measuring above the threshold never engage");
    }

    // ---- Engaged AIMD ----

    @Test
    void keptUpStepsUpAndShortfallCutsWithTheAbsoluteBand() {
        var g = new TransferRateGovernor();
        long t = 0;
        tick(g, t, 0, 0, 1, false, NORMAL_PING);
        tick(g, t += INTERVAL, 800 * KB, 100, 1, false, CONGESTED_PING);
        tick(g, t += INTERVAL, 1600 * KB, 200, 1, false, CONGESTED_PING); // debounce
        long desired = 400 * KB - STEP / 2; // 272 KB/s
        assertEquals(desired, g.getDesiredBytesPerSec());
        // Kept-up: measured within STEP/4 below desired → +STEP (the ABSOLUTE band,
        // review M3 — a multiplicative 0.9 band ratchets standing queue).
        long bytes = 1600 * KB;
        long measuredBps = desired - STEP / 4 + KB; // just inside the band
        bytes += measuredBps * 2;
        tick(g, t += INTERVAL, bytes, 200, 1, false, CONGESTED_PING);
        desired += STEP;
        assertEquals(desired, g.getDesiredBytesPerSec(), "kept-up adds STEP");
        // Shortfall (offer-backed via the helper): measured below desired − STEP/4 →
        // cut to measured − STEP/2.
        long slowBps = desired - STEP / 4 - KB;
        bytes += slowBps * 2;
        tick(g, t += INTERVAL, bytes, 300, 1, false, CONGESTED_PING);
        assertEquals(Math.max(slowBps - STEP / 2, TransferRateGovernor.MIN_RATE_BYTES_PER_SEC),
                g.getDesiredBytesPerSec(), "shortfall cuts to measured − STEP/2");
    }

    @Test
    void underOfferedShortfallFreezesInsteadOfCutting() {
        // Impl review MAJOR-1: the actuator is a stop-and-wait window — a cadence
        // hold, the walk-cost coverage limit, actionable retries, or an RTT-stretched
        // window all collapse the ACHIEVED rate with no link involvement. A shortfall
        // interval that did not OFFER ≥ ¾ of the governed rate is the window's doing
        // and must freeze, not cut (the ratchet-to-floor defect).
        var g = new TransferRateGovernor();
        long t = 0;
        long declared = 0;
        g.tick(t, 0, 0, declared, declared, 1, false, NORMAL_PING, true);
        g.tick(t += INTERVAL, 800 * KB, 100, declared += 200, declared, 1, false,
                CONGESTED_PING, true);
        g.tick(t += INTERVAL, 1600 * KB, 200, declared += 200, declared, 1, false,
                CONGESTED_PING, true); // debounce
        long desired = g.getDesiredBytesPerSec(); // 272 KB/s; EWMA 8 KB → R = 34
        assertEquals(34, g.sustainedColumnsPerSecond());
        // A quarter-rate interval (68 KB/s) with almost nothing OFFERED (10 declared
        // vs the ~51 the ¾ term requires): frozen.
        long bytes = 1600 * KB + 68 * KB * 2;
        g.tick(t += INTERVAL, bytes, 217, declared += 10, declared, 1, false,
                CONGESTED_PING, true);
        assertEquals(desired, g.getDesiredBytesPerSec(),
                "an under-offered shortfall must freeze desired");
        // The SAME measured rate with a full offer is genuine link evidence: cut.
        bytes += 68 * KB * 2;
        g.tick(t += INTERVAL, bytes, 334, declared += 200, declared, 1, false,
                CONGESTED_PING, true);
        assertEquals(TransferRateGovernor.MIN_RATE_BYTES_PER_SEC,
                g.getDesiredBytesPerSec(),
                "the offer-backed twin of the same interval cuts");
    }

    @Test
    void everyFourthKeptUpIsADrainInterval() {
        // Review M3's second defect + live round 2's retune: an AIMD equilibrium
        // hovers AT capacity (measured ~500 ms standing queue live), so the bleed
        // runs every 4th kept-up at STEP/2 depth.
        var g = new TransferRateGovernor();
        long t = 0;
        tick(g, t, 0, 0, 1, false, NORMAL_PING);
        tick(g, t += INTERVAL, 800 * KB, 100, 1, false, CONGESTED_PING);
        tick(g, t += INTERVAL, 1600 * KB, 200, 1, false, CONGESTED_PING); // debounce
        long bytes = 1600 * KB;
        // Feed exactly-kept-up intervals (measured = desired): 3 step up, the 4th drains.
        for (int keptUp = 1; keptUp <= TransferRateGovernor.DRAIN_EVERY_KEPT_UP; keptUp++) {
            long desiredBefore = g.getDesiredBytesPerSec();
            long measuredBps = desiredBefore;
            bytes += measuredBps * 2;
            tick(g, t += INTERVAL, bytes, 200 + keptUp * 10L, 1, false, CONGESTED_PING);
            if (keptUp < TransferRateGovernor.DRAIN_EVERY_KEPT_UP) {
                assertEquals(desiredBefore + STEP, g.getDesiredBytesPerSec(),
                        "kept-up " + keptUp + " probes up");
            } else {
                assertEquals(measuredBps - STEP / 2, g.getDesiredBytesPerSec(),
                        "the 4th consecutive kept-up bleeds standing queue instead");
            }
        }
    }

    @Test
    void movementIntervalHoldsTheClimb() {
        // Live round 2: the ~1500 ms movement spikes were the governor climbing +STEP
        // into the window where vanilla's own chunk bursts compete for the link. A
        // kept-up interval that saw a chunk crossing HOLDS desired; shortfall during
        // movement still cuts.
        var g = new TransferRateGovernor();
        long t = 0;
        tick(g, t, 0, 0, 1, false, NORMAL_PING);
        tick(g, t += INTERVAL, 800 * KB, 100, 1, false, CONGESTED_PING);
        tick(g, t += INTERVAL, 1600 * KB, 200, 1, false, CONGESTED_PING); // debounce
        long desired = g.getDesiredBytesPerSec();
        long bytes = 1600 * KB;
        // Kept-up interval WITH movement: held.
        bytes += desired * 2;
        g.noteMovement();
        tick(g, t += INTERVAL, bytes, 200, 1, false, CONGESTED_PING);
        assertEquals(desired, g.getDesiredBytesPerSec(),
                "a moving kept-up interval must not probe up");
        // The same shape WITHOUT movement climbs — the hold was the movement's doing.
        bytes += desired * 2;
        tick(g, t += INTERVAL, bytes, 300, 1, false, CONGESTED_PING);
        assertEquals(desired + STEP, g.getDesiredBytesPerSec(),
                "a stationary kept-up interval probes up");
        // Shortfall during movement still cuts (the hold pauses only the up-probe).
        long slow = (desired + STEP) / 2;
        bytes += slow * 2;
        g.noteMovement();
        tick(g, t += INTERVAL, bytes, 400, 1, false, CONGESTED_PING);
        assertEquals(Math.max(slow - STEP / 2, TransferRateGovernor.MIN_RATE_BYTES_PER_SEC),
                g.getDesiredBytesPerSec(), "movement never suppresses a genuine cut");
    }

    @Test
    void drainIntervalNeverRaisesDesired() {
        // Impl review MINOR-3: when the size EWMA lags a regime change, measured can
        // exceed desired — a measured-anchored drain would RAISE desired several-fold
        // in one step, the opposite of a bleed. The drain anchors at
        // min(desired, measured).
        var g = new TransferRateGovernor();
        long t = 0;
        tick(g, t, 0, 0, 1, false, NORMAL_PING);
        tick(g, t += INTERVAL, 800 * KB, 100, 1, false, CONGESTED_PING);
        tick(g, t += INTERVAL, 1600 * KB, 200, 1, false, CONGESTED_PING); // debounce
        long bytes = 1600 * KB;
        for (int keptUp = 1; keptUp < TransferRateGovernor.DRAIN_EVERY_KEPT_UP; keptUp++) {
            bytes += g.getDesiredBytesPerSec() * 2;
            tick(g, t += INTERVAL, bytes, 200 + keptUp * 10L, 1, false, CONGESTED_PING);
        }
        long desiredBefore = g.getDesiredBytesPerSec();
        // The drain-cadence kept-up measures DOUBLE desired (EWMA-lag shape): must
        // bleed down from desired, never jump up toward measured.
        bytes += desiredBefore * 4;
        tick(g, t += INTERVAL, bytes, 300, 1, false, CONGESTED_PING);
        assertEquals(desiredBefore - STEP / 2, g.getDesiredBytesPerSec(),
                "the drain anchors at min(desired, measured)");
    }

    @Test
    void desiredFloorsAtMinRate() {
        var g = new TransferRateGovernor();
        long t = 0;
        tick(g, t, 0, 0, 1, false, NORMAL_PING);
        // Engage at a crawl: 20 KB over 2 s = 10 KB/s (twice — the debounce).
        tick(g, t += INTERVAL, 20 * KB, 4, 1, false, CONGESTED_PING);
        tick(g, t += INTERVAL, 40 * KB, 8, 1, false, CONGESTED_PING);
        assertEquals(TransferRateGovernor.MIN_RATE_BYTES_PER_SEC, g.getDesiredBytesPerSec(),
                "desired floors at MIN_RATE, never below");
    }

    // ---- Disengagement ----

    @Test
    void sustainedRateAboveTheEngageThresholdDisengages() {
        // Disengage path (a): the loop probed clear of ENGAGE_BELOW and stayed there
        // for DISENGAGE_RATE_INTERVALS qualifying intervals — the link recovered.
        var g = new TransferRateGovernor();
        long t = 0;
        tick(g, t, 0, 0, 1, false, NORMAL_PING);
        // Engage at 3.5 MB/s (just under the 4 MB threshold), twice — the debounce.
        tick(g, t += INTERVAL, 7_168 * KB, 700, 1, false, CONGESTED_PING);
        tick(g, t += INTERVAL, 14_336 * KB, 1400, 1, false, CONGESTED_PING);
        assertTrue(g.isEngaged());
        long bytes = 14_336 * KB;
        long cols = 1400;
        // Feed measured = desired: the loop climbs +STEP per kept-up (every 4th
        // drains −STEP/2), crossing 4 MB in a few intervals, then needs 10 more
        // qualifying intervals above it. 20 is comfortably enough.
        int intervals = 0;
        while (g.isEngaged() && intervals++ < 20) {
            bytes += g.getDesiredBytesPerSec() * 2;
            cols += 100;
            tick(g, t += INTERVAL, bytes, cols, 1, false, CONGESTED_PING);
        }
        assertFalse(g.isEngaged(), "sustained desired above the threshold disengages");
        assertEquals(0, g.sustainedColumnsPerSecond(), "the cap drops entirely");
    }

    @Test
    void pingNormalNeverDisengagesWhileTheCapBinds() {
        // Live round 5 INVERTED review m10's ping-normal disengage: normal ping under
        // a binding cap is the governor's own success, not link health. The traced
        // sawtooth — ~1 min of governed calm, path (b) fires, the un-capped link runs
        // away to 4.7 s ping for 25 s (the tab-ping blind spot) — is what this pin
        // prevents. 100 converged normal-ping intervals (~3 min): still engaged.
        var g = new TransferRateGovernor();
        long t = engageAt(g, 0, 300 * KB);
        long bytes = 600 * KB; // engageAt's cumulative endpoint
        for (int i = 0; i < 100; i++) {
            tick(g, t += INTERVAL, bytes, 30, 0, false, NORMAL_PING);
            assertTrue(g.isEngaged(), "normal ping alone must NEVER disengage (i=" + i + ")");
        }
        assertTrue(g.sustainedColumnsPerSecond() > 0, "the cap stays armed");
    }

    @Test
    void bindingCapWithNormalPingNeverDisengagesWhileServerLimited() {
        // The qualifying-interval variant of the deletion pin (round-5 review m3):
        // bytes flowing at a server-limited 300 KB/s, awaiting > 0, normal ping
        // throughout. The AIMD oscillates desired around the achieved rate (kept-up
        // climb → offer-backed shortfall cut) and never crosses the 4 MB/s rate
        // disengage — ping-normal must not be an exit for this population either.
        var g = new TransferRateGovernor();
        long t = engageAt(g, 0, 600 * KB);
        long bytes = 1200 * KB;
        long cols = 20;
        for (int i = 0; i < 40; i++) {
            bytes += 300 * KB * 2; // measured 300 KB/s every interval
            cols += 30;
            tick(g, t += INTERVAL, bytes, cols, 1, false, NORMAL_PING);
            assertTrue(g.isEngaged(),
                    "a server-limited binding cap must stay engaged (i=" + i + ")");
        }
        assertTrue(g.getDesiredBytesPerSec() < TransferRateGovernor.ENGAGE_BELOW_BYTES_PER_SEC,
                "the oscillation stays below the rate-disengage threshold");
    }

    @Test
    void adoptFromCoversEveryStateFieldOrDocumentsTheReset() throws Exception {
        // Final-review B-N3: a future governor field silently missing from adoptFrom
        // would regress the manager-rebuild carry invisibly (the gate pin asserts only
        // engaged + desired). Every instance field must be either COPIED by adoptFrom
        // or on the documented reseed list (the interval accumulator + per-interval
        // latches + the injectable clock).
        var reseeded = java.util.Set.of(
                "intervalSeeded", "intervalStartMillis", "intervalStartWireBytes",
                "intervalStartColumns", "intervalStartDeclared", "intervalStartAnswered",
                "awaitingAtStart", "haltSeenThisInterval", "movementSeenThisInterval",
                "awaitingSeenThisInterval", "windowLimitedSeenThisInterval", "clock");
        var src = new TransferRateGovernor();
        var dstProbe = new TransferRateGovernor();
        int seed = 7;
        for (var f : TransferRateGovernor.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (reseeded.contains(f.getName())) continue;
            f.setAccessible(true);
            Class<?> ty = f.getType();
            if (ty == int.class) f.setInt(src, ++seed);
            else if (ty == long.class) f.setLong(src, ++seed);
            else if (ty == boolean.class) f.setBoolean(src, true);
            else if (ty == double.class) f.setDouble(src, ++seed + 0.5);
            else if (ty.isEnum()) {
                // Deterministically DIFFERENT from a fresh instance's value (review:
                // a counter-derived pick can land on the default and go vacuous when
                // field order shifts).
                Object fresh = f.get(dstProbe);
                Object[] constants = ty.getEnumConstants();
                Object pick = constants[0].equals(fresh) ? constants[1] : constants[0];
                f.set(src, pick);
            }
            else org.junit.jupiter.api.Assertions.fail("unhandled field type " + ty
                    + " for '" + f.getName() + "' — extend adoptFrom AND this pin");
        }
        var dst = new TransferRateGovernor();
        dst.adoptFrom(src);
        for (var f : TransferRateGovernor.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (reseeded.contains(f.getName())) continue;
            f.setAccessible(true);
            org.junit.jupiter.api.Assertions.assertEquals(f.get(src), f.get(dst),
                    "'" + f.getName() + "' must be copied by adoptFrom (or added to the"
                            + " documented reseed list)");
        }
    }

    // ---- Vanilla-first (live round 5: the fly-then-stop desync) ----

    /** Engaged at ~300 KB/s with the missing floor learned at 20 (the view-edge ring). */
    private long engageWithMissingFloor(TransferRateGovernor g) {
        g.noteMissingVanilla(20);
        long t = engageAt(g, 0, 600 * KB);
        return t;
    }

    @Test
    void missingVanillaExcessCutsEvenWhenKeptUp() {
        // The precondition measured live: governed 590 KB/s of a 750 KB/s link, vanilla
        // 20+ chunks behind (miss 41-43 over the 20 floor), silent movement rejections.
        // A kept-up interval (measured = desired, which would otherwise STEP UP) must
        // CUT once the excess crosses the margin.
        var g = new TransferRateGovernor();
        long t = engageWithMissingFloor(g);
        long desired = g.getDesiredBytesPerSec();
        g.noteMissingVanilla(20 + TransferRateGovernor.MISSING_VANILLA_CUT_EXCESS);
        long bytes = 1200 * KB + desired * 2; // measured = desired: kept up
        tick(g, t + INTERVAL, bytes, 60, 1, false, CONGESTED_PING);
        assertTrue(g.getDesiredBytesPerSec() < desired,
                "vanilla-behind must cut, never climb");
        assertEquals(Math.max(desired - STEP / 2, TransferRateGovernor.MIN_RATE_BYTES_PER_SEC),
                g.getDesiredBytesPerSec(), "the cut lands at measured − STEP/2");
    }

    @Test
    void missingVanillaBelowTheMarginStepsNormally() {
        var g = new TransferRateGovernor();
        long t = engageWithMissingFloor(g);
        long desired = g.getDesiredBytesPerSec();
        g.noteMissingVanilla(20 + TransferRateGovernor.MISSING_VANILLA_CUT_EXCESS - 1);
        long bytes = 1200 * KB + desired * 2;
        tick(g, t + INTERVAL, bytes, 60, 1, false, CONGESTED_PING);
        assertEquals(desired + STEP, g.getDesiredBytesPerSec(),
                "below the margin the kept-up climb proceeds");
    }

    @Test
    void permanentEdgeRingFloorNeverReadsAsExcess() {
        // The session MIN learns the server-view-distance edge ring (~20 on the rig):
        // a steady 20 is the floor itself, excess 0 — cutting on it would punish every
        // session with a permanent missing ring.
        var g = new TransferRateGovernor();
        long t = engageWithMissingFloor(g);
        long desired = g.getDesiredBytesPerSec();
        g.noteMissingVanilla(20);
        long bytes = 1200 * KB + desired * 2;
        tick(g, t + INTERVAL, bytes, 60, 1, false, CONGESTED_PING);
        assertEquals(desired + STEP, g.getDesiredBytesPerSec(),
                "the learned floor is not excess");
    }

    @Test
    void spuriouslyLowMissingFloorHealsByDrift() {
        // Round-5 review M2: the floor is a MIN over a SETTINGS-dependent baseline —
        // a render-distance change can leave a stale-low floor reading the new
        // permanent edge ring as perpetual excess. The per-interval drift raises the
        // floor toward the sample, so the spurious cut disarms and the climb resumes
        // within ~a dozen intervals instead of pinning MIN forever.
        var g = new TransferRateGovernor();
        g.noteMissingVanilla(0); // old settings: floor 0
        long t = engageAt(g, 0, 600 * KB);
        g.noteMissingVanilla(20); // new settings: the permanent ring reads "excess 20"
        long bytes = 1200 * KB;
        long cols = 20;
        boolean climbed = false;
        long prev = g.getDesiredBytesPerSec();
        for (int i = 0; i < 20; i++) {
            bytes += g.getDesiredBytesPerSec() * 2; // measured = desired: kept up
            cols += 30;
            tick(g, t += INTERVAL, bytes, cols, 1, false, CONGESTED_PING);
            if (g.getDesiredBytesPerSec() > prev) { climbed = true; break; }
            prev = g.getDesiredBytesPerSec();
        }
        assertTrue(climbed, "the drifted floor must disarm the spurious cut");
    }

    @Test
    void vanillaFirstCutNeverRaisesDesired() {
        // Round-5 review m2: the cut anchors min(desired, measured) — the interval
        // right after a deep cut measures the pre-cut in-flight tail (measured well
        // above the new desired), and a bare measured anchor would RAISE the cap
        // mid-shed.
        var g = new TransferRateGovernor();
        g.noteMissingVanilla(20);
        long t = engageAt(g, 0, 1600 * KB); // measured 800 KB/s → desired 672 KB/s
        g.noteMissingVanilla(40); // deep excess (drift erodes slowly at gap/8)
        long bytes = 3200 * KB;
        bytes += 672 * KB * 2; // measured = desired → first cut lands 544 KB/s
        tick(g, t += INTERVAL, bytes, 60, 1, false, CONGESTED_PING);
        assertEquals(544 * KB, g.getDesiredBytesPerSec(), "first cut: anchor − STEP/2");
        bytes += 672 * KB * 2; // the pre-cut tail still delivers ABOVE desired
        tick(g, t += INTERVAL, bytes, 90, 1, false, CONGESTED_PING);
        assertEquals(416 * KB, g.getDesiredBytesPerSec(),
                "a tail above desired keeps the shed moving DOWN (a bare measured"
                        + " anchor would hold at 544)");
    }

    @Test
    void missingVanillaNeverEngagesAnUnengagedSession() {
        // Engaged-only by design: fast links (which never engage) must be untouched —
        // vanilla briefly behind during flight on a LAN is normal, not congestion.
        var g = new TransferRateGovernor();
        tick(g, 0, 0, 0, 1, false, NORMAL_PING);
        g.noteMissingVanilla(0);
        g.noteMissingVanilla(200);
        tick(g, INTERVAL, 300 * KB, 30, 1, false, NORMAL_PING);
        assertFalse(g.isEngaged(), "missing-vanilla excess is never an engage trigger");
    }

    @Test
    void missingFloorRelearnsAfterDimensionChange() {
        // A new dimension is a new view: a stale low floor would read the whole fresh
        // view as vanilla-behind and cut for nothing. onDimensionChange clears both
        // samples; a post-change interval at the new dimension's floor steps normally.
        var g = new TransferRateGovernor();
        g.noteMissingVanilla(0); // old dimension's floor: 0
        long t = engageAt(g, 0, 600 * KB);
        g.onDimensionChange(); // disengages (control state dies; measurements survive)
        // Re-engage in the new dimension off the surviving ping baseline (the unseeded
        // interval re-bases the cumulative counters, so absolute values restart fine).
        t = engageAt(g, t + INTERVAL, 600 * KB);
        long desired = g.getDesiredBytesPerSec();
        g.noteMissingVanilla(40); // the new view: first sample seeds the new floor
        long bytes = 1200 * KB + desired * 2;
        tick(g, t + INTERVAL, bytes, 60, 1, false, CONGESTED_PING);
        assertEquals(desired + STEP, g.getDesiredBytesPerSec(),
                "the first post-change sample is the new floor, not excess");
    }

    // ---- Size estimator + conversion ----

    @Test
    void conversionFloorsAtOneColumnPerSecond() {
        // Review m2/m5: MIN_RATE over a >64 KB column EWMA would integer-convert to the
        // <=0 OFF sentinel — and a 0 budget kills the walk (no declaration ever).
        var g = new TransferRateGovernor();
        tick(g, 0, 0, 0, 1, false, NORMAL_PING);
        // 2 columns of 100 KB each: EWMA seeds at ~100 KB; measured 100 KB/s → engage
        // at MIN_RATE. MIN_RATE/100KB < 1 → floors at 1.
        tick(g, INTERVAL, 200 * KB, 2, 1, false, CONGESTED_PING);
        tick(g, 2 * INTERVAL, 400 * KB, 4, 1, false, CONGESTED_PING); // debounce
        assertTrue(g.isEngaged());
        assertEquals(1, g.sustainedColumnsPerSecond(), "conversion floors at 1 col/s");
        assertEquals(1, g.burstColumnsPerSecond(), "burst floors at 1 too");
    }

    @Test
    void engagedWithNoSizeSampleSuppliesNoCap() {
        // The pre-first-sample division guard: bytes flowed but no column count moved
        // (deltaColumns 0) — engaged with no size estimate must supply NO cap, never
        // a garbage conversion.
        var g = new TransferRateGovernor();
        tick(g, 0, 0, 0, 1, false, NORMAL_PING);
        tick(g, INTERVAL, 200 * KB, 0, 1, false, CONGESTED_PING);
        tick(g, 2 * INTERVAL, 400 * KB, 0, 1, false, CONGESTED_PING); // debounce
        assertTrue(g.isEngaged(), "byte flow with congested ping engages");
        assertEquals(-1.0, g.getSizeEstimateForTest(), 1e-9, "no sample yet");
        assertEquals(0, g.sustainedColumnsPerSecond(), "no estimate = no cap");
        assertEquals(0, g.burstColumnsPerSecond(), "no estimate = no burst cap");
    }

    @Test
    void burstCapIsAQuarterOfSustainedRoundedUp() {
        // Review M2's seam split: burst = ceil(R/4) so the spacing gate equilibrates
        // at the 5-tick floor — 4 Hz quarter-batches.
        var g = new TransferRateGovernor();
        tick(g, 0, 0, 0, 1, false, NORMAL_PING);
        // 100 columns × 8 KB = 800 KB over 2 s: EWMA ~8 KB, desired 272 KB/s → R = 34.
        tick(g, INTERVAL, 800 * KB, 100, 1, false, CONGESTED_PING);
        tick(g, 2 * INTERVAL, 1600 * KB, 200, 1, false, CONGESTED_PING); // debounce
        assertEquals(34, g.sustainedColumnsPerSecond());
        assertEquals(9, g.burstColumnsPerSecond(), "ceil(34/4) = 9");
    }

    @Test
    void sizeEstimatorRisesFastAndDecaysSlowly() {
        // Review M4: bimodal sizes (ghost-clears vs terrain). After an ocean run the
        // estimate must jump most of the way up on the FIRST terrain interval
        // (under-counting R instead of over-bursting), and terrain→ocean must decay
        // slowly rather than collapse.
        var g = new TransferRateGovernor();
        long t = 0;
        long bytes = 0;
        long cols = 0;
        tick(g, t, bytes, cols, 1, false, NORMAL_PING);
        // Ocean: 100 columns of 1 KB.
        bytes += 100 * KB;
        cols += 100;
        tick(g, t += INTERVAL, bytes, cols, 1, false, NORMAL_PING);
        // Terrain: 50 columns of 16 KB.
        bytes += 50 * 16 * KB;
        cols += 50;
        tick(g, t += INTERVAL, bytes, cols, 1, false, NORMAL_PING);
        // EWMA after fast-up from 1 KB toward 16 KB: 1 + 0.5*(16−1) = 8.5 KB — most
        // of the way up in ONE interval (a symmetric slow alpha would sit at ~1.75 KB
        // and over-burst ~5x on the next batch).
        assertEquals(8.5 * KB, g.getSizeEstimateForTest(), 1.0,
                "terrain after ocean must raise the estimate fast");
        // Ocean again: a 1 KB-mean interval decays SLOWLY (alpha 0.05): 8.5 − 0.05*7.5
        // = 8.125 KB — never a collapse back to ghost-clear size.
        bytes += 40 * KB;
        cols += 40;
        tick(g, t += INTERVAL, bytes, cols, 1, false, NORMAL_PING);
        assertEquals(8.5 * KB - 0.05 * (8.5 * KB - 1 * KB), g.getSizeEstimateForTest(), 1.0,
                "one ocean interval must not collapse the size estimate");
    }

    // ---- Gates and lifecycle ----

    @Test
    void inactiveHardResetsAndSuppliesNoCap() {
        var g = new TransferRateGovernor();
        long t = engageAt(g, 0, 300 * KB);
        assertTrue(g.isEngaged());
        // Kill switch / legacy session: active=false hard-resets — no stale cap.
        g.tick(t + INTERVAL, 400 * KB, 40, 4_000, 4_000, 1, false, CONGESTED_PING, false);
        assertFalse(g.isEngaged());
        assertEquals(0, g.sustainedColumnsPerSecond());
    }

    @Test
    void harnessJvmPropertiesGateTheGovernorOff() {
        // Integration review M1: soak configs create sub-threshold qualifying intervals
        // (bandwidth-throttle caps at 256 KB/s; superflat columns keep byte rates low),
        // and a governed want-set breaks premises calibrated to the constant budget.
        // Both harness properties gate, independently.
        for (String prop : new String[] {"lss.soak", "lss.benchmark"}) {
            System.setProperty(prop, "true");
            try {
                var g = new TransferRateGovernor();
                tick(g, 0, 0, 0, 1, false, NORMAL_PING);
                tick(g, INTERVAL, 300 * KB, 30, 1, false, CONGESTED_PING);
                assertFalse(g.isEngaged(), "-D" + prop + " must gate the governor off");
            } finally {
                System.clearProperty(prop);
            }
        }
    }

    @Test
    void resetKillsEverythingIncludingBaselineAndEstimator() {
        var g = new TransferRateGovernor();
        long t = engageAt(g, 0, 300 * KB);
        assertTrue(g.getSizeEstimateForTest() > 0, "engaged run seeded the estimator");
        g.reset();
        assertFalse(g.isEngaged());
        assertEquals(0, g.sustainedColumnsPerSecond());
        assertEquals(-1.0, g.getSizeEstimateForTest(), 1e-9,
                "the size estimator dies with the session");
        // After reset the baseline reseeds — a formerly-congested reading seeds the NEW
        // baseline, so the same ping no longer reads as excess and cannot engage.
        tick(g, t += INTERVAL, 0, 0, 1, false, CONGESTED_PING);
        tick(g, t += INTERVAL, 300 * KB, 30, 1, false, CONGESTED_PING);
        assertFalse(g.isEngaged(),
                "post-reset the baseline reseeds from the first sample — no excess");
    }

    @Test
    void dimensionChangeKeepsTheMeasurementsAndDropsTheCap() {
        // Impl review MINOR-2: same connection, same link — a full reset would reseed
        // the ping baseline from the CONGESTED current reading and the engagement
        // conjunct could never fire again. The measurements survive; control drops.
        var g = new TransferRateGovernor();
        long t = engageAt(g, 0, 300 * KB);
        double estimate = g.getSizeEstimateForTest();
        assertTrue(estimate > 0);
        g.onDimensionChange();
        assertFalse(g.isEngaged(), "the cap drops at a dimension change");
        assertEquals(0, g.sustainedColumnsPerSecond());
        assertEquals(estimate, g.getSizeEstimateForTest(), 1e-9,
                "the size estimate survives");
        // Re-engagement uses the KEPT 50 ms baseline: two congested slow intervals
        // (the debounce) suffice — a reseeded baseline would read excess ~0 here and
        // never engage at all.
        tick(g, t += INTERVAL, 400 * KB, 40, 1, false, CONGESTED_PING); // reseed tick
        tick(g, t += INTERVAL, 700 * KB, 70, 1, false, CONGESTED_PING);
        tick(g, t += INTERVAL, 1000 * KB, 100, 1, false, CONGESTED_PING);
        assertTrue(g.isEngaged(), "the kept baseline re-engages via the debounce");
    }

    @Test
    void baselineDriftsUpwardOneMsPerSecond() {
        // A genuinely changed route must re-baseline in minutes, not never.
        var g = new TransferRateGovernor();
        long t = 0;
        tick(g, t, 0, 0, 1, false, 50);
        // 300 s later the baseline has drifted +300 ms; a 320 ms ping now reads as
        // ~-30 excess (below the engage conjunct) instead of +270.
        t += 300_000;
        tick(g, t, 0, 0, 1, false, 320);
        tick(g, t + INTERVAL, 300 * KB, 30, 1, false, 320);
        assertFalse(g.isEngaged(),
                "the drifted baseline absorbs a modest permanent ping shift");
    }

    // ---- Composition + cross-mechanism constants ----

    @Test
    void composeRateCapsHandlesBothOffSentinels() {
        // Review m2: <=0 = OFF on each side and must never win a naive min.
        assertEquals(0, LodRequestManager.composeRateCaps(0, 0), "both off = off");
        assertEquals(40, LodRequestManager.composeRateCaps(40, 0), "manual only");
        assertEquals(25, LodRequestManager.composeRateCaps(0, 25), "governed only");
        assertEquals(25, LodRequestManager.composeRateCaps(40, 25), "min when both");
        assertEquals(40, LodRequestManager.composeRateCaps(40, 90), "min when both, manual tighter");
        assertEquals(0, LodRequestManager.composeRateCaps(-1, -2), "negatives are off");
    }

    @Test
    void operatingRegionsStaySeparated() {
        // The A/B composition argument IS this constant relationship: A engages far
        // below B's cut threshold, so a governed session's converged excess never
        // reaches B's trigger. A tuning edit to either constant can silently close
        // the margin — pin it.
        assertTrue(TransferRateGovernor.ENGAGE_PING_EXCESS_MS
                        <= PingBackstop.RECOVER_EXCESS_MS,
                "A must engage no later than the excess B already calls calm");
        assertTrue(TransferRateGovernor.ENGAGE_PING_EXCESS_MS * 3
                        <= PingBackstop.CUT_EXCESS_MS,
                "A's engage threshold needs real margin under B's cut threshold");
        assertTrue(PingBackstop.RECOVER_EXCESS_MS < PingBackstop.CUT_EXCESS_MS,
                "B recovers below where it cuts");
    }

    @Test
    void pacerFloorClearsAGovernedQuarterBatchAtDefaults() {
        // send-pacing-plan.md §4 (the m7 coupling invariant, static over compiled
        // defaults — the runtime allocation is a variable): the send pacer's refill
        // floor at the DEFAULT per-player cap must deliver a governed quarter-batch
        // (≤ ENGAGE_BELOW/4 bytes/s worth) within the client's 5-tick fast-fire
        // floor, or pacing would slow the governor's own loop. Documented
        // NON-guarantee under deep pingf cuts / heavy global dilution — the degrade
        // path there is the offer-backing freeze.
        long defaultCapBytesPerSec =
                new dev.vox.lss.config.LSSServerConfig().bytesPerSecondPerPlayer();
        // Both sides are BYTES WITHIN ONE FAST-FIRE WINDOW (5 ticks = 0.25 s at
        // defaults — the units audit's fix; the old form hardcoded the 4 Hz divisor
        // and would have RELAXED if the fast-fire floor ever widened):
        // LHS = what the pace floor delivers in that window; RHS = the byte size of a
        // governed quarter-batch (desired x window, desired < ENGAGE_BELOW). The RHS
        // understates the disengage-probe overshoot (desired may legally exceed
        // ENGAGE_BELOW by DISENGAGE_RATE_INTERVALS x STEP ≈ 2.5 MB/s for a few
        // intervals) — the ~6x headroom at defaults absorbs it.
        long window = SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS;
        long floorBytesPerWindow =
                (defaultCapBytesPerSec / dev.vox.lss.common.LSSConstants.TICKS_PER_SECOND)
                        * window;
        long quarterBatchBytes = TransferRateGovernor.ENGAGE_BELOW_BYTES_PER_SEC
                * window / dev.vox.lss.common.LSSConstants.TICKS_PER_SECOND;
        assertTrue(floorBytesPerWindow >= quarterBatchBytes,
                "the pace floor must clear a governed quarter-batch inside the fast-fire floor");
    }

    // ---- Join slow start (join-slow-start-plan.md v1.1) ----

    private static final long SS_INITIAL = TransferRateGovernor.SLOW_START_INITIAL_BYTES_PER_SEC;
    private static final long SS_CEILING = TransferRateGovernor.SLOW_START_CEILING_BYTES_PER_SEC;
    private static final long ENGAGE_BELOW = TransferRateGovernor.ENGAGE_BELOW_BYTES_PER_SEC;

    private static TransferRateGovernor armed() {
        var g = new TransferRateGovernor();
        g.setSlowStartEnabled(true);
        return g;
    }

    @Test
    void unarmedGovernorStartsOpenAndCapless() {
        // The class-level default is OFF (plan §1.4 — every pre-existing pin in this
        // suite constructs exactly this shape); production arming is the manager's.
        var g = new TransferRateGovernor();
        tick(g, 0, 0, 0, 1, false, NORMAL_PING);
        assertEquals(TransferRateGovernor.Phase.OPEN, g.getPhase());
        assertEquals(0, g.sustainedColumnsPerSecond());
        assertEquals(0, g.getDesiredBytesPerSec());
    }

    @Test
    void armedSessionStartsRampedAtInitialWithThePreSampleSeed() {
        var g = armed();
        tick(g, 0, 0, 0, 1, false, NORMAL_PING);
        assertEquals(TransferRateGovernor.Phase.RAMP, g.getPhase());
        assertEquals(SS_INITIAL, g.getDesiredBytesPerSec(),
                "the ramp starts at MIN_RATE (plan review: STEP already saturates 1 Mbps)");
        // No arrival sample yet: the conversion must still CAP (the whole point is the
        // first walk), via the deliberately-high 32 KB seed — never uncapped garbage.
        assertEquals(Math.max(1, (int) (SS_INITIAL
                        / TransferRateGovernor.SLOW_START_SEED_COLUMN_BYTES)),
                g.sustainedColumnsPerSecond());
    }

    @Test
    void keptUpIntervalsDoubleToTheCeilingAndTenCreditedIntervalsOpen() {
        var g = armed();
        long t = 0;
        long bytes = 0;
        long cols = 0;
        tick(g, t, 0, 0, 1, false, NORMAL_PING);
        // Feed measured = desired each interval (>= the 3/4 proportional band) until
        // the ceiling clamps, then ride the ceiling. Doublings: 64K -> 8M = 7.
        for (int i = 0; i < 7; i++) {
            long desired = g.getDesiredBytesPerSec();
            t += INTERVAL;
            bytes += desired * (INTERVAL / 1000);
            cols += Math.max(1, desired / (16 * KB));
            tick(g, t, bytes, cols, 1, false, NORMAL_PING);
        }
        assertEquals(SS_CEILING, g.getDesiredBytesPerSec(), "doubling clamps at the ceiling");
        assertEquals(TransferRateGovernor.Phase.RAMP, g.getPhase());
        // The 4M->8M doubling landed above the engage threshold and CREDITED once; the
        // OPEN confirmation needs DISENGAGE_RATE_INTERVALS consecutive credits total.
        for (int i = 0; i < TransferRateGovernor.DISENGAGE_RATE_INTERVALS - 2; i++) {
            t += INTERVAL;
            bytes += SS_CEILING * (INTERVAL / 1000);
            cols += SS_CEILING / (16 * KB);
            tick(g, t, bytes, cols, 1, false, NORMAL_PING);
        }
        assertEquals(TransferRateGovernor.Phase.RAMP, g.getPhase(),
                "nine credited intervals are not ten — still confirming");
        // A non-qualifying idle interval (no arrivals, no answers, no demand) is NO
        // observation: it must not wipe the nine earned credits (impl review — a
        // single idle blip otherwise restarts the whole OPEN confirmation).
        t += INTERVAL;
        tick(g, t, bytes, cols, 0, false, NORMAL_PING);
        assertEquals(TransferRateGovernor.Phase.RAMP, g.getPhase());
        t += INTERVAL;
        bytes += SS_CEILING * (INTERVAL / 1000);
        cols += SS_CEILING / (16 * KB);
        tick(g, t, bytes, cols, 1, false, NORMAL_PING);
        assertEquals(TransferRateGovernor.Phase.OPEN, g.getPhase(),
                "the tenth credit completes the slow start (the streak survived the idle)");
        assertEquals(0, g.sustainedColumnsPerSecond(), "OPEN is capless");
    }

    @Test
    void rampEngageKeepsTheMeasuredBelowConjunctVerbatim() {
        // Plan review (semantics MEDIUM): v1.0 dropped the measured-below term on the
        // false premise that a ramp sits below it — the ceiling is 2x ABOVE it, and a
        // 2-interval ping blip there would have engaged a fast link at an 8 MB/s
        // anchor. Congested-but-fast intervals must NOT engage.
        var g = armed();
        long t = 0;
        long bytes = 0;
        long cols = 0;
        tick(g, t, 0, 0, 1, false, NORMAL_PING);
        for (int i = 0; i < 7; i++) {
            long desired = g.getDesiredBytesPerSec();
            t += INTERVAL;
            bytes += desired * (INTERVAL / 1000);
            cols += Math.max(1, desired / (16 * KB));
            tick(g, t, bytes, cols, 1, false, NORMAL_PING);
        }
        assertEquals(SS_CEILING, g.getDesiredBytesPerSec());
        for (int i = 0; i < 3; i++) {
            t += INTERVAL;
            bytes += 6 * 1024 * KB * (INTERVAL / 1000); // measured 6 MB/s >= ENGAGE_BELOW
            cols += 400;
            tick(g, t, bytes, cols, 1, false, CONGESTED_PING);
        }
        assertFalse(g.isEngaged(),
                "congestion above the engage-rate threshold never engages (kept verbatim)");
        // Now congested AND slow: first excess-slow interval HOLDS (streak 1), second engages.
        long before = g.getDesiredBytesPerSec();
        t += INTERVAL;
        bytes += 300 * KB * (INTERVAL / 1000);
        cols += 30;
        tick(g, t, bytes, cols, 1, false, CONGESTED_PING);
        assertFalse(g.isEngaged(), "streak 1 is a HOLD, not an engagement");
        assertEquals(before, g.getDesiredBytesPerSec(),
                "the first excess interval must not double into detected congestion");
        t += INTERVAL;
        bytes += 300 * KB * (INTERVAL / 1000);
        cols += 30;
        tick(g, t, bytes, cols, 1, false, CONGESTED_PING);
        assertTrue(g.isEngaged(), "the debounced second slow-congested interval engages");
        assertEquals(300 * KB - STEP / 2, g.getDesiredBytesPerSec(),
                "engage anchors at measured - STEP/2, the existing rule");
    }

    @Test
    void movementHoldsGrowthOnlyWithPositivePingExcess() {
        // Plan review MAJOR (both lenses): an unconditional movement hold pins every
        // join-then-travel session at INITIAL forever (the rig hands out elytra at
        // spawn). Clean-ping movement grows — that is today's uncapped behavior.
        var g = armed();
        tick(g, 0, 0, 0, 1, false, NORMAL_PING);
        g.noteMovement();
        long desired = g.getDesiredBytesPerSec();
        tick(g, INTERVAL, desired * 2, 8, 1, false, NORMAL_PING);
        assertEquals(2 * desired, g.getDesiredBytesPerSec(),
                "movement with ping at/below baseline must not hold the ramp");
        // Movement WITH positive excess holds (baseline ~50 drifted; ping 150 => +~100,
        // under the engage threshold so only the movement row can act).
        g.noteMovement();
        desired = g.getDesiredBytesPerSec();
        tick(g, 2 * INTERVAL, desired * 4, 16, 1, false, 150);
        assertEquals(desired, g.getDesiredBytesPerSec(),
                "movement with positive excess holds the climb (vanilla's burst window)");
        // Sub-threshold jitter (impl review MAJOR: "> 0" against a rolling-MIN
        // baseline is a jitter detector — ordinary WAN jitter sits above the session
        // floor most intervals, so join-then-fly would freeze the climb for the whole
        // flight). Excess ~+20-25 ms < RAMP_MOVEMENT_HOLD_EXCESS_MS (62): grows.
        g.noteMovement();
        tick(g, 3 * INTERVAL, 512 * KB + desired * 2, 24, 1, false, 75);
        assertEquals(2 * desired, g.getDesiredBytesPerSec(),
                "sub-threshold jitter never holds a moving climb");
    }

    @Test
    void highRttDeliveredAllOfferedStillGrows() {
        // Plan review HIGH (the RTT park): the stop-and-wait window caps offered at
        // ~0.6-0.7x desired on >=250 ms links, so the kept-up band alone is
        // unreachable and v1.0 parked clean fast links at INITIAL forever. A link
        // that delivered >=3/4 of a >=half-desired offer grows.
        var g = armed();
        // Establish EWMA 16 KB: 128 KB over 8 columns, declared 8 (offered == desired).
        g.tick(0, 0, 0, 0, 0, 1, false, NORMAL_PING, true);
        g.tick(INTERVAL, 128 * KB, 8, 8, 8, 1, false, NORMAL_PING, true);
        assertEquals(2 * SS_INITIAL, g.getDesiredBytesPerSec()); // 128K after one doubling
        // desired 128K: offer 10 cols x 16K / 2s = 80 KB/s (0.63x desired), measured
        // 80 KB/s (delivered everything) -> grows despite failing the kept-up band.
        g.tick(2 * INTERVAL, 128 * KB + 160 * KB, 18, 18, 15, 1, false, NORMAL_PING, true);
        assertEquals(4 * SS_INITIAL, g.getDesiredBytesPerSec(),
                "delivered-all-offered is growth evidence (the high-RTT rule)");
    }

    @Test
    void underOfferedIntervalHoldsAndPlateauSnapsWithoutRaising() {
        var g = armed();
        g.tick(0, 0, 0, 0, 0, 1, false, NORMAL_PING, true);
        g.tick(INTERVAL, 128 * KB, 8, 8, 8, 1, false, NORMAL_PING, true);   // EWMA 16K, desired 128K
        g.tick(2 * INTERVAL, 256 * KB, 16, 16, 16, 1, false, NORMAL_PING, true); // desired 256K
        long desired = g.getDesiredBytesPerSec();
        assertEquals(4 * SS_INITIAL, desired);
        // Demand-limited: 2 columns declared -> offered 16 KB/s < desired/2, and the
        // answers lag (answered delta 0 — the byte-free rung must not fire) -> HOLD.
        g.tick(3 * INTERVAL, 256 * KB + 32 * KB, 18, 18, 16, 1, false, NORMAL_PING, true);
        assertEquals(desired, g.getDesiredBytesPerSec(),
                "a converged client sits mid-ramp; no growth, no snap");
        // Plateau: offered 20 x 16K / 2s = 160 KB/s (>= desired/2), measured 100 KB/s
        // (< 3/4 of both) -> snap to measured x 5/4 = 125 KB/s, never a raise.
        g.tick(4 * INTERVAL, 256 * KB + 32 * KB + 200 * KB, 38, 38, 26, 1, false, NORMAL_PING, true);
        assertEquals(100 * KB * 5 / 4, g.getDesiredBytesPerSec(),
                "the plateau snap bounds the standing offer overhang at 25%");
    }

    @Test
    void vanillaBehindHoldsTheRamp() {
        var g = armed();
        tick(g, 0, 0, 0, 1, false, NORMAL_PING);
        g.noteMissingVanilla(20);   // session floor
        g.noteMissingVanilla(20 + TransferRateGovernor.MISSING_VANILLA_CUT_EXCESS);
        long desired = g.getDesiredBytesPerSec();
        tick(g, INTERVAL, desired * 2, 8, 1, false, NORMAL_PING);
        assertEquals(desired, g.getDesiredBytesPerSec(),
                "vanilla-behind HOLDs the ramp (the cut stays engaged-only)");
    }

    @Test
    void dimensionChangeReRampsAtHalfTheProvenRate() {
        // ENGAGED prior: ssthresh memory, not a fresh INITIAL ramp and not today's
        // uncapped re-engage gap. Engage first (unarmed — the row-1 gate refuses
        // engagement from a low ramp by design), then arm: the toggle-table's
        // on-mid-ENGAGED row is a no-op, and the dimension change re-ramps.
        var g = new TransferRateGovernor();
        long t = engageAt(g, 0, 800 * KB);
        g.setSlowStartEnabled(true);
        long desired = g.getDesiredBytesPerSec();
        g.onDimensionChange();
        tick(g, t + INTERVAL, 0, 0, 1, false, NORMAL_PING);
        assertEquals(TransferRateGovernor.Phase.RAMP, g.getPhase());
        assertEquals(Math.clamp(desired / 2, SS_INITIAL, SS_CEILING),
                g.getDesiredBytesPerSec(), "re-ramp at half the proven rate");
        // OPEN prior: the session proved >= the engage threshold to open.
        var g2 = armed();
        long t2 = 0;
        long bytes = 0;
        long cols = 0;
        tick(g2, t2, 0, 0, 1, false, NORMAL_PING);
        for (int i = 0; i < 7 + TransferRateGovernor.DISENGAGE_RATE_INTERVALS - 1; i++) {
            long d = Math.max(g2.getDesiredBytesPerSec(), SS_CEILING);
            t2 += INTERVAL;
            bytes += d * (INTERVAL / 1000);
            cols += Math.max(1, d / (16 * KB));
            tick(g2, t2, bytes, cols, 1, false, NORMAL_PING);
        }
        assertEquals(TransferRateGovernor.Phase.OPEN, g2.getPhase());
        g2.onDimensionChange();
        tick(g2, t2 + INTERVAL, bytes, cols, 1, false, NORMAL_PING);
        assertEquals(ENGAGE_BELOW, g2.getDesiredBytesPerSec(),
                "an OPEN session re-ramps from the engage threshold");
    }

    @Test
    void toggleOffDemotesRampOnlyAndNeverTouchesEngaged() {
        // Plan review (both lenses): v1.0's hard-reset-on-toggle would have un-capped
        // an engaged congested link — the round-5 runaway shape.
        var g = armed();
        tick(g, 0, 0, 0, 1, false, NORMAL_PING);
        assertEquals(TransferRateGovernor.Phase.RAMP, g.getPhase());
        g.setSlowStartEnabled(false);
        assertEquals(TransferRateGovernor.Phase.OPEN, g.getPhase());
        assertEquals(0, g.sustainedColumnsPerSecond());

        var g2 = new TransferRateGovernor();
        long t = engageAt(g2, 0, 800 * KB);
        g2.setSlowStartEnabled(true);  // toggle ON mid-ENGAGED: no phase change
        assertTrue(g2.isEngaged(), "toggle-on never demotes a live session");
        g2.setSlowStartEnabled(false);
        assertTrue(g2.isEngaged(), "ENGAGED earned its state on evidence — the toggle never touches it");
        assertTrue(g2.getDesiredBytesPerSec() > 0);
        tick(g2, t + INTERVAL, 2 * 800 * KB, 200, 1, false, CONGESTED_PING);
        assertTrue(g2.isEngaged(), "still governed on the congested link after the flip");
    }

    @Test
    void hintSurvivesHardResetButDiesInReset() {
        var g = new TransferRateGovernor();
        long t = engageAt(g, 0, 800 * KB);       // engage unarmed (the row-1 gate)
        g.setSlowStartEnabled(true);
        long desired = g.getDesiredBytesPerSec();
        g.onDimensionChange();                    // hint = desired/2, phase DISABLED
        g.tick(t + INTERVAL, 0, 0, 0, 0, 1, false, NORMAL_PING, false); // inactive: stays DISABLED
        tick(g, t + 2 * INTERVAL, 0, 0, 1, false, NORMAL_PING);
        assertEquals(Math.clamp(desired / 2, SS_INITIAL, SS_CEILING),
                g.getDesiredBytesPerSec(), "the hint survives the inactive hard-reset");
        g.reset();
        tick(g, t + 3 * INTERVAL, 0, 0, 1, false, NORMAL_PING);
        assertEquals(SS_INITIAL, g.getDesiredBytesPerSec(),
                "session teardown clears the hint — the next session ramps from INITIAL");
    }

    @Test
    void warmRejoinByteFreeAnsweredIntervalsGrow() {
        // Impl review MAJOR (all three lenses — the byte-vs-position denomination):
        // a warm rejoin's answers are up_to_date frames, so the whole revalidation
        // moves ZERO wire bytes; a byte-denominated ramp parks it at 2 col/s for
        // minutes. An interval whose declared positions were ANSWERED is the position
        // window saturated with timely service — growth that costs the link nothing.
        var g = armed();
        long declared = 0;
        g.tick(0, 0, 0, 0, 0, 1, false, NORMAL_PING, true);
        long desired = g.getDesiredBytesPerSec();
        g.tick(INTERVAL, 0, 0, declared += 200, declared, 1, false, NORMAL_PING, true);
        assertEquals(2 * desired, g.getDesiredBytesPerSec(),
                "answered-all-asked is growth evidence with zero wire bytes");
        desired = g.getDesiredBytesPerSec();
        g.tick(2 * INTERVAL, 0, 0, declared += 200, declared, 1, false, NORMAL_PING, true);
        assertEquals(2 * desired, g.getDesiredBytesPerSec(),
                "the byte-free rung keeps earning across intervals");
    }

    @Test
    void awaitingLatchQualifiesMidIntervalDemandAndItsAbsenceDisqualifies() {
        // Impl review MAJOR: awaiting sampled at the two boundary INSTANTS is bimodal
        // at ramp-sized batches (the 2 s interval is 0 mod the 5-tick fire cycle), so
        // a session whose boundary phase lands in the answered window would be
        // PERMANENTLY non-qualifying. Any mid-interval tick with a non-empty awaiting
        // set latches the interval as qualifying.
        var g = armed();
        g.tick(0, 0, 0, 0, 0, 0, false, NORMAL_PING, true);   // boundary: awaiting EMPTY
        long desired = g.getDesiredBytesPerSec();
        g.tick(500, 0, 0, 0, 0, 3, false, NORMAL_PING, true); // mid-interval demand
        g.tick(INTERVAL, desired * 2, 8, 8, 8, 0, false, NORMAL_PING, true); // empty again
        assertEquals(2 * desired, g.getDesiredBytesPerSec(),
                "mid-interval awaiting latches the interval as qualifying");
        // Negative arm: an interval that never saw demand is no observation — even
        // with kept-up bytes on the wire (a background drain), no growth.
        desired = g.getDesiredBytesPerSec();
        g.tick(2 * INTERVAL, desired * 2 + desired * 4, 16, 16, 16, 0,
                false, NORMAL_PING, true);
        assertEquals(desired, g.getDesiredBytesPerSec(),
                "no demand anywhere in the interval: non-qualifying, the ramp holds");
    }

    @Test
    void congestionBelowTheEngageGateContainsViaThePlateauSnapNotEngagement() {
        // Impl review MAJOR (the nullified discriminator): below RAMP_ENGAGE_MIN_DESIRED
        // the verbatim measured-below engage term is TRIVIALLY true — the ramp itself
        // caps measured — so vanilla's own join-burst ping would engage a fast link at
        // a MIN-rate anchor (~70-90 s recovery). Below the gate, congested intervals
        // must fall to the plateau snap, which tracks desired down toward measured
        // and floors at INITIAL — containment without a false engagement.
        var g = armed();
        g.tick(0, 0, 0, 0, 0, 1, false, NORMAL_PING, true);
        g.tick(INTERVAL, 128 * KB, 8, 8, 8, 1, false, NORMAL_PING, true); // EWMA 16K → 128K
        assertEquals(2 * SS_INITIAL, g.getDesiredBytesPerSec());
        assertTrue(2 * SS_INITIAL < TransferRateGovernor.RAMP_ENGAGE_MIN_DESIRED_BYTES_PER_SEC,
                "premise: the fixture sits below the engage gate");
        // Congested + slow, repeatedly: offered 8 cols × 16K / 2 s = 64 KB/s
        // (≥ desired/2 — past the under-offer row), measured 20 KB/s, answers lag so
        // the byte-free rung stays out.
        long bytes = 128 * KB;
        long declared = 8;
        for (int i = 0; i < 4; i++) {
            bytes += 40 * KB;
            declared += 8;
            g.tick((2 + i) * INTERVAL, bytes, 8, declared, 8, 1, false, CONGESTED_PING, true);
            assertFalse(g.isEngaged(), "congestion below the gate must never engage");
        }
        assertEquals(SS_INITIAL, g.getDesiredBytesPerSec(),
                "the snap tracked desired down to the INITIAL floor, not an engage anchor");
    }

    @Test
    void answeredTrickleUnderTheOfferFloorNeverRamps() {
        // Round-3 review MAJOR: the under-offer hold must gate the growth rungs.
        // With growth evaluated first, a converged client's occasional one-column
        // dirty-edit intervals (offered a fraction of desired, trivially answered)
        // satisfied answered-all-asked and ~17 sparse intervals walked a
        // never-proven link to capless OPEN — non-qualifying gaps preserve both
        // streaks, so the doublings accumulate across a whole session.
        var g = armed();
        long declared = 0;
        g.tick(0, 0, 0, 0, 0, 1, false, NORMAL_PING, true);
        for (int i = 1; i <= 8; i++) {
            g.tick(i * INTERVAL, 0, 0, declared += 1, declared, 1,
                    false, NORMAL_PING, true);
            assertEquals(SS_INITIAL, g.getDesiredBytesPerSec(),
                    "an under-offered answered trickle is demand-limited: HOLD, "
                            + "never growth (interval " + i + ")");
        }
        assertEquals(TransferRateGovernor.Phase.RAMP, g.getPhase());
    }

    @Test
    void partiallyAnsweredByteFreeIntervalHoldsInsteadOfSnapping() {
        // Round-3 review MINOR: a zero-byte answer-only interval with PARTIAL
        // answers (a server hiccup mid-revalidation) fails every growth rung and
        // used to fall to the plateau snap with measured = 0 — wiping the earned
        // ramp to INITIAL on no rate evidence. It must HOLD.
        var g = armed();
        long declared = 0;
        g.tick(0, 0, 0, 0, 0, 1, false, NORMAL_PING, true);
        g.tick(INTERVAL, 0, 0, declared += 200, declared, 1, false, NORMAL_PING, true);
        g.tick(2 * INTERVAL, 0, 0, declared += 200, declared, 1, false, NORMAL_PING, true);
        long desired = g.getDesiredBytesPerSec();
        assertEquals(4 * SS_INITIAL, desired, "premise: two byte-free doublings earned");
        // 50% answered, zero bytes: offered ≈ 3.2 MB/s via the seed (past the
        // under-offer row), growth fails (100×4 < 200×3), plateau reached.
        g.tick(3 * INTERVAL, 0, 0, declared += 200, declared - 100, 1,
                false, NORMAL_PING, true);
        assertEquals(desired, g.getDesiredBytesPerSec(),
                "zero bytes moved is not link evidence — hold, never a snap to INITIAL");
    }

    // ---- Window-limited Row-4 bypass (ramp-window-limited-credit-plan.md) ----

    /** Climbs an armed governor to the ceiling via 7 kept-up doublings (the existing
     *  keptUp test's feed shape; the resulting size EWMA is 32K — desired×2s bytes
     *  over desired/16K columns). Returns {time, bytes, cols} cursors;
     *  rampOpenStreak is 1 afterward (the 4M→8M doubling credited once). */
    private long[] climbToCeiling(TransferRateGovernor g) {
        long t = 0, bytes = 0, cols = 0;
        tick(g, t, 0, 0, 1, false, NORMAL_PING);
        for (int i = 0; i < 7; i++) {
            long desired = g.getDesiredBytesPerSec();
            t += INTERVAL;
            bytes += desired * (INTERVAL / 1000);
            cols += Math.max(1, desired / (16 * KB));
            tick(g, t, bytes, cols, 1, false, NORMAL_PING);
        }
        assertEquals(SS_CEILING, g.getDesiredBytesPerSec(), "climb premise: at the ceiling");
        return new long[]{t, bytes, cols};
    }

    /** One stuck-rig stop-and-wait interval at the ceiling (the measured live shape,
     *  rescaled to the climb's ACTUAL EWMA of 32K — the climb feeds desired×2s bytes
     *  over desired/16K columns, so each sample is 32K/column): declared 200 →
     *  offered ≈ 200×32K/2s = 3.2 MB/s < desired/2 = 4.19 MB/s (under-offered),
     *  measured = answered×16K/2s (16K samples slide the EWMA down only slowly at
     *  the 0.05 down-alpha, keeping offered < desired/2 throughout), answered/declared
     *  ratio per the {@code answered} arg. Latches first when {@code latched}.
     *  Returns the advanced cursors; declared rides its own cursor (index 3). */
    private long[] stuckInterval(TransferRateGovernor g, long[] cur, boolean latched,
                                 int declared, int answered) {
        long t = cur[0] + INTERVAL;
        long bytes = cur[1] + answered * 16 * KB;
        long cols = cur[2] + answered;
        long declCursor = (cur.length > 3 ? cur[3] : cur[2] * 100) + declared;
        long ansCursor = (cur.length > 4 ? cur[4] : cur[2] * 100) + answered;
        if (latched) g.noteWindowLimited();
        g.tick(t, bytes, cols, declCursor, ansCursor, 1, false, NORMAL_PING, true);
        return new long[]{t, bytes, cols, declCursor, ansCursor};
    }

    @Test
    void windowLimitedStopAndWaitCompletesTheRamp() {
        // The headline repro (plan §1): at the ceiling the stop-and-wait loop offers
        // desired/4 × cadence ≈ 3.2 MB/s < desired/2, so Row 4 held every interval
        // and the exit never fired. With the window-limited latch, answeredAllAsked
        // (190/200 = 95% ≥ 3/4) credits each interval; 9 more credits after the
        // climb's one complete the 10-streak.
        var g = armed();
        long[] cur = climbToCeiling(g);
        for (int i = 0; i < 9; i++) {
            assertEquals(TransferRateGovernor.Phase.RAMP, g.getPhase(),
                    "still confirming at credit " + (1 + i));
            cur = stuckInterval(g, cur, true, 200, 190);
        }
        assertEquals(TransferRateGovernor.Phase.OPEN, g.getPhase(),
                "nine window-limited credited intervals + the climb's one = ten → OPEN");
        assertEquals(0, g.sustainedColumnsPerSecond(), "OPEN is capless");

        // Control arm — the same ten intervals WITHOUT the latch stay parked (the
        // pre-fix stuck shape, byte-identical Row-4 hold).
        var stuck = armed();
        long[] c2 = climbToCeiling(stuck);
        for (int i = 0; i < 10; i++) {
            c2 = stuckInterval(stuck, c2, false, 200, 190);
        }
        assertEquals(TransferRateGovernor.Phase.RAMP, stuck.getPhase(),
                "unlatched under-offer holds exactly as before the fix");
        assertEquals(SS_CEILING, stuck.getDesiredBytesPerSec(),
                "no growth, no snap — the bit-identical hold");
    }

    @Test
    void windowLimitedSingleAliasedMissIsForgiven() {
        // Plan §3.4: the 2 s interval aliases against the ~630 ms cycle, failing
        // answeredAllAsked ~1-in-6. One uncredited qualifying interval must not wipe
        // the streak — the exit completes at the same total credit count.
        var g = armed();
        long[] cur = climbToCeiling(g); // streak 1
        for (int i = 0; i < 4; i++) cur = stuckInterval(g, cur, true, 200, 190); // 5
        cur = stuckInterval(g, cur, true, 200, 50); // aliased miss: forgiven
        assertEquals(TransferRateGovernor.Phase.RAMP, g.getPhase());
        assertEquals(SS_CEILING, g.getDesiredBytesPerSec(),
                "the miss neither credits nor snaps");
        for (int i = 0; i < 4; i++) cur = stuckInterval(g, cur, true, 200, 190); // 9
        assertEquals(TransferRateGovernor.Phase.RAMP, g.getPhase());
        cur = stuckInterval(g, cur, true, 200, 190); // 10
        assertEquals(TransferRateGovernor.Phase.OPEN, g.getPhase(),
                "one forgiven miss: ten credits still open the ramp");
    }

    @Test
    void windowLimitedTwoConsecutiveMissesResetTheStreak() {
        var g = armed();
        long[] cur = climbToCeiling(g); // streak 1
        for (int i = 0; i < 8; i++) cur = stuckInterval(g, cur, true, 200, 190); // 9
        cur = stuckInterval(g, cur, true, 200, 50); // miss 1: forgiven
        cur = stuckInterval(g, cur, true, 200, 50); // miss 2: RESET
        // If the reset fired, one credit is streak 1, not 10 — no OPEN.
        cur = stuckInterval(g, cur, true, 200, 190);
        assertEquals(TransferRateGovernor.Phase.RAMP, g.getPhase(),
                "two consecutive misses are real degradation: the streak restarted");
        for (int i = 0; i < 8; i++) cur = stuckInterval(g, cur, true, 200, 190); // 9
        assertEquals(TransferRateGovernor.Phase.RAMP, g.getPhase());
        cur = stuckInterval(g, cur, true, 200, 190); // 10
        assertEquals(TransferRateGovernor.Phase.OPEN, g.getPhase());
    }

    @Test
    void windowLimitedUnderOfferNeverSnaps() {
        // The pre-snap guard (plan §3.3): a window-limited UNDER-OFFERED interval
        // whose growth rungs fail is not offer-backed evidence — HOLD (the ENGAGED
        // freeze rule), never a snap toward measured (which would wipe the earned
        // ceiling to ~0.5 MB/s here).
        var g = armed();
        long[] cur = climbToCeiling(g);
        cur = stuckInterval(g, cur, true, 200, 50); // bytes>0, growth fails
        assertEquals(SS_CEILING, g.getDesiredBytesPerSec(),
                "under-offered + latched + growth-failed = HOLD, never the plateau snap");
        assertEquals(TransferRateGovernor.Phase.RAMP, g.getPhase());
    }

    @Test
    void windowLimitedOfferBackedPlateauStillSnaps() {
        // 1-Fable fold minor-2: the bypass is a ROW-4 bypass, not a blanket pre-snap
        // return. A latched interval that IS offer-backed (offered×2 ≥ desired) with
        // growth failed and bytes moved must still take the containment snap — the
        // exact numbers of underOfferedIntervalHoldsAndPlateauSnapsWithoutRaising's
        // plateau arm, plus the latch.
        var g = armed();
        g.tick(0, 0, 0, 0, 0, 1, false, NORMAL_PING, true);
        g.tick(INTERVAL, 128 * KB, 8, 8, 8, 1, false, NORMAL_PING, true);   // EWMA 16K
        g.tick(2 * INTERVAL, 256 * KB, 16, 16, 16, 1, false, NORMAL_PING, true); // 256K
        assertEquals(4 * SS_INITIAL, g.getDesiredBytesPerSec());
        g.noteWindowLimited();
        g.tick(3 * INTERVAL, 256 * KB + 200 * KB, 36, 36, 26, 1, false, NORMAL_PING, true);
        assertEquals(100 * KB * 5 / 4, g.getDesiredBytesPerSec(),
                "an offer-backed latched plateau still snaps — the latch only bypasses Row 4");
    }

    @Test
    void windowLimitedLatchIsIntervalScopedAndClearedByResets() {
        var g = armed();
        long[] cur = climbToCeiling(g);
        cur = stuckInterval(g, cur, true, 200, 190); // credited, latch consumed at seed
        assertFalse(g.windowLimitedLatched(),
                "seedInterval clears the latch — it never leaks into the next interval");
        long desired = g.getDesiredBytesPerSec();
        cur = stuckInterval(g, cur, false, 200, 190); // same shape, NO latch
        assertEquals(desired, g.getDesiredBytesPerSec(),
                "the unlatched twin interval holds at Row 4 (no leaked credit)");
        // minor-3 (panel): desired can't move at the ceiling, so pin the no-leak
        // claim on the PHASE — nine more unlatched twins would open a leaked latch.
        for (int i = 0; i < 9; i++) {
            cur = stuckInterval(g, cur, false, 200, 190);
        }
        assertEquals(TransferRateGovernor.Phase.RAMP, g.getPhase(),
                "unlatched intervals never credit — the latch does not leak");
        // hardReset (an active=false tick) clears a pending latch.
        g.noteWindowLimited();
        assertTrue(g.windowLimitedLatched());
        g.tick(cur[0] + 1, cur[1], cur[2], cur[3], cur[4], 1, false, NORMAL_PING, false);
        assertFalse(g.windowLimitedLatched(), "hardReset clears the latch");
        // adoptFrom never carries a pending latch (interval state reseeds).
        var donor = armed();
        climbToCeiling(donor);
        donor.noteWindowLimited();
        var heir = new TransferRateGovernor();
        heir.adoptFrom(donor);
        assertFalse(heir.windowLimitedLatched(), "adoptFrom reseeds the interval");
    }

    @Test
    void unlatchedUncreditedIntervalStillResetsTheStreak() {
        // The panel's forgiveness-scoping MAJOR: forgiveness is for WINDOW-LIMITED
        // aliasing only. An un-latched uncredited qualifying interval keeps main's
        // semantics — immediate reset — so alternating credited/uncredited demand
        // (~3/8 average answered) can never walk to OPEN.
        var g = armed();
        long[] cur = climbToCeiling(g); // streak 1
        for (int i = 0; i < 8; i++) cur = stuckInterval(g, cur, true, 200, 190); // 9
        cur = stuckInterval(g, cur, false, 200, 50); // UN-latched miss: hard reset
        cur = stuckInterval(g, cur, true, 200, 190); // one credit = streak 1, not 10
        assertEquals(TransferRateGovernor.Phase.RAMP, g.getPhase(),
                "an un-latched miss resets exactly as before the fix");
        for (int i = 0; i < 8; i++) cur = stuckInterval(g, cur, true, 200, 190);
        assertEquals(TransferRateGovernor.Phase.RAMP, g.getPhase());
        cur = stuckInterval(g, cur, true, 200, 190);
        assertEquals(TransferRateGovernor.Phase.OPEN, g.getPhase(),
                "ten fresh consecutive credits confirm after the reset");
    }

    @Test
    void trickleThroughTinyBudgetSelfArrestsAndNeverCredits() {
        // 1-Fable fold MAJOR-1(b): near INITIAL the governed burst budget is 1-3
        // columns, so a small fixed trickle CAN truncate and latch. The escape is
        // self-arresting at the WIRING level — each doubling doubles the budget, and
        // once burst > demand the walk stops truncating and the latch stops. This
        // test replays that geometry against the governor (latch only while the
        // implied burst = desired/(4·EWMA) < the 3-column demand) and pins the
        // bound: desired parks a few doublings up, far below the engage threshold,
        // with zero credits and no OPEN.
        var g = armed();
        long t = 0, bytes = 0, cols = 0, decl = 0, ans = 0;
        g.tick(t, 0, 0, 0, 0, 1, false, NORMAL_PING, true);
        for (int i = 0; i < 12; i++) {
            long desired = g.getDesiredBytesPerSec();
            double ewma = g.getSizeEstimateForTest() > 0
                    ? g.getSizeEstimateForTest() : 16 * KB;
            long impliedBurst = Math.max(1, (long) (desired / ewma) / 4);
            boolean latch = impliedBurst < 3; // the scanner's truncation geometry
            t += INTERVAL;
            bytes += 3 * 16 * KB;
            cols += 3;
            decl += 3;
            ans += 3;
            if (latch) g.noteWindowLimited();
            g.tick(t, bytes, cols, decl, ans, 1, false, NORMAL_PING, true);
        }
        assertEquals(TransferRateGovernor.Phase.RAMP, g.getPhase(),
                "a self-arrested trickle never opens the ramp");
        assertEquals(4 * SS_INITIAL, g.getDesiredBytesPerSec(),
                "the escape is bounded: two latched doublings (64K→256K), then the "
                        + "budget outgrows the demand and Row 4 holds forever");
        assertTrue(g.getDesiredBytesPerSec() < ENGAGE_BELOW,
                "parked far below the credit threshold — no OPEN confirmation can start");
    }
}
