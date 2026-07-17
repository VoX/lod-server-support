# v16 Legacy Client Compatibility Layer — Design

**Status:** DESIGN ONLY — not implemented. Written 2026-07-17 against `feat/want-set-requests`
(protocol 18) using a full study of the last v16 release (`v0.6.2`, MC 26.2).
Adversarially reviewed the same day (one reviewer vs the v0.6.2 client code, one vs the
current server internals); all confirmed findings are folded in below — §11 maps each
finding to its amendment so none get silently lost in a rewrite.

## 1. Purpose & scope

Let clients running the released v0.6.x mods (protocol **16**, MC 26.2) get a working —
slower, rougher — LOD session against a v0.7.0+ server, instead of today's silent
no-session at the handshake's version rung.

**Goals**
- A v0.6.2 client joins, handshakes, and steadily loads LOD chunks on both Fabric and
  Paper/Folia servers.
- The translation layer is a **separate, self-contained handler**: the v17 pipeline
  (router, per-player state, off-thread processor, generation, dedup, broadcasters) is
  not modified and never learns that v16 clients exist. The only touched internals are
  existing *seams* (handshake gate, batch ingress branch, the per-player send seams).
- Conservative and minimal. Where fidelity to old server behavior conflicts with
  isolation, isolation wins — we accept slower/hackier service for old clients.

**Non-goals**
- Throughput or convergence parity with a real v16 server (v16 clients self-cap at ~216
  in-flight vs the v17 client's 800-wide want-set — expect roughly 4× slower backfill).
- v17/v18 *client* against a v16 *server* (client side is untouched).
- Faithful re-implementation of v16 server conventions (rate-limit bounces on slot
  pressure, generate-only-when-asked, duplicate-bounce). Explicitly broken; see §6.
- Soak/benchmark coverage of v16 sessions (the harness clients are v17-only).

## 2. Verified wire delta (v16 → current v18)

Verified by direct diff of `PaperPayloadHandler` + `LSSConstants` + the Fabric payload
records between `v0.6.2` and HEAD. Channel IDs are unchanged across versions.

| Payload | Direction | Delta |
|---|---|---|
| `HandshakeC2SPayload` | C2S | **byte-identical** (VarInt version, VarInt caps) |
| `BatchChunkRequestC2SPayload` | C2S | **byte-identical** (VarInt count, then `long pos`/`long ts` pairs) |
| `SessionConfigS2CPayload` | S2C | **changed**: v16 = 6 fields — `protocolVersion:VarInt, enabled:bool, lodDistanceChunks:VarInt, syncOnLoadConcurrencyLimitPerPlayer:VarInt, generationConcurrencyLimitPerPlayer:VarInt, generationEnabled:bool`. v18 dropped the two cap VarInts. |
| `VoxelColumnS2CPayload` | S2C | **changed**: v18 inserts one `source` byte between `columnTimestamp` and `sectionBytes`. v16 has no such byte. |
| `BatchResponseS2CPayload` | S2C | **byte-identical**. Bytes 1 (`UP_TO_DATE`) / 2 (`NOT_GENERATED`) were deliberately never renumbered. Byte 0 (`RATE_LIMITED`) is retired-but-reserved on v18 and still fully understood by v16 clients. |
| `DirtyColumnsS2CPayload` | S2C | **byte-identical** |

So: **C2S needs no translation at all**; S2C needs exactly two re-encodes
(SessionConfig, VoxelColumn) plus pass-through for the rest.

**Load-bearing:** the v0.6.2 client's SessionConfig codec hard-gates on the leading
VarInt — `version != 16` drains the buffer and returns a disabled config, and the client
never starts a session (`SessionConfigS2CPayload.java:32-40 @ v0.6.2`). The shim **must
echo protocol version 16**, not 18, inside the legacy-shaped reply.

## 3. The v16 client behavior contract (what the shim must satisfy)

From the v0.6.2 study. The essentials, most load-bearing first:

1. **Self-healing via a 10 s timeout sweep.** Any in-flight request unanswered for 10 s
   (`LodRequestManager.TIMEOUT_NANOS`) is evicted, marked retry, and re-sent on a later
   scan. Silent server-side drops are *tolerated* — they just cost a 10 s round trip.
   This is the property the whole shim leans on: nothing we drop is ever fatal.
   **Caveat:** the sweep rides the scan phase — sustained chunk-boundary movement
   restarts the scan cadence and the backpressure halt returns before it, so a flying
   or halted client refreshes *nothing* until it rests. The shim's TTL must outlast
   that starvation (§4.3), not assume an ~11 s refresh floor.
2. **The advertised caps ARE the client's pacing.** The client keeps at most
   `syncOnLoadConcurrencyLimitPerPlayer` sync + `generationConcurrencyLimitPerPlayer`
   generation requests in flight (defaults 200/16), and its scan budget is
   `syncCap × 4`. These come from SessionConfig — the values we echo directly control
   how hard the old client pushes.
3. **Drip-feed, not want-set.** Discovery scan every 20 ticks fills a queue; each tick
   drains up to `max(16, ceil(scanSize/20))` positions into one batch (≤1024). New
   batches contain only *newly drained* positions — the client never re-declares its
   in-flight set (the scanner and drain both suppress in-flight positions).
4. **ts semantics:** `-1` = "I hold nothing, first serve" (v16 servers would *never*
   generate for this); `0` = generate-retry of a 0-stamped position — usually a prior
   `NOT_GENERATED` answer, but persisted 0-stamps also re-ask as `ts=0` straight off a
   warm reconnect with no in-session answer (never assume 0 implies "we said
   NOT_GENERATED this session"); `>0` = resync of held data (answer `UP_TO_DATE` iff
   server stamp ≤ ts). On reconnect the client re-asks every cached column with its
   stored epoch-second ts.
5. **`RATE_LIMITED` (byte 0) is a soft bounce:** the client skips its next discovery
   scan (~1 s global pause) and re-queues the position via retry-mark. Safe to send
   liberally; no exponential backoff exists.
6. **`NOT_GENERATED`** stamps the position 0. With generation enabled the client
   retries it as `ts=0` roughly every scan, forever; with generation disabled it parks
   until a dirty broadcast or reconnect.
7. **`UP_TO_DATE`** permanently satisfies the position for the session. A false
   `UP_TO_DATE` for data the client never received is an invisible hole — the current
   server's honesty ladder (skip-don't-lie on in-pipeline duplicates, ts≤0 re-resolution)
   already protects this without shim involvement.
