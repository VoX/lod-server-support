# Region-major want-set scanning ("region scan") — design

Status: DRAFT for 2-Fable plan review. Scope: main/26.2 ONLY (user decision
2026-08-24 — backports follow the user's live approval). Successor problem
statement to xaero-map-bridge-plan.md §14/§18 and the live 26.2 sessions of
2026-08-24 (~12-15% far-radius Xaero tile drops at recv ~725 col/s).

## 1. Problem and goals

The client's want-set is declared ring-major (SpiralScanner: Chebyshev chunk
rings). At radius ~380+ one ring crosses ~95 of the 32×32-chunk regions every
downstream system is organized around — Xaero map regions, `.mca` region files,
the tscache's 32×32 tiles, region-summary tiles, and (at 4×4 granularity) the
quadtree's 8×8 leaves. Consequences measured live:

- The Xaero bridge must hold tiles for ~95 regions awaiting Xaero's ~10/s
  expensive region loads → queue overflow → 12-15% dropped tiles (§14 widened
  the buffers; §18 heals the drops after the fact — both are treating the
  symptom).
- Xaero region-load churn 1.87× (load_requests 2037 for a ~1089-region disc):
  successive rings graze a region, it parks between grazes, reloads.
- Rebuild coalescing 8.6 tiles/recolor vs the ideal 16 (a tile chunk's 16
  tiles arrive spread across many ring passes).
- Server disk reads hop across ~95 region files per ring pass instead of
  streaming one file.

GOAL: declare the want-set REGION-major — complete one 32×32 region, then the
next, spiraling region-by-region outward — so every downstream consumer sees
one or two active regions instead of ~95, while preserving the want-set
model's semantics exactly (re-declaration self-heal, budget, backpressure,
cadence, wire format, server behavior).

## 2. Core design: ordering, not gating

The user's original formulation was "only advance the spiral when all the
chunks in the region are loaded". This plan implements the same working-set
collapse WITHOUT a hard gate, because the existing budget already provides it:

- The walk visits regions in region-spiral order and emits, for each region,
  its still-wanted positions; the want-set budget (WANT_SET_BUDGET = 800,
  UNCHANGED) truncates the emission.
- The walk's structural invariant (v1.1, both reviewers): COMPLETE-PREFIX +
  AT-MOST-ONE-PARTIAL-TAIL — regions are visited in fixed order and left only
  when exhausted, so every emitted-from region before the truncation point is
  fully drained and at most ONE region is partially emitted. In the DENSE
  fill regime (each region ≥ ~budget needy positions) this makes the declared
  span ≤ 2 regions (800 < 1024); in SPARSE states (scattered dirty marks,
  summary-revocation residue, warm-rejoin stragglers) the span legitimately
  reaches the count of marked regions while total VOLUME stays ≤ budget. The
  Xaero working-set argument rests on the VOLUME bound, not the span bound:
  per-scan tile arrival ≤ 800 columns regardless of span, and sparse
  multi-region scans are inherently low-volume per region.
- A hard "region complete before advancing" gate would add pipeline bubbles
  (the server idles while the last stragglers of region N resolve at 1 Hz
  re-declare cadence, unable to prefetch region N+1) and needs a wedge rule
  anyway. Ordering + budget gives the same locality with zero throughput loss
  and no wedge risk: an unservable position (transient drop churn) simply
  keeps its region at the walk head, and the budget's remainder flows into
  the next region behind it.

Terminal dispositions (`classify` → SATISFIED: received/validated stamps,
sessionSatisfied via NOT_GENERATED, up_to_date) advance the walk naturally —
"all chunks in the region are loaded" is exactly "no position in the region
still classifies as needed", the same ladder the ring walk uses today.

### 2.1 Geometry and order

