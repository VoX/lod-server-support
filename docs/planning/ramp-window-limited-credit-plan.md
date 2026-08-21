# Ramp window-limited credit — the Row-4 refinement (v1.1, 2026-08-21 — §8 is the 1-Fable fold, normative)

Fixes the never-ending slow-start RAMP on healthy connections whose server serves
slower than the ramp ceiling. Live-diagnosed 2026-08-21 on the Paper test rig
(v0.12.0 client, default server config): `governed=ramp@8192 KB/s` frozen for the
whole session, budget pinned at 353, ~25-35% throughput left on the table.

## 1. The defect, mechanically

The client's request loop is a **stop-and-wait window**: the adaptive-cadence fast
trigger refuses to fire until ≤5% of the last batch is unanswered
(`SpiralScanner.fastRescanDue`, the outstanding gate), so the scan cadence is
clocked by the server's delivery time, not by the designed 4 Hz spacing-gate
equilibrium. The server's delivery time for one governed burst window
(`burst = desired/(4·EWMA)` columns) is set by the per-player RAW bandwidth
allocation (default 25 MB/s → 1.25 MB/tick; send pacing floors at exactly that
share), which on the measured rig yields a ~630 ms cycle → **1.6 Hz**.

RAMP's Row 4 (`TransferRateGovernor.stepRamp`, the under-offer hold) requires
`offered × 2 ≥ desired` before any growth rung is evaluated. Offered rate =
`burst × cadence × EWMA` = `desired/4 × cadence`, so Row 4 needs **cadence ≥ 2 Hz**
— unreachable at 1.6 Hz. Worse, the `finally` resets `rampOpenStreak` on every
qualifying-but-uncredited interval, so the 10-interval RAMP→OPEN confirmation
never accumulates. The `answeredAllAsked` growth rung — which fires at the
measured 92% answered/declared — sits behind Row 4 and is unreachable.

The compression ratio compounds it: the 8 MB/s **wire** ceiling assumed 2-3×
compression ("≈ 16-24 MB/s raw ≈ the default per-player cap"); the measured
terrain compresses ~5.5×, so the ceiling's raw-equivalent (~44 MB/s) is ~2× the
default server cap — in wire terms a default-capped server can serve at most
~4.5 MB/s at perfect duty against a `desired/2` threshold of 4.19 MB/s. With any
duty-cycle loss the ramp exit is **structurally unreachable**, not timing-unlucky.

Cost of the stuck state: the RAMP burst cap clamps the want-set budget to
`desired/(4·EWMA)` (≈353 measured) instead of `WANT_SET_BUDGET` (800), costing
~25-35% steady-state throughput; `/lss diag` reads `governed=ramp@…` forever; and
every future session repeats it (the dimension-change ssthresh hint re-enters RAMP).

## 2. Why Row 4 exists (the constraint the fix must keep)

Round-3 review MAJOR: with growth evaluated first, a **converged client's
one-column dirty-edit trickle** (offered a fraction of desired, trivially
answered) satisfied `answeredAllAsked`, and ~17 sparse intervals walked a
never-proven link to capless OPEN — non-qualifying gaps preserve both streaks.
Row 4 makes under-offer the gate growth must pass. Pinned by
`answeredTrickleUnderTheOfferFloorNeverRamps`.

The defect is that Row 4 **conflates two under-offer causes**:
- *demand-limited*: the walk had nothing more to declare (the trickle, the
  converged client) — growth would be vacuous; HOLD is correct;
- *window-limited*: the walk FILLED the clamped budget and was truncated with
  demand left over — the offer shortfall is the stop-and-wait actuator's
  arithmetic, not absent demand; the link/server is carrying the entire window
  continuously.

The discriminator is built from the scanner's `lastWalkTruncated` ("did the
last walk stop early because the budget filled?") — but truncation ALONE is not
the discriminator (1-Fable fold MAJOR-1): exact-fill demand truncates (the
budget check precedes classify, so `truncated ⇔ count == budget` in practice),
tiny early-ramp budgets (burst floors at 1 near INITIAL) let a 2-3-column
trickle truncate, and the #71 pressure taper or the manual knob can be the
binding clamp. The latch therefore requires **provenance**: truncated AND the
governed burst cap was the binding, taper-free clamp (§3.2). The residual
trickle escape through a tiny governed budget is **self-arresting**: each
credit-free doubling doubles the burst budget, so a fixed small demand stops
truncating within 1-3 doublings and parks — and CREDITS require desired >
4 MB/s, i.e. a sustained ≥~170-350-column-per-scan demand with ≥¾ answered for
ten forgiveness-bounded intervals, which no trickle produces. The bound is
pinned by test, not assumed (§5).

