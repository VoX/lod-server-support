# Remaining runtime command matrix — prepared, not executed

Prepared from the final plan, current harness/wrapper sources and read-only environment inspection on 2026-09-08. No Gradle, server, actual benchmark/soak, GUI input, screenshot capture or profile mutation was performed for this matrix. The parent selected the primary 1.21.1 gates below and one relevant 26.2 Folia lifecycle smoke after final builds.

## Resource prerequisites and current state

- **Preserve the regular real-map server, PID489815, port25564.** Only25564 was listening among25564/25565/25566/25567/25569 during this inspection. Port25565 was free. Do not use `test-server.sh clean`, replace its world, or stop it for these gates.
- Run one outer harness/wrapper at a time, after the parent's build slot is idle. Each harness inherently launches its own server and client Gradle tasks; no unrelated build/test should overlap. The per-user flock at `/tmp/lss-harness-$UID/port-25565.lock` protects all worktrees' harness worlds/client scratch/results as a coarse transaction lock. Never unlink it or manufacture an inherited descriptor. Lock refusal is a scheduling issue, not a product failure.
- All ordinary harnesses use25565, including Paper/Folia. Their directories differ from the normal server: Fabric `fabric/build/run/{soak,benchmark}-{server,client}`, Paper `paper/build/run/soak-server`, Folia `paper/build/run/folia-soak-server`. The soak client directory is shared between server-platform arms in a worktree.
- `/usr/lib/jvm/java-21-openjdk-amd64` exists for1.21.x. `/home/vox/.local/jdk/jdk-25.0.2+10` exists for26.x. The inherited `java` currently resolves to25, so select Java21 explicitly for1.21.1/1.21.10/1.21.11. Do not rely on test-server.sh's looser runtime minimum for build-tool compatibility.
- The documented WSLg `glfwShowWindow` wedge makes Xvfb the established harness route (`region-scan-plan.md:694`). `xvfb-run`, `Xvfb` and `xauth` are installed. OnlyX0 currently exists; let `xvfb-run -a` own a new display, with Wayland unset. This is a harness graphics route, not proof of a real Sodium/Xaero/Voxy GUI.
- Every line has a Fabric `soak-worlds/base/world`. The1.21.1 base has marker1.21.1 and totals about21MiB. **No line currently has a Paper or Folia base.** The1.21.1 benchmark base is also absent. Budget one auto `fresh-backfill` (~300s) before the first Paper/Folia base-dependent scenario. Script version checks remain authoritative if a base changes later.
- About717GiB free on the workspace filesystem at inspection. Record the normal server's actual activity/load during runs; the protected server remains running, so benchmark smoke below establishes lifecycle/export correctness, not an isolated performance comparison.

## Exact primary commands

Start only after final builds/ports settle. The following shell setup affects these invocations, not user profiles. `run21` clears experimental inherited knobs and then allows explicit `env SOAK_PLATFORM=...` arguments for each selected gate.

```bash
cd /home/vox/projects/lss-lines/1.21.1
RUNTIME_EVIDENCE=/home/vox/.local/state/lss-review/20260908-implementation/runtime
mkdir -p "$RUNTIME_EVIDENCE"
set -o pipefail
run21() {
  env -u WAYLAND_DISPLAY -u SOAK_PLATFORM -u SOAK_WORLD_FROM \
    -u SOAK_EXTRA_GRADLE_ARGS -u SOAK_DIALECT \
    -u SOAK_LODSTORE_OVERRIDE -u SOAK_LODSTORE_BACKFILL_OVERRIDE \
    -u SOAK_MIGRATION_HOLD_SECONDS \
    -u BENCHMARK_CONFIG_STAGED -u BENCHMARK_SERVER_GRADLE_ARGS \
    -u BENCHMARK_CLIENT_GRADLE_ARGS -u BENCHMARK_DROP_CACHES \
    -u BENCHMARK_EXTRA_SERVER_PROPS -u BENCHMARK_NO_BASE_SAVE \
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
    PATH="/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH" \
    xvfb-run -a -s '-screen 0 1280x720x24' "$@"
}
```

Preflight the exact scenario/config pairs without launching them:

```bash
(set -e
for scenario in store-offline-populate store-offline-mutate store-offline-verify \
  store-migration-join generation-disabled generation-capacity-stress \
  dimension-rejoin-warm dirty-while-offline-summary; do
  python3 scripts/check_soak.py --validate "$scenario"
done
)
```

Do not execute a runtime command after any failed preflight. Run these rows serially; retain the outer exit status through `tee` via `pipefail`.

