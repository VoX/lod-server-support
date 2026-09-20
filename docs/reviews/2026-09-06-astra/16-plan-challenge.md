# 16 — Final plan challenge

Astra, 2026-09-06. Reviewed `draft-plan.md` and reports 01-wire-parent, 02-server, 03-storage-parent, 04-client, 05-freshness, 06-xaero, 07-farplayers, 08-permissions, 09-paper, 10-compat, 11-config, 12-validation, 13-cross-wire and 15-cross-release, plus saved probe XML, selected external fixtures, relevant production/test anchors and the pinned-decision memory. No production edits, test runs, server operations or children. Report 14 was still pending during this challenge and is not certified here.

**Independence limitation:** this is not a fully independent final audit. This reused Astra thread authored lenses 05, 11, 13 and 15 and supplied part of the initial interrupted storage investigation. I therefore concentrated adversarial scrutiny on the server/client/Xaero/far-player/privacy/Paper/compatibility/harness findings, their fixtures and the parent's synthesis. Reading one's own earlier reports is consistency review, not independent corroboration.

## Decision

The proposed plan is supportable. **No established finding needs rejection, no priority upgrade is warranted, and no production fix has been validated as implemented.** The draft appropriately separates ordinary-session defects, conditional integrations, inconsistent-peer robustness and operational improvements. The amendments below are limited corrections/strengthenings for the final deliverable, not grounds to restart the review.

## Required amendments

### R1 — make the master-toggle acceptance pin delivery honesty explicitly

WI-3 says both “retire queued decode/retry work without charging intentional disable as failed ingestion” and “preserve retained cache proofs.” Specify the boundary: a column merely stamped by `onReceived` and queued, but never accepted by a consumer, must lose that provisional proof when OFF discards it. Only genuinely retained accepted proofs should survive. Disabling must not charge that retirement against the resumed session's consumer-failure budget.

Why: `fabric/src/test/java/dev/vox/lss/networking/client/ClientColumnProcessorTest.java:639` (`receiveServerLodsDisableFlipReportsClearedBacklog`, CL-044) explicitly pins reporting every discarded column to undo the receipt stamp. Merely suppressing reports during disable would make OFF quiet but could leave a persistent false warm-cache proof. The current draft's general language could admit that implementation even though report04 correctly rejects it.

Add two acceptance controls: (1) queued/onReceived-stamped/unconsumed bytes are unstamped and cannot be persisted as an accepted warm proof after OFF; (2) an actually accepted cached column remains reusable after ON. Preserve the existing real-consumer-failure retry cap. The implementation can change how retirement is represented; it must preserve the honesty invariant rather than preserve a particular reporting API mechanically.

### R2 — align the final evidence index and deduplicate execution totals

At inspection time `validated-index.json` still had only ten entries. It omitted PRIV1, COMPAT1, VALID-1, VALID-2 and REL-1, and did not record the generation extension of WI-2. Update it to the final plan or mark it superseded so two conflicting inventories do not ship together.

The saved `*probe.xml` files contain **17 unique test methods: 16 expected RED, one GREEN normal-read checksum control, zero errors**. Counts deduplicated by class and method name. Three methods appear again in strengthened/combined rerun XML: both session-ownership methods and the Xaero detached-release method. Do not count those reruns as additional regressions or independent findings. These red tests establish missing invariants on unchanged code; they are not the passing verification of a fix. The separately executed 169-test wire baseline is GREEN and must stay a separate census.

Keep generation as a second mechanism within WI-2, not a separate extra finding. Keep the Xaero negative gauge as the companion consequence of WI-5, not proof by itself of cross-server contamination. Keep source-confirmed harness/control-flow work distinct from executed test cases.

### R3 — make source scope and execution scope explicit in the final matrix

The work table's “All five” is an affected-source/port scope, not five-line runtime or probe validation. Add a short matrix note or separate evidence column: probes executed on MC1.21.1; common/shared source parity supports five-line fixes; per-line acceptance remains future work. For Voxy, the verified missing holder pairing is the particular MC1.21.1 0.2.15-beta artifact in report10. Porting the shared bounded resolver enhancement to other lines does not establish an equivalent deployed defect there.