## 3. Design

### 3.1 The latch (governor)

New interval-scoped latch on `TransferRateGovernor`, mirroring `noteMovement()`:

- `private boolean windowLimitedSeenThisInterval;`
- `void noteWindowLimited()` sets it (main client thread, like every other input).
- Cleared in `seedInterval(…)`, `hardReset()`, and `adoptFrom(…)` (interval
  accumulator state — the adopt contract already reseeds the interval).
  `setSlowStartEnabled`'s RAMP→OPEN demote deliberately does not clear it (OPEN
  never reads it; RAMP re-entry passes through `hardReset`).

### 3.2 The wiring (manager + scanner)

- `SpiralScanner` gains two package-private accessors: `wasLastWalkTruncated()`
  (the existing field) and `wasLastBudgetCapClamped()` — a NEW per-walk flag
  recorded in `maybeScan`: true iff the burst-cap clamp set the final budget
  (`burstCap > 0 && burstCap < WANT_SET_BUDGET` and the pressure taper did not
  reduce it further, i.e. the final budget still equals the cap-clamped value).
  A taper-reduced budget (MAJOR-1(c): the CPU-weak client at 25-99% of halt)
  reads false and can never latch.
- `LodRequestManager.sendRequests` returns `boolean` (true iff the batch send
  succeeded — the same condition that bumps `declaredColumnsCumulative`).
- `tickScanPhase` latches after a successful non-empty send of a walk truncated
  by the GOVERNED cap:

```java
int governedBurst = governedBurstCap(); // the ONE composition both sites read
int manual = LSSClientConfig.CONFIG.lodColumnsPerSecondLimit;
if (scanned > 0 && sent
        && this.scanner.wasLastScanFast()          // completion-clocked only
        && this.scanner.wasLastWalkTruncated()
        && this.scanner.wasLastBudgetCapClamped()
        && governedBurst > 0 && (manual <= 0 || governedBurst <= manual)) {
    this.governor.noteWindowLimited();
}
```

Two as-built amendments (§9, the implementation panel):
- **The fast conjunct** (dynamics MAJOR-2): only a COMPLETION-CLOCKED (fast-
  fired) walk may latch. The 1 Hz fallback fires unconditionally and re-declares
  a full budget however slow the drain, so a backlogged slow-but-clean link
  would otherwise satisfy `answeredAllAsked` up to ~5.3× its rate; fallback
  fires never latch, so such links park near main's Row-4 point (the residual
  widening is bounded: while cycles stay under ~1 s the loop is fast-clocked
  and can credit to ~4× link — an accepted, ENGAGE-guarded landing; past ~1 s
  the fallback outruns the fast path and Row 4 holds). The rig is 381/383 fast.
- **The provenance tolerance** (dynamics MAJOR-1): the cap-clamped flag is
  `budget × 4 ≥ burstCap × 3`, not exact equality — equality disarmed the latch
  at ~9 queued columns near the ~350-column park point (round(347×0.9985)=346).
  Deep taper (scale < ~0.75) still attributes to the taper and refuses.
- The RAMP diag label gains a receipt: `ramp@8192 KB/s (1419/s, credits=N/10,
  wl)` — confirmation progress + whether the current interval latched, so a
  "still parked" field report is self-diagnosing.

The last conjunct keeps the latch's meaning honest when the manual knob is the
binding half of the min-compose (MAJOR-1(c) second shape): a manually-capped
loop never claims to be governor-window-limited (in effect the min-compose
keeps the manual cap binding either way; this is about meaning, not safety).
A failed send latches nothing (nothing was offered); an untruncated or
taper-clamped walk latches nothing. Tick order keeps
attribution honest: `governor.tick()` (interval evaluation + reseed) runs BEFORE
`tickScanPhase` in the manager, so a scan's latch lands in the interval its
declaration counts toward.

### 3.3 The Row-4 bypass (governor)

In `stepRamp`, two edits, both inert while the latch is false:

