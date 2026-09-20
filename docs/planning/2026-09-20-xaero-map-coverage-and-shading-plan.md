# Xaero map coverage and boundary shading: research and implementation plan

Date: 2026-09-20. Status: **deferred following the user’s complexity/value assessment; neither workaround is included in the proposed 0.15.0 release.** Scope: all five maintained Minecraft support lines, with 1.21.1 Fabric/NeoForge as the first implementation and native validation targets.

This is a follow-up to the [accepted visual review](../implementation/visual-signoff-2026-09-20.md) and [diagnosis](../implementation/visual-review-followup-2026-09-20.md). That acceptance remains valid with its recorded limitations. Neither defect is silently reclassified as fixed. This document plans product changes; it does not launch clients, install artifacts, or authorize publication.

## 1. Decisions and intended behavior

| Problem | Selected approach | Confidence and completion criterion |
| --- | --- | --- |
| Eight blank chunks around a stationary player at view distance 4 | Add a bounded, Xaero-specific acquisition supplement for loaded chunks inside vanilla's render exclusion that Xaero cannot write natively. Keep the existing generic scanner exclusion. | Cause demonstrated in the recorded 1.21.1 scene. Complete when genuine requests, deliveries, bridge commits and map textures fill those cells without player movement, with both scanner implementations. |
| Missing gold-ramp stripe at a 512-block region boundary | Prototype an optional shading correction using bounded copies of already available boundary heights. Preserve native approximation when inputs or compatibility checks are unavailable. | Intentional upstream approximation, not missing terrain. Shipping the workaround is conditional on proving safe lifecycle, native redraw, invalidation and save behavior. |
| Map absent although LSS already considers the column satisfied | Diagnose explicitly during the new tests; keep a separately identified coverage-demand/replay extension. | The first-spawn supplement alone does not solve historical consumer coverage. Do not promise that it does or use fake ingest failures to force a replay. |

Recommended delivery: finish and validate the acquisition correction independently. Investigate shading through a small isolated prototype before expanding production integration. Failure of the shading prototype must not hold the acquisition correction indefinitely or become a cosmetic screenshot-only fix.

## 2. Research findings and evidence

### 2.1 Acquisition mismatch

At player chunk (16,16), the affected chunks are (12,13), (12,19), (13,12), (13,20), (19,12), (19,20), (20,13), (20,19). Their offsets are permutations of (±4,±3). The retained diagnostic measured a stable real FULL-chunk grid that explains exactly these eight holes. It strongly explains the reported first-spawn pattern, without establishing the cause of every possible transient map hole.

`SpiralScanner.isVanillaRendered` implements the vanilla render footprint. `RegionScanner` also skips whole excluded rings; both use retained completion state. Xaero requires the center and all eight neighboring chunks to be FULL and non-`EmptyLevelChunk`. `XaeroTileWriter.nativelyWritable` already models that correctly, and loaded-edge tiles are written when offered. The missing step is offering tiles that acquisition excludes.

For the audited 1.21.1 tracking rules, let a=max(0,|dx|−1), b=max(0,|dz|−1). Render exclusion E(r) is a²+b²<r²; buffered tracking S(r) is min(a,b)²+max(0,max(a,b)−1)²<r². An independent integer enumeration gives:

| View distance | Excluded cells lacking a full 3×3 neighborhood in S(r) |
| --- | ---: |
| 2 | 4 |
| 3 | 4 |
| 4 | 8 |
| 5 | 12 |
| 8 | 16 |
| 12 | 28 |
| 16 | 36 |
| 32 | 72 |

These are analytic counts for the audited formula, not native measurements on every support line. Eight must not become a hardcoded production count. S(r)=E(r+1) happens at some radii and fails at others; use actual loaded coverage.

### 2.2 Shading is an upstream performance tradeoff

Xaero's [official changelog](https://chocolateminecraft.com/update.php?mod_id=2), entry 1.12.0 dated 2021-02-07, explains that approximate region-edge slopes replaced accurate ones to reduce region reloads. Its current 1.46.x entries do not announce a reversal of that policy. This explains the design intent; the exact installed bytecode establishes present behavior.

In the pinned 1.45.0 jars, `MapTileChunk.updateBuffers` obtains north, northwest and west tile groups with cross-region lookup disabled. `MapBlock.fixHeightType` falls forward to a nearby slope when the preceding height is unavailable. `MapPixel.getPixelColours` derives slopes when unknown. The native writer can replace blocks and invalidate slopes again later. Consequently, setting a slope once during an LSS commit is insufficient for a durable correction.

