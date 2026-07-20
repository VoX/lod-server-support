# Server-side negative miss memo — design & implementation plan

**Status:** IMPLEMENTED on `feat/miss-memo` (branched off the v0.7.0 release branch,
2026-07-19), and SHIPS WITH v0.7.0 by explicit maintainer decision (2026-07-19) — a
deliberate reversal of this doc's original "target v0.7.1, never a release-week change"
guardrail, taken after the full review/gauntlet cycle below. Maintainer decisions: **TTL 30 s** (`missMemoTtlSeconds`, clamp [0,60],
0 = kill switch), region-granular variant stays deferred, diag naming `disk.memo_hits`.
All Tier 1 (both platforms) + Tier 2 (memo live under default config) green; check_soak
selftest 140 cases with the A5 memo term. One implementation discovery beyond this plan:
the disk-drain's not-found stamp guard called the full `invalidate()`, which would have
erased every memo the instant it was written — split into `invalidateStamps()` (drain,
stamps-only) vs `invalidate()` (edits, clears misses too), pinned by the churn-loop test.
Supersedes the sketch at `v0.7.1-candidates.md` item 10a. Original plan below.

**Soak A/B finding (2026-07-19, WSL2 + a live concurrent test server — read before judging
an A7 red):** fresh-backfill with the memo ON red-flags A7 on constrained boxes with 12-15
timeout-triaged `disk.errors` (decisive signature — counter-based since the throttled-WARN
change: `disk.errors` increased with zero `"Failed to read chunk"` log lines, **A5 exact**
— the memo term closes the identity; see the CLAUDE.md A7 catalog entry). The kill-switch
A/B on identical code (`missMemoTtlSeconds: 0` scenario override, now allowlisted in
check_soak) proved it is the MEMO's dynamics, not the environment: memo-off = PASS, zero
timeouts, `disk.not_found` 17,378; memo-on = `not_found` 2,129 + ~20,800 memo hits,
convergence ~2x faster (37 vs 17 of 54 snapshots verified-quiescent), fewer column sends —
and 12-15 background reads starved past the 10 s timeout. Mechanism: pre-memo, the churn
reads accidentally PACED generation admission (every gen escalation waited behind a fresh
read); the memo removes that pacing, generation runs at its full caps continuously, and the
resulting save traffic monopolizes the shared single-threaded vanilla IOWorker — on which
LSS's `useBackgroundReadPriority` reads deliberately run BELOW foreground work — so the few
remaining real data reads can queue past `DISK_READ_TIMEOUT_SECONDS` on slow IO. Every such
timeout is transient and self-heals (error triage → not-found ladder → re-declaration), so
this is log-noise + an A7 red on constrained boxes, not data loss. Accepted for now as the
price of the speedup; candidate refinement (v0.7.2): gate memo escalation on a reader-latency
signal (the `AdaptiveReadThrottle` pattern) so generation admission re-acquires pacing
exactly when reads slow down. The fresh-backfill gen caps (40/64, above the 16/32 defaults)
exaggerate the effect; default-config servers see proportionally less write pressure.

