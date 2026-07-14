# Backpressure, Queueing, Concurrency & Rate-Limiting — Architecture Review

**Status:** review / proposal document — nothing here is implemented. Companion to the issue-#32 fixes (PR #33 console-spam, PR #34 disk-admission capacity gate + default alignment).
**Scope:** every flow-control mechanism on the client offer-loop and the server serve-loop, both Fabric and Paper (the mechanisms live in shared `common/`; the platforms are thin adapters).
**Date:** 2026-07-14. Protocol v16.

---

## 1. Executive summary

**Verdict: the design is sound and already well-simplified — this is a tuning-and-clarity pass, not a redesign.** The core protocol (idempotent, position-keyed requests; bounce-and-retry backpressure; cross-player dedup; "a pending-map entry IS the admission slot") is robust and should not change. The big structural simplification already happened: the v15→v16 rework deleted the entire parallel rate-limiter machinery (`RateLimiterSet`, `ConcurrencyLimiter`, `requestId` addressing, the request-cancel path, the server waiting-queue, the bandwidth-negotiation pipeline, and the write-only SessionConfig fields). The old `simplification-review.md` is effectively spent.

What remains is a **layered control system with ~15 distinct bounds**, of which a handful never bind at default config, two enforce the same limit twice, one constant drives three behaviors, one signal aliases four causes, and two latent coupling bugs can stall retries. None is a crisis; together they are real complexity that can be trimmed or clarified.

**Top proposals, ranked by value/risk:**

| # | Proposal | Kind | Behavior change | Risk |
|---|----------|------|-----------------|------|
| P1 | Split the `rate-limited` diagnostic by cause (slot-full vs pool-full vs dedup-bounce vs saturation-fallback) | Observability | none | very low |
| P2 | Fix bandwidth idle-dilution: divide the global budget by *senders*, not all handshaked players | Fairness bug | yes (better) | low–med |
| P3 | Decouple the 10 s in-flight timeout sweep from the scan cadence so movement/backpressure can't starve retries | Latent bug | yes (better) | medium |
| P4 | Delete genuinely dead code: `RequestQueue`'s timestamp column + `peekTimestamp()` | Simplification | none | very low |
| P5 | Collapse the client's three stacked reactions to one rate-limited response into one | Simplification | yes (subtle) | medium |
| P6 | Decide the two-level bandwidth design: keep + document, or fold to one bucket | Clarity/simplify | none–some | low–med |
| P7 | Reconcile the doubly-enforced per-player generation cap (slot 16 == service 16) | Simplification | none (if verified) | medium |

Details and the full inventory below. **Leave alone:** the idempotent protocol, dedup, derived-slot admission, the `RejectedExecutionException` fallback, the defensive `MAX_BATCH_CHUNK_REQUESTS` clamp, and the deliberately-kept unreachable generation guard (its own comment explains why).

---

## 2. The control flow, end to end

Two independent loops meet at the wire. The client *offers* requests as fast as its budget allows; the server *serves* what its resources allow and bounces the rest; both self-heal because every response is an idempotent statement about a position.

```
CLIENT (offer loop, 20-tick cadence)                    SERVER (serve loop, per tick)
─────────────────────────────────────                  ──────────────────────────────────────
SpiralScanner                                           incoming queue  [MAX_INCOMING_QUEUE 16384]
  budget = syncCap×4 = 512        ①                       │  (silent drop if full)
  × queue-pressure scale (→0 @6000) ②                     ▼
  × vanilla-load scale             ③                     IncomingRequestRouter ladder:
  skipNextScan (1-scan backoff)    ④                       1 duplicate / diskReadDone
     │  writes ≤budget positions                            2 send-queue-full → break  [4000] ⑧
     ▼                                                      3 timestamp cache → up_to_date
RequestQueue (single-pass, regenerated each scan)          4 loaded probe → in-memory serve
     │                                                      5 DISK-CAPACITY GATE  [pool 165] ⑨
     ▼                                                        (skip if dedup hasGroup)
LodRequestManager.drainQueue                                6 slot admission tryAdmit [sync 128] ⑩
  maxSendPerTick = ceil(scanned/20) [floor 16] ⑤            7 dedup attach / disk submit
  per-type gate: sync<128, gen<16   ⑥                       └ gen path: slot 16 + service 16+32 ⑪
     │  batched request packet [≤1024/pkt]                       │
     ▼                                                            ▼  ChunkDiskReader pool
   WIRE  ───────────────────────────────────────────────►   [threads 5 × 32 queue = 165] ⑨
     ▲                                                            │
InFlightTracker (10 s timeout → retry) ⑦                          ▼  results → OffThreadProcessor
     ▲                                                        readyPayloads (unbounded)
ClientColumnProcessor                                            → sendQueue [4000] ⑧
  decode queue [8000 count / 256 MiB] ②                          │
     │  depth 6000 → HALT tick; →0 budget                        ▼  flushSendQueue
     ▼                                                        BANDWIDTH (per tick):
   consumers (Voxy)                                             global ÷ activeCount ⑫
                                                               ∧ min per-player cap 20 MiB ⑬
   DirtyColumnBroadcaster (every 10 s) ──── dirty push ──────►  ∧ per-player burst bucket /4 ⑭
```

