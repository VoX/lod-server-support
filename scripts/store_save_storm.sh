#!/usr/bin/env bash
set -euo pipefail

# Phase 3 MSPT-pairing gate (lod-store-implementation-plan.md Phase 3): runs the
# store-save-storm scenario twice — lodStore=full (the gated arm, with the save-hook
# deposit named check) and lodStore=off (the pairing arm, laws only) — and compares
# paired PROCESS CPU over the STORM window (t in [60s,115s]; the save-alls fire at
# 65..110 s). The save-hook path must be CPU-neutral: the serialization it consumes
# was already paid by the DirtyContentFilter hash; the added work is a queue offer +
# the batcher's off-thread compress/insert of the first-observation set.
# mspt_avg_window is an INTERVAL metric pegged at ~50 ms below saturation (measured:
# both arms 49.97) — kept only as a cadence-hold overload guard, never the pairing.
#
# Usage: ./scripts/store_save_storm.sh
# CPU bound: on - off <= max(1.0 CPU-s, 25% of off) over the save-all-derived storm
# window. Honesty note (review): this is a gross-regression smoke bound on an n=1
# pair whose observed noise is ~0.5 s — it catches a hook doing real per-save tick
# work, not sub-noise drift; the per-column truth lives in the named check's
# zero-read re-serve leg + suppression pin.

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$PROJECT_ROOT/scripts/lib/harness-lock.sh"
harness_acquire
RESULTS_ROOT="$PROJECT_ROOT/soak-results"
WRAPPER_START_EPOCH="$(date +%s)"

log() { echo "[save-storm] $*"; }

latest_results() { # <scenario>
    local d
    d="$(ls -1d "$RESULTS_ROOT/$1-"2* 2>/dev/null | sort | tail -1)"
    if [[ -z "$d" || "$(stat -c %Y "$d")" -lt "$WRAPPER_START_EPOCH" ]]; then
        echo "[save-storm] ERROR: no fresh results dir for $1 (latest: ${d:-none})" >&2
        exit 1
    fi
    echo "$d"
}

log "=== arm: store-save-storm (lodStore=full, gated) ==="
harness_run_script "$PROJECT_ROOT/scripts/soak.sh" store-save-storm
ON_DIR="$(latest_results store-save-storm)"
log "=== arm: store-save-storm-off (lodStore=off, MSPT pair) ==="
# env -u: the burn-in lever (SOAK_LODSTORE_OVERRIDE=full) rewrites EVERY staged
# config's lodStore — inherited here it silently turns the off arm into full-vs-full
# and the CPU pairing measures nothing (4-agent round R4). The on arm may inherit it
# (full is what it pins anyway); the off arm must not.
harness_run_script env -u SOAK_LODSTORE_OVERRIDE -u SOAK_LODSTORE_BACKFILL_OVERRIDE "$PROJECT_ROOT/scripts/soak.sh" store-save-storm-off
OFF_DIR="$(latest_results store-save-storm-off)"

log "paired storm-window verdict (process CPU + cadence hold)"
python3 - "$ON_DIR" "$OFF_DIR" <<'VERDICT'
import json, statistics, sys

def rows_of(d, kind):
    return [json.loads(l) for l in open(d + "/server.jsonl", encoding="utf-8")
            if f'"{kind}"' in l]

def storm_window_ms(d):
    # The storm window is derived from the ACTUAL save-all command rows (join-anchored
    # timelines drift vs the first snapshot by the join latency — the review found the
    # old snapshot-anchored window shifted ~15 s and could miss the storm entirely on
    # a slow join). Bounds: 5 s before the first save-all to 10 s after the last (the
    # batcher drain tail).
    saves = [r["wallMs"] for r in rows_of(d, "command")
             if r.get("cmd", "").startswith("save-all")]
    if len(saves) < 8:
        print(f"[save-storm] FAIL: only {len(saves)} save-all command rows in {d} — "
              "the storm did not run as scripted")
        sys.exit(1)
    return min(saves) - 5_000, max(saves) + 10_000

