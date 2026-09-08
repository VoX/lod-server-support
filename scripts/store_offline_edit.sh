#!/usr/bin/env bash
set -euo pipefail

# LOD-store offline-edit gate (docs/planning/lod-store-implementation-plan.md Phase 2):
# three chained, individually law-checked soak phases —
#   1. store-offline-populate  lodStore=full backfill charges the store; probes 20:0
#                              (to be edited) and -20:0 (control) record baseline hashes
#   2. store-offline-mutate    LSS disabled end-to-end; a REAL setblock in chunk (20,0)
#                              reaches the region file with no LSS-visible event, then
#                              save-all — the exact server-offline-edit shape
#   3. store-offline-verify    a cold-cache client on the carried world + store: the
#                              startup sweep must drop ONLY what the edit justifies
#                              (hits floor + disk ceiling checked in-scenario)
# plus the cross-phase probe-hash verdict only this wrapper can compute:
#   - edited probe 20:0   : verify hash MUST DIFFER from populate hash (the store did
#                           not serve stale bytes across the offline edit)
#   - control probe -20:0 : verify hash MUST EQUAL populate hash (byte-stable store
#                           round trip across a server restart)
#
# Usage: [SOAK_PLATFORM=fabric|paper] ./scripts/store_offline_edit.sh
# Exit nonzero on any phase failure or a cross-phase hash violation.

PLATFORM="${SOAK_PLATFORM:-fabric}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$PROJECT_ROOT/scripts/lib/harness-lock.sh"
harness_acquire
RESULTS_ROOT="$PROJECT_ROOT/soak-results"
CARRY_DIR="$PROJECT_ROOT/soak-results/store-offline-carry.$$"

case "$PLATFORM" in
    fabric) SERVER_RUN_DIR="$PROJECT_ROOT/fabric/build/run/soak-server"; TAG="" ;;
    paper)  SERVER_RUN_DIR="$PROJECT_ROOT/paper/build/run/soak-server"; TAG="paper-" ;;
    *) echo "[store-offline] ERROR: SOAK_PLATFORM must be fabric|paper" >&2; exit 1 ;;
esac

log() { echo "[store-offline] $*"; }
cleanup() { harness_cleanup; rm -rf "$CARRY_DIR"; }
trap cleanup EXIT

latest_results() { # <scenario>
    # Freshness-guarded: only dirs created after this wrapper started qualify — a
    # zero-exit phase that somehow wrote no results dir must fail here, never silently
    # compare a previous run's recording (review finding).
    local d
    d="$(ls -1d "$RESULTS_ROOT/$1-$TAG"2* 2>/dev/null | sort | tail -1)"
    if [[ -z "$d" || "$(stat -c %Y "$d")" -lt "$WRAPPER_START_EPOCH" ]]; then
        echo "[store-offline] ERROR: no fresh results dir for $1 (latest: ${d:-none})" >&2
        exit 1
    fi
    echo "$d"
}
WRAPPER_START_EPOCH="$(date +%s)"

carry_world() {
    rm -rf "$CARRY_DIR"
    mkdir -p "$CARRY_DIR"
    cp -r "$SERVER_RUN_DIR/world" "$CARRY_DIR/world"
}

run_phase() { # <scenario> [carry]
    local scenario="$1" carry="${2:-}"
    log "=== phase: $scenario (platform=$PLATFORM) ==="
    if [[ -n "$carry" ]]; then
        SOAK_PLATFORM="$PLATFORM" SOAK_WORLD_FROM="$CARRY_DIR" \
            harness_run_script "$PROJECT_ROOT/scripts/soak.sh" "$scenario"
    else
        SOAK_PLATFORM="$PLATFORM" harness_run_script "$PROJECT_ROOT/scripts/soak.sh" "$scenario"
    fi
    carry_world
}

run_phase store-offline-populate
POPULATE_DIR="$(latest_results store-offline-populate)"
run_phase store-offline-mutate carry
run_phase store-offline-verify carry
VERIFY_DIR="$(latest_results store-offline-verify)"

log "cross-phase probe-hash verdict"
log "  populate: $POPULATE_DIR"
log "  verify:   $VERIFY_DIR"
python3 - "$POPULATE_DIR/server.jsonl" "$VERIFY_DIR/server.jsonl" <<'EOF'
import json, sys

def final_hashes(path):
    hashes = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            row = json.loads(line)
            if row.get("event") == "snapshot" and "probe_hashes" in row:
                hashes = row["probe_hashes"]
    return hashes

pop, ver = final_hashes(sys.argv[1]), final_hashes(sys.argv[2])
print(f"[store-offline]   populate hashes: {pop}")
print(f"[store-offline]   verify hashes:   {ver}")
failures = []
for token, want_equal, why in (
        ("20:0", False, "the store served STALE bytes across the offline edit"),
        ("-20:0", True, "the store round trip is not byte-stable across a restart")):
    p, v = pop.get(token), ver.get(token)
    if p in (None, -1) or v in (None, -1):
        failures.append(f"probe {token} unserved on a phase (populate={p}, verify={v}) "
                        f"— comparison void")
    elif (p == v) != want_equal:
        failures.append(f"probe {token}: populate={p} verify={v} — {why}")
for f in failures:
    print(f"[store-offline] FAIL: {f}")
if failures:
    sys.exit(1)
print("[store-offline] cross-phase verdict: PASS "
      "(edited probe re-served fresh, control probe byte-identical)")
EOF
log "offline-edit gate PASS ($PLATFORM)"
