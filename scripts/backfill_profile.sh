#!/usr/bin/env bash
set -euo pipefail

# Store-backfill profile harness (PERF Phase 0 item 3 — the committed form of the round's
# ad-hoc F3 runner). SERVER-ONLY: no client joins; the measured work is the background
# region walk (StoreBackfill) depositing the pre-built base world into a FRESH store.
#
# Each rep: reset world from benchmark-worlds/base (deleting any store DB so the walk has
# regions to process), stage lodStore=full + backfill config, run :fabric:runBenchmarkServer
# for <duration> (the wired server JFR + the external 1 Hz sampler record it), then parse
# the walk's start/terminal log lines into meta.json — walk seconds + deposit counts are
# what defines the Phase 2 gate's deposits/s. There is no committed export of walk timing
# anywhere else: server.json's store block is a cumulative end-of-run dump.
#
# Arms (matching profile_disk_read.sh — the phase gates say "ref-vs-ref … + backfill arm",
# which means the backfill A/B must interleave, not run as sequential blocks):
#   backfill        this tree (default when PROFILE_BASE_REF is unset)
#   base | change   ref-vs-ref behind PROFILE_BASE_REF: `change` runs THIS tree, `base` a
#                   detached worktree at the ref (worktree + prebuild + rsync'd world).
#                   The matrix interleaves base/change ABBA across reps.
#
# An INCOMPLETE walk invalidates the arm (deposits/s undefined) — raise the duration.
# PROFILE_ALLOW_PARTIAL_WALK=1 keeps such a run pool-able if a partial profile is ever
# deliberately wanted (compare_profile.py still refuses an arm with 0 completed seconds).
#
# Analyze the walk window (no client -> no wire slope for the default window detection):
#   analyze_profile_jfr.py run <run-dir> --window walk     # reads this meta.json
#
# Usage:
#   backfill_profile.sh run <arm> <rep> [duration]   # one rep (duration default 300 s)
#   backfill_profile.sh matrix <reps> [duration]     # ABBA when PROFILE_BASE_REF is set
#   backfill_profile.sh setup                        # ref mode: worktree + prebuild
#
# Knobs:
#   PROFILE_BACKFILL_CPS   lodStoreBackfillColumnsPerSecond (default 1000 = clamp max, the
#                          F3 setting — the walk should be tooling-bound, not pace-bound)
#   PROFILE_NBT_TRANSCODE  useNbtTranscode (default true)
#   PROFILE_SELECTIVE_PARSE useSelectiveNbtParse (default true — the Phase 4 arm knob)
#   PROFILE_LOD_R          lodDistanceChunks (default 256; irrelevant to the walk itself)
#   PROFILE_BASE_REF/_WT   ref-vs-ref base ref + worktree path (see profile_disk_read.sh)
#   OUT_ROOT, RUN_STAMP    results root / stamp dir (default profile-results/<stamp>)
#
# Results: profile-results/<stamp>/<arm>-rep<N>/{server.json,server-benchmark.jfr,
#          cpu.jsonl,orchestrator.log,server.log,meta.json}

MAIN_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$MAIN_ROOT/scripts/lib/harness-lock.sh"
harness_acquire
OUT_ROOT="${OUT_ROOT:-$MAIN_ROOT/profile-results}"
PROJECT_ROOT="$MAIN_ROOT"   # for mc-run.sh (re-pointed per arm in cmd_run)
LOG_PREFIX="backfill-profile"

BASE_REF="${PROFILE_BASE_REF:-}"
BASE_WT="${PROFILE_BASE_WT:-$(dirname "$MAIN_ROOT")/lss-profile-base}"

source "$MAIN_ROOT/scripts/lib/mc-run.sh"
trap mc_cleanup EXIT

log() { echo "[backfill-profile] $*"; }
die() { echo "[backfill-profile] ERROR: $*" >&2; exit 1; }

stage_server_config() { # <path>
    cat > "$1" <<EOF
{
  "enabled": true,
  "lodDistanceChunks": ${PROFILE_LOD_R:-256},
  "diskReaderThreads": 5,
  "maxConcurrentDiskReads": 5,
  "enableChunkGeneration": false,
  "missMemoTtlSeconds": 30,
  "useBackgroundReadPriority": true,
  "useNbtTranscode": ${PROFILE_NBT_TRANSCODE:-true},
  "useSelectiveNbtParse": ${PROFILE_SELECTIVE_PARSE:-true},
  "lodStore": "full",
  "lodStoreBackfill": true,
  "lodStoreBackfillColumnsPerSecond": ${PROFILE_BACKFILL_CPS:-1000}
}
EOF
}

root_for_arm() {
    case "$1" in
        base) echo "$BASE_WT" ;;
        *)    echo "$MAIN_ROOT" ;;
    esac
}

