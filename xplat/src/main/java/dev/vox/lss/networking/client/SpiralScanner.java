package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.compat.ModCompat;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Expanding Chebyshev ring scanner that produces the client's want-set: every position it
 * still wants, closest-first, written straight into {@link LodRequestManager}'s send buffers
 * and shipped whole in the same tick. Owns the scan policy — the adaptive cadence (a 20-tick
 * fallback plus the completion-triggered fast re-scan, see {@link #fastRescanDue}) and the
 * budget with its queue-pressure scale (the vanilla-load scale is retired: server-side
 * priority/throttling owns that protection under v17).
 *
 * <p>The scan does NOT suppress in-flight positions: under want-set semantics the server may
 * silently supersede any not-yet-admitted ask, and the periodic re-declare is the only thing
 * that heals it. An awaited position is therefore an ordinary unsatisfied want-set member,
 * which also means it blocks ring confirmation until its data actually lands. The adaptive
 * fast trigger reads the awaiting-set SIZE as a cadence input — it never filters what the
 * walk declares.
 */
class SpiralScanner {

    /**
     * Adaptive cadence (docs/planning/adaptive-scan-cadence-design.md): minimum ticks
     * between consecutive fires — 5 ticks = 250 ms, capping the fast cadence at 4 Hz. Bounds
     * the worst-case C2S declaration rate (and its ~12.8 KB/batch upstream cost) and the
     * render-thread walk rate.
     */
    static final int FAST_RESCAN_MIN_INTERVAL_TICKS = 5;
    /**
     * Fast fires require ≥95% of the last declared batch answered:
     * {@code outstanding <= lastSentCount / 20}. Integer math — declares under 20 degenerate
     * to "outstanding == 0", the strictest (correct) form for tiny tails. Also bounds the
     * redundant in-flight re-asks a fast batch can carry at 5%, and makes straggler chatter
     * self-limiting: a fast walk that declares only the stragglers shrinks the next
     * threshold 20-fold (geometric tightening).
     */
    static final int FAST_RESCAN_OUTSTANDING_DIVISOR = 20;
    /**
     * Fast fires require MILD downstream pressure at most: each pipe signal below 1/4 of its
     * halt threshold (decode queue &lt; 1500 columns AND &lt; 48 MiB queued bytes — the byte
     * halt is the one that binds for real terrain columns — and consumer ingest backlog
     * &lt; 1536 sections at the production constants). Deliberately proportional, NOT strict
     * zero: a
     * received column leaves the awaiting set and enters the decode queue in the same
     * network-handler statement pair, so at the instant outstanding reaches 5% the queue
     * holds that batch's peak — a strict-zero gate would suppress the fast path in exactly
     * the data-bearing warm-backfill case it exists for. Under a sustained decode
     * bottleneck the cadence bang-bangs around this line, i.e. rate-matches the decoder;
     * deeper pressure (including the tick recovering out of a backpressure halt) stays at
     * the 1 Hz fallback where the budget taper + halt own regulation.
     */
    static final int FAST_RESCAN_PRESSURE_DIVISOR = 4;
    /**
     * Fast fires require the NEXT walk to be cheap: at most this many ring positions
     * examined (see {@link #predictedWalkCost()}). Replaces the original
     * {@code confirmedRing > 0} term, which was a PROXY for walk cost and — because
     * {@link #recenter(int)} then ZEROED the prefix on every chunk-boundary crossing
     * (the pre-retention semantics, now the kill-switch-off arm) while nothing
     * re-derived it until the next walk — measured movement instead. At 33 blocks/s
     * crossings run 2.76 Hz against 1 Hz scans, so the proxy was structurally inert for the
     * whole of any sustained flight: the elytra trace of 2026-08-01 measured 2–3 Hz standing
     * still and exactly 1.000 s gaps for 23 consecutive seconds of flight
     * (docs/planning/elytra-chunk-wall-investigation-2026-08-01.md §8.6.3).
     *
     * <p>Calibration: a walk to ring R costs {@code 4R(R+1)} — NOT 4R² — so the measured
     * flight walk (frontier ring 75) is 22,800 and a full walk at the default 256 LOD
     * distance is 263,168. 65,536 gives the flight case ~2.9× headroom while refusing the
     * warm full-disc walk by 4×. The budget is a FRAME budget, not a per-second one: an
     * admitted walk spends its whole cost inside one client tick (~65k × 50–100 ns ≈
     * 3–7 ms), and the 250 ms floor lets that happen at most 4×/s.
     *
     * <p>Refusing the expensive case is deliberate policy, not a limitation: a disc already
     * satisfied out to the LOD distance is both expensive to walk AND has little left to
     * fetch, so the 1 Hz fallback is right there. It is never a regression — movement rides
     * 1 Hz unconditionally today.
     */
    static final int FAST_RESCAN_MAX_WALK_COST = 65_536;

    private SessionConfigS2CPayload sessionConfig;

    private int confirmedRing = 0;
    private int scanRing = 0;
    /**
     * The vanilla-view exclusion radius the last walk ran with, -1 = no walk yet. A SHRINK
     * (render distance lowered) turns positions inside the old exclusion — which confirmed
     * their rings without ever being declared — into LOD-needing positions below the
     * confirmed prefix, structurally unreachable until movement; the walk resets the prefix
     * once per shrink (see {@link #scan}). A grow needs nothing: newly excluded positions
     * are skipped without breaking confirmation.
     */
    private int lastExclusionRadius = -1;
    /** The effective LOD distance the last walk ran with, -1 = no walk yet — the shrink
     *  rung's memory (see the scan-head comment; the F1 field above is its exclusion
     *  twin). */
    private int lastLodDistance = -1;
    /**
     * Did the last walk stop early because the budget filled? Decides which end
     * {@link #predictedWalkCost()} measures to: a truncated walk stops at {@link #scanRing},
     * an untruncated one iterates every ring out to the LOD distance. Starts false so a
     * never-walked scanner predicts the full disc — fail-closed.
     */
    private boolean lastWalkTruncated = false;
    private int scanTickCounter = LSSConstants.TICKS_PER_SECOND - 1; // starts at max so first scan fires immediately on join
    private int missingVanillaChunks = Integer.MAX_VALUE;

    // --- Reopened rings (docs/planning/scanner-reopened-rings-plan.md) ---
    // Rings BELOW the confirmed prefix that must be re-walked, so a chunk crossing or a
    // dirty mark no longer collapses the prefix to 0 (the measured render-thread hitch:
    // one full-disc walk = ~1M classify probes = 30-90 ms on one tick at distance 512).
    // long[33] covers rings 0..2048 inclusive — MAX_LOD_DISTANCE itself is a walkable
    // ring. Main client thread only, like the rest of the scan state.
    private final long[] reopenedRings = new long[33];
    /** Chebyshev crossing delta at which {@link #recenter(int)} gives up on shifting and
     *  falls back to the full reset (teleport-scale movement; the prune hysteresis fires
     *  at the same magnitude, so all shifted state would be pruned anyway). */
    static final int RECENTER_FULL_RESET_DELTA = 8;
    /** Overflow valve: above this many set bits the bitset clears and the prefix drops to
     *  0 — today's full-reset behavior as the conservative fallback (pathological only:
     *  a dirty storm touching more distinct rings than this). */
    static final int REOPENED_RING_VALVE = 64;
    /** Set by {@link #recenter(int)}, cleared when a scan actually WALKS (fires-not-
     *  declares — a 0-count walked fire clears it; converged clients fire 1 Hz walks, so
     *  it cannot stick). While set, {@link #predictedWalkCost()} uses the bare from-zero
     *  formula — the pre-retention movement semantics the elytra-wall calibration pin
     *  asserts exactly. */
    private boolean recenteredSinceLastFire = false;
    /** Did the last walk budget-break at a ring BELOW the prefix? Then {@code scanRing}
     *  is the low reopened ring and the truncated-walk prediction branch would be blind
     *  to the frontier interval the next walk also iterates — predict the full span
     *  instead (fail closed; plan v1.1 MAJOR-2). */
    private boolean truncatedBelowPrefix = false;
    /** Kill switch seam ({@code enableScanPrefixRetention}, default true) — injectable
     *  like {@link #adaptiveCadenceEnabled}. False = every reset path keeps today's
     *  prefix-to-0 semantics (delegation, not a parallel implementation): the field A/B
     *  lever, and the safety net for the silent-orphan failure class no conservation law
     *  can see. */
    BooleanSupplier prefixRetentionEnabled =
            () -> LSSClientConfig.CONFIG.enableScanPrefixRetention;
    /** Kill switch seam for the section-store ring fast path
     *  (docs/planning/quadtree-client-state-plan.md; {@code enableQuadtreeScan}, default
     *  true). ON: a ring whose crossed leaves all have clear needs-masks confirms in
     *  O(leaves-on-ring) without touching its positions ({@link ColumnStateMap#ringNeedsFree})
     *  — emission, confirmation, truncation, and cadence state are bit-identical to the
     *  legacy per-position walk by construction (the needs mask is derived from the same
     *  classify ladder; QuadtreeWalkDifferentialTest pins the equivalence). OFF: the
     *  per-position walk runs verbatim — the field A/B lever. NOTE the switch gates the
     *  WALK only; the section-backed state store is not switchable (jar rollback is the
     *  store's lever). */
    BooleanSupplier quadtreeScanEnabled =
            () -> LSSClientConfig.CONFIG.enableQuadtreeScan;
    /** Rings the fast path confirmed without a position walk — session diagnostic
     *  (diag {@code ring_skips=}, exporter {@code scan.quad_ring_skips}). */
    private long quadRingSkips;
    /** REOPENED_RING_VALVE overflow firings — session diagnostic (plan §6 phase 0: the
     *  B1 "is the valve pathological-only?" measurement; diag {@code valve=}, exporter
     *  {@code scan.valve_trips}). Counted in BOTH walk modes — the reopen bookkeeping
     *  runs identically either way. */
    private long valveTrips;

    // --- Adaptive-cadence state (see fastRescanDue) ---
    /**
     * The armed state: the count of the last want-set batch the manager DECLARED (tracker
     * replaced + send attempted). Written only by {@link #noteDeclared} from
     * {@link LodRequestManager#tickScanPhase} beside {@code tracker.replaceWith} — so
     * "lastSentCount == what the awaiting set was replaced with" holds by construction —
     * and re-zeroed by the send-failure catch, {@code disconnect()}, {@link #reset()} and
     * {@link #resetScanCounter()}. 0 = disarmed: a converged client (0-count walk) must
     * fall back to the 1 Hz re-walk, never a 250 ms walk loop, and a dying connection must
     * not retry at 4 Hz. Rigs that drive {@link #maybeScan} directly without
     * {@code tickScanPhase} never arm and see pre-adaptive behavior bit-identically.
     */
    private int lastSentCount;
    /** The awaiting-set size ({@code tracker::size} in production; main-client-thread only).
     *  Null = fast path off (bare test rigs). */
    private IntSupplier outstandingSupplier;
    /** Config seam ({@code enableAdaptiveScanCadence} kill switch) — injectable so tests
     *  don't mutate the global CONFIG (the ingestBacklogSupplier pattern). */
    BooleanSupplier adaptiveCadenceEnabled =
            () -> LSSClientConfig.CONFIG.enableAdaptiveScanCadence;
    /**
     * Manual column-rate cap seam (docs/planning/client-column-rate-cap-design.md), the
     * {@link #adaptiveCadenceEnabled} pattern so tests don't mutate the global CONFIG.
     * {@code <= 0} = off, bit-identical to pre-cap behavior. A positive R clamps the
     * want-set budget to {@code min(WANT_SET_BUDGET, R)} (bounds the burst any one
     * declaration can trigger) and adds the size-weighted spacing gate to
     * {@link #fastRescanDue} (bounds the sustained rate: after declaring N positions the
     * next FAST fire waits at least {@code 20*N/R} ticks — each batch pre-pays its own
     * interval, a stateless token bucket, so rate {@code <= R/sec} by construction). The
     * 20-tick fallback is deliberately not consulted here: re-declaration is the want-set's
     * only self-heal and must stay un-gateable, and with the budget clamped to R the
     * fallback alone stays {@code <= R/sec} anyway.
     */
    IntSupplier columnRateCap = () -> LSSClientConfig.CONFIG.lodColumnsPerSecondLimit;
    /**
     * The BUDGET-CLAMP half of the cap seam (the transfer governor's seam split —
     * adaptive-transfer-rate-plan.md review M2). Defaults to whatever
     * {@link #columnRateCap} supplies, so the manual knob keeps its shipped
     * single-value shape (budget = R, spacing = R → 1 Hz full batches). The governed
     * path supplies {@code ceil(R/4)} here while {@link #columnRateCap} carries R:
     * the spacing gate then equilibrates at the 5-tick floor — 4 Hz quarter-batches,
     * burst ≈ a quarter-second of the governed rate instead of a full second.
     */
    IntSupplier columnBurstCap = () -> this.columnRateCap.getAsInt();
    private boolean lastScanWasFast;
    private long fastScans; // session-scoped diagnostic counter (reset() zeroes it)
    /** Ticks the rate cap's spacing gate refused when every OTHER fast-fire condition had
     *  passed — "the knob is binding" evidence for weak-client reports (surfaced in the
     *  client diag Budget line). Session-scoped like {@link #fastScans}; reset() zeroes it. */
    private long rateGated;

    // Last scan budget tracking
    private int lastBudget;
    private int lastQueued;
    /** Did the burst-cap clamp SET the last walk's final budget (below the constant,
     *  un-reduced by the taper)? The governed window-limit's provenance half —
     *  see the maybeScan recording site (ramp-window-limited-credit-plan.md §3.2). */
    private boolean lastBudgetCapClamped;

    // Cached Voxy view distance — refreshed every 20th getEffectiveLodDistance()
    // INVOCATION, and the walk is not the dominant caller: getPruneDistance() reads it per
    // received column and per movement crossing, so the effective window is sub-second
    // whenever columns are arriving (pinned by voxyDistanceRefreshIsInvocationCountBased…).
    // The lookup is a cheap MethodHandle call, so fresher is fine.
    private int cachedVoxyDistance = -1; // -1 = not present
    private int voxyDistanceStaleness = 0;

    /** Set once per session, alongside {@link #reset()}. */
    void setConfig(SessionConfigS2CPayload sessionConfig) {
        this.sessionConfig = sessionConfig;
    }

    // --- Reopened-ring bit helpers (main client thread only) ---

    private void reopenedSetBit(int ring) {
        this.reopenedRings[ring >> 6] |= (1L << (ring & 63));
    }

    private void reopenedClearBit(int ring) {
        this.reopenedRings[ring >> 6] &= ~(1L << (ring & 63));
    }

    private void clearAllReopened() {
        java.util.Arrays.fill(this.reopenedRings, 0L);
    }

    int reopenedRingCount() {
        int n = 0;
        for (long w : this.reopenedRings) n += Long.bitCount(w);
        return n;
    }

    /** The lowest set ring at or after {@code from}, or -1. */
    private int nextReopenedBit(int from) {
        if (from < 0) from = 0;
        int word = from >> 6;
        if (word >= this.reopenedRings.length) return -1;
        long w = this.reopenedRings[word] & (-1L << (from & 63));
        while (true) {
            if (w != 0) return (word << 6) + Long.numberOfTrailingZeros(w);
            if (++word >= this.reopenedRings.length) return -1;
            w = this.reopenedRings[word];
        }
    }

    private int lowestReopenedBit() {
        return nextReopenedBit(0);
    }

    /** Union of the bitset shifted by every offset in [-d, d] — a reopened ring r around
     *  the old center spans rings [r-d, r+d] around the new one. */
    private void shiftReopenedBits(int d) {
        // orShifted's word math is only valid for 1 <= |k| <= 63 (Java shifts are mod-64,
        // so a >= 64 shift would silently corrupt the union). RECENTER_FULL_RESET_DELTA
        // bounds every caller; the constants pin in SpiralScannerTest enforces the bound
        // at build time, this assert at test time.
        assert d > 0 && d < 64 : d;
        long[] src = this.reopenedRings.clone();
        clearAllReopened();
        for (int k = -d; k <= d; k++) {
            orShifted(src, k);
        }
    }

    /** OR {@code src} shifted by {@code k} rings (negative = toward ring 0) into the live
     *  bitset. |k| < 64 always (RECENTER_FULL_RESET_DELTA bounds it). */
    private void orShifted(long[] src, int k) {
        int n = this.reopenedRings.length;
        if (k == 0) {
            for (int i = 0; i < n; i++) this.reopenedRings[i] |= src[i];
            return;
        }
        int shift = Math.abs(k);
        for (int i = 0; i < n; i++) {
            long lo, hi;
            if (k > 0) { // toward higher rings
                lo = src[i] << shift;
                hi = i > 0 ? src[i - 1] >>> (64 - shift) : 0;
            } else {     // toward ring 0
                lo = src[i] >>> shift;
                hi = i + 1 < n ? src[i + 1] << (64 - shift) : 0;
            }
            int dst = i;
            this.reopenedRings[dst] |= (lo | hi);
        }
    }

    /**
     * Reopen one ring for re-walking (the manager's dirty sites, the crescent band, the
     * actionable-retry collect). No-ops above the current effective LOD distance
     * (set-time clamp — the only guard keeping bits inside the lod range: the below-prefix
     * invariant sweep bounds shifted bits, and a lod SHRINK full-resets via the scan-head
     * rung, so no other above-lod source exists) and at/above the prefix (the frontier
     * walk covers those). With retention off, delegates to today's semantics: the prefix
     * drops to 0.
     */
    void reopenRing(int ring) {
        reopenRing(ring, this.sessionConfig == null
                ? LSSConstants.MAX_LOD_DISTANCE : getEffectiveLodDistance());
    }

    /** The current set-time lod bound for a caller about to issue a BURST of reopens
     *  (the manager's dirty batch loop) — hoisted once so a WorldEdit-scale broadcast
     *  does not bump the Voxy view-distance cache's invocation counter per position
     *  (since-release review M1). */
    int currentReopenLod() {
        return this.sessionConfig == null
                ? LSSConstants.MAX_LOD_DISTANCE : getEffectiveLodDistance();
    }

    /** The hoisted-lod core: recenter's band loop, the scan-time retry collect, and the
     *  manager's dirty batch pass the bound once instead of re-reading the effective
     *  distance per call (each read bumps the Voxy view-distance cache's invocation
     *  counter — see its comment). */
    void reopenRing(int ring, int lod) {
        if (!this.prefixRetentionEnabled.getAsBoolean()) {
            this.confirmedRing = 0;
            return;
        }
        if (ring < 0) return;
        if (ring > lod) return;
        if (ring >= this.confirmedRing) return;
        reopenedSetBit(ring);
        if (reopenedRingCount() > REOPENED_RING_VALVE) {
            // Overflow valve: conservative fallback to today's full reset.
            this.valveTrips++;
            this.confirmedRing = 0;
            clearAllReopened();
        }
    }

    /**
     * Advance the scan cadence and, when it fires with a nonzero budget, walk the rings
     * and write the complete want-set (closest-first) into {@code posOut}/{@code tsOut}.
     *
     * @param missingVanilla evaluated only on PERIODIC fires (diagnostics only — fast fires
     *        keep the last value rather than 4×-ing an O((2·vd+1)²) hasChunk sweep)
     * @return -1 when no walk happened this tick (cadence not fired); otherwise the
     *         number of want-set entries written
     *         (0 = walked and found nothing — the converged case; the caller must then
     *         send NOTHING but still replace its awaiting set)
     */
    int maybeScan(int playerCx, int playerCz, int viewDistance,
                  int columnQueueSize, int columnQueueHaltThreshold,
                  long columnQueueBytes, long columnQueueByteHaltThreshold,
                  int ingestBacklogSections, int ingestBacklogHaltThreshold,
                  IntSupplier missingVanilla,
                  ColumnStateMap columns,
                  long[] posOut, long[] tsOut) {
        int ticksSinceFire = ++this.scanTickCounter;
        boolean fast = ticksSinceFire < LSSConstants.TICKS_PER_SECOND;
        if (fast && !fastRescanDue(ticksSinceFire, playerCx, playerCz, viewDistance, columns,
                columnQueueSize, columnQueueHaltThreshold,
                columnQueueBytes, columnQueueByteHaltThreshold,
                ingestBacklogSections, ingestBacklogHaltThreshold)) {
            return -1;
        }
        // Either fire resets the counter: the 20-tick fallback measures from the LAST batch
        // ("more than 1 s since the last declaration ⇒ fire regardless"). A position a fast
        // walk omitted (budget/center shift under movement) waits at most
        // 2*TICKS_PER_SECOND - 1 = 39 ticks for its re-declaration when ONE fast fire
        // intervenes; a chain of budget-truncated fast walks can extend that, all
        // self-healing by re-declaration.
        this.scanTickCounter = 0;

        // "Last" can be arbitrarily old under SUSTAINED fast cadence (a long warm backfill
        // never takes the periodic branch) — acceptable for a diagnostic-only stat.
        if (!fast) this.missingVanillaChunks = missingVanilla.getAsInt();

        // Compute scan budget: base × pressure-scale. The base is
        // the ONE want-set budget — a constant; no client budget derives from any server cap
        // (server-owned generation). Raising it buys nothing: the window self-throttles to
        // the serve rate (as heads resolve they classify SATISFIED and drop out), so a bigger
        // window only inflates duplicate-skip traffic. WantSetBudgetInvariantTest pins it
        // above the worst-case in-flight set with frontier headroom and inside one wire batch.
        //
        // Two independent pressure factors, composed by MIN (not multiplied — both gauge the
        // same downstream pipe, so multiplying would double-count): LSS's own decode queue,
        // and the consumer-reported ingest backlog (issue #71 — Voxy's unbounded ingest queue
        // is invisible to the decode-queue signal; <=0 means no signal and leaves the budget
        // untouched). Linear taper against each signal's halt threshold: the budget shrinks
        // until arrivals match the consumer's real drain rate — a proportional controller
        // whose equilibrium IS rate-matching (docs/planning/ingest-backpressure-design.md).
        int budget = LSSConstants.WANT_SET_BUDGET;
        // Column-rate cap, burst half (client-column-rate-cap-design.md; the burst/
        // sustained SEAM SPLIT is adaptive-transfer-rate-plan.md review M2): at most
        // burstCap columns outstanding, so at most ~burstCap can arrive inside one
        // declaration interval. MIN-composes with the pressure taper below — the scale
        // applies to the already-clamped base, which is <= both, the same composition
        // rule as the taper's own two factors. The sustained-rate half lives in
        // fastRescanDue's spacing gate (columnRateCap).
        int burstCap = this.columnBurstCap.getAsInt();
        if (burstCap > 0) {
            budget = Math.min(budget, burstCap);
        }
        float scale = 1f;
        if (columnQueueSize > 0) {
            scale = Math.min(scale, 1f - (float) columnQueueSize / columnQueueHaltThreshold);
        }
        if (ingestBacklogSections > 0) {
            scale = Math.min(scale, 1f - (float) ingestBacklogSections / ingestBacklogHaltThreshold);
        }
        if (scale < 1f) {
            budget = Math.max(1, Math.round(budget * Math.max(0f, scale)));
        }
        // The vanilla-load budget scale is GONE (2026-07-17, user call): it was client-side
        // triage of SERVER resources — v17 moved all of that server-side (BACKGROUND/LOW
        // read+gen priority, the adaptive throttle, headroom gates), and its only observable
        // effect was silently stopping LOD during fast travel, the same class of starvation
        // as the removed movement cadence debounce. missingVanillaChunks is still counted
        // for /lss diag and the trace — as a diagnostic, not a lever.
        // The want-set must fit one wire batch: replace semantics tear across frames.
        budget = Math.min(budget, Math.min(LSSConstants.MAX_BATCH_CHUNK_REQUESTS, posOut.length));

        // Latch provenance for the governor's window-limited bypass
        // (ramp-window-limited-credit-plan.md §3.2, 1-Fable fold MAJOR-1): true iff
        // the burst-cap clamp SET the final budget — the cap actually clamped below
        // the constant AND neither the pressure taper nor the wire-batch min reduced
        // it further. A taper-reduced budget (the CPU-weak client between 25% and
        // halt) must never read as governor-window-limited.
        // MARGINAL-pressure tolerance (dynamics review MAJOR-1): exact equality
        // disarmed the latch at ~9 queued columns when the cap sits near the rig's
        // ~350 (round(347×0.9985) = 346 != 347) — ordinary Voxy ingest counts would
        // have killed the fix at exactly the park point it targets. The cap counts
        // as the binding clamp while the tapered budget stays within 25% of it
        // (budget×4 >= cap×3): mild pressure tolerated, deep pressure (scale <
        // ~0.75) attributes to the taper and refuses — preserving MAJOR-1(c)'s
        // CPU-weak exclusion (the 500-of-1000 taper test still reads false).
        // Accepted corner: at burstCap == 1 the max(1,·) floor keeps budget == cap
        // under any pressure — bounded by the self-arrest geometry (a wrongly
        // latched doubling doubles the budget past the pressure point, and credits
        // still require desired > ENGAGE_BELOW).
        this.lastBudgetCapClamped = burstCap > 0
                && burstCap < LSSConstants.WANT_SET_BUDGET
                && budget * 4 >= burstCap * 3;

        if (budget <= 0) return -1;

        // Counted after the budget guard: a non-walk must not read as a fast scan (the
        // guard is unreachable in production — the taper floors at 1 and the buffers are
        // batch-cap length — but a zero-length test buffer reaches it).
        this.lastScanWasFast = fast;
        if (fast) this.fastScans++;

        // The movement window closes on FIRES, not declares: this walk re-derives the
        // prefix for the CURRENT center, so predictedWalkCost may trust the retained
        // state again. A converged client's 0-count walked fire clears it too (1 Hz
        // fallback fires walk unconditionally), so the flag cannot stick.
        this.recenteredSinceLastFire = false;

        return scan(playerCx, playerCz, viewDistance, columns, posOut, tsOut, budget);
    }

    /**
     * The adaptive-cadence fast trigger (docs/planning/adaptive-scan-cadence-design.md):
     * between 20-tick fallback fires, fire early — at most every
     * {@link #FAST_RESCAN_MIN_INTERVAL_TICKS} — once the last declared batch is ≥95%
     * answered and the downstream pipeline is at most mildly loaded. Conditions cheap-first;
     * the supplier read comes last so non-firing ticks stay O(1).
     *
     * <p>The v16 gate is non-optional, not merely polite: besides the legacy server's real
     * rate limiter, Tier B's onColumnNotGenerated removes a bounced position from the
     * awaiting set before its transient-heal early return, so every legacy gen-slot bounce
     * drops outstanding — a v16 session would fast-fire on exactly that churn loop and
     * hammer the old server's gen slots at 4 Hz. ClientSessionGate admits only the current
     * and the v16 protocol versions, so {@code != 16} is exact today and stays conservative
     * if an intermediate legacy dialect ever lands.
     *
     * <p>The walk-cost gate has two halves, because the walk grows in two tempos. The
     * {@link #predictedWalkCost()} term sees the SYNCHRONOUS invalidations — movement
     * ({@link #recenter}) and dirty re-opens ({@link #reopenRing}) adjust the retained
     * state (or, kill-switch off, zero {@code confirmedRing}) before the next tick's
     * predicate, so the prediction reflects the walk those force. The
     * {@code hasActionableRetries} term sees the IN-WALK one: an actionable retry mark
     * reopens its ring inside {@code scan()} (after this predicate already ran), so no
     * prediction-derived value can gate it — the boolean rung stays verbatim under
     * retention (plan v1.1: the option-a rung), refusing fast fires while any actionable
     * mark is pending. Only walks that are actually EXPENSIVE stay at the 1 Hz fallback,
     * rather than every reset regardless of cost.
     *
     * <p>The predecessor of the cost term was {@code confirmedRing > 0}, which conflated
     * "the prefix was invalidated" with "the walk is expensive". Those coincide for a
     * stationary client and diverge completely for a moving one, where the prefix is
     * invalidated ~3×/s and the resulting walk still costs ~22,800 iterations (~0.9 ms,
     * fps flat at 60 in the measured trace). See {@link #FAST_RESCAN_MAX_WALK_COST}. This
     * reverses adaptive-scan-cadence-design.md §5.5 — a review-round decision taken on an
     * ANALYTIC cost estimate; the trace measured it.
     *
     * <p>Caller contract: the halt thresholds should be positive (production passes the
     * constants). A zero threshold cannot throw — the divisions are by the constant
     * {@link #FAST_RESCAN_PRESSURE_DIVISOR}, not by the threshold — it simply fails closed
     * on all three pipes, which is the safe direction. (The javadoc previously claimed a
     * divide-by-zero here; the only threshold-denominated division is the taper in
     * {@code maybeScan}, and that yields {@code -Infinity} → a budget of 1, also no throw.)
     */
    private boolean fastRescanDue(int ticksSinceFire, int playerCx, int playerCz, int viewDistance,
                                  ColumnStateMap columns,
                                  int columnQueueSize, int columnQueueHaltThreshold,
                                  long columnQueueBytes, long columnQueueByteHaltThreshold,
                                  int ingestBacklogSections, int ingestBacklogHaltThreshold) {
        if (ticksSinceFire < FAST_RESCAN_MIN_INTERVAL_TICKS) return false;
        if (!this.adaptiveCadenceEnabled.getAsBoolean()) return false;
        if (this.lastSentCount <= 0) return false; // disarmed: converged, send-failed, or never declared
        if (this.outstandingSupplier == null) return false;
        if (this.sessionConfig == null
                || this.sessionConfig.protocolVersion() == LSSConstants.V16_COMPAT_PROTOCOL_VERSION) {
            return false;
        }
        if (predictedWalkCost() > FAST_RESCAN_MAX_WALK_COST) return false;
        // The F1 shrink reset is an IN-WALK prefix invalidation like hasActionableRetries
        // below: scan() zeroes the prefix AFTER this predicate evaluated predictedWalkCost
        // off the still-high confirmedRing, so without this rung the shrink tick could ride
        // a fast fire straight into the full from-ring-0 walk the cost gate exists to
        // refuse (three-lens review, correctness MINOR — dynamic-view-distance servers
        // shrink repeatedly under load).
        if (this.lastExclusionRadius >= 0 && viewDistance < this.lastExclusionRadius) return false;
        if (columnQueueSize >= columnQueueHaltThreshold / FAST_RESCAN_PRESSURE_DIVISOR) return false;
        if (columnQueueBytes >= columnQueueByteHaltThreshold / FAST_RESCAN_PRESSURE_DIVISOR) return false;
        if (ingestBacklogSections >= ingestBacklogHaltThreshold / FAST_RESCAN_PRESSURE_DIVISOR) return false;
        if (columns.hasActionableRetries(playerCx, playerCz, viewDistance)) return false;
        if (this.outstandingSupplier.getAsInt()
                > this.lastSentCount / FAST_RESCAN_OUTSTANDING_DIVISOR) {
            return false;
        }
        // Manual column-rate cap, sustained half: the last batch of N positions pre-pays a
        // 20*N/R-tick interval before the next FAST fire (the 5-tick floor above still binds
        // for small tails, so the converging-tail sparkle survives; a full 800 batch at
        // R=3200 spaces to exactly the floor — today's behavior IS the R=3200 point). LAST
        // in the ladder deliberately: rateGated then counts exactly the ticks where the cap
        // was the binding refusal, and the supplier read costs only near-fire ticks. Long
        // math is belt-and-braces (max real product ~2M fits int comfortably).
        int cap = this.columnRateCap.getAsInt();
        if (cap > 0 && (long) ticksSinceFire * cap
                < (long) LSSConstants.TICKS_PER_SECOND * this.lastSentCount) {
            this.rateGated++;
            return false;
        }
        return true;
    }

    /**
     * Ring positions the NEXT walk will examine, from the two fields that already describe
     * it. It starts at {@link #confirmedRing} and runs to {@code s}, which is
     * {@link #scanRing} only for a budget-TRUNCATED walk; otherwise the walk iterates every
     * ring out to {@link #getEffectiveLodDistance} (see the comment in the body — predicting
     * off {@code scanRing} unconditionally under-reports by three orders of magnitude on a
     * warm disc). Ring {@code r} holds {@code 8r} positions, so the span costs
     * {@code Σ_{r=c}^{s} 8r = 4·(s(s+1) − c(c−1))} — note {@code c(c−1)}, not {@code c(c+1)}:
     * the loop starts AT {@code confirmedRing}. Getting that term wrong once already cost a
     * mis-sized budget constant.
     *
     * <p><b>Coverage limit — deliberate, and load-bearing for what the release notes may
     * claim.</b> In the MOVEMENT WINDOW ({@code recenteredSinceLastFire} — a crossing with no
     * walk since) this deliberately prices the bare from-zero walk even though prefix
     * retention (2026-08-18, scanner-reopened-rings-plan.md) keeps the prefix standing: the
     * retained state makes the walk CHEAP, but admitting fast fires on it would lift the
     * flight regime toward the stationary 2-3 Hz — the 50-75 MB/s consequence the elytra
     * investigation argued against. Retention decouples the WALK COST from recenter without
     * touching this cadence policy. So under sustained movement {@code c} is 0
     * and this reduces to {@code 4·s(s+1)}. Against
     * {@link #FAST_RESCAN_MAX_WALK_COST} = 65536 that admits {@code s ≤ 127}
     * (4·127·128 = 65024) and refuses {@code s = 128} (4·128·129 = 66048). So on the shipped
     * {@code lodDistanceChunks} = 256 the fast cadence covers rings 0–127 — 65024 of the
     * disc's 263168 positions, about a quarter — and a MOVING client falls back to 1 Hz once
     * the frontier passes ring 128. Stationary clients are unaffected ({@code confirmedRing}
     * survives, so the span stays narrow at any depth). This is a partial fix to the elytra
     * chunk wall, not a complete one, and it is the conservative direction the investigation
     * argued for: docs/planning/elytra-chunk-wall-investigation-2026-08-01.md warns that
     * lifting the flight regime toward the stationary 2–3 Hz would put it at 50–75 MB/s and
     * walk back toward the wall. Raising the constant only moves the cliff; extending the
     * coverage means decoupling the prefix from {@code recenter}, which is a separate design
     * change with that throughput consequence attached.
     *
     * <p>Deliberately a PREDICTION, not a measurement of the last walk. The two differ
     * exactly where this gate matters: after {@link #recenter} the next walk restarts at
     * ring 0 while the last one started at the frontier, so a remembered cost would admit
     * one full-price walk after every prefix collapse — and would split two
     * identically-shaped events, letting movement resets run fast while
     * {@code hasActionableRetries} resets (also "next walk starts at ring 0") stay at 1 Hz.
     *
     * <p>Costs nothing in the walk itself: no counter in the hot loop. The long math and
     * the saturation are belt-and-braces — {@code scanRing} is clamped to the LOD distance,
     * so the worst real product (~16.8M at the 2048 ceiling) fits an int comfortably.
     */
    int predictedWalkCost() {
        if (this.sessionConfig == null) return Integer.MAX_VALUE; // fail closed
        // WHERE the walk stops is not scanRing. scan()'s ONLY early exit is the budget
        // `break outer`; without it the loop runs all the way to lodDistance, and scanRing
        // is merely the outermost ring that QUEUED something. Those coincide only for a
        // truncated walk. On a warm disc — the shipped server's own regime — a moving
        // client finds work solely in the trailing view-edge crescents near ring
        // ~viewDistance, so scanRing stays tiny while the walk still iterates every
        // satisfied ring out to lodDistance. Predicting off scanRing there under-reports by
        // three orders of magnitude and admits exactly the walk this gate exists to refuse.
        // A walk that budget-broke INSIDE the reopened interval left scanRing at the low
        // reopened ring, blind to the frontier interval the next walk also iterates —
        // predict the full span instead (fail closed; plan v1.1 MAJOR-2). The
        // scanRing >= confirmedRing guard closes the second flavor (3-Opus review
        // MAJOR, 2026-08-18): a break landing on the frontier's FIRST ring right after a
        // reopened ring — the budget filled at the reopened ring's LAST index, so the
        // break's ring is not below the prefix and the flag does not fire — leaves
        // scanRing at the reopened ring while the whole frontier interval is unpriced
        // (measured 1038x under-price admitting an 83k-probe walk at the fast floor).
        int s = (this.lastWalkTruncated && !this.truncatedBelowPrefix
                && this.scanRing >= this.confirmedRing)
                ? this.scanRing : getEffectiveLodDistance();
        int c;
        if (this.recenteredSinceLastFire) {
            // The movement window (a recenter with no walk since): the bare from-zero
            // formula — the pre-retention movement semantics, verbatim. Prefix retention
            // must not LOWER the movement-window prediction: that would admit fast fires
            // sustained flight never had, walking back toward the elytra chunk wall's
            // 50-75 MB/s regime (plan v1.1 MAJOR-3; the coverage-limit paragraph above
            // still holds for exactly this window).
            c = 0;
        } else {
            c = this.confirmedRing;
            // A reopened ring below the prefix is where the next walk actually STARTS.
            // Bounding with the ring-sum from that floor over-counts the skipped
            // in-between rings — conservative, the safe direction for an admission gate.
            int lowBit = lowestReopenedBit();
            if (lowBit >= 0 && lowBit < c) c = lowBit;
        }
        if (s < c) return 0;
        // Σ 8r for r in [c, s] — the loop starts AT confirmedRing, so the lower term is
        // c(c-1), not c(c+1).
        long cost = 4L * ((long) s * (s + 1) - (long) c * (c - 1));
        return cost <= 0 ? 0 : (int) Math.min(cost, Integer.MAX_VALUE);
    }

    /**
     * Arm/disarm the fast cadence with the count the manager just declared (awaiting set
     * replaced + send attempted). See {@link #lastSentCount} for the contract; called with
     * 0 by the converged walk, the send-failure catch, and {@code disconnect()}.
     */
    void noteDeclared(int count) {
        this.lastSentCount = count;
    }

    /** Production: {@code tracker::size}. Absent (null) in bare rigs ⇒ fast path off. */
    void setOutstandingSupplier(IntSupplier supplier) {
        this.outstandingSupplier = supplier;
    }

    /**
     * Scans expanding Chebyshev rings for positions that need requesting.
     * Skips fully-confirmed rings (all positions satisfied) without spending budget,
     * and continues across multiple rings until budget is exhausted.
     */
    private int scan(int playerCx, int playerCz, int viewDistance,
                     ColumnStateMap columns,
                     long[] posOut, long[] tsOut, int budget) {
        int exclusionRadius = viewDistance;
        int lodDistance = getEffectiveLodDistance();

        int count = 0;

        int[] chunkCoords = new int[2];
        int localScanRing = -1;
        int queued = 0;
        boolean truncated = false;
        boolean truncatedBelow = false;

        // A SHRUNK effective LOD distance resets the prefix once (the lodDistance twin of
        // the F1 exclusion rung below — 3-Opus review MAJOR, 2026-08-18): a prefix parked
        // ABOVE the new distance would make nextWalkRing walk nothing forever, while a
        // movement prune at the shrunk radius deletes the outer annulus's state and the
        // set-time lod clamp drops its dirty reopens — a permanently blank annulus for a
        // stationary player once the distance grows back (retention deleted the
        // incidental crossing-collapse that used to heal this in one second). The rung
        // also makes any above-lod bit structurally impossible, which is what lets
        // reopenRing's set-time clamp be the ONLY above-lod guard.
        if (this.lastLodDistance >= 0 && lodDistance < this.lastLodDistance) {
            this.confirmedRing = 0;
            clearAllReopened();
        }
        this.lastLodDistance = lodDistance;

        // A SHRUNK exclusion radius (render distance lowered) resets the prefix once: the
        // rings between the new and old radius confirmed while vanilla rendered them and
        // would otherwise never be walked again for a stationary player (2026-08-05 review
        // F1). Cheap — fires once per change, and the shrink walk has real work to declare.
        // The full from-0 walk covers every reopened ring, so the bitset clears with it.
        if (this.lastExclusionRadius >= 0 && exclusionRadius < this.lastExclusionRadius) {
            this.confirmedRing = 0;
            clearAllReopened();
        }
        this.lastExclusionRadius = exclusionRadius;

        // Kill-switch A/B hygiene: bits set while retention was ON must not survive a
        // mid-session flip to OFF — the off arm is the field CONTROL arm, so it must walk
        // (and price the cadence gate) exactly like legacy, not "legacy plus leftovers".
        // A standing bit represents BELOW-PREFIX work (a dirty/retry ring not yet
        // re-walked); dropping it without collapsing the prefix would strand that work,
        // so the flush converts it into the legacy full re-walk.
        if (!this.prefixRetentionEnabled.getAsBoolean()) {
            if (lowestReopenedBit() >= 0) this.confirmedRing = 0;
            clearAllReopened();
        }

        // Only an ACTIONABLE retry mark (outside the vanilla-view exclusion) can force
        // below-prefix re-walking. A mark whose position slipped INSIDE the exclusion
        // after it was set is unconsumable — an excluded position is never declared, so no
        // terminal answer ever consumes it — and letting it invalidate the prefix forced a
        // full-distance re-walk EVERY scan for as long as the player lingered. With
        // retention on, each actionable mark reopens exactly ITS ring (the collect visitor
        // applies the same actionability ladder as hasActionableRetries — the two must not
        // drift; the lod bound is hoisted so the collect costs no Voxy-cache invocations);
        // with the kill switch off, the legacy prefix-to-0 reset applies.
        if (this.prefixRetentionEnabled.getAsBoolean()) {
            columns.collectActionableRetryRings(playerCx, playerCz, exclusionRadius,
                    ring -> reopenRing(ring, lodDistance));
        } else if (columns.hasActionableRetries(playerCx, playerCz, exclusionRadius)) {
            this.confirmedRing = 0;
        }

        int localConfirmedRing = this.confirmedRing;
        boolean quad = this.quadtreeScanEnabled.getAsBoolean();

        // Two-interval walk (retention plan §4): the reopened rings below the prefix
        // (ascending), then the frontier interval [confirmedRing, lodDistance]. All
        // reopened bits sit below the prefix, so the combined order is still ascending —
        // the want-set stays closest-first.
        int r = -1;
        outer:
        while ((r = nextWalkRing(r, localConfirmedRing, lodDistance)) >= 0) {
            boolean reopenedBelowPrefix = r < localConfirmedRing;
            int ringSize = 8 * r;
            // The budget break, hoisted from the loop's first iteration (identical
            // outcome — i=0 always ran it): it must precede the fast path so a
            // budget-exhausted walk terminates at the next nonzero ring exactly as
            // before, fast path or not.
            if (ringSize > 0 && count >= budget) {
                truncated = true;
                // A budget break INSIDE the reopened interval leaves scanRing blind to
                // the frontier interval the next walk also iterates — predictedWalkCost
                // must fail closed off the full span (plan v1.1 MAJOR-2).
                truncatedBelow = reopenedBelowPrefix;
                break;
            }
            // Section-store fast path (quadtree-client-state-plan.md): when every leaf
            // this ring crosses has a clear needs-mask, every ring position classifies
            // SATISFIED — apply the ring's confirmation bookkeeping without touching its
            // positions. Any needs bit in any crossed leaf (even off-ring) falls through
            // to the per-position walk below, so emission order, budget accounting, and
            // confirmation stay bit-identical to the legacy walk (the differential pin).
            if (quad && ringSize > 0 && columns.ringNeedsFree(playerCx, playerCz, r)) {
                this.quadRingSkips++;
                if (reopenedBelowPrefix) {
                    reopenedClearBit(r);
                } else if (localConfirmedRing == r) {
                    localConfirmedRing = r + 1;
                }
                continue;
            }
            boolean ringFullySatisfied = true;
            for (int i = 0; i < ringSize; i++) {
                if (count >= budget) {
                    ringFullySatisfied = false;
                    truncated = true;
                    // (See the hoisted break above — this inner form handles the
                    // mid-ring case.)
                    truncatedBelow = reopenedBelowPrefix;
                    break outer;
                }

                ringIndexToCoord(r, i, playerCx, playerCz, chunkCoords);
                int cx = chunkCoords[0];
                int cz = chunkCoords[1];

                long packed = PositionUtil.packPosition(cx, cz);

                // Exclude chunks vanilla RENDERS, replicating its own view-distance test
                // (ChunkTrackingView.isInViewDistance: a 1-chunk-buffered Euclidean radius). The
                // render SQUARE's corners fall OUTSIDE this rounded view — e.g. at viewDistance 12
                // the corner (12,12) is buffered-distance 11^2+11^2=242 vs 12^2=144 — so vanilla
                // never renders them, and the old Chebyshev exclusion (max(|dx|,|dz|) <= vd) left
                // them blank until the player moved. They now fall through to LOD. Like an in-flight
                // skip, an excluded (in-view) chunk does NOT break ring confirmation. Loop-safe:
                // DirtyContentFilter suppresses metadata-only (inhabitedTime) re-saves of a served
                // corner, so re-serving one cannot revive the old re-request loop.
                if (isVanillaRendered(cx, cz, playerCx, playerCz, exclusionRadius)) continue;

                // No in-flight suppression: re-declaration is load-bearing. The server may
                // silently supersede any not-yet-admitted ask; only the 1 Hz re-declare heals
                // that. An awaited position is unsatisfied, so it blocks ring confirmation
                // until its data actually arrives — confirmedRing lags the frontier by the
                // in-flight window, and satisfied positions skip free, so the walk stays cheap.
                long ts = columns.classify(packed);
                if (ts == ColumnStateMap.SATISFIED) continue;

                ringFullySatisfied = false;
                posOut[count] = packed;
                tsOut[count] = ts;
                count++;
                queued++;
                if (localScanRing < r) localScanRing = r;
            }

            if (ringFullySatisfied) {
                if (reopenedBelowPrefix) {
                    // The reopened ring is clean again; an unsatisfied or truncated ring
                    // keeps its bit and re-walks next scan.
                    reopenedClearBit(r);
                } else if (localConfirmedRing == r) {
                    // Contiguous prefix only: confirming a satisfied OUTER ring while an
                    // inner ring still has unsatisfied positions (an uncovered corner hole)
                    // would start every later scan past the inner ring — a permanent LOD
                    // hole for a stationary player.
                    localConfirmedRing = r + 1;
                }
            }
        }

        this.confirmedRing = localConfirmedRing;
        this.scanRing = localScanRing >= 0 ? localScanRing : localConfirmedRing;
        this.lastWalkTruncated = truncated;
        this.truncatedBelowPrefix = truncatedBelow;
        this.lastBudget = budget;
        this.lastQueued = queued;

        return count;
    }

    /**
     * The walk-order iterator: the next ring after {@code prev} — the lowest reopened bit
     * below {@code confirmedRing}, then the frontier interval up to {@code lodDistance},
     * -1 when done. {@code confirmedRing} may ADVANCE between calls (frontier rings
     * confirming); by then {@code prev} is already at/past it, so the reopened interval is
     * never re-entered.
     */
    private int nextWalkRing(int prev, int confirmedRing, int lodDistance) {
        if (prev + 1 < confirmedRing) {
            int bit = nextReopenedBit(prev + 1);
            if (bit >= 0 && bit < confirmedRing) return bit;
            // Reopened interval exhausted — jump to the frontier.
            return confirmedRing <= lodDistance ? confirmedRing : -1;
        }
        int next = Math.max(prev + 1, confirmedRing);
        return next <= lodDistance ? next : -1;
    }

    /**
     * Vanilla's own view-distance test (ChunkTrackingView.isInViewDistance): a 1-chunk-
     * buffered Euclidean radius. Shared by the ring walk's exclusion skip and
     * {@link ColumnStateMap#hasActionableRetries} so the two tests cannot drift.
     */
    static boolean isVanillaRendered(int cx, int cz, int playerCx, int playerCz, int exclusionRadius) {
        int adx = Math.max(0, Math.abs(cx - playerCx) - 1);
        int adz = Math.max(0, Math.abs(cz - playerCz) - 1);
        return (long) adx * adx + (long) adz * adz < (long) exclusionRadius * exclusionRadius;
    }

    /**
     * Maps linear index {@code i} (0 to 8r-1) to the chunk coordinates of the
     * i-th border chunk in Chebyshev ring {@code r}. Four edges, 2r points each,
     * clockwise from top-left.
     */
    static void ringIndexToCoord(int r, int i, int centerX, int centerZ, int[] out) {
        int edge = i / (2 * r);
        int pos = i % (2 * r);
        switch (edge) {
            case 0 -> { out[0] = centerX - r + pos; out[1] = centerZ - r; }
            case 1 -> { out[0] = centerX + r;       out[1] = centerZ - r + pos; }
            case 2 -> { out[0] = centerX + r - pos; out[1] = centerZ + r; }
            case 3 -> { out[0] = centerX - r;       out[1] = centerZ + r - pos; }
        }
    }

    void reset() {
        this.confirmedRing = 0;
        this.scanRing = 0;
        this.lastExclusionRadius = -1; // next session re-anchors; no spurious shrink reset
        this.lastLodDistance = -1;     // ...same for the lod-shrink rung
        this.lastWalkTruncated = false;
        this.lastBudgetCapClamped = false; // fresh session predicts the full disc — fail closed
        this.scanTickCounter = LSSConstants.TICKS_PER_SECOND - 1;
        this.missingVanillaChunks = Integer.MAX_VALUE;
        this.cachedVoxyDistance = -1;
        this.voxyDistanceStaleness = 0;
        this.lastSentCount = 0; // disarm the fast cadence with the rest of the session state
        this.lastScanWasFast = false;
        this.fastScans = 0;
        this.rateGated = 0;
        this.quadRingSkips = 0;
        this.valveTrips = 0;
        clearAllReopened();
        this.recenteredSinceLastFire = false;
        this.truncatedBelowPrefix = false;
    }

    void resetScanCounter() {
        this.confirmedRing = 0;
        this.scanTickCounter = 0;
        // Disarm too: this is the deliberate post-dimension-change 20-tick wait, and the
        // fresh dimension's empty awaiting set would otherwise satisfy the fast trigger
        // trivially against a stale armed count.
        this.lastSentCount = 0;
        // The fresh dimension shares nothing with the old one's ring geometry.
        clearAllReopened();
        this.recenteredSinceLastFire = false;
        this.truncatedBelowPrefix = false;
        this.lastLodDistance = -1;
    }

    /** Movement re-center: adjust the scan state for a Chebyshev crossing of {@code d}
     *  chunks WITHOUT touching the cadence. The pre-v17 movement path used
     *  {@link #resetScanCounter} (a debounce), which starved scanning — and with it
     *  re-declaration, the want-set's only healer — for as long as the player crossed a
     *  chunk boundary more often than every 20 ticks: sustained creative flight stopped
     *  LOD generation entirely. Under latest-wins replace semantics a moving client
     *  declaring on schedule is the DESIGNED behavior; yielding to vanilla's own chunk
     *  loading during fast travel is SERVER-SIDE read/generation priority's job, not the
     *  cadence's.
     *
     *  <p>The prefix handling is the retention plan's core
     *  (docs/planning/scanner-reopened-rings-plan.md §5): the old behavior zeroed
     *  {@code confirmedRing} on EVERY crossing, forcing a full-disc re-walk (~1M classify
     *  probes at distance 512 — the measured 30-90 ms render-thread hitch, spark profile
     *  6HZTTXT5pn). A ring confirmed around the old center is instead conservatively
     *  still-confirmed within {@code [r-d, r+d]} around the new one, so the prefix only
     *  DECREMENTS by {@code d} — except for the view-exclusion crescent: positions that
     *  confirmed while vanilla rendered them and just exited the (Euclidean, buffered)
     *  view circle behind the player. Their Chebyshev rings around the NEW center span
     *  {@code [⌊(R−d)/√2⌋−1, R+d]} (the {@code /√2} low edge is the diagonal exit — a
     *  Chebyshev band down only to {@code R−d} misses it and leaves PERMANENT holes;
     *  plan v1.1 MAJOR-1), reopened as a band. Existing reopened bits shift by the union
     *  {@code [r-d, r+d]}. A crossing of {@link #RECENTER_FULL_RESET_DELTA} or more
     *  (teleport-scale — the prune hysteresis fires at the same magnitude) falls back to
     *  the full reset, as does the kill switch. */
    void recenter(int d) {
        // Both modes: the movement window opens for predictedWalkCost — the elytra-wall
        // calibration must see the bare from-zero prediction until the next actual walk.
        this.recenteredSinceLastFire = true;
        if (!this.prefixRetentionEnabled.getAsBoolean()) {
            this.confirmedRing = 0;
            clearAllReopened();
            return;
        }
        if (d <= 0) return;
        if (d >= RECENTER_FULL_RESET_DELTA || this.lastExclusionRadius < 0) {
            // Teleport-scale crossing, or no walk yet (nothing confirmed to retain).
            this.confirmedRing = 0;
            clearAllReopened();
            return;
        }
        int oldPrefix = this.confirmedRing;
        shiftReopenedBits(d);
        this.confirmedRing = Math.max(0, oldPrefix - d);
        // Invariant: bits live strictly BELOW the prefix (the shift can push one to or
        // past it; the frontier walk covers those rings anyway, and a stranded bit would
        // pollute the overflow valve).
        for (int r = nextReopenedBit(this.confirmedRing); r >= 0; r = nextReopenedBit(r + 1)) {
            reopenedClearBit(r);
        }
        // The view-exclusion crescent band (Euclidean low edge — see javadoc). The lod
        // bound is hoisted out of the loop (one Voxy-cache invocation, not ~14).
        int viewR = this.lastExclusionRadius;
        int low = Math.max(0, (int) Math.floor((viewR - d) / Math.sqrt(2.0)) - 1);
        int high = viewR + d;
        int lod = this.sessionConfig == null
                ? LSSConstants.MAX_LOD_DISTANCE : getEffectiveLodDistance();
        for (int r = low; r <= high; r++) {
            reopenRing(r, lod); // ≥prefix skip + overflow valve inside
        }
    }


    int getEffectiveLodDistance() {
        int serverDistance = this.sessionConfig.lodDistanceChunks();
        int clientDistance = LSSClientConfig.CONFIG.lodDistanceChunks;
        int effective;
        if (clientDistance > 0) {
            effective = Math.min(clientDistance, serverDistance);
        } else {
            effective = serverDistance;
        }
        int voxyDist = getCachedVoxyDistance();
        if (voxyDist > 0 && voxyDist < effective) {
            effective = voxyDist;
        }
        // Defensive clamp: a legitimate server clamps its advertised distance to
        // [MIN,MAX]_LOD_DISTANCE, but the SessionConfig decoder does not, so a hostile or
        // broken server could send a huge value that overflows getPruneDistance() negative
        // (effective + LOD_DISTANCE_BUFFER) and makes isOutOfRange refuse every column,
        // busy-looping the request loop. Clamp to the same ceiling the server enforces.
        return Math.min(effective, LSSConstants.MAX_LOD_DISTANCE);
    }

    private int getCachedVoxyDistance() {
        if (++this.voxyDistanceStaleness >= LSSConstants.TICKS_PER_SECOND) {
            this.voxyDistanceStaleness = 0;
            var voxyDistance = ModCompat.getVoxyViewDistanceChunks();
            this.cachedVoxyDistance = voxyDistance.isPresent() ? voxyDistance.getAsInt() : -1;
        }
        return this.cachedVoxyDistance;
    }

    int getPruneDistance() {
        return getEffectiveLodDistance() + LSSConstants.LOD_DISTANCE_BUFFER;
    }

    // --- Getters ---

    int getConfirmedRing() { return this.confirmedRing; }
    /** Reopened-below-prefix ring count — the diag/exporter {@code reopened=} field. */
    int getReopenedRingCount() { return reopenedRingCount(); }
    // Test accessors for the retention flags (the reset-path pins assert they clear).
    boolean recenteredSinceLastFireForTest() { return this.recenteredSinceLastFire; }
    boolean truncatedBelowPrefixForTest() { return this.truncatedBelowPrefix; }
    /** predictedWalkCost's truncation input — the walk differential asserts arm parity
     *  on it directly (review round 2 MINOR: only count trajectories implied it). */
    boolean wasLastWalkTruncated() { return this.lastWalkTruncated; }
    int getScanRing() { return this.scanRing; }
    int getMissingVanillaChunks() { return this.missingVanillaChunks; }
    int getLastBudget() { return this.lastBudget; }

    /** Was the burst cap the binding, taper-free clamp on the last walk's budget?
     *  (The provenance half — see the maybeScan recording site.) */
    boolean wasLastBudgetCapClamped() { return this.lastBudgetCapClamped; }
    int getLastQueued() { return this.lastQueued; }
    boolean wasLastScanFast() { return this.lastScanWasFast; }
    long getFastScans() { return this.fastScans; }
    long getRateGated() { return this.rateGated; }
    /** Rings the section-store fast path confirmed without a position walk (session). */
    long getQuadRingSkips() { return this.quadRingSkips; }
    /** REOPENED_RING_VALVE overflow firings (session) — the B1 field measurement. */
    long getValveTrips() { return this.valveTrips; }
}
