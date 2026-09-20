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
