#!/bin/bash
set -euo pipefail

# Test server script for LOD Server Support (LSS)
# Sets up Fabric/Paper/Folia/NeoForge servers and runs them on different ports.
# Fabric: localhost:25564   Paper: localhost:25566   Folia: localhost:25567
# NeoForge: localhost:25569   (25568 = the legacy protocol-16 server)
# (25565 is deliberately left free: the soak/benchmark harness binds it and a test
#  server there shows up identically in the multiplayer list — accidental joins
#  contaminate soak runs.)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FABRIC_DIR="$SCRIPT_DIR/test-server/fabric"
PAPER_DIR="$SCRIPT_DIR/test-server/paper"
FOLIA_DIR="$SCRIPT_DIR/test-server/folia"
NEOFORGE_DIR="$SCRIPT_DIR/test-server/neoforge"
# Legacy = an OLD LSS release (protocol 16) on the SAME Minecraft version, for eyeballing the
# client-side v16 backward-compat path (a current v0.7.0+ client joining a pre-v0.7.0 server).
# See docs/planning/v16-client-compat-design.md.
LEGACY_DIR="$SCRIPT_DIR/test-server/fabric-legacy"

# ======================= LINE DATA (per-MC-line values) =======================
# The port runbook's step 8 edits exactly this block (MC versions, CDN URLs, the
# legacy LSS pin below) — nothing else in this script is per-line.
# --- Fabric versions ---
# NOTE: the Java-version gate below (JAVA_MAJOR check) is ALSO per-line data —
#       a Java-21 line port must retarget it (round-3 review NIT).
FABRIC_MC_VERSION="1.21.10"
FABRIC_LOADER_VERSION="0.19.3"
FABRIC_INSTALLER_VERSION="1.1.1"

# --- Paper/Folia versions ---
PAPER_MC_VERSION="1.21.10"
FOLIA_MC_VERSION="1.21.10"

# --- NeoForge version ---
# Pinned to the version the neoforge module BUILDS AGAINST (gradle.properties
# neoforge_version) so the rig can never drift from the compile target.
NEOFORGE_VERSION="${NEOFORGE_VERSION:-$(sed -n 's/^neoforge_version=//p' "$SCRIPT_DIR/gradle.properties" | tr -d '\r')}"
if [ -z "$NEOFORGE_VERSION" ]; then
    echo "ERROR: could not read neoforge_version from gradle.properties" >&2
    exit 1
fi
NEOFORGE_INSTALLER_URL="https://maven.neoforged.net/releases/net/neoforged/neoforge/${NEOFORGE_VERSION}/neoforge-${NEOFORGE_VERSION}-installer.jar"

# --- Download URLs ---
FABRIC_SERVER_URL="https://meta.fabricmc.net/v2/versions/loader/${FABRIC_MC_VERSION}/${FABRIC_LOADER_VERSION}/${FABRIC_INSTALLER_VERSION}/server/jar"
FABRIC_API_URL="https://cdn.modrinth.com/data/P7dR8mSH/versions/tV4Gc0Zo/fabric-api-0.138.4%2B1.21.10.jar"
C2ME_URL="https://cdn.modrinth.com/data/VSNURh3q/versions/b2odfQPM/c2me-fabric-mc1.21.10-0.3.6%2Balpha.0.11.jar"
# DrexHD AntiXray (Modrinth sml2FMaA), fabric-1.4.12+1.21.10 — this line's own build.
# `run-fabric-antixray` enables it as the live gate for LSS's AntiXray compat
# (docs/planning/antixray-compat-design.md): a current LSS build must SURVIVE an LSS client
# join — 1.21.x line: ScopedCarrier is the PASS-THROUGH variant (ScopedValue is preview
# on Java 21 — no crash shim exists or is needed; this rig is the pass-through claim's
# ONLY live verification) — and masking adopts the mod's hidden list (watch for a clean
# join + 'LOD x-ray masking active' and the /lsslod diag Xray line). Every other run
# command parks the jar as .jar.disabled.
ANTIXRAY_URL="https://cdn.modrinth.com/data/sml2FMaA/versions/ZLcQWfAn/antixray-fabric-1.4.12%2B1.21.10.jar"

# --- Legacy (protocol-16) LSS server ---
# 1.21.10 line: NO pre-v0.7.0 LSS release ever shipped a +mc1.21.10 build (this line was
# created at v0.12.0), so the legacy protocol-16 rig has nothing real to download —
# LEGACY_LSS_MC stays empty (the 1.21.1 line's shape) and the legacy rig is inert here.
LEGACY_LSS_VERSION="0.5.0"
LEGACY_LSS_MC=""
LEGACY_LSS_FABRIC_URL=""