8. **Dirty broadcasts** are the only revival for satisfied/parked positions; the client
   re-requests them itself with its stored stamp. Wire-identical → pure pass-through.
9. **Convergence is silence.** A stationary converged v16 client stops transmitting.
10. **Backpressure:** above 6000 queued undecoded columns the client hard-halts its
    entire request loop (including the sweep) and sends nothing until it drains. It
    never sends the v17 edge-triggered empty clear batch.

## 4. Design overview

One new module in `common/` (`dev.vox.lss.common.compat` — name TBD) with two classes,
plus small platform glue at existing seams:

- **`V16CompatManager`** — one per service. Owns a `UUID → V16CompatSession` map,
  created at a v16 handshake, dropped at network disconnect. Exposes the calls the
  platform glue wires in: `onHandshake`, `onClientBatch`, `tick` (1 Hz re-declare),
  `rewriteColumn`/`observeColumnSent`, `observeBatchResponse`, `onServiceRemove`,
  `onDisconnect`, `isV16(uuid)`.
- **`V16CompatSession`** — per-player recordkeeping: the **synthetic want-set**, an
  insertion-ordered `pos → (clientTs, lastSeenNanos)` map that converts the old drip-feed
  into v17-style full-set declarations.

The pipeline sees a v16 player as a perfectly ordinary v17 player whose "client"
(actually the shim) declares a want-set. All v16-specific knowledge lives in the shim
and in the two payload records' legacy encode branches.

### 4.1 Handshake: a wire-dialect rung in the shared gate

