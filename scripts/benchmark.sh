#!/usr/bin/env bash
set -euo pipefail

# LSS Benchmark Orchestrator
# Usage: ./scripts/benchmark.sh [scenario] [duration]
#   scenario: fresh | no-cache | warm-join   (default: fresh)
#   duration: seconds per cycle             (default: 60)
#
# Scenarios:
#   fresh      Empty world, chunks generated on demand (generation + serialization).
#              Saves the resulting world to benchmark-worlds/base for reuse.
#   no-cache   Pre-built base world, cold client cache (disk-read + serialization).
#   warm-join  LOD-store cross-restart warm join (lod-store-implementation-plan.md §5):
#              cycle A ("populate") serves the base world once — with lodStore on, the
#              delivery-path deposits fill the store; cycle B ("measure") RESTARTS the
#              server on the SAME world (the store DB lives in the world folder) and a
#              second client with a cleared cache joins — the measured arm. Results:
#              server.json/client.json = cycle B; *-populate.json = cycle A.
#              The kill-switch A/B arm is the same scenario with lodStore=off staged in
#              the server config (the caller stages config; this script is config-agnostic).
#
# Env knobs:
#   BENCHMARK_SERVER_GRADLE_ARGS  extra gradle args for the SERVER invocation only
#                                 (e.g. -Pbenchmark.c2me=true)
#   BENCHMARK_DROP_CACHES=1       drop the OS page cache before the MEASURE cycle
#                                 (warm-join cycle B / the single cycle otherwise).
#                                 Needs passwordless sudo; warns and continues without
#                                 it otherwise. The store's worst case is ~800 random
#                                 point reads into a multi-GB file on a cold cache —
#                                 every warm number rode a fully-cached world.

SCENARIO="${1:-fresh}"
DURATION="${2:-60}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$PROJECT_ROOT/scripts/lib/harness-lock.sh"
harness_acquire
SERVER_RUN_DIR="$PROJECT_ROOT/fabric/build/run/benchmark-server"
CLIENT_RUN_DIR="$PROJECT_ROOT/fabric/build/run/benchmark-client"
RESULTS_DIR="$PROJECT_ROOT/benchmark-results"
WORLDS_DIR="$PROJECT_ROOT/benchmark-worlds"
LOG_PREFIX="benchmark"

source "$PROJECT_ROOT/scripts/lib/mc-run.sh"
RUN_RESULTS_DIR=""
benchmark_cleanup() {
    local status=$?
    mc_cleanup
    if [[ $status -ne 0 && -n "$RUN_RESULTS_DIR" ]]; then
        python3 "$HARNESS_LIB_DIR/benchmark-results.py" fail "$RUN_RESULTS_DIR" "$status" || true
    fi
}
trap benchmark_cleanup EXIT
case "$SCENARIO" in fresh|no-cache|warm-join) ;; *) echo "[benchmark] Unknown scenario: $SCENARIO" >&2; exit 1 ;; esac
[[ "$DURATION" =~ ^[1-9][0-9]*$ ]] || { echo "[benchmark] Duration must be positive integer seconds" >&2; exit 1; }
RUN_RESULTS_DIR="$(python3 "$HARNESS_LIB_DIR/benchmark-results.py" begin "$RESULTS_DIR" "$SCENARIO" "$DURATION")"
echo "[benchmark] Run evidence: $RUN_RESULTS_DIR"

echo "========================================="
echo " LSS Benchmark: scenario=$SCENARIO, duration=${DURATION}s"
echo "========================================="

# Step 1: Build
echo "[benchmark] Building mod..."
cd "$PROJECT_ROOT"
harness_gradle :fabric:build -x test -x runGameTest -x runClientGameTest --quiet

# Step 2: Prepare run directories
mkdir -p "$SERVER_RUN_DIR" "$CLIENT_RUN_DIR" "$RESULTS_DIR"

