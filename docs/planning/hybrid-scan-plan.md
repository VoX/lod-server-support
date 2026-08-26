# Hybrid scan: ring-major near, region-major far (v3.2 — §12.8/§12.9 supersede the §12 -1-on-blocked/refusal doctrine)

**Status: PLANNED, NOT EXECUTED** (user directive 2026-08-24: write + review only).
Follow-up to `region-scan-plan.md` (§14/§14.1 = the region round's as-built + review
records). **v2 folds the 4-agent plan review** (3 Opus: design/geometry,
cadence+server+boundary, tests+consistency; 1 Fable: holistic/premise — §11 is the
disposition record; 13 MAJORs, all folded). **v2.1 folds the final Fable review**
(2 localized MAJORs + 5 MINORs + 4 NITs; verdict: no structural rework — the
two-phase design, partition proof, boundary derivation and §8 break inventory
survive adversarial checking). The WALK design is SETTLED; execution stays gated on
§0. **v3 (user-directed, same day, after the live region-build session): §12 adds
the Xaero ingestion BACKPRESSURE mechanism (want-set taper off the bridge queue)
and §12.1 records the §18 heal's REMOVAL — §12 is SEPARABLE from the hybrid walk
and may ship ahead of it (§0). **v3.1 folds §12's own 3-Opus review** (15 MAJORs
across mechanism/dynamics/consistency lenses — headline folds: the drainable-latch
-1 rule with watchdog + hysteresis + halt time-box, the 75% protective mapping,
the heal removal RE-SCOPED to ledger-only with the immediate reporter KEPT, the
cave-layer class corrected to refusal-while-paused, the two-regime dynamics, and
§12.5's v0.12.1 re-cut recommendation). §12 is now build-ready pending two USER
decisions recorded in §12.5.** **v3.2 (2026-08-24, after the first live session
refuted the doctrine): §12.8/§12.9 INVERT the -1-on-blocked rule and DELETE the
refusal latch — read §12.8 before treating anything in §12.1(b)/§12.2's -1
rule/§12.4's refusal pins as current.**

## §0 Sequencing (panel-settled — the fold-in option is DELETED)

Follow-up branch `feat/hybrid-scan` off `feat/region-scan`, opened ONLY after the
region round's live gate — and only if that gate actually REPORTS the near-fill
artifact (§1 is a geometry-derived hypothesis, not yet a live observation; the user
judges). Reasons the panel closed fold-in: it would destroy the region round's
mandated live measurement (the §14.1 damp ceiling — the hybrid moves the frontier
anchor, the ceiling's input); it collapses rollback attribution (one lever would
revert two changes); and the test debt (§8) touches the region round's own suites.
Baseline bookkeeping: the hybrid's live fill-rate comparison target is the REGION
round's measured numbers (which will exist by then), not shipped legacy's ~725 col/s.
**§12 (Xaero backpressure + heal removal) is SEPARABLE and sequence-independent of
the walk change**: it addresses a finding from the region build's LIVE session
(777 col/s arrival vs the ~680 col/s frame-budgeted writer ⇒ steady 2.2% overflow
drops, landing wherever the walk is when saturation starts) and is recommended to
ship AHEAD of the hybrid as its own small round — the user decides at execution
time.

## §1 Problem — what pure region-major costs near the player

- **The region-grid artifact.** The walk completes each region before the next: a
  player 2 chunks from a region boundary watches their own region fill to its far
  corner (up to 31 chunks Chebyshev away) before the terrain 2 chunks away across
  the boundary gets a column. Near a region corner the whole 3×3 neighborhood fills
  region-at-a-time (the spiral visits region ring 0 then ring 1 — the observable is
  block-at-a-time fill exactly where the eye is).
- **The frontier weakening** (region §14.1 server lens): the spread-gate anchor is
  the declaration head — under region-major "nearest within the active region", up
  to ~31 rings beyond the true nearest work (the A-4-corrected span). Permissive
  direction only, but it loosens the near-fill ordering the settled backfill model
  promises.
- Far away neither matters: fill order at 1000+ blocks is ACCEPTABLE (panel: not
  "imperceptible" — a 512-block region filling at ~1100 blocks subtends ~25° and
  the Xaero map view shows fill order at any radius; the claim is that block
  advance there is the tradeoff the user already accepted for far terrain), and the
  working-set collapse is the whole point there.

## §2 Design

### §2.1 The two-phase walk (same class, same arm — `RegionScanner.scan()` rewritten)

`HYBRID_NEAR_RADIUS = 64` (constant; test seam `hybridNearRadius`; §10 = the
derivation). `N = min(HYBRID_NEAR_RADIUS, lod)`. `lod` and `N` are hoisted ONCE per
scan (getEffectiveLodDistance is a cached Voxy query — a mid-scan shrink would
otherwise double-emit the band (N_new, N_old]).