`HandshakeGate` gains a dialect, not a new ladder: `Decision` grows a
`WireDialect { V18, V16 }` field. Version rung becomes:

- `version == PROTOCOL_VERSION` → dialect `V18`, ladder unchanged.
- `version == 16 && v16CompatEnabled` → dialect `V16`, then the **same** ladder
  (NO_CONSUMER / DISABLED / REGISTER) runs unchanged.
- anything else → `VERSION_MISMATCH`, silent, as today.

This keeps reply/registration policy in the one shared, unit-tested place (the existing
cross-platform drift guarantee). Platform glue then:

- Replies with the **v16-shaped 6-field SessionConfig**, echoing `protocolVersion=16`
  and filling the two legacy caps with the server's *real* admission values:
  `syncOnLoadConcurrencyLimitPerPlayer = SYNC_ON_LOAD_SLOT_CAP` (200) and
  `generationConcurrencyLimitPerPlayer = config.generationConcurrencyLimitPerPlayer`
  (default 16). Principled (we advertise what we actually admit) and it reproduces the
  old client's default pacing exactly (200/16, scan budget 800 = `WANT_SET_BUDGET`).
- On REGISTER: `service.registerPlayer(...)` as normal **plus**
  `compatManager.onHandshake(uuid)`.

### 4.2 Incoming: drip-feed → synthetic want-set

One branch at the top of each platform's `handleBatchRequest` (this is a seam, not
pipeline internals — it's where range filtering already happens):

```
if (compat.isV16(uuid)) { compat.onClientBatch(state, positions, timestamps); return; }
```

`onClientBatch`, after the normal range filter:

1. **Merge only — ingress NEVER declares.** Each position merges into the synthetic
   want-set (`lastSeen` refreshed; the client's declared ts always wins an update — the
   client is the sole authority on what it holds, see §4.4). The v16 client drips up to
   20 batches/s while backfilling; offering per batch would fight every mechanism tuned
   for a 1 Hz declare cadence — on Folia each `republishHeldBatch` `compareAndSet(null,
   held)` would find a freshly offered mailbox and drop the held batch as perpetually
   superseded (duty-cycled routing starvation, zero region-probe coverage), and
   `service.superseded`/`requests_received` would inflate by ~10⁴/s per v16 player,
   drowning the counters every dashboard treats as load signals. Declaration happens
   only in `tick()` (§4.3). **No ts translation is needed**: v16 `-1`/`>0` mean the
   same thing in v17, and the v17 router already routes the retired `0` identically to
   `-1` (pinned as inert). The v16 "don't generate on ts=-1" convention is deliberately
   *not* honored — server-owned generation serves first-time chunks better than the old
   client knew to ask for (accepted break, §6).
2. **The merge must not require the registered service state.** On Paper the handshake
   reply outruns the mailboxed registration by up to a pump tick, and a drip batch
   landing in that gap would otherwise be dropped at a 10 s sweep cost. The session
   (created at handshake, keyed by UUID) absorbs merges immediately; the 1 Hz tick
   starts declaring once the state exists.
3. **Post-reset ingress grace (~20 ticks):** after a want-set reset (`onServiceRemove`,
   i.e. a dimension change) incoming merges are discarded for ~1 s. Batches already in
   netty flight from the OLD dimension would otherwise merge into the fresh set, pass
   the range filter under Nether↔Overworld coordinate overlap near the origin, and be
   re-declared — generating wrong-dimension chunks for up to the TTL. The client's real
   first new-dimension batch arrives after its transition screen (well past 1 s); the
   rare fresh ask that hits the grace heals at the client's 10 s sweep.
4. **Overflow valve:** if a merge would push the set past `WANT_SET_BUDGET` (800), the
   overflow positions are *not* merged and are answered immediately with a
   `RESPONSE_RATE_LIMITED` batch — the one place byte 0 comes back to life, exactly what
   it was reserved for. The old client backs off ~1 s and retries later (its
   `skipNextScan` is a one-shot flag and drain is scan-independent, so repeated bounces
   halve discovery cadence at worst — no livelock). Sent directly from the ingress path
   via the platform's existing raw batch-response encoder, outside the pipeline's
   SendActionBatcher. With advertised caps 200/16 the steady-state set is ≈216 entries,
   so this fires mainly during warm-reconnect resync floods.

