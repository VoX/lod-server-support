# Hybrid scan: ring-major near, region-major far (v3 — panel-folded + Xaero backpressure)

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
and may ship ahead of it (§0); a 3-Opus focused review of §12/§12.1 is the
amendment's gate.**

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
  unchanged in kind. This reservation is THE ceiling argument against raising N
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
reservation. Far: unchanged. `nativelyWritable` near tiles are Xaero's own writer
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
live gate re-measures it under the hybrid.

## §7 Config / diag / trace (v1's "add nothing" REVERSED by the panel)

- No new config key for the walk; `HYBRID_NEAR_RADIUS` constant + seam.
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
flat; fill rate ≥ the region round's LIVE numbers (aggregate) with the §2.3
far-first-column latency expected; the §6 generation-bound fill-rate measurement
RE-RUN. Docs task: CLAUDE.md's three scanner sites (want-set paragraph
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

## §12 Xaero ingestion backpressure (v3 — the want-set taper; user-directed)

**Problem (measured live on the region build, 2026-08-24):** arrival 777 col/s vs
the §17-frame-budgeted bridge writer ~680 col/s. The deficit fills the bridge queue
(byte-capped at 48 MB ≈ ~3k columns) in under a minute and the surplus becomes
`dropped_overflow` for the rest of the fill (5,632 / 252k = 2.2%, landing wherever
the walk is when saturation starts — ~944 blocks in the session). Any arrival above
writer throughput saturates eventually; a repair mechanism (the §18 heal) can only
act after the fill ends. The fix is the one the architecture already owns for Voxy
(issue #71): the consumer REPORTS, the want-set TAPERS, arrival self-paces to what
the consumer can commit.

### §12.1 The §18 heal is REMOVED (user decision 2026-08-24)

The heal held correctly by design mid-fill (its headroom gate refuses to re-request
into a saturated queue — healing there displaces fresh columns 1:1), but that is
exactly the indictment: under a steady rate mismatch the heal's operating window
only opens after convergence, so the user watches holes for the whole fill. With
backpressure the mismatch never produces drops, and the heal is redundant mass
(~121 reference sites: DroppedLedger, healPhase, the heal_* counters,
`enableXaeroMapBridgeHeal`, the §18 test suites — all deleted; the
`dropped_overflow`/`dropped_expired` counters STAY as observability). **Named
residual classes, accepted:** (a) governor-lag transients (bounded by one scan
cycle; the queue's 48 MB absorbs ~4 s of full-rate arrival — §12.3); (b)
DEFER_CAP expiry while the map is structurally unwritable (cave layer — see the
-1 rule: the fill deliberately does NOT stall for an unwritable map, so long cave
sessions can still expire deferred writes ⇒ map holes healed only by revisit or
`/lss clearcache`); (c) dimension-switch queue clears (self-healing — re-entry
re-declares, re-serves, re-offers through the ordinary path). The old far-radius
expiry class is already gone under region-major (`dropped_expired=0` live).

### §12.2 Mechanism

The bridge is ALREADY a registered `VoxelColumnConsumer` (XaeroMapCompat.java:255,
via `LSSApi::registerColumnConsumer`). It overrides `pendingIngestBacklog()` — the
exact VoxyCompat pattern (VoxyCompat.java:102) — so ZERO new plumbing: `LSSApi`
already polls max across consumers, `LodRequestManager`/`SpiralScanner` already
taper the budget against `INGEST_BACKLOG_HALT_SECTIONS` (6144), halt at the
threshold with the edge-triggered empty batch, and gate fast fires above ¼ of
halt.

- **The reported value is a governor signal dressed in the halt domain, not a
  section count** (documented as such): `report = round(6144 × fillFraction)`
  with `fillFraction = max(queuedBytes / MAX_QUEUE_BYTES, queueSize / maxQueue)`.
  A FULL bridge queue reports exactly the halt threshold — the #71 doctrine ("no
  work for a client that cannot ingest") applied verbatim; a raw section count
  (queue ≈ 72k sections) would hard-halt at 8% fill and a raw column count would
  make the taper an accident of MAX_QUEUE's value.
- **The -1 rule (no-signal): report -1 unless the queue is actually DRAINING.**
  -1 whenever: the bridge is disabled (`enableXaeroMapBridge` off), inactive (no
  Xaero / not ready), the kill switch (below) is off, OR writes are structurally
  paused (cave-layer wait, dimension not ready). Governing a queue that cannot
  drain would halt the whole LOD fill — Voxy included — for a map that is not
  even writing. When undraining, the existing defer/DEFER_CAP machinery runs
  unchanged (the §12.1(b) residual). Empty-and-draining reports 0 (a real "no
  backlog" signal; identical to -1 under the max-composition, pinned anyway).
- **Thread safety:** the poll runs up to 20 Hz on the client tick; the report
  reads a `volatile` fillFraction mirror maintained under `queueLock` at
  offer/drain/clear (the `backlogSizeSnapshot` pattern) — no lock on the poll.
- **Composition:** automatic — `LSSApi` takes the MAX across consumers, so the
  worse of Voxy's real backlog and the bridge's scaled one governs; the ¼-halt
  fast-path gate then also suspends 4 Hz once the bridge is >25% full (desired:
  fast cadence only while arrival ≈ writer).
- **Kill switch:** `enableXaeroMapBackpressure` (client config, default true;
  inert while the bridge is off), composing UNDER the global
  `enableIngestBackpressure` (#71's switch owns the whole signal path).
- **Wire/server:** none. The taper is entirely client-owned want-set sizing.

### §12.3 Control dynamics

Linear taper, 1 Hz scan feedback (4 Hz while below ¼ of halt): budget ≈
800·(1 − f), f = fillFraction. Equilibrium at arrival ≈ writer: f* ≈ 1 −
writer/(800·scanHz) ≈ **15-20% queue fill at 1 Hz** — the queue rides at ~500-700
columns of its ~3k byte-capped ceiling, 5-6× headroom above the operating point,
`dropped_overflow` structurally 0. Transients (writer stall spikes, region-load
waits) are absorbed by the remaining ~80% of the queue ≈ 3-4 s of full-rate
arrival before the halt threshold even approaches. Full-halt is reachable only if
the writer stops while draining-eligible (a Xaero-internal wedge) — and then
halting the fill is correct. Cost: fill rate converges to writer throughput
(~650-700 col/s, a ~10-15% slowdown vs 777) — the map completes on the FIRST pass
with zero holes. Interaction with the hybrid walk: the taper shrinks the BUDGET,
phases keep their order — under heavy taper the whole batch can be phase-1
(near-first is exactly right while the map catches up); no §2/§3 machinery
changes.

### §12.4 Tests + live signatures (§8 additions)

- fillFraction mirror: offer/drain/clear/dimension-switch transitions, lock-free
  read, byte- vs count-cap max.
- Report mapping pins: full ⇒ exactly 6144; empty+draining ⇒ 0; disabled /
  inactive / kill-switch / cave-layer(undraining) ⇒ -1; the -1 rule's cave-layer
  transition both directions.
- Taper integration: budget shrinks at fractions through the REAL manager tick
  (the existing #71 test pattern); halt at full queue fires the edge-triggered
  empty batch; recovery when the queue drains; fast-path suspension above ¼.
- Composition: bridge report vs a larger/smaller Voxy consumer backlog (max
  wins); global #71 switch off ⇒ bit-identical to pre-amendment.
- Heal removal: the §18 suites deleted; `dropped_overflow`/`dropped_expired`
  counters keep their pins; diag line census updated (heal_* tokens gone, `bp=`
  fraction token added to the XaeroMap line); exporter/contract rows updated;
  `enableXaeroMapBridgeHeal` round-trip pin deleted with the key.
- Live acceptance: a full lod-512 fill with `dropped=0`, queue riding ~15-25%,
  fill rate ≈ writer rate (the ~10-15% slowdown NAMED so it is not misread), map
  complete on first pass; bridge off ⇒ fill rate back to uncapped; Voxy alone
  unaffected.
