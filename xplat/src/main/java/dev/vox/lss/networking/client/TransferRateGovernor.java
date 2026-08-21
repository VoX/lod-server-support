package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSLogger;

import java.util.function.LongSupplier;

/**
 * The client transfer governor (adaptive-transfer-rate-plan.md, Mechanism A): an AIMD
 * loop over the client's own RECEIVED wire-byte rate that, on a congested slow link,
 * caps the want-set through the same machinery as the manual
 * {@code lodColumnsPerSecondLimit} knob. Client-measured arrival bytes are
 * post-bottleneck ground truth — every artifact class that falsified the deleted AUTO
 * outbound ceiling (async netty writes, kernel-buffer absorption, vanilla write
 * interleaving) is structurally invisible to it. Latency comes from pacing UNDER link
 * capacity, never from bounding one queue's depth (the program's structural finding).
 *
 * <p><b>Engagement is congestion-gated</b>: a measured-rate shortfall alone can never
 * engage, because the measured rate equals the demand or serve rate whenever the link
 * is not the bottleneck (review M1 — without the ping conjunct every walking player on
 * a healthy link would engage fleet-wide). The congestion signal is the client's own
 * 1 Hz ping probe (live round 5 — the manager sends ServerboundPingRequestPacket and
 * reads the debug-overlay ping logger; the tab-list ping, which 26.2 refreshes only
 * every 600 ticks with {@code (3·old+new)/4} smoothing, survives as the no-sample
 * fallback) against a rolling-min baseline, debounced over TWO consecutive qualifying
 * intervals. The drain bias is the DETERMINISTIC every-4th-kept-up variant (live
 * round 2) because a ping-driven one would over-cut after the queue actually drained.
 *
 * <p>All state is main-client-thread confined and dies with the session
 * ({@link #reset()} — the adaptive-cadence reset-family precedent). Intervals whose
 * byte counters ran backwards (a session reset zeroed them mid-interval) are
 * NON-QUALIFYING and re-seed. Harness JVMs ({@code -Dlss.soak} / {@code -Dlss.benchmark})
 * gate the governor OFF (the far-player precedent): soak configs themselves create
 * sub-threshold qualifying intervals, and a governed want-set would break premises
 * calibrated to the constant budget.
 */
final class TransferRateGovernor {

    /** 2 s measurement intervals (review m6): 1 s aliases against the ~4 Hz batch
     *  cadence (0 or 2 bursts per window); 2 s bounds the burst-count error to ~±12.5%,
     *  comparable to the kept-up band. */
    static final long INTERVAL_MILLIS = 2_000L;
    /** Additive up-step per kept-up interval; also sizes the cut margin. */
    static final long STEP_BYTES_PER_SEC = 256L * 1024;
    /** The governed floor — the cap never converts to the {@code <= 0} OFF sentinel
     *  (review m2/m5: the conversion below also floors at 1 column/s). */
    static final long MIN_RATE_BYTES_PER_SEC = 64L * 1024;
    /** Sessions measuring at/above this never engage — the disarm posture. */
    static final long ENGAGE_BELOW_BYTES_PER_SEC = 4L * 1024 * 1024;
    /** The congestion conjunct: ping excess over baseline required to ENGAGE, and the
     *  per-interval drain trigger while engaged. Far below Mechanism B's 750 ms cut. */
    static final int ENGAGE_PING_EXCESS_MS = 250;
    /** Consecutive qualifying congested intervals required to ENGAGE (round-5 review
     *  M1): the ping input is now a raw 1 Hz probe sample, not the tab value vanilla
     *  both refreshed at 30 s and smoothed (3l+s)/4 — the 250 ms threshold was
     *  calibrated against that damped signal, and with the ping-normal escape deleted
     *  a single GC pause or WiFi retransmit burst must not buy a sticky engagement.
     *  Real congestion sustains across intervals; transients do not. */
    static final int ENGAGE_CONSECUTIVE_INTERVALS = 2;
    /** The ONLY disengage path: consecutive QUALIFYING intervals with desired above
     *  the engage threshold — rate evidence that the loop probed clear of the slow
     *  regime. There is deliberately NO ping-normal disengage (live round 5 deleted
     *  review m10's path (b)): while the cap binds, normal ping is the governor's own
     *  SUCCESS, not link health — the traced sawtooth was exactly that path firing
     *  after ~1 min of the calm it was causing, un-capping a 750 KB/s link into a
     *  25 s line-rate runaway (ping 4.7 s, vanilla 20+ chunks behind, movement
     *  rejections) until the 30 s tab-ping refresh let it re-engage. A frozen
     *  (demand-limited) engaged session now stays engaged, which is harmless: the
     *  cap sits above actual demand, and the kept-up climb resumes with demand. */
    static final int DISENGAGE_RATE_INTERVALS = 10;
    /** The deterministic drain bias (review M3; retuned at live round 2): every Nth
     *  consecutive kept-up interval bleeds standing queue instead of probing up — a
     *  rate-matched loop never drains queue it INHERITED, and an AIMD equilibrium
     *  HOVERS AT capacity (the first live session measured ~500 ms standing queue at
     *  convergence — the climb/overshoot cycle keeps the link full). 8→4 with a
     *  deeper bleed halves the time spent at/above capacity. */
    static final int DRAIN_EVERY_KEPT_UP = 4;
    /** Asymmetric column-size EWMA (review M4): fast-up so a terrain batch after an
     *  ocean crossing under-counts R instead of over-bursting; slow-down so
     *  ghost-clear runs decay the estimate gently. */
    static final double SIZE_EWMA_UP_ALPHA = 0.5;
    static final double SIZE_EWMA_DOWN_ALPHA = 0.05;
    /** Baseline upward drift: +1 ms/s, so a genuinely changed route re-baselines in
     *  minutes (Mechanism B's rule, mirrored client-side). */
    private static final long BASELINE_DRIFT_MS_PER_SEC = 1;
    /** Live round 5 (the fly-then-stop desync): missing vanilla chunks IN VIEW above
     *  the session's observed floor mean vanilla delivery is BEHIND the player — the
     *  moved-wrongly precondition (26.2 clients walk into unreceived terrain and the
     *  server rejects the move). An engaged interval seeing at least this much excess
     *  CUTS regardless of kept-up rate: the governor's fair-share equilibrium
     *  otherwise holds its rate while vanilla's catch-up burst crawls (measured live:
     *  governed 590 KB/s of a 750 KB/s link with silent movement rejections). The
     *  floor is learned as a session MIN (the server-view-distance edge ring is a
     *  permanent nonzero baseline — ~20 on the rig). Unengaged sessions never
     *  evaluate this: fast links are untouched. */
    static final int MISSING_VANILLA_CUT_EXCESS = 8;
    /** Join slow start (join-slow-start-plan.md v1.1): the RAMP's starting rate.
     *  MIN_RATE, not STEP (plan review, both lenses): 256 KB/s ≈ 2.1 Mbps already
     *  saturates a 1 Mbps link 2× from the first interval — reproducing the
     *  baseline-pollution defect instead of fixing it. 64 KB/s = 512 kbps sits under
     *  the links the ramp can actually serve; the fast-link cost is two extra
     *  doublings (4 s). Sub-512 kbps links are Mechanism B + transport yield's
     *  territory, never the ramp's. */
    static final long SLOW_START_INITIAL_BYTES_PER_SEC = MIN_RATE_BYTES_PER_SEC;
    /** RAMP growth ceiling — bounds the OPEN-confirmation stretch (2× the engage
     *  threshold; parking AT the ceiling is harmless: 8 MB/s wire ≈ 16-24 MB/s raw ≈
     *  the default per-player bandwidth cap). */
    static final long SLOW_START_CEILING_BYTES_PER_SEC = 2 * ENGAGE_BELOW_BYTES_PER_SEC;
    /** RAMP may hand off to ENGAGED only once desired has reached this rate (impl
     *  review MAJOR: below it the kept-verbatim {@code measured < ENGAGE_BELOW}
     *  discriminator is TRIVIALLY true — the ramp itself suppresses measured — so
     *  vanilla's own spawn-burst ping could engage a 100 Mbps join at a MIN_RATE
     *  anchor with a ~70-90 s recovery. Below this rung a congested interval falls
     *  through to the plateau snap instead, which tracks desired DOWN toward
     *  measured — the ramp contains slow links itself (the 1.25×/2.5× sawtooth is
     *  the documented operating mode there; Mechanism B backstops real bloat). */
    static final long RAMP_ENGAGE_MIN_DESIRED_BYTES_PER_SEC = ENGAGE_BELOW_BYTES_PER_SEC / 2;
    /** The RAMP movement hold's ping-excess threshold (impl review MAJOR: {@code > 0}
     *  against a rolling-MIN baseline is a jitter detector — ordinary WAN jitter sits
     *  above the session floor most intervals, and join-then-fly would hold nearly
     *  always. A quarter of the engage threshold is above jitter, far below
     *  congestion). */
    static final int RAMP_MOVEMENT_HOLD_EXCESS_MS = ENGAGE_PING_EXCESS_MS / 4;
    /** Pre-sample conversion seed, RAMP only: until the first arrival sample the
     *  byte budget converts through this instead of supplying NO cap (the whole
     *  point is capping the first walk). Deliberately HIGH — the safe join direction
     *  is fewer columns than the byte budget intends; the fast-up EWMA's first real
     *  sample REPLACES it. */
    static final double SLOW_START_SEED_COLUMN_BYTES = 32.0 * 1024;

