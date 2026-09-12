# Project improvements implementation ledger

**In progress — 2026-09-12.** Nonnumeric tooling and current classification metadata are committed across all five lines. The prior all-five full gates remain valid for their recorded source heads. The coverage diagnostic failed eight closed targets; a subsequent overlap correction passes 73 targeted tests on 26.2, but diagnostic `20260912T061930Z-19127a71249f` failed five closed B targets (2849–2853). Porting and new full gates remain pending. Performance, measured conservative values, final native acceptance and six final Astra reviews remain unfinished.

The [implementation plan](../planning/2026-09-09-project-improvements-implementation-plan.md) governs Minecraft 1.21.1, 1.21.10, 1.21.11, 26.1 and 26.2. [Original baselines](baseline.json) remain the comparison for the final reviews. This task does not include merging, publishing or deployment.

| Package | Implementation | Outstanding work |
| --- | --- | --- |
| P0: baseline | Isolated branches and original source/test inventories | Final source and evidence binding |
| P2–P3: compatibility and support lines | Catalog, resolver, generated views, file classification and isolated port tooling | Current inventory has zero unclassified paths; two future numeric command paths per line still require final reviewed blobs |
| P7a: settings | Descriptors, consistent runtime publication, preview/apply/undo and persistence feedback | Final preset integration and review |
| P4: disposable rigs | Private WSL displays and input, owned process cleanup, physical host storage guard and independent evidence checks | Final applicable native runs |
| P6a: test organization | 67 pure suites moved with Java 21 and test identity accounting | Final accounting and aggregate gates; no speedup claimed |
| P1: status and exports | Immutable status UI/commands and bounded lifecycle-aware exports | Measured impact and final acceptance |
| P5: Xaero ownership | Facade and five ownership components | Final map/lifecycle evidence, human visual assessment and measured impact |
| P6b: concurrent correctness/performance | Four-client workloads, native region ownership, target oracle and fixed performance protocol | Resolve the reconnect failure, pass functional prerequisites and execute the preregistered comparison |
| P7b: presets and documentation | Qualitative presets and operating guides; conservative implementation prepared | Select numeric values from accepted measurements, apply and validate them |

## Current build and regression evidence

The `pre-performance-late-correction-schema-20260912` gates passed on all five lines, including platform builds, applicable server/client gametests, packaging, release contracts and fixture exclusion. Minecraft 1.21.1 has no client gametest task or Folia target. Source, compiler and JDK inputs were checked for the builds.

The previously qualified corrections address a late Folia loaded-probe result and an old client worker taking a replacement session's queued receipt. Their 18 added cases per line executed exactly once and passed: 90 executions in total, separate from the earlier pairing regressions and the original test migration. Passing these controls does not establish that the native reconnect failure is fixed. Exact heads and gate receipts are recorded in [the current checkpoint](current-checkpoint-2026-09-12.json).

## Current native blocker

Full corrected-reference Folia run `20260912T043435Z-113ea71137d9` **failed**: 23,045 of 23,056 targets committed. Client B missed targets 2854–2864, at chunk x=270, z=-2 through 8, after reconnecting. Clients A, C and D each committed all 5,764 targets. The strict raw result equals the retained proof. Region ownership and debt-drain subchecks passed, and owned cleanup completed; these do not pass the failed source check.

The previous full run and separate diagnostic failures remain retained. A previous diagnostic directly observed an allowed disk-source body with the wrong expected block in the missed interval. Diagnostic `20260912T050933Z-f8ff9f29ef7a` subsequently recorded 800-position reconnect declarations whose first 512 probe positions excluded the affected cells. Their disk requests had no retained late-probe opportunity. All completed intervals passed in that diagnostic, so it establishes the coverage gap without claiming to reconstruct the earlier failed execution.

The coverage correction passed 56 targeted tests on 26.2, but diagnostic `20260912T054915Z-205396905ccc` **failed eight successor-closed B targets** (2853–2856, 2858, 2861, 2863–2864). Its 208 open uncommitted targets reflect the diagnostic cutoff, not additional closed misses. Strict raw results equal retained proof and cleanup completed with zero children. The exact summary is linked from [the current checkpoint](current-checkpoint-2026-09-12.json); the full `043435` failure above remains unchanged.

The subsequent overlap correction passes 73 targeted controls on 26.2. Diagnostic `20260912T061930Z-19127a71249f` **failed five successor-closed B targets, 2849–2853**. The generation-3 declaration omitted those cells, cancelling their probe captures even though admitted disk work survived. An admission-lifecycle correction is pending; no new native success is claimed. The correction is not yet ported or qualified by new full gates. No ownership architecture change is claimed for the other lines.

The remaining dependent functional and performance runs are held after this failure. No numeric conservative operating point has been selected. The tested 32/4/1 configuration is an experimental input, not an accepted preset derivation.

The supporting integration committed 162 nonnumeric code/tool placements across the five lines. Current classification metadata is also committed: no current paths are unclassified, while two numeric command paths per line retain ten explicit review-required flags until final numeric integration. All 3,065 distinct Python cases passed, including eight controls rerun after harness contention cleared. Separately, current `tools/compat` controls passed 91 cases on each line (455 total, no skips) with unchanged Python sources; these are additional to the 3,065 cases above. Forty-one measured product/test placements remain pending; these counts describe placements across lines, not separate features.

## Completion gates

1. Resolve and reproduce the native reconnect failure with a meaningful regression; validate applicable fixes across support lines.
2. Pass the six current Fabric/Paper/Folia functional prerequisites and the original fixed nine-slot calibration/measurement schedule. Preserve failed attempts and the original budgets.
3. Integrate the measured conservative preset and complete remaining classification, test accounting, catalog references and documentation.
4. Complete final build/tool checks and the applicable 42 required native rows, four supplemental rows and six preset runs. Earlier evidence closes only unchanged, independently matched inputs. Eight visual assessments require actual user review of the relevant captures.
5. Run six new Astra reviews over the complete original-baseline-to-final changes, validate findings and implement justified corrections. Recovery and implementation agents do not count as those final reviewers.

## Retained history and operation

[The original chronological ledger](project-improvements-ledger-history-2026-09-10.md) preserves the previous in-tree ledger byte for byte. [The subsequent recovery checkpoint](project-improvements-ledger-history-2026-09-12.md) preserves its dated claims and detailed evidence links; its active/pending labels describe that checkpoint, not current status. Historical native successes do not certify changed artifacts.

Automated clients run on private WSL displays. Normal Windows clients, personal worlds and the user's desktop are outside the rigs. See [disposable rigs](../operations/disposable-rigs.md), [the physical host storage guard](physical-host-storage-guard.md), [the failure catalog](../operations/test-flakes.md) and [current ownership](../architecture/ownership.md).
