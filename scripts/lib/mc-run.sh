# Shared Minecraft server/client lifecycle helpers for benchmark.sh and soak.sh.
# Source this file — do not execute it.
#
# Callers must source harness-lock.sh and acquire ownership before using these
# helpers. They must also set before sourcing:
#   LOG_PREFIX    - log tag for messages, e.g. "benchmark" or "soak"
#   PROJECT_ROOT  - repo root (gradle invocations run from here)
#
# These helpers own the globals SERVER_PID and CLIENT_PID (initialized empty
# here so the cleanup trap is always safe to install):
#   trap mc_cleanup EXIT

SERVER_PID=""
CLIENT_PID=""

# Kill any still-running server/client processes.
mc_cleanup() {
    harness_cleanup
    echo "[$LOG_PREFIX] Cleaning up..."
    if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "[$LOG_PREFIX] Killing server (PID $SERVER_PID)"
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
    if [[ -n "$CLIENT_PID" ]] && kill -0 "$CLIENT_PID" 2>/dev/null; then
        echo "[$LOG_PREFIX] Killing client (PID $CLIENT_PID)"
        kill "$CLIENT_PID" 2>/dev/null || true
        wait "$CLIENT_PID" 2>/dev/null || true
    fi
}

# mc_start_server <gradle-log> <gradle-task> [gradle-args...]
# Launches an owned server supervisor in the background; sets SERVER_PID.
mc_start_server() {
    local gradle_log="$1" owner_pid=$BASHPID
    shift
    echo "[$LOG_PREFIX] Starting server..."
    cd "$PROJECT_ROOT"
    LSS_HARNESS_OWNER_PID="$owner_pid" python3 "$HARNESS_LIB_DIR/owned-process.py" -- ./gradlew --no-daemon "$@" > "$gradle_log" 2>&1 &
    SERVER_PID=$!
    echo "[$LOG_PREFIX] Server PID: $SERVER_PID"
}

# mc_start_client <gradle-log> <gradle-task> [gradle-args...]
# Launches an owned client supervisor in the background; sets CLIENT_PID.
mc_start_client() {
    local gradle_log="$1" owner_pid=$BASHPID
    shift
    echo "[$LOG_PREFIX] Starting client..."
    cd "$PROJECT_ROOT"
    LSS_HARNESS_OWNER_PID="$owner_pid" python3 "$HARNESS_LIB_DIR/owned-process.py" -- ./gradlew --no-daemon "$@" > "$gradle_log" 2>&1 &
    CLIENT_PID=$!
    echo "[$LOG_PREFIX] Client PID: $CLIENT_PID"
}

# mc_wait_server_ready <server-latest-log> <gradle-log> <timeout-seconds>
# Polls for "Done" in the Minecraft server log. Returns 1 (after printing an
# error and the gradle log tail) if the server process dies first, or if the
# timeout elapses.
mc_wait_server_ready() {
    local server_log="$1"
    local gradle_log="$2"
    local timeout="$3"
    local elapsed=0
    echo "[$LOG_PREFIX] Waiting for server to be ready..."
    while [[ $elapsed -lt $timeout ]]; do
        if ! kill -0 "$SERVER_PID" 2>/dev/null; then
            echo "[$LOG_PREFIX] ERROR: Server process exited before becoming ready"
            echo "[$LOG_PREFIX] Last 20 lines of server log:"
            tail -20 "$gradle_log" 2>/dev/null || true
            return 1
        fi
        if [[ -f "$server_log" ]] && grep -q "Done" "$server_log" 2>/dev/null; then
            echo "[$LOG_PREFIX] Server ready after ${elapsed}s"
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    echo "[$LOG_PREFIX] ERROR: Server did not start within ${timeout}s"
    return 1
}

# Return the actual client exit status, or 124 when its deadline expires.
mc_wait_client_exit() {
    local timeout="$1" elapsed=0 status=0
    while kill -0 "$CLIENT_PID" 2>/dev/null && [[ $elapsed -lt $timeout ]]; do
        sleep 1
        elapsed=$((elapsed + 1))
    done
    if kill -0 "$CLIENT_PID" 2>/dev/null; then
        echo "[$LOG_PREFIX] Client did not exit within ${timeout}s, killing"
        kill "$CLIENT_PID" 2>/dev/null || true
        status=124
    fi
    local waited=0
    wait "$CLIENT_PID" 2>/dev/null || waited=$?
    [[ $status -ne 0 ]] || status=$waited
    CLIENT_PID=""
    return "$status"
}
