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
# on MC 1.21.11 Paper/Folia use the legacy split layout (world/, world_nether/,
# world_the_end/), while Fabric keeps a single world/ — the world* glob covers both, so the
# snapshot carries every dimension (including the End) on every platform.
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
               rate-limit-storm disk-saturation generation-disabled
               generation-capacity-stress bandwidth-throttle
               cold-restart-resync enabled-false teleport-prune
               dirty-range-filter dirty-during-backfill dirty-while-offline
               clearcache-mid-session dimension-rejoin-warm)
# Scenarios ported to Paper. The remaining ones are Fabric-specific for now: the dirty-*
# family leans on the save-hook + DirtyContentFilter (Paper's dirty detection is
# event-driven — paper-dirty-falling-block is the Paper-native dirty scenario),
# cold-restart-resync restores a Fabric-layout world/cache snapshot pair, and the
# stress/config scenarios simply haven't been validated on Paper yet.
PAPER_SCENARIOS=(fresh-backfill warm-rejoin dimension-trip paper-dirty-falling-block)
# Folia runs the identical Paper scenario set: same plugin jar, same timelines, same checker.
# save-all steps are mapped to acknowledged no-ops by the driver (Folia unregisters the
# command); an aggressive bukkit.yml autosave keeps chunks flushing mid-run instead.
FOLIA_SCENARIOS=("${PAPER_SCENARIOS[@]}")
# Scenarios that run on a fresh (deleted) world; everything else copies the base world.
FRESH_WORLD_SCENARIOS="fresh-backfill rate-limit-storm generation-disabled generation-capacity-stress"
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
    rate-limit-storm|disk-saturation|generation-disabled|generation-capacity-stress|bandwidth-throttle) ;;
    cold-restart-resync|enabled-false|teleport-prune|dirty-range-filter) ;;
    dirty-during-backfill|dirty-while-offline|clearcache-mid-session|dimension-rejoin-warm) ;;
    paper-dirty-falling-block) ;;
    *)
        echo "[soak] ERROR: Unknown scenario '$SCENARIO'"
        usage
        exit 1
        ;;
esac

# Platform gating: the Paper port covers a validated subset; the falling-block scenario is
# Paper-native (setblock fires no Bukkit event, and Fabric's save-hook detection would need
# a save-all the timeline deliberately omits).
if [[ "$SOAK_PLATFORM" == "paper" && " ${PAPER_SCENARIOS[*]} " != *" $SCENARIO "* ]]; then
    echo "[soak] ERROR: Scenario '$SCENARIO' is not ported to SOAK_PLATFORM=paper"
    usage
    exit 1
fi
if [[ "$SOAK_PLATFORM" == "folia" && " ${FOLIA_SCENARIOS[*]} " != *" $SCENARIO "* ]]; then
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
case "$SCENARIO" in
    fresh-backfill)             CLIENT_RUNS=1; EXPECTED_SECONDS=300 ;;
    warm-rejoin)                CLIENT_RUNS=2; EXPECTED_SECONDS=360 ;;
    dimension-trip)             CLIENT_RUNS=1; EXPECTED_SECONDS=440 ;;
    dirty-broadcast)            CLIENT_RUNS=1; EXPECTED_SECONDS=270 ;;
    rate-limit-storm)           CLIENT_RUNS=1; EXPECTED_SECONDS=370 ;;
    disk-saturation)            CLIENT_RUNS=1; EXPECTED_SECONDS=250 ;;
    generation-disabled)        CLIENT_RUNS=1; EXPECTED_SECONDS=230 ;;
    generation-capacity-stress) CLIENT_RUNS=1; EXPECTED_SECONDS=330 ;;
    bandwidth-throttle)         CLIENT_RUNS=1; EXPECTED_SECONDS=290 ;;
    cold-restart-resync)        CLIENT_RUNS=1; EXPECTED_SECONDS=280 ;;
    enabled-false)              CLIENT_RUNS=1; EXPECTED_SECONDS=230 ;;
    teleport-prune)             CLIENT_RUNS=1; EXPECTED_SECONDS=470 ;;
    dirty-range-filter)         CLIENT_RUNS=1; EXPECTED_SECONDS=350 ;;
    dirty-during-backfill)      CLIENT_RUNS=1; EXPECTED_SECONDS=240 ;;
    dirty-while-offline)        CLIENT_RUNS=2; EXPECTED_SECONDS=420
                                CLIENT_EXTRA_ARGS=("-Psoak.probes=20:0,-20:0") ;;
    clearcache-mid-session)     CLIENT_RUNS=1; EXPECTED_SECONDS=280
                                CLIENT_EXTRA_ARGS=("-Psoak.clientActionAt=60:clearcache") ;;
    dimension-rejoin-warm)      CLIENT_RUNS=2; EXPECTED_SECONDS=650 ;;
    paper-dirty-falling-block)  CLIENT_RUNS=1; EXPECTED_SECONDS=300 ;;
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

