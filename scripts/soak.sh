#!/usr/bin/env bash
set -euo pipefail

# LSS Soak Test Orchestrator
# Usage: [SOAK_PLATFORM=fabric|paper|folia] ./scripts/soak.sh <scenario>|all
#   scenario: fresh-backfill | warm-rejoin | dimension-trip | dirty-broadcast
#           | rate-limit-storm | disk-saturation | generation-disabled
#           | generation-capacity-stress | bandwidth-throttle
#           | cold-restart-resync | enabled-false | teleport-prune
#           | dirty-range-filter | dirty-during-backfill | dirty-while-offline
#           | clearcache-mid-session | dimension-rejoin-warm
#           | paper-dirty-falling-block (SOAK_PLATFORM=paper|folia)
#
# Runs a real dedicated server + headless client through a scripted timeline
# (scripts/soak-scenarios/<name>.json), collects jsonl snapshots from both
# sides into soak-results/<scenario>-<timestamp>/, then runs
# scripts/check_soak.py against them. Exit code = checker exit code.
#
# SOAK_PLATFORM=paper runs the identical scenario against a real Paper server
# (:paper:runSoakServer + PaperSoakScenarioDriver) with the UNCHANGED Fabric soak
# client and checker. Paper keeps its own base-world snapshot (soak-worlds/base-paper);
# on the 1.21.x lines Paper/Folia use the legacy SPLIT layout (world/, world_nether/,
# world_the_end/) — the staging's world* glob handles it, so the snapshot carries every
# dimension including the End.
#
# SOAK_PLATFORM=folia runs the Paper scenario set against a real Folia server
# (:paper:runFolia downloads the jar; base world soak-worlds/base-folia). Same plugin,
# same driver: the driver maps save-all to an acknowledged no-op (Folia unregisters the
# command) and the staging compensates with an aggressive bukkit.yml autosave.

SCENARIO="${1:-}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SELF="$PROJECT_ROOT/scripts/soak.sh"
CLIENT_RUN_DIR="$PROJECT_ROOT/fabric/build/run/soak-client"
RESULTS_ROOT="$PROJECT_ROOT/soak-results"
WORLDS_DIR="$PROJECT_ROOT/soak-worlds"
SCENARIOS_DIR="$PROJECT_ROOT/scripts/soak-scenarios"
ALL_SCENARIOS=(fresh-backfill warm-rejoin dimension-trip dirty-broadcast
               rate-limit-storm disk-saturation disk-read-gate generation-disabled
               generation-capacity-stress bandwidth-throttle
               cold-restart-resync enabled-false teleport-prune
               dirty-range-filter dirty-during-backfill dirty-while-offline
               clearcache-mid-session dimension-rejoin-warm store-second-join
               store-save-storm warm-rejoin-summary dirty-while-offline-summary
               hybrid-boundary)
# Scenarios ported to Paper. The remaining ones are Fabric-specific for now: the dirty-*
# family leans on the save-hook + DirtyContentFilter (Paper's dirty detection is
# event-driven — paper-dirty-falling-block is the Paper-native dirty scenario),
# cold-restart-resync restores a Fabric-layout world/cache snapshot pair, and the
# stress/config scenarios simply haven't been validated on Paper yet.
# warm-rejoin-summary is in the Bukkit sets per the no-cheap-unit-test doctrine (the
# summary sweeper's live Paper/Folia gate); dirty-while-offline-summary stays Fabric-only
# like its namesake — its console setblock fires no Bukkit event, so the Paper tscache
# would answer the probe stale (the documented unfired-event staleness bound).
PAPER_SCENARIOS=(fresh-backfill warm-rejoin dimension-trip warm-rejoin-summary
                 paper-dirty-falling-block)
# Folia runs the identical Paper scenario set: same plugin jar, same timelines, same checker.
# save-all steps are mapped to acknowledged no-ops by the driver (Folia unregisters the
# command); an aggressive bukkit.yml autosave keeps chunks flushing mid-run instead.
FOLIA_SCENARIOS=("${PAPER_SCENARIOS[@]}")
# Store scenarios that are portable to the Bukkit platforms but stay OUT of every 'all'
# list. store-second-join is the only scenario that actually creates store DEMAND — it
# clearcaches mid-session so the re-serve wave must come from the store — and its steps are
# plain vanilla commands with a Paper twin for the probe recorder (PaperSoakProbeBridge), so
# nothing in it was ever Fabric-specific; it simply was never added to the Bukkit lists.
# That mattered once the Folia store question came up: warm-rejoin, the closest scenario in
# the Folia set, keeps the client cache and so answers up_to_date instead of serving, which
# means the Folia set could not exercise a store SERVE at all.
STORE_STANDALONE_SCENARIOS=(store-second-join)
# Phases of scripts/store_offline_edit.sh (populate -> offline mutate -> verify, chained
# via SOAK_WORLD_FROM). The store-offline trio is valid standalone on fabric AND
# paper, but excluded from every 'all' list: mutate/verify are meaningless without
# the carried world.
PHASE_SCENARIOS=(store-offline-populate store-offline-mutate store-offline-verify)
# FABRIC-ONLY phase-2 scenarios (final panel: these must NOT inherit the Paper
# allowance PHASE_SCENARIOS carries — their wrapper chains refuse non-fabric, the
# eviction rm targets the Fabric world layout, and Bukkit's split world dirs break
# the world-carry): evicted-tscache-rejoin is phase 2 of scripts/summary_evicted.sh;
# stamp-heal-rejoin is phase 2 of scripts/stamp_heal.sh. stamp-heal-prime (phase 1)
# is standalone-runnable — its own named check pins the UNHEALED before-state — but
# stays out of 'all' with its chain.
FABRIC_PHASE_SCENARIOS=(evicted-tscache-rejoin stamp-heal-rejoin stamp-heal-prime)
# Paper-only, AFTER the Folia copy above so Folia does not inherit it (the store is
# unvalidated on Folia): console setblock fires no Bukkit event, so only the store's
# periodic resweep (lodStoreResweepSeconds) can catch the edit — the unfired-event
# staleness-bound gate (lod-store-implementation-plan.md Phase 2).
PAPER_SCENARIOS+=(paper-store-unfired-event)
# Scenarios that run on a fresh (deleted) world; everything else copies the base world.
FRESH_WORLD_SCENARIOS="fresh-backfill rate-limit-storm generation-disabled generation-capacity-stress hybrid-boundary"
LOG_PREFIX="soak"

