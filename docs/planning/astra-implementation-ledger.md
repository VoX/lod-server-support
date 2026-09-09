# Astra implementation ledger

Started 2026-09-08. Scope: the detailed [implementation plan](astra-system-review-fix-plan.md), all five support lines. Three Astra plan challenges completed; corrections incorporated. All 15 implementation packages are committed on their applicable lines; required targeted runtime acceptance is complete. Optional and unsupported coverage limits are listed below. Review artifacts: `/home/vox/.local/state/lss-review/20260908-implementation/`.

## Work packages

| WI | Status | Implementation / evidence | Remaining |
| --- | --- | --- | --- |
| 1 | RUNTIME GREEN | 4b29afd2; interrupted policy sweep remains resumable; 2 permanent shutdown controls | None; full migration and Paper offline-edit chains pass |
| 2 | RUNTIME GREEN | a6562743; registration-owned reads/generation/removal; ported all five lines; lifecycle selection 384 passed | None; corrected Folia and primary generation gates pass |
| 3 | RUNTIME GREEN | cd0790e9 +9b3fe935; acquisition retirement, cancelled receipt guards and LAN host resumption; all five lines; focused lifecycle controls green | None; all four Sodium/loader OFF/ON and initial-OFF joins pass |
| 4 | RUNTIME GREEN | d01c3be0; summary-owned proof retraction; deterministic FIFO/manager controls; all five lines | None; dirty-summary and dimension/rejoin pass |
| 5 | RUNTIME GREEN | 0c613fca; origin-bound queue/debt/failures and acceptance leases; all five lines; final lifecycle/Xaero selection 213 passed | None; positive-backlog server switch passes both loaders; exact race schedules remain deterministic |
| 6 | RUNTIME GREEN | f84f9f6d; transactional OFF clear and ON full roster; all five lines; wire-to-tracker controls green | None; stationary proxies visibly disappear/restore on both loaders |
| 7 | RUNTIME GREEN | 7a674e0b; verify legacy hashes/length before translation; all five lines; migration unit suite green | None; live all-air canary caveat retained below |
| 8 | DONE | e3a69850 +b2756f81; bounded one-to-one identity mapping and stationary rename; all five lines; focused controls green | None; full builds and artifacts green |
| 9 | RUNTIME GREEN | 1f8bb5be; both 1.21.1 renderers restore seated failure pose; real PoseStack regression green | None; both loaders pass strict same-frame injected failure proof |
| 10 | RUNTIME GREEN | 8729d674; report applied-but-unsaved while retaining runtime apply; all five lines incl. 26.2 per-world adaptation | None; all three platforms denied-save and recovery green |
| 11 | DONE; OPTIONAL LIVE DEFERRED | e438a633; plugin-instance/loader-owned disguise binding; isolated loader regression green; all lines | Controlled live plugin replacement (fixture missing) |
| 12 | RUNTIME GREEN | 6a57b37a; old holder with namespaced shutdown supported; actual resolved handle invoked in fixture; all lines | None; contained ordinary reset and fresh ingest pass both loaders |
| 13 | RUNTIME GREEN | 851479d7; owned processes, shared lock, fresh manifests; all five lines each 21 fixtures +274 checker +20 report tests green | None; real benchmark + full wrappers pass |
| 14 | DONE | Metadata/hash inventory of 10 Prism profiles; corrected NeoForge guidance; explicit 1.21.11 C2ME profiles | C2ME and four UI combinations green; rejected native Neo Voxy0.2.9 trial documented separately |
| 15 | RUNTIME GREEN | 1.21.10 e8a0326f /1.21.11 b49f62af; real wing state gametest; focused +Tier2 builds green on both | None; both lines actual stand/crouch/glide/landed recovery observed |

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

At this historical checkpoint, client gametests and the two explicit C2ME arms were pending; subsequent passing runs are recorded below. A narrow Tier3 follow-up was subsequently added and passed on supported lines to exercise real LAN publication while reception is OFF, settings-save enable, and OFF/ON after an admitted terrain receipt; existing graphics/UI-specific deferrals remain separate.

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


## Runtime acceptance — current state