**Server tick order** (`RequestProcessingService.tick`): generation poll → player lifecycle + active-count → snapshot to processing thread → drain batched responses → submit generation tickets → **bandwidth-metered send** → dirty broadcast.
**Processing-thread cycle** (`OffThreadProcessor.processCycle`): apply events (invalidations/removals/dirty-clears) → drain disk results → deliver generation outcomes → route incoming requests → evict/save timestamp cache.

**Backpressure directions.** Server→client: `rate-limited` (retry later), `not-generated`, `up_to_date`, and silent queue-full drops. Client-internal (decode queue → scanner): the decode depth both scales the scan budget down (soft) and halts the whole tick (hard). Bandwidth exhaustion propagates *upward* on the server: a full byte-bucket stalls the send queue, which fills toward its cap, which eventually trips the router's queue-full break.

---

## 3. Mechanism inventory

Numbers ① – ⑭ key to the diagram. "Binds at default?" = does this limit ever actually reject/throttle with out-of-the-box config and a normal client.

| # | Mechanism | Default | Purpose | Binds at default? |
|---|-----------|---------|---------|-------------------|
| ① | Scan budget = `syncCap × BUDGET_MULTIPLIER(4)` | 512 pos/scan | discover positions to request | as headroom only — drain gate ⑥ is the real limit |
| ② | Decode-queue pressure (soft budget ramp + hard halt) | ramp→0 and halt at **6000** (of 8000/256 MiB) | stop offering when decode can't keep up | yes, under dirty storms / slow decode |
| ③ | Vanilla-load budget scale | quadratic | don't scan LODs while vanilla terrain still streaming | yes, on join/teleport |
| ④ | `skipNextScan` rate-limit backoff | 1 scan (~1 s) | global cooldown after any bounce | yes |
| ⑤ | Drip-feed `maxSendPerTick = ceil(scanned/20)` | floor **16** | spread a scan's sends over ~1 s | floor dominates in light traffic (see §4) |
| ⑥ | Per-type send concurrency gate | sync **128**, gen **16** | cap outstanding in-flight requests | **yes — the effective client limiter** |
| ⑦ | In-flight timeout → retry | **10 s** | recover lost/dropped requests | yes |
| ⑧ | `sendQueueLimitPerPlayer` | **4000** | bound server outbound backlog | **no — unreachable (§4)** |
| ⑨ | Disk pool + capacity gate | **165** (5×32+5) | bound concurrent disk reads; gate before submit | yes (multi-player / large scans) |
| ⑩ | Per-player sync slot cap | **128** | per-player fairness on the pool | only under multi-player contention |
| ⑪ | Generation caps | slot **16**, service per-player **16**, global **32** | bound concurrent chunk generation | per-player 16 binds; global 32 only with ≥3 generators |
| ⑫ | Global bandwidth ÷ active count | 100 MiB ÷ N | fair egress division | rarely (100 MiB is huge) |
| ⑬ | Per-player bandwidth cap | **20 MiB/s** | hard per-player egress | yes, on a fast link |
| ⑭ | Per-player burst bucket | allocation / 4 (~250 ms) | anti-hoard smoothing | yes |

---

## 4. Findings

### A. Redundant or doubly-enforced