The fixture's gold ramp resets from 71 to 64 every eight blocks. X=512 yields diagonal slope +1, whereas the equivalent reset at X=520 yields −7. Height 64 is correct in both. The recorded bridge and subsequent native rebuilds agree because they share the approximation. Existing `shading_valid` and `boundary_continuity` assertions establish their historical native-parity contract, not an independently correct region-edge slope.

A read-only audit in this research checked seven profile-pinned 1.45.0 artifacts: Fabric on all five lines, NeoForge on 1.21.1 and 26.2. All seven retain the three region-local lookups. A separately downloaded [official 1.21.1 Fabric 1.46.0 artifact](https://api.modrinth.com/v2/version/txl9KURC), SHA256 `0f87dd9fbe60e56f17f0b49b73717c1f73ec7aa7b3faf7a99ae34e74c5fe4985`, has unchanged selected `updateBuffers`, neighbor lookup, slope calculation, pixel-color and `writeChunk` disassembly after constant-pool reference normalization. Upgrading that target alone does not remove this mechanism. This is bytecode evidence, not runtime compatibility acceptance for 1.46.0.

### 2.3 Sources and support-line differences

Primary repository sources:

- [SpiralScanner](../../xplat/src/main/java/dev/vox/lss/networking/client/SpiralScanner.java), [RegionScanner](../../xplat/src/main/java/dev/vox/lss/networking/client/RegionScanner.java), [LodRequestManager](../../xplat/src/main/java/dev/vox/lss/networking/client/LodRequestManager.java), [ColumnStateMap](../../xplat/src/main/java/dev/vox/lss/networking/client/ColumnStateMap.java).
- [XaeroSession](../../xplat/src/main/java/dev/vox/lss/compat/XaeroSession.java), [XaeroTileWriter](../../xplat/src/main/java/dev/vox/lss/compat/XaeroTileWriter.java), [XaeroRebuildScheduler](../../xplat/src/main/java/dev/vox/lss/compat/XaeroRebuildScheduler.java), [XaeroBindings](../../xplat/src/main/java/dev/vox/lss/compat/XaeroBindings.java).
- [XaeroMapCompatTest](../../fabric/src/test/java/dev/vox/lss/compat/XaeroMapCompatTest.java), [XaeroAcquisitionLifecycleTest](../../fabric/src/test/java/dev/vox/lss/compat/XaeroAcquisitionLifecycleTest.java), scanner/request-manager tests, and [the native map checker](../../tools/rig/check_xaero_map.py).

Retained local research: `/home/vox/.local/state/lss-project-improvements/map-fix-research-20260920/`, containing `bytecode-audit.json`, artifact-specific disassembly, official download metadata and `geometry-enumeration.json`. Earlier diagnostic evidence is indexed by the linked follow-up report. Only summaries and identifiers belong in maintained docs; do not vendor decompiled Xaero code or JARs.

The scanners, column state, writer, rebuild scheduler, bindings and compatibility facades are byte-identical across all five current worktrees. Request-manager source has two line variants; the session has the 1.21.1 world-height variant; extraction differs on every line. Preserve those adaptations and consult [version surfaces](per-version-surfaces.md). Shared source similarity does not prove identical vanilla tracking geometry or native runtime behavior.

## 3. A — acquisition correction

### A1. Introduce a consumer-specific coverage seam

Add a narrow injectable interface, provisionally `SupplementalMapCoverage`, to the scanner's shared dispatch. Wire production through `LodRequestManager` → `ModCompat` → `XaeroMapCompat`/`XaeroSession`. Default implementation is absent. Keep Xaero reflection and Minecraft world access out of scanner policy tests.

The seam exposes a current-session eligibility token and a non-loading coverage query. It must be available only when the manager can receive, the bridge is enabled/resolved/registered and alive, and acquisition belongs to the current client world/dimension. `isArmed()` is insufficient: it deliberately has weaker semantics for cache aliasing. Do not change that existing method's contract.

Respect acquisition retirement, disconnect, same-dimension world replacement, known both-map-write-switches-off and the bridge's current settings policy. Use current-world identity, not dimension alone. Reobserve disabled Xaero write switches through the existing bounded recovery path so OFF→ON cannot become an idle permanent suppression latch. Transient writer pauses still use the existing bounded queue and backpressure behavior.

Factor the FULL/non-placeholder neighborhood definition into one reusable helper, retaining the writer's final recheck. The scan observes readiness; it does not grant commit permission or acquire Xaero region locks.

### A2. Enumerate loaded-edge demand independently of far-ring completion

Eligibility for the initial correction:

1. Inside the existing vanilla render exclusion and within the effective LOD request range.
2. The center chunk is a real FULL client chunk, and at least one of its eight neighbors is not.
3. Bridge/session eligibility passes and `ColumnStateMap.classify` returns ordinary actionable demand.

The loaded-center condition deliberately limits this correction to the demonstrated edge gap. Chunks whose center has not arrived still await vanilla; reevaluate them on subsequent eligible scans. This avoids requesting the entire temporarily empty vanilla interior on join. A separate test must distinguish that ordinary loading delay from persistent edge holes.

Run supplemental discovery at shared `maybeScan` dispatch, independent of confirmed prefixes, quadtree masks and the region scanner's whole-ring skip. Keep both ordinary scanners' exclusion and retry-ring logic unchanged. No fixed eight-position table, radius shrink, square exclusion, `hasChunk` substitution or server-view-distance increase.

Bound discovery as well as emitted requests. Initial implementation limits to evaluate: at most 1,024 candidate positions and 4,096 unique non-loading chunk queries per fired scan. Memoize coverage only within that scan. A rotating cursor makes progress across larger or mod-expanded distances; retain no loaded-state snapshot between fires. A partially evaluated candidate is revisited rather than skipped. Scope/reset the cursor to session, world, center and distance changes; do not reset the existing scan cadence on movement. Include discovery work in the conservative fast-scan cost prediction. Confirm these initial bounds with the focused measurements in §6; adjust from evidence, without changing rate limits.

### A3. Compose one honest want-set

Use the already computed pressure/rate/wire budget B. The initial supplement reservation is at most min(8, B−1) when B>1 and ordinary demand is available; ordinary work gets the remainder. Unused supplemental capacity returns to the ordinary walk. The supplement drains larger gap sets across successive answers rather than allocating beyond B. Existing server ownership, timestamps, request-body handling, consumer fan-out and receipt accounting remain authoritative.

For B=1, keep an eligible unanswered declaration stable through ordinary retry/redeclaration; alternate which source gets the next slot after progress or loss of eligibility. Define fairness under responsive answers, with explicit delayed-answer tests. Blindly alternating every timer fire can repeatedly replace/cancel an unanswered want, so it is not an acceptable fairness implementation. Do not claim bounded completion when the server never answers.

Produce one combined batch, one `tracker.replaceWith`, one `noteDeclared`, one send attempt. No extra network batch, heartbeat, uncapped append or fabricated receipt. Preserve no-walk (−1), walked-empty (0), failed-send disarming, legacy v16 cadence, halt/clear and successful-send governor provenance.

Refactor scan bookkeeping so ordinary frontier state and combined batch diagnostics are distinct. In particular, `lastWalkTruncated` currently participates in both ordinary walk-cost prediction and governor accounting. Overwriting it with supplemental exhaustion can make a stale `scanRing` predict a falsely cheap ordinary walk. Preserve ordinary truncation/frontier state separately; aggregate total budget/count/truncation for the manager. If the supplement consumes the only slot, do not call an ordinary scan with budget zero or advance its untouched prefix. Compute combined trace range/span from the actual emitted set where required.

### A4. State, retries and the satisfied-column boundary

Use `classify` as written: dirty, session-terminal, unknown, retry and timestamp validation retain their precedence. Genuine bridge failures continue through the existing generation-bound failure/debt path. Supplemental retries remain reachable without reopening every ordinary in-view ring. A native-writable tile becoming surrounded before commit may still be skipped by the writer; cover the transition back to an exposed edge in the satisfied-column investigation below.

Record separate diagnostic counts for candidates examined, uncovered loaded centers, requested supplements, satisfied uncovered centers and discovery truncation. Avoid per-pixel/per-tick log spam.

If a missing tile has an LSS-satisfied column, establish whether that is because the map was cleared, bridge writes were disabled, an earlier offer was native-skipped, or cache validation succeeded without a body. None of those is an ingest failure. The initial correction must report its scope honestly.

Before promising retrospective repair, design a separate per-consumer coverage demand: a current-map-generation token, explicit distinction between native/bridge commit/pending/terminal-empty states, a bounded fresh-body requirement independent of timestamp satisfaction, and clearing only after genuine commit/native coverage or a genuine terminal result. Reconcile timestamps, summaries, cache-body availability, map reset, backpressure, all consumers and receipts before implementing it. Local FULL-chunk snapshot extraction is an alternative to evaluate for this extension, but introduces section/light snapshot ownership and another per-line input path. Do not silently broaden A into that framework.

### A5. Regression and native acceptance

Parameterize both scanner arms and retention/quadtree settings where relevant:

| Test | Required result |
| --- | --- |
| Exact S(4) grid, bridge enabled, initially unknown columns | Ordinary set plus exactly the eight excluded edge positions; each requested once per declaration, no duplicates. |
| Same grid with bridge absent/disabled/unresolved/dead, receive OFF, or both writes OFF | Original generic request behavior; no supplemental traffic. |
| Center absent; then FULL; then all neighbors FULL | No early interior request; edge eligibility appears; supplement disappears when natively writable. |
| Ordinary prefixes/quadtree fully confirmed | Supplemental positions remain reachable. |
| B=1, B=2, normal B, pressure taper, delayed answers and send failure | Budget respected; stable re-declaration; progress from both sources; honest diagnostics/governor state. |
| Genuine drop/retry, temporary no-data, permanent terminal response, dirty edit | Existing retry/receipt/terminal contracts preserved; eventual convergence under responsive conditions. |
| Movement, view/LOD shrink/grow, dimension change, same-dimension replacement, retirement | No stale candidates or cross-session credit; no cadence debounce. |
| Satisfied timestamp/native-skipped/bridge-disabled history | Scope limitation demonstrated explicitly, not converted into a fake pass. |
| Large view, query/candidate exhaustion | Bounded work and fair eventual discovery while stationary. |

Retain the existing generic exclusion/reload-loop tests, `wholeExcludedRingsMatchesTheRealExclusionShape`, `fullySurroundedLoadedChunksAreSkippedNotWritten`, `aLoadedEdgeChunkIsBridgeWritten`, and the request-manager no-walk/convergence/send-failure tests.

Native fixture: retain player position, record actual FULL coverage beside each observation, and bind each expected hole to a real request, body, bridge outcome and texture containing the seeded terrain. Do not equate black RGB with absence on arbitrary terrain. Use the explicit seeded fixture oracle, not only PNG center color or elapsed time. Require the eight tiles to fill with the player stationary, then converge without continuing requests. Preserve a fresh baseline that exhibits the gap and native negative controls. A fully surrounded control should remain native-owned.

## 4. B — boundary-shading correction

### B1. Options and selection

| Option | Assessment |
| --- | --- |
| Upgrade Xaero | The audited 1.21.1 1.46.0 artifact retains the relevant implementation. Keep existing dependency profiles while implementing A. |
| Disable terrain-slope shading | Hides the symptom by removing wanted map detail. Not a correction. |
| Change `crossRegion=false` to true | Not selected. Changes Xaero's deliberate boundary policy and requires a complete concurrency/loading audit; never invoke foreign-region loads from the current bridge lock ladder. |
| Read neighboring resident regions directly during redraw | Even without forced loads, this crosses mutable native ownership and needs unavailable/busy/PBO handling. Do not assume a resident reference is safe to read. |
| Set exact slopes once when LSS commits a tile | Useful prototype control only. Later native writes, invalidation and rebuilds can replace the result. |
| Retain immutable copies of known edge heights; correct at the native slope/rebuild path | Preferred prototype: bounded metadata, no foreign-region lock nesting or extra region loads, and a route to cover both bridge and later native redraws. |
| Upstream change/API | Best long-term ownership if Xaero accepts it. Prepare a local reproduction/design note if useful; do not send an issue or message without user authorization. |

The correction can be exact only where trustworthy neighboring heights exist. Unknown, stale, unloaded/unverifiable or incompatible data must retain native behavior. The target is correct shading for mapped, available neighboring terrain, not reconstruction of unexplored terrain.

### B2. Prototype before committing to a production hook

Use a separate test-only fixture and disposable copied map. Keep the normal observer independent: a mutation-capable prototype must be named and identified as such, and cannot masquerade as the product artifact.

1. Prove the numeric oracle with an X ramp, a Z ramp and a corner crossing, including negative coordinates. Vertical slope is clamp(height−northHeight, −128,127); diagonal slope is clamp(height−northwestHeight, −128,127). Use effective heights under Xaero's short-block/config interpretation, not blindly `floorY` or `topY`.
2. Demonstrate that setting both slopes and clearing `slopeUnknown` before native color derivation yields the expected stripe using public native setters. Measure whether later native writes/rebuilds undo it. This establishes the hook requirement, not a shipping implementation.
3. Evaluate two narrow hooks: correction at the cross-region fallback inside `fixHeightType`, or correction of only boundary pixels immediately before color derivation in `updateBuffers`. Prefer the smaller seam that also handles known-but-approximate slopes on redraw. Explicitly cover native `useSourceData=true` writes and later unknown-slope derivation; do not assume a single HEAD/RETURN hook covers both.
4. Enumerate every entry path to that hook: LSS rebuild, native writer, texture/cache reload, resource/config change and export if shared. Audit actual thread/monitor ownership for each. A hook must not acquire processor/global or another region's locks from inside native callbacks. If a path lacks safe ownership, the hook falls back and schedules bounded correction through the owner; it must not mutate pixels there.
5. Prove source-update, load/unload and same-coordinate replacement invalidation. Identify exact native members/events on every supported artifact; method-name presence alone is insufficient. In particular `setTile`, native `setBlock`/write completion, buffer/load state changes and map clearing must not leave a previously published edge silently valid.
6. Exercise save, close/reopen, native rewriting, resource/config changes and source arrival after target. Count region load requests and save/rebuild activity. A pristine screenshot is insufficient if the stripe returns on the next native pass.

Gate B-P: proceed only with a written hook/ownership table, demonstrated invalidation coverage, correct independent slopes in all three orientations, and zero additional region loads for shading. If these cannot be established with narrow hooks, leave shading unchanged and record the unresolved seam; do not expand to a full Xaero renderer replacement.

### B3. Candidate production structure after B-P

Provisional new components: `XaeroBoundaryShading` (pure calculations and policy), `XaeroBoundaryHeightCache` (bounded immutable samples), optional binding group in `XaeroBindings`, and narrowly scoped optional Xaero mixins only if the prototype proves them necessary. Keep core bridge availability independent of correction availability. Preserve the existing reflective bridge floor; an older or unknown Xaero may continue using native shading.

Scope new corrections to the current LSS-bound surface map while the bridge is enabled and the optional capability is verified. A class-level hook must remain inert for unrelated/native-only sessions, other processors/worlds and cave layers. Use the existing bridge preference rather than adding an unproven user-facing setting; the prototype's test switch remains test-only. Already owed valid-world redraws follow the lifecycle below.

Publish samples from the current source tile group's safe native owner point, copying only valid effective boundary heights. A 64×64 group needs at most 127 unique heights for its south row and east column, with validity bits; retain only edges relevant to region seams. Do not retain live foreign `MapRegion`, `MapTile`, `MapBlock`, client chunk or direct-buffer objects in this cache. A snapshot must never mark unloaded/uninitialized height cells as real data (including Xaero's unknown-height sentinel).