Existing jar checks are artifact snapshots, not fresh builds from the reviewed commits. The draft already says this in its body; preserve the distinction in any short final summary too. Do not let the final completion edit remove the explicit partial wire/storage coverage or turn the multiple reused lens reports into independent-reviewer counts. Fold report14 with its own evidence boundary, then replace the draft/pending language.

## Findings challenged and retained

| Work | Challenge | Disposition |
|---|---|---|
| WI-1 / ST-01 | Does the shutdown probe prove a lasting failure rather than an ordinary interrupted cleanup? | Yes: it uses actual shutdown, then a normal reopen under the new policy, and the old row still serves. P1 is reasonable because a stricter mask can remain unapplied durably. The test proves old-policy row retention, not a live ore-disclosure demonstration. Keep the priority and bounded-batch fix. |
| WI-2 / S1 + generation | Are these merely same-session ghost results, or invalid test-only submissions? | Disk probes use the actual asynchronous reader. The generation fixture substitutes platform IO/extraction but obtains an actual generation ticket through the real disk-miss admission path and retains the result in the real mailbox across service-order removal/re-registration. The replacement-state delivery/done assertions fail. That proves the ownership boundary; it does not claim a fresh live Paper generation run. Treat both as one P2 family and keep the adapter-level regression requirement. |
| WI-3 / C1 | Is the retry cap or CL-044's discard reporting itself the bug? | No. The missing acquisition/session transition causes the loop. Keep P2 and amend acceptance as R1. OFF-at-join → ON is source-supported, not one of the two executed assertions. |
| WI-4 / FRESH-1 | Does the RED establish the portal schedule? | No, it establishes frame-state proof retention. The draft correctly labels the composed gameplay chronology inferred and requires an integration pin. P2 remains defensible for the demonstrated state-machine invariant; do not claim a live portal reproduction. |
| WI-5 / X1 | Can owed=-1 alone establish corruption of the next session? | No. The release report can be dropped by the null manager during ordinary teardown. The separate shed-after-clear probe establishes debt surviving title settlement and publication in a new session. The draft distinguishes these correctly. Epoch ownership must be captured at origin, not at a delayed callback's start. |
| WI-6 / FP0 | Is silence legitimate stationary-player behavior? | Yes in ON mode, and the control demonstrates that. OFF needs explicit withdrawal because no expiry is deliberate. The real broadcaster → encoded wire → real tracker fixture is sufficient to establish the defect without graphics. Both disabled platform wrappers must drain the fix. |
| WI-7 / ST-02 | Does the opaque translator make the corrupt-row finding artificial? | It narrows the proof but does not invalidate it: normal read rejects the same stored checksum mismatch and migration certifies it. The probe does not test palette translation. Keep the checksum control and real-translator tests separate. P2 is appropriate; do not describe the hash as cryptographic authentication. |
| WI-8 / FP1 | Does a stock LSS server actually send the index-rebinding sequence? | No. The report explicitly rules it out. Keep as conditional peer-robustness P2, below ordinary lifecycle work. It remains a demonstrated bypass of the actual retained-identity bound, not an ordinary gameplay ghost claim. |
| WI-9 / FP2 | Does the seated catch cause a persistent vanilla pose leak or hard crash? | The outer finally still restores the pass. The source/dispatcher evidence proves only contamination of subsequent draws in that pass after an exception. P3 is appropriate; preserve the exact branch-specific recovery and avoid a global substring-count “test.” |
| WI-10 / CFG-1 | Should save failures start throwing or roll back runtime state? | No; nonfatal persistence is pinned. Keep P3 feedback only, preserving applied runtime state and startup/UI containment. |
| WI-11 / PRIV1 | Is the external loader fixture faithful to a real plugin replacement? | It exercises the actual production resolver and JVM initiated-class behavior with opposite registries. Loader objects are only identity tokens in the current Object seam; they are not faithful plugin instances for the proposed new defining-loader implementation. The draft already requires genuine loader-defined plugin-instance fixtures for the final regression. Retain conditional P2, below normal-session failures, and no live PlugMan claim. |
| WI-12 / COMPAT1 | Is the missing Voxy shape conjectured from a different fork? | No: the original and mapped MC1.21.1 artifact descriptors and own reload bytecode are identified, and the resolver-shape test fails. The regression presently proves resolution only. The planned invocation/carrier and teardown-order controls remain necessary. Keep the actual carrier on `Minecraft.levelRenderer`; `getNullable()` is not the holder. |
| WI-13 / VALID-1,2 | Are all performance gates broken or was the user's running regular server damaged? | Neither claim follows. Source ordering proves the same-scratch soak refusal occurs after mutation; the regular server uses different paths/port. Direct benchmark is affected; wrappers that already clear result destinations are excluded. P2 tooling is justified, with isolated shell fixtures before further soak use. |
| WI-14 / REL-1 and prose | Does a different C2ME pin prove incompatibility, or does questionable rig inventory prove an LSS failure? | No. Keep explicit validation-matrix/rig-identity improvements only. Do not upgrade either pin automatically or alter installed mods based solely on filenames. |