    /** The governor's lifecycle phase (join-slow-start-plan.md §1.1): DISABLED =
     *  capless + inert (the !active posture AND the pre-first-active-tick state);
     *  RAMP = the slow-start climb; OPEN = today's unengaged capless state;
     *  ENGAGED = the congestion AIMD (byte-for-byte the pre-phase behavior). */
    enum Phase { DISABLED, RAMP, OPEN, ENGAGED }

    /** Injectable MONOTONIC clock in millis (impl review m2: interval math on the
     *  wall clock misreads an NTP step or VM resume as a rate collapse — a spurious
     *  cut to the floor). Arbitrary epoch; only deltas are used. */
    LongSupplier clock = () -> System.nanoTime() / 1_000_000L;

    // ---- Interval accumulation (main client thread) ----
    private boolean intervalSeeded;
    private long intervalStartMillis;
    private long intervalStartWireBytes;
    private long intervalStartColumns;
    private long intervalStartDeclared;
    private long intervalStartAnswered;
    private boolean awaitingAtStart;
    /** Latched on ANY tick of the interval with a non-empty awaiting set (impl
     *  review MAJOR: sampling awaiting at the two boundary INSTANTS is bimodal at
     *  small ramp batches — the 2 s interval is 0 mod the 5-tick fire cycle, so a
     *  session whose boundary phase lands in the answered window is permanently
     *  non-qualifying. RAMP consumes the latch; ENGAGED keeps the instant pair —
     *  its same-shaped MIN-rate latent predates this change and is recorded as a
     *  follow-up, not silently re-scoped here). */
    private boolean awaitingSeenThisInterval;
    private boolean haltSeenThisInterval;
    private boolean movementSeenThisInterval;
    /** Latched by {@link #noteWindowLimited()} (the manager's post-send hook,
     *  ramp-window-limited-credit-plan.md §3.1/§3.2): at least one successfully-sent
     *  scan this interval was truncated by the GOVERNED burst cap with the taper
     *  inactive and the governed rate the binding half of the min-compose — i.e. the
     *  walk had more demand than the governor's window admitted. RAMP-only consumer
     *  (the Row-4 bypass); interval-scoped like the movement latch. */
    private boolean windowLimitedSeenThisInterval;
    private int lastMissingVanilla = -1;   // newest sample (<0 = none yet)
    private int minMissingVanilla = -1;    // session floor (the permanent edge ring)

