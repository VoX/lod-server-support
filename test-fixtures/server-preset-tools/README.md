# Owned server preset acceptance tooling

These Python entrypoints drive and independently verify qualitative and conservative
server preset controls using the existing owned rig command queue. They are test-only
orchestration. They do not build a mod/plugin, own a new supervisor, select measured
values, or publish a product. The server/client fixtures and main rig own native lifecycles.

Stage the following exact repository files into each private rig runtime:

| Repository source | Run stage target |
| --- | --- |
| test-fixtures/server-preset-tools/drive.py | preset-tools/drive.py |
| test-fixtures/server-preset-tools/verify.py | preset-tools/verify.py |
| tools/rig/server_control_smoke.py | preset-tools/server_control_smoke.py |
| tools/rig/conservative_native.py | preset-tools/conservative_native.py |
| tools/rig/check_conservative_native.py | preset-tools/check_conservative_native.py |

Record every SHA256 in stage_files and preset_contract.fixture_artifacts before create.
Use an explicit owned run root and the checked-out repository's tool root:

```sh
python3 "$RUN/preset-tools/drive.py" "$RUN" "$REPO/tools/rig"
python3 "$RUN/preset-tools/verify.py" "$RUN" "$REPO/tools/rig" --cleanup
```

The driver is a rig launch, after the server and native preset-client handshake. It
uses the existing console queue and stays alive until the owning supervisor stops it.
The second command is an independent after-collection verification, not a substitute
for owned rig collection. Match active tool bytes to the frozen staged dependencies;
never mix a newer checkout's checker with an older run without explicit provenance.

`runtime.preset_contract` names config, platform, server_profile_hash, exact production
and fixture artifact maps, modes, previous_run/previous_receipt_sha256, and the accepted
conservative_target dictionary. Target keys are lodDistanceChunks,
generationConcurrencyLimitGlobal, and generationConcurrencyLimitPerPlayer. Values
must be accepted measurement-derived integers already compiled into the final product.
Synthetic unit-control values are not preset recommendations.

Run the existing qualitative modes apply-undo/save-failure/stage-restart, then
conservative-numeric LAST in each before-restart attempt. It restores exact staged
config bytes so a separate after-restart attempt can use the same previous qualitative
receipt. Require every before checker and cleanup/observer closure gate before creating
its after run. Use one before/after pair each for primary Fabric, NeoForge and Paper.
Do not automatically retry a failed attempt or erase its evidence.

Keep the native smoke observer and ordinary client lodDistanceChunks=1 preference.
The unchanged product logger records every actual received v20 SessionConfig radius;
the observer pins the native world/connection and records handshake/closure. Numeric
acceptance recomputes typed exports, persisted config, actual filesystem save error,
contiguous client-log windows, and original console results. No console success string
alone proves an effect. Global/per-player atomic publication remains unit controlled.

Run Python checker controls from any checkout location:

```sh
python3 tools/rig/test_conservative_native.py
```

They use synthetic inputs and deliberate bad evidence to test rejection. Native
acceptance still requires actual final shipping artifacts, maintained storage guard,
owned private runtime, frozen recipe/tool closure, and after-collection verification.
