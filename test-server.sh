#!/bin/bash
set -euo pipefail

# Test server script for LOD Server Support (LSS)
# Sets up Fabric/Paper/Folia servers and runs them on different ports.
# Fabric: localhost:25565   Paper: localhost:25566   Folia: localhost:25567

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FABRIC_DIR="$SCRIPT_DIR/test-server/fabric"
PAPER_DIR="$SCRIPT_DIR/test-server/paper"
FOLIA_DIR="$SCRIPT_DIR/test-server/folia"

# --- Fabric versions ---
FABRIC_MC_VERSION="26.1.2"
FABRIC_LOADER_VERSION="0.19.3"
FABRIC_INSTALLER_VERSION="1.1.1"

# --- Paper/Folia versions ---
PAPER_MC_VERSION="26.1.2"
FOLIA_MC_VERSION="26.1.2"

# --- Download URLs ---
FABRIC_SERVER_URL="https://meta.fabricmc.net/v2/versions/loader/${FABRIC_MC_VERSION}/${FABRIC_LOADER_VERSION}/${FABRIC_INSTALLER_VERSION}/server/jar"
FABRIC_API_URL="https://cdn.modrinth.com/data/P7dR8mSH/versions/yALY9gHM/fabric-api-0.151.0%2B26.1.2.jar"
C2ME_URL="https://cdn.modrinth.com/data/VSNURh3q/versions/MmyZoUyp/c2me-fabric-mc26.1.2-0.4.0-alpha.0.4.jar"

# --- Java version check ---
JAVA_MAJOR=$(java -version 2>&1 | head -1 | sed 's/.*"\([0-9]\+\).*/\1/')
if [ "$JAVA_MAJOR" -lt 25 ] 2>/dev/null; then
    echo "ERROR: Java 25+ required for MC 26.1. Found: Java $JAVA_MAJOR" >&2
    echo "  Set JAVA_HOME to a JDK 25+ installation." >&2
    exit 1
fi

# --- Settings ---
SERVER_RAM="${SERVER_RAM:-2G}"

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
    curl -fsSL -o "$dest" "$url"
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

    write_server_properties "$FABRIC_DIR" 25565 "LSS Test Server (Fabric)"
    write_ops_json "$FABRIC_DIR"
    write_lss_config "$FABRIC_DIR/config"

    echo "=== Installing Fabric mods ==="
    download "$FABRIC_API_URL" "$mods_dir/fabric-api.jar"
    download "$C2ME_URL" "$mods_dir/c2me.jar"

    echo "  Installing LSS..."
    local lss_jar
    lss_jar=$(build_fabric_jar)
    rm -f "$mods_dir"/lod-server-support-fabric*.jar
    cp "$lss_jar" "$mods_dir/"
    echo "  Installed: $(basename "$lss_jar")"
}

run_fabric() {
    cd "$FABRIC_DIR"
    java -Xmx${SERVER_RAM} -Xms${SERVER_RAM} -jar fabric-server-launch.jar nogui
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
    cd "$FOLIA_DIR"
    java -Xmx${SERVER_RAM} -Xms${SERVER_RAM} -jar folia.jar nogui
}

# ============================================================
# Combined
# ============================================================

run_all() {
    echo "=== Starting all servers ==="
    echo "  Fabric: localhost:25565"
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
    java -Xmx${SERVER_RAM} -Xms${SERVER_RAM} -jar fabric-server-launch.jar nogui &
    SERVER_PIDS+=($!)

    sleep 2

    cd "$PAPER_DIR"
    java -Xmx${SERVER_RAM} -Xms${SERVER_RAM} -jar paper.jar nogui &
    SERVER_PIDS+=($!)

    sleep 2

    cd "$FOLIA_DIR"
    java -Xmx${SERVER_RAM} -Xms${SERVER_RAM} -jar folia.jar nogui &
    SERVER_PIDS+=($!)

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
        echo ""
        echo "=== Starting Fabric server ==="
        echo "  Connect to: localhost:25565"
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
    clean)
        echo "Removing test servers..."
        rm -rf "$SCRIPT_DIR/test-server"
        echo "Done."
        ;;
    *)
        echo "Usage: $0 {setup|run|run-fabric|run-paper|run-folia|update|clean}"
        echo ""
        echo "  setup      - Download and set up all servers"
        echo "  run        - Set up and start all servers (default)"
        echo "  run-fabric - Set up and start Fabric server only (port 25565)"
        echo "  run-paper  - Set up and start Paper server only (port 25566)"
        echo "  run-folia  - Set up and start Folia server only (port 25567)"
        echo "  update     - Rebuild and install LSS JARs for all servers"
        echo "  clean      - Delete all test server directories"
        echo ""
        echo "Environment variables:"
        echo "  SERVER_RAM  - Server memory allocation per server (default: 2G)"
        exit 1
        ;;
esac