# This script is config-agnostic BY CONTRACT — benchmark_compare.sh and store_gate.sh stage
# their own arm configs into $SERVER_RUN_DIR/config and then call us, so we must never
# clobber theirs. They announce that by exporting BENCHMARK_CONFIG_STAGED=1.
#
# Standalone, though, "no config" no longer means "neutral": since 2026-08-02 the shipped
# defaults are lodStore=full + lodStoreBackfill=true, so a bare `benchmark.sh no-cache`
# would run a 500 col/s background region walk over the pre-built world DURING the measured
# window — silently voiding the scenario's own "disk-read + serialization pipeline" premise
# and making results incomparable with every earlier baseline. Worse, a config left behind
# by a previous store_gate.sh run would apply to an unrelated later run. So when no caller
# claims the config, stage the neutral one the scenarios were defined against.
if [[ -z "${BENCHMARK_CONFIG_STAGED:-}" ]]; then
    mkdir -p "$SERVER_RUN_DIR/config"
    # lodDistanceChunks is PINNED (v0.11.0 stage A): the neutral config used to ride the
    # shipped default, so every default retune silently re-sized the benchmark workload
    # and broke cross-era comparability. 512 is the value the v0.10.0-era baselines ran
    # at (the 2026-08-08 rework default) — keep it pinned even as the shipped default
    # moves (300 since v0.11.0).
    cat > "$SERVER_RUN_DIR/config/lss-server-config.json" <<'EOF'
{
  "enabled": true,
  "enableChunkGeneration": true,
  "lodDistanceChunks": 512,
  "lodStore": "off",
  "lodStoreBackfill": false,
  "maxConcurrentDiskReads": 64
}
EOF
    echo "[benchmark] Staged neutral config (lodStore=off, lodDistance=512 pinned) — export BENCHMARK_CONFIG_STAGED=1 to keep your own"
fi

require_base_world() {
    if [[ ! -d "$WORLDS_DIR/base/world" ]]; then
        echo "[benchmark] ERROR: No base world found at $WORLDS_DIR/base/world"
        echo "[benchmark] Run a 'fresh' scenario first to generate a base world."
        exit 1
    fi
}

reset_world_from_base() {
    rm -rf "$SERVER_RUN_DIR/world"
    cp -r "$WORLDS_DIR/base/world" "$SERVER_RUN_DIR/world"
}

clear_client_lss_cache() {
    # Both cache roots (stage D relocation: legacy config/lss/cache is adopted when it
    # exists, fresh run dirs write the game-root .lss/cache). The old config/vss/cache
    # entry was a dead path (no in-repo VSS build ever used it) — dropped.
    rm -rf "$CLIENT_RUN_DIR/config/lss/cache" "$CLIENT_RUN_DIR/.lss/cache" 2>/dev/null || true
}

# Drop the OS page cache (measure-cycle cold-read variant). Best-effort: passwordless
# sudo only — an interactive prompt would hang an unattended run.
drop_page_caches() {
    sync
    if sudo -n sh -c 'echo 3 > /proc/sys/vm/drop_caches' 2>/dev/null; then
        echo "[benchmark] Dropped OS page caches (cold-cache measure cycle)"
        DROPPED_CACHES=true
    else
        echo "[benchmark] WARNING: BENCHMARK_DROP_CACHES=1 but passwordless sudo is"
        echo "[benchmark]          unavailable — measure cycle runs WARM. Grant NOPASSWD"
        echo "[benchmark]          for 'sh -c echo 3 > /proc/sys/vm/drop_caches' or run once with sudo -v."
        DROPPED_CACHES=false
    fi
}
DROPPED_CACHES=n/a