```java
// Row 4 — under-offered HOLDs, UNLESS the interval was window-limited: the walk
// filled the clamped budget and was truncated (demand exceeded the window), so
// the offer shortfall is the stop-and-wait actuator's arithmetic — the window
// drains at the serve rate and cadence is completion-clocked below the 2 Hz the
// threshold implies. The trickle (round-3 MAJOR) cannot CREDIT through this
// branch: the latch requires the governed cap as the binding clamp (provenance,
// wiring side), a tiny-budget trickle self-arrests within a few doublings, and
// credits require desired > ENGAGE_BELOW — see the plan's §2/§4.1.
if (offered * 2L < this.desiredBytesPerSec
        && !this.windowLimitedSeenThisInterval) {
    return;
}
```

and, gating the plateau snap (which today is only reachable offer-backed):

```java
// A window-limited under-offered interval whose growth rungs did not fire is
// NOT offer-backed evidence — the ENGAGED path's freeze rule (MAJOR-1): HOLD,
// never snap. Unreachable while the latch is false (the un-latched under-offer
// case returned at Row 4), so the un-latched ladder is bit-identical.
if (offered * 2L < this.desiredBytesPerSec) {
    return;
}
```

The growth rungs between the two are evaluated verbatim. In the bypass branch,
`deliveredAllOffered` is structurally false (it requires `offered ≥ desired/2`)
and `keptUp` is ~unreachable in steady state (measured ≤ offered + one in-flight
window), so **the operative rung is `answeredAllAsked`** — the byte-free
"position window saturated with timely service" rung, which is exactly the
evidence a window-limited loop produces. Crediting, the ceiling clamp, and
`openFromRamp()` are untouched.

### 3.4 Streak forgiveness (1-Fable fold MAJOR-2)

The 2 s interval aliases against the ~630 ms stop-and-wait cycle: a window
containing 4 declarations carries the 4th batch's answers into the NEXT window,
so `answeredAllAsked` fails roughly 1 interval in 6 under metronomic timing —
and the current `finally` resets `rampOpenStreak` on EVERY qualifying
uncredited interval, so the 10-credit exit could never fire on the fix's own
headline rig. The streak reset gains one interval of forgiveness:

- New field `rampUncreditedRun`. In the `finally`: a credited interval zeroes
  it; a qualifying uncredited interval that was WINDOW-LIMITED is forgiven once
  (the run marks 1); any other uncredited qualifying interval — un-latched, or
  the second consecutive miss — resets `rampOpenStreak` exactly as main did
  (non-qualifying intervals remain no-observations, as today).
- SCOPED TO THE LATCH (implementation-panel fold, governor MAJOR): an unscoped
  forgiveness loosened the OPEN confirmation for every ramp session —
  alternating credited/uncredited demand patterns could confirm at ~3/8 average
  answered where main required 10 consecutive; and it silently forgave Row-1/2/3
  holds (vanilla-behind included). Scoping costs the headline fix nothing: in
  the stop-and-wait steady state EVERY interval latches, so the aliased miss is
  always a latched interval.
