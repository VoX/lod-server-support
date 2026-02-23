#!/bin/bash
set -euo pipefail

# Test server script for LOD Server Support (LSS)
# Sets up a Fabric and/or Paper server and runs them on different ports.
# Fabric: localhost:25565   Paper: localhost:25566

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FABRIC_DIR="$SCRIPT_DIR/test-server/fabric"
PAPER_DIR="$SCRIPT_DIR/test-server/paper"

# --- Fabric versions ---
FABRIC_MC_VERSION="1.21.11"
FABRIC_LOADER_VERSION="0.18.2"
FABRIC_INSTALLER_VERSION="1.1.1"

# --- Paper versions ---
PAPER_MC_VERSION="1.21.11"

# --- Download URLs ---
FABRIC_SERVER_URL="https://meta.fabricmc.net/v2/versions/loader/${FABRIC_MC_VERSION}/${FABRIC_LOADER_VERSION}/${FABRIC_INSTALLER_VERSION}/server/jar"
FABRIC_API_URL="https://cdn.modrinth.com/data/P7dR8mSH/versions/gB6TkYEJ/fabric-api-0.140.2%2B1.21.11.jar"

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

download_paper_jar() {
    local dest="$PAPER_DIR/paper.jar"
    if [ -f "$dest" ]; then
        echo "  Already exists: paper.jar"
        return 0
    fi
    echo "  Fetching latest Paper build for MC ${PAPER_MC_VERSION}..."
    local builds_json
    builds_json=$(curl -fsSL "https://api.papermc.io/v2/projects/paper/versions/${PAPER_MC_VERSION}/builds")
    local build
    build=$(echo "$builds_json" | python3 -c "import sys,json; builds=json.load(sys.stdin)['builds']; print(builds[-1]['build'])")
    if [ -z "$build" ]; then
        echo "ERROR: Failed to determine Paper build number" >&2
        return 1
    fi
    local url="https://api.papermc.io/v2/projects/paper/versions/${PAPER_MC_VERSION}/builds/${build}/downloads/paper-${PAPER_MC_VERSION}-${build}.jar"
    echo "  Downloading: paper-${PAPER_MC_VERSION}-${build}.jar"
    curl -fsSL -o "$dest" "$url"
}

build_fabric_jar() {
    local jar
    jar=$(ls -t "$SCRIPT_DIR"/fabric/build/libs/lod-server-support-fabric-*.jar 2>/dev/null | head -1)
    if [ -z "$jar" ]; then
        echo "No Fabric LSS JAR found, building..." >&2
        (cd "$SCRIPT_DIR" && ./gradlew :fabric:build -x runClientGameTest) >&2
        jar=$(ls -t "$SCRIPT_DIR"/fabric/build/libs/lod-server-support-fabric-*.jar | head -1)
    fi
    echo "$jar"
}

build_paper_jar() {
    local jar
    jar=$(ls -t "$SCRIPT_DIR"/paper/build/libs/lod-server-support-paper-all.jar 2>/dev/null | head -1)
    if [ -z "$jar" ]; then
        echo "No Paper LSS JAR found, building..." >&2
        (cd "$SCRIPT_DIR" && ./gradlew :paper:shadowJar) >&2
        jar=$(ls -t "$SCRIPT_DIR"/paper/build/libs/lod-server-support-paper-all.jar | head -1)
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
view-distance=10
simulation-distance=10
max-players=5
level-name=world
enable-command-block=true
server-port=$port
motd=$motd
EOF
    fi
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

    echo "=== Installing Fabric mods ==="
    download "$FABRIC_API_URL" "$mods_dir/fabric-api.jar"

    echo "  Installing LSS..."
    local lss_jar
    lss_jar=$(build_fabric_jar)
    rm -f "$mods_dir"/lod-server-support-fabric-*.jar
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

    download_paper_jar

    if [ ! -f "$PAPER_DIR/eula.txt" ]; then
        echo "eula=true" > "$PAPER_DIR/eula.txt"
    fi

    write_server_properties "$PAPER_DIR" 25566 "LSS Test Server (Paper)"

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
# Combined
# ============================================================

run_both() {
    echo "=== Starting both servers ==="
    echo "  Fabric: localhost:25565"
    echo "  Paper:  localhost:25566"
    echo "  Commands: /lsslod stats, /lsslod diag"
    echo ""

    cd "$FABRIC_DIR"
    java -Xmx${SERVER_RAM} -Xms${SERVER_RAM} -jar fabric-server-launch.jar nogui &
    FABRIC_PID=$!

    sleep 2

    cd "$PAPER_DIR"
    java -Xmx${SERVER_RAM} -Xms${SERVER_RAM} -jar paper.jar nogui &
    PAPER_PID=$!

    trap 'echo ""; echo "Stopping servers..."; kill $FABRIC_PID $PAPER_PID 2>/dev/null; wait $FABRIC_PID $PAPER_PID 2>/dev/null; echo "Done."' INT TERM EXIT
    wait $FABRIC_PID $PAPER_PID 2>/dev/null
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
        echo "Setup complete. Run '$0 run' to start both servers."
        ;;
    update)
        echo "=== Updating LSS JARs ==="
        fabric_jar=$(build_fabric_jar)
        paper_jar=$(build_paper_jar)

        mkdir -p "$FABRIC_DIR/mods" "$PAPER_DIR/plugins"

        rm -f "$FABRIC_DIR/mods"/lod-server-support-fabric-*.jar
        cp "$fabric_jar" "$FABRIC_DIR/mods/"
        echo "  Fabric: $(basename "$fabric_jar")"

        rm -f "$PAPER_DIR/plugins"/lod-server-support-paper*.jar
        cp "$paper_jar" "$PAPER_DIR/plugins/"
        echo "  Paper:  $(basename "$paper_jar")"

        echo "  Restart the servers to apply."
        ;;
    run)
        setup_fabric
        echo ""
        setup_paper
        echo ""
        run_both
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
    clean)
        echo "Removing test servers..."
        rm -rf "$SCRIPT_DIR/test-server"
        echo "Done."
        ;;
    *)
        echo "Usage: $0 {setup|run|run-fabric|run-paper|update|clean}"
        echo ""
        echo "  setup      - Download and set up both servers"
        echo "  run        - Set up and start both servers (default)"
        echo "  run-fabric - Set up and start Fabric server only (port 25565)"
        echo "  run-paper  - Set up and start Paper server only (port 25566)"
        echo "  update     - Rebuild and install LSS JARs for both servers"
        echo "  clean      - Delete both test server directories"
        echo ""
        echo "Environment variables:"
        echo "  SERVER_RAM  - Server memory allocation per server (default: 2G)"
        exit 1
        ;;
esac
