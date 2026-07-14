# Adaptive Client Request Backoff — Implementation Plan

> **For agentic workers:** this is an implementation plan. Execute task-by-task with TDD; steps use checkbox syntax.

**Status:** plan / not yet implemented. Grows out of the backpressure review (`docs/planning/backpressure-review.md`, proposal **P5**) and the issue-#32 analysis: the client is an open-loop offerer — it re-presents peak request pressure every second regardless of server pushback, because a `rate-limited` response only triggers a one-scan skip and never lowers the request rate.

**Goal:** give the client a self-tuning request-rate control loop (AIMD congestion window) that converges to the server's actual sustainable throughput, and retire the crude `skipNextScan` backoff it replaces — staying responsive on healthy servers and adding **no new config knobs**.

**Architecture:** a small `AdaptiveConcurrencyWindow` owned by `LodRequestManager` replaces the *static* per-player sync in-flight ceiling (today read straight from the server-advertised `syncOnLoadConcurrencyLimitPerPlayer`). The window drives both the drain-gate in-flight limit and the scan budget base. It shrinks multiplicatively when the client sees sustained rate-limiting and grows additively when it's saturating the window without pushback. `skipNextScan` is deleted.

**Tech stack:** Java 25 (Fabric client), JUnit 5 (Tier 1), existing soak harness for convergence validation.

## Global constraints (hard requirements)

