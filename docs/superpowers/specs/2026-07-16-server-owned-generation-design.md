# Server-Owned Generation + Undifferentiated Want-Set — Design

**Status:** design spec, awaiting review. Supersedes the sync-only decouple plan
(`docs/superpowers/plans/2026-07-16-decouple-wantset-and-generation-priority.md`) — that plan's goal
is a strict subset of this one, so it will be replaced by a plan built from this spec. Single
protocol-breaking change, folded into the still-unreleased v17.

## Goal

Make the client purely declarative and move *all* resource limiting to the server. The client
declares the complete set of positions it wants; the server decides — per position, in the client's
proximity order — whether to serve from memory, read from disk, or generate, bounded entirely by its
own limiters. The client stops classifying requests as "sync" vs "generation"; the server owns the
disk/generation decision.

## Motivation

Under v17 the client already declares a full want-set and the server replaces its backlog with it —
but the client still pre-classifies each position via the request timestamp: `-1` = "read from disk,
don't generate," `0` = "generate this," `>0` = "resync." That `0 = generate` signal (with a
client-side gen-frontier hold of `generationConcurrencyLimitPerPlayer × 4`) is the client reaching
into a server-side concern — *how much worldgen to trigger*. It also forces two per-player caps onto
the wire purely so the client can size its budgets. The want-set philosophy is "client declares
intent, server limits"; the generation classification is the last place that's violated. Removing it
lets the two concurrency caps leave the wire entirely and collapses the client's two budgets into one.

## The model (one paragraph)