# Exported so 'all' recursion and auto-run fresh-backfill inherit the platform.
SOAK_PLATFORM="${SOAK_PLATFORM:-fabric}"
export SOAK_PLATFORM
case "$SOAK_PLATFORM" in
    fabric)
        SERVER_RUN_DIR="$PROJECT_ROOT/fabric/build/run/soak-server"
        SERVER_GRADLE_TASK=":fabric:runSoakServer"
        SERVER_CONFIG_DIR="$SERVER_RUN_DIR/config"
        BASE_WORLD_DIR="$WORLDS_DIR/base"
        PLATFORM_TAG=""
        SERVER_READY_TIMEOUT=120
        ;;
    paper)
        SERVER_RUN_DIR="$PROJECT_ROOT/paper/build/run/soak-server"
        SERVER_GRADLE_TASK=":paper:runSoakServer"
        # PaperConfig loads from the plugin data folder (plugin.yml name: LodServerSupport)
        SERVER_CONFIG_DIR="$SERVER_RUN_DIR/plugins/LodServerSupport"
        BASE_WORLD_DIR="$WORLDS_DIR/base-paper"
        PLATFORM_TAG="paper-"
        # run-paper downloads the Paper server jar inside the gradle task on first run
        SERVER_READY_TIMEOUT=240
        ;;
    folia)
        SERVER_RUN_DIR="$PROJECT_ROOT/paper/build/run/folia-soak-server"
        SERVER_GRADLE_TASK=":paper:runFolia"
        SERVER_CONFIG_DIR="$SERVER_RUN_DIR/plugins/LodServerSupport"
        BASE_WORLD_DIR="$WORLDS_DIR/base-folia"
        PLATFORM_TAG="folia-"
        # run-paper downloads the Folia server jar inside the gradle task on first run
        SERVER_READY_TIMEOUT=240
        ;;
    *)
        echo "[soak] ERROR: Unknown SOAK_PLATFORM '$SOAK_PLATFORM' (want fabric, paper or folia)"
        exit 1
        ;;
esac

# Base worlds are MC-version-specific; fresh-backfill stamps this marker when it saves one.
# The version comes from gradle.properties so the guard and the stamp can never drift
# apart (a drifted pair silently clears the base world on EVERY run).
# (V-1/T3a — reverse-flow: this guard was independently re-invented on four support
# branches; a base world from another line silently invalidates a soak.)
MC_LINE_VERSION=$(grep -oP '^minecraft_version=\K.*' "$PROJECT_ROOT/gradle.properties")
WORLD_VERSION_MARKER="$BASE_WORLD_DIR/mc-version"

source "$PROJECT_ROOT/scripts/lib/mc-run.sh"

usage() {
    echo "Usage: [SOAK_PLATFORM=fabric|paper|folia] $0 <scenario>|all"
    echo "  fabric scenarios: ${ALL_SCENARIOS[*]}"
    echo "  paper scenarios:  ${PAPER_SCENARIOS[*]}"
    echo "  folia scenarios:  ${FOLIA_SCENARIOS[*]}"
}

if [[ -z "$SCENARIO" ]]; then
    usage
    exit 1
fi

# 'all' runs every scenario in spec order; set -e stops at the first failure
# and propagates the failing child's exit code.
if [[ "$SCENARIO" == "all" ]]; then
    if [[ "$SOAK_PLATFORM" == "paper" ]]; then
        for s in "${PAPER_SCENARIOS[@]}"; do
            "$SELF" "$s"
        done
    elif [[ "$SOAK_PLATFORM" == "folia" ]]; then
        for s in "${FOLIA_SCENARIOS[@]}"; do
            "$SELF" "$s"
        done
    else
        for s in "${ALL_SCENARIOS[@]}"; do
            "$SELF" "$s"
        done
    fi
    echo "[soak] All scenarios passed ($SOAK_PLATFORM)"
    exit 0
fi

case "$SCENARIO" in
    fresh-backfill|warm-rejoin|dimension-trip|dirty-broadcast) ;;
    rate-limit-storm|disk-saturation|disk-read-gate|generation-disabled|generation-capacity-stress|bandwidth-throttle) ;;
    cold-restart-resync|enabled-false|teleport-prune|dirty-range-filter) ;;
    dirty-during-backfill|dirty-while-offline|clearcache-mid-session|dimension-rejoin-warm) ;;
    store-second-join) ;;
    store-offline-populate|store-offline-mutate|store-offline-verify) ;;
    store-migration-join) ;;
    store-save-storm|store-save-storm-off) ;;
    warm-rejoin-summary|dirty-while-offline-summary|evicted-tscache-rejoin) ;;
    stamp-heal-prime|stamp-heal-rejoin) ;;
    hybrid-boundary) ;;
    paper-dirty-falling-block|paper-store-unfired-event) ;;
    *)
        echo "[soak] ERROR: Unknown scenario '$SCENARIO'"
        usage
        exit 1
        ;;
esac

# Platform gating: the Paper port covers a validated subset; the falling-block scenario is
# Paper-native (setblock fires no Bukkit event, and Fabric's save-hook detection would need
# a save-all the timeline deliberately omits).
if [[ "$SOAK_PLATFORM" != "fabric" && " ${FABRIC_PHASE_SCENARIOS[*]} " == *" $SCENARIO "* ]]; then
    echo "[soak] ERROR: '$SCENARIO' is a Fabric-only chain phase (its wrapper script and"
    echo "        world-carry paths assume the Fabric world layout) — run its chain on fabric"
    exit 1