**Ordering regression + the pacing fix (2026-07-19, maintainer-reported live):** the memo
also regressed backfill ORDERING badly, even on vanilla Fabric — it deleted the
negative-feedback pacing the read pipeline provided implicitly (slot refills used to cost a
~1 s re-declare→read→miss round trip, keeping the outstanding set small, co-ring, and
admitted in ≈proximity order; instant memo refill pinned the full gen cap in flight across
the whole frontier+2 window, and memo-fresh far positions leapfrogged near positions stuck
behind the starved reads). Fixed by two explicit memo-path rules in
`escalateMissToGeneration` (`paced=true` — SUPERSEDED by the movement follow-up below:
the flag is gone, both paths run the rules, and ttl=0 therefore no longer reproduces
pre-memo ORDERING dynamics, only pre-memo read churn): the **nearer-read hold** (refuse while any nearer SYNC read
is pending — kills the leapfrog and re-creates the read-latency feedback loop) and the
**generation cohort span** (candidate ≤ nearest-outstanding-ticket ring +
`MAX_GENERATION_COHORT_SPAN`(1) — restores ring-by-ring waves; the outstanding set is a
RESTRICTION, never the window's anchor — the straggler-leak lesson). `generation.order_gated`
and `generation.inversions` are now soak-exported on both platforms (they were diag-only,
which is why recordings couldn't see the regression). Measured A/B (same box, same timeline):

| fresh-backfill      | memo OFF (pre-memo)* | memo unpaced | memo-rung pacing | unified pacing | + frontier damping |
|---------------------|---------------------|--------------|------------------|----------------|--------------------|
| inversions          | 179/1983 (9.0%)     | unmeasured, visually severe | 81/2012 (4.0%) | 46/1992 (2.3%) | **2/1993 (0.1%)** |
| miss re-reads       | 17,643              | ~2,130       | 2,134            | 2,144          | 2,533              |
| quiescent snapshots | 25                  | 37-39        | 39               | 38             | 34/34 (full PASS)  |
| disk.errors (A7)    | 0                   | 12-15        | 10               | 5              | **0**              |
| order_gated         | —                   | —            | ~14k             | 16,134         | 37,140             |

\* the "memo OFF" column is a true pre-memo baseline only for this recording — after the
unified pacing the rules are ttl-independent, so a fresh ttl=0 run restores the read churn
but NOT this column's ordering dynamics.

The paced memo is ~2x BETTER ordered than pre-memo, and unifying the rules onto the
delivery path halved both residuals again (explicit cohort rules order admission more
strictly than emergent read-completion order ever did) while keeping the full ~8x read
reduction and the convergence speedup. Residual: ~5 timeout-triaged disk.errors on
constrained boxes (down from 12-15 unpaced; the A7 catalog entry stands); the v0.7.2
reader-latency gate above remains the refinement if that needs to reach 0.

**Movement follow-up — pacing UNIFIED across both admission paths (2026-07-19,
maintainer-reported: "chunks generating out in space ahead of me while flying"):** the
rules were initially memo-rung-only, justified by the delivery path's emergent pacing
(read completions arrive ≈ proximity-ordered). That justification only holds for a
STATIONARY player with fast reads: under movement, completions are proximity-ordered
relative to the SUBMISSION-time position, and with generation save pressure starving reads
by seconds, a stale far miss delivering late escalated UNPACED — only frontier-gated, with
the frontier at the view-edge crescent (~ring 12), so isolated chunks admitted and popped
at ring 13-14 ahead/flanking a moving player. Fix: the paced flag is gone; BOTH paths run
the nearer-read hold + cohort span. A held delivery is memoized + dropped and re-enters
through the closest-first drain — pacing everywhere converts delivery disorder into drain
order, making the drain the sole effective orderer of generation admission. This
strengthens the historical gate pin (`generationRefillMustNotRaceBeyondTheOldestOutstanding
Ticket` re-calibrated: refill now holds at nearest-outstanding+1, tighter than the old
frontier+2 refill window). Read-timeout console noise also demoted: TimeoutException is a
documented transient, now one throttled WARN line per minute with a count instead of a
stack trace per chunk.

**Review round (2026-07-19, three-agent subagent review of the full changeset — findings
validated from scratch, fixes mutation-checked):**
- **`submitRead`'s outer `Throwable` catch delivered an AUTHORITATIVE miss** (via the
  `empty()` alias): an `Error` (SOE on corrupt NBT, OOM) during a read of an EXISTING
  chunk would have memoized a false absence for the TTL. Now `notFoundFromError`; the
  ambiguous `empty()` alias is deleted outright. Pinned in `AbstractChunkDiskReaderTest`.
- **The memo write ignored the edit-overtake stale guard**: an invalidation consumed for a
  not-found delivery meant a save raced the read — writing the memo after the falsifying
  `invalidate()` re-asserted absence with the one falsification event already spent. The
  delivery now strips the authoritative flag (`authoritativeMiss && !staleAgainstEdit`).
  Pinned: `editOvertakenMissDeliveryNeverSeedsTheMemo`.
- **Gen-disabled servers no longer write memo entries** (the rung that reads them is gated
  on `generationAvailable`, and entries only expire lazily on read — dead weight until
  wholesale eviction). Pinned: `genDisabledAuthoritativeMissWritesNoMemo`.
- **New pins for gaps the review found**: the delivery-path nearer-read hold (the movement
  bug's exact mechanism had no direct test — `aMissDeliveryHoldsEscalationWhileANearerRead
  IsInFlight`), the delivered-data clear-matrix flavor, the cache-level `invalidateStamps`
  memo-preservation split, and the nanoTime-wrap-safe TTL comparison.
- **Known divergences from the plan below, accepted**: miss maps evict against their OWN
  per-dimension cap, not the shared stamp budget (worst case ~1.5x the modeled memory,
  bounded + kill-switchable — v0.7.1 candidate); the promised `memo_size` diag gauge was
  dropped deliberately (it would iterate the map cross-thread; `missCount()` is a test
  seam only); the plan's Tier-2 memo gametest, Paper twin tests, and a live
  requirement-2 ("already generated") pin are deferred to `v0.7.1-candidates.md`.

**Movement "line inversions" — admission-trace verdict + frontier outward damping
(2026-07-19, maintainer-reported: far ring-arcs popping after "move fast then stop"):**
diagnosed with the new `-Dlss.admissionTrace=true` instrument (one `[lss-adm]` line per
escalation decision; test-server.sh enables it on dev Fabric servers) against paired
client traces. Verdict: the gates were HONEST — the far wave (40 tickets, the x=39/z=-36
leading-edge bands) was admitted at `r=18-19, f=18, sync=18, gen=18`, i.e. candidate at
the frontier with a clean window, because at that drain instant the near field was
GENUINELY all-satisfied (crescents probe-served, rings 6-17 answered up_to_date/disk in
the same second). One second later, continued movement minted ~120 brand-new near
positions (frontier collapsed to 9, admitted `via=memo` at `r=9-11, f=9-10`); ~2 s
generation latency then displayed the two waves in admission order — far line first,
near bands after. So the frontier is NON-MONOTONIC under movement, and instantaneous
gates cannot refuse work that is legitimately the frontier NOW on behalf of work that
will only exist a second from now. Fix: **frontier outward damping** — the spread-gate
reference (`liveFrontierRing`) follows inward observations instantly but climbs outward
at one ring per `FRONTIER_OUTWARD_DAMP_MILLIS_PER_RING` (333 ms, ~3 rings/s), so a
movement-minted oscillation can never drag the window to the far edge between mints;
after stopping, the reference climbs to the true frontier in a few seconds and far fill
resumes. Stationary cost ~zero: ring-by-ring advance stays inside the +2 spread window
(candidates at old-frontier+1/+2 admit without waiting on the damper). A long-idle stamp
gap accumulates outward budget, so teleport-into-explored-terrain and
resume-after-convergence apply instantly. Alternatives rejected: movement-hold (binary
cliff — a slow walker freezes far fill forever; rhymes with the retired client movement
debounce), velocity prediction (fragile), smaller admission waves (softens the pop,
keeps the order), client predictive declaration / ticket cancellation (protocol churn /
vanilla can't cancel). Pins: `frontierOutwardDampingHoldsJumpsAndFollowsInwardInstantly`,
`productionDefaultEnablesOutwardDamping` (wired nonzero default — rigs run damping OFF
to keep the pre-damping gate pins calibrated), `dampingIntervalZeroRestoresInstant…`,
and `movementMintedNearWorkOutrunsAFarFrontierObservation` (the live-trace scenario
end-to-end: far held -> minted near admits instantly -> far follows after the climb).

## Goal

Under server-owned generation, a position waiting for a generation slot re-reads disk at
~1 Hz until a slot frees (the deliberate miss-churn loop pinned by the
`generation-capacity-stress` soak: re-declare → SYNC slot → disk read → not-found →
gen-full drop → repeat). On a fresh world this is hundreds of cheap not-found reads per
player per second for minutes. The memo records "this chunk was authoritatively absent on
disk at time T" server-side, so a re-declared miss within the TTL **skips the redundant
read and falls through to the generation decision immediately** — freeing SYNC slots and
reader-pool capacity for positions that might actually have data.

Two maintainer requirements are load-bearing in this design:
1. **When chunk generation finishes — in EVERY outcome flavor — the memo entry is cleared.**
2. **The "chunk already generated by another process" fall-through must stay graceful**
   (a memo-hit that escalates to generation for a chunk that meanwhile appeared on disk
   must serve correctly).

## Invariants preserved (why this is not v16's client classification)

- The **server** owns the knowledge, derived from its own disk observation, TTL-bounded.
  The client still declares only "I want / I have"; nothing client-side changes. Wire: zero
  changes, no protocol bump.
- The disk miss is still the generation trigger — the memo is *the same miss, cached
  briefly*, not a second routing input. Route count stays one; the memo only short-circuits
  the read inside it.
- Re-declaration remains the sole self-healing mechanism. The memo can only DELAY service
  by ≤ TTL in its worst (stale) case — it can never park, answer, or satisfy anything.
- `RESPONSE_NOT_GENERATED` emission conditions are untouched (gen-disabled / permanent
  failure only). A memo hit never produces a wire answer.

## Design

### Data structure — inside `ColumnTimestampCache`, beside (never inside) the stamps

Per-dimension `Long2LongOpenHashMap missExpiryByPosition` (`packedXZ → expiry deadline,
monotonic nanos`) as a sibling of the timestamp maps in
`common/src/main/java/dev/vox/lss/common/voxel/ColumnTimestampCache.java`.

- **Never overload the timestamp value space.** The 0-stamp ghost history (client legacy
  stamps, the DirtyContentFilter zero-hash sentinel) forbids sentinel encodings. Misses get
  their own maps and their own API.
- New API: `putMiss(dim, packed, now)` (stores `now + ttl`), `isFreshMiss(dim, packed,
  now)` (false + lazy-remove when expired), `clearMiss(dim, packed)`,
  `missCount()` (diag gauge).
- `put(dim, packed, timestamp, now)` — the positive stamp — **implicitly clears the miss**
  for that position. This one choke point makes every serve path (in-memory probe serve,
  disk success, generation delivery, resync) a memo-clear for free.
- `invalidate(dim, positions)` — the dirty/edit path — clears misses too (a dirty mark
  means a save happened: the chunk exists; the memo is falsified).
- Memory: miss entries count toward the same per-dimension entry budget
  *(as-built divergence: the miss map checks its own equal-size cap independently —
  see the review-round addendum above)*
  (`perDimensionTimestampCacheSizeMB` → `mbToEntries`) and are evicted by
  `evictIfOversized` FIRST (a miss is always cheaper to lose than a stamp — losing one
  costs a redundant read, losing a stamp costs a redundant serve).
- **Excluded from `save()`/`load()` and `snapshotForSave()`**: a restart may follow
  external world writes the server never observed; seconds-fresh info must not survive a
  process boundary. (Also keeps the cache file format unchanged — no version bump.)
- Threading: identical to the stamps — processing-thread-owned; writes arrive via the same
  paths that already write stamps (disk delivery, generation drain, invalidation events
  applied at the mailbox). No new synchronization.

### TTL / config

`missMemoTtlSeconds` in `ServerConfigBase` (both platforms verbatim, per project pattern):
default **8**, clamp **[0, 60]**, **0 = feature off** (kill switch folded into the knob; no
separate boolean). 8 s ≈ eight declaration cycles — kills ~85-90 % of churn reads while
capping any staleness at a service *delay* strictly better than v16's ~10 s retry cadence.
Server-internal; not on the wire; the v16 compat shim is unaffected.

### Write site — exactly one, and it is the narrow one

`OffThreadProcessor.handleDiskNotFound`, normal path only (`pending != null`), records the
memo **before** the gen-availability ladder runs (a gated or slot-full drop still learned
the chunk is absent — that knowledge is the whole point).

Explicitly NOT written on:
- **Error/timeout triage.** A `TimeoutException` from the 10 s read guard
  (`NbtSectionSerializer.readAndSerializeSections:52` / Paper twin) or any read exception
  resolves down the not-found ladder by design (law A5's `d_nf += disk.errors` fold) — but
  it says nothing about existence. Memoizing it would suppress reads of an EXISTING chunk
  for TTL. Implementation note: verify the delivered result distinguishes
  authoritative-empty from exception-triaged; if the current result shape can't (both may
  arrive as "no data"), add an `authoritativeMiss` boolean to the disk-result record —
  set only on the `optionalTag.isEmpty()` path (Fabric) / Moonrise empty-read (Paper).
- **The ghost path** (`pending == null` — raced/duplicate delivery): rare, and a stale
  observation by construction.
- All-air sentinel results (they are a SERVE, not a miss).

### Read site — one rung, placed after every resolving rung

In `IncomingRequestRouter.tryAdmitAndSubmit`, before SYNC admission:

```
if (diskReadingAvailable && cache.isFreshMiss(dimension, packed, now)) {
    diagnostics.addMemoHit(1);
    return escalateMissFromMemo(state, playerUuid, req, packed, dimension);
}
```

- The drain-order ladder above it is untouched, which is load-bearing:
  `resolvedAsDuplicate` (pending/in-flight skip), send-queue gate, `resolvedFromTimestamp`
  (a position with a real stamp can't be memo-blocked — put() cleared the miss), and
  **`resolvedFromLoadedProbe` stays ahead of the memo**, so a chunk loaded by walk-in play
  serves via probe regardless of a stale miss entry (this closes the default-config Paper
  walk-in case, where generation does not mark dirty).
- `escalateMissFromMemo` reuses the generation half of `handleDiskNotFound`, extracted into
  a shared helper (`escalateMissToGeneration(playerUuid, state, packed, cx, cz,
  claimsData, dimension)`) so the two paths cannot drift: same order-spread gate (with its
  `gen_order_gated` counting), same GENERATION `tryAdmit`, same
  `addGenerationInFlight` + ticket enqueue, same silent-drop counting on a full slot.
  `claimsData` comes from `req.clientTimestamp() > 0` exactly as the SYNC path derives it.
- Slot semantics: the memo path takes a **GENERATION slot only** — no SYNC slot, no reader
  pool. Deliberate behavior change: under disk-pool saturation (`NO_DISK_HEADROOM` stops
  the pass today), memo-hits ahead of the stop still escalate — generation keeps flowing
  while disk is busy. Document in the router's drain comment.
- Drain result plumbing: the helper returns submitted/dropped; both map to the existing
  `SUBMITTED`-style "handled, keep draining" disposition (a memo-hit is never retained —
  its drop is the standard transient drop, healed by re-declaration).
- Frontier stamping: unchanged — a memo-hit entry is "real work" and the drain already
  stamps the first such entry.

### Clear sites — the complete list (requirement 1)

| # | Site | Covers |
|---|---|---|
| 1 | `ColumnTimestampCache.put()` implicit clear | every serve: in-memory probe, disk success, **generation success delivery**, resync |
| 2 | `processGenerationReady` — explicit `clearMiss` for EVERY drained outcome, positioned **before** the `state == null` / dimension-mismatch `continue`s (the miss is a world fact, not a player fact) | generation finished without a stamping serve: **permanent failure, transient timeout, all-air edge, player-gone, dimension-change** |
| 3 | `ColumnTimestampCache.invalidate()` | dirty marks / edit-overtake invalidation events (a save happened → chunk exists) |
| 4 | Lazy TTL expiry in `isFreshMiss` | positions never re-asked stop costing memory on next touch |
| 5 | `evictIfOversized` (misses first) | memory pressure |

Rows 1+2 together are maintainer requirement 1: **generation finishing always clears the
memo** — successful generation through the put() choke point, every non-serving outcome
through the explicit drain clear. Known acceptable gap: the service-side ticket-drop paths
(`drainGenerationTicketRequests` state-gone / dimension-mismatch — the documented A5
boundary drops) produce no outcome to drain; their memo entries die by TTL within seconds.

### The "already generated by another process" fall-through (requirement 2)

**Today's behavior, verified from source — correct, but unmeasured:** generation on both
platforms is load-if-exists by construction. Fabric: `addTicketWithRadius(LSS_GEN_TICKET)`
+ `getChunkNow` polling (`ChunkGenerationService.java:111,156`) — a ticket on an
already-on-disk chunk loads it to FULL without regenerating. Paper:
`scheduleChunkLoad(..., ChunkStatus.FULL, ...)` — same semantics. The loaded chunk is
serialized and served exactly like a generated one, the serve stamps the timestamp cache
(clearing the memo, rung 1), and subsequent asks take the up-to-date/disk paths. Nothing
misbehaves; the cost is a full chunk load + a GENERATION slot where a targeted region read
would have sufficed.

Today this race window is milliseconds (read-issue → miss-delivery). **The memo widens it
to ≤ TTL**, so the plan must (a) pin the graceful path with tests it never had, and (b)
keep the window's edges tight:

- **Pin with tests (both platforms):** a generation submitted for a chunk that already
  exists on disk completes as a load, serves the column with correct content, stamps the
  cache, clears the memo, and releases the ticket/slot — no error, no `NOT_GENERATED`, no
  double-serve. (Fabric: gametest seam — write a chunk to the region file, seed a memo
  entry, declare; Paper: the generation-service twin via the `launchAsyncLoad` seam.)
  *(NOT SHIPPED — deferred to `v0.7.1-candidates.md`; the fall-through behavior itself is
  the platforms' pre-existing scheduleChunkLoad/ticket semantics, unchanged by the memo)*
- **Window edges:** C2ME-ahead saves fire the first-save dirty mark on Fabric → rung 3
  clears the memo within a dirty-broadcast interval; loaded walk-in chunks are caught by
  the probe rung ahead of the memo on both platforms; everything else is bounded by the
  8 s TTL.
- **Accepted corner, document in the trace docs:** such a serve is tagged
  `COLUMN_SOURCE_GENERATION` (src:2) though the terrain pre-existed — the serve-source
  attribution becomes approximate for exactly this fall-through. A far src:2 inside the
  memo-TTL of a C2ME-ahead save is NOT the "LSS gate bug" signal the backfill-ordering
  model warns about; note this beside that model's diagnosis table.

## Accounting — law A5 gains one term, nothing else moves

New counter `disk.memo_hits` (ProcessingDiagnostics, processing-thread single-writer like
its siblings), incremented on every memo hit — **it is a virtual `disk.not_found`**:

```
A5:  d(disk.not_found) + d(disk.errors) + d(disk.memo_hits)
        == d(generation.submitted) + d(responses.not_generated) + d(service.miss_dropped)
```

Both memo dispositions balance by inspection: memo-hit → gen submitted (+1 LHS, +1
`generation.submitted`); memo-hit → slot-full/gated drop (+1 LHS, +1 `miss_dropped` — the
drop counting inside the shared escalation helper is reused verbatim). The
`generation-capacity-stress` premise (`superseded >= 100`, `not_generated == 0`) survives
unchanged — drops still count identically; only `disk.not_found` and read counts shrink.

Checker work: `law_A5` formula + comment in `check_soak.py`; schema: `disk.memo_hits`
required + monotonic in snapshots (both exporters — `BenchmarkMetricsExporter` +
`PaperSoakMetricsExporter` twins; old recordings become invalid, consistent with the
"v17-only harness, re-record rather than debug" policy); `--selftest`: one consistent case
and one doctored case per new branch (memo-hit-submitted balance, memo-hit-dropped
balance, an UNBALANCED memo count that must fire A5); `soak_report.py`: `memo_hits` is a
mechanism counter (never concerning); `exporter-contract` golden schemas updated.

## Observability

- `/lsslod diag`: DiskReader line gains `memo_hits=<n> memo_size=<n>`
  (`DiagnosticsFormatter` + golden-line tests, both platforms' command tests).
  *(as-built: only `memo_hits` shipped — `memo_size` would iterate the miss map off the
  processing thread; see the review-round addendum above)*
- Benchmark `server.json`: `disk_reader.memo_hits`.
- `/lss trace`: no client change (the client never sees the memo).

## Test plan (Tier 1 unless noted)

1. **`ColumnTimestampCacheTest`**: putMiss/isFreshMiss/expiry boundary (at-TTL vs
   TTL+1ns); put() clears the miss; invalidate() clears the miss; misses evicted first
   under budget pressure; misses absent from save/load round-trip; per-dimension isolation;
   ttl=0 stores nothing.
2. **Router (`IncomingRequestRouterTest`/`OffThreadProcessor*Test`)**: memo-hit takes no
   SYNC slot, issues no read, submits generation; memo-hit with gen slot full →
   `superseded`+`miss_dropped`+`memo_hits`, no read, entry NOT retained; memo-hit respects
   the order-spread gate (+`gen_order_gated`); expired memo → normal read path; probe rung
   beats a fresh miss entry (loaded chunk serves); timestamp rung beats it (stamped
   position answers up-to-date); ts>0 vs ts<=0 claimsData carried into the escalation.
3. **Disk-result twins**: authoritative not-found writes the memo; **timeout/exception
   triage does NOT**; ghost delivery does not; all-air does not.
4. **Generation outcomes** (the requirement-1 pins, Fabric + Paper twins): EVERY outcome
   flavor clears the memo — delivered, all-air, permanent failure, transient timeout,
   player-gone, dimension-change — including when the per-player `continue`s fire.
   *(as-shipped: delivered/permanent/transient/player-gone pinned in the shared common
   tests, Fabric-side; all-air + dimension-change flavors and literal Paper twins deferred
   — the clear is one line before any per-player logic, placement pinned by player-gone)*
5. **Already-generated fall-through** (the requirement-2 pins, both platforms): generation
   submitted for an on-disk chunk loads + serves + stamps + clears + releases; no
   NOT_GENERATED, no error. *(NOT SHIPPED — deferred, see v0.7.1-candidates.md)*
6. **`WantSetBudgetInvariantTest`**: unchanged (memo uses existing GENERATION slots) —
   verify it still re-derives.
7. *(NOT SHIPPED — deferred, see v0.7.1-candidates.md)* **Tier 2 gametest**
   (`GenerationLifecycleGameTests` + entrypoint listing already
   covered): one scenario — cold declare → miss (memo written) → immediate re-declare
   within TTL → no second read (assert read counters) → generation completes → served →
   re-declare → up-to-date. Plus the requirement-2 gametest from §5.
8. **Soak**: full 17-scenario Fabric run + the 4-scenario Paper run; expect
   `disk.not_found` to drop sharply on fresh-backfill and `generation-capacity-stress`
   while both keep their premises; re-baseline any not-found-derived expectations;
   `check_soak --selftest` extended per §Accounting.

## Sequencing & risk

1. Land after v0.7.0 tags, on a fresh branch off main.
2. Order: cache structure + unit tests → shared escalation helper extraction (pure
   refactor, tests green before the memo exists) → write/read/clear wiring → diagnostics +
   exporters + checker algebra → gametests → full soak.
3. Risk containment: `missMemoTtlSeconds: 0` disables the feature wholesale (the rung
   short-circuits on an always-stale answer); the helper extraction is the only change to
   the real-miss path and is diff-reviewable as a pure move.
4. Rollback: config 0 at runtime; revert is one commit (no wire, no cache-file, no client
   surface).

## Open questions for the maintainer

- TTL default 8 s — happy with that, or prefer matching the dirty-broadcast interval
  (10 s) so a C2ME-ahead save's dirty mark always lands within one memo lifetime?
- Region-granular variant ("region file absent", 32×32): deferred entirely by this plan
  (the per-chunk memo already covers fresh-world churn; region granularity complicates the
  generation-finish clear semantics you asked for). Keep deferred, or layer it later
  behind the same TTL discipline if header-lookup churn still shows in profiles?
- Should `memo_hits` appear in the benchmark `sources` block (as a non-serve counter it
  fits `disk_reader` better) — any preference for the diag naming?
