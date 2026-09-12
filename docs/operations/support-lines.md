# Compatibility and support-line maintenance

Run from the repository root with Python 3.11 or newer:

```sh
python3 tools/compat/catalog.py validate
python3 tools/compat/catalog.py render --check
python3 tools/compat/catalog.py inspect path/to/mod.jar
python3 tools/lines/lines.py inventory --output /tmp/line-inventory.json
python3 tools/lines/lines.py check
python3 tools/lines/lines.py plan path/to/reviewed-batch.json
python3 tools/verify/verify.py fast common/src/main/java/example.java
```

`config/compatibility/line.json` belongs to this line. Its facts are checked against `.github/line.env`, Gradle properties and the actual renderer constant. Shipping is not rendering, consumer availability, a support tier, or proof of a live test. `source-refs.json` pins exact commits for all five lines; generated documentation uses this snapshot, never moving branch tips. Fetch missing objects explicitly before offline validation. Before opening a cross-line port PR, make every referenced commit available on the remote; CI refuses unavailable snapshot objects. Creating local implementation branches does not publish them. A current candidate comparison is a separate report and must not put its containing commit ID into a checked-in generated file.

Profiles are immutable dependency candidates. They exclude candidate LSS and fixture artifacts, which are part of a separate run identity. `blocked` means dependency resolution or metadata review remains necessary. A hash or filename alone cannot establish a compatible loader route. Unsupported range syntax requires review rather than guessing. Inspection reads embedded Fabric/Neo metadata and hashes the actual bytes without network activity. Installed historical inventories carry no current pass claim. Validation records must identify the exact feature/scenario and run digest; the newest attempt wins even when older successful evidence is reimported.

The file classification catalog is explicit for production/build/tooling paths. New unclassified files fail checking. Identical files must match across every specified source. Adapted files retain an exact reviewed blob per line: a familiar axis token cannot conceal deleted shared logic. Corpus rows preserve their separate provenance; do not regenerate cross-version capture corpora during a port.

A port batch is JSON with `schema_version: 1`, explicit `targets` mapping line IDs to refs, and an ordered `commits` array of full `commit` SHAs and `prerequisites` SHAs. Merge entries also require a reviewed `mainline` parent index. Planning is read-only. To create a reviewed isolated port:

```sh
python3 tools/lines/lines.py prepare reviewed-batch.json \
  --target 1.21.1 --destination /tmp/lss-reviewed-port --branch port/reviewed-batch
```

Preparation refuses dirty sources and existing destinations, never merges target branches, and stops with conflicts intact. Its Git-worktree administrative directory retains `lss-port-state.json` containing original source/target identities, completed picks, and conflict paths. Resolve conflicts with normal Git review, re-run semantic gates, and compare the saved identities before continuing; do not reinterpret an adapted patch as already applied merely from its subject. No push or deployment occurs.

The optional CI allowance file, `config/lines/port-batch.json`, serves a different purpose from the ordered cherry-pick proposal above. It requires `schema_version: 1`, `baseline` exactly matching the catalog snapshot, all five `required_targets`, explicit per-target `prerequisites`, and `reviewed_candidate_blobs` mapping each target's changed paths to full Git blob IDs (or JSON null for a reviewed deletion). `final_shared_invariants` lists paths whose reviewed final blobs must be present and identical on every target. Prerequisites must be acyclic. A candidate cannot waive another line's adaptation, a newly unclassified path, or bytes changed after review. A candidate report always leaves `batch_complete` false: after every target is ready, record their fixed source snapshots and run the full explicit-ref cross-line check. Build and live acceptance remain separate gates.

`tools/verify/verify.py` provides `fast`, `platform`, `integration` and `full` entry points; add `--run` to execute. Fast selection is advisory. Unknown and shared source/tooling impact choose full platform validation and report that all configured lines require validation. `--run` executes the selected checkout only; repeat the reported gates in the other isolated line checkouts to close cross-line acceptance. Java 21 is required on 1.21.x, Java 25 on 26.x; common tests always use Java 21. Full existing CI/release checks remain required regardless of fast selection.

Full and unknown-impact validation explicitly include the real client-gametest task on all four newer lines; it remains absent on 1.21.1. On Linux, `verify --run` requires `xvfb-run` for that task and explicitly enables Loom’s private-display setting while removing inherited desktop display/authority variables. Loom’s default only chooses Xvfb in CI, so local automation must not rely on that default. The client test uses the null audio backend to avoid the recorded WSL device-open stall; audio behavior is outside this lane. This headless correctness lane does not establish GPU performance.

Verification with `--run` takes the existing coarse harness lock before Gradle starts. It refuses an occupied rig or server and uses the owned-process supervisor for cleanup.