fi
if [[ "$SOAK_PLATFORM" == "paper" && " ${PAPER_SCENARIOS[*]} ${PHASE_SCENARIOS[*]} ${STORE_STANDALONE_SCENARIOS[*]} " != *" $SCENARIO "* ]]; then
    echo "[soak] ERROR: Scenario '$SCENARIO' is not ported to SOAK_PLATFORM=paper"
    usage
    exit 1
fi
if [[ "$SOAK_PLATFORM" == "folia" && " ${FOLIA_SCENARIOS[*]} ${STORE_STANDALONE_SCENARIOS[*]} " != *" $SCENARIO "* ]]; then
    echo "[soak] ERROR: Scenario '$SCENARIO' is not ported to SOAK_PLATFORM=folia"
    usage
    exit 1
fi
if [[ "$SOAK_PLATFORM" == "fabric" && "$SCENARIO" == paper-* ]]; then
    echo "[soak] ERROR: Scenario '$SCENARIO' requires SOAK_PLATFORM=paper (or folia)"
    usage
    exit 1
fi

# Per-scenario knobs: number of client runs, expected end-to-end seconds, and extra
# gradle -P properties for the CLIENT JVM (per-position probes / scripted client action).
# Kill switch budget = expected + 240s slack.
CLIENT_EXTRA_ARGS=()
SERVER_EXTRA_ARGS=()
case "$SCENARIO" in
    fresh-backfill)             CLIENT_RUNS=1; EXPECTED_SECONDS=300 ;;
    warm-rejoin)                CLIENT_RUNS=2; EXPECTED_SECONDS=360 ;;
    dimension-trip)             CLIENT_RUNS=1; EXPECTED_SECONDS=440 ;;
    dirty-broadcast)            CLIENT_RUNS=1; EXPECTED_SECONDS=270 ;;
    rate-limit-storm)           CLIENT_RUNS=1; EXPECTED_SECONDS=370 ;;
    disk-saturation)            CLIENT_RUNS=1; EXPECTED_SECONDS=250 ;;
    # K=1 over the prebuilt annulus: with the park list feeding the permit, healthy IO
    # converges in seconds; degraded WSL2 IO (107-131 ms/read) serializes ~2112 reads
    # to ~4-5 min — the 400 s timeline budgets that plus the >=25 s converged tail
    # (v1.3 sizing decision + the stage-B park deviation's margin).
    disk-read-gate)             CLIENT_RUNS=1; EXPECTED_SECONDS=450 ;;
    generation-disabled)        CLIENT_RUNS=1; EXPECTED_SECONDS=230 ;;
    generation-capacity-stress) CLIENT_RUNS=1; EXPECTED_SECONDS=330 ;;
    hybrid-boundary)            CLIENT_RUNS=1; EXPECTED_SECONDS=1800 ;;
    bandwidth-throttle)         CLIENT_RUNS=1; EXPECTED_SECONDS=290 ;;
    cold-restart-resync)        CLIENT_RUNS=1; EXPECTED_SECONDS=280 ;;
    enabled-false)              CLIENT_RUNS=1; EXPECTED_SECONDS=230 ;;
    teleport-prune)             CLIENT_RUNS=1; EXPECTED_SECONDS=470 ;;
    dirty-range-filter)         CLIENT_RUNS=1; EXPECTED_SECONDS=350 ;;
    dirty-during-backfill)      CLIENT_RUNS=1; EXPECTED_SECONDS=240 ;;
    dirty-while-offline)        CLIENT_RUNS=2; EXPECTED_SECONDS=420
                                CLIENT_EXTRA_ARGS=("-Psoak.probes=20:0,-20:0") ;;
    warm-rejoin-summary)        CLIENT_RUNS=2; EXPECTED_SECONDS=470
                                # Region summaries (region-summary-sync-plan.md §8): run 1
                                # generates the 9-tile disc around mid-tile chunk (16,16),
                                # a t130 save-all settles EVERY pending header (Paper's
                                # distributed autosave otherwise trickles gen saves past
                                # the re-stamp window — 6 stale tiles on the first Paper
                                # run; Folia maps save-all to a no-op but its 100-tick
                                # autosave settles the same way), then the 160s clearcache
                                # re-serves the disc so the cached stamps CLEAR the
                                # freshness margin (a serve-then-save stamp never can).
                                # Run 2 (too short for the action to re-fire) validates
                                # the disc off one summary frame; the t195 setblock in
                                # the player tile is the EXPLICIT poison for the honesty
                                # leg — Fabric's kick re-saves inhabitedTime-dirty chunks
                                # but Paper's kick writes nothing after the save-all
                                # (recorded: the whole disc validated, stale=0), so the
                                # stale tile must come from a real edit, not from
                                # platform save behavior.
                                CLIENT_EXTRA_ARGS=("-Psoak.summary=true"
                                                   "-Psoak.clientActionAt=160:clearcache") ;;
    dirty-while-offline-summary) CLIENT_RUNS=2; EXPECTED_SECONDS=480
                                # The false-clean canary: warm-rejoin-summary's shape plus
                                # an offline edit in chunk (36,-4) (tile (1,-1)). The edited
                                # tile must NOT validate (probe 36:-4 rises) while the
                                # control tile does (probe -4:36 stays exactly equal).
                                CLIENT_EXTRA_ARGS=("-Psoak.summary=true"
                                                   "-Psoak.clientActionAt=160:clearcache"
                                                   "-Psoak.probes=36:-4,-4:36") ;;
    evicted-tscache-rejoin)     CLIENT_RUNS=1; EXPECTED_SECONDS=260
                                # P1 header-rung live gate (chained phase 2 — run via
                                # scripts/summary_evicted.sh, which carries the
                                # warm-rejoin-summary world forward): a fresh server boot
                                # with world/data/lss-timestamps.bin DELETED re-resolves the
                                # whole-disc ts>0 re-declare through the region-header rung
                                # (disk.header_hits) instead of a full re-download.
                                ;;
    stamp-heal-prime)           CLIENT_RUNS=2; EXPECTED_SECONDS=470
                                # Phase 1 of scripts/stamp_heal.sh (3-Opus fold: the
                                # heal gate needs an UNHEALED before-state, and
                                # warm-rejoin-summary's clearcache re-stamp erases it):
                                # warm-rejoin-summary WITHOUT the clearcache and
                                # WITHOUT the poison — run 1's stamps stay
                                # serve-then-save, so run 2's frame finds the BULK
                                # stale (the before-pin), re-asks it, and the
                                # up_to_date answers RATCHET the carried cache.
                                CLIENT_EXTRA_ARGS=("-Psoak.summary=true") ;;
    stamp-heal-rejoin)          CLIENT_RUNS=1; EXPECTED_SECONDS=260
                                # Stamped-up_to_date heal gate (chained phase 2 — run via
                                # scripts/stamp_heal.sh, which carries the
                                # stamp-heal-prime world AND client cache forward):
                                # phase 1 PINNED the bulk stale (before), its run-2
                                # up_to_date answers ratcheted the cached stamps, so THIS
                                # rejoin's frame must validate the once-stale bulk
                                # (stale -> stamped -> clean, both halves pinned).
                                CLIENT_EXTRA_ARGS=("-Psoak.summary=true") ;;
    clearcache-mid-session)     CLIENT_RUNS=1; EXPECTED_SECONDS=280
                                CLIENT_EXTRA_ARGS=("-Psoak.clientActionAt=60:clearcache") ;;
    store-second-join)
                                CLIENT_RUNS=1; EXPECTED_SECONDS=280
                                # The Phase 1/2 LOD-store gate (lodStore=full): backfill
                                # populates the store, the clearcache forces the full
                                # ts<=0 re-declaration, the checker requires the re-serve
                                # wave to be STORE HITS with byte-identical probe hashes.
                                # Absorbed the old store-second-join-full twin when
                                # lodStore=memory was retired (2026-08-02) — same
                                # timeline, and this arm now IS the SQLite one.
                                CLIENT_EXTRA_ARGS=("-Psoak.clientActionAt=60:clearcache")
                                SERVER_EXTRA_ARGS=("-Psoak.probes=20:0,-20:0") ;;
    dimension-rejoin-warm)      CLIENT_RUNS=2; EXPECTED_SECONDS=650 ;;
    store-offline-populate)     CLIENT_RUNS=1; EXPECTED_SECONDS=280
                                SERVER_EXTRA_ARGS=("-Psoak.probes=20:0,-20:0") ;;
    store-offline-mutate)       CLIENT_RUNS=1; EXPECTED_SECONDS=160 ;;
    store-save-storm)           CLIENT_RUNS=1; EXPECTED_SECONDS=320
                                # Phase 3: save-hook deposits under an autosave storm;
                                # the clearcache re-serve must come from the store with
                                # the edit's fresh bytes (see check_store_save_storm).
                                CLIENT_EXTRA_ARGS=("-Psoak.clientActionAt=130:clearcache")
                                SERVER_EXTRA_ARGS=("-Psoak.probes=20:0,-20:0") ;;
    store-save-storm-off)       CLIENT_RUNS=1; EXPECTED_SECONDS=320
                                # store_save_storm.sh's MSPT pairing arm (lodStore off).
                                SERVER_EXTRA_ARGS=("-Psoak.probes=20:0,-20:0") ;;
    store-offline-verify)       CLIENT_RUNS=1; EXPECTED_SECONDS=280
                                SERVER_EXTRA_ARGS=("-Psoak.probes=20:0,-20:0") ;;
    store-migration-join)       CLIENT_RUNS=1; EXPECTED_SECONDS=280
                                # Overlap hold (pre-D3 review L3-1): keep the walk
                                # parked past the join so warm serves provably hit
                                # 19-rows; 0 disables (SOAK_MIGRATION_HOLD_SECONDS).
                                SERVER_EXTRA_ARGS=("-Psoak.migrationHoldSeconds=${SOAK_MIGRATION_HOLD_SECONDS:-0}") ;;
    paper-dirty-falling-block)  CLIENT_RUNS=1; EXPECTED_SECONDS=300 ;;
    paper-store-unfired-event)  CLIENT_RUNS=1; EXPECTED_SECONDS=320
                                # Backfill charges the store; the un-evented setblock +
                                # save-all go stale-invisible; two 10 s resweep cycles
                                # later the clearcache re-declare must get FRESH bytes.
                                CLIENT_EXTRA_ARGS=("-Psoak.clientActionAt=120:clearcache")
                                SERVER_EXTRA_ARGS=("-Psoak.probes=20:0,-20:0"
                                                   "-Psoak.dirtyTrace=${SOAK_DIRTY_TRACE:-false}") ;;