    // ---- Ping baseline ----
    private int pingBaselineMs = -1;       // -1 = unseeded (zero/absent samples ignored)
    private long baselineDriftAnchorMillis;
    private int lastPingMs = -1;

    // ---- AIMD ----
    private Phase phase = Phase.DISABLED;
    private long desiredBytesPerSec;
    private int keptUpStreak;
    private int rateDisengageStreak;
    private int engagePendingStreak;
    // ---- Slow start (join-slow-start-plan.md) ----
    /** Wiring, not session state: the manager re-asserts it every tick from config.
     *  A true→false flip mid-RAMP demotes to OPEN (the toggle × phase table);
     *  ENGAGED is NEVER touched by the toggle — it earned its state on congestion
     *  evidence independent of the ramp (plan review: hard-resetting there re-opens
     *  the round-5 runaway). */
    private boolean slowStartEnabled;
    /** RAMP restart rate (0 = INITIAL). Survives hardReset (the DISABLED→RAMP
     *  re-entry and dimension-change paths depend on it); dies only in reset(). */
    private long restartHintBytesPerSec;
    /** Consecutive CREDITED ramp intervals above the engage threshold — the
     *  RAMP→OPEN confirmation (reuses DISENGAGE_RATE_INTERVALS as the constant,
     *  not the code path). A single qualifying uncredited interval is FORGIVEN
     *  (held, not reset — plan §3.4: the 2 s interval aliases against the
     *  stop-and-wait cycle, failing answeredAllAsked ~1-in-6 on the measured rig,
     *  and a single-miss reset made the 10-streak unreachable there); the SECOND
     *  consecutive uncredited interval resets. Non-qualifying intervals stay
     *  no-observations. */
    private int rampOpenStreak;
    /** Consecutive qualifying UNCREDITED ramp intervals — the forgiveness counter
     *  (plan §3.4). */
    private int rampUncreditedRun;

    // ---- Size estimator (runs from session start, pre-engagement) ----
    private double sizeEwmaBytes = -1;     // -1 = no sample yet (division guarded)

    // ---- Session-scoped receipts ----
    private boolean engageNoted;
    private boolean disengageNoted;
    private boolean slowStartNoted;

    static boolean harnessJvm() { // package-private: the manager's probe gate shares it
        return Boolean.getBoolean("lss.soak") || Boolean.getBoolean("lss.benchmark");
    }

    /**
     * One observation per client tick. {@code wireBytesCumulative}/{@code columnsCumulative}
     * are the session gate's monotonic arrival counters (they zero at session reset — the
     * negative-delta guard makes such intervals non-qualifying);
     * {@code declaredCumulative} counts want-set columns actually OFFERED to the wire
     * (the manager's post-send counter — the impl review MAJOR-1 offer-backing input:
     * a downward step is link evidence only when the actuator genuinely offered the
     * governed rate, because the actuator is a stop-and-wait WINDOW whose achieved
     * rate collapses with answer latency — cadence holds, the walk-cost coverage
     * limit, gen-bound serves, high base RTT — none of which is link shortfall);
     * {@code halted} is this tick's #71 backpressure-halt verdict (a halt anywhere in
     * an interval disqualifies it — the client deliberately stopped ingesting, so the
     * depressed tail rate is not a link measurement); {@code pingMs} is the tab-list
     * latency ({@code <= 0} = no sample); {@code active} is the composed gate (config
     * kill switch on, not a harness JVM, not a legacy-dialect session) — false
     * hard-resets so a mid-session toggle leaves no stale cap behind.
     */
    void tick(long nowMillis, long wireBytesCumulative, long columnsCumulative,
              long declaredCumulative, long answeredCumulative, int awaitingSize,
              boolean halted, int pingMs, boolean active) {
        if (!active || harnessJvm()) {
            if (this.phase != Phase.DISABLED || this.intervalSeeded) hardReset();
            return;
        }
        // The single phase entry point (plan §1.2): the first ACTIVE tick leaves
        // DISABLED for RAMP (slow start armed) or OPEN. This precedes the scan phase
        // in the manager's tick order, so the session's very first walk is already
        // clamped — the whole point.
        if (this.phase == Phase.DISABLED) {
            enterFromDisabled();
        }
        updatePingBaseline(nowMillis, pingMs);
        if (!this.intervalSeeded) {
            seedInterval(nowMillis, wireBytesCumulative, columnsCumulative,
                    declaredCumulative, answeredCumulative, awaitingSize);
            return;
        }
        if (halted) this.haltSeenThisInterval = true;
        if (awaitingSize > 0) this.awaitingSeenThisInterval = true;
        // movementSeenThisInterval is latched by noteMovement() (the manager's
        // chunk-crossing hook) and cleared at each interval seed.
        long elapsed = nowMillis - this.intervalStartMillis;
        if (elapsed < INTERVAL_MILLIS) return;

        evaluateInterval(nowMillis, wireBytesCumulative, columnsCumulative,
                declaredCumulative, answeredCumulative, awaitingSize, elapsed);
        seedInterval(nowMillis, wireBytesCumulative, columnsCumulative,
                declaredCumulative, answeredCumulative, awaitingSize);
    }

