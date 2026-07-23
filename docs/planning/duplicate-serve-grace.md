# Duplicate-Serve Grace

**Status:** IMPLEMENTED 2026-07-23 (feat/duplicate-serve-grace) — ahead of the telemetry
trigger originally set below, as a deliberate call. The finding and fix shape date from
the same day's investigation: `docs/planning/benchmark-cpu-compare-design.md` (results
addendum) and the instrumented diagnostic in `benchmark-compare-results/diag-dupes/`
(gitignored; regenerate by pairing `runBenchmarkServer` with `runSoakClient` — the soak
client exports `dropped`/`queued`/`columns.known` at 5 s cadence, which the benchmark
client does not).

## What shipped (see §Implementation notes below for the deltas from the plan)

- `LSSConstants.SEND_DEPARTURE_GRACE_MILLIS` = 500 (0 disables — no stamps written).
- `AbstractPlayerRequestState`: `departedColumns` stamp map + `isWithinDepartureGrace`,
  stamped ONLY at the send-success decrement in `flushSendQueue`; expired entries removed
  on read AND swept once per grace interval from the flush path (most stamps are never
  re-asked — without the sweep the map grows with every column ever sent).
- `IncomingRequestRouter.resolvedAsDuplicate`: the grace rung sits between the
  `hasEnqueuedColumn` rung and the clear-and-re-resolve, returns `Duplicate.IN_FLIGHT`
  (same silent-skip disposition and frontier-pinning as the enqueued rung), and counts
  `grace_skipped` — never `re_resolved`, never an answer.
- Observability: `service.grace_skipped` in both exporters + the shared contract file,
  `grace_skipped=` on the `/lsslod diag` Sources line, `check_soak.py` SERVER_MONOTONIC
  (A6; no law consumes it — the skip answers nothing and touches no disk, so no identity
  moves), `soak_report.py` mechanism digest ("grace-absorbed re-asks").
- Pins: `FlushSendQueueTest` (stamp-on-success-only, expiry + sweep, grace-0 off,
  production default nonzero) and `IncomingRequestRouterTest.
  crossingReAskWithinTheDepartureGraceIsAbsorbedThenHealsAfterExpiry` (absorb inside the
  grace, ts>0 unaffected, honest re-resolve after expiry). The pre-existing crossing-race
  pin (`crossingReAskCostsExactlyOneRedundantServeThenConverges`) now documents itself as
  the POST-grace behavior — its rig bypasses the send queue, so no stamp exists there.

## The finding

During cold-backfill bursts, a v17 server re-serves ~7–8% of columns (measured: ~1,300 of
~16,600 at ~600 col/s on loopback, accruing smoothly across the burst). This is **not** a
bug and **not** client decode pressure (measured: decode queue peaked at 52 of 8,000, zero
drops, zero ingest failures). It is the **documented accepted race** in
`IncomingRequestRouter.resolvedAsDuplicate`:

1. The client re-declares every still-unstamped awaited position each 1 Hz scan — the
   pinned no-send-suppression rule (re-declaration is the single self-healing mechanism).
2. A ts≤0 re-ask arriving while the column is still in the send queue is skipped silently
   (`hasEnqueuedColumn` rung) — safe, no duplicate.
3. A ts≤0 re-ask whose payload departed the send queue *between the client building the
   batch and the server processing it* finds the done-bit set but the pipeline empty.
   Answering up-to-date to a client that claims nothing would risk a permanent invisible
   hole (the open-to-LAN bug class), so the server clears the done-bit and re-serves: one
   redundant disk read + send per crossed re-ask.

Expected crossings ≈ departure_rate × (batch-build → server-process latency) per scan;
~600/s × ~0.1 s ≈ 60/scan on loopback — matches the measured 60–80 dupes/s. Each position
duplicates at most once (it stamps on the dup and leaves the want-set — `up_to_date`
stays 0 throughout). **The window scales with client→server latency**, so a high-RTT +
high-throughput backfill could exceed 8%; that telemetry (a real deployment showing
`re_resolved` spiking during backfill) is the trigger for picking this up.

v16 had zero duplicates via client-side send-time in-flight suppression
(`tracker.isInFlight` skip + timeout sweep) — deliberately deleted in v17 for its
10 s-stall and permanent-hole failure modes (161 orphans found by the bandwidth-throttle
soak). Do not resurrect that; the fix below is server-side and preserves re-declaration.

A second, smaller component rides the idle tail (~300/run): probe-served view-area columns
do not seed `DirtyContentFilter` (deliberate — see the seeding rules), so their first
vanilla re-save hashes as changed and broadcasts, re-serving them once. Separate mechanism,
separate (lower) priority; noted here so the two aren't conflated again.

## Implementation notes (deltas from the plan as written)