### 4.3 The 1 Hz declare — sole declarer, sorted, range-refiltered — and the TTL

`compat.tick()` runs from the service tick (MAIN/PUMP), every 20 ticks per session,
and is the **only** place a batch is ever offered:

- **Declare:** if the set is non-empty and the service state exists: (a) **re-apply the
  range filter against the player's current chunk**, evicting violators — the
  ingress-time filter cannot cover later movement, and a 1 Hz re-declare must not keep
  resurrecting out-of-range work; (b) **sort by Chebyshev distance from the player's
  current chunk**; (c) build the `IncomingBatch` and offer it, with build-and-offer
  inside the session lock so a slower concurrent build can never publish stale
  membership over a newer one. The sort is load-bearing, not cosmetic: the router
  stamps the live frontier from the *first not-yet-satisfied entry in declaration
  order* (`stampLiveFrontier`), and the spread gate, the probe's per-tick examination
  cap, and drain order all assume closest-first. An insertion-ordered set breaks that
  after movement — a stale-far head stamps a huge frontier ring and *neuters* the
  far-before-near gate while up to 800 stale entries starve the player's new near wants
  (re-opening the settled backfill-ordering regression). Sorting ≤800 entries once a
  second is trivial.
- **Unconditional at 1 Hz** (matching the v17 client): re-declaring an *unchanged* set
  is precisely what heals individual silent drops — a gen-slot-full miss removes one
  entry from the backlog while its neighbours stay retained, and only a fresh
  declaration restores it. The structural `superseded` this produces is the same
  accounting as any v17 client whose backlog outruns the declare — mechanism, not
  pressure (note in §7 diagnostics).
- **TTL sweep: 75 s.** Evict entries whose `lastSeen` no client re-ask has refreshed.
  30 s (the first draft) was wrong: the client's 10 s sweep rides its scan phase, and
  sustained movement starves both (§3.1) — a >30 s flight would TTL-evict positions the
  client still counts in flight, re-stalling them post-flight. With the declare-time
  range filter now carrying the geometric pruning (movement), the TTL's only remaining
  job is in-range abandonment (backpressure halt, ingest-drop churn), where slow
  draining is fine. Consequence: a decode-halted client keeps being served for up to
  ~75 s before its set drains — bounded by the old client's own 8000-column/256 MiB
  decode caps, which it survives (drops report ingest failures and re-ask).
- Empty set → offer nothing (convergence silence preserved).

### 4.4 Outgoing: two rewrites, one observation, two pass-throughs

- **`VoxelColumnS2CPayload`** — at the per-player column send seam (Fabric: the
  `ColumnPayloadSender` lambda in `RequestProcessingService.flushSendQueues`; Paper: the
  already-injectable `columnPayloadSender` field). For v16 players: strip the source
  byte (§5) and **prune** the position from the synthetic want-set (satisfied-by-data).
  Both seams run on MAIN/PUMP with the player in hand.
- **`BatchResponseS2CPayload`** — wire-identical, passes through untouched; the shim
  only **observes** it at the `drainSendActions` send site (both platforms) to prune
  `UP_TO_DATE`/`NOT_GENERATED` positions from the synthetic set.
- **`DirtyColumnsS2CPayload`** — untouched; the client's own dirty re-request flows
  back through §4.2 like any batch.
- **`SessionConfigS2CPayload`** — v16 shape constructed at the handshake site only (§4.1).