    private void evaluateInterval(long nowMillis, long wireBytes, long columns,
                                  long declared, long answered, int awaitingSize,
                                  long elapsedMillis) {
        long deltaBytes = wireBytes - this.intervalStartWireBytes;
        long deltaColumns = columns - this.intervalStartColumns;
        long deltaDeclared = declared - this.intervalStartDeclared;
        long deltaAnswered = answered - this.intervalStartAnswered;
        // A reset zeroed the gate counters mid-interval (review m3): non-qualifying, and
        // the estimator takes no sample from garbage.
        boolean invalid = deltaBytes < 0 || deltaColumns < 0;
        if (!invalid && deltaColumns > 0) {
            recordSizeSample((double) deltaBytes / deltaColumns);
        }
        boolean qualifying = !invalid && deltaBytes > 0
                && this.awaitingAtStart && awaitingSize > 0
                && !this.haltSeenThisInterval;
        long measured = invalid ? 0 : deltaBytes * 1000L / Math.max(1L, elapsedMillis);
        // NEGATIVE excess is legitimate and NORMAL (ping below the drifted baseline —
        // the steady state, since the baseline min-snaps down then drifts up between
        // samples); only Integer.MIN_VALUE means "no signal".
        int pingExcess = (this.pingBaselineMs >= 0 && this.lastPingMs > 0)
                ? this.lastPingMs - this.pingBaselineMs : Integer.MIN_VALUE;

        if (this.phase == Phase.ENGAGED) {
            if (qualifying) {
                // Offer-backing (impl review MAJOR-1): the interval must have OFFERED
                // ≥ ¾ of the governed rate for a downward step to be link evidence.
                long expectedDeclared =
                        (long) sustainedColumnsPerSecond() * elapsedMillis / 1000L;
                boolean offerBacked = deltaDeclared >= 0
                        && deltaDeclared * 4L >= expectedDeclared * 3L;
                stepEngaged(measured, offerBacked);
            }
        } else if (this.phase == Phase.RAMP) {
            // RAMP's qualifying differs from ENGAGED's (impl review MAJOR — the
            // byte/position denomination split): an up_to_date-dominated interval
            // moves ZERO wire bytes but is real, answered demand (a warm rejoin is
            // the whole session in that shape), and the awaiting LATCH replaces the
            // bimodal boundary-instant pair.
            boolean rampQualifying = !invalid
                    && (deltaBytes > 0 || deltaAnswered > 0)
                    && this.awaitingSeenThisInterval
                    && !this.haltSeenThisInterval;
            stepRamp(measured, deltaDeclared, deltaAnswered, elapsedMillis,
                    pingExcess, rampQualifying);
        } else if (qualifying
                && pingExcess != Integer.MIN_VALUE
                && pingExcess > ENGAGE_PING_EXCESS_MS
                && measured < ENGAGE_BELOW_BYTES_PER_SEC) {
            if (++this.engagePendingStreak >= ENGAGE_CONSECUTIVE_INTERVALS) {
                engage(measured, pingExcess);
            }
        } else {
            this.engagePendingStreak = 0;
        }
        driftMissingFloor();
    }

    /** Round-5 review M2: the missing floor is a session MIN over a SETTINGS-dependent
     *  baseline (client render distance vs server view distance — either side can
     *  change mid-session), so a stale-low floor would read the new permanent edge
     *  ring as perpetual excess and pin an engaged session at MIN forever. The ping
     *  baseline's own answer, mirrored: drift the floor UP toward the newest sample
     *  once per evaluated interval (never past it — a genuine vanilla-behind episode
     *  keeps its gap while the view keeps falling behind, and a settings shift heals
     *  in ~a minute). The min-snap in noteMissingVanilla restores a genuinely lower
     *  floor instantly. */
    private void driftMissingFloor() {
        if (this.minMissingVanilla >= 0 && this.lastMissingVanilla > this.minMissingVanilla) {
            int gap = this.lastMissingVanilla - this.minMissingVanilla;
            this.minMissingVanilla += Math.max(1, gap / 8);
        }
    }

    private void stepEngaged(long measured, boolean offerBacked) {
        if (vanillaBehind()) {
            // Vanilla-first (live round 5): the world around the player is missing —
            // whatever LSS's own rate looks like, its link share is starving vanilla's
            // catch-up and prolonging the desync window. Cut unconditionally (not
            // gated on offer-backing: this is a PRIORITY decision, not rate evidence)
            // and reset the kept-up streak; the loop re-probes once the view heals.
            // Anchored min(desired, measured) — the MINOR-3 drain rule: the interval
            // right after a deep cut measures the pre-cut in-flight tail, and a bare
            // measured anchor would RAISE the cap mid-shed (round-5 review m2).
            this.desiredBytesPerSec = Math.max(
                    Math.min(this.desiredBytesPerSec, measured) - STEP_BYTES_PER_SEC / 2,
                    MIN_RATE_BYTES_PER_SEC);
            this.keptUpStreak = 0;
            this.rateDisengageStreak = 0;
            return;
        }
        boolean shortfall = measured < this.desiredBytesPerSec - STEP_BYTES_PER_SEC / 4;
        if (shortfall && !offerBacked) {
            // The actuator under-offered (a cadence hold, the walk-cost coverage
            // limit, actionable retries, an RTT-stretched window) — the low measured
            // rate is the WINDOW's doing, not the link's. FREEZE: adjusting nothing
            // is safe; cutting here is the MAJOR-1 ratchet-to-floor defect.
            return;
        }
        if (shortfall) {
            this.desiredBytesPerSec =
                    Math.max(measured - STEP_BYTES_PER_SEC / 2, MIN_RATE_BYTES_PER_SEC);
            this.keptUpStreak = 0;
        } else if (this.movementSeenThisInterval) {
            // Live round 2: a kept-up interval that saw chunk crossings HOLDS — the
            // climb must not probe into the window where vanilla's own chunk bursts
            // are about to compete for the link (the measured movement spikes:
            // ~1500 ms while climbing +STEP into a moving player's vanilla traffic).
            // Shortfall above still cuts normally during movement; only the up-probe
            // pauses. The streak is untouched (movement neither earns nor forfeits
            // drain cadence).
        } else if (++this.keptUpStreak % DRAIN_EVERY_KEPT_UP == 0) {
            // Deterministic drain interval: bleed standing queue instead of probing
            // up. Anchored at min(desired, measured) (impl review MINOR-3): when the
            // size EWMA lags a regime change, measured can exceed desired, and a
            // measured-anchored drain would RAISE desired several-fold in one step —
            // the opposite of a bleed. Depth STEP/2 since live round 2 (with STEP/4
            // the bleed barely outran the climb's overshoot).
            this.desiredBytesPerSec = Math.max(
                    Math.min(this.desiredBytesPerSec, measured) - STEP_BYTES_PER_SEC / 2,
                    MIN_RATE_BYTES_PER_SEC);
        } else {
            this.desiredBytesPerSec += STEP_BYTES_PER_SEC;
        }
        // Disengage (a): the loop probed clear of the engage threshold and stayed there.
        if (this.desiredBytesPerSec > ENGAGE_BELOW_BYTES_PER_SEC) {
            if (++this.rateDisengageStreak >= DISENGAGE_RATE_INTERVALS) {
                disengage("sustained rate above the engage threshold");
            }
        } else {
            this.rateDisengageStreak = 0;
        }
    }

