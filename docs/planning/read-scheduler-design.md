# Design: Region-Bucketed Read Scheduler

**Status:** partly realized, partly superseded — see the notes in §1, §3 and §7 before acting on this doc. Originally a concrete design for replacing the server's dispatch-or-bounce request handling with a real, reprioritizable, disk-aware read scheduler, within the existing client-pull, full-resolution-section model. Since written: **Phase 0 shipped** (PR #36's `useBackgroundReadPriority`, §10.2/§10.3), and **protocol v17's declarative want-set** (`docs/superpowers/plans/2026-07-15-declarative-want-set-requests.md`) deleted the dispatch-or-bounce baseline §1 describes and realized the hold-don't-bounce backlog + reprioritization goals of §3/§7 — by client-declared replace semantics rather than a server-side scheduler. **Still unbuilt:** region bucketing, the nearest-region-first budgeted scheduler, and adaptive concurrency.

**Constraints (fixed):**
- Client-pull model stays: client scans in a spiral and sends batched (packed position + clientTimestamp) requests; server serves from disk/memory and responds.
- Full-resolution sections must be shipped (Voxy can't ingest downsampled LODs today).
- Optimize for **disk I/O** and **network bandwidth**; keep vanilla gameplay + vanilla chunk loading unstarved; degrade gracefully from 1 to dozens of players.
- Worlds are far too large to cache meaningfully, and players spread out, so cross-player/cross-session cache-hit rates are low — the disk read is the real, unavoidable cost.

---

## 0. The load-bearing fact this design is built on — VERIFIED against 26.2

LSS does **not** read region files itself. Both platforms serve a cold column via
`chunkMap.read(new ChunkPos(cx, cz)).get(timeout)` (`ChunkDiskReader.java:24`, `PaperChunkDiskReader.java:37`), which dispatches to **Minecraft's own per-dimension async IO path** (`IOWorker` → `RegionFileStorage`).

**Verified against the 26.2 Mojang-mapped server jar (javap):**
- `ChunkMap extends SimpleRegionStorage`, which holds `private final IOWorker worker`; `chunkMap.read(pos)` is `SimpleRegionStorage.read` → `worker.loadAsync(pos)`, returning a `CompletableFuture`.
- `IOWorker` has one `private final PriorityConsecutiveExecutor consecutiveExecutor` over one `RegionFileStorage storage` — **a single serialized worker per dimension.**
- `RegionFileStorage` has `private final Long2ObjectLinkedOpenHashMap<RegionFile> regionCache` bounded by `MAX_CACHE_SIZE` — **the LRU region-file handle cache.**
- LSS calls `read()` on the *same* ChunkMap instance vanilla uses to load chunks, so **LSS and vanilla share the exact same single IO worker per dimension.**
- `IOWorker.loadAsync(ChunkPos)` takes **no priority parameter** (the `IOWorker$Priority{FOREGROUND,BACKGROUND,SHUTDOWN}` enum is internal to the executor). So there is **no public API to submit an LSS read at a lower priority than vanilla's** — they enter the worker as equals; making LSS yield would require reaching the private `consecutiveExecutor` via mixin (fragile, version-specific).

Consequences that reframe everything:

1. **The actual disk read is serialized by MC's single-threaded IOWorker for that dimension — the same one vanilla chunk loads and saves use.** "LSS reads competing with vanilla chunk loads" is not a metaphor; it is literally the same IO queue. This is precisely the resource the reliability goal protects.
2. **LSS's "5 disk-reader threads" do not give 5× disk parallelism.** Each thread blocks on `.get()` waiting for the shared IOWorker, then does the NBT parse + wire-serialize on its own thread. So the pool parallelizes *CPU* (parse/serialize) and *hides IO latency*, but the reads themselves funnel through IOWorker. The thread count is a parse/serialize width, not a disk width.
3. **`RegionFileStorage` already caches open region-file handles (LRU) and the OS caches pages.** So the cost I earlier attributed to "a seek/open per column" is partly absorbed already: consecutive reads to the same region reuse a warm handle. The real per-read cost is IOWorker queue time + decompress + our NBT parse + re-serialize.
4. **The IOWorker budget the design calls for already exists — as `diskReaderThreads` — but it's mislabeled and mis-defaulted.** Because IOWorker is single-threaded, `diskReaderThreads` is *not* disk parallelism: it's the count of LSS reads that can sit in the shared IO queue *ahead of a vanilla read*, since each pool thread blocks on one `loadAsync().get()` at a time. So more threads **do not speed up cold reads** (IOWorker serializes them regardless) but **linearly increase how long a vanilla chunk load can wait behind LSS reads.** The default of 5 is tuned for a throughput that a single-threaded worker can't deliver, while paying 5× the worst-case vanilla-delay. The parse/serialize CPU *does* parallelize across the threads — but that benefit saturates at ~2–3 and only matters in the warm-cache regime, which (per the problem statement: spread-out players, low hit rate) is the *rare* case.

**Therefore the true levers are:**
- **Reliability** = bound how many reads LSS has concurrently on the shared IOWorker (i.e. lower `diskReaderThreads` / decouple IO-concurrency from parse-concurrency), so a vanilla read never waits behind a large batch of LSS reads. No public priority lever exists (§0), so *concurrency reduction is the tool*.
- **Disk efficiency** = submit reads with *region locality* (consecutive same-region reads stay handle/page-cache warm — value uncertain given the handle cache; must be measured) and *don't do reads whose result will be thrown away* (movement staleness).
- **Responsiveness** = order the reads we do submit nearest-first to the player's *live* position, continuously.

The current design manages none of these; it runs 5 concurrent LSS reads on the shared worker in FIFO submission order and bounces the overflow back to the client. Crucially, the single highest-value reliability lever — capping LSS's IOWorker concurrency — needs **no scheduler at all**, just a smaller, correctly-understood `diskReaderThreads` (see §7 Phase 0).

---

## 1. The current pipeline, piece by piece (baseline)

> **SUPERSEDED (2026-07-15) — this baseline no longer exists.** Protocol v17's declarative
> want-set (`docs/superpowers/plans/2026-07-15-declarative-want-set-requests.md`) deleted every
> piece described below: `enqueueIncomingRequest`, the per-player FIFO `ConcurrentLinkedQueue`,
> `MAX_INCOMING_QUEUE = 16384`, and the rate-limited bounce (the response type is off the wire;
> byte 0 is retired and reserved). The client now declares its **complete want-set** once per
> second and the server **replaces** a per-player backlog with it. Neither saturation bounces:
> a full per-player slot retains the entry and the drain **continues** (so a full SYNC cap cannot
> starve admissible GENERATION entries behind it), while an exhausted disk pool retains the entry
> and **stops** the pass. Read this section as history — it is
> accurate about v16 and about *why* the change happened, not about current code.

- **Intake** (`handleBatchRequest`): distance-filters each position (`lodDistance + 32`) against the player's position *at arrival*, then `enqueueIncomingRequest` appends it to a per-player FIFO `ConcurrentLinkedQueue`, tail-dropping at `MAX_INCOMING_QUEUE = 16384`.
- **Dispatch** (`IncomingRequestRouter.processIncomingRequests`, once per processing cycle, 20/s): drains the *entire* incoming queue. Each request resolves to: up-to-date (timestamp/probe), duplicate-skip, in-memory serve, or — for a cold read — `tryAdmit` a per-player sync slot + `submitDiskRead`, or **bounce as rate-limited** if the slot or the pool queue is full.
- **Disk pool** (`AbstractChunkDiskReader`): `ThreadPoolExecutor`, 5 threads, `ArrayBlockingQueue(5×32=160)`. FIFO by submission order. On overflow → `RejectedExecutionException` → the request is delivered back as `saturated` → answered `rate-limited`.
- **Result delivery** (`OffThreadProcessor`): drains per-player read-result queues, fans a shared read out to dedup-attached players, stamps the timestamp cache, enqueues the payload for the bandwidth-limited send.
- **Backlog location:** there isn't a server one. Overflow is bounced, and the client's re-request-next-scan loop *is* the backlog; the only server buffer is the 160-slot FIFO inside the pool.

**Where this hurts, restated against §0:** FIFO submission floods IOWorker with scattered, non-region-ordered reads (poor locality, and it delays vanilla); no reprioritization means a read submitted 3 s ago for a now-behind column outranks a now-in-front one; overflow churns the network (bounce → re-request → re-bounce); and reads for columns the player has already left still run and still get sent (wasted IOWorker time *and* wasted bandwidth).

---

## 2. The proposed design

A per-player, region-bucketed **read backlog**, drained by a **budgeted, nearest-region-first scheduler** that feeds the existing async pool at its real throughput. One structure delivers all three levers because *region bucketing is simultaneously the unit of disk locality and the unit of cheap reprioritization.*

```
client batch ─▶ INTAKE ─▶ per-player backlog:  region ─▶ {pending columns + ts}
(unchanged                    (dedup vs served/in-flight; distance-filtered)
 protocol)                             │
                                       ▼
                          SCHEDULER (each IO pass):
                          pick nearest region(s) to LIVE player pos
                          with pending columns, submit their columns
                          to the pool up to the IOWorker BUDGET,
                          only as the pool drains (no bounce)
                                       │
                                       ▼
                          existing pool → chunkMap.read (IOWorker) → serialize
                                       │
                                       ▼
                          existing delivery → send queue → bandwidth
```

---

## 3. Piece-by-piece: current → proposed → why → tradeoff

> **PARTLY REALIZED / PARTLY SUPERSEDED (2026-07-15).** Protocol v17's declarative want-set
> (`docs/superpowers/plans/2026-07-15-declarative-want-set-requests.md`) shipped this section's
> *goals* by a different mechanism — **client-declared replace semantics, not a server-side
> region-bucketed scheduler.** Concretely:
> - **Piece A** (populate a backlog, don't dispatch-or-bounce) and **Piece D** (hold, never
>   bounce) are DONE. The backlog is a per-player `ArrayDeque` on `AbstractPlayerRequestState`,
>   replaced wholesale by each 1 Hz want-set; the pool gate is `AbstractChunkDiskReader.hasHeadroom()`,
>   and a full pool retains the entry and stops the drain (`RejectedExecutionException` is now
>   unreachable in steady state; any residual is a counted silent drop, never a bounce).
> - **Piece E** (reprioritization on movement, drop what the player left) is DONE *client-side*:
>   the client re-declares closest-first every second, so a replaced backlog structurally drops
>   the positions it has left. No server-side region re-ranking or hysteresis was needed.
> - **Piece F** is MOOT. Neither option shipped: there is no 10 s client timeout to map (option a's
>   premise is gone) and no `queued` ack (option b). Held positions simply stay in the want-set and
>   are re-declared; that *is* the ack.
> - **Pieces B and C** (region bucketing, the nearest-region-first budgeted scheduler) remain
>   UNBUILT and are still gated on S1/S2 below — v17 did not touch disk locality.
>
> Read the per-piece "Current:" lines as v16 history. The rest of the analysis stands.

### Piece A — Intake: populate a backlog, don't dispatch-or-bounce
- **Current:** append to FIFO; the router later dispatches or bounces.
- **Proposed:** `handleBatchRequest` inserts each in-range, not-already-known position into the per-player backlog, bucketed by region key `(cx>>5, cz>>5)`. Re-requesting an already-pending/in-flight/served position is a cheap dedup no-op. Keep the intake distance filter.
- **Why:** the backlog now lives on the server, where it can be reprioritized and coalesced; the client's re-request storm collapses into idempotent no-ops instead of re-bounces.
- **Tradeoff:** the server holds per-player pending state. It's positions + timestamps, not column data, so it's small (a few thousand longs/player worst case), but it's new state with lifecycle rules (clear on disconnect/dimension change, bound the size). The dedup check on insert is O(1) but must be correct against three sets (pending, in-flight, recently-served).

### Piece B — The backlog structure: region → pending columns
- **Current:** a flat FIFO of positions; no spatial structure.
- **Proposed:** `Map<regionKey, LongSet pendingColumns>` per player (+ per-column timestamp), plus a cheap way to enumerate regions by distance to the live player position. Total pending is capped (e.g. ∝ the LOD disc, a few thousand); on overflow, evict the **farthest** region's entries.
- **Why:** a region is 32×32 chunks. At the 256-chunk default LOD radius the disc spans ~200 regions worst-case (8-region radius), but only the regions with *unsatisfied cold* columns are ever in the backlog — a handful in steady state, up to ~200 only while exploring fresh terrain, and ranking even 200 by distance each pass is trivial arithmetic. It is exactly the unit that shares a region-file handle and page-cache warmth. Bucketing by region is what makes reprioritization *and* coalescing both cheap; they stop being two features and become one.
- **Tradeoff:** priority is coarsened to region granularity (32-chunk steps), so within a region there's no nearest-first ordering. For LOD at hundreds of chunks that's invisible, but it is a real loss of precision vs a per-column priority queue (which would cost O(n log n) reprioritization on every move — the reason we don't do that).

### Piece C — The scheduler: nearest-region-first, budgeted, coalesced
- **Current:** FIFO submission of everything admitted, flooding IOWorker; overflow bounced.
- **Proposed:** each IO pass, walk regions nearest-to-live-position first; for each, submit its pending columns to the pool **only while the pool has capacity** (the `hasReadCapacity` gate we already built), stopping when the **per-tick IOWorker budget** is spent. Unsubmitted columns stay in the backlog for the next pass. Because a region's columns are submitted consecutively, IOWorker services them against a warm handle + warm pages.
- **Why:** (1) *reliability* — the budget is a hard ceiling on LSS's share of the shared IOWorker, so vanilla reads/saves are never buried behind an LSS flood; (2) *disk efficiency* — region-consecutive submission maximizes handle/page-cache hits, so each read costs IOWorker less time; (3) *responsiveness* — nearest-region-first means the near ring is always served before the far ring, continuously, not at 1 s rescan granularity.
- **Tradeoff:** introduces the budget dial (reads or in-flight-reads per tick). Too low starves LOD fill; too high re-introduces vanilla contention. It needs a sane default and ideally an adaptive form (see Open Questions). Region-first also means a far column in a near region can be served before a near column in a slightly-farther region — a small, bounded priority inversion.

### Piece D — Feeding the pool: hold, never bounce
- **Current:** submit unconditionally; pool-full → `RejectedExecutionException` → `saturated` → `rate-limited` bounce → client re-requests.
- **Proposed:** the scheduler submits only up to the pool's spare capacity; the remainder waits in the backlog. `RejectedExecutionException` becomes truly unreachable; the pool is just the *in-flight window* (how many reads IOWorker is chewing on now), and the backlog is the buffer. (Note: the `hasReadCapacity()` gate this leans on is **not on `main`** — it was added on the unmerged PR #34; this design assumes that lands or is re-added here.)
- **Why:** kills the bounce↔re-request churn entirely (wasted network + wasted CPU re-processing), and makes the pool's size a pure IO-concurrency parameter rather than a de-facto per-tick admission limit.
- **Tradeoff:** requests can now sit server-side across many ticks instead of getting a fast bounce — which changes what the client observes (Piece F). The pool/backlog split must not leak (a submitted read that errors must free its slot and not orphan its backlog entry).

### Piece E — Reprioritization on movement + structural staleness
- **Current:** the only reprioritization is the client rescanning nearest-first once per second and re-sending; the server serves whatever it already admitted, in FIFO order, including columns the player has left. Out-of-range reads still run and still send.
- **Proposed:** on player movement (chunk-boundary crossing, or every N ticks), re-rank regions by distance to the new position, and **drop regions now outside LOD range + buffer wholesale.** A read is never *started* for a column the player has already left.
- **Why:** continuous nearest-first (vs 1 s granularity) is the responsiveness win while moving; dropping out-of-range regions before reading is the *combined* disk+bandwidth win — it's the "serve-time relevance" idea, but structural (you evict the bucket) rather than a per-request re-check.
- **Tradeoff:** a fast in-and-out pass over a region wastes the work already queued for it (but not yet read) — acceptable, since we drop it *before* the read. Re-ranking cost is negligible (few regions). "Out of range" needs a hysteresis buffer so a player oscillating on a boundary doesn't thrash a region in and out.

### Piece F — Client interaction: timeout mapping, optional "queued" ack
- **Current:** every request gets a fast disposition; a bounce removes the client's in-flight tracking and it re-requests next scan.
- **Proposed, option (a) — no protocol change:** held requests simply don't get a fast bounce; the client keeps them in-flight until served or its 10 s timeout, which re-requests them (dedup no-op against the backlog). Simplest; residual churn only for backlogs deeper than 10 s of serving.
- **Proposed, option (b) — a "queued" ack:** the server answers held positions with a new lightweight `queued` response distinct from `rate-limited`; the client marks them queued (no re-request until a longer queued-timeout), eliminating residual churn. Still pull-model — only the *answer's meaning* is new.
- **Why:** the backlog changes response timing; without handling it the client's 10 s timeout could re-flood a deep backlog.
- **Tradeoff:** (a) is zero-protocol but leans on timeout tuning and tolerates some churn; (b) is cleaner but adds a response type + a client state + wire-parity test churn across both platforms. Recommend shipping (a) first (perhaps with a slightly longer, backlog-aware timeout) and adding (b) only if measured churn justifies it.

---

## 4. Composition with existing subsystems (what stays)

- **Cross-player dedup (`DedupTracker`):** still valuable and now cleaner — when two players' backlogs both want a column, the scheduler issues one read and fans the result to both. Dedup moves from "per-submit" to "the scheduler coalesces identical columns across players' backlogs before submitting."
- **Timestamp cache / up-to-date & loaded-chunk probe:** unchanged and still run at intake — an up-to-date or in-memory hit never enters the backlog, so the backlog only ever holds genuine cold reads. This keeps the backlog small (the disk-bound residue), which is what makes region ranking cheap.
- **Generation (ungenerated chunks):** a separate concern — it's CPU/tick-bound, not IOWorker-bound. Leave it on its own budgeted path (its current caps), triggered by a backlog read resolving not-found. It should have its *own* budget so generation and disk reads don't cannibalize each other.
- **Bandwidth / send queue:** entirely downstream and unchanged — a served column still flows through the send queue + the two-tier bandwidth limiter. Note the ordering benefit compounds: nearest-first reads feed nearest-first sends.
- **The per-player concurrency window (the AIMD work):** its role shifts from *the* admission gate to a *client-side offer-rate shaper*. The server backlog + IOWorker budget is now the real scheduler, so the client window mostly stops the client over-sending; the two are complementary, and with option (b)'s `queued` ack the client window could even be retired in favor of pure server scheduling.

---

## 5. What we trade off (consolidated, honest)

- **Complexity & new invariants.** A stateful per-player backlog + scheduler replaces a near-stateless dispatch. New failure modes: backlog leaks on disconnect/dimension-change, orphaned entries when a submitted read errors, fairness starvation across players, oscillation thrash on region hysteresis. This is the biggest cost.
- **Memory.** Per-player pending positions (bounded, positions-not-data — modest), plus per-player per-region bookkeeping. Not on the precious list, but non-zero.
- **Latency semantics change.** Requests can dwell server-side instead of bouncing fast; the client must tolerate it (Piece F). A naïve client timeout could still churn a deep backlog.
- **Priority granularity.** Region-level (32 chunks), not column-level — a deliberate trade for O(few-regions) reprioritization instead of O(n log n).
- **A new tuning dial.** The IOWorker budget is powerful but must be defaulted/auto-tuned well; a bad value either starves LOD or contends with vanilla — the exact axis we're trying to control, so getting the *mechanism* right but the *number* wrong would undercut the whole point.

## 6. Why it's worth it

- It targets the **true** bottleneck identified in §0 — LSS's share of the shared IOWorker — with a direct rate budget, which is the only honest way to guarantee vanilla isn't starved. The current design has no such lever.
- Region-coalesced submission extracts more LOD throughput per unit of IOWorker time (warm handles/pages), so the same disk budget serves more columns.
- Server-side, continuous, nearest-first scheduling gives real responsiveness under load and while moving — the thing the client's 1 s rescan can only approximate.
- Not starting reads for departed columns reclaims disk *and* bandwidth on exactly the fast-movement case that hurts today.
- It eliminates the bounce↔re-request churn, cutting wasted network and CPU, and it scales: the global budget bounds total IO load, and fairness splits it across players.

## 7. Staging — restaged after verification (the budget already exists)

> **STATUS (2026-07-15) — Phases 0-2 are settled; only the scheduler itself is still open.**
> - **Phase 0** landed as PR #36 (`useBackgroundReadPriority`, §10.2/§10.3): IOWorker `BACKGROUND`
>   submission on Fabric, Moonrise `Priority.LOW` on Paper/Folia.
> - **Phase 1's "hold-don't-bounce backlog" is DONE**, but *not* as this doc stages it — protocol
>   v17 (`docs/superpowers/plans/2026-07-15-declarative-want-set-requests.md`) put the
>   reprioritization on the **client** (a complete want-set re-declared closest-first each second,
>   replacing the server's per-player backlog) instead of building server-side ring/distance
>   priority. So Phase 1's bounce-churn kill and its sub-second-reprioritization goal are both
>   banked; its *scheduler* half is not built.
> - **Phase 2 (`queued` ack) is MOOT** — v17 deleted the client timeout that created the residual
>   churn it existed to remove, and it deliberately adds no new response type.
> - **Still genuinely open:** Phase 3's adaptive concurrency, and the region-bucketing question
>   (S2) — v17 changed *what* is submitted and when, never disk locality.
>
> S1/S2/S3 below stand as written. Note S3 ("if the backlog is ever built, absorb dedup into it")
> was NOT taken: v17's backlog is per-player and `DedupTracker` remains a separate structure.

Verification (§0) collapsed the highest-value step to something far smaller than a scheduler: the IOWorker concurrency budget the whole design is about **already exists as `diskReaderThreads`**, just mis-understood and defaulted for a throughput a single-threaded worker can't give. So build the cheap, high-value, low-risk piece first and measure before committing to the state-heavy scheduler at all.

- **Phase 0 — cap IOWorker concurrency + instrument + measure (no scheduler, no protocol change).** Treat `diskReaderThreads` as what it is — the number of LSS reads that can sit ahead of vanilla on the shared worker — and (a) fix its semantics in config docs/comments, (b) set a default that reflects the tradeoff, and/or (c) decouple IO-submit concurrency (small, the vanilla-protection knob) from parse/serialize concurrency (larger) since `chunkMap.read` is already async. Instrument LSS's read latency + in-flight concurrency and, ideally, vanilla chunk-load latency, so the number is chosen from data, not guessed.
  - **The honest catch — the concurrency knob is a *blunt* tradeoff, not a free win.** On a single shared worker, LSS throughput and vanilla protection are directly coupled: to keep LSS reads flowing you must keep the worker fed with LSS reads (higher concurrency → more pipelining → more LSS throughput → *but* more vanilla contention); to protect vanilla you must leave the worker idle-for-LSS between reads (lower concurrency → less contention → *but* less LSS throughput). You cannot both saturate the worker with LSS reads and leave it free for vanilla. So lowering `diskReaderThreads` genuinely reduces worst-case vanilla delay, but it also caps LSS fill speed — there is no setting that gives both.
  - **The clean fixes both need what Phase 0 instruments**, and are the real target: (1) **`BACKGROUND`-priority submission** via an IOWorker mixin so vanilla `FOREGROUND` reads always jump ahead — LSS can then pipeline aggressively *and* never delay vanilla (fragile/version-specific, hence gated); (2) **adaptive throttling** — submit LSS reads freely when measured vanilla IO latency is low, back off when it rises. Both dissolve the blunt tradeoff; the concurrency cap is only the stopgap until one of them lands.
- **Phase 1 — only if Phase 0 proves insufficient:** the hold-don't-bounce backlog + nearest-first server scheduling. Start with a plain *ring/distance* priority (per S2 below), **not** region-bucketing — add bucketing only if Phase 0's measurement shows region-coalescing actually beats MC's handle cache. This buys sub-second reprioritization and kills bounce churn.
- **Phase 2 — optional:** the `queued` ack (F-b) to remove residual timeout churn; consider retiring the client concurrency window in favor of pure server scheduling.
- **Phase 3 — optional:** adaptive concurrency (react to measured vanilla IO latency / MSPT), and — the biggest latent lever, gated on feasibility — a mixin to submit LSS reads at IOWorker `BACKGROUND` priority so vanilla `FOREGROUND` reads always jump ahead (fragile, version-specific; only if measurement shows concurrency-capping isn't enough).

### Simplifications folded in from review
- **S1 — the win is a budget, not a scheduler**, and the budget already exists (Phase 0). Do not build the scheduler until Phase 0 is measured insufficient.
- **S2 — region-bucketing is premature.** Its only justification (coalescing) is unproven against the handle cache; default to ring/distance priority and add bucketing only if measured to help.
- **S3 — if the backlog is ever built, absorb dedup into it.** A single per-dimension backlog keyed by column, each carrying its requester-set, merges the backlog and `DedupTracker` (read-once-fan-out-to-all) — one structure instead of two.
- **S4 — do not give generation "its own budget."** It's separately capped already and mostly doesn't hit the read worker (`getChunkAtAsync`/worldgen); just scope the IOWorker budget to disk reads.

## 8. Open questions to prototype before committing

- **IOWorker budget: unit and default.** Reads/tick vs max-in-flight-reads vs a fraction of measured IOWorker throughput. Best would be adaptive: back off when vanilla chunk-load latency or MSPT rises. Needs measurement of real IOWorker behavior under mixed LSS+vanilla load.
- **Does region-coalescing measurably help given the handle cache?** Prototype and measure IOWorker time for region-consecutive vs scattered submission — the win is real but its size depends on RegionFileStorage/OS-cache behavior; confirm it's worth the scheduler complexity.
- **Cross-player fairness under one global budget** — round-robin nearest-region across players vs weighted by need vs strict global-nearest. Define starvation guarantees.
- **Priority granularity in practice** — is region-level (32-chunk) coarseness ever visible at LOD distances? If yes, a two-level (region-then-column) ordering within the nearest region.
- **Backlog dwell vs client timeout** — measure how deep the backlog gets under realistic load to decide whether F-a suffices or F-b is needed.

## 9. The honest caveat

This is a scheduler, and schedulers are where subtle bugs live (leaks, starvation, priority inversion, thrash). The current dispatch-or-bounce is dumb but robust precisely because it's near-stateless and pushes the backlog onto the client. The bet is that the disk + responsiveness wins justify taking that state onto the server and paying for the invariants — and the §0 realization (shared IOWorker) is what makes the bet sound, because it identifies a real, controllable bottleneck the current design simply doesn't manage.

---

## 10. Vanilla-protection prototype bake-off (the two "clean fix" candidates, being built to compare)

The §7 Phase 0/3 analysis left two candidates that actually *dissolve* the throughput-vs-vanilla tradeoff (concurrency-capping only trades one for the other). We are **building both as toggled prototypes off `main` (branch `feat/read-io-priority`)** and will pick the one we're more confident in after seeing them built and tested.

- **A — `BACKGROUND`-priority submission (Fabric mixin).** Accessor-mixin into `SimpleRegionStorage.worker` and `IOWorker.{consecutiveExecutor,storage}`, then submit LSS reads via `consecutiveExecutor.scheduleWithResult(BACKGROUND, …)` instead of `chunkMap.read` (FOREGROUND). LSS reads run on the *same* serialized IO thread but yield to vanilla's FOREGROUND reads. Config-gated by `useBackgroundReadPriority`.
- **B — adaptive read throttle (portable, `common/`).** An AIMD controller keyed on LSS's own observed read latency (a proxy for shared-worker congestion): grow the effective read concurrency while latency is low, shrink it when latency rises. Config-gated by `useAdaptiveReadThrottle` + `adaptiveReadTargetLatencyMs`.

**What each is expected to trade (the bake-off hypothesis):**

| | Precision (does vanilla truly win?) | Portability | Fragility / maintenance | Failure mode |
|---|---|---|---|---|
| **A priority** | High — vanilla FOREGROUND always jumps ahead | Fabric-only (mixin); Paper needs a separate reflection path | High — package-private `Priority` enum (hardcoded ordinal), replicates `loadAsync`, skips pendingWrites read-your-writes; breaks silently on MC internals churn | If MC renames/reorders internals, the accessor/ordinal breaks (ideally loudly at load) |
| **B throttle** | Approximate — reduces LSS load but doesn't make vanilla jump ahead; LSS's own latency conflates vanilla-load with LSS-backlog | Both platforms (pure `common/`) | Low — no MC internals; a self-contained control loop | Mis-tuned target latency over/under-corrects; the proxy can misjudge the source of latency |

**How we'll pick (honest about the constraint):** the decisive metric — *does LSS actually delay vanilla, and does each approach reduce it* — needs a real server under mixed load, which this dev environment (WSL2, synthetic world, no vanilla disk pressure) can't measure trustworthily. So the pick is an **engineering-confidence** judgment from the built code: correctness (reads return right bytes — Tier 2/3), code complexity and fragility, portability across Fabric/Paper, and how directly each addresses the mechanism (A targets the cause; B targets a symptom). Runtime confirmation of the vanilla-protection effect is deferred to a real-server measurement and noted as such.

### 10.1 Built outcome & pick (both prototypes complete)

Both were built as toggled prototypes in isolated worktrees, each green on Tier 1 + Tier 2 with a new correctness gametest.

**A — BACKGROUND-priority (Fabric mixin).** ~142 LOC across 7 files. Two accessor mixins (`SimpleRegionStorage.worker`, `IOWorker.{consecutiveExecutor,storage}`), an `NbtSectionSerializer.ChunkNbtRead` seam mirroring Paper's, and `ChunkDiskReader.backgroundReader` scheduling `consecutiveExecutor.scheduleWithResult(1, f -> f.complete(Optional.ofNullable(storage.read(pos))))`. New gametest `backgroundPriorityReadMatchesForegroundReadForDiskLoadedColumn` proves byte-parity between the background and foreground read of the same on-disk chunk. Confirmed fragilities in the built code: (1) the priority ordinal is hardcoded `1` because `IOWorker$Priority` is package-private — a **silent** wrong-priority break if Mojang reorders the enum; (2) reads straight from `RegionFileStorage`, skipping IOWorker's `pendingWrites` read-your-writes — a read racing a still-in-flight save can serve pre-save bytes (self-heals via dirty-broadcast; the parity test can't catch it because it flushes first); (3) Fabric-only — Paper's chunk IO is Moonrise's rewritten subsystem, not the vanilla IOWorker, so this mechanism doesn't port; a Paper equivalent is a separate implementation against a different priority API. The 3 accessors themselves fail *loudly* at load (`defaultRequire: 1`), which is the safe kind.

**B — adaptive read throttle (portable, `common/`).** ~162 LOC across 11 files (both platforms wired). `AdaptiveReadThrottle` is a self-contained AIMD control law (EWMA α=0.2, ×0.7 decrease, +1 increase, floor 1, ceiling = pool depth) gating `AbstractChunkDiskReader.submitRead`; over-limit submits bounce down the existing saturated self-heal path, completed real reads feed `recordLatency`. Bounces feed `recordCompleted(0)` directly (not the throttle) so they don't poison the EWMA; the submit-gate accounting keeps `submitted−completed` balanced so `inFlight` stays exact. 14 unit tests on the control law + 3 integration + a new `adaptiveThrottleOnStillFlowsDiskReads` gametest. Confirmed weaknesses in the built code: the latency proxy conflates vanilla load with LSS's own backlog; it's self-restraint, not prioritization (no priority hand-off — vanilla does **not** jump ahead, LSS just offers less); and a collapsed limit recovers slowly (few samples flow at limit 1). All failure modes degrade **LSS's own** throughput and self-heal; none harm vanilla or correctness.

**Pick: B (ship as the cross-platform default); keep A as the documented Fabric-only "true-prioritization" reference for later.** Rationale, weighted for this project:

1. **Portability is decisive for a *server-support* mod.** B protects Fabric + Paper + Folia from one `common/` control loop with zero MC-internal coupling. A covers only Fabric, and the more disk-pressured deployments (large multiplayer) are Paper/Folia — the platforms A can't reach without a separate Moonrise implementation.
2. **Failure-mode asymmetry.** B's worst case is "LSS backs off too much" — bounded, self-healing, never harming vanilla or bytes. A's worst cases are subtle and one is *silent* (reordered enum → protection quietly gone; storage read skips pending-writes → momentarily stale bytes).
3. **Project fit.** LSS deliberately avoids compile-time MC coupling (all mod compat via `MethodHandle`/direct class literals) and prizes testability. B is a pure, unit-tested `common/` law; A adds private-field accessors + a hardcoded ordinal + replicated read logic — the exact "MC internals churn" surface the codebase otherwise avoids.

**Where A is genuinely better, kept on the table:** A is the *fundamentally correct* mechanism — true priority on the actual shared bottleneck, so vanilla is never delayed by LSS at all, fully **dissolving** the throughput-vs-vanilla tradeoff rather than auto-tuning within it (B does the latter). Revisit A if B's approximation proves insufficient under real-server measurement, first hardening it: resolve the `Priority` ordinal by enum-name at load and fail loudly if absent, decide the pending-writes semantics deliberately, and scope a Moonrise-based Paper twin.

**Caveat unchanged:** neither prototype's tests measure the decisive runtime metric (actual vanilla read delay under mixed load) — that still needs a real server on a non-superflat world. This is an engineering-confidence pick, exactly as the table above anticipated, not a measured winner. B is the safer default to put in front of that measurement; if the measurement later shows B's self-restraint leaves vanilla under-protected under heavy mixed load, that is the trigger to invest in a hardened A (and its Paper twin).

### 10.2 The Paper/Folia story for the priority approach — VERIFIED against the 26.2 dev bundle

I initially hand-waved "Paper is Moonrise, so A doesn't port." Verifying against the paperweight `26.2.build.48-alpha` dev bundle (decompiled `.java` sources) makes the real story precise — and it is more interesting than "can't":

**The two IO paths on Paper do not share an executor.**
- *LSS's read path* is unchanged from vanilla: `chunkMap.read` → `SimpleRegionStorage.read` → `IOWorker.loadAsync` → the per-dimension **`IOWorker.consecutiveExecutor`** (`PriorityConsecutiveExecutor` over `Util.ioPool()`) → `RegionFileStorage.read` (a synchronous region-file read; *not* rerouted through Moonrise). VERIFIED: `SimpleRegionStorage.read` body is `this.worker.loadAsync(chunkPos)`; `IOWorker` is structurally vanilla with the same `Priority{FOREGROUND,BACKGROUND,SHUTDOWN}` enum.
- *Moonrise gameplay chunk loading* goes through **`MoonriseRegionFileIO.loadDataAsync(world, x, z, RegionFileType.CHUNK_DATA, onComplete, intendingToBlock, Priority.NORMAL)`** on Moonrise's **own** `PrioritisedExecutor` IO pool — a different thread pool. VERIFIED: the file + public signature exist; `concurrentutil` `Priority` ladder is `COMPLETING, BLOCKING, HIGHEST, HIGHER, HIGH, NORMAL, LOW, LOWER, LOWEST, IDLE`.

The two paths meet only at the shared `RegionFileStorage` handle cache + `RegionFile` locks (brief per-read critical sections) and at disk/CPU — **not** at a shared task queue.

**Consequence 1 — verbatim-A is a no-op on Paper.** Submitting LSS reads at IOWorker `BACKGROUND` only reorders them against *other IOWorker tasks* (other LSS reads, blender scans, pending writes). Paper gameplay never touches that executor, so BACKGROUND priority buys ~nothing for vanilla protection. The premise that makes A work on Fabric — one shared serialized executor — is **absent** on Paper. (Mechanically the port is trivial: Paper already makes `SimpleRegionStorage.worker` and `IOWorker.storage` `public` — "// Paper - public" — so only `consecutiveExecutor` needs reflection. But it wouldn't do the job.)

**Consequence 2 — real Paper prioritization exists, via a *cleaner* API than Fabric's.** The correct Paper/Folia mechanism is to route LSS reads through `MoonriseRegionFileIO.loadDataAsync(..., Priority.IDLE/LOWEST/LOW)` so they defer to gameplay's `NORMAL` reads on Moonrise's shared prioritised pool — genuine priority interleaving, which is exactly what Moonrise's rich priority ladder is *designed for*. This is a **public, javadoc'd** method (not a private-field mixin), compile-time reachable via paperweight (Moonrise classes ship un-obfuscated in the same server jar as NMS), returning a `Cancellable`. Trade-offs: (a) it is Moonrise-*internal* API — version-brittle, but it breaks **loudly at compile time** (unlike Fabric-A's silent enum-ordinal), and LSS already accepts NMS-level brittleness on Paper; (b) the completion callback runs on a Moonrise IO thread ("interacting with the file IO thread … is undefined behaviour"), so LSS must only copy the `CompoundTag` out and hand off to its existing processing thread — the same discipline `PaperChunkGenerationService` already follows; (c) Moonrise owning the read threads makes LSS's own `AbstractChunkDiskReader` pool partly redundant on that path, so it's a **structural change** to the Paper reader, not a drop-in.

**How difficult, concretely:** a *separate* Paper reader strategy (moderate effort — a new `submitReadDirect` branch calling `MoonriseRegionFileIO.loadDataAsync` at low priority, feeding the existing `PaperNbtSectionSerializer` → `ChunkReadResult` pipeline; rework of who owns the read threads; flag wiring; soak-harness validation on real Paper/Folia since there's no cheap byte-parity gametest for it). Conceptually identical idea to Fabric-A, different (and arguably nicer) API.

**Sharper insight for the pick:** the shared-serialized-queue starvation that A precisely fixes is largely a **Fabric-specific** phenomenon. On Paper, LSS (legacy IOWorker / `Util.ioPool()`) and gameplay (Moonrise pool) are *already* on separate thread pools, so LSS can't monopolize a gameplay queue there; the residual Paper contention is RegionFile-lock + disk-bandwidth, which even Moonrise-priority routing only partially orders (disk bandwidth is disk bandwidth). Issue #32's saturation was LSS's *own* pool saturating (a platform-independent, self-inflicted signal) — which is exactly what **B** throttles, on both platforms, with no MC-internal coupling. So this investigation **reinforces the B pick**: A's exact win is Fabric-only by nature, a proper Paper equivalent is a whole second implementation against internal Moonrise API, and B already covers the actual cross-platform saturation problem. A (Fabric IOWorker priority) + a Moonrise-`loadDataAsync` Paper twin remains the "true prioritization everywhere" endgame if B's approximation ever proves insufficient under real-server measurement.

### 10.3 Implementation finding — where BACKGROUND actually lands us (verified from 26.2 bytecode)

Implementing A (commits on `feat/read-io-priority`) surfaced a fact neither §10.1 nor §10.2 knew,
and it strengthens the case for the approach. Disassembling `IOWorker` on the real 26.2 jar shows
the priorities vanilla itself schedules at on the shared per-dimension `PriorityConsecutiveExecutor`:

| Task | Vanilla priority |
|---|---|
| chunk **loads** (`loadAsync` → `submitTask`) | `FOREGROUND` (0) |
| chunk **saves** (`storePendingChunk`) | `BACKGROUND` (1) |
| shutdown flush (`synchronize`) | `SHUTDOWN` (2) |

So LSS's reads move as follows:

- **Before:** `chunkMap.read` → `loadAsync` → **FOREGROUND** — LSS reads were level with the chunk
  loads players wait on, *and ranked ahead of vanilla's own saves*.
- **After:** **BACKGROUND** — strictly below vanilla's loads, and tied with vanilla's saves.

Both vanilla loads *and* vanilla saves therefore get better, not just loads: the change doesn't
merely deprioritize LSS relative to gameplay reads, it also stops LOD streaming from outranking the
server's own chunk writes. It also pins down the read-your-writes gap precisely: reads and saves
share BACKGROUND, so the executor orders them FIFO — a save already queued ahead of an LSS read
flushes first, and only a save queued *behind* an in-flight read can be missed. That window is
self-healing, because the save we raced is the very event that marks the column dirty.

`IOWorker$Priority` is a package-private `final class`, so the ordinal must be pinned as a literal
(`1`); `scheduleWithResult(int, Consumer<CompletableFuture<T>>)` takes the priority as an `int`,
which is how vanilla passes it too (`Priority.FOREGROUND.ordinal()`). A reordering of the 3-constant
enum is the one silent risk, and `SerializerParityGameTests
.backgroundPriorityReadMatchesForegroundReadForDiskLoadedColumn` is positioned to catch it.

### 10.4 Shipped resolution — A + B synthesis (supersedes both §10.1 "pick B" and the later "A everywhere")

The bake-off (§10.1) picked B; the code that actually shipped (PR #36, §10.2/§10.3) was **A** —
IOWorker `BACKGROUND` on Fabric, Moonrise `Priority.LOW` on Paper/Folia — because A is the
*fundamentally correct* mechanism (true priority on the shared bottleneck, §10.1's own "where A is
genuinely better"). That left B built but unwired.

**The C2ME incident (2026-07-16) forced the synthesis.** A live Fabric server running C2ME
NPE-stormed every LOD read: C2ME's chunkio rewrite replaces vanilla's single-threaded IOWorker with
its own concurrent IO, leaving `consecutiveExecutor`/`storage` null on the vanilla shell that A
reaches through accessor mixins (see the `c2me-background-read-incompat` memory + the CLAUDE.md
Known-issues note). The first fix (`f65a447`) fell back to bare foreground `chunkMap.read` — correct,
but it dropped **all** read protection exactly on the servers most likely to be disk-pressured. B was
the missing half: it needs no vanilla internals at all (a pure `common/` AIMD law keyed on LSS's own
read latency), so it is precisely what still works when A's substrate is gone.

**Shipped design = A default, B as the auto-engaged Fabric fallback:**

- **A is the default and is unchanged on the compatible path.** When the IOWorker executor resolves,
  reads schedule at `BACKGROUND` exactly as before; the throttle stays null (throttling a working-A
  server would only cost LSS throughput for zero gameplay benefit).
- **Detection is maximally fail-safe** (`ChunkDiskReader.backgroundReaderOrFallback`): a null handle
  **or any `Throwable`** from accessor resolution latches a server-wide one-way `backgroundIncompatible`
  flag, enables the throttle, warns exactly once, and reads foreground thereafter. A mod we cannot
  anticipate has changed vanilla's internals, so detection itself must never throw.
- **B integrates through `hasHeadroom()`, not the old saturated bounce.** This is the one change from
  B's §10.1 prototype that protocol v17 made possible: the want-set router *retains* a throttled-out
  read (the `NO_DISK_HEADROOM` path) and the client's 1 Hz re-declaration heals it — no
  `RESPONSE_RATE_LIMITED` (retired in v17), no bounce on the wire. B became a headroom modifier
  instead of a self-heal-via-bounce mechanism.
- **`useBackgroundReadPriority = false` remains a true rollback:** plain foreground, no throttle. A
  user who disables A has opted out of all read protection.
- **Paper never engages B.** Its A path (`MoonriseRegionFileIO.loadDataAsync`) is a compile-time call
  to an always-present class — a missing Moonrise fails loudly at class-load, never as a silent null —
  so Paper never detects incompatibility. Paper's protection stays Moonrise `Priority.LOW`.

**Test-reachability (the honest limit, unchanged from §10.1's caveat and §10.3):** the fallback
end-to-end cannot be reached by any automated test — gametests and soak run plain vanilla IO where the
executor is always non-null, and no dev/CI environment loads a chunk-IO-overhaul mod. Every *reachable*
piece is pinned as a `common/`/Fabric unit test — the null/throwable predicate, the AIMD control law,
the enable→narrow-`hasHeadroom`→recover wiring, the throwable-latch→enable→warn-once path via an
injected throwing resolver — and the decisive end-to-end is a **manual C2ME smoke test** (the built
Fabric jar on a C2ME server: exactly one warning, no NPE, LOD still streams, `/lsslod diag` shows the
throttle ENGAGED and adapting). This is the same reachability limitation the Paper Moonrise LOW path
already lives with.

**Still the endgame if B's approximation proves insufficient** (unchanged from §10.1/§10.2): a hardened
A everywhere — resolve the `Priority` ordinal by enum-name, deliberate pending-writes semantics, and a
Moonrise-based Paper twin — remains "true prioritization everywhere." A+B is not that; it is A where A
works, and self-restraint where A cannot reach, with zero gameplay-harming failure modes on either path.
