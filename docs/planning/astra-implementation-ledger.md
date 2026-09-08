# Astra implementation ledger

Started 2026-09-08. Scope: the detailed [implementation plan](astra-system-review-fix-plan.md), all five support lines. Three Astra plan challenges completed; corrections incorporated. All 15 implementation packages are committed on their applicable lines; final runtime acceptance is underway. Review artifacts: `/home/vox/.local/state/lss-review/20260908-implementation/`.

## Work packages

| WI | Status | Implementation / evidence | Remaining |
| --- | --- | --- | --- |
| 1 | UNIT GREEN | 4b29afd2; interrupted policy sweep remains resumable; 2 permanent shutdown controls | Full store wrapper gates |
| 2 | UNIT GREEN | a6562743; registration-owned reads/generation/removal; ported all five lines; lifecycle selection 384 passed | Generation smoke |
| 3 | UNIT GREEN | cd0790e9 +9b3fe935; acquisition retirement, cancelled receipt guards and LAN host resumption; all five lines; focused lifecycle controls green | 1.21.1 Sodium/loader live toggle matrix |
| 4 | UNIT GREEN | d01c3be0; summary-owned proof retraction; deterministic FIFO/manager controls; all five lines | Summary smoke |
| 5 | UNIT GREEN | 0c613fca; origin-bound queue/debt/failures and acceptance leases; all five lines; final lifecycle/Xaero selection 213 passed | Xaero live teardown/backlog gates |
| 6 | UNIT GREEN | f84f9f6d; transactional OFF clear and ON full roster; all five lines; wire-to-tracker controls green | Live proxy toggle |
| 7 | UNIT GREEN | 7a674e0b; verify legacy hashes/length before translation; all five lines; migration unit suite green | Full migration wrapper |
| 8 | UNIT GREEN | e3a69850 +b2756f81; bounded one-to-one identity mapping and stationary rename; all five lines; focused controls green | None; full builds and artifacts green |
| 9 | UNIT GREEN | 1f8bb5be; both 1.21.1 renderers restore seated failure pose; real PoseStack regression green | Live injected rendering gate (fixture missing) |
| 10 | UNIT GREEN | 8729d674; report applied-but-unsaved while retaining runtime apply; all five lines incl. 26.2 per-world adaptation | Command smoke |
| 11 | UNIT GREEN | e438a633; plugin-instance/loader-owned disguise binding; isolated loader regression green; all lines | Controlled live plugin replacement (fixture missing) |
| 12 | UNIT GREEN | 6a57b37a; old holder with namespaced shutdown supported; actual resolved handle invoked in fixture; all lines | Matching Voxy live reset |
| 13 | UNIT GREEN | 851479d7; owned processes, shared lock, fresh manifests; all five lines each 21 fixtures +274 checker +20 report tests green | Genuine server harness run and validated manifest |
| 14 | IN PROGRESS | Metadata/hash inventory of 10 Prism profiles; corrected NeoForge guidance; explicit 1.21.11 C2ME profiles | Both 1.21.11 C2ME arms green; C2ME 1.21.1/1.21.10 and both 1.21.11 arms green; live UI matrix |
| 15 | UNIT GREEN | 1.21.10 e8a0326f /1.21.11 b49f62af; real wing state gametest; focused +Tier2 builds green on both | Live Elytra observation |

## Line baselines

| Line | Target | Starting merged commit | Implementation branch |
| --- | --- | --- | --- |
| 1.21.1 | support/mc1.21.1 | `1b544494d66e` | `fix/astra-review-mc1.21.1` |
| 1.21.10 | support/mc1.21.10 | `d08faa91428d` | `fix/astra-review-mc1.21.10` |
| 1.21.11 | support/mc1.21.11-v0.14 | `da726358f2c0` | `fix/astra-review-mc1.21.11` |
| 26.1 | support/mc26.1-v0.14 | `8e900e69e8ce` | `fix/astra-review-mc26.1` |
| 26.2 | main | `2b2a0df728e2` | `fix/astra-review-mc26.2` |

## Validation rules

Record exact commands, outcome, reused results, unexpected skips and evidence paths. Do not count historical probe failures or jar inspection as fixed-code validation. Do not run soaks concurrently with builds/tests. No release tag or publication is part of this implementation.

## Validation evidence to date

Evidence root: `/home/vox/.local/state/lss-review/20260908-implementation/`. The focused second run selected 669 Fabric tests; three failures were subsequently corrected (initial OFF control behavior and two server fixtures that reused a retired identity or raced an undrained outcome). `lifecycle-first.log` and `lifecycle-green-xml/` record 384 selected tests passing, zero failures/errors/skips. `paper-first.log` selected 501 tests; three plugin-loader fixture failures were corrected and `paper-corrections.log` records the affected suites passing. These are focused development results, not a claim that the final complete suites passed.

