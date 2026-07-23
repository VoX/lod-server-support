# CPU-Efficiency Comparison: v16 protocol (v0.6.2) vs v17 protocol (v0.7.x)

**Question.** Did CPU cost *per chunk delivered* regress between the v16 line (v0.6.2, client-driven
drip-feed + rate limiting) and the v17 line (v0.7.x, declarative want-set + server-owned
generation)? A higher chunk-loading *rate* is fine — the metric of record normalizes CPU by
delivered work, and the rate is reported alongside so a "faster but hungrier per second" result
is not misread as a regression.

**Scope.** Disk-read serving only: fully pre-generated world, generation disabled. Mixing in
generation would confound the interpretation (variable worldgen cost dominates CPU); the
generation scenario is a separate later pass. Fabric only (the benchmark harness is Fabric-side;
Paper twins the same pipeline architecture).

## Why the existing harness is reusable

`scripts/benchmark.sh no-cache` is exactly this workload: copy a pre-built base world into the
server run dir, start a dedicated server + auto-joining client, run for a fixed tick count,
export `server.json`/`client.json` + JFR. Decisive practical facts (verified against both refs):

- `scripts/benchmark.sh`, `scripts/lib/mc-run.sh`, and `BenchmarkHook` are **byte-identical**
  between v0.6.2 and HEAD; only `BenchmarkMetricsExporter`'s counter set differs (v17 counters).
  Each ref is driven by *its own* checked-in harness — zero backporting.
- The Gradle wrapper, loom, and loader versions are identical (only `mod_version` differs), so
  builds share one daemon and the run JVMs are launched identically (same JFR settings both —
  equal profiling overhead, and the recordings double as attribution evidence).
- Both benchmark run tasks use port 25565 and the same run dirs per checkout.

## Design

### Arms

| Arm | Checkout | Config delta | Isolates |
|-----|----------|--------------|----------|
| `v16` | worktree @ `v0.6.2` | shipped defaults | the old line as shipped |
| `v17` | main checkout (v0.7.1+) | shipped defaults (`useBackgroundReadPriority=true`) | the new line as shipped |
| `v17-fg` | main checkout | `useBackgroundReadPriority=false` | protocol change alone — v0.6.2 has no background-read scheduler, so `v17-fg` vs `v16` is the closest protocol-only comparison, and `v17` vs `v17-fg` prices the read-scheduler change |

The miss memo (`missMemoTtlSeconds=30`) stays at default in both v17 arms: with a fully covered
world there are no authoritative misses, so it is inert (verified per run by `not_found == 0`).
`enableV16Compat`/`enableV16ServerCompat` are inert on self-paired sessions.

### Workload — equal work, then idle tail

Each run: identical copy of one pre-generated base world (built once by a long `fresh` run,
shared byte-for-byte by all arms), **cold client column cache** (deleted before every run),
generation disabled, LOD distance `R` chosen so the `R`-square around spawn is fully inside the
generated area. Every arm therefore serves the *same finite column set* to convergence, then
idles. Run duration is sized so the slowest arm converges with ≥ 30 s of idle tail.

Validity gates per run (a run failing any gate is re-run, not averaged):
- `disk_reader.not_found == 0` and `errors == 0` — full coverage, memo never armed, no
  generation-path contamination.
- Delivery byte curve flat over the final ~20 s — the run actually converged.
- `columns_received` agrees across arms (±1%) — same work was done.
- System-wide non-benchmark CPU below a sanity threshold — no foreign load spike.

### Measurement — external, version-neutral

No code changes to either ref (nothing to keep "identical instrumentation" honest across a
27-release gap). A 1 Hz sampler alongside each run records:

- `/proc/<pid>/stat` utime+stime (all threads), RSS, thread count for the **server JVM**
  (discovered via `pgrep -f 'Dlss.benchmark.duration'`) and the **client JVM**
  (`pgrep -f 'quickPlayMultiplayer'`);
- `/proc/stat` system line — whole-box busy jiffies, for the noise gate;
- `ss -ti 'sport = :25565'` `bytes_acked` — the cumulative delivery byte curve, which is what
  defines the active window and detects convergence externally.

