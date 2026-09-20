# Final review 06 — concurrent source correctness and performance acceptance

Date: 2026-09-16. Reviewer: Claude Fable 5.1 (Claude Code), independent final review, assignment 6 of 6. Read-only: no repository file was edited, no Gradle/Java/Minecraft process was started, no subagent was used. Only Python (niced), git read commands and file inspection were performed.

**Result.** Half A (production concurrency correctness): **no blocker/high/medium findings; two low findings (R06-1, R06-2)**, both hypotheses from code reading about bounded cost and an unchanged pre-existing exposure, neither a regression. Half B (performance acceptance): **two medium findings (R06-3, R06-4) and two low findings (R06-5, R06-6)**. The nine-run arithmetic recomputed here matches the official evaluation exactly (30/30 comparisons, identical paired differences and bounds, identical single out-of-bound pair); the predeclared median-plus-two-of-three rule was applied honestly and the documented exception is accurate.

## Exact scope and identities

Worktree HEADs at review time (all on `feat/project-improvements-mc<line>`; these are one commit past the frozen finals recorded in review 04 — the R04-1 compat fix, tooling only):

| Line | Baseline | HEAD reviewed |
| --- | --- | --- |
| 1.21.1 | `48268c7d` | `0d14bcd71772afdc71cef07d7db070c592e902ad` |
| 1.21.10 | `948b0bf0` | `f26fae1def6786c0945e0d426f05d99f47fc5267` |
| 1.21.11 | `8ac799a9` | `decbc5eae959160f4f9def60e31705a3b82ef713` |
| 26.1 | `1e7c9a9d` | `2ddee76d89edfe38dd28db63df929b35820f587e` |
| 26.2 | `98b67abc` | `bdc6b3ef030d092608a2d748ca222ec76cfe2177` |

Plan file identical on all five lines (SHA256 `d6d5ac2d…bac9b`). `evaluation.json` SHA256 `455c0101dfde3ebf22bb75f29c76d7d587741b7726df9e8d423da5be45bd4447` (345,742,498 bytes), matching `completion-summary.json`, `root-final-comparison-review.json` and `docs/operations/performance-v27-summary.json`. Registration canonical digest `24c12a5d…be7`; frozen-calibration digest `05bcfd47…5b2`.

Experiment arms (26.2): baseline `source_tree 4162bc39…` = commit `c2283b03` (the "corrected reference": `98b67abc` + seven correctness commits `a326e586..c2283b03`, ancestor-verified); candidate `source_tree d9866a3d…` = commit `84f63361` on the feat branch (2026-09-14 06:56 UTC). The four correctness-fix common files are blob-identical between the two arms and HEAD (`AbstractPlayerRequestState` 40833bed, `OffThreadProcessor` 5ab46f2f, `IncomingRequestRouter` 8b29b12f, `LoadedProbeGuard` 98311c60). Product delta between arms: 57 files, +5264/−3307. HEAD's product delta over the measured candidate: only the preset commit `b3498fb0` (2026-09-14 18:32 UTC, after the experiment finished 18:18 UTC; +86/−8 in `CommandHelp`, `RuntimeSettings`, `ServerConfigBase`, `PaperCommands`, `LSSServerCommands`).

Cross-line drift check: the seven common processing files, `PaperOffThreadProcessor` and `PaperPlayerRequestState` are byte-identical across all five lines; `PaperRequestProcessingService` and xplat `RequestProcessingService` differ only by known line flavor (`location()`/`identifier()`, split world dirs on 1.21.1, `PaperWorldLod` per-world distance on 26.2), with every concurrency hunk present at identical multiplicity on all five lines (probe handoff, fresh-take, claim/complete/publish/cancel, late-probe range, `discardProbeHandoff` ×2, `captureLoadedProbe` ×2, registration-bound `RegionProbeBatch`, `serializeCapturedProbe`). All ten new/changed test classes are byte-identical on all five lines. On 1.21.1 (no Folia) `regionizedProbing = FoliaSupport.IS_FOLIA` is always false, so the late-probe/paired-envelope machinery is dormant there and only the `LoadedProbeGuard` capture path is live; the Paper unit tests exercise the Folia path through the `regionized` seam on every line.