Identity includes LSS/Xaero native-world generation, processor/world id, dimension, surface layer, source group coordinates, replacement/content revision and height-interpretation revision. Resource reloads, short-block interpretation and map changes invalidate relevant entries. Publication is transactional after native validity checks; a dirty source invalidates its former snapshot before it can be consumed as current. Copy on the owning thread; any notification arriving elsewhere is generation-tagged and handed off without touching native regions.

Initial cache cap to measure: 1,024 edge-group records, each at most 127 heights plus validity/identity metadata. Bounded coordinate-only dependent work is coalesced by newest revision, with explicit caps. These are proposed operational limits, not measured memory/performance claims. Unknown/evicted data falls back; it must not pin a region or fabricate height zero. Maintain consumed source revisions for corrected targets so edits invalidate old slopes. Source or target arriving first must both converge when valid inputs become available.

For each correction, obtain the target's effective height and its north/northwest inputs from current-group data or a validated immutable foreign-edge sample. Only correct pixels whose dependency actually crosses a 512-block region boundary. Preserve color, light, biome, overlay, cave and terrain data. Do not bypass native air-pixel handling or invent a new shading formula. Correct both needed slope components coherently; partial/missing input retains the native path.

### B4. Redraw debt, eviction and lifecycle

Extend the rebuild scheduler with an explicit boundary-shading reason/revision, not merely `wasChanged()`. A source may arrive after the target's approximate texture became clean. Schedule that correction by coordinates; later resolve only an existing target under its own writer-pause/region gates, validate world/object identity and reserve redraw capacity before mutation. Do not create/load the target just to fix a seam. Process at most the few directional dependents, never scan every map region on a frame.