- Round-3 safety is preserved by demand geometry, not by a delivery claim (the
  same fold corrected §3.4's earlier justification — `credited` measures the
  governor's post-doubling desired, not delivered bytes): credits require
  desired > 4 MB/s, which requires the walk to keep FILLING a ≥~170-350-column
  governed budget with ≥¾ answered — sustained backfill demand no trickle or
  alternating pattern produces, and un-latched misses now reset regardless.
- Expected exit at the measured rig shape: ~12 intervals ≈ 24 s (10 credits
  with ~1-in-6 forgiven misses). A REAL degradation (two consecutive uncredited
  intervals) still resets exactly as before.
- Known adjacent dynamic, unchanged by this plan: at the alias boundary the
  offered×2-vs-desired compare sits within ~1% of the threshold, so EWMA noise
  can occasionally route a 4-declare window into the offer-backed plateau snap
  (growth fails by a hair) — desired snaps toward measured and the ramp
  re-climbs in seconds. Pre-existing containment behavior; the forgiveness
  absorbs the streak cost of such an interval.

### 3.4 What does NOT change

- No new config. The kill switch remains `enableJoinSlowStart` (no RAMP at all).
- ENGAGED/OPEN ladders untouched. Row 1 (engage), Row 2 (vanilla-behind),
  Row 3 (movement hold) untouched and still precede the bypass.
- Latch-false behavior is bit-identical by construction (both edits are
  unreachable or no-ops when the latch is false).
- The server side is untouched — pacing and the bandwidth limiter are behaving
  as designed; they merely set the loop period the client must reason about.

## 4. Safety argument (adversarial)

1. **The round-3 trickle stays excluded, by geometry rather than by "never
   truncates" (1-Fable fold).** A trickle CAN truncate a tiny early-ramp budget
   and latch — but the escape is self-arresting (each doubling doubles the
   budget past the fixed demand within 1-3 intervals) and can never CREDIT
   (credits require desired > 4 MB/s = a sustained ≥~170-350-column answered
   window, which is a backfill by definition). Taper-clamped and
   manual-capped truncations never latch at all (provenance, §3.2). The pin
   test stays green unmodified, and the bound gets its own wiring-level tests.
2. **A slow-but-clean link (no bufferbloat) can now reach OPEN via the bypass.**
   This is contained and is the pre-slow-start posture for every session:
   (a) the actuator is completion-clocked in OPEN too — the 95% outstanding gate
   means OPEN cannot outrun the serve rate; batches grow to 800 but remain
   self-paced; (b) a bufferbloat link accumulates ping excess → RAMP Row 1 or
   OPEN's engage conjunct → ENGAGED (byte-for-byte the pre-phase machinery;
   the vanilla-behind cut is deliberately NOT claimed here — it is evaluated
   only in RAMP/ENGAGED, per the constant's own comment). The asymmetry favors
   the fix: today's failure (permanent ramp park) costs every good-server
   session ~25-35% forever; the new failure lands in OPEN = v0.11 behavior with
   all its containment.
3. **Genuinely slow serves and slow links park via the FAST conjunct (mechanism
   corrected twice — final form is the dynamics fold).** The latch requires a
   completion-clocked (fast) fire, and the fast path only fires while the last
   batch reaches 95% answered before the 1 Hz fallback — i.e. while the cycle
   stays under ~1 s. A serve/link rate slow enough to push the cycle past ~1 s
   runs fallback-clocked, never latches, and Row 4 holds exactly as main
   (park ≈ desired where burst ≈ serve-rate ≈ ~4× link at the boundary, vs
   main's ~2× — the bounded widening §9 records as accepted; bufferbloat links
   additionally engage via Row 1's ping conjunct, and the un-latched double-miss
   reset still guards the confirmation).
4. **Partial answers never credit and never snap.** Window-limited + answered <
   ¾ declared → all rungs fail → HOLD (the new pre-snap guard). A server hiccup
   costs an interval, not the earned desired (mirrors
   `partiallyAnsweredByteFreeIntervalHoldsInsteadOfSnapping`).
5. **The exit is achievable under aliasing (re-derived, 1-Fable fold
   MAJOR-2).** Without forgiveness the 1-in-6 aliased `answeredAllAsked` miss
   recurs faster than the 10-streak and the exit never fires; with one-miss
   forgiveness (§3.4) the expected exit is ~12 intervals ≈ 24 s at the measured
   rig shape, and only two consecutive uncredited intervals (real degradation)
   reset.

## 5. Test plan (all Tier 1)

`TransferRateGovernorTest` additions:
- `windowLimitedStopAndWaitCompletesTheRamp` — the headline repro: desired at
  the ceiling, offered < desired/2 every interval, answered ≥ ¾ declared, latch
  set each interval → 10 intervals → `Phase.OPEN`, capless, and the un-fixed
  shape (same ticks, no latch) stays RAMP (asserted in the same test as the
  control arm).
- `windowLimitedBypassStillRequiresAnswers` — latch set, answered < ¾ declared,
  zero bytes → HOLD, desired unchanged, streak observably reset (a subsequent
  credited run needs a fresh 10).
- `windowLimitedUnderOfferNeverSnaps` — latch set, bytes moved (measured > 0),
  growth fails → desired unchanged (the pre-snap guard; the ENGAGED-freeze
  mirror).
- `windowLimitedLatchIsIntervalScoped` — a latch in interval N does not leak
  into N+1; `hardReset`/`adoptFrom` clear it.
- `windowLimitedOfferBackedPlateauStillSnaps` (1-Fable fold minor-2): a
  LATCHED interval that is offer-backed (offered×2 ≥ desired) with growth
  failed and measured > 0 must still take the plateau snap — pins that the
  bypass is a Row-4 bypass, not a blanket pre-snap return (a wrong
  implementation early-returning on the latch before the snap must red here).
- `windowLimitedSingleAliasedMissIsForgiven` / `twoConsecutiveMissesReset`
  (§3.4): credited×5, one latched uncredited, credited×5 → OPEN; credited×9,
  latched uncredited×2 → streak restarts.
- `unlatchedUncreditedIntervalStillResetsTheStreak` (the scoping pin): an
  UN-latched miss after nine credits resets immediately — the alternating
  pattern can never confirm.
- `trickleThroughTinyBudgetSelfArrestsAndNeverCredits` (MAJOR-1(b) bound):
  latched intervals with small answered demand double desired only while the
  implied budget < demand, park below ENGAGE_BELOW, phase stays RAMP,
  `rampOpenStreak` stays 0.
- Existing pins run unmodified: `answeredTrickleUnderTheOfferFloorNeverRamps`,
  `partiallyAnsweredByteFreeIntervalHoldsInsteadOfSnapping`,
  `underOfferedIntervalHoldsAndPlateauSnapsWithoutRaising`,
  `keptUpIntervalsDoubleToTheCeilingAndTenCreditedIntervalsOpen`.

Manager/scanner wiring:
- `LodRequestManager` test (the governor-wiring suite): a successful send of a
  governed-cap-truncated walk latches the governor; a failed send does not; an
  untruncated walk does not; a MANUAL-capped walk (manual < governed) does not.
  (Seam: the existing injectable `batchSender`.)
- `SpiralScanner`: `wasLastWalkTruncated()` reflects a budget-truncated walk and
  clears on an untruncated one; `wasLastBudgetCapClamped()` is true when the
  burst cap set the final budget, false when the pressure taper reduced it
  below the cap (the MAJOR-1(c) shape) and false when the constant
  `WANT_SET_BUDGET` was the binder.

## 6. Live validation

1. (Model check, pre-fix, optional) `/lsslod set mbPerSecondLimitPerPlayer 50`
   on the test rig → cadence ≈ 2.4 Hz → the UNMODIFIED ramp should complete in
   ~20 s. Confirms the model independently of the fix.
2. (Fix check) Default config, fixed client: join → within ~40 s (7 doublings
   ≈ 14 s + 9 further credits ≈ 18 s + join lead-in and any forgiven alias
   miss) the once-per-session log "LOD slow start complete", `governed=` absent
   from `/lss diag` (while confirming it reads `ramp@… credits=N/10, wl`),
   `Budget: used=800/800` (or pressure-scaled), recv rate up ~25% vs the stuck
   baseline.

## 7. Rollout

Branch `feat/ramp-window-limited-credit`; PR against main but **NOT merged** —
main is the frozen, pre-flighted v0.12.0 release tip. Landing options (user's
call at review time): fold into v0.12.0 pre-tag (requires re-running the D-prep
pre-flights + CI on the new tip), or first post-release patch (v0.12.1) /
v0.13. Client-only change; wire untouched; no backport pressure (the support
lines inherit whenever the stack is next picked).

## 8. The 1-Fable plan review fold (2026-08-21) — normative

Findings and dispositions (all folded into the sections above):

- **MAJOR-1 (latch provenance):** `lastWalkTruncated` alone conflates exact-fill,
  tiny-early-ramp-budget trickles, and taper/manual-capped walks with the
  governed window-limit. Folded: the `wasLastBudgetCapClamped` provenance flag +
  the manual-vs-governed conjunct in the manager (§3.2), the self-arrest
  geometry replacing the false "never truncates" claim (§2, §4.1), and the
  bound pinned by `trickleThroughTinyBudgetSelfArrestsAndNeverCredits` plus the
  scanner/manager wiring tests. Exact-fill remains a bounded, self-arresting
  spurious latch (accepted; at most a handful of credit-free doublings).
- **MAJOR-2 (streak aliasing):** the 2 s interval vs ~630 ms cycle aliasing
  fails `answeredAllAsked` ~1-in-6 intervals, and a single-miss reset makes the
  10-streak unreachable on the headline rig. Folded: one-miss forgiveness
  (`rampUncreditedRun`, §3.4) with the alternating-credit safety argument and
  the two streak tests. The reviewer's alternative (crediting against
  `deltaDeclared − Δawaiting`) was considered and set aside: it changes the
  rung's meaning for pile-up shapes the hiccup pin protects, while forgiveness
  is strictly a reset-policy change.
