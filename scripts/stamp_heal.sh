#!/usr/bin/env bash
set -euo pipefail

# Stamped-up_to_date live heal gate (stamped-up-to-date-plan.md §9.9): two chained,
# individually law-checked soak phases —
#   1. stamp-heal-prime      warm-rejoin-summary WITHOUT the clearcache re-stamp and
#                            the poison (3-Opus fold: the clearcache erased the very
#                            inversion the heal must prove against): run 1's stamps
#                            stay serve-then-save, run 2's frame finds the BULK stale
#                            (the named check's before-pin: stale+unknown >= 8), and
#                            the up_to_date answers RATCHET the carried cache
#   2. stamp-heal-rejoin     the carried world + carried client cache rejoin once
#                            more: the named check pins the HEADLINE claim —
#                            tiles_stale collapsed to the designed residue (the
#                            phase-1 kick-save's player tile) while
#                            columns_validated stays bulk-scale and the re-ask
#                            volume drops accordingly (stale -> stamped -> clean)
#
# Same two-carry contract as summary_evicted.sh: the WORLD travels via
# SOAK_WORLD_FROM below; the CLIENT CACHE carry rides soak.sh's staging —
# stamp-heal-rejoin is declared cache-KEEPING there. FABRIC-ONLY like the evicted
# chain (Bukkit's split world dirs make the carry unvalidated on Paper).
#
# Usage: ./scripts/stamp_heal.sh
# Exit nonzero on any phase failure.

PLATFORM="${SOAK_PLATFORM:-fabric}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$PROJECT_ROOT/scripts/lib/harness-lock.sh"
harness_acquire
CARRY_DIR="$PROJECT_ROOT/soak-results/stamp-heal-carry.$$"

case "$PLATFORM" in
    fabric) SERVER_RUN_DIR="$PROJECT_ROOT/fabric/build/run/soak-server" ;;
    *) echo "[stamp-heal] ERROR: fabric-only (see header note)" >&2; exit 1 ;;
esac

log() { echo "[stamp-heal] $*"; }
cleanup() { harness_cleanup; rm -rf "$CARRY_DIR"; }
trap cleanup EXIT

log "=== phase 1: stamp-heal-prime (platform=$PLATFORM) ==="
SOAK_PLATFORM="$PLATFORM" harness_run_script "$PROJECT_ROOT/scripts/soak.sh" stamp-heal-prime

rm -rf "$CARRY_DIR"
mkdir -p "$CARRY_DIR"
cp -r "$SERVER_RUN_DIR/world" "$CARRY_DIR/world"

log "=== phase 2: stamp-heal-rejoin (platform=$PLATFORM, carried world + cache) ==="
SOAK_PLATFORM="$PLATFORM" SOAK_WORLD_FROM="$CARRY_DIR" \
    harness_run_script "$PROJECT_ROOT/scripts/soak.sh" stamp-heal-rejoin

log "both phases green — the stamped rejoin healed the stale set"