## Implementation-order and regression considerations

The proposed broad order is sound: durable policy invalidation first; ordinary lifecycle/freshness fixes next; conditional compatibility and robustness later; reporting/prose improvements last. **WI-13 must precede any new soak/benchmark execution, but does not need to block isolated unit fixes.** Keep this dependency explicit rather than treating numerical work-item order as an execution script.

WI-2 can be implemented in small internal steps (reader sinks, generation ownership, dedup/callback attachment audit), but should not be declared complete after only fixing disk `addResult`. Origin identity must be checked before mutating replacement pending/tracking state, not just before final send. Preserve deliberately accepted same-session ghost results and two-player shared-load fan-out.

WI-3 and WI-5 touch related client lifecycle but are separate invariants. Keep their commits and regressions scoped; ensure they compose when OFF retires a session while Xaero has outstanding debt. Do not accidentally retract target privacy preferences merely because LOD acquisition turns off.

The draft's bounded-batch storage requirement, outside-lock Xaero probe requirement, summary-only proof revocation, stationary-player no-expiry policy, Voxy domain isolation and nonfatal config saves all correctly preserve pinned decisions. None needs reversing to repair the demonstrated failures.

## Optional improvements

- Add rapid far-player ON → OFF → ON while the clear is withheld. Verify a delayed clear cannot erase the newly re-established roster and that stationary targets get initial updates. This sharpens the already requested off/on and withheld-clear tests.
- Make isolated harness fixtures execute the actual script with injected/stubbed dependencies where practical. A copied script can miss future control-flow changes; if a copied fixture is necessary, pin its source relationship. This is validation quality, not another reported bug.
- Record per-work-item “done” gates in the final implementation checklist: focused regressions green, relevant existing pins green, source port review, and the explicitly needed live check. A gate being deferred under a support tier should stay visible rather than be translated into a pass.
- For WI-1, describe the motivating urgent case as a stricter mask policy followed by interrupted cleanup and normal reopen. Policy changes in the opposite direction also fail the invariant but do not carry the same exposure consequence.

## Rejected findings / extrapolations

No enumerated draft finding was rejected. Reject these stronger interpretations: live gameplay proof from the summary state fixture; cross-server pixels or a permanent Xaero hole from the negative gauge; stock-server exploitability from roster rebinding; live rendering/reset/plugin-reload certification from pure fixtures; five-line executed correctness from common-source parity; any fresh release build from existing jar equality; an incompatibility conclusion from a C2ME pin split or unverified Prism inventory.

This challenge does not complete the interrupted original full wire/storage reviews or replace the pending cross-loader lens. It validates the plan's bounded claims and priorities subject to the amendments above.
