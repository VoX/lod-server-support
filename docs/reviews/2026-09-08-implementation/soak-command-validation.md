# Soak setup command validation — 2026-09-08

A real Folia 26.2 auto-prime exposed invalid setup: all five legacy gamerule names
printed parser errors, while the old exporter wrote `ok=true` because Minecraft
handled the errors without throwing. The checker and informational report could not
distinguish that from successful setup. The parent stopped that attempt with exit143;
it is not accepted evidence. Its original logs and exports are archived outside the
repository under `20260908-implementation/runtime/folia-rejected-first`.

The correction changes development harness code and per-version scenario data only.
The ordinary LSS delivery/store/lifecycle code is unchanged. Modern gamerules are
adapted on 1.21.11/26.1/26.2; 1.21.1/1.21.10 keep the other legacy names.
The actual-tree regression additionally caught that spawnChunkRadius is already absent
on 1.21.10: remove those four obsolete steps there too; 1.21.1 retains the real rule.
The canonical per-version
surfaces note records the engine evidence for the names, fire radius0, and removal of
the obsolete spawnChunkRadius command.

Both excluded dev-only executor twins parse the real native command and require an
executable context chain. Generic commands retain dispatch-only acceptance: an
idempotent cleanup can legitimately have no affected targets, and Folia may schedule
an accepted teleport asynchronously. Existing scenario outcome checks still prove
those effects. Gamerules additionally require the setter's successful result callback
and an exact query readback; result0 is successful when the callback says so. Paper
fans out to every loaded dimension and retains the same dimension prefix on readback.
Its existing native routing and Folia scheduling are preserved. An explicitly mapped
Folia save-all remains an acknowledged no-op, backed by the existing autosave/shutdown
contract; it is not reported as an executed save command.

Rows distinguish `validation=dispatch`, `gamerule-readback` and `folia-noop`. The checker
fails explicit unsuccessful commands and rejects gamerule rows lacking readback proof,
including old recordings whose `ok=true` only meant did-not-throw. This deliberately
prevents historical false greens from being silently reused as current setup evidence.
The informational report now labels a failed step as validation/execution failure,
without falsely claiming every such failure threw an exception.

The helper is restricted to independent driver tick callbacks, outside an active
Minecraft command execution context. The real-tree regression verifies that a nested
command defers its callback and cannot be accepted prematurely. Other gametest controls
cover actual JSON commands with save/restore, unknown and incomplete syntax, handler
failure, successful zero-valued setters and wrong readback. A source parity/exclusion
contract ensures the Paper twin matches the Fabric executor exercised by those tests
and both remain excluded from release jars. Checker selftests cover failed rows,
zero-success proof, generic cleanup and explicit Folia mapping.

Validation results and exact runtime acceptance evidence are recorded in the external
implementation ledger after the gates finish. A corrected Folia smoke remains
single-player evidence; Folia's experimental multi-region limitation is unchanged.

## Completed focused validation

All five lines passed the real server gametest suite with the three new command tests:
26.2 **79/79**, 26.1 **79/79**, 1.21.11 **80/80**, 1.21.10 **80/80**, 1.21.1 **78/78**.
Each line also passed the two Fabric executor parity/exclusion unit tests and four Paper
soak unit tests. Each checker passed **280 selftests** and all **33 timeline validations**;
the informational report passed its **20 selftests** on26.2.

The 1.21.10 first attempt caught its test-only Component assertion seam (adapted to the
existing Gt helper); the second reached the real command tree and rejected the removed
spawnChunkRadius rule. After removing those four obsolete steps, the third passed.
Those failures are preserved, not described as flakes. Other lines passed their first
focused attempt. The new checker separately rejects all five actual archived Folia
`ok=true` gamerule rows for lacking semantic proof, without modifying that archive.

Logs, exit receipts and XML snapshots are under the external implementation evidence
`runtime/soak-command-*` paths. This source/test correction does not itself claim a
corrected live Folia soak: the parent owns that subsequent acceptance run.