The completed build, unit, Tier2/Tier3 and artifact gates do not stand in for the following live observations. Exact commands, prerequisites, profiles and pass criteria are in `remaining-runtime-matrix.md` in the evidence directory.

| Gate | State | Required next evidence / reason |
| --- | --- | --- |
| Supervised benchmark smoke | RUNTIME GREEN | Both producers exit0; validated exports and complete matching manifest; 20260908T215639-4fad536d11ef49519da93e18e17c424d |
| Fabric store migration wrapper | RUNTIME GREEN | Both phases and wrapper pass; 1960 real rows migrated, clean integrity; all-air live canary caveat below |
| Paper offline edit wrapper | RUNTIME GREEN | Auto-prime plus full three-phase wrapper pass; edited hash changes, control stays identical |
| Fabric generation stress + disabled | RUNTIME GREEN | Both scenarios pass; exact registration races remain deterministic-test evidence |
| Fabric dimension rejoin + dirty offline summary | RUNTIME GREEN | Both scenarios and two-client transitions pass, zero checker violations/warnings |
| 26.2 Folia store-second-join | RUNTIME GREEN | Corrected full auto-prime40/39/40 and warm29/27/29 pass; exact semantic setup proof below |
| 1.21.1 modern/legacy Sodium × Fabric/NeoForge | RUNTIME GREEN | All four actual menu OFF/ON and initial-OFF joins pass; accepted Neo legacy trial is Xaero-only |
| Xaero debt/backlog reconnect | RUNTIME GREEN | Positive queue/pending before kick, second-server fresh ingest and settled debt on both loaders |
| Stationary far-player mode | RUNTIME GREEN | Both loaders visibly remove/restore two stationary subjects without reconnect |
| Matching Voxy reset | RUNTIME GREEN | Both matching Voxy profiles ordinary-reset contained roots and ingest2244 fresh bodies |
| Injected seated renderer failure | RUNTIME GREEN | Fabric pass677 /Voxy pass2821; Neo pass3; next proxy and two tags retain restored matrices before outer unwind |
| Disguise replacement | DEFERRED | No controlled supported single-plugin replacement fixture preserving LSS; real defining-loader regression is green |
| Equipped Elytra visual | RUNTIME GREEN | Both lines real20TPS glide1b/landed0b and observed wing pose changes; close-up evidence and limits recorded |
| Wide hybrid-boundary frontier | DEFERRED | Distinct 30-minute large-distance gate; selected small-radius scenarios cannot establish it |

Required targeted runtime gates are complete. Deferred live coverage is explicitly separate from completed implementation; no deferred live gate is represented as RUNTIME GREEN. No original Prism instance, regular real-map server, release tag or published artifact has been changed.


## Final test-premise controls — green

The first C2ME 1.21.1 dedup failure did not recur in its diagnostic run; that run instead exposed a second disk-fallback fixture without a proven persisted-content premise. Neither first failure is claimed conclusively diagnosed from an outcome partition that was not captured. The bounded `SavedColumnFixture` now verifies actual held-holder save eligibility, performs one flush save and one batch of setup reads, and requires FULL plus saved terrain before releasing tickets or admitting measured requests. It submits no service-reader work and preserves the exact three deduped reads/content and probe-budget assertions. This additional fixture is confined to 1.21.1, where the failures were observed.

After the change: C2ME alpha.0.27 **75/75** (`c2me-1211-persisted-premise-first.log`, 15s), vanilla 1.21.1 **75/75** (`c2me-1211-persisted-premise-vanilla.log`, 41s). Final 26.1 Tier2 control after the earlier fixture port **76/76** (`final-261-fixture-tier2.log`, 36s). No production source changed after the accepted artifact builds. The real benchmark smoke began at 2026-09-08T21:56:39Z; all long harness gates run serially with the task's idle Gradle daemons stopped, preserving the normal server on 25564.


## Genuine benchmark acceptance — green

