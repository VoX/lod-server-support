# Astra system review: detailed implementation plan

Review date: 2026-09-06. Implementation-plan expansion: 2026-09-08. Status: ready for implementation; no fixes implemented. The review and its challenge are complete; this expanded implementation design has been source-checked by the parent, not subjected to a new subagent review. Primary target MC 1.21.1; all five merged lines included in port review. Review only; no production implementation, installed-jar replacement or server restart performed by this review.

## Scope and evidence discipline

Review baselines: 26.2 `2b2a0df7`, 26.1 `8e900e69`, 1.21.11 `da726358`, 1.21.10 `d08faa91`, 1.21.1 `1b544494`. Worktree source trees were verified identical to these merged commits even where local HEAD remains the pre-merge feature commit.

All reviewer threads use gpt-6-astra with high reasoning. The tool permits three reviewer threads alongside the parent and refused additional threads after they completed; subsequent subsystem passes therefore reuse those Astra threads. Reports are separate lenses, not fifteen simultaneously independent agents. Two initial passes (wire, storage) ended with automated content-filter errors; their incomplete coverage is explicitly recorded. Parent performed a focused wire pass and validated the storage evidence independently. No claim of an exhaustive security audit is made.

Findings must survive related test/decision review and have a concrete trigger. Deterministic external tests run against unchanged production source; expected RED probes establish a missed invariant and are not existing-suite regressions. Source-only findings and inferred integration schedules are labeled. Existing known limitations are not silently reclassified as new defects.

## How to execute this plan

The work-item numbers remain stable for tracking against the evidence bundle. Each item below now includes the intended design, files to change, commit sequence and observable completion conditions. Proposed helper/type names are design suggestions, not claims that those APIs already exist. Locate code by symbol: the review's line numbers belong to the recorded baseline and will drift during implementation.

### Preparation and branch layout

1. Record the current worktree status and exact commit/tree IDs for all five lines. Fetch the branch tips when implementation begins; compare intervening changes with the recorded review baselines before applying a fix. Preserve unrelated local work. A finding already repaired upstream should receive a verification record rather than a duplicate patch.
2. Use a dedicated implementation branch per line, based on that line's current target: `support/mc1.21.1`, `support/mc1.21.10`, `support/mc1.21.11-v0.14`, `support/mc26.1-v0.14`, and `main` for 26.2. Confirm these target names still exist. Never merge a support branch wholesale into main. Port individual scoped commits with traceable source SHAs.
3. Read each affected suite's javadoc and the applicable per-version surfaces row before changing behavior. Record any intended departure from a pinned decision explicitly; this plan requires none of the documented feature cuts to be reversed.
4. Promote the diagnostic prototypes into the appropriate existing test suite or a focused new test class. Replace external absolute paths and reflection that is unnecessary in the permanent fixture. Keep fixtures outside real worlds and installed client profiles. Establish the original failing assertion on the baseline, then make it green with the fix; record the control results too.
5. Keep the reviewed wire layout, schema version and released golden corpus unchanged. WI-1 repairs completion semantics; WI-7 validates existing schema-3 data. Neither requires a database schema bump or a wire change.

### Dependency and commit sequence

| Stage | Packages | Dependency / exit condition |
| --- | --- | --- |
| A: urgent durable correctness | WI-1 | Start here; shutdown/reopen regression green. Independent of harness work. |
| B: reliable test infrastructure | WI-13a, WI-13b | Must finish before any new soak/benchmark evidence is accepted. May follow A before the rest of product work. |
| C: asynchronous server ownership | WI-2a reader, WI-2b generation, WI-2c fan-out audit | All three required to close WI-2; carry one coherent registration identity through adapters. |
| D: client lifecycle and freshness | WI-3, WI-4, WI-5 | Separate commits; joint OFF/reconnect/debt tests after all three. Coordinate shared ClientNetGlue edits. |
| E: remaining ordinary correctness | WI-6, WI-7, WI-15 | Independent mechanisms; WI-15 changes only two Fabric ports. |
| F: conditional integration and robustness | WI-12, WI-8, WI-11 | Preserve successful compatibility paths and normal roster behavior. |
| G: contained rendering / feedback / operations | WI-9, WI-10, WI-14 | WI-14 rig inventory must precede the live gates it identifies, even if prose cleanup lands last. |
| H: aggregate acceptance | All applicable packages | Per-line builds, relevant runtime gates, artifact checks, final matrix and reviewable changes. |

Each product commit should contain its regression and production repair together, with the prior RED result recorded in the implementation log. Small internal commits are acceptable for WI-2, but do not release a partial ownership repair. Port validated common logic promptly rather than allowing all five trees to diverge until the end. Run CPU-heavy suites and soaks serially; the existing flake catalog explicitly excludes concurrent soak/test contention from valid evidence.

### Shared path key

All paths below are relative to the relevant line's worktree. `common/...` abbreviates `common/src/main/java/dev/vox/lss/common/`; `xplat/...` abbreviates `xplat/src/main/java/dev/vox/lss/`; Paper production code is under `paper/src/main/java/dev/vox/lss/paper/`. Shared/common tests are predominantly under `fabric/src/test/java/dev/vox/lss/`, not a standalone common test task. Same-FQN loader twins must be reviewed together.

## Work order