    /** The RAMP evaluation ladder (join-slow-start-plan.md §1.2 — row order is the
     *  spec). Growth is EARNED: double only on kept-up (¾·desired proportional band —
     *  the absolute STEP/4 band degenerates to ≥0 at INITIAL) or delivered-all-offered
     *  (the high-RTT rule: the stop-and-wait window caps offered at ~0.6-0.7×desired
     *  on ≥250 ms links, so a link that delivered ≥¾ of a ≥half-desired offer IS
     *  growth evidence — doubling widens the window, which is how a windowed protocol
     *  discovers capacity; ping + Mechanism B bound the overshoot). */
    private void stepRamp(long measured, long deltaDeclared, long deltaAnswered,
                          long elapsedMillis, int pingExcess, boolean qualifying) {
        // Streak semantics (impl review): a NON-QUALIFYING interval is no observation
        // — it neither credits nor resets the OPEN confirmation (a single idle blip
        // must not wipe 9 earned intervals). Only a qualifying-but-not-credited
        // interval resets.
        if (!qualifying) {
            this.engagePendingStreak = 0;
            return;
        }
        boolean credited = false;
        try {
            // Row 1 — the engage conjunct, kept VERBATIM incl. the measured-below term,
            // and GATED on the ramp having reached the regime that term describes
            // (RAMP_ENGAGE_MIN_DESIRED — see the constant: below it every session
            // trivially satisfies measured < ENGAGE_BELOW because the ramp itself caps
            // measured, and the join burst's ping would engage fast links at a MIN
            // anchor). Below the gate a congested interval falls through to the
            // plateau snap, which tracks desired DOWN toward measured — containment
            // without a false engagement. Streak 1 is a HOLD.
            if (pingExcess != Integer.MIN_VALUE
                    && pingExcess > ENGAGE_PING_EXCESS_MS
                    && measured < ENGAGE_BELOW_BYTES_PER_SEC
                    && this.desiredBytesPerSec >= RAMP_ENGAGE_MIN_DESIRED_BYTES_PER_SEC) {
                if (++this.engagePendingStreak >= ENGAGE_CONSECUTIVE_INTERVALS) {
                    engage(measured, pingExcess);
                }
                return;
            }
            this.engagePendingStreak = 0;
            // Row 2 — vanilla-behind HOLDs (never cuts here: the floor is freshly
            // learned at join and ramp rates are low — the HOLD half of vanilla-first).
            if (vanillaBehind()) {
                return;
            }
            // Row 3 — movement holds only above the JITTER threshold (impl review:
            // "> 0" against a rolling-MIN baseline holds on ordinary WAN jitter, and
            // join-then-fly would freeze the climb for the whole flight).
            boolean meaningfulExcess = pingExcess != Integer.MIN_VALUE
                    && pingExcess > RAMP_MOVEMENT_HOLD_EXCESS_MS;
            if (this.movementSeenThisInterval && meaningfulExcess) {
                return;
            }
            double columnBytes = this.sizeEwmaBytes > 0
                    ? this.sizeEwmaBytes : SLOW_START_SEED_COLUMN_BYTES;
            long offered = (long) (deltaDeclared * columnBytes * 1000.0
                    / Math.max(1L, elapsedMillis));
            // Row 4 — under-offered (demand-limited) HOLDs, and it must PRECEDE the
            // growth rungs (round-3 review MAJOR: with growth first, answered-all-
            // asked doubles on a one-column dirty-edit trickle — offered at a
            // fraction of desired — and ~17 sparse trivially-answered intervals walk
            // a never-proven link to capless OPEN, non-qualifying gaps preserving
            // both streaks along the way. The plan's row order makes under-offer the
            // gate growth must pass; a converged client sits mid-ramp and resumes
            // earning with demand. The warm rejoin is unaffected: its actuator-
            // clamped declarations put offered ≈ desired through the seed
            // conversion, so the byte-free rung still fires past this row.)
            if (offered * 2L < this.desiredBytesPerSec
                    && !this.windowLimitedSeenThisInterval) {
                // Demand-limited: HOLD (unchanged). The window-limited bypass
                // (ramp-window-limited-credit-plan.md): when the interval's walks
                // FILLED the governed burst budget and were truncated with demand
                // left over, the offer shortfall is the stop-and-wait actuator's
                // arithmetic — offered = desired/4 × cadence, and the completion-
                // clocked cadence (the 95%-outstanding fast gate) sits at the
                // SERVE rate, below the 2 Hz this threshold implies whenever the
                // server's per-player allocation drains a window slower than
                // 500 ms. The trickle (round-3 MAJOR) cannot CREDIT through the
                // bypass: the latch requires the governed cap as the binding
                // clamp, a tiny-budget trickle self-arrests within a few
                // doublings, and credits still require desired > ENGAGE_BELOW.
                return;
            }
            boolean keptUp = measured * 4L >= this.desiredBytesPerSec * 3L;
            boolean deliveredAllOffered = offered * 2L >= this.desiredBytesPerSec
                    && measured * 4L >= offered * 3L;
            // The byte-free rung (impl review MAJOR, all three lenses): an interval
            // whose declared positions were ANSWERED (any response type — a warm
            // rejoin's answers are up_to_date frames that move zero wire bytes) is
            // the actuator's position window saturated with timely service. That is
            // cap-bound, not link-bound: growth is safe by construction there (the
            // answers that carried no bytes cost the link nothing), and it is the
            // only signal a revalidation-dominated session ever produces.
            boolean answeredAllAsked = deltaDeclared > 0
                    && deltaAnswered * 4L >= deltaDeclared * 3L;
            if (keptUp || deliveredAllOffered || answeredAllAsked) {
                this.desiredBytesPerSec = Math.min(this.desiredBytesPerSec * 2,
                        SLOW_START_CEILING_BYTES_PER_SEC);
                if (this.desiredBytesPerSec > ENGAGE_BELOW_BYTES_PER_SEC) {
                    credited = true;
                    if (++this.rampOpenStreak >= DISENGAGE_RATE_INTERVALS) {
                        openFromRamp();
                    }
                }
                return;
            }
            // Row 5 — plateau (offer-backed shortfall): the one-time overhang snap —
            // a bare HOLD leaves desired at up to 2x capacity, a permanent standing
            // offer; the snap bounds it at 25% and never raises. Also the containment
            // path for congestion BELOW the row-1 gate: excess intervals land here
            // and desired tracks measured down. BYTE EVIDENCE REQUIRED (round-3
            // review): a zero-byte answer-only interval reaches this row only via
            // the answered-qualifying term with PARTIAL answers (growth failed) —
            // measured is 0 because nothing needed bytes, not because the link is
            // slow, and snapping would wipe the ramp to INITIAL on a server hiccup.
            // Such an interval HOLDs.
            if (measured <= 0) {
                return;
            }
            // A window-limited UNDER-OFFERED interval whose growth rungs did not
            // fire is not offer-backed evidence — the ENGAGED path's freeze rule
            // (MAJOR-1): HOLD, never snap. Unreachable while the latch is false
            // (the un-latched under-offer case returned at Row 4 above), so the
            // un-latched ladder is bit-identical.
            if (offered * 2L < this.desiredBytesPerSec) {
                return;
            }
            this.desiredBytesPerSec = Math.max(SLOW_START_INITIAL_BYTES_PER_SEC,
                    Math.min(this.desiredBytesPerSec, measured * 5L / 4L));
        } finally {
            if (qualifying) {
                if (credited) {
                    this.rampUncreditedRun = 0;
                } else if (this.windowLimitedSeenThisInterval
                        && this.rampUncreditedRun == 0) {
                    // Forgiven ONCE, and only for a WINDOW-LIMITED interval (the
                    // implementation panel's MAJOR: unscoped forgiveness loosened
                    // the OPEN confirmation for every ramp session — alternating
                    // credited/uncredited demand could confirm at ~3/8 average
                    // answered where main required 10 consecutive). The scoped
                    // case is exactly plan §3.4's aliasing shape: in the stop-and-
                    // wait steady state every interval latches, and the ~1-in-6
                    // window whose 4th batch drains into the next interval fails
                    // answeredAllAsked without being degradation.
                    this.rampUncreditedRun = 1;
                } else {
                    // Un-latched uncredited (main's semantics, restored) or the
                    // second consecutive miss (real degradation): reset.
                    this.rampUncreditedRun = 1;
                    this.rampOpenStreak = 0;
                }
            }
        }
    }

