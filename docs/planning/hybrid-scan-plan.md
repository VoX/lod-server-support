# Hybrid scan: ring-major near, region-major far (v2 — panel-folded)

**Status: PLANNED, NOT EXECUTED** (user directive 2026-08-24: write + review only).
Follow-up to `region-scan-plan.md` (§14/§14.1 = the region round's as-built + review
records). **v2 folds the 4-agent plan review** (3 Opus: design/geometry,
cadence+server+boundary, tests+consistency; 1 Fable: holistic/premise — §11 is the
disposition record; 13 MAJORs, all folded). A final Fable review of THIS revision is
the last gate before the plan is considered settled.

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
  order pins — a box scan + sort would change intra-ring order), from
  `r₀ = ⌈vd/√2⌉` (rings below r₀ are wholly vanilla-rendered by construction —
  skipping them saves 4·r₀(r₀+1) cells/scan, 2,208 at vd 32; r₀ recomputed live,
  view distance is dynamic) up to N. Per ring: `ColumnStateMap.ringNeedsFree`
  FIRST (existing leaf probe, center-parameterized — ~r+4 leaf lookups, NOT r/8),
  skipping satisfied rings wholesale; needy rings pay the 8r per-position pass
  (exclusion skip + classify ladder identical to phase 2's). Ring 0 stays empty
  (the near clamp `ring ≤ N` in phase 2 subsumes the ring-0 parity skip — do not
  also delete or duplicate the guard). Phase 1 does NOT write
  `lastExclusionRadius` (keeps the inherited F1 shrink rung inert, as on the
  region arm). NOTE the never-skippable band: rings r₀..~vd+8 contain leaf-less
  excluded positions (absent leaf = all-needs), so they pay their 8r pass every
  scan forever — priced into §3, and the reason `ring_skips` reads ~0 at soak
  lods (record beside §14.1's region-skip equivalent; not a defect).
- **Phase 2 — far regions:** the region spiral, classified against the
  **lod-clamped RESIDUE** (panel MAJOR ×3 — the v1 raw-rectangle classification
  falsified the degeneracy claim and double-walked the boundary band):
  `residue(region) = (region ∩ lodSquare) \ nearSquare`, which decomposes into ≤ 4
  axis-aligned rectangles (both squares are axis-aligned Chebyshev balls).
  - residue EMPTY → the region is ignored outright: **no `regionNeedsFree` probe,
    no emit pass, no counter** (like a beyond-lod region). At lod ≤ N every
    region's residue is empty ⇒ phase 2 performs literally nothing — the
    degeneracy claim, now true and pinnable.
  - residue non-empty → `regionNeedsFree` probe restricted to leaves intersecting
    the residue; on needs, the emit pass runs over the residue rectangles ONLY
    (their loop bounds ARE the near+lod clamp — no per-cell near test, no
    double-observation of phase-1 territory).
  The counting sort, scratch, and emission conventions are unchanged.

Order = phase 1 rings ascending, then phase 2 region-spiral. Exactly-once holds by
strict partition (panel-verified exact: both zones are axis-aligned rectangles about
one center; no rounding, no metric mismatch; negative coords floor-shift clean).

### §2.2 What each regime gets

- **Near:** fill ORDER identical to a FRESH legacy walk (the panel qualifier: the
  shipped legacy arm carries prefix retention/reopened bitsets — phase 1 matches
  the from-scratch enumeration, which is also what the differential's A-7 validity
  condition compares). Declaration head = true nearest work inside N.
- **Far:** the region walk verbatim — complete-prefix, span ≤ 2 dense (with
  §14.1's clipped-edge caveat), Xaero collapse, server locality. The near zone
  GIVES BACK region-locality (~20-25-region interleave during near fill — bounded,
  short, page-cache-friendly; named per the panel's honesty ask).
- **Small lod (≤ 64):** phase 2 provably does nothing; the walk is a stateless
  legacy-ORDER scan. NOT "strictly stronger" than legacy (panel carve-outs:
  `lastWalkTruncated` keeps the region arm's needy-work-remains convention —
  §14.1's pinned divergence; cadence prices differ — observe-meter memory vs
  legacy prediction; diag differs). Order equality is the pinned claim.
- **Cold join at lod 512:** near disc ≈ 16.6k raw / ~12.4k after exclusion;
  convergence is bounded by SERVER throughput, not scan count (batches re-declare
  the unanswered prefix) — ~17 s at the rig's measured ~725 col/s, ~4-5 s at the
  3,200 col/s ceiling. The first FAR column waits behind that (see §2.3 —
  deliberate, and recorded so the live round doesn't misread it).

### §2.3 Prioritization = nearest-first restored (and its cost named)

Phase 1 emitting first is a HARD reservation of the budget for near work. This is
not new policy — legacy was globally nearest-first, and the far zone always waited
behind near; the REGION arm was the anomaly (a region's far corner before a nearer
neighbor). Two recorded consequences:
- **Far-fill latency vs the region arm regresses**: first far column ~1-2 batches →
  ~16-21 batches on a cold join (the flip side of "the first ~20 s look like
  legacy"). The live comparison must expect it.
- **Sustained flight**: each crossed chunk makes 2N+1 near positions declarable
  (+2vd+1 view-exit re-asks) — at N=64 ≈ 400 col/s ≈ 55% of a 725 col/s stream
  reserved for near. The far crescent falls behind — the documented elytra wall,
  unchanged in kind. This reservation is THE ceiling argument against raising N
  (§10). A far-fairness budget reserve is structurally incompatible with the
  complete-prefix invariant and is rejected (panel-settled).
- Complete-prefix, hybrid form (STRONGER than v1's wording): at most ONE partial
  group across BOTH phases — a phase-1 budget break means phase 2 contributes
  nothing; a phase-2 break means rings r₀..N are complete. (Modulo the needs
  invariant, as always — the audit belt owns divergence.)
- `lastWalkTruncated` convention (panel MAJOR-adjacent, feeds the governor latch
  AND movement pricing): the ARM's convention, both phases — set only when NEEDY
  work provably remains: a mid-emission break anywhere sets it via the same
  scan-on-past-budget probing the region arm does (phase 1 continues ring probes
  emit-free; phase 2's needy-region guard unchanged); the exact-fill
  satisfied-tail case stays FALSE (§14.1's pinned divergence, carried forward and
  carved out of the order differential exactly as it is carved out of the set
  differential).

## §3 Cost model and cadence (panel-rederived — v1's three terms were all wrong)

- **Converged phase-1 floor** (N=64): rendered-ring skip (r < r₀) + never-skippable
  band (r₀..~vd+8, full 8r passes) + probed rings (~r+4 each): ≈ **3.7k at vd 12 /
  7.9k at vd 32** — not v1's ~2k. Offset: the wholly-inside skip removes phase 2's
  emit passes over the player's 3×3 block (1k-9.2k on the region arm, which can
  never skip them) ⇒ **net steady-state meter delta ≈ −1.3k…+1k vs the region
  arm** — "about a wash", not "cheaper by construction".
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
  is never 95% answered in 250 ms at real fill rates); the exposed case is
  warm-rejoin-while-moving (bulk up_to_date answers ⇒ sustained 4 Hz declarations
  ≈ 51 KB/s upstream — declaration traffic, not the 50-75 MB/s regime). The
  price is BIMODAL on whether phase 2 emitted (16,640 vs 4·s(s+1) at the far
  frontier) — a moving client oscillates 4↔1 Hz with the phase mix; expected in
  live traces, not a bug to chase. Pinned in §8; if live traffic says otherwise
  the fallback is pricing s = lod whenever the walk never reached phase 2.
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
reservation. Far: unchanged. `nativelyWritable` near tiles are Xaero's own writer
anyway. Steady near working set ≈ 25-36 resident regions with legacy-style graze
churn INSIDE N (the region plan's §1 churn complaint applies there again — bounded,
accepted, named).

## §6 Server interplay (reworded from v1's "strictly orthogonal")

The hybrid TIGHTENS the frontier anchor: declared head inside N = true nearest
work, ≤ the region arm's anchor by up to ~31 rings — always the tightening
direction, and tightening costs: an anchoring near event pulls the window inward
and the far band reopens at 333 ms/ring ⇒ up to ~N/3 ≈ 21 s of added far-generation
stall per event. BOUNDED BY the acquisition-frontier rule (the good news v1
missed): unsatisfied ts>0 entries do NOT stamp — so ordinary dirty broadcasts on
known columns cannot collapse the window; the anchoring events are only ts≤0
acquisitions (view-exit crossings under movement, ingest-failure re-asks, prune
holes, dirty-revived NOT_GENERATED). Near-zone damp never binds (full 8r rings).
The far ceiling itself (~96 col/s/player where gen is faster than ~417 ms) is
unchanged; the live gate re-measures it under the hybrid.

## §7 Config / diag / trace (v1's "add nothing" REVERSED by the panel)

- No new config key; `HYBRID_NEAR_RADIUS` constant + seam.
- **`near_rings=` is REQUIRED** (phase-1 rings walked last scan): diag Scan line,
  trace `scan` event, exporter `scan.near_rings` + contract row. It is the round's
  only in-band instrument — `region_span=0` alone is triply ambiguous (near fill /
  converged / phase-2 wrongly idle) and the region round trained operators that
  span is the fill gauge.
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
  four suite tests using it move to geometries where both phases emit (lod ≥ 80).
- `firstWalkDeclaresTheWholeAnnulus…`, `emissionIsACompletePrefix…` (lod 24):
  re-homed to lod ≥ 80 with a both-phases premise (near pre-satisfied + needy far
  spanning ≥ 2 regions — at lod > 64 from empty, phase 1 alone out-holds the
  budget and phase 2 never emits on a first walk; the premise must construct it).
- `steadyFillKeepsSpanAtMostTwo…`, `boundaryRegionsClampPerPosition…` (lod 40):
  now single-phase — re-homed to lod ≥ 80 shapes.
- `needsFreeRegionsSkipWithoutAnEmitPass` (9/18 census) + `resetClears…`
  (`skips>0` premise) at lod 40: re-derived at a lod > 64 geometry against §7's
  probe-skip-only counting.
- `exactFillEndingInSatisfiedTail…`: carried (the convention is §2.3's).
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
4. Degeneracy pin, implementable form: at lod ≤ 64, phase 2 performs ZERO
   `regionNeedsFree` calls and zero emit passes (probe-count seam), not
   "region_skips == 0".
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
    end-to-end gate):** a new scenario at lod 80-96 over a prebuilt/warm world
    (the disk-read-gate annulus or warm-rejoin staging pattern; ~26-37k columns,
    disk-served, ~60-90 s), checked by convergence (`scan.confirmed = lod+1`),
    the order-blind disc-completeness law, and quiescence. Everything else in the
    fleet degenerates to phase 1 by design.

## §9 Rollout

`feat/hybrid-scan` per §0. Gates: T1/T2, fresh-backfill + the new boundary soak +
`soak.sh all` (regression only), the live dev-jar round with signatures: near fill
visually concentric at a region corner (the artifact §0's precondition observed,
now absent); `near_rings=` active during near fill then ~0; far fill
`region_span ≤ 2`; Xaero dropped flat; fill rate ≥ the region round's LIVE numbers
(aggregate) with the §2.3 far-first-column latency expected; the §6 generation-
bound fill-rate measurement RE-RUN. Docs task (was missing in v1): CLAUDE.md's
three scanner sites (want-set paragraph "REGION-MAJOR by default", the
RegionScanner bullet, the config-key note), the §14.1 coverage-policy amendment,
and a release-notes item for the visible fill-pattern change. Backports follow the
region round's user-deferred schedule.

## §10 Boundary derivation (panel-rewritten; KEEP 64)

1. **Containment proof (the real rationale):** N ≥ 63 contains the player's whole
   3×3 region block for EVERY intra-region offset o (max Chebyshev to the block =
   max(o+32, 63−o) = 63) — the §1 artifact dies STRUCTURALLY, corner cases
   included. N=32 fails it (neighbors uncontained ⇒ the artifact reappears at 512
   blocks) — N=32 is dead, do not A/B it.
2. **Straddle-safety constraint:** N > vd+31, else straddling regions hold
   permanently-needy exclusion cells and can never probe-skip. At N=64, vd=32:
   exactly one ring of clearance (recorded; an effective vd > 33 degrades to
   extra emit passes, not incorrectness).
3. **Flight reservation is the ceiling (not Xaero):** N=64 reserves ~55% of a
   725 col/s stream for near under sustained elytra; N=96 → ~73% (−40% far
   throughput). Cost ∝ N². Xaero doesn't constrain N below ~250 (§5).
4. A/B bracket if contested live: 64 vs 80/96 (visual side); the seam makes it a
   rebuild-per-value A/B — not "cheap", recorded honestly.

## §11 Plan-review fold record (v2, 2026-08-24)

Panel: design/geometry (Opus, 2 MAJOR), cadence+server+boundary (Opus, 2 MAJOR),
tests+consistency (Opus, 8 MAJOR), holistic/premise (Fable, 1 MAJOR). Premise
UPHELD by the holistic lens (decisive structural advantage: phase 1's free legacy
oracle; steelmanned alternatives — interleaved near sub-rings, graded tile sizes —
lose on order-oracle absence and boundary multiplication). Headline folds: the
raw-rectangle classification → lod-clamped residue rectangles (fixes the false
degeneracy claim, the straddler double-walk, and the census contradictions in one
move); the movement-gate scanRing-pinning → adjudicated deviation with pins + a
stated fallback; the cost model fully re-derived (r+4 probes, the never-skippable
exclusion band, both-arms past-cap at converged lod 512, net-wash meter delta);
the far-coverage differential hole; the boundary soak; near_rings promoted to
required; the frontier-anchor tightening with the ts>0 no-stamp bound; the
nearest-first/starvation reframing; the 3×3-containment + flight-reservation
boundary rationale; fold-in deleted from §9 with the artifact-observation
precondition; misc corrections (31 not 62/33; batch-count derivations;
"imperceptible"→"acceptable"; churn/locality give-backs named). vN+1 changes:
NONE pending — the final Fable review of this revision adjudicates.
