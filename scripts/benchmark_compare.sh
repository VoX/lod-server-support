#!/usr/bin/env bash
set -euo pipefail

# CPU-efficiency comparison harness: v16 protocol (v0.6.2) vs v17 protocol (v0.7.x).
# Design: docs/planning/benchmark-cpu-compare-design.md
#
# Drives each ref's OWN checked-in scripts/benchmark.sh (no-cache scenario) with staged
# configs and an external 1 Hz CPU/wire sampler (scripts/lib/proc_sampler.sh), archiving
# per-run evidence under benchmark-compare-results/.
#
# Usage:
#   benchmark_compare.sh setup                       # create v0.6.2 worktree + prebuild both
#   benchmark_compare.sh baseworld <seconds>         # build the shared base world (fresh run)
#   benchmark_compare.sh coverage                    # report base-world coverage (pick R)
#   benchmark_compare.sh run <arm> <rep> <dur> <R>   # one run: arm = v16 | v17 | v17-fg
#   benchmark_compare.sh matrix <reps> <dur> <R>     # interleaved full matrix
#
# Environment overrides: V16_REF (default v0.6.2), V16_WT (worktree path), OUT_ROOT.

MAIN_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
V16_REF="${V16_REF:-v0.6.2}"
V16_WT="${V16_WT:-$(dirname "$MAIN_ROOT")/lss-bench-${V16_REF}}"
OUT_ROOT="${OUT_ROOT:-$MAIN_ROOT/benchmark-compare-results}"

log() { echo "[compare] $*"; }
die() { echo "[compare] ERROR: $*" >&2; exit 1; }

root_for_arm() {
    case "$1" in
        v16) echo "$V16_WT" ;;
        v17|v17-fg) echo "$MAIN_ROOT" ;;
        *) die "unknown arm '$1' (want v16 | v17 | v17-fg)" ;;
    esac
}

prebuild() {
    local root="$1"
    log "Prebuilding $root ..."
    (cd "$root" && ./gradlew :fabric:build -x test -x runGameTest -x runClientGameTest --quiet)
}

cmd_setup() {
    if [[ ! -d "$V16_WT" ]]; then
        log "Creating worktree $V16_WT @ $V16_REF"
        git -C "$MAIN_ROOT" worktree add --detach "$V16_WT" "$V16_REF"
    else
        log "Worktree $V16_WT already exists"
    fi
    prebuild "$MAIN_ROOT"
    prebuild "$V16_WT"
    log "Setup complete"
}

cmd_baseworld() {
    local seconds="${1:?baseworld needs a duration in seconds}"
    # Fresh scenario with each config at its shipped defaults (generation ON) — delete any
    # staged config so the run regenerates pure defaults, and clear the client cache.
    rm -f "$MAIN_ROOT/fabric/build/run/benchmark-server/config/lss-server-config.json"
    rm -f "$MAIN_ROOT/fabric/build/run/benchmark-client/config/lss-client-config.json"
    rm -rf "$MAIN_ROOT/fabric/build/run/benchmark-client/config/lss/cache"
    log "Building base world: fresh run for ${seconds}s (generation enabled, defaults)"
    (cd "$MAIN_ROOT" && ./scripts/benchmark.sh fresh "$seconds")
    log "Base world saved to benchmark-worlds/base/world"
}

cmd_coverage() {
    python3 "$MAIN_ROOT/scripts/analyze_benchmark_compare.py" coverage \
        "$MAIN_ROOT/benchmark-worlds/base/world"
}

stage_server_config() { # <path> <use_bg_read>
    cat > "$1" <<EOF
{
  "enabled": true,
  "lodDistanceChunks": $LOD_R,
  "bytesPerSecondLimitPerPlayer": 20971520,
  "diskReaderThreads": 5,
  "sendQueueLimitPerPlayer": 4000,
  "bytesPerSecondLimitGlobal": 104857600,
  "enableChunkGeneration": false,
  "generationConcurrencyLimitGlobal": 32,
  "generationTimeoutSeconds": 60,
  "dirtyBroadcastIntervalSeconds": 10,
  "syncOnLoadConcurrencyLimitPerPlayer": 200,
  "generationConcurrencyLimitPerPlayer": 16,
  "perDimensionTimestampCacheSizeMB": 32,
  "missMemoTtlSeconds": 30,
  "useBackgroundReadPriority": $2,
  "enableV16Compat": true
}
EOF
}

stage_client_config() { # <path>
    cat > "$1" <<EOF
{
  "receiveServerLods": true,
  "lodDistanceChunks": $LOD_R,
  "enableV16ServerCompat": true,
  "enableV16Generation": true
}
EOF
}