# --- Java version check ---
JAVA_MAJOR=$(java -version 2>&1 | head -1 | sed 's/.*"\([0-9]\+\).*/\1/')
if [ "$JAVA_MAJOR" -lt 21 ] 2>/dev/null; then
    echo "ERROR: Java 21+ required for MC 1.21.10. Found: Java $JAVA_MAJOR" >&2
    echo "  Set JAVA_HOME to a JDK 21 installation (this line BUILDS with 21 —" >&2
    echo "  paperweight's codebook cannot parse Java 25 class files)." >&2
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

# Move-desync tracer (docs/planning/move-desync-tracer-plan.md): LSS_MOVE_TRACE=1 stages
# the marker file config/lss-move-trace.enable on the Fabric dev server — this rig IS the
# tracer's vanilla-rung environment (the plan's E6 rig), and a manual flight here is how
# the row schema gets eyeballed before a live deploy. Default off (the marker is REMOVED
# when unset, so a previous session's tracer never lingers). Output:
# test-server/fabric/logs/lss-move-trace.jsonl; validate with scripts/check_move_trace.py.
LSS_MOVE_TRACE="${LSS_MOVE_TRACE:-0}"

# Via variant (XVER plan §7 / mega plan R-8): LSS_VIA=1 stages ViaFabric+ViaBackwards on
# the Fabric server (ViaVersion+ViaBackwards on Paper) so the C5 cross-MC mismatch guard
# has something to fire against — the guard is DEAD CODE on a rig without Via. Old-MC
# clients for the denial test live in the lss-multi-test Prism profiles (v0.8.0-era jars).
# Watch for the "LOD unavailable for <name>: Via reports client protocol X vs server Y"
# INFO line; enableViaMismatchGuard=false in the staged config is the kill-switch A/B.
# Default off — every non-via run parks the jars so the baseline stays Via-free.
# Version resolution rides the Modrinth API (latest for this MC line) rather than pinned
# URLs, so the variant keeps working as Via ships new builds.
LSS_VIA="${LSS_VIA:-0}"
# The guard's rig kill switch (review MAJOR-1): the staging REWRITES the config each
# run, so a hand-edited enableViaMismatchGuard=false would be silently clobbered before
# the JVM starts — this knob is the supported A/B lever. Default 1 (the shipped default).
LSS_VIA_GUARD="${LSS_VIA_GUARD:-1}"
stage_move_trace_marker() {
    # Called just before each Fabric launch; the tracer reads the marker once at
    # SERVER_STARTING, so staging at launch time is race-free by operation.
    mkdir -p "$FABRIC_DIR/config"
    case "$LSS_MOVE_TRACE" in
        0|false|no|off) rm -f "$FABRIC_DIR/config/lss-move-trace.enable" ;;
        *)              touch "$FABRIC_DIR/config/lss-move-trace.enable" ;;
    esac
}

# LOD store for manual play: LSS_LODSTORE=off|on|full (default on — matching a fresh install; on==full) is written into
# the staged lss-server-config.json on EVERY run — the staging rewrites that file, so a
# hand-edit does not survive a re-run; this variable is the supported way to flip it.
# `run-fabric-store` / `run-paper-store` below force "full". The store DB lives at
# <world>/lss-lod/store.db and persists across restarts (derived data — deleting the
# lss-lod/ dir is always safe); eyeball it with '/lsslod store status' in-game.
# Default "on", matching a FRESH INSTALL since the 2026-08-08 config rework (the compiled
# default stays "off" so an UPGRADING server never silently arms the store, but a brand-new
# install's generated config says "on" — and a fresh test-server rig is the fresh-install
# case). run-fabric-store / run-paper-store therefore only differ from the plain
# entrypoints by FORCING the store on (immune to LSS_LODSTORE=off) + enabling backfill.
LSS_LODSTORE="${LSS_LODSTORE:-on}"
case "$LSS_LODSTORE" in
    off|on|full) ;; # "on" == "full" since the 2026-08-08 config rework
    *) echo "LSS_LODSTORE must be off, on, or full (got '$LSS_LODSTORE')" >&2; exit 1 ;;
esac
# Background store population (Fabric only — Paper has no backfill wiring yet, the key
# is inert there): lodStoreBackfill=true auto-starts a low-priority region walk that
# pre-warms the store, yielding to players and tick health (default 500 col/s cap —
# LSS_LODSTORE_BACKFILL_CPS below overrides — pauses under load). run-fabric-store
# forces it on; '/lsslod store backfill status|stop' to steer.
# Stays TRUE like the shipped default: the key is inert while the store is off, so this
# only decides what the store arms WITH once LSS_LODSTORE=full.
LSS_LODSTORE_BACKFILL="${LSS_LODSTORE_BACKFILL:-true}"
case "$LSS_LODSTORE_BACKFILL" in
    true|false) ;;
    *) echo "LSS_LODSTORE_BACKFILL must be true or false (got '$LSS_LODSTORE_BACKFILL')" >&2; exit 1 ;;