1. **Trap #4 dissolved by placement.** The stamp map lives inside
   `AbstractPlayerRequestState` (next to `enqueuedColumns`), and the state object is
   replaced via removePlayer+registerPlayer on BOTH player removal and dimension change
   on BOTH platforms — so the lifecycle sweeps are automatic, and the gen-guard leak
   shape (a UUID-keyed map in the processor) cannot occur. Cross-thread: stamped on the
   main/pump thread (`flushSendQueue`), read on the processing thread — ConcurrentHashMap
   + the frontier-damping clock-seam pattern (wrap-safe `now - stamp` compares).
2. **The plan omitted steady-state pruning.** ~92% of stamps are never re-asked, so
   read-time removal alone leaks one entry per column ever sent. Added: an opportunistic
   sweep in `flushSendQueue`, rate-limited to once per grace interval.
3. **Send-failure drops were already double-protected.** The failure path not only must
   not stamp (the plan's trap #2) — its dropped positions get their done-bits proactively
   cleared via `OffThreadProcessor.clearDiskReadDone`, so the grace rung is structurally
   unreachable for that loss class. Only post-send losses (decode failure, consumer
   rejection) see the bounded ~1-scan heal delay.
4. **Test-surface fallout was smaller than budgeted.** The unit-pin rigs deliver through
   a payload capture that bypasses the send queue — no stamps, so every existing honesty
   pin held unmodified. The one live interaction: `TwoPlayerGameTests` step 1's ts≤0
   re-ask of a really-flushed position is now grace-absorbed for ~10 ticks before its
   retry loop (already present from the flake hardening) re-resolves — well inside the
   1200-tick budget, exactly-once counts unchanged. Tier 3's ingest-failure loop budget
   (600 ticks vs ≤ ~1.5 s added latency) needed no change.

## The fix shape: a "recently departed" grace

When `decrementEnqueued` fires **on the send-success path only**
(`AbstractPlayerRequestState` — the post-`sender.send()` decrement), stamp
`packed → departedAtNanos` in a per-player map. In `resolvedAsDuplicate`, a ts≤0 re-ask
within GRACE (~300–500 ms) of the stamp is treated like the enqueued rung: **silent skip,
done-bit kept**. After the grace, today's clear-and-re-resolve applies unchanged. The
client re-declares at 1 Hz regardless, so a genuinely lost payload heals at most ~1 scan
later than today.

## Side effects and implementation traps (from the 2026-07-23 analysis)

1. **Every genuine post-send loss heals up to ~1 s slower** — including the
   ingest-failure recovery loop, which is pinned end-to-end by a Tier 3 gametest
   (consumer rejects → re-served). Re-check that test's tick budget; this is a deliberate
   spec change to a delivery-honesty behavior.
2. **Stamp only on send SUCCESS.** The send-failure path bulk-drops the remaining queue
   and decrements every entry (`AbstractPlayerRequestState` catch block) — those columns
   never reached the wire; stamping them would suppress the heal of exactly the loss class
   the honesty rung exists for.
3. **The grace must stay a silent skip — never answer up_to_date.** A client claiming
   nothing must never be told it's current, or the permanent-invisible-hole class returns.
   As a skip, termination is guaranteed (grace expires → honest re-resolve).
4. **Per-player lifecycle bookkeeping.** The stamp map must be per-player (dedup fans one
   read to multiple holders; a global map would suppress another holder's FIRST ask),
   thread-safe (send path vs processing thread), monotonic-clock + wrap-safe (copy the
   miss-memo deadline pattern), and swept on player removal AND dimension change — the
   same leak shape as the gen-guard leak caught on proto/folia-region-probes.
5. **Pinned-surface churn:** the honest re-resolution ladder is unit-pinned;
   `re_resolved` stops counting absorbed crossings — add a `grace_skipped` counter for
   observability and check no soak conservation law folds `re_resolved` into an identity.
6. **Tuning is asymmetric:** too short buys nothing (window ≈ RTT + a tick); too long only
   adds the bounded heal delay of #1. ~500 ms covers realistic deployments.

**Non-effects:** wire-invariant (server-internal only, works identically through the v16
compat shim's synthetic want-set); no change to convergence silence, `NOT_GENERATED`
permanence, or the dirty/edit path (edits clear the done-bit outright, so an edited column
never reaches the grace rung; dirty re-asks carry ts>0 and take the up-to-date rung).

## Alternative with zero honesty-path risk

Keep the last few seconds of serialized column payloads in a small per-player (or global,
position-keyed) byte cache and let a crossed re-ask re-**send** without re-**reading**.
Halves the duplicate's cost (the disk read), touches no pinned semantics, but adds memory
and still performs the redundant send. Worth considering if the grace's test-surface churn
(#1/#5) is judged too expensive for the win.

## Why it was originally left alone (2026-07-23, superseded the same day)

Duplicates exist only during cold-backfill bursts (a converged client sends nothing), are
bounded at one extra serve per crossed position, and the reads are cheap and typically
page-cache-warm. The v16→v17 CPU benchmark charged v17 for them and still measured a wash
per distinct terrain column at a ~20% faster convergence — there was no current
performance case, only a latent one on high-RTT/high-throughput servers. Implemented
anyway (same day) as a deliberate call to close the latent case ahead of telemetry.