`runtime/run21.sh ./scripts/benchmark.sh fresh 60` exited0. Both server/client producers exit0, required fresh metrics validate, both optional JFRs exist, and `current.json` exactly equals the completed run manifest. `benchmark-results.py record` independently validates the published identity and exports; evidence is `runtime/1211-benchmark-fresh.log`, `runtime/1211-benchmark-smoke/benchmark-manifest.json`, and immutable worktree `benchmark-results/runs/20260908T215639-4fad536d11ef49519da93e18e17c424d/`. The manifest captures HEAD5887c45a at start; the concurrent later commit e176ac7d only records already-present gametest fixtures and does not change benchmark production sources. This is lifecycle/provenance validation, not an isolated performance comparison with the regular server running.

Additional primary test-only premise commit: `e176ac7d`; its report and all three final controls are recorded above. It postdates the port-map snapshot and is deliberately confined to 1.21.1. The selected real soak queue started with the full migration wrapper after the benchmark exited; there is no overlapping unrelated build/test/client lane.


## Fabric migration wrapper — green

Full `SOAK_PLATFORM=fabric ./scripts/store_migration_gate.sh` exited0, 2026-09-08T21:58:18Z–22:04:46Z. Population: **30 windows /29 client-law windows /30 quiescent snapshots**, no violations/warnings. Migration: **36/35/36**, no violations/warnings. Wrapper verifies downgrade, client join during held migration, completion and zero `DELETED` anomalies. Final **1960 store hits /0 disk submissions /0 store errors /2144 client columns /0 ingest failures**. Post-exit read-only SQLite `quick_check=ok`, schema4, all1960 rows wirefmt20, no migration metadata. All owned phase processes exited.

Scope limit: the pre-existing all-air canary at(1000000,1000000) lies in a nonexistent region, so startup sweep removes it before migration. Thus1961 pending→1960 real rewritten rows does not prove live all-air migration; the permanent unit controls remain that evidence. Exact source/log lines and ancestry: `runtime/store-migration-monitor.md`. This ordinary migration chain also does not inject interrupted policy invalidation or corrupt rows; those WI1/WI7 invariants are covered by their deterministic regressions.


## Paper offline-edit wrapper — green

Full `SOAK_PLATFORM=paper ./scripts/store_offline_edit.sh` exited0, 2026-09-08T22:04:46Z–22:17:13Z, including fresh-backfill auto-prime. Prime **41/40/41** windows/client-law/quiescent; population **30/29/30**; mutation **9/8/9**; verification **30/29/30**. Every checker reports zero violations/warnings. Mutation acknowledges real forceload, glowstone edit and save with zero LSS requests/serves. Cross-phase wrapper: edited probe20:0 changes **-8582001278133590983→-7005409826721539322**; control-20:0 remains **-8582001278133590983**. Verify serves **1847 warm store hits +297 successful disk refreshes**, no errors, **2144 received columns**, no ingest failures/in-flight residue. Post-exit SQLite integrity passes with all2144 rows in wire20; all owned phase processes exit. Exact receipts and artifact checks: `runtime/paper-offline-monitor.md`.


## Remaining primary soaks — green

All four final serial scenarios exited0 with no reruns. Generation-capacity-stress: **18/17/18** windows/client-law/quiescent, zero violations/warnings; cap1, **185/185** generations complete, **17261** transient superseded/miss-dropped declarations, zero timeouts or permanent NOT_GENERATED replies. Generation-disabled: **35/34/35**, zero violations/warnings; no generation work, **1996** terminal missing-terrain replies plus **363** available columns, no in-flight/ingest failures.

Dimension-rejoin-warm: **62/58/61**, zero violations/warnings. Run1 saves **2144** overworld and **103** End cache entries. Run2 loads both corresponding cache files; each dimension segment validates **2144** positions without terrain body downloads. Both clients exit cleanly. Dirty-while-offline-summary: **52/48/51**, zero violations/warnings, full offline edit/rejoin timeline; both clients and server exit cleanly. The dirty target timestamp advances by **65**, control stays unchanged; rejoin validates **1049 columns /13 clean tiles /3 stale tiles /9 no-region tiles** and downloads only the changed column. Exact counters, receipts, JSONL integrity and process cleanup are recorded in `runtime/primary-remaining-soaks-monitor.md`.