prebuild() { # <root>
    log "Prebuilding $1 ..."
    harness_gradle_at "$1" :fabric:build -x test -x runGameTest -x runClientGameTest --quiet
}

ensure_base_worktree() {
    [[ -n "$BASE_REF" ]] || die "arm base/change needs PROFILE_BASE_REF=<git-ref>"
    local want
    want="$(git -C "$MAIN_ROOT" rev-parse "${BASE_REF}^{commit}")" \
        || die "PROFILE_BASE_REF '$BASE_REF' does not resolve"
    if [[ ! -d "$BASE_WT" ]]; then
        log "Creating worktree $BASE_WT @ $BASE_REF"
        git -C "$MAIN_ROOT" worktree add --detach "$BASE_WT" "$BASE_REF"
    elif [[ "$(git -C "$BASE_WT" rev-parse HEAD)" != "$want" ]]; then
        log "Repointing worktree $BASE_WT -> $BASE_REF"
        git -C "$BASE_WT" checkout --detach "$want"
    fi
}

cmd_setup() {
    ensure_base_worktree
    prebuild "$MAIN_ROOT"
    prebuild "$BASE_WT"
    log "Setup complete: base=$BASE_WT@$BASE_REF change=$MAIN_ROOT (working tree)"
}

# "[HH:MM:SS] ..." log-line prefix -> time-of-day seconds (matches the analyzer's
# JFR startTime domain, so meta's walk window feeds --window walk directly).
tod_of_line() { # <line> -> seconds or ""
    sed -n 's/^\[\([0-9][0-9]\):\([0-9][0-9]\):\([0-9][0-9]\)\].*/\1 \2 \3/p' <<< "$1" \
        | awk '{print $1*3600 + $2*60 + $3}'
}

cmd_run() {
    local arm="${1:?arm (backfill | base | change)}" rep="${2:?rep}" duration="${3:-300}"
    local root="$MAIN_ROOT"
    case "$arm" in
        backfill) ;;
        base|change)
            [[ -n "$BASE_REF" ]] || die "arm '$arm' needs PROFILE_BASE_REF (ref-vs-ref mode)"
            root="$(root_for_arm "$arm")"
            [[ "$arm" == "change" || -d "$root" ]] || die "worktree $root missing — run setup first"
            ;;
        *) die "unknown arm '$arm' (want backfill | base | change)" ;;
    esac
    local server_run_dir="$root/fabric/build/run/benchmark-server"

    [[ -d "$MAIN_ROOT/benchmark-worlds/base/world" ]] || die "no base world — build one first (benchmark.sh fresh)"
    if ss -ltnH 'sport = :25565' 2>/dev/null | grep -q .; then
        die "port 25565 is in use — refusing to start (soak/benchmark conflict guard)"
    fi

    RUN_OUT="$OUT_ROOT/$RUN_STAMP/${arm}-rep${rep}"
    python3 "$HARNESS_LIB_DIR/benchmark-results.py" prepare "$RUN_OUT"
    log "=== RUN $arm rep$rep (root=$root, duration=${duration}s, cps=${PROFILE_BACKFILL_CPS:-1000}) ==="

    # Fresh world from base, FRESH store (the walk only visits unmarked regions — a
    # leftover store DB means 0 regions to process and a vacuous run).
    rm -rf "$server_run_dir/world"
    mkdir -p "$server_run_dir"
    cp -r "$MAIN_ROOT/benchmark-worlds/base/world" "$server_run_dir/world"
    rm -rf "$server_run_dir/world/lss-lod"

    mkdir -p "$server_run_dir/config"
    stage_server_config "$server_run_dir/config/lss-server-config.json"

    # pause-when-empty-seconds=-1 is load-bearing: no client EVER joins, and a paused
    # server neither ticks the benchmark duration counter nor advances the walk.
    cat > "$server_run_dir/server.properties" <<'PROPS'