# Base worlds are MC-version-specific; a 26.1.2 world will not downgrade to 1.21.11. Clear a
# stale base BEFORE Step 1 so its '! -d "$BASE_WORLD_DIR/world"' check regenerates naturally
# (must run before that check — clearing after staging would boot 1.21.11 against a 26.1.2 world).
if [[ -d "$BASE_WORLD_DIR" && "$(cat "$WORLD_VERSION_MARKER" 2>/dev/null)" != "1.21.11" ]]; then
    echo "[soak] Base world at $BASE_WORLD_DIR is not for MC 1.21.11 — clearing (will re-run fresh-backfill)"
    rm -rf "$BASE_WORLD_DIR"
fi

# Step 1: Auto-run fresh-backfill first if a base world is required but missing
# (fresh-world scenarios never need it). cold-restart-resync additionally needs the
# client-cache snapshot taken at the same instant as the base world — a base world
# saved by an older fresh-backfill (pre-snapshot) must be regenerated.
if [[ " $FRESH_WORLD_SCENARIOS " != *" $SCENARIO "* ]]; then
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
./gradlew :fabric:build -x test -x runGameTest -x runClientGameTest --quiet
if [[ "$SOAK_PLATFORM" == "paper" || "$SOAK_PLATFORM" == "folia" ]]; then
    ./gradlew :paper:soakShadowJar --quiet
fi

# Step 4: Prepare run + results directories
mkdir -p "$SERVER_RUN_DIR" "$CLIENT_RUN_DIR"
RUN_RESULTS_DIR="$RESULTS_ROOT/$SCENARIO-$PLATFORM_TAG$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$RUN_RESULTS_DIR"

# Step 5a: Stage world. Fresh-world scenarios start from nothing (generation paths);
# only fresh-backfill SAVES its world as the reusable base afterwards (Step 13).
# 1.21.11 Bukkit platforms (Paper/Folia) use the legacy split world_nether/world_the_end
# layout; Fabric keeps a single world/ (dedicated-server DIM-1/DIM1 nest inside it) — the
# world* glob covers both, so the End round-trips on every platform.
echo "[soak] Staging world for scenario: $SCENARIO"
rm -rf "$SERVER_RUN_DIR"/world "$SERVER_RUN_DIR"/world_nether "$SERVER_RUN_DIR"/world_the_end
if [[ " $FRESH_WORLD_SCENARIOS " != *" $SCENARIO "* ]]; then
    cp -r "$BASE_WORLD_DIR"/world* "$SERVER_RUN_DIR"/
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
CACHE_PLATFORM_MARKER="$CLIENT_RUN_DIR/config/lss/cache-platform"
if [[ -f "$CACHE_PLATFORM_MARKER" && "$(cat "$CACHE_PLATFORM_MARKER")" != "$SOAK_PLATFORM" ]]; then
    echo "[soak] Client cache was populated on platform '$(cat "$CACHE_PLATFORM_MARKER")' — clearing for $SOAK_PLATFORM"
    rm -rf "$CLIENT_RUN_DIR/config/lss/cache"
