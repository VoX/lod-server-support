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
