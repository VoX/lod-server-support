# Pure Java test placement

`./gradlew :common:test` executes pure Java cases with JUnit Jupiter and a Java 21 test JVM on every line. It has no Minecraft or loader dependency. Explicit test-only fastutil, Gson, SQLite and zstd dependencies supply the libraries that production common declares compile-only. Platform `check` tasks depend on `common:test`, preserving required aggregate/CI coverage after the move.

The 67 moved suites preserve package names, source bodies and parameter identities. `RegionSummaryServiceFixture` is shared with a Fabric integration suite and therefore lives in `common/src/testFixtures`; only test configurations consume it. It is absent from the production common jar. The MC-dependent batch/parity suites, Netty transport math, Via stub test and cross-platform stamped-call source pin remain in Fabric.

The native-shape suite remains per-line: moving its task does not erase the line's actual descriptor or regenerated corpus. Common test working directory is explicitly `fabric`, preserving the corpus locator. The corpus directory and common production sources are declared task inputs, so source-reading architecture/native tests and external fixture changes invalidate the task cache. Corpus resources stay in their original tree and are never repackaged as shipping resources.

`docs/implementation/test-baseline-sources.json` captures source dependency/resource discovery before edits. `test-moves.json` records each old/new task, working directory, engine, properties and resource provenance. Source annotation counts are an inventory aid, never claimed execution counts. To capture actual parameterized cases and assumptions:

```sh
python3 tools/tests/inventory.py validate-moves
python3 tools/tests/inventory.py capture --output /tmp/before.json
# Run the changed common and Fabric tasks; Gradle replaces each task's XML reports.
python3 tools/tests/inventory.py capture --output /tmp/after.json
python3 tools/tests/inventory.py compare --before /tmp/before.json --after /tmp/after.json --moves docs/implementation/test-moves.json
python3 tools/tests/test_inventory.py
```

The source guard rejects a migrated source reappearing at its former owner. The comparison rejects missing parameter cases, changed pass/skip outcomes, duplicate execution, absent baselines and old-task XML left behind after a move. A stale report is not proof a class still executes; refresh its owning task and compare again. It does not claim that an unexecuted sibling line is accepted.

The initial 1.21.1 baseline executed 904 cases (Xaero and all original common-package suites), all passed. After extraction/migration, 689 moved case/outcome identities matched exactly; common ran 697 cases including 8 concurrently added status/settings cases, and the selected Xaero suite ran 176, all passed. These are dated intermediate artifact results; final task results and live gates belong in the implementation ledger. Later cached-status controls add snapshot lifecycle and cold-query purity cases.

Observed initial task invocations took 2m30s before and 2m04s after; their compile/task-cache conditions differ, so this is **not a speedup measurement**. A later matched warm-compilation control ran the same 689 cases with test execution forced: original Fabric 129.065s wall / 115.114s summed cases, moved common 127.233s wall / 114.671s summed cases. Each arm had a separate untimed warmup, and every compile/resource dependency was UP-TO-DATE in its measured run. This single pair establishes comparable timing conditions, not a general performance improvement. Raw commands, per-case outcomes and logs live under `~/.local/state/lss-project-improvements/migration-matched-timing`. The external corpus input probe also passed: unchanged selected test UP-TO-DATE, addition of an owned non-golden corpus marker forced execution and was identified by Gradle as an added input, then the marker was removed. Evidence is under `~/.local/state/lss-project-improvements/common-input-invalidation`. Sibling execution accounting and final platform/live gates retain their own evidence. Incidental source pins were not removed; architectural/mapping/entrypoint pins remain mandatory.

Pre-migration sibling XML is retained as `test-historical-baseline-cases.json`, explicitly an identity reference rather than acceptance of new code. The final matrix must freshly execute all applicable tasks. The expected moved-case counts differ by native-line tests: 689 on 1.21.1, 688 on 1.21.10/1.21.11/26.1, and 692 on 26.2. `tools/tests/check_artifacts.py` recursively rejects common test/helper and maintained fixture classes in all six local LSS/VSS artifact families; the CI build invokes this guard.

Post-migration regression tests are recorded separately in `test-moves.json` under `post_migration_additions`: exact executed identity, owning task, expected passed outcome/count one, rationale and introducing source commit. This extends only the expected post-move inventory; it never rewrites original XML or permits a lost/changed old case. Unknown, missing, duplicated, wrong-task or failed/skipped additions fail comparison; a baseline identity cannot be declared as new. The original `source_unchanged` entries describe the migration operation at its historical checkpoint, not a promise that moved suites never gain later regression tests. The loaded-probe stale-stamp regression is the single currently declared addition.