    /** The single phase entry point — see tick(). */
    private void enterFromDisabled() {
        if (this.slowStartEnabled) {
            this.phase = Phase.RAMP;
            this.desiredBytesPerSec = this.restartHintBytesPerSec > 0
                    ? Math.clamp(this.restartHintBytesPerSec,
                            SLOW_START_INITIAL_BYTES_PER_SEC, SLOW_START_CEILING_BYTES_PER_SEC)
                    : SLOW_START_INITIAL_BYTES_PER_SEC;
        } else {
            this.phase = Phase.OPEN;
            this.desiredBytesPerSec = 0;
        }
        this.rampOpenStreak = 0;
        this.rampUncreditedRun = 0;
        this.keptUpStreak = 0;
        this.rateDisengageStreak = 0;
        this.engagePendingStreak = 0;
    }

    private void openFromRamp() {
        this.phase = Phase.OPEN;
        this.desiredBytesPerSec = 0;
        this.rampOpenStreak = 0;
        this.rampUncreditedRun = 0;
        if (!this.slowStartNoted) {
            this.slowStartNoted = true;
            LSSLogger.info("LOD slow start complete — the connection kept up through the"
                    + " ramp; LOD downloads now run uncapped (logged once per session)");
        }
    }

    /** The manager re-asserts this every tick from config (wiring, not session
     *  state). The toggle × phase table (plan §1.2): OFF demotes RAMP→OPEN only —
     *  ENGAGED is untouched (un-capping a congested link here is the round-5
     *  runaway); ON takes effect at the next DISABLED→active entry, never by
     *  re-clamping a live session. */
    void setSlowStartEnabled(boolean enabled) {
        if (this.slowStartEnabled && !enabled && this.phase == Phase.RAMP) {
            this.phase = Phase.OPEN;
            this.desiredBytesPerSec = 0;
            this.rampOpenStreak = 0;
            this.engagePendingStreak = 0;
        }
        this.slowStartEnabled = enabled;
    }

    private void engage(long measured, int pingExcess) {
        this.phase = Phase.ENGAGED;
        this.engagePendingStreak = 0;
        this.desiredBytesPerSec =
                Math.max(measured - STEP_BYTES_PER_SEC / 2, MIN_RATE_BYTES_PER_SEC);
        this.keptUpStreak = 0;
        this.rateDisengageStreak = 0;
        if (!this.engageNoted) {
            this.engageNoted = true;
            LSSLogger.info("LOD transfer governor engaged at "
                    + (this.desiredBytesPerSec / 1024) + " KB/s (link congestion: ping +"
                    + pingExcess + " ms over baseline; LOD downloads now pace themselves"
                    + " below link capacity; logged once per session)");
        }
    }