- **minor-1:** the §4 vanilla-behind-in-OPEN containment claim was false
  (evaluated only in RAMP/ENGAGED) — removed.
- **minor-2:** the test plan admitted a blanket pre-snap `if (latch) return`
  implementation — `windowLimitedOfferBackedPlateauStillSnaps` added.
- Verified by the reviewer (no change needed): the mechanism arithmetic, the
  pre-snap guard's bit-identicality when unlatched, the tick-order attribution,
  the latch-clearing coverage (including the DISABLED-scan corner), the
  credited-streak accounting, and the pinned-decision inventory.

## 9. The 3-Opus implementation panel fold (2026-08-21) — normative

- **Wiring MAJOR (composition mismatch):** with `enableAdaptiveScanCadence`
  false the scan's governed half is the FULL sustained rate while the latch
  re-read burst = ceil(sustained/4) — manually-capped walks in
  [ceil(sustained/4), sustained/2) latched as governor-window-limited. Folded:
  `governedBurstCap()` is the ONE composition both sites read; pinned by
  `manualBelowTheGovernedHalfNeverLatches` (cadence-off, manual 1 < sustained 2).
- **Governor MAJOR (forgiveness scope):** folded as §3.4 now records — the
  forgiveness is latch-scoped; un-latched uncredited intervals reset exactly as
  main; pinned by `unlatchedUncreditedIntervalStillResetsTheStreak`. §3.4's
  alternating-link justification was replaced (credited measures post-doubling
  desired, not delivery); §4.3's slow-serve mechanism corrected (parks via the
  double-miss reset past ~2.67 s cycles; the 2.0-2.67 s band reaches OPEN,
  accepted).
