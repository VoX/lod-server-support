# Owned no-Sodium client preset controls

These entrypoints drive and recompute the existing standalone status, map-only, explicit Xaero writes, real filesystem save failure, effective snapshot and exact config restoration checks. They use the maintained owned rig; they do not launch a server, display or supervisor.

Stage these four Python files from `test-fixtures/client-preset-tools/` to `preset-tools/` in the runtime recipe before create. The runtime must bind their hashes in staged inputs and bind the selected repository's maintained `tools/` closure. The operator chooses an existing run and the exact repository containing that closure. Run staged entrypoints, or byte-identical portable copies:

```sh
python3 RUN/preset-tools/drive.py RUN --repo REPO --joined-layout INSPECTED_LAYOUT_JSON
python3 RUN/preset-tools/verify.py RUN --repo REPO
python3 RUN/preset-tools/finalize.py RUN --repo REPO --visuals-checked
```

The joined layout must be derived from an actually inspected owned native frame, with the existing run/screenshot hash and status button coordinates. Drive validates input and active source identities before owned input. Verify independently repeats these identity and raw evidence checks. The required `--visuals-checked` flag records an actual operator inspection of the retained standalone status screen; automation must not supply it without that inspection. Raw controls and screenshots never imply visual approval. Complete owned cleanup and enclosing rig acceptance remain separate required gates.

The four original tools were recovered byte-exact from historical runs; these portable files are a new derivation and require fresh runtime/source hashes. They cannot retrospectively replace historical proof tools. No command, feedback window, snapshot timing, filesystem blocker, restore or screen-inspection policy was changed.

Run the small portability/identity controls with `python3 -m unittest discover -s test-fixtures/client-preset-tools -p 'test_*.py'`. They use temporary Python files and synthetic manifests only, and establish no native result.
