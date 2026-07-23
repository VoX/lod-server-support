# Delivery-Honesty Hardening — Design

**Date:** 2026-07-01
**Status:** IMPLEMENTED — shipped post-v0.4.1 (see `docs/planning/plans/2026-07-01-delivery-honesty.md`); kept as the design record
**Scope:** The full delivery-honesty finding cluster from the 2026-07-01 code review:
#2, #4, #5, #11, #13, #21, #36, #43 (#3/#14 are documented tradeoffs, unchanged).

> Revision note: this spec was revised after a multi-agent adversarial review found a critical
> flaw in the first draft (the `sessionSatisfied` air→content permanent hole), reworked WS3
> onto a no-protocol-bump "authoritative column" model, corrected the ingest-park model, and
> trimmed #43. The pinned tests each change touches are now enumerated explicitly.

## 1. Problem

LSS's protocol is built on a **self-healing delivery-honesty invariant**: every response is a
statement about a packed position, so a lost/late/duplicate answer is idempotent and a client
missing a column eventually re-requests and gets it. The review found eight places where that
invariant silently breaks, leaving a client rendering **stale or ghost LOD terrain
permanently** (until an unrelated future edit or a cache flush).

### Root cause: the client conflates two facts

`ColumnStateMap` tracks each column with one `timestamps` value: `-1` never seen, `0`
not-generated, `>0` received at that **server** epoch-second. The server's freshness check
(`IncomingRequestRouter.resolvedFromTimestamp`: `cachedTs>0 && cachedTs <= clientTimestamp` →
`up_to_date`) is valid ONLY because both sides compare server-issued timestamps — the invariant
pinned by `IncomingRequestRouterTest` ("the client stamps the SERVER-sent columnTimestamp
verbatim, never its own clock").

Two paths violate it by writing the **client wall clock** into `timestamps`:
- `ColumnStateMap.onUpToDate` (~line 123): `put(packed, epochSeconds())` for a `-1` (all-air,
  never carries a `VoxelColumn`) or `0` (not-generated-then-resolved) position, to escape the
  `stored == -1` re-request loop in `classify` (the confirmed **End ~50 req/s loop**).
- `ColumnStateMap.onIngestFailed` park branch (~line 157): `put(packed, epochSeconds())` after
  `MAX_INGEST_FAILURES`, to stop a permanently-rejecting consumer re-downloading each scan.

Both fabricated values persist to `ColumnCacheStore` and return next session as the
`clientTimestamp`; a client clock ahead of the server makes `cachedTs <= clientTimestamp` read
true → false `up_to_date` → permanent hole.

## 2. Unifying model

Separate **"what server data do I hold"** from **"have I resolved this position this session"**:

| Fact | Home | Values | Persisted |
|------|------|--------|-----------|
| Held server data | `timestamps` map | `-1` none, `0` not-generated, `>0` server epoch-sec | yes (only server-issued) |
| Session resolution | new `sessionSatisfied` set | membership | **no** (cleared on reconnect / dimension change; pruned by distance) |

`classify` gains a `sessionSatisfied` rung that returns `SATISFIED`, so the within-session
re-request loops (End loop, ingest-park) stop **without writing a timestamp**. Across sessions
the set is empty, so a satisfied position re-asks once with its honest `ts`, the server
re-resolves, and it re-settles. `timestamps` (and `ColumnCacheStore`) hold only server-issued
values.

### Two satisfied cases — same mechanism, honest either way

- **All-air up-to-date** (`onUpToDate`, `stored ∈ {-1, 0}`): add to `sessionSatisfied`, leave
  `timestamps` at `-1`/`0`. Next session it re-asks `-1`/`0`, the server answers a cheap
  batched `up_to_date`, re-park. Honest: the client genuinely holds no server data.
- **Ingest-park** (`onIngestFailed` after cap): **drop the timestamp back to `-1`** and add to
  `sessionSatisfied` (NOT a fabricated `>0` stamp; NOT a retained `>0` stamp — the consumer
  never stored the data, so `ts>0` would be the same lie). Next session it re-asks `-1`, the
  server re-serves, the consumer re-attempts — a *transient* failure (storage briefly down)
  heals, exactly as the original park comment already promised ("heals … next session"). A
  *permanent* failure (incompatible consumer) costs one re-download attempt per session,
  bounded by the per-session cap.

## 3. Workstreams

### WS1 — Clock honesty (#4, #13, #5)

`ColumnStateMap`:
- Add `sessionSatisfied` (`LongOpenHashSet`), cleared wherever `validated` is cleared **and
  pruned by distance in `pruneOutOfRange`** (via `pruneSet`, like `dirty`/`retry`/`validated`/
  `ingestFailures`) so an exploring all-air player cannot grow it without bound.
- **`classify` rung order (critical — see review):** `dirty` MUST be checked **before**
  `sessionSatisfied`, so a dirtied position always re-requests. Proposed order: `dirty` →
  `sessionSatisfied` (→ `SATISFIED`) → `stored == -1` (→ `-1`) → `stored == 0 && gen` (→ `0`) →
  `retry` → cached-not-validated → `SATISFIED`.
- `onUpToDate` for `stored ∈ {-1,0}`: `sessionSatisfied.add(packed)` instead of
  `put(epochSeconds())`. Real `>0` up-to-date still validates as today.
- `onIngestFailed` park branch: `timestamps.remove(packed)` (→ `-1`) + `sessionSatisfied.add`,
  clearing `validated`/`dirty`; keep the failure-cap semantics.
- **`markDirtyIfKnown` (critical fix for the air→content hole):** must fire for a position that
  is in `sessionSatisfied` **even when `stored == -1`** — remove it from `sessionSatisfied`, add
  to `dirty`, and return `true` so `onDirtyColumns` resets the scan counter / `confirmedRing`
  and the below-ring position re-walks. Truly-unknown `-1` positions (not in `sessionSatisfied`)
  stay unmarked as today (scanned anyway; marking them adds retry-marks nothing consumes).

`LodRequestManager.drainQueue` (#5): re-derive the timestamp from `classify(pos, genEnabled)` at
send time (skip on `SATISFIED`) instead of the scan-time `queue.peekTimestamp()`; the queued
timestamp becomes advisory. Closes the window where a stamp forgotten (ingest failure) between
scan and drain would send a stale `ts>0` for data the client no longer holds.

`ColumnCacheStore`: bump `FORMAT_VERSION` 3 → 4; **discard `version < 4` on load** (a pre-fix
cache may hold indistinguishable fabricated stamps). Remove the now-dead v1/v2 migration code
and update/remove the migration tests. One cold resync per server/dimension after upgrade.

**No wire-protocol change; server unchanged for WS1.**

### WS2 — Dirty-notification timing (#11, #43)

**#11 — dirty crossing an in-flight first serve.** A `DirtyColumns` notification for a position
the client is serving for the first time (`stored == -1`, dropped by `markDirtyIfKnown`) must
not be lost. Add a `staleInFlight` set: when a dirty notification arrives for a position the
`InFlightTracker` currently holds, record it. Resolve it at the **common
`tracker.removeByPosition` site** so every terminal first-serve outcome is covered
(`onReceived`, `onUpToDate`, `onNotGenerated`), not just `onReceived`: on a hit, do NOT validate
— instead re-request AND force a ring re-walk (`scanner.resetConfirmedRing()` or a retry mark,
as `onColumnNotGenerated` already does) so a below-`confirmedRing` position is reachable. Clear
`staleInFlight` on every `InFlightTracker` exit (`removeByPosition` + `timeoutSweep` +
`pruneOutOfRange`) so a never-consumed mark cannot pin `confirmedRing`. Rationale is
safety-first: an occasional benign redundant re-serve (when the in-flight column already
reflects the edit) is acceptable; new `dirty-*` tests must tolerate one extra request+response
per crossing rather than asserting flat counts.

**#43 — done-bit clear vs notification ordering.** The review confirmed the proposed route-time
gate is a **no-op**: `applyEvents` (which applies `clearDiskReadDone`) already precedes
`routeIncomingRequests` within every processing cycle, so the clear is effective before any
re-request (which arrives ~network+scan latency later) is routed. Scope #43 to: (a) fix the
misleading "done-bits clear before the send" comment in `DirtyColumnBroadcaster` (~line 132) and
`PaperDirtyColumnBroadcaster` to state the real guarantee (correctness comes from applyEvents
preceding routing, not from the send order); (b) add an ordering-regression test. The
pathological case (processing thread stalled longer than the client's ~1s re-request latency) is
noted as optional future hardening (a mailbox-read gate), not built.

**No wire-protocol change.**

### WS3 — Ghost-terrain conveyance (#21) — no protocol bump

Today an emptied column (content → all-air, e.g. WorldEdit) is served as empty bytes and
answered `ColumnUpToDate`, and a *partial* clear omits the now-air sections from the served
column, so a client keeps **ghost terrain** in both cases. Fix with an **authoritative-column**
model, reusing the existing `VoxelColumnS2CPayload` (which already carries `columnTimestamp`) —
**no new payload, no `RESPONSE_EMPTIED`, no protocol bump**:

1. **Client authoritative clear (covers partial + full).** On a **resync** ingest (a column the
   client re-requested with `ts>0`), the client treats the received column as authoritative for
   the full section-Y range: it materializes an all-air `LevelChunkSection` for every section-Y
   in the level range that is **absent** from the payload and dispatches those too, so the
   consumer overwrites ghost content with air. On a **first serve** (`ts≤0`) it does not (the
   client had nothing; absent = air = no-op), bounding the extra air-ingests to rare resyncs.
2. **Fully-all-air resync.** When the server resolves a column to all-air (empty section bytes)
   **and the requester claims data**, it sends a **0-section `VoxelColumnS2CPayload`** (carries
   `columnTimestamp`) instead of `up_to_date`, so the client clears all sections. A requester
   that claims nothing still gets `up_to_date` (keeps void dimensions cheap — no regression).
3. **Thread a "client-claims-data" bit** (`clientTimestamp > 0`) from `IncomingRequestRouter`
   through `PendingRequest` → `submitDiskRead`/`ChunkReadResult` → `deliverDiskResult`, and
   through `DedupTracker.Attachment` (per-recipient, so dedup fan-out honors it per player) and
   `GenerationReadyData`, on **both platforms**. This is the input the all-air serve paths
   currently lack; without it the server cannot choose emptied-vs-`up_to_date` at delivery.
4. **Client decode/dispatch.** A 0-section column decodes to `[0x00]` (length 1, so it does NOT
   trip the empty-bytes ingest-failure at `ClientColumnProcessor` ~line 177). The
   authoritative-clear must build air sections from the live `ClientLevel`
   (`getSectionsCount` + `PalettedContainerFactory`) on the **LSS-ColumnProcessor decode
   thread**, dispatch via `LSSApi.dispatchColumn` (never the main-thread batch-response path),
   and stamp the position honestly with the server `columnTimestamp`.
5. **Trigger already exists.** A content→air transition changes the Fabric `DirtyContentFilter`
   hash (all-air sentinel) → dirty broadcast; Paper's block/WorldEdit events fire the same → the
   client re-requests `ts>0` and reaches the authoritative path.
6. **Task 0 — Voxy capability (gates payoff, not code).** This only clears ghosts if Voxy's
   `rawIngest` overwrites a stored section when handed an all-air `LevelChunkSection` (both for
   sections absent from a normal column and for a 0-section column). Verify against Voxy FIRST.
   Per decision we ship the path regardless; if Voxy cannot clear, ghosts persist only until
   Voxy adds support, and that limitation is documented.

**No wire-format change** — a `VoxelColumnS2CPayload` with 0 sections is valid v16; behavior
degrades gracefully old↔new (an old client ignores the clear; an old server sends `up_to_date`).

### WS4 — Durable timestamp-cache invalidation (#2)

A dirty broadcast invalidates the server `ColumnTimestampCache` in memory only; it persists
~every 5 min, so a crash inside that window resurrects invalidated entries → false `up_to_date`
for a broadcast-missing client.

- **Debounced save-on-invalidate, entirely on the processing thread.** The main-thread
  `invalidateTimestamps` producer only posts the existing mailbox event; `applyEvents` (already
  on the processing thread) applies the invalidation and sets a dirty flag. `processCycle`
  triggers a save when dirty, mirroring the existing `SAVE_INTERVAL_CYCLES` block (same thread,
  same `snapshotForSave` + per-instance `saveExecutor` + atomic-move). Do NOT touch cache/dirty
  state from the main thread.
- **`invalidate()` returns a removed-count** so the dirty flag trips only on a real removal (no
  save when a broadcast's positions were never cached).
- **Max-delay cap (coalesce, don't reset):** the save fires within `N` cycles of the **first**
  dirty since the last save, so continuous churn cannot starve durability. Add a **min-interval
  floor** (a few seconds) so the on-processing-thread `snapshotForSave` deep-copy does not run
  per-cycle under a high edit rate (or snapshot only changed keys — decide in the plan).
- **Durability scope (honest):** atomic-move gives atomicity, not power-loss durability (no
  fsync). The "~5 min → seconds" window applies to graceful/process crashes; power-loss /
  kernel-panic durability is deferred to the tombstone fallback (documented, not built now).

### WS5 — Cross-dimension ingest report (#36)

`LodRequestManager.onIngestFailure` drops a report whose dimension ≠ current, leaving a false
received-stamp in the old dimension's **already-saved** cache (the stamp was persisted on the
dimension change). A per-session in-memory side-store is insufficient because the false stamp is
already on disk. Fix with a **targeted persistent unstamp**: a cross-dimension report triggers a
`ColumnCacheStore` load-remove-save of that `(server, dimension)` file on the IO thread, removing
the position **only if the stored value matches the stale timestamp** (so a legitimate re-serve
in the meantime is never clobbered), one-shot. Bound the pending set with a hard cap + GC-on-load
sweep; the WS1 v4 discard subsumes any pending intent across an upgrade, so no cross-version
ordering rule is needed.

## 4. Components touched

- `common/`: `IncomingRequestRouter` (WS3 claims-data threading + emptied resolution),
  `OffThreadProcessor` (WS3 all-air serve paths, WS4 debounced save + dirty flag),
  `ColumnTimestampCache` (WS4 `invalidate` returns count + dirty flag), `PendingRequest`,
  `ChunkReadResult`, `DedupTracker.Attachment`, `TickSnapshot.GenerationReadyData` (WS3
  claims-data bit), `AbstractChunkDiskReader` (WS3 bit passthrough).
- `fabric/` client: `ColumnStateMap` (WS1, WS2), `LodRequestManager` (WS1 drain, WS2, WS5),
  `ColumnCacheStore` (WS1 v4 + dead-migration removal, WS5 targeted unstamp),
  `ClientColumnProcessor` (WS3 authoritative clear off-thread), `LSSApi`/`VoxyCompat` (WS3
  air-section dispatch), `SpiralScanner` (WS2 ring re-walk hook).
- `fabric/` server: `DirtyColumnBroadcaster` (WS2 comment/test), `FabricOffThreadProcessor`,
  `ChunkDiskReader`, `ChunkGenerationService`, `SectionSerializer` (WS3 claims-data serve).
- `paper/`: `PaperDirtyColumnBroadcaster` (WS2), `PaperOffThreadProcessor`,
  `PaperChunkDiskReader`, `PaperChunkGenerationService`, `PaperRequestProcessingService`,
  `PaperPayloadHandler` (WS3 twins — 0-section column encode/decode parity), `PaperConfig` n/a.
- **No `LSSConstants.PROTOCOL_VERSION` bump; no new payload; no `RESPONSE_*` addition.**

## 5. Error handling & edge cases

- **Clock skew neutralized by construction:** after WS1 the client never sends a self-generated
  timestamp, so `cachedTs <= clientTimestamp` only ever compares server-issued values. Keep the
  server-ahead skew test; add a client-ahead test that now cannot produce a false `up_to_date`.
- **All-air re-park each session** is expected and cheap (batched `up_to_date`), but it removes
  all-air positions from the client's `receivedCount`/`emptyCount`; the soak
  disc-completeness floor must be updated (see §6) before any void-dimension scenario.
- **Air→content within a session** must heal (WS1 `markDirtyIfKnown`/`sessionSatisfied` fix) —
  the critical-flaw regression; covered by a dedicated test.
- **Partial vs full clear** both heal via WS3's authoritative-clear.
- **Ingest-park:** transient failures heal next session (re-ask `-1`); permanent ones cost one
  attempt/session. Do NOT reset a park to a fabricated or retained `>0` stamp.
- **Dimension round-trips (A→B→A):** `sessionSatisfied`, `staleInFlight`, the pending-unstamp
  store are per-dimension / cleared on change, consistent with existing keying.

## 6. Testing

**Pinned tests to REWRITE (behavior changes; the first draft wrongly said "still pass"):**
- `ColumnStateMapTest`: `onUpToDateStampsAbsentPositions` and the ingest-park stamp tests
  (`dirtyHealsIngestParkedPositionForExactlyOneAttempt`, `ingestParkStampPersistsToCache…`,
  `ingestFailureCapParksThePosition`) → assert `SATISFIED` via `sessionSatisfied` with
  `timestampFor == -1` and **no client-clock stamp persisted**; dirty un-parks to a re-ask.
- `LodRequestManagerTest.drainSendsTheScanTimeTimestampWhenClassificationChangedSinceScan` and
  `LodRequestManagerTickTest` **CL-071** (`stalePreTeleport…`, line 347: `5000L` → re-derived
  `-1L`) → assert the re-derived ts; note the server distance guard still absorbs the request so
  CL-071's send-once + in-flight-churn-bound essence holds.
- `SerializerParityGameTests.becomesAllAirReServeResolvesUpToDateWithNoColumnPayload`
  (FP-054/CL-074) → `ts>0` all-air re-serve now yields a 0-section column; `ts≤0` still yields
  `up_to_date`.
- `ColumnCacheStore` v1/v2 migration tests → removed with the dead migration code (v4 discards).
- `ProtocolConstantsTest` — **unchanged** (no version bump).

**New coverage:**
- Tier 1: `sessionSatisfied` classify precedence (dirty > sessionSatisfied); no-clock-stamp on
  up-to-date/park; `markDirtyIfKnown` un-parks a `stored==-1` sessionSatisfied position and
  triggers a ring re-walk; drain re-derives ts (incl. gen/sync slot-type preserved by seeding
  matching column state); v4 discards `<4`; WS3 claims-data bit chooses emptied vs up_to_date at
  each serve path; client-ahead skew is not false-`up_to_date`; WS4 debounced save fires within
  the cap and only on real removals; WS5 targeted unstamp matches-then-removes.
- Tier 2 (Fabric gametests): content→air (full **and** partial) delivers a clearing column
  end-to-end; an all-air position that gains content heals within one session; a dirty crossing
  an in-flight first serve heals within a session.
- Tier 3 (client gametest): the consumer receives and applies an authoritative/0-section column
  (air sections), off the render thread, with **no** `reportIngestFailure`.
- Soak: extend `dirty-*` with an emptied-column and a client-clock-skew scenario; a new
  `cold-restart-after-invalidate` scenario asserting no false `up_to_date` post-crash (WS4);
  update `make_disc_completeness` (or gate void scenarios) for the dropped all-air counts (#25);
  Paper soak twins for WS2/WS3/WS4.
- Regression guard: End-loop and ingest-park still terminate under `sessionSatisfied`
  (non-vacuously — assert **zero** client-clock values ever enter `timestamps` or the cache).

## 7. Rollout / compatibility

- **No protocol bump** (WS3 reuses `VoxelColumnS2CPayload`); behavior degrades gracefully across
  mismatched client/server versions.
- **Client cache v4:** old caches discarded on first load post-upgrade (one cold resync).
- **Server timestamp cache:** no format change (WS4 is debounced saves only).

## 8. Sequencing (for the implementation plan)

0. **Verify Voxy clear-on-air-ingest** — for both absent-section-in-a-normal-column and the
   0-section column (gates WS3's user-visible payoff, not its code).
1. **WS1** (clock honesty + the critical dirty/`sessionSatisfied` interaction) — foundational.
2. **WS2** (dirty timing) — builds on WS1's `sessionSatisfied`/`markDirtyIfKnown`.
3. **WS4** (durable invalidation) — server-local, independent.
4. **WS3** (authoritative-column ghost clear) — largest surface; claims-data threading + client
   materialization + both platforms.
5. **WS5** (cross-dim unstamp) — small, independent, last.

Each workstream is independently shippable and testable; WS1 first because WS2/WS3/WS5 assume an
honest timestamp map.

## 9. Post-implementation review (since-release audit, 2026-07-02)

A multi-agent adversarial review of `v0.4.1..HEAD` surfaced 8 findings. Dispositions:

**Fixed:**
- **#1 dirty crossing an in-flight RESYNC** — WS2's `staleInFlight` only covered first serves
  (`stored==-1`). `LodRequestManager.onDirtyColumns` now calls `noteStaleIfInFlight`
  unconditionally, so a second edit crossing an in-flight resync survives `onReceived`'s
  dirty-clear and re-requests. (`LodRequestManagerTest.dirtyCrossingAnInFlightResyncReRequests`.)
- **#2/#3 rejected authoritative clear stranded ghost terrain (WS3 completion)** — a consumer
  rejecting a 0-section content→air clear dropped the client stamp to `-1`; a `ts=-1` re-request
  draws an all-air `up_to_date` (a clear is only sent for `claimsData`/`ts>0`), so the ghost
  persisted for the session. Root cause: `onIngestFailed`'s `-1` drop is correct for lost
  *content* (server re-serves on `ts=-1`) but wrong for a lost *clear* (server invalidated its
  cache, so `ts>0` correctly re-draws the clear). Fix: `ColumnStateMap` tracks `clearedResync`
  (packed → pre-clear content stamp); a rejected clear re-requests with that pre-clear stamp — a
  real server-issued value `<` the server's cached clear stamp, so the `up_to_date` check fails
  and the clear is re-sent. Wire form of a clear is detected by `ClientColumnProcessor.isClearColumn`
  (leading section-count varint == 0). Bounded by the existing ingest-failure cap.
- **#6 saveExecutor shutdown race** — a processing thread outliving the join timeout could reach
  the periodic-save `execute()` after `saveExecutor.shutdown()` and throw
  `RejectedExecutionException` (surfacing as a spurious shutdown ERROR). The periodic-save site
  now swallows it at debug; the unconditional shutdown (classloader-leak guard) is unchanged.
- **#7 Fabric/Paper dimension-context parity** — `FabricOffThreadProcessor.updateDimensionContext`
  used `putIfAbsent` while the Paper twin used `put`; a recreated dimension (LAN re-publish /
  dynamic-dimension mods) would keep a stale `ServerLevel`. Now `put`, matching Paper.

**Documented residuals (deferred, self-healing or scope-gated):**
- **#4 Paper `updateEvents` upgrade gap (LOW).** `EntityExplodeEvent` was added to the default
  list, but `JsonConfig.load` overwrites defaults with an existing file, so already-run Paper
  servers never register it (explosion-caused terrain changes aren't detected; Fabric catches
  them via the save hook regardless). Not auto-fixed because `updateEvents` is an intentionally
  *user-customizable* list (the defaults' comments document deliberate inclusions/omissions and
  invite admin edits) — a union-merge would break the "admins can remove events" contract, and a
  versioned config migration is a separate feature. **Upgrade note for admins:** add
  `org.bukkit.event.entity.EntityExplodeEvent` to `updateEvents`, or delete the config to
  regenerate. New installs already include it.
- **#5 WS4 whole-cache deep-copy on invalidation (LOW, extreme scale).** The invalidation-triggered
  save deep-copies the entire timestamp cache (`snapshotForSave`) as often as every dirty-broadcast
  interval (~10 s) under sustained in-range edits, vs every 5 min pre-diff. The ~2 s debounce
  bounds frequency but not per-copy cost, which scales with cache size (tens of ms near the 32 MB
  cap). Tuning lever: raise `INVALIDATE_SAVE_MAX_CYCLES` (larger debounce, wider durability window)
  or move to an incremental save. Only bites a near-cap cache with a redstone/world-eater workload.
- **#8 resync air-fill vs unparseable sections (LOW), tied to deferred #16 (DataFixer).**
  `withAirFilledAbsentSections` treats every absent section as air, but `NbtSectionSerializer`
  omits a section whose `block_states` fails to parse (region corruption / removed-mod palette),
  not only all-air ones — so a disk-served resync could clear a still-present but unreadable
  section to air. The wire form doesn't distinguish "authoritatively air" from "unreadable"; fixing
  it is a serializer/protocol change that belongs with the broader old-chunk DataFixer work (#16).
