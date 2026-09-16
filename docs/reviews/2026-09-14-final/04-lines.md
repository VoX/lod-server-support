# Fresh final Astra review 04 — compatibility, support lines, tests, shipping

Date: 2026-09-14. Independent read-only review of assignment 4. One actionable medium-severity finding; no other actionable findings in the inspected scope. No Java, Gradle, native client/server, network, Windows foreground, or child-agent work was performed. Maintained sources were not edited. Temporary Python/Git/ZIP negative controls and read-only artifact inspection were performed.

## Finding R04-1 — wrong-loader metadata can make a profile launch-ready

**Severity: medium (P2 tooling correctness). Observed, reproducible. Applies to all five lines.**

Location: `tools/compat/materialize.py:98` (native metadata branch; surrounding descriptor selection at line 81), and the asymmetric admission check in `tools/compat/catalog.py:147` onward. All five frozen lines carry materialize blob `afb4ce2e5aef3b37256a2993ca819b6db47501e3` and catalog blob `cc1eac670c17bba90c757a411ab424a108d8d64b`.

Trigger: an unverified **native Fabric** profile contains an exact-hash jar with **only `META-INF/neoforge.mods.toml`**, no Fabric descriptor, and a satisfiable Minecraft dependency. Catalog profile validation accepts it; materialization reads its native descriptor, registers its mod IDs/dependencies, and returns `ready: true`, `errors: []`, `missing: []`. Native descriptor processing lacks the platform guard present in the Fabric branch. The same missing guard permits native mod metadata on Paper profiles; the directly executed minimal reproduction is the Fabric case.

Consequence: the compatibility authority certifies dependency readiness using a mod the selected loader cannot load. It can also count that mod as a dependency provider. This contradicts exact loader-route admission and can cause a late launch failure or a silently absent optional integration. It does **not** demonstrate a false final live pass: real-loader/scenario proof remains another gate. Inspection of the tracked profile inventories did not identify an existing Neo-only jar on a non-Neo profile; this is a reproducible validator hole, not a claim that the current final candidates contain this mismatch.

Concrete reproduction and full result are durable beside this report:

- `04-loader-route-repro.py`: complete temporary ZIP/profile construction, fixed ZIP timestamp, no download or Java.
- `04-loader-route-repro-result.json`: exact profile, embedded TOML, artifact hash, and observed `ready: true` result.

Run `PYTHONDONTWRITEBYTECODE=1 python3 /home/vox/.local/state/lss-project-improvements/recovery-20260910/final-astra-reviews-20260914/04-loader-route-repro.py` against the reviewed frozen source. It creates and removes only its own temporary directory. The existing `test_native_multi_loader_jar_uses_native_descriptor` was read first: a fix must preserve dual-format jar selection on native Neo. Require a compatible selected top-level descriptor for the platform/route, and traverse only metadata/nested declarations that the selected loader actually uses. Add negative controls for Neo-only on Fabric/Paper and retain positive dual-format Fabric/Neo and Connector controls. Coordinator was notified before report completion and is independently validating; this report does not assume a fix has landed.

## Exact source scope

Read each checkout's current `CLAUDE.md`; the implementation plan is byte-identical on all five lines (SHA256 `d6d5ac2d4efd033a16836d7d12045fa90698d350940b14ba8c440c2c340bac9b`). Reviewed P0/P2/P3/P6a, validation and amendment boundaries, and inspected actual baseline-to-final changes in build/workflow/test placement/catalog/line tooling. Baselines come from `docs/implementation/baseline.json`; final refs match `accepted-performance-v27-20260914/docs-and-refs-integration.json`:

| Line | Baseline | Frozen final |
| --- | --- | --- |
| 1.21.1 | `48268c7d7e8740a93fbc6c7cf60fb36f7b75d690` | `0ae20c5f5f41e9482f865b5da7faf6a1d16bfacd` |
| 1.21.10 | `948b0bf09dd852d357176577d7ccacd589aadb7d` | `8a6457bd4250b495d523f9382ff3f73a1c5e7353` |
| 1.21.11 | `8ac799a901e7eb99cb9403d512b790a0b6576190` | `b8828f5ad550c73e26de158b3c56c877cc6e5d8a` |
| 26.1 | `1e7c9a9d3507c3ba90ac511ba6b62995b8796ab2` | `f8f31bb68432db75b32908c83890587cc0ee0dd6` |
| 26.2 | `98b67abc82283417ad288cbb0cca2f4fb3343425` | `6f7081101b4fbbdb946b6160618bc71cd8d20b44` |

Source snapshots intentionally point to preceding code commits. They are exact numeric references, not moving branches or a self-reference to the containing documentation commit. All five final manifests bind the actual frozen final HEAD, record executed unit tasks, unchanged source/artifact inputs, and first-attempt success. No historical validation record/profile was treated as final acceptance. No final 52-run native evidence existed for this review.

## All-line comparisons and retained boundaries

