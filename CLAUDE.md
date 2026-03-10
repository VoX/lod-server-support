# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

LOD Server Support (LSS) — distributes LOD chunk data from servers to clients over a custom networking protocol. Supports both Fabric (client + server) and Paper (server only). Clients request distant chunks individually; the server reads them from disk or memory and streams serialized sections back, enabling LOD rendering mods to display terrain beyond vanilla render distance.

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

- **Tier 1** (`fabric/src/test/java/dev/vox/lss/`): JUnit 5 tests via `fabric-loader-junit`. Tests position packing, bandwidth limiter, config validation, column cache store, column timestamp cache, payload codecs, and negative/oversized payload edge cases.
- **Tier 2** (`fabric/src/gametest/java/dev/vox/lss/test/LSSGameTests.java`): 11 Fabric server gametests. Validates `RequestProcessingService` activation, diagnostics, command registration, and config loading inside a real dedicated server.
- **Tier 3** (`fabric/src/gametest/java/dev/vox/lss/test/LSSClientGameTests.java`): End-to-end client-server flow test. Validates handshake, session config, spiral scanning, chunk section receipt. Fabric Loom handles headless rendering automatically.

The system property `-Dlss.test.integratedServer=true` (set in fabric/build.gradle for gametest JVMs) allows `RequestProcessingService` to activate on integrated servers during testing.

## Architecture

### Entry Points

- **Fabric:** `LSSMod` — server initializer: registers payloads, starts `RequestProcessingService` on dedicated servers
- **Fabric:** `LSSClient` — client initializer: sets up client networking, commands, mod compatibility
- **Paper:** `LSSPaperPlugin` — plugin entry point: registers Plugin Messaging channels, starts `PaperRequestProcessingService`

### Networking Protocol (v15)

Batch model with 8 payload types. Fabric uses `LSSNetworking` with Fabric `StreamCodec`;
Paper uses `PaperPayloadHandler` with raw `FriendlyByteBuf` encoding. Both produce identical wire format.

**C2S:** `HandshakeC2SPayload` (capabilities bitmask) → `BatchChunkRequestC2SPayload` (batch of requestId + packed position + clientTimestamp tuples) → `CancelRequestC2SPayload` (cancel in-flight request) → `BandwidthUpdateC2SPayload` (client desired bandwidth)

**S2C:** `SessionConfigS2CPayload` (limits/config/rate limits/generation toggle) → `VoxelColumnS2CPayload` (requestId + MC-native sections + columnTimestamp) → `BatchResponseS2CPayload` (batch of responseType + requestId pairs — covers rate-limited, up-to-date, and not-generated) → `DirtyColumnsS2CPayload` (server-pushed change notifications)

Flow: client handshakes with capabilities bitmask (CAPABILITY_VOXEL_COLUMNS set if LSSApi has consumers) → server sends session config with rate limits → client scans in expanding spiral every second (20 ticks) and sends batched requests with clientTimestamp (-1 for unknown/first request, 0 for generation request, >0 for resync) → server routes by timestamp: sync-on-load path (clientTimestamp > 0 or -1) checks rate limiter then reads disk, generation path (clientTimestamp == 0) checks rate limiter then generates → server batches lightweight responses (rate-limited, up-to-date, not-generated) per tick; column data payloads are sent individually. After initial scan, server periodically pushes `DirtyColumnsS2CPayload` listing changed columns, client re-requests only those.

Chunk coordinates are packed as `((long)chunkX << 32) | (chunkZ & 0xFFFFFFFFL)`.

### Common Module (`common/`)

- `LSSConstants` — protocol version, channel IDs, wire format limits, status codes
- `LSSLogger` — SLF4J logging wrapper
- `SharedBandwidthLimiter` — fair per-tick bandwidth allocation across active players (global + per-player caps)
- `PositionUtil` — chunk coordinate packing/unpacking
- `DiagnosticsFormatter` — formats diagnostic data for `/lsslod diag` output
- `ColumnTimestampCache` — in-memory per-dimension `(packedXZ → epochSeconds)` timestamp cache for up-to-date checks without disk IO
- `OffThreadProcessor` — abstract base for main-thread → processing-thread handoff (prepares pre-serialized column data off-thread, routes requests via `IncomingRequestRouter`)
- `IncomingRequestRouter` — routes incoming chunk requests: checks timestamps for up-to-date, probes loaded chunks, dispatches disk reads, with cross-player dedup via `DedupTracker`
- `RateLimiterSet` — holds sync-on-load + generation limiter pair per player
- `DirtyColumnTracker` — thread-safe tracker of per-dimension dirty chunk positions, drained by broadcasters
- `SendActionBatcher` — reusable per-tick accumulator for batching `SendAction` responses by player

