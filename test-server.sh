#!/bin/bash
set -euo pipefail

# Test server script for LOD Server Support (LSS)
# Sets up Fabric/Paper/Folia servers and runs them on different ports.
# Fabric: localhost:25564   Paper: localhost:25566   Folia: localhost:25567
# (25565 is deliberately left free: the soak/benchmark harness binds it and a test
#  server there shows up identically in the multiplayer list — accidental joins
#  contaminate soak runs.)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FABRIC_DIR="$SCRIPT_DIR/test-server/fabric"
PAPER_DIR="$SCRIPT_DIR/test-server/paper"
FOLIA_DIR="$SCRIPT_DIR/test-server/folia"
# Legacy = an OLD LSS release (protocol 16) on the SAME Minecraft version, for eyeballing the
# client-side v16 backward-compat path (a current v0.7.0+ client joining a pre-v0.7.0 server).
# See docs/planning/v16-client-compat-design.md.
LEGACY_DIR="$SCRIPT_DIR/test-server/fabric-legacy"

# --- Fabric versions ---
FABRIC_MC_VERSION="26.2"
FABRIC_LOADER_VERSION="0.19.3"
FABRIC_INSTALLER_VERSION="1.1.1"

# --- Paper/Folia versions ---
PAPER_MC_VERSION="26.2"
FOLIA_MC_VERSION="26.2"

# --- Download URLs ---
FABRIC_SERVER_URL="https://meta.fabricmc.net/v2/versions/loader/${FABRIC_MC_VERSION}/${FABRIC_LOADER_VERSION}/${FABRIC_INSTALLER_VERSION}/server/jar"
FABRIC_API_URL="https://cdn.modrinth.com/data/P7dR8mSH/versions/Cpy2Px2f/fabric-api-0.154.0%2B26.2.jar"
C2ME_URL="https://cdn.modrinth.com/data/VSNURh3q/versions/nvOkOiyi/c2me-fabric-mc26.2-0.4.2-alpha.0.9.jar"

# --- Legacy (protocol-16) LSS server ---
# The last pre-v0.7.0 release on this Minecraft line (26.2), pulled straight from GitHub
# Releases (a real protocol-16 server, not a rebuild). MC 26.2 == the current line, so a
# current client CAN join it — only the LSS protocol differs (16 vs 18), which is exactly
# what the v16 client-compat path bridges. Bump this when a newer pre-v0.7.0 tag is preferred.
LEGACY_LSS_VERSION="0.6.2"
LEGACY_LSS_MC="26.2"
LEGACY_LSS_FABRIC_URL="https://github.com/VoX/lod-server-support/releases/download/v${LEGACY_LSS_VERSION}/lod-server-support-fabric-${LEGACY_LSS_VERSION}%2B${LEGACY_LSS_MC}.jar"

# --- Java version check ---
JAVA_MAJOR=$(java -version 2>&1 | head -1 | sed 's/.*"\([0-9]\+\).*/\1/')
if [ "$JAVA_MAJOR" -lt 25 ] 2>/dev/null; then
    echo "ERROR: Java 25+ required for MC 26.2. Found: Java $JAVA_MAJOR" >&2
    echo "  Set JAVA_HOME to a JDK 25+ installation." >&2
    exit 1
fi

# --- Settings ---
SERVER_RAM="${SERVER_RAM:-2G}"

# Dev-only [lss-adm] admission trace: one line per generation-admission decision
# (candidate ring, damped frontier, nearest in-flight rings, verdict) — the instrument for
# diagnosing far-arc / inversion reports. On by default for Fabric dev servers; it is
# VERBOSE (hundreds of lines/sec during backfill, since every held position re-logs on each
# 1 Hz re-declaration), so set LSS_ADMISSION_TRACE=0 for quiet console play or when reading
# the log for anything else. Never enabled in release jars — the flag is read only here.
LSS_ADMISSION_TRACE="${LSS_ADMISSION_TRACE:-1}"
case "$LSS_ADMISSION_TRACE" in
    0|false|no|off) ADMISSION_TRACE_FLAG="-Dlss.admissionTrace=false" ;;
    *)              ADMISSION_TRACE_FLAG="-Dlss.admissionTrace=true" ;;
