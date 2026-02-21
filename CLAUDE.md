# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

LOD Server Support (LSS) — a Fabric mod (Minecraft 1.21.11, Java 21) that distributes LOD chunk data from servers to clients over a custom networking protocol. Clients request distant chunks in batches; the server reads them from disk or memory and streams serialized sections back, enabling LOD rendering mods to display terrain beyond vanilla render distance.

## Build Commands

```bash
./gradlew build          # Build mod JAR (output: build/libs/lod-server-support-*.jar)
./gradlew clean          # Clean build artifacts
./test-server.sh         # Set up and run a local Fabric test server with the mod
```

There are no automated tests. The test server script provisions a full Fabric server with Chunky for world pregeneration.

## Architecture

### Entry Points

- `LSSMod` — server initializer: registers payloads, starts `RequestProcessingService` on dedicated servers
- `LSSClient` — client initializer: sets up client networking, commands, mod compatibility

### Networking Protocol (v2)

Client-request model with 7 payload types registered in `LSSNetworking`:

**C2S:** `HandshakeC2SPayload` → `ChunkRequestC2SPayload` (batched chunk positions + timestamps as packed longs) → `CancelRequestC2SPayload`

**S2C:** `SessionConfigS2CPayload` (limits/config) → `ChunkSectionS2CPayload` (serialized blocks/biomes/light per section + columnTimestamp) → `ColumnUpToDateS2CPayload` (lightweight "no update needed" response) → `RequestCompleteS2CPayload` (batch status)

Flow: client handshakes → server sends session config → client scans in expanding spiral from player and sends batched requests with timestamps (0 for first request, stored timestamp for resync) → server compares timestamps: if client is current sends `ColumnUpToDateS2CPayload`, otherwise reads/serializes chunks with `columnTimestamp` → server signals batch complete. After fresh scan completes, client runs periodic resync sweeps to detect world changes.

Chunk coordinates are packed as `((long)chunkX << 32) | (chunkZ & 0xFFFFFFFFL)`.

### Server-Side

- `RequestProcessingService` — orchestrates per-player processing, ticks on server thread
- `PlayerRequestState` — per-player batch tracking, send queue, metrics
- `ChunkDiskReader` — async region file reader with configurable thread pool
- `SharedBandwidthLimiter` — fair per-tick bandwidth allocation across active players (global + per-player caps)
- `AccessorServerChunkCache` (mixin) — exposes `ChunkMap` for disk reads

### Client-Side

- `LodRequestManager` — expanding spiral scan, batch management, cancellation on player movement, periodic resync sweep with timestamp-based change detection
- `ColumnCacheStore` — per-server per-dimension binary cache of column positions and timestamps (enables resync across sessions)
- `LSSClientNetworking` — packet handlers, dispatches sections to `LSSApi` consumers

### Public API (`LSSApi`)

LOD rendering mods register a `ChunkSectionConsumer` to receive deserialized `LevelChunkSection` data with coordinates and optional light layers. Consumer list is `CopyOnWriteArrayList` for thread safety.

### Compatibility

`VoxyCompat` uses `MethodHandle` reflection to call Voxy's ingest API at runtime — zero compile-time dependency. `ModCompat` checks `FabricLoader.isModLoaded()` before attempting.

### Configuration

`JsonConfig` base class with GSON. Two configs auto-created in `config/`:
- `lss-server-config.json` — bandwidth caps, LOD distance, disk reader threads, send queue limits
- `lss-client-config.json` — receive toggle, distance override, resync interval/batch size

## Key Patterns

- **Payloads** use Fabric `StreamCodec` with lambda encode/decode, registered in `LSSNetworking`
- **Thread safety** via `ConcurrentHashMap`, `AtomicLong`/`AtomicInteger`, `volatile`, `CopyOnWriteArrayList`
- **No compile-time optional deps** — all mod compat uses `MethodHandle` bridges
- **Mappings:** Mojang official (not Yarn)
- **Package:** `dev.vox.lss`