online-mode=false
level-seed=benchmark-seed-42
spawn-protection=0
max-tick-time=-1
pause-when-empty-seconds=-1
difficulty=peaceful
PROPS
    echo "eula=true" > "$server_run_dir/eula.txt"

    # Stale-artifact guard: a crashed run must yield MISSING files, not the last run's.
    rm -f "$server_run_dir/benchmark-results/server.json" \
          "$server_run_dir/server-benchmark.jfr" \
          "$server_run_dir/logs/latest.log"

    log "Building mod ($root)..."
    harness_gradle_at "$root" :fabric:build -x test -x runGameTest -x runClientGameTest --quiet

    harness_start_observer "$MAIN_ROOT/scripts/lib/proc_sampler.sh" "$RUN_OUT/cpu.jsonl" $((duration + 300))
    local sampler_pid=$HARNESS_OBSERVER_PID

    local rc=0
    PROJECT_ROOT="$root"   # mc_start_server cd's here for the gradle invocation
    mc_start_server "$RUN_OUT/orchestrator.log" :fabric:runBenchmarkServer \
        -Pbenchmark.duration="$duration"
    PROJECT_ROOT="$MAIN_ROOT"
    if ! mc_wait_server_ready "$server_run_dir/logs/latest.log" "$RUN_OUT/orchestrator.log" 180; then
        rc=1
    else
        # Wait for the auto-shutdown tick counter, with a hard deadline (the benchmark
        # server only halts on tick count; a stalled JVM must not hang the matrix).
        local total_timeout=$((duration + 180)) elapsed=0
        while kill -0 "$SERVER_PID" 2>/dev/null; do
            if [[ $elapsed -ge $total_timeout ]]; then
                log "deadline exceeded (${total_timeout}s) — killing stalled server"
                kill "$SERVER_PID" 2>/dev/null || true
                rc=1
                break
            fi
            sleep 1
            elapsed=$((elapsed + 1))
        done
        wait "$SERVER_PID" 2>/dev/null || true
        SERVER_PID=""
    fi

    kill "$sampler_pid" 2>/dev/null || true
    wait "$sampler_pid" 2>/dev/null || true
    HARNESS_OBSERVER_PID=""

    [[ -f "$server_run_dir/benchmark-results/server.json" ]] \
        && cp "$server_run_dir/benchmark-results/server.json" "$RUN_OUT/"
    [[ -f "$server_run_dir/server-benchmark.jfr" ]] \
        && cp "$server_run_dir/server-benchmark.jfr" "$RUN_OUT/"
    [[ -f "$server_run_dir/logs/latest.log" ]] \
        && cp "$server_run_dir/logs/latest.log" "$RUN_OUT/server.log"

    # Walk timing + deposit counts (Phase 0 item 3): parsed from StoreBackfill's start
    # line ("Store backfill: N region(s) to process, estimated ~...") and terminal line
    # ("Store backfill complete|stopped: R regions, D deposited, S skipped, E errors,
    # P pauses"). Times are TIME-OF-DAY seconds — the domain --window walk consumes.
    # The low-disk and shutdown terminal variants have different shapes and correctly
    # fall out as walk_complete=false.
    local start_line end_line
    start_line="$(grep -m1 'Store backfill: .* region(s) to process' "$RUN_OUT/server.log" 2>/dev/null || true)"
    end_line="$(grep -m1 -E 'Store backfill (complete|stopped):' "$RUN_OUT/server.log" 2>/dev/null || true)"
    local walk_complete=false walk_start="null" walk_end="null" walk_seconds="null"
    local regions="" deposited="" skipped="" errors="" pauses="" deposits_per_s="null"
    local parse_ok=true
    if [[ -n "$start_line" ]]; then
        walk_start="$(tod_of_line "$start_line")"
        [[ -n "$walk_start" ]] || walk_start="null"
    fi
    if [[ -n "$end_line" ]]; then
        walk_end="$(tod_of_line "$end_line")"
        [[ -n "$walk_end" ]] || walk_end="null"
        read -r regions deposited skipped errors pauses < <(sed -n \
            's/.*: \([0-9]*\) regions, \([0-9]*\) deposited, \([0-9]*\) skipped, \([0-9]*\) errors, \([0-9]*\) pauses.*/\1 \2 \3 \4 \5/p' \
            <<< "$end_line") || true
        # Terminal line matched but the counters didn't parse: the statusLine format
        # drifted — the run must red loudly, never record deposits_per_s = 0 (N6).
        [[ -n "${deposited:-}" ]] || parse_ok=false
        [[ "$end_line" == *"Store backfill complete:"* && "$parse_ok" == "true" ]] && walk_complete=true
        if [[ "$walk_complete" == "true" && "$walk_start" != "null" && "$walk_end" != "null" \
              && "$walk_end" -gt "$walk_start" ]]; then
            walk_seconds=$((walk_end - walk_start))
            deposits_per_s="$(awk -v d="$deposited" -v s="$walk_seconds" 'BEGIN{printf "%.1f", d/s}')"
        fi
    fi

    # Effective-config assertion (Phase 0 item 1) — same contract as the serve harness.
    local echo_line
    echo_line="$(grep -o 'Effective config: .*' "$RUN_OUT/server.log" 2>/dev/null | tail -1 || true)"
    local arm_valid=true
    [[ "$echo_line" == *"useNbtTranscode=${PROFILE_NBT_TRANSCODE:-true}"* ]] || arm_valid=false
    [[ "$echo_line" == *"diskReaderThreads=5"* ]] || arm_valid=false
    # Same ref-predates-the-key tolerance for the disk-read gate's K (v0.11.0 stage B —
    # the staged no-op pin is 5 = the pool, so an explicit different value is a staging
    # bug, while an absent key is just an older ref). Reset per arm — not a local.
    __gate_echo_bad=false
    if [[ "$echo_line" == *"maxConcurrentDiskReads="* ]]; then
        [[ "$echo_line" == *"maxConcurrentDiskReads=5"* ]] || __gate_echo_bad=true
    fi
    if [[ "$echo_line" == *"useSelectiveNbtParse="* ]]; then
        [[ "$echo_line" == *"useSelectiveNbtParse=${PROFILE_SELECTIVE_PARSE:-true}"* ]] || arm_valid=false
    fi
    [[ "${__gate_echo_bad:-false}" != "true" ]] || arm_valid=false
    # A walk that never STARTED is a vacuous run (leftover store marks, staging bug),
    # and an INCOMPLETE walk has no defined deposits/s (M2) — both invalidate the arm.
    [[ -n "$start_line" ]] || arm_valid=false
    [[ "$parse_ok" == "true" ]] || arm_valid=false
    if [[ "$walk_complete" != "true" && "${PROFILE_ALLOW_PARTIAL_WALK:-0}" != "1" ]]; then
        arm_valid=false
    fi

    cat > "$RUN_OUT/meta.json" <<EOF
{
  "arm": "$arm",
  "rep": $rep,
  "ref": "$(git -C "$root" rev-parse --short HEAD)",
  "worktree_dirty": $(if [[ -n "$(git -C "$root" status --porcelain 2>/dev/null)" ]]; then echo true; else echo false; fi),
  "base_ref": "${BASE_REF:-}",
  "duration_s": $duration,
  "backfill_cps": ${PROFILE_BACKFILL_CPS:-1000},
  "nbt_transcode": ${PROFILE_NBT_TRANSCODE:-true},
  "selective_parse": ${PROFILE_SELECTIVE_PARSE:-true},
  "config_echo": "$echo_line",
  "walk": {
    "complete": $walk_complete,
    "start_tod_s": $walk_start,
    "end_tod_s": $walk_end,
    "walk_seconds": $walk_seconds,
    "regions": ${regions:-0},
    "deposited": ${deposited:-0},
    "skipped": ${skipped:-0},
    "errors": ${errors:-0},
    "pauses": ${pauses:-0},
    "deposits_per_s": $deposits_per_s
  },
  "arm_valid": $arm_valid,
  "orchestrator_rc": $rc,
  "finished": "$(date -Is)"
}
EOF
    if [[ $rc -ne 0 ]]; then
        log "run $arm rep$rep FAILED (rc=$rc) — see $RUN_OUT/orchestrator.log"
        return 1
    fi
    if [[ "$arm_valid" != "true" ]]; then
        log "run $arm rep$rep INVALID: echo='${echo_line:-<missing>}' walk_started=$([[ -n "$start_line" ]] && echo yes || echo no) walk_complete=$walk_complete parse_ok=$parse_ok"
        return 1
    fi
    log "run $arm rep$rep done -> $RUN_OUT (deposited=${deposited:-0} in ${walk_seconds}s, ${deposits_per_s}/s)"
}

