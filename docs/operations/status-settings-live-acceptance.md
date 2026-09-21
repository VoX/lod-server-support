# P1/P7 live status and settings acceptance

This is a run checklist, not completed evidence. Use final candidate jars and the [owned disposable rig](disposable-rigs.md). Preserve the run/profile/scenario hashes, actual loaded mod versions, candidate and fixture hashes, action timeline, screenshots, command receipts, config before/after copies, and resulting exports. Keep private paths and raw logs out of public exports. A successful click or clean process exit does not prove the resulting state.

## Applicable UI matrix

Use the profile in the named line's worktree. Resolve blocked dependencies explicitly before launch; do not replace a jar or remove Sodium inside a locked profile without producing a new profile/run identity.

| Line / loader / UI | Profile ID | Required observation |
| --- | --- | --- |
| 1.21.1 Fabric modern | `mc1211-fabric-modern` | Modern Sodium status entry and existing page controls |
| 1.21.1 Fabric legacy | `mc1211-fabric-legacy` | Legacy Sodium status entry and existing page controls |
| 1.21.1 NeoForge modern | `mc1211-neo-modern` | Native modern page, status entry and effective settings |
| 1.21.1 NeoForge legacy | `mc1211-neo-legacy-xaero` | Legacy page, status entry and explicit Xaero preference |
| 1.21.10 Fabric legacy | `mc12110-fabric-legacy` | Legacy UI with this line's actual dependencies |
| 1.21.11 Fabric modern | `mc12111-fabric-modern` | Modern UI and current line's actual dependencies |
| 26.1 Fabric modern | `mc261-fabric-modern` | MC 26.1.2 render API and status screen |
| 26.2 Fabric modern | `mc262-fabric-modern` | Current render, screen replacement and chat APIs |
| 1.21.10 NeoForge legacy | `mc12110-neoforge-legacy` | Legacy UI; renderer capability remains unavailable |
| 1.21.11 NeoForge modern | `mc12111-neoforge-modern` | Modern UI; renderer capability remains unavailable |
| 26.1 NeoForge modern | `mc261-neoforge-modern` | MC 26.1.2 UI; renderer capability remains unavailable |
| 26.2 NeoForge modern | `mc262-neoforge-modern` | Current UI; renderer capability remains unavailable |

Also create an explicitly reviewed no-Sodium variant to exercise the standalone command. Check maintained NeoForge renderer-stub lines against their actual applicable profiles: status must say renderer unavailable, not failed. Dependency/shipping/renderer applicability comes from the [catalog](../compatibility.md), independently of the table's UI requirement. A resolved profile remains unverified for a feature until the exact candidate run proves it.

## Prepare and drive the owned client

Run from the selected worktree. Fill `LSS_PROFILE`, `LSS_RUNTIME`, `LSS_RUN_DIR`, `LSS_WINDOW` and `LSS_WINDOW_IDENTITY` from the reviewed runtime and owned run records, not a personal launcher instance. `LSS_WINDOW_IDENTITY` names the recorded process-creation identity JSON for the client window.

```sh
tools/rig/rig doctor --runtime "$LSS_RUNTIME"
tools/rig/rig plan "$LSS_PROFILE" tools/rig/scenarios/ui-apply.json --runtime "$LSS_RUNTIME"
tools/rig/rig create "$LSS_PROFILE" tools/rig/scenarios/ui-apply.json --runtime "$LSS_RUNTIME"
tools/rig/rig run "$LSS_RUN_DIR"
```

While the owning runner remains active, use a second terminal for identity-checked private input. The following opens chat, enters the real command and presses Return; it does not use the host clipboard or desktop:

```sh
python3 tools/rig/private_input.py "$LSS_RUN_DIR" --window "$LSS_WINDOW" --identity "$LSS_WINDOW_IDENTITY" key t
python3 tools/rig/private_input.py "$LSS_RUN_DIR" --window "$LSS_WINDOW" --identity "$LSS_WINDOW_IDENTITY" text '/lss status'
python3 tools/rig/private_input.py "$LSS_RUN_DIR" --window "$LSS_WINDOW" --identity "$LSS_WINDOW_IDENTITY" key Return
python3 tools/rig/private_input.py "$LSS_RUN_DIR" --window "$LSS_WINDOW" --identity "$LSS_WINDOW_IDENTITY" capture status-open.png
```