esac
# Optional backfill pace override (columns/second, server clamps 10..1000). UNSET means
# the key is omitted from the staged config so the server default (500) rules —
# run-fabric-store deliberately keeps the default.
LSS_LODSTORE_BACKFILL_CPS="${LSS_LODSTORE_BACKFILL_CPS:-}"
# Optional LOD-distance override (chunks, server clamps 32..2048). UNSET means the key is
# omitted so the shipped default (256) rules. The rig used to hardcode 64; 256 is 16x the
# area, so on a small box — or with all three test servers up at once — set this to 64 or
# 96 to keep generation and IO manageable while still exercising the real defaults
# elsewhere.
LSS_LOD_DISTANCE="${LSS_LOD_DISTANCE:-}"
if [ -n "$LSS_LOD_DISTANCE" ]; then
    case "$LSS_LOD_DISTANCE" in
        ''|*[!0-9]*) echo "LSS_LOD_DISTANCE must be a positive integer (got '$LSS_LOD_DISTANCE')" >&2; exit 1 ;;
    esac
    # Same overflow guard as the CPS knob: a value too large for an int makes GSON throw,
    # and JsonConfig's whole-file fallback then silently resets the ENTIRE staged config
    # to defaults rather than failing loudly.
    if [ "${#LSS_LOD_DISTANCE}" -gt 4 ]; then
        echo "LSS_LOD_DISTANCE too large — server clamps to 2048 (got '$LSS_LOD_DISTANCE')" >&2; exit 1
    fi
fi
if [ -n "$LSS_LODSTORE_BACKFILL_CPS" ]; then
    case "$LSS_LODSTORE_BACKFILL_CPS" in
        ''|*[!0-9]*) echo "LSS_LODSTORE_BACKFILL_CPS must be a positive integer (got '$LSS_LODSTORE_BACKFILL_CPS')" >&2; exit 1 ;;
    esac
    # Bound the digits too: an int-overflowing value staged into the JSON makes GSON
    # throw on load and JsonConfig's whole-file fallback silently resets EVERY key to
    # its default — the store/backfill run asked for would quietly not happen.
    if [ "${#LSS_LODSTORE_BACKFILL_CPS}" -gt 4 ]; then
        echo "LSS_LODSTORE_BACKFILL_CPS too large — server clamps to 1000 (got '$LSS_LODSTORE_BACKFILL_CPS')" >&2; exit 1
    fi
fi

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