**A1 — The per-player generation limit is enforced twice (⑪).** A not-found disk read escalates to generation and takes a `GENERATION` pending slot capped at `generationConcurrencyLimitPerPlayer = 16` (`OffThreadProcessor.handleDiskNotFound`). One hop later, `ChunkGenerationService.submitGeneration` independently checks `perPlayerActive < maxPerPlayerActive = 16`. The same per-player number is guarded by two mechanisms at two lifecycle points. They *may* diverge briefly (slot taken at escalation, service count at ticket submit) and piggybacking muddies it, so this is "verify then consolidate," not "obviously dead."

**A2 — The client over-provisions the request queue 4× on purpose (①/⑥, ②gen).** Scan budget is `syncCap×4` (512) but the drain gate only lets 128 sync in flight; the gen sub-cap is `genCap×4` (64) but the drain lets 16. The surplus is skipped each drain and re-discovered next scan. This is *intentional* headroom so freed slots refill mid-period — but it means the budget number never bounds anything, and a reader has to know it's 4× to reason about it. Worth a one-line comment at the constant, not removal.

### B. Never binds at default config (dead weight or pure defense)

**B1 — `sendQueueLimitPerPlayer = 4000` is unreachable (⑧).** A player can hold at most `syncSlotCap + genSlotCap = 128 + 16 = 144` resolved-but-unsent columns, because that's all the slots it can hold. 144 ≪ 4000, so the send-queue-full break never fires in normal operation. Keep as a hostile-input safety valve, but its default is 28× larger than reachable — either document it as a safety valve or lower it to something meaningful (e.g. 512).

**B2 — `RequestQueue`'s timestamp column + `peekTimestamp()` are dead in the live flow (genuinely removable).** The scanner writes a timestamp per queued position, but `drainQueue` re-derives the timestamp from `classify` at send time (correctly — a stamp can change between scan and drain) and never reads the stored one. `peekTimestamp()` has zero production callers (only tests). This is a real, safe deletion: drop the parallel `timestamps` array, `put`'s ts parameter, and `peekTimestamp`, updating the two tests that pin them.

**B3 — `MAX_BATCH_CHUNK_REQUESTS = 1024` never clamps `maxSendPerTick` (⑤).** It would require a scan to queue ≥20480 positions; the budget maxes at 4000. Purely defensive on that path (it still legitimately sizes the send buffers). Leave it.

**B4 — The router's direct-generation branch is unreachable — but keep it.** Both platforms always construct a disk reader, so generation is only ever reached via disk-not-found escalation. The branch's own comment explains it's a deliberate guard so *any* future generation-ticket producer stays covered by the in-flight taint tracking. Do **not** delete; it's intentional defense with a documented reason.

**B5 — The disk `RejectedExecutionException` path is now a fallback (⑨).** PR #34's pre-submit capacity gate makes it unreachable in normal operation. Correctly retained as belt-and-suspenders with the throttled warn. Leave it.

**B6 — `MIN_SEND_PER_TICK = 16` makes the "adaptive" drip rate constant in light traffic (⑤).** `ceil(scanned/20)` only exceeds 16 when a scan queues >320 positions; below that the floor is the send rate. So the adaptive formula is effectively "16/tick" for steady-state play and only ramps on big fresh scans. Fine, but the adaptivity is doing less than it looks — worth knowing when tuning.

### C. One constant / one signal, many meanings

**C1 — `6000` drives three behaviors (②).** The decode-queue depth of 6000 (¾ of 8000) is simultaneously the denominator of the soft budget ramp *and* the hard tick-halt threshold. Because the halt is checked before the scan, the top quarter of the soft ramp is unreachable in the same tick — the soft ramp and hard halt partly shadow each other. Not a bug, but two behaviors welded to one number; splitting them (ramp to 8000, halt at 6000) would make each independently tunable.

**C2 — `rate-limited` aliases four distinct causes (P1).** The wire signal and the `totalSyncRateLimited`/`totalGenRateLimited` counters are emitted for: (a) per-player slot full, (b) disk-pool capacity gate, (c) duplicate-in-flight `ts≤0` bounce, and (d) the disk-saturation fallback. An operator reading `/lsslod` or soak metrics cannot tell per-player pressure from global-pool pressure from dedup churn. Splitting the counter by cause is the single highest-value, lowest-risk change here.

