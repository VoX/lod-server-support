# Astra bounded verification of September 16 rig fixes

Date: 2026-09-19. Reviewer: GPT-6 Astra independent review agent.

Result: **one residual low-severity actionable finding: R05-4's PID-reuse ownership gap is not closed.** The existing bounded-stop, exited-Xvfb, environment and loader-route regression checks pass. This review found no new blocker in the inspected changes; it is not a native acceptance run or a review of the entire improvement program.

## Scope and identity

Read the current `CLAUDE.md`, `docs/reviews/2026-09-14-final/05-rig.md`, both complete fix diffs, and the surrounding supervisor/runner/materialization code and tests. Focus: `8811d8d1` (R05-1..R05-5) and `0d14bcd7` (loader-route admission). Postbuild base: `0ae20c5f`. Representative HEAD: `2e4790aae752486d8d745d91c6ac1ca39b4fcb13`.

| Line | Inspected HEAD |
|---|---|
| 1.21.1 | `2e4790aae752486d8d745d91c6ac1ca39b4fcb13` |
| 1.21.10 | `bdc1f69ec2ef3c77a102269e773d562226da7024` |
| 1.21.11 | `50f518365fc04bb4447b38b4306f39cdeea84e0c` |
| 26.1 | `3eaa2b9a439180e5ec08e3ee8ad6013037a74924` |
| 26.2 | `d3a5bcd03498b2e80e484a28da78a2b08682d560` |

All five HEADs have identical trees for the reviewed tooling:

- `tools/rig`: `a8a125d00f5004114e9975029b9bcf67b6a8efab`
- `tools/compat`: `537ddb0c6e182a718d459b266860a90a315cfc46`
- `scripts/lib`: `2d2eb86d7448b8f55f7ab316e62a228de2b36717`

The representative worktree already contained recovery/evidence changes. No maintained worktree file was edited by this review. Tests ran with `PYTHONDONTWRITEBYTECODE=1`. No Java, Gradle, Xvfb, Prism, game, PowerShell or real native rig was launched. Existing unit tests launched their bounded synthetic fixture processes only. The additional PID-reuse control mocks every signal and launches no processes.

## Actionable finding: R05-4 remains open — nonempty numeric PGID does not establish ownership

**Severity: low; pre-existing hazard incompletely repaired, not a newly introduced regression.**

Locations: `scripts/lib/owned-process.py:29` (`group_members`), `:44` (`signal_group`), and the escalation call in `main` around `:167`.

`signal_group(pgid, sig)` scans `/proc` for any process whose current pgrp equals the old direct child's numeric PID, then calls `os.killpg(pgid, sig)`. It neither checks that the original direct child remains unreaped nor ties the observed group to a retained process identity or to the supervisor's children.

If the old direct child/group has disappeared and its ID has been reused by a foreign session/group leader, the new group is nonempty and passes this check. The supervisor still sends SIGTERM/SIGKILL to that foreign group. The scan also does not remove the scan-to-signal race. Therefore the exact PID-reuse premise of R05-4 remains possible despite the new empty-group test.

**Evidence boundary:** actual kernel PID wrap/reuse was not induced. The observed reproduction is a deterministic mocked `/proc` snapshot of that state: a foreign replacement with PPID 98765, PGID/session 4242 and a new creation time is accepted, and the mocked `killpg` receives `(4242, SIGKILL)`. No real signal is sent. Low probability under ordinary host PID limits is unchanged from the original review.

Retained runnable control:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 /home/vox/.local/state/lss-project-improvements/recovery-20260919/test_astra_rig_review_controls.py
```

Observed: **2/2 controls pass**, confirming both (a) an empty reaped group is skipped and (b) a foreign replacement group is still authorized. The second control asserts the observed defective behavior; it must be inverted when the repair is applied.

### Minimal fix proposal

At the escalation call site, gate group signalling on an immediate fresh poll:

```python
if group_signal != sig:
    if proc.poll() is None:
        signal_group(proc.pid, sig)
    group_signal = sig
```

Once the direct child is reaped, rely on the existing adopted-children loop. An unreaped direct child retains its PID, including if it exits between the poll and signal. In this single-threaded supervisor there is no intervening wait/reap between the poll and `killpg`. Use `proc.poll()`, not merely `proc.returncode`: the general `waitpid` loop can reap the direct child after an earlier poll.

A cleaner equivalent makes the helper itself enforce the condition and removes the now-unnecessary numeric group scan:

```python
def signal_group(proc, sig):
    if proc.poll() is not None:
        return False
    try:
        os.killpg(proc.pid, sig)
    except ProcessLookupError:
        return False
    return True
```

Then pass `proc`, rather than `proc.pid`, at the call site. Either approach is sufficient; no other supervisor redesign is needed for this finding.

Regression control for the helper variant:

```python
proc = MagicMock(pid=4242)
proc.poll.return_value = 0  # Original leader reaped; numeric PGID may be foreign.
with patch.object(module.os, 'killpg') as kill:
    assert module.signal_group(proc, signal.SIGKILL) is False
    kill.assert_not_called()
