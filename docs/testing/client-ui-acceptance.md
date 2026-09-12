# Native client UI acceptance

The two maintained seven-assertion routes cover negotiated `ui-apply` clients and intentionally consumer-free native NeoForge `client-ui-no-consumer` clients. Both run the same raw settings/export/capture checker. The latter alone exempts negotiation and requires connected/no-consumer/protocol0/unavailable-renderer/zero-reception facts. Neither route proves map rendering, far-player drawing, lifecycle transport or performance.

Use a fresh owned Prism client/server recipe and the exact locked artifact profile. Announce exclusive runtime ownership and preserve the run's tool-source snapshot through collection. First-launch dependency acquisition is a failed attempt if the pinned closure changes; collect it and explicitly prepare a new recipe with the acquired closure. Never repair an existing run's input hashes or promote that failed run.

After the native title screen appears, find/capture the actual owned window:

```sh
python3 tools/rig/client_ui_steps.py RUN discover
```

Use maintained `private_input.py` only with that run's guarded window and process identity to navigate the real native menus and Direct Connection. Inspect the screenshots; never assume sibling coordinates. Create `RUN/evidence/observed-layout.json` with the actual run_hash, dialect (`legacy` or `modern`), inspected_screenshot filename and SHA256, and actual coordinate pairs: status_export, status_reception, status_done, sodium_status, sodium_slow, sodium_reception, sodium_apply; modern layouts may need sodium_general for the LSS General page. The helper verifies this binding and every private display/window input operation.

With the actual world visible, `client_ui_steps.py RUN status --layout LAYOUT` types `/lss status`, captures it and creates a fresh typed export. Inspect readability and declared native capabilities. Navigate the actual Sodium LSS page and rebind its inspected layout before the remaining phases:

```sh
python3 tools/rig/client_ui_steps.py RUN pending --layout LAYOUT
python3 tools/rig/client_ui_steps.py RUN restore --layout LAYOUT
python3 tools/rig/client_ui_steps.py RUN save-failure --layout LAYOUT
```

`pending` stages slow-start OFF without applying, opens status, saves reception OFF, uses one real Escape press/release to return, then applies the preserved slow-start draft. `restore` returns through Done, restores reception/slow-start ON through the actual Sodium Apply button, and records a canonical baseline. `save-failure` creates an actual empty `.tmp` directory only when absent, verifies reception OFF is effective while disk bytes are unchanged, removes only that owned empty blocker, restores ON and verifies exact baseline bytes. If any phase fails, stop and diagnose; the helper never declares a visual pass. Restore an outstanding setting/blocker before teardown where possible, preserving the failed evidence.

Inspect all six required captures: status command, status entry, parent refreshed/pending draft, save-failure feedback, final saved state and Escape parent return. After that actual operator inspection:

```sh
python3 tools/rig/finalize_client_ui.py RUN --visuals-checked
```

The finalizer verifies raw evidence, actual candidate bytes, restored config and absence of the temporary blocker. It writes an exclusive proof and a run-bound operator-inspection receipt with screenshot hashes; it refuses to replace an existing proof. The runner must then stop all owned processes and pass collection. Export feature `status-ui` only from a passed, completely cleaned-up run using `tools/compat/export_validation.py`.

Operator UI inspection is permitted for these assertions. It is not a fabricated user review: map and far-player scenarios retain their separate explicit real-user image review. Older seven-assertion UI evidence can be rechecked read-only with the same strict raw checker; four-assertion legacy records do not satisfy the current gate. Such a recheck never changes a failed runtime/closure/cleanup result.