### D. Coupling / latent fragility

**D1 — Bandwidth idle-dilution (⑫, P2).** `activeCount` counts every handshake-completed player, not just those with queued sends. An idle player who finished loading still divides the global budget, so an actively-downloading player gets `global/N` instead of `global/(active senders)`. On a busy server with many settled players and one newcomer, the newcomer is throttled by peers doing nothing. (The `TwoPlayerGameTests` "idle dilution" case pins the *current* behavior, so changing it means updating that expectation deliberately.)

**D2 — Timeout sweep starves under movement or backpressure (⑦, P3).** The 10 s in-flight timeout sweep rides the scan cadence, and the cadence is reset on every chunk-boundary crossing and skipped entirely while the decode queue is halted. So a fast-moving player, or one under sustained decode backpressure, stops sweeping timeouts — lost requests aren't retried until they rest. The retry itself is correct; its *trigger* is coupled to the wrong clock. Give the sweep its own cadence independent of scan gating.

**D3 — Three stacked reactions to one rate-limited response (④/⑥, P5).** A bounce triggers (a) `skipNextScan` (whole next scan skipped, ~1 s, global), (b) a per-position `retry` mark, and (c) the per-type concurrency gate that would have prevented the over-send anyway. A single bounce thus both freezes all scanning for a second and re-queues the position — arguably double-penalizing, and it makes the client's true offer-rate under load hard to predict. One coherent backoff (AIMD on the budget, or just the retry mark) would be simpler and more predictable.

### E. Bandwidth: three superimposed egress limits (P6)

The send path is gated by three things at once: the global bucket divided by active count (⑫), a hard per-player cap via `min()` (⑬), and a per-player burst bucket with a /4 cap (⑭). This is a legitimate two-level token-bucket (global fairness + per-player smoothing), but it's the most intricate mechanism in the system for a feature — bandwidth shaping — that rarely binds at the 100 MiB/20 MiB defaults. Worth an explicit decision: keep it and document the design, or simplify to a single per-player bucket clamped by a global counter.

---

## 5. Proposals

Each: **what**, **why**, **behavior change**, **risk/effort**. Ranked by value ÷ risk. Per the project's "review-findings-vs-pinned-decisions" history, every one should be checked against the pinned tests named before implementing — several current behaviors are deliberately pinned.

**P1 — Split the `rate-limited` diagnostic by cause. [do first]**
*What:* give `incrementRateLimited` a cause enum (slot-full / pool-capacity / dedup-in-flight / saturation-fallback) and surface the breakdown in `/lsslod diag`, the exporters, and soak snapshots. *Why:* fixes C2 — the current single counter can't distinguish per-player from global-pool pressure, which is exactly the ambiguity that made issue #32 hard to reason about. *Behavior:* none (observability only). *Risk:* very low. *Effort:* small; touches `ProcessingDiagnostics`, the router call sites, and the exporter schemas (+ their parity tests).

**P2 — Divide bandwidth by active senders, not all handshaked players.**
*What:* count a player toward `activeCount` only if it has queued/ready payloads (or requested within the last N ticks). *Why:* fixes D1 idle-dilution so a newcomer isn't throttled by settled peers. *Behavior:* yes — improves fairness; **update `TwoPlayerGameTests` idle-dilution expectation deliberately.** *Risk:* low–medium (fairness edge cases; make sure a player mid-send isn't flip-flopped in/out of the divisor). *Effort:* small.