proc.poll.return_value = None  # Direct child is still unreaped.
with patch.object(module.os, 'killpg') as kill:
    assert module.signal_group(proc, signal.SIGTERM) is True
    kill.assert_called_once_with(4242, signal.SIGTERM)
```

Retain a control for escaped owned children after leader exit, since that cleanup now intentionally relies entirely on the adopted-children path. The parent agent requested that application wait until the fixture batch finishes because current fixtures bind tooling bytes; no repair has been applied by this reviewer.

## Verified changes and tests

From `1.21.1/tools/rig`:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest -v test_supervisor_stop test_private_display_context test_launch_environment test_metric_schema
```

**18 tests passed in 4.632 s.** The signal control runs the real ownership supervisor with a synthetic runner and a fixture that needs four seconds to exit; it verifies terminal failed journal/manifest, an issued cleanup receipt, and no remaining fixture. It does not exercise a Minecraft save or the complete shell-wrapper/real-runner path. Private-display controls reject an already-exited purported Xvfb and a live non-Xvfb process before display adoption. Launch controls verify inherited credential-named/ownership/display keys are removed and the recognized GUI launch is refused without a private display. Schema tests pin documented values to current checker code.

From `1.21.1/tools/compat`:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest -v test_materialize
```

**21 tests passed in 0.046 s.** The changes correctly require a descriptor for the selected route, require plugin metadata on Paper/Folia, ignore foreign Neo descriptors/jarjar dependencies on Fabric, retain Fabric-declared nested dependencies, choose native metadata on NeoForge, and retain Neo jarjar dependency discovery. No actionable defect was found in this bounded loader-route change.

Two additional offline ownership controls passed in 0.001 s as described above. Total executed: **39 existing targeted tests plus 2 additional diagnostic controls**. The full 621-test rig suite and live acceptance matrix were not rerun by this reviewer. Optional improvements are not being proposed in this bounded completion review.

## Staged repair requested by the parent agent

The Popen-based helper variant above is staged, **not applied to any maintained worktree**, in:

- Patch: `/home/vox/.local/state/lss-project-improvements/recovery-20260919/astra-rig-owned-group.patch`
- Preview tree: `/home/vox/.local/state/lss-project-improvements/recovery-20260919/astra-rig-patch-preview/`

The patch changes only `scripts/lib/owned-process.py` and `tools/rig/test_supervisor_stop.py`. It removes the numeric group-membership scan, polls the retained direct-child Popen immediately before group signalling, preserves live-group escalation, adapts the existing real-process control, and adds controls for a reaped leader with stale `returncode` and for live-leader SIGKILL escalation.

Validation against the staged supervisor (the original repository's rig modules were supplied on `PYTHONPATH`; the supervisor/test paths were redirected to the preview): **4 supervisor tests passed in 4.223 s**, including the four-second signal-stop/cleanup-receipt fixture. The existing **escaped-child cleanup test passed in 5.142 s** against the staged supervisor, confirming that adopted-child cleanup still works after the leader exits. These are five additional test executions beyond the 41 executions reported above. `git apply --check` succeeds on all five maintained worktrees. Actual application and any subsequent maintained-tool identity refresh remain with the parent agent after fixture jobs finish.


## Native execution follow-ups

# Connector native-container regression review

The R04 top-descriptor guard rejected the exact supported Connector full distribution before native launch. Its outer archive intentionally carries only `META-INF/jarjar/metadata.json`; the `connector` native mod descriptor is in its declared nested mod archive. Rejecting the outer archive also removed its bundled Fabric Loader identity and generated secondary missing-dependency errors.

Exact examined artifact: `connector-2.0.0-beta.14+1.21.1-full.jar`, SHA256 `a79817f1076e2e6499b0ab09c4e8c032d04b071341b66d036c3d96112804e51b`. The declared nested native descriptor resolves `connector` version `2.0.0-beta.14+1.21.1`; the outer manifest declares Fabric Loader `0.18.4` and actually packages `FabricLoaderImpl.class`. The outer archive declares and packages `org.sinytra.connector.locator.ConnectorEarlyLocatorBootstrap` as a NeoForge `IModFileCandidateLocator` service.

The staged patch is `connector-native-container.patch`. It admits a descriptorless outer archive only on NeoForge when its declared jarjar closure contains an actual native mod and its root is loader-discoverable through FML library metadata or a packaged native candidate-locator service provider. The existing bounded traversal is reused with Fabric-declared child edges disabled during this admission check. Normal dependency traversal remains unchanged. The actual loader remains the runtime acceptance authority; packaged service-class presence is a metadata preflight, not verification of arbitrary provider bytecode behavior.

Native discovery was checked against primary FML 4.0.44 sources retained in `fml-loader-4.0.44-sources.jar` from https://maven.neoforged.net/releases/net/neoforged/fancymodloader/loader/4.0.44/loader-4.0.44-sources.jar. `ModDiscoverer` loads candidate locator services; the service discovery constants admit that interface into the service layer; native readers admit native mod metadata and explicit FML library types; jarjar dependency discovery reads declared children.

Verification:

- All 25 staged `test_materialize` tests passed, including synthetic native-container positives and negative controls for wrong loader, missing discovery, missing provider class, undeclared child, empty native metadata, Fabric-only child, top-level Fabric wrapper, and Fabric-declared native grandchild. Existing wrong-loader and dual-descriptor controls remain passing.
- The patch applied cleanly in dry-run to all five worktrees.
- Exact real Connector bytes passed native-container admission and native identity extraction.
- Python-only full-profile and profile-plus-candidate resolution removed all descriptor and missing-dependency errors; only six preexisting unsupported complex-range results remained, without being called ready.
- Root subsequently ran `actual-profile-preflight.py --real-ranges` against the applied maintained bytes. Retained `actual-profile-real-ranges.json` reports **ready=true, errors=[], missing=[]** for both the six-artifact profile and seven-artifact profile plus LSS candidate. The checked materialize SHA256 is `222c9b18aa1fba04c1ec8bb185e991fb385200163857bab6c70f507e1bcb8e67`.

Additional route review found no new bypass: Fabric admission still requires its own root descriptor; Paper/Folia follow their separate plugin guard; Fabric-only jarjar children cannot establish native admission even on a Connector route; service presence without a declared native mod is insufficient. Native dual-descriptor behavior and dependency range evaluation are unchanged.

No product Java/build output or cached runtime artifact was changed by this review. The subagent staged outside maintained worktrees; root coordinated application. Native modern-Neo launch acceptance remains a separate live gate and must be recorded independently.

Reproduce the actual profile preflight after application (this command invokes the existing Java range helper):

```sh
PYTHONDONTWRITEBYTECODE=1 python3 /home/vox/.local/state/lss-project-improvements/recovery-20260919/connector-nested-fix/actual-profile-preflight.py --real-ranges --output /tmp/connector-preflight-new.json
```

The output is created exclusively, preserving existing evidence. Add `--preview` to check the staged module instead.


# Retained 1.21.11 Elytra mouse recapture failure

Failed attempt: `20260920T022329Z-debec8597aec`. Passing comparison: `20260920T022211Z-6d1eb705d803` (1.21.10). Both retained unchanged; only game-native logs and phase receipts inspected.

The failed attempt completed equipped, crouched, standing_recovered, falling, and gliding, including the actual gliding capture at monotonic ns199109364781124. It then stopped inside drive_elytra.py's wait for mouse_grabbed after one private target click. The capture-mouse action is journaled only after that wait in the old driver, so its absence, no relative-look action, and 30 rather than31 commands locate the stop precisely. All1,406 subsequent target native rows report mouse_grabbed=false and pitch10 degrees. The target naturally glided onward and landed; the controller never reached its genuine downward-look or landed proof stage. Proof records bounded Elytra phase/owner deadline. Generic missing proof messages are consequences of that controller failure, not evidence that no native handshake/glide/render occurred.

The passing comparison immediately observed grabbed=true after capture and recorded the look input, native pitch55 degrees, second observer-facing command, landed phase, and completed proof. On the failed1.21.11 attempt mouse_grabbed was false from the first native sample throughout; in the passing1.21.10 run it became true before phase setup. Both use rawMouseInput=false and pauseOnLostFocus=false. Native retained evidence proves the one-shot capture failure; it does not identify which GLFW/X event was missed. The current XInput.click raises/focuses/moves/clicks in one flush with no readiness barrier or retry. Focus/event timing is a plausible mechanism, not asserted as proven.

Minimal external candidate: recapture.patch modifies only existing drive_elytra.py, elytra_input.py, and test_elytra_input.py. It gives identity-verified target focus0.2seconds to settle, emits a real click, records its interval, and accepts only fresh native mouse-grabbed evidence after the click. At most six actual clicks with0.3second observation windows; existing owner/global deadline still bounds the wait. Every click is retained in the single capture-mouse action's nested attempts array, including unsuccessful attempts; success closes that action's original checker-compatible interval. No checker, pose, render, landing, or source fixture change. No teleport or synthetic flight state.

Validation: seven isolated Python unit tests passed (two existing, five added). They cover first-click success, missed first click and genuine retry, stale native samples, six-click failure, identity failure before input, and preservation of the caller's owner/deadline. git apply --check passed across all five worktrees. No maintained files modified; no Java/native launches. A fresh1.21.11 native attempt is still required to establish actual recovery.


Independent review found one materializer preflight issue; no regression found in the Elytra recapture change. Reviewed all five applied trees: the five affected files are byte-identical across lines (`native-followups-reviewed-hashes.json`). No maintained edits, Java or live input occurred in this review.

**P2 — native-container admission reads named manifest sections as root attributes.** `tools/compat/materialize.py:51` searches the entire manifest for `FMLModType`. FML 4.0.44's retained primary sources (`ModFile` and `JarModsDotTomlModFileReader`) use `getMainAttributes()`. A valid manifest with `FMLModType: GAMELIBRARY` only in an unrelated named-entry section, a declared native jarjar child and no candidate-locator service incorrectly returns `ready=true`. Python-only reproduction is preserved in `native-followups-named-manifest-control.json`. This affects the new generic library-container branch; the exact supported Connector distribution uses the service-provider branch and is unaffected.

At root's request, staged the minimal repair outside maintained trees: `../connector-main-attributes-fix/main-attributes.patch`. It restricts the check to the main section, handles CRLF/LF/CR separators, and prevents whitespace matching across lines. All 26 materializer tests pass in a fresh Python process, including LIBRARY/GAMELIBRARY × three line endings × main/named-section controls without a service provider. A separate fresh Python process confirmed that exact Connector SHA `a79817f1076e2e6499b0ab09c4e8c032d04b071341b66d036c3d96112804e51b` remains admitted through its packaged service. Dry apply checks pass on all five trees. Proposed materializer SHA: `4f2f532133b6b569e4426a1c766977b24c90554ed44314f4f59e52611cb37b71`. Root owns application between native runs and any necessary unlaunched-target regeneration.

The remaining loader-route behavior is sound within the stated metadata-preflight scope: admission remains NeoForge-only; Fabric top-level wrappers and Fabric-declared child edges cannot establish native container admission; a declared native child plus recognized library or packaged service discovery is required. Artifact hashes, locked metadata equality, nested depth/byte bounds, dependency/range checks, plugin-route guards and actual native launch acceptance are preserved. Packaged service-class presence is intentionally not a claim to validate arbitrary provider bytecode.

The Elytra repair preserves genuine input and proof requirements. It performs at most six real clicks, records every attempt, requires a native `mouse_grabbed` sample at or after the latest click's completion time, and leaves downward-pitch, flight/landing, render and visual checks intact. Focus is identity-verified before settling; `XInput.click` verifies focus again after the settle, including owner/display/process/window identity. The outer deadline/owner wait remains authoritative; success retains one checker-compatible capture action, while failure leaves the attempted inputs recorded and cannot create passing proof. No synthetic flight state, target teleport, checker weakening or foreground-display input was introduced. Root's seven targeted tests already cover the relevant bounds and freshness controls, so they were not needlessly rerun. Live retry `20260920T023835Z-6bfbbf2347f8` remains root's independent acceptance gate; this code review makes no claim about its outcome.


### Applied disposition

The nested-container and mouse-recapture repairs were applied to all five support lines. The independent main-manifest-attributes finding was also fixed on all five; all 26 materializer tests and seven Elytra-input tests pass. The exact Connector service-provider branch remains accepted; named-section library markers cannot establish discovery. The final materializer SHA256 is 4f2f532133b6b569e4426a1c766977b24c90554ed44314f4f59e52611cb37b71.

The fresh Minecraft 1.21.11 Elytra run 20260920T023835Z-6bfbbf2347f8 passed all six native phase checks, including actual landed proof, and completed owned cleanup. The preceding failed run remains failed. All four map and both seated-player runs also completed semantic checks and cleanup. These eight visual runs still require the user's actual image assessment; none is promoted by this source review.


## Subsequent integration and acceptance audits — September 20

The following dated reports retain their audit-time scope. Later test completion is recorded in the current ledger and progress index; these reports do not replace actual user review.


# Astra final integration review

**Signoff: no concrete remaining blocker in the reviewed integration/export machinery.** This is a review of the procedure and scripts, not acceptance of unfinished native runs or the eight pending human visual reviews. No maintained edits, rig-lock tests, Java, or native execution were performed.

The contract enumerates 54 distinct cases: required42, supplemental4, extra-source2, and preset6. The required42 IDs equal the complete 42-row set in each of the five maintained matrices. Folia is counted once among required42; Fabric/Paper source checks are additional correctness evidence. Full preparation requires every contract case and rejects duplicate case IDs and reused run directories. Partial preparation remains external-only and cannot apply. Individual proposed matrix rows must match their exact profile, scenario, participant roles, candidate/fixture artifacts and predeclared final recipe target. Observed run output cannot silently refresh that target.

The exporter path uses retained runtime tool bytes after verifying their complete recorded hashes. Its sole source substitution restores the original owning repository for scenario-closure path classification; it neither substitutes current checker bytes nor changes proof, ownership, artifact or cleanup checks. Export provenance records the retained exporter, replay bootstrap, original logical repository and retained tool-manifest digest. Pass export requires a collected pass with complete cleanup and independently recomputed proof. Pending review and failed attempts cannot become accepted records. Previously root-reviewed C2ME byte-equality and control exports were not rerun.

Apply preflights hashes and paths before writing, permits only the enumerated validation records, global required matrix and dated acceptance index, rejects partial plans, symlinks, changed destinations and conflicting historical evidence, and does no Git staging. Identical reapplication is safe. Its checked proposed bytes, exact path list and root review are the authorization boundary; it is not a general importer of unreviewed plans.

The documented fixed-snapshot sequence is correct: commit each line's reviewed evidence/profile/matrix changes first; record those five full commit IDs; then update all source-refs to those immutable evidence commits, validate/render/check each catalog, and require all42 exact-target rows to pass using required_matrix_status.py. Commit explicit refs/generated documents separately. Catalog read_ref requires full40-character commit SHAs and snapshot_validation_records reads profiles and records from those commits, preventing a dirty-worktree or moving-branch claim. Do not describe catalog publication as complete until that second phase has passed.

Delayed human review must finalize each visual attempt with its original retained rig, using `python3 /ABS/RUN/tool-sources/tools/rig/rig.py review /ABS/RUN`, after the genuine user decision is bound to the required artifact. The reviewed rig collect/review path verifies both its own repository tool set and the run's retained set against the original runtime_tools manifest. Thus running current maintained rig bytes after later Connector/Elytra fixes can reject an earlier valid pending attempt; retained tools preserve its honest identity. No automated approval or review bypass is authorized. The retained parser takes the run directory as its positional target.

Reviewed hashes:
- `integrate.py`: `e702b52359074321dc27fbb59f1afc88b2913148ce9ee4c3f76f63b040816126`
- `replay_export.py`: `90146e0cd2a62b34b745eb138473f2d07c424dff1bba71fbcc4421b16e51a26a`
- `contracts.json`: `6579b58ee8a6a48d63f541d7754ef8241402dc471e047803a93387a909341a11`
- `README.md`: `8184b98a6ead6cc79a5c8cde38f0f74978ff6be2849e531973ac509d155ec4b4`


## Addendum: exact 26.1.2 fixture repair and integration v2

**Signoff: no new blocker.** The original failure is an honest preflight rejection (`wrong MC metadata`, `run: null`), not a failed native attempt being relabeled. Old fixture/recipe evidence remains retained.

Read-only comparison of all three repaired Fabric/Paper/NeoForge recipes against `final-native/remaining36-typed-metadata-restored` found identical profiles and scenarios. Runtime differences are confined to the smoke fixture artifact/source/cache references, its exact Minecraft metadata and smoke-fixture hash. Exactly the first/second smoke-client fixture targets change. Each expected target changes only those two fixture hashes; product candidates, settings identity, participants, scenario and scoped checker identity remain identical. The full tool-scope receipt additionally incorporates the three already reviewed Connector/Elytra helper changes, with exactly their independently reviewed hashes; none changes the smoke checker closure. No hidden checker or runtime-setting relaxation was found.

The new artifact SHA `efcc4a92956482573fe65bb018ca7e113061f056458142fd4667d9179d8a68b6` and receipt SHA `4567e3f56785bf7168e66d6a642407cd7c432af4280258431b28baed76a0b82c` verify against their bytes. Packaged metadata declares exact `26.1.2`. The bound compiler receipt verifies, reports compileJava completed, records the actual Minecraft26.1.2 classpath SHA `e4a447fe5d87d2ba1cbd036e319c5d1a18ff08cfbc60321ccde51d7fa6b4e225`, and has identical before/after compiler inputs. Build inputs and copied source trees are likewise unchanged across execution. Original fixture-source hashes, final Fabric dependency and embedded common dependency match the superseded build; this is a corrected engine compilation, not repacking. The retained owned-process binding and unchanged r5 runner/validator hashes verify.

All three recorded target derivations invoke maintained build_required_target.py with recipe files and explicit fixture targets, return0, and preserve derived-not-executed metadata. That builder reads declared recipe/artifact bytes, verifies current product bindings and refuses existing target output; it does not consume a new run result. The exact target delta provides no evidence of target refresh from observed execution. Native acceptance remains root's separate execution/export gate.

Integration v2 scripts are byte-identical to the signed-off versions. All54 case contracts are structurally identical. The only contracts.json changes are the path/hash of final_fixture_index, now SHA `8844e41ab0ee3df89603f366f3cf08512a07991e87735ed9c0c645d6496de52c`; v2 contracts SHA is `b9c73af96c87f29cefc8faee7f8626895d2a518414da1580af869a1e009739bf`. Only the server-smoke-26.1 build entry changes in the successor index, which explicitly retains the superseded build and correction. Accounting remains14 selected fixture families/20 selected outputs, with15 successful compile attempts including the superseded build. This addendum changes no earlier review condition or acceptance requirement.


# Astra final plan acceptance audit — 2026-09-20 UTC

**No additional implementation package or new native/build campaign was found missing.** Completion is justified once the known remaining acceptance list closes, with the handoff details below. This is a bounded reconciliation, not a fresh whole-program correctness review and not approval of the eight user-reviewed images.

Read all 361 lines of `docs/planning/2026-09-09-project-improvements-implementation-plan.md` in the 1.21.1 worktree, including amendments and September19 reconciliation, against current contributor/operator guidance, implementation inventories, final reviews, exact build receipts, and recovery records. No maintained file was changed; no test, Java, native process or GUI action was launched during this audit.

| Plan obligation | Existing implementation/evidence and remaining boundary |
| --- | --- |
| P0 contracts and P3 ports | Baseline, test-source/case inventories, migration map, classification/impact manifests, completed Astra dry-run and tools/lines exist. Current support-line guide documents exact refs, prerequisites, conflict preservation and candidate-versus-snapshot distinctions. Final fixed-ref reconciliation remains on the known list. |
| P2 catalog | Line-local facts/profile locks, feature-specific evidence, inspect/materialize/export, pinned five-line snapshots and docs-sensitive CI are maintained. Final exports, all42 exact-target rows, generated views and immutable evidence commits remain required; partial export cannot close the batch. |
| P1/P7 status/settings | Schema/ownership/privacy audit and regression suites cover configured/effective values, scratch validation, save failure, lifecycle and exports. All12 final UI runs and two no-Sodium runs now have actual operator screen inspection and passed collection; six server preset runs passed. No unsupported optional combination substitutes for a required row. |
| P4/P5 rigs/Xaero | Ownership and extraction records, maintained fixture sources and targeted native follow-up fixes are present. All14 fixture builds completed; fresh native acceptance and eight actual user visual dispositions remain distinct. Full rig suites on all5 lines must run after the native lock is free, avoiding skipped ownership controls. |
| P6a tests | 67-suite migration and parameter identities are retained; common runs Java21 without Minecraft dependencies. Matched forced-execution timing and external-resource cache invalidation are documented. No general speedup claim is made. |
| P6b measurement | Accepted V27 and independent arithmetic/source review exist. Preserve its corrected-reference, pacing, noise, fixed-heap and 32/4/1 operating-point limitations. The known three final source lanes close final-byte correctness; no replacement performance experiment is requested. |
| Developer/operator handoff | Short current CLAUDE, canonical flakes, ownership, installation, troubleshooting, status/presets, support-lines, rig and performance guides exist, with historical links preserved. Final evidence/status pointers still need reconciliation. |

I verified the SHA256 of all five manifests referenced by `recovery-20260910/final-integrated-inputs-20260914/full-build-receipts-v1.json`. They identify the September14 builds and named skips. A fresh read-only Git comparison found no changes since those builds in common/xplat/platform source trees or the selected root/module Gradle build inputs at current heads: 1.21.1 `7765423`, 1.21.10 `e5361b2`, 1.21.11 `2919064`, 26.1 `3fa4497`, 26.2 `3ce259b`. Keep original build provenance; do not relabel builds as newly executed. The older checked-in `final-build-gates.json` is historical September10 evidence, so final handoff should point explicitly to the final September14 receipts.

Known remaining scope is coherent: 21 automatic runs, three source lanes, full rig suites on five lines, actual eight user image decisions, complete54-case export/integration with required42 matrix, final catalog/classification/docs and commits. At audit time these are in progress, not accepted by this report. The already-completed manual/preset/C2ME records do not erase failed first attempts.

Required handoff details, within that existing closing work:

- Replace current ledger/checkpoint claims that all42 rows and fixture builds are pending; preserve dated historical records. Include exact commands, named skips, current native identities, offline fixture-role limitation and remaining optional-profile limitations.
- After the last run, retain an aggregate cleanup observation: no owned game/server/Prism/Xvfb/helper descendants, no owned test listeners or held harness lock. Stop the separately owned visual-gallery server after review using its recorded identity. Preserve normal profiles/world/server and the installed useful launcher/account/cache; keep failed attempts and immutable evidence. Disposable injectors remaining only inside inactive retained evidence roots are not installed production fixtures.
- Section16 asks to repeat the positive smoke while the user uses Windows and record resource interference separately from input isolation. The retained material I searched establishes the September9 user-witnessed coexistence and current technical isolation, but I found no newer user observation. Root has now asked a concise asynchronous question about actual Windows usability/resource slowdown during these final runs, separately from the eight-image review. This user observation is pending; no confirmation is inferred. Retain its eventual dated answer alongside the historical September9 observation. This is a small evidence item, not a new automated benchmark, native Windows gate or test framework.
- Keep native Windows observation-only/unverified; WSL D3D12 does not certify that driver lane. Preserve Folia experimental status, native Voxy0.2.9 optional rejection, and optional R06-1/R06-2 follow-ups. Native Windows ownership work is a fallback prerequisite, not required expansion of this selected WSL completion lane.
- Validate existing LSS/VSS packaging/fixture exclusion through retained exact build/artifact records and planned final reconciliation. Merge, tagging, publication, deployment and release-pipeline redesign remain separate; none is needed to close this local implementation acceptance.

Recommendations6/7/8 and optional/unmeasured R06-1/R06-2 were not reopened. No additional product patch is proposed.


# Astra Paper initial-source premise review — 2026-09-20

**Established: the fixture's initial ACK/mutation protocol does not prevent pre-edit loaded acquisition. Not established: the decoded content of the rejected run's bodies, or a product correctness defect.** Run `20260920T040604Z-972d7c8fb9d2` remains failed under its original checker. No checker waiver, source change, build, test or runtime action resulted from this review. Existing bounded diagnostic replay remains the next step; this report does not automatically accept the timing hypothesis.

## Evidence boundary

The reported D initial loaded target changed from gold to diamond after its oracle ACK. A source0 body arrived about630ms after the edit, followed roughly190ms later by a source1 body. A/B/C committed their source0 diamond obligations; D's initial obligation was the sole missing one of28. Diagnostics were off for the failed run. Arrival time and compressed body length do not establish snapshot time or decoded block identity. In particular, this review does not label the236-byte body gold or the237-byte body diamond.

## Established ordering gap

All source references below are in `/home/vox/projects/lss-improvements/26.2` and describe the inspected source state, not a newly instrumented replay.

- `test-fixtures/concurrent-server-core/src/dev/vox/lssfixture/concurrent/SourceWorkload.java:85–90` defines the initial loaded target as gold. `test-fixtures/paper-concurrent/src/dev/vox/lss/paper/PaperSourceProbe.java:83–88` seeds gold and retains loaded targets, then saves. `SourceWorkload.java:154–158` offers the loaded target as diamond on native join. Oracle acknowledgment authorizes the actual mutation at `SourceWorkload.java:357–376`; successful completion records `edit_applied` at337–347.
- `PaperSourceProbe.java:65–69` installs the fixture's `NOT_WRITABLE` pressure signal. This gates column flushing, not acquisition. `paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java:1363–1404` performs lifecycle/probe work and posts a snapshot before flushing. Its `probeLoadedChunks` at1716–1746 serializes pending loaded columns without consulting that fixture pressure signal. `common/src/main/java/dev/vox/lss/common/processing/IncomingRequestRouter.java:175–187` resolves loaded probes before disk admission.
- `common/src/main/java/dev/vox/lss/common/processing/AbstractPlayerRequestState.java:1075–1098` explicitly retains the queued body while the transport is not writable and permits a starvation-floor send. Consequently the fixture pressure signal is neither an acquisition barrier nor an absolute delivery barrier.

A loaded gold snapshot can therefore be acquired/queued before ACK-authorized mutation, then delivered after the mutation. That ordering is permitted by the inspected implementation. It establishes that strict initial source0-plus-diamond is not guaranteed by the fixture's existing ordering; it does not establish that this particular interleaving explains the failed run.

## Dirty handling and acceptance implications

`PaperSourceProbe.java:141–146` mutates on the server owner and then calls `markDirty`. `paper/src/main/java/dev/vox/lss/paper/PaperDirtyColumnBroadcaster.java:53–78` drains dirty positions on the configured cadence and invalidates timestamps/probe epochs. At117–130 it queues done-bit clears, clears probe suppression and sends dirty notices. This re-resolution path does not promise that the next current body uses source0.

`IncomingRequestRouter.java:113–121` takes the newest incoming batch while using the previously supplied snapshot's loaded probes. Missing or invalid probes fall through to disk admission at175–187. `common/src/main/java/dev/vox/lss/common/processing/OffThreadProcessor.java:363–375` rotates loaded-probe epochs when timestamp invalidation is enqueued; `PaperSourceProbe.markDirty` itself is not an immediate acquisition barrier.

The fixture intentionally distinguishes a current valid body from satisfaction of its exact obligation. `test-fixtures/concurrent-client/src/dev/vox/lssfixture/concurrent/AcceptancePolicy.java:15–25` requires the application time, accepted source and matching block for commit. `ClientProbe.java:286–312` observes a valid nonmatching body without synthesizing a commit or forcing storage rejection. `OracleJournal.java:136–146` keeps initial source checks exact; only explicitly registered repeated measured edits permit fallback sources. None of those checks should be relaxed to make this run pass.

## Tentative minimal premise control — not implemented or qualified

Prefer startup acquisition ordering through the existing reception option over a product patch or a weaker source assertion:

1. Materialize `receiveServerLods=false` before the first native JOIN. `xplat/src/main/java/dev/vox/lss/networking/client/ClientSessionGate.java:216–257` initializes reception disabled, leaves no request manager, and returns before sending the LSS handshake. Disabling only after acquisition has started would not establish absence of already queued work.
2. Keep the fixture's real native connection/session tracking, oracle parsing and ACK writing active. `ClientProbe.java:51–91,101–128` performs these independently of an LSS request manager. Require all four initial source targets for the same run/subject/native session and their genuine `target_ready` or `edit_applied` records before enabling reception. The loaded target's actual application record establishes that the server consumed its ACK before mutation. Preserve original identity/revision/ownership/deadline checks.
3. Release on the client main thread through the existing `LSSClientConfig.CONFIG.receiveServerLods=true` and `ClientNetGlue.reconcileClientConfig()` path. `ClientNetGlue.java:479–497` supplies that transition; `ClientSessionGate.java:149–157` initiates normal negotiation. Preserve all initial exact source checks and subsequent independent real update obligations.

This avoids starvation-floor bypass because no earlier LSS acquisition should exist. It also addresses the timing deficiency of merely pre-establishing diamond: a body must not arrive before the oracle/application evidence needed to qualify it.

**Avoid a circular wait:** do not require the non-edit `target_acknowledged` rows before release. `SourceWorkload.java:380–395` emits those only when all product players are registered. In contrast, pending ACK-authorized edits are processed at317–377 before that origin/registration gate and before the `origin==0` return at400. Paper mutation ownership at `PaperSourceProbe.java:136–146` does not require LSS registration. Folia's native player-owner observation/join at `test-fixtures/folia-source-concurrent/src/dev/vox/lssfixture/concurrent/FoliaSourceProbe.java:90–122` and owner permit at168–173 likewise do not depend on product registration. Thus the inspected paths support the proposed order, but this is not runtime validation of a completed design.

Any implementation would still need bounded negative controls for premature/wrong-session/incomplete application evidence and confirmation that no acquisition occurred before release. This review does not author or execute those changes. Do not infer a product pass, user approval, confirmed body diagnosis or overall acceptance from this report.


# Paper diagnostic replay: final disposition

**Accept run `20260920T042642Z-c80ce612cd66` as the explicitly identified diagnostic-runtime correctness pass.** Collected result is passed with complete cleanup and no errors; the unchanged strict source checker reports28/28 targets, seven per client, and successful debt drain in1.649955068s. All four consumers closed with zero held receipts, no overflow and no diagnostic error. All report-indexed raw evidence hashes verify. This is correctness evidence, not performance acceptance. No extra blind retry or new product/fixture workstream is required for this pass.

The diagnostic confirms an actual nonmatching-to-current loaded-body sequence for C's initial target at(520,8). Mutation applied at206727601915598ns. Source0 wire/body209 arrived at206728179830033ns (577.914ms later),236 bytes, and the native consumer recorded BLOCK_ONLY with matching_block=false, native_authority=true and lease_active=true. Source0 wire/body4177 then arrived at206737514600567ns,247 bytes, and committed the exact diamond obligation at206737516306061ns (9.914390463s after mutation). This is the only recorded rejection; subsequent independent targets all passed.

Together with `astra-paper-initial-source-premise.md`, this supports a fixture acquisition/mutation race: oracle registration ACK does not block pre-edit loaded acquisition, and post-edit network arrival does not establish post-edit serialization. The replay demonstrates benign transient nonmatching delivery followed by valid correction under the strict initial route. It does not establish the exact serialization time or decoded block of either old D body in failed run `20260920T040604Z-972d7c8fb9d2`; that27/28 attempt remains failed. No shipping regression or checker defect has been demonstrated, and no assertion was waived.

Acceptance must name the predeclared diagnostic recipe `source-paper-diagnostics-recipe`. It adds only the existing bounded rejectionDiagnostics=true property on all four clients; it changes neither acquisition ordering nor checker, profiles, artifacts, debt, duration or28 obligations. Runtime settings identity is `c88c2ae408957fa402667f13dc236fdf108db324021c68e257c1b24710d8fe06`; the prelaunch expected-target file SHA is `67f3f4f4e7144aeaafc2ae9bf3ddb2447183a8d26d9caf0ee545b04d8db89a29`. Integration should select this recipe explicitly and keep the old normal runtime target/failure unchanged. Its optional future startup acquisition gate is fixture maintenance, not a change effected by this retry.

Evidence identities:

- Run hash: `bbde976add7b7a73f7b2c0608bb03b34fe1bcf8f5bd4756bedbc018a407fc4e6`.
- Canonical runtime hash: `6f5e6d4b7351ab12d66da31d9f9ade7ef08a679cd2cefa863df2b6431fb51174`.
- `evidence/result.json` SHA256: `4424d74845e8a1ab05b6ac74f9cbde49c6669995928c37abe62e11357fbd71a0`.
- `evidence/source-correctness.json` SHA256: `c0ba9b4a30adfee49cf879336c882859e341a67bacc63397551f3b79e0ccb7c8`.
- `proof.json` SHA256: `fa5d64a278f1e1c8edaa86e21ca2af1dc3a42bc343d9d5d2bf9d7b8a4d816422`.