def cadence(d):
    w_lo, w_hi = storm_window_ms(d)
    vals = [r["mspt_avg_window"] for r in rows_of(d, "snapshot")
            if w_lo <= r["wallMs"] <= w_hi
            and isinstance(r.get("mspt_avg_window"), (int, float))
            and r["mspt_avg_window"] >= 0]
    return statistics.mean(vals) if len(vals) >= 5 else None

def storm_cpu_seconds(d):
    # Process-CPU jiffies over the save-all-derived storm window from the attached proc
    # sampler. mspt_avg_window measures tick INTERVAL (pegged at ~50 ms below
    # saturation — measured 49.97 on both arms), so it cannot see sub-cadence work; CPU
    # is the real pairing measure and MSPT stays only as a cadence-hold (overload)
    # guard.
    rows = [json.loads(l) for l in open(d + "/cpu.jsonl", encoding="utf-8") if l.strip()]
    rows = [r for r in rows if isinstance(r.get("srv_cpu"), (int, float))]
    w_lo, w_hi = storm_window_ms(d)
    window = [r for r in rows if w_lo / 1000.0 <= r["t"] <= w_hi / 1000.0]
    if len(window) < 10:
        print(f"[save-storm] FAIL: only {len(window)} CPU samples in the storm window of {d}")
        sys.exit(1)
    return (window[-1]["srv_cpu"] - window[0]["srv_cpu"]) / 100.0

on_dir, off_dir = sys.argv[1], sys.argv[2]

# Arm validity (4-agent round R4): the pairing is meaningless unless the arms actually
# differ — the on arm must have a live store (deposits accrued) and the off arm must
# not (an inherited SOAK_LODSTORE_OVERRIDE used to silently make both arms full).
def final_deposits(d):
    snaps = rows_of(d, "snapshot")
    if not snaps:
        return None
    store = snaps[-1].get("store") or {}
    return store.get("deposits", 0)

on_dep, off_dep = final_deposits(on_dir), final_deposits(off_dir)
if not on_dep:
    print(f"[save-storm] FAIL: the on arm recorded no store deposits ({on_dep}) — "
          "lodStore was not full on the arm that must pin it")
    sys.exit(1)
if off_dep:
    print(f"[save-storm] FAIL: the off arm recorded store deposits ({off_dep}) — "
          "both arms ran with the store on; the CPU pairing measured nothing")
    sys.exit(1)
print(f"[save-storm]   arm validity: on deposits={on_dep}, off deposits={off_dep or 0}")

on_cpu, off_cpu = storm_cpu_seconds(on_dir), storm_cpu_seconds(off_dir)
delta = on_cpu - off_cpu
bound = max(1.0, 0.25 * off_cpu)
print(f"[save-storm]   storm-window process CPU: off {off_cpu:.2f} s -> on {on_cpu:.2f} s "
      f"(delta {delta:+.2f} s, bound +{bound:.2f} s)")
if delta > bound:
    print("[save-storm] FAIL: paired storm-window CPU outside noise — the save-hook "
          "deposit path is doing real per-save work beyond the queue offer")
    sys.exit(1)
on_mspt, off_mspt = cadence(on_dir), cadence(off_dir)
if on_mspt is None or off_mspt is None:
    print("[save-storm] FAIL: too few MSPT samples for the cadence-hold check")
    sys.exit(1)
print(f"[save-storm]   cadence hold: off {off_mspt:.2f} ms, on {on_mspt:.2f} ms "
      f"(both must stay <= 52 ms — interval metric, overload guard only)")
if on_mspt > 52.0 or off_mspt > 52.0:
    print("[save-storm] FAIL: an arm lost the tick cadence during the storm")
    sys.exit(1)
print("[save-storm] paired verdict: PASS (save-hook path CPU-neutral, cadence held)")
VERDICT
log "save-storm gate PASS"