An update arriving during queued work replaces the requested revision. Capacity refusal must preserve bounded dirty/revision state for retry, or invalidate the claim of an exact correction and fall back; it must not silently lose a necessary repaint. A correction must not publish a new terrain-height revision, otherwise neighboring redraws can ping-pong indefinitely.

Use the existing frame/tick time allowance, save eligibility and coalescing rules. Never set the unsafe `setToUpdateBuffers` flag from LSS. New work must not make unchanged terrain perpetually dirty or produce repeat saves each frame. Preserve acquisition OFF versus committed-native-work retirement: receive OFF stops requests, while already owed valid-world redraws can finish; disconnect/world replacement drops old-world work and scalar samples. Bridge-disabled behavior and any already committed cleanup must be explicit and tested.

Cache eviction, source unload and missing invalidation hooks cannot justify stale exact slopes. Define when an already corrected target becomes unknown again, how it is redrawn safely, and what happens if the target is also unavailable. Persisted native slopes/textures must be checked on reload; do not add a new on-disk cache format as an incidental workaround. If correctness needs reliable cache-load hooks, that is part of B-P, not deferred cleanup.

Optional mixin selection must verify the expected target descriptors and supported structure before application, avoid loading absent optional classes, and provide an observable capability/disabled reason. `require=0` alone can hide a non-working correction and is insufficient. Unsupported correction must leave the ordinary bridge functioning. Test application with absent Xaero, audited versions and a deliberately incompatible stub/artifact; keep test-only classes out of shipping jars.