cmd_run() {
    local arm="${1:?arm}" rep="${2:?rep}" duration="${3:?duration}" LOD_R="${4:?lod-distance}"
    local root; root="$(root_for_arm "$arm")"
    local bg_read="true"
    [[ "$arm" == "v17-fg" ]] && bg_read="false"

    [[ -d "$MAIN_ROOT/benchmark-worlds/base/world" ]] || die "no base world — run baseworld first"
    [[ -d "$root" ]] || die "checkout $root missing — run setup first"
    if ss -ltnH 'sport = :25565' 2>/dev/null | grep -q .; then
        die "port 25565 is in use — refusing to start (soak/benchmark conflict guard)"
    fi

    RUN_OUT="$OUT_ROOT/$RUN_STAMP/${arm}-rep${rep}"
    mkdir -p "$RUN_OUT"
    log "=== RUN $arm rep$rep (root=$root, duration=${duration}s, R=$LOD_R, bgRead=$bg_read) ==="

    # Identical base world for every arm: sync main's into the worktree.
    if [[ "$root" != "$MAIN_ROOT" ]]; then
        mkdir -p "$root/benchmark-worlds/base"
        rsync -a --delete "$MAIN_ROOT/benchmark-worlds/base/world/" \
            "$root/benchmark-worlds/base/world/"
    fi

    # Cold client every run + staged configs.
    local srv_cfg_dir="$root/fabric/build/run/benchmark-server/config"
    local cli_cfg_dir="$root/fabric/build/run/benchmark-client/config"
    mkdir -p "$srv_cfg_dir" "$cli_cfg_dir"
    rm -rf "$cli_cfg_dir/lss/cache"
    stage_server_config "$srv_cfg_dir/lss-server-config.json" "$bg_read"
    stage_client_config "$cli_cfg_dir/lss-client-config.json"

    # Stale-artifact guard: benchmark.sh collects from these paths — a crashed run must
    # yield MISSING files, not silently re-collect the previous run's output.
    rm -f "$root/benchmark-results/server.json" "$root/benchmark-results/client.json" \
          "$root/benchmark-results/"*.jfr \
          "$root/fabric/build/run/benchmark-server/benchmark-results/server.json" \
          "$root/fabric/build/run/benchmark-client/benchmark-results/client.json" \
          "$root/fabric/build/run/benchmark-server/server-benchmark.jfr" \
          "$root/fabric/build/run/benchmark-client/client-benchmark.jfr"

    "$MAIN_ROOT/scripts/lib/proc_sampler.sh" "$RUN_OUT/cpu.jsonl" $((duration + 420)) &
    local sampler_pid=$!

    local rc=0
    (cd "$root" && ./scripts/benchmark.sh no-cache "$duration") \
        > "$RUN_OUT/orchestrator.log" 2>&1 || rc=$?

    kill "$sampler_pid" 2>/dev/null || true
    wait "$sampler_pid" 2>/dev/null || true

    for f in server.json client.json server.log client.log \
             server-benchmark.jfr client-benchmark.jfr; do
        [[ -f "$root/benchmark-results/$f" ]] && cp "$root/benchmark-results/$f" "$RUN_OUT/"
    done

    cat > "$RUN_OUT/meta.json" <<EOF
{
  "arm": "$arm",
  "rep": $rep,
  "ref": "$(git -C "$root" rev-parse --short HEAD)",
  "duration_s": $duration,
  "lod_distance": $LOD_R,
  "use_background_read": $bg_read,
  "orchestrator_rc": $rc,
  "finished": "$(date -Is)"
}
EOF
    if [[ $rc -ne 0 ]]; then
        log "run $arm rep$rep FAILED (rc=$rc) — see $RUN_OUT/orchestrator.log"
        return 1
    fi
    log "run $arm rep$rep done -> $RUN_OUT"
}

cmd_matrix() {
    local reps="${1:?reps}" duration="${2:?duration}" lod_r="${3:?lod-distance}"
    log "Matrix: ${reps} reps x {v16, v17, v17-fg}, duration=${duration}s, R=$lod_r -> $OUT_ROOT/$RUN_STAMP"
    for rep in $(seq 1 "$reps"); do
        for arm in v16 v17 v17-fg; do
            cmd_run "$arm" "$rep" "$duration" "$lod_r"
            sleep 15   # settle: let the gradle daemon/page cache quiesce between runs
        done
    done
    log "Matrix complete: $OUT_ROOT/$RUN_STAMP"
    python3 "$MAIN_ROOT/scripts/analyze_benchmark_compare.py" analyze "$OUT_ROOT/$RUN_STAMP" || true
}

CMD="${1:-}"
shift || true
# One stamp per invocation; matrix groups all its runs under it. `run` honors an
# externally provided RUN_STAMP so pilots and matrices can share a directory scheme.
RUN_STAMP="${RUN_STAMP:-$(date +%Y%m%d-%H%M%S)}"

case "$CMD" in
    setup)     cmd_setup "$@" ;;
    baseworld) cmd_baseworld "$@" ;;
    coverage)  cmd_coverage "$@" ;;
    run)       cmd_run "$@" ;;
    matrix)    cmd_matrix "$@" ;;
    *) sed -n '3,20p' "$0"; exit 1 ;;
esac