esac
RUNTIME_BUDGET=$((EXPECTED_SECONDS + 240))
DEADLINE_EPOCH=0

SCENARIO_JSON="$SCENARIOS_DIR/$SCENARIO.json"
SCENARIO_CONFIG="$SCENARIOS_DIR/$SCENARIO-config.json"
for f in "$SCENARIO_JSON" "$SCENARIO_CONFIG"; do
    if [[ ! -f "$f" ]]; then
        echo "[soak] ERROR: Missing scenario file: $f"
        exit 1
    fi
done

trap mc_cleanup EXIT

# Hard ceiling on total scenario runtime, armed once the server is ready.
soak_check_deadline() {
    if [[ "$DEADLINE_EPOCH" -gt 0 ]] && (( $(date +%s) >= DEADLINE_EPOCH )); then
        echo "[soak] ERROR: Runtime exceeded ${RUNTIME_BUDGET}s budget (expected ~${EXPECTED_SECONDS}s + 240s slack), killing server and client"
        [[ -n "$SERVER_PID" ]] && kill "$SERVER_PID" 2>/dev/null || true
        [[ -n "$CLIENT_PID" ]] && kill "$CLIENT_PID" 2>/dev/null || true
        exit 1
    fi
}

soak_port_in_use() {
    if command -v ss >/dev/null 2>&1; then
        [[ -n "$(ss -ltn 2>/dev/null | awk '$4 ~ /:25565$/')" ]]
    else
        # LISTEN state only ($4 == 0A) — matching any state falsely flags the previous
        # scenario's just-halted server whose sockets linger in TIME_WAIT for ~60s.
        [[ -n "$(awk '$4 == "0A" && $2 ~ /:63[Dd][Dd]$/' /proc/net/tcp /proc/net/tcp6 2>/dev/null)" ]]
    fi
}

