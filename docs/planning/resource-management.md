# Resource-Management Reference — How LOD Streaming Yields to Vanilla

**Status:** reference snapshot as of 2026-07-16 (protocol v17, A+B read protection). Line
numbers cited inline were exact at authoring; re-grep the symbol if they've drifted. This
documents *what exists* — the mechanisms by which LSS keeps LOD streaming from degrading
vanilla chunk distribution across CPU, memory, disk, and network.

## Governing philosophy

Nearly every mechanism below is an instance of one of three layers:

1. **Yield, don't just cap.** On the one resource with a true scheduler (disk I/O), LSS
   submits its reads at a *lower priority* than gameplay so the OS/executor defers them.
   This is the only place LSS achieves genuine "vanilla never waits."
2. **Cap admission + *retain, don't bounce*.** Everywhere else, LSS bounds in-flight work,
   and when a cap is hit the request is **left in a server-side backlog**, never rejected on
   the wire. Backpressure propagates *upstream*: send-queue-full → stop issuing disk reads →
   stop admitting want-set entries.
3. **1 Hz re-declaration = universal self-heal.** Under protocol v17 the client re-declares
   its entire want-set every second, so any silently-dropped work (saturated read, superseded
   batch, full slot) is re-asked next second. This is what *lets* layers 1–2 drop work safely.

Mechanism types (as classified in the tables): **REAL MEASURE** (adapts to observed resource
state), **HARD LIMIT** (fixed cap / config clamp), **IMPLICIT** (bounded as a side-effect).

---

## 1. Disk I/O — the one resource with true priority

**Approach A — real priority (default, `useBackgroundReadPriority=true`) · REAL MEASURE**
- **Fabric:** LOD reads schedule on vanilla's IOWorker `PriorityConsecutiveExecutor` at
  `BACKGROUND` (ordinal 1). Vanilla *loads* run `FOREGROUND(0)`, saves `BACKGROUND(1)` — so
  LOD reads sit strictly below the loads players wait on, tied with saves.
- **Paper/Folia:** reads route through Moonrise `loadDataAsync(…, Priority.LOW)` on Moonrise's
  own prioritised IO pool, deferring to gameplay's `NORMAL`. Keeps read-your-writes (Fabric
  doesn't). `intendingToBlock=false` prevents priority escalation.

**Approach B — adaptive read throttle (Fabric fallback only) · REAL MEASURE (latency)**
- Engaged **only** when a chunk-IO-overhaul mod (C2ME) nulls vanilla's IOWorker, making A
  impossible; otherwise `null`. An AIMD law keyed on LSS's own submit→result read latency
  (EWMA α=0.2) steers an in-flight read limit toward a **20 ms** setpoint: ≤ target → +1,
  > target → ×0.7, floor 1, ceiling = pool depth. Self-restraint, not true priority.

**Retention gate — HARD LIMIT composed with the measures**
- Fixed pool: `diskReaderThreads=5` (clamp [1,64]) at `MIN_PRIORITY`, bounded queue
  `threads×32` = **160**. `hasHeadroom()` = queue has space AND (throttle null or
  `inFlight < throttleLimit`). False → router returns `NO_DISK_HEADROOM`: entry retained in
  backlog, pass stops — saturation never bounced. Residual rejection → silent drop + **one
  warn/60 s**, healed by re-declaration.
- `DISK_READ_TIMEOUT_SECONDS = 10` bounds a stuck read's occupancy of a pool thread.

| Mechanism | Class | Value / knob |
|---|---|---|
| Background read priority (A) | REAL MEASURE | `useBackgroundReadPriority=true`; Fabric BACKGROUND, Paper Moonrise LOW |
| Adaptive read throttle (B) | REAL MEASURE | AIMD → 20 ms; Fabric fallback only, else null |
| Reader thread pool | HARD LIMIT | `diskReaderThreads=5` [1,64], MIN_PRIORITY |
| Pool queue depth | HARD LIMIT | `threads×32` = 160 |
| `hasHeadroom` / `NO_DISK_HEADROOM` | HARD LIMIT + retain | retain backlog, no wire bounce |
| Read timeout | HARD LIMIT | `DISK_READ_TIMEOUT_SECONDS=10` |
| Queue-full saturation | IMPLICIT | silent drop + 1 warn/60 s, re-declared |

---

## 2. Network / bandwidth — manual caps, metered by real token buckets

**Two-tier token bucket · REAL MEASURE**
- Global bucket `bytesPerSecondLimitGlobal` = **100 MiB/s** (clamp 1 KiB–1 GiB), fair-shared
  `availableTokens / activePlayerCount`.
- Per-player cap each tick = `min(globalFairShare, bytesPerSecondLimitPerPlayer)`, with
  `bytesPerSecondLimitPerPlayer` = **20 MiB/s** (clamp 1 KiB–100 MiB).
- Per-player **250 ms burst** bucket (`rate/4` cap) smooths sends across ~5 ticks. Sends debit
  both the per-player and global buckets.
