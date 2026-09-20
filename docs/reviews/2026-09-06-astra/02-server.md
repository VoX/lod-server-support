# Server request pipeline and concurrency review — MC 1.21.1

Reviewed 2026-09-06, Astra. Review and plan only; no repository edits and no Gradle or Minecraft server execution by this reviewer. Working tree read: `/home/vox/projects/lss-lines/1.21.1`, HEAD observed `49e588dba275`; parent verified its tree is identical to merged `1b544494`. External JUnit regression source was written under the review artifact directory for the parent's coordinated test run.

## Finding S1 — P2, high confidence: a late disk read can enter a replacement session and lose its dirty invalidation

**Primary location:** `common/src/main/java/dev/vox/lss/common/processing/AbstractChunkDiskReader.java:807-810` (`addResult`). Related locations: `OffThreadProcessor.java:797-806`, `934-951`, `1051-1087`, `1110-1146`, `1195-1202`, `1269-1272`; `xplat/src/main/java/dev/vox/lss/networking/server/RequestProcessingService.java:509-513,541-543,476-487`.

**Problem:** reads capture only a player UUID. `addResult` looks up that UUID's *current* queue when the asynchronous read finishes. Removing/re-registering a player replaces the queue but does not retire already-running reads. If that player reconnects in the same dimension before an old read finishes, the old read is appended to the new session's queue. The processor checks dimension strings, which still match, and processes that old read against the fresh state. This is a normal reconnect sequence and does not require an instantaneous dimension A→B→A swap or another plugin invoking internals.

**Concrete harmful sequence:**

1. Old session admits a disk request. Its worker reads/captures pre-edit chunk bytes and remains in flight.
2. A save invalidates that position. The processor taints the old dedup group in `invalidatedInFlight` so this read cannot later stamp stale content as current.
3. The player disconnects and reconnects to the same dimension before the read finishes. The normal service teardown removes the result queue and posts a removal; registration creates a new state/queue.
4. Applying the removal deletes the old dedup group **and consumes the old read's taint**, based on the stated assumption that its result will never deliver.
5. The old worker finishes. `addResult` finds the new queue and publishes its old bytes there. Both dimension checks pass. The old result now has no taint; the processor marks the **new** state's done bit and stamps/deposits the bytes.
6. The fresh client's warm `ts>0` declaration draws `UP_TO_DATE` from the done-bit ladder rather than re-reading the saved edit. There need not be any second request in flight to heal it. A later edit/dirty broadcast can heal it; ordinary declarations cannot. The same untainted delivery can pollute shared timestamps/store, extending beyond merely one harmless extra payload.

The read does not need to outlast an unbounded timeout: delayed region/NBT work overlapping a quick reconnect is enough. A processing read is not canceled by `removePlayerResults`.

**Evidence/test gap:** Existing `AbstractChunkDiskReaderTest.resultsDeliveredAfterPlayerRemovalAreSilentlyDropped` (SP-074, lines 869-900) specifically waits for the old completion *before* registering the player again. It correctly proves absent-queue drops but misses register-before-completion. `OffThreadProcessorMailboxTest.aBufferedRemovalDoesNotKillAReRegisteredSameUuidSession` (SP-042) covers fresh admissions after removal, without a real old worker completing into the fresh queue. Stale-dimension tests cover different dimension strings, not same-dimension replacement. Normal data delivery intentionally tolerates `pending == null` in existing rigs; this finding does not propose deleting that behavior indiscriminately.

External regressions ready for parent execution:

`/home/vox/.local/state/lss-review/20260906-astra/probes/server/dev/vox/lss/common/processing/SessionOwnershipReviewTest.java`

- `oldReadMustNotEnterAReRegisteredPlayersQueue`: actual single-thread reader and latches; replace its player queue before releasing the first read, and use a second player's FIFO read as the delivery fence. Expected replacement queue empty; current source appends the old result.
- `reconnectMustNotEraseAnOldReadsDirtyTaintAndSealItsPreEditBytes`: actual reader + processor; invalidation is applied before teardown, teardown completes before release, then a warm declaration is processed. Expected no new-state done bit and no false `UP_TO_DATE`; current source sets the bit and emits that answer. Cycle barriers use `routeCyclesForTest`, not timed sleeps against tick speed.

