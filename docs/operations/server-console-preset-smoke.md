# Owned server console preset smoke

Use `tools/rig/server_control_smoke.py` in a **separate disposable settings
attempt**, while its rig supervisor is running and polling `commands/`. The
driver writes bounded single-line requests to that existing queue. It does not
open a foreign process, use a shell console command, or restart/stop a server.
Paper and Fabric use the same console commands, without a leading slash:

```text
lsslod diag
lsslod diagnostics export
lsslod preset pregenerated-world
lsslod preset apply
lsslod set lodDistanceChunks <temporary-distance>
lsslod diagnostics export
lsslod preset undo
lsslod set lodDistanceChunks <original-distance>
```

The driver derives a valid adjacent distance from the typed initial export and
restores it. It first saves the already-running distance once, which canonicalizes
a minimal config file so later comparisons can distinguish newly serialized
defaults from an actual preset scope change. Preview must leave those bytes
unchanged. Exports must have exactly the typed server DTO fields and the bounded
named component version allowlist; new unrelated loader metadata fails the check.

Select the actual adopted server config path inside the owned server tree:

```bash
python3 tools/rig/server_control_smoke.py "$LSS_RUN_DIR" \
  --config "$LSS_CONFIG_FILE" --mode apply-undo
```

This requires running and configured generation both initially true. It checks
that applying the pregenerated-world preset persists generation false for the
next restart, while running generation remains true. It changes the distance,
checks the unrelated save preserves the restart overlay, and verifies undo
restores only generation while retaining the temporary distance. Finally it
restores the original distance and checks the complete canonical config matches
its baseline. The actual preview must say SERVER GLOBAL and explain restart.

A separate real save-failure run creates an empty directory at the config's
`.tmp` save target (only if absent), exercises apply, unrelated runtime set and
undo, and removes only its own empty blocker. Effective values must update while
persisted bytes stay unchanged; command feedback must say not saved. After
removing the blocker, the original distance is restored and the final state is
persisted and checked:

```bash
python3 tools/rig/server_control_smoke.py "$LSS_RUN_DIR" \
  --config "$LSS_CONFIG_FILE" --mode save-failure
```

The server's actual configured-versus-effective restart outcome requires two
attempts. First stage the preset and leave the persisted generation choice false:

```bash
python3 tools/rig/server_control_smoke.py "$LSS_RUN_DIR" \
  --config "$LSS_CONFIG_FILE" --mode stage-restart
```

Wait for the printed `evidence/settings-<id>/receipt.json` result. Stop through
the owning rig. Create a **new run identity** using that disposable server state
and the exact staged config bytes; ensure the next runtime's generated files do
not replace them with the original config. Start the next owned server and run:

```bash
python3 tools/rig/server_control_smoke.py "$LSS_NEW_RUN_DIR" \
  --config "$LSS_NEW_CONFIG_FILE" --mode after-restart \
  --previous-receipt "$LSS_STAGING_RECEIPT"
```

The second mode requires a successful stage receipt from a different run, exact
staged config SHA256, both generation fields false, no restart-pending summary,
and the real command response `No preset application to undo.` The stopped run
cannot lend in-memory undo state to its successor. VSS uses `--brand vss`, which
selects `vsslod` and `vss-diagnostics`; use its actual adopted config path.

After cleanup, independently recheck the preserved receipt and original command
results/log offsets without sending commands:

```bash
python3 tools/rig/server_control_smoke.py "$LSS_RUN_DIR" \
  --verify-receipt "$LSS_RECEIPT"
```

Every mode writes a receipt bound to run/profile/scenario hashes, owned command
results and log offsets, copied bounded JSON/text exports and their hashes, and
the final config hash. A failed mode is retained as failed; it does not authorize
a blanket config rewrite or claim the wider UI/performance gates passed. Inspect
and explicitly clean up any reported blocker cleanup failure in that disposable
run before reusing its state. Numeric conservative presets remain unavailable
until their separate measured prerequisites pass.

Eight focused driver tests pass, including actual owned-supervisor stdin queue
submission to a disposable Python child. The four modes still require real
Paper/Fabric runtime acceptance; simulated command responses are not that gate.
