# Progress — declarative want-set requests (protocol v17)

Branch: `feat/want-set-requests`, STACKED on `feat/read-io-priority` (PR #36, open+green).
User chose: branch off #36 rather than merging it first; rebase onto main after #36 merges.
User chose: implement INLINE (not subagent-driven), task by task.

Plan: docs/superpowers/plans/2026-07-15-declarative-want-set-requests.md (1469 lines, 10 tasks)
  - Authored by a Fable design agent, then hardened by 4 review passes (adversarial claim
    refutation, completeness sweep, vestige audit, + a competing design discarded in its favour).
  - Base commit: 049fdbd (plan doc).

## Task status

- Task 1: COMPLETE — 73d2fd0. Answer-time mark consumption in ColumnStateMap
  (onUpToDate/onNotGenerated now clear dirty+retry). Tier 1 green (601 tests).
  **PLAN DEVIATION (plan was wrong):** Task 1 Step 4 says "Expected: PASS (all ~592)". It is not —
  the existing `ColumnStateMapTest.notGeneratedAnswerAcrossDirtyRetryAndParkedPriorStates` pinned
  the OLD map-level survival of dirty/retry across a not-generated answer. Its dirty/retry legs
  were rewritten to assert answer-time consumption. Verified safe: crossings are preserved at the
  MANAGER layer — `onDirtyColumns` calls `noteStaleIfInFlight` for EVERY position (explicitly
  covering stored>0, not just stored==-1) and `consumeStaleCrossing` re-marks at every terminal
  outcome (LodRequestManager:340,419,439). The real client never produced the test's sequence:
  markSent already consumed the mark before the answer landed, so the outcome is identical.
- Task 2: COMPLETE — see commit below. Tier 1 green (584 tests, 0 failures); `:paper:test` green
  (UP-TO-DATE — Task 2 touches no Paper/common source).
  MAIN SOURCE (was already done before this session; one addition, see deviation D2-6):
    - InFlightTracker rewritten as the awaiting-answer set (replaceWith/removeByPosition/
      isInFlight/size/pruneOutOfRange/clear); markPending/generationCount/timeoutSweep gone.
    - SpiralScanner: array sink (posOut/tsOut), no in-flight predicate, no skipNextScan/
      noteRateLimited/clearSkipNextScan, budget clamped to min(MAX_BATCH_CHUNK_REQUESTS, posOut.length),
      returns -1 for "no walk" (incl. budget==0). Class javadoc + BUDGET_MULTIPLIER doc rewritten.
    - LodRequestManager: scan-emits-batch tick shape; empty batch = edge-triggered backpressure
      clear (backpressureClearSent); drip-feed (maxSendPerTick/updateSendPerTick/tickDrainPhase/
      drainQueue), sweepTimeouts, TIMEOUT_NANOS, MIN_SEND_PER_TICK all deleted; sendRequests no
      longer restores marks; onRateLimited is the transitional metrics-only stub;
      getQueueRemaining() is a 0-stub (deleted in Task 5/6).
    - DELETED: RequestQueue.java, RequestQueueTest.java, ColumnStateMap.markSent.
  TESTS: DONE. SpiralScannerTest, LodRequestManagerTest, LodRequestManagerTickTest,
  ColumnStateMapTest, ClientSessionGateTest all rewritten/updated. New pins added:
  convergedScanSendsNothingAtAll, enteringBackpressureHaltSendsExactlyOneEmptyClearBatch,
  awaitedPositionsAreReDeclaredAndBlockRingConfirmation, plus noWalkTickNeitherSendsNorReplaces-
  TheAwaitingSet (see D2-2).

  ### Task 2 PLAN DEVIATIONS (all recorded, none silent)

  - **D2-1 (plan gap — the two CL-014 tests).** Plan Step 5 does not mention
    `lateNotGeneratedBelowConfirmedRingIsRereachedByResetConfirmedRing` /
    `lateNotGeneratedStaysParkedWhenGenerationDisabled`, but their shared helper
    (`stageRing1WithOneInFlightTarget`) is built on a premise the want-set DELETES: it needed the
    ring to confirm PAST an in-flight position, which only happened because the scanner suppressed
    in-flight positions without breaking confirmation. Under re-declaration an awaited position is
    an ordinary unsatisfied want-set member and BLOCKS its ring — so the premise is now
    structurally impossible and the helper's `assertTrue(confirmedRing > 1)` cannot pass.
    Resolution: kept both tests, restaged the premise DIRECTLY (seed ring 1 fully satisfied, scan
    so the ring confirms, then re-open the target via onNotGenerated). resetConfirmedRing's
    contract is still production-called (onColumnNotGenerated, consumeStaleCrossing) and must hold.
    Renamed to ts0PositionBelowConfirmedRing… / ts0PositionStaysParkedWhenGenerationDisabled.
    **Note for a later task:** under want-set, `LodRequestManager.onColumnNotGenerated`'s
    `resetConfirmedRing()` is now defence-in-depth rather than a live fix — to get the answer at
    all the position must have been in the last declared want-set, which means it blocked its
    ring's confirmation. Left in place (cadence-neutral, gen-disabled-safe).
    **FIX PASS (2026-07-15, amended into b52f694):** this bullet previously claimed the stale
    "the scanner skips in-flight positions" comment "was corrected" — it was NOT; the correction
    was never written to the file. A review caught it. Three stale comments in
    `LodRequestManager` all still described mechanisms this commit DELETED, and the worst of them
    was the sole justification for a live call:
      - L368-373 (`onColumnNotGenerated`'s `resetConfirmedRing()` rationale) still asserted "the
        scanner skips in-flight positions without breaking ring confirmation, so the ring can
        confirm PAST this position" — the exact premise D2-1 deletes. It now states the real
        mechanism (an awaited position blocks its own ring, so the call is defence-in-depth),
        keeps the history of WHY it exists (CL-014), and records that resetConfirmedRing stays
        load-bearing on consumeStaleCrossing's path. D2-1's note now lives in the code where
        the next task will actually read it.
      - L354/L357 gated on a tracker "cleared on dimension change / timeout / prune" and
        explained an untracked answer as one whose entry "died to a timeout/prune". There is no
        timeout sweep. Now: cleared on dimension change / pruned on movement / replaced per scan;
        an untracked answer is one whose position left the last want-set.
      - **Fourth instance, not in the review's findings:** `tickMovementPhase`'s javadoc (L155)
        credited the cadence with "starving both scans AND the timeout sweep that rides them".
        Now names want-set re-declaration as what rides the scan. The pinned invariant is intact
        and still named by its test (`chunkBoundaryCrossingFasterThanTheCadenceStarvesScansUntilRest`).
    A grep sweep for every mechanism this commit deletes then found two MORE in main source that
    D2-9 had only corrected on the TEST side, leaving the production comment lying:
      - `ClientSessionGate` L117 justified the hostile-config clamp by "drives
        RequestQueue.ensureCapacity" — a deleted class. The clamp's INVARIANT survives but its
        rationale moved: the unbounded-allocation vector is closed by construction (fixed send
        buffers + the scanner's min(MAX_BATCH_CHUNK_REQUESTS, posOut.length) clamp); the clamp
        stays because an unclamped LOD distance still bounds the ring walk (CPU-stall vector).
      - `ColumnStateMap.onReceived` L164 explained the retry-supersede by "the timeout sweep
        retries positions whose response may still arrive late". Now: the rate-limit bounce that
        wrote these marks is a metrics-only stub, and the sole live writer is `onIngestFailed`,
        whose position CAN still have a column in flight — which is what keeps the supersede live.
    Comment-only; no assertion or behaviour changed. Tier 1 re-run green (584, 0 failures);
    `:paper:test` UP-TO-DATE (no Paper/common source touched).
    **Finding for Task 5/6 (not acted on here):** `ColumnStateMap.markRetry` now has ZERO
    production callers — the rate-limit bounce was its only writer and byte 0 is a stub. It is
    dead code the moment RESPONSE_RATE_LIMITED goes. Delete it with the stub in Task 5, and check
    `hasRetries()`/`SpiralScanner:115` at the same time: retry marks still flow from
    `onIngestFailed`, so `hasRetries()` stays live — do NOT delete it along with markRetry.
    **Lesson for later tasks:** this commit deletes mechanisms (drip-feed, timeout sweep,
    in-flight suppression, rate-limit backoff) that comments elsewhere may still cite as live.
    Grep for them before trusting any comment's premise — and never record a correction as done
    without re-reading the file.
  - **D2-2 (CL-005 successor, beyond the plan).** Plan says delete the zero-budget remainder
    tests, full stop. Deleted — but HALF that invariant is still live and would otherwise have
    gone unpinned: a budget-0 tick must not be mistaken for convergence. Split it in two:
    the scanner's -1 contract (missingVanillaShrinksBudgetQuadraticallyToZero) and a new manager
    pin `noWalkTickNeitherSendsNorReplacesTheAwaitingSet` (a no-walk tick must not replace the
    awaiting set — that would un-gate the status responses for every outstanding declaration).
  - **D2-3 (plan miss).** Plan's delete list omits `trackedRateLimitedMarksRetryAndReleasesSlot`
    (LodRequestManagerTest:273) although it asserts exactly the two behaviours the stub removes
    (retry mark + awaiting-entry release). Deleted with a note.
  - **D2-4 (plan's duplicate-frame rewrite was under-powered).** With byte 0 reduced to a
    metrics-only stub, the plan's `[UP_TO_DATE, RATE_LIMITED, RATE_LIMITED, UP_TO_DATE]` frame no
    longer discriminates anything (its `hasRetries()` leg is dead — nothing marks retry). Rewrote
    the frame as `[UP_TO_DATE(pA), RATE_LIMITED(pA), NOT_GENERATED(pB), UP_TO_DATE(pB)]` so the
    trailing untracked up_to_date must NOT overwrite pB's ts=0 generation retry — restoring real
    "never half-applied" discrimination while keeping the byte-0 stub inertness pinned.
  - **D2-5 (CL-071 / `drainReDerivesTheTimestampFromClassifyAtSendTime`).** Both pinned a
    scan→send WINDOW that the want-set closes by construction (classify and send are the same
    tick). Kept both as construction pins rather than deleting: `teleportDropsStaleWantsFromThe-
    NextBatch` (stale wants never reappear) and `wantSetCarriesTheClassifyDerivedTimestampNotA-
    StaleClaim` (no path may reintroduce a stored, pre-classified ts).
  - **D2-6 (main-source change).** `LodRequestManager.haltThreshold()` private→package-private, as
    the plan's Step 5 anticipated ("expose … via a package-private static accessor if needed").
  - **D2-7 (test-authoring trap, cost 2 of the 7 first-run failures — worth knowing downstream).**
    `viewDistance == lodDistance` does NOT produce an empty want-set: vanilla's view is a rounded
    1-chunk-buffered Euclidean disc, so the lod square's CORNERS stay outside it and are correctly
    declared. Fully excluding a lod-64 disc needs vd >= 90 ((64-1)^2*2 = 7938 < vd^2). Any future
    "converged scan" helper must use vd 90, not 64.
  - **D2-8 (chaos-test bound).** `anyChaosInterleavingLeavesNoPositionPermanentlyOrphaned` caps its
    random missingVanilla at 24/25, not 25: at 25 the budget zeroes and maybeScan returns -1
    forever, which a "loop until >= 0" driver cannot observe. Alphabet otherwise per plan:
    noteRateLimited + in-flight suppression removed, "superseded server-side" (answer NEVER
    arrives) added and asserted to actually fire, and the end-of-chaos scheduled answers are now
    dropped as superseded too rather than retry-marked — strictly stronger than before, since only
    re-declaration can recover them. Terminal no-orphan assertion unchanged.
  - **D2-9 (stale comments corrected, no assertion changes).** ColumnStateMapTest's
    `onReceivedClearsRetry` rationale cited the deleted timeout sweep; ClientSessionGateTest's
    hostile-config test cited `RequestQueue.ensureCapacity` as the OOM vector (closed by the fixed
    send buffers + budget clamp — the clamp still matters for the ring walk).
- Task 3: COMPLETE — 9096326. Purely additive; production still routes through the old CLQ path
  (cut over in Task 4). Tier 1 green (593 tests, 0 failures — 584 + 9 new); `:paper:test` green
  (245, 0 failures). Plan code followed verbatim except as noted below.
  MAIN SOURCE:
    - NEW `common/…/processing/IncomingBatch.java` — immutable `record IncomingBatch(IncomingRequest[])`.
    - `AbstractPlayerRequestState`: `pendingBatch` AtomicReference mailbox (latest-wins) +
      `pendingSuperseded`/`pendingRangeFiltered` AtomicLongs + processing-thread `ArrayDeque` backlog
      + `backlogSizeSnapshot` + `publishedWantSet`. Methods: offerIncomingBatch, peekIncomingBatch,
      peekWantSet, publishWantSet, takeIncomingBatch, republishHeldBatch, recordRangeFiltered,
      drainPendingSuperseded, drainPendingRangeFiltered, replaceBacklogWith, pollBacklog,
      restoreBacklog, getBacklogSize. Nothing deleted (Task 4 deletes the CLQ machinery).
    - `ProcessingDiagnostics`: totalSuperseded/totalRangeFiltered + addSuperseded/addRangeFiltered/
      getters. Rate-limited counters left in place per plan (deleted in Task 4 with their callers).
    - `AbstractChunkDiskReader`: work queue hoisted into a field; `hasHeadroom()` added.
  TESTS: new `BacklogReplaceTest` (8), +1 in `AbstractChunkDiskReaderTest`.

  ### Task 3 PLAN DEVIATIONS / ADDITIONS

  - **D3-1 (test coverage beyond the plan — `hasHeadroom`).** The plan adds `hasHeadroom()` as
    production API in Step 4 and specifies no test for it, so it would have landed with zero
    coverage and become load-bearing in Task 4 (the NO_DISK_HEADROOM retain-and-stop gate — the
    thing that takes issue #32's saturation off the wire). Added
    `hasHeadroomIsFalseExactlyWhenTheNextSubmitWouldBeRejected` to `AbstractChunkDiskReaderTest`,
    reusing that file's existing pinned-worker/gated-read rig. It asserts the gate against a REAL
    rejection (headroom true at 31/32 queued, false at exactly full, and a submit made anyway
    genuinely bounces → saturationCount 1), not the gauge in isolation — the gauge agreeing with
    the pool's actual accept/reject decision is the only property Task 4 relies on.
  - **D3-2 (test coverage beyond the plan — `publishedWantSet`).** The plan's amended
    `publishedWantSet`/`peekWantSet()` design is the fix for a draft bug that would have halved
    in-memory probe coverage on Fabric AND Paper (rough edge #2), but the plan's `BacklogReplaceTest`
    does not test it at all — the amendment would have shipped unpinned, and a later "simplify
    peekWantSet() back into peekIncomingBatch()" would pass every test. Added two tests:
    `applyPublishesTheWantSetAndDrainingTheBacklogClearsIt` (the published set SURVIVES
    takeIncomingBatch() — the exact property the mailbox lacks — and clears only when the backlog
    drains) and `emptyClearBatchPublishesNothingToProbe`.
  - **D3-3 (style, not semantics).** Plan writes fully-qualified `java.util.concurrent.atomic.
    AtomicReference` / `java.util.ArrayDeque` / `java.util.Collections` / `java.util.List` inline;
    used imports instead, matching the file's existing style. No behaviour change.

  ### Task 3 OBSERVATION FOR TASK 4 (not a bug now — do not "fix" blindly)

  - **`restoreBacklog` does not re-publish the want-set.** Per the plan, `pollBacklog` publishes
    null once the deque empties; `restoreBacklog` then re-prepends retained entries WITHOUT
    republishing. So after a pass that polls the last entry and retains it (SLOT_FULL /
    NO_DISK_HEADROOM), the backlog is non-empty while `peekWantSet()` is null — those entries go
    unprobed until the next 1 Hz batch republishes. Not a correctness break: the probe is
    best-effort by design (rough edge #2), the entry simply serves from disk once and the dirty
    broadcast self-heals it — the same self-heal class the plan already accepts. Implemented per
    the plan verbatim rather than deviating, because Task 4's router is written against exactly
    these semantics. If Task 4 wants the tighter behaviour, the one-line fix is for
    `restoreBacklog` to republish the current want-set; decide it there, with the router in view.
    **→ RESOLVED in Task 4's fix pass — see D4-10.** This observation UNDERSTATED the impact on two
    counts: (a) "unprobed until the next 1 Hz batch republishes" is wrong — the next batch republished
    and the SAME pass immediately re-nulled it on drain-to-empty, so they went unprobed permanently;
    (b) it reads as an edge case, but SLOT_FULL retains-and-CONTINUES, making drain-to-empty-then-
    restore the STEADY state. Net effect was ~zero probe coverage, not a small residual.
    `restoreBacklog` now republishes.
- Task 4: COMPLETE — see commit below. Resumed from the interrupted implementer's tree (that agent
  finished BOTH platforms' main source + the Fabric Tier-1 rewrites, and never wrote a ledger entry;
  its work is sound and was kept — two of its plan deviations are load-bearing, see D4-1/D4-2).
  Tier 1 green (592 tests, 0 failures); `:paper:test` green (246, 0 failures — 245 + the new CAS pin).
  Tier 2/3 are EXPECTED RED (`TwoPlayerGameTests` still calls the deleted `addRequest`/
  `getIncomingRequestCount`) — that is Task 7's listed work, per plan rough edge #6.
  WHAT THIS SESSION ADDED: the 5 failing Tier-1 tests fixed (the ledger said 4; `emptyBatchClearsThe-
  Backlog` was a 5th), plus the ENTIRE Paper test surface, which had never been touched and did not
  compile (`:paper:compileTestJava` failed on 42+34+12+2 errors across RegionProbeSchedulingTest,
  PaperRequestProcessingServiceTest, PaperPlayerRequestStateTest, PaperExporterContractTest).

  ### Task 4 PLAN DEVIATIONS / FINDINGS

  - **D4-1 (the plan's `holdAndScheduleRegionProbe` code is WRONG — kept the dead agent's fix, then
    proved it).** The plan's Step 4 body takes `fresh` FIRST, then releases the held batch via
    `republishHeldBatch` (a `compareAndSet(null, held)`). Taking first EMPTIES the mailbox, so the CAS
    then succeeds unconditionally — republishing the STALE held batch while parking the newer one for
    probing. That is the exact opposite of the plan's own stated "Ordering caveat" ("the CAS fails and
    the HELD batch dies while the FRESH one is parked; the newer declaration always wins"). The tree's
    implementation releases BEFORE taking and returns on a successful release. Verified by mutation:
    reverting to the plan's ordering fails `heldBatchLosesToANewerArrivalAndIsCountedSuperseded` and
    NOTHING else — i.e. the plan's code ships a resurrect-superseded-wants bug that only the new CAS
    pin catches. The plan's prose is right; its code contradicts it. Do not "restore" the plan here.
  - **D4-2 (headroom gate needs `diskReadingAvailable`, beyond the plan's verbatim code).** The plan
    writes `if (!attached && !this.processor.hasDiskHeadroom())`. But `tryAdmitAndSubmit`'s first
    branch is entered for `type == SYNC || diskReadingAvailable`, so a SYNC request on a processor with
    NO reader enters it — and `hasDiskHeadroom()` is false-for-lack-of-a-pool there. The plan's code
    therefore returns NO_DISK_HEADROOM and RETAINS the entry forever with no answer (retain-and-stop,
    so it also freezes the rest of the backlog). The tree gates the check on `diskReadingAvailable`.
    Verified by mutation: the plan's verbatim form fails 10+ Tier-1 tests. Well covered; keep.
  - **D4-3 (two Tier-1 failures were TEST races against v17 semantics, not production bugs).**
    Both are traps for later tasks:
      - `generationTimestampRoutes…WithoutDiskReader` seeded with TWO back-to-back `enqueue()` calls.
        The mailbox is latest-wins, so the second declaration SUPERSEDED the first and the ts=0
        request never routed. Multi-position seeds must be ONE `offer(...)` batch. (The `enqueue`
        shim's own javadoc states the rule: call sites must be separated by a routing cycle.)
      - `emptyBatchClearsTheBacklog` waited on `getBacklogSize() == 2`, but that gauge is LIVE —
        `pollBacklog` updates it mid-pass, so it reads 2 transiently before the first entry is even
        admitted. Fixed with the file's established two-phase barrier (await p1's own cycle, THEN
        post a marker-ONLY snapshot and await ITS submit — a shared snapshot proves nothing, since
        `routeAll`'s player order is HashMap iteration). NOTE: `postSnapshot` is ALSO latest-wins —
        two back-to-back posts silently drop the first. That bug was independently present in the
        dead agent's `sendQueueFullRetainsTheBacklogInOrderUntilAReplaceSupersedesIt` (its "second
        queue-full break" never ran). Both fixed. **Any future test posting two snapshots must await
        the first.**
  - **D4-4 (the dead agent's new `sendQueueFullRetains…` test: premise CORRECT, kept).** The ledger
    flagged it as unreviewed. Judged on merits: it pins retain-and-break ordering, `queue_full` as a
    pure counter, and replace-supersedes-retained — all real Task 4 contracts, and it covers the plan's
    Step 6 requirement for `:489` ("assert the retained entries survive INTO the next cycle AND that a
    replace supersedes them") better than the plan's sketch. Only its snapshot barrier was broken (D4-3).
  - **D4-5 (Paper test surface — the plan's Step 6 list is accurate but was entirely undone).**
    `PaperPlayerRequestStateTest.floodThroughTheRealSubclass…` → `secondBatchThroughTheRealSubclass-
    SupersedesTheFirst` per plan. Its stated PURPOSE (catch constructor drift in the real subclass:
    UUID wiring, slot caps, join-dimension capture) is preserved verbatim; only the ingress-glue leg
    moved to offer/supersede/take. The 16384 flood bound it defended is now STRUCTURAL (one batch,
    wire-capped) — recorded in the class javadoc so nobody re-adds the bound.
  - **D4-6 (probe seeding splits by platform path — subtle, worth knowing).** Paper's SYNC probe reads
    `peekWantSet()` (the APPLIED want-set) while Folia's hold-release reads the MAILBOX
    (`takeIncomingBatch`). So `PaperRequestProcessingServiceTest`'s probe tests and
    `RegionProbeSchedulingTest.syncProbingIsUnchangedWhenRegionizedProbingIsOff` must seed via a
    `publish(...)` helper (standing in for the processing thread's `replaceBacklogWith`), while every
    regionized test seeds via `offer(...)`. Seeding the wrong one makes a test vacuous, not red.
  - **D4-7 (`unconsumedPublishesMergePerPlayer` premise restaged, not weakened).** It declared (1,1),
    ticked, then declared (2,2) and expected task 2 to snapshot BOTH. Under replace semantics a bare
    (2,2) declaration supersedes (1,1) entirely, so the second declaration is now `{(1,1),(2,2)}` —
    which is exactly what a real want-set client sends (it re-declares everything still unsatisfied).
    The test's actual invariant (region-probe publishes MERGE per player instead of clobbering) is
    unchanged and still fires.
  - **D4-8 (stale comments/dead code swept, per Task 2's lesson).** `SlotAdmissionTest`'s
    `MAX_INCOMING_QUEUE = 16384` mirror constant was dead after its two flood tests were deleted —
    removed. `DedupFanoutTest.primaryDisconnectFreesAttachmentsSilently…` cited "the client's own
    timeout re-request" as the recovery (Task 2 DELETED the timeout sweep) — now names re-declaration;
    it also gained the plan-required superseded assertion. `OffThreadProcessorDiskResultTest`'s class
    javadoc still described the rate-limited bounce — corrected.
  - **D4-10 (FIX PASS 2026-07-16 — the probe-coverage collapse D3-2 predicted; RESOLVED, not accepted).**
    A review found the Task 4 probe javadoc (verbatim from plan line ~1069) asserted "The published
    want-set still lists already-routed positions until the next batch replaces it." It did not, and
    the false comment hid a real ~total collapse of in-memory probe coverage. **Judged on merits and
    CONFIRMED — then found to be WORSE than the review stated.** The review characterised coverage as
    "exists only under saturation"; in fact it was ~zero in the STEADY state too, because `SLOT_FULL`
    retains-and-**continues**, so a normal pass (slot cap 4, want-set of hundreds) polls the ENTIRE
    backlog → `pollBacklog` publishes null on drain-to-empty → `restoreBacklog` re-prepends the
    retained entries WITHOUT republishing. Net: `peekWantSet()` was null on essentially every
    main-thread tick, the probe map handed to every routing cycle was empty, and EVERY position
    disk-read — the exact regression rough edge #2 spent a paragraph preventing via `peekWantSet`,
    defeated one layer down. Only an EARLY stop (NO_DISK_HEADROOM/sendQueueFull leaving un-polled
    entries) kept the set alive — i.e. coverage existed only when saturated. Backwards.
    **Not a Task 4 invention** — root is plan-level (`pollBacklog`'s null-on-empty is specified
    verbatim in Task 3, landed in 9096326), and Task 3's ledger OBSERVATION explicitly deferred the
    call: "If Task 4 wants the tighter behaviour, the one-line fix is for `restoreBacklog` to
    republish the current want-set; decide it there, with the router in view." This is that decision.
    **Chose FIX over the review's alternative** ("accept it, let Task 10's soak read `sources.in_memory`"):
    accepting would disk-read every position, contradicting rough edge #2's budgeted "residual window
    is small" and pushing straight into issue #32's saturation territory — for a ~3-line fix that
    keeps `pollBacklog`'s genuinely-correct converged-player-costs-zero-probes property intact. The
    fix restores the plan's STATED intent, so no plan-owner confirmation was needed (it would have
    been, had I accepted).
    **Proved before fixing, per method:** wrote `retainedEntriesKeepTheWantSetPublishedAcrossThe-
    DrainAndRestore` (BacklogReplaceTest) modelling the steady-state pass shape → RED on the old code,
    green after. Note the pre-existing `applyPublishesTheWantSetAndDrainingTheBacklogClearsIt` already
    *stated* this invariant in its comment ("lives exactly as long as there is backlog left to route")
    but never exercised `restoreBacklog` — the invariant was asserted in prose and unpinned in code.
    **Change:** new `appliedWantSet` field (the last applied batch, retained so `restoreBacklog` can
    republish on a cycle where no batch arrived — batches land at 1 Hz, the pass runs at 20 Hz;
    single-writer/processing-thread, so non-volatile is correct — `publishedWantSet` stays volatile
    for the main thread's read). Invariant now stated once and maintained in exactly three places:
    `replaceBacklogWith` (apply) / `pollBacklog` (null on drain-to-empty) / `restoreBacklog`
    (republish iff non-empty). Verified every early-stop path retains (both `stopPass` and the
    `sendQueueFull` break add to `retained`), so "published iff backlog non-empty" holds on every
    exit. Probe javadoc on BOTH platforms rewritten to describe what the code does; the plan's
    already-routed-positions caveat is now TRUE (and notes the 512 probe budget is spent on the
    closest-first head, so the ≤4 already-admitted head entries waste a negligible slice).
    Tier 1 green (593 = 592 + the new pin); `:paper:test` green (246, force-reran — `common/` +
    `paper/` both touched).
    **Lesson (same shape as Task 2's):** a comment restating the PLAN rather than the CODE is worse
    than no comment — this one asserted the mitigation was working while it was inert. Rough edge #2's
    `peekWantSet` mitigation was correct at its own layer and silently undone by a different task's
    verbatim-correct code; a cross-layer invariant needs a test at the layer that can break it.
  - **D4-9 (Task 5 hand-off).** `SendAction.RateLimited` now has ZERO producers (the router's bounce
    and the saturation bounce were its only two) and `LSSConstants.RESPONSE_RATE_LIMITED` survives only
    for it + the client dispatch arm at `LSSClientNetworking:213`. All three die together in Task 5.
    The two frozen rate-limited counters + getters are KEPT per plan (exporters read them until Task 6);
    `incrementRateLimited`/`incrementSyncRateLimited`/`incrementGenRateLimited` are deleted as specified.
- Task 5: COMPLETE — fc6b426. PROTOCOL_VERSION 16→17; `RESPONSE_RATE_LIMITED`,
  `SendAction.RateLimited`, the byte-0 client dispatch arm, `LodRequestManager.onRateLimited` and
  `LodRequestManager.getTotalRateLimited()` all deleted. Byte 0 RETIRED AND RESERVED — UP_TO_DATE(1)
  / NOT_GENERATED(2) NOT renumbered. Tier 1 green (593, 0 failures); `:paper:test` green (246, 0).
  Tier 2/3 remain EXPECTED RED (Task 7). Wire bytes unchanged for batchResponse — see D5-2.
  KEPT per plan (Task 6 deletes them with their exporter keys): `RequestMetrics.recordRateLimited`
  /`getTotalRateLimited` + `RequestMetricsTest`, `LodRequestManager.getQueueRemaining()` (still read
  by `BenchmarkMetricsExporter:428`'s `request_queue`), `ProcessingDiagnostics`' two frozen
  sync/gen rate-limited counters.

  ### Task 5 PLAN DEVIATIONS / FINDINGS

  - **D5-1 (plan undercounted the exporter callers — 2, not 1).** Plan Step 2 names only
    `BenchmarkMetricsExporter:397` (`responses.rate_limited`). There is a SECOND caller of the
    deleted `manager.getTotalRateLimited()` at `:571` (`total_rate_limited`, the soak/JFR client
    dump). Both are now literal `0L` with a Task-6 pointer comment; missing the second would have
    failed compilation, so this is a plan gap, not a judgement call.
  - **D5-2 (kept byte 0 in the wire fixtures instead of replacing it — STRONGER than the plan).**
    Plan Step 3 says replace the type-0 entry in both `WireParityTest.batchResponse` frames with
    type 1/2 and "regenerate the expected hex literals by running the test once". Two corrections:
    (a) there ARE no hex literals — both frames compute `expected` via `ref(b -> …)` from the same
    `types` array, so nothing needed regenerating and the encoded bytes are byte-identical to v16;
    (b) replacing 0 with 1/2 would DELETE the only coverage that the codec ships byte 0 unaltered.
    The codec is type-agnostic by contract, and that is precisely what makes reserving byte 0
    forward-safe (the plan's own Step 1 comment says so). Kept `(byte) 0` as a raw literal (no
    constant to reference now) alongside the existing `(byte) 200` future-type entry, on BOTH
    platforms, with a comment saying why it must stay. Same reasoning applied to
    `PayloadCodecTest:74` and `WireEdgeCaseTest:165`. Wire parity re-verified: both suites green.
  - **D5-3 (`SendActionBatcherTest.typeFor` — 3 types → 2, discrimination preserved).** Plan says
    drop `case 0` and map onto 1/2. Done as `i % 2`. Checked the resulting loss: the helper's job
    is to catch a type↔position mis-pairing across a frame split, and `positionFor(i)` is injective,
    so the pairing assertion at `:73` still discriminates every index. Documented on the helper.
  - **D5-4 (the reserved-byte pin, and PROOF it fires).** Rewrote `LodRequestManagerTest`'s two
    byte-0 tests from "the stub counts the bounce" into the forward-safety pin the plan asked for:
    byte 0 must be as inert as the unknown byte 99 (no stamp, no satisfy, no retry mark, no
    awaiting-entry consumption). **Mutation-verified rather than assumed:** re-adding a
    `case 0 -> manager.onColumnUpToDate(packed)` arm fails exactly
    `dispatchRoutesEachTypeAndUnknownTypeSkipsOnlyThatEntry` and
    `duplicatePositionInOneBatchFrameResolvesConsistently` and nothing else. The load-bearing half
    is that an inert byte 0 must not SATISFY — that would be a permanent invisible hole.
    D2-4's duplicate-frame discrimination survives unchanged (byte 0 just lost its metrics leg).
  - **D5-5 (`markRetry` NOT deleted — deviating from Task 2's hand-off note, with reasoning).**
    Task 2's ledger said "delete `ColumnStateMap.markRetry` with the stub in Task 5" because the
    bounce was its only production writer. Confirmed dead in production — but I kept it, because
    the note was written without checking what its 15 call sites pin. The retry MECHANISM is very
    much alive (`onIngestFailed` writes the mark; `hasRetries()` → `SpiralScanner:115`'s
    confirmed-ring reset), and `onIngestFailed` is the only other writer — but it necessarily
    rewrites the timestamp too (removes it, or restores the pre-clear stamp). So the 15 tests in
    `ColumnStateMapTest`/`SpiralScannerTest` that pin the retry mark's OWN lifecycle (prune, clear,
    ring reset, the chaos alphabet) cannot seed through it without changing their premises — the
    only alternatives were to weaken them or delete them, both worse than a 3-line seam. Kept and
    documented as an explicit test seam ("not called from production; do not add a production
    caller"). If someone still wants it gone, the honest route is a test-only helper, not a
    reseed through `onIngestFailed`.
  - **D5-6 (stale-comment sweep — Task 2/D4-10's lesson applied; found a user-facing LIE).**
    I deleted the mechanism several comments still named as live, so I swept for it. Fixed in
    MAIN source: `ColumnStateMap`'s `retry` field comment ("Positions bounced with rate-limited"
    — that writer no longer exists), its class javadoc, its `classify` javadoc ladder (which was
    ALSO already stale independently of this task — it claimed `unknown > generation > dirty`
    when the code has put dirty first since the delivery-honesty refactor), and the `// Rate-limit
    retry` rung comment. The worst find was in `common/`: `AbstractChunkDiskReader:36-37` and its
    **admin-facing WARN string at :109** both still told server operators that saturated reads are
    "bounced as rate-limited" and that "clients retry automatically" — Task 4 had already made a
    saturated result a SILENT DROP (`OffThreadProcessor:670`, counted superseded, recovered by
    re-declaration), and this task removed the bounce from the wire, so the log line was shipping a
    statement about a wire disposition that no longer exists. Log text and comment corrected;
    `ChunkReadResult`'s javadoc ("the position should be retried later") likewise. Comment/log-only
    — no assertion or behaviour changed, both gates re-run green afterwards.
  - **D5-7 (Task 8 hand-off, NOT actioned here).** `scripts/check_soak.py:1007-1039`'s
    `rate-limit-storm` named check will now fail by construction: it gates on
    `service.gen_rate_limited >= 1` and `client.responses.rate_limited >= 50`, and both are frozen
    at 0 from this commit on. This is exactly Task 8's specified work (its bullet re-premises the
    scenario onto `service.superseded >= 100`, keeps the generation floor + quiescent tail, and
    deletes the two `responses.rate_limited` checks) — flagged here only so nobody runs
    `./scripts/soak.sh rate-limit-storm` between Tasks 5 and 8 and reads the red as a regression.
    Soak is not a gate for this commit; Tier 1 + `:paper:test` are.
- Task 6: COMPLETE — 0ed76a7. Both exporter families patched, contracts moved in lockstep, the
  frozen counters and their last callers deleted. Tier 1 green (593 tests, 0 failures);
  `:paper:test` green (246, 0 failures). Tier 2/3 remain EXPECTED RED (Task 7).
  MAIN SOURCE:
    - `BenchmarkMetricsExporter` — FAMILY 1 (soak snapshots): `service.sync_rate_limited`/
      `gen_rate_limited` → `service.superseded`/`range_filtered`; `players[].incoming_dropped` →
      `players[].backlog` (getBacklogSize); client `responses.rate_limited` + `request_queue`
      deleted; `tracker_in_flight`/`queued`/`requested_total` kept with re-declaration comments.
      FAMILY 2 (real benchmark, zero test coverage): `exportServer()`'s map and
      `buildClientMetrics()`'s `total_rate_limited` — see D6-1.
    - `PaperSoakMetricsExporter` — same service/players changes, verbatim lockstep.
    - DELETED (last callers gone): `ProcessingDiagnostics.getTotalSyncRateLimited`/
      `getTotalGenRateLimited` + both frozen fields; `RequestMetrics.recordRateLimited`/
      `getTotalRateLimited` + `totalRateLimited`; `LodRequestManager.getQueueRemaining()`.
  CONTRACTS: `server-snapshot.contract` −gen_rate_limited/−sync_rate_limited/−incoming_dropped,
  +service.superseded/+service.range_filtered/+players[].backlog (re-sorted);
  `client-snapshot.contract` −request_queue/−responses.rate_limited.
  TESTS: `RequestMetricsTest` (both rate-limited legs incl. :55), `ExporterContractTest:298`,
  `PaperExporterContractTest` (backlog + distinct superseded/range_filtered stubs).

  ### Task 6 PLAN DEVIATIONS / FINDINGS

  - **D6-1 (family 2's `rate_limiting` map — RENAMED, not dropped; plan offered both).** Plan Step 2
    says "drop the whole map or just the two entries at :527-528". Neither is right as stated:
    dropping the map loses `queue_full` (a live counter, its only other member), and keeping a map
    literally named `rate_limiting` in a protocol with no rate limiting is exactly the stale-naming
    trap Tasks 2/4/5 each had to clean up after. Renamed to `backpressure` and gave it the two v17
    dispositions alongside `queue_full`: `{queue_full, superseded, range_filtered}`. This family has
    ZERO Tier-1 coverage (only `-Dlss.benchmark=true` reaches it), so the rename is unpinned by
    construction — verified it compiles as part of `:fabric:compileJava` and that no script/doc reads
    the key (`grep` over scripts/ docs/ README/CLAUDE: only CLAUDE.md:235, which is Task 9's).
    **→ TASK 9 HAND-OFF:** the plan's Task 9 bullet (line ~1382) says to DROP CLAUDE.md :235's
    `rate_limiting.sync_rate_limited`/`gen_rate_limited` lines. Because of this rename it should
    instead DOCUMENT `backpressure.{queue_full,superseded,range_filtered}` in the server.json
    interpretation table. CLAUDE.md :203/:241's `total_rate_limited` (client.json) is a plain
    deletion as planned — that key is gone with no successor.
  - **D6-2 (the contract fixture is load-bearing on BOTH modules — mutation-proved, not assumed).**
    The plan warns the `.contract` files are a cross-module coupling but specifies no proof. Both
    suites went green on the first run, which is exactly the shape of a vacuous pass, so I mutated
    the fixture (`players[].backlog=int` → `=long`) and confirmed it fails
    `ExporterContractTest.serverSnapshotMatchesTheCheckedInContract` AND
    `PaperExporterContractTest.serverSnapshotMatchesTheSharedFabricContractLiteral`, then restored
    and re-ran both green. The new keys are genuinely emitted, at the right types, by both exporters.
  - **D6-3 (`ExporterContractTest`'s "disabled zero-fill branch" is not a branch).** Plan Step 2 says
    to "update the disabled-session zero-fill branch to the same key set". There is no separate
    branch — `buildClientSnapshot` zero-fills inline via `manager != null ? … : 0`, so the key set
    could not drift and the only edit needed was dropping the test's `request_queue` assertion.
    The test's real invariant (enabled and disabled flatten to an IDENTICAL key+type map) is
    untouched and still fires.
  - **D6-4 (stale-comment sweep — one real find, per the Task 2/4/5 lesson).**
    `FlushSendQueueTest:138` justified a live invariant with "re-requests bounce rate-limited, not
    re-resolve" — a bounce Task 5 removed from the wire. The INVARIANT is intact and still correct
    (a bandwidth-gated payload stays in the enqueued set); only its disposition changed, and the
    replacement is real: `IncomingRequestRouter.resolvedAsDuplicate` skips a ts<=0 re-declaration of
    an enqueued column SILENTLY and deliberately does not clear the done-bit. Comment re-pointed at
    that method. Every other rate-limit hit in the sweep is correctly framed as history
    (retired-and-reserved byte 0, deleted-test tombstones) — left alone.
  - **D6-5 (soak-side lockstep debt — Task 8's, verified covered, NOT actioned).** The
    `server-snapshot.contract` header declares editing it a schema change requiring `check_soak.py`
    KNOWN keys + `docs/planning/soak-test-design.md` to move in lockstep. They are now out of step
    (`check_soak.py:160,176,205-210`, `soak_report.py:57-58,67`, design doc L85/L101/L114/L121).
    Checked the plan: Task 8 Step 1 covers every one of these lines by name (KNOWN_SERVER_KEYS/
    KNOWN_CLIENT_KEYS, PLAYER_DRAINS + backlog, SERVER_MONOTONIC, SERVER_MECHANISM, the design-doc
    tables). Nothing to do here — flagged only so nobody runs a soak between Tasks 6 and 8 and reads
    the KNOWN-key rejection as a regression. Soak is not a gate for this commit.
- Task 7: NOT STARTED — Tier 2 gametests + Tier 3 (~1215-1258).
- Task 8: NOT STARTED — soak harness (part of the change, not a gate) (~1259-1327).
- Task 9: NOT STARTED — docs (CLAUDE.md, README, read-scheduler-design.md supersession note).
- Task 10: NOT STARTED — validation gauntlet + live soak.

## Standing reminders

- HARD SEQUENCING: no commit may ship server-side silent drops while the client still suppresses
  in-flight positions at send (10s-class stall). Client lands FIRST — that is why Tasks 1-2 precede 3-4.
- Tier 2/3 are EXPECTED RED between Tasks 4 and 7 (plan rough edge #6). Tier 1 + :paper:test gate
  every commit. Do not push to CI expecting green gametests in that window.
- Folia 26.2 unpublished upstream → SOAK_PLATFORM=folia cannot run. Paper soak + RegionProbeSchedulingTest carry it.
- `:fabric:test` does NOT pull runGameTest into its graph — run `:fabric:runGameTest` explicitly for Tier 2.