- **Conservative:** metering is on *estimated uncompressed* size (`sectionBytes + 45 B`); MC's
  zlib compresses below the LSS layer, so the real link is throttled tighter than the numbers.

**Send queue = upstream backpressure hinge · HARD LIMIT**
- `sendQueueLimitPerPlayer` = **4000** (clamp 1–100 000). Full → router `sendQueueFull` retains
  the request and stops the pass, so a bandwidth-limited client stops causing disk reads.

**Wire-frame caps (protocol safety) · HARD LIMIT**
- `MAX_SEND_SECTIONS_SIZE = 2 000 000` (server emit ceiling, under MC netty frame limit with
  zlib-expansion margin); `MAX_SECTIONS_SIZE = 2 MiB` (client decoder reject/disconnect);
  `MAX_BATCH_CHUNK_REQUESTS = 1024`; `MAX_BATCH_RESPONSES = 4096`; `MAX_DIRTY_COLUMN_POSITIONS
  = 10240`/frame (paginated).

**Cadence / volume** — client ships its whole want-set once per second (20 ticks);
`dirtyBroadcastIntervalSeconds` = 10 (clamp 1–300); `lodDistanceChunks` = **256** (clamp
1–2048) is the dominant volume driver (~`(2·dist+1)²` columns).

> Not metered: control packets (up-to-date/not-generated) and dirty-column broadcasts — only
> column payloads pass the token buckets.

---

## 3. CPU — almost entirely *implicit*, by design

LSS has **no CPU meter**. It protects the vanilla tick structurally:

1. **Serialization moved off the tick.** NBT→bytes runs on the disk-reader pool; the dedicated
   "LSS Processing Thread" (priority `NORM-1`, ~20 Hz) routes/bookkeeps but **never
   re-serializes** — it wraps pre-serialized bytes. Paper serializes generated chunks on its
   async completion thread, off the pump.
2. **The one main-thread cost is hard-capped.** In-memory loaded-chunk probing is capped at
   `MAX_PROBES_PER_TICK_PER_PLAYER = 512` per player per tick, closest-first. (Fabric
   generation serialization also runs on-tick, bounded by the generation caps.)
3. **Downstream CPU is implicitly bounded by disk + network.** Serialization volume can't
   exceed what the disk pool feeds it or what the send queue + bandwidth drain — CPU is
   bounded transitively by §1 and §2.
4. **Threads:** 7 daemon threads total (1 processing + 1 timestamp-save + 5 disk at
   MIN_PRIORITY); **zero** generation threads (Fabric gen = main thread, Paper gen =
   Paper-owned async pool).

Two client-side real-measure throttles that also cut server demand:
- **Scan-budget scaling** — the client's per-scan want-set (`4×syncCap`) is scaled down by its
  own decode-queue pressure and by **vanilla load** (`1 − missingFraction²`): when the client's
  own vanilla chunks aren't loaded, the budget can drop to zero.
- The adaptive read throttle (§1 B) indirectly caps serialization CPU when engaged.

---

## 4. Memory — hard-capped caches + implicitly-bounded bookkeeping

| Structure | Class | Bound |
|---|---|---|
| `ColumnTimestampCache` | HARD LIMIT | `perDimensionTimestampCacheSizeMB=32` [1,256] → ~524 288 entries/dim @64 B; evicts ~60 s |
| `DirtyContentFilter` (Fabric) | HARD LIMIT | 524 288/dim, whole-map-clear on overflow (self-heals) |
| Ingress mailbox | HARD LIMIT | latest-wins `AtomicReference` → ≤ 1 batch (≤ 1024) / player |
| Backlog `ArrayDeque` | HARD LIMIT | ≤ 1024 (replaced wholesale each batch) |
| Send queue | HARD LIMIT | `sendQueueLimitPerPlayer=4000` [1,100 000] |
| Client ingest queue | HARD LIMIT | 8000 cols / 256 MiB, drop-and-re-request |
| Client `ColumnCacheStore` | HARD LIMIT | 2 000 000-entry load guard |
| `DedupTracker` groups | IMPLICIT | bounded by in-flight disk reads; swept on player removal |
| `pendingByPosition` | IMPLICIT | bounded by slot caps (pending entry *is* the held slot) |

**Concurrency slot caps (in-flight work bound) · HARD LIMIT**
- `syncOnLoadConcurrencyLimitPerPlayer` = **200**, `generationConcurrencyLimitPerPlayer` = **16**,
  cross-clamped by Global Constraint #28 so the want-set batch can't be swallowed by
  re-declared in-flight positions: sync ≤ **956**, gen = `(1024 − syncCap − 64)/4` (= **190**
  at default). Full slot → `SLOT_FULL`: retain but keep scanning.
- Generation: `generationConcurrencyLimitGlobal` = **32** (clamp 1–256), `generationTimeoutSeconds`
  = **60** (clamp 1–600), with piggybacking (one generation serves all requesters of a position).

---

## Cross-resource interaction chain (outbound path)