**Pruning is load-bearing, not an optimization** (first draft claimed otherwise —
wrong). The cheap `UP_TO_DATE`-off-the-done-bit answer exists only for `ts > 0`
re-declarations; a **ts ≤ 0** entry — the entire fresh-backfill population — that
survives its own serve is *honestly re-resolved* on every declare: done-bit cleared,
full disk re-read, full column re-sent, once per second until pruned or TTL'd. So
prune at both seams (column-sent and response-observed), and the only redundant serve
left is the unavoidable one: a client 10 s-sweep re-ask crossing its own serve in
flight re-adds the position and costs exactly one re-serve. Do NOT "fix" that by
upgrading a client's declared `ts=-1` to the served stamp — the client is the sole
authority on what it holds, and a fabricated `ts>0` would let a later `UP_TO_DATE`
seal a column the client failed to ingest: the invisible hole this protocol is built
to avoid. Late/duplicate serves to the client are idempotent (position-keyed), same
as v17.

## 5. Platform wire mechanics

The Fabric `StreamCodec`s have no per-connection context, so per-player shape selection
must happen at the send sites, carried by the payload object:

- **Fabric:** the two changed records gain a legacy encode branch — e.g.
  `SessionConfigS2CPayload` gets `legacySyncCap`/`legacyGenCap`/`v16Wire` fields
  (canonical 4-arg constructor defaults them off) and `VoxelColumnS2CPayload` gets
  `v16Wire` + an `asV16()` copy factory; each codec's *write* branches on the flag
  (6-field / source-less layout), the *read* side stays v18-only (the legacy variants
  are server-constructed, never decoded). This is the one deliberate touch outside the
  shim: the payload records are the wire layer — the translation layer's home turf —
  and vanilla Fabric offers no raw-bytes send path (vanilla `DiscardedPayload` cannot
  encode data; that trick is Paper-patched only).
- **Paper:** trivially raw already. Add `encodeSessionConfigV16(...)` and a
  `rewriteColumnToV16(byte[])` splice (drop 1 byte at the computed offset: 4+4, skip
  VarInt-prefixed UTF, +8, remove next byte) to `PaperPayloadHandler`; wrap the
  `columnPayloadSender` seam for v16 players. Position for pruning is read straight
  from the first 8 bytes.
- **Parity:** a Fabric/Paper v16 wire-parity test (same fixture → identical bytes) plus
  golden-byte fixtures derived from the v0.6.2 encoders, mirroring the existing
  cross-module golden corpus pattern.
- **Dialect selection must be structurally total, not per-producer.** A v18-shaped
  VoxelColumn reaching a v16 client is the design's one non-self-healing failure: the
  old decode reads the source byte as the section-array length VarInt and the leftover
  bytes hard-kick the client (a mis-shaped SessionConfig "only" kills the session
  silently). The column seam therefore converts *unconditionally* for v16 sessions —
  every producer (probe, disk, generation, the 0-section ghost clear) funnels through
  it, so no producer can leak the wrong shape — and asserts (warn-once + drop rather
  than send) if a frame is somehow already legacy-shaped or unconvertible. A dropped
  column self-heals by re-declaration; a kicked client does not.

## 6. Accepted degradations & convention breaks (deliberate, do not "fix")

1. **No `RATE_LIMITED` on slot/disk pressure.** The old server bounced; the new one
   retains in the backlog and the shim re-declares. The client just waits — strictly
   fewer wire round-trips, slower perceived progress under load.
2. **`ts=0`/"generate only when asked" not honored.** The v17 server generates on any
   miss when enabled. Old clients get *more* generation than they asked for; their
   generate-request lane (`genCap=16`) sits mostly idle because fresh chunks are served
   off `ts=-1` sync asks before a `NOT_GENERATED` ever teaches the client to ask.
3. **`NOT_GENERATED` now means permanent-only.** On a gen-enabled server it fires only
   on hard generation failure — and the v16 client will then retry that position as
   `ts=0` about once a second, forever (bounded: ≤16 in flight, only failed positions;
   a healthy server emits none at all, pinned by the generation-capacity-stress soak).
4. **Duplicate-while-in-pipeline** answers nothing (v17 skip) instead of the old
   `RATE_LIMITED` bounce — that corner heals by the 10 s sweep instead of ~1 s.
5. **Serve-source attribution is stripped** for v16 clients (they predate the trace).
6. **Backfill pacing is client-capped at ~216 in flight** vs the v17 want-set's 800 —
   expect visibly slower rings. The §4.1 cap values are the only tuning lever.
