# Far-player client/render review — MC 1.21.1

2026-09-06, Astra. Exact source tree `/home/vox/projects/lss-lines/1.21.1` (`49e588dba275`, parent verified tree equality with merged `1b544494`). Review and plan only. No repository changes, child agents, Gradle invocation or runtime-server changes by this reviewer. Parent coordinated external JUnit execution.

## FP0 — P2, high confidence, normal runtime operation: server mode off leaves existing far players frozen indefinitely

**Locations:** `xplat/src/main/java/dev/vox/lss/networking/server/RequestProcessingService.java:724-726`; Paper twin `paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java:1979` (parent config lens verified); common core `common/src/main/java/dev/vox/lss/common/farplayers/FarPlayerBroadcastService.java:234-236`. Runtime setting registration: `common/src/main/java/dev/vox/lss/common/config/RuntimeSettings.java:158-167`.

An administrator can run the supported `/lsslod set farPlayers off` command while viewers already see far-player proxies. The setting promises that off disables far players at the next broadcast tick. Both platform tick wrappers instead return immediately when mode is off; the core also immediately returns for off. No empty roster/removal frame reaches existing viewers. The client retains roster/motion indefinitely because ordinary stationary players are intentionally delta-suppressed; its render gate knows only client preferences, capability and dimension, not the newly changed server mode. Existing proxies and their tags therefore remain visible at the last received positions until another lifecycle reset, even if the actual target moves, leaves or becomes hidden afterward.

**Impact classification:** normal supported operation, not hostile protocol input. It is a stale ghost and retained last-known-position/name footprint after an administrative disable. It does **not** continue transmitting live movement while off. Parent's separate config reviewer verified command mutation is runtime-supported, both command implementations only specially repush the LOD-distance key, and `applyRuntimeConfig` supplies no compensating far-player transition/clear.

**Reproduction:** external `FarPlayerDisableReviewTest.runtimeOffMustWithdrawAlreadyPublishedPlayersInsteadOfFreezingThem`, located beside the identity probes. It connects the actual common broadcaster's encoded frames to the real client tracker, populates one far target, confirms stationary on-mode silence correctly leaves it present, switches to off, and asserts that the old target is withdrawn. Parent ran the test: **RED at the intended final assertion** (tracker remains 1 after off); the stationary-on control passed. Evidence: `farplayer-disable-probe.xml` and `xaero-and-farplayer-lifecycle-probes.log`. This pure test proves the protocol/lifecycle issue independently of graphics. The production wrapper's earlier skip makes the same problem unconditional there.

**Fix/plan:** introduce an explicit on→off withdrawal path and invoke it before either platform's off early-return. Send an empty epoch-appropriate/full roster or explicit removals to existing viewers; keep any withheld send pending and retry while off without rebuilding online-player snapshots. Preserve subscriptions and retained target privacy prefs. On off→on, force a fresh roster/first updates (including stationary targets) so the new clear cannot make unchanged players disappear permanently through delta suppression. A core-only change is insufficient if platform tick wrappers still bypass it. Add direct on→off→on, stationary-target, withheld-clear retry, and multiple-viewer regressions, plus a source/wiring or service seam test that the disabled platform pump still drains withdrawals. Do not add a generic client inactivity TTL; it breaks legitimate stationary proxies.

## FP1 — P2, high confidence, protocol robustness: occupied-index replacement bypasses the client's identity cap

**Location:** `common/src/main/java/dev/vox/lss/common/farplayers/FarPlayerClientTracker.java:69-75`, with the retained drawable map populated at `117-118`.

`onRoster` overwrites an existing index→UUID binding without retiring the previous UUID from `nameByUuid` and `tracked`. The only identity-cap check examines `uuidByIndex.size()`. Therefore a peer can repeatedly bind index 0 to fresh UUIDs, sending one update after each rebind, and the index map remains size 1 while the name/tracked maps grow without limit. `snapshot()` exposes every accumulated old tracked identity to the renderer, so this is both an orphaned-proxy bug and an unbounded heap/per-frame render-work path. The prior UUID is no longer addressable by a removal frame: removing index 0 only removes the most recently bound UUID. Full roster/reset/disconnect clears the accumulation.