### B5. Shading acceptance

Add a versioned independent slope oracle; retain historical native-parity results under their original scenario version. The new assertion is expected seam slope/content, not equality to an unmodified native renderer with the same approximation. Within-region pixels still need native parity. Explicitly label Sodium modern/legacy profiles separately from Xaero terrain-slope modes (off, 2D, 3D); they are different axes.

Required cases: X, Z and diagonal/corner seams; positive and negative coordinates; flat/rising/falling terrain; short blocks with interpretation changes; overlays and air; target-before-source and source-before-target; edits on either side; unknown heights; cache saturation/eviction; busy/PBO/unloaded/replaced groups; save pause and reload; cave/dimension/multiworld changes; receive OFF/disconnect; native rewrite; optional-hook absence/failure. Queue and save-pressure controls must show bounded work and quiescence.

For the recorded gold fixture specifically, require height 64 and diagonal slope −7 at X=512 once the preceding height is known; compare the equivalently lit/materialled X=520 control for native RGBA. General fixtures use their independent numeric oracle instead of hardcoded gold byte values. Capture original native PNGs and user-readable close views. Any genuine visual review remains separate from automatic assertions.

## 5. Implementation order and support-line delivery

| Step | Work and exit condition |
| --- | --- |
| 1 | Extend coverage fixture/oracle and introduce failing A regression tests on 1.21.1; retain the existing eight-hole evidence and generic negative controls. |
| 2 | Implement A1–A4 with injectable seams and bounded batch composition; pass A5 tests, including receipt and delayed-answer tests. |
| 3 | Run focused 1.21.1 Fabric/Neo native controls; prove stationary fill and convergence. Freeze A behavior and port using exact reviewed source differences. |
| 4 | Run B2 as a clearly identified test-only experiment. Record B-P outcome before adding production hooks. |
| 5 | If B-P passes, implement B3–B5 with capability isolation and native lifecycle tests; otherwise retain native shading and document the specific unresolved requirement. |
| 6 | Port the accepted production changes to 1.21.10, 1.21.11, 26.1 and 26.2; update explicit file classifications, adaptation blobs, fixture targets and compatibility evidence. |
| 7 | Run required per-line build/release checks and focused native matrix; record exact candidate hashes and outcomes, then prepare deployment artifacts for a separately authorized install/restart. |