| Order | Exact command (after setup above) | Nominal scenario time, excluding builds/startup | What a pass establishes |
|---|---|---:|---|
|1|`run21 ./scripts/benchmark.sh fresh 60 2>&1 \| tee "$RUNTIME_EVIDENCE/1211-benchmark-fresh.log"`|60s|Real supervisor/Gradle/game lifetime completes, required current exports validate, final manifest/aliases publish. Creates a new benchmark base only on a valid fresh cycle. No performance-regression claim.|
|2|`run21 env SOAK_PLATFORM=fabric ./scripts/store_migration_gate.sh 2>&1 \| tee "$RUNTIME_EVIDENCE/1211-store-migration.log"`|280+280=560s|Real downgrade→lazy migration→warm19-row serving, held-walk overlap, completed migration and zero unexpected deleted anomalies. Existing integrity/shutdown unit fixtures remain the exact corruption/interruption proofs.|
|3|`run21 env SOAK_PLATFORM=paper ./scripts/store_offline_edit.sh 2>&1 \| tee "$RUNTIME_EVIDENCE/1211-paper-offline-edit.log"`|280+160+280=720s, **plus300s auto-prime now**|Three real Paper restarts with carried world/store; edited probe must change, control probe must remain identical, warm serving and per-phase laws pass. This is also the representative Paper storage/transport smoke; a second separate Paper smoke is redundant unless diagnosing a failure.|
|4|`run21 env SOAK_PLATFORM=fabric ./scripts/soak.sh generation-capacity-stress 2>&1 \| tee "$RUNTIME_EVIDENCE/1211-generation-stress.log"`|330s|Fresh-world generation pressure and valid followers conserve/converge. It does not itself construct WI2's exact stale-generation registration race.|
|5|`run21 env SOAK_PLATFORM=fabric ./scripts/soak.sh generation-disabled 2>&1 \| tee "$RUNTIME_EVIDENCE/1211-generation-disabled.log"`|230s|Complementary generation-disabled terminal outcome/admission control from the planned matrix.|
|6|`run21 env SOAK_PLATFORM=fabric ./scripts/soak.sh dimension-rejoin-warm 2>&1 \| tee "$RUNTIME_EVIDENCE/1211-dimension-rejoin.log"`|650s|Warm state across dimension/session transitions with live transport. It is not an OFF/ON menu or Xaero fault-injection gate.|
|7|`run21 env SOAK_PLATFORM=fabric ./scripts/soak.sh dirty-while-offline-summary 2>&1 \| tee "$RUNTIME_EVIDENCE/1211-dirty-summary.log"`|480s|Dirty/offline summary false-clean canary and convergence; exact retained-proof reversal remains the deterministic WI4 regression.|

Primary nominal total including the currently needed Paper auto-prime: **3330s /55m30s**, plus build, JVM launch/join and world-copy time. Each soak's existing hard budget is EXPECTED_SECONDS+240s after readiness; do not shorten timelines/floors to fit this estimate. The migration hold's90s default is already inside its scenario, not an extra timer to remove.

Immediately after the successful benchmark, before another benchmark overwrites current aliases, verify/archive its actual identity:

```bash
python3 scripts/lib/benchmark-results.py record "$PWD" "$RUNTIME_EVIDENCE/1211-benchmark-smoke"
python3 - <<'PY'
import json
from pathlib import Path
root = Path('benchmark-results')
m = json.loads((root / 'current.json').read_text())
assert m['status'] == 'complete'
assert m == json.loads((root / 'runs' / m['run_id'] / 'manifest.json').read_text())
cycle = m['cycles']['measure']
assert cycle['server_exit'] == cycle['client_exit'] == 0
print('Verified current run:', m['run_id'], 'commit:', m['commit'])
print('Optional JFR:', cycle['server_jfr'], cycle['client_jfr'])
PY
```

Missing/malformed JSON, nonzero exit, timeout, incomplete manifest or failed wrapper assertion is not a pass. Preserve `benchmark-results/runs/<id>` and the exact timestamped `soak-results` directories, not merely the latest filename. Archive checker verdicts and wrapper cross-phase verdicts. Apply only a specifically matching CLAUDE flake procedure; a premise failure stays inconclusive until a genuine checker-green run.

### Smaller substitutes and conditional expansions

- If a short initial Paper bring-up is needed before the longer offline chain: `run21 env SOAK_PLATFORM=paper ./scripts/soak.sh store-second-join` is280s plus the same initial300s auto-prime. It validates demanded warm store serving; it **does not replace** the offline edited/control hash comparison. Running it first merely moves base preparation earlier and adds280s.
- Fabric offline-editor control, if Paper fails with a platform-specific question: `run21 env SOAK_PLATFORM=fabric ./scripts/store_offline_edit.sh` (720s with existing base). Avoid the isolated mutate/verify phase names because they lack their required ancestry.
- Additional ordinary summary warm utility: `run21 env SOAK_PLATFORM=fabric ./scripts/soak.sh warm-rejoin-summary` (470s). Use `run21 ./scripts/stamp_heal.sh` or `run21 ./scripts/summary_evicted.sh` for their full two-phase guarantees (470+260s each); never invoke only their rejoin phases and claim the chain.
- `hybrid-boundary` is1800s and exercises a distinct wide-distance frontier. It is not necessary to repeat indiscriminately for every line's source-identical fixes. If omitted, record the planned wide-distance/live-backpressure gate as deferred, not inferred from small-radius scenario greens. Existing ordinary Fabric scenarios operate at small radii and do not establish wide-region skipping or Xaero backlog behavior.