echo "========================================="
echo " LSS Soak: platform=$SOAK_PLATFORM, scenario=$SCENARIO, client runs=$CLIENT_RUNS, budget=${RUNTIME_BUDGET}s"
echo "========================================="

# Step 1: Auto-run fresh-backfill first if a base world is required but missing
# (fresh-world scenarios never need it). cold-restart-resync additionally needs the
# client-cache snapshot taken at the same instant as the base world — a base world
# saved by an older fresh-backfill (pre-snapshot) must be regenerated.
if [[ " $FRESH_WORLD_SCENARIOS " != *" $SCENARIO "* ]]; then
    # Another line's base world will not downgrade (MC refuses newer-DataVersion worlds).
    # Clear a stale base so the '! -d .../world' check below regenerates it naturally;
    # unstamped pre-marker bases also clear once and regenerate. (V-1/T3a.)
    if [[ -d "$BASE_WORLD_DIR" && "$(cat "$WORLD_VERSION_MARKER" 2>/dev/null)" != "$MC_LINE_VERSION" ]]; then
        echo "[soak] Base world at $BASE_WORLD_DIR is not for MC $MC_LINE_VERSION — clearing (will re-run fresh-backfill)"
        rm -rf "$BASE_WORLD_DIR"
    fi
    if [[ ! -d "$BASE_WORLD_DIR/world" ]]; then
        echo "[soak] No base world at $BASE_WORLD_DIR/world — running fresh-backfill first"
        "$SELF" fresh-backfill
    elif [[ "$SCENARIO" == "cold-restart-resync" && ! -d "$BASE_WORLD_DIR/client-cache" ]]; then
        echo "[soak] Base world has no client-cache snapshot — re-running fresh-backfill"
        "$SELF" fresh-backfill
    fi
fi

# Step 2: Pre-flight — validate the scenario timeline before anything boots
echo "[soak] Validating scenario..."
python3 "$PROJECT_ROOT/scripts/check_soak.py" --validate "$SCENARIO"

# Step 3: Build (the soak client is always the Fabric client; paper/folia additionally need
# the dev plugin jar that retains the soak package)
echo "[soak] Building mod..."
cd "$PROJECT_ROOT"
./gradlew :fabric:build -x test -x runGameTest --quiet
if [[ "$SOAK_PLATFORM" == "paper" || "$SOAK_PLATFORM" == "folia" ]]; then
    ./gradlew :paper:soakShadowJar --quiet
fi

# Step 4: Prepare run + results directories
mkdir -p "$SERVER_RUN_DIR" "$CLIENT_RUN_DIR"
RUN_RESULTS_DIR="$RESULTS_ROOT/$SCENARIO-$PLATFORM_TAG$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$RUN_RESULTS_DIR"

# Step 5a: Stage world. Fresh-world scenarios start from nothing (generation paths);
# only fresh-backfill SAVES its world as the reusable base afterwards (Step 13).
# 1.21.x line: Bukkit platforms (Paper/Folia) use the legacy split
# world_nether/world_the_end layout; Fabric keeps a single world/ (dedicated-server
# DIM-1/DIM1 nest inside it) — the world* glob covers both, so the End round-trips on
# every platform. (The v0.10 line carried exactly this flavor; the v0.11 port lost it —
# review 2026-08-15. The mc-version marker half back-flowed to main; this layout half is
# per-line forever.)
echo "[soak] Staging world for scenario: $SCENARIO"
rm -rf "$SERVER_RUN_DIR"/world "$SERVER_RUN_DIR"/world_nether "$SERVER_RUN_DIR"/world_the_end
if [[ -n "${SOAK_WORLD_FROM:-}" ]]; then
    # Multi-phase orchestrators (scripts/store_offline_edit.sh) carry a prior phase's
    # world forward instead of restaging the base. The dir must contain world/.
    if [[ ! -d "$SOAK_WORLD_FROM/world" ]]; then
        echo "[soak] ERROR: SOAK_WORLD_FROM=$SOAK_WORLD_FROM has no world/ dir"
        exit 1
    fi
    echo "[soak] Staging world from SOAK_WORLD_FROM=$SOAK_WORLD_FROM"
    cp -r "$SOAK_WORLD_FROM"/world* "$SERVER_RUN_DIR"/
elif [[ " $FRESH_WORLD_SCENARIOS " != *" $SCENARIO "* ]]; then
    cp -r "$BASE_WORLD_DIR"/world* "$SERVER_RUN_DIR"/
fi
if [[ "$SCENARIO" == "stamp-heal-rejoin" && -z "${SOAK_WORLD_FROM:-}" ]]; then
    echo "[soak] ERROR: stamp-heal-rejoin is phase 2 of scripts/stamp_heal.sh"
    echo "[soak]        (needs the stamp-heal-prime world carried via SOAK_WORLD_FROM —"
    echo "[soak]        the heal premise is phase 1's STAMPED client cache against that"
    echo "[soak]        exact world's headers; tscache and lss-timestamps.bin stay INTACT,"
    echo "[soak]        unlike the evicted chain)"
    exit 1
fi
if [[ "$SCENARIO" == "evicted-tscache-rejoin" ]]; then
    if [[ -z "${SOAK_WORLD_FROM:-}" ]]; then
        echo "[soak] ERROR: evicted-tscache-rejoin is phase 2 of scripts/summary_evicted.sh"
        echo "[soak]        (needs the warm-rejoin-summary world carried via SOAK_WORLD_FROM"
        echo "[soak]        — the plain base world's headers all postdate its cached stamps,"
        echo "[soak]        so the header rung would legitimately answer nothing)"
        exit 1
    fi
    # The P1 premise: boot with an EMPTY timestamp cache so every ts>0 re-declare
    # falls through to the region-header freshness rung instead of the tscache.
    rm -f "$SERVER_RUN_DIR/world/data/lss-timestamps.bin"
    echo "[soak] evicted-tscache-rejoin: deleted world/data/lss-timestamps.bin (empty-tscache boot)"