Use observed widget coordinates for `click X Y`; do not reuse coordinates across GUI scales or generations. For VSS repeat the relevant branded surfaces with `/vss` and `/vsslod`. Server console command text omits the leading slash. The rig's `commands/<unique-id>.json` accepts a recorded `launch_id`, one-line `command` and optional `response_contains`; its receipt must report the semantic response, not merely submission.

## Screen, state and existing controls

1. Open the actual Sodium options screen and its **LOD status** button on each applicable row. Exercise page changes, **More**, resize/GUI scale, **Done**, and reopen. Check wrapping, reachable controls, restored parent screen and no duplicate entry. From the title-screen Sodium route, **Export** feedback must remain visible even without a player. Exercise `/lss status` in the no-Sodium variant.
2. Run `/lss diag` with reception OFF, then with reception ON and no compatible consumer in a separately reviewed variant. Preserve useful discovery, reception, consumer, renderer, version and freshness facts even when detailed manager counters are absent. Verify unknown integration resolution is not labeled unavailable or failed.
3. In connected runs, capture negotiated service and a server explicitly disabled by its disposable configuration. Exercise protocol rejection/handshake failure only with the matching controlled fixture; keep them distinct from explicit server disable. A missing fixture leaves that row unverified.
4. Apply reception through the status button and through each existing Sodium generation's normal Apply flow. Observe manager/traffic behavior and persisted `receiveServerLods`, not only the checkbox. Verify existing rate slider semantics, page order and far-player preference application using the profile's applicable consumer/renderer. UI slider indices must not become persisted columns/s values. Stage an unrelated slow-start change without Apply, open status and change reception, then return through both **Done** and **Escape** in recorded attempts. Reception must refresh without losing the pending slow-start edit. A real Apply must save that preserved edit; merely seeing an enabled Apply button is insufficient. Escape must return only once: modern Sodium handles its own Escape release by undoing and closing, so the status screen must consume the complete key pair before restoring that parent. Restore all original values afterward.
5. Exercise the existing `receive-lifecycle` and `xaero-map` scenarios with real backlog. OFF must retire acceptance while keeping committed Xaero rebuild debt visible; ON resumes fresh work. Disconnect and same-dimension replacement retire the native world and immediately remove old status counters; an old callback cannot republish them. Rapidly open status/export around replacement and verify the captured lifecycle belongs to the observed session.
6. Compare successive samples: queues are labeled observations, rate gate events are interval deltas, and remote causes stay unknown. `/lss diag` retains its detailed active counters. Do not infer a frame-time pass from visual smoothness; use the separate preregistered status-collection experiment.

## Real persistence failure

Use only the config file inside this run's disposable tree. After successful startup, set `LSS_CONFIG_FILE` to the **actual adopted** client or server config path. The following creates a directory at the temporary-file save target, which makes the production write fail without replacing the existing config or depending on Unix permission bits. It refuses existing blockers and paths outside the run:

```sh
python3 - "$LSS_RUN_DIR" "$LSS_CONFIG_FILE" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1]).resolve(strict=True)
config = Path(sys.argv[2]).resolve(strict=True)
assert config.is_relative_to(root) and config.is_file()
blocker = config.with_name(config.name + '.tmp')
assert not blocker.exists() and not blocker.is_symlink()
blocker.mkdir()
print(blocker)
PY
```

Record the original file bytes, then apply a reception change through the status button and apply a client preset in separate attempts. The effective state must change, feedback must say **not saved**, and original on-disk bytes must remain unchanged. Repeat the server preset staging and an existing runtime `set` action with the server blocker; a failed save must not masquerade as persisted success or undo the effective runtime change. Existing Sodium Apply retains its established logged failure behavior; do not require a new dialog that that path does not implement.

Remove only the empty blocker created above, then retry the action and confirm actual persistence:

```sh
python3 - "$LSS_RUN_DIR" "$LSS_CONFIG_FILE" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1]).resolve(strict=True)
config = Path(sys.argv[2]).resolve(strict=True)
assert config.is_relative_to(root) and config.is_file()
config.with_name(config.name + '.tmp').rmdir()
PY
```