After these soaks, the 1.21.10 default Tier2 control for the later test-only fixture port passed **77/77** (`final-12110-fixture-tier2.log`, 42s). This complements its already-green C2ME arm without repeating unchanged unit suites. The independent WI9 fixture build starts only after this control exits; no product jar is modified by that fixture.


## Actual Paper and NeoForge settings persistence — green

Both isolated 1.21.1 server platforms passed the real command sequence: save ON, deny writes to the disposable config directory, apply OFF and observe accurate applied-but-not-saved feedback, separately list effective OFF while disk remains byte-identical ON, restore directory permissions, save ON, separately verify effective/disk ON, and stop cleanly. Paper and NeoForge exact candidate hashes and original/failure/final config receipts are in `platform-command-runtime.md` and its JSON evidence. No player was connected: this proves runtime application and persistence feedback, not graphical roster removal. Fabric's corresponding live command check subsequently passed; see the dated GUI results below.

## Additional harness correctness fix — all five lines

The first real 26.2 Folia auto-prime exposed rejected legacy gamerule names that the exporter incorrectly marked successful. The attempt was stopped with exit143 and archived; it is not accepted. Dev-only executors now require actual command parsing, and gamerules require a successful setter callback plus exact per-world readback. Generic dispatch and Folia's deliberate save-all no-op are labeled separately. Modern names apply on 1.21.11/26.1/26.2; obsolete spawnChunkRadius steps are removed starting at 1.21.10, whose other rule names remain legacy. Real command-tree tests found and verified that additional axis.

Commits: 1.21.1 `49c8c689`, 1.21.10 `948b0bf0`, 1.21.11 `8ac799a9`, 26.1 `1e7c9a9d`, 26.2 `98b67abc`. Each line passes six focused unit tests and 280 checker selftests; full server gametests pass respectively **78, 80, 80, 79, 79** (396 total). All33 scenarios validate per line. Independent Astra review found no blocker. Full details: `soak-command-fix.md` and `scenario-command-validation-review.md`. These helpers stay outside release jars; no product delivery code or accepted release artifact changes.

Earlier primary soak passes above retain their original harness/checker provenance. Their valid legacy command feedback was observed, but old JSONL lacks the new semantic-proof marker and cannot independently pass the stricter current checker. The rejected Folia recording is explicitly rejected by that checker. Corrected live Folia acceptance is running separately; no old recording has been rewritten.


## Corrected Folia full-chain acceptance — green

Corrected 26.2 Folia `store-second-join`, including fresh-backfill auto-prime, exited0 (root session10111). Fresh phase40 windows/39 client-law/40 quiescent; warm29/27/29; both zero violations/warnings. All five setup gamerules carry successful per-dimension setter/query readback. The warm cache-clear wave adds2125 SQLite hits and zero disk reads; both byte probes stay identical. Final2144-row V20 store passes read-only integrity and schema checks after shutdown. All owned processes exit; regular server489815 remains. Reports `folia-store-monitor.md` and `folia-store-corrected-proof.json` preserve exact identities, hashes, and the rejected first attempt. This scenario uses its defined mid-session client-cache clear; it is not a second TCP login or concurrent multi-region Folia proof.

Fabric real command denial/recovery also passed at23:39:10–28Z, completing WI10's three-platform live feedback/application check. Disposable config directory permissions are restored0755, final disk/effective farPlayers ON. Config snapshots and `gui-server-a-console.log` accompany the live monitor.


## First actual client gates — Fabric

Both 1.21.1 Fabric Sodium generations passed actual page Apply OFF/ON and initial-OFF join/enable. Modern0.8.13-beta.2 observer AstraValidator froze server requests101254 during OFF; legacy0.6.13 observer LssSubjectB froze77716. Both remained connected/inactive and resumed negotiation/request processing on Apply ON. Separate OFF joins had no LSS registration until enabled; accepted map/cache data survived. Exact logs, UTC actions and screenshots are under `runtime/gui-fabric-*` and the immutable `runtime/gui-logs/` checkpoints. Windows clients require GUI direct-connect `[::1]:port` here; Prism's CLI quick-play address strips the IPv6 brackets and is not used as a successful connection route.

