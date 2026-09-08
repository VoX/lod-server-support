# Client implementation-plan review — 2026-09-08

Reviewer: Astra. Review only of the expanded `docs/planning/astra-system-review-fix-plan.md` in `/home/vox/projects/lss-lines/1.21.1`, focusing WI-3, WI-4, WI-5, WI-8, WI-9, WI-12 and WI-15. Read current source and related pinned state/processor/session/render contracts. Consulted the prior reports and the review-findings-vs-pinned-decisions discipline. No source edits, tests, builds, live client/server operations or children.

## Disposition

Proceed after incorporating the three corrections below. The findings themselves remain valid. WI-4/8/9/12/15 require no design reversal. WI-3/5 need an explicit ownership boundary and separate treatment of already committed state; otherwise a superficially successful toggle repair could reintroduce pinned ghost-terrain or texture-loss behavior.

### Required correction 1 — capture report ownership at delivery; retain the tokenless API limitation

**Source:** `xplat/src/main/java/dev/vox/lss/api/LSSApi.java:87–102`, `networking/client/ClientNetGlue.java:130–139`, `ClientColumnProcessor.java:51`, `:80`; existing late-report limitation in `ColumnStateMap.java:746–750` and `ClientSessionGate.java:408–413`, `:521–527`.

The public `reportIngestFailure(dimension, x, z)` API contains no owner or delivery token. The current glue resolves the global manager inside a later main-thread runnable. The expanded plan correctly demands stronger ownership for internal work, but cannot promise exact-delivery or old-session exclusion for arbitrary detached third-party callbacks using that unchanged signature. Capturing the current generation when such a callback finally reports merely gives old work the new generation. A dispatch ThreadLocal covers synchronous callbacks, not detached threads.

**Correction:** create a captured internal acquisition/receipt report handle at admission/dispatch and carry it through processor cancellation, implicit consumer throws and Xaero's deferred work. Publication must target that captured owner and verify it on the owning thread. Keep the old public API compatible and explicitly document its tokenless detached-report limitation. An additive capture API is reasonable if a consumer can retain the handle during its supplied callback; it is not necessary to pretend old integrations already use it. Do not add a wire field.

**Acceptance:** pause an internally owned report, retire and replace the manager at the same coordinate/dimension, then release: it cannot remove the replacement proof or spend its retry budget. A synchronous consumer throw and a captured asynchronous handle must still repair their own active receipt. Document and test legacy behavior separately rather than advertising all tokenless asynchronous reports as protected.

Parent accepted this correction during review.

### Required correction 2 — cancellation of an undelivered authoritative clear must restore the prior content claim

**Source:** `networking/client/ColumnStateMap.java:552–560`, `:746–750`, `:756–799`; rejected-clear and cap-retention tests in `fabric/src/test/java/dev/vox/lss/networking/client/ColumnStateMapTest.java:460–507`, `:646–666`; receipt-before-enqueue in `ClientNetGlue.java:373–393`.

WI-3's intentional cancellation must not become a generic unstamp-to-`-1`. If the queued payload is an authoritative content-to-air clear and never reaches the consumer, the consumer still holds the previous terrain. The existing failure path restores the real pre-clear positive stamp because the server sends a clearing column only to a client claiming data. Reporting `-1` instead can elicit all-air UP_TO_DATE and strand the old terrain.

**Correction:** retirement has distinct lost-content and lost-clear outcomes, both with no extra failure strike. Restore the honest pre-clear stamp for a cancelled clear; remove an unaccepted content stamp when no stronger retained proof exists. Match the receipt identity so a cancellation cannot undo a later accepted delivery. Carry this through pending cache-load removals and persistence; simply changing the in-memory retry counter is insufficient.

**Ordering:** retire admission and account for queued/in-flight provisional receipts before detaching state for save, and before the resumed manager loads that key. `LodRequestManager.saveCache()` transfers ownership of the state to the IO save. Sending an eventual old failure to an immutable retired manager does not repair a stamp already detached and persisted. Establish a bounded outstanding-receipt ledger or equivalent admission-time tracking; do not wait indefinitely for external consumer completion on the client thread.

**Acceptance:** accept content, receive a clear, turn OFF before dispatch, save/reload and turn ON: the next declaration claims the pre-clear data and obtains a clearing delivery. Verify no failure-budget charge and no invalid persisted clear stamp. Keep accepted-clear, cancelled ordinary content, overlapping later delivery and existing sibling-failure/cap controls.

Parent accepted the lost-clear and retirement-before-save corrections during review.

### Required correction 3 — acquisition OFF is not native Xaero world destruction

**Source:** `xplat/src/main/java/dev/vox/lss/compat/XaeroMapCompat.java:694–709` and `:713–738`, especially the pending-rebuild drop at `:717–722`.

