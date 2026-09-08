# Astra review evidence — 2026-09-06

Canonical deliverable: [fix/improvement plan](../../planning/astra-system-review-fix-plan.md). All reports describe unchanged production source at the merged baselines in scope.json. Reports retain their original local paths and chronological assessments; the final plan and validated-index.json incorporate subsequent validation and supersede pending language in individual reports.

Fifteen completed Astra passes comprise ten MC1.21.1 subsystem lenses, three cross-line lenses, and two final validation/challenge lenses. Three reviewer slots were reused. Initial wire/storage passes were interrupted; reports01/03 describe the narrower parent work. See the plan for limits and remaining live checks.

## Evidence inventory

- Numbered Markdown reports: reviewer findings, dismissed candidates, source references and acceptance proposals.
- validated-index.json: final sixteen primary entries mapped to fifteen work packages. Generation extends session ownership; the Xaero gauge race is a companion, not another independent package.
- final-probe-results.json and final-probe-xml/: authoritative deduplicated seventeen methods, sixteen intended RED assertions and one GREEN normal-read control. No execution errors/skips.
- final-baseline-results.json / final-baseline.log: unchanged ordinary suites green; NeoForge UP-TO-DATE rather than freshly rerun. wire-baseline-results.json / wire-baseline.log:169 separate focused baseline tests, all green.
- 15-artifact-inspection.json: thirty existing artifacts, not a fresh rebuild. cross-line-inventory.json and bytecode/diff files document source and dependency inspection.
- probes/ and paper-probes/: external diagnostic sources, outside normal Gradle source sets. These use existing seams and selected reflection/fixture stubs; they are evidence prototypes, not ready-made production regressions. The plan records where fixtures need strengthening.

## Reproduce the diagnostic run

These tests intentionally fail on the reviewed baseline. They use temporary test fixtures, not the running test-server world. They do not modify production source. The opt-in init script adds external test sources only for that invocation. Do not run alongside a soak; use the correct Java21 JDK and the recorded MC1.21.1 source tree. Later fixed commits should change these outcomes.

```bash
review_root="$PWD/docs/reviews/2026-09-06-astra"
# Use this line's Java 21 JDK. Run from the MC 1.21.1 worktree.
./gradlew -I "$review_root/probe.init.gradle" -PlssReviewRoot="$review_root" \
  :fabric:test --tests 'dev.vox.lss.common.farplayers.FarPlayerDisableReviewTest' --tests 'dev.vox.lss.common.farplayers.FarPlayerIdentityReviewTest' --tests 'dev.vox.lss.common.processing.GenerationSessionOwnershipReviewTest' --tests 'dev.vox.lss.common.processing.SessionOwnershipReviewTest' --tests 'dev.vox.lss.common.store.ReviewMaskShutdownTest' --tests 'dev.vox.lss.common.store.ReviewMigrationChecksumTest' --tests 'dev.vox.lss.compat.VoxyHolderCompatibilityReviewTest' --tests 'dev.vox.lss.compat.XaeroOwedDisconnectReviewTest' --tests 'dev.vox.lss.networking.client.ClientMasterToggleReviewTest' --tests 'dev.vox.lss.networking.client.ReviewSummarySentinelTest' \
  :paper:test --tests 'dev.vox.lss.paper.LibsDisguisesReloadReviewTest' \
  --continue --max-workers=1 --console=plain
```

Afterwards, run ordinary tests without the init script to restore normal compiled test inputs:

```bash
./gradlew :fabric:test :paper:test :neoforge:test --max-workers=1 --console=plain
```

Raw review working files remain at /home/vox/.local/state/lss-review/20260906-astra. The evidence bundle excludes downloaded mod jars. No gameplay, GUI, gametest, soak or benchmark execution is claimed by this review. Installed mods and the normal test server were left unchanged.