Each scan (1 Hz / 20 ticks) the client declares **every in-range position it lacks current data
for**, closest-first, in one batch, each carrying a resync timestamp (`>0` = "I have this at T, send
if newer"; `≤0` = "I have nothing"). The server replaces that player's backlog and drains it in
declaration (proximity) order: up-to-date check → serve from a loaded-chunk probe or a disk read →
and **on a disk miss, the server itself decides to generate** (generation enabled + a generation slot
free), bounded only by the generation concurrency caps and, on Paper, `Priority.LOW`. A position that
cannot be served *right now* (full slot, no disk headroom, full send queue) is silently retained and
re-declared next scan — no wire response. A position that can *never* be served (generation disabled,
or a generation error) gets `NOT_GENERATED` — a **permanent, session-level "stop asking,"** revived
only by a later dirty broadcast — so the spiral advances instead of churning on blank terrain.

---

## Protocol changes (breaking — v17, still unreleased)

`PROTOCOL_VERSION` stays `17` (the v16→v17 break has not shipped; latest tag ships v16). These fold
into that break.

1. **`SessionConfigS2CPayload` drops both concurrency fields.** New shape (4 components):
   `(protocolVersion, enabled, lodDistanceChunks, generationEnabled)`. Fabric codec + Paper
   `encodeSessionConfig` + the `LSSPaperPlugin` bridge + the version-mismatch fallback constructor
   all update. Byte-parity between platforms preserved (`WireParityTest`).
2. **Request timestamp semantics collapse.** The `0 = generate` meaning is retired. The
   `BatchChunkRequest` timestamp is now `>0` (resync) or `≤0` (no data). Wire shape unchanged (still
   one VarInt per position) — this is a breaking *behavior* change that belongs in the same bump.
   The client never emits `0`; the server treats `≤0` uniformly as "client has nothing."
3. **Response tags unchanged; `NOT_GENERATED` semantics narrow** (see below). `RESPONSE_UP_TO_DATE=1`,
   `RESPONSE_NOT_GENERATED=2`, byte 0 stays retired/reserved. No new tags.

No other payloads change. Capability bits unchanged. (If other breaking changes are wanted in this
release, they are out of this spec's scope — raise them separately.)

---

## Client changes

- **`ColumnStateMap.classify` loses the generation-retry rung.** Today: `stored == 0 &&
  generationEnabled → return 0` (re-ask as a generation request). Removed. The ladder becomes:
  `sessionSatisfied → SATISFIED`; `stored == -1 → -1` (declare, no data); `stored > 0 → resync`
  (up-to-date-or-send); dirty/retry marks as today; else `SATISFIED`. There is no client-emitted `0`.
- **A single want-set budget replaces the sync-budget + gen-frontier split.** `SpiralScanner` sizes
  one budget, `WANT_SET_BUDGET` (a shared constant, default 800 — the current effective sync volume;
  must satisfy `WANT_SET_BUDGET + WANT_SET_FRONTIER_RESERVE ≤ MAX_BATCH_CHUNK_REQUESTS`, i.e. 864 ≤
  1024). No `genCap × BUDGET_MULTIPLIER` gen frontier; no `syncQueued`/`genQueued` split. The
  queue-pressure and vanilla-load down-scaling of the budget stay. `SCAN_BUDGET_MULTIPLIER` /
  `WANT_SET_FRONTIER_RESERVE` may be simplified once the gen-frontier derivation is gone (keep
  `WANT_SET_FRONTIER_RESERVE` if it still documents batch headroom).
- **`NOT_GENERATED` → PERMANENT session-satisfy.** The position is added to `ColumnStateMap`'s
  `sessionSatisfied` set, so `classify` returns SATISFIED for the rest of the session **regardless of
  movement** — the client never re-requests it via the spiral. This is deliberate: on a server that
  cannot generate a chunk (generation disabled, or a corrupt/failed chunk), re-requesting it as the
  player wanders near it would be pure churn. The **only** recovery is the dirty-broadcast mechanism:
  if the chunk later comes into existence (a player walks into it and vanilla generates + saves it, or
  it is edited), the server's dirty detection broadcasts it, and the dirty handler removes it from
  `sessionSatisfied` so the next scan re-declares it. (`onDirtyColumns` already outranks and clears
  `sessionSatisfied` — this reuses that exact path; no new mechanism.)
  - **Precise change:** this *inverts* today's `ColumnStateMap.onNotGenerated`, which currently
    `sessionSatisfied.remove(packed)` + `put(packed, 0L)` to drive the retired gen-retry. The new form
    is `sessionSatisfied.add(packed)` and drops the `put(packed, 0L)`; the `classify` rung
    `stored == 0 && generationEnabled → return 0` is deleted. (`onUpToDate`'s existing
    `sessionSatisfied.add` for a no-data position is the model to mirror.)
- **The client no longer reads the two concurrency caps** (they left the wire); `ClientSessionGate`
  drops their clamps.

## Server changes

- **Router: every request is SYNC; generation is decided on the disk-miss, by server policy.** Remove
  `IncomingRequestRouter`'s `type = clientTimestamp() == 0 ? GENERATION : SYNC` classification
  (line ~121) — every cold column enters the SYNC/disk path. The generation trigger moves to the
  disk-read result: on a not-found, **if generation is available and a generation slot is free**, hand
  the position to the generation service (taking a GENERATION slot); otherwise emit `NOT_GENERATED`.
  This generalizes today's "auto-triggered on disk-read not found" by removing its `ts==0` gate.
- **The SYNC/GENERATION *slot* model stays — internal only.** A disk read still holds a SYNC slot; a
  generation still holds a GENERATION slot. The caps (`SYNC_ON_LOAD_SLOT_CAP` constant = 200, shadowed
  by the disk pool; `generationConcurrencyLimitPerPlayer`/`Global` config) remain server-internal
  admission limiters with unchanged retain semantics (`SLOT_FULL` retain-keep-scanning,
  `NO_DISK_HEADROOM` retain-stop). The client is unaware of them.
- **`syncOnLoadConcurrencyLimitPerPlayer` config field is deleted** (constant `SYNC_ON_LOAD_SLOT_CAP`
  replaces it, same value/semantics). `generationConcurrencyLimitPerPlayer`/`Global` stay as config —
  but leave the wire. The #28 cross-clamp is **deleted entirely**: with no client budget derived from
  either cap, there is nothing to clamp against the batch size (the single `WANT_SET_BUDGET` constant
  satisfies the batch invariant by construction, pinned by a unit test).

## Generation admission — concurrency-only

Per the design decision: **no distance/count frontier.** On a disk miss the server generates the
position if generation is enabled and a generation slot is free, else retains it (full slot →
re-declared next scan) — draining the want-set closest-first because the client already ordered it.
Over time a stationary player in unexplored terrain generates their whole in-range want-set, gradually,
rate-limited by:

- **The generation concurrency caps** (`generationConcurrencyLimitGlobal` = 32,
  `generationConcurrencyLimitPerPlayer` = 16) — the primary bound on concurrent worldgen.
- **Piggybacking** — one generation serves all requesters of a position (unchanged; more valuable
  now that neighbours all declare the same frontier).
- **Paper: `Priority.LOW`** — LOD generation routes through Moonrise
  `PlatformHooks.get().scheduleChunkLoad(level, x, z, true, ChunkStatus.FULL, true, Priority.LOW,
  onComplete)` so it defers to player-driven `NORMAL` generation (the generation analogue of the
  Moonrise `Priority.LOW` read path). Completion delivers an NMS `ChunkAccess` on the owning thread —
  serialized there as today. This is the "server owns limiting via real priority" half.
- **Fabric: concurrency cap only** — vanilla pins worldgen priority to ticket level (FULL = level 33,
  the low floor; no per-task priority), and worldgen latency is too variable a baseline for an
  adaptive throttle to read as contention. So Fabric keeps the concurrency cap as its honest limiter
  (no priority hand-off, no latency throttle). Documented asymmetry, like the disk-read A/B story.

## `NOT_GENERATED` — narrowed role (not a backpressure signal)

- **Fires only on permanent unservability:** generation disabled, or a generation failure/error for a
  not-on-disk chunk. Never for transient pressure (full slot / no disk headroom / full send queue —
  those are silent drops, re-declared).
- **On a generation-enabled server it never fires** (every miss is generated and served eventually).
- **It is permanent for the session** — the client adds the position to `sessionSatisfied` and never
  re-requests it via the spiral, even on movement. This is the anti-churn guarantee: a player wandering
  near unservable terrain (corrupt chunks, or a gen-disabled server) must not keep re-asking for it.
- **The only recovery is the dirty broadcast.** If the chunk later exists (vanilla generates + saves
  it as a player approaches, or an edit occurs), the server's dirty detection pushes it, which clears
  the position from `sessionSatisfied` and re-declares it. There is no movement- or timeout-based
  recovery by design.
- **Its purpose is to let the spiral advance** past permanently-blank terrain so the want-set budget
  flows to servable positions — without it, a gen-disabled server near unexplored terrain would
  re-request blank chunks forever and starve the far on-disk LOD.

---

## What is removed

- The two concurrency caps from the `SessionConfig` wire payload.
- `syncOnLoadConcurrencyLimitPerPlayer` config knob (→ constant).
- The client-emitted `ts == 0` "generate" request and `ColumnStateMap`'s generation-retry rung.
- The client gen-frontier hold (`genCap × BUDGET_MULTIPLIER`) and the sync/gen budget split (→ one
  `WANT_SET_BUDGET`).
- The `ServerConfigBase.validate()` #28 cross-clamp.
- The router's `ts == 0` GENERATION classification.

## Accepted tradeoffs (non-goals)

1. **Unexplored terrain fills in gradually, and the world grows.** A stationary player in never-visited
   terrain triggers generation of their whole in-range want-set over time (rate-limited by the gen
   cap; on Paper, at `Priority.LOW`). Chosen deliberately (concurrency-only) for maximal LOD coverage
   and simplicity, over a frontier knob. Operators who don't want LOD to grow the world set
   `enableChunkGeneration=false` (then LOD shows only already-generated terrain, terminated by
   `NOT_GENERATED`).
2. **Fabric generation gets no priority.** The concurrency cap is its only limiter. A tick-health
   (MSPT)-gated backoff is the correct future direction if needed — explicitly *not* an absolute-latency
   throttle (worldgen's variable baseline makes latency a poor congestion signal). Out of scope here.
3. **No server-side negative cache.** Not needed: with generation enabled, misses become data; with
   generation disabled, `NOT_GENERATED` + `confirmedRing` stops the re-asking. A negative cache is a
   possible future cross-player-dedup optimization, not required for correctness.

## Edge cases

- **Generation disabled** (`generationEnabled=false`): disk miss → `NOT_GENERATED` → client permanently
  satisfies (`sessionSatisfied`), spiral advances. The one steady-state path where `NOT_GENERATED` is
  load-bearing.
- **All-air / End void:** served as a 0-section column (existing sentinel), not `NOT_GENERATED`.
- **Generation error / corrupt chunk:** resolves as `NOT_GENERATED` → **permanent** session-satisfy;
  the client never re-asks (a transient error will not auto-retry — accepted; a corrupt chunk should
  not be hammered). Recovery only via a later dirty broadcast. Matches today's failure accounting
  (`totalRemovedInFlight`, soak law A4).
- **Dimension change:** existing teardown + fresh registration; the new dimension's want-set starts
  clean (`sessionSatisfied` is per-session/dimension state, cleared on the reset).
- **Movement:** `confirmedRing` reset re-declares closer *unsatisfied* positions. A previously
  `NOT_GENERATED` position stays satisfied (it is in `sessionSatisfied`) and is **not** re-declared —
  only a dirty broadcast revives it.
- **A chunk becomes generatable later** (vanilla loads/generates it as a player walks in): the chunk
  save triggers dirty detection → broadcast → the client clears it from `sessionSatisfied` and
  re-requests → now served. This is the intended "it fills in once real terrain exists there" path.
- **Cross-player dedup / piggyback:** unchanged; a shared frontier position is read/generated once and
  fanned out.

## Testing strategy

- **Unit (`common/` + Fabric):** `ColumnStateMap.classify` without the gen rung; the router
  generating on a disk-miss under server policy (gen-enabled → generate; gen-disabled → `NOT_GENERATED`)
  with no `ts==0` input; `NOT_GENERATED → SATISFIED` unconditional; the single-budget `SpiralScanner`;
  the batch invariant (`WANT_SET_BUDGET + reserve ≤ MAX_BATCH`); wire round-trip of the 4-field
  `SessionConfig` (Fabric + Paper parity); config validation without the deleted field / #28.
- **Gametests:** the in-memory probe + two-player dedup still converge; a generation gametest that a
  disk-missing chunk generates *without* any client generation request (server-triggered); a
  gen-disabled gametest that a disk-missing chunk resolves `NOT_GENERATED` and the spiral advances.
- **Soak:** `generation-disabled` now exercises the `NOT_GENERATED` terminator as the primary path;
  `generation-capacity-stress` exercises concurrency-only generation under a tight gen cap;
  `rate-limit-storm` re-baselined (its `syncCap:4` premise is doubly gone — no client budget derives
  from a cap at all); `fresh-backfill` exercises server-triggered generation end to end. Paper soak
  exercises the live `Priority.LOW` generation path. Update `check_soak.py` named checks + the config
  key whitelist.
- **Reachability caveat:** the Paper `Priority.LOW` generation path has no cheap unit test (soak-gated,
  like the Moonrise read path); its priority argument + completion are pinned via the existing
  `launchAsyncLoad`/`completeAsyncLoad` seams.

## Open questions for review

1. `WANT_SET_BUDGET` default — 800 (preserve current sync volume) vs a larger value now that it is the
   *whole* budget (up to `MAX_BATCH − reserve` = 960)? Larger = faster frontier coverage, more per-scan
   declare traffic.
2. Keep `SCAN_BUDGET_MULTIPLIER` / `WANT_SET_FRONTIER_RESERVE` as named constants, or inline the single
   budget + a literal headroom check?
3. Confirm no *other* breaking wire changes want to ride this release.
