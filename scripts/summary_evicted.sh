#!/usr/bin/env bash
set -euo pipefail

# P1 header-rung live gate (region-summary-sync-plan.md P1; the P1 review's MAJOR-5):
# two chained, individually law-checked soak phases —
#   1. warm-rejoin-summary     its run-1 clearcache re-serve leaves the client cache
#                              holding stamps that CLEAR the serve-latency margin over
#                              every settled region header (a plain serve-then-save
#                              stamp never can — saves postdate serves)
#   2. evicted-tscache-rejoin  the carried world boots a FRESH server with
#                              world/data/lss-timestamps.bin DELETED: the whole-disc
#                              ts>0 re-declare hits an EMPTY timestamp cache and must
#                              resolve through the region-header freshness rung
#                              (disk.header_hits floor + bounded re-download, checked
#                              by the scenario's named check) instead of the GB-class
#                              full re-serve the rung exists to kill
#
# The chain has TWO carries and only one is this script's (final harness review):
# the WORLD is carried explicitly below via SOAK_WORLD_FROM; the CLIENT CACHE carry
# rides soak.sh's staging — evicted-tscache-rejoin is declared cache-KEEPING there
# (most scenarios wipe the client cache at stage time), so phase 2's client still
# holds phase 1's re-stamped disc. If soak.sh's cache staging for this scenario ever
# changes, phase 2 degrades to a cold resync and the header_hits floor reds with a
# mystery — check the staging before the rung.
#
# Usage: ./scripts/summary_evicted.sh          (Fabric only)
# Exit nonzero on any phase failure.
#
# FABRIC-ONLY, deliberately: Bukkit splits the world into world/world_nether/world_the_end
# directories and the run-dir layout differs, so the world carry and the
# lss-timestamps.bin deletion below are unvalidated on Paper — port the staging
# before offering the platform, don't just widen the case.

PLATFORM="${SOAK_PLATFORM:-fabric}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$PROJECT_ROOT/scripts/lib/harness-lock.sh"
harness_acquire
CARRY_DIR="$PROJECT_ROOT/soak-results/summary-evicted-carry.$$"

case "$PLATFORM" in
    fabric) SERVER_RUN_DIR="$PROJECT_ROOT/fabric/build/run/soak-server" ;;
    *) echo "[summary-evicted] ERROR: fabric-only (see header note)" >&2; exit 1 ;;
esac

log() { echo "[summary-evicted] $*"; }
cleanup() { harness_cleanup; rm -rf "$CARRY_DIR"; }
trap cleanup EXIT

log "=== phase 1: warm-rejoin-summary (platform=$PLATFORM) ==="
SOAK_PLATFORM="$PLATFORM" harness_run_script "$PROJECT_ROOT/scripts/soak.sh" warm-rejoin-summary

rm -rf "$CARRY_DIR"
mkdir -p "$CARRY_DIR"
cp -r "$SERVER_RUN_DIR/world" "$CARRY_DIR/world"

log "=== phase 2: evicted-tscache-rejoin (platform=$PLATFORM, carried world) ==="
SOAK_PLATFORM="$PLATFORM" SOAK_WORLD_FROM="$CARRY_DIR" \
    harness_run_script "$PROJECT_ROOT/scripts/soak.sh" evicted-tscache-rejoin

log "both phases green — the header rung carried an evicted-tscache rejoin"