| Line | Platform / common Java | Neo shipping / renderer | Folia | Client gametests | Whole-line tier |
| --- | --- | --- | --- | --- | --- |
| 1.21.1 | 21 / 21 | shipped / available | absent | absent | best-effort |
| 1.21.10 | 21 / 21 | maintained build / stub | absent | present | correct-not-perfect |
| 1.21.11 | 21 / 21 | maintained build / stub | experimental | present | correct-not-perfect |
| 26.1 | 25 / 21 | shipped / stub | experimental | present | correct-not-perfect |
| 26.2 | 25 / 21 | shipped / stub | experimental | present | full |

26.1's actual platform target remains 26.1.2. Neo support commitment remains best-effort separately from the whole-line tier. Inspected native descriptors are unchanged from each original baseline: one count short on 1.21.x, two on 26.x; 1.21.1 retains the prefixed native long-array flavor and its folds. V20 native-gating/literal tests remain explicit. The wire subtree and primary-line corpus were not rewritten by this task. Java 21 common test runtime is explicit on every line, and 26.x build/release workflows install the extra Java 21 runtime before selecting Java 25 for Minecraft.

Reviewed current README/catalog views and line-local locked profile inventories: 1.21.1 retains both Sodium generations and distinct native/Connector profiles; 1.21.10 legacy UI, newer modern UI, both separate 1.21.11 C2ME profile families, and intentional Neo renderer stubs remain represented. Current generated compatibility documentation makes no live validation claim. Folia's experimental label survives. UI implementation/lifecycle behavior and the 26.2 Paper per-world implementation are owned by other review scopes; this review checked their line classifications and build/test surfaces, not duplicate behavioral review.

`config/lines/classification.json` explicitly classifies 1,508 paths. Reviewed exact-blob protection for adapted/generated files, narrow nonproduction wildcards, native/corpus provenance, and `support-line-adaptation-audit.json` as historical explanation rather than final acceptance. Executed an explicit-ref cross-line check using **all five frozen final HEADs**: `passed: true`, `issues: []`. Ran catalog validation and generated-view freshness checks in every checkout: all passed.

Read `tools/lines/lines.py`, `test_lines.py`, `test_resume_skip.py`, `tools/compat/ci.py`, `port_batch.py`, and their relevant tests. Planning uses a private scratch index/repository; clean isolated preparation preserves conflicts and identity; resume verifies branch/HEAD/CHERRY_PICK_HEAD/source patch identity and handles both exact ancestors and patch equivalents before subsequent picks. Batch allowances bind exact candidate blobs and cannot waive another line or declare a one-line comparison batch-complete. No additional actionable finding here. Remote availability of exact snapshot objects is intentionally a later publication/CI prerequisite; no remote fetch or publication was attempted.

## Test and shipping verification

Read the migrated native-shape pins, inventory comparison and negative controls, build wiring, artifact exclusion, plugin/release workflow contract tests, impact selection and selector tests before judging behavior. No incidental source pin was removed in this scope. Common's Java/test dependency closure, `fabric` working directory, original corpus location, common-source and external-corpus task inputs, and test-only fixture consumption remain explicit.

Executed 75 bounded offline tests from `test_catalog`, `test_materialize`, `test_port_batch`, `test_gametest_capability`, `test_lines`, `test_resume_skip`, `test_inventory`, `test_artifacts`, and `test_verify`: all passed. These existing tests do not cover R04-1. Executed migrated-source validation on every line: 67 suites each, no old owner reappeared. Independently compared retained baseline XML identities to current final XML via `inventory.compare`:

| Line | Original moved cases | Final moved cases | Explicit later additions | Problems |
| --- | --- | --- | --- | --- |
| 1.21.1 | 689 | 696 | 7 | none |
| 1.21.10 | 688 | 695 | 7 | none |
| 1.21.11 | 688 | 695 | 7 | none |
| 26.1 | 688 | 695 | 7 | none |
| 26.2 | 692 | 699 | 7 | none |

These seven additions per line refer only to migrated suites, not the separate all-suite 82-new-JUnit accounting assigned to the source owner. No headline count was used to infer migration preservation. Unknown/shared impact retains full platform validation; fast selection remains advisory, all newer lines explicitly include client gametests, and 1.21.1 excludes the nonexistent task.

Read all five final `attempt-final-integrated-20260914/manifest.json` records under `final-validation-after-preset`. They report source identity stable, release check 0, fixture exclusion 0, migration comparison 0, executed common/Fabric/Paper/Neo unit tasks, and server required-test summaries of 78/8, 80/8, 80/8, 79/8, 79/8 respectively. Recorded unit totals and named skips were inspected; they are preserved evidence, not new test execution by this reviewer. The final native 52-run matrix is still separate and unreviewed.

Independently reran the read-only Python `scripts/release_check.py` and `tools/tests/check_artifacts.py` on every line: all ten invocations passed. They inspected all six LSS/VSS artifact families per line, including nested fixtures/common jars; existing cross-brand class/nested-common byte identity and metadata/wire boundaries remain enforced. Independently hashed every artifact named by the five final gate manifests: all matched, no mismatch. Build/release changes preserve existing publishing behavior; excluded artifact-promotion redesign was not reviewed or requested.

Limits: this is source/offline/artifact review, not fresh native rendering, UI, concurrent-region, or performance acceptance. The current native matrix, public source-ref reachability, and final source-owner new-test accounting require their own closure. The catalog loader-route issue is the only actionable finding from this review.