    private void disengage(String reason) {
        this.phase = Phase.OPEN;
        this.desiredBytesPerSec = 0;
        this.keptUpStreak = 0;
        this.rateDisengageStreak = 0;
        if (!this.disengageNoted) {
            this.disengageNoted = true;
            LSSLogger.info("LOD transfer governor disengaged (" + reason
                    + "; logged once per session)");
        }
    }

    private void recordSizeSample(double meanColumnBytes) {
        if (this.sizeEwmaBytes < 0) {
            this.sizeEwmaBytes = meanColumnBytes;
            return;
        }
        double alpha = meanColumnBytes > this.sizeEwmaBytes
                ? SIZE_EWMA_UP_ALPHA : SIZE_EWMA_DOWN_ALPHA;
        this.sizeEwmaBytes += alpha * (meanColumnBytes - this.sizeEwmaBytes);
    }

    private void updatePingBaseline(long nowMillis, int pingMs) {
        if (pingMs > 0) {
            this.lastPingMs = pingMs;
            if (this.pingBaselineMs < 0) {
                // Seed from the first NONZERO sample (review m9): a ~0 anchor would read
                // a distant player's natural ping as permanent excess.
                this.pingBaselineMs = pingMs;
                this.baselineDriftAnchorMillis = nowMillis;
            } else if (pingMs < this.pingBaselineMs) {
                this.pingBaselineMs = pingMs;
            }
        }
        if (this.pingBaselineMs >= 0) {
            long driftSec = (nowMillis - this.baselineDriftAnchorMillis) / 1000L;
            if (driftSec > 0) {
                this.pingBaselineMs += (int) Math.min(Integer.MAX_VALUE,
                        driftSec * BASELINE_DRIFT_MS_PER_SEC);
                this.baselineDriftAnchorMillis += driftSec * 1000L;
            }
        }
    }

    private void seedInterval(long nowMillis, long wireBytes, long columns,
                              long declared, long answered, int awaitingSize) {
        this.intervalSeeded = true;
        this.intervalStartMillis = nowMillis;
        this.intervalStartWireBytes = wireBytes;
        this.intervalStartColumns = columns;
        this.intervalStartDeclared = declared;
        this.intervalStartAnswered = answered;
        this.awaitingAtStart = awaitingSize > 0;
        this.haltSeenThisInterval = false;
        this.movementSeenThisInterval = false;
        this.awaitingSeenThisInterval = awaitingSize > 0;
        this.windowLimitedSeenThisInterval = false;
    }

    private void hardReset() {
        this.phase = Phase.DISABLED;
        this.rampOpenStreak = 0;
        // restartHintBytesPerSec deliberately SURVIVES (the DISABLED→RAMP re-entry
        // and dimension-change paths depend on it); it dies only in reset().
        this.desiredBytesPerSec = 0;
        this.keptUpStreak = 0;
        this.rateDisengageStreak = 0;
        this.engagePendingStreak = 0;
        this.intervalSeeded = false;
        this.intervalStartMillis = 0;
        this.haltSeenThisInterval = false;
        this.movementSeenThisInterval = false;
        this.awaitingSeenThisInterval = false;
        this.windowLimitedSeenThisInterval = false;
        this.rampUncreditedRun = 0;
        // The size estimator and ping baseline survive a config-toggle reset (they are
        // measurements, not control state) but die with the session in reset().
    }

    /** Chunk-crossing hook (live round 2): latches the movement hold for the current
     *  measurement interval — see the kept-up branch. Main client thread. */
    void noteMovement() {
        this.movementSeenThisInterval = true;
    }

    /** Window-limited hook (ramp-window-limited-credit-plan.md §3.2): the manager
     *  calls this after a successfully-sent scan that was truncated by the governed
     *  burst cap (provenance-checked on the wiring side — taper-clamped, manual-
     *  capped, and untruncated walks never reach here). Latches the Row-4 bypass
     *  for the current measurement interval. Main client thread. */
    void noteWindowLimited() {
        this.windowLimitedSeenThisInterval = true;
    }

    /** Missing-vanilla-chunks sample (live round 5) — the scanner's 1 Hz periodic
     *  count; the session MIN learns the permanent view-edge ring so only EXCESS
     *  reads as vanilla-behind. Main client thread. */
    void noteMissingVanilla(int missing) {
        if (missing < 0) return;
        this.lastMissingVanilla = missing;
        if (this.minMissingVanilla < 0 || missing < this.minMissingVanilla) {
            this.minMissingVanilla = missing;
        }
    }

    private boolean vanillaBehind() {
        return this.lastMissingVanilla >= 0 && this.minMissingVanilla >= 0
                && this.lastMissingVanilla - this.minMissingVanilla
                        >= MISSING_VANILLA_CUT_EXCESS;
    }

    /** Dimension change (impl review MINOR-2): same connection, same link — the
     *  MEASUREMENTS (ping baseline, size estimate) survive so a still-congested link
     *  re-engages in 1-2 intervals off the kept baseline; only control state drops
     *  (a full reset would reseed the baseline from the CONGESTED current ping and
     *  the engagement conjunct could never fire again). */
    /** Carry the governor across a same-connection manager REBUILD (a mid-session
     *  SessionConfig re-push: {@code /lsslod set}, a Paper /reload re-attach, the
     *  establish heal). Same link, same session — the review-wave C-M1 finding:
     *  discarding state here un-capped a governed slow link for the seconds a fresh
     *  governor needs to re-learn congestion (the round-5 runaway shape, briefly),
     *  and reseeded the ping baseline from the CONGESTED current reading (the
     *  MINOR-2 hazard onDimensionChange was built to avoid). Copies measurements
     *  AND control; the interval accumulator deliberately reseeds so the first
     *  new-manager tick re-bases the cumulative counters instead of inheriting a
     *  torn window. */
    void adoptFrom(TransferRateGovernor other) {
        this.pingBaselineMs = other.pingBaselineMs;
        this.baselineDriftAnchorMillis = other.baselineDriftAnchorMillis;
        this.lastPingMs = other.lastPingMs;
        this.sizeEwmaBytes = other.sizeEwmaBytes;
        this.lastMissingVanilla = other.lastMissingVanilla;
        this.minMissingVanilla = other.minMissingVanilla;
        this.phase = other.phase;
        this.desiredBytesPerSec = other.desiredBytesPerSec;
        this.keptUpStreak = other.keptUpStreak;
        this.rateDisengageStreak = other.rateDisengageStreak;
        this.engagePendingStreak = other.engagePendingStreak;
        this.slowStartEnabled = other.slowStartEnabled;
        this.restartHintBytesPerSec = other.restartHintBytesPerSec;
        this.rampOpenStreak = other.rampOpenStreak;
        this.rampUncreditedRun = other.rampUncreditedRun;
        this.engageNoted = other.engageNoted;
        this.disengageNoted = other.disengageNoted;
        this.slowStartNoted = other.slowStartNoted;
        this.intervalSeeded = false;
        this.haltSeenThisInterval = false;
        this.movementSeenThisInterval = false;
        this.awaitingSeenThisInterval = false;
        this.windowLimitedSeenThisInterval = false;
    }