`mc12111-wing-store.log` and `mc12110-wing-second.log` record focused store/render tests and complete Fabric Tier2 passing. The first 1.21.10 compile exposed the line's Component assertion signature; the new test now uses its existing Gt seam. The 1.21.11 run preceded WI2's port and must be supplemented by the final combined run.

Independent implementation reports: `wi2-implementation.md`, `wi13-and-root-ports.md`, and `root-fix-independent-review.md` in the evidence root. Root-fix review found the WI8 stationary rename gap, closed by b2756f81 and its four ports; no other blocking finding was reported. No installed jar or historical review result counts as current live feature validation.

## First combined 1.21.1 build

Command: `CI=true JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :fabric:build :paper:build :neoforge:build vssJars -Pmod_version=0.14.0 --max-workers=1 --console=plain`. The existing development version is used for CI-style artifact validation; no release version/tag is created.

`final-1211-build.log`: Fabric Tier2 **75/75 required passed**. Fabric Tier1 **2378 tests, 5 failures, 0 errors, 4 skips**. All failures are VoxelColumnReceiverTest's recording processor overriding the old two-argument offer instead of the new receipt-bearing overload; retain the actual resync/clear/stamp assertions and update the recorder. Four skipped tools are opt-in experiments (compressed columns, store experiment, live corpus capture, settling measurement), not skipped regressions. `final-1211-first-xml/` preserves evidence. Paper/NeoForge XML in that snapshot is older output: those tasks had NOT run when Fabric failure stopped this command and must not be counted as combined-build evidence.

## Combined 1.21.1 acceptance build — green

The same CI-style command after the receiver recorder correction passed (`final-1211-second.log`, 3m16s). Fabric: **2379 selected /2375 passed /4 opt-in experiment skips**, 0 failures/errors; Paper: **501/501**, NeoForge: **23/23**. All three unit tasks executed in this run; unchanged prerequisite tasks reused Gradle outputs. Fabric Tier2 ran again: **75/75** required. Full XML and exact skipped names: `final-1211-green-xml/summary.json`.

`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :neoforge:runGameTestServer --max-workers=1 --console=plain` passed **8/8 required** (`final-1211-neogametest.log`). `python3 scripts/release_check.py --version 0.14.0` passed all six fresh LSS/VSS loader artifacts (`final-1211-release-check.log`). Both skipped plain NeoForge jar and absent test-resource tasks are expected packaging shape, not skipped NeoForge tests.

All eight selected primary soak scenarios passed their `check_soak.py --validate` preflight (`runtime-preflight.log`); no runtime scenario is claimed by that preflight. Prism clones are prepared under `lss-astra-validation-fabric-1.21.1` and `lss-astra-validation-neoforge-1.21.1`; no launch or live feature observation yet.

## Combined 1.21.10 acceptance — green

Java21/Xvfb, same CI-style full build: `final-12110-build.log` (3m16s). Fabric **2374 selected /2369 passed /5 skipped**, 0 failures/errors; the skips are the four opt-in experiments plus the deliberately unavailable modern Sodium API on this legacy-only line. Paper **501/501**, NeoForge **19/19**. Fabric Tier2 **77/77** required, including actual wing-state interpolation. `final-12110-green-xml/summary.json` records names/counts.

Explicit `:fabric:runClientGameTest :neoforge:runGameTestServer` under Java21/Xvfb passed (`final-12110-runtime-tests.log`, 1m14s). The Fabric client entrypoint exercised its real main-flow, ingest rejection/fallback and LAN-publish sequence; NeoForge **8/8** required. These are game integration checks, not Sodium-menu or seated-fault visual acceptance.

Artifact checker initially selected stale unversioned NeoForge outputs alongside the new versioned outputs (2 per brand), exposing missing new classes in the OLD jar. Archived all six old unversioned build jars to `stale-artifacts-12110/`; the intended six CI-style jars pass (`final-12110-release-check-second.log`). No checker assertion or artifact content was relaxed. One initial Xvfb command failed before Gradle because the inherited Windows PATH contained spaces; quoting PATH corrected the launch.

## Combined 1.21.11 acceptance build — green

Java21/Xvfb, same CI-style full build: `final-12111-build.log` (3m20s). Fabric **2378 selected /2374 passed /4 opt-in experiment skips**, 0 failures/errors; Paper **501/501**, NeoForge **23/23**. Fabric Tier2 **77/77** required. Exact XML: `final-12111-green-xml/summary.json`. Archived old unversioned outputs before artifact discovery using the established 1.21.10 procedure; all six intended jars pass `final-12111-release-check.log`.