# Step 3: Prepare world based on scenario
echo "[benchmark] Preparing world for scenario: $SCENARIO"
case "$SCENARIO" in
    fresh)
        rm -rf "$SERVER_RUN_DIR/world"
        rm -rf "$SERVER_RUN_DIR/world_nether"
        rm -rf "$SERVER_RUN_DIR/world_the_end"
        ;;
    no-cache|warm-join)
        require_base_world
        reset_world_from_base
        ;;
    *)
        echo "[benchmark] ERROR: Unknown scenario '$SCENARIO'. Use: fresh | no-cache | warm-join"
        exit 1
        ;;
esac

# Step 4a: Write client options.txt to bypass first-launch screens (muted — these
# clients run on a dev machine)
cat > "$CLIENT_RUN_DIR/options.txt" <<'OPTS'
onboardAccessibility:false
skipMultiplayerWarning:true
joinedFirstServer:true
soundCategory_master:0.0
OPTS

# Step 4b: Write server.properties + eula.txt
# difficulty=peaceful: the base world's saved time-of-day is whatever the build run ended
# at (a 900 s build saves NIGHT), and a hostile mob killing the idle benchmark player
# freezes the client's want-set declarations mid-run (tick()'s isDeadOrDying guard) —
# diagnosed 2026-07-29 as the "client early-stop" that truncated 2 of 4 profile runs.
cat > "$SERVER_RUN_DIR/server.properties" <<'PROPS'
online-mode=false
level-seed=benchmark-seed-42
spawn-protection=0
max-tick-time=-1
pause-when-empty-seconds=-1
difficulty=peaceful
PROPS
# BENCHMARK_EXTRA_SERVER_PROPS: extra server.properties lines (e.g. a level-type override
# for the store's bytes/col distribution arms — amplified is the §4 upside case).
if [[ -n "${BENCHMARK_EXTRA_SERVER_PROPS:-}" ]]; then
    printf '%s\n' "$BENCHMARK_EXTRA_SERVER_PROPS" >> "$SERVER_RUN_DIR/server.properties"
fi

echo "eula=true" > "$SERVER_RUN_DIR/eula.txt"

# run_cycle <result-suffix>: one full server+client cycle. Collects
# server/client JSON and optional JFR into this run's separate cycle directory.
run_cycle() {
    local suffix="$1" cycle=measure server_status=0 client_status=0
    [[ -z "$suffix" ]] || cycle=populate
    local cycle_dir="$RUN_RESULTS_DIR/$cycle"
    python3 "$HARNESS_LIB_DIR/benchmark-results.py" cycle-start "$RUN_RESULTS_DIR" "$cycle"

    rm -f "$SERVER_RUN_DIR/logs/latest.log"
    rm -f "$SERVER_RUN_DIR/benchmark-results/server.json" \
          "$CLIENT_RUN_DIR/benchmark-results/client.json" \
          "$SERVER_RUN_DIR/server-benchmark.jfr" "$CLIENT_RUN_DIR/client-benchmark.jfr"

    # Start server
    # BENCHMARK_SERVER_GRADLE_ARGS: optional extra gradle args for the SERVER invocation only
    # (e.g. -Pbenchmark.c2me=true for the C2ME A/B arm — the client stays unmodified).
    mc_start_server "$cycle_dir/server.log" :fabric:runBenchmarkServer -Pbenchmark.duration="$DURATION" ${BENCHMARK_SERVER_GRADLE_ARGS:-}
    mc_wait_server_ready "$SERVER_RUN_DIR/logs/latest.log" "$cycle_dir/server.log" 120

    # 1 Hz CPU/RSS/wire sampler — cpu.jsonl is what analyze_profile_jfr.py derives the
    # active window from, and the idle-corrected CPU input to the store's §0 metric-2 gate.
    harness_start_observer "$PROJECT_ROOT/scripts/lib/proc_sampler.sh" "$cycle_dir/cpu.jsonl" $((DURATION + 300))
    local sampler_pid=$HARNESS_OBSERVER_PID

    # BENCHMARK_CLIENT_GRADLE_ARGS: extra -P args for the client task (C6: the
    # legacy-dialect arm passes -Psoak.dialect=19 so the benchmark client emulates a
    # protocol-19 install and the server pays its egress translation per column).
    # shellcheck disable=SC2086
    mc_start_client "$cycle_dir/client.log" :fabric:runBenchmarkClient ${BENCHMARK_CLIENT_GRADLE_ARGS:-}

    # Wait for server to exit (auto-stops after duration). Enforce the deadline: the
    # benchmark server only halts on tick count (max-tick-time=-1 disables the vanilla
    # watchdog), so a stalled JVM would otherwise hang this script forever.
    local total_timeout=$((DURATION + 120))
    echo "[benchmark] Waiting up to ${total_timeout}s for cycle to complete..."
    local elapsed=0
    while kill -0 "$SERVER_PID" 2>/dev/null; do
        if [ "$elapsed" -ge "$total_timeout" ]; then
            echo "[benchmark] Deadline exceeded (${total_timeout}s) — killing stalled server" >&2
            server_status=124
            kill "$SERVER_PID" 2>/dev/null || true
            break
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    local waited=0
    wait "$SERVER_PID" 2>/dev/null || waited=$?
    [[ $server_status -ne 0 ]] || server_status=$waited
    SERVER_PID=""

    mc_wait_client_exit 30 || client_status=$?
    kill "$sampler_pid" 2>/dev/null || true
    wait "$sampler_pid" 2>/dev/null || true
    HARNESS_OBSERVER_PID=""
    python3 "$HARNESS_LIB_DIR/benchmark-results.py" collect "$RUN_RESULTS_DIR" "$cycle" \
        "$SERVER_RUN_DIR" "$CLIENT_RUN_DIR" "$server_status" "$client_status"

}