## Representative other-line / Folia lane

Parent selected one relevant26.2 Folia smoke after final builds. Preserve its existing platform registry and experimental label; **do not attempt Folia1.21.1 or1.21.10**, even though shared shell text still contains the family branch. Neither has a runnable upstream build.

```bash
cd /home/vox/projects/lss-lines/26.2
python3 scripts/check_soak.py --validate store-second-join
env -u WAYLAND_DISPLAY -u SOAK_WORLD_FROM -u SOAK_EXTRA_GRADLE_ARGS \
  -u SOAK_DIALECT -u SOAK_LODSTORE_OVERRIDE -u SOAK_LODSTORE_BACKFILL_OVERRIDE \
  JAVA_HOME=/home/vox/.local/jdk/jdk-25.0.2+10 \
  PATH="/home/vox/.local/jdk/jdk-25.0.2+10/bin:$PATH" \
  SOAK_PLATFORM=folia xvfb-run -a -s '-screen 0 1280x720x24' \
  ./scripts/soak.sh store-second-join 2>&1 | \
  tee /home/vox/.local/state/lss-review/20260908-implementation/runtime/262-folia-store-second-join.log
```

Expected280s plus currently required300s Folia auto-prime; startup allowance240s. The plugin task may fetch the configured Folia jar on first use. A download/unavailable-artifact failure is an environment prerequisite failure, not a green lifecycle result. This single-player smoke exercises the regionized lifecycle/loaded-store route; it cannot satisfy the documented concurrent multi-region criterion or exact WI2 replacement-registration race. Other1.21.11/26.1 Folia runtime lanes remain explicitly unrun if only this representative is selected.

For additional per-line representative Paper coverage, run the same `SOAK_PLATFORM=paper ./scripts/soak.sh store-second-join` through that line's Xvfb/JDK wrapper. Java21:1.21.10/1.21.11; Java25:26.1/26.2. Every first Paper lane currently auto-primes. Do not transplant the1.21.1 nonexistent-Tier3 exclusion rule; the ported harnesses already retain each target's correct build arguments.

WI14's two1.21.11 C2ME pins have an existing narrow explicit validation route; schedule in the parent's ordinary serialized Gradle slot, not alongside a soak:

```bash
cd /home/vox/projects/lss-lines/1.21.11
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH="/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH" \
./gradlew :fabric:runGameTest -Pbenchmark.c2me=true \
  -Pbenchmark.c2meProfile=regular-map --max-workers=1 --console=plain
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH="/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH" \
./gradlew :fabric:runGameTest -Pbenchmark.c2me=true \
  -Pbenchmark.c2meProfile=benchmark --max-workers=1 --console=plain
```

Record actual loaded alpha.0.18/MfQIu1Y0 versus alpha.0.26/879vA5z6. If a save-hook live question survives those tests, the targeted expansion is `SOAK_EXTRA_GRADLE_ARGS='-Pbenchmark.c2me=true -Pbenchmark.c2meProfile=regular-map' ./scripts/soak.sh dirty-broadcast`, then the benchmark profile, each under Java21/Xvfb. Do not repoint or restart the regular-map server to exercise the second pin.

## Actual GUI automation availability

Read-only checks established:

- No dedicated desktop/computer MCP tool is exposed. Linux `xdotool`, `wmctrl`, `pyautogui`, python-Xlib and pynput are absent. X11 libraries/XTest are installed, but the LinuxX0 tree contains no Minecraft/Prism window. Linux screenshot/input tools would not address the Windows Prism client.
- Windows interop **works** via `/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe`. Prism11.0.3 is running (PID39344; handle2165200 at inspection). The native desktop is2560×1440. WinForms `SendKeys`, System.Drawing screenshot types and UIAutomationClient loaded successfully. Read-only UIAutomation found75 descendant controls including buttons/list items/check boxes. This is a viable bounded GUI automation route; lack of Linux xdotool alone is not a reason to declare all UI gates impossible.
- No Windows `javaw` Minecraft window was returned. A compatible isolated client must first be prepared/launched and its new window handle re-read. OpenGL game controls will generally need observed keyboard/coordinate input rather than assuming Prism's accessible control tree applies to the game. No focus, click, keypress or screenshot was sent/captured in this task.
- Native launcher path exists: `C:\Users\Ian\AppData\Local\Programs\PrismLauncher\prismlauncher.exe`. Actual instances are under `C:\Users\Ian\AppData\Roaming\PrismLauncher\instances`, with **minecraft/mods**, not `.minecraft/mods`.