Work in the existing isolated improvement worktrees or new isolated branches from their exact recorded heads. Preserve unrelated modified/untracked evidence and fixture documentation. No blanket staging/reset, target-branch merge or dependency-profile replacement. Record dated decisions for changes to region-local shading policy and any new optional hook; the current region-local writer test continues to protect its own commit lock discipline.

Java 21 on 1.21.x; Java 25 on 26.x, with common remaining Java 21. Paper/Folia server behavior and wire format are unchanged, but the repository's full source-change gates still apply. No Folia or Tier 3 client-gametest task on 1.21.1. NeoForge code remains maintained on all lines; publishing eligibility and client-profile feasibility remain separate catalog facts.

Native target matrix:

- 1.21.1: Fabric and NeoForge, each with the existing modern and legacy Sodium profiles; both scanner arms for coverage. Include a genuine bridge-disabled/native-only control and no-Sodium control where needed to isolate the hook.
- 1.21.10, 1.21.11, 26.1 and 26.2: each profile-pinned Fabric client with actual loaded-grid observations; do not assume the hole count is eight. Include the maintained 26.2 NeoForge profile for optional-hook compatibility. Other Neo targets get compile/contract checks unless an exact runnable profile is prepared and explicitly reported.
- If a newer Xaero artifact becomes a claimed supported correction target, add its own exact binding/native checks. The read-only 1.46.0 audit does not satisfy that gate.