# Step 5: Run the scenario's cycle(s)
if [[ "$SCENARIO" == "warm-join" ]]; then
    clear_client_lss_cache
    echo "[benchmark] warm-join cycle A (populate)..."
    run_cycle "-populate"
    # Cycle B: SAME world (keeps the store DB the populate cycle deposited), fresh
    # client cache, fresh server session — the cross-restart warm join.
    clear_client_lss_cache
    if [[ "${BENCHMARK_DROP_CACHES:-0}" == "1" ]]; then
        drop_page_caches
    fi
    echo "[benchmark] warm-join cycle B (measure)..."
    run_cycle ""
    cat > "$RUN_RESULTS_DIR/warm-join-meta.json" <<EOF
{"scenario":"warm-join","duration_s":$DURATION,"dropped_caches":"$DROPPED_CACHES","finished":"$(date -Is)"}
EOF
else
    if [[ "${BENCHMARK_DROP_CACHES:-0}" == "1" ]]; then
        drop_page_caches
    fi
    run_cycle ""
fi

# Step 6: Save world for reuse (fresh scenario only).
# BENCHMARK_NO_BASE_SAVE=1 skips it — one-off distribution arms (amplified etc.) must
# never clobber the standard base world every other scenario builds on.
if [[ "${BENCHMARK_NO_BASE_SAVE:-0}" != "1" && "$SCENARIO" == "fresh" && -d "$SERVER_RUN_DIR/world" ]]; then
    echo "[benchmark] Saving world to $WORLDS_DIR/base/ for reuse"
    mkdir -p "$WORLDS_DIR/base"
    rm -rf "$WORLDS_DIR/base/world"
    cp -r "$SERVER_RUN_DIR/world" "$WORLDS_DIR/base/world"
fi

# Step 7: Publish aliases only after all required cycles validated.
python3 "$HARNESS_LIB_DIR/benchmark-results.py" finish "$RUN_RESULTS_DIR"

# Print only this validated run, never an old compatibility alias.
echo ""
echo "========================================="
echo " Benchmark Complete"
echo "========================================="
python3 "$HARNESS_LIB_DIR/benchmark-results.py" current "$RUN_RESULTS_DIR"