esac

# ============================================================
# Helpers
# ============================================================

download() {
    local url="$1"
    local dest="$2"
    if [ -f "$dest" ]; then
        echo "  Already exists: $(basename "$dest")"
        return 0
    fi
    echo "  Downloading: $(basename "$dest")"
    # --remove-on-error: on a 404/failure, curl has already truncated/created $dest; without
    # this it leaves a 0-byte file that the "already exists" check above would skip on the NEXT
    # run, silently installing a broken jar. Most likely to bite the legacy jar (its URL is the
    # one edited when LEGACY_LSS_VERSION is bumped).
    curl -fsSL --remove-on-error -o "$dest" "$url"
}

# Downloads the latest stable build of a PaperMC-family server (paper|folia) via
# fill.papermc.io/v3 (PaperMC retired the old api.papermc.io/v2 API for the 26.x line).
download_papermc_jar() {
    local project="$1"
    local mc_version="$2"
    local dest="$3"
    if [ -f "$dest" ]; then
        echo "  Already exists: $(basename "$dest")"
        return 0
    fi
    echo "  Fetching latest stable ${project} build for MC ${mc_version}..."
    local builds_json
    builds_json=$(curl -fsSL -A "lod-server-support/test-server" \
        "https://fill.papermc.io/v3/projects/${project}/versions/${mc_version}/builds")
    local url
    url=$(echo "$builds_json" | python3 -c "
import sys, json
data = json.load(sys.stdin)
builds = data if isinstance(data, list) else data.get('builds', [])
stable = [b for b in builds if b.get('channel') == 'STABLE'] or builds
print(stable[0]['downloads']['server:default']['url']) if stable else print('')
")
    if [ -z "$url" ]; then
        echo "ERROR: Failed to resolve ${project} download URL" >&2
        return 1
    fi
    echo "  Downloading: $(basename "$url")"
    curl -fsSL -o "$dest" "$url"
}

# build_*_jar: build only when the jar is missing (first-time setup). Pass force=1 to
# always run gradle — `update` promises a REBUILD, and skipping it silently reinstalls
# whatever stale jar a previous build left (gradle's up-to-date check keeps no-ops cheap).
build_fabric_jar() {
    local force="${1:-}" jar
    jar="$SCRIPT_DIR/fabric/build/libs/lod-server-support-fabric.jar"
    if [ -n "$force" ] || [ ! -f "$jar" ]; then
        echo "Building Fabric LSS JAR..." >&2
        (cd "$SCRIPT_DIR" && ./gradlew :fabric:build -x runClientGameTest) >&2
    fi
    echo "$jar"
}

build_paper_jar() {
    local force="${1:-}" jar
    jar="$SCRIPT_DIR/paper/build/libs/lod-server-support-paper.jar"
    if [ -n "$force" ] || [ ! -f "$jar" ]; then
        echo "Building Paper LSS JAR..." >&2
        (cd "$SCRIPT_DIR" && ./gradlew :paper:shadowJar) >&2
    fi
    echo "$jar"
}

write_server_properties() {
    local dir="$1"
    local port="$2"
    local motd="$3"
    # Enforce the port on EXISTING installs too (the Fabric port moved off the soak
    # harness's 25565; a stale server.properties would silently keep colliding).
    if [ -f "$dir/server.properties" ] && ! grep -q "^server-port=$port$" "$dir/server.properties"; then
        echo "  Updating server-port to $port"
        sed -i "s/^server-port=.*/server-port=$port/" "$dir/server.properties"
    fi
    if [ ! -f "$dir/server.properties" ]; then
        echo "  Creating server.properties (port $port)"
        cat > "$dir/server.properties" << EOF
online-mode=false
spawn-protection=0
view-distance=4
simulation-distance=4
max-players=5
level-name=world
enable-command-block=true
server-port=$port
motd=$motd
EOF
    fi
}

write_ops_json() {
    local dir="$1"
    if [ ! -f "$dir/ops.json" ]; then
        echo "  Creating ops.json"
        cat > "$dir/ops.json" << 'EOF'
[
  {
    "uuid": "270f8c92-35c1-35d6-9b80-ca694ebb4367",
    "name": "Voximus_Maximus",
    "level": 4,
    "bypassesPlayerLimit": true
  }
]
EOF
    fi
}

write_lss_config() {
    local dir="$1"
    echo "  Writing lss-server-config.json"
    mkdir -p "$dir"
    cat > "$dir/lss-server-config.json" << 'EOF'
{
  "enabled": true,
  "lodDistanceChunks": 64,
  "bytesPerSecondLimitPerPlayer": 8388608,
  "diskReaderThreads": 8,
  "sendQueueLimitPerPlayer": 9600,
  "bytesPerSecondLimitGlobal": 41943040,
  "syncOnLoadConcurrencyLimitPerPlayer": 400,
  "generationConcurrencyLimitPerPlayer": 40,
  "enableChunkGeneration": true,
  "generationConcurrencyLimitGlobal": 40,
  "generationTimeoutSeconds": 60
}
EOF
}

# ============================================================
# Fabric
# ============================================================

setup_fabric() {
    echo "=== Setting up Fabric server ==="
    local mods_dir="$FABRIC_DIR/mods"
    mkdir -p "$FABRIC_DIR" "$mods_dir"

    download "$FABRIC_SERVER_URL" "$FABRIC_DIR/fabric-server-launch.jar"

    if [ ! -f "$FABRIC_DIR/eula.txt" ]; then
        echo "eula=true" > "$FABRIC_DIR/eula.txt"
    fi

    write_server_properties "$FABRIC_DIR" 25564 "LSS Test Server (Fabric)"
    write_ops_json "$FABRIC_DIR"
    write_lss_config "$FABRIC_DIR/config"

    echo "=== Installing Fabric mods ==="
    download "$FABRIC_API_URL" "$mods_dir/fabric-api.jar"
    # Skip the download while a no-c2me A/B run has it parked as .jar.disabled —
    # otherwise every such run re-downloads a jar it is about to disable.
    if [ ! -f "$mods_dir/c2me.jar.disabled" ]; then
        download "$C2ME_URL" "$mods_dir/c2me.jar"
    fi

    echo "  Installing LSS..."
    local lss_jar
    lss_jar=$(build_fabric_jar)
    rm -f "$mods_dir"/lod-server-support-fabric*.jar
    cp "$lss_jar" "$mods_dir/"
    echo "  Installed: $(basename "$lss_jar")"
}

run_fabric() {
    cd "$FABRIC_DIR"
    # admissionTrace: dev-only [lss-adm] lines (candidate ring vs frontier stamp per
    # generation-admission decision) — the instrument for far-arc/inversion reports.
    # Disable with LSS_ADMISSION_TRACE=0 (see the flag's definition near SERVER_RAM).
    java -Xmx${SERVER_RAM} -Xms${SERVER_RAM} "$ADMISSION_TRACE_FLAG" -jar fabric-server-launch.jar nogui
}

# Toggle c2me*.jar in the Fabric mods folder for A/B runs (LSS vs C2ME's chunk-system
# rewrite — e.g. generation completion-order testing). Disabling renames to
# .jar.disabled, which the Fabric loader ignores; run-fabric re-enables.
set_c2me_enabled() {
    local enabled="$1" f moved=false
    mkdir -p "$FABRIC_DIR/mods"
    if [ "$enabled" = true ]; then
        for f in "$FABRIC_DIR/mods"/c2me*.jar.disabled; do
            [ -e "$f" ] || continue
            mv "$f" "${f%.disabled}"
            echo "  Re-enabled: $(basename "${f%.disabled}")"
            moved=true
        done
    else
        for f in "$FABRIC_DIR/mods"/c2me*.jar; do
            [ -e "$f" ] || continue
            mv "$f" "$f.disabled"
            echo "  Disabled: $(basename "$f")"
            moved=true
        done
        if [ "$moved" = false ]; then
            echo "  (no c2me*.jar in $FABRIC_DIR/mods — nothing to disable)"
        fi
    fi
}

# ============================================================
# Paper
# ============================================================

setup_paper() {
    echo "=== Setting up Paper server ==="
    local plugins_dir="$PAPER_DIR/plugins"
    mkdir -p "$PAPER_DIR" "$plugins_dir"

    download_papermc_jar paper "$PAPER_MC_VERSION" "$PAPER_DIR/paper.jar"

    if [ ! -f "$PAPER_DIR/eula.txt" ]; then
        echo "eula=true" > "$PAPER_DIR/eula.txt"
    fi

    write_server_properties "$PAPER_DIR" 25566 "LSS Test Server (Paper)"
    write_ops_json "$PAPER_DIR"
    write_lss_config "$PAPER_DIR/plugins/LodServerSupport"

    echo "=== Installing Paper plugins ==="
    echo "  Installing LSS..."
    local lss_jar
    lss_jar=$(build_paper_jar)
    rm -f "$plugins_dir"/lod-server-support-paper*.jar
    cp "$lss_jar" "$plugins_dir/"
    echo "  Installed: $(basename "$lss_jar")"
}

run_paper() {
    cd "$PAPER_DIR"
    java -Xmx${SERVER_RAM} -Xms${SERVER_RAM} -jar paper.jar nogui
}

# ============================================================
# Folia
# ============================================================

setup_folia() {
    echo "=== Setting up Folia server ==="
    local plugins_dir="$FOLIA_DIR/plugins"
    mkdir -p "$FOLIA_DIR" "$plugins_dir"

    # Folia lags Paper when a new Minecraft version lands — it may not have a build for
    # FOLIA_MC_VERSION yet. Skip the local Folia server gracefully (the Paper plugin jar already
    # carries Folia support) instead of aborting the whole script under `set -e`.
    if ! curl -fsSL -A "lod-server-support/test-server" -o /dev/null \
            "https://fill.papermc.io/v3/projects/folia/versions/${FOLIA_MC_VERSION}/builds" 2>/dev/null; then
        echo "  NOTE: Folia has no MC ${FOLIA_MC_VERSION} build published upstream yet — skipping the local Folia server."
        echo "  (Folia support ships inside the Paper plugin jar; only the standalone Folia test server is skipped.)"
        return 0
    fi

    download_papermc_jar folia "$FOLIA_MC_VERSION" "$FOLIA_DIR/folia.jar"

    if [ ! -f "$FOLIA_DIR/eula.txt" ]; then
        echo "eula=true" > "$FOLIA_DIR/eula.txt"
    fi

    write_server_properties "$FOLIA_DIR" 25567 "LSS Test Server (Folia)"
    write_ops_json "$FOLIA_DIR"
    write_lss_config "$FOLIA_DIR/plugins/LodServerSupport"

    echo "=== Installing Folia plugins ==="
    echo "  Installing LSS (same jar as Paper — folia-supported: true)..."
    local lss_jar
    lss_jar=$(build_paper_jar)
    rm -f "$plugins_dir"/lod-server-support-paper*.jar
    cp "$lss_jar" "$plugins_dir/"
    echo "  Installed: $(basename "$lss_jar")"
}

run_folia() {
    if [ ! -f "$FOLIA_DIR/folia.jar" ]; then
        echo "Folia server not set up (no MC ${FOLIA_MC_VERSION} build upstream yet) — nothing to run."
        return 0
    fi
    cd "$FOLIA_DIR"
    java -Xmx${SERVER_RAM} -Xms${SERVER_RAM} -jar folia.jar nogui
}

# ============================================================
# Legacy (protocol-16 LSS server, for v16 client-compat eyeballing)
# ============================================================

setup_legacy() {
    echo "=== Setting up legacy (LSS v${LEGACY_LSS_VERSION}, protocol 16) Fabric server ==="
    local mods_dir="$LEGACY_DIR/mods"
    mkdir -p "$LEGACY_DIR" "$mods_dir"

    # Same MC 26.2 Fabric server launcher + Fabric API as the current Fabric server — only the
    # LSS jar differs (an old release instead of the local build). No C2ME: keep the legacy
    # server a clean vanilla-IO protocol-16 baseline so nothing confounds the compat eyeball.
    download "$FABRIC_SERVER_URL" "$LEGACY_DIR/fabric-server-launch.jar"

    if [ ! -f "$LEGACY_DIR/eula.txt" ]; then
        echo "eula=true" > "$LEGACY_DIR/eula.txt"
    fi

    write_server_properties "$LEGACY_DIR" 25568 "LSS LEGACY v${LEGACY_LSS_VERSION} (protocol 16)"
    write_ops_json "$LEGACY_DIR"
    write_lss_config "$LEGACY_DIR/config"

    echo "=== Installing legacy mods ==="
    download "$FABRIC_API_URL" "$mods_dir/fabric-api.jar"

    echo "  Installing LSS v${LEGACY_LSS_VERSION} (downloaded release — NOT the local build)..."
    local legacy_jar="$mods_dir/lod-server-support-fabric-${LEGACY_LSS_VERSION}.jar"
    download "$LEGACY_LSS_FABRIC_URL" "$legacy_jar"
    # Guard against a stale current-build jar left behind by an earlier copy/paste.
    find "$mods_dir" -maxdepth 1 -name 'lod-server-support-fabric*.jar' \
        ! -name "$(basename "$legacy_jar")" -delete 2>/dev/null || true
    echo "  Installed: $(basename "$legacy_jar")"
}

run_legacy() {
    cd "$LEGACY_DIR"
    # No admissionTrace flag — that is a dev-build-only system property the v${LEGACY_LSS_VERSION}
    # release jar never reads.
    java -Xmx${SERVER_RAM} -Xms${SERVER_RAM} -jar fabric-server-launch.jar nogui
}

# ============================================================
# Combined
# ============================================================

run_all() {
    echo "=== Starting all servers ==="
    echo "  Fabric: localhost:25564"
    echo "  Paper:  localhost:25566"
    echo "  Folia:  localhost:25567"
    echo "  Commands: /lsslod stats, /lsslod diag"
    echo ""

    # Install the trap BEFORE the first launch: a Ctrl+C during the startup sleeps would
    # otherwise orphan the already-started JVMs (holding their ports and failing the next
    # run's — or soak.sh's — port pre-flight).
    SERVER_PIDS=()
    trap 'echo ""; echo "Stopping servers..."; kill "${SERVER_PIDS[@]}" 2>/dev/null; wait "${SERVER_PIDS[@]}" 2>/dev/null; echo "Done."' INT TERM EXIT

    cd "$FABRIC_DIR"
    java -Xmx${SERVER_RAM} -Xms${SERVER_RAM} "$ADMISSION_TRACE_FLAG" -jar fabric-server-launch.jar nogui &
    SERVER_PIDS+=($!)

    sleep 2

    cd "$PAPER_DIR"
    java -Xmx${SERVER_RAM} -Xms${SERVER_RAM} -jar paper.jar nogui &
    SERVER_PIDS+=($!)

    sleep 2

    if [ -f "$FOLIA_DIR/folia.jar" ]; then
        cd "$FOLIA_DIR"
        java -Xmx${SERVER_RAM} -Xms${SERVER_RAM} -jar folia.jar nogui &
        SERVER_PIDS+=($!)
    fi

    wait "${SERVER_PIDS[@]}" 2>/dev/null
}

# ============================================================
# Main
# ============================================================

case "${1:-run}" in
    setup)
        setup_fabric
        echo ""
        setup_paper
        echo ""
        setup_folia
        echo ""
        echo "Setup complete. Run '$0 run' to start all servers."
        ;;
    update)
        echo "=== Updating LSS JARs ==="
        fabric_jar=$(build_fabric_jar force)
        paper_jar=$(build_paper_jar force)

        mkdir -p "$FABRIC_DIR/mods" "$PAPER_DIR/plugins" "$FOLIA_DIR/plugins"

        rm -f "$FABRIC_DIR/mods"/lod-server-support-fabric*.jar
        cp "$fabric_jar" "$FABRIC_DIR/mods/"
        echo "  Fabric: $(basename "$fabric_jar")"

        rm -f "$PAPER_DIR/plugins"/lod-server-support-paper*.jar
        cp "$paper_jar" "$PAPER_DIR/plugins/"
        echo "  Paper:  $(basename "$paper_jar")"

        # Folia runs the same plugin jar as Paper (folia-supported: true)
        rm -f "$FOLIA_DIR/plugins"/lod-server-support-paper*.jar
        cp "$paper_jar" "$FOLIA_DIR/plugins/"
        echo "  Folia:  $(basename "$paper_jar")"

        echo "  Restart the servers to apply."
        ;;
    run)
        setup_fabric
        echo ""
        setup_paper
        echo ""
        setup_folia
        echo ""
        run_all
        ;;
    run-fabric)
        setup_fabric
        set_c2me_enabled true   # undo a previous run-fabric-no-c2me
        echo ""
        echo "=== Starting Fabric server ==="
        echo "  Connect to: localhost:25564"
        echo ""
        run_fabric
        ;;
    run-fabric-no-c2me)
        setup_fabric
        echo ""
        echo "=== Disabling C2ME for this run ==="
        set_c2me_enabled false
        echo ""
        echo "=== Starting Fabric server (C2ME disabled) ==="
        echo "  Connect to: localhost:25564"
        echo ""
        run_fabric
        ;;
    run-paper)
        setup_paper
        echo ""
        echo "=== Starting Paper server ==="
        echo "  Connect to: localhost:25566"
        echo ""
        run_paper
        ;;
    run-folia)
        setup_folia
        echo ""
        echo "=== Starting Folia server ==="
        echo "  Connect to: localhost:25567"
        echo ""
        run_folia
        ;;
    run-legacy)
        setup_legacy
        echo ""
        echo "=== Starting legacy LSS v${LEGACY_LSS_VERSION} server (protocol 16) ==="
        echo "  Connect to: localhost:25568  (join with a CURRENT v0.7.0+ client)"
        echo ""
        echo "  What to look for (client-side v16 backward compat):"
        echo "   - Client log: 'Connected to a legacy (protocol 16) server — using v16"
        echo "     backward-compat wire' (after a ~5 s discovery delay: the client announces 18,"
        echo "     this old server drops it silently, then the client re-handshakes as 16)."
        echo "   - Tier B (default ON): flying into never-generated terrain drives the old"
        echo "     server to generate it on demand, so cold terrain fills in — the full LOD"
        echo "     experience. Already-generated terrain (near spawn / where players walked)"
        echo "     also renders. To test strict Tier A load-only instead, set"
        echo "     \"enableV16Generation\": false in the CLIENT's config/lss-client-config.json"
        echo "     and rejoin — then cold terrain will NOT fill (only already-generated shows)."
        echo "   - Old-server command is '/lsslod' (this jar predates any /vss rebrand)."
        echo ""
        run_legacy
        ;;
    clean)
        echo "Removing test servers..."
        rm -rf "$SCRIPT_DIR/test-server"
        echo "Done."
        ;;
    *)
        echo "Usage: $0 {setup|run|run-fabric|run-fabric-no-c2me|run-paper|run-folia|run-legacy|update|clean}"
        echo ""
        echo "  setup      - Download and set up all servers"
        echo "  run        - Set up and start all servers (default)"
        echo "  run-fabric - Set up and start Fabric server only (port 25564; re-enables C2ME)"
        echo "  run-fabric-no-c2me - Same, with any c2me*.jar in mods/ disabled (A/B testing)"
        echo "  run-paper  - Set up and start Paper server only (port 25566)"
        echo "  run-folia  - Set up and start Folia server only (port 25567)"
        echo "  run-legacy - Set up and start an OLD LSS v${LEGACY_LSS_VERSION} (protocol 16) server (port 25568),"
        echo "               for eyeballing the client-side v16 backward-compat path"
        echo "  update     - Rebuild and install LSS JARs for all servers (NOT the legacy one)"
        echo "  clean      - Delete all test server directories"
        echo ""
        echo "Environment variables:"
        echo "  SERVER_RAM  - Server memory allocation per server (default: 2G)"
        echo "  LSS_ADMISSION_TRACE - Fabric [lss-adm] generation-admission trace (default: 1)."
        echo "                        Set to 0 to silence it — it is verbose during backfill."
        exit 1
        ;;
esac
