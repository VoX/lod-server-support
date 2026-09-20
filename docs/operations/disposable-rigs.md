# Disposable live rigs

Use `tools/rig/rig doctor --runtime /private/runtime.json` before staging, then
`tools/rig/rig plan /profile.json /scenario.json --runtime /private/runtime.json`.
Planning and doctor do not launch Minecraft or download dependencies. Profiles
use P2 compatibility schema v1; blocked/unsupported profiles cannot run. Exact
missing hashes retain source locations. Dependency fetching remains explicit.

```
tools/rig/rig create /profile.json /scenario.json --runtime /private/runtime.json
tools/rig/rig run /private/run-directory
tools/rig/rig status /private/run-directory
tools/rig/rig stop /private/run-directory
tools/rig/rig collect /private/run-directory
```

Create and run reuse the existing coarse lock and Linux descendant supervisor.
Direct Python create/run calls require the inherited lock. A configurable port
does not bypass the legacy25565 occupancy check. Stop writes a request consumed
by the owning runner; it never kills by port. Runs are immutable attempts; create
a new run for an explicit retry and retain the first failure.

Runtime bindings contain backend, separate `bind_endpoint` and `client_endpoint`,
a SHA256-to-local-file `cache`, optional exact `stage_files` (source, target,
SHA256), generated disposable settings, and launch arrays (id, cwd, argv).
`{run}`, `{run_id}` and `{endpoint}` are expanded without shell evaluation.
Every launch must use independent game/cache/output roots. Launcher accounts,
hooks, wrappers, environment bindings and personal worlds are not cloned.
Runtime files and raw logs stay in the private run directory, not exported results.
Credentials must remain in an explicitly authorized launcher context.

The isolated Linux Prism backend creates a unique Xvfb display and Xauthority,
disables X TCP, sets null audio, and checks accelerated GL under the game GPU
environment. Configure adapter selection; do not assume NVIDIA elsewhere.
Window input/capture passes creation-identity and owned-descendant checks before
each action. The ordinary WSLg/Windows display, foreign windows and stale PIDs
are rejected. No host clipboard, Windows input or full-desktop capture path exists.
Windows remains observation-only until its separate native ownership gate passes.

Readiness, actual LSS handshake, test count, every scenario assertion and required
human visual reviews are separate proof fields. Fixture evidence must carry run,
profile and scenario identity. An image alone never marks a human review accepted.
Collection excludes raw logs and requires completed process cleanup. The
checker intentionally fails missing proof even after a clean process exit.

Run tooling tests with `python3 -m unittest discover -s tools/rig -p 'test_*.py'`.
`tools/rig/check_packaging.py` rejects fixtures in shipping jars.
`tools/rig/check_regions.py` checks early two-client Folia feasibility.
`tools/rig/performance.py` rejects incomplete, stale or incorrect fixed three-pair
experiments; measured presets remain unavailable without accepted measurements.
The scenario JSON files define semantic requirements, not completed acceptance.

The imported WI fixtures and the new Folia fixture still require exact descriptor
and live matrix validation. The maintained runner does not convert the historical
WSL smoke, prior fixture logs, or baseline bytecode into final candidate evidence.

`tools/rig/metric-schema.json` describes the metric shapes and thresholds; the
enforced values live in `performance.py`/`metrics.py` and a unit test pins the
two equal. The JSON is documentation, not an identity-bound input. Raw RSS observations
retain each scheduled second, process creation identity, units and explicit
missing flags. The Folia observer records full owning tick spans independently
from scheduler delay; client timing agents record real frame entry intervals.
Class hashes and exact descriptors must be pinned before measured runs. An
unverified class records a failure and produces no substitute frame timings.

The controller accepts bounded console requests in
`run/commands/<unique-id>.json`: `launch_id`, one-line `command`, optional
`timeout_seconds` (maximum60), and optional literal `response_contains`. Commands
are written directly to the recorded launch's stdin with a nonblocking atomic
write. Receipts under `commands/results/` identify the owned process and private
log offset. A submitted command is not proof of game state; require its semantic
response or the corresponding fixture assertion. Replayed IDs and foreign targets
are rejected.


## Recording a collected feature result

Use `tools/compat/export_validation.py` after collection to derive a line-local catalog record. Select the production jar by its staged relative path, list the actual fixture jars with repeated `--fixture-target` arguments, and name the feature proved by the scenario. For example:

```bash
python3 tools/compat/export_validation.py "$RUN" \
  --candidate-target instances/lss-rig-client/minecraft/mods/lod-server-support-fabric.jar \
  --feature status-ui \
  --limitation 'UI assertions only; no map or performance acceptance.' \
  --output config/compatibility/validation/NEW-RUN-ui.json
```

The exporter refuses active runs, changed input/candidate bytes, invalid proof and a passing result with incomplete cleanup. It automatically checks and indexes the proof's declared evidence files and native gametest report. Additional sanitized files can be selected with `--evidence evidence/NAME`. It never includes raw launcher logs or the launcher account context. The output must be a new file; existing failed records are retained.

The catalog's generated view reads only the committed five-line source snapshot. Exporting a local record does not silently update that snapshot, certify unrelated features, or turn an older artifact's result into acceptance of a newer jar.

## Physical host storage budget and recovery

Follow the [physical host storage guard](../implementation/physical-host-storage-guard.md) before staging or launching a rig. Keep at least 50 GiB free on both Linux and the actual VHD host volume. Create additionally budgets all explicit copies, 64 MiB of overhead, and growth of at least 8 GiB (or the copy estimate, if larger). The current source recipes budget about 59 GiB of free capacity at create; this is a per-run estimate, not a fixed promise for other workloads.

On WSL, a large Linux `df` result does not establish host capacity. The guard discovers the current distribution's VHDX through Lxss and queries its actual Windows volume; `LSS_RIG_WSL_VHD_PATH` supplies an explicit actual VHDX path when registry discovery is unavailable. Unknown host location or an unverifiable monitor rejects admission. `doctor` reports storage readiness for known runtime stages; create also includes profile artifacts. Startup and observation sample capacity every 60 seconds and use owned cleanup on a reserve breach.

Retained runs accumulate independently of each run's growth budget. Reassess capacity between runs; do not delete historical evidence or replace independent stages with shared mutable links to fit a run. After an interruption, retain the incomplete directory and reverify recorded input/artifact/evidence hashes before new preparation. Missing manifests or zero-byte staged files establish neither a native attempt nor a pass. A reboot invalidates prior-boot live GPU/owner proof for future measurement binding. See [the environment failure record](test-flakes.md#wsl-physical-host-exhaustion-and-interrupted-staging-2026-09-10).
