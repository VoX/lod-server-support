# Shared ownership for every soak/benchmark wrapper. Source before staging anything.
# These tools all use port 25565, so one coarse lock also serializes their scratch
# directories across worktrees. Never unlink the lock file (flock locks its inode).
HARNESS_TASK_PID=""
HARNESS_OBSERVER_PID=""
HARNESS_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

harness_port_in_use() {
    if command -v ss >/dev/null 2>&1; then
        [[ -n "$(ss -ltnH 'sport = :25565' 2>/dev/null)" ]]
    else
        [[ -n "$(awk '$4 == "0A" && $2 ~ /:63[Dd][Dd]$/' /proc/net/tcp /proc/net/tcp6 2>/dev/null)" ]]
    fi
}

harness_acquire() {
    trap 'exit 143' TERM
    trap 'exit 130' INT
    trap 'exit 129' HUP
    trap harness_cleanup EXIT
    local root="/tmp/lss-harness-$UID" lock
    mkdir -p -m 700 "$root"
    lock="$root/port-25565.lock"
    if [[ -n "${LSS_HARNESS_LOCK_FD:-}" ]]; then
        # A recursion claim must identify the actual lock inode AND own its flock.
        # Re-locking an inherited open-file description is nonblocking/reentrant.
        if [[ ! "$LSS_HARNESS_LOCK_FD" =~ ^[0-9]+$ ]] \
            || [[ ! /proc/$BASHPID/fd/$LSS_HARNESS_LOCK_FD -ef "$lock" ]] \
            || ! flock -n "$LSS_HARNESS_LOCK_FD"; then
            echo "[harness] Invalid inherited ownership descriptor" >&2
            return 1
        fi
    else
        exec {LSS_HARNESS_LOCK_FD}>"$lock"
        if ! flock -n "$LSS_HARNESS_LOCK_FD"; then
            echo "[harness] Another soak/benchmark owns the shared resources; refusing before staging" >&2
            return 1
        fi
        export LSS_HARNESS_LOCK_FD
    fi
    if harness_port_in_use; then
        echo "[harness] Port 25565 is occupied; refusing before build or staging" >&2
        return 1
    fi
}

# Every owned command is supervised and waited. The shell's EXIT trap forwards
# signals even while a build or nested wrapper is running (foreground Bash waits
# otherwise defer traps until the child exits).
harness_cleanup() {
    local pid
    for pid in "$HARNESS_TASK_PID" "$HARNESS_OBSERVER_PID"; do
        if [[ -n "$pid" ]]; then
            kill "$pid" 2>/dev/null || true
            wait "$pid" 2>/dev/null || true
        fi
    done
    HARNESS_TASK_PID=""
    HARNESS_OBSERVER_PID=""
}

harness_run() {
    local status=0 owner_pid=$BASHPID
    LSS_HARNESS_OWNER_PID="$owner_pid" python3 "$HARNESS_LIB_DIR/owned-process.py" "$@" &
    HARNESS_TASK_PID=$!
    wait "$HARNESS_TASK_PID" || status=$?
    HARNESS_TASK_PID=""
    return "$status"
}

# Only supervisors and nested harness scripts inherit ownership. Build/game
# children close the descriptor; no unrelated persistent Gradle daemon is reused.
harness_gradle() { harness_run -- ./gradlew --no-daemon "$@"; }
harness_gradle_at() {
    local directory="$1"
    shift
    harness_run --cwd "$directory" -- ./gradlew --no-daemon "$@"
}
harness_run_script() { harness_run --inherit-lock -- "$@"; }

# Observers never retain the lock, and their owner reaps them on every exit path.
harness_start_observer() {
    local owner_pid=$BASHPID
    (
        exec {LSS_HARNESS_LOCK_FD}>&-
        unset LSS_HARNESS_LOCK_FD
        export LSS_HARNESS_OWNER_PID="$owner_pid"
        exec python3 "$HARNESS_LIB_DIR/owned-process.py" -- "$@"
    ) &
    HARNESS_OBSERVER_PID=$!
}