The plan defines the bridge generation as acquisition/session-owned and asks OFF to retire it. That is right for queued deliveries, owed repair debt and deferred failure reports. However, reusing the whole existing `onSessionEnd`/`settleSessionEnd` teardown indiscriminately also discards `pendingUpdates`: rebuild obligations for pixels already committed to Xaero. The existing comment justifies that loss because the old world's tile chunks will never be touched again, accepting the last ≤2-second texture window at disconnect. Local receive OFF leaves that Xaero world live, so the premise no longer holds.

**Correction:** distinguish acquisition retirement from native-world/disconnect teardown. Retire old acquisition queue/debt/report ownership while allowing already committed same-native-world rebuilds to complete, or explicitly finish their safe rebuild obligation before clearing them. Do not use a blocking flush inside Xaero locks or clear an accepted proof to disguise this issue. Full disconnect/world replacement retains its existing bounded loss policy.

**Acceptance:** queue a pending rebuild after a successful map commit, switch receive OFF, and show that the committed tile can still become rebuilt without accepting new columns. Separately prove acquisition debt/report cancellation, and retain actual disconnect's existing pending-rebuild drop behavior. Repeat OFF/ON while a rebuild is pending.

Parent notified; implementation should incorporate this distinction with WI-5.

## Accepted design and implementation notes

### WI-3

The shared client-thread Apply/tick reconciliation, idempotence, delayed session-config guard, OFF-at-join handshake and independent privacy preferences are appropriate. Do not implement OFF by calling full `ClientSessionGate.onDisconnect`: that resets connection dialect/discovery and other independent state. The existing empty-want-set path is `LodRequestManager.sendClearBatch`; stopping scans or `disconnect()` alone does not withdraw the backlog. Already admitted server work may finish; acceptance should measure no new nonempty wants and honest local retirement, not zero immediately arriving network bytes.

The current processor epoch is checked at queue poll, so a polled callback may continue. Track its receipt before polling/dispatch and prevent later state mutation from escaping its owner. There is no supported way to forcibly cancel arbitrary external code already executing; the new guarantee should concern admitted/retired state and report ownership, with captured context for integrations under LSS control.

### WI-4

The new summary-only revocation operation is correctly separate from numeric `applyTileValidation`. The latter explicitly requires real timestamps; do not pass a sentinel through it. Walk materialized leaves only, clear only summary-owned validation, recompute needs, and invoke the existing reopen callback for revoked positions. Per-column delivery/up-to-date proofs and dirty/retry marks must survive. The independent reference-model requirement is useful. The portal chronology remains an integration acceptance case, not prior live proof.

### WI-5

Keep outside-lock Xaero probes and revalidate exact record identity after each one. A generation-only check followed by global-manager lookup is still unsafe; the captured report sink from correction 1 closes that boundary. Capture the origin while the delivery is admitted, before a delayed consumer/overflow callback can run. Queue records, owed records, classifiers and deferred reports need coherent ownership; same-dimension and a currently true sessionActive boolean are insufficient. Correction 3 distinguishes committed native-world rebuild obligations from those cancelled acquisition records.

### WI-8

The deterministic one-to-one mapping repair is bounded and avoids requiring a new resync protocol. Preserve existing **adds before removals** frame ordering (`FarPlayerClientTracker.onRoster:69–85`). A UUID moved to a new index must leave an old-index removal unable to delete its new binding. Same-index/same-UUID rename should update observable snapshot naming without throwing away motion/equipment unnecessarily. Keep cap enforcement and reset lifecycle for any reverse map. No expiry or stock-server exploit claim is needed.

### WI-9

The two 1.21.1 seated catches need restore-before-latch, preserving outer cleanup and buffer ownership. Keep the fixture connected to the production catch/helper so it does not merely prove PoseStack itself can restore. No broad render refactor is justified for these two sites. Other four Fabric implementations are controls; their NeoForge stubs are deliberate.

### WI-12

The explicit old-interface/namespaced-method rung and carrier are correct. Preserve modern precedence and all-or-nothing reset-domain handle publication. Resolution and invocation tests should cover all three valid shapes and ensure the fallback carrier is `Minecraft.levelRenderer`, not the old interface's static render-system getter. The actual dependency gap remains specific to the verified 1.21.1 port; cross-line shared helper changes do not establish matching deployed failures.

### WI-15

Reviewed the 1.21.11 Proxy.apply newTick/pose/shared-flag order and the 26.2 reference tick. Updating elytra state once per animation tick after current pose/flag application fits this family. Apply the same bounded change on 1.21.10, with its own native fixture. No full synthetic entity tick, velocity fabrication, 1.21.1 state-field port or per-render-frame stepping. The source/behavioral fixture should observe production's tick placement, not only an isolated vanilla animation object.

## Remaining limits and implementation readiness

No tests were executed in this review. No current live mod matrix was checked. Review of the broad five-line execution runbook is outside this assigned client pass; these comments concern the listed designs and ownership boundaries. The original source findings remain supported by their recorded evidence. Ready for scoped WI-3/4/5 implementation after all requested reviewers finish and the parent incorporates these corrections; no production work began during this review.
