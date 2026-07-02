#!/usr/bin/env bash
set -euo pipefail

# LSS Benchmark Orchestrator
# Usage: ./scripts/benchmark.sh [scenario] [duration]
#   scenario: fresh | no-cache  (default: fresh)
#   duration: seconds                     (default: 60)

SCENARIO="${1:-fresh}"
DURATION="${2:-60}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_RUN_DIR="$PROJECT_ROOT/fabric/build/run/benchmark-server"
CLIENT_RUN_DIR="$PROJECT_ROOT/fabric/build/run/benchmark-client"
RESULTS_DIR="$PROJECT_ROOT/benchmark-results"
WORLDS_DIR="$PROJECT_ROOT/benchmark-worlds"
LOG_PREFIX="benchmark"

source "$PROJECT_ROOT/scripts/lib/mc-run.sh"
trap mc_cleanup EXIT

echo "========================================="
echo " LSS Benchmark: scenario=$SCENARIO, duration=${DURATION}s"
echo "========================================="

# Step 1: Build
echo "[benchmark] Building mod..."
cd "$PROJECT_ROOT"
./gradlew :fabric:build -x test -x runGameTest -x runClientGameTest --quiet

# Step 2: Prepare run directories
mkdir -p "$SERVER_RUN_DIR" "$CLIENT_RUN_DIR" "$RESULTS_DIR"

# Step 3: Prepare world based on scenario
echo "[benchmark] Preparing world for scenario: $SCENARIO"
case "$SCENARIO" in
    fresh)
        rm -rf "$SERVER_RUN_DIR/world"
        rm -rf "$SERVER_RUN_DIR/world_nether"
        rm -rf "$SERVER_RUN_DIR/world_the_end"
        ;;
    no-cache)
        if [[ ! -d "$WORLDS_DIR/base/world" ]]; then
            echo "[benchmark] ERROR: No base world found at $WORLDS_DIR/base/world"
            echo "[benchmark] Run a 'fresh' scenario first to generate a base world."
            exit 1
        fi
        rm -rf "$SERVER_RUN_DIR/world"
        cp -r "$WORLDS_DIR/base/world" "$SERVER_RUN_DIR/world"
        ;;
    *)
        echo "[benchmark] ERROR: Unknown scenario '$SCENARIO'. Use: fresh | no-cache"
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

# Step 4b: Clear stale server log from previous runs
rm -f "$SERVER_RUN_DIR/logs/latest.log"

# Step 4c: Write server.properties + eula.txt
cat > "$SERVER_RUN_DIR/server.properties" <<'PROPS'
online-mode=false
level-seed=benchmark-seed-42
spawn-protection=0
max-tick-time=-1
pause-when-empty-seconds=-1
PROPS

echo "eula=true" > "$SERVER_RUN_DIR/eula.txt"

# Step 5: Start server
mc_start_server "$RESULTS_DIR/server.log" :fabric:runBenchmarkServer -Pbenchmark.duration="$DURATION"

# Step 6: Wait for server ready
mc_wait_server_ready "$SERVER_RUN_DIR/logs/latest.log" "$RESULTS_DIR/server.log" 120

# Step 7: Start client
mc_start_client "$RESULTS_DIR/client.log" :fabric:runBenchmarkClient

# Step 8: Wait for server to exit (auto-stops after duration). Enforce the deadline: the
# benchmark server only halts on tick count (max-tick-time=-1 disables the vanilla
# watchdog), so a stalled JVM would otherwise hang this script forever.
TOTAL_TIMEOUT=$((DURATION + 120))
echo "[benchmark] Waiting up to ${TOTAL_TIMEOUT}s for benchmark to complete..."
elapsed=0
while kill -0 "$SERVER_PID" 2>/dev/null; do
    if [ "$elapsed" -ge "$TOTAL_TIMEOUT" ]; then
        echo "[benchmark] Deadline exceeded (${TOTAL_TIMEOUT}s) — killing stalled server" >&2
        kill "$SERVER_PID" 2>/dev/null || true
        break
    fi
    sleep 1
    elapsed=$((elapsed + 1))
done
if wait "$SERVER_PID" 2>/dev/null; then
    echo "[benchmark] Server exited normally"
else
    echo "[benchmark] Server exited with code $?"
fi
SERVER_PID=""

# Step 9: Wait for client to exit (auto-stops on disconnect)
mc_wait_client_exit 30

# Step 10: Collect results
echo "[benchmark] Collecting results..."
if [[ -f "$SERVER_RUN_DIR/benchmark-results/server.json" ]]; then
    cp "$SERVER_RUN_DIR/benchmark-results/server.json" "$RESULTS_DIR/server.json"
    echo "[benchmark] Server metrics: $RESULTS_DIR/server.json"
else
    echo "[benchmark] WARNING: No server metrics found"
fi

if [[ -f "$CLIENT_RUN_DIR/benchmark-results/client.json" ]]; then
    cp "$CLIENT_RUN_DIR/benchmark-results/client.json" "$RESULTS_DIR/client.json"
    echo "[benchmark] Client metrics: $RESULTS_DIR/client.json"
else
    echo "[benchmark] WARNING: No client metrics found"
fi

# Copy JFR files if present
for jfr in "$SERVER_RUN_DIR/server-benchmark.jfr" "$CLIENT_RUN_DIR/client-benchmark.jfr"; do
    if [[ -f "$jfr" ]]; then
        cp "$jfr" "$RESULTS_DIR/"
        echo "[benchmark] JFR: $RESULTS_DIR/$(basename "$jfr")"
    fi
done

# Step 11: Save world for reuse (fresh scenario only)
if [[ "$SCENARIO" == "fresh" && -d "$SERVER_RUN_DIR/world" ]]; then
    echo "[benchmark] Saving world to $WORLDS_DIR/base/ for reuse"
    mkdir -p "$WORLDS_DIR/base"
    rm -rf "$WORLDS_DIR/base/world"
    cp -r "$SERVER_RUN_DIR/world" "$WORLDS_DIR/base/world"
fi

# Step 12: Print server results
echo ""
echo "========================================="
echo " Benchmark Complete"
echo "========================================="
if [[ -f "$RESULTS_DIR/server.json" ]]; then
    cat "$RESULTS_DIR/server.json"
fi