fi

# Step 5b: Stage client column cache. warm-rejoin clears too: its run 1 IS the
# cache-populating run (otherwise, under 'all' ordering, run 1 starts warm from the
# previous scenario's cache and the run1-vs-run2 named check has nothing to compare).
# The contention/generation-config/bandwidth scenarios clear so every position goes
# through the live request path instead of resolving as a cached revalidation.
# cold-restart-resync RESTORES the snapshot taken alongside the base world: the client
# cache and the world's data/lss-timestamps.bin were persisted at the same instant, so
# a brand-new server JVM must resync this client almost entirely via up_to_date.
# Provenance guard: the cache is keyed only by server address (localhost_25565), which
# ALL platforms share — a kept cache populated against another platform's base world
# carries stamps from a different world's clock and fails the warm-path expectations.
# Stage D (cache relocation): the client cache may live at EITHER root — the legacy
# config/lss/cache (adopted when it exists) or the game-root .lss/cache (fresh run
# dirs). Clearing must cover both or a stale cache at the other root warms a run that
# expects cold. NOTE the cold-restart-resync restore deliberately creates
# config/lss/cache: under the adoption rule that FORCES the legacy root for the run,
# which is load-bearing — the restored cache must be the one the client actually opens.
LEGACY_CACHE_DIR="$CLIENT_RUN_DIR/config/lss/cache"
DOTLSS_CACHE_DIR="$CLIENT_RUN_DIR/.lss/cache"
CACHE_PLATFORM_MARKER="$CLIENT_RUN_DIR/config/lss/cache-platform"
if [[ -f "$CACHE_PLATFORM_MARKER" && "$(cat "$CACHE_PLATFORM_MARKER")" != "$SOAK_PLATFORM" ]]; then
    echo "[soak] Client cache was populated on platform '$(cat "$CACHE_PLATFORM_MARKER")' — clearing for $SOAK_PLATFORM"
    rm -rf "$LEGACY_CACHE_DIR" "$DOTLSS_CACHE_DIR"
fi
case "$SCENARIO" in
    dirty-broadcast)
        echo "[soak] Keeping client column cache"
        ;;
    evicted-tscache-rejoin)
        # Phase 2 of summary_evicted.sh: the cache carries the phase-1 clearcache
        # re-serve stamps — clearing it would turn the run into a cold resync and the
        # header rung would never be consulted (ts<=0 declares skip it by design).
        echo "[soak] Keeping client column cache (carried from warm-rejoin-summary)"
        ;;
    stamp-heal-rejoin)
        # Phase 2 of stamp_heal.sh: the cache carries phase 1's RATCHETED stamps —
        # the entire heal premise. Clearing it would make the run a cold resync.
        echo "[soak] Keeping client column cache (carried, ratcheted stamps)"
        ;;
    cold-restart-resync)
        echo "[soak] Restoring client column cache from $BASE_WORLD_DIR/client-cache"
        rm -rf "$LEGACY_CACHE_DIR" "$DOTLSS_CACHE_DIR"
        mkdir -p "$CLIENT_RUN_DIR/config/lss"
        cp -r "$BASE_WORLD_DIR/client-cache" "$LEGACY_CACHE_DIR"
        ;;
    *)
        echo "[soak] Clearing client column cache"
        rm -rf "$LEGACY_CACHE_DIR" "$DOTLSS_CACHE_DIR"
        ;;
esac
mkdir -p "$CLIENT_RUN_DIR/config/lss"
printf '%s' "$SOAK_PLATFORM" > "$CACHE_PLATFORM_MARKER"

# Step 6a: Stage server config override (fabric: config/; paper: the plugin data folder)
mkdir -p "$SERVER_CONFIG_DIR"
cp "$SCENARIO_CONFIG" "$SERVER_CONFIG_DIR/lss-server-config.json"
# C2 legacy-dialect lever: SOAK_DIALECT=19 makes the soak CLIENT emulate a
# protocol-19 install (announce 19, accept the 19 echo, skip the v20 decode
# translation) so the run exercises the server's legacy egress translators
# end-to-end — every conservation law then runs against translated bodies.
if [[ -n "${SOAK_DIALECT:-}" ]]; then
    CLIENT_EXTRA_ARGS+=("-Psoak.dialect=${SOAK_DIALECT}")
    echo "[soak] SOAK_DIALECT=${SOAK_DIALECT}: client emulates a protocol-${SOAK_DIALECT} install"
fi

# Phase 5 burn-in lever: SOAK_LODSTORE_OVERRIDE=full merges the store into EVERY
# scenario's staged config (the laws are store-aware; named checks are engine-blind).
# SOAK_LODSTORE_BACKFILL_OVERRIDE independently forces the backfill, because every
# scenario config now PINS lodStoreBackfill (the 19 pre-store ones to keep their
# pre-store baselines, the store ones so their paired-CPU arms are not contaminated by
# an unpaired region walk). Without this second lever the override could only ever
# produce store-on/backfill-off — which is not the shipped default, so the suite could
# not be run "against the shipped defaults" as intended. (v0.9.0 review.)
if [[ -n "${SOAK_LODSTORE_OVERRIDE:-}" || -n "${SOAK_LODSTORE_BACKFILL_OVERRIDE:-}" ]]; then
    python3 - "$SERVER_CONFIG_DIR/lss-server-config.json" \
        "${SOAK_LODSTORE_OVERRIDE:-}" "${SOAK_LODSTORE_BACKFILL_OVERRIDE:-}" <<'PYEOF'
import json, sys
path, mode, backfill = sys.argv[1], sys.argv[2], sys.argv[3]
cfg = json.load(open(path))
applied = []
if mode:
    cfg["lodStore"] = mode
    applied.append(f"lodStore={mode}")