- **Phase 1 — near rings (legacy order, stateless):** Chebyshev rings around the
  live player position, enumerated with `ringIndexToCoord` (load-bearing for the
  order pins — a box scan + sort would change intra-ring order), from `r₀ + 1` up
  to N, where `r₀ = ⌈vd/√2⌉` (rings ≤ r₀ are wholly vanilla-rendered by
  construction — the first ring with an unrendered cell is r₀+1, code-shape
  verified; skipping them saves 4·r₀(r₀+1) cells/scan, 2,208 at vd 32; r₀ is
  recomputed live — view distance is dynamic). Per ring:
  `ColumnStateMap.ringNeedsFree` FIRST (existing leaf probe, center-parameterized
  — ~r+4 leaf lookups, NOT r/8; consulted gate-INDEPENDENTLY —
  `enableQuadtreeScan` gates the legacy arm only, per §7's legend correction),
  skipping satisfied rings wholesale; needy rings pay the 8r per-position pass
  (exclusion skip + classify ladder identical to phase 2's). Ring 0 stays empty
  (ring 0 ≤ r₀ keeps it out of phase 1 and the residue bounds exclude it from
  phase 2 — the existing dx==0&&dz==0 guard becomes unreachable and stays as a
  commented belt, neither deleted nor duplicated). Phase 1 does NOT write
  `lastExclusionRadius` (keeps the inherited F1 shrink rung inert, as on the
  region arm). NOTE the never-skippable band: rings r₀+1..~vd+8 contain leaf-less
  excluded positions (absent leaf = all-needs), so they pay their 8r pass every
  scan forever — priced into §3, and the reason `ring_skips` reads ~0 at soak
  lods (vd-dependent; record beside §14.1's region-skip equivalent; not a defect).
- **Phase 2 — far regions:** the region spiral, classified against the
  **lod-clamped RESIDUE** (panel MAJOR ×3 — the v1 raw-rectangle classification
  falsified the degeneracy claim and double-walked the boundary band):
  `residue(region) = (region ∩ lodSquare) \ nearSquare`, which decomposes into ≤ 4
  axis-aligned rectangles (both squares are axis-aligned Chebyshev balls).
  - residue EMPTY → the region is ignored outright: **no probe, no emit pass, no
    counter** (like a beyond-lod region). At lod ≤ N every region's residue is
    empty ⇒ phase 2 performs literally nothing — the degeneracy claim, now true
    and pinnable.
  - residue non-empty → a residue-restricted leaf probe (a NEAR-AWARE variant of
    `regionNeedsFree` — the existing one clamps to the lod square ONLY; same
    conservative partial-leaf convention, own gridding pins per §8) over leaves
    intersecting the residue; on needs, the emit pass runs over the residue
    rectangles ONLY (their loop bounds ARE the near+lod clamp — no per-cell near
    test, no double-observation of phase-1 territory).
  The counting sort, scratch, and emission conventions are unchanged.

Order = phase 1 rings ascending, then phase 2 region-spiral. Exactly-once holds by
strict partition (panel-verified exact: both zones are axis-aligned rectangles about
one center; no rounding, no metric mismatch; negative coords floor-shift clean).

### §2.2 What each regime gets

- **Near (≤ N):** fill ORDER identical to a FRESH legacy walk (the panel qualifier:
  the shipped legacy arm carries prefix retention/reopened bitsets — phase 1
  matches the from-scratch enumeration, which is also what the differential's A-7
  validity condition compares). Declaration head = true nearest work inside N.
- **Far (> N):** the region walk verbatim — complete-prefix, span ≤ 2 dense (with
  §14.1's lod-edge clipped caveat AND its inner twin, final review: NEAR-straddling
  regions' residues are slivers too — as small as ~32 cells at worst alignment —
  so the FIRST far batches legitimately span many regions; §9's span ≤ 2 signature
  reads past them), Xaero collapse, server locality. The near zone GIVES BACK
  region-locality (~20-25-region interleave during near fill — bounded, short,
  page-cache-friendly).
- **Small lod (≤ 64):** phase 2 provably does nothing; the walk is a stateless
  legacy-ORDER scan. NOT "strictly stronger" than legacy (panel carve-outs:
  `lastWalkTruncated` keeps the region arm's needy-work-remains convention —
  §14.1's pinned divergence; cadence prices differ — observe-meter memory vs
  legacy prediction; diag differs). Order equality is the pinned claim.
- **Cold join at lod 512:** near disc ≈ 16.6k raw / ~13.2k after the code's
  buffered-Euclidean exclusion (~3.5k excluded — not the Chebyshev-square 4.2k);
  convergence is bounded by SERVER throughput, not scan count (batches re-declare
  the unanswered prefix) — ~18 s at the rig's measured ~725-777 col/s, ~4-5 s at
  the 3,200 col/s ceiling. The first FAR column waits behind that (§2.3 —
  deliberate, and recorded so the live round doesn't misread it).

### §2.3 Prioritization = nearest-first restored (and its cost named)

Phase 1 emitting first is a HARD reservation of the budget for near work. This is
not new policy — legacy was globally nearest-first, and the far zone always waited
behind near; the REGION arm was the anomaly (a region's far corner before a nearer
neighbor). Recorded consequences:
- **Far-fill latency vs the region arm regresses**: first far column ~1-2 batches →
  ~16-21 batches on a cold join (the flip side of "the first ~20 s look like
  legacy"). The live comparison must expect it.
- **Sustained flight**: each crossed chunk makes 2N+1 near positions declarable
  (+2vd+1 view-exit re-asks) — at N=64 ≈ 400 col/s ≈ 55% of a 725 col/s stream
  reserved for near. The far crescent falls behind — the documented elytra wall,
  unchanged in kind. Under §12's taper the effect compounds: a governed budget
  below ~50% is consumed entirely by near demand during sustained flight, so the
  far phase starves for the flight's duration — named here so §9's live round
  attributes the slowdown to the taper, not the walk. This reservation is THE ceiling argument against raising N
  (§10). A far-fairness budget reserve is structurally incompatible with the
  complete-prefix invariant and is rejected (panel-settled).
- Complete-prefix, hybrid form (STRONGER than v1's wording): at most ONE partial
  group across BOTH phases — a phase-1 budget break means phase 2 contributes
  nothing; a phase-2 break means rings r₀+1..N are complete. (Modulo the needs
  invariant, as always — the audit belt owns divergence.)
- `lastWalkTruncated` convention (feeds the governor latch AND movement pricing):
  the ARM's convention, both phases — set only when NEEDY work provably remains: a
  mid-emission break anywhere sets it via the same past-budget OBSERVATION the
  region arm does — full emit-free observation passes (leaf probe, then
  per-position classify; n == 0 ⇒ not needy), NEVER bare `ringNeedsFree`
  verdicts: the never-skippable exclusion band always FAILS the leaf probe, so
  probe-verdict truncation would violate this very convention on any exact fill
  at/below the band (final-review MAJOR); phase 2's needy-region guard unchanged;
  the exact-fill satisfied-tail case stays FALSE (§14.1's pinned divergence,
  carried forward and carved out of the order differential exactly as it is
  carved out of the set differential).

## §3 Cost model and cadence (panel-rederived — v1's three terms were all wrong)

- **Converged phase-1 floor** (N=64): rendered-ring skip (r ≤ r₀) + never-skippable
  band (r₀+1..~vd+8, full 8r passes) + probed rings (~r+4 each): ≈ **~3.1k at
  vd 12 / ~5.2k at vd 32** (final review — re-derived by simulation against the
  code's buffered-EUCLIDEAN exclusion shape, sampled over leaf alignments; earlier
  drafts used the Chebyshev square) — not v1's ~2k. Post-budget emit-free
  observation passes CHARGE the meter (honest pricing of real work — an early
  phase-1 break can make them O(8r·remaining); §8 pin 7 holds either way, the
  charge is normative).
- **Converged far floor at lod 512 prices past the 65,536 cap on BOTH arms** for
  ~7/8 of player alignments (the lod-edge band ≈ 65k clamped cells) — harmless
  (converged ⇒ 1 Hz is correct, as legacy) but it falsifies v1's "comfortably
  admissible"; the §8 cadence pin is a DELTA against the region arm, not an
  absolute.
- **Meter composition** (the under-metering hazard is the live risk): phase 1
  charges its walked cells + probe lookups into the SAME `lastWalkObserveCost`;
  a near-only walk's meter ≥ its walked cells is pinned (§8). Probe charge =
  actual lookups (early-exit on the first needy leaf usually costs 1-2).
- **Movement-window gate — ADJUDICATED DEVIATION (panel MAJOR):** with phase 1
  emitting first, any walk that truncates inside phase 1 ends with
  `scanRing ≤ N` ⇒ the movement price caps at 4·64·65 = 16,640 ⇒ a MOVING client
  keeps fast fires regardless of the true far frontier — a structural change from
  the region arm's "cliff lands slightly early". Adjudication: ACCEPT with the
  bounds stated — the cold case is covered by the ≥95%-answered gate (an 800-batch
  is not 95% answered in 250 ms at measured rates, ~725-777 col/s; exactly
  marginal at the 3,200 ceiling); the exposed case is warm-rejoin-while-moving
  (bulk up_to_date answers ⇒ sustained 4 Hz declarations ≈ 51 KB/s upstream —
  declaration traffic, not the 50-75 MB/s regime). The price is BIMODAL on whether
  phase 2 emitted (16,640 vs 4·s(s+1) at the far frontier) — a moving client
  oscillates 4↔1 Hz with the phase mix; expected in live traces, not a bug to
  chase. Pinned in §8; if live traffic says otherwise the fallback is pricing
  s = lod whenever the walk never reached phase 2.
- **Audit rung: runs on every FIRED walk regardless of which phase the budget
  broke in** (stated explicitly — the natural early-return would starve it during
  cold near fill, §14.1's own MAJOR class). Phase 1's ring skip adds a second
  stranded-orphan surface: partially self-healing (a stuck bit sharing a ring
  with any needy leaf still emits — finer than region granularity), belted by the
  same audit; the player's own region is the cursor's first stop and auditing it
  every fire (16 lookups) is recorded as cheap optional insurance.

## §4 Rollback and attribution

`enableRegionScan=false` stays the ONE lever → true legacy (it rolls back past the
hybrid too — a live problem cannot be attributed hybrid-vs-region without a rebuild
via the `hybridNearRadius` seam: N=0 ⇒ pure region-major. Recorded as accepted for
a dev-rig round; post-backport field diagnosis has only the all-or-nothing lever).
The far damp ceiling (§14.1) is untouched as a CEILING — but see §6: its ANCHOR
tightens, so the mandated generation-bound live measurement must be RE-RUN under
the hybrid, never inherited from the region round.

## §5 Xaero / working set

Near: ~r/4+4 regions per ring, ≤ ~21 per batch at the boundary — but the panel's
sharper frame: Xaero's constraint is region ACTIVATION RATE (~3.1 regions/s at
3,200 col/s, independent of N) vs ~10/s capacity, so Xaero does not constrain N
until N ≳ 250; the binding constraints are walk cost (∝N²) and the §2.3 flight
reservation. For BRIDGE USERS the true binding constraint is neither: it is the
§17-frame-budgeted WRITER THROUGHPUT (~680 col/s measured), which §12's taper
makes the whole fill's ceiling — this section's activation-rate arithmetic
governs only bridge-off sessions. Far: unchanged. `nativelyWritable` near tiles are Xaero's own writer
anyway. Steady near working set ≈ 25-36 resident regions with legacy-style graze
churn INSIDE N (the region plan's §1 churn complaint applies there again — bounded,
accepted, named).

## §6 Server interplay (reworded from v1's "strictly orthogonal")

The hybrid TIGHTENS the frontier anchor: declared head inside N = true nearest
work, ≤ the region arm's anchor by up to ~31 rings — always the tightening
direction, and tightening costs: an anchoring near event pulls the window inward
and the far band reopens at 333 ms/ring. TWO numbers, not one (final-review MAJOR —
the reopen crawl itself is SHARED by both arms): the crawl back to the far
frontier F costs (F − anchor)/3 s on EITHER arm (~80-160 s mid-fill at lod 512 —
a live trace showing a multi-minute far-gen stall after a near event is the
shared mechanism, NOT a hybrid regression); the hybrid's ADDED stall vs the
region arm is only the anchor DELTA, ≤ ~31 rings ≈ ~10.3 s — and typically ~0,
because a fresh near mint is the first unsatisfied ts≤0 entry on BOTH arms (the
player's region is region-ring 0 of the region spiral too). BOUNDED BY the
acquisition-frontier rule (the good news v1 missed): unsatisfied ts>0 entries do
NOT stamp — so ordinary dirty broadcasts on known columns cannot collapse the
window; the anchoring events are only ts≤0 acquisitions (view-exit crossings
under movement, ingest-failure re-asks, prune holes, dirty-revived
NOT_GENERATED). Near-zone damp never binds (full 8r rings). The far ceiling
itself (~96 col/s/player where gen is faster than ~417 ms) is unchanged; the
live gate re-measures it under the hybrid. During a §12-GOVERNED fill a
near-heavy tapered stream keeps the anchor near and the far spread gate clamped
— expected, not a hybrid regression (the §9 mis-attribution guard).

## §7 Config / diag / trace (v1's "add nothing" REVERSED by the panel)

- No new config key for the WALK (`HYBRID_NEAR_RADIUS` constant + seam); §12 adds `enableXaeroMapBackpressure` and the `bp=` diag token — see §12.2/§12.4 for that inventory.
- **`near_rings=` is REQUIRED** — defined as phase-1 rings that EMITTED (or
  observed needy work) last scan, NOT merely walked (the never-skippable band
  walks forever, which would make §9's "then ~0" signature unsatisfiable — final
  review): diag Scan line, trace `scan` event, exporter `scan.near_rings` +
  contract row. It is the round's only in-band instrument — `region_span=0` alone
  is triply ambiguous (near fill / converged / phase-2 wrongly idle) and the
  region round trained operators that span is the fill gauge.
- Phase-1 ring SKIPS feed the existing `quad_ring_skips` counter, whose diag
  legend is corrected ("ring skips (leaf fast path; phase 1 on the region arm)" —
  the current "0 with enableQuadtreeScan=false" note becomes false).
- `region_skips` counts probe-skips only; residue-empty regions are silently
  ignored (not counted) — censuses in §8 are specified against that.

## §8 Test plan (v2 — every pin the hybrid breaks is listed; the v1 omissions were
a panel MAJOR)

Existing pins that BREAK or go vacuous, with dispositions:
- `assertRegionMajorOrder` (suite helper): gains a hybrid contract — a ring-
  ascending prefix (phase 1) followed by the existing region-major property; the
  TWO suite tests calling it (`firstWalkDeclares…`, `emissionIsACompletePrefix…`)
  move to geometries where both phases emit (lod ≥ 80).
- `emissionIsACompletePrefix…` premise: at lod > 64 from an empty state, phase 1
  alone out-holds the budget and phase 2 never emits on a first walk — the
  premise must construct a pre-satisfied near disc + needy far spanning ≥ 2
  regions.
- `steadyFillKeepsSpanAtMostTwo…`, `boundaryRegionsClampPerPosition…` (lod 40):
  now single-phase — re-homed to lod ≥ 80 shapes; the span pin's allFull
  predicate additionally tests NEAR-square containment (the inner clipped-sliver
  band, §2.2), or it reds on the first far batches.
- `needsFreeRegionsSkipWithoutAnEmitPass` (9/18 census) + `resetClears…`
  (`skips>0` premise) at lod 40: re-derived at a lod > 64 geometry against §7's
  probe-skip-only counting.
- `exactFillEndingInSatisfiedTail…`: carried (the convention is §2.3's).
- `auditRungHealsAStrandedOrphan…` (lod 40): survives, but its premise SWAPS —
  phase-1's RING skip replaces the region skip as the orphan-invisibility
  mechanism; the premise comment updates with the re-home.
- The dirty-frame manager twins (session lod 64 = exactly N — single-phase):
  recorded as phase-1 pins, like the summary twin.
- `massDirtyScatterPricesPastTheCap…`: margin re-derived (~10.7k→~12.5k at
  lod 128 — holds 5× under cap; N-coupled: flips at N=96, so the pin documents
  the coupling); keep all cadence pins as inequalities vs the cap.
- Chaos ports: premise comments corrected (lod 40 is single-phase now); add a
  lod-96 region-corner seed so the chaos interleaves BOTH phases.
- The §14.1 manager twins (region needs-bit re-declaration): lod bumped > 64 or
  recorded as phase-1 pins; §14.1's coverage-policy paragraph amended.

New pins:
1. Near-order pin: first emissions == fresh-legacy ring order up to N (legacy
   control with `quadtreeScanEnabled` PINNED both variants — the local-config
   hazard class).
2. Small-lod ORDER differential (lod ≤ 64): ordered-sequence equality vs fresh
   legacy; `lastWalkTruncated` carved out (§2.3).
3. **Far-coverage differential restored (panel MAJOR):** the set differential
   gains lod {80, 96, 130} geometries with region-corner AND interior centers —
   without this the differential stops covering the region walk entirely (all v1
   lods ≤ 43 degenerate to phase 1).
4. Degeneracy pin, implementable form: at lod ≤ 64, phase 2 performs ZERO probe
   calls and zero emit passes (probe-count seam), not "region_skips == 0".
5. Boundary exactly-once: the lod > 64 differential's duplicate detection covers
   it globally; targeted N−1/N/N+1 geometries (region-corner player) cover the
   straddle-residue decomposition.
6. Phase-1 truncation-convention pin + the governor-latch input at a phase-1
   break.
7. Meter-composition pin: near-only walk ⇒ `lastWalkObserveCost` ≥ walked cells.
8. Movement-gate deviation pin (§3): truncated-in-phase-1 while moving ⇒ fast
   admitted; truncated-in-phase-2 at a deep frontier ⇒ refused; converged lod-512
   vd-32 unaligned ⇒ past-cap on BOTH arms (delta pin vs region arm).
9. Phase-1 orphan-heal twin: corrupt a needs bit inside a ring (the ring-skip
   strand class), audit heals, next walk declares.
10. **The boundary soak (panel MAJOR — the phase boundary must have ONE automated
    end-to-end gate; scenario vd chosen so lod > vd+8 — §2.1's ring_skips≈0 note
    is vd-dependent):** a new scenario at lod 80-96 over a prebuilt/warm world
    (the disk-read-gate annulus or warm-rejoin staging pattern; ~26-37k columns,
    disk-served, ~60-90 s), checked by convergence (`scan.confirmed = lod+1`),
    the order-blind disc-completeness law, and quiescence. Everything else in the
    fleet degenerates to phase 1 by design.
11. `reopenRing`/`recenter` REMAIN structural no-ops on the rewritten arm (the
    region round's §2.4-table carry, restated — the summary twin's
    `getReopenedRingCount()==0` assert silently depends on it).
12. The near-aware residue probe's ColumnStateMapTest gridding pins (partial-leaf
    convention at the NEAR boundary, mirroring the existing lod-clamp pins).

## §9 Rollout

`feat/hybrid-scan` per §0. Gates: T1/T2, fresh-backfill + the new boundary soak +
`soak.sh all` (regression only), the live dev-jar round with signatures: near fill
visually concentric at a region corner (the artifact §0's precondition observed,
now absent); `near_rings=` active during near fill then ~0; far fill
`region_span ≤ 2` (past the first clipped-sliver batches, §2.2); Xaero dropped
flat; fill rate ≥ the region round's LIVE numbers (aggregate, measured with the
bridge OFF — §12's governed rate is ~10-15% lower BY DESIGN, so three baselines
now exist: legacy ~725 / region ~777 / governed ~650-700, and the comparison
must name its regime) with the §2.3 far-first-column latency expected; the §6
generation-bound fill-rate measurement RE-RUN. Docs task: CLAUDE.md's three scanner sites (want-set paragraph
"REGION-MAJOR by default", the RegionScanner bullet, the config-key note), the
§14.1 coverage-policy amendment, and a release-notes item for the visible
fill-pattern change. Backports follow the region round's user-deferred schedule.

## §10 Boundary derivation (panel-rewritten; KEEP 64)

1. **Containment proof (the real rationale):** N ≥ 63 contains the player's whole
   3×3 region block for EVERY intra-region offset o (max Chebyshev to the block =
   max(o+32, 63−o) = 63) — the §1 artifact dies STRUCTURALLY, corner cases
   included. N=32 fails it (neighbors uncontained ⇒ the artifact reappears at 512
   blocks) — N=32 is dead, do not A/B it.
2. **Straddle-safety constraint:** N ≥ vd+31, else straddling regions hold
   permanently-needy exclusion cells and can never probe-skip. At N=64, vd=32:
   exactly one ring of clearance (recorded; an effective vd > 33 degrades to
   extra emit passes, not incorrectness).
3. **Flight reservation is the ceiling (not Xaero):** N=64 reserves ~55% of a
   725 col/s stream for near under sustained elytra; N=96 → ~73% (−40% far
   throughput). Cost ∝ N². Xaero doesn't constrain N below ~250 (§5).
4. A/B bracket if contested live: 64 vs 80/96 (visual side); the seam makes it a
   rebuild-per-value A/B — not "cheap", recorded honestly.

## §11 Plan-review fold record

**v2 (2026-08-24, 4-agent panel).** Panel: design/geometry (Opus, 2 MAJOR),
cadence+server+boundary (Opus, 2 MAJOR), tests+consistency (Opus, 8 MAJOR),
holistic/premise (Fable, 1 MAJOR). Premise UPHELD by the holistic lens (decisive
structural advantage: phase 1's free legacy oracle; steelmanned alternatives —
interleaved near sub-rings, graded tile sizes — lose on order-oracle absence and
boundary multiplication). Headline folds: the raw-rectangle classification →
lod-clamped residue rectangles; the movement-gate scanRing-pinning → adjudicated
deviation with pins + a stated fallback; the cost model fully re-derived; the
far-coverage differential hole; the boundary soak; near_rings promoted to
required; the frontier-anchor tightening with the ts>0 no-stamp bound; the
nearest-first/starvation reframing; the 3×3-containment + flight-reservation
boundary rationale; fold-in deleted (§0) with the artifact-observation
precondition; misc corrections (31 not 62/33; batch-count derivations;
"imperceptible"→"acceptable"; churn/locality give-backs named).

**v2.1 (the final Fable review fold, same day):** §6's stall bound corrected to
the two-number form (the (F−anchor)/3 reopen crawl is SHARED; the hybrid delta is
≤ ~10.3 s, typically ~0); §2.3 truncation defined via emit-free OBSERVATION
passes, never probe verdicts; floors re-derived against the code's
buffered-Euclidean exclusion (~3.1k/~5.2k; ~13.2k near disc; walk starts r₀+1;
N ≥ vd+31); `near_rings` defined on EMITTED rings; the inner clipped-sliver span
caveat + the allFull near-containment fix; §8 corrections (two helper callers,
the audit-premise swap, the lod-64 twins as phase-1 pins, the no-op carry pin,
the residue-probe gridding pins); the meter charges post-budget observation
passes; phase-1 probe gate-independence stated. The WALK is SETTLED — execution
remains gated on §0's live-artifact precondition.

**v3 (user-directed):** §12 Xaero want-set backpressure + §12.1 heal removal —
separable from the walk, reviewable by its own 3-Opus panel, may ship ahead.

**v3.1 (§12's 3-Opus panel fold, same day; 3+7+5 MAJORs, convergent):** the -1
rule rebuilt on a pump drainable LATCH (set false at every pumpLadder early
return — enumeration forbidden; ~11 of ~13 undrainable states were unlisted, each
a permanent whole-client halt) + staleness watchdog + flap hysteresis + a halt
TIME-BOX (the bridge may pace, never stop — the #71 halt was designed for Voxy's
unbounded-queue OOM emergency, not a bounded cosmetic queue); the report remapped
to 75% occupancy so the halt fires AHEAD of the first drop (decode-queue
doctrine) with ~750 columns of landing room; the heal removal RE-SCOPED to
LEDGER-ONLY — the immediate reporter (reportStaleDropped/DropReporter) is KEPT,
because all three reviewers proved §12.1(c)'s self-healing claim depends on it
(per-dimension stamps survive a clear; only reportIngestFailure un-stamps);
structurally-paused classes now REFUSE offers (no extraction cost, no churny
reports) with the honest release-note; dynamics re-derived as two regimes (the
95%-answered gate, not the ¼ gate, holds the rig at 1 Hz / fast-capable sessions
ride the ¼ boundary bang-bang, stable, mean ~25%); the governor burst-cap and
window-limited-latch couplings pinned; the lambda-consumer trap (must be an
anonymous class); the full deletion inventory (ingestParked family, census, ctor
arity, SUPERSEDED marks) and §12.5's sequencing/release mechanics incl. the
v0.12.1 re-cut recommendation.

## §12 Xaero ingestion backpressure (v3.1 — the want-set taper; panel-folded)

**Problem (measured live on the region build, 2026-08-24):** peak arrival 777
col/s vs the §17-frame-budgeted bridge writer ~680 col/s (a PEAK pair — the
session's 5,632 drops / 252k imply ~58 s of sustained surplus, so the deficit is
episodic; the equilibrium below uses the measured writer). The deficit fills the
bridge queue (byte-capped at 48 MB ≈ ~3k columns) and the surplus becomes
`dropped_overflow` (2.2%, landing wherever the walk is when saturation starts).
Any arrival above writer throughput saturates eventually; a repair mechanism can
only act after the fill ends. The fix is the one the architecture already owns
for Voxy (issue #71): the consumer REPORTS, the want-set TAPERS, arrival
self-paces to what the consumer can commit.

### §12.1 Heal removal — RE-SCOPED to ledger-only (user decision + panel fold)

The user's decision (2026-08-24) to drop the §18 heal stands for the LEDGER
machinery: `DroppedLedger`, `healPhase`, `flushLedgerRegion`,
`probeRegionForHeal`, `reportedHistory`, `rotateLedgerToTail`, the five `heal_*`
gauges, `enableXaeroMapBridgeHeal` + its ConfigValidationTest round-trip pin, the
§18 test suites, the ctor's two heal args, and the `clearQueue(boolean)` arity
(the keepLedger distinction dies with the ledger). **KEPT — the immediate
reporter**: `reportStaleDropped`, the `DropReporter` seam,
`reportDroppedProduction`, and their pins. All three reviewers independently
proved the original "(c) dimension-switch clears are self-healing" claim DEPENDS
on it: client stamps persist PER DIMENSION (`ColumnCacheStore`), so a cleared
column stays classify-SATISFIED forever unless `reportIngestFailure` →
`removeAsync` un-stamps it — without the reporter, every portal taken mid-fill
permanently holes ~450-700 queued columns. The reporter carries none of the
heal's indictment (its re-serves land in another dimension/session — never
displacing fresh columns 1:1 — and cannot churn: a foreign-dimension un-stamp
re-declares only when the player returns, when that dimension's map is active).
The world-id bulk clear reports through the same path (it used to count
`heal_abandoned`).

**Decision-record evidence** (discipline): live session at 6 min mid-fill —
`heal_pending=5632, heal_reported=0, heal_redropped=0` (the headroom gate held
by design; the sweep window never opened). Counter-argument recorded (mechanism
lens): §12's own equilibrium (queue ~11-30%) sits permanently INSIDE the heal's
operating window, so backpressure is exactly the change that would have made the
ledger heal functional — the fold's answer: prevention plus the kept reporter
make repair-BY-LEDGER redundant; the decision deletes the ledger, not repair.
Revert lever: the removal has no runtime switch — the revert point is the git
history of this file and `XaeroMapCompat` (named here per the decision
discipline).

**Residual classes, restated (panel-corrected):**
- (a) governor-lag transients: with the 75% mapping (§12.2) the halt fires
  BEFORE the first drop; a TOTAL writer stall drains into taper-stretched
  headroom (closed form Q' = B·(1 − Q/Q₇₅): ~8 s to 90%, ~10.6 s to 95%) —
  transient overflow requires a stall longer than that.
- (b) **[SUPERSEDED by §12.8 — the first live session measured this class at
  56k silent offers in 8.5 min: offers are no longer refused, the blocked pump
  keeps reporting, and the surviving residual class (b') is wedge-window sheds
  only — see §12.9's taxonomy.]** STRUCTURALLY-PAUSED states (the drainable latch's full set: cave layer,
  map locked, cache-only mode, ignored world, crash latch, writing toggled off,
  multiworld unwritable, …): the -1 rule keeps the FILL running for Voxy;
  `offerColumn` REFUSES new offers while undrainable (counted in its own class;
  the refusal also skips the 256-pixel extraction the old count-only pre-gate
  paid before evicting) and does NOT report (immediate reporting there would
  churn: un-stamp → re-declare → re-refuse at full server rate). Terrain served
  during a pause is not mapped and not retried — healed by revisit or
  `/lss clearcache`, and NAMED in the release notes ("fills while the map shows
  a cave layer are not written to the map").
- (c) dimension-switch clears: SELF-HEALING via the kept reporter.
- The old far-radius expiry class is already gone under region-major
  (`dropped_expired=0` live). DEFER_CAP still burns for tile-busy/region-saving
  deferrals in the fully-drainable regime (n=1 live evidence at zero) — the
  reporter covers genuine drops there too.

### §12.2 Mechanism (v3.1)

The bridge is a registered `VoxelColumnConsumer` (built in `buildConsumer`,
registered in `maybeRegister`; the single cached instance is the
register/deregister identity). **The lambda must become an anonymous class** — a
lambda cannot override the default `pendingIngestBacklog()`; VoxyCompat documents
this exact trap in its own comment. That conversion is the "plumbing", ~3 lines;
everything downstream (the `LSSApi` max-across-consumers poll, the
`SpiralScanner` linear taper with floor 1, the halt + edge-triggered empty
batch, the ¼-halt fast-path gate) is existing #71 machinery, verified.

- **The report — protective, not punitive (panel MAJOR):**
  `report = round(6144 × min(1, occupancy / 0.75))` with
  `occupancy = max(queuedBytes / maxQueueBytes, queueSize / maxQueue)` (the SEAM
  fields, not the constants; fraction clamped — the eviction loop can leave
  bytes momentarily above cap). The 75% denominator puts the halt AHEAD of the
  first drop — the decode-queue halt's own doctrine (it sits at ¾ "to keep the
  designed halt+clear ahead of the drop regime") — leaving ~750 columns of
  landing room for the in-flight tail (already-admitted server work + the LSS
  decode queue keep landing ~1 s past a halt). Equilibrium is set by
  arrival = writer, so the remap costs nothing in throughput; the operating
  point just moves to ~11% occupancy.
- **The -1 rule — DERIVED, never enumerated (panel MAJOR): [SUPERSEDED by
  §12.8 — the latch no longer produces -1 anywhere; it survives as diagnostics
  (`(blocked)` suffix) + the idle-guard key. -1 remains only for kill-switch /
  inactive / watchdog-stale / wedged.]** a `volatile
  drainable` latch set FALSE at EVERY `pumpLadder` early return and TRUE only
  where `drainEntries` actually runs — the pump's ~13 undrainable states
  (crash latch, map locked, cache-only, ignored world, session teardown,
  region-detection, writing-paused, cave layer, dimension mismatch, …) are
  covered by construction, and a future gate cannot silently re-open the hole.
  Plus a STALENESS WATCHDOG: report -1 unless the pump ran within the last N
  ticks (a frozen mirror must never read as live backlog). Plus FLAP
  HYSTERESIS: M consecutive undraining pumps before the switch to -1, and
  resume re-enters through the taper from the live fraction (never a -1 → halt
  step); transitions counted in diag.
- **The halt TIME-BOX (panel MAJOR — the doctrine):** the bridge may PACE the
  stream, never STOP it. The #71 halt was designed for Voxy's UNBOUNDED ingest
  queue (an OOM emergency); the bridge's queue is bounded and self-shedding —
  saturation there is cosmetic loss. At halt with no net drain for ~5-10 s the
  report degrades to -1 (warn once, diag flag) and re-arms below ~50%
  occupancy — a wedged Xaero saver can therefore cost the map, never the
  terrain.
- Empty-and-draining reports 0; disabled / kill-switch / dead / unregistered
  report -1. Thread safety: the poll (≤20 Hz, client tick) reads the volatile
  occupancy mirror maintained under `queueLock` at offer/drain/clear INCLUDING
  the byte-cap eviction branch. Composition: `LSSApi` max() across consumers —
  the worse of Voxy's real backlog and the bridge's scaled signal governs (the
  unit divergence — a governor signal dressed in section units — is recorded at
  the `VoxelColumnConsumer` javadoc as the sanctioned pattern).
- **Kill switch:** `enableXaeroMapBackpressure` (client, default true; inert
  while the bridge is off), under the global `enableIngestBackpressure`. The
  second key is NOT the discoverability story: the Sodium map toggle's tooltip
  gains "the map paces LOD downloads to what it can draw", and `bp=` is
  tri-state (`-1(reason)` / `0` / fraction) so a governed fill is self-evident
  in diag.
- **Wire/server:** none.

### §12.3 Control dynamics (v3.1 — two regimes, panel-rederived)

- **Rig regime (measured: serve 777, writer ~680):** what holds cadence at 1 Hz
  is the ≥95%-ANSWERED gate, not the ¼ gate — a fast fire needs ~95% of the
  batch answered in 250 ms ≈ 2.6k col/s serve rate, unreachable at 777.
  Equilibrium occupancy ≈ 0.75 × (1 − 680/800) ≈ **11%**, stable (per-scan gain
  800/Q₇₅ ≈ 0.36, damped even with two intervals of dead time).
- **Fast-capable regime (store-warm/LAN, serve ≥ ~2.6k):** the system rides the
  ¼-gate boundary as a stable bang-bang — ~250 ms at 4 Hz, ~2-3 s draining at
  1 Hz; mean ~25%, peak ~35% occupancy — far from the drop point, and average
  throughput still converges to the writer rate. Named UX cost: map-on fills
  lose most of the 4 Hz duty (~10%). Warm rejoins are unaffected (up_to_date
  answers move no columns; occupancy stays ~0).
- **Governor couplings (pinned in §12.4):** the taper multiplies the transfer
  governor's burst cap, and at report ≥ ¼ the window-limited credit latch
  disarms (`budget×4 ≥ burstCap×3` fails) — a slow-start join during a governed
  fill holds RAMP longer. A taper below 712 puts the want-set under the
  server's worst-case in-flight bound — harmless (position-keyed, idempotent;
  the #71 Voxy taper already does it), noted against
  `WantSetBudgetInvariantTest`.
- **Cost:** fill converges to writer throughput (~650-700 col/s, ~10-15% below
  the ungoverned 777) — the map completes on the FIRST pass with
  `dropped_overflow` structurally 0. Hybrid couplings: under heavy taper the
  whole batch can be phase-1 (near-first is right while the map catches up);
  the sustained-flight far-starvation and the near-pinned-anchor effects are
  recorded at §2.3/§6.

### §12.4 Tests + live signatures

- Mechanism pins: the END-TO-END WIRING pin (register the real bridge consumer,
  assert the `LSSApi` aggregate returns its scaled report — the lambda-trap
  catcher; TickTest's productionBacklogSupplier pattern); occupancy mirror
  under offer/drain/clear + the byte-eviction branch; clamp; count-dominant
  case (needs a `maxQueueBytes` test seam beside the existing `maxQueue` one);
  full ⇒ report ≥ halt only at ≥75% occupancy (the halt PRECEDES the first
  drop — pinned as an ordering, not a constant).
- -1 rule pins: table-driven — EVERY `pumpLadder` early return ⇒ latch false ⇒
  -1 (so a new early return fails the table, not the field); the watchdog; the
  hysteresis count; resume-through-taper (never -1 → halt in one poll); the
  halt time-box degrade + re-arm; a NEGATIVE pin that a -1 state can never fire
  the edge-triggered empty batch.
- Refusal-while-paused pins **[SUPERSEDED by §12.8 — the refusal is deleted;
  the replacement pins are §12.9's: blocked-keeps-governing, offers-absorbed,
  blocked-overflow-reports, the settings-off pre-extraction drop]**; reporter-kept
  pins: dimension-switch un-stamps via
  `reportStaleDropped`, the world-id bulk clear reports.
- Composition pins: vs a larger/smaller Voxy backlog AND vs a Voxy -1
  (unresolvable probe); global #71 switch off ⇒ bit-identical;
  bridge-on + `enableXaeroMapBackpressure=false` ⇒ pre-amendment behavior;
  the ¼-gate/window-latch coupling both directions.
- Config/diag: `enableXaeroMapBackpressure` round-trip pin (the discipline the
  heal key loses); `bp=` tri-state in the diag census; the census keeps
  `dropped_stale`/`cave_layer_waits`/`skipped_settings`/`dropped_updates`/
  `dropped_unloaded` as the residual-class evidence; `ingest_parked` family
  (ColumnStateMap.ingestParked, the manager getter, the Columns diag token, the
  MAX_INGEST_FAILURES javadoc) resolved keep-and-reword vs delete — nothing may
  be left orphaned; `xaero-map-bridge-plan.md` §18/§18.1 get SUPERSEDED marks;
  `region-scan-plan.md`'s `heal_pending ≈ 0` live signature amended in the same
  change.
- No automated end-to-end gate is POSSIBLE (Xaero is absent from soaks and
  gametests) — stated: unit + the live round IS the whole gate.
- Live acceptance **(cave-layer arm INVERTED by §12.8 — judge against this
  corrected form)**: a full lod-512 SURFACE fill with `dropped_overflow≈0` and
  occupancy riding ≤~30%; a cave-layer phase: the fill HALTS (≤7 s per window,
  then the wedge duty cycle — brief pauses ARE the mechanism working; Voxy's
  columns pause with it, nothing is lost), `bp=` shows a fraction, possibly
  `(blocked)`; the one-region-stall discriminator:
  `regions_waiting`/`load_requests` climbing while dropped stays 0 (the
  region-major far phase concentrates the queue in 1-2 regions — a parked
  region stalls ~100% of the drain, which the §18-era ring-major masked);
  fill rate ≈ writer rate, the ~10-15% slowdown NAMED; bridge off ⇒ ungoverned
  rate restored; Voxy alone unaffected.

### §12.5 Sequencing + release mechanics (panel; two USER decisions)

1. **v0.12.1 (USER DECISION — the panel's release-mechanics MAJOR):** all five
   `v0.12.1+mc*` tags exist LOCALLY ONLY (never pushed), and their notes
   headline the heal ("dropped tiles are now re-requested automatically …
   disable with `enableXaeroMapBridgeHeal`"). RECOMMENDED: fold §12 (this
   round) before pushing, and RE-CUT the five tags with pacing-based notes —
   the same user symptom, the stronger fix, no retraction. Publishing as-cut
   instead requires an explicit retraction record (removed-key note + the
   five-line backport of the removal).
2. **Scope of the heal deletion (USER DECISION, §12.1):** the fold re-scopes
   "drop it entirely" to LEDGER-ONLY, keeping the ~15-line immediate reporter —
   the correctness of residual (c) depends on it. If the user insists on
   deleting the reporter too, residual (c) must be re-classified as a
   permanent-hole class beside (b).
3. §12 ships as its own small round on a branch off `feat/region-scan` —
   separate from the walk (rollback attribution) and after the region round's
   live gate, OR amending `region-scan-plan.md`'s §14.1 signature list in the
   same change. Backports carry §12 + the heal removal as ONE unit across the
   lines (never main heal-less while the ports carry it — those branches
   cherry-pick this file).
4. The hybrid walk ships LAST, per §0 unchanged, with §9's rate gate qualified
   bridge-off.

### §12.6 As-built record (2026-08-24, branch feat/xaero-backpressure off feat/region-scan)

Implemented per §12.1-§12.4 (v3.1). As-built facts:

- **Consumer**: `buildConsumer()` returns an anonymous class overriding
  `pendingIngestBacklog()` → `reportBackpressure()` (the lambda trap closed;
  wiring pin `theConsumerOverridesThePendingIngestBacklogDefault`).
- **Report**: `round(INGEST_BACKLOG_HALT_SECTIONS × min(1, occupancy/0.75))`;
  the halt constant went PUBLIC on `LodRequestManager` (single source of truth).
  Occupancy = `max(bytes/maxQueueBytes, count/maxQueue)` over the SEAM fields,
  clamped, mirrored volatile under `queueLock` at every mutation
  (`updateOccupancyLocked` — offer replace/insert/evict, removeIfCurrent,
  clearQueue). `maxQueueBytes` became a seam beside `maxQueue`.
- **Drainable latch**: `pumpLadder()` is now a wrapper — `pumpLadderInner()`
  returns true ONLY on the path that reaches `drainEntries` (14 early returns
  retargeted mechanically; a throwing ladder counts as undrainable). Hysteresis:
  `BP_PAUSE_PUMPS = 20` consecutive undrainable pumps before
  `pausedForOffers`/`pumpDrainable` flip; one drainable pump clears both.
  Watchdog: `BP_PUMP_STALE_MILLIS = 1000` on a `lastPumpMillis` stamped at the
  top of every live pump; clock seam `bpClock` (LongSupplier field — the class
  is final, an override seam was impossible).
- **The idle-fast-out deadlock guard** (found in design): refusals keep the
  queue empty, and the empty-queue pump fast-out would then skip the ladder
  forever — the fast-out now yields while `pausedForOffers` holds, so the
  ladder run (~10 reflective reads) is what clears the pause.
- **Halt time-box**: at a full report with `written` unchanged for
  `BP_HALT_WEDGE_MILLIS = 7000`, `haltWedged` latches (warn once) → report -1;
  re-arms below `BP_WEDGE_REARM_OCCUPANCY = 0.5`. Wedged drops are SILENT
  (reporting would churn re-serves into a wedged writer).
- **Refusal-while-paused**: `offerColumn` refuses pre-extraction when
  `pausedForOffers && backpressureEnabled` (counter `refused_paused`, no
  report). `offerPrepared` (the test seam / post-extraction path) is not
  re-gated — the race window is one extraction, bounded.
- **Reporting split as specified**: stale-dimension + world-id-change drops
  report UNCONDITIONALLY (`reportDropped` — correctness); overflow/eviction/
  defer-expiry drops report under `reportDroppedIfGoverned`
  (backpressureEnabled && !haltWedged — kill-switch off = pre-amendment
  silence); settings-off and teardown clears never report. The world-id clear
  uses `clearQueueReporting()` (collect under lock, report outside — the §18.1
  lock discipline); reports carry counter `drops_reported`.
- **Deleted**: `DroppedLedger`, `recordDropped(Locked)`, `abandonLedgerLocked`,
  `healPhase`, `rotateLedgerToTail`, `flushLedgerRegion`, `probeRegionForHeal`,
  `reportedHistory`, `ledgerTotal`, `LEDGER_MAX_REGIONS`,
  `LEDGER_FLUSH_PER_PUMP`, `ledgerMaxRegions`, the five heal counters/gauges,
  `clearQueue(boolean)` (arity collapsed), the pump's idle-ledger clause,
  `enableXaeroMapBridgeHeal` + its round-trip pin, ~11 §18 tests. The
  `committedRegions` set in `drainEntries` went with the heal phase.
- **Diag**: `heal_*` tokens replaced by `refused_paused=`, `drops_reported=`,
  and the tri-state `bp=` (`off` / `-1(inactive|paused|wedged|stale)` /
  occupancy fraction); census updated + a negative heal-token pin.
- **Config**: `enableXaeroMapBackpressure` (default true) replaces the heal key
  in `LSSClientConfig`; round-trip pin swapped (incl. a deleted-key
  no-resurface assert); no options-page row (the bridge toggle's TOOLTIP gained
  the pacing clause instead, per the panel's discoverability fold).
- **ingest_parked family**: KEPT, javadocs reworded to the §12 framing
  (`MAX_INGEST_FAILURES` is the report-loop belt; small again under the taper).
- **Tests**: 14 §12 suites in XaeroMapCompatTest (wiring, halt-domain scaling +
  the halt-precedes-drop ordering pin, byte-dominance, hysteresis + refusal,
  the negative paused-never-halts pin, the empty-queue resume/deadlock guard,
  watchdog, wedge + re-arm, governed-vs-silent reporting, stale-dimension
  unconditional, world-id reporting, settings-off non-reporting, defer-expiry
  both flavors, session-teardown reset). Full T1 green; T2 green.
- **Docs**: xaero-map-bridge-plan §18 SUPERSEDED banner;
  region-scan-plan §14.1's three heal signatures AMENDED in the same change
  (the M7 condition for shipping before the region live gate);
  the Sodium tooltip clause; README untouched (its Xaero paragraph describes
  behavior, not the heal).
- **NOT done, deliberate**: no exporter/soak contract rows (no automated
  harness reaches Xaero — unit + live IS the whole gate, per §12.4); the
  v0.12.1 re-cut decision stays with the user (§12.5.1); backports deferred
  (§12.5.3 — as one unit with the lines' heal removal).

### §12.7 Implementation-review fold (2026-08-24, 4-Opus panel)

Panel: mechanism (2 MAJOR), heal-removal/reporting (2 MAJOR), integration/dynamics
(4 MAJOR), tests+docs (6 MAJOR) — 14 MAJORs, heavily convergent (the halt
time-box's two disarm bugs found by three lenses; the global-switch composition by
three; the stale inverted test by two). All folded:

- **Halt time-box rebuilt**: progress RE-BASES the window (the
  equality-against-opening-value check was permanently disarmed by the first
  commit inside the window — a commit-once-then-wedge writer halted LOD for the
  session); every -1 exit CLEARS the timer via `noSignal()` (a timer surviving a
  cave-layer pause fired a FALSE wedge + user-facing warn on the first governing
  poll back). Pinned both directions (`progressReBasesTheHaltWindow…`,
  `aPauseInterruptionClearsTheHaltWindow`).
- **Two-threshold pause** (the transient-gate MAJOR): governance goes -1 at
  `BP_PAUSE_PUMPS` = 20 (~1 s, unchanged — a paused queue must not halt the
  fill), offer REFUSAL now engages only at `BP_REFUSE_PUMPS` = 200 (~10 s):
  Xaero's startup gates (region detection, multiworld resolution, the join
  window, portal transit) clear within seconds and ABSORB into the queue
  instead of shedding ~500/s unreported permanent holes around spawn; only the
  structural pauses (cave layer, map locked, writing off) outlive the
  threshold. The two latch fields are now genuinely distinct (closing the
  redundancy NIT).
- **Global-switch composition wired** (three lenses): the production supplier is
  `enableIngestBackpressure && enableXaeroMapBackpressure` — with the global
  #71 switch off the refusal/report halves go dark WITH the taper (an armed
  reporter with no taper behind it restored the §18.1 churn regime in the one
  config with no governance). `reportDroppedIfGoverned` additionally requires
  `pumpDrainable` (pause-window evictions are silent — reporting into a closed
  gate churns). The kill-switch pin covers report/-1, refusal-off, and bp=off.
- **DEFERRED (region-scoped) is DEFER_CAP-EXEMPT** (integration MAJOR): retained
  like AWAITING_* — the ledger's hold-until-committable semantic via the queue
  itself; a genuinely stuck region freezes occupancy and flows into the wedge
  machinery, which is designed for it. DEFERRED_TILE keeps the cap but its
  expiry is SILENT (counted `dropped_expired`, never reported — a report burned
  the client's 3 ingest strikes at one per cap interval against a stalled
  resource and PARKED the position: the deleted §18 header's own indictment,
  restored by the first cut). A foreign entry behind a deferred sibling is
  retained with the bucket and exits via the stale filter when the region rests
  (pinned).
- **Pump-side reports deferred out of Xaero's monitors** (two lenses): the stale
  filter and the world-id clear COLLECT into `deferredReports`; `pump()` drains
  after the ladder returns — an un-stamp burst (≤ a queue's worth) never runs
  under `renderThreadPauseSync`. The world-id same-dimension churn (~3 k
  re-serves ≈ 4 s, rare) is recorded accepted: the new map needs those tiles.
- **The silent uncounted stale-replacement drop** (offerPrepared's in-place
  foreign-dimension replace) now counts `dropped_stale` + reports — it broke the
  §12.1(c) claim at portals. `dropped_stale=` gains a diag token + census.
- **Session-end race**: `settleSessionEnd` (main-thread half) re-clears
  `pausedForOffers`/`pumpDrainable`/`haltWedged` + drops `deferredReports` — an
  in-flight ladder's finally could re-latch after the off-thread clear.
  `undrainablePumps` also resets on the ladder-skipping pump exits (dead, idle)
  so "consecutive" means consecutive.
- **The stale doctrine-inverted test deleted** (two lenses):
  `theConsumerDoesNotOverrideThePacingGauge` asserted the pre-§12 doctrine and
  passed VACUOUSLY via the new watchdog (setUp never pumps); the class javadoc's
  matching bullet rewritten. The old `busyRegionDefersAndTheCapEventuallyDrops`
  (the reversed DEFERRED-cap doctrine) replaced by the retention pins;
  `aCrossDimensionServeReplacesTheStaleEntry` updated to the counted semantics.
- **Test hardening**: `bpClock` is set deterministically in `@BeforeEach` (12 of
  14 suites raced the 1 s watchdog against wall time — this repo's catalogued
  flake family); the teardown pin keeps its gate armed across the reset (it was
  vacuous); the pre-gate overflow path has its own pin (the arriving-vs-oldest
  asymmetry recorded); bp= is tri-state-pinned; suite count now 20.
- **Docs corrected** (tests+docs lens): bridge-plan design item 8 (the normative
  "never overrides pendingIngestBacklog" contract) AMENDED — the SUPERSEDED
  banner at §18 did not reach it; README's cave-layer sentence rewritten (writes
  are DISCARDED, not "waited", + the pacing note); region-plan §14.1's own
  Xaero-heal bullet amended and §10's fill-rate gate qualified BRIDGE-OFF;
  the tooltip's "completes without gaps" softened + the pause class named; the
  VoxelColumnConsumer javadoc records the sanctioned scaled-signal pattern;
  ingestParked's javadoc un-narrowed (provenance-free); CLAUDE.md's bridge
  paragraph gains the §12 facts; the five v0.12.1 release-tag files carry
  SUPERSEDED headers (the tags themselves stay the user's §12.5.1 decision).
- **Recorded deviations** (tests+docs m16): resume-from-pause reports the LIVE
  fraction — which IS the halt when the queue latched full; deliberate (a full
  queue warrants one halt cycle; refusal-freeze keeps occupancy honest).
  `drops_reported` counts ATTEMPTS (correlate with `ingest_parked=` to detect a
  saturated reporter). The wedge's silent-drop class added to the §12.1
  taxonomy: (d) wedge-degraded drops are silent by design — the map wears the
  loss of a wedged writer.
- Verified-clean by the panel: the 75% mapping edge arithmetic, the 14+1 return
  rewrite, mirror coverage, sign-correct unpacking, deletion completeness
  (zero surviving ledger references in code/tests/resources), the reporting
  split site census, the full self-heal chain incl. the foreign-dimension
  `removeAsync` route, LSSApi composition (LSSApiBacklogTest owns it), zero
  wire/server change, and §12.3's equilibrium arithmetic (¼ gate = 18.75%
  occupancy under the 75% mapping — the cycle-mean ~25% claim is consistent).

### §12.8 The blocked-pump amendment (2026-08-24, live-diagnosed + as-built)

**The finding (first live session, 8m34s at 731 col/s):** the §12.6 taper never
engaged while 68k tiles went missing. Movement-driven Xaero contention (native
writes of vanilla chunks around the moving player + region loads holding the
renderPause/busy-region/cave-layer gates) failed the pump ladder for
multi-second episodes; §12.6's doctrine then made everything worse in three
compounding ways: (1) at 20 undrainable pumps the report went **-1 = no
signal** — the brake released exactly during contention, and the diag's
`bp=0.01` sampled only the calm between bursts (the aliasing hole); (2) at 200
pumps §12.1(b) **refused offers pre-extraction, silently** — `refused_paused=
56,139`, 4.3× the visible overflow drops, all permanent map holes by design;
(3) `reportDroppedIfGoverned`'s `pumpDrainable` conjunct silenced ~11.8k of the
12.9k overflow drops too (12,932 − 1,156 reported = 11,776 — a conservative
floor, since stale-dimension reports also count into `drops_reported`). The
headline "68k tiles missing" = 56,139 refused + 12,932 dropped − 1,156
reported-and-re-served = 67,915. The old review pin "paused + full queue = -1, never
the halt" was load-bearing for the failure.

**The amendment (all client-side, XaeroMapCompat only):**
- **A blocked pump with a live watchdog REPORTS.** `reportBackpressure` drops
  the `pumpDrainable` silence; -1 remains only for the honest no-signal states
  (kill switch, inactive/dead, watchdog-stale, wedge-degraded). The queue is
  the pressure gauge: as a burst accumulates, the report escalates taper → halt
  (75%), throttling the stream DURING the contention.
- **Offers are never refused.** §12.1(b) is deleted (`BP_REFUSE_PUMPS`,
  `pausedForOffers`, `refused_paused` all removed); the queue absorbs bursts —
  which is also what makes the occupancy signal real — and the cap is the only
  shed point. The pre-extraction cap check (CPU guard) is unchanged.
- **Blocked-not-wedged drops report.** `reportDroppedIfGoverned` keeps only the
  kill-switch and wedge conjuncts. No churn loop: the halt the blocked pump is
  simultaneously reporting defers the re-declaration, so the re-serve lands in
  a draining queue after the burst. Wedge-degraded drops stay silent
  (doctrine (d) — there the stream IS flowing against a stuck writer).
- **The halt time-box is the anti-stall protection** the -1 doctrine pretended
  to be: a structural pause holds the halt at most `BP_HALT_WEDGE_MILLIS` (7 s,
  progress-rebased), then degrades to -1 with the once-warn and the map wears
  the loss. The wedge re-arms below 0.5 occupancy as before.
- `pumpDrainable`/`BP_PAUSE_PUMPS` survive as the flap-hysteresis + diagnostics
  latch only: the `bp=` token shows `<fraction>(blocked)` while down (replacing
  `-1(paused)`), and the idle fast-out deadlock guard is rekeyed on it.
- Tests: the three refusal-era pins rewritten to the new doctrine (the full-queue
  negative pin deliberately INVERTED — recorded in-test), the interruption pin
  rekeyed to the stale-watchdog interval (the surviving -1 class), new pins for
  burst-escalation-to-halt, blocked-overflow-reports, wedged-drops-silent, and
  the `drainableForTest` seam replacing `pausedOffersForTest`.
- Expected live signatures: `bp=` shows a real fraction (often `(blocked)`)
  during movement; `dropped_overflow` near zero; `drops_reported` covers every
  same-dimension governed drop (the by-design silent classes remain: expiry,
  settings-off, teardown clears, wedge-window sheds — so `drops_reported` <
  `dropped` totals is expected, §12.9); no `refused_paused` token (deleted);
  brief LOD-fill pauses (≤7 s per window, then the §12.9 duty cycle) during
  heavy map contention are the mechanism working, not a stall — and the
  fast-rescan cadence legitimately drops to 1 Hz during those episodes (the
  ¼-halt gate closes on real reports now).

### §12.9 §12.8 implementation-review fold (2026-08-24, 3-Opus panel: control-loop / deletion-surface / tests-docs)

Verdicts: control-loop 2 MAJORs; deletion-surface 2 MAJORs (one shared);
tests/docs 1 MAJOR (shared) — 3 unique. The §12.8 inversion itself, the no-churn
ordering (drop points sit ABOVE the halt point, so every reported drop lands on
an already-halted client), signal composition (≤0 uniformly no-signal at all
three manager rungs), thread safety (the cross-thread surface SHRANK), and the
deletion (zero surviving symbols, complete lifecycle resets, the empty-queue
ladder provably reaches drainable) all verified clean.

- **MAJOR A (one-way wedge)** — the occupancy-0.5 re-arm was unreachable under
  sustained arrival ≥ drain (731/s in vs ~680/s out pins occupancy at 1.0): one
  >7 s pause cost the whole session's governance with every subsequent drop
  silenced. FIX: `BP_WEDGE_REARM_MILLIS` (10 s) — the wedge re-arms on the
  clock OR the occupancy floor, turning a persistent pause into a bounded duty
  cycle (≤7 s halted / 10 s released); the halt re-engages on a still-full
  queue and the writer out-drains a silenced stream. The wedge warn is
  60 s-rate-limited (it is cyclical now). Pinned:
  `theWedgeReArmsOnTheDutyCycleClockUnderSustainedArrival`.
- **MAJOR B (trickle-writer hold; both panels independently, R2 with the
  sharper trigger — a parked DEFERRED region is cap-EXEMPT and the drain
  rotation's strays tick `written`)** — the written-delta re-base held the fill
  at a DEAD STOP indefinitely ("may pace, never stop" violated with the sign
  flipped). FIX: the window re-bases only on an occupancy RECESSION ≥
  `BP_HALT_PROGRESS_EPS` (0.05) from the in-window PEAK (the landing tail
  raising occupancy only raises the peak); a commit offset by an arrival is NOT
  progress. §12.7's fix is preserved (a genuinely draining writer re-bases /
  exits through the report-below-halt branch). The old
  `progressReBasesTheHaltWindow…` pin — which encoded the forbidden behavior —
  is REWRITTEN as `aRecedingQueueReBasesTheHaltWindowAndATrickleCommitDoesNot`
  with the trickle-commit-then-wedge arm.
- **MAJOR C (stale doctrine records)** — CLAUDE.md's bridge paragraph rewritten
  to the §12.8/§12.9 facts (it still published the -1-on-blocked + 10 s-refusal
  doctrine — the backport hazard); region-scan-plan.md's live-signature list
  drops `refused_paused`; this plan's §12.1(b)/§12.2 -1 rule/§12.4 refusal pins
  carry SUPERSEDED markers, the §12.4 cave-layer acceptance arm is INVERTED in
  place, and the header is v3.2.
- **Settings-off extraction tax** (all three panels): the deleted refusal's one
  legitimate job restored as a targeted flag — the ladder's settings-both-off
  branch sets `settingsWritesOff`, `offerColumn` drops pre-extraction (counted
  `skipped_settings`, silent — the user turned the map's writes off), cleared
  when the switches return / at session settle. Pinned in the extended
  settings-off test.
- **De-vacuumed pins** (tests lens): the deadlock-guard pin now actually
  reaches the blocked+idle fast-out (flush the owed rebuild, clearQueue) and
  additionally pins the §12.9 ~1 Hz blocked-idle ladder throttle (each run
  takes Xaero's renderPause/mainStuff monitors — R1 NIT); the teardown pin
  asserts `undrainablePumpsForTest()==0` after settle (deleting the m5 reset
  reds it); the flap hysteresis got its own pin
  (`theLatchWaitsOutTheFlapHysteresis`); the bp token pins are exact-string
  (`", bp=0.00(blocked)"`) with a no-suffix negative on the drainable path; the
  census gains the `refused_paused` ABSENCE assert.
- **Residual drop taxonomy (b')** — the by-design silent classes after §12.8:
  wedge-window sheds (doctrine (d)), tile-deferral expiry, settings-off clears,
  teardown/world-going-away clears, `dropped_updates`/`dropped_unloaded`, and
  a throwing drop-reporter (contained). `drops_reported < dropped` totals is
  therefore the EXPECTED live shape; the strike-burn correlate
  (`drops_reported` vs `ingest_parked` — 4 drops of one position parks it for
  the session) is a live-gate leg, not a failure.
- **Recorded, not changed**: the byte-regime landing room is ~185 tiles (48 MiB
  / ~68 KB ocean tiles at 25% headroom) vs a worst-case ~1,700-column in-flight
  tail — overlay-heavy halts CAN overshoot into reported (healing) drops; the
  count regime absorbs ~2,048 vs the same tail. Raising `MAX_QUEUE_BYTES` is a
  memory trade deferred to live evidence. `bpToken`'s wedged-before-stale order
  deliberately mirrors `reportBackpressure` (R2's consistency argument beats
  R1's triage preference). The disabled-mid-session ladder-skip exit now resets
  the hysteresis chain (R2 NIT). The kill-switch offer-acceptance arm is
  vacuous-by-universality and kept as a comment-noted tautology.
- Suite: XaeroMapCompatTest 109/0 (was 107 — +wedge-duty-cycle, +hysteresis;
  the recession rewrite replaced the progress-rebase pin 1:1).

**§12.9 addendum — live render-thread profile (2026-08-25, spark N9Vpz1sjA7, 51 s @ 4 ms sampling, mid-fill).**
ALL of dev.vox.lss inclusive = **480 ms = 0.95%** of the Render thread (reflective
Xaero work under our frames counted as ours). Split: the frame hook 268 ms
(frameFlush→rebuildTileChunk — 264 ms of it is Xaero's own updateBuffers/
getPixelColour/biome-tint recolor arithmetic the map would run for these tiles
anyway; ≈0.09 ms/frame average, inside the 2 ms budget), the tick hook 208 ms
(pump 176 ms — drainEntries/commitEntry/commitPixels ≈ 150-170 ms, again mostly
Xaero setTile/RegionTexture work; ≈0.17 ms/tick), payload receive 4 ms (decode is
off-thread). **The entire want-set machinery is 32 ms/51 s = 0.06%**: hybrid
`RegionScanner.scan` 20 ms, `rectNeedsFree` 8 ms, movement/prune 8 ms, sends
4 ms; `reportBackpressure` is below the sampling floor. Context: the thread is
79% GpuSurface.present (GPU/vsync wait); Xaero's OWN minimap rendering is 9.17%
(3.2 s handleRenderTick + 1.2 s MinimapPipRenderer) — 10× our whole footprint
and present without LSS. Known residual: single-rebuild spikes to ~35 ms exist
(the session diag's rebuild_max_us) — rare enough not to appear at 4 ms
sampling; the budget bounds the sustained rate, not the single worst recolor.

## §13 Walk as-built record (2026-08-24, branch feat/hybrid-scan off feat/xaero-backpressure)

§2-§10 implemented as specified; deviations and concretions below. One jar now
carries the full plan (hybrid walk + §12 backpressure), per the user's explicit
override of §0's ship-§12-first gate ("do the entire plan").

- **`RegionScanner.scan()` two-phase rewrite.** `HYBRID_NEAR_RADIUS = 64`
  (test seam `hybridNearRadius`); `lod` and `N = min(seam, lod)` hoisted once
  per scan. Phase 1: labeled loop over rings r₀+1..N in legacy
  `ringIndexToCoord` order — `ringNeedsFree` probe first (gate-independent,
  counts `quadRingSkips`, observe cost r+4 = the probe's actual perimeter
  lookups), needy rings pay 8r + per-position emit; a budget break sets
  `truncated`, `phase1Broke`, counts the ring into `nearRings`, and phase 2
  never runs. **§2.3/§3 deviation (fold-recorded):** the first past-budget
  UNRESOLVED position breaks the WHOLE walk — there is no emit-free observation
  sweep of the remaining rings, so §3's O(8r·remaining) post-budget charge does
  not exist as-built (this is what keeps a cold-fill phase-1 break inside the
  4 Hz meter — the opposite of §3's prediction; do not "restore" the sweep).
  Phase 2 gated on `!phase1Broke && N < lodDistance`: the region spiral emits
  per-region residue only; per-rect probe charge = the rect's leaf-bbox count.
  One recorded pricing corner: a walk whose budget is consumed exactly at the
  seam and then truncates at its FIRST needy phase-2 region leaves
  maxEmittedRing ≤ N, so the movement window prices the near frontier while the
  next walk re-sweeps the region spiral (~1 ms of leaf probes; the stationary
  branch meters it honestly) — accepted, comment at the break site.
- **`wholeExcludedRings(vd)`** — seeded at `vd/√2 + 1`, stepped to the largest
  r with `2(r−1)² < vd²` (the corner test against the 1-buffered Euclidean
  exclusion `adx²+adz² < vd²`); brute-pinned for vd 0..40 against the real
  `isVanillaRendered` shape.
- **`residueRects(area, near, out)`** — left/right full-height strips + middle
  top/bottom strips; disjoint → the whole rect; contained → 0 (region skipped
  outright — the lod ≤ 64 degeneracy is exact). Scratch `int[4][4]` reused,
  no allocation. 2000-trial brute pin: exact cover, pairwise disjoint.
- **`ColumnStateMap.rectNeedsFree(x0,z0,x1,z1)`** — leaf-granular over the
  rect; absent leaf = needs; partial-leaf overlap answered conservatively
  (needs-anywhere-in-leaf → walk). `regionNeedsFree` is DELETED (fold — its
  lod-intersection job lives in the residue rect bounds; the SectionStateFuzz
  differential now sweeps `rectNeedsFree` over leaf-aligned region rects).
  Phase-2 entry/probes tracked via the package-private session-cumulative
  fields `phase2Rounds`/`phase2Probes` (test seams, no getters; both reset at
  session AND dimension resets).
- **`near_rings`** (§7 REQUIRED) — phase-1 rings that emitted or observed needy
  work; wired through diag Scan line, trace `scan` event, exporter
  `scan.near_rings` (+ contract row — NOTE the contract file is
  ALPHABETICALLY sorted; the row sits between `scan.missing_vanilla` and
  `scan.quad_ring_skips`, and an out-of-order insert fails
  ExporterContractTest).
- **Tests** (§8): RegionScannerTest 27/0 — re-homes as predicted (small-lod
  legacy-order degeneracy at lod 24; far-fill residue order at lod 96; census
  structural pins; movement chaos extended to mixed lods {24,96} × 4 seeds,
  90-iteration convergence), new pins (phase-1-order-equals-fresh-legacy under
  both quad variants; budget-break-skips-phase-2 with `phase2Probes == 0`;
  observe-cost-meters-emitted-work; the two brute pins above).
  RegionScanDifferentialTest 3/0 — dynamic `bufSize(lod)`, lods
  {2,8,24,33,40,63,64,65,68,80,96,130} (63-68 = the N=64 phase-flip band,
  fold-added), chaos alternating 24..43/70..109 by seed parity, and the
  lod ≤ 64 ORDERED-sequence arm (covers 63/64 — the seam order itself).
  `ColumnStateMapTest` gains the rectNeedsFree granularity/conservatism pin.
  Full T1 green, T2 green.
  **Complete-prefix re-home deviation:** the ≥2-group premise fails for an
  origin-centered player (the first far sliver is 992 cells ≥ the 800 budget)
  — the pin now runs off-center at (16,16) with a local brute over
  |x−16|/|z−16|. **Movement chaos** runs a 4-element (seed, lod) ZIP —
  {(3,24),(11,24),(77,24),(5,96)} — not a cross product; the ring-order chaos
  gains its own lod-96 seed (fold).
- **Boundary soak** (§8 item 10, resized by the fold — M1/M2): scenario
  `hybrid-boundary` — lod 72 fresh fill, tp to (500,150,500) at t+4 s (the fill
  ORIGIN sits at region (0,0)'s far corner, the §1 artifact geometry — NOT a
  mid-run re-center), save-all@1020, end@1080. Sizing: the annulus is
  (2·72+1)² − 17² = 20,736 columns at the measured ~30 gen/s admission pace
  ≈ 690 s, leaving a ~390 s settle tail (§8 item 10's prebuilt/disk-served
  premise was inverted to fresh-generation as built — recorded; the original
  540 s/lod 88 cut was red-by-construction at ~1,015 s of fill).
  `check_hybrid_boundary` (gen.completed > 15000 as the fill PREMISE, quiescent
  tail, scan.confirmed > 72) + `make_disc_completeness` — the area floor is the
  SEAM-HOLE discriminator (an unobserved ring-65 hole FALSE-CONVERGES
  scan.confirmed; only disc completeness reds). Registered in
  CHECKS/MIN_CLIENT_WINDOWS/ANOMALY_OPT_INS ({"saturated"}), 5 selftest cases
  (270 total), soak.sh FRESH_WORLD_SCENARIOS **and ALL_SCENARIOS** (M1 — the
  gate runs in `soak.sh all`; CLAUDE.md's count is 23). First live run still
  pending.
- **Docs:** CLAUDE.md's three scanner sites (want-set paragraph, the
  RegionScanner bullet, the `enableRegionScan` key note) now describe the
  hybrid walk; `ring_skips=` legend covers both arms.

### §13.1 Implementation-review fold (2026-08-24, 3-agent panel: geometry / integration-cadence / tests-docs)

Verdicts: geometry 0 MAJORs (1 MINOR, 5 NITs); integration 0 MAJORs (6 MINORs,
2 NITs); tests/docs 3 MAJORs (9 MINORs, 10 NITs). Every finding folded or
recorded; the walk itself survived both adversarial lenses untouched — all
three MAJORs were gate/test defects.

- **M1 (gate never runs)** — `hybrid-boundary` added to `ALL_SCENARIOS`
  (was FRESH_WORLD_SCENARIOS only); CLAUDE.md scenario count 22→23 + the
  scenario named in the "Further scenarios" enumeration.
- **M2 (gate red-by-construction)** — resized lod 88→72, end 540→1080 s,
  save-all 480→1020 s; thresholds re-derived (gen > 15000, confirmed > 72);
  the §8-item-10 prebuilt→fresh deviation and the disc-completeness-is-the-
  discriminator doctrine recorded in the check's docstring and §13.
- **M3 (vacuous span pin)** — `steadyFillKeepsSpanAtMostTwo…` re-homed to
  lod 128 with the near-square-containment `allFull` predicate, a
  `sawFullSpanFire` vacuity guard, and 32-slack on sliver fires (the +lod edge
  clips to 1-wide slivers — 2·lod+1 is never 32-aligned).
- Meters made honest (geometry-MINOR/integration-n8/n9): phase-1 probe charge
  r/4+4 → r+4; phase-2 flat 16 → per-rect leaf-bbox count.
- New pins: `phase2Rounds` degeneracy/break seams (m1), the deep-phase-2
  movement REFUSAL + the converged-lod-512 both-arms delta pin (m2 — §8 pin
  8's missing arms), the walked-cells meter pin (m5 — sparse-needy scatter,
  walked > 4×emitted premise), the phase-2 satisfied-tail §2.3 carry twin at
  lod 96 (m8 — rides the natural +edge-sliver conservative-probe shape), the
  hybrid quad-off order arm (n4) with audit-off fixture hygiene (n10),
  `wholeExcludedRings` brute extended to vd 80 (geometry NIT), differential
  lods += {63,64,65,68} (m3/n5 — the phase-flip band).
- `regionNeedsFree` DELETED (dead since the rewrite; CLAUDE.md's stale credit
  fixed both sites); the SectionStateFuzz differential retargeted to
  `rectNeedsFree` over leaf-aligned region rects; the two direct pins dropped
  in favor of the live-probe granularity pin.
- Diag legend repaired (integration-m3/tests-n7): the orphaned `valve`
  parenthetical restored to its token, `near_rings` given its legend with the
  hybrid-arm ring_skips caveat (unconditionally large there — NOT a
  dirty-dispersion signal).
- Records: LodRequestManagerTest's lod-64 twins marked phase-1 pins (m6);
  region-scan-plan.md §14.2 supersession note (m6); massDirtyScatter's
  N-coupling comment (n2); §13 corrected in place (t+4 s tp, seam-pricing
  corner, field-not-getter, ZIP-not-cross-product, §8-item-10 numbering, the
  §2.3/§3 no-observation-sweep deviation) (n1/n3, geometry-2/5).
- Accepted as-is: the differential's 64-entry buffer slack (n9 — AIOOBE still
  reds), `wholeExcludedRings` analytically exact beyond the brute bound
  (geometry-6), phase-1's stronger-than-plan short-circuit at lod ≤ N
  (geometry-4 — now discriminated by `phase2Rounds`).

**§13.1 addendum — soak gates run (2026-08-24).** Two soak.sh registration
points the M1 fold missed (neither I nor the panel knew the dispatch shape):
the scenario-validity `case` at soak.sh:171 and the CLIENT_RUNS/EXPECTED_SECONDS
table at :220 are SEPARATE from ALL_SCENARIOS — both now carry hybrid-boundary
(a new-scenario checklist fact: FOUR registration points in soak.sh + three in
check_soak.py). fresh-backfill: first run redded the catalogued A7 environmental
shape (disk.errors +10, zero log lines, A5 silent), re-run PASSED clean.
hybrid-boundary run 1 (end 1080, 5/5 pool): red at 62% fill — the lod-24 burst
rate does not hold at scale (20.8/s → ~12/s sustained under a 289-timeout A7
storm wedging >half the 5-thread reader pool while generation slots idled);
resized to end 1800 s + an 8/8 reader pool. hybrid-boundary run 2: **every
hybrid leg GREEN** — 88 quiescent snapshots, 81 client-law windows, disc
completeness 20,768 ≥ 20,736, confirmed 73 = lod+1, gen 20,221 (19k at
t+1173 s, 12.1/s avg), and the §7 live signatures exact (near_rings 3 mid-fill
→ 0 converged, audit_heals 0, region_skips 4,258, quad_ring_skips 68,558 —
unconditionally large on the hybrid arm as documented). One violation:
**A7, accepted-with-record** (disk.errors 556, ZERO "Failed to read chunk" log
lines = all timeouts — the catalogued environmental storm on this degraded
WSL2 box, same day as the two accepted B2 clock-step artifacts; the fill
converged through it, which is what the resize bought). Artifact dir
`soak-results/hybrid-boundary-20260824T233947Z`. On a healthy box A7 here
should not fire — a recurring healthy-box A7 in this scenario is a real
question, not this record.