# Resolve the newest Modrinth version file for a project/loader/MC-line and download it.
# Used by the Via variant (no pinned URLs — Via ships frequently and any current-MC build
# works for the guard's live pull).
download_modrinth_latest() {
    local slug="$1" loader="$2" mc_version="$3" dest="$4"
    if [ -f "$dest" ] || [ -f "$dest.disabled" ]; then
        echo "  Already present: $(basename "$dest")"
        return 0
    fi
    echo "  Resolving ${slug} (${loader}, MC ${mc_version}) via Modrinth..."
    local url
    url=$(curl -fsSL -A "lod-server-support/test-server"         "https://api.modrinth.com/v2/project/${slug}/version?loaders=%5B%22${loader}%22%5D&game_versions=%5B%22${mc_version}%22%5D"         | python3 -c "
import sys, json
versions = json.load(sys.stdin)
for v in versions:
    files = v.get('files', [])
    primary = next((f for f in files if f.get('primary')), files[0] if files else None)
    if primary:
        print(primary['url'])
        break
")
    if [ -z "$url" ]; then
        echo "ERROR: no ${slug} build for ${loader}/MC ${mc_version} on Modrinth" >&2
        return 1
    fi
    echo "  Downloading: $(basename "$url")"
    # .part + mv (review m8): a truncated curl must not leave a file the
    # already-present early-return then trusts forever.
    curl -fsSL -o "$dest.part" "$url" && mv "$dest.part" "$dest"
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

build_neoforge_jar() {
    local force="${1:-}" jar
    # shadowJar is THE artifact (the plain jar task is disabled — a slim jar would
    # match release globs; stage N-4).
    jar="$SCRIPT_DIR/neoforge/build/libs/lod-server-support-neoforge.jar"
    if [ -n "$force" ] || [ ! -f "$jar" ]; then
        echo "Building NeoForge LSS JAR..." >&2
        (cd "$SCRIPT_DIR" && ./gradlew :neoforge:shadowJar) >&2
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

# Stages the SHIPPED defaults, deliberately. This used to hand-write nine tuned values
# — lodDistanceChunks 64, 8 MiB/player, 40 MiB global, diskReaderThreads 8,
# sendQueueLimitPerPlayer 9600, generation caps 40/40 — dating from before those defaults
# were reviewed, and every one of them has since been superseded:
#   * diskReaderThreads 8 defeats the whole point of 0 = AUTO (which derives the pool from
#     the resolved read path), and 8 is precisely the over-provisioned figure the v0.9.0
#     review flagged on the unprioritized path;
#   * sendQueueLimitPerPlayer 9600 predates issue #62, which LOWERED the default to 1024
#     (= the wire batch cap) to stop unbounded snapshot backlog;
#   * 8 MiB/player and 40 MiB global sit far under the reviewed 25 MiB / 256 MiB, so the
#     rig throttled exactly the bandwidth behaviour it exists to eyeball.
# A dev rig that contradicts the shipped defaults tests a configuration no player runs.
# Only genuinely rig-specific keys belong here now; everything else falls through to the
# mod's own defaults. NOTE the shipped default lodDistanceChunks is 512 (restored
# 2026-08-13, reverting stage A's 300; ~64x the old rig's 64-chunk area) — set
# LSS_LOD_DISTANCE to dial it back on a small box or when running all three servers.
write_lss_config() {
    local dir="$1"
    echo "  Writing lss-server-config.json (shipped defaults; lodStore=${LSS_LODSTORE}, backfill=${LSS_LODSTORE_BACKFILL}${LSS_LOD_DISTANCE:+, lodDistance=${LSS_LOD_DISTANCE}}$([ "$LSS_VIA_GUARD" = 0 ] && echo ', viaGuard=OFF'))"
    mkdir -p "$dir"
    cat > "$dir/lss-server-config.json" << EOF
{
  "enabled": true,
  "enableChunkGeneration": true,
  "lodStore": "${LSS_LODSTORE}",
  "lodStoreBackfill": ${LSS_LODSTORE_BACKFILL}$(
    if [ -n "$LSS_LODSTORE_BACKFILL_CPS" ]; then
        printf ',\n  "lodStoreBackfillColumnsPerSecond": %s' "$LSS_LODSTORE_BACKFILL_CPS"
    fi)$(
    if [ -n "$LSS_LOD_DISTANCE" ]; then
        printf ',\n  "lodDistanceChunks": %s' "$LSS_LOD_DISTANCE"
    fi)$(
    if [ "$LSS_VIA_GUARD" = 0 ]; then
        printf ',\n  "enableViaMismatchGuard": false'
    fi)
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

    stage_via_fabric
}

run_fabric() {
    stage_move_trace_marker
    cd "$FABRIC_DIR"
    # admissionTrace: dev-only [lss-adm] lines (candidate ring vs frontier stamp per
    # generation-admission decision) — the instrument for far-arc/inversion reports.
    # Disable with LSS_ADMISSION_TRACE=0 (see the flag's definition near SERVER_RAM).
    java -Xmx${SERVER_RAM} -Xms${SERVER_RAM} "$ADMISSION_TRACE_FLAG" -jar fabric-server-launch.jar nogui
}

# Toggle <prefix>*.jar in the Fabric mods folder for A/B runs (c2me: LSS vs C2ME's
# chunk-system rewrite; antixray: the DrexHD AntiXray incompatibility repro). Disabling
# renames to .jar.disabled, which the Fabric loader ignores.
set_mod_enabled() {
    local prefix="$1" enabled="$2" f
    mkdir -p "$FABRIC_DIR/mods"
    if [ "$enabled" = true ]; then
        for f in "$FABRIC_DIR/mods/$prefix"*.jar.disabled; do
            [ -e "$f" ] || continue
            mv "$f" "${f%.disabled}"
            echo "  Re-enabled: $(basename "${f%.disabled}")"
        done
    else
        for f in "$FABRIC_DIR/mods/$prefix"*.jar; do
            [ -e "$f" ] || continue
            mv "$f" "$f.disabled"
            echo "  Disabled: $(basename "$f")"
        done
    fi
}

# ============================================================
# Paper
# ============================================================

# Enable Paper's built-in anti-xray (config/paper-world-defaults.yml) so LSS's LOD channel
# can be tested against packet-level ore obfuscation. Paper's ChunkPacketBlockController
# rewrites only vanilla chunk-packet buffers and never touches LSS's out-of-band
# LevelChunkSection.write serves — the historical bypass (real ores in LODs). Since the
# antixray-compat work (docs/planning/antixray-compat-design.md §3) LSS closes it: in the
# default "auto" mode it detects this per-world config and masks LOD columns with the SAME
# hidden list + max-block-height, so the eyeball with an xray resource pack should show
# ores hidden in BOTH near terrain and LOD rings (a pre-masking LSS jar shows the bypass).
enable_paper_antixray() {
    local cfg="$PAPER_DIR/config/paper-world-defaults.yml"
    if [ ! -f "$cfg" ]; then
        # First boot merges this partial file with Paper's full defaults.
        mkdir -p "$PAPER_DIR/config"
        echo "  Creating paper-world-defaults.yml (anti-xray enabled, engine-mode 1)"
        cat > "$cfg" << 'EOF'
anticheat:
  anti-xray:
    enabled: true
    engine-mode: 1
EOF
        return 0
    fi
    python3 - "$cfg" << 'EOF'
import re, sys
path = sys.argv[1]
src = open(path).read()
m = re.search(r'(?m)^([ \t]*)anti-xray:[^\n]*\n((?:\1[ \t]+[^\n]*\n?)*)', src)
if not m:
    print("  WARNING: no anti-xray block in paper-world-defaults.yml — enable it by hand")
    sys.exit(0)
block = m.group(0)
new_block, n = re.subn(r'(?m)^([ \t]+enabled:[ \t]*)false[ \t]*$', r'\1true', block, count=1)
if n:
    src = src.replace(block, new_block, 1)
    open(path, 'w').write(src)
    print("  Enabled anti-xray in paper-world-defaults.yml")
elif re.search(r'(?m)^[ \t]+enabled:[ \t]*true', block):
    print("  anti-xray already enabled in paper-world-defaults.yml")
else:
    print("  WARNING: anti-xray block has no recognisable enabled key — check it by hand")
EOF
}

# Park/unpark Paper plugin jars by filename prefix (the set_mod_enabled twin — Paper
# only loads *.jar, so .jar.disabled is ignored the same way).
set_paper_plugin_enabled() {
    local prefix="$1" enabled="$2" f
    mkdir -p "$PAPER_DIR/plugins"
    if [ "$enabled" = true ]; then
        for f in "$PAPER_DIR/plugins/$prefix"*.jar.disabled; do
            [ -e "$f" ] || continue
            mv "$f" "${f%.disabled}"
            echo "  Re-enabled: $(basename "${f%.disabled}")"
        done
    else
        for f in "$PAPER_DIR/plugins/$prefix"*.jar; do
            [ -e "$f" ] || continue
            mv "$f" "$f.disabled"
            echo "  Disabled: $(basename "$f")"
        done
    fi
}

# Stage or park the Via pair per LSS_VIA on both platforms (called from each setup).
stage_via_fabric() {
    if [ "$LSS_VIA" = 1 ]; then
        echo "=== Staging Via (Fabric): ViaFabric + ViaBackwards ==="
        # Non-fatal (review m9): a Modrinth outage must not abort the whole setup —
        # but a rig run whose entire point is Via needs to hear about it loudly.
        if download_modrinth_latest viafabric fabric "$FABRIC_MC_VERSION" "$FABRIC_DIR/mods/viafabric.jar" \
            && download_modrinth_latest viabackwards fabric "$FABRIC_MC_VERSION" "$FABRIC_DIR/mods/viabackwards.jar"; then
            set_mod_enabled viafabric true
            set_mod_enabled viabackwards true
        else
            echo "WARNING: Via staging FAILED — the server will run WITHOUT Via and the" >&2
            echo "         mismatch guard will be no-signal (nothing to test)." >&2
        fi
    else
        set_mod_enabled viafabric false
        set_mod_enabled viabackwards false
    fi
}

stage_via_paper() {
    if [ "$LSS_VIA" = 1 ]; then
        echo "=== Staging Via (Paper): ViaVersion + ViaBackwards ==="
        download_modrinth_latest viaversion paper "$PAPER_MC_VERSION" "$PAPER_DIR/plugins/viaversion.jar" \
            || download_modrinth_latest viaversion bukkit "$PAPER_MC_VERSION" "$PAPER_DIR/plugins/viaversion.jar"
        download_modrinth_latest viabackwards paper "$PAPER_MC_VERSION" "$PAPER_DIR/plugins/viabackwards.jar" \
            || download_modrinth_latest viabackwards bukkit "$PAPER_MC_VERSION" "$PAPER_DIR/plugins/viabackwards.jar"
        set_paper_plugin_enabled viaversion true
        set_paper_plugin_enabled viabackwards true
    else
        set_paper_plugin_enabled viaversion false
        set_paper_plugin_enabled viabackwards false
    fi
}

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
    enable_paper_antixray
    stage_via_paper

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
    # 1.21.10 status: Folia publishes NO 1.21.10 build (fill-verified at line creation,
    # 2026-08-22 — its versions jump 1.21.8 -> 1.21.11; folia-supported: false on this
    # line), so this probe correctly fails and the Folia rig is skipped.
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
    echo "  Installing LSS (same jar as Paper — folia-supported: true, EXPERIMENTAL)..."
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
# NeoForge (best-effort tier — stage N; server-side LSS, same wire as Fabric)
# ============================================================

setup_neoforge() {
    echo "=== Setting up NeoForge server (${NEOFORGE_VERSION}) ==="
    local mods_dir="$NEOFORGE_DIR/mods"
    mkdir -p "$NEOFORGE_DIR" "$mods_dir"

    # The installer lays down libraries/ + the version's unix_args.txt; that file is the
    # installed marker (one-time, downloads the vanilla server jar + NeoForge libraries).
    local args_file="libraries/net/neoforged/neoforge/${NEOFORGE_VERSION}/unix_args.txt"
    if [ ! -f "$NEOFORGE_DIR/$args_file" ]; then
        download "$NEOFORGE_INSTALLER_URL" "$NEOFORGE_DIR/neoforge-installer-${NEOFORGE_VERSION}.jar"
        echo "  Running the NeoForge server installer (one-time, downloads libraries)..."
        # Flag spelling differs across installer generations — try both.
        (cd "$NEOFORGE_DIR" && java -jar neoforge-installer-${NEOFORGE_VERSION}.jar --install-server . > installer.log 2>&1) \
            || (cd "$NEOFORGE_DIR" && java -jar neoforge-installer-${NEOFORGE_VERSION}.jar --installServer . >> installer.log 2>&1) \
            || { echo "ERROR: NeoForge installer failed — see $NEOFORGE_DIR/installer.log" >&2; return 1; }
        if [ ! -f "$NEOFORGE_DIR/$args_file" ]; then
            echo "ERROR: installer ran but $args_file is missing — see $NEOFORGE_DIR/installer.log" >&2
            return 1
        fi
    fi

    if [ ! -f "$NEOFORGE_DIR/eula.txt" ]; then
        echo "eula=true" > "$NEOFORGE_DIR/eula.txt"
    fi

    write_server_properties "$NEOFORGE_DIR" 25569 "LSS Test Server (NeoForge)"
    write_ops_json "$NEOFORGE_DIR"
    # Same config file + location as Fabric (LoaderServices.configDir -> <server>/config).
    write_lss_config "$NEOFORGE_DIR/config"

    echo "=== Installing NeoForge mods ==="
    echo "  Installing LSS..."
    local lss_jar
    lss_jar=$(build_neoforge_jar)
    rm -f "$mods_dir"/lod-server-support-neoforge*.jar
    cp "$lss_jar" "$mods_dir/"
    echo "  Installed: $(basename "$lss_jar")"
}

run_neoforge() {
    cd "$NEOFORGE_DIR"
    # Launch via the installer's args file (what the generated run.sh does), with our own
    # RAM + the dev admission trace. Fabric and vanilla clients CAN join this server:
    # LSS registers every payload channel .optional() and adds no registry content, so
    # NeoForge's connection negotiation treats a client without the mod as
    # vanilla-compatible (the stage-N interop matrix; a Fabric client WITH LSS announces
    # the lss:* channels via minecraft:register and gets a full LOD session).
    java -Xmx${SERVER_RAM} -Xms${SERVER_RAM} "$ADMISSION_TRACE_FLAG" \
        @"libraries/net/neoforged/neoforge/${NEOFORGE_VERSION}/unix_args.txt" nogui
}

# ============================================================
# Legacy (protocol-16 LSS server, for v16 client-compat eyeballing)
# ============================================================

setup_legacy() {
    echo "=== Setting up legacy (LSS v${LEGACY_LSS_VERSION}, protocol 16) Fabric server ==="
    local mods_dir="$LEGACY_DIR/mods"
    mkdir -p "$LEGACY_DIR" "$mods_dir"

    # Same Fabric server launcher + Fabric API family as the current Fabric server — only the
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

    stage_move_trace_marker
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
        setup_neoforge
        echo ""
        echo "Setup complete. Run '$0 run' to start all servers."
        ;;
    update)
        echo "=== Updating LSS JARs ==="
        fabric_jar=$(build_fabric_jar force)
        paper_jar=$(build_paper_jar force)
        neoforge_jar=$(build_neoforge_jar force)

        mkdir -p "$FABRIC_DIR/mods" "$PAPER_DIR/plugins" "$FOLIA_DIR/plugins" "$NEOFORGE_DIR/mods"

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

        rm -f "$NEOFORGE_DIR/mods"/lod-server-support-neoforge*.jar
        cp "$neoforge_jar" "$NEOFORGE_DIR/mods/"
        echo "  NeoForge: $(basename "$neoforge_jar")"

        echo "  Restart the servers to apply."
        ;;
    run)
        setup_fabric
        set_mod_enabled antixray false  # the all-servers run stays AntiXray-free (baseline)
        echo ""
        setup_paper
        echo ""
        setup_folia
        echo ""
        run_all
        ;;
    run-fabric)
        setup_fabric
        set_mod_enabled c2me true       # undo a previous run-fabric-no-c2me
        set_mod_enabled antixray false  # undo a previous run-fabric-antixray
        echo ""
        echo "=== Starting Fabric server ==="
        echo "  Connect to: localhost:25564"
        echo ""
        run_fabric
        ;;
    run-fabric-no-c2me)
        setup_fabric
        set_mod_enabled antixray false  # undo a previous run-fabric-antixray
        echo ""
        echo "=== Disabling C2ME for this run ==="
        set_mod_enabled c2me false
        echo ""
        echo "=== Starting Fabric server (C2ME disabled) ==="
        echo "  Connect to: localhost:25564"
        echo ""
        run_fabric
        ;;
    run-fabric-via)
        LSS_VIA=1
        setup_fabric
        set_mod_enabled c2me true       # same baseline as run-fabric, only Via differs
        set_mod_enabled antixray false
        echo ""
        echo "=== Starting Fabric server (ViaFabric + ViaBackwards enabled) ==="
        echo "  The C5 mismatch guard's live rig: join with an OLD-MC client (lss-multi-test"
        echo "  Prism profiles) running a v0.8.x/v0.9.x LSS build and watch for the"
        echo "  'LOD unavailable for <name>: Via reports client protocol X vs server Y' INFO."
        echo "  Kill-switch A/B: LSS_VIA_GUARD=0 $0 run-fabric-via (the staging rewrites"
        echo "  the config each run, so a hand-edit would be clobbered — use the knob)."
        echo "  Connect to: localhost:25564"
        echo ""
        run_fabric
        ;;
    run-fabric-antixray)
        setup_fabric
        set_mod_enabled c2me true       # same baseline as run-fabric, only AntiXray differs
        echo ""
        echo "=== Enabling AntiXray (DrexHD) for this run ==="
        # Skip the download while a previous run has it parked as .jar.disabled.
        if [ ! -f "$FABRIC_DIR/mods/antixray.jar.disabled" ]; then
            download "$ANTIXRAY_URL" "$FABRIC_DIR/mods/antixray.jar"
        fi
        set_mod_enabled antixray true
        echo ""
        echo "=== Starting Fabric server (AntiXray enabled) ==="
        echo "  Connect to: localhost:25564"
        echo ""
        run_fabric
        ;;
    run-fabric-store)
        LSS_LODSTORE=full
        LSS_LODSTORE_BACKFILL=true
        setup_fabric
        set_mod_enabled c2me true       # same baseline as run-fabric, only the store differs
        set_mod_enabled antixray false
        echo ""
        echo "=== Starting Fabric server (LOD store: full + background backfill) ==="
        echo "  Connect to: localhost:25564"
        echo "  Store DB: test-server/fabric/world/lss-lod/store.db — persists across runs"
        echo "  (a REJOIN after backfill should serve warm: watch '/lsslod store status'"
        echo "  and the client's /lss trace src:3 columns). Delete the lss-lod/ dir for a"
        echo "  cold-store run; run-fabric (store off) leaves the DB in place but unused."
        echo "  The backfill walk auto-starts after the startup sweep and pre-warms the"
        echo "  store from existing region files, nearest-spawn first — low priority,"
        echo "  yields to players/tick, resumes across restarts. Steer it with"
        echo "  '/lsslod store backfill status|stop|start'."
        echo ""
        run_fabric
        ;;
    run-paper)
        setup_paper
        echo ""
        echo "=== Starting Paper server ==="
        echo "  Connect to: localhost:25566"
        echo "  Paper native anti-xray is ENABLED (config/paper-world-defaults.yml)."
        echo ""
        run_paper
        ;;
    run-paper-store)
        LSS_LODSTORE=full
        setup_paper
        echo ""
        echo "=== Starting Paper server (LOD store: full) ==="
        echo "  Connect to: localhost:25566"
        echo "  Paper native anti-xray is ENABLED (config/paper-world-defaults.yml) — the"
        echo "  store fingerprints the mask per dimension and rebuilds on any change."
        echo "  Store DB: test-server/paper/world/lss-lod/store.db — persists across runs;"
        echo "  '/lsslod store status' shows health, '/lsslod store invalidate all' resets."
        echo "  NOTE: background backfill is Fabric-only for now — Paper's store warms"
        echo "  from serves (first joins backfill it; rejoins serve warm)."
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
    run-neoforge)
        setup_neoforge
        echo ""
        echo "=== Starting NeoForge server (${NEOFORGE_VERSION}) ==="
        echo "  Connect to: localhost:25569"
        echo "  Fabric/vanilla clients can join — LSS channels are .optional() and add no"
        echo "  registry content (a red/incompatible marker in the client's server LIST is"
        echo "  cosmetic; the join itself works). A Fabric client WITH LSS+Voxy gets a full"
        echo "  LOD session — the cross-loader wire is the point of this rig."
        echo "  NOTE best-effort tier: the /lsslod command tree + wire behavior should match"
        echo "  Fabric exactly. NeoForge terrain needs a Voxy build matching this MC/loader."
        echo "  Check the verified profile inventory; jar filenames alone are not compatibility proof."
        echo "  LSS far-player rendering on this NeoForge line: not available (intentional renderer stub)."
        echo ""
        run_neoforge
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
        echo "Usage: $0 {setup|run|run-fabric|run-fabric-no-c2me|run-fabric-antixray|run-fabric-via|run-fabric-store|run-paper|run-paper-store|run-folia|run-neoforge|run-legacy|update|clean}"
        echo "  (LSS_VIA=1 stages ViaVersion+ViaBackwards on run-paper too; LSS_VIA_GUARD=0 = guard kill-switch A/B)"
        echo ""
        echo "  setup      - Download and set up all servers"
        echo "  run        - Set up and start all servers (default)"
        echo "  run-fabric - Set up and start Fabric server only (port 25564; re-enables C2ME,"
        echo "               parks AntiXray)"
        echo "  run-fabric-no-c2me - Same, with any c2me*.jar in mods/ disabled (A/B testing)"
        echo "  run-fabric-antixray - Same as run-fabric plus DrexHD AntiXray — the live gate"
        echo "               for the AntiXray compat shim + masking (current builds must"
        echo "               survive a client join and serve masked; only pre-shim builds crash)"
        echo "  run-fabric-store - run-fabric with the store and backfill on (the store is"
        echo "               opt-in, so this is the store arm; plain run-fabric has it off)."
        echo "               Warm rejoins serve from world/lss-lod/store.db;"
        echo "               '/lsslod store status' + client /lss trace src:3 are the"
        echo "               eyeball instruments"
        echo "  run-paper  - Set up and start Paper server only (port 25566; native anti-xray on)"
        echo "  run-paper-store - run-paper with the store on (opt-in, so plain run-paper"
        echo "               has it off). No backfill on Paper (Fabric-only) — the store"
        echo "               warms from serves"
        echo "  run-folia  - Set up and start Folia server only (port 25567)"
        echo "  run-neoforge - Set up and start the NeoForge server only (port 25569; the"
        echo "               stage-N best-effort loader — server-side LSS, same wire as"
        echo "               Fabric; Fabric clients join fine, channels are optional)"
        echo "  run-legacy - Set up and start an OLD LSS v${LEGACY_LSS_VERSION} (protocol 16) server (port 25568),"
        echo "               for eyeballing the client-side v16 backward-compat path"
        echo "  update     - Rebuild and install LSS JARs for all servers incl. NeoForge (NOT the legacy one)"
        echo "  clean      - Delete all test server directories"
        echo ""
        echo "Environment variables:"
        echo "  SERVER_RAM  - Server memory allocation per server (default: 2G)"
        echo "  LSS_ADMISSION_TRACE - Fabric [lss-adm] generation-admission trace (default: 1)."
        echo "                        Set to 0 to silence it — it is verbose during backfill."
        echo "  LSS_MOVE_TRACE - Move-desync tracer on the Fabric server (default: 0). Set 1"
        echo "                   to stage config/lss-move-trace.enable; rows land in"
        echo "                   test-server/fabric/logs/lss-move-trace.jsonl (this rig is"
        echo "                   the tracer's vanilla-rung environment)."
        echo "  LSS_LODSTORE - lodStore mode written into EVERY staged lss-server-config.json"
        echo "                 (off|full, default: off — the shipped default; the store is"
        echo "                 opt-in). Hand-edits to the config do NOT survive a re-run"
        echo "                 — the staging rewrites it; this variable is the supported knob."
        echo "  LSS_LODSTORE_BACKFILL - lodStoreBackfill written the same way (true|false,"
        echo "                 default: true, matching the shipped default; inert unless"
        echo "                 LSS_LODSTORE=full). Fabric-only — inert on Paper/Folia too."
        echo "  LSS_LODSTORE_BACKFILL_CPS - optional backfill pace (columns/second, server"
        echo "                 clamps 10..1000). Unset = key omitted, server default (500)"
        echo "                 rules; run-fabric-store keeps the default."
        echo "  LSS_LOD_DISTANCE - optional lodDistanceChunks override. Unset = the shipped"
        echo "                 default (256). The rig used to hardcode 64; 256 is 16x the"
        echo "                 area, so set 64 or 96 on a small box or when running all"
        echo "                 three servers at once."
        echo ""
        echo "The staged config now carries the SHIPPED DEFAULTS. It used to hand-write nine"
        echo "tuned values (distance 64, 8 MiB/player, diskReaderThreads 8, sendQueue 9600,"
        echo "generation caps 40/40) that predated the config review and contradicted what"
        echo "players actually run — the rig tested a configuration nobody had."
        exit 1
        ;;
esac