At this historical checkpoint, client gametests and the two explicit C2ME arms were pending; subsequent passing runs are recorded below. A narrow Tier3 follow-up is being added on supported lines to exercise real LAN publication while reception is OFF, settings-save enable, and OFF/ON after an admitted terrain receipt; existing graphics/UI-specific deferrals remain separate.

## C2ME validation route correction — historical failed attempts

The first 1.21.11 regular-map launch failed before tests: `localRuntime` retained intermediary access-widener names in a named development runtime. Changing the umbrella to `modLocalRuntime` alone exposed its missing POM module dependencies; flattening all nested modules then incorrectly made the Java-25-only optional native module mandatory. The final shared `gradle/c2me-dev-runtime.gradle` helper on the three 1.21.x lines remaps the declared bundle modules and preserves Java21 optional-module dependency closure. It fails unknown Java predicates or an incompatible required root closure. All 26.x runtime declarations are unchanged. Independent review: `opt-in-mod-remap-review.md` in the evidence root.

The first successfully loaded runs executed **77 gametests, 75 pass /2 fail**: `c2me-12111-regular-map-fourth.log` proves alpha.0.18, `c2me-12111-benchmark-first.log` proves alpha.0.26. Remaining assertions are background split raw-serves and immediate edited-LevelChunk dirty marking. These two fixtures were subsequently corrected and both configurations passed, as recorded below; the original failures remain evidence of the route/fixture investigation. No checker expectation has been weakened and no pinned mod was upgraded.

## Additional runtime acceptance