- **No new config knobs**, server or client. The window auto-tunes *under* the server's advertised `syncOnLoadConcurrencyLimitPerPlayer`; admins tune only that one existing cap.
- **No wire/protocol change.** This is purely client-internal pacing; the `rate-limited` signal and all payloads are unchanged. (So it needs no protocol bump and helps only updated clients — acceptable, matches PR #34's server-side complement.)
- **No regression on healthy servers.** With zero rate-limiting the window sits pinned at the advertised cap, so steady-state behavior is byte-for-byte today's.
- **Self-scaling constants.** AIMD parameters derive from the advertised cap where dimensioned, so raising the server cap needs no client retune.
- Verify every step against pinned tests before changing them (`SpiralScannerTest`, `LodRequestManagerTest*`) — several current behaviors are deliberately pinned.

---

## Design

### The control variable

Today two places read the static cap:
- `SpiralScanner.maybeScan` (`SpiralScanner.java:75`): `budget = syncOnLoadConcurrencyLimitPerPlayer() * BUDGET_MULTIPLIER(4)` — the scan queue's over-provisioned base.
- `LodRequestManager.drainQueue` (`LodRequestManager.java:238,254`): `maxSyncConcurrency = syncOnLoadConcurrencyLimitPerPlayer()` — the real in-flight ceiling.

Both are replaced by a single adaptive `window ∈ [MIN, cap]`:
- Drain gate: `(tracker.size() - genCount) >= window` → skip (instead of `>= cap`).
- Scan budget base: `budget = window * BUDGET_MULTIPLIER` — so when the window shrinks, the scan stops discovering positions it can't send, cutting wasted client-side `classify` work (directly serves "don't waste resources").

Generation keeps its static caps (16 per-player / 32 global) in v1 — generation is low-volume and wasn't the #32 bottleneck. Gen bounces do **not** move the sync window (see the signal below). A `genWindow` is a documented future option.

### The control law (AIMD, evaluated once per second)

State on the window: `int window`, `int rateLimitedThisCycle`, `boolean windowLimitedThisCycle`.

- **Signal — congestion:** `onRateLimited` for a *sync* bounce increments `rateLimitedThisCycle`. (Gen bounces are ignored for the sync window.)
- **Signal — saturation:** `drainQueue` sets `windowLimitedThisCycle` whenever the sync gate actually blocks a send because the window is full — i.e. the client wanted to send more but couldn't. This is the TCP "cwnd-limited" rule: only grow when more capacity would actually be used.
- **Update (once per second):**
  - `if rateLimitedThisCycle >= threshold` → **multiplicative decrease**: `window = max(MIN, round(window * MD))`
  - `else if windowLimitedThisCycle` → **additive increase**: `window = min(cap, window + AI)`
  - `else` → hold (dead-band: idle, or below the window with no pushback)
  - reset both signals to 0/false.

The dead-band + the "only grow when saturated" rule mean the window settles at the discovered-safe level during idle instead of drifting to `cap` and then bursting — no sawtooth on a capacity-limited server.

### Update cadence must be decoupled from the scan cadence (review refinement)

**Do NOT drive `update()` off `maybeScan`'s scan cadence.** `tickMovementPhase` zeroes `scanTickCounter` on every chunk-boundary crossing (`LodRequestManager.java:152`), and `maybeScan` returns `-1` until the counter reaches 20 (`SpiralScanner.java:64`). So under continuous fast movement — elytra, boats, `/tp`-spam, crossing a chunk more than once per second — the scan cadence never fires and an update tied to it would **freeze the window**: it stops both growing (can't recover when server load clears) and shrinking (the #32 back-off can't engage) for the whole trip. That's the same starvation the review flags as D2 for the timeout sweep, and a frozen adaptive window is worse than a frozen sweep because it's a persistent wrong throughput ceiling. (Note: server disk congestion is *global* — a small window is still the correct learned rate wherever the player moves — so the harm is the loss of *adaptation*, not a wrong value per se.)

**Fix:** run `update()` on its own tick counter incremented once per client tick in `tickScanPhase`, firing every `TICKS_PER_SECOND`, independent of whether `maybeScan` fired. Movement does not gate `tickScanPhase`, so the window keeps adapting while moving; a decode-backpressure halt returns *before* `tickScanPhase` (`LodRequestManager.java:117`), so the window correctly freezes during a halt (no sends → no signals to act on). This is the same decoupling the review's P3 wants for the timeout sweep — the sweep can move onto the identical counter, either here or in P3.

### Initial constants (all soak-validated in Task 6; none admin-facing)

| Constant | Initial value | Rationale |
|----------|---------------|-----------|
| `MIN` (floor) | `max(4, cap/16)` (=8 at cap 128) | never fully stall; keep a responsive trickle |
| `MD` (decrease factor) | `0.7` | gentler than halving — higher utilization, less oscillation, still responsive |
| `AI` (increase step) | `max(8, cap/8)` (=16 at cap 128) | recover from a decrease in a few seconds (~64→128 in 4 cycles) |
| `threshold` (decrease trigger) | `max(2, window/16)` | ignore 1–2 stray/aliased bounces (dedup `ts≤0`); react to sustained congestion |
| initial / reset window | `cap` | start optimistic; healthy servers never leave the cap |

`MD` and `threshold` are dimensionless; `MIN` and `AI` derive from `cap` → self-scaling.

### Why it stays responsive

- Starts at `cap`; a healthy server sees zero bounces so the window never leaves `cap` (== today).
- Unlike `skipNextScan`, scanning **never stops** — a shrunk window keeps discovering positions at a reduced budget, so the moment capacity frees the client is already offering into it.
- `AI` recovers a post-congestion cut in a few seconds.
- `MIN` keeps a small trickle even under total saturation, so a transient overload doesn't lock the client out.

### Known limitations (v1, documented not fixed)

- **Recovery from the floor is ~seconds, not instant.** After a cut to `MIN`, climbing back to `cap` at `+AI`/second is a deliberate AIMD trade for smoothness vs the old skip-then-snap-back. `AI` is the tuning knob (Task 6 soak); acceptable because congestion that drove the cut is usually still present.
- **Dedup-bounce false-shrink near `MIN`.** `threshold` floors at 2, and the `rate-limited` signal aliases benign cross-player dedup (`ts≤0`) bounces (review C2). On a server with heavy clustered dedup churn, ≥2 aliased bounces/second could hold the window below `cap` with no real slot/pool pressure. The dead-band handles the common case at the cap; the clean fix is review **P1** (split the `rate-limited` signal by cause and feed only slot-full/pool-capacity bounces into `onRateLimited`). Until P1 lands, this is a known, bounded false-throttle — the window still floors at `MIN`, never zero.
- **Grow is suppressed while the budget is scaled below the window.** The grow signal (`windowLimited`) only fires when `drainQueue` fills the gate, which needs the scan to queue more than `window` positions. During vanilla-load or decode-pressure the budget (`window×4 × those scales`) can fall below `window`, so the window can't grow back until those scales relax. This is usually correct (those periods genuinely want throttling) but means post-teleport recovery into fresh terrain can lag until vanilla finishes streaming.

### Retirements (subsumed by the window)

- **`skipNextScan`** — the one-scan global skip is fully subsumed: the window is a finer, continuous global backoff that keeps scanning. Delete the field, `noteRateLimited`, the skip branch in `maybeScan` (`SpiralScanner.java:29,46-48,67-70`), `clearSkipNextScan` (`:230-231`), the `reset()` clear (`:204`), and the two call sites (`LodRequestManager.java:444,467`). Its ~8 `SpiralScannerTest` cases are replaced by the window's unit tests.
- **The disconnected 4× budget base** — folded onto the window (`budget = window*4`), so `BUDGET_MULTIPLIER` now over-provisions relative to the *live* target instead of a static constant that never bounded anything (review finding A2). The multiplier itself stays.

**Kept (orthogonal — not backoff):** the per-position `retry` mark (addressing: a bounced position still must be re-requested), decode-queue pressure (client-side backpressure for a different bottleneck), vanilla-load scaling, and the 10 s timeout retry.

---

## Tasks

### Task 1: `AdaptiveConcurrencyWindow` (pure unit, no MC deps)
**Files:** Create `fabric/src/main/java/dev/vox/lss/networking/client/AdaptiveConcurrencyWindow.java`; Test `fabric/src/test/java/dev/vox/lss/networking/client/AdaptiveConcurrencyWindowTest.java`.
**Produces:** `AdaptiveConcurrencyWindow(int cap)`; `int current()`; `void onRateLimited()`; `void noteWindowLimited()`; `void update()` (applies AIMD, resets signals); `void reset()` (→ cap); constants derived from cap.

- [ ] Write failing tests: (a) starts at cap; (b) `update()` with no signals holds; (c) `noteWindowLimited()` + no bounces → `+AI` up to cap, clamped; (d) sustained bounces (≥ threshold) → `×MD` down to MIN, clamped; (e) below-threshold stray bounces → hold (dead-band); (f) `reset()` → cap; (g) signals reset each `update()`.
- [ ] Run — fail (class absent).
- [ ] Implement the class with the constants above.
- [ ] Run — pass.
- [ ] Commit.

### Task 2: Drain gate uses the window
**Files:** Modify `LodRequestManager.java` (field + `drainQueue` L234-265); Test `LodRequestManagerTest.java`.
**Consumes:** Task 1. **Produces:** `LodRequestManager` owns a `syncWindow`, initialized from session config; `drainQueue` gates sync on `syncWindow.current()` and calls `noteWindowLimited()` when the sync gate blocks.

- [ ] Failing test: with the window forced low, `drainQueue` admits only `window` sync requests and flags window-limited; gen path unchanged.
- [ ] Run — fail.
- [ ] Add the `syncWindow` field (init in the session-config handler); replace `maxSyncConcurrency` (L238/254) with `syncWindow.current()`; call `syncWindow.noteWindowLimited()` on the sync-gate skip branch.
- [ ] Run — pass; run full `LodRequestManagerTest` (update any pinned static-128 expectation deliberately).
- [ ] Commit.

### Task 3: Feed the congestion signal + evaluate on an independent 1 s counter
**Files:** Modify `LodRequestManager.java` (`onRateLimited` L443, `tickScanPhase` L198-206, `onDimensionChange` L467, `flushCache` L493-502); Test `LodRequestManagerTickTest.java`.
**Consumes:** Task 2.

- [ ] Failing test: a sync rate-limited increments the window's congestion signal; a gen rate-limited does not; `update()` fires once per second **even when the scan cadence is starved by continuous movement** (reset the scan counter every tick and assert the window still updates); dimension change AND `flushCache` reset the window to cap.
- [ ] Run — fail.
- [ ] In `onRateLimited`: classify the bounce **before** `removeByPosition` clears the entry (L450) — gen iff `InFlightTracker.isGeneration(pos)` (add it; the `generationPositions` set already exists); call `syncWindow.onRateLimited()` only for sync bounces (untracked → treat as sync). Remove the `scanner.noteRateLimited()` call.
- [ ] In `tickScanPhase`: increment a dedicated `windowUpdateCounter` every tick and call `syncWindow.update()` every `TICKS_PER_SECOND`, **independent of `maybeScan`'s return** (so movement's scan-counter resets can't freeze it; a decode halt still freezes it correctly by returning before `tickScanPhase`). Do NOT gate it on `scanned >= 0`.
- [ ] In `onDimensionChange`: replace `scanner.clearSkipNextScan()` with `syncWindow.reset()`.
- [ ] In `flushCache`: add `syncWindow.reset()` alongside `scanner.reset()` (parity — a `/lsslod clearcache` must not carry a shrunk window across the flush).
- [ ] Run — pass.
- [ ] Commit.

### Task 4: Scan budget tracks the window
**Files:** Modify `SpiralScanner.java` (`maybeScan` signature + L75) and its caller `LodRequestManager.tickScanPhase`; Test `SpiralScannerTest.java`.
**Consumes:** Tasks 1-3.

- [ ] Failing test: with an explicit window arg, `budget == window * BUDGET_MULTIPLIER` (update the existing `budgetBoundsQueuedPositions` harness to pass a window).
- [ ] Run — fail.
- [ ] Add an `int syncWindow` parameter to `maybeScan`; use it at L75 (`budget = syncWindow * BUDGET_MULTIPLIER`) instead of the session-config read. `LodRequestManager` passes `this.syncWindow.current()`. Update the `fireScan`/`fireScanFull` test harness to thread the window.
- [ ] Leave the gen sub-cap `genCap = generationConcurrencyLimitPerPlayer() * BUDGET_MULTIPLIER` (L111) reading the static config value, and add a one-line comment there noting generation is deliberately un-windowed in v1 (mirrors the kept static gen drain gate).
- [ ] Run — pass.
- [ ] Commit.

### Task 5: Keep scanning during continuous movement (the root of the freeze)
**Files:** Modify `LodRequestManager.java` (`tickMovementPhase` L152); Tests `SpiralScannerTest.java` (the reset matrix ~L539-557), `LodRequestManagerTickTest.java` (the CL-002 first-tick quirk ~L98-117).
**Consumes:** none (independent of the window, but complementary — land together so movement-loading is regulated by the window).

**Why:** `tickMovementPhase` calls `scanner.resetScanCounter()` (L152), which zeros both `confirmedRing` AND the 20-tick scan cadence (`SpiralScanner.java:207-210`). Because movement zeros the cadence on every chunk crossing, crossing faster than once per second (elytra/boats/`/tp`) means the cadence never reaches 20 and **no scan ever fires** — the observed "no LOD loads while moving fast." The cadence-zero half is a debounce that buys nothing (the cadence already caps scans at 1/s) and costs everything (total freeze under fast travel). The scanner already has `resetConfirmedRing()` (L221) which re-surveys from ring 0 *without* touching the cadence — its own javadoc notes it exists precisely so a recurring trigger "would [not] otherwise debounce scans back indefinitely." Use it for movement.

**Impact:** a moving player now scans at the normal 1/s rate, re-surveying nearest-first, so nearby LODs load continuously while travelling (best-effort; the far ring lags fast movement, which is the correct priority). This adds sustained request/serve/bandwidth load for moving players (today it's zero) — bounded by the per-player caps and regulated by the adaptive window (Tasks 1-4), which is why they ship together. Bonus: it also un-starves the 10 s timeout sweep, which rides the same cadence (review D2). The dirty-broadcast path keeps `resetScanCounter()` (out of scope).

- [ ] Failing test: with the scan counter reset every tick (simulating continuous movement), assert a scan still fires within ~20 ticks (today it never does). Update `SpiralScannerTest.movementResetZeroesConfirmedRingRestartsCadenceAndKeepsMarks` to reflect that the *movement path* now preserves the cadence (repoint the direct `resetScanCounter()` unit assertion to the dirty-broadcast path, which still uses it).
- [ ] Run — fail.
- [ ] In `tickMovementPhase` (L152): replace `this.scanner.resetScanCounter()` with `this.scanner.resetConfirmedRing()`.
- [ ] Update the CL-002 first-tick quirk test (`LodRequestManagerTickTest` ~L111-117): a player joining off (0,0) no longer has its primed tick-1 scan cancelled by the movement branch — assert the first scan now fires (the ~1s join delay is removed, a bonus).
- [ ] Run — pass; run full Tier 1.
- [ ] Commit.

### Task 6: Retire `skipNextScan`
**Files:** Modify `SpiralScanner.java` (delete field/methods/branch L29,46-48,67-70,204,230-231); `LodRequestManager.java` (L444 already removed in Task 3; confirm L467 replaced); delete the `skipNextScan` cases in `SpiralScannerTest.java`.
**Consumes:** Tasks 3-4.

- [ ] Delete `skipNextScan`, `noteRateLimited`, `clearSkipNextScan`, the `maybeScan` skip branch, and the `reset()` clear.
- [ ] Delete the now-obsolete `SpiralScannerTest` skip cases (L317, L395-412, L528-530, L548, L576-582, L606) and any `noteRateLimited` use in the fuzz test (L816 → drive backoff via the window instead, or drop that line's bounce simulation).
- [ ] Run full Tier 1 (`:fabric:test -x runGameTest -x runClientGameTest`) — pass.
- [ ] Commit.

### Task 7: Soak validation (convergence + responsiveness + movement-loading)
**Files:** Use existing `scripts/soak-scenarios/rate-limit-storm.json` and `teleport-prune.json`; add a client instrumentation probe for the window if useful.
**Consumes:** Tasks 1-6.

- [ ] Run `./scripts/soak.sh rate-limit-storm` (Fabric) and confirm: the window shrinks under the storm, no conservation-law violation, and recovers toward cap after the storm ends (add a window gauge to client snapshots if the report needs it).
- [ ] Run `./scripts/soak.sh fresh-backfill` and confirm steady-state throughput is not regressed vs a baseline run (healthy server → window stays at cap).
- [ ] Run `./scripts/soak.sh teleport-prune` and confirm a moving/teleporting client keeps loading LODs (columns received > 0 during movement) without re-triggering saturation.
- [ ] Tune `MD`/`AI`/`threshold` only if the soak shows oscillation or slow recovery; record final values in `AdaptiveConcurrencyWindow`.
- [ ] Commit any tuning.

### Task 8: Docs
- [ ] Update `CLAUDE.md` client-side section (`SpiralScanner`/`LodRequestManager` bullets) to describe the adaptive window, that `skipNextScan` is gone, and that movement no longer freezes scanning.
- [ ] Update `docs/planning/backpressure-review.md`: P5 status → implemented; note D3 (stacked reactions) resolved and D2 (scan-cadence starvation) resolved for movement.
- [ ] Add release notes (Performance): "Client now adapts its request rate to the server's capacity, reducing wasted retries under load" and "LOD chunks now keep loading while moving quickly (previously fast travel could suspend LOD loading entirely)."
- [ ] Commit.

---

## Test surface summary
- **New:** `AdaptiveConcurrencyWindowTest` (the control law).
- **Changed:** `LodRequestManagerTest` / `LodRequestManagerTickTest` (drain gate + signal + reset), `SpiralScannerTest` (budget base now window-driven; harness threads a window).
- **Deleted:** the `SpiralScannerTest` `skipNextScan` cases.
- **Tier 3** (`LSSClientGameTests`) exercises the live client loop end-to-end and should pass unchanged (window starts at cap; the gametest server doesn't saturate).

## Risks & mitigations
- **`SpiralScannerTest` blast radius** — the budget tests and skip tests are the main churn; contained to one file, mostly deletions + a harness param. Mitigation: Task 4/5 are separate commits so the budget-fold and the skip-retirement can be reviewed independently.
- **Over-backoff from aliased bounces** — the `rate-limited` signal aliases four server causes (review C2). Mitigated by the `threshold` dead-band (ignores 1–2 stray dedup bounces) without needing a wire-level cause split. If soak shows spurious backoff, raising the threshold is the knob (internal).
- **Under-utilization / oscillation** — mitigated by `MD=0.7` (gentle) + the cwnd-limited grow rule; Task 6 validates and tunes.
- **Pinned behavior change** — `TwoPlayerGameTests` bandwidth fairness and any `LodRequestManager` test asserting a static 128 ceiling must be updated deliberately, not worked around.

## Explicitly out of scope (documented, not done)
- A `genWindow` for the generation path (low-volume; gen has its own caps).
- Splitting the `rate-limited` wire signal by cause (review P1) — the dead-band makes it good enough for this loop; P1 is the clean fix for the near-`MIN` false-shrink limitation above and remains a separate observability win.
- **Shrinking `BUDGET_MULTIPLIER` (review A2 / cleanup B1).** Reviewed and deliberately NOT done here: the 4× is the mid-period slot-refill reservoir and now auto-scales with the window (level vs refill-headroom are different jobs); shrinking it risks under-utilization on *healthy* servers, so it's separate throughput/RTT tuning, not this plan.
- **Simplifying the `maxSendPerTick` drip / `MIN_SEND_PER_TICK` (review B6).** Under the window the drip is effectively a constant-16 burst cap, but it's orthogonal burst-smoothing that interacts with healthy-server throughput — a separate cleanup, not retuned here.
- **Deleting `RequestQueue`'s dead timestamp column (review P4).** Real dead code, but it has three extra `SpiralScannerTest` assertions (L592/653/699) that verify scan-time ts-queuing correctness, unrelated to backoff. Bundling that refactor would muddy review; keep it as its own PR (paired with P1 as the review sequenced it).
- Any server-side change — this is the client-side complement to PR #34's server-side capacity gate.