**Trigger classification:** buggy/hostile server or a different implementation sending occupied-index replacements. **This is not emitted by the stock LSS broadcaster:** its `FarPlayerBroadcastService.java:307-340` assigns monotonically increasing `nextIndex` values within an epoch, and full rosters clear/rebuild state before resetting the counter. Do not present this as normal stock-server gameplay. The frames nevertheless pass the real codec (same current epoch, one permitted roster add and one permitted update per frame); the cap explicitly promises hostile/buggy-server armor across multiple frames, which this sequence bypasses.

**Reproduction and evidence:** external decoded-wire JUnit source:

`/home/vox/.local/state/lss-review/20260906-astra/probes/farplayers/dev/vox/lss/common/farplayers/FarPlayerIdentityReviewTest.java`

- `rebindingOneIndexMustRetireItsPreviousTrackedIdentity`: two decoded roster/update pairs leave **two drawable identities behind one index**, retaining the old UUID.
- `repeatedlyRebindingOneIndexMustNotBypassTheIdentityCap`: 4097 decoded pairs leave `tracked=4097`, `identityCapResets=0`, despite the 4096 bound.

Parent ran both: **both RED at intended assertions**, evidence `farplayer-identity-probe.log` / `.xml`. Existing `FarPlayerClientTrackerTest.identityAccumulationPastTheCapClearsAndSelfHeals` adds fresh indices, so it does not exercise this bypass.

**Fix/plan:** enforce the roster's identity mapping consistently when adding. Either reject/reset on a conflicting occupied index (and await a full roster), or replace the binding while removing the displaced UUID's name/motion/equipment state when it no longer has a live binding. Define duplicate-UUID aliases too, rather than fixing only one overwrite direction. Bound the actual retained identity maps as defense in depth. Avoid introducing a time-based tracker expiry: stationary players intentionally receive no repeated updates. Add the external two regressions, same-UUID rename/update, explicit remove→reuse, duplicate-UUID aliases, full-roster rebuild and stale-epoch cases. Pure common Tier 1 testing suffices for the state contract; no graphics runtime is needed to prove the leak.

## FP2 — P3, high confidence, exceptional render path: seated-rider recovery leaves a corrupted pose for the rest of the pass

**Locations:** `fabric/src/main/java/dev/vox/lss/networking/client/FarPlayerRenderer.java:422-424`; `neoforge/src/main/java/dev/vox/lss/networking/client/FarPlayerRenderer.java:481-483`.

Both seated-proxy `dispatcher.render` catch blocks call `latchSeatedFailure` and then continue the same render pass **without** `restorePose(poseStack, passMark)`. `latchSeatedFailure` only latches/removes the mount and breaks the ride link; it has no pose-stack parameter and does not restore one. The separate mount catch restores the mark, and NeoForge's unseated-proxy catch restores it, but the seated-rider branch was missed.

**Concrete trigger/impact:** a renderer/layer/mixin throws while drawing a seated far player, or on NeoForge a `RenderLivingEvent.Pre` listener throws for the seated proxy. The dispatcher already pushed and translated the stack for that entity. After LSS catches the exception, later far players and the queued name tags draw under the failed player's leftover transform (and potentially its living-model rotations/scales). A distant player can disappear or subsequent proxies/tags can be misplaced for that frame. Type latching generally bounds the event to the first failure of that vehicle type; a new session can retry it.

**Do not overstate:** the outer pass `finally` eventually unwinds the sentinel, so this omission does **not** recreate the old `LevelRenderer.checkPoseStack` hard crash. Its demonstrated consequence is contamination of later draws within the current pass, not a persistent vanilla pose leak.

**Evidence:** source-level control-flow proof against actual 1.21.1 patched sources, archive:

`/home/vox/.gradle/caches/neoformruntime/intermediate_results/transformSources_018e6231f9cda0b7144d640a99c7f57e639c29ea_output.zip`

`net/minecraft/client/renderer/entity/EntityRenderDispatcher.java:160-162` performs `pushPose`, `translate`, then `entityrenderer.render`; the normal `popPose` is line 183, with a catch/rethrow at 184 onward rather than a finally. `LivingEntityRenderer.java:53` posts NeoForge's pre event inside that dispatcher push; its own further push is line 54. This precisely reaches LSS's missed seated catch with an extra translated pose. The hardening plan's WI-6 fold (f) explicitly promises **every** per-proxy/per-mount catch restores the sentinel before continuing. `FarPlayerRenderSourceContractTest` merely counts four NeoForge restore sites (three unseated drops plus mount catch) and tests file-wide presence on Fabric; it cannot detect this missing branch.

**Fix/plan:** restore the sentinel in both seated-render catches before continuing, mirroring the mount/unseated paths. Preserve type-latching and contained dismount semantics. Add a branch-specific source contract (or factor recovery into a small testable operation) so the exact seated catch cannot omit restoration while a global occurrence count still passes. A useful behavioral fault test uses a real `PoseStack`, simulates the dispatcher's push/translate then throws from the seated draw, and asserts that the next draw starts at the sentinel. The next already-planned modded-mount client smoke can exercise an injected seated render failure; no new live test was run for this report.

## Accepted decisions and examined non-findings

- Both 1.21.1 renderers are live immediate-mode implementations. Their actual diff was inspected: loader event/context wiring, NeoForge's wider per-proxy containment, its nameplate-distance attribute handling, and idempotent init are intentional loader differences.
- The sky-15 light floor, optional full-bright, overlay cutoff at 80 blocks, radial/tiered armor/held-item lift, dual depth-tested name-tag passes, and animation default 512 are already present. No proposal to undo those live-verified fixes. Tiered depth lift deliberately accepts small incorrect overlaps; shaders that exclude LODs from vanilla depth and the unextended vanilla far plane are documented external limitations.
- Skin overlays/cape policy, frozen boat paddles and missing elytra yaw banking are documented cosmetic decisions. The renderer writes glide flags/ticks, swim amount/water flag, body/head rotations and their old values; the 1.21.1 walk stop uses `setSpeed(0)` plus `update(0,1)` as required.
- Real-player/proxy handoff deliberately uses the real entity's cull predicate, without a distance hysteresis band. The `hasChunk` term is known inert on this line, not a new finding. No extra real-entity spawning occurs; proxies remain render-only, IDs are in the reserved block and checked against the level.
- Mount resolution uses strict optional registry lookup, per-type failed-creation/render latches, bounded latch size and contained dismount/cleanup. Direct mount only, player-vehicle collapse, type attribution on a seated failure, and per-session retry are existing choices. No new ordinary mount-creation crash was established.
- Motion interpolation uses monotonic time, bounded declared-cadence correction, shortest-angle lerp and capped velocity extrapolation. Full/ incremental roster epoch guards, sticky equipment and session reset/resubscribe paths were examined. Absence of inactivity expiry is intentional because stationary updates are delta-suppressed.
- `endLastBatch` only, rather than global `endBatch`, is the current immediate-mode flush discipline. The pass sentinel and tag `finally` are present; FP2 concerns the omitted *intermediate* recovery, not their absence.

## Coverage and limits

Reviewed common tracker/motion and wire acceptance; xplat client support, mount ladder and server snapshot inputs relevant to appearance; complete Fabric and NeoForge renderer implementations; their source contracts and tracker/motion/mount/support tests; current far-player render-hardening and NeoForge render-port plans, including live-rig folds and explicit accepted tradeoffs. Session-end/config/reset call sites were traced. Rendering observations in this report are source-supported, not claims of a new visual live run.

The runtime off transition was cross-checked with the separate config reviewer and is reported as FP0 above. Other server privacy, exclusions/permissions/vanish, and cross-region snapshot privacy are owned by another lens.