### Fabric Server-Side (`fabric/`)

- `RequestProcessingService` — orchestrates per-player processing, ticks on server thread, broadcasts dirty columns
- `PlayerRequestState` — per-player request state, rate limiters, send queue, metrics
- `ChunkDiskReader` — async region file reader with configurable thread pool
- `FabricOffThreadProcessor` — extends `OffThreadProcessor`; main thread probes loaded chunks via `SectionSerializer`, processing thread prepares payloads and schedules sends
- `SectionSerializer` — serializes loaded MC `LevelChunkSection` + light data into wire-format bytes using MC's native `section.write(buf)`
- `NbtSectionSerializer` — parses region file NBT into `LevelChunkSection` objects, then serializes them (used by `ChunkDiskReader`)
- `ChunkGenerationService` — ticket-based chunk generation, auto-triggered on disk-read "not found"
- `DirtyColumnBroadcaster` — periodically pushes dirty column notifications to connected players
- `LSSNetworking` — `PayloadTypeRegistry` registration for all C2S and S2C payload types
- `LSSServerNetworking` — server-side event listeners, `RequestProcessingService` lifecycle (started via `ServerLifecycleEvents.SERVER_STARTED`)
- `AccessorServerChunkCache` (mixin) — exposes `ChunkMap` for disk reads
- `AccessorChunkMap` / `ChunkMapSaveHook` (mixins) — hooks chunk saves to feed `DirtyColumnTracker`

### Paper Server-Side (`paper/`)

