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
SERVER_PID=""
CLIENT_PID=""

cleanup() {
    echo "[benchmark] Cleaning up..."
    if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "[benchmark] Killing server (PID $SERVER_PID)"
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
    if [[ -n "$CLIENT_PID" ]] && kill -0 "$CLIENT_PID" 2>/dev/null; then
        echo "[benchmark] Killing client (PID $CLIENT_PID)"
        kill "$CLIENT_PID" 2>/dev/null || true
        wait "$CLIENT_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

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

# Step 4a: Write client options.txt to bypass first-launch screens
cat > "$CLIENT_RUN_DIR/options.txt" <<'OPTS'
onboardAccessibility:false
skipMultiplayerWarning:true
joinedFirstServer:true
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
echo "[benchmark] Starting server..."
cd "$PROJECT_ROOT"
./gradlew :fabric:runBenchmarkServer \
    -Pbenchmark.duration="$DURATION" \
    > "$RESULTS_DIR/server.log" 2>&1 &
SERVER_PID=$!
echo "[benchmark] Server PID: $SERVER_PID"

# Step 6: Wait for server ready
echo "[benchmark] Waiting for server to be ready..."
SERVER_LOG="$SERVER_RUN_DIR/logs/latest.log"
TIMEOUT=120
ELAPSED=0
while [[ $ELAPSED -lt $TIMEOUT ]]; do
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "[benchmark] ERROR: Server process exited before becoming ready"
        echo "[benchmark] Last 20 lines of server log:"
        tail -20 "$RESULTS_DIR/server.log" 2>/dev/null || true
        exit 1
    fi
    if [[ -f "$SERVER_LOG" ]] && grep -q "Done" "$SERVER_LOG" 2>/dev/null; then
        echo "[benchmark] Server ready after ${ELAPSED}s"
        break
    fi
    sleep 1
    ELAPSED=$((ELAPSED + 1))
done

if [[ $ELAPSED -ge $TIMEOUT ]]; then
    echo "[benchmark] ERROR: Server did not start within ${TIMEOUT}s"
    exit 1
fi

# Step 7: Start client
echo "[benchmark] Starting client..."
cd "$PROJECT_ROOT"
./gradlew :fabric:runBenchmarkClient \
    > "$RESULTS_DIR/client.log" 2>&1 &
CLIENT_PID=$!
echo "[benchmark] Client PID: $CLIENT_PID"

# Step 8: Wait for server to exit (auto-stops after duration)
TOTAL_TIMEOUT=$((DURATION + 120))
echo "[benchmark] Waiting up to ${TOTAL_TIMEOUT}s for benchmark to complete..."
if wait "$SERVER_PID" 2>/dev/null; then
    echo "[benchmark] Server exited normally"
else
    echo "[benchmark] Server exited with code $?"
fi
SERVER_PID=""

# Step 9: Wait for client to exit (auto-stops on disconnect)
CLIENT_TIMEOUT=30
ELAPSED=0
while [[ $ELAPSED -lt $CLIENT_TIMEOUT ]]; do
    if ! kill -0 "$CLIENT_PID" 2>/dev/null; then
        echo "[benchmark] Client exited"
        break
    fi
    sleep 1
    ELAPSED=$((ELAPSED + 1))
done
if kill -0 "$CLIENT_PID" 2>/dev/null; then
    echo "[benchmark] Client did not exit within ${CLIENT_TIMEOUT}s, killing"
    kill "$CLIENT_PID" 2>/dev/null || true
    wait "$CLIENT_PID" 2>/dev/null || true
fi
CLIENT_PID=""

# Step 10: Collect results
echo "[benchmark] Collecting results..."
if [[ -f "$SERVER_RUN_DIR/benchmark-results.json" ]]; then
    cp "$SERVER_RUN_DIR/benchmark-results.json" "$RESULTS_DIR/server.json"
    echo "[benchmark] Server metrics: $RESULTS_DIR/server.json"
else
    echo "[benchmark] WARNING: No server metrics found"
fi

if [[ -f "$CLIENT_RUN_DIR/benchmark-results.json" ]]; then
    cp "$CLIENT_RUN_DIR/benchmark-results.json" "$RESULTS_DIR/client.json"
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
