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
- Since 800 < 1024 (one region's positions), a scan's declared set spans AT
  MOST TWO regions (the tail of the active region + the head of the next) —
  and typically one. That is the entire working-set collapse.
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
  `classify == SATISFIED` skip.

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

### 2.4 Scanner selection (the kill switch)

`ScanPolicy` interface extracted over the manager's scanner surface
(maybeScan, reopenRing→`noteInvalidated(ring)` no-op on the region path,
recenter, noteDeclared, reset/resetScanCounter, the diag getters,
getEffectiveLodDistance/getPruneDistance). `SpiralScanner` implements it
verbatim (its tests untouched); new `RegionScanner` is selected by client
config `enableRegionScan` (default TRUE on main — the user is live-testing
this line), read at session start (reset()): a mid-session flip applies at
the next join/dimension change (documented; hot-swap of scan state is
complexity without a user story). `enableScanPrefixRetention` and
`enableQuadtreeScan` gate the LEGACY path only and are untouched.

## 3. Region walk details

- Emission buffers, budget computation (incl. taper/burst/wire-batch min):
  reuse the exact `maybeScan` head logic (shared or duplicated-with-pin).
- Within-region bucketing: `int[64] bucketHeads` over a 1024-entry scratch
  (region ring span ≤ 63 for any region intersecting the disc when the
  player is inside it... the region containing the player spans rings 0..~45;
  a far region spans ~2×32 √. Bound: max ring within a region minus min ring
  ≤ 62 — assert + fall back to a plain sort if ever exceeded).
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
  instant by design. The outward damp then paces the new region's advance at
  ≤3 rings/s; a region spans ≤ ~46 rings → ≥ ~15 s/region damping floor,
  vs 1024 positions / 40 gen/s = 25.6 s/region gen time — the damp does NOT
  bind at default caps. It binds only above ~68 generated columns/s/player
  (config-raised caps); recorded as a known ceiling, not changed here.
- No server change. The live gates: the `fresh-backfill` and
  `generation-capacity-stress` soaks (which exercise the REAL client scanner)
  must stay green, including their `not_generated == 0` and superseded-churn
  premises; plus `gen_order_gated` staying proportionate in the backfill
  soak's server.jsonl.

## 6. Why this fixes the Xaero map issue (and what it obsoletes)

- Working set ≤ 2 regions (≤ ~2048 queued tiles worst case, vs MAX_QUEUE
  8192): overflow drops are impossible by construction at any radius.
- Xaero needs ~0.7 region loads/s at the observed 725 col/s serve rate vs its
  ~10/s capacity — 14× headroom (vs 95-region demand spikes today).
- Deferral expiries (DEFER_CAP) vanish: a region's load (~100-200 ms) races a
  1.4 s stream, not a 10 s backlog.
- Load churn → ~1.0×; recolor coalescing → ~16 tiles/rebuild (halving recolor
  count); §18's heal becomes a pure backstop (dimension flips, parked-region
  races); `heal_pending` ≈ 0 in steady state is the live signature.
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

## 8. Cadence policy note (for the review to attack)

Dropping the walk-cost gate removes the mechanism that today keeps a MOVING
client's fast cadence off past ring 128 (the elytra-wall partial fix). The
claim: the outstanding gate replaces it — sustained movement continuously
mints leading-edge needs, keeping outstanding > 5% of the last batch, which
already holds the cadence at 1 Hz in exactly the regimes the elytra analysis
worried about (50-75 MB/s at 2-3 Hz). A stationary converged client fast-
fires only when a re-scan has almost nothing outstanding — the cheap-walk
sparkle case the cadence exists for. Reviewers: attack this equivalence; the
fallback position is a region-count-based cost rung (refuse fast fires when >
N regions have needs), which restores a conservative gate at trivial cost.

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
  region scanner therefore keeps `scan.confirmed` MEANINGFUL, redefined as
  the CONFIRMED RADIUS IN CHUNKS: the largest R such that every region
  intersecting the Chebyshev disc of radius R is needs-free — computed during
  the walk at no extra cost (the walk already probes regions in ring order).
  A completed fresh-backfill yields ≥ 32 (region ring ≤1 complete), so the
  checker's `> 24` law holds without a checker change on the region path;
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

- `RegionScannerTest` (new, ~25-35 tests): region-spiral order + bounds
  (incl. negative coords, player at region corners); within-region ring
  bucketing order; budget truncation + resumption; the ≤2-REGION SPAN
  invariant under budget < 1024 (the core pin, asserted on emitted sets);
  vanilla-exclusion skip not breaking completion; needs-driven re-emission
  for dirty / retry / summary-revocation / adoptLoaded / shrink-grow
  scenarios (the F1-class holes); cadence arm/disarm + fast-fire parity
  (incl. v16 exclusion, pressure gates, rate-cap spacing); stateless
  movement/teleport behavior; converged silence (0-count walks send nothing).
- `RegionScanDifferentialTest`: randomized ColumnStateMap states + exclusion
  radii → the region walk's emitted SET equals the legacy walk's emitted SET
  (order aside, budget=∞) — the semantic-equivalence pin that makes the
  switch safe by construction.
- Manager wiring: interface routing (no-op invalidations on the region path),
  scanner selection at session start, diag/trace field presence both modes.
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
  `region_span<=2` steady, XaeroMap `dropped` ≈ flat at any radius,
  `heal_pending` ≈ 0, fill rate ≥ today's ~725/s, `gen_order_gated`
  proportionate on a fresh-world test.

## 11. Rollout

Main/26.2 only. Implementation lands behind `enableRegionScan=true` via PR
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
| `scan.confirmed` semantics shift breaks tooling | §9 confirmed-radius redefinition keeps the checker law green; pinned in exporter tests |