    void onDimensionChange() {
        // ssthresh memory (plan §1.2): the link didn't change, the view did — re-ramp
        // from half the proven rate instead of INITIAL. Read the RAW field BEFORE the
        // reset (the accessor returns 0 outside ENGAGED/RAMP); an OPEN session proved
        // >= the engage threshold to open. Honesty note: for a proven-fast OPEN
        // session this is a mild regression vs today's stays-uncapped hop (~20-30 s
        // of re-confirmation), accepted under the join-latency-first priority; for a
        // governed link it is strictly SAFER than today's uncapped re-engage gap.
        if (this.phase == Phase.OPEN) {
            this.restartHintBytesPerSec = ENGAGE_BELOW_BYTES_PER_SEC;
        } else if (this.phase == Phase.RAMP || this.phase == Phase.ENGAGED) {
            this.restartHintBytesPerSec = Math.clamp(this.desiredBytesPerSec / 2,
                    SLOW_START_INITIAL_BYTES_PER_SEC, SLOW_START_CEILING_BYTES_PER_SEC);
        }
        hardReset();
        // A new dimension is a new view — the missing floor re-learns (the edge ring
        // differs per dimension; a stale low floor would read the whole fresh view
        // as vanilla-behind and cut for nothing).
        this.lastMissingVanilla = -1;
        this.minMissingVanilla = -1;
    }

    /** Session teardown (the reset family): everything dies with the session. */
    void reset() {
        hardReset();
        this.restartHintBytesPerSec = 0;
        this.slowStartNoted = false;
        this.lastMissingVanilla = -1;
        this.minMissingVanilla = -1;
        this.sizeEwmaBytes = -1;
        this.pingBaselineMs = -1;
        this.lastPingMs = -1;
        this.baselineDriftAnchorMillis = 0;
        this.engageNoted = false;
        this.disengageNoted = false;
    }

    /** Production-dead since the phase machine (every live caller reads
     *  {@link #getPhase()}); kept as the test suite's accessor. */
    boolean isEngaged() { return this.phase == Phase.ENGAGED; }

    Phase getPhase() { return this.phase; }

    /** Test accessor: the asymmetric size estimator's current value (-1 = no sample). */
    double getSizeEstimateForTest() { return this.sizeEwmaBytes; }

    /** The window-limited latch's current-interval state — the diag receipt
     *  (dynamics review minor-2: a "still parked" field report must be able to
     *  answer "did the latch arm?" without a code read) and the wiring tests'
     *  observable. */
    boolean windowLimitedLatched() { return this.windowLimitedSeenThisInterval; }

    /** RAMP→OPEN confirmation progress for the diag line (credits=N/10). */
    int rampOpenStreakForDiag() { return this.rampOpenStreak; }

    /** Desired rate in bytes/s while ENGAGED or RAMP, 0 otherwise — diag only. */
    long getDesiredBytesPerSec() {
        return (this.phase == Phase.ENGAGED || this.phase == Phase.RAMP)
                ? this.desiredBytesPerSec : 0;
    }

    /**
     * The SUSTAINED-rate cap in columns/s for the scanner's spacing-gate site, or 0 =
     * no governed cap. Floors at 1 (review m2: {@code columnRateCap}'s contract is
     * {@code <= 0} = OFF, and MIN_RATE over a >64 KB column EWMA would integer-convert
     * to the off sentinel exactly when the cap must bind hardest). Division guarded:
     * engaged-with-no-size-sample supplies no cap rather than garbage.
     */
    int sustainedColumnsPerSecond() {
        if (this.phase == Phase.RAMP) {
            // Pre-sample conversion seeds HIGH (fewer columns than the byte budget
            // intends — the safe join direction); the first real sample replaces it.
            double columnBytes = this.sizeEwmaBytes > 0
                    ? this.sizeEwmaBytes : SLOW_START_SEED_COLUMN_BYTES;
            return Math.max(1, (int) Math.min(Integer.MAX_VALUE,
                    (long) (this.desiredBytesPerSec / columnBytes)));
        }
        if (this.phase != Phase.ENGAGED || this.sizeEwmaBytes <= 0) return 0;
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE,
                (long) (this.desiredBytesPerSec / this.sizeEwmaBytes)));
    }

    /**
     * The BURST cap for the scanner's budget-clamp site: {@code ceil(R/4)}, floored at
     * 1 — the seam split (review M2). One value at both sites would declare 1 Hz
     * FULL-second batches (the spacing gate is {@code ticks × cap < 20 × lastSent} and
     * the walk fills the clamped budget), a burst 4× the plan's target that grazes
     * Mechanism B's threshold; quarter-batches equilibrate the spacing gate at its
     * 5-tick floor — 4 Hz, burst ≈ desired/4.
     */
    int burstColumnsPerSecond() {
        int sustained = sustainedColumnsPerSecond();
        if (sustained <= 0) return 0;
        return Math.max(1, (sustained + 3) / 4);
    }
}
