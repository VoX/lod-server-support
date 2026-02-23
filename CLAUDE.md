# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

LOD Server Support (LSS) — distributes LOD chunk data from servers to clients over a custom networking protocol. Supports both Fabric (client + server) and Paper (server only). Clients request distant chunks in batches; the server reads them from disk or memory and streams serialized sections back, enabling LOD rendering mods to display terrain beyond vanilla render distance.

## Project Structure

Multi-project Gradle build with three subprojects:

```
lod-server-support/
├── common/   Pure Java utilities (no MC deps) — shared by fabric/ and paper/
├── fabric/   Fabric mod (client + server), Minecraft 1.21.11
└── paper/    Paper plugin (server only), Minecraft 1.21.11
```

## Build Commands

```bash
./gradlew :fabric:build -x runClientGameTest  # Build Fabric mod + Tier 1 & 2 tests
./gradlew :paper:shadowJar                    # Build Paper plugin JAR
./gradlew clean                               # Clean build artifacts
```

Output JARs:
- `fabric/build/libs/lod-server-support-fabric-*.jar` — Fabric mod (client + server)
- `paper/build/libs/lod-server-support-paper-all.jar` — Paper plugin (server only, shadow JAR)

## Test Commands

```bash
./gradlew :fabric:test -x runGameTest -x runClientGameTest  # Tier 1: JUnit unit tests only (fast, ~7s)
./gradlew :fabric:runGameTest                                # Tier 2: server gametests (starts dedicated server, ~13s)
./gradlew :fabric:test -x runClientGameTest                  # Tier 1 + 2 combined
./gradlew :fabric:runClientGameTest                          # Tier 3: client gametests (starts integrated server + client)
./gradlew :fabric:build -x runClientGameTest                 # Full build + Tier 1 + 2 tests
```

Tests only exist for the Fabric module. Paper is compile-only (manual testing required).

### Test Architecture

- **Tier 1** (`fabric/src/test/java/dev/vox/lss/`): 54 JUnit 5 tests via `fabric-loader-junit`. Tests position packing, bandwidth limiter, chunk change tracker, config validation, column cache store, and all 8 payload codecs (including zstd compression roundtrips).
- **Tier 2** (`fabric/src/gametest/java/dev/vox/lss/test/LSSGameTests.java`): 6 Fabric server gametests. Validates `RequestProcessingService` activation, diagnostics, command registration, and config loading inside a real dedicated server.
- **Tier 3** (`fabric/src/gametest/java/dev/vox/lss/test/LSSClientGameTests.java`): End-to-end client-server flow test. Validates handshake, session config, spiral scanning, chunk section receipt. Fabric Loom handles headless rendering automatically.

The system property `-Dlss.test.integratedServer=true` (set in fabric/build.gradle for gametest JVMs) allows `RequestProcessingService` to activate on integrated servers during testing.

## Architecture

### Entry Points

- **Fabric:** `LSSMod` — server initializer: registers payloads, starts `RequestProcessingService` on dedicated servers
- **Fabric:** `LSSClient` — client initializer: sets up client networking, commands, mod compatibility
- **Paper:** `LSSPaperPlugin` — plugin entry point: registers Plugin Messaging channels, starts `PaperRequestProcessingService`

### Networking Protocol (v5)

Client-request model with 8 payload types. Fabric uses `LSSNetworking` with Fabric `StreamCodec`; Paper uses `PaperPayloadHandler` with raw `FriendlyByteBuf` encoding. Both produce identical wire format over Plugin Messaging channels:

**C2S:** `HandshakeC2SPayload` → `ChunkRequestC2SPayload` (batched chunk positions + timestamps as packed longs) → `CancelRequestC2SPayload`

**S2C:** `SessionConfigS2CPayload` (limits/config) → `ChunkSectionS2CPayload` (zstd-compressed serialized blocks/biomes/light per section + columnTimestamp) → `ColumnUpToDateS2CPayload` (lightweight "no update needed" response) → `RequestCompleteS2CPayload` (batch status) → `DirtyColumnsS2CPayload` (server-pushed change notifications)

Flow: client handshakes → server sends session config → client scans in expanding spiral from player and sends batched requests with timestamps (0 for first request, stored timestamp for resync) → server compares timestamps: if client is current sends `ColumnUpToDateS2CPayload`, otherwise reads/serializes/compresses chunks with `columnTimestamp` → server signals batch complete. After initial scan completes, server periodically pushes `DirtyColumnsS2CPayload` listing changed columns, and client re-requests only those.

