#!/usr/bin/env bash
set -euo pipefail

# Disk-read serving profile harness. Two arm modes over benchmark.sh's no-cache scenario
# (same pre-generated base world, generation disabled, cold client cache, server JFR via
# runBenchmarkServer + the external 1 Hz CPU/wire sampler):
#
#   IO-path A/B (default)     arm = vanilla | c2me — same tree, the only variable is C2ME
#                             on the server runtime (-Pbenchmark.c2me=true):
#                               vanilla  LSS reads at IOWorker BACKGROUND priority
#                               c2me     C2ME nulls the vanilla IOWorker -> LSS latches the
#                                        incompatible fallback: chunkMap.read + throttle
#   Ref-vs-ref (PERF Phase 0) arm = base | change — set PROFILE_BASE_REF=<git-ref>:
#                             `change` runs THIS tree, `base` runs a detached worktree at
#                             the ref (benchmark_compare.sh pattern: worktree + prebuild +
#                             rsync'd base world). Both refs must carry the effective-config
#                             echo (>= the Phase 0 commit) or the arm fails its echo check.
#
# The matrix interleaves arms ABBA across reps (odd reps A,B; even reps B,A) — a fixed
# order is a systematic first-position bias (page cache, JIT, gradle daemon warmth).
#
# Usage:
#   profile_disk_read.sh run <arm> <rep> <duration> <R>   # one arm run
#   profile_disk_read.sh matrix <reps> <duration> <R>     # ABBA-interleaved matrix
#   profile_disk_read.sh setup                            # ref mode: worktree + prebuild
#
# Results: profile-results/<stamp>/<arm>-rep<N>/{server.json,client.json,server-benchmark.jfr,
#          client-benchmark.jfr,cpu.jsonl,orchestrator.log,server.log,meta.json}

MAIN_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$MAIN_ROOT/scripts/lib/harness-lock.sh"
harness_acquire
OUT_ROOT="${OUT_ROOT:-$MAIN_ROOT/profile-results}"

# Ref-vs-ref arm mode (PERF Phase 0 item 2). PROFILE_BASE_WT default deliberately
# OUTSIDE the repo (a worktree inside would be swept into release_check globs).
BASE_REF="${PROFILE_BASE_REF:-}"
BASE_WT="${PROFILE_BASE_WT:-$(dirname "$MAIN_ROOT")/lss-profile-base}"

log() { echo "[profile] $*"; }
die() { echo "[profile] ERROR: $*" >&2; exit 1; }

