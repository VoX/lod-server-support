# Delivery-Honesty Hardening — Design

**Date:** 2026-07-01
**Status:** Approved (design), pending implementation plan
**Scope:** The full delivery-honesty finding cluster from the 2026-07-01 code review:
#2, #4, #5, #11, #13, #21, #36, #43 (plus #3/#14 addressed as documented tradeoffs).

## 1. Problem

LSS's protocol is built on a **self-healing delivery-honesty invariant**: every response
is a statement about a packed position, so a lost/late/duplicate answer is idempotent and a
client that is missing a column eventually re-requests and gets it. The review found eight
places where that invariant silently breaks, leaving a client rendering **stale or ghost LOD
terrain permanently** (until an unrelated future edit or a cache flush).

They share one structural root cause plus four smaller mechanism-specific gaps.

### Root cause: the client conflates two different facts

The client tracks each column with a single `timestamps` map value (`ColumnStateMap`):
`-1` = never seen, `0` = server said not-generated, `>0` = received/validated at that
server epoch-second. The protocol's freshness check on the server
(`IncomingRequestRouter.resolvedFromTimestamp`: `cachedTs <= clientTimestamp`) is only valid
because **both sides compare server-issued timestamps** — the invariant pinned by
`IncomingRequestRouterTest` ("the client stamps the SERVER-sent columnTimestamp verbatim,
never its own clock").

Two code paths violate it by writing the **client's own wall clock** into `timestamps`:

- `ColumnStateMap.onUpToDate` (currently line ~123): for a `-1` (all-air, never carries a
  `VoxelColumn`) or `0` (not-generated since resolved) position, it does
  `put(packed, epochSeconds())`. This exists to escape the `stored == -1` early-return in
  `classify`, which otherwise re-requests every scan forever (the confirmed **End
  ~50 req/s loop**).
- `ColumnStateMap.onIngestFailed` park branch (currently line ~157): after
  `MAX_INGEST_FAILURES`, it parks the position with `put(packed, epochSeconds())` +
  `validated` so a permanently-rejecting consumer stops driving a full re-download each scan.

Both fabricated values persist to `ColumnCacheStore` and return next session as the
`clientTimestamp`. Because a client-clock value is unrelated to the server's serve-time, a
client clock ahead of the server (or simply a park stamp taken after the real serve) makes
`cachedTs <= clientTimestamp` read **true** → false `up_to_date` → permanent hole. #3/#14 are
adjacent tradeoffs already documented in code (probe-seed suppression); this design does not
change those, but the honesty split below removes the class of bug they warn about.

## 2. Unifying model

Separate **"what server data do I hold"** from **"have I resolved this position this
session"**:

| Fact | Home | Values | Persisted |
|------|------|--------|-----------|
| Held server data | `timestamps` map | `-1` none, `0` not-generated, `>0` server epoch-sec | yes (only server-issued) |
| Session resolution | new `sessionSatisfied` set | membership | **no** (cleared on reconnect / dimension change, like `validated`) |

`classify` consults `sessionSatisfied` **first** and returns `SATISFIED` on a hit — stopping
the within-session re-request loops (End loop, ingest-park) **without ever writing a
timestamp**. Across sessions the set is empty, so a parked/all-air position re-asks once with
its honest `ts` (`-1`), the server re-resolves cheaply (`up_to_date` batch response), and the
position re-parks. `timestamps` — and therefore `ColumnCacheStore` — now only ever holds
server-issued values.

## 3. Workstreams

### WS1 — Clock honesty (#4, #13, #5)

1. **`ColumnStateMap`**
   - Add `sessionSatisfied` (`LongOpenHashSet`), cleared wherever `validated` is cleared.
   - `classify`: `if (sessionSatisfied.contains(packed)) return SATISFIED;` as the first rung,
     before the `stored == -1` return. Dirty must still win: a dirty broadcast removes the
     position from `sessionSatisfied` (see WS2) so it re-requests.
   - `onUpToDate`: for `stored == -1 || stored == 0`, `sessionSatisfied.add(packed)` instead
     of `put(epochSeconds())`. Leave `timestamps` unchanged (honest). Real `>0` up-to-date
     still validates as today.
   - `onIngestFailed` park branch: `sessionSatisfied.add(packed)` instead of
     `put(epochSeconds())`; keep the failure-count cap semantics.
2. **`LodRequestManager.drainQueue` (#5):** re-derive the timestamp from
   `classify(pos, generationEnabled)` at send time and send THAT (skip on `SATISFIED`), rather
   than the scan-time `queue.peekTimestamp()`. The queued timestamp becomes advisory. This
   closes the window where a stamp is forgotten (ingest failure) between scan and drain and a
   stale `ts>0` would otherwise be sent for data the client no longer holds.
3. **`ColumnCacheStore` cutover:** bump `FORMAT_VERSION` 3 → 4; **discard** any file with
   `version < 4` on load (a pre-fix cache may hold fabricated stamps that are indistinguishable
   from real ones). One cold resync per server/dimension after upgrade; the cache is
   rebuildable. Save format is unchanged except the version int.

**No wire-protocol change.** Server unchanged for WS1.

### WS2 — Dirty-notification timing races (#11, #43)

1. **#11 — dirty crossing an in-flight first serve.** A `DirtyColumns` notification for a
   position that is in-flight on its first serve (`stored == -1`, so `markDirtyIfKnown` drops
   it) must not be lost. Add a `staleInFlight` set: when a dirty notification arrives for a
   position the `InFlightTracker` currently holds, record it. `onReceived` checks
   `staleInFlight`; on a hit it clears the mark and **re-requests** (does not `validated.add`),
   because the arriving column predates the edit. `markSent`/prune clear stale entries so they
   cannot accumulate for positions nothing will consume (mirrors the `onIngestFailed`
   confirmed-ring caution).
2. **#43 — done-bit clear vs notification ordering.** The broadcaster clears server done-bits
   via a mailbox event (`clearDiskReadDone`) applied in `applyEvents`, which already precedes
   `routeIncomingRequests` within a processing cycle, while the `DirtyColumns` frame goes on the
   wire immediately. The clear is therefore effective before any re-request (which arrives
   ~network+scan latency later) is routed — the invariant holds unless the processing thread
   stalls longer than the client's re-request latency. Make this explicit and robust: gate the
   `up_to_date`-from-done-bit answer so a position with a pending (not-yet-applied) dirty-clear
   for that player re-resolves rather than answering stale. Fix the misleading
   "done-bits clear before the send" comment to state the real ordering guarantee. Applies to
   both `DirtyColumnBroadcaster` and `PaperDirtyColumnBroadcaster`.

**No wire-protocol change.**

### WS3 — All-air conveyance (#21) — protocol v16 → v17

Today an emptied column (content → all-air, e.g. WorldEdit clear) is served as empty bytes and
answered `ColumnUpToDate` on every path, so a client that holds the pre-empty content keeps
**ghost terrain** forever.

- **Wire:** add `RESPONSE_EMPTIED` handling. When the server resolves a column to all-air
  (empty section bytes) **and the request claims data (`clientTimestamp > 0`)**, send an
  emptied response carrying the server `columnTimestamp`; a `clientTimestamp <= 0` request
  (client has nothing) still gets `up_to_date`. Bump `LSSConstants.PROTOCOL_VERSION` to 17.
  Both encoders (`VoxelColumnS2CPayload`/`BatchResponseS2CPayload` on Fabric,
  `PaperPayloadHandler`) must stay byte-identical; add wire-parity + edge tests.
- **Client:** on `emptied`, materialize an all-air `VoxelColumnData` (air sections across the
  level's section-Y range) and dispatch to consumers so a consumer that overwrites on ingest
  clears the stored content; stamp the position honestly with the server timestamp (`>0`) so
  future resyncs are correct.
- **Trigger already exists:** a content→air transition changes the Fabric
  `DirtyContentFilter` hash (all-air sentinel) → dirty broadcast; Paper's block/WorldEdit
  events fire the same. So the client re-requests with `ts>0` and reaches the emptied path.
- **Consumer-capability risk (must validate FIRST):** this only clears ghost terrain if Voxy's
  `rawIngest` overwrites a stored section when handed an all-air `LevelChunkSection`. **Task 0
  of implementation is to verify this against Voxy.** Per decision, we ship the protocol path
  regardless: LSS becomes correct end-to-end and any consumer that can clear benefits
  immediately; if Voxy cannot clear, ghost terrain persists only until Voxy adds support, and
  that limitation is documented.

### WS4 — Durable timestamp-cache invalidation (#2)

A dirty broadcast invalidates the server `ColumnTimestampCache` in memory only; the cache
persists every ~5 min, so a crash inside that window resurrects invalidated entries → false
`up_to_date` for a broadcast-missing client.

- **Debounced save-on-invalidate:** when `invalidateTimestamps` removes entries, mark the cache
  dirty and schedule a save within a small number of processing cycles (debounced so a burst of
  invalidations coalesces into one save). Shrinks the crash window from ~5 min to seconds with
  no format change. Reuse the existing per-instance save executor (post-Wave-4 fix) and the
  atomic-move save. Tombstones (exact durability, format change + GC) are the documented
  fallback if the debounced window proves insufficient.

### WS5 — Cross-dimension ingest report (#36)

`LodRequestManager.onIngestFailure` drops a report whose dimension ≠ current, leaving a false
received-stamp in the old dimension's already-saved cache. Add a small persistent
**pending-unstamp side-store** keyed by `(serverAddress, dimension, packed)`: a cross-dimension
report records an unstamp intent that is applied when that dimension's cache is next loaded
(the position is removed before it can be re-sent as a false `ts>0`). Bounded and rebuildable.

## 4. Components touched

- `common/`: `IncomingRequestRouter` (WS2 done-bit gate, WS3 emptied resolution),
  `OffThreadProcessor` (WS3 all-air serve path, WS4 debounced save), `ColumnTimestampCache`
  (WS4 dirty flag), `LSSConstants` (WS3 protocol v17 + `RESPONSE_EMPTIED`), payload constants.
- `fabric/` client: `ColumnStateMap` (WS1, WS2), `LodRequestManager` (WS1 drain, WS2, WS5),
  `ColumnCacheStore` (WS1 v4, WS5 side-store), `ClientColumnProcessor`/`LSSClientNetworking`
  (WS3 emptied handling), `LSSApi`/`VoxyCompat` (WS3 air-section dispatch).
- `fabric/` server: `DirtyColumnBroadcaster` (WS2), `VoxelColumnS2CPayload`/
  `BatchResponseS2CPayload` (WS3 wire).
- `paper/`: `PaperPayloadHandler` (WS3 wire parity), `PaperDirtyColumnBroadcaster` (WS2),
  `PaperRequestProcessingService`/`PaperOffThreadProcessor` (WS3/WS4 twins).

## 5. Error handling & edge cases

- **Clock skew is neutralized by construction:** after WS1 the client never sends a
  self-generated timestamp, so `cachedTs <= clientTimestamp` only ever compares two
  server-issued values. The existing server-ahead skew test stays; add a client-ahead test that
  now cannot produce a false `up_to_date`.
- **All-air re-park each session** is expected and cheap (batched `up_to_date`/`emptied`
  responses); assert it does not regress into a within-session loop.
- **Dimension round-trips (A→B→A):** `sessionSatisfied`, `staleInFlight`, and the pending-unstamp
  store are per-dimension / cleared on change, consistent with existing per-dimension keying.
- **Emptied vs not-generated:** an all-air chunk that never had content still resolves
  `up_to_date` for `ts<=0`; only `ts>0` (client claims data) triggers `emptied`.

## 6. Testing

- **Tier 1 (JUnit):** `ColumnStateMap` — `sessionSatisfied` classify precedence, no-clock-stamp
  on up-to-date/park, dirty un-parks; `LodRequestManager` — drain re-derives ts, cross-dim
  report side-store; `ColumnCacheStore` — v4 discards v<4, save holds only server stamps;
  wire-parity + edge for `RESPONSE_EMPTIED`; `ColumnTimestampCache`/`OffThreadProcessor` —
  debounced save-on-invalidate; `IncomingRequestRouter` — emptied resolution + client-ahead
  skew no longer false-`up_to_date` + done-bit-clear gate.
- **Tier 2 (Fabric gametests):** content→air transition delivers an emptied column end-to-end;
  a dirty broadcast crossing an in-flight first serve heals within a session.
- **Tier 3 (client gametest):** consumer receives and applies an emptied column (air sections).
- **Soak:** extend `dirty-*` scenarios with an emptied-column case and a client-clock-skew case;
  a new `cold-restart-after-invalidate` scenario asserting no false `up_to_date` post-crash
  (WS4). Paper soak twins for the WS2/WS3/WS4 server changes.
- **Regression guard:** the End-loop and ingest-park tests must still pass with
  `sessionSatisfied` instead of the fabricated stamp (non-vacuously — assert zero client-clock
  values ever enter `timestamps`/the cache).

## 7. Rollout / compatibility

- **Protocol v17:** the handshake version gate degrades gracefully (mismatched peer → LOD
  disabled until both update). LSS ships matched client+server, so this is a clean bump; older
  LSS peers lose LOD until updated. Note in release notes.
- **Client cache v4:** old caches discarded on first load post-upgrade (one cold resync).
- **Server timestamp cache:** no format change (WS4 is debounced saves only).

## 8. Sequencing (for the implementation plan)

0. **Verify Voxy clear-on-air-ingest** (gates WS3's user-visible payoff, not its code).
1. WS1 (clock honesty) — foundational; unblocks the honest-timestamp assumption the rest lean on.
2. WS2 (dirty timing) — builds on WS1's `sessionSatisfied`/dirty interaction.
3. WS4 (durable invalidation) — server-local, independent.
4. WS3 (all-air conveyance, protocol v17) — largest surface; wire + client + consumer.
5. WS5 (cross-dim report) — small, independent, last.

Each workstream is independently shippable and testable; WS1 first because WS2/WS3/WS5 assume
the timestamp map is honest.