For export failure, use a separate fresh disposable run whose `lss-diagnostics` destination does not yet exist, and create a regular file at that destination before invoking `/lss diagnostics export`. Expect generic failure feedback with no raw exception/path disclosure in report content. Remove only that test-created file, retry, and inspect the resulting JSON plus text. For VSS use `vss-diagnostics`. Generate more than ten reports and check bounded retention; inspect exports for fixture-provided identity/address/seed/path markers. The local displayed output path is expected; embedding it in the report is not.

## Client preset state transitions

Use fresh disposable configurations or deliberately recorded starting values.

| Action sequence | Required result |
| --- | --- |
| Reception OFF, Xaero writes OFF; `/lss preset map-only` | Preview says reception becomes true, preserves the map-write choice, requires a compatible consumer and promises no recipient routing. No file/effective change yet. |
| `/lss preset apply`; `/lss preset undo` | Apply changes only the previewed values and saves; undo restores those values. World/cache data is retained. |
| `/lss preset map-only-xaero-writes` then `apply` | Preview explicitly selects persistent Xaero writes; apply publishes both final booleans before one reconciliation. Observe actual compatible map behavior separately. |
| Preview reception ON from OFF; turn reception ON with the status button; `preset apply` | Relevant-input mismatch rejects the stale preview and requires a fresh preview. |
| Apply from OFF to ON; turn reception OFF with the status button; `preset undo` | Conflicting undo is refused. |
| Preview/apply while changing an unrelated name-tag preference between steps | The unrelated preference is preserved; it does not silently join the preset patch. |
| Preview or apply, then replace connection/world/config scope; apply or undo | Old preview/undo is expired. Repeat application replaces the previous undo history. |

## Server restart overlay and scope

Run the following on disposable Fabric, Paper and applicable NeoForge servers, with running generation initially true. Folia uses the existing owner command path; do not traverse regions synchronously to collect status.

```text
/lsslod set
/lsslod preset pregenerated-world
/lsslod preset apply
/lsslod diagnostics export
/lsslod set lodDistanceChunks 300
/lsslod diagnostics export
```

The preview must explicitly say **SERVER GLOBAL**, generation false for restart, and running generation unchanged. Before apply the file must be unchanged. After a successful apply, exported `generationEnabled` remains true while `generationConfiguredForRestart` is false; the summary marks restart pending. The saved config contains false, and the subsequent unrelated distance save preserves it. Record and restore the original distance after the test.

Run `/lsslod preset undo` before restart in one attempt: it restores only the generation choice. In another attempt, stage and save again, stop through the owning runner, and restart the same disposable server state with a new run identity. Both generation fields must now be false, the restart-pending note absent, and old undo unavailable. A save-failure attempt must keep the configured restart choice distinct from the unchanged on-disk value.

On every support line, exercise the world-distance command separately:

```text
/lsslod set lodDistanceChunks minecraft:overworld 256
/lsslod set lodDistanceChunks minecraft:overworld default
/lsslod set generationConcurrencyLimitGlobal minecraft:overworld 2
/lsslod preset pregenerated-world minecraft:overworld
```

For Paper replace `minecraft:overworld` with the actual disposable Bukkit world name for the world-name override, then also verify dimension fallback and global fallback. Set/removal must publish a fresh map and re-push the effective distance. The last two commands must reject global settings/presets in a world operation without applying or saving a mutation. Other lines do not gain 26.2's world syntax from this checklist.

## Close the attempt

```sh
tools/rig/rig stop "$LSS_RUN_DIR"
tools/rig/rig status "$LSS_RUN_DIR"
tools/rig/rig collect "$LSS_RUN_DIR"
```

Wait for completed owned-process cleanup before collection. Keep `ui-apply`'s four base assertions and `receive-lifecycle`'s five required assertions tied to their proof. A reviewed UI run may explicitly add `parent_binding_refreshed`, `pending_edit_preserved` and `escape_parent_preserved` before creation, with a matching required count of seven. Never increase a proof count after launch or substitute these UI observations for lifecycle fixture assertions. Attach this expanded checklist's observations separately; do not fabricate fixture counts or mark unexercised states passed. Link the exact attempts in the central ledger, including failures and applicability reasons. Product tests and screenshots alone do not close live UI, save-failure, lifecycle or performance acceptance.

The reusable [owned server console driver and receipt checker](server-console-preset-smoke.md) automates the server apply/undo, real save-failure, and two-run restart checks below; it does not replace client UI acceptance.
