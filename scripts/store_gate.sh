#!/usr/bin/env bash
set -euo pipefail

# LOD-store §0 gate runner (docs/planning/lod-store-implementation-plan.md §0/§5):
# interleaved kill-switch A/B arms of the warm-join benchmark scenario, one rep = one
# off-arm + one on-arm back-to-back (the plan's same-session A/B discipline — never
# compare against numbers from another day/box-state).
#
# Usage:
#   store_gate.sh warm <reps> <duration> [lodStore-on-mode]   # warm-join A/B (§0 gate)
#   store_gate.sh cold <reps> <duration> [lodStore-on-mode]   # no-cache deposits A/B
#                                                             # (the ≤10% cold-path gate)
#   lodStore-on-mode defaults to (and since 2026-08-02 may only be) "full" — the
#   "memory" mode Phase 1 used is retired, and it now normalizes to OFF, which would
#   silently turn the on-arm into a second off-arm and read as "the store is free".
#
# Results: store-gate-results/<stamp>/<arm>-rep<N>/… then store_gate_check.py runs the
# §0 math (work-elimination, band CPU/col, non-regression) over the whole stamp dir.
#
# Env: BENCHMARK_DROP_CACHES=1 propagates to benchmark.sh (cold-page-cache variant).

MODE="${1:?warm|cold}"
REPS="${2:?reps}"
DURATION="${3:?duration-seconds}"
ON_MODE="${4:-full}"
if [ "$ON_MODE" != "full" ]; then
    echo "store_gate.sh: lodStore-on-mode must be 'full' (got '$ON_MODE') — 'memory' was" >&2
    echo "  retired 2026-08-02 and normalizes to off, which would make both arms off-arms." >&2
    exit 2
fi
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$PROJECT_ROOT/scripts/lib/harness-lock.sh"
harness_acquire
OUT_ROOT="${OUT_ROOT:-$PROJECT_ROOT/store-gate-results}"
STAMP="${RUN_STAMP:-$(date +%Y%m%d-%H%M%S)}"
SRV_CFG_DIR="$PROJECT_ROOT/fabric/build/run/benchmark-server/config"
RESULTS="$PROJECT_ROOT/benchmark-results"

case "$MODE" in
    warm) SCENARIO="warm-join" ;;
    cold) SCENARIO="no-cache" ;;
    *) echo "[store-gate] ERROR: mode must be warm|cold" >&2; exit 1 ;;
esac

log() { echo "[store-gate] $*"; }

stage_config() { # <lodStore-value>
    mkdir -p "$SRV_CFG_DIR"
    # warm mode MUST use a disc that CONVERGES within one cycle (~480 col/s measured):
    # cycle B re-declares closest-first, so any frontier beyond cycle A's deposited
    # coverage reads as store misses and the gate measures frontier progression, not
    # warm serving (diagnosed live 2026-07-31: 256-distance 60 s cycles red-ded metric 1
    # at 0.87-0.95 with the ratio CLIMBING per rep as the page cache warmed cycle A).
    # The disc must ALSO be large enough to dilute the constant spawn-annulus artifact:
    # columns disk-served before vanilla finishes loading them get re-saved at cycle-A
    # shutdown and conservatively swept (deterministic ~240-500 rows depending on how
    # fast cycle A wins that race) — at distance 64 that constant was 2.2-3% of the disc
    # and grazed the 2% disk.submitted ceiling; at 96 (~37k cols, still inside the ~45k
    # base world) it is ~1%. Pass duration >= 120 for warm so cycle A converges.
    # cold mode keeps the huge disc: sustained-throughput measurement wants no idle tail.
    local distance=256
    [[ "$MODE" == "warm" ]] && distance=96
    cat > "$SRV_CFG_DIR/lss-server-config.json" <<EOF
{
  "enabled": true,
  "lodDistanceChunks": $distance,
  "diskReaderThreads": 5,
  "maxConcurrentDiskReads": 5,
  "enableChunkGeneration": false,
  "missMemoTtlSeconds": 30,
  "useBackgroundReadPriority": true,
  "useNbtTranscode": true,
  "lodStore": "$1",
  "lodStoreBackfill": false
}
EOF
}

collect() { # <run-out-dir>
    local out="$1"
    mkdir -p "$out"
    local f
    for f in server.json client.json server-populate.json client-populate.json \
             cpu.jsonl cpu-populate.jsonl server-benchmark.jfr \
             server-benchmark-populate.jfr warm-join-meta.json server.log; do
        [[ -f "$RESULTS/$f" ]] && cp "$RESULTS/$f" "$out/"
    done
}

run_arm() { # <arm-label> <lodStore-value> <rep>
    local arm="$1" value="$2" rep="$3"
    local out="$OUT_ROOT/$STAMP/${arm}-rep${rep}"
    python3 "$HARNESS_LIB_DIR/benchmark-results.py" prepare "$out"
    log "=== $SCENARIO $arm rep$rep (lodStore=$value, ${DURATION}s) ==="
    stage_config "$value"
    # Stale-artifact guard: a crashed run must yield MISSING files, not the last run's.
    rm -f "$RESULTS/current.json" "$RESULTS"/server*.json "$RESULTS"/client*.json "$RESULTS"/cpu*.jsonl \
          "$RESULTS"/*.jfr "$RESULTS"/warm-join-meta.json
    local rc=0
    harness_run_script env BENCHMARK_CONFIG_STAGED=1 "$PROJECT_ROOT/scripts/benchmark.sh" "$SCENARIO" "$DURATION" \
        > "$OUT_ROOT/$STAMP/${arm}-rep${rep}.orchestrator.log" 2>&1 || rc=$?
    collect "$out"
    if [[ $rc -eq 0 ]]; then
        python3 "$HARNESS_LIB_DIR/benchmark-results.py" record "$PROJECT_ROOT" "$out" || rc=$?
    fi
    cat > "$out/meta.json" <<EOF
{"mode":"$MODE","arm":"$arm","lodStore":"$value","rep":$rep,"duration_s":$DURATION,
 "ref":"$(git -C "$PROJECT_ROOT" rev-parse --short HEAD)","rc":$rc,"finished":"$(date -Is)"}
EOF
    if [[ $rc -ne 0 ]]; then
        log "arm $arm rep$rep FAILED (rc=$rc)"
        return 1
    fi
}

mkdir -p "$OUT_ROOT/$STAMP"
if ss -ltnH 'sport = :25565' 2>/dev/null | grep -q .; then
    echo "[store-gate] port 25565 in use — refusing to start (soak/benchmark conflict guard)" >&2
    exit 1
fi

for rep in $(seq 1 "$REPS"); do
    run_arm off off "$rep"
    sleep 10
    run_arm on "$ON_MODE" "$rep"
    sleep 10
done

log "Runs complete: $OUT_ROOT/$STAMP — computing gates"
python3 "$PROJECT_ROOT/scripts/store_gate_check.py" "$MODE" "$OUT_ROOT/$STAMP"