# PROFILE_BW_PER_PLAYER: per-player bandwidth cap override (default 20 MiB/s) — the
# low-cap backpressure experiment sets e.g. 2097152 to verify CPU scales with the cap.
# PROFILE_SEND_QUEUE: sendQueueLimitPerPlayer override (default 1024) — set BELOW
# WANT_SET_BUDGET (800) to force the router's sendQueueFull admission gate to engage
# (single-player, the client's bounded want-set otherwise backpressures first).
# PROFILE_NBT_TRANSCODE: useNbtTranscode override (default true) — set false for a
# same-box-state object-path baseline matrix (round-2 A/B; pitfall: never compare
# matrices across days or box states).
# PROFILE_SELECTIVE_PARSE: useSelectiveNbtParse override (default true) — the Phase 4
# kill-switch A/B arm variable (same jar, config-flipped).
stage_server_config() { # <path>
    cat > "$1" <<EOF
{
  "enabled": true,
  "lodDistanceChunks": $LOD_R,
  "bytesPerSecondLimitPerPlayer": ${PROFILE_BW_PER_PLAYER:-20971520},
  "diskReaderThreads": 5,
  "maxConcurrentDiskReads": 5,
  "sendQueueLimitPerPlayer": ${PROFILE_SEND_QUEUE:-1024},
  "bytesPerSecondLimitGlobal": 104857600,
  "enableChunkGeneration": false,
  "generationConcurrencyLimitGlobal": 32,
  "generationTimeoutSeconds": 60,
  "dirtyBroadcastIntervalSeconds": 10,
  "generationConcurrencyLimitPerPlayer": 16,
  "perDimensionTimestampCacheSizeMB": 32,
  "missMemoTtlSeconds": 30,
  "useBackgroundReadPriority": true,
  "useNbtTranscode": ${PROFILE_NBT_TRANSCODE:-true},
  "useSelectiveNbtParse": ${PROFILE_SELECTIVE_PARSE:-true},
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

cmd_run() {
    local arm="${1:?arm}" rep="${2:?rep}" duration="${3:?duration}" LOD_R="${4:?lod-distance}"
    local extra_args="" root="$MAIN_ROOT"
    case "$arm" in
        vanilla) ;;
        c2me) extra_args="-Pbenchmark.c2me=true" ;;
        # C6 legacy-dialect arm pair (XVER §12): SAME tree, arm variable = the client's
        # announced protocol. dialect19 exercises the server's per-recipient egress
        # translation (v20 -> native + zstd recompress) on every served column.
        # unset, not bare (C6 review C-1, empirically proven): cmd_run is a FUNCTION,
        # so an exported var from a prior dialect19 arm survives into native arms —
        # 7 of 8 runs of the first c6-dialect matrix negotiated v19 and the "A/B" was
        # an A/A. The arm-validity check below pins the announced protocol per run.
        native) unset BENCHMARK_CLIENT_GRADLE_ARGS ;;
        dialect19) export BENCHMARK_CLIENT_GRADLE_ARGS="-Psoak.dialect=19" ;;
        base|change)
            [[ -n "$BASE_REF" ]] || die "arm '$arm' needs PROFILE_BASE_REF (ref-vs-ref mode)"
            root="$(root_for_arm "$arm")"
            # ensure_base_worktree is idempotent and REPOINTS a leftover worktree from a
            # previous session's ref — without this a standalone `run base` measures
            # whatever the stale worktree happens to hold (B0 review N7).
            [[ "$arm" == "change" ]] || ensure_base_worktree
            ;;
        *) die "unknown arm '$arm' (want vanilla | c2me | base | change | native | dialect19)" ;;
    esac

    [[ -d "$MAIN_ROOT/benchmark-worlds/base/world" ]] || die "no base world — build one first"
    if ss -ltnH 'sport = :25565' 2>/dev/null | grep -q .; then
        die "port 25565 is in use — refusing to start (soak/benchmark conflict guard)"
    fi

    # Identical base world for every arm: sync main's into the worktree.
    if [[ "$root" != "$MAIN_ROOT" ]]; then
        mkdir -p "$root/benchmark-worlds/base"
        rsync -a --delete "$MAIN_ROOT/benchmark-worlds/base/world/" \
            "$root/benchmark-worlds/base/world/"
    fi

    RUN_OUT="$OUT_ROOT/$RUN_STAMP/${arm}-rep${rep}"
    python3 "$HARNESS_LIB_DIR/benchmark-results.py" prepare "$RUN_OUT"
    log "=== RUN $arm rep$rep (root=$root, duration=${duration}s, R=$LOD_R) ==="

    # Cold client cache every run + staged configs (gen OFF: pure disk-read + serialization).
    local srv_cfg_dir="$root/fabric/build/run/benchmark-server/config"
    local cli_cfg_dir="$root/fabric/build/run/benchmark-client/config"
    mkdir -p "$srv_cfg_dir" "$cli_cfg_dir"
    rm -rf "$cli_cfg_dir/lss/cache" "$root/fabric/build/run/benchmark-client/.lss/cache"  # both roots (stage D)
    stage_server_config "$srv_cfg_dir/lss-server-config.json"
    stage_client_config "$cli_cfg_dir/lss-client-config.json"

    # Stale-artifact guard: a crashed run must yield MISSING files, not the previous run's.
    rm -f "$root/benchmark-results/current.json" "$root/benchmark-results/server.json" "$root/benchmark-results/client.json" \
          "$root/benchmark-results/"*.jfr \
          "$root/fabric/build/run/benchmark-server/benchmark-results/server.json" \
          "$root/fabric/build/run/benchmark-client/benchmark-results/client.json" \
          "$root/fabric/build/run/benchmark-server/server-benchmark.jfr" \
          "$root/fabric/build/run/benchmark-client/client-benchmark.jfr"

    harness_start_observer "$MAIN_ROOT/scripts/lib/proc_sampler.sh" "$RUN_OUT/cpu.jsonl" $((duration + 420))
    local sampler_pid=$HARNESS_OBSERVER_PID

    local rc=0
    # BENCHMARK_CONFIG_STAGED: without it benchmark.sh's neutral-staging block (6856bcb,
    # 2026-08-02) silently replaces the config staged above and every PROFILE_* knob is
    # inert — found 2026-08-06 (this round's F1 ran shipped defaults; see the findings
    # doc's erratum). store_gate.sh/benchmark_compare.sh always exported it.
    harness_run_script env BENCHMARK_CONFIG_STAGED=1 BENCHMARK_SERVER_GRADLE_ARGS="$extra_args" \
        "$root/scripts/benchmark.sh" no-cache "$duration" \
        > "$RUN_OUT/orchestrator.log" 2>&1 || rc=$?

    kill "$sampler_pid" 2>/dev/null || true
    wait "$sampler_pid" 2>/dev/null || true
    HARNESS_OBSERVER_PID=""

    if [[ $rc -eq 0 ]]; then
        python3 "$HARNESS_LIB_DIR/benchmark-results.py" record "$root" "$RUN_OUT" legacy || rc=$?
    fi

    for f in server.json client.json server.log client.log \
             server-benchmark.jfr client-benchmark.jfr; do
        [[ -f "$root/benchmark-results/$f" ]] && cp "$root/benchmark-results/$f" "$RUN_OUT/"
    done

    # A/B validity 0 (dialect arms): the negotiated protocol IS the arm variable —
    # assert it from the client log (C6 review C-1; no meta field carries it).
    local dialect_ok=true
    if [[ "$arm" == "native" || "$arm" == "dialect19" ]]; then
        local want_proto="v20"
        [[ "$arm" == "dialect19" ]] && want_proto="v19"
        if ! grep -q "Server session config received (protocol ${want_proto}," \
                "$RUN_OUT/client.log" 2>/dev/null; then
            dialect_ok=false
            log "ARM INVALID: $arm rep$rep did not negotiate ${want_proto} (see client.log)"
        fi
    fi

    # A/B validity 1 (io-path mode): the c2me arm must have latched the incompatible
    # fallback (warn present), every other arm must not. Ref arms run vanilla IO.
    local warn="absent"
    if grep -q 'Background-priority disk reads unavailable' "$RUN_OUT/server.log" 2>/dev/null; then
        warn="present"
    fi
    local warn_ok=true
    if [[ "$arm" == "c2me" ]]; then
        [[ "$warn" == "present" ]] || warn_ok=false
    else
        [[ "$warn" == "absent" ]] || warn_ok=false
    fi

    # A/B validity 2 (Phase 0 item 1): the server's effective-config echo must carry the
    # staged knobs — an ignored key must FAIL the arm, not compare two identical arms.
    # Requires both refs >= the echo commit in ref-vs-ref mode.
    local echo_line
    echo_line="$(grep -o 'Effective config: .*' "$RUN_OUT/server.log" 2>/dev/null | tail -1 || true)"
    local echo_ok=true
    [[ "$echo_line" == *"useNbtTranscode=${PROFILE_NBT_TRANSCODE:-true}"* ]] || echo_ok=false
    [[ "$echo_line" == *"diskReaderThreads=5"* ]] || echo_ok=false
    # Tolerant when the ref predates the key (pre-B4 base arms echo no such key —
    # only an EXPLICIT mismatch invalidates the arm).
    # Same ref-predates-the-key tolerance for the disk-read gate's K (v0.11.0 stage B —
    # the staged no-op pin is 5 = the pool, so an explicit different value is a staging
    # bug, while an absent key is just an older ref). Reset per arm — not a local.
    __gate_echo_bad=false
    if [[ "$echo_line" == *"maxConcurrentDiskReads="* ]]; then
        [[ "$echo_line" == *"maxConcurrentDiskReads=5"* ]] || __gate_echo_bad=true
    fi
    if [[ "$echo_line" == *"useSelectiveNbtParse="* ]]; then
        [[ "$echo_line" == *"useSelectiveNbtParse=${PROFILE_SELECTIVE_PARSE:-true}"* ]] || echo_ok=false
    fi
    [[ "${__gate_echo_bad:-false}" != "true" ]] || echo_ok=false

    local arm_valid=true
    { [[ "$warn_ok" == "true" ]] && [[ "$echo_ok" == "true" ]] \
        && [[ "$dialect_ok" == "true" ]]; } || arm_valid=false

    cat > "$RUN_OUT/meta.json" <<EOF
{
  "arm": "$arm",
  "rep": $rep,
  "ref": "$(git -C "$root" rev-parse --short HEAD)",
  "worktree_dirty": $(if [[ -n "$(git -C "$root" status --porcelain 2>/dev/null)" ]]; then echo true; else echo false; fi),
  "base_ref": "${BASE_REF:-}",
  "duration_s": $duration,
  "lod_distance": $LOD_R,
  "bw_per_player": ${PROFILE_BW_PER_PLAYER:-20971520},
  "nbt_transcode": ${PROFILE_NBT_TRANSCODE:-true},
  "selective_parse": ${PROFILE_SELECTIVE_PARSE:-true},
  "fallback_warn": "$warn",
  "config_echo": "$echo_line",
  "arm_valid": $arm_valid,
  "orchestrator_rc": $rc,
  "finished": "$(date -Is)"
}
EOF
    if [[ $rc -ne 0 ]]; then
        log "run $arm rep$rep FAILED (rc=$rc) — see $RUN_OUT/orchestrator.log"
        return 1
    fi
    if [[ "$warn_ok" != "true" ]]; then
        log "run $arm rep$rep INVALID ARM: fallback warn $warn for arm $arm"
        return 1
    fi
    if [[ "$echo_ok" != "true" ]]; then
        log "run $arm rep$rep INVALID ARM: config echo '${echo_line:-<missing>}' does not carry the staged knobs"
        return 1
    fi
    # dialect_ok joins the return-1 ladder (pre-D3 review L3-6): it stamped
    # arm_valid:false but fell through to "done", so only compare_profile.py's
    # pooling would surface the invalid arm — any other analysis path consumed it.
    if [[ "$dialect_ok" != "true" ]]; then
        log "run $arm rep$rep INVALID ARM: negotiated protocol did not match the arm's dialect"
        return 1
    fi
    log "run $arm rep$rep done -> $RUN_OUT"
}

