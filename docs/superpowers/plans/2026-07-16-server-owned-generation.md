# Server-Owned Generation + Undifferentiated Want-Set — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Source of truth:** `docs/superpowers/specs/2026-07-16-server-owned-generation-design.md`. This plan
implements it, folding in the corrections from the 2026-07-16 adversarial review (below). It replaces
the superseded sync-only decouple plan (deleted in the same change that adds this file); that plan's
verified reference material (Moonrise API signature, file-touch lists, G1–G7 findings) is folded in
where used.

**Goal:** The client becomes fully declarative — declares every position it wants, no sync/gen
classification, one want-set budget, both concurrency caps off the wire. The server owns all
disk/generation limiting: every cold column is disk-first, generation is decided on the disk miss
(concurrency-only), Paper generation runs at Moonrise `Priority.LOW`, and `NOT_GENERATED` narrows to
a **permanent, session-level "stop asking"** revived only by dirty broadcast. One protocol-breaking
change inside the still-unreleased v17.

**Tech stack:** Java 25 (fabric/paper), Java 21 (common); Loom + accessor mixins; paperweight-userdev;
MC 26.2. Moonrise `ca.spottedleaf.moonrise.common.PlatformHooks.get().scheduleChunkLoad(ServerLevel,
int, int, boolean, ChunkStatus, boolean, Priority, Consumer<ChunkAccess>)` — **confirmed present and
compile-reachable** (inherited from `ChunkSystemHooks`; the exact call Paper's `CraftWorld` uses);
`ChunkStatus` = `net.minecraft.world.level.chunk.status.ChunkStatus`; `Priority.LOW` real. No new deps.

**Branch:** the current `feat/want-set-requests`.

## Global constraints

- **v17 is UNRELEASED** (latest tag `v0.6.2` ships `PROTOCOL_VERSION=16`) → all wire changes are free.
  `PROTOCOL_VERSION` stays `17`. Fabric and Paper encoders MUST stay byte-identical (`WireParityTest`
  both modules — reference bytes regenerated once, in the wire task).
- **Tier 1 (`:fabric:test -x runGameTest -x runClientGameTest`) and `:paper:test` green at every
  commit.** Tier 2 (`:fabric:runGameTest`) green at every commit that touches server behavior.
  Tier 3 + soak at part boundaries (Tasks 7 and 10).
- **Task order is compatibility-ordered.** The server learns to generate on ANY disk miss (Task 2)
  *before* the client stops emitting `ts==0` (Task 3): during the interim the old client's `ts==0`
  asks still resolve (GENERATION-typed requests are disk-first; the miss now generates regardless of
  type), so every commit boundary keeps client and server converging.
- **`NOT_GENERATED` = permanent unservability ONLY.** Transient pressure (full gen slot, gen-service
  capacity, generation timeout, submit no-op, disk headroom, send queue) NEVER reaches the wire — it
  is a silent drop counted `superseded`, healed by the next 1 Hz re-declaration. This is the spec's
  own stated rule; the four existing code sites that violate it under the new semantics are
  enumerated in Task 2 (review findings R1–R4).
- **The generation books must keep balancing** (`submitted == completed + timeouts + removed`, soak
  law A4): transient drops change only the *wire disposition* (superseded instead of not_generated),
  never the counters. The per-player `generationInFlight` stale guard must be consumed on every
  drained outcome, transient included (it already is — transients ride the same
  `processGenerationReady` drain).
- **Decisions signed off (VoX, 2026-07-16):** `WANT_SET_BUDGET = 800` (vs up to 960); delete
  `SCAN_BUDGET_MULTIPLIER`, keep `WANT_SET_FRONTIER_RESERVE` (repurposed invariant, Task 1);
  generation *timeout* is a TRANSIENT drop (not `NOT_GENERATED`); `generationEnabled` STAYS on the
  wire (4-field SessionConfig — re-adding later would cost another protocol break); no other wire
  changes ride this release.

## Review findings folded into this plan (2026-07-16 adversarial pass)

Verified against code; each lands in a specific task.

- **R1 — gen-slot-full on a disk miss** (`OffThreadProcessor.handleDiskNotFound`, the
  `tryAdmit`-fails branch at line ~745): today emits `ColumnNotGenerated`. Under permanent
  semantics that would blank a position for the whole session because a slot was momentarily full.
  → silent drop + `superseded`. (Task 2)
- **R2 — gen-service capacity reject** (`RequestProcessingService.drainGenerationTicketRequests:470`
  + Paper twin `:659`): `submitGeneration` returns false at capacity → `feedGenerationFailure` →
  `NOT_GENERATED`. Same transient→permanent bug. → feed a TRANSIENT outcome. (Task 2)
- **R3 — generation timeout** (Fabric `ChunkGenerationService.tick` timeout branch; Paper
  `tickActiveTimeouts`): today → failure outcome → `NOT_GENERATED`. Timeouts are transient
  (and under Paper `Priority.LOW`, starvation-under-load timeouts become *routine* — Task 8 makes
  this finding load-bearing). → TRANSIENT outcome; `totalTimeouts` still counts (books unchanged).
  Extraction errors / null-chunk completions stay PERMANENT (`NOT_GENERATED`) per spec — a corrupt
  chunk must not be hammered. (Task 2)
- **R4 — submit no-op** (`IncomingRequestRouter.tryAdmitAndSubmit:286`, dimension level not
  registered yet): today emits `ColumnNotGenerated` "so it re-requests later" — that reasoning dies
  with permanent semantics. → silent drop + `superseded`. The no-disk+no-gen branch (`:307`) stays
  `NOT_GENERATED` (genuinely permanent). (Tasks 2/4)
- **R5 — legacy 0-stamps in the column cache.** `ColumnCacheStore` persists raw timestamps and
  RELEASED clients (v0.4.1–v0.6.2) wrote `put(packed, 0L)` on `NOT_GENERATED`. A v17 client loading
  such a cache must treat `stored == 0` as "no data" (declare `-1`), or those positions silently
  classify SATISFIED forever. (Task 3)
- **R6 — Paper dirty-revival gap (pre-existing, documented not fixed).** The spec's only
  `NOT_GENERATED` recovery is the dirty broadcast. On Fabric, a first-time vanilla chunk save marks
  dirty (`ChunkMapSaveHook` + `DirtyContentFilter` first-observation fail-open — verified). On
  Paper there is NO save hook and `ChunkPopulateEvent` is deliberately not a default `updateEvents`
  entry — a chunk generated by a player walking in does NOT revive mid-session (reconnect heals it:
  `sessionSatisfied` is session-scoped). This asymmetry exists TODAY for gen-disabled servers (the
  client already parks `ts==0` when generation is off); the redesign extends it only to the rare
  gen-error case on gen-enabled servers. Not worth new mechanism: document it, and note that
  operators can opt in by adding `ChunkPopulateEvent` to `updateEvents`. (Task 9)
- **R7 — repeated-miss disk churn (accepted, measured).** A position that misses disk but can't get
  a gen slot is re-declared, re-read, re-missed each scan until a slot frees. Bounded by the reader
  pool's `hasHeadroom` gate and 1 Hz cadence; region-absent misses are cheap. The old model had the
  same loop at gen-frontier size (64–160); the want-set widens it (≤800). `generation-capacity-stress`
  (global cap 1) is the worst case and gets measured in Task 7 — if the measured IO is pathological,
  the negative-cache non-goal gets revisited as a follow-up, not in this plan.
- **R8 — `#28` successor invariant.** With no client budget derived from any cap, the old
  cross-clamp dies entirely — but the *reason* it existed (the want-set must dominate what is
  already in flight, or frontier discovery starves) survives as a static constant inequality:
  `SYNC_ON_LOAD_SLOT_CAP (200) + MAX_CONCURRENT_GENERATIONS (256) + WANT_SET_FRONTIER_RESERVE (64)
  ≤ WANT_SET_BUDGET (800) ≤ MAX_BATCH_CHUNK_REQUESTS (1024)`. Worst-case per-player in-flight is
  sync slots + the global gen ceiling (per-player gen can't exceed global's 256 clamp). Pinned by
  unit test; `WANT_SET_FRONTIER_RESERVE` survives as the frontier-headroom term. (Task 1)
- **R9 — `onColumnNotGenerated`'s `resetConfirmedRing()` dies.** It existed to re-walk the below-ring
  `ts==0` hole. Under the inversion the position is SATISFIED — there is no hole, and keeping the
  reset would force a full ring re-walk per NOT_GENERATED answer during gen-disabled backfill (pure
  churn). The stale-crossing path (`consumeStaleCrossing`) keeps its own reset — that one still
  guards a real re-want. (Task 3)
- **R10 — `RESPONSE_NOT_GENERATED` still resolves awaited positions** via
  `InFlightTracker.removeByPosition` — unchanged; it is what lets rings confirm past permanently
  blank terrain. Never delete the response tag handling.

## What is removed (net)

- `SessionConfigS2CPayload` fields: `syncOnLoadConcurrencyLimitPerPlayer`,
  `generationConcurrencyLimitPerPlayer` (6 → 4 components, both platforms).
- Config field `syncOnLoadConcurrencyLimitPerPlayer` (→ constant `SYNC_ON_LOAD_SLOT_CAP = 200`).
- The `ServerConfigBase.validate()` #28 cross-clamp (→ static invariant test).
- `LSSConstants.SCAN_BUDGET_MULTIPLIER` + `SpiralScanner.BUDGET_MULTIPLIER` (nothing derives a
  budget from a cap anymore).
- Client `ts==0` emission: `ColumnStateMap.classify`'s gen rung, the scanner's `genCap`/`genQueued`/
  `syncQueued` split, `IncomingRequestRouter`'s `ts==0 → GENERATION` classification (line 121).
- `LodRequestManager.onColumnNotGenerated`'s `resetConfirmedRing()` call (R9).
- Possibly `RequestType` itself (Task 4 decides: if nothing reads `PendingRequest.type()` after the
  router change, fold it away; `SlotType` stays — it is the slot-accounting key).

`generationConcurrencyLimitPerPlayer`/`Global` stay as server config (internal admission only).
`generationEnabled` stays on the wire.

---

## File structure (touch list — verified by exhaustive sweep 2026-07-16)

**common/** `LSSConstants.java` (constants block), `config/ServerConfigBase.java` (field + #28),
`processing/OffThreadProcessor.java` (`handleDiskNotFound`, `processGenerationReady`,
`feedGenerationFailure`, `GenerationReadyData` transient flag), `processing/IncomingRequestRouter.java`
(:121, :257, :286, :291–309), `processing/PendingRequest.java` / `RequestType` / `SlotType.java`
(javadoc + possible fold), `processing/TickSnapshot.java` (record + javadoc).

**fabric/ production:** `networking/payloads/SessionConfigS2CPayload.java`,
`networking/client/{ClientSessionGate,SpiralScanner,ColumnStateMap,LodRequestManager}.java`,
`networking/server/{LSSServerNetworking,RequestProcessingService,ChunkGenerationService}.java`.

**paper/ production:** `PaperPayloadHandler.java`, `LSSPaperPlugin.java`,
`PaperRequestProcessingService.java`, `PaperChunkGenerationService.java`.

**Tests (fabric):** `WantSetBudgetInvariantTest` (new), `ColumnStateMapTest`, `SpiralScannerTest`,
`LodRequestManagerTest`, `LodRequestManagerTickTest`, `ClientSessionGateTest`, `PayloadCodecTest`,
`VoxelSectionPayloadTest`, `WireParityTest`, `ConfigValidationTest`, `JsonConfigLoadTest`,
`IncomingRequestRouterTest`, `OffThreadProcessorDiskResultTest`, `ServiceGlueTest`,
`SlotAdmissionTest`, `SendActionIdentityTest`, `DedupFanoutTest`.
**Gametests:** `ServiceLifecycleGameTests` (the 252/253 wire test dies), `LSSGameTests` (:133–137
bounds asserts), `GenerationLifecycleGameTests`, `RegionFaultGameTests` (premise check, Task 2),
`TwoPlayerGameTests` (6-arg ctor at :151).
**Tests (paper):** `WireParityTest`, `LSSPaperPluginGlueTest`, `PaperConfigValidationTest`,
`PaperConfigLoadTest`, `PaperChunkGenerationServiceTest`, `PaperRequestProcessingServiceTest`.

**Scripts:** `check_soak.py` (`SERVER_CONFIG_INT_KEYS:136`, named checks at :1030/:1091/:1143/:1178),
`soak_report.py` (counter classification sanity), `soak-scenarios/*.json` (5 configs carry the dead
sync key; `rate-limit-storm` re-premised; `generation-capacity-stress` expectations flip).

**Docs:** `CLAUDE.md`, `README.md`, `docs/planning/resource-management.md`.

---

## Part 1 — Server owns generation (Tasks 1–2)

### Task 1: Constants + the #28-successor invariant

**Files:** Modify `common/…/LSSConstants.java`; New test
`fabric/src/test/java/dev/vox/lss/common/WantSetBudgetInvariantTest.java`.

- [x] **Step 1: Write the failing invariant test:**
```java
package dev.vox.lss.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The #28 successor: no client budget derives from any server cap anymore, so the old
 *  validate() cross-clamp is gone. What survives is the REASON it existed, now a static
 *  inequality between constants: the declared want-set must dominate the worst-case
 *  per-player in-flight (sync slots + the global generation ceiling — per-player gen can
 *  never exceed the global clamp) with frontier headroom left over, and must always fit
 *  one wire batch. If any constant changes, this test re-derives the boundary. */
class WantSetBudgetInvariantTest {
    @Test
    void wantSetDominatesWorstCaseInFlightAndFitsOneBatch() {
        assertTrue(LSSConstants.WANT_SET_BUDGET <= LSSConstants.MAX_BATCH_CHUNK_REQUESTS,
                "want-set budget must fit one wire batch");
        assertTrue(LSSConstants.SYNC_ON_LOAD_SLOT_CAP
                        + LSSConstants.MAX_CONCURRENT_GENERATIONS
                        + LSSConstants.WANT_SET_FRONTIER_RESERVE
                        <= LSSConstants.WANT_SET_BUDGET,
                "budget must dominate max in-flight (sync cap + global gen ceiling) + frontier reserve");
        assertTrue(LSSConstants.SYNC_ON_LOAD_SLOT_CAP <= LSSConstants.MAX_CONCURRENCY_LIMIT);
    }
}
```
- [x] **Step 2: Run — fails to compile.**
  `./gradlew :fabric:test --tests '*WantSetBudgetInvariantTest' -x runGameTest -x runClientGameTest`
- [x] **Step 3: Add to `LSSConstants.java`** (near the want-set block at :75–84; leave
  `SCAN_BUDGET_MULTIPLIER`/`WANT_SET_FRONTIER_RESERVE` untouched for now — the multiplier is still
  read by `SpiralScanner:34` and `ServerConfigBase:61` until Tasks 3/6):
```java
    // The client's single want-set budget: max positions the scanner declares per scan.
    // A fixed constant — under server-owned generation NO client budget derives from any
    // server cap (the caps left the wire). 800 preserves the historic effective volume
    // (old syncCap 200 x 4). Invariant pinned by WantSetBudgetInvariantTest:
    // SYNC_ON_LOAD_SLOT_CAP + MAX_CONCURRENT_GENERATIONS + WANT_SET_FRONTIER_RESERVE
    //   <= WANT_SET_BUDGET <= MAX_BATCH_CHUNK_REQUESTS.
    public static final int WANT_SET_BUDGET = 800;

    // Server per-player SYNC (disk-read) slot cap. Formerly the config field
    // syncOnLoadConcurrencyLimitPerPlayer; now a constant — its admission role was always
    // shadowed by the shared disk-pool hasHeadroom() gate (pool depth ~165 < 200), so it
    // was never an operator-meaningful knob. Router retain semantics unchanged.
    public static final int SYNC_ON_LOAD_SLOT_CAP = 200;
```
- [x] **Step 4: Run — passes.** Same command → green. Also `./gradlew :paper:test` (untouched, sanity).
- [x] **Step 5: Commit.** `wantset: single-budget + sync-slot-cap constants + the #28 successor invariant`

### Task 2: Server generates on ANY disk miss; transient outcomes leave the wire

The heart of the redesign, entirely server-side, backward-compatible with the still-`ts==0`-emitting
client (a GENERATION-typed request is disk-first today, so removing the type gate at the miss serves
both eras). Implements R1–R4.

**Files:** Modify `common/…/OffThreadProcessor.java`, `common/…/TickSnapshot.java`,
`fabric/…/RequestProcessingService.java`, `fabric/…/ChunkGenerationService.java`,
`paper/…/PaperRequestProcessingService.java`, `paper/…/PaperChunkGenerationService.java`,
`common/…/IncomingRequestRouter.java` (the `:286` no-op site only — the classification removal is
Task 4); Tests `OffThreadProcessorDiskResultTest`, `ServiceGlueTest`, `IncomingRequestRouterTest`,
`PaperChunkGenerationServiceTest`, `PaperRequestProcessingServiceTest`, gametests below.

**Interfaces — Produces:** `TickSnapshot.GenerationReadyData` gains `boolean transientFailure`
(meaningful only when `columnData == null`); `OffThreadProcessor.feedGenerationFailure(...)` gains the
same flag (or a `feedGenerationTransient` overload — implementer's choice, one drain path either way).

- [x] **Step 1: Write/adjust the failing tests first.**
  - `OffThreadProcessorDiskResultTest`: (a) disk not-found + gen available + gen slot FREE →
    generation ticket enqueued + `addGenerationInFlight` registered, **for a plain SYNC pending**
    (no GENERATION type required — the new gate); (b) not-found + gen available + gen slot FULL →
    NO send action, `superseded` incremented, pending freed (R1); (c) not-found + gen UNAVAILABLE →
    `ColumnNotGenerated` (unchanged); (d) `processGenerationReady` with `columnData == null &&
    transientFailure` → NO send action, `superseded` incremented, `generationInFlight` consumed;
    (e) null + !transient → `ColumnNotGenerated` (unchanged).
  - `ServiceGlueTest` (:178–192): the capacity-reject feed now flags transient → drains silent.
  - `IncomingRequestRouterTest`: submit no-op (`:286`) → silent drop + `superseded`, NOT
    `ColumnNotGenerated`; conservation totals updated (R4).
  - Fabric `ChunkGenerationService`/Paper twin unit tests: timeout outcome carries
    `transientFailure = true`; extraction-error outcome carries `false`; `totalTimeouts` /
    `totalRemovedInFlight` books unchanged (law A4).
- [x] **Step 2: Run — red.** `./gradlew :fabric:test :paper:test -x runGameTest -x runClientGameTest`
- [x] **Step 3: Implement.**
  - `TickSnapshot.GenerationReadyData` + record javadoc: add `transientFailure`.
  - `OffThreadProcessor.handleDiskNotFound` — the new ladder:
```java
    if (this.generationAvailable) {
        if (state.tryAdmit(new PendingRequest(cx, cz, RequestType.GENERATION, SlotType.GENERATION, pending != null && pending.claimsData()))) {
            addGenerationInFlight(playerUuid, dimension, packed);
            this.ctx.generationTicketRequests().add(new GenerationTicketRequest(
                    playerUuid, cx, cz, dimension, this.ctx.sequence().next()));
        } else {
            // Transient: the gen slot cap is momentarily full. Under permanent
            // NOT_GENERATED semantics an answer here would blank the position for the
            // session — silent drop instead; the next want-set declaration retries.
            this.ctx.diagnostics().addSuperseded(1);
        }
    } else {
        this.ctx.sendActions().add(new SendAction.ColumnNotGenerated(playerUuid, packed, state));
    }
```
  - `OffThreadProcessor.processGenerationReady` null-columnData branch: `transientFailure` →
    `addSuperseded(1)` (no send action); else `ColumnNotGenerated` as today. (The pending removal,
    `consumeGenerationInFlight`, and `incrementGenDrained` stay common to both.)
  - `feedGenerationFailure` callers: `drainGenerationTicketRequests` capacity/removed-player rejects
    (both platforms) feed TRANSIENT (R2).
  - Fabric `ChunkGenerationService.tick` timeout branch + Paper `tickActiveTimeouts`: failure
    outcomes marked transient (R3). `addFailures` gains the flag; extraction-error callers pass
    permanent.
  - `IncomingRequestRouter:286`: replace the `ColumnNotGenerated` send with
    `this.ctx.diagnostics().addSuperseded(1)` (comment: transient — level not yet registered;
    the want-set re-declares). Still `return SUBMITTED` (dispositioned).
- [x] **Step 4: Run — Tier 1 + Paper green.** Same command.
- [x] **Step 5: Tier 2 sweep.** `./gradlew :fabric:runGameTest`. Expected touch points:
  `GenerationLifecycleGameTests` — the caps test: at-capacity asks now silently retry instead of
  answering NOT_GENERATED; assert eventual service (the piggyback/cap counters), not a
  NOT_GENERATED count. `RegionFaultGameTests` — the corrupt-region chunk now resolves not-found
  *and then regenerates* (gen is enabled in the gametest config): the test's premise is containment
  (`errors=1`, reader survives), which holds; if it pinned "resolves as not_found on the client",
  relax to "resolves terminally (not_found consumed by a successful regeneration serve)" — the
  self-healing is correct new behavior. Adjust in the same commit with a comment.
- [x] **Step 6: Commit.** `server: generate on any disk miss; transient generation outcomes never reach the wire`

## Part 2 — Client inversion (Tasks 3–4)

### Task 3: Client — single want-set budget + permanent NOT_GENERATED

**Files:** Modify `fabric/…/client/ColumnStateMap.java`, `SpiralScanner.java`,
`LodRequestManager.java`; Tests `ColumnStateMapTest`, `SpiralScannerTest`, `LodRequestManagerTest`,
`LodRequestManagerTickTest`.

- [x] **Step 1: Flip the tests first** (exhaustive list from the 2026-07-16 sweep):
  - `ColumnStateMapTest`: `notGeneratedRequestsGenerationOnlyWhenEnabled` → becomes
    `notGeneratedIsPermanentSessionSatisfy` (classify == SATISFIED regardless of the deleted flag;
    no 0-stamp written; a later `markDirtyIfKnown` revives → classify returns the stored ts / -1);
    `onUpToDateResolvesNotGeneratedStamp`, `cacheLoadedZeroStampRetriesGenerationOnlyWhenEnabled`
    (→ `cacheLoadedZeroStampDeclaresAsNoData`: legacy 0-stamp classifies as `-1`, R5),
    `dirtyRescuesParkedNotGeneratedStamp` (dirty after NOT_GENERATED → declare `-1`, since no stamp
    is written anymore), `zeroStampWithDirtyEmitsGenerationRequestWhenGenerationEnabled` (legacy 0 +
    dirty → `-1`, never `0`), `countsTrackTransitions` (onNotGenerated no longer moves
    received→empty), the three `…AcrossDirty…PriorStates` matrices, and
    `notGeneratedAnswerConsumesDirtyAndRetryMarksButStaysGenRetryable` (→ consumes marks AND
    session-satisfies).
  - `SpiralScannerTest`: budget asserts → `WANT_SET_BUDGET` constant, config-independent
    (`budgetBoundsQueuedPositions` etc.); DELETE the gen-frontier tests
    (`satisfiedOuterRingMustNotConfirmPastUnsatisfiedInnerRing`'s genCap arithmetic,
    `getLastGenQueued` asserts); `ts0PositionBelowConfirmedRing…`/`ts0PositionStaysParked…` →
    NOT_GENERATED positions are satisfied, never re-walked (only a dirty revives);
    queue-pressure/vanilla-scaling asserts survive on the new base.
  - `LodRequestManagerTest`: `trackedNotGeneratedStampsGenerationRetryAndReleasesSlot` → tracked
    NOT_GENERATED session-satisfies + releases tracking;
    `notGeneratedResponseResetsConfirmedRingSoTheStrandedHoleIsRewalked` → DELETED (R9 — no hole
    exists); `duplicatePositionInOneBatchFrameResolvesConsistently` re-derived without 0-stamps;
    `untrackedNotGeneratedCountsMetricButNeverStampsState` survives as-is (untracked still
    metrics-only — the dimension guard).
  - `LodRequestManagerTickTest`: `generationDisabledDeclaresZeroGenRequestsAndReEnableResumes` →
    DELETED (no client gen requests exist in any state); verify the want-set pins (convergence
    sends nothing; backpressure clear batch; awaited re-declaration) still green — they must not
    change.
- [x] **Step 2: Run — red.**
- [x] **Step 3: Implement.**
  - `ColumnStateMap.onNotGenerated` (:213–218) — the spec's 2-line inversion:
```java
    void onNotGenerated(long packed) {
        this.dirty.remove(packed);   // answer-time consumption, same as onUpToDate
        this.retry.remove(packed);
        this.sessionSatisfied.add(packed);  // PERMANENT for the session; only a dirty
                                            // broadcast (markDirtyIfKnown) revives it
    }
```
    No `put(0L)`; existing stamps are left untouched (a data-claiming client keeps its stale-but-real
    terrain — mirrors `onUpToDate`).
  - `classify` (:71–90): delete the `generationEnabled` parameter and the
    `stored == 0L && generationEnabled → 0L` rung; the `stored == -1L` rung widens to
    `stored <= 0L && stored != SATISFIED-sentinel` → return `-1L` — concretely: `if (stored == -1L
    || stored == 0L) return -1L;` (the `== 0L` half is the R5 legacy-cache normalization; comment it).
    Dirty rung: `return dirtyStored <= 0L ? -1L : dirtyStored;` (same normalization).
  - `SpiralScanner`: `int budget = LSSConstants.WANT_SET_BUDGET;` (replaces `syncCap × mult` at :78);
    delete `genCap` (:111), `syncQueued`/`genQueued` (:116–117, :163, :178–179) and the
    gen-cap gate (:157); delete the `BUDGET_MULTIPLIER` field (:34) and the `generationEnabled`
    local (:107); `classify(packed)` single-arg. Ring rule collapses to: SATISFIED → skip (does not
    block confirmation); any declared position blocks confirmation. Keep queue-pressure scaling,
    vanilla-load scaling, and the `MAX_BATCH_CHUNK_REQUESTS` clamp (:90) verbatim.
  - `LodRequestManager.onColumnNotGenerated` (:352–385): keep tracker removal, RTT discard,
    `columns.onNotGenerated`, `consumeStaleCrossing`; DELETE the trailing
    `scanner.resetConfirmedRing()` (:383) with a comment citing R9.
- [x] **Step 4: Run — Tier 1 green**; `./gradlew :fabric:runGameTest` (Tier 2 — two-player dedup,
  generation lifecycle, and probe tests must converge with the never-emits-0 client against the
  Task 2 server).
- [x] **Step 5: Commit.** `client: one want-set budget; NOT_GENERATED is a permanent session-satisfy (dirty-broadcast revival only)`

### Task 4: Router — every request is SYNC

**Files:** Modify `common/…/IncomingRequestRouter.java`, `common/…/PendingRequest.java` /
`RequestType` (fold decision), `common/…/SlotType.java` (javadoc); Tests `IncomingRequestRouterTest`,
`SlotAdmissionTest`, `DedupFanoutTest`.

- [x] **Step 1: Tests first.** Router tests: a `ts==0` incoming request routes exactly like `ts==-1`
  (disk-first, SYNC slot, `claimsData=false`) — the retired value must stay inert, mirroring the
  Tier-1 "retired byte 0" wire pin; the no-disk branches: gen available → direct-generation for ANY
  request; no gen → `ColumnNotGenerated`.
- [x] **Step 2: Implement.** Delete `:121` (`type = clientTimestamp()==0 ? GENERATION : SYNC`); the
  `tryAdmitAndSubmit` signature drops `type`; branch 1 condition `type == SYNC ||
  diskReadingAvailable` becomes `this.diskReadingAvailable`; branch 2 (`:291`, no-disk direct gen)
  loses its type comment; escalation in `handleDiskNotFound` keeps `SlotType.GENERATION`.
  **`RequestType` fold:** after this change, grep `PendingRequest.type()` readers — the Task 2
  ladder no longer reads it; if (as expected) zero production readers remain, delete `RequestType`
  and the `PendingRequest` component, updating `SlotAdmissionTest`; if a reader remains, keep and
  note why in the commit. Update `SlotType` javadoc (:3–8 documents the dead disk-first-ts
  semantics).
- [x] **Step 3: Run — Tier 1 + `:paper:test` + Tier 2 green.**
- [x] **Step 4: Commit.** `router: every request is SYNC; generation is the server's disk-miss decision`

## Part 3 — Wire + config (Tasks 5–6)

### Task 5: SessionConfig 6 → 4 fields (both platforms)

**Files:** Modify `SessionConfigS2CPayload.java`, `ClientSessionGate.java`,
`LSSServerNetworking.java:89–96`, `PaperPayloadHandler.java:36–61`, `LSSPaperPlugin.java:225–287`,
`PaperRequestProcessingService.java`; Tests: fabric `WireParityTest` (:125–188, new reference
bytes), `PayloadCodecTest:56–64`, `VoxelSectionPayloadTest:132–137`, `ClientSessionGateTest`
(:65–215 — drop both clamp tests, 4-arg ctors), `ClientColumnProcessorTest` (5 ctor sites),
`LodRequestManagerTest:66`, `LodRequestManagerTickTest:71`, `SpiralScannerTest:32,871`,
`VoxelColumnReceiverTest:67`, gametest `TwoPlayerGameTests:151`; paper `WireParityTest:110–143`
(new reference bytes), `LSSPaperPluginGlueTest:90–158`.

- [x] **Step 1: Update every test to the 4-field shape first** (`protocolVersion, enabled,
  lodDistanceChunks, generationEnabled`) → red.
- [x] **Step 2: Fabric.** Record components (:13–14 deleted), codec write/read, version-mismatch
  fallback ctor (:38) → 4-arg. `ClientSessionGate.clampToProtocolBounds` drops both concurrency
  clamps (:156–157), keeps the lodDistance clamp.
- [x] **Step 3: Paper.** `encodeSessionConfig`/`sendSessionConfig` drop both int params + writeVarInts;
  `SessionConfigSender.send` interface (:225–229) and both call sites → 4 params.
- [x] **Step 4: Delete the `ServiceLifecycleGameTests` 252/253 end-to-end wire test** (:738–772) —
  both fields it wires are gone (note in commit body); `LSSGameTests` bounds asserts (:133–137)
  drop the sync line, keep Global/PerPlayer as plain config-range asserts.
- [x] **Step 5: Run — Tier 1 + `:paper:test` + Tier 2 green.** Fabric/Paper wire parity must agree
  on the 4-field bytes.
- [x] **Step 6: Commit.** `wire: SessionConfig drops both concurrency caps (6 -> 4 fields, v17)`

### Task 6: Config — delete the syncOnLoad knob and the #28 cross-clamp

**Files:** Modify `common/…/ServerConfigBase.java`, `common/…/LSSConstants.java`,
`fabric/…/RequestProcessingService.java:108`, `paper/…/PaperRequestProcessingService.java:270`;
Tests `ConfigValidationTest`, `PaperConfigValidationTest`, `PaperConfigLoadTest`,
`JsonConfigLoadTest`.

- [x] **Step 1: Tests first.** Fabric `ConfigValidationTest`: delete
  `syncOnLoadConcurrencyLimitPerPlayerClamped` and `maxConfigLeavesFrontierHeadroomInTheWantSetBudget`
  (the invariant lives in `WantSetBudgetInvariantTest` now);
  `generationConcurrencyLimitPerPlayerClamped` expects the plain `[1, 1000]` clamp (9999 → 1000, was
  190). Paper `PaperConfigValidationTest`: remove the `SHARED_BOUNDS` sync entry (the anti-vacuity
  check forces it), delete the helper constants deriving from the deleted field, delete the #28
  boundary twin (`inFlightWorstCase` :125–141). `JsonConfigLoadTest:152` / `PaperConfigLoadTest:116`
  drop the default assert.
- [x] **Step 2: Implement.** `ServerConfigBase`: delete the field (:23), its clamp (:49), and the
  entire #28 block (:53–70). `LSSConstants`: delete `SCAN_BUDGET_MULTIPLIER` (now zero readers) and
  rewrite the :75–84 comment block around `WANT_SET_BUDGET`/`WANT_SET_FRONTIER_RESERVE` + the
  invariant. Both services construct player state with `LSSConstants.SYNC_ON_LOAD_SLOT_CAP`.
- [x] **Step 3: Run — Tier 1 + `:paper:test` + Tier 2 green.**
- [x] **Step 4: Commit.** `config: delete the syncOnLoad knob and the #28 cross-clamp (constants + static invariant)`

## Part 4 — Soak + Paper priority + docs (Tasks 7–10)

### Task 7: Soak surface

**Files:** Modify `scripts/soak-scenarios/{rate-limit-storm,bandwidth-throttle,disk-saturation,generation-capacity-stress,generation-disabled}-config.json`, `scripts/check_soak.py`, `scripts/soak_report.py` (only if its counter classification names the flipped checks).

- [x] **Step 1: Drop the dead `syncOnLoadConcurrencyLimitPerPlayer` key** from the four `=200`
  configs (pure cleanup) and from `rate-limit-storm` (`=4` — premise dead).
- [x] **Step 2: Re-premise `rate-limit-storm`.** Its `syncCap:4` lever no longer exists in any form —
  under this model NO client budget derives from a cap, so the scenario becomes the
  **abundance-ceiling pin**: default caps, full 800-want-set declarations, assertion stays a
  *ceiling* (`service.superseded <= N`) proving that steady-state re-declaration against an
  unconstrained service does NOT inflate supersession (the convergence pin: at quiescence the
  client sends nothing). Keep `generation.completed > 120`. N measured live in Task 10 (expect ~0
  at quiescence; leave the committed threshold at 50 unless the measured run says otherwise).
  Rewrite the scenario comment + `check_soak.py:1030–1088` docstring: supersession under v17 comes
  from slow service, never a small cap — cite this plan.
- [x] **Step 3: Flip `generation-capacity-stress`** (`check_soak.py:1178–1202`): on a gen-ENABLED
  server `NOT_GENERATED` never fires now — `responses.not_generated >= 50` becomes
  `not_generated == 0` (the permanence guarantee, negatively pinned) plus a transient-churn floor
  (`service.superseded >= N`, N measured — the R7 repeated-miss loop under global gen cap 1 IS this
  scenario's subject now). Keep `generation.completed >= 120` and quiescence.
- [x] **Step 4: Verify `generation-disabled` needs no threshold change** (each ungenerated position
  is answered `NOT_GENERATED` exactly once TODAY too — the client already parks when gen is off;
  ≥1000 counts distinct positions). Confirm live; adjust only if the measured run disagrees.
- [x] **Step 5: `check_soak.py`** — remove the sync key from `SERVER_CONFIG_INT_KEYS:136`;
  `--selftest` green (update the named-check registry cases); `--validate` green on all edited
  scenarios.
- [x] **Step 6: Commit.** `soak: re-premise storm + capacity-stress for server-owned generation; drop the dead sync key`

### Task 8: Paper — generate at Moonrise `Priority.LOW`

Reuses the superseded plan's verified Task 8 verbatim (API signature confirmed compile-reachable;
reuse the existing `launchAsyncLoad`/`completeAsyncLoad` seams — do NOT add a parallel launcher).
R3's transient-timeout classification is what makes LOW safe: a starved LOW load that trips the 60s
timeout self-heals via re-declaration instead of permanently blanking the column.

**Files:** Modify `paper/…/PaperChunkGenerationService.java`; Test `PaperChunkGenerationServiceTest`
via the `launchAsyncLoad` override seam.

- [x] **Step 1: Seam test** — override `launchAsyncLoad` to record the `Priority` and complete
  synchronously with a stub NMS chunk; assert `submitGeneration` drives `Priority.LOW` and completion
  routes through serialization + the monotonic-`token` stale guard. Red (still `getChunkAtAsync`).
- [x] **Step 2: Adapt the completion contract.** `whenComplete((Chunk, Throwable))` →
  `Consumer<ChunkAccess>` — **no throwable channel; a null chunk IS the failure outcome** (permanent,
  R3 boundary: null-chunk = failed load/extraction → `NOT_GENERATED`; only the tick-timeout is
  transient). `completeAsyncLoad` takes `net.minecraft.world.level.chunk.ChunkAccess`, drops the
  `Throwable` param + its branch; keep the token stale guard and both admission caps.
- [x] **Step 3: Production `launchAsyncLoad`:**
```java
    ca.spottedleaf.moonrise.common.PlatformHooks.get().scheduleChunkLoad(
        level, cx, cz, /*gen=*/true,
        net.minecraft.world.level.chunk.status.ChunkStatus.FULL,
        /*addTicket=*/true, ca.spottedleaf.concurrentutil.util.Priority.LOW,
        (net.minecraft.world.level.chunk.ChunkAccess chunk) -> completeAsyncLoad(key, level, chunk, cx, cz, token));
```
  `addTicket=true` — Moonrise owns the ticket (no manual bookkeeping to remove; that is the Fabric
  service). Serialization stays in the completion on the owning thread (`PaperSectionSerializer`),
  exactly as today — Folia-critical (the chunk can unload before a hop to the global tick).
- [x] **Step 4: Run — `:paper:test` green; `./gradlew :paper:shadowJar`.**
- [x] **Step 5: Commit.** `paper: LOD generation at Moonrise Priority.LOW (defers to player generation; Folia experimental)`

### Task 9: Docs

- [x] **Step 1: `CLAUDE.md`.** Rewrite the v17 protocol section: timestamp semantics (`>0` resync /
  `≤0` no data — no `0=generate`), the 4-field SessionConfig, the single `WANT_SET_BUDGET`, the #28
  bullet → the successor invariant + `WantSetBudgetInvariantTest`, `NOT_GENERATED` permanence +
  dirty-only revival, the transient/permanent outcome rule, `ChunkGenerationService` /
  `PaperChunkGenerationService` bullets (Moonrise LOW; Fabric asymmetry), the `backpressure.superseded`
  interpretation (now also counts transient generation drops), the soak-scenario descriptions
  (storm/capacity-stress new premises).
- [x] **Step 2: `README.md`** — config table: remove `syncOnLoadConcurrencyLimitPerPlayer`; note
  generation caps are server-internal. **Release notes MUST carry an operator-facing line**: the
  option was removed (now a fixed internal value); a config file still carrying it loads fine but
  the key is dropped on the next save (Task 6 review finding — at `diskReaderThreads >= 7` the old
  knob WAS binding, so tuned configs see a silent cap change without this note).
- [x] **Step 3: `docs/planning/resource-management.md`** — generation section: Paper = Moonrise
  `Priority.LOW` hand-off; Fabric = concurrency cap only (vanilla pins worldgen priority to ticket
  level 33; worldgen latency is a poor congestion signal — an absolute-latency throttle would
  collapse LSS generation on a fresh uncontended world; the correct future direction is a tick-health
  (MSPT)-gated backoff). Record R6 (Paper dirty-revival gap + the `ChunkPopulateEvent` opt-in) and
  R7 (repeated-miss churn + the negative-cache follow-up trigger).
- [x] **Step 4: Commit.** `docs: server-owned generation (protocol, asymmetries, soak premises)`

### Task 10: Validation gauntlet + measured re-baselines

- [x] **Step 1:** `./gradlew :fabric:test -x runClientGameTest` (Tier 1+2) + `:paper:test
  :paper:shadowJar` → green.
- [x] **Step 2:** `./gradlew :fabric:runClientGameTest` (Tier 3) → green.
- [x] **Step 3: Fabric soak.** `./scripts/soak.sh all` (17 scenarios). `fresh-backfill` is the
  end-to-end server-triggered-generation proof (no client ever asks to generate);
  `generation-disabled` is the primary NOT_GENERATED-terminator exerciser; record the measured
  `rate-limit-storm` ceiling and `generation-capacity-stress` supersession floor and bake them into
  `check_soak.py` (Task 7 committed provisional values).
- [x] **Step 4: Paper soak.** `SOAK_PLATFORM=paper ./scripts/soak.sh all` — the live `Priority.LOW`
  generation path's only gate. **Ran: 4/4 PASS** (fresh-backfill through the live LOW path:
  not_found 20360 == gen 2070 + miss_dropped 18290, zero timeouts). Folia: 26.2 upstream jar
  unpublished (dropped from the release listing) — unrunnable on this line, same as the A+B work.
- [x] **Step 5: Commit** measured thresholds + any flake-catalog notes.

---

## Accepted tradeoffs (carried from the spec, unchanged)

1. **Unexplored terrain fills in gradually and the world grows** (concurrency-only admission; the
   operator lever is `enableChunkGeneration=false`).
2. **Fabric generation gets no priority and no latency throttle** — concurrency cap only; future
   direction is MSPT-gated backoff, out of scope.
3. **No server-side negative cache** — R7's measurement is the revisit trigger, not this plan.
4. **Paper mid-session revival gap for walk-in generation** (R6) — pre-existing, documented,
   `ChunkPopulateEvent` opt-in available, reconnect always heals.

## Self-review

- Every transient pressure path is enumerated (R1–R4) and none reaches the wire; the permanence
  guarantee is pinned negatively in soak (`not_generated == 0` on a gen-enabled server) and
  positively in unit tests (SATISFIED regardless of movement, dirty revives). ✅
- Task order keeps every commit green with a mixed-era client/server (server generalizes first). ✅
- Books/laws: gen counters unchanged on every path; transient drops ride `superseded`, which the
  harness already treats as a mechanism counter, not a loss. ✅
- Legacy cache 0-stamps normalized (R5) — no released-client cache can seal a permanent hole. ✅
- The #28 deletion is replaced by a *stronger* static invariant (R8) that re-derives on any constant
  change. ✅
- Wire parity: one reference-byte regeneration, both platforms, same task, pinned by both
  WireParityTests. ✅
- The Paper LOW change is Folia-affecting → release note must carry the experimental label. ✅