Use `docs/testing/astra-live-profiles.md` plus the embedded-metadata inventory for exact artifact sets. Ready candidates: Fabric `lss-test-1.21.1` for modern Sodium/Xaero (no enabled Voxy); NeoForge `LSS dev — 1.21.1 NeoForge - Copy For Far Testing` for native modern Sodium plus Connector Voxy0.2.15-beta. The dedicated NeoForge profile's enabled Voxy metadata targets1.21.11 and is not a valid Voxy1.21.1 candidate. Legacy Sodium combinations need a compatible isolated clone, not silent enable/disable edits to the user's profile.

## Graphics-dependent procedures and explicit deferral

| Gate | Exact remaining procedure | Honest status until performed |
|---|---|---|
|WI3 / WI14 options surfaces|In each verified loader/Sodium clone: join with Receive ON, establish wants, use the actual options page Apply to turn OFF, observe retirement/no new nonempty wants, Apply ON and observe convergence; separately join OFF then turn ON. Record loaded versions, menu screenshots, diagnostics and action times. `BenchmarkHook` only implements the `clearcache` scripted action; ordinary soak timelines cannot claim to drive this toggle.|Runtime UNRUN; profile readiness differs by row.|
|WI5 Xaero lifecycle|In a matching clone with real Xaero, establish measurable pending/debt work, disconnect while pending, connect to a second disposable server with the same dimension, then repeat receive OFF/ON. Observe exact manager/bridge diagnostics and new work progressing. A warm rejoin without backlog is not the trigger.|Runtime UNRUN until backlog/replacement chronology is captured.|
|WI6 far-player mode|Use a stationary second player beyond vanilla range in a disposable server fixture. Server console `lsslod set farPlayers off`, then `lsslod set farPlayers on`; confirm removal/restored pose. VSS console uses `vsslod`. Withheld-clear coverage needs a controlled transport fixture; simply pausing a visual client is not proof of an unwritable send.|Ordinary live toggle UNRUN; exact withheld ordering remains unit-proven unless separately injected.|
|WI9 seated failure|Prepare a **small isolated dev fault fixture** that throws after pose push/translation inside the seated dispatcher draw, for both1.21.1 loaders. Observe the next proxy/tag and final stack/render continuation. No shipped command/property was found to inject this exception. Mounting a boat without an exception is only normal seated rendering, not the required regression trigger.|DEFERRED: missing runtime injector/compatible isolated fixture. Keep production code unchanged merely to manufacture a broad test harness. Record owner, exact missing hook and follow-up; do not count helper reflection tests as this live gate.|
|WI12 Voxy reset|In an isolated clone of the verified NeoForge far-testing profile, install the exact candidate and confirm actual transformed Voxy0.2.15-beta loaded. With an active LSS session and terrain/storage established: `/lss diag`, `/lss reset`, then observe correct reset outcome and ingestion resume. After repopulation, `/lss reset voxy-force` is the read-only storage probe. Use `/lss reset voxy-force confirm` only if the fixture's stage1 actually arms an in-scope root and its shown path is the intended disposable storage; grant expires after60s and is connection/root-bound. A normal matching-root probe may correctly tell the user to use ordinary reset. VSS local command prefix is `/vss`.|Runtime UNRUN, but no new broad infrastructure is needed for ordinary reset/probe once the verified clone is ready. Forced wipe is a separate conditional fixture action.|
|WI11 disguise replacement|On a disposable Paper setup with a supported replacement lifecycle, keep the same LSS instance alive while replacing the enabled disguise plugin and making a target disguised in the replacement registry. Capture new-instance answer and far-player visibility. Full server restart/all-plugin reload is a different transition. No current controlled plugin-replacement fixture was identified.|DEFERRED: missing isolated supported plugin-replacement setup. Preserve the deterministic two-defining-loader regression as unit evidence only.|
|WI14 profile/C2ME guidance|Run both named C2ME configurations above; for each GUI candidate confirm launch-log mod identities and the actual intended page/render route. Inventory and jars alone certify neither loading nor UI operation.|Inventory/source documentation complete; runtime rows remain individually UNRUN until observed.|

A deferred row should retain: exact profile/loader/version, candidate commit/hash, missing prerequisite, steps above, expected result, available unit evidence and the reason no live claim is made. Do not erase the row or label the package fully runtime-green. This matrix adds no broad test infrastructure and changes no personal profile.