if backfill:
    cfg["lodStoreBackfill"] = backfill.lower() in ("1", "true", "yes", "on")
    applied.append(f"lodStoreBackfill={cfg['lodStoreBackfill']}")
json.dump(cfg, open(path, "w"), indent=2)
print("[soak] SOAK_LODSTORE override: " + ", ".join(applied) + " merged into the staged config")
PYEOF
fi

# Step 6b: Write server.properties + eula.txt. Superflat: fresh noise terrain carries
# minutes of unsettled fluid ticks (aquifers, gen-border flows) that mutate chunk content
# on every save cycle and keep the system from ever quiescing — flat terrain settles
# instantly and the conservation laws don't care about terrain shape. Structures must be
# OFF: the classic flat preset generates villages, and live villagers toggle doors and
# path around (real block-state changes the content filter CORRECTLY marks dirty) —
# whether a village churns during a quiet window depends on the villager state captured
# at base-world save time, i.e. nondeterministic across base rebuilds.
cat > "$SERVER_RUN_DIR/server.properties" <<'PROPS'
online-mode=false
level-seed=soak-seed-42
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
max-tick-time=-1
pause-when-empty-seconds=-1
view-distance=8
gamemode=creative
force-gamemode=true
PROPS

echo "eula=true" > "$SERVER_RUN_DIR/eula.txt"

# Folia: save-all is unregistered (the driver maps it to an acknowledged no-op), so flush
# chunks continuously — every 100 ticks — to keep mid-run disk state close to what the
# shared timelines assume; the end-of-scenario halt performs a full save regardless.
if [[ "$SOAK_PLATFORM" == "folia" ]]; then
    cat > "$SERVER_RUN_DIR/bukkit.yml" <<'BUKKIT'
ticks-per:
  autosave: 100
BUKKIT
fi

# Step 6c: Write client options.txt to bypass first-launch screens and pin render distance
cat > "$CLIENT_RUN_DIR/options.txt" <<'OPTS'
onboardAccessibility:false
skipMultiplayerWarning:true
joinedFirstServer:true
renderDistance:8
soundCategory_master:0.0
OPTS

# Step 7: Clear stale server log and stale soak-results from previous runs
rm -f "$SERVER_RUN_DIR/logs/latest.log"
rm -rf "$SERVER_RUN_DIR/soak-results" "$CLIENT_RUN_DIR/soak-results"

# Step 8: Pre-flight — refuse to start on top of a stale server
if soak_port_in_use; then
    echo "[soak] ERROR: Port 25565 is already in use — a stale dev server is likely still running."
    echo "[soak] Stop it first, e.g.: pkill -f net.fabricmc.devlaunchinjector"
    exit 1
fi

# Step 9: Start server and arm the kill switch once it is ready
mc_start_server "$RUN_RESULTS_DIR/server.log" "$SERVER_GRADLE_TASK" -Psoak.scenario="$SCENARIO_JSON" ${SERVER_EXTRA_ARGS[@]+"${SERVER_EXTRA_ARGS[@]}"} ${SOAK_EXTRA_GRADLE_ARGS:-}
mc_wait_server_ready "$SERVER_RUN_DIR/logs/latest.log" "$RUN_RESULTS_DIR/server.log" "$SERVER_READY_TIMEOUT"
DEADLINE_EPOCH=$(( $(date +%s) + RUNTIME_BUDGET ))

# Step 9b: 1 Hz CPU/RSS/wire sampler (LOD-store plan §5 Phase 0 (c) — the store's CPU
# gates need Paper/Folia samples, and benchmark.sh is Fabric-only). The soak server JVM
# is discovered via -Dlss.soak.scenario (every platform's soak run task carries it). The
# sampler self-terminates when the server JVM disappears; the explicit kill at collect is
# belt-and-braces. Analysis lives with the profile tooling (cpu.jsonl schema unchanged).
PROC_SAMPLER_SRV_PATTERN='Dlss\.soak\.scenario' \
    "$PROJECT_ROOT/scripts/lib/proc_sampler.sh" "$RUN_RESULTS_DIR/cpu.jsonl" $((RUNTIME_BUDGET + 300)) &
SAMPLER_PID=$!
# Kill the sampler on ANY exit (review B14): a set -e exit between here and the
# Step 12 kill left it alive for RUNTIME_BUDGET+300 s, and its pattern matches any
# soak server — an orphan latched onto the NEXT scenario's JVM and appended to the
# previous run's cpu.jsonl.
trap 'kill "$SAMPLER_PID" 2>/dev/null || true; mc_cleanup' EXIT

# Step 10: Client runs (the server kicks the client between runs / halts at scenario end)
for (( run=1; run<=CLIENT_RUNS; run++ )); do
    echo "[soak] Client run $run/$CLIENT_RUNS"
    mc_start_client "$RUN_RESULTS_DIR/client-run$run.log" :fabric:runSoakClient ${CLIENT_EXTRA_ARGS[@]+"${CLIENT_EXTRA_ARGS[@]}"}
    while kill -0 "$CLIENT_PID" 2>/dev/null; do
        soak_check_deadline
        sleep 1
    done
    # Capture the client exit status instead of discarding it. A controlled disconnect halts
    # the client with 0 (BenchmarkHook writes its disconnect row, flushes the cache, then
    # halt(0)); a crash exits nonzero AND skips the disconnect row. This log is diagnostic —
    # the hard gate is the checker's per-run disconnect-row requirement, so a spurious gradle
    # exit code never false-reds a run here.
    if wait "$CLIENT_PID" 2>/dev/null; then
        CLIENT_EXIT=0
    else
        CLIENT_EXIT=$?
    fi
    CLIENT_PID=""
    if [[ "$CLIENT_EXIT" -ne 0 ]]; then
        echo "[soak] ERROR: Client run $run exited nonzero ($CLIENT_EXIT) — likely crashed before writing its disconnect row (the checker will flag the missing disconnect)"
    else
        echo "[soak] Client run $run exited cleanly"
    fi
    if [[ -f "$CLIENT_RUN_DIR/soak-results/client.jsonl" ]]; then
        mv "$CLIENT_RUN_DIR/soak-results/client.jsonl" "$CLIENT_RUN_DIR/soak-results/client-run$run.jsonl"
    else
        echo "[soak] WARNING: No client.jsonl found after run $run"
    fi