Modern Fabric Xaero teardown smoke: at23:37:09Z queue69/pending_updates140 immediately preceded a server kick. Joining second fixture B in the same MC overworld produced2244 fresh columns; subsequent queue/pending/owed gauges were0 with no ingest/commit failures. written8679 is cumulative, not all attributable to B. This is observable backlog/reconnect integration; exact paused-worker identity schedules remain deterministic-test evidence.

Controlled Fabric seated failure passed in actual renderer pass677: two real proxy draws armed the fixture, one exception fired after the dispatcher's real push/translate, next proxy and two tag draws saw the restored sentinel/matrices, outer unwind succeeded, global crash latch remained false. First injector launch failed because its helper shared a Mixin-owned package; the external fixture was corrected, rebuilt and package-verified, with failed logs preserved. No production edit was involved. Vanilla fog hides these160-block proxies in the Xaero-only profile despite successful draw-path recovery; a separately recorded matching Fabric Voxy configuration is being used for the visible toggle gate. NeoForge injection subsequently passed; see the final GUI results below.

## Final GUI acceptance — 2026-09-09

See [GUI evidence report](../reviews/2026-09-08-implementation/gui-live-monitor.md) for exact timestamps, hashes, accepted and rejected attempts. All four actual Sodium/loader UI combinations pass reception OFF/ON and initial-OFF join. The accepted NeoForge legacy trial is Xaero-only: native Voxy0.2.9-alpha raised an ingest exception and did not close cleanly, so that pairing remains an explicitly unvalidated coverage gap. The error is not conclusively attributed to LSS or upstream. Modern NeoForge uses the metadata-verified Fabric Voxy0.2.15 Connector pairing.

Both loaders pass positive-backlog Xaero abrupt server replacement, contained ordinary Voxy reset with2244 fresh body deliveries, and visible two-subject stationary far-player OFF/ON. Fabric strict seated fault passes677 and2821 used fixture04289fbd…; Neo strict pass3 at00:20:49 used SLF4J fixturec1ac8a05…. Each proves next-proxy and both tag transforms before outer unwind, with no crash latch. The prior no-marker Neo attempt is not accepted. Injectors were removed after clean client exits.

Both1.21.10 and1.21.11 actual equipped-Elytra targets visibly change wings while crouching and gliding, then restore standing wings after landing. Native FallFlying readback is1b during real20TPS glide and0b after return to the grounded fixture.1.21.10 has supplementary5TPS close-up pose evidence; later lost-subject/frozen captures are excluded and do not establish animation timing. Permanent gametests own exact once-per-tick advancement. All Elytra fixtures and subjects closed cleanly; original real-map server remains untouched.

Final-review follow-up closed with external opt-in fixtures: both loaders applied the real menu catalog setter/SaveHook on a tick with queued=1 and native pending_updates=1. OFF retired the manager and acquisition queue while preserving that committed native update; the update naturally rebuilt while reception remained OFF. ON initiated fresh real negotiation, obtained a different manager under the same world/connection/cache key, and increased received bodies and native writes. Fabric PASS at00:40:56 (24,592ms OFF); Neo PASS at00:43:40 (21,994ms OFF). No artificial queue or bridge repair was used. One Fabric reset command while OFF only armed confirmation; no confirmation/reset occurred.

The real Fabric server adapter then declined549 identical empty clear frames (epoch3) while farPlayers was OFF. After ON it accepted the replacement nonempty roster and subject update;100 real server ticks passed without any obsolete clear attempt. PASS at00:42:08, with client epoch3/tracked1/drawn1/tags1 and a visible proxy. This is controlled **send-admission denial**, exercising the broadcaster's actual boolean contract; it does not claim physical Netty saturation. Normal visible OFF/ON was separately observed on both loaders. Filesystem-denied config saves are not used as network evidence.

See [controlled lifecycle proof](../reviews/2026-09-08-implementation/controlled-lifecycle-live.md). All runtime fixture mods/arming markers were removed from the disposable clients/server after use. Final fixture server saved and stopped with exit0; Linux dummy exited0; original server PID489815 on25564 remained alive. No release, merge, ordinary-rig deployment or publication was performed.