## Findings

### R06-1 — Folia one-shot correction re-sends a body even when identical to what the recipient holds

Severity: **low**. Hypothesis from code reading (no test asserts the opposite; the measured runs cannot quantify it because the rig's `product_metrics` rows do not carry `corrective_columns_sent`).

Location: `common/src/main/java/dev/vox/lss/common/processing/OffThreadProcessor.java:306-345` (`processLateProbes`), `:1307`, `:1341`, `:1378` (`markLateProbeDiskFallback` after header-fresh, store up_to_date, and body/all-air deliveries); `AbstractPlayerRequestState.java:299-334` (`takeLateProbe`/`consumeLateProbe`); `paper/.../PaperRequestProcessingService.java:1877-1908` (`runRegionProbe` publishes any loaded, owned column).

Trigger: Folia only (`probeHandoffRequired`). An admitted disk read for a position that the player's own region finds loaded within `LATE_PROBE_LIFETIME_NANOS` (10 s), after the disk result was delivered as a body, an all-air clear, a store up_to_date or a header-fresh up_to_date. There is no content comparison between the loaded capture and the delivered disk/store bytes, so the correction fires even when both are identical (the common case: loaded chunk with no unsaved edit). In the header-fresh case the recipient has already been proven current and still receives a full body.

Consequence: bounded redundant bandwidth/serialization — at most 512 attempts and 2,000,000 raw bytes retained per player, one owner opportunity per admission, ≤10 s lifetime (`AbstractPlayerRequestState.java:152-160`). Delivery honesty is preserved (done-bit marked after acceptance, `cycleNow` stamp, departure grace and probe suppression apply through `addReadyPayload`/`flushSendQueue`). Not a correctness issue; the authors chose this ("Even an original ts<=0 requester may now hold the fallback's non-air data").

Verification: run `SOAK_PLATFORM=folia ./scripts/soak.sh warm-rejoin` (26.2) with the store on and read `service.corrective_columns_sent` against `service.columns_sent` and `disk.header_hits`; if the ratio is material, gate the correction on a per-recipient served-bytes hash (FNV of the last delivered body per position, the `DirtyContentFilter` shape) or split the counter into identical/differing so the cost stays visible.

### R06-2 — Correction scope is the player's own region; cross-region loaded-unsaved edits keep the pre-existing stale-disk exposure

Severity: **low** (not a regression; narrows but does not close a documented Paper residual). Hypothesis from code.

Location: `paper/.../PaperRequestProcessingService.java:1888-1889` (`ownsChunk` false → `completeLateProbe(claim, null)`), `:1804` (claims scheduled on the player's `EntityScheduler` only).

Trigger: player A's LOD disc contains a chunk owned by player B's region; B edits it (event-marked, broadcast, mark drained); A's re-ask is admitted to disk before Folia's autosave lands (native save setup in the experiment: 200 ticks, 24 chunks/tick). A receives the pre-edit disk body, its done-bit is set, and no later correction is possible because A's region never owns the chunk; Paper's dirty detection is event-driven, so nothing re-marks the position at save time.

Consequence: A stays stale until the next event in that chunk — the same exposure as before this change set. The plan §11 scenario 2 ("a content update in each occupied region … reach the right consumers") is exercised by the fixture only with subject-local edits (`SourceWorkload.java:91-93`, `MeasuredSchedule.chunkX/Z`: each subject edits its own 256 cells), so the cross-region observer case has no fixture coverage.

Verification: add a Folia fixture case where subject B edits a cell inside subject A's disc while A's disk read is admitted before autosave; expect A's committed block to reach the expected value only after the next event/save. A design fix is to schedule the claim probe on the chunk's owning region (`RegionScheduler.execute(plugin, location, …)`) instead of the player's entity scheduler, keeping `ownsChunk` as the guard.

### R06-3 — Frame-duration comparisons are pacing-saturated; the ±10% frame budget cannot detect sub-period frame-cost regressions

Severity: **medium** (evidence interpretation; the plan makes P1/P5 depend on "this explicit frame/tick budget"). Observed.

Evidence: in all nine runs every client's `frame_p50_ms` is 33.3333 ± 0.0005 ms and p95/p99 lie within 0.01–0.07 ms of the 30 FPS period (calibration values 33.339–33.399 ms; frozen frame floors 0.0005–0.047 ms). Every frame bound is therefore the 10% relative term, ≈3.33 ms. A candidate can add up to ~3.3 ms of per-frame work (i.e. anything short of dropping below ~27 FPS) without moving any frame comparison. The measured paired differences (|Δ| ≤ 0.09 ms) say only that neither arm exceeded the frame period.

Location: `docs/operations/performance.md` §"Accepted 26.2 comparison" and `performance-v27-summary.json` `interpretation` ("whole-client frame cadence includes waiting/pacing; no 33 ms subtraction") — the text states the caveat but not its consequence, that the frame gate has essentially no power at this configuration.

Verification/action: state the sensitivity limit explicitly in `performance.md` and the summary; for any future frame claim, either uncap FPS in the client profile (`client_max_fps` 30, `inactivity_fps_limit: minimized`) or add a per-frame render/CPU-time metric to the frozen schema (`tools/rig/metric-schema.json`) before calibration. Do not widen or reinterpret the current experiment.

### R06-4 — Functional prerequisites, measured arms and shipped HEAD are three different artifact sets; Fabric/Paper functional evidence exists only on pre-fix bytes

Severity: **medium**. Observed (artifact hashes from each run's `manifest.json` staged inputs).

| Evidence | Fabric jar | Paper jar | Source state |
| --- | --- | --- | --- |
| Six functional lanes 2026-09-14 04:18–04:49 UTC (fabric, paper, folia sparse; folia-sustained 09-12) | `48503110…` | `0f560e45…` | predates `2cf79579` (06:53 UTC, store/all-air correction retention) and `84f63361` |
| baseline-folia-sustained / -sparse | `bc479ccf…` | `73e8e88c…` | earlier corrected-reference build |
| Experiment baseline arm | `f5732dc9…` | `c823cbec…` | tree `4162bc39` (commit `c2283b03`) |
| Experiment candidate arm | `3f82f8d4…` | `35f9c5ee…` | tree `d9866a3d` (commit `84f63361`) |
| HEAD (all lines) | not built here | not built here | candidate + preset commit (+ R04-1 tooling on 1.21.1) |

Consequence: the plan §11 gate "comparable multi-client correctness on Fabric/Paper" has passed only on `48503110/0f560e45`, not on the measured candidate and not on HEAD. Folia correctness IS established on both measured arms: every measured run embeds the source-correctness checker (`independent_target_delivery`, `actual_payload_source`, `bounded_debt_drain`, `owning_region_overlap`) and passed with 23,056/23,056 targets, four region identities with genuine owning-region overlap, two faults per run (`slow-consumer`, `send-admission`), queue bounds true, drain ≤ 2.05 s. The gap is acknowledged in the summary's `remaining` list ("final all-five full builds", "52 final native rows including six preset runs") and in review 04; it is reported here because the final-acceptance text must not treat the six functional rows as covering the shipped bytes. The behavioural risk is small (the two later commits touch Folia-only branches; Fabric/Paper never set `probeHandoffRequired`), but the identity gap is real.

Verification: re-run the fabric and paper four-client source-correctness lanes (`check_concurrent_sources.py`) against the final HEAD artifacts and record their hashes beside the preset rows.

### R06-5 — 32/4/1 is the experiment's fixed operating point, not a measured selection

Severity: **low**. Observed.

Evidence: `effective-config-observation.json` in all six measured runs shows both arms running at `lodDistanceChunks = 32`, `generationConcurrencyLimitGlobal = 4`, `generationConcurrencyLimitPerPlayer = 1`; the server config generated for each run carries the same values; no run at the defaults (512/40/40, unchanged at HEAD on all five lines: `ServerConfigBase.java:160/328/352`, 26.2 `:162/361/385`) or at any other point exists in the evidence root. The distance 32 also bounds the fixture domain (cells at local chunk X 12–27, Z −7–8 per subject).

Consequence: the evidence supports "the candidate is within budget of the corrected reference at 32/4/1 under this workload", not "32/4/1 is conservative/safer than the defaults" or "preferable to 64/8/2". The docs are literally accurate ("The numbers come from the accepted Minecraft 26.2 V27 comparison"; `ServerConfigBase.java` "tied to the documented reference measurement"), but the commit title "measured conservative server preset" and `CommandHelp` invite the stronger reading. Preset mechanics are correct: opt-in only (`/lsslod preset conservative` → preview → `apply` → `undo`; `LSSServerCommands`/`PaperCommands` re-push SessionConfig only on a distance change; `RuntimeSettings.previewBatch/applyBatch/undoBatch` go through `scratchCopy().validate()` and the same per-key clamps, R-2 preserved; `ConservativePresetTest` pins 32/4/1 on all five lines).

Action: word `docs/operations/status-and-presets.md` and `performance.md` as "the settings under which the reference measurement ran"; do not claim a comparison across operating points.

### R06-6 — Run-to-run noise on tick delay is of the same size as the 10% budget; floors were never binding; five of thirty comparisons are the same server observation

Severity: **low** (limitation of power, not of honesty). Observed.

Evidence (recomputed): tick_delay_p95 paired differences −9.2% / +0.9% / +9.4%; tick_delay_p99 +0.2% / +1.0% / +11.3% (the documented exception: +0.216949 ms vs bound 0.1919346 ms). The three calibration runs, taken within 32 minutes, spread only 3.0% (p95) and 0.5% (p99), so the frozen floors (0.0424 / 0.0096 ms) were far below the relative term and never selected by `max(0.1·baseline, floor)`; the measured pairs then swung ~3× the calibration spread. `aggregate` repeats `server` verbatim for the four tick metrics and useful throughput (only frame p95/p99 and simultaneous RSS are genuinely aggregate), so 25 comparisons are independent; the docs note the duplication only for the exception.

Consequence: the experiment cannot separate a ~10% tick-delay regression from noise; "passed" means "no regression larger than budget plus noise was demonstrated". The rule was applied as preregistered (median 0.020 ≤ 0.211; 2 of 3 within), and thresholds were not widened — correct handling. Action: record the 25-independent count and the noise-vs-budget observation in `performance.md`; future experiments need calibration spread over more than one contiguous hour or more pairs, fixed before candidate runs.

## Reviewed and preserved

- **Loaded-probe guard** (`LoadedProbeGuard.java`, all 50 lines): epoch tokens captured before serialization on both Fabric (`RequestProcessingService.serializeCapturedProbe`, the capture-then-serialize seam pinned by `ProbeContainmentTest.capturePrecedesSerializerAndReentrantInvalidationCannotRefreshIt`) and Paper/Folia (`captureLoadedProbe` before `loadedColumnProbe.probe` on the pump and on the region thread); invalidated under `mailboxLock` before the lossless invalidation append (`OffThreadProcessor.java:374`); `current()` binds guard instance, registration, non-retired, dimension, position and token; 4096-stripe collisions only cause conservative misses (`LoadedProbeGuardTest`). The router prefers a valid snapshot probe and falls back to the paired probe only when both pass `currentLoadedProbe` (`IncomingRequestRouter.java:349-357`; `PairedProbeRoutingTest` runs the real processor thread for invalidated-snapshot, invalidated-pair, newer-snapshot-preferred and generation-outcome exclusion).
- **Late-probe lifetime/memory/ownership**: per-player fields; 512 attempts, 2,000,000 raw bytes, 10 s; created only for admitted disk submissions under `probeHandoffRequired` (`IncomingRequestRouter.java:489`), rolled back on submit refusal; discarded on invalidation apply (`OffThreadProcessor.java:880`), on `clearDiskReadDone`, on generation outcomes (`:1657`), on out-of-range, on retirement; swept at `removePlayer` (`PaperRequestProcessingService.java:1137`), at shutdown (`:2159`) and therefore on dimension change (the Paper dimension change is `removePlayer` + `registerPlayer`, `:1562-1570`). Dedup-attached members mark their own `attachment.submissionOrder()`, so per-member entries match. Fabric never creates entries or paired envelopes (`republishHeldBatch` is Folia-only), so the missing `discardProbeHandoff` in Fabric's `removePlayer` leaks nothing. `PairedIngressLifecycleTest` pins one-shot, count/byte bounds, expiry, range, dirty/retirement non-resurrection, replacement-order isolation and frontier-replacement preservation.
- **Reconnect receipt ownership**: `RegionProbeBatch` carries the registration; `consumeRegionProbes` and the `compute` merge refuse a changed state/registration or shutdown; the region task exits when `players.get(uuid) != capturedState`; client side, `ClientColumnProcessor.java:311` rejects (with `delivery.report()`) a receipt admitted by a replacement session before decoding, and `:399` reports instead of silently skipping.
- **Delivery honesty and rungs**: the header-fresh and store up_to_date branches gain only `markLateProbeDiskFallback` after the existing answer; the tscache doctrine, the two-site `stampSecond` census and the header margin are untouched (`ReadFreshnessRungTest` unchanged pins plus the correction-retention additions); corrections go through `enqueueLoadedColumn` with `cycleNow` exactly as ordinary in-memory serves, mark the done-bit after acceptance, are counted only at actual send success (`CorrectiveSendAccountingTest`: throwing sender, relevance prune and failed build scope are not counted), and the soak law A1 subtracts only the tagged successful sends with monotonic/subset guards (`check_soak.py`, selftest cases added).
- **Race pins are real, not happy-path**: `PaperPumpRouterAlignmentTest` (601 lines) drives a real `PaperOffThreadProcessor` with latches on `beforeRouteHook`/`postSnapshot`, asserting the actual wire frame order for probe-before-worker, probe-surviving-release-before-snapshot, probe-surviving-pump-overwrite, absent-callback disk fallback, late callback after body/store/header-fresh/all-air, cannot-overtake-queued-disk, target beyond the probe prefix, and frontier replacement/empty backpressure not cancelling an admitted correction. `RegionProbeSchedulingTest` pins registration-bound consumption and one task per player per tick.
- **Config refactor**: `generationLimits()` returns the validated snapshot (post-`validate()` fields) or clamped raw values on the constructor-only test seam; identical effective values.
- **Rig tooling**: `experiment.py` slot binding (intent → runtime hash → claim), terminal `record_failure`, `freeze` recomputation on `evaluate`, and alternating `ORDER` match the plan V5 text; `tools/rig` unit suites executed here: `test_performance*` 16, `test_measure*` 16, `test_assemble*` 7, `test_experiment*` 4 — all passed. `check_regions.py`/`measured_regions.py` require genuine `owning-region` samples with distinct region identities and subjects overlapping in time inside registered sessions; `check_debt.py` requires two ≥1 s-apart all-zero debt samples within 120 s of `offers_closed` with all four subjects still registered; `check_concurrent_sources.py` requires the armed slow-consumer hold on RigSubjectD with exact receipt identities, `adapter_denial_reads > 0`, the RigSubjectB reconnect successor session and four simultaneous registrations.

## Performance arithmetic re-derived

Inputs: the nine `*-result.json` reports (49 MB each) loaded individually; `tools/rig/performance.py` imported for `correctness()`, `sample_errors()` and `overlap()`; floors and comparisons recomputed in my own code; the official `comparisons` block parsed from the tail of `evaluation.json`.

- Identity: every report digest equals its `report_sha256`; every claim's `intent_sha256` equals the digest of its intent; every `measurement_context` equals its intent; run_id/run_hash match the claims; nine unique run_ids in strictly increasing time (15:54, 16:10, 16:26 calibration; 16:42 B, 16:58 C, 17:14 C, 17:30 B, 17:46 B, 18:03 C UTC); artifact identity matches the registered arm for all nine; all six shared input hashes match the registration; the three calibrations were recorded before `frozen_ns` (16:41:36 UTC) and all six measured intents were created after it and bind `digest(frozen)`; arm order B/C, C/B, B/C as preregistered.
- Sufficiency: min frame samples 17,970 (RigSubjectD, measured-1), min tick and tick-delay samples 47,999, RSS 600/600 observed for every subject in every run, warmup 120 s, measurement window 600.000 s, `errors: []` everywhere; `correctness()` and `sample_errors()` return empty for all nine.
- Correctness content per run: 5 sessions (4 subjects + server), 23,056 oracle targets all `actual == expected` with matching delivery session, max recovery after fault removal 14.1–15.2 s (deadline 120 s), 47,854–47,855 region samples over exactly 4 region identities with `overlap == True`, 80 progress windows all eligible with min useful outcomes 79–81, `queue_bounds_ok`, `cleanup_complete`, drain 1.10–2.05 s.
- Floors: recomputed max pairwise |Δ| over the three calibrations equals `frozen.json` for all 30 subject/metric rows; largest relative spread 7.17% (tick_execution_p99: 1.5999/1.5590/1.4882 ms), then 5.42% (tick_execution_p95), 3.17% (RigSubjectC RSS); all ≤ 10% of median, so `calibrate()` would not have raised.
- Comparisons: 30 rows, all `passed`, paired differences and bounds float-identical to the official block; exactly one pair outside its bound — tick_delay_p99 pair 3: candidate − baseline = 0.216949 ms vs bound 0.1919346 ms (aggregate row is the same server observation). Rule: median(Δ) 0.020309 ≤ median(bound) 0.210533 and 2 of 3 within → passed. Other notable rows: tick_execution_p95/p99 pair 2 −0.48/−0.60 ms (candidate faster), pair 1 +0.072/+0.096 ms; useful throughput Δ within ±0.4% (≈8 KB/s total, four subjects at ≈1,976–2,044 B/s — the fixture's 8 targets/s per subject); peak RSS Δ within ±2%; frame Δ within ±0.09 ms of a 33.33 ms period.
- Docs cross-check: `performance.md` "0.216949 ms above its 0.1919346 ms bound", "All 30 calibrated comparisons passed", "third pair", and the summary's `exception` block are all exact. `tested_effective_config` (SHA `5ebd6ad1…`) was not located under the execution directory by hashing the per-run effective-config files; the per-run observations themselves were read directly.
- Six functional runs (`six-functional-completion-20260914.json`): all six `passed`, sustained lanes 23,056 targets, sparse lanes 28 targets, debt drain 1.05–1.70 s, cleanup complete, `no_rebuilds`/`no_retries` true — on the artifact set described in R06-4.

## Limitations

- Half A is a source review with existing unit/thread tests as the only executed evidence; no gametest, soak or native run was performed here. R06-1 and R06-2 are code-derived hypotheses with concrete verifications, not observed failures.
- The measured workload is a light, fixture-driven edit stream (8 edits/s/subject, ≈8 KB/s useful bytes, 4 clients, Folia only, 26.2 only, Xaero World Map 1.45.0 with the bridge and backpressure enabled, 30 FPS cap, fixed 1500 M client / 2 G server heaps, EDF 4). Nothing here measures a cold backfill, a large disc, the defaults, Fabric or Paper performance, or NeoForge. Roughly 45% of served columns in each measured run came from disk (`disk.successful` ≈ 19–20 k of ≈ 44–45 k `columns_sent`), so the Folia late-correction path was exercised heavily but its share cannot be separated from the rig's metrics.
- RSS is resident process memory of pre-touched fixed heaps, so the RSS budget mostly compares native/metaspace growth, not Java heap occupancy (the docs say this).
- Jar-hash-to-source-tree provenance for the two arms rests on the preparation records (`prepared-v27-fixed-client-heap/output-manifest.json`, root review) that I did not reproduce, since that would require Gradle builds.
- Folia's experimental label is not affected by any of this: the bounded gate passed on one scenario family; it does not certify every Folia line or workload.