- Extended real-client receive regression: 1.21.10 `tier3-receive-toggle-12110-third.log` passed (57s); 1.21.11 `final-12111-runtime-tests.log` passed (1m19s), including NeoForge **8/8**. The test publishes a LAN world while OFF, enables through `SaveHook.SAVE`, establishes a real one-position recovery/acceptance lease, retires OFF without additional strikes, and proves exact-position redelivery with a new manager on ON. Earlier fixture failures (test-thread cleanup and selection of the earlier C10 test's future-stamped target) are preserved separately; no product failure was inferred from them. Report: `tier3-receive-toggle.md`.
- 26.1 full Java25 build: `final-261-build.log`; Fabric **2384 selected /2380 passed /4 opt-in skips**, Paper **501/501**, NeoForge **23/23**, all zero failures/errors. Tier2 **76/76**; separate extended client+Neo runtime command passed (`final-261-runtime-tests.log`, 1m18s). Six artifacts pass release_check and independent inspection. Common classes deliberately remain Java21 even on the Java25 platform line.
- C2ME fixture diagnosis completed without production hook changes. The raw-read test now requires actual C2ME presence before asserting the adaptive replacement route, while retaining the vanilla split/raw assertions and exact byte parity. The dirty-save control waits for actual holder save eligibility, then retains one-shot edit/save assertions and explicitly invokes the real serializer on a proto chunk to exercise its negative guard. On 1.21.11 **both C2ME pins and vanilla pass 77/77** (`c2me-12111-regular-map-fixture-first.log`, corresponding benchmark/vanilla fixture logs; `c2me-gametest-fixtures.md`). The older-line ports and launch results are recorded in the next section.


## Final 26.2 build and C2ME fixture ports

`final-262-build.log` passed in 3m11s with Java25: Fabric **2394 selected /2390 passed /4 opt-in experiment skips**, Paper **511/511**, NeoForge **23/23**; zero failures/errors. Fabric Tier2 **76/76**. Exact XML: `final-262-green-xml/summary.json`. All six CI artifacts pass `final-262-release-check.log`. Explicit extended client and NeoForge runtime tests passed separately (`final-262-runtime-tests.log`, 1m14s; NeoForge **8/8**). All four available client-gametest lines pass the extended receive-toggle regression. Independent inspection passes all **30** exact CI-versioned jars (`final-artifact-inspection.md/.json`).

The C2ME-compatible gametest fixture changes are committed on all five lines: 1.21.1 `5887c45a`, 1.21.10 `28899adb`, 1.21.11 `f683bf56`, 26.1 `7caf3303`, 26.2 `9638042b`. These change test premises and line-specific test seams, not production serialization/save hooks. The 26.2 full build includes them; 26.1's earlier build precedes them and needs a Tier2 control. C2ME 1.21.10 passes **77/77**. C2ME 1.21.1 passes the two corrected fixtures but its separate dedup test fails the content-count assertion (**74/75**, `c2me-1211-fixture-second.log`); this remains under investigation and is not classified as a benign timing flake.


## Remaining runtime acceptance — current state

The completed build, unit, Tier2/Tier3 and artifact gates do not stand in for the following live observations. Exact commands, prerequisites, profiles and pass criteria are in `remaining-runtime-matrix.md` in the evidence directory.

| Gate | State | Required next evidence / reason |
| --- | --- | --- |
| Supervised benchmark smoke | RUNTIME GREEN | Both producers exit0; validated exports and complete matching manifest; 20260908T215639-4fad536d11ef49519da93e18e17c424d |
| Fabric store migration wrapper | IN PROGRESS | Complete downgrade/migration/warm chain and cross-phase checker |
| Paper offline edit wrapper | NOT STARTED | Complete populate/mutate/verify chain with changed edited probe and unchanged control |
| Fabric generation stress + disabled | NOT STARTED | Both complementary admission/conservation scenarios |
| Fabric dimension rejoin + dirty offline summary | NOT STARTED | Live transport/session transitions and false-clean canary |
| 26.2 Folia store-second-join | NOT STARTED | Representative regionized lifecycle; remains experimental, not multi-region evidence |
| 1.21.1 modern/legacy Sodium × Fabric/NeoForge | IN PROGRESS | Isolated profiles prepared; actual menu Apply OFF/ON and initial-OFF join not observed yet |
| Xaero debt/backlog reconnect | NOT STARTED | Same-dimension server replacement with observable pending work, both 1.21.1 loaders |
| Stationary far-player mode | NOT STARTED | Owned dummy subject and real client; OFF removes and ON restores proxy |
| Matching Voxy reset | NOT STARTED | Verify storage stays inside disposable clone, then ordinary reset/probe and ingest resumption |
| Injected seated renderer failure | DEFERRED | Both 1.21.1 loaders need an isolated runtime exception injector; actual PoseStack regression is green but is not a live-render claim |
| Disguise replacement | DEFERRED | No controlled supported single-plugin replacement fixture preserving LSS; real defining-loader regression is green |
| Equipped Elytra visual | DEFERRED | 1.21.10/1.21.11 real animation-state gametests pass; equipped-player visual observation not performed |
| Wide hybrid-boundary frontier | DEFERRED | Distinct 30-minute large-distance gate; selected small-radius scenarios cannot establish it |

Root owns the unresolved gates. No deferred item is represented as RUNTIME GREEN or DONE. No original Prism instance, regular real-map server, release tag or published artifact has been changed.


## Final test-premise controls — green

The first C2ME 1.21.1 dedup failure did not recur in its diagnostic run; that run instead exposed a second disk-fallback fixture without a proven persisted-content premise. Neither first failure is claimed conclusively diagnosed from an outcome partition that was not captured. The bounded `SavedColumnFixture` now verifies actual held-holder save eligibility, performs one flush save and one batch of setup reads, and requires FULL plus saved terrain before releasing tickets or admitting measured requests. It submits no service-reader work and preserves the exact three deduped reads/content and probe-budget assertions. This additional fixture is confined to 1.21.1, where the failures were observed.

After the change: C2ME alpha.0.27 **75/75** (`c2me-1211-persisted-premise-first.log`, 15s), vanilla 1.21.1 **75/75** (`c2me-1211-persisted-premise-vanilla.log`, 41s). Final 26.1 Tier2 control after the earlier fixture port **76/76** (`final-261-fixture-tier2.log`, 36s). No production source changed after the accepted artifact builds. The real benchmark smoke began at 2026-09-08T21:56:39Z; all long harness gates run serially with the task's idle Gradle daemons stopped, preserving the normal server on 25564.


## Genuine benchmark acceptance — green

`runtime/run21.sh ./scripts/benchmark.sh fresh 60` exited0. Both server/client producers exit0, required fresh metrics validate, both optional JFRs exist, and `current.json` exactly equals the completed run manifest. `benchmark-results.py record` independently validates the published identity and exports; evidence is `runtime/1211-benchmark-fresh.log`, `runtime/1211-benchmark-smoke/benchmark-manifest.json`, and immutable worktree `benchmark-results/runs/20260908T215639-4fad536d11ef49519da93e18e17c424d/`. The manifest captures HEAD5887c45a at start; the concurrent later commit e176ac7d only records already-present gametest fixtures and does not change benchmark production sources. This is lifecycle/provenance validation, not an isolated performance comparison with the regular server running.

Additional primary test-only premise commit: `e176ac7d`; its report and all three final controls are recorded above. It postdates the port-map snapshot and is deliberately confined to 1.21.1. The selected real soak queue started with the full migration wrapper after the benchmark exited; there is no overlapping unrelated build/test/client lane.
