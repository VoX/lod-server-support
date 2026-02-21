#!/bin/bash
set -euo pipefail

# Test server script for LOD Server Support (LSS)
# Creates a Fabric MC server, installs Chunky + LSS, pregenerates chunks, and starts the server

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER_DIR="$SCRIPT_DIR/test-server"
MODS_DIR="$SERVER_DIR/mods"

# Versions
MC_VERSION="1.21.11"
LOADER_VERSION="0.18.2"
INSTALLER_VERSION="1.1.1"

# Download URLs
FABRIC_SERVER_URL="https://meta.fabricmc.net/v2/versions/loader/${MC_VERSION}/${LOADER_VERSION}/${INSTALLER_VERSION}/server/jar"
FABRIC_API_URL="https://cdn.modrinth.com/data/P7dR8mSH/versions/gB6TkYEJ/fabric-api-0.140.2%2B1.21.11.jar"
CHUNKY_URL="https://cdn.modrinth.com/data/fALzjamp/versions/1CpEkmcD/Chunky-Fabric-1.4.55.jar"
C2ME_URL="https://cdn.modrinth.com/data/VSNURh3q/versions/QdLiMUjx/c2me-fabric-mc1.21.11-0.3.7%2Balpha.0.7.jar"

# Pregen settings
PREGEN_RADIUS="${PREGEN_RADIUS:-500}"
PREGEN_WORLD="${PREGEN_WORLD:-overworld}"

# Server JVM settings
SERVER_RAM="${SERVER_RAM:-2G}"

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

build_lss() {
    local jar
    jar=$(ls -t "$SCRIPT_DIR"/build/libs/lod-server-support-*.jar 2>/dev/null | head -1)
    if [ -z "$jar" ]; then
        echo "No LSS JAR found, building..."
        (cd "$SCRIPT_DIR" && ./gradlew build)
        jar=$(ls -t "$SCRIPT_DIR"/build/libs/lod-server-support-*.jar | head -1)
    fi
    echo "$jar"
}

setup_server() {
    echo "=== Setting up Fabric server ==="
    mkdir -p "$SERVER_DIR" "$MODS_DIR"

    # Fabric server launcher
    download "$FABRIC_SERVER_URL" "$SERVER_DIR/fabric-server-launch.jar"

    # EULA
    if [ ! -f "$SERVER_DIR/eula.txt" ]; then
        echo "eula=true" > "$SERVER_DIR/eula.txt"
    fi

    # server.properties
    if [ ! -f "$SERVER_DIR/server.properties" ]; then
        echo "  Creating server.properties"
        cat > "$SERVER_DIR/server.properties" << 'EOF'
online-mode=false
spawn-protection=0
view-distance=10
simulation-distance=10
max-players=5
level-name=world
enable-command-block=true
motd=LSS Test Server
EOF
    fi

    echo "=== Installing mods ==="
    download "$FABRIC_API_URL" "$MODS_DIR/fabric-api.jar"
    download "$CHUNKY_URL" "$MODS_DIR/chunky.jar"
    download "$C2ME_URL" "$MODS_DIR/c2me.jar"

    # Install local LSS build
    echo "  Installing LSS..."
    local lss_jar
    lss_jar=$(build_lss)
    rm -f "$MODS_DIR"/lod-server-support-*.jar
    cp "$lss_jar" "$MODS_DIR/"
    echo "  Installed: $(basename "$lss_jar")"
}

run_pregen() {
    echo "=== Pregenerating chunks (radius: ${PREGEN_RADIUS} blocks) ==="
    echo "  Starting server for pregeneration..."

    cd "$SERVER_DIR"
    # Clear old log so we don't match stale entries
    rm -f logs/latest.log

    # Pipe commands to server stdin:
    # 1. Wait for server to finish loading (watch log file)
    # 2. Send chunky commands
    # 3. Wait for pregen to complete
    # 4. Stop the server
    {
        # Wait for server to be ready
        while [ ! -f logs/latest.log ] || ! grep -q "Done (" logs/latest.log 2>/dev/null; do
            sleep 1
        done
        echo "  Server ready, starting pregeneration..." >&2

        sleep 2
        echo "chunky world $PREGEN_WORLD"
        sleep 1
        echo "chunky radius $PREGEN_RADIUS"
        sleep 1
        echo "chunky start"

        # Wait for pregen to complete
        while ! grep -q "Task finished" logs/latest.log 2>/dev/null; do
            # Show progress
            progress=$(grep -oP '\d+\.\d+%' logs/latest.log 2>/dev/null | tail -1 || true)
            if [ -n "$progress" ]; then
                printf "\r  Pregen progress: %s  " "$progress" >&2
            fi
            sleep 5
        done
        echo "" >&2
        echo "  Pregeneration complete!" >&2

        sleep 1
        echo "stop"
    } | java -Xmx${SERVER_RAM} -Xms${SERVER_RAM} -jar fabric-server-launch.jar nogui

    touch "$SERVER_DIR/.pregen-done"
    echo "  Server stopped after pregeneration."
}

run_server() {
    cd "$SERVER_DIR"
    echo "=== Starting server ==="
    echo "  Connect to: localhost:25565"
    echo "  Server commands: /lsslod stats, /lsslod diag"
    echo ""
    exec java -Xmx${SERVER_RAM} -Xms${SERVER_RAM} -jar fabric-server-launch.jar nogui
}

# --- Main ---

case "${1:-run}" in
    setup)
        setup_server
        echo ""
        echo "Setup complete. Run '$0 run' to start the server."
        ;;
    update)
        echo "=== Updating LSS JAR ==="
        mkdir -p "$MODS_DIR"
        lss_jar=$(build_lss)
        rm -f "$MODS_DIR"/lod-server-support-*.jar
        cp "$lss_jar" "$MODS_DIR/"
        echo "  Installed: $(basename "$lss_jar")"
        echo "  Restart the server to apply."
        ;;
    run)
        setup_server
        if [ ! -f "$SERVER_DIR/.pregen-done" ]; then
            run_pregen
        fi
        echo ""
        run_server
        ;;
    pregen)
        setup_server
        rm -f "$SERVER_DIR/.pregen-done"
        run_pregen
        echo "Done. Run '$0 run' to start the server."
        ;;
    clean)
        echo "Removing test server..."
        rm -rf "$SERVER_DIR"
        echo "Done."
        ;;
    *)
        echo "Usage: $0 {setup|run|update|pregen|clean}"
        echo ""
        echo "  setup   - Download and set up the server without starting it"
        echo "  run     - Set up (if needed), pregenerate, and start the server (default)"
        echo "  update  - Rebuild and install LSS JAR only"
        echo "  pregen  - Force re-run chunk pregeneration"
        echo "  clean   - Delete the test server directory"
        echo ""
        echo "Environment variables:"
        echo "  PREGEN_RADIUS  - Chunk pregeneration radius in blocks (default: 500)"
        echo "  PREGEN_WORLD   - World to pregenerate (default: overworld)"
        echo "  SERVER_RAM     - Server memory allocation (default: 2G)"
        exit 1
        ;;
esac