### Analysis

Per run, from the byte curve: `active window` = first→last second with byte growth;
`idle slope` = server CPU/s over the post-convergence tail. Headline metric:

> **idle-corrected server CPU-seconds per 1000 columns delivered**
> = (ΔCPU over active window − idle_slope × active_seconds) / columns × 1000

reported with the raw (uncorrected) variant, the delivery rate (columns/s over the active
window), CPU per MB sent, client-side CPU per 1000 columns (secondary — the client is a full
rendering client, so render noise is large even after idle correction), GC time, peak RSS, and
the idle slope itself (steady-state cost after convergence — the place a 1 Hz re-declaration
model could plausibly have regressed; note v17's converged client sends *nothing*, so this is a
real comparison, not a formality).

3 repetitions per arm, interleaved (`v16, v17, v17-fg, v16, …`) to decorrelate machine drift.
Medians headline; min/max shown. WSL2 box with a user's idle Paper test server (~1.3% CPU)
running — constant, affects all arms equally, recorded by the noise gauge.

### Known asymmetries accepted as "part of the version under test"

- v16 answers some traffic with `RESPONSE_RATE_LIMITED` bounces and client retry/backoff; v17
  replaces that with silent supersession + re-declaration. Different wire volumes for the same
  delivered work are *the thing being measured*, not a confound. `requests_received` /
  `positions_requested` are reported to quantify it.
- v0.6.2 reads at IOWorker FOREGROUND priority (no background scheduler existed); shipped-v17
  reads BACKGROUND on the same single IO thread. The `v17-fg` arm exists precisely to split
  this from the protocol delta.
- The v16 sync-slot knob (`syncOnLoadConcurrencyLimitPerPlayer`, default 200) equals v17's
  `SYNC_ON_LOAD_SLOT_CAP` constant (200) — no effective admission-cap difference.

## Files

- `scripts/benchmark_compare.sh` — orchestrator: worktree setup, one-time base-world build +
  coverage sizing, config staging, per-run sampler, run matrix, archival under
  `benchmark-compare-results/<timestamp>/`.
- `scripts/analyze_benchmark_compare.py` — stdlib analyzer: per-run gates + metrics, cross-arm
  aggregation, `report.md`. Its `coverage` subcommand counts only `Status == minecraft:full`
  chunks — region-header presence overcounts (MC saves proto-chunks at the generation
  frontier, and LSS disk reads resolve those as not-found).
- This document.

## Result (2026-07-23, matrix `benchmark-compare-results/20260723-105408/`)

**No CPU-per-chunk regression.** 3×3 matrix, all validity gates green (`not_found = 0`,
converged tails 219–260 s, foreign load ≤ 0.14 cores). Medians, idle-corrected server
CPU-s per 1000 delivered columns: v16 **2.25**, v17 **2.08**, v17-fg **2.00**; delivery
rate v16 **473** col/s, v17 **563**, v17-fg **594**. Per distinct terrain column (charging
v17 for its ~7% duplicate re-serves) it is an exact wash: 2.25 vs 2.25 vs 2.16. The
duplicates are NOT client decode drops (an instrumented rerun measured queue peak 52 of
8000, zero drops): ~1300/run are the documented accepted race in
`IncomingRequestRouter.resolvedAsDuplicate` — a 1 Hz re-declaration of a still-awaited
position crossing its own payload's send-queue departure re-resolves once redundantly —
plus ~300/run of dirty-broadcast tail serves (probe serves don't seed DirtyContentFilter).
v16 had zero duplicates because its client suppressed in-flight positions at send time,
the mechanism v17 deliberately deleted (10 s-stall / permanent-hole failure modes).
Total CPU to converge the same 16.5 k-column terrain: ~37 CPU-s in both
protocols, with v17 ~20% faster wall-clock. Background-read priority costs ~4% CPU and
~0.6 ms median read latency vs foreground (within noise) — the deliberate
gameplay-protection tradeoff. Full interpretation in the matrix dir's `report.md` addendum.