The current map live fixture compiles against 1.21.1 even when mirrored into newer worktrees. It is not automatically a runnable newer-Minecraft fixture. Prepare exact target-specific fixture/runtime receipts or use a compatible observer build with its descriptors verified per target; never relabel a 1.21.1 capture as a newer-line pass.

## 6. Validation cost, evidence and completion

Planning itself requires document/link and research-summary checks, not builds or benchmarks. Implementation should run focused regression suites during editing, then each changed line's established full verification once final source bytes are stable:

```sh
python3 tools/verify/verify.py full --run
bash tools/verify/run-gradle.sh vssJars
python3 scripts/release_check.py
python3 tools/tests/check_artifacts.py
python3 tools/compat/catalog.py validate
python3 tools/compat/catalog.py render --check
```

Run heavy validation sequentially under the existing harness lock. Bind native results to exact product/fixture/dependency hashes. Older builds and 54-case acceptance retain their historical scope and cannot be relabeled as new-JAR evidence. Preserve first failures and cleanup records; update maintained ledgers/catalog snapshots only when new results actually exist.

Use private WSL displays and owned disposable instances exclusively. Preserve the Windows desktop, normal test server, accounts and worlds. Check disk headroom before new native/build artifacts, reuse immutable dependencies and avoid duplicating entire old evidence trees. A user-facing gallery can use IPv6 loopback as before.

Measure the new work with matched short controls: acquisition queries/time, supplement request count, total bytes, convergence, map backlog, redraw duration/count, cache occupancy/evictions and region-load/save counts. Compare A and B separately against the same scene/artifacts/settings. B must introduce zero shading-driven region loads. Set any timing acceptance threshold from the matched baseline and existing frame allowance before evaluating the candidate. Do not claim a performance win from functional runs or automatically repeat the unrelated 2½-hour V27 campaign.

Completion is reported separately:

- **A complete:** regression and exact-artifact native evidence demonstrate stationary loaded-edge coverage with bounded/fair acquisition and convergence across supported targets. Historical satisfied-column repair is explicitly included only if separately designed and validated.
- **B complete:** B-P passed, independently correct known-neighbor slopes survive the tested native lifecycle, and bounded work/fallback behavior passes. Unknown terrain still uses approximation. If B-P fails, report B as unresolved, never complete by accepting the old stripe again.
- **Delivery complete:** required builds and relevant native evidence are recorded per line; remaining capability limits are explicit. Deployment, merges and publication are separate actions.

## 7. Planning verification performed

Read the maintained implementation, related regression contracts, native checker and support-line surfaces. Compared shared source hashes across all five worktrees. Independently enumerated the eight-hole geometry and other radii. Audited seven pinned Xaero jars and the separately downloaded 1.21.1 Fabric 1.46.0 jar without installing or executing them. Checked the upstream changelog for intended behavior. No production source was edited, no clients/servers were started and no runtime fix is claimed.