**Concrete fix plan:** bind every submitted read and every parked continuation to a session-specific result sink captured at submission. A new registration must have a different identity. Deliver only when that identity is still current (or append to the captured old queue, which the new session can never drain). Carry the captured identity through header/store hits, expensive-read successes/errors, gate overflow, park draining, and submission-rejection/error fallback; changing only the normal success call misses other terminal results. Keep retire/cleanup semantics: old groups and dirty markers can be removed precisely because their results can no longer cross the session boundary. Ideally extend session ownership to dedup attachments and generation tickets/outcomes in a separate, scoped follow-up audit, because those also currently carry UUID/dimension rather than state identity; this review has not established a second independent production reproduction for those paths.

**Regression plan:** add the two external probes into their corresponding existing test suites; preserve SP-074, SP-042, dimension-change isolation, dirty-overtaken read, dedup fan-out, gate park/release, header/store rung, and error-containment tests. Include a parked old request and a late old error/not-found, not only an already-running successful read. Run the common/fabric Tier 1 suites and Paper's corresponding reader/processor suites; no new live-server soak is needed to establish the race, though the next normal reconnect smoke should include it.

**Status:** parent ran both external regressions and both failed at the intended ownership assertions (replacement queue nonempty; fresh state incorrectly marked done), with no setup/timeout failures. The strengthened `assertAll` rerun also failed at ALL THREE assertions: fresh done bit, false `UP_TO_DATE`, and stale byte1 delivery. Parent evidence: `session-ownership-combined-probe.log` / `.xml`. Priority P2 because the trigger is an overlapping reconnect/read/save sequence, but the resulting stale answer can persist, so this is correctness work rather than cosmetic cleanup.

## Reviewed and accepted behavior — not findings

- Want-set replacement, retention under pool/send/gate saturation, silent transient drops, and re-declaration recovery are deliberate protocol semantics. Pending slots are derived from the pending map. Dedup-primary departure intentionally frees attached pending entries and lets them re-declare.
- Router per-cycle player rotation correctly applies before the global disk headroom gate; dedup attachments bypass headroom and park saturation because they cost no new read. A scheduling rotation need not guarantee equal useful bytes every tick.
- Generation admission runs the nearer pending-read hold, cohort span, and frontier-spread gate on both real misses and memo hits. Acquisition-first frontier stamping, revalidation fallback, outward damping, and a ts>0 pending request's stronger anti-starvation carrier are documented and test-pinned. I did not treat their intentional conservative gating as a bug.
- Miss memo is populated only by authoritative, non-tainted misses with generation enabled; it follows timestamp/loaded probes and is cleared on generation outcomes. Error/timeout triage and permanent extraction-failure policy are existing choices; no speculative reclassification proposed.
- Global bandwidth debt, one-payload presence admission, per-player token debt, idle connected player dilution, raw-byte accounting, refill-floor pacing, and the transport-yield starvation floor are intentional. Configured caps are not strict instantaneous packet-sized bounds.
- Loaded-probe suppression's narrow stale-disk/never-saved fallthrough and global probe budget corner are explicitly accepted in the code and planning record. They were not relabeled as new defects.
- Processing mailbox latest-wins snapshots versus lossless events, phase-completion requeue flags, late dirty-event drain, per-delivery/per-generation containment, and shutdown invalidation flush were inspected. No independent actionable defect found in those paths within this review's bounded scope.
- Generation service ticket piggybacking, deferred departure/timeout ticket release, reuse of still-held deferred tickets, and serialization cleanup in `finally` were inspected. Existing permanent serialization-error behavior is intentional; no speculative change proposed.

## Coverage and limits

Read the server support-line instructions and the explicit review-findings-vs-pinned-decisions memory. Reviewed common request router/state/processor/dedup/read gate/reader/send/bandwidth paths plus xplat service, generation service, and loader payload-build glue. Read relevant lifecycle, reader, processor, fan-out/fairness and stale-result test contracts; consulted miss-memo, acquisition-frontier, send-pacing, and disk-gate planning records. Paper threading, region-summary correctness, client acquisition/cache, and privacy policy are owned by separate review lenses. This report does not assert those systems are clean. No broad test or live gate claim is made by this reviewer.

## Final validation addendum

Report09-paper subsequently reproduced buffered generation crossing the same registration boundary. Its real admission/dirty-mark/held-processor probe failed on stale delivery and a fresh done bit. Treat generation as confirmed within WI-2, superseding any provisional generation assessment above. See final-probe-results.json for the deduplicated final run.