cmd_matrix() {
    local reps="${1:?reps}" duration="${2:?duration}" lod_r="${3:?lod-distance}"
    local arm_a="vanilla" arm_b="c2me"
    if [[ -n "${PROFILE_DIALECT_MATRIX:-}" ]]; then
        # C6 legacy-dialect matrix: same tree, client dialect is the arm variable.
        arm_a="native"; arm_b="dialect19"
    elif [[ -n "$BASE_REF" ]]; then
        arm_a="base"; arm_b="change"
        cmd_setup
    fi
    log "Matrix: ${reps} reps x {$arm_a, $arm_b} ABBA, duration=${duration}s, R=$lod_r -> $OUT_ROOT/$RUN_STAMP"
    if (( reps % 2 != 0 )); then
        log "NOTE: odd rep count leaves residual first-position bias after ABBA — prefer even reps"
    fi
    for rep in $(seq 1 "$reps"); do
        # ABBA: odd reps run A,B; even reps run B,A — cancels first-position bias.
        local first="$arm_a" second="$arm_b"
        if (( rep % 2 == 0 )); then first="$arm_b"; second="$arm_a"; fi
        for arm in "$first" "$second"; do
            cmd_run "$arm" "$rep" "$duration" "$lod_r"
            sleep 15   # settle: gradle daemon / page cache quiesce between runs
        done
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
    *) sed -n '3,29p' "$0"; exit 1 ;;
esac