7. **Warm-reconnect floods may bounce.** A big cached resync can overflow the 800-entry
   synthetic set; overflow positions get `RATE_LIMITED` and trickle in over subsequent
   scans. Converges, slowly (bounce cadence is bounded — the client's `skipNextScan` is
   one-shot and drain continues).
8. **Frontier residual after the declare-time sort (§4.3):** a stale-*near* head that
   is pinned in flight anchors the frontier at its own ring until it resolves — that is
   the deliberate v17 anti-starvation pin, bounded by the disk timeout / generation
   timeout (default 60 s), not a new v16 behavior. The stale-*far* pathology is closed
   by the sort + declare-time range filter.
9. **Post-dimension-change grace (§4.2):** old-dimension straggler batches are
   discarded for ~1 s after the reset; the rare legitimate ask that lands in the grace
   heals at the client's 10 s sweep.
10. **A decode-halted client is served for up to the 75 s TTL** before its set drains
    (it stops refreshing but cannot signal); bounded by its own decode-queue caps and
    ingest-failure re-asks. The v17 empty-clear-batch backpressure signal has no v16
    equivalent — this is the accepted substitute.

## 7. Lifecycle, threading, config, diagnostics

- **Lifecycle:** identity (`UUID → session`) is created at handshake and dropped at the
  *network* disconnect hooks (Fabric `DISCONNECT` event / Paper `onPlayerQuit`) — the
  same level that owns the handshake. `service.removePlayer` (also fired by the
  dimension-change remove+register cycle) only **resets the session's want-set** via
  `onServiceRemove(uuid)` (and arms the §4.2 ingress grace), mirroring how capabilities
  survive the dim-change rebuild. The v16 client fully resets per-dimension itself and
  re-asks from its cache, so a cleared set is correct there. A **duplicate handshake**
  (the server's `registerPlayer` is `computeIfAbsent`-idempotent) resets the session's
  want-set but keeps the identity.
- **Threading:** Fabric is single-threaded at every touch point (MAIN). Paper ingress
  can be a region thread on Folia while tick/flush run on the pump, so
  `V16CompatSession` is coarse-grained `synchronized` (tiny critical sections; v16
  throughput is explicitly not a goal), and the declare's batch **build and offer
  happen inside the same lock** (§4.3) — an out-of-lock offer could publish stale
  membership after a newer build, resurrecting pruned entries. `offerIncomingBatch` is
  already any-thread safe. The set invariant, stated honestly: *an entry absent from a
  later declaration is satisfied, range-evicted, or TTL-stale — never merely lost.*
- **Config:** one new boolean `enableV16Compat` on `ServerConfigBase` (shared by both
  platforms). **Default `true`** — the layer is completely inert for v18 clients and
  only activates on a version-16 handshake; the flag is the kill switch. `false`
  restores today's silent version-mismatch.
- **Diagnostics:** a `v16_clients` gauge plus `v16.redeclares` / `v16.overflow_bounced`
  / `v16.grace_discarded` counters — held as **`AtomicLong`s on `V16CompatManager`**,
  rendered as one `/lsslod diag` line via a nullable `DiagData.v16Line` (omitted while the
  shim is untouched). Do NOT route them through `ProcessingDiagnostics`: it is explicitly
  single-writer (processing thread, plain `volatile`), and the shim's events fire on
  MAIN/PUMP and Folia region threads — naive increments there are lost-update races.
  **Implementation decision (2026-07-17): the metrics exporters are deliberately NOT
  extended** — their key set is pinned by `ExporterContractTest` against a checked-in
  contract literal, and the soak/benchmark harness clients are v17-only, so v16 fields
  would be permanently-zero schema churn. Note for dashboard readers: an active v16
  session produces structural `service.superseded` at the 1 Hz declare exactly like a
  v17 client whose backlog outruns the declare — mechanism, not pressure.

## 8. Test plan

- **Tier 1 (common):** `V16CompatSessionTest` — merge/refresh semantics (client ts
  always wins), prune on column-sent and on both response types **as load-bearing**
  (a missed prune of a ts≤0 entry costs exactly one honest re-serve, then prunes; the
  crossed 10 s-sweep re-ask scenario), ingress-never-declares / tick-is-sole-declarer,
  declare-time Chebyshev sort + range re-eviction against a moved player chunk,
  post-reset ingress grace discards (counted), TTL eviction at 75 s (injected clock),
  overflow bounce, empty-set silence, reset-on-service-remove vs drop-on-disconnect,
  duplicate-handshake reset-keep-identity, build+offer atomicity under the lock.
- **Tier 1 (gate):** `HandshakeGateTest` dialect rung — v16+enabled → V16 dialect with
  the unchanged outcome ladder; v16+disabled → VERSION_MISMATCH; v18 unaffected.
- **Tier 1 (wire):** golden-byte tests for the 6-field SessionConfig and source-less
  VoxelColumn against fixtures derived from the v0.6.2 encoders; Fabric/Paper v16
  parity twins; a regression pin that the v18 encode paths are byte-identical to today
  when the legacy flags are off; and a **dialect-totality contract test** — every S2C
  emission path of a v16-dialect session (probe / disk / generation serves, the
  0-section ghost clear, the handshake reply) produces the legacy shape, parameterized
  over producers, since a leaked v18 column frame hard-kicks the old client (§5).
- **Tier 2 (optional):** one gametest sending a version-16 `HandshakeC2SPayload` (the
  record's version field is a plain int) and asserting a 6-field reply decodes, using a
  test-local v16 decoder.
- **Live acceptance (the real gate — MANUAL, user-run):** join `test-server.sh` servers
  (Fabric + Paper) with the actual released v0.6.2 jar on an MC 26.2 client and watch
  LODs backfill, converge, and dirty-refresh. There is no automated stand-in for this,
  and none should be built — the automated tiers validate the translation layer's
  behavior only; the old-client integration test is owned by the user.
- **Not extended:** soak (v17-only client harness) and benchmarks.

## 9. Implementation order (rough)

1. `common/`: `WireDialect` on `HandshakeGate.Decision` + config flag + gate tests.
2. `common/`: `V16CompatManager`/`V16CompatSession` + unit tests (no platform deps —
   they operate on `AbstractPlayerRequestState` + small functional seams).
3. Fabric: legacy encode branches on the two records + golden tests; glue at handshake,
   `handleBatchRequest`, column sender lambda, `drainSendActions` observation,
   disconnect hook; service-tick `compat.tick()`.
4. Paper: `encodeSessionConfigV16` + `rewriteColumnToV16` + parity tests; the same five
   glue points (`columnPayloadSender` wrap is an existing setter).
5. Diagnostics + release-notes line (mention Folia experimental status as usual).
6. Hand off to the user for manual live acceptance with the v0.6.2 jar; fix what it
   teaches us.

Estimated ~600–900 LOC production + a similar volume of tests. No protocol bump — v18
stays v18; the shim speaks 16 only to clients that handshake 16.

## 10. Open questions

1. **Advertised caps as a throttle lever** — ship 200/16 (matches real admission), or
   expose a config to lower them for servers that want v16 clients gentler? Leaning:
   constants now, knob only if a real deployment asks.
2. **Overflow bounce vs drop-oldest** on synthetic-set overflow — bounce is chosen for
   honesty (client visibly backs off); drop-oldest would be silent but relies purely on
   the 10 s sweep. Revisit if resync floods bounce-loop in live testing.
3. **Should `v16_clients > 0` log a one-time INFO** naming the legacy player joins?
   Probably yes (admin visibility), warn-once style.

## 11. Adversarial review round (2026-07-17) — finding → amendment map

Two independent reviews: one vs the v0.6.2 client code, one vs the current server
internals. Every confirmed finding and its fold-in, so a future rewrite doesn't
silently regress them:

| # | Finding (severity order) | Amendment |
|---|---|---|
| S1 | Per-ingress full-set offers (drip = up to 20/s) perpetually supersede Folia's held-batch CAS (`republishHeldBatch` loses to a fresh mailbox every tick) → duty-cycled routing starvation, zero probe coverage. CONFIRMED | §4.2.1: ingress merges only; `tick()` is the sole declarer (1 Hz). |
| S2 | Insertion-ordered declares break the closest-first precondition of `stampLiveFrontier` (first unsatisfied entry in declaration order): a moved player's stale-far head *neuters* the spread gate and ~800 stale entries starve new near wants for up to the TTL — first draft's "bounded, heals on next merge" was false. CONFIRMED | §4.3: declare-time Chebyshev sort from the current player chunk + range re-eviction. Residual (stale-near in-flight pin) documented §6.8. |
| S3/C2 | "Pruning is an optimization" was false: ts≤0 entries (all fresh backfill) re-resolve honestly — full disk re-read + full re-serve per declare — not cheap `UP_TO_DATE` (that path needs ts>0). CONFIRMED (both reviewers) | §4.4 rewritten: prune is load-bearing, both seams, tested; client-declared ts always wins (no served-stamp upgrades — invisible-hole risk). |
| C1 | A v18-shaped column frame reaching a v16 client hard-kicks it (source byte parsed as the section-array length); a mis-shaped SessionConfig silently kills the session. Consequence CONFIRMED | §5: seam converts unconditionally (structurally total over producers) + warn-once drop guard; §8 dialect-totality contract test. |
| S4 | Counter inflation (~10⁴/s `superseded`/`requests_received` per player) under per-ingress offers. CONFIRMED | Cured by S1's 1 Hz-only declare; §7 note that residual 1 Hz structural supersession matches v17 semantics. |
| S5 | Routing shim counters through `ProcessingDiagnostics` violates its single-writer contract (shim events fire on MAIN/PUMP/region threads). CONFIRMED | §7: `AtomicLong`s on `V16CompatManager`, read directly by formatter/exporters. |
| C3 | Old-dimension straggler batches (in netty flight across a dimension change) merge into the fresh set, defeat the range filter near portal-origin overlap, and generate wrong-dimension chunks for up to the TTL. CONFIRMED | §4.2.3: ~1 s post-reset ingress grace (discards counted). |
| C4 | 30 s TTL evicts positions a moving player still wants — the client's 10 s sweep is starved by sustained movement (and the backpressure halt), refuting the "~11 s refresh floor" rationale. CONFIRMED | §4.3: TTL raised to 75 s; §3.1 caveat added; range re-filter carries movement pruning instead. |
| S6 | Paper's handshake reply outruns mailboxed registration; a batch in that gap dropped at 10 s cost; duplicate handshakes unspecified. PLAUSIBLE | §4.2.2: merge is session-only, no state needed; §7: duplicate handshake = reset want-set, keep identity. |
| S7 | Out-of-lock build/offer can publish stale membership (resurrecting pruned entries); "superset-vs-superset" invariant was overstated. PLAUSIBLE | §4.3/§7: build+offer inside the session lock; invariant restated ("absent ⇒ satisfied, range-evicted, or TTL-stale — never merely lost"). |
| C5 | ts=0 is not strictly "after an in-session NOT_GENERATED" — persisted 0-stamps re-ask ts=0 on warm reconnect. CONFIRMED, cosmetic | §3.4 corrected. |

Claims that survived both attacks (for confidence, not re-verification): all §2 wire
deltas byte-for-byte at both tags; the v0.6.2 client handles 0-section ghost clears
(`isClearColumn` + air-fill) and never sends empty batches; untracked answers and
duplicate columns are dropped/ingested harmlessly (the client is structurally robust to
shim re-declares); the column/response send seams are the sole egress on both
platforms; send-drop honesty holds end-to-end; the lifecycle model has no
remove-without-reregister-or-disconnect leak; vanilla `DiscardedPayload` is data-less
in mapped 26.2 (the Fabric legacy-encode branch is genuinely forced); overflow-bounce
livelock refuted (`skipNextScan` is one-shot).