- Region coords: `rx = cx >> 5, rz = cz >> 5` (floor shift — negative-safe,
  identical to the bridge's bucket key and the tscache tile key).
- Region spiral: Chebyshev rings of REGIONS around the player's region,
  reusing the ring-index→coord mapping at region scale
  (`SpiralScanner.ringIndexToCoord` made shareable). Region ring bound:
  `ceil((effectiveLod + 32) / 32) + 1` covers every region the chunk disc can
  intersect regardless of the player's offset within their region.
- WITHIN a region: positions ordered by chunk-Chebyshev ring from the PLAYER,
  ascending (bucketed: one O(1024) pass classifies the region's positions
  into ≤64 ring buckets spanning the region's ring range, then buckets emit
  in ascending order). Two reasons, both load-bearing:
  1. The server's generation pacing anchors on the FIRST unsatisfied ts≤0
     declaration entry (`generationOrderSpreadExceeded`: candidate ring >
     frontier + MAX_GENERATION_RING_SPREAD(2) refuses). Ring-ascending
     within-region order keeps the frontier honest and the admission band
     rolling ring-by-ring through the region — §5 has the full analysis.
  2. Approximate nearest-first is preserved at 512-block granularity between
     regions and 16-block granularity within one.
- Across regions in the same region-ring: fixed spiral-index order (stable,
  deterministic; the region-ring's members differ by ≤ ~2× in true distance —
  accepted coarseness, same class as the ring-major model's arc scrambling).
- The per-position skips are UNCHANGED: `isVanillaRendered` exclusion
  (positions vanilla renders are skipped without breaking region completion),
  `classify == SATISFIED` skip — PLUS a per-position `ring <= effectiveLod`
  clamp (v1.1 A-7: the region bound over-covers by ~1-2 region rings whose
  beyond-lod positions have absent, all-needs leaves; without the clamp the
  walk would emit them forever and the server would churn `range_filtered`).

### 2.2 The region-skip fast path (replaces prefix + reopened rings + valve)

A region whose 4×4 quadtree leaves (8×8 chunks each) all have clear `needs`
masks contains no walkable work — skip it in O(16) map lookups
(`ColumnStateMap.regionNeedsFree(rx, rz)`, the `ringNeedsFree` sibling; the
leaf grid aligns exactly: leaf key = chunk >> 3, region = 4×4 leaves).

With that, EVERY scan is a stateless full-order walk:

    for each region in spiral order:
        if regionNeedsFree(region): continue        // 16 lookups
        emit region's needy positions ring-ascending // O(1024) + skips
        if budget exhausted: stop

Full-disc cost at lod 512: 1089 regions × 16 lookups ≈ 17.4k map hits + the
emitting regions' O(1024) passes — well under a millisecond, EVERY scan, from
ANY state. This deletes the need for the entire incremental-scan machinery on
the new path:

- No confirmed prefix, no `recenter` state, no crescent bands, no reopened-
  ring bitsets, no overflow valve, no shrink rungs (F1/lod twins), no
  `collectActionableRetryRings` walk coupling. Movement, dirty marks, summary
  revocations, retry marks, LOD/view-distance changes — ALL of them simply
  set/clear `needs` bits (they already do), and the next walk sees them
  because it looks at everything cheaply. The scanner's incremental state was
  compensation for O(disc) position walks; region-skip makes the walk cheap
  enough to be stateless.
- The 30-90 ms full-walk hitch class (the reason prefix retention exists)
  cannot occur: the walk is O(regions × 16 + emitted).
- AUDIT RUNG (v1.1 A-6): the region path's one structural regression risk vs
  legacy is a needs-mask/classify divergence — legacy heals a stuck-CLEAR
  needs bit incidentally on any per-position walk; the region walk never
  re-classifies inside a mask-clear region, so such a bit would strand its
  position FOREVER. The needs invariant is fuzz-pinned, but as runtime belt
  each 1 Hz fallback scan additionally classify-walks ONE skipped
  (needs-free) region round-robin (O(1024) ≈ µs), heals any divergence it
  finds, and counts `scan.audit_heals` — expected 0; nonzero is the tripwire.
  SectionStateFuzzTest gains per-op `regionNeedsFree` soundness probes.

### 2.3 What survives unchanged

- The want-set CONTRACT: complete declaration each scan, closest-first-ish,
  budget-bounded, replace semantics, no send-time suppression of awaited
  positions, empty-batch backpressure edge, `noteDeclared` arm/disarm.
- The adaptive cadence (20-tick fallback + fast re-scans): all of
  `fastRescanDue`'s rungs carry over EXCEPT two that priced the legacy walk:
  - `predictedWalkCost() > FAST_RESCAN_MAX_WALK_COST`: the region walk is
    always cheap, so the rung becomes constant-permissive. The cadence POLICY
    consequences (the elytra-wall analysis) are preserved by the rungs that
    actually regulate throughput: the ≥95%-answered outstanding gate (a
    moving client's leading-edge regions keep outstanding > 5% → 1 Hz, same
    equilibrium as today) and the pressure gates. §8 records this reasoning
    for the review to attack.
  - `hasActionableRetries`: existed because retry marks below the prefix were
    invisible to `outstanding`. The region walk declares them (they are
    needs), so the outstanding gate covers them; the rung drops.
  - The rate-cap spacing gate, burst cap, taper, v16 exclusion, shrink-tick
    refusal (drops — no prefix to protect), min-interval floor: unchanged.
- InFlightTracker, sendRequests, tracker.replaceWith, metrics, the governor
  wiring, prune (position-radius-based, order-free): unchanged.
- The SERVER: zero changes required (§4, §5). The wire: zero changes.

### 2.4 Scanner selection and the ScanPolicy cut (v1.1: full table, B-1/B-2)

`ScanPolicy` interface (or a shared abstract base — implementer's choice; the
three supplier SEAMS are fields today and become setter/getter methods either
way). The COMPLETE manager-facing surface, from the inventory:

| Member | Legacy | Region path |
|---|---|---|
| `maybeScan(...)` | verbatim | region walk |
| `setConfig`, `reset()`, `resetScanCounter()` | verbatim | reset clears cadence+diag; resetScanCounter KEEPS the deliberate post-dimension 20-tick wait + cadence disarm (its prefix-zero half is n/a) |
| `recenter(d)` | verbatim | no-op (stateless walk; the movement-window cadence flag is replaced by the region-count rung, §8) |
| `reopenRing(ring)` AND `reopenRing(ring, lod)` + `currentReopenLod()` | verbatim | no-ops (needs bits carry the information; currentReopenLod returns the effective lod for the caller's hoist, harmless) |
| `noteDeclared(n)`, `setOutstandingSupplier` | verbatim | verbatim (cadence shared) |
| `columnRateCap` / `columnBurstCap` / `adaptiveCadenceEnabled` seams | fields | interface `setColumnRateCap/setColumnBurstCap/setAdaptiveCadenceeEnabled`-style setters + the `governedBurstCap()` read path; tests that poke the fields pin the LEGACY arm |
| `wasLastScanFast/wasLastWalkTruncated/wasLastBudgetCapClamped` (governor latch) | verbatim | same semantics — truncated = budget ended the walk (§8) |
| `getEffectiveLodDistance/getPruneDistance` | verbatim | shared implementation (hoisted) |
| diag getters (`getConfirmedRing/getReopenedRingCount/getScanRing/getMissingVanillaChunks/getLastBudget/getLastQueued/getFastScans/getRateGated/getQuadRingSkips/getValveTrips`) | verbatim | §9 mapping (confirmed-radius; reopened/quad/valve = 0) + region getters |
| `scannerForTest()` | returns the interface; a legacy-typed accessor remains for scanner-mechanics tests |

SELECTION POINT (v1.1 B-2 — the draft's "reset()" was wrong in both
directions: reset() fires mid-session via /lss clearcache and /lss reset,
and dimension changes call resetScanCounter, never reset): the scanner is
chosen at MANAGER CONSTRUCTION (`ClientNetGlue.createRequestManager` — the
session gate rebuilds the manager on every SessionConfig). A config flip
therefore applies at the next SessionConfig (join, server `/lsslod set`
re-push, `/reload`) — NOT at dimension change, NOT mid-session via
clearcache. `reset()` stays selection-free. `enableScanPrefixRetention` and
`enableQuadtreeScan` gate the LEGACY path only and are untouched.
`enableRegionScan` deliberately gets NO ClientOptionCatalog row (an A/B
lever, not a user preference — recorded so the options-page review does not
re-litigate it).

## 3. Region walk details

- Emission buffers, budget computation (incl. taper/burst/wire-batch min):
  reuse the exact `maybeScan` head logic (shared or duplicated-with-pin).
- Within-region bucketing: Chebyshev distance is 1-Lipschitz in the Chebyshev
  metric, so a 32×32 region's ring span is ≤ 31 EXACTLY (v1.1 A-4 — the
  draft's ~46 was a Euclidean-diagonal error). A 32-slot bucket array over a
  1024-entry scratch suffices (+1 slack asserted).
- Region completion diagnostics: `regions_done` (regions before the walk
  head), `region_active=(rx,rz)`, `region_span` (regions the last declaration
  touched — the ≤2 invariant's live gauge), `region_skips` (needs-free skips
  this scan). Diag `Scan:` line gains mode-appropriate fields; exporter adds
  `scan.region_span`/`scan.region_skips`; the trace `scan` event carries
  them. Ring-named legacy fields keep emitting from the legacy path only;
  shared consumers (soak checker etc.) are audited in §10.
- `missing_vanilla`, effective-lod/Voxy-distance caching: carried over
  (shared base or composition — implementation's choice, pinned by tests).

## 4. Server-side leverage (user ask: get the most out of the change)

The single most important architectural fact: THE ROUTER DRAINS EACH PLAYER'S
BACKLOG IN DECLARATION ORDER. Region-major declarations therefore make every
server-side consumer region-local AUTOMATICALLY, with no server change:

- Disk reads stream one `.mca` at a time (vanilla IOWorker/Moonrise + OS page
  cache do the rest). Expected: `disk.avg_read_time_ms` drops on cold fills.
- The LOD store's SQLite point lookups hit one region's key range
  consecutively (page-cache locality; no schema change).
- The tscache's per-tile `int[1024]` arrays are hit tile-consecutively
  (LRU-friendlier; no change).
- Generation tickets cluster spatially (platform chunk-gen locality).

Candidates CONSIDERED and deliberately deferred (measure first, on the
benchmark harness's `no-cache` scenario, as a follow-up):

- S3 region-file read-ahead (fadvise-style warm of a just-entered region
  file): plausible latency win, but the page cache may already deliver it
  once reads are sequential — measure before adding surface.
- S2 store range-reads (one query per region): the pos packing is not
  1-D-contiguous per region and per-key lookups are index-fast; page-cache
  locality is the real win and it is free.
- Any wire-level region batching: rejected — the wire contract is
  position-keyed and NEVER tiered; region semantics must not leak into it.

## 5. Generation-pacing interaction (the one server-coupled risk)

Server facts (verified in source): the spread gate refuses a generation
candidate whose ring exceeds `frontier + 2`, where frontier = the LIVE
first-unsatisfied ts≤0 declaration entry's ring (fallback: wantSet[0]'s
ring); the cohort rule additionally refuses candidates > 1 ring beyond the
NEAREST outstanding ticket; outward frontier advance is damped at ~333 ms per
ring (inward instant).

Under region-major, ring-ascending-within-region declarations:

- HISTORY: this gate has been wedged ONCE ALREADY by a first-entry geometry
  change (the `updatePlayerChunk` call-site comment records "the want-set's
  first entry sits at ~viewDistance on a ring perimeter, which wedged the
  gate") — treat every claim in this section as review-mandatory.
- The frontier = the FIRST unsatisfied ts≤0 entry in declaration order = the
  active region's nearest unresolved ring (within-region ring-ascending makes
  this the minimum of the declared set's ring values for the active region;
  the player-region's excluded interior means the first entry sits at
  ~viewDistance, exactly as today). The admission band (frontier..frontier+2)
  intersected with one region is an arc ≤ 32 chunks wide × 3 rings ≈ 64-96
  candidates — comfortably above the default per-player generation cap (40).
  Generation rolls ring-band by ring-band through the region exactly as it
  rolls through global rings today.
- The declaration is deliberately NOT globally ring-monotonic: when the budget
  spans two regions, region N+1's near-corner entries carry lower rings than
  region N's tail. Harmless to the gate (the frontier is the first
  UNSATISFIED entry — region N's — and candidates are judged individually
  against it; N+1's near corner sits within ~0-2 rings of N's near corner in
  the same region-ring), but the non-monotonicity must be stated in the
  server-side comment that today says "closest-first by construction"
  (AbstractPlayerRequestState's backlog javadoc) — the property the server
  actually RELIES on is "the first unsatisfied acquisition entry is the
  nearest outstanding work", which region-major preserves.
- Region transitions move the frontier INWARD (next region's near corner) —
  instant by design (exactly at the moment N completes; while N's tail and
  N+1's head coexist in the two-region window, the COHORT rule (nearest
  outstanding + 1) briefly holds N's tail candidates behind N+1's nearer
  head — a bounded, self-resolving cross-region ring interleave, worth one
  test). The outward damp paces the new region's advance at ≤3 rings/s; a
  region spans ≤ 31 rings (A-4) → ≥ ~10.3 s/region damping floor vs 1024/40
  = 25.6 s/region gen time — the damp does NOT bind at default caps; it
  binds only above ~99 generated columns/s/player (config-raised caps);
  recorded as a known ceiling, not changed here.
- CHURN REGIME CHANGE (v1.1 A-5, must anchor the soak baselines): a
  region-major declaration is always up to ~31 rings deep, so on a GEN-bound
  cold frontier ~600-700 of each 800-batch sit beyond frontier+2 and every
  escalation attempt for them is REFUSED — `gen_order_gated`/`superseded`/
  `miss_dropped` run at ~hundreds/s/player through a cold backfill (legacy's
  compact 1-2-ring window kept them near zero), plus ~one memo-expiry disk
  re-read per waiting position per 30 s (miss-memo TTL vs ~26 s/region gen
  time ≈ ~20-25 extra reads/s — mild). No wedge — the admission band always
  holds ≥ the gen cap — but the fresh-backfill soak's counter expectations
  MUST be re-derived against this regime, not judged against ring-major
  baselines. NOTE: fresh-backfill runs at lod 24 (a ≤2×2-region disc) and
  barely exercises the region machinery — the far-radius weight rests on the
  dev-jar live gate, stated plainly.
- No server change. The live gates: the `fresh-backfill` and
  `generation-capacity-stress` soaks (which exercise the REAL client scanner)
  must stay green, including their `not_generated == 0` and superseded-churn
  premises; plus `gen_order_gated` staying proportionate in the backfill
  soak's server.jsonl.

## 6. Why this fixes the Xaero map issue (and what it obsoletes)

- Per-scan arrival volume ≤ the 800 budget and dense-fill span ≤ 2 regions:
  bridge-queue overflow becomes STRUCTURALLY RARE (it would need ~8
  concurrently active regions' worth queued vs the ≤2-region working set —
  v1.1: not "impossible"; generation stragglers completing 10-30 s late land
  as single tiles ~7-21 regions behind the stream head and re-activate a
  parked region — one extra load and occasional drop-report traffic is the
  expected residual, recorded so live drop-counter noise is not chased).
  [AMENDED by the §12 backpressure round, hybrid-scan-plan.md §12.1: the §18
  ledger heal is DELETED — `heal_pending` no longer exists; the taper prevents
  the drops and the kept immediate reporter covers the residuals.]
- Xaero needs ~0.7 region loads/s at the observed 725 col/s serve rate vs its
  ~10/s capacity — 14× headroom (vs 95-region demand spikes today).
- Deferral expiries (DEFER_CAP) vanish: a region's load (~100-200 ms) races a
  1.4 s stream, not a 10 s backlog.
- Load churn → ~1.0×; recolor coalescing → ~16 tiles/rebuild (halving recolor
  count); [AMENDED, §12 round: the §18 heal is deleted — the live signature is
  now `dropped_overflow` ~0 + `bp=` a live fraction (`(blocked)` during map contention is governance working, §12.8; `refused_paused` is DELETED) + `drops_reported`
  small; hybrid-scan-plan.md §12.4 is the signature list.]
- NO bridge changes in this round — it benefits passively. Bridge-side
  simplifications (deterministic flush on region completion) are a possible
  LATER round once region scan is field-proven.

## 7. Compatibility & edge cases

- Legacy servers/dialects: ordering is invisible to every server version (the
  wire carries positions+timestamps; v16 synthetic want-sets are server-side).
  The v16 fast-cadence exclusion carries over.
- Dimension change / reset / disconnect: RegionScanner state is (almost)
  stateless — reset() clears the cadence fields and diag counters only.
- Cache adoption (`adoptLoaded` stamps = revalidation needs): the walk
  re-declares them region-ordered; the post-load hitch class stays gone (the
  walk is O(regions×16 + emitted)).
- Summary tiles: `applyTileValidation` revocations set needs bits; the
  reopenRing call at the manager's revocation site routes to the interface
  no-op on the region path (needs bits are sufficient — pinned by test).
- Teleport (`RECENTER_FULL_RESET_DELTA`-scale): nothing to reset; the next
  walk re-derives from the new center. Prune hysteresis unchanged.
- LOD/view-distance shrink+grow: no special rungs — the walk always looks at
  live state (pinned: the F1-class "permanently blank annulus" scenarios must
  have differential tests proving the region walk re-declares).
- Order-coarseness residual: between-region order is spiral-fixed, so within
  one region-ring the fill completes region-block by region-block (a visible
  512-block checkerboard advance instead of arc sweeps. The user has seen and
  accepted the tradeoff conceptually; the dev-jar live test is the real
  acceptance gate).

## 8. Cadence: the region-count rung (v1.1 — the draft's equivalence claim was REFUTED)

The draft claimed the outstanding gate alone holds flight at 1 Hz. Reviewer A
refuted it with the elytra trace's own numbers: `inflight` = 0 at 18 of 26
samples during sustained 33 b/s flight (the server answered each batch inside
the 1 s gap at ~920 col/s) — legacy stays at 1 Hz there ONLY because of the
walk-cost rung's movement window, which this plan deletes. Without a
replacement, a raised-cap store-armed server (the user's own rig, 50/100
MB/s) would let flight intake climb from ~26 MB/s toward cap-bound 50-100
MB/s — the elytra investigation's warned-against regime.

THEREFORE, from day one: `fastRescanDue` on the region path carries a
REGION-COUNT rung — refuse fast fires while the last walk OBSERVED needy
positions in more than `FAST_RESCAN_MAX_ACTIVE_REGIONS` (2) regions
(maintained free from the walk's own bookkeeping; test seam like the other
cadence knobs). Behavior audit: dense warm backfill (the 4 Hz feature case)
observes ≤ 2 active regions → fast admitted; sustained flight mints
leading-edge + crescent needs across > 2 regions → 1 Hz (legacy-equivalent);
sparse dirty scatter across > 2 regions → 1 Hz (legacy also refuses via the
reopened-bit cost); a truncated DENSE walk still reads span ≤ 2 → fast
admitted (matching legacy's compact-frontier fast fills). The rung is
deliberately NOT `lastWalkTruncated` (that would kill the warm-backfill 4 Hz
— truncation is the NORMAL dense state at 800 < 1024).

Governor window-limited latch (`wasLastScanFast && wasLastWalkTruncated &&
wasLastBudgetCapClamped` → noteWindowLimited): `lastWalkTruncated` keeps its
exact meaning — the BUDGET ended the walk before the order was exhausted —
and under region scan a mid-fill full region (1024 > 800) truncates most
walks. That is the same provenance the latch already tolerates on legacy
mid-fill walks; the latch's discriminating conjuncts remain the FAST fire
(rare mid-fill: outstanding > 5%) and the burst-cap-was-binding flag, both
unchanged. Pinned by carrying the existing latch tests over the interface.

## 9. Config & diag surface

- NEW client key `enableRegionScan` (default true on main), applied at
  session start. Config round-trip + default pins per house style.
- Exporter/diag continuity (inventory audit): `check_soak.py`'s
  `check_fresh_backfill` HARD-READS `client.scan.confirmed > 24`, and the
  `Scan:` diag line + `scan.*` exporter fields are consumed by tools. The
  region scanner therefore keeps `scan.confirmed` MEANINGFUL — but v1.1
  replaces the draft definition, which reviewer A proved is IDENTICALLY 0
  (vanilla-excluded positions never get leaves; an absent leaf is all-needs;
  the player's own region is therefore never needs-free — the draft's
  "needs-free disc radius" can never cover it). Definition v1.1: `confirmed`
  = the minimum chunk ring of any position the walk OBSERVED as unresolved
  (in-lod AND not vanilla-excluded AND classify ≠ SATISFIED), or
  `effectiveLod + 1` when the walk observed none — exactly legacy's
  confirmedRing meaning ("everything below the nearest outstanding work is
  complete"; legacy also reports lod+1 on a converged disc). Computed free in
  the emit pass (needs-free skipped regions contribute nothing by
  construction — that is what needs-free means). NOT capped at effectiveLod
  (B-6: a lod-24 converged disc must read 25 > 24 for the fresh-backfill
  law) and approximate only under budget truncation (unwalked farther
  regions; ≤ one region-ring quantum, exact at convergence — the checker
  reads the final converged snapshot). Pinned: converged lod-24 disc reads
  exactly 25;
  `scan.ring` maps to the last walk's max emitted ring; `scan.reopened`,
  `scan.quad_ring_skips`, `scan.valve_trips` report 0 on the region path
  (legacy-path-only mechanics — 0 is their true value there); ADDITIVE fields
  `scan.region_span`, `scan.region_skips`, `scan.regions_done`. The `Scan:`
  diag line mirrors the same mapping plus `region_active=`. The trace `scan`
  event keeps its keys and adds the region ones. `soak_report.py` reads no
  scan fields (inventory); the 35-scenario `disc-completeness` area law is
  ORDER-BLIND and stays the primary safety net, unchanged.
- The v16-SERVER client fallback (old servers) has no spread gate and rate-
  limits regardless of order; the server-side v16 shim re-sorts its own
  synthetic sets — both unaffected. `TransferRateGovernor.minMissingVanilla`
  measures the vanilla view edge, not scan order — unaffected (recorded).

## 10. Test plan

- `RegionScannerTest` (new, ~30-40 tests): region-spiral order + bounds
  (incl. negative coords, player at region corners); within-region ring
  bucketing order; budget truncation + resumption; the COMPLETE-PREFIX +
  ONE-PARTIAL-TAIL invariant asserted on emitted sets in ALL states, with
  span ≤ 2 asserted under DENSE fixtures only (v1.1 A-1 — the draft's
  universal span pin is falsified by a 3-scattered-dirty state); the
  per-position lod clamp; the audit rung; the region-count cadence rung;
  vanilla-exclusion skip not breaking completion; needs-driven re-emission
  for dirty / retry / summary-revocation / adoptLoaded / shrink-grow
  scenarios (the F1-class holes); cadence arm/disarm + fast-fire parity
  (incl. v16 exclusion, pressure gates, rate-cap spacing); stateless
  movement/teleport behavior; converged silence (0-count walks send nothing).
- `RegionScanDifferentialTest`: randomized ColumnStateMap states + exclusion
  radii → the region walk's emitted SET equals the legacy walk's emitted SET
  (order aside, budget=∞) — the semantic-equivalence pin. Two conditions
  v1.1 A-7 makes explicit: the legacy arm runs FRESH-PREFIX (a mid-session
  retained prefix deliberately omits below-prefix positions), and the region
  arm's per-position lod clamp is in force (the over-covered boundary
  regions' beyond-lod absent leaves would otherwise over-emit).
- Manager suites policy (v1.1 B-3 — three-way, budgeted as comparable in
  size to RegionScannerTest itself): (a) scanner-MECHANICS-coupled manager
  tests (confirmed/reopened asserts, cadence-field pokes, direct maybeScan
  drives — LodRequestManagerTest:124/:238/:840-938, TickTest:904-922/:514/
  :988, SummaryTest:355) pin the LEGACY arm explicitly via the construction
  seam; (b) scanner-AGNOSTIC behavior pins (self-heal, dirty re-request,
  backpressure clear/disarm, tracker replace, stale crossing) run
  parameterized over BOTH arms; (c) the summary-revocation and dirty-reopen
  behaviors get REGION-path twins asserting needs-bit re-declaration (the
  §7 claims' actual pins). Manager wiring: interface routing, construction-
  time selection, diag/trace field presence both modes. The exporter
  contract FILE (fabric/src/test/resources/exporter-contract/
  client-snapshot.contract) gains the region keys (B-5).
- Existing suites: SpiralScannerTest + QuadtreeWalkDifferentialTest untouched
  (legacy path pinned as the control arm); ColumnStateMapTest gains
  `regionNeedsFree` gridding pins (incl. negatives); SectionStateFuzzTest
  gains a `regionNeedsFree` soundness probe beside its `ringNeedsFree` one
  (a needs-free verdict must imply every contained position classifies
  SATISFIED).
- The two chaos/orphan properties are PORTED in spirit to RegionScannerTest
  (`anyChaosInterleavingLeavesNoPositionPermanentlyOrphaned` /
  `movementChaos...` — the inventory ranks them the load-bearing pins): any
  interleaving of moves/dirties/revocations/answers/prunes must leave no
  position permanently undeclared while it still classifies as needed.
- The governor window-limited latch tests (LodRequestManagerTickTest ~:929+)
  run against BOTH scanner arms via the interface.
- Gates: full T1, T2; the FABRIC SOAK SUITE (`soak.sh all`) on this branch —
  the scan-order change is client-behavioral, and the soaks are the only
  harness that runs the real client loop. Any order-sensitive premise redding
  is adjusted only with a derivation note (per the soak discipline), expected
  candidates: warm-rejoin suppression timing legs, storm superseded ceilings.
- Live: dev jar on lss-test-26.2 — the user's approval gate. Signatures:
  `region_span <= 2` DURING FILL PHASES (span spikes correlating with
  dirty/revocation events are expected and harmless — v1.1), XaeroMap
  `dropped` ≈ flat at any radius, [AMENDED §12: `bp=` governing, no heal gauges] modulo the
  gen-straggler residual, fill rate ≥ today's ~725/s MEASURED BRIDGE-OFF (the
  §12 governed rate is ~650-700 by design — a bridge-on comparison must use the
  governed baseline, hybrid-scan-plan.md §12.3), and on a fresh-world
  test `gen_order_gated` judged against the §5 v1.1 regime numbers (NOT
  against ring-major baselines).

## 11. Rollout

Originally main/26.2 only [ported to 1.21.10 2026-08-26]. Implementation lands behind `enableRegionScan=true` via PR
after the 2-Fable + 3-Opus implementation review; dev jar to lss-test-26.2;
backports and any v0.13.0 packaging wait for the user's live approval. The
v0.12.1 staged tags are NOT touched by this round.

## 12. Risks

| Risk | Standing |
|---|---|
| Soak premises tuned to ring-major churn | Audited via full soak run; adjust-with-derivation only |
| Gen damping binds at raised gen caps (>~68 col/s/player) | Documented ceiling; revisit only if a real server hits it |
| Cadence equivalence claim (§8) wrong | Review attack surface + fallback region-count rung |
| Region-block visual fill unacceptable | User live gate; fallback = enableRegionScan=false |
| Hidden ring-field consumers (tools/scripts) | Inventory audit (§10); legacy fields stay on legacy path |
| Two scanners to maintain | Deliberate: control arm + instant rollback; retirement is a later, user-approved decision |
| Server "closest-first by construction" comments go stale | §5: reword to the property actually relied on (first unsatisfied entry = nearest outstanding), same PR |
| `scan.confirmed` semantics shift breaks tooling | §9 v1.1 definition keeps the checker law green; pinned in exporter tests |
| Docs drift | Same-PR task: CLAUDE.md want-set/architecture/config-key sites, README key, release-notes item incl. the visible 512-block fill-pattern change (B-7) |

## 13. Plan review fold (2-Fable, 2026-08-24)

Reviewer A (design lens, 3 MAJOR / 4 minor) and reviewer B (integration lens,
4 MAJOR / 4 minor). Every finding folded in place above; the headline
reshapes: the span claim restated as complete-prefix + one-partial-tail with
the Xaero argument re-derived from the VOLUME bound (A-1/B-4); the
confirmed-radius definition replaced after A proved the draft's was
identically 0 via the absent-leaf/exclusion interplay, reconciled with B's
no-lod-clamp law requirement into the min-observed-unresolved definition
(A-2/B-6); the cadence-equivalence claim WITHDRAWN as refuted by the elytra
trace's inflight=0 samples and replaced by the day-one region-count rung
(A-3); the ScanPolicy cut expanded to the full signature table incl. the
three field seams, and the selection point corrected to manager construction
(B-1/B-2); the manager-suite three-way test policy added (B-3); ring-span
arithmetic corrected to ≤31 with the damp floor/ceiling recomputed and the
cohort cross-region interleave recorded (A-4); the gen-churn regime change
quantified and anchored for soak baselines, with fresh-backfill's lod-24
non-coverage stated (A-5); the audit rung added for the needs-divergence
stranding class (A-6); the differential's two validity conditions and the
region walk's production lod clamp made explicit (A-7); exporter contract
file, docs tasks, and the no-options-row decision recorded (B-5/B-7/B-8).
Verified-sound by both reviewers: the ordering-not-gating core, the §5
server-gate facts and the non-reintroduction of the historical wedge, the
router's declaration-order drain (the server-leverage foundation), harness/
v16/governor/InFlightTracker neutrality, and the governor-latch provenance
argument.


## §14 As-built record (2026-08-24, main @ feat/region-scan)

Implemented per §2-§10 with the following as-built facts, deviations, and findings:

- **Shape**: `RegionScanner extends SpiralScanner` — the §2.4 "shared base" latitude
  exercised as subclassing. `scan()` went private→protected; the fast-path ladder
  gained two hook points at the exact legacy rung slots (`prePressureFastRefusal`
  base behavior retained / `postPressureFastRefusal` — the region arm drops only the
  retry rung, per §2.3); `sessionConfig` and `recenteredSinceLastFire` went
  protected. All cadence/budget/latch machinery, getters, the governor latch, diag
  and exporter plumbing are inherited untouched. Selection at
  `LodRequestManager` construction per §2.4 (`enableRegionScan`, default true;
  package-private test ctor `LodRequestManager(SpiralScanner)` = the §10 arm seam).
- **Ring-0 parity** (found by the manager suites): the legacy ring enumeration is
  structurally EMPTY at ring 0 (8·0 positions) — the legacy walk never declares the
  player's own chunk. The region emit pass skips `dx==0 && dz==0` to match
  (production-irrelevant — always vanilla-rendered at real view distances; load-
  bearing for the differential and the annulus-count pins).
- **DEVIATION — the §8 region-count cadence rung is replaced** (adjudicated by the
  implementation review): two facts refuted it in test. (1) A small LOD disc
  inherently spans up to 4 regions (the 2×2 around any player near a region
  corner), so `activeRegions > 2` refuses fast fires FOREVER at lod ≤ 32 — the
  cadence suites caught it. (2) The inherited legacy cost formula over the v1.1
  confirmed/scanRing fields prices a dense far-frontier fill at ~8·c·31, refusing
  the 4 Hz warm backfill past ring ~260 — the feature's own headline case. As
  built: `predictedWalkCost` is overridden — the MOVEMENT WINDOW (`recenter` opens
  it, the base fire path closes it) prices the whole disc with legacy's from-zero
  formula (flight keeps the legacy 1 Hz policy above lod 127, the elytra-wall
  line); STATIONARY prices 0 (the stateless walk genuinely costs
  O(regions×16 + emitted) at any frontier depth — this extends legacy's
  stationary-deep-fill admission to every depth, deliberately better than the
  ring-128 cliff). `postPressureFastRefusal` returns false per §2.3 (retry marks
  are ordinary declared needs). `FAST_RESCAN_MAX_ACTIVE_REGIONS` was deleted.
- **Span at the lod boundary** (found by the steady-fill pin): `region_span ≤ 2`
  holds exactly while fires stay in FULL-thickness regions; once the frontier
  reaches the lod-edge CLIPPED regions (an 81-288-position sliver each at lod 40;
  as little as ~33-65 at lod 512) one 800-budget fire legitimately spans several.
  The normative invariant is complete-prefix + one-partial-tail — a `region_span`
  above 2 during the final boundary ring of a fill is CORRECT, not a regression.
  Diag/monitoring must not alarm on it.
- **Skip census is leaf-granular**: `regionNeedsFree` intersects the lod square at
  8-chunk LEAF granularity — a partially-intersecting leaf with beyond-lod holes
  reads needs (conservative: a needless emit pass, never a wrong skip; the emit
  pass's per-position clamp keeps beyond-lod positions out of the want-set). Edge
  alignment therefore matters: a satisfied square whose edge is leaf-aligned skips
  its boundary regions, an unaligned edge walks them (pinned in
  `regionNeedsFreeSkipsLeavesWhollyBeyondLod…` + the RegionScannerTest census).
- **Audit rung**: one region per PERIODIC fire, round-robin over the region spiral,
  `ColumnStateMap.auditRegionNeeds` recompute; heals counted (`scan.audit_heals`,
  expected 0). The stranded-orphan class is demonstrated end to end in
  `auditRungHealsAStrandedOrphanWithinTwoFires` via the `corruptNeedsBitForTest`
  seam: a corrupted-OFF needs bit inside an otherwise-clear region produces FALSE
  convergence (confirmed = lod+1 with an unserved position) until the audit heals
  it on the very next fire (the player's own region is the cursor's first stop).
- **Test inventory**: `RegionScannerTest` (14 — order/prefix/clamp/confirmed/skip/
  audit/cadence/reset pins), `RegionScanDifferentialTest` (3 — want-SET equality vs
  the legacy fresh walk: geometries × centers, 4-seed chaos, moved centers),
  `SectionStateFuzzTest` regionNeedsFree brute-force probe (sampled per 50 ops),
  `ColumnStateMapTest` 3 region pins, `ConfigValidationTest` round-trip,
  the summary suite's region twin (revocation re-declares via needs bits, reopen
  surface a no-op) + the scanner-level dirty twin; the four legacy mechanics pins
  construct `new LodRequestManager(new SpiralScanner())` per §10 policy (a).
  Full T1: 2051/0 green.

## §14.1 Implementation-review fold (2026-08-24, 2-Fable + 3-Opus panel)

Panel: walk-correctness (Fable, 0 MAJOR), integration/lifecycle (Fable, 1 MAJOR),
cadence adjudication (Opus, 1 MAJOR — deviation ACCEPTED conditional), server
interaction (Opus, 1 MAJOR), tests+docs (Opus, 4 MAJOR). All seven MAJORs folded:

- **Cadence repriced (the two client MAJORs, superseding §14's first policy).**
  Movement window: `4·s(s+1)` with `s = lastWalkTruncated ? scanRing : lod` — the
  as-built lod-only price silently reverted the test-pinned elytra unlock at every
  shipped lod ≥ 128 (the pins live in `SpiralScannerTest` and no longer covered the
  default arm; region pins added). Stationary: the LAST walk's measured observe cost
  (16/region probe floor + clamped emit-pass areas — metered in `scan()`), never a
  flat 0 — the walk is NOT free in the sparse-scatter regime (each needy region
  costs an emit pass; a WorldEdit-scale broadcast at lod 512 walked 300-800k probes),
  so §8's "sparse scatter → 1 Hz" row is restored while dense fill/deep warm
  backfill (~10-45k) keeps 4 Hz. This stationary price is a MEMORY, deviating from
  the legacy prediction doctrine — sound because the doctrine's motivation (a
  crossing invalidates last-walk knowledge) is priced by the window branch, and
  stationary state evolves incrementally. One inherent lag: the first post-scatter
  fire may still be fast (the meter prices the PREVIOUS walk); the walk it admits
  meters the scatter and the cadence drops from the next decision on.
- **§14's refutation write-ups corrected** (cadence lens): the small-disc block is
  3×3 = 9 regions (not 2×2 = 4), and it breaks the SPARSE/TAIL regime at small lod
  (dense fills keep span ≤ 2 and the §8 rung would have admitted them); the
  inherited-formula figures are 256c+3968 (one-region span, refusal from c ≈ 241)
  to 520c+16640 (two-region span, c ≈ 95) — and what it refutes is "inherit the
  rung unchanged" (§2.3 mandated a constant-permissive override regardless of §8).
  Movement parity holds at HIGH crossing rates (elytra: window open at every
  decision point); at sprint/walk rates the window is closed at most decision
  points and the stationary price rules — which now matches legacy's truncated
  narrow-span admission.
- **Emit-pass loops clamped to the lod intersection** (server lens): the
  never-skippable lod-edge/near-player regions used to pay full 32×32 sweeps every
  scan (the soak's `region_skips: 0` is real: at lod ≤ 24 NO region is ever
  skippable — edge leaves straddle the square and exclusion positions are
  leaf-less); clamped bounds make a sliver cost its in-lod AREA and double as the
  per-position lod clamp.
- **Audit rung decoupled from periodic-only fires** (it starved under sustained
  4 Hz — now also fires every 4th consecutive fast fire); full round-robin latency
  (~1225 stops ≈ 20 min at lod 512) accepted for an expected-0 belt and now
  documented. `auditEnabled` test seam added: the differential disables it so a
  needs divergence in the shared fixture cannot be healed mid-compare.
- **SERVER MAJOR, recorded accepted-open (no code change this round): the outward
  frontier damp becomes the BINDING generation limiter under region-major.**
  `FRONTIER_OUTWARD_DAMP_MILLIS_PER_RING` (333 ms) × ~32 admissible positions per
  ring-within-one-region ≈ **96 col/s/player generation ceiling at far radius**,
  independent of config; legacy's ceiling was cap/latency (40/L). Break-even
  L ≈ 417 ms: slow-gen worlds never notice; superflat/pregenerated-with-holes/
  C2ME-accelerated worlds take up to ~4× slower far-radius generation backfill.
  §5's "does not bind at default caps" divided by the wrong quantity (a concurrency
  cap is not a rate) and §12's "config-raised caps" trigger is wrong — it needs no
  config change. UNOBSERVABLE by the soaks (all lod ≤ 24 — quadrant rings still
  carry 25-48 positions; the fresh-backfill run measured cap-saturated
  `active_hw=40`, latency-bound). The live dev-jar gate MUST include a
  generation-bound fill-rate comparison; if the ceiling bites in practice, the
  candidate server-side fix is a region-aware damp (advance keyed to completed
  band occupancy rather than a flat ms/ring) — a separate decision, deliberately
  not bundled into this client round.
- **Test MAJORs folded**: the complete-prefix assertion was dead code at lod 40
  (region (0,0) alone out-holds the budget — moved to lod 24 with a ≥2-group
  premise); both chaos/orphan pins ported (fixed-center at a cross-region lod 40
  with budget-truncated fires + the movement-chaos twin); the production arm
  selection pinned against the gitignored-local-config hazard
  (`productionDefaultCtorSelectsTheArmFromTheConfigKey`); the governor
  window-limited latch pinned on BOTH arms (four legacy twins); the F1
  shrink→grow blank-annulus differential added (prune → absent leaves → full
  re-declaration); region twins for the agnostic halves of the moved dirty pins;
  the exact-fill `lastWalkTruncated` divergence pinned as deliberate (region says
  false when only satisfied work remains — the correct-er reading for the
  governor latch; legacy says true; the unbounded differential deliberately does
  not compare the flag).
- **Coverage policy as settled** (deviation from §10's blanket "parameterize both
  arms"): the legacy arm retains `SpiralScannerTest` (full scanner-level),
  `QuadtreeWalkDifferentialTest`, the differential control (both quad variants,
  seams pinned), the four mechanics pins, the four latch twins, and the
  arm-selection pin; the region arm owns every default-ctor manager suite plus its
  own suites. Full both-arm parameterization of ~100 manager tests was judged not
  worth doubling the suite for the fallback arm; this record is the deviation note.
- **§9 surface, as settled**: `region_span` added to the client trace `scan` event
  (the §10 live signature needs its time series); `regions_done` and
  `region_active=` CUT — span/skips/audit_heals cover the diagnosis need.
  `region_skips` stays a session-cumulative counter (NIT noted: read it as a rate).
- **Misc corrections**: `maxRegionRing = ((lod+31)>>5)+1` = ceil(lod/32)+1 is a
  deliberate tightening of §2.1's ceil(lod/32)+2 (proven sufficient by
  brute-force); the stale `prePressureFastRefusal` javadoc (still citing the
  deleted §8 rung) rewritten; `closest-first` server comments reworded to the
  property actually relied on (declaration-order / nearest-within-active-region);
  CLAUDE.md's soak-law and ring-128 phrasings corrected; the fuzz probe's
  lod-4096 shape VERIFIED sound by construction (position-granular brute ≡
  leaf-granular impl exactly when no leaf is clipped; the partial-leaf arm is
  example-pinned in ColumnStateMapTest).
- **Xaero coupling recorded** (server lens, verified): drainEntries buckets by the
  same 32-chunk grid, so the collapsed span maps 1:1. [AMENDED by the §12
  backpressure round: the §18 heal is deleted — the re-mark-behind-the-head
  traffic is now the kept immediate reporter's (dimension/world-change drops),
  far rarer; `region_span` spikes correlated with `drops_reported` remain the
  designed behavior, not the invariant breaking.]
- **Gate status at fold time**: T1 full green; T2 green; `fresh-backfill` soak
  PASS (region arm live: confirmed=25, fast=2, audit_heals=0; the §5 churn regime
  visible — order_gated 33k, miss_dropped 40k — with all laws green at default
  caps). HONEST CAVEAT (server lens): every fabric scenario runs lod ≤ 24, where
  no region is ever skippable and region-major degenerates to quadrant-major —
  `soak.sh all` is a regression gate here, NOT evidence for §5/§6; the live
  dev-jar round is. The WSLg display wedge (client hangs in glfwShowWindow) is an
  environment fact of this box: soaks run under Xvfb (`DISPLAY=:99`).
  `soak.sh all` run 2026-08-24: nine scenarios green through
  generation-capacity-stress, then `bandwidth-throttle` redded with the CATALOGUED
  WSL2 clock-step artifact and was accepted-with-record per the catalog's decisive
  test — both B2 windows recompute to tick_dt 5.00 s vs wall_dt 2.52 s with traffic
  UNDER the cap on the tick clock (258,956 / 222,323 B/s vs 262,144) and byte deltas
  at/below the neighbors' steady 5 s quantum (1,301,096); artifact dir
  `soak-results/bandwidth-throttle-20260824T200445Z`. The remaining scenarios ran
  as a follow-on batch: `dirty-during-backfill` redded with the IDENTICAL
  clock-step shape (one B2 window, wall 2.52 s vs tick 5.00 s, 523 KB/s on the
  tick clock vs ~682 allowed — accepted-with-record,
  `soak-results/dirty-during-backfill-20260824T210715Z`); every other scenario
  in the batch green. A WSL restart clears the clock steps (user's call — it
  kills the working session).

**§14.2 Hybrid supersession note (2026-08-24, feat/hybrid-scan).** The walk this
plan specifies is superseded as the DEFAULT by the hybrid two-phase walk
(hybrid-scan-plan.md §2/§13): rings ≤ N=64 now run in legacy ring order and the
region spiral covers only the far RESIDUE (`(region ∩ lodSquare) \ nearSquare`),
probed via `rectNeedsFree` — `regionNeedsFree` is DELETED (its lod-intersection
job moved into the residue rect bounds). The §10 coverage policy carries: the
manager mechanics pins stay legacy-arm constructed; the steadyFill span pin is
re-homed to lod 128 with near-square containment in its allFull predicate
(hybrid impl-review M3), and the manager suites' lod-64 twins are recorded as
phase-1 pins. Cadence (§6) carries with hybrid amendments recorded in
hybrid-scan-plan.md §13/§13.1.