- Minors folded: the tiny-cap taper floor corner documented at the scanner
  recording site (bounded by self-arrest; `burstCap == 1` always reads
  cap-clamped under pressure); `lastBudgetCapClamped` cleared in
  `SpiralScanner.reset()` for lifecycle symmetry; the duplicate truncation
  accessor collapsed to one (`wasLastWalkTruncated`, production + tests);
  `climbToCeiling`'s EWMA javadoc corrected (32K); the interval-scoped test's
  vacuous desired-at-ceiling assertion pinned on PHASE over nine unlatched
  twins; the manager test suite gained the cadence-off manual-binding arm the
  original suite could not red on.

### 9.1 The dynamics lens fold (same panel, second round)

- **MAJOR-1 (provenance brittleness):** exact `budget == burstCap` disarmed the
  latch at ~9 queued columns at the rig's park point — the fix would have been
  green in every suite and dead on its own rig. Folded: the 25%-tolerance
  predicate (`budget×4 ≥ cap×3`), the `marginalPressureKeepsTheCapClampFlag`
  scanner test at rig scale (cap 350, q=9), the deep-taper refusal preserved
  (the 500-of-1000 test unchanged).
- **MAJOR-2 (fallback-clocked credits):** the 1 Hz fallback re-declares full
  budgets regardless of drain, so slow-but-clean links credited to ~5.3× link
  rate through the bypass. Folded: the `wasLastScanFast()` conjunct — the latch
  now MEANS completion-clocked window-limited; fallback fires never latch;
  `truncatedGovernedWalkLatchesOnFastFiresOnly` pins both directions (the
  primed fallback scan must NOT latch; the fast follow-up must). Residual
  widening ~2× at the fast/fallback boundary recorded as accepted (§4.3).
- minor-1 (bit-identical claim vs the finally): resolved by the forgiveness
  scoping in §9 — with the scope, latch-false behavior is equivalent to main's
  (an un-latched uncredited interval resets, as before).
- minor-2: the RAMP diag receipt (credits=N/10, wl) added.
- minor-3: §6 timing corrected to ~40 s.
- Residual test-system note: the fully-composed marginal shape (burst ≈ 350
  AND queue = 9 through the real manager tick) is covered piecewise (scanner at
  rig scale; manager at burst 1 with the fast/fallback split); a composed
  manager test would need EWMA feeding through real ticks and is left to the
  live check (§6.2's `wl` receipt makes the composed answer observable in one
  diag read).