Chunk coordinates are packed as `((long)chunkX << 32) | (chunkZ & 0xFFFFFFFFL)`.

### Common Module (`common/`)

- `LSSConstants` — protocol version, channel IDs, wire format limits, status codes
- `LSSLogger` — SLF4J logging wrapper
- `SharedBandwidthLimiter` — fair per-tick bandwidth allocation across active players (global + per-player caps)
- `PositionUtil` — chunk coordinate packing/unpacking

### Fabric Server-Side (`fabric/`)

- `RequestProcessingService` — orchestrates per-player processing, ticks on server thread, broadcasts dirty columns
- `PlayerRequestState` — per-player batch tracking, send queue, metrics
- `ChunkDiskReader` — async region file reader with configurable thread pool
- `ChunkChangeTracker` — per-dimension set of recently saved chunk positions, drained on dirty broadcast
- `AccessorServerChunkCache` (mixin) — exposes `ChunkMap` for disk reads
- `AccessorChunkMap` / `ChunkMapSaveHook` (mixins) — hooks chunk saves to feed `ChunkChangeTracker`

### Paper Server-Side (`paper/`)

- `PaperRequestProcessingService` — same orchestration as Fabric, sends via Plugin Messaging instead of Fabric networking
- `PaperPlayerRequestState` — per-player state adapted for `byte[]` payloads instead of `CustomPacketPayload`
- `PaperChunkDiskReader` — async disk reader using direct NMS `ChunkMap` access (no mixin needed)
- `PaperChunkGenerationService` — ticket-based chunk generation via `addRegionTicket`/`removeRegionTicket`
- `PaperPayloadHandler` — encodes S2C / decodes C2S payloads using same wire format as Fabric
- `PaperCommands` — Bukkit `CommandExecutor` for `/lsslod stats` and `/lsslod diag`
- `PaperConfig` — GSON JSON config (same defaults/validation as Fabric)

Note: Paper v1 does not include dirty column tracking (no `ChunkMapSaveHook` equivalent). Clients resync via the `resyncBatchSize` mechanism during initial join sweeps.

### Client-Side

- `LodRequestManager` — expanding spiral scan, batch management, cancellation on player movement, dirty column re-requests, batch timeout (60s), timestamp pruning on movement
- `ColumnCacheStore` — per-server per-dimension binary cache of column positions and timestamps (enables resync across sessions)
- `LSSClientNetworking` — packet handlers, dispatches sections to `LSSApi` consumers

### Public API (`LSSApi`)

LOD rendering mods register a `ChunkSectionConsumer` to receive deserialized `LevelChunkSection` data with coordinates and optional light layers. Consumer list is `CopyOnWriteArrayList` for thread safety.

### Compatibility

`VoxyCompat` uses `MethodHandle` reflection to call Voxy's ingest API at runtime — zero compile-time dependency. `ModCompat` checks `FabricLoader.isModLoaded()` before attempting.

### Configuration

**Fabric:** `JsonConfig` base class with GSON. Two configs auto-created in `config/`:
- `lss-server-config.json` — bandwidth caps, LOD distance, disk reader threads, send queue limits, dirty broadcast interval
- `lss-client-config.json` — receive toggle, distance override, resync batch size

**Paper:** `PaperConfig` with GSON. Config at `plugins/LodServerSupport/config.json` — same fields and defaults as Fabric server config.

All config values are clamped to safe ranges (min and max) on load.

## Key Patterns

- **Payloads:** Fabric uses `StreamCodec` with lambda encode/decode registered in `LSSNetworking`; Paper uses raw `FriendlyByteBuf` in `PaperPayloadHandler`. Both produce identical wire bytes.
- **Thread safety** via `ConcurrentHashMap`, `AtomicLong`/`AtomicInteger`, `volatile`, `CopyOnWriteArrayList`
- **No compile-time optional deps** — all mod compat uses `MethodHandle` bridges
- **Mappings:** Mojang official (not Yarn). Paper uses Mojang mappings natively via paperweight-userdev.
- **Package:** `dev.vox.lss` (Fabric), `dev.vox.lss.paper` (Paper), `dev.vox.lss.common` (shared)