cmd_matrix() {
    local reps="${1:?reps}" duration="${2:-300}"
    local arm_a="backfill" arm_b=""
    if [[ -n "$BASE_REF" ]]; then
        arm_a="base"; arm_b="change"
        cmd_setup
    fi
    log "Matrix: ${reps} reps x {$arm_a${arm_b:+, $arm_b}}${arm_b:+ ABBA}, duration=${duration}s -> $OUT_ROOT/$RUN_STAMP"
    if [[ -n "$arm_b" && $((reps % 2)) -ne 0 ]]; then
        log "NOTE: odd rep count leaves residual first-position bias after ABBA — prefer even reps"
    fi
    for rep in $(seq 1 "$reps"); do
        local first="$arm_a" second="$arm_b"
        if [[ -n "$arm_b" ]] && (( rep % 2 == 0 )); then first="$arm_b"; second="$arm_a"; fi
        cmd_run "$first" "$rep" "$duration"
        sleep 15
        if [[ -n "$second" ]]; then
            cmd_run "$second" "$rep" "$duration"
            sleep 15
        fi
    done
    log "Matrix complete: $OUT_ROOT/$RUN_STAMP"
}

CMD="${1:-}"
shift || true
RUN_STAMP="${RUN_STAMP:-$(date +%Y%m%d-%H%M%S)}"

case "$CMD" in
    run)    cmd_run "$@" ;;
    matrix) cmd_matrix "$@" ;;
    setup)  cmd_setup ;;
    *) sed -n '3,45p' "$0"; exit 1 ;;
esac