done

# Step 11: Wait for the server to halt itself at scenario end
echo "[soak] Waiting for server to halt..."
while kill -0 "$SERVER_PID" 2>/dev/null; do
    soak_check_deadline
    sleep 1
done
wait "$SERVER_PID" 2>/dev/null || true
SERVER_PID=""
echo "[soak] Server exited"

# Step 12: Collect results (gradle logs were written there directly)
kill "$SAMPLER_PID" 2>/dev/null || true
wait "$SAMPLER_PID" 2>/dev/null || true
echo "[soak] Collecting results into $RUN_RESULTS_DIR"
if [[ -f "$SERVER_RUN_DIR/soak-results/server.jsonl" ]]; then
    cp "$SERVER_RUN_DIR/soak-results/server.jsonl" "$RUN_RESULTS_DIR/server.jsonl"
else
    echo "[soak] WARNING: No server.jsonl found"
fi
cp "$CLIENT_RUN_DIR/soak-results/"client-run*.jsonl "$RUN_RESULTS_DIR/" 2>/dev/null \
    || echo "[soak] WARNING: No client jsonl files found"
cp "$SCENARIO_JSON" "$RUN_RESULTS_DIR/"

# Step 13: Save world for reuse (fresh-backfill only). The client column cache is
# snapshotted alongside it: the world copy carries data/lss-timestamps.bin (final save
# runs on server shutdown, before this copy), so world + client-cache form a mutually
# consistent warm pair that cold-restart-resync restores into a brand-new server JVM.
if [[ "$SCENARIO" == "fresh-backfill" && -d "$SERVER_RUN_DIR/world" ]]; then
    echo "[soak] Saving world to $BASE_WORLD_DIR/ for reuse"
    mkdir -p "$BASE_WORLD_DIR"
    # Remove ALL prior world dirs before copying: leaving a stale world_nether/world_the_end
    # in place would make the world* glob-copy nest world_nether/world_nether and silently
    # keep the STALE End/Nether in the snapshot (the split-world handling's exact failure).
    rm -rf "$BASE_WORLD_DIR"/world "$BASE_WORLD_DIR"/world_nether "$BASE_WORLD_DIR"/world_the_end
    cp -r "$SERVER_RUN_DIR"/world* "$BASE_WORLD_DIR"/
    printf '%s' "$MC_LINE_VERSION" > "$WORLD_VERSION_MARKER"
    # Stage D: collect from whichever root the client actually used this run — a
    # fresh run dir writes .lss/cache while a legacy dir adopts config/lss/cache;
    # checking only the old path would silently turn every warm scenario cold.
    COLLECT_CACHE_DIR=""
    if [[ -d "$CLIENT_RUN_DIR/config/lss/cache" ]]; then
        COLLECT_CACHE_DIR="$CLIENT_RUN_DIR/config/lss/cache"
    elif [[ -d "$CLIENT_RUN_DIR/.lss/cache" ]]; then
        COLLECT_CACHE_DIR="$CLIENT_RUN_DIR/.lss/cache"
    fi
    if [[ -n "$COLLECT_CACHE_DIR" ]]; then
        echo "[soak] Saving client column cache snapshot from $COLLECT_CACHE_DIR"
        rm -rf "$BASE_WORLD_DIR/client-cache"
        cp -r "$COLLECT_CACHE_DIR" "$BASE_WORLD_DIR/client-cache"
    else
        echo "[soak] WARNING: No client cache to snapshot (cold-restart-resync will re-run fresh-backfill)"
    fi
fi

# Step 14: Anomaly digest (a lens, not a gate — always written, never fails the run).
# Lets a reviewer skim each run for spikes/stalls/unexpected counters beyond pass/fail.
python3 "$PROJECT_ROOT/scripts/soak_report.py" "$RUN_RESULTS_DIR" > "$RUN_RESULTS_DIR/report.md" 2>&1 \
    && echo "[soak] Anomaly digest: $RUN_RESULTS_DIR/report.md" \
    || echo "[soak] WARNING: soak_report.py failed (non-fatal)"

# Step 15: Run the checker — its exit code is this script's exit code
echo "[soak] Running checker..."
# C6 negotiated-protocol assertion: every soak asserts the session's established
# dialect — SOAK_DIALECT when the lever is armed, else the native protocol read from
# LSSConstants. A lever run that silently degraded to another rung (e.g. 19 falling
# to the v16 fallback) used to PASS on format-blind laws; now it reds session-version.
NATIVE_PROTOCOL=$(grep -oE 'int PROTOCOL_VERSION = [0-9]+' \
    "$PROJECT_ROOT/common/src/main/java/dev/vox/lss/common/LSSConstants.java" | grep -oE '[0-9]+' || true)
if [[ -z "$NATIVE_PROTOCOL" ]]; then
    # Loud, not a silent fallback (C6 review m1): under set -e a failed grep used to
    # abort the script AFTER the whole run with an opaque exit and no verdict.
    echo "[soak] ERROR: cannot read PROTOCOL_VERSION from LSSConstants.java — fix the grep"
    exit 1
fi
if python3 "$PROJECT_ROOT/scripts/check_soak.py" "$RUN_RESULTS_DIR" "$SCENARIO" \
    --expect-session-version "${SOAK_DIALECT:-$NATIVE_PROTOCOL}" \
    --platform "$SOAK_PLATFORM"; then
    echo "[soak] PASS: $SCENARIO — results in $RUN_RESULTS_DIR"
else
    code=$?
    echo "[soak] FAIL: $SCENARIO (checker exit $code) — results in $RUN_RESULTS_DIR"
    exit "$code"
fi
