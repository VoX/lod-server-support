# WI3 / WI4 / WI5 implementation record

Implemented and ported across all five supported lines; runtime/live checks remain the coordinator's responsibility. No live visual result is claimed here.

## Changes

- WI4 `d01c3be0`: a newer NO_REGION or NEVER_CLEAN summary retracts only earlier summary-derived proof. Per-column validated proofs, real timestamps, dirty state and retry ownership retain their meanings. Both scan modes redeclare the affected positions.
- WI3 `cd0790e9`: Apply/tick reconcile receiveServerLods, withdraw wants on OFF, retire the acquisition owner before saving, gate delayed frames by captured owner, and renegotiate ON without reconnect. Withdrawal retries remain OFF-only. Receipt cancellation costs no ingest failure strike. A lost CLEAR retains the last accepted positive content stamp, including repeated queued CLEARs. Real consumer failures still reach the existing per-delivery cap.
- WI3 followup `9b3fe935`: dimension cancellation retires each pending receipt, including superseded receipts, even while its manager remains active. Both the callback and posted report event check receipt activity. Open-to-LAN service readiness allows later OFF/ON negotiation while ordinary singleplayer remains silent; the corresponding client hook ships in WI5's transition wiring.
- WI5 `0c613fca`: a bounded queue/debt origin captures generation and receipt at admission; its lease keeps deferred map work cancellable until commit, intentional disposal, or rejection. Replacement, eviction, debt release and teardown resolve that lease once. Deferred reports target the captured receipt. Old generation admissions and old detached debt releases cannot recreate debt or decrement a new session's gauges. Accounting updates occur with the exact record's actual removals. Xaero probes remain outside owedLock. OFF retires acquisition work but preserves native texture rebuilds for already committed pixels. A server session configuration replacing the manager also retires old queued/debt work.

## Compatibility and constraints

The existing tokenless public report API remains compatible. A detached third-party tokenless callback cannot identify the historical delivery after a session switch; documentation says to capture a handle during the callback. Consumers retaining unfinished work must additionally acquire/release a bounded acceptance lease during that callback. Internal Xaero follows this path. No arbitrary detached tokenless callback safety is claimed.

Existing intentional silent Xaero drops (ungoverned/settings-off/death-latch/native-writer ownership), cap values, latest-wins queue identity, native monitor ordering, full-disconnect committed-rebuild tradeoff, legacy wire flavor and no-consumer discipline remain. Local OFF preserves far-player privacy/session identity and same-connection governor state. Native-world teardown is distinct from acquisition retirement.

## Tests and evidence

- Coordinator established WI4's four sentinel regressions RED in `store-summary-baseline.log`; selected summary/state/fuzz suites were GREEN in the coordinator's 669-test `focused-second` run (its remaining three failures belonged to separately corrected server fixtures).
- Coordinator `lifecycle-first.log`: WI3 manager/processor/gate/API/save-hook tests GREEN, including actual saved-cache reload after cancelled CLEAR, real throwing consumer cap, and retirement inside an already-polled owned dispatch.
- `xaero-first.log`: compile-only correction needed: package-private Xaero facade was reached from ClientNetGlue. Routed through the existing public ModCompat facade instead.
- `xaero-second.log`: all selected Xaero/previous WI3 tests plus root FarPlayerClientTrackerTest GREEN. This includes both formerly RED review interleavings, now permanent `XaeroAcquisitionLifecycleTest` cases.
- `dimension-clear-baseline.log`: the new cancelled-CLEAR / late old-dimension failure test RED at the saved positive stamp assertion before the per-receipt cancellation fix. It uses the real ColumnCacheStore IO queue and reload, not a synthetic cache sink.
- `client-xaero-final.log`: 213 tests GREEN, XML snapshotted in `client-xaero-final-xml/` before aggregate overwrite. Class counts below.
- The review's additionally identified already-posted failure-event variant was added with its final receipt-activity check after that 213-test run. Coordinator's full aggregate covers this final test; no separate GREEN result is claimed for it here until that aggregate completes.
- No Gradle run or live profile modification was performed during ports. Coordinator owns full per-line Fabric/Paper/NeoForge build, gametest and release artifact validation.

| Suite | Tests | Failures | Errors |
|---|---:|---:|---:|
| LanHookContractTest | 1 | 0 | 0 |
| XaeroAcquisitionLifecycleTest | 2 | 0 | 0 |
| XaeroMapCompatTest | 149 | 0 | 0 |
| ClientSessionGateTest | 52 | 0 | 0 |
| ColumnDeliveryLifecycleTest | 9 | 0 | 0 |

## Commit map

| MC line | WI3 | Cancellation/LAN gate followup | WI5 / wiring |
|---|---|---|---|
| 1.21.1 | cd0790e9 | 9b3fe935 | 0c613fca |
| 26.2 | e3d67aae | 71caf90b | adfd0426 |
| 26.1 | 7d87d6ac | c97c0a4b | 324623fc |
| 1.21.11 | 7b6b67e5 | 8a253e7a | 6587b4da |
| 1.21.10 | 801ed3f4 | eec2c392 | b1f3ab22 |

All picks applied without merge conflicts. The new Xaero lifecycle test adopts Identifier on 26.x / 1.21.11 and ResourceLocation on 1.21.10 / 1.21.1. Existing per-line section construction, native prefix/count handling, height expressions and data-version APIs were preserved. Read-only final diffs confirmed the shared owner/gate/API/facade code is identical across lines.

## Review contribution and limits

Server reviewer independently identified the dimension-cancelled CLEAR late-report and LAN readiness omissions; both were incorporated. I authored the original relevant review lenses and implementation, so this is not an independent final review. Graphics behavior, actual Sodium Apply interactions on both loaders, and abrupt live reconnect remain required runtime checks in the plan. The previous vertical map-line symptom has not been reproduced or attributed to these changes.

## Aggregate fixture followup

The first complete Fabric T1 aggregate ran 2378 tests and exposed five `VoxelColumnReceiverTest` failures: its RecordingProcessor still overrode the old two-argument offer, while production now uses the owner-aware three-argument overload. The fixture now records the real overload; existing stamp and resync assertions are preserved, with receipt-owner/pre-clear assertions and a retired-owner refusal control added. The direct unowned dispatch seam is explicitly distinguished from production frame admission.

`receiver-fixture-final.log` passed both the receiver suite and all delivery lifecycle tests, including the already-posted failure-event variant. The two XML files are saved in `receiver-fixture-final-xml/`. Primary test-only commit `3a0c61c2`; corresponding four-line commits are reported to the coordinator. Root reruns the full aggregate after this correction.