| Work | Priority | Scope | Result and evidence |
| --- | --- | --- | --- |
| [WI-1 Store invalidation completion](#wi-1) | P1 | All five, common SQLite store | Interrupted mask-policy cleanup commits new fingerprint over surviving old rows; reopen probe RED. |
| [WI-2 Async result session ownership](#wi-2) | P2 | All five, common reader, generation and loader lifecycle | Old read enters replacement queue, delivers stale bytes, sets fresh done bit and answers false up_to_date; three probes RED, including buffered generation. |
| [WI-3 Client receive toggle](#wi-3) | P2 | All five, shared client/menu | OFF continues acquisition and consumes ingest retries; ON leaves parked terrain. Joining OFF then ON lacks handshake (source). Two probes RED. |
| [WI-4 Summary proof retraction](#wi-4) | P2 | All five, shared client | New doubt sentinel leaves old summary proof SATISFIED; two frame-level probes RED. Portal schedule inferred, integration test required. |
| [WI-5 Xaero session ownership](#wi-5) | P2 plus P3 accounting | All five, shared bridge | Old debt survives into next server; release can leave owed=-1. Two interleaving probes RED. |
| [WI-6 Runtime far-player disable](#wi-6) | P2 | All five, common + xplat/Paper wrappers | OFF leaves published proxies frozen. Broadcaster-to-tracker probe RED with stationary-ON control. |
| [WI-7 Migration integrity](#wi-7) | P2 | All five, common SQLite store | Migration accepts checksum-mismatched row normal reader rejects. One RED and one GREEN control. |
| [WI-8 Roster identity robustness](#wi-8) | P2 robustness | All five, common tracker | Rebinding one occupied index leaks old identities and exceeds 4096 cap; two probes RED. Stock broadcaster does not emit this sequence. |
| [WI-9 Seated render recovery](#wi-9) | P3 | 1.21.1 Fabric+NeoForge; other renderers separately audited | Missing intermediate pose restore after seated-render throw affects later proxies/tags in that frame. Source/vanilla path verified; no live render test. |
| [WI-10 Persistence feedback](#wi-10) | P3 improvement | All five, common config + commands | Runtime apply reports no persistence failure. Source validated; nonfatal save is intentional and must remain. |
| [WI-11 Disguise reload binding](#wi-11) | P2 conditional lifecycle | All five Paper | Single-plugin replacement keeps old API through surviving LSS classloader; production-default-resolver probe RED. Ordinary restart/full reload excluded. |
| [WI-12 Voxy reset compatibility](#wi-12) | P2 conditional integration | Verified 1.21.1 artifact; shared ladder port to all | IGetVoxyRenderSystem + voxy$shutdownRenderer shape unsupported; actual jar member verified, resolution probe RED. Ingestion not implicated. |
| [WI-13 Harness ownership/results](#wi-13) | P2 validation tooling | All five scripts | Soak mutates shared world before stale-server check; direct benchmark can print stale results after failed run. Source/control flow verified. |
| [WI-14 Validation matrix/prose](#wi-14) | P3 improvement | Line-specific | C2ME 1.21.11 pin split; installed Voxy rig identities need explicit verification; stale operational NeoForge guidance. |
| [WI-15 Elytra animation port](#wi-15) | P2 cosmetic | Fabric 1.21.10 and 1.21.11 only | Render-only proxies never advance the wing state read by vanilla; source and real MC bytecode verified. |

<a id="wi-1"></a>

## WI-1 — make policy invalidation completion durable

Evidence ST-01 in 03-storage-parent. Primary SqliteLodStore:2382–2389; deletion stops early at 2597. A shutdown during dimension deletion must not let the fingerprint advance until all old-policy rows are gone.

Return explicit completed/interrupted status or propagate a controlled interruption. Persist new fingerprint only after complete deletion. Preserve bounded batches, reader exclusion, deposit barriers, shutdown responsiveness and admin-drop behavior. Do not replace batching with one unbounded deletion or hold shutdown indefinitely.

Acceptance: interrupted before first batch and between batches; reopen cannot serve old-policy rows; completed invalidation does not repeat unnecessarily; admin-drop interruption remains honest; existing store/sweep/shutdown tests pass. Entire store source is byte-identical across five lines. Treat this as the first correctness fix before the next release.

### Implementation steps

**Files:** `common/.../store/SqliteLodStore.java`; existing `SqliteLodStoreTest` and store sweep/shutdown suites. Seed the regression from `ReviewMaskShutdownTest` in the evidence bundle.

1. Replace the ambiguous integer return from `dropDimensionRows` with an explicit result such as `DropResult(rowsDeleted, completed)`. `completed=true` means the bounded SELECT observed no remaining rows; a shutdown exit is incomplete even if the last batch happened to empty the table. Do not infer completion from a row count or a late read of the shutdown flag.
2. Audit all three callers: missing-region-directory cleanup, mask-drift cleanup and admin drop. In the sweep callers, an incomplete result must follow the existing interrupted-sweep path before writing a new fingerprint, clearing freshness bookkeeping or declaring sweep completion. In the admin path, aggregate explicit completion across dimensions before clearing migration metadata; preserve existing log/metadata semantics. The command enqueues work and has no synchronous completion response to add.
3. Keep already committed delete batches committed. Do not pretend a transaction rollback can restore them. Leave the old fingerprint durable so the next open repeats the required invalidation; publish the new fingerprint only after actual exhaustion. Preserve existing `droppingDims` reader exclusion, old-deposit barriers and finally cleanup. Confirm the existing startup sweep/read gate prevents access to surviving rows until resumed invalidation finishes.
4. Preserve bounded shutdown: the writer must stop between batches, release resources and allow same-JVM reopen without two active writers. Do not fix durability by blocking shutdown until an arbitrarily large deletion completes.

**Regression sequence:** seed old-policy rows with a valid unchanged region header; reopen with a stricter fingerprint; stop once before any delete and once after a committed batch; reopen normally under the new policy. Assert no old-policy hit, fingerprint advances only after completion, and a subsequent clean reopen does not repeat deletion. Use latches at existing IO seams, not timing sleeps. Add an admin-drop control and a reader/deposit race control. Assert listener/accounting behavior for rows actually deleted remains unchanged.

**Completion:** original shutdown probe green; all affected store lifecycle tests green; failure/cancellation outcomes distinguish incomplete work from a completed empty dimension. Port the same common-source change to all five lines and confirm no schema/native-format edits.

<a id="wi-2"></a>

## WI-2 — bind asynchronous reads and generation to their originating session

Evidence S1 in 02-server, extended by the confirmed generation case in 09-paper. Primary AbstractChunkDiskReader.addResult:807–810; normal reconnect replaces queue before old read completes. Old dirty taint is removed based on the assumption the result cannot return.

Capture a session-specific sink/identity at admission, carry it through normal/parked/store/header/error/refusal outcomes, and prevent an old operation from appending to a new registration's queue. Keep legitimate same-session ghost results and dedup fan-out behavior. The Paper lens 09 also reproduced buffered generation crossing this boundary: a real admitted generation and dirty mark, held processor, lossless old ready snapshot, then same-UUID/dimension registration yields old data and a done bit in the fresh state. Carry session identity through generation tickets, per-player callbacks and ready records, and validate before consuming tracking/removing pending or delivering/stamping/depositing. Merely clearing Paper mainReady cannot fix an old result already buffered in the common mailbox. Audit dedup attachments as the remaining related boundary.

Acceptance: two supplied real-worker regressions plus the buffered-generation regression; permanent-generation-failure, shared-load/two-player and adapter-ready-list variants; parked old work; late old error/not-found; ordinary replacement registration; dirty-overtaken reads; dedup primary departure/fan-out; all loader removal paths. Assert stale bytes are not delivered, new state is not marked done, and no false up_to_date/store stamp is produced. Run affected common/Fabric and Paper suites.

### Implementation steps

**Files:** `common/.../processing/AbstractChunkDiskReader.java`, `OffThreadProcessor.java`, `TickSnapshot.java`, relevant read-result/ticket/state/dedup types; xplat and Paper request-processing services and generation adapters. Tests: `AbstractChunkDiskReaderTest`, `OffThreadProcessorDiskResultTest`, `OffThreadProcessorLifecycleTest`, mailbox/store tests, `PaperOffThreadProcessorTest` and generation/two-player gametests.

**WI-2a — reader ownership.** Introduce an opaque, immutable registration identity with a result queue/sink owned by that registration. A new lifecycle registration for the same UUID always creates a different identity; an idempotent repeat within the same active registration must retain its identity. Capture the sink at submission before scheduling work; carry it through executor closures, `ParkedRead`, store-hit/header/all-air paths, pool rejection, gate overflow, timeout, not-found and error results. Never look up the current UUID queue at completion to choose where an old result belongs. Removal retires the old sink; a result racing retirement may at most reach its detached queue and can never become drainable by the replacement registration. If retired results are discarded early, retain exactly-once permit/in-flight accounting in finally paths.

**WI-2b — generation ownership.** Carry the same registration identity through `GenerationTicketRequest`, platform request/callback state and `TickSnapshot.GenerationReadyData`. Capture it when the ticket is admitted, including fast paths that bypass the disk reader. Validate queued ticket ownership before platform submission or rejection feedback, and identity-scope deferred removal events so they cannot sweep fresh-generation tracking. Preserve world-scoped clearMiss for completed generation outcomes even when the originating player is gone; this is distinct from stale per-registration mutation. Preserve it through already completed Paper ready lists and the common lossless mailbox; latest-wins snapshots must not erase identity. In `processGenerationReady`, reject mismatches before removing pending requests, consuming generation tracking, applying dirty state, delivering bytes or depositing/stamping the store. Retirement must unwind resources owned by the old operation, not touch the new session's counters or tickets. Cover error and permanent-failure outcomes as well as successful payloads.

**WI-2c — shared work audit.** Separate registration ownership from the lifetime of a shared chunk load. Preserve the accepted primary-departure behavior: release attached followers' pending slots so their re-declarations converge, unless an actual shared result sink is introduced. Do not leave followers parked behind a retired primary queue. A replacement UUID must not inherit the old attachment. Give each attached recipient its own admission identity. Preserve shared dirty-taint semantics and existing same-session ghost delivery: an origin that still belongs to the active registration is not stale merely because a request declaration changed. Record the admission, attach, completion and removal order for each dedup path before deciding whether to retain the shared read or resubmit it for valid followers.

**Required interleavings:** block a real read, remove/re-register, release; repeat with parked read and each terminal result category. Block a real admitted generation before snapshot consumption, dirty the column, replace registration, release. Include replacement into the same dimension and a different dimension; a surviving second player; primary departure; same-session ghost control; an old error arriving after a new same-position request. Assert both negative effects (no stale bytes, done bit, false up_to_date or deposit) and positive convergence of valid requests. Use wall-clock-bounded latch waits and always release worker barriers in finally.

**Completion:** all three supplied methods green; new adapter and fan-out controls green; applicable Fabric and NeoForge generation smoke plus Paper adapter coverage. A final search of result/ticket constructors must find no production producer that silently supplies a current or default identity. No protocol field is added: registration identity is process-local.

<a id="wi-3"></a>

## WI-3 — make the master receive toggle a real session transition

Evidence C1 in 04-client. ClientNetGlue.onEndClientTick:449+ ticks manager on serverEnabled alone; processor discards while local receive is OFF; SaveHook only saves; initial OFF join never arms handshake.

Define one shared apply transition usable by modern/legacy menus and reload paths. OFF must stop new declarations, withdraw outstanding wants as needed, and retire queued decode/retry work without charging intentional disable as failed ingestion. ON must establish/re-establish handshake and resume useful acquisition, including OFF-at-join. Preserve genuinely consumer-accepted cache proofs and far-player privacy preferences according to their independent capability contract. Preserve CL-044 receipt honesty: a provisional stamp written by onReceived before consumer acceptance must be revoked, or excluded from persisted warm-cache proof, when OFF cancels that delivery. This cancellation must not consume the real-ingestion retry budget. Simply suppressing failure reports would leave dishonest receipt stamps.

Acceptance: existing session ON→OFF→ON and OFF-at-join→ON; no nonempty asks while OFF; no artificial ingest-parking; no stale decode crossing resumed session; both Sodium generations × both 1.21.1 loaders. Add separate controls proving cancelled provisional receipts cannot become warm-cache proofs and already consumer-accepted proofs survive the toggle. Keep honest real-consumer failure retries. Do not merely add a processor discard guard; that guard already exists.

### Implementation steps

**Files:** `xplat/.../networking/client/{ClientNetGlue,ClientSessionGate,ClientColumnProcessor,LodRequestManager,ColumnStateMap}.java`, `config/menu/SaveHook.java`, option catalog/storage adapters only where needed. Tests: `ClientSessionGateTest`, processor and manager tests, `SaveHookContractTest`, catalog and modern/legacy page tests.

1. Add one idempotent client-thread reconciliation entry point for the *effective* `receiveServerLods` setting. Invoke it from Apply/save and the existing client tick so supported reload/direct-setting paths converge too. Track the last applied value separately from a UI's staged value. Saving unchanged settings must not restart acquisition; Cancel must have no effect. Preserve the legacy page's once-per-storage save contract and far-player preference push behavior.
2. OFF: close the acquisition admission gate first. Stop manager scan/declarations and prevent queued handshake completion from creating an active receiver while disabled. Use the existing dialect-appropriate withdrawal mechanism for outstanding wants; keep other negotiated capabilities and privacy preferences intact. Invalidate the acquisition generation before draining decode/ingest queues, so callbacks already running cannot mutate a resumed manager.
3. Model intentional retirement separately from real consumer failure. Lost authoritative CLEAR deliveries need the existing clearedResync rollback to the pre-clear positive stamp: unconditional unstamp-to--1 can prevent the client from ever receiving its clearing payload again. Retire and reconcile receipts before saving the old cache or loading the next manager. A queued delivery carrying a provisional receipt stamp must retract that exact delivery's proof, but must not increase failure/retry counters. Match by delivery/session ownership so an old cancellation cannot remove a later accepted proof for the same coordinate. Preserve real consumer rejection/exception handling and its cap. Document when receipts become accepted, including supported asynchronous consumer-failure reporting. Internal dispatch and Xaero deferred work must carry a captured owner/receipt handle. The existing public tokenless LSSApi.reportIngestFailure API is retained for compatibility; an arbitrary detached third-party callback using that API cannot identify its old delivery after ON, and is outside the new ownership guarantee. A synchronous ThreadLocal is not a solution for detached consumers.
4. ON: reuse accepted cache data only under the existing server/world/dimension key rules, initialize useful acquisition state, and invoke the established handshake/capability path when no session was armed at join. Do not manufacture a server-enabled state locally. Respect delayed handshake, legacy fallback and service permission denial. Do not expand the separately documented Xaero-only capability-toggle limitation as incidental scope.

**State cases:** enabled-at-join → OFF → ON; disabled-at-join → ON; OFF while handshake reply is delayed; OFF during queued decode; OFF while a consumer callback is already executing; rapid OFF/ON/OFF; actual disconnect while disabled. For every case assert no nonempty declarations while OFF, no artificial retry exhaustion, no unaccepted persisted stamp, and eventual resumption only after legitimate negotiation. Include accepted-cache and actual-consumer-failure controls. Verify old callbacks cannot erase new proof after ON.

**Completion:** the two diagnostic tests plus CL-044's honesty invariant are green; modern and legacy Apply invoke the same transition on Fabric and NeoForge 1.21.1. Rejoin/reset/summary and WI-5 composition tests pass. No far-player opt-out is revoked by switching LOD reception off.

<a id="wi-4"></a>

## WI-4 — retract summary-owned proofs when newer summary has no proof

Evidence FRESH-1 in 05-freshness. LodRequestManager.applySummary:872–888 skips sentinel tiles before revocation. Add an operation that revokes only summaryValidated-owned marks, recomputes needs, and reopens affected scanner rings. Preserve per-column received/up_to_date proof, stamps, dirty/retry bits, and sentinel diagnostics. Never validate against the sentinel value.

Acceptance: both supplied stale-numeric→sentinel tests; per-column proof controls; empty tile does not gain leaves; hybrid needs and legacy confirmed-ring redeclaration. Compose retained old-A frame, edit while B, and new-A NEVER_CLEAN using service clock/source seams. Current evidence proves state transition; that complete gameplay chronology has not been run live.

### Implementation steps

**Files:** `xplat/.../networking/client/ColumnStateMap.java`, `LodRequestManager.java`, and scanner/leaf bookkeeping only where the existing invalidation API requires it. Tests: `ColumnStateMapTest`, `LodRequestManagerSummaryTest`, manager/scanner reference-model suites.

1. Add a narrowly named state operation for loss of summary proof. It must operate only on materialized entries whose current validation owner is the summary path. Specify its return value as whether requestability/leaf need changed, so callers can schedule work without reopening unrelated tiles.
2. In `applySummary`, handle NO_REGION and NEVER_CLEAN before the current early skip: retract older summary-owned validation and recompute affected leaf needs. Keep numeric-summary handling on its existing timestamp rules. A per-column accepted delivery or up_to_date response that superseded the summary must survive later sentinel application.
3. Route changed leaves through the existing ring/hybrid invalidation mechanisms. Do not allocate missing columns merely because a sentinel covers a large region. Do not reset dirty state, retry budgets, diagnostics or retained timestamps as a side effect. Preserve packet ordering/session guards before applying either numeric or sentinel frames.
4. Update the state-map reference model if its documented operation set changes. A model must implement the semantic rule independently rather than copy the production bit manipulation.

**Regression sequence:** numeric proof → each sentinel; numeric proof → accepted per-column proof → sentinel; repeated sentinel idempotence; untouched empty tile; multiple leaves and region boundary; dirty/retry state already present. Run the same requestability assertions with hybrid region scan and the legacy ring path. Add a composed service-clock fixture for retained A summary, edit during B, return to A with a new doubt frame; report that test separately from the already proven two-frame transition.

**Completion:** both frame probes green, legitimate per-column proofs retained, changed positions become re-requestable without a full cache clear, and no unnecessary whole-map scan/allocation is introduced.

<a id="wi-5"></a>

## WI-5 — give Xaero debt and deferred reports an origin session

Evidence X1 in 06-xaero. shedToOwed:1048 can run after teardown cleared queues; releaseOwed:1267–1275 mutates a detached record after outside-lock probes.

Carry an origin generation through extraction, overflow/eviction classification, debt creation/release and deferred reports. Advance it before teardown clears; validate under the relevant lock and again at publication. A current sessionActive boolean is insufficient after reconnect. Couple set removal and gauge accounting to live record identity; reset stale awaiting classifiers. Audit where origin is captured so already-old work cannot acquire the new generation merely by starting a delayed callback.

Preserve acyclic queue/owed locks and Xaero probes outside owedLock. Preserve the newly merged shading, pending-rebuild capacity, settings ordering and native save-race behavior.

Acceptance: both supplied disconnect interleavings; count/byte overflow paths; next server uses same MC dimension; fresh debt still works; stale reports never touch new manager; gauges zero after teardown. Existing 168 Xaero tests plus live abrupt-disconnect/rejoin smoke on both 1.21.1 loaders. This review does not reproduce the user's transient vertical map lines.

### Implementation steps

**Files:** `xplat/.../compat/XaeroMapCompat.java`, client lifecycle/report routing where needed. Tests: `XaeroMapCompatTest` and the two `XaeroOwedDisconnectReviewTest` interleavings.

1. Define a bridge generation owned by the logical acquisition/session lifetime and advance it before teardown clears bridge state. Capture it at the earliest queued column/extraction origin, not at a delayed overflow callback. Propagate it through pending extraction, evictions, owed records and deferred ingest-failure reports. Same dimension names or active-state booleans do not identify a session.
2. At each mutation under queue/owed locks, verify the origin is still current. For outside-lock probes, copy the origin and record identity, release the lock, perform the Xaero call, then revalidate both before removing debt or adjusting counters. A detached record cannot decrement the live gauge. Prefer accounting changes conditioned on successful removal of the exact current record; do not clamp a negative count to conceal a stale mutation.
3. Make report publication target the captured manager/acquisition owner. Checking the epoch and then looking up a global current manager still leaves a check/use race. Dispatch on the owning thread with a final owner check, or send to an immutable old sink that cannot resolve into the new manager. Keep Xaero calls outside owedLock and avoid introducing an inverse queue/owed lock order.
4. Reset generation-owned awaiting-classifier state on teardown. Establish how WI-3's OFF transition retires bridge work even if the underlying network connection stays open. Distinguish acquisition retirement from native-world teardown: preserve already committed pixels' pending rebuilds on receive OFF. Full onSessionEnd drops pendingUpdates under the old-world-is-gone assumption, which is false for a local toggle; do not reuse that entire teardown blindly. New accepted columns after ON must get the new generation and remain ingestible.

**Regression sequence:** stop after overflow selection but before shedding, disconnect, reconnect to another server with the same dimension, release; stop after owed extraction but before its probe returns, disconnect, release; add a replacement live record at the same coordinate; repeat for byte/count overflow and OFF/ON. Assert old reports never reach the replacement manager, gauges equal actual live membership, and new-session debt still releases normally. Add a reentrant probe control to protect outside-lock behavior.

**Completion:** the two diagnostic methods and the existing Xaero suite are green; no lock-order change without an explicit interleaving test; live abrupt-disconnect/rejoin smoke on both 1.21.1 loaders. Keep the previous shading, edge rebuild and save-race repairs intact. Visual seams remain a separately unproven symptom.

<a id="wi-6"></a>

## WI-6 — withdraw far-player rosters when server mode becomes off

Evidence FP0 in 07-farplayers and configuration corroboration 11. Both platform wrappers and common broadcaster return before sending removals.

Introduce explicit on→off withdrawal before those guards. Retain unsent withdrawal on an unwritable connection and retry while off without rebuilding target snapshots. Preserve subscriptions/privacy preferences. On off→on force fresh roster and initial updates, including stationary targets. Do not add generic inactivity expiry: stationary delta suppression deliberately permits silence.

Acceptance: actual broadcaster→wire→tracker on/off/on; withheld-clear retry; rapid OFF→ON while a clear is withheld, with honest membership epochs; multiple viewers; stationary targets; both platform disabled wrappers still drain clear; user command smoke on Fabric/NeoForge/Paper.

### Implementation steps

**Files:** `common/.../farplayers/FarPlayerBroadcastService.java`, xplat `RequestProcessingService.tickFarPlayers` and Paper's equivalent; broadcaster/tracker and platform wiring tests.

1. Represent withdrawal as broadcaster state, with pending clears per viewer. Detect effective ON→OFF on the owning tick/pump even when no ordinary broadcast cadence is due. Platform wrappers must continue a lightweight control drain while disabled; they should avoid expensive target snapshots while still permitting pending withdrawals.
2. Use the existing full-roster replacement/epoch mechanism to clear published state. Server-control withdrawal bypasses the ordinary preference-triggered full-roster throttle and broadcast cadence; keep that bypass separate from client-triggerable fullRosterPending and preserve its flood bound. Prepare a fresh epoch consistently with current protocol rules. Mark withdrawal delivered only when the sender accepts the frame; retry a declined/unwritable send without pretending the viewer has forgotten its old roster.
3. While OFF, retain subscription and privacy preference state but do not produce target updates. On ON, arrange a fresh full roster and first state update even when every target is stationary. Keep full-roster ordering ahead of updates on the connection.
4. For OFF→ON before a clear succeeds, either complete the clear before repopulating or supersede it with a newer full replacement roster. Prefer the latter when the existing sender is ordered: invalidate the pending clear and issue a new epoch/full roster, never allow the obsolete clear to be sent after the new roster. Test the selected ordering with a sender that declines and later accepts frames.

**Regression sequence:** broadcaster → encoded wire → real tracker; ON stationary silence keeps one target; OFF removes it; ON restores it. Repeat with two viewers of different writability, disconnect during pending withdrawal, rapid off/on and a permission/private-target change. Assert no fresh position updates while OFF and correct first update after ON.

**Completion:** disabled wrappers demonstrably drain control traffic on xplat and Paper; no inactivity TTL is introduced; command-driven live toggle clears and restores proxies on supported clients.

<a id="wi-7"></a>

## WI-7 — verify legacy row integrity before migration

Evidence ST-02 in 03-storage-parent. Migration SELECT:1262–1274 omits checksums, then translation:1326–1335 writes fresh ones. Include legacy hashes and verify with existing wirefmt19 FNV semantics before translation/re-hash. Verify declared size and raw length with the existing bounded read rules as well; preserve the all-air bypass before ordinary payload checks.

Acceptance: decodable row with bad content hash; bad frame hash; valid row; existing all-air special handling; per-row anomaly deletion; transaction/watermark retry and interrupted resume. The external reproduction uses the existing opaque-translator seam and proves store integrity policy, not palette fidelity. Preserve real-translator corpus tests separately.

### Implementation steps

**Files:** `common/.../store/SqliteLodStore.java`; `SqliteLodStoreMigrationTest` and existing legacy read/hash fixtures.

1. Extend migration's local row record and SELECT with stored `chash` and `fhash`. Keep its bounded batch and position watermark unchanged. The row's legacy wire format determines validation hash semantics; do not use the current CRC32C helper to validate a legacy FNV row.
2. Preserve the existing `usize == 0` all-air retag before body/hash work. For non-air rows, check size bounds, frame checksum and the same declared-content-size rules used by the normal framed reader before allocating/decompressing. Then verify actual raw length and the legacy content checksum before invoking the translator.
3. Only validated input may be translated, compressed, assigned current-format checksums and tagged V20. Reuse narrow existing validation/hash helpers when possible, without coupling migration to a reader connection or triggering its external side effects. Keep per-row anomaly deletion, catch/forward-progress semantics, watermark updates and transaction boundaries intact.
4. Preserve translation fidelity as a separate obligation: the opaque checksum fixture is a legitimate integrity test, while actual native-to-V20 corpus tests remain the authority for block/palette correctness.

**Regression matrix:** bad content hash with valid frame; bad frame hash with valid raw payload; inconsistent declared size; raw-length mismatch; out-of-bounds usize; valid ordinary row; all-air sentinel; translator failure; anomaly followed by valid row; shutdown/reopen mid-migration. Assert the translator is not invoked for rejected integrity rows and valid rows still advance migration. Keep the normal-reader rejection control for the same corrupt fixture.

**Completion:** both supplied checksum methods green and existing migration/resume/corpus tests green on each line's native flavor. No new full-store rebuild, hash algorithm change or schema bump.

<a id="wi-8"></a>

## WI-8 — keep roster identity mappings consistent and bounded

Evidence FP1 in 07-farplayers. Define occupied-index replacement behavior: reject/reset until full roster, or replace and retire all state for displaced identities no longer bound. Define duplicate-UUID aliases as well. Bound retained identities, not only index count.

Acceptance: supplied two decoded-wire tests; rename/sameUUID; remove→reuse; duplicate aliases; full rebuild/stale epoch. No renderer needed to prove this. This is robustness for inconsistent peer frames; it is not an observed stock-server gameplay sequence.

### Implementation steps

**Files:** `common/.../farplayers/FarPlayerClientTracker.java`; `FarPlayerClientTrackerTest`.

Choose deterministic replacement cleanup, which preserves recovery without requiring a new wire resync message. Maintain a one-to-one UUID/index relationship, using a bounded reverse map or a bounded lookup helper. On occupied-index replacement, retire the displaced UUID's name, motion and equipment state if it has no surviving binding. On a UUID arriving at a different index, retire its old index before accepting the new one. Same UUID at the same index may update its name without resetting motion unnecessarily. Preserve current added/removed frame ordering and stale-epoch handling.

After each accepted roster mutation, `tracked` and `nameByUuid` must be subsets of live UUID bindings. Bound every retained identity map by `MAX_TRACKED_IDENTITIES`; a reverse map is subject to the same clear/reset lifecycle. Keep the existing full-clear/cap-reset behavior as a final guard, with no wire changes or periodic expiry. A removed obsolete index must not delete the UUID's current new binding.

**Regression matrix:** the two supplied decoded-wire methods; repeated replacement of one index; UUID moved to a second index then old-index removal; same-binding rename; legitimate remove/reuse; full roster reset; stale epoch; cap boundary and boundary+1; retained equipment/motion after an ordinary incremental update. Assert all maps remain bounded and no drawable identity lacks a binding. Test sequences through decoded frames, not only direct map manipulation.

**Completion:** inconsistent-peer cases cannot accumulate ghosts, while a stock broadcaster's normal sequence produces identical tracking results. Document the chosen duplicate-UUID rule in the tracker javadoc; do not describe this as a stock-server exploit or ordinary gameplay failure.

<a id="wi-9"></a>

## WI-9 — restore pass pose after a contained seated render error

Evidence FP2 in 07-farplayers. Add restorePose at both 1.21.1 seated renderer catches before continuing, consistent with mount/unseated recovery. Preserve outer-finally cleanup and mount-type latching.

Acceptance: a renderer that pushes/translates then throws; next proxy/tag sees original pass pose; outer stack remains balanced; both loaders. Prefer a behavioral seam over merely increasing a source substring count. Cross-line lens 14 confirms only the two 1.21.1 twins are affected: the four other Fabric lines already restore the pose and their NeoForge renderers are intentional stubs. Do not transplant immediate-mode code to submit/extract renderers.

### Implementation steps

**Files:** only the Fabric and NeoForge 1.21.1 `networking/client/FarPlayerRenderer.java` twins, plus a focused render-recovery fixture and relevant contract tests.

At each seated-rider catch, restore the pose to the saved per-pass sentinel before recording/latching the seated failure and continuing. Follow the stronger existing 26.x order: restore first, then failure reporting. Retain the outer finally, mount-type suppression and buffer ownership semantics; do not copy a deferred submit/extract renderer into the immediate-mode line.

Use a minimal injectable draw operation or existing render seam to model the actual failure: push a pose, apply a visible transform, then throw. Observe the next rider/tag draw's transform and final stack depth. An assertion that only counts `restorePose` text is insufficient. Cover seated failure as well as the already-correct mount/unseated paths so helper extraction cannot regress them. Keep this fixture loader-independent where possible, with both twins wired to the verified behavior.

**Completion:** both twins restore the next draw's transform and overall stack; one controlled live seated-failure check records continued rendering without contamination. Other four Fabric lines are audited controls, not port targets. Their NeoForge stubs remain unchanged.

<a id="wi-10"></a>

## WI-10 — distinguish runtime success from persistence failure

Evidence CFG-1 in 11-config. JsonConfig.save's nonfatal behavior is deliberate. Add an observable outcome for callers that need feedback while preserving existing startup/UI failure containment. Commands should say applied but not saved, with actionable cause, instead of implying persistence. Do not rollback unrelated runtime state or make all save callers throw.

Acceptance: blocked config path/disk write failure, runtime value actually applied, previous file intact, accurate feedback, normal success and clamp messages unchanged, both command families/brands.

### Implementation steps

**Files:** `common/.../config/JsonConfig.java`, `RuntimeSettings.java`; xplat `networking/server/LSSServerCommands.java`, Paper `PaperCommands.java`; config and command tests.

Add a nonthrowing outcome-returning save entry point, for example `trySave()`, and keep `save()` as a compatibility wrapper that delegates and ignores the result. Preserve the existing temporary-file/replace algorithm and logging. Represent success versus failure explicitly; avoid leaking raw exception text to user output. Return an actionable log reference and effective config path where available.

Carry persistence outcome alongside the validated effective value and application note through the runtime-settings operation. Malformed parse failure still means no runtime mutation; preserve the existing apply-then-clamp validation order rather than introducing rollback for arbitrary validator exceptions. Persistence failure means runtime mutation succeeded, and any required runtime reapplication still occurs. Both command adapters should say “Applied …, but could not save; see server log.” Preserve branded command names, permission checks, clamping and successful feedback. Decide command integer return semantics consistently with existing runtime-success contracts and pin that behavior; do not accidentally skip reapplication because persistence failed.

**Regression:** seed a valid config, obstruct its sibling temporary path with a directory, apply a setting, then verify changed live state, unchanged original config file, nonfatal operation and explicit feedback. This works under root unlike permission-bit-only tests. Remove obstruction and verify persistence and normal feedback. Cover both command families, LSS/VSS naming, adopted config path and existing direct-save nonfatal pins.

**Completion:** no startup/UI save caller begins throwing; the interactive caller accurately distinguishes applied-and-saved from applied-but-unsaved. No rollback of a successfully applied runtime value.

## Port and validation discipline

Implement common/shared fixes once then port literally to all five lines, preserving per-line Java, mapping names, native count/prefix flavors, world-height/light APIs and rendering architecture. Wire compatibility remains full-fidelity on every tier. MC 1.21.1 has no Folia and its Tier 3 client gametests are deliberately cut. NeoForge shipping choices and modern Sodium generation cuts are not review defects.

At implementation time: first add failing regressions to existing suites, then fix without weakening pinned behavior. Run focused tests per work package, full 1.21.1 Fabric/Paper/NeoForge unit/build gates, relevant server gametests for server lifecycle fixes, and cross-line Fabric+Paper+NeoForge builds/tests with correct JDK. Run release_check on full LSS/VSS artifact sets. Require client live gates for runtime toggles, seated-render failure and Xaero reconnect; tests without graphics must not be advertised as live validation.

Do not change released wire layouts, regenerate cross-version captured corpus, merge support branches into main, or change soak floors to hide regressions. Add native fixtures only under the existing line-specific golden discipline. Keep user-visible fix packages small and reviewable, with source parity checks after each port.

<a id="wi-11"></a>

## WI-11 — bind disguise API to current plugin loader

Evidence PRIV1 in 08-permissions. Single-plugin replacement while LSS stays loaded can resolve the previous plugin's static registry despite the new plugin-instance token. Resolve through the enabled current plugin's defining loader, bind handle lifetime to that identity, and keep existing disabled/absent/drift/throw semantics. Preserve deferred class initialization.

Acceptance: actual production bridge/default resolver with two API classes/loaders and opposite disguise answers (supplied probe RED); new final fixture must create plugin instances defined by their respective loaders, rather than using loader objects as identity tokens. Add same-instance fast path and disabled/throw controls. No live LibsDisguises/PlugMan operation was tested. This conditional supported-claim repair is lower priority than ordinary-session failures; regular restart and all-plugin reload replace LSS too and are not this bug.

### Implementation steps

**Files:** Paper `LibsDisguisesBridge.java` and `LibsDisguisesBridgeTest` on all five lines.

Pass the current enabled plugin instance, or its defining classloader, into API resolution rather than default `Class.forName` on LSS's surviving loader. Resolve the API with initialization disabled and the plugin's actual loader; validate the expected method signature before caching. Cache plugin identity, classloader and resolved handle as one coherent binding. On replacement/disable, invalidate the entire binding; preserve same-instance fast path, absent-plugin behavior, negative-cache/retry rules and current exception containment policy.

Strengthen the external regression by loading actual fixture plugin-instance classes through two separate defining loaders. Each loader must define its own API class and static registry with opposite answers. Load the production bridge once through a surviving LSS-side loader, switch the plugin provider to the second actual instance, and verify the second answer. Using loader objects themselves as plugin identity tokens would not test the new defining-loader implementation faithfully.

Add disabled plugin, same-instance repeat, missing method and throwing API controls. Ensure replacing the binding releases strong references to the old instance/handle where the bridge owns them; do not claim complete JVM classloader collection, which also depends on the plugin/server.

**Completion:** fixture exercises the production default resolver and hides the newly disguised target after replacement. Port literally across Paper lines. Single-plugin live reload remains a conditional integration smoke; a successful full-server restart is not a substitute for this trigger.

<a id="wi-12"></a>

## WI-12 — recognize the deployed Voxy holder/method combination

Evidence compatibility lens 10. A verified 1.21.1 Voxy 0.2.15-beta artifact exposes IGetVoxyRenderSystem.voxy$shutdownRenderer, while LSS tries the namespaced method only on IVoxyRenderSystemHolder and the plain method on IGetVoxyRenderSystem. Add the valid old-interface/new-method pairing to the reset-domain ladder without changing successful existing rungs. Resolve signatures exactly, preserve domain failure isolation and renderer-carrier behavior.

Acceptance: supplied resolution probe RED; current and legacy supported combinations stay green; actual artifact member resolution; live reset/forced storage probe on the matching 1.21.1 rig when implementing. Ingest/storage/backlog signatures were separately checked and are not claimed broken by this result. Confirm actual installed MC/loader metadata rather than relying on jar filenames or unrelated profiles.

### Implementation steps

**Files:** `xplat/.../compat/VoxyCompat.java`, `VoxyCompatTest`, reset-domain fixtures/stubs. The holder is the actual level renderer; do not redirect lookup to the Voxy engine singleton.

Add the exact `IGetVoxyRenderSystem` plus `voxy$shutdownRenderer` pairing to the bounded reset-resolution ladder. Preserve precedence of already working modern combinations. Keep class/method names and descriptors explicit; do not replace the ladder with arbitrary method-name scanning or invoke every candidate. Resolution and invocation must retain reset-domain failure isolation from ingestion/storage/backlog domains.

Extend the fixture matrix to cover modern-holder/namespaced method, old-holder/plain method and old-holder/namespaced method. Invoke each resolved method on the expected carrier and assert exactly one call; resolution-only success is not sufficient. Cover missing interface, descriptor drift and invocation throw. Verify ResetCoordinator's existing drain → Voxy reset → LSS flush → far-player resubscribe order, including failure outcomes.

Reinspect the actual matching 1.21.1 artifact's embedded metadata and descriptors before its live gate. Use the verified far-testing profile or an isolated clone with the same resolved mods; record jar hashes and logs proving the intended loader/mod loaded. A jar's filename is not validation. The shared helper ports to all lines, but the demonstrated compatibility gap remains specific to that inspected artifact.

**Completion:** three shape/invocation fixtures green, existing optional-domain controls green, and live reset/forced storage probe succeeds on the matching rig. No ingestion regression is inferred or fixed without separate evidence.

<a id="wi-13"></a>

## WI-13 — make harness refusal and result freshness reliable

VALID-1, lens 12: soak.sh removes/stages shared world and cache and truncates logs/results before its occupied-port refusal (1.21.1 world delete 425, port guard 598). A second/stale soak using the same run directory can have its live fixture altered before the new invocation refuses. Acquire a resource lock and check conflicting server/port ownership before build or any shared mutation. Cover shared client scratch and cross-worktree use of the same port. Design inherited/reentrant ownership for all/auto-prime and multi-phase wrappers so a valid recursion does not deadlock or bypass exclusion. The regular test server uses a different directory and port 25564; it was not implicated or altered.

Acceptance: occupied-port and concurrent-invocation fixture tests show zero world/cache/log/config mutations by the loser; correct lock release; all/auto-prime/multi-phase behavior. Use temporary fake processes/files, never run the destructive script against a live world to prove this. Parent independently verified ordering on all five scripts.

VALID-2, lens 12: direct benchmark.sh removes current source artifacts but not prior destination JSON/JFR. It swallows server wait failure and only warns when exports are absent, then can print old destination metrics under Benchmark Complete and exit 0. Comparison/store-gate wrappers clear destinations themselves and do not establish this direct-CLI guarantee.

Clear or isolate each cycle's destination artifacts before starting; attach run identity; require current expected exports and propagate unsuccessful/incomplete run status. Publish/reuse fresh base worlds only after valid completion. Preserve useful partial logs and distinguish optional JFR from required metric outputs.

Acceptance: successful run followed by failed/missing-export run cannot reuse old metrics; timeout/nonzero server exit produces nonzero result; warm-join populate/measure cycles have distinct identities; wrappers remain correct. Parent source/control-flow validated, no live benchmark was launched.

### Implementation steps

**Files:** `scripts/soak.sh`, `scripts/benchmark.sh`, their wrappers and `scripts/lib/mc-run.sh` as required; add a focused isolated shell/Python harness test under `scripts/` using the repository's test conventions.

**WI-13a — exclusive ownership before mutation.** Enumerate canonical resources first: server run directory/world, client scratch/cache, result/log directories and listener port across worktrees. Acquire locks outside those disposable directories before build, staging, cache removal, log truncation or cleanup traps capable of mutation. Use a stable per-user lock root and canonical-path/port keys with a fixed acquisition order. `flock` descriptor ownership is preferable to PID files. Check an already occupied port after acquiring the locks and before mutation; retain the actual bind/startup check because an unrelated program does not honor harness locks.

Make `all`, auto-prime and multi-phase wrappers hold ownership across their complete transaction, including any wrapper-side config/result staging. Children may reuse a verified inherited descriptor; an environment boolean alone must not bypass locking. Do not unlink a locked file on exit, which permits another process to lock a different inode. Ensure cleanup targets only processes launched by this invocation and never kills an unrelated port owner. If a descendant still uses the world, ownership must outlive the parent until that descendant is stopped. Existing PID variables identify Gradle launchers, not necessarily the game JVM: establish owned process groups/descendant cleanup and reap them explicitly. Do not assume a long-lived Gradle daemon preserves or releases inherited lock descriptors correctly; test the launcher/daemon lifetime model. Recheck the resource graph for benchmark/soak overlap rather than assuming the two tools' ports guarantee isolation.

**Tests:** execute the actual scripts with controlled dependency/path seams and fake launchers in temporary directories. Snapshot sentinel world/cache/config/log bytes and mtimes. An occupied-port invocation and the losing member of two concurrent invocations must fail before any shared fixture mutation. Cover cross-worktree same-port contention, wrapper recursion, inherited ownership, abnormal exit and lock reuse after cleanup. The tests must never invoke the real launcher or target the normal test server.

**WI-13b — result provenance and failure propagation.** Create a unique run/cycle staging directory and manifest before launch, with commit, scenario, timestamps and populate/measure identity. Clear current source exports; copy only artifacts created for this cycle. Capture server/client completion statuses before cleanup overwrites shell `$?`. Missing required JSON, malformed metrics, timeout or unexpected nonzero exit makes the cycle incomplete and the command nonzero. Optional JFR absence is a separately recorded condition, not a substitute for required JSON.

Publish current result aliases only after successful validation. On failure, remove/mark invalid the aliases a direct invocation might otherwise print as current, while retaining prior successful runs under their own IDs. The summary must read the validated current manifest, never whatever JSON happens to exist at the old path. Publish a fresh benchmark base world only after a successful fresh cycle; warm-join population must succeed before measurement starts. Preserve wrapper-staged configs and update comparison/store-gate consumers to consume the validated result identity without changing their workload assumptions.

**Tests:** success then failed run in the same destination; missing one export; malformed JSON; server failure with old valid JSON present; timeout; optional JFR absent; failed populate; failed measure after successful populate; failed fresh cycle leaves prior valid base untouched. Assert nonzero status and no “Complete”/old-current summary on incomplete runs. Syntax-check shell scripts and run checker selftests after changes.

**Completion:** both harness defects have executable isolated regressions, wrappers share the same ownership/provenance contract, and a subsequent genuine smoke produces a valid current-run result. Do not adjust checker floors or scenario timing to obtain a pass.

<a id="wi-14"></a>

## WI-14 — make validation routes and operational instructions explicit

Before new performance/soak validation, complete WI-13. Product correctness packages WI-1 through 7 take priority; then the ordinary render-port defect WI-15 and conditional integration/robustness WI-12, WI-8 and WI-11; render/error-feedback/matrix improvements WI-9, WI-10 and WI-14 follow. Each package gets its own regression/port review rather than one broad rewrite.

### Implementation steps

**Files:** 1.21.11 `test-server.sh` and its C2ME Gradle pin; affected `run-neoforge` launch guidance; `docs/planning/per-version-surfaces.md` and a current rig/validation inventory. Locate actual script paths before editing; preserve historical dated release records.

1. Inventory every proposed live-test profile by absolute instance path, `mmc-pack.json` component versions, internal mod metadata, enabled/disabled filename state, artifact hash and launch-log confirmation. Assign an explicit purpose: Xaero-only, Voxy integration, far-player animation or loader/Sodium combination. Record missing components as coverage gaps, not product defects. Prefer isolated clones for fault injection; do not silently change the user's general-purpose profile.
2. For C2ME 1.21.11, retain the existing two pins as explicitly named validation configurations initially: regular-map alpha.0.18 and benchmark alpha.0.26. Record exact artifact IDs/hashes and test both relevant paths. Consolidate only after compatibility evidence supports the choice; if consolidated, put the selected identity in one authoritative source and pin consumers to it. This closes ambiguity without an unverified automatic upgrade.
3. Correct present-tense NeoForge launcher messages against each line's actual shipping flags, renderer availability, nested-library layout and documented Voxy pairing. Distinguish “Voxy can ingest terrain” from “LSS far-player renderer available”; a renderer stub does not imply every client function is inert.
4. Keep the per-line source identity and live-profile inventory separate: a well-formed TOML or correct artifact hash does not prove a GUI page/render path ran. Link each claimed live gate to dated evidence and exact profile/commit.

**Completion:** future testers can select a valid profile without guessing; all intended C2ME configurations have an explicit rationale; current launch instructions agree with artifacts and deliberate cuts. No historical shipping decision is rewritten as if it had always been different.

### Cross-line packaging and operational follow-ups

Cross-release lens 15 inspected all 30 existing LSS/VSS jars: correct Java majors/mapping namespaces, byte-identical class content and nested jars within each brand pair, and NeoForge nested SQLite/Zstd matching stock Maven bytes. No new release-blocking mismatch. This is read-only artifact evidence, not a fresh rebuild.

REL-1 (P3): 1.21.11 test-server.sh selects C2ME alpha.0.18 while Gradle benchmark.c2me selects alpha.0.26. Choose/document one deliberate shared test pin or explicitly test both; do not assume either is incompatible or upgrade automatically. Add a consistency pin if shared selection is intended. Other line-specific shipping, Folia, Sodium, Java and namespace differences were deliberate and consistent.

Documentation cleanup: emitted run-neoforge instructions on 26.2/26.1/1.21.1 still describe an inert client/noVoxy pairing despite currently supported pairings; some historical guidance still describes flat NeoForge Zstd or obsolete shipping scope. Update present-tense operational guidance while preserving dated historical records. Paper's VSS plugin data-folder change is explicitly deliberate and must not be “fixed” as accidental adoption loss.

<a id="wi-15"></a>

## WI-15 — advance the wing animation state on the two affected ports

Evidence CROSS-RENDER-1 in 14-cross-loaders. Fabric 1.21.10 Proxy.apply:1179–1185 and 1.21.11:1175–1181 never advance elytraAnimationState. Actual Minecraft bytecode confirms the constructor leaves rotation fields at zero, LivingEntity.tick normally advances them, and the avatar/humanoid renderer copies those angles directly into the elytra model. These synthetic players never receive vanilla entity ticks. Flags and fallFlyTicks alone therefore cannot spread their wings.

Add the same once-per-animation-tick state update already used by 26.1/26.2 after applying glide/crouch inputs. Do not full-tick a synthetic player. Correct the affected source contracts and explanatory comments, which incorrectly treat flags alone as sufficient. MC 1.21.1 uses direct model angle computation and needs no such change; other NeoForge lines use intentional renderer stubs.

Acceptance: real state advances to nonzero wing angles when gliding at zero movement; a second render in the same animation tick does not advance it again; standing/crouching transitions converge correctly; existing walk/swim/glide behavior remains intact. Use a behavioral fixture or render-input helper with actual MC state, supplemented by the bytecode contract. Run an equipped-player visual smoke on both affected Fabric lines. This finding is source/bytecode validated, not visually reproduced during review.

### Implementation steps

**Files:** Fabric 1.21.10 and 1.21.11 `networking/client/FarPlayerRenderer.java`, specifically `Proxy.apply`; their `FarPlayerRenderSourceContractTest` plus a behavioral fixture. The working 26.1/26.2 tick placement is the reference, not a whole-file port source.

Set current gliding/crouching/standing inputs before advancing `elytraAnimationState`. Place the call inside the existing new-animation-tick guard with the other proxy animation updates. Do not use render-frame count as the clock, tick the whole synthetic player, invent velocity to force wing spread or change the documented yaw-banking omission. Verify the state samples the newly applied pose rather than the previous packet's pose.

Construct an actual line-native wing state/proxy fixture, or extract a narrow render-input helper that the production proxy uses. Apply gliding with zero movement on one animation tick: X/Z angles must move away from zero toward the real engine's expected pose. Apply the same tick again and assert no second interpolation step. Advance ticks, switch to standing and crouching, and verify convergence toward those poses. Supplement this behavior with the existing contract's actual invocation/tick-guard pin; a text presence check alone is insufficient.

**Completion:** both affected Fabric versions pass their own real-MC-state fixture and equipped-player live smoke. The 26.x controls remain unchanged and the 1.21.1 direct-model path gains no nonexistent/newer field. Record this as cosmetic rendering repair, with no terrain/protocol claim.

## Cross-line port and acceptance runbook

### Scope matrix

“Port” below means carry the change and its applicable regression; it does not mean the original review executed tests on that line. Re-read `.github/line.env`, `gradle.properties` and the per-version surfaces document at implementation time rather than treating dated shipping prose as a substitute for current flags.

| Work | 1.21.1 | 1.21.10 | 1.21.11 | 26.1 | 26.2 |
| --- | --- | --- | --- | --- | --- |
| WI-1–8 common/shared behavior | Implement + all applicable loaders | Port | Port | Port | Port |
| [WI-9 seated exception recovery](#wi-9) | Fabric + NeoForge repair | Existing Fabric control | Existing Fabric control | Existing Fabric control | Existing Fabric control |
| [WI-10 config feedback](#wi-10) | Shared + Paper adapters | Port | Port | Port | Port |
| [WI-11 disguise reload](#wi-11) | Paper | Paper | Paper | Paper | Paper |
| [WI-12 reset resolver](#wi-12) | Implement; verified artifact live gate | Shared resolver/control matrix | Shared resolver/control matrix | Shared resolver/control matrix | Shared resolver/control matrix |
| [WI-13 harness](#wi-13) | Implement actual-script fixtures | Port with local task/platform differences | Port with local task/platform differences | Port with local task/platform differences | Port with local task/platform differences |
| [WI-14 pins/profiles/prose](#wi-14) | Inventory and applicable prose | Inventory / intentional legacy UI | C2ME two-configuration matrix + inventory | Inventory and applicable prose | Inventory and applicable prose |
| [WI-15 elytra state](#wi-15) | Direct-model control; no repair | Fabric repair | Fabric repair | Existing tick control | Existing tick control |

Build with Java 21 on the three 1.21.x lines and the configured Java 25 toolchain on the two 26.x lines, confirming current repository settings before execution. Preserve native count/prefix flavors, loader-only API adaptations, mapping namespaces and modern/legacy Sodium availability. A common Java helper must not accidentally acquire MC or loader dependencies. For shared files that were byte-identical, compare final bytes; where a line has a deliberate adaptation, compare the scoped semantic diff and record the exception. Do not overwrite a whole adapted source file to obtain parity.

### Test execution layers

**Layer 1 — per-package regression.** Run only the permanent tests for the work item plus its closely related existing contract suites while iterating. A failure at the original invariant must become a pass without relaxing the assertion. Add permanent fixtures for source-only findings before claiming a tested fix. Review XML for exact methods and errors/skips; “BUILD SUCCESSFUL” is not enough if filters selected no tests or a task was cached.

**Layer 2 — complete 1.21.1 unit and build gates.** From the 1.21.1 implementation worktree, with its Java 21 JDK selected:

```bash
./gradlew :fabric:test :paper:test :neoforge:test --max-workers=1 --console=plain
./gradlew :fabric:build :paper:build :neoforge:build vssJars --max-workers=1 --console=plain
./gradlew :neoforge:runGameTestServer --max-workers=1 --console=plain
```

Fabric build includes Tier 2 on this line. Its Tier 3 client gametest task is deliberately absent; do not request or exclude a nonexistent task. The second command may reuse first-command unit results; report that accurately rather than counting two test executions. Confirm the expected Fabric gametest entrypoint inventory and NeoForge smoke actually ran. If a gate is UP-TO-DATE and fresh execution is required for changed fixture discovery, rerun that task deliberately and record why.

**Layer 3 — each other line.** Execute that line's complete Fabric/Paper/NeoForge unit/build tasks and LSS/VSS artifact creation with its configured JDK. Discover task availability from the build and current instructions; do not paste 1.21.1 gametest exclusions into every line. Run relevant generation/service gametests after WI-2 and line-available client gametests after WI-3–5. Preserve support-tier distinctions, but wire/native serializer and schema integrity gates are never waived based on tier. An explicitly deferred live gate remains DEFERRED with a reason, never PASS.

**Layer 4 — harness checks, then scenarios.** After WI-13's actual-script fixture suite passes:

```bash
bash -n scripts/soak.sh scripts/benchmark.sh
python3 scripts/check_soak.py --selftest
python3 scripts/release_check.py --selftest
```

Validate selected scenario/config pairs with `python3 scripts/check_soak.py --validate SCENARIO` before execution. Use the real scenario names below only where the line/platform registry supports them. Invoke store-offline and migration scenarios through their complete scripts/store_offline_edit.sh and scripts/store_migration_gate.sh wrappers so fixture ancestry and cross-phase assertions are exercised. A scenario that fails its premise is inconclusive for the product invariant until the checker passes; do not turn a prose explanation into a waiver. Apply only the specifically matching known-flake procedure in CLAUDE.md.

| Scenario family | Packages | Required observation |
| --- | --- | --- |
| `store-migration-join`, `store-offline-verify`, `store-second-join` | WI-1, WI-7 | Valid warm data still serves; migration progresses; corrupted/old-policy rows never become certified warm hits. Deterministic shutdown fixtures remain the exact WI-1 proof. |
| `generation-disabled`, `generation-capacity-stress`, plus targeted reconnect fixture | WI-2 | Admission/conservation and valid followers converge; obsolete generation cannot seal replacement state. Existing scenario greens alone do not replace the exact ownership regression. |
| `dimension-rejoin-warm`, `warm-rejoin-summary`, `dirty-while-offline-summary`, `hybrid-boundary` | WI-3–5 | Retained valid proof is useful, doubt reopens requests, dirty data converges, no old-session bridge debt. Add a dedicated toggle/reconnect timeline if existing scenarios do not exercise the trigger. |
| Paper store/generation representative smoke | WI-2, WI-7 | Platform extraction and ready-list behavior obey the same common ownership and integrity rules. |

Do not run every scenario merely because it exists. Select the above relevant gates once for the final combined changes, broaden only for a new failure or untested dependency, and give soaks the machine to themselves. No 1.21.1 Folia run exists; where other lines support Folia, run the affected lifecycle smoke on that platform and retain its experimental label. Single-player evidence does not satisfy the documented multi-region exit criterion.

### Live client acceptance

Use WI-14's verified profile inventory and record loader, MC, Sodium, Xaero/Voxy versions, LSS commit/jar hash, server platform and date. For disruptive error injection or plugin reload use an isolated fixture/profile/world. The regular real-map server is not a disposable soak directory.

| Gate | Procedure | Pass criterion |
| --- | --- | --- |
| Receive toggle | Join ON, establish traffic, Apply OFF, wait for in-flight retirement, Apply ON; repeat joining OFF. Repeat modern/legacy Sodium on both 1.21.1 loaders where supported. | OFF produces no new nonempty wants; accepted terrain remains; ON converges without rejoin or exhausted retries; privacy preference unchanged. |
| Xaero teardown | Build a visible pending/debt backlog, disconnect during work, join a second fixture server with the same dimension, then repeat via receive OFF/ON. | Old debt/reports never affect the new manager; live gauges match queues; new map work still progresses. Capture any vertical-line symptom separately. |
| Far-player mode | Stationary remote player beyond vanilla range; server mode OFF then ON; repeat temporary unwritability in a controlled fixture. | OFF removes the proxy; ON restores its roster/initial pose; late clear cannot remove the newly restored proxy. |
| Seated exception | 1.21.1 Fabric and NeoForge fixture injects an exception after pose push during a seated draw. | Next proxy/tag has normal transform; pass remains balanced and rendering continues. |
| Elytra | 1.21.10 and 1.21.11 Fabric equipped target cycles stand/crouch/glide across animation ticks. | Wings follow the real pose; no per-frame over-advancement or walk/swim regression. |
| Voxy reset | Verified matching 1.21.1 port, established storage path; execute the supported reset/forced probe sequence in a fixture. | Correct holder method invoked and reset-domain outcome reported; normal ingestion resumes. |
| Disguise replacement | Optional plugin replacement while the same LSS instance survives, on a controlled Paper setup supporting that lifecycle. | Newly disguised target uses the new registry; full restart is not accepted as the same test. |

Where automation cannot supply the required client interaction, record the exact remaining manual procedure, expected result and profile. Do not mark a work item fully runtime-validated from a unit stub or request implementation approval as if all remaining gates were already green.

### Artifact and release-readiness gates

Build the final LSS and VSS jars from each line's exact candidate commit. Use the repository's CI-style versioned naming and the intended version supplied at implementation/release time; no release version or tag is chosen by this plan. Run `python3 scripts/release_check.py --version VERSION` against that exact artifact set, including all locally required brand/loader variants. Resolve ambiguous stale jars rather than relying on a checker selecting the intended one accidentally.

Record Java major, mapping namespace, metadata loader/MC ranges, shipping flags, nested stock-library identity, LSS/VSS class/nested-common equality and absence of test/probe/dev classes. The 2026-09-06 thirty-jar inspection is historical baseline evidence, not acceptance for newly built jars. Do not create a throwaway release tag to test the workflow: tags can publish artifacts.

## Tracking, review and handoff

Create an implementation ledger next to this plan when work begins, with one row per WI and per affected line. Use statuses `NOT STARTED`, `IN PROGRESS`, `UNIT GREEN`, `RUNTIME GREEN`, `DEFERRED` and `DONE`; include commit, test command/evidence path, actual counts/skips, live profile, outstanding gates and reviewer disposition. The initial state of every fix is NOT STARTED. Neither the prior 16 diagnostic RED assertions nor this documentation expansion changes that state.

Each package is DONE only when:

- The implementation matches its stated invariant and preserves the named pinned behaviors.
- Its original failure and added boundary/control fixtures pass; existing relevant tests pass with no unexplained new skips.
- Every affected source line has the fix or an explicit already-correct/not-applicable record, with port differences reviewed.
- Required runtime/artifact gates are passed, or the package explicitly remains incomplete with a documented support-tier deferral. A deferred gate is not silently converted into runtime confidence.
- The implementation log links the final commits and evidence, and any user-facing behavioral change has current documentation/release-note wording that matches what actually ships.

Review the combined result for the three cross-package boundaries: WI-2 registration versus dedup dirty ownership; WI-3 acquisition retirement versus WI-4 accepted proof; WI-3 retirement versus WI-5 bridge report publication. Also verify WI-6's runtime reapplication still occurs when WI-10 reports a persistence failure. A narrow fix passing alone is not sufficient if the combined transitions undo each other's bookkeeping.

The handoff should include reviewable per-line PRs or diffs, a changed-file/commit map, the ledger, final artifact hashes and remaining limitations. Merge, release publication and deployment are separate operational steps when requested; this planning task performs none of them.

If deployment is subsequently requested, prepare the concrete artifact-to-instance map first, stop only the owned test server cleanly, preserve previous jars/configs and the real world, install verified artifacts into the actual Prism `minecraft/mods` paths, then restart and confirm loader/mod identity and the intended map. Keep one rollback set of prior jars. A regression rollback must preserve world/store data; after WI-1, reverting to the old bug may require a controlled affected-cache invalidation under the intended mask before serving again. Never use a world deletion or schema downgrade as an implicit rollback step.

## Completed review coverage

All completed reviewer passes used Astra. Ten focused subsystem passes covered MC 1.21.1, three additional passes compared all five lines, and two final passes challenged the plan and storage evidence. The parent independently checked triggers, related pinned decisions, source scopes and reproduction results. Three reviewer slots were reused at the tool's concurrency limit; these were separate review passes, not fifteen simultaneous reviewers.

| Lens | Report | Outcome |
| --- | --- | --- |
| Server processing and asynchronous lifecycle | [02-server](../reviews/2026-09-06-astra/02-server.md) | Session ownership finding; generation extension in 09 |
| Client acquisition and ingest lifecycle | [04-client](../reviews/2026-09-06-astra/04-client.md) | Master-toggle finding |
| Summary/cache freshness | [05-freshness](../reviews/2026-09-06-astra/05-freshness.md) | Summary proof retraction |
| Xaero integration | [06-xaero](../reviews/2026-09-06-astra/06-xaero.md) | Session debt and accounting races |
| Far-player protocol/tracker/rendering | [07-farplayers](../reviews/2026-09-06-astra/07-farplayers.md) | Disable, identity robustness, seated recovery |
| Privacy and permissions | [08-permissions](../reviews/2026-09-06-astra/08-permissions.md) | Conditional disguise-plugin reload |
| Paper/platform lifecycle | [09-paper](../reviews/2026-09-06-astra/09-paper.md) | Confirmed generation extension of WI-2 |
| Optional integrations and installed artifacts | [10-compat](../reviews/2026-09-06-astra/10-compat.md) | Voxy reset pairing; bounded rig coverage |
| Configuration, UI and commands | [11-config](../reviews/2026-09-06-astra/11-config.md) | Persistence feedback; corroborates disable |
| Tests, soaks and benchmarks | [12-validation](../reviews/2026-09-06-astra/12-validation.md) | Two harness findings |
| Cross-line wire/native/API adaptations | [13-cross-wire](../reviews/2026-09-06-astra/13-cross-wire.md) | No additional defect; deliberate differences verified |
| Cross-line loaders/renderers/UI | [14-cross-loaders](../reviews/2026-09-06-astra/14-cross-loaders.md) | Elytra port defect; exact seated-fix scope |
| Cross-line releases/packaging | [15-cross-release](../reviews/2026-09-06-astra/15-cross-release.md) | Thirty jars inspected; pin/prose improvements |
| Independent plan challenge | [16-plan-challenge](../reviews/2026-09-06-astra/16-plan-challenge.md) | Amendments incorporated, including provisional receipt honesty |
| Independent storage proof validation | [17-storage-validation](../reviews/2026-09-06-astra/17-storage-validation.md) | Both storage findings upheld; size checks added |

The initial full wire and storage reviewer attempts were interrupted by automated content filtering and are not counted as completed audits. [Parent wire review](../reviews/2026-09-06-astra/01-wire-parent.md) and [parent storage validation](../reviews/2026-09-06-astra/03-storage-parent.md) record the narrower completed coverage. The final storage reviewer independently validated the evidence. No exhaustive security-review claim is made.

## Validation completed against unchanged production code

- Dedicated wire/cursor/payload/legacy/corpus baseline: 169 tests, zero failures/errors/skips.
- Final ordinary 1.21.1 unit suites: Fabric 2325 tests, zero failures/errors, four existing skips; Paper 495 tests, zero failures/errors/skips. NeoForge's 23-test task was UP-TO-DATE with green cached results, not freshly executed. The combined Gradle invocation completed successfully.
- External diagnostic tests: 17 unique methods, 16 expected assertion failures and one passing normal-read control, zero errors/skips. These establish missed invariants in the unchanged baseline; they are not failures of the existing suite. Earlier reruns are not counted again. [Exact methods and assertions](../reviews/2026-09-06-astra/final-probe-results.json).
- Cross-line source inventory and targeted real Minecraft/optional-mod bytecode inspection establish fix scope. Thirty previously built LSS/VSS jars were inspected read-only. These are not fresh cross-line build results.

The five-line labels above describe inspected source/fix scope. Executed diagnostic tests and ordinary suite validation were on 1.21.1. No new gametests, soaks, benchmarks or live graphics checks were run during this review. The Voxy compatibility artifact evidence is specifically 1.21.1; porting the shared compatibility rung elsewhere does not establish a runtime failure there. The final cross-loader finding arrived after the independent plan challenge and was verified and integrated by the parent.

Dedicated Prism profile roles must be recorded before claiming live integration coverage: the dedicated Fabric 1.21.1 profile currently lacks Voxy, which may be intentional for Xaero-only testing; the dedicated NeoForge profile contains a top-level Voxy jar whose internal metadata targets Fabric 1.21.11. A separate 1.21.1 far-testing copy contains the verified community port. These observations do not establish what was loaded in a running client. Report 10 preserves exact paths and metadata. No profile was changed by this review.

The original transient vertical Xaero map lines were not reproduced. The confirmed Xaero lifecycle races merit repair independently, but are not presented as their proven cause.

## Deliverables and implementation boundary

[Evidence bundle and reproduction instructions](../reviews/2026-09-06-astra/README.md) contains the reports, validated finding index, opt-in diagnostic sources, final result summaries/XML, bytecode evidence and logs. Production files, installed mods and the running test server remain unchanged. This expanded document specifies implementation and acceptance; production implementation and deployment remain future work.