**P3 — Give the in-flight timeout sweep its own cadence.**
*What:* run `sweepTimeouts` on a fixed tick counter independent of scan gating and the backpressure halt. *Why:* fixes D2 so movement/backpressure can't strand lost requests for far longer than 10 s. *Behavior:* yes (retries fire on time under load). *Risk:* medium — verify it doesn't fight the halt (sweeping is cheap and doesn't send, so it should be safe during a halt). *Effort:* small–medium.

**P4 — Delete `RequestQueue`'s dead timestamp column.**
*What:* remove the `timestamps` array, the ts param on `put`, and `peekTimestamp()`; update the two tests. *Why:* fixes B2 — genuinely dead in the live flow. *Behavior:* none. *Risk:* very low. *Effort:* small.

**P5 — Consolidate the client's rate-limit reactions. — ✅ IMPLEMENTED (`AdaptiveConcurrencyWindow`, see `docs/planning/client-backoff-plan.md`).** This retired `skipNextScan` for an AIMD window and, as part of the same work, decoupled the window's update (and, in effect, the scan cadence for movement) from the starved clock — resolving **D3** (stacked reactions) and the movement leg of **D2** (the timeout sweep can now move onto the same independent counter). The near-`MIN` dedup false-shrink is the remaining tie to **P1**.

*Original write-up:* pick one backoff — most likely keep the per-position `retry` mark and replace `skipNextScan` with a multiplicative budget cut that recovers additively (AIMD), so sustained pushback smoothly lowers the offer rate instead of stuttering whole scans. *Why:* fixes D3 and folds A2's over-provisioning into one adaptive rate; this is the client-side half of the "close the loop" idea from the issue-#32 architecture analysis. *Behavior:* yes (subtle; smoother under load). *Risk:* medium — `SpiralScannerTest` pins the current budget/skip behavior extensively; needs careful re-pinning. *Effort:* medium.

**P6 — Decide the bandwidth design.**
*What:* either (a) keep the two-level bucket and add a design comment explaining global-fairness + per-player-smoothing, or (b) simplify to one per-player bucket clamped by a shared global atomic counter. *Why:* addresses E — the most complex mechanism for a rarely-binding feature. *Behavior:* none (a) or minor (b). *Risk:* low (a) / medium (b). *Effort:* small (a) / medium (b). Recommend (a) unless bandwidth shaping is on the roadmap.

**P7 — Reconcile the doubly-enforced generation per-player cap.**
*What:* determine whether the `GENERATION` slot cap (16) and the gen-service per-player active count (16) ever bind independently (piggyback / timing windows); if not, drop one. *Why:* fixes A1. *Behavior:* none if truly redundant. *Risk:* medium — the generation in-flight/taint accounting is subtle and has had per-player-leak bugs before (`inflight-guard-per-player`). *Effort:* medium; must be test-driven.

**P8 (optional, larger) — Document or lower the unreachable `sendQueueLimitPerPlayer`.**
*What:* either comment it as a hostile-input safety valve or lower the default to ~512 (still well above the ~144 reachable). *Why:* B1. *Behavior:* none (comment) / negligible (lower). *Risk:* low. *Effort:* trivial.

---

## 6. What to leave alone (the parts that are right)

- **The idempotent, position-keyed protocol.** Late/duplicate/lost responses all self-heal; this is the system's core strength and the reason bounce-and-retry works. The honest re-resolution ladder (`ts>0` claims data → up_to_date; `ts≤0` re-resolves) is subtle and correct.
- **Cross-player dedup** — collapses overlapping reads with clean attach/detach and per-player-slot accounting.
- **Derived admission** — "a pending-map entry IS the slot" removed an entire parallel limiter subsystem in v16. Don't reintroduce separate permit objects.
- **The v16 simplification is already banked.** The old `simplification-review.md`'s rate-limit/queueing/addressing bundle is implemented; don't re-propose it.
- **The defensive fallbacks** — the `RejectedExecutionException` path (B5), the unreachable-but-guarded generation branch (B4), and the `MAX_BATCH_CHUNK_REQUESTS` buffer clamp (B3) are cheap insurance with documented rationale.

---

## 7. Suggested roadmap

1. **P1** (diagnostic split) and **P4** (dead-column delete) — small, zero/near-zero behavior change, do together as one cleanup PR.
2. **P3** (timeout-sweep cadence) and **P2** (bandwidth active-senders) — two latent-bug fixes; separate PRs, each with a soak scenario to confirm (a movement-heavy retry scenario for P3; a settled-peers-plus-newcomer fairness scenario for P2).
3. **P8** / **P6(a)** — documentation-grade clarifications, fold into any nearby PR.
4. **P5** (client backoff consolidation) and **P7** (gen cap reconciliation) — larger, test-heavy; schedule only if the added predictability/simplicity is worth the re-pinning cost. P5 pairs naturally with the client-side AIMD idea deferred from the issue-#32 analysis.

Everything here is behavior-preserving or behavior-improving and independently shippable; none requires a protocol bump (P5's wire format is unchanged — it's client-internal pacing).