fi
case "$SCENARIO" in
    dirty-broadcast)
        echo "[soak] Keeping client column cache"
        ;;
    cold-restart-resync)
        echo "[soak] Restoring client column cache from $BASE_WORLD_DIR/client-cache"
        rm -rf "$CLIENT_RUN_DIR/config/lss/cache"
        mkdir -p "$CLIENT_RUN_DIR/config/lss"
        cp -r "$BASE_WORLD_DIR/client-cache" "$CLIENT_RUN_DIR/config/lss/cache"
        ;;
    *)
        echo "[soak] Clearing client column cache"
        rm -rf "$CLIENT_RUN_DIR/config/lss/cache"
        ;;
esac
mkdir -p "$CLIENT_RUN_DIR/config/lss"
printf '%s' "$SOAK_PLATFORM" > "$CACHE_PLATFORM_MARKER"

# Step 6a: Stage server config override (fabric: config/; paper: the plugin data folder)
mkdir -p "$SERVER_CONFIG_DIR"
cp "$SCENARIO_CONFIG" "$SERVER_CONFIG_DIR/lss-server-config.json"

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
mc_start_server "$RUN_RESULTS_DIR/server.log" "$SERVER_GRADLE_TASK" -Psoak.scenario="$SCENARIO_JSON" ${SOAK_EXTRA_GRADLE_ARGS:-}
mc_wait_server_ready "$SERVER_RUN_DIR/logs/latest.log" "$RUN_RESULTS_DIR/server.log" "$SERVER_READY_TIMEOUT"
DEADLINE_EPOCH=$(( $(date +%s) + RUNTIME_BUDGET ))

# Step 10: Client runs (the server kicks the client between runs / halts at scenario end)
for (( run=1; run<=CLIENT_RUNS; run++ )); do
    echo "[soak] Client run $run/$CLIENT_RUNS"
    mc_start_client "$RUN_RESULTS_DIR/client-run$run.log" :fabric:runSoakClient ${CLIENT_EXTRA_ARGS[@]+"${CLIENT_EXTRA_ARGS[@]}"}
    while kill -0 "$CLIENT_PID" 2>/dev/null; do
        soak_check_deadline
        sleep 1
    done
    wait "$CLIENT_PID" 2>/dev/null || true
    CLIENT_PID=""
    echo "[soak] Client run $run exited"
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
    # in place would make the world* glob-copy nest world_nether/world_nether and silently keep
    # the STALE End/Nether in the snapshot (the exact failure this split-world handling fixes).
    rm -rf "$BASE_WORLD_DIR"/world "$BASE_WORLD_DIR"/world_nether "$BASE_WORLD_DIR"/world_the_end
    cp -r "$SERVER_RUN_DIR"/world* "$BASE_WORLD_DIR"/
    printf '%s' "1.21.11" > "$WORLD_VERSION_MARKER"
    if [[ -d "$CLIENT_RUN_DIR/config/lss/cache" ]]; then
        echo "[soak] Saving client column cache snapshot to $BASE_WORLD_DIR/client-cache"
        rm -rf "$BASE_WORLD_DIR/client-cache"
        cp -r "$CLIENT_RUN_DIR/config/lss/cache" "$BASE_WORLD_DIR/client-cache"
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
if python3 "$PROJECT_ROOT/scripts/check_soak.py" "$RUN_RESULTS_DIR" "$SCENARIO"; then
    echo "[soak] PASS: $SCENARIO — results in $RUN_RESULTS_DIR"
else
    code=$?
    echo "[soak] FAIL: $SCENARIO (checker exit $code) — results in $RUN_RESULTS_DIR"
    exit "$code"
fi