- `PaperRequestProcessingService` — same orchestration as Fabric, sends via Plugin Messaging instead of Fabric networking
- `PaperPlayerRequestState` — per-player state adapted for `byte[]` payloads instead of `CustomPacketPayload`
- `PaperChunkDiskReader` — async disk reader using direct NMS `ChunkMap` access (no mixin needed)
- `PaperOffThreadProcessor` — extends `OffThreadProcessor`; same main-thread/off-thread split as Fabric
- `PaperSectionSerializer` — Paper-side serializer using NMS `LevelChunkSection.write(buf)` + light data
- `PaperNbtSectionSerializer` — parses region file NBT into `LevelChunkSection` objects, then serializes them (used by `PaperChunkDiskReader`)
- `PaperChunkGenerationService` — async chunk generation via Paper's `World.getChunkAtAsync()` API, auto-triggered on disk-read "not found"
- `PaperPayloadHandler` — encodes S2C / decodes C2S payloads using same wire format as Fabric
- `PaperCommands` — Bukkit `CommandExecutor` for `/lsslod stats` and `/lsslod diag`
- `PaperConfig` — GSON JSON config (same defaults/validation as Fabric)
- `PaperDirtyColumnBroadcaster` — broadcasts dirty column notifications to connected players (same as Fabric's `DirtyColumnBroadcaster`)
- `PaperWorldHandler` — registers configurable Bukkit event listeners for dirty chunk detection via reflection-based position extraction

### Client-Side

- `LodRequestManager` — expanding spiral scan with 1-second (20-tick) scan interval, per-request tracking, rate-limit retry, request cancellation, dirty column re-requests, timestamp pruning on movement
- `ColumnCacheStore` — per-server per-dimension binary cache of column positions and timestamps (enables resync across sessions)
- `LSSClientNetworking` — packet handlers, dispatches sections to `LSSApi` consumers

### Public API (`LSSApi`)

LOD rendering mods register a `VoxelColumnConsumer` via `LSSApi.registerColumnConsumer()` to receive `VoxelColumnData` (MC-native `LevelChunkSection` + `DataLayer` light data per section, with coordinates). Consumer list is `CopyOnWriteArrayList` for thread safety. The client sets `CAPABILITY_VOXEL_COLUMNS` in the handshake only if consumers are registered.

### Compatibility

`VoxyCompat` uses `MethodHandle` reflection to call Voxy's `rawIngest` API at runtime — zero compile-time dependency, only 2 core MethodHandles (`WorldIdentifier.of` + `VoxelIngestService.rawIngest`). MC types (`LevelChunkSection`, `DataLayer`) must be referenced via direct class literals (not `Class.forName()`) due to Fabric's runtime remapping. `ModCompat` checks `FabricLoader.isModLoaded()` before attempting.

### Configuration

**Fabric:** `JsonConfig` base class with GSON. Two configs auto-created in `config/`:
- `lss-server-config.json` — bandwidth caps, LOD distance, disk reader threads, send queue limits, rate limits, generation toggle, dirty broadcast interval, concurrency limits
- `lss-client-config.json` — receive toggle (`receiveServerLods`), distance override (`lodDistanceChunks`), off-thread section processing toggle (`offThreadSectionProcessing`)

**Paper:** `PaperConfig` with GSON. Config at `plugins/LodServerSupport/lss-server-config.json` — same fields and defaults as Fabric server config, plus an `updateEvents` list of Bukkit event class names for dirty chunk detection.

All config values are clamped to safe ranges (min and max) on load.

## Benchmarking

Automated benchmark system for measuring server pipeline performance. Gated behind `-Dlss.benchmark=true` — never activates in production.

### Quick Start

```bash
./scripts/benchmark.sh fresh 60       # Fresh world, 60s — tests generation + serialization
./scripts/benchmark.sh no-cache 60    # Pre-built world — tests disk-read + serialization
```

Run `fresh` first to generate a base world, then `no-cache` reuses it.

### Optimization Workflow

1. Run baseline: `./scripts/benchmark.sh fresh 60`
2. Make optimization changes
3. Run again: `./scripts/benchmark.sh fresh 60`
4. Compare `benchmark-results/server.json` — key metrics: `throughput.sections_per_second`, `throughput.bytes_per_second`, `disk_reader.avg_read_time_ms`, `jvm.gc_time_ms`
5. Client metrics in `benchmark-results/client.json` — key: `avg_rtt_ms`, `columns_received`, `total_rate_limited`

### Output Files

- `benchmark-results/server.json` — server throughput, sources, disk reader, generation, rate limiting, bandwidth, JVM stats
- `benchmark-results/client.json` — columns received, bytes, send cycles, RTT, bandwidth rate
- `benchmark-results/*.jfr` — Java Flight Recorder profiles for both server and client

### How It Works

The shell script builds the mod, starts a dedicated server (`runBenchmarkServer`) and client (`runBenchmarkClient`) as separate Gradle tasks. The client auto-joins via `--quickPlayMultiplayer`. After the configured duration, the server exports metrics and halts; the client exports on disconnect. Duration is configurable via `-Pbenchmark.duration=N` on the server Gradle task.

### Key Files

- `fabric/src/main/java/dev/vox/lss/benchmark/BenchmarkHook.java` — tick counter, auto-shutdown, metric dump
- `fabric/src/main/java/dev/vox/lss/benchmark/BenchmarkMetricsExporter.java` — JSON serialization of all diagnostic + JVM data
- `scripts/benchmark.sh` — orchestrator (build → start server → start client → wait → collect)

### Scenarios Explained

| Scenario | World State | Tests |
|----------|------------|-------|
| `fresh` | Empty world, chunks generated on demand | Generation + serialization pipeline |
| `no-cache` | Pre-built world | Disk-read + serialization pipeline |

### Interpreting Results

**Server `server.json`:**
- `throughput.sections_per_second` — primary throughput metric
- `sources` — where data came from: `in_memory` (loaded chunks), `disk_read`, `generation`
- `disk_reader.avg_read_time_ms` — disk I/O latency
- `rate_limiting.sync_rate_limited` / `gen_rate_limited` — requests rejected by rate limiter
- `jvm.gc_time_ms` — GC overhead during benchmark

**Client `client.json`:**
- `avg_rtt_ms` — round-trip time for chunk requests
- `total_not_generated` — chunks the server couldn't serve (fresh world only)
- `total_rate_limited` — client-observed rate limiting

## Key Patterns

- **Java 21** target (`options.release = 21`). Uses `var`, records, sealed-pattern `instanceof`, switch expressions.
- **Payloads:** Fabric uses `StreamCodec` with lambda encode/decode registered in `LSSNetworking`; Paper uses raw `FriendlyByteBuf` in `PaperPayloadHandler`. Both produce identical wire bytes.
- **Raw chunk shipping:** Server serializes MC-native `LevelChunkSection` data (block states + biomes via `section.write(buf)`) plus light `DataLayer` nibbles and sends. MC's built-in zlib network compression handles packet compression. No server-side voxelization or caching — client receives MC objects directly.
- **Off-thread processing:** Main server thread serializes loaded chunks into `LoadedColumnData` (pre-serialized bytes); a dedicated processing thread prepares payloads and schedules sends via `OffThreadProcessor`. `ColumnTimestampCache` provides in-memory up-to-date checks.
- **Thread safety** via `ConcurrentHashMap`, `AtomicLong`/`AtomicInteger`, `volatile`, `CopyOnWriteArrayList`
- **No compile-time optional deps** — all mod compat uses `MethodHandle` bridges (MC types must use direct class literals, not `Class.forName()`, due to Fabric remapping)
- **Mappings:** Mojang official (not Yarn). Paper uses Mojang mappings natively via paperweight-userdev.
- **Package:** `dev.vox.lss` (Fabric), `dev.vox.lss.paper` (Paper), `dev.vox.lss.common` (shared)
