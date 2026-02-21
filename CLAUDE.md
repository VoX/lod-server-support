# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

LOD Server Support (LSS) — a Fabric mod (Minecraft 1.21.11, Java 21) that distributes LOD chunk data from servers to clients over a custom networking protocol. Clients request distant chunks in batches; the server reads them from disk or memory and streams serialized sections back, enabling LOD rendering mods to display terrain beyond vanilla render distance.

## Build Commands

```bash
./gradlew build          # Build mod JAR (output: build/libs/lod-server-support-*.jar)
./gradlew clean          # Clean build artifacts
```

## Test Commands

```bash
./gradlew test -x runGameTest -x runClientGameTest  # Tier 1: JUnit unit tests only (fast, ~7s)
./gradlew runGameTest                                # Tier 2: server gametests (starts dedicated server, ~13s)
./gradlew test -x runClientGameTest                  # Tier 1 + 2 combined
./gradlew runClientGameTest                          # Tier 3: client gametests (starts integrated server + client)
./gradlew build -x runClientGameTest                 # Full build + Tier 1 + 2 tests
```

### Test Architecture

- **Tier 1** (`src/test/java/dev/vox/lss/`): 54 JUnit 5 tests via `fabric-loader-junit`. Tests position packing, bandwidth limiter, chunk change tracker, config validation, column cache store, and all 8 payload codecs (including zstd compression roundtrips).
- **Tier 2** (`src/gametest/java/dev/vox/lss/test/LSSGameTests.java`): 6 Fabric server gametests. Validates `RequestProcessingService` activation, diagnostics, command registration, and config loading inside a real dedicated server.
- **Tier 3** (`src/gametest/java/dev/vox/lss/test/LSSClientGameTests.java`): End-to-end client-server flow test. Validates handshake, session config, spiral scanning, chunk section receipt. Fabric Loom handles headless rendering automatically.

The system property `-Dlss.test.integratedServer=true` (set in build.gradle for gametest JVMs) allows `RequestProcessingService` to activate on integrated servers during testing.

## Architecture

### Entry Points

- `LSSMod` — server initializer: registers payloads, starts `RequestProcessingService` on dedicated servers
- `LSSClient` — client initializer: sets up client networking, commands, mod compatibility

### Networking Protocol (v4)

Client-request model with 8 payload types registered in `LSSNetworking`:

**C2S:** `HandshakeC2SPayload` → `ChunkRequestC2SPayload` (batched chunk positions + timestamps as packed longs) → `CancelRequestC2SPayload`

**S2C:** `SessionConfigS2CPayload` (limits/config) → `ChunkSectionS2CPayload` (zstd-compressed serialized blocks/biomes/light per section + columnTimestamp) → `ColumnUpToDateS2CPayload` (lightweight "no update needed" response) → `RequestCompleteS2CPayload` (batch status) → `DirtyColumnsS2CPayload` (server-pushed change notifications)

Flow: client handshakes → server sends session config → client scans in expanding spiral from player and sends batched requests with timestamps (0 for first request, stored timestamp for resync) → server compares timestamps: if client is current sends `ColumnUpToDateS2CPayload`, otherwise reads/serializes/compresses chunks with `columnTimestamp` → server signals batch complete. After initial scan completes, server periodically pushes `DirtyColumnsS2CPayload` listing changed columns, and client re-requests only those.

Chunk coordinates are packed as `((long)chunkX << 32) | (chunkZ & 0xFFFFFFFFL)`.

### Server-Side

- `RequestProcessingService` — orchestrates per-player processing, ticks on server thread, broadcasts dirty columns
- `PlayerRequestState` — per-player batch tracking, send queue, metrics
- `ChunkDiskReader` — async region file reader with configurable thread pool
- `SharedBandwidthLimiter` — fair per-tick bandwidth allocation across active players (global + per-player caps)
- `ChunkChangeTracker` — per-dimension set of recently saved chunk positions, drained on dirty broadcast
- `AccessorServerChunkCache` (mixin) — exposes `ChunkMap` for disk reads
- `AccessorChunkMap` / `ChunkMapSaveHook` (mixins) — hooks chunk saves to feed `ChunkChangeTracker`

### Client-Side

- `LodRequestManager` — expanding spiral scan, batch management, cancellation on player movement, dirty column re-requests, batch timeout (60s), timestamp pruning on movement
- `ColumnCacheStore` — per-server per-dimension binary cache of column positions and timestamps (enables resync across sessions)
- `LSSClientNetworking` — packet handlers, dispatches sections to `LSSApi` consumers

### Public API (`LSSApi`)

LOD rendering mods register a `ChunkSectionConsumer` to receive deserialized `LevelChunkSection` data with coordinates and optional light layers. Consumer list is `CopyOnWriteArrayList` for thread safety.

### Compatibility

`VoxyCompat` uses `MethodHandle` reflection to call Voxy's ingest API at runtime — zero compile-time dependency. `ModCompat` checks `FabricLoader.isModLoaded()` before attempting.

### Configuration

`JsonConfig` base class with GSON. Two configs auto-created in `config/`:
- `lss-server-config.json` — bandwidth caps, LOD distance, disk reader threads, send queue limits, dirty broadcast interval
- `lss-client-config.json` — receive toggle, distance override, resync batch size

All config values are clamped to safe ranges (min and max) on load.

## Key Patterns

- **Payloads** use Fabric `StreamCodec` with lambda encode/decode, registered in `LSSNetworking`
- **Thread safety** via `ConcurrentHashMap`, `AtomicLong`/`AtomicInteger`, `volatile`, `CopyOnWriteArrayList`
- **No compile-time optional deps** — all mod compat uses `MethodHandle` bridges
- **Mappings:** Mojang official (not Yarn)
- **Package:** `dev.vox.lss`