```
client scans want-set @1Hz, budget scaled by ITS vanilla load  → CPU/net demand governor (client)
server range-filters at lodDist+32                             → volume bound
router admits only while send queue < 4000                     → NETWORK backpressure → throttles disk
slot cap (sync 200 / gen 16) gates concurrency                 → MEMORY/CPU bound; retain-don't-bounce
hasHeadroom(): disk pool queue + adaptive throttle             → DISK gate → NO_DISK_HEADROOM retains
disk read at BACKGROUND / Moonrise LOW priority                → DISK yields to vanilla (real priority)
serialize OFF-tick (pool thread); probe path capped 512/tick   → CPU protection
column dropped if > 2 MB, else queued                          → wire safety
flushSendQueue: min(globalFairShare, 20 MiB/s) via 250ms burst → NETWORK meter (real token bucket)
MC zlib compresses below the LSS accounting layer              → real link throttled even tighter
```
Any drop along this chain is healed by the next 1 Hz re-declaration.

---

## Tuning observations & candidate improvements

Static-read observations (not profiled). Some defaults may be deliberate; treat as questions,
not verdicts.

**Mechanism-level (potential easy wins):**
- **Main-thread probe cap is per-player with no global ceiling.** `MAX_PROBES_PER_TICK_PER_PLAYER
  = 512` bounds one player, but N active players cost up to 512×N column serializations on the
  tick (only during backfill/movement — a converged player probes zero). A *global* per-tick
  probe budget would keep player-count from multiplying tick cost.
- **Fabric/Paper serialization asymmetry.** Paper serializes generated chunks off the pump;
  Fabric serializes generation on the main thread (`ChunkGenerationService.tick`). Not trivial
  to change (MC threading), but the asymmetry is the largest remaining on-tick LSS cost on Fabric.

**Defaults worth a second look (deployment-dependent):**
- **`bytesPerSecondLimitPerPlayer = 20 MiB/s` and `bytesPerSecondLimitGlobal = 100 MiB/s` are
  generous.** 100 MiB/s ≈ 800 Mbps — saturates a gigabit link; both are far above residential
  uplinks. Conservative on the wire (uncompressed metering), but the *knob values* assume a
  well-provisioned host. Constrained/home servers should lower both. Also: only ~5 players fit
  under the global cap at the per-player max before fair-share binds.
- **`generationConcurrencyLimitGlobal = 32` is aggressive for CPU/worldgen-bound servers.**
  Worldgen is among the most expensive server operations; 32 concurrent LOD-driven generations
  can compete with player-driven generation during fresh-world exploration. Consider a lower
  default (piggybacking + the per-player 16 cap partly mitigate).
- **`syncOnLoadConcurrencyLimitPerPlayer = 200` is shadowed by the disk pool depth (165).** A
  single player can't hold more pending reads than the shared pool allows, so raising this knob
  mostly grows the want-set (`4× = 800/scan`), not throughput. To actually serve more per
  player, raise `diskReaderThreads` alongside it. Worth documenting the coupling.

**Looks right — leave alone:** `useBackgroundReadPriority=true`, the retain-don't-bounce design,
cache sizes, `lodDistanceChunks=256`, `dirtyBroadcastIntervalSeconds=10`, the #28 cross-clamp.

---

## One-page config quick-reference

| Knob | Default | Clamp | Resource |
|---|---|---|---|
| `useBackgroundReadPriority` | `true` | — | Disk (priority A / →throttle B) |
| `diskReaderThreads` | 5 | 1–64 | Disk / CPU |
| `bytesPerSecondLimitPerPlayer` | 20 MiB | 1 KiB–100 MiB | Network |
| `bytesPerSecondLimitGlobal` | 100 MiB | 1 KiB–1 GiB | Network |
| `sendQueueLimitPerPlayer` | 4000 | 1–100 000 | Network → disk backpressure |
| `lodDistanceChunks` | 256 | 1–2048 | Network volume |
| `dirtyBroadcastIntervalSeconds` | 10 | 1–300 | Network |
| `syncOnLoadConcurrencyLimitPerPlayer` | 200 | 1–1000 → ≤956 | CPU/Mem/Disk in-flight |
| `generationConcurrencyLimitPerPlayer` | 16 | 1–1000 → ≤190 | CPU/gen |
| `generationConcurrencyLimitGlobal` | 32 | 1–256 | CPU/gen |
| `generationTimeoutSeconds` | 60 | 1–600 | CPU/gen |
| `perDimensionTimestampCacheSizeMB` | 32 | 1–256 | Memory |
| `enableChunkGeneration` | true | — | CPU/gen |
| *(const)* `ADAPTIVE_READ_TARGET_LATENCY_MS` | 20 | — | Disk (throttle B) |
| *(const)* `MAX_PROBES_PER_TICK_PER_PLAYER` | 512 | — | CPU (main-thread cap) |
| *(const)* `DISK_READ_TIMEOUT_SECONDS` | 10 | — | Disk |
