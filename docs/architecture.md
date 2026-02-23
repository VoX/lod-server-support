# Architecture

This page covers the project structure, key components, and the server-side processing pipeline.

## Project Structure

LSS is a multi-project Gradle build with three subprojects:

```
lod-server-support/
├── common/   Pure Java utilities (no Minecraft dependencies)
├── fabric/   Fabric mod (client + server), Minecraft 1.21.1
└── paper/    Paper plugin (server only), Minecraft 1.21.1
```

The `common` module is included in both `fabric` and `paper` at build time. Fabric uses Fabric Loom with official Mojang mappings. Paper uses paperweight-userdev, which also provides Mojang mappings natively.

### Package Layout

| Module | Root Package |
|--------|-------------|
| common | `dev.vox.lss.common` |
| fabric | `dev.vox.lss` |
| paper | `dev.vox.lss.paper` |

## Common Module

The `common` module contains pure Java utilities with no Minecraft dependencies. Its only external dependency is SLF4J for logging.

### LSSConstants

Protocol constants shared between Fabric and Paper.

```java
// common/src/main/java/dev/vox/lss/common/LSSConstants.java

public static final int PROTOCOL_VERSION = 5;

// Channel identifiers
public static final String CHANNEL_HANDSHAKE       = "lss:handshake_c2s";
public static final String CHANNEL_CHUNK_REQUEST    = "lss:chunk_request";
public static final String CHANNEL_CANCEL_REQUEST   = "lss:cancel_request";
public static final String CHANNEL_SESSION_CONFIG   = "lss:session_config";
public static final String CHANNEL_CHUNK_SECTION    = "lss:chunk_section";
public static final String CHANNEL_REQUEST_COMPLETE = "lss:request_complete";
public static final String CHANNEL_COLUMN_UP_TO_DATE = "lss:column_up_to_date";
public static final String CHANNEL_DIRTY_COLUMNS    = "lss:dirty_columns";

// Wire format limits
public static final int MAX_CHUNK_REQUEST_POSITIONS = 1024;
public static final int MAX_CANCEL_BATCH_IDS        = 256;
public static final int MAX_DIRTY_COLUMN_POSITIONS   = 4096;
public static final int MAX_SECTION_DATA_SIZE        = 1_048_576;  // 1 MiB

// Status codes for RequestComplete
public static final int STATUS_DONE      = 0;
public static final int STATUS_CANCELLED = 1;
public static final int STATUS_REJECTED  = 2;
```

### PositionUtil

Packs two 32-bit chunk coordinates into a single 64-bit long:

```java
// common/src/main/java/dev/vox/lss/common/PositionUtil.java

public static long packPosition(int cx, int cz) {
    return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
}

public static int unpackX(long packed) {
    return (int) (packed >> 32);
}

public static int unpackZ(long packed) {
    return (int) packed;
}
```

This encoding is used throughout the protocol for chunk coordinate arrays.

### SharedBandwidthLimiter

Token bucket that divides a global bandwidth budget fairly among active players:

- Tokens refill proportionally to elapsed real time (sub-millisecond refills skipped, capped at 1 second to prevent overflow)
- `getPerPlayerAllocation(activeCount)` refills tokens, then returns `availableTokens / activeCount`
- `recordSend(bytes)` deducts from the global pool
- The server also applies a per-player cap (`maxBytesPerSecondPerPlayer`) on top of the fair share

### LSSLogger

Thin SLF4J wrapper providing a shared logger instance (`LSSLogger.LOG`).

## Fabric Entry Points

### LSSMod (Server Initializer)

`LSSMod.onInitialize()` runs on both client and server:

1. `LSSNetworking.registerPayloads()` -- registers all 8 payload type codecs with Fabric's `PayloadTypeRegistry`
2. `LSSServerNetworking.init()` -- registers server lifecycle events and packet receivers

### LSSClient (Client Initializer)

`LSSClient.onInitializeClient()` runs on client only:

1. `LSSClientNetworking.init()` -- registers client packet handlers and tick/join/disconnect events
2. `LSSClientCommands.init()` -- registers client-side `/lsslod` command
3. `ModCompat.init()` -- checks for installed LOD mods and registers compatibility bridges

### LSSServerNetworking Lifecycle

`LSSServerNetworking` wires Fabric events to the `RequestProcessingService`:

| Event | Action |
|-------|--------|
| `SERVER_STARTED` | Creates `RequestProcessingService` (skipped on integrated servers unless `-Dlss.test.integratedServer=true`) |
| `SERVER_STOPPING` | Calls `service.shutdown()` |
| `END_SERVER_TICK` | Calls `service.tick()` |
| Player `DISCONNECT` | Calls `service.removePlayer(uuid)` |

C2S packet receivers:

| Payload | Handler |
|---------|---------|
| `HandshakeC2SPayload` | Validates protocol version, sends `SessionConfigS2CPayload`, registers player |
| `ChunkRequestC2SPayload` | Forwards to `service.handleRequestBatch()` |
| `CancelRequestC2SPayload` | Forwards to `service.handleCancelRequest()` |

## Server Processing Pipeline

### RequestProcessingService

The central orchestrator. One instance per server, created on `SERVER_STARTED`. Maintains a `ConcurrentHashMap<UUID, PlayerRequestState>` of all connected players.

Key fields:
- `ChunkDiskReader diskReader` -- async region file reader (null if disk reading disabled)
- `ChunkGenerationService generationService` -- ticket-based chunk generator (null if generation disabled)
- `SharedBandwidthLimiter bandwidthLimiter` -- global token bucket
- `int serializationsThisTick` -- caps in-memory serializations to prevent tick monopolization
- `long chunkSequence` -- monotonic counter for ordering queued payloads

### Tick Loop (5 Phases)

Each server tick executes these phases in order:

```
+-------------------------------------------------------------------+
|                        Server Tick                                |
+-------------------------------------------------------------------+
|                                                                   |
|  Phase 1: Drain disk read results --> send queues                 |
|           (routes not-found to generation if eligible)            |
|                                                                   |
|  Phase 2: Tick generation service                                 |
|           Drain generation results --> send queues                |
|           (peek-pause if send queue full)                         |
|                                                                   |
|  Phase 3: Calculate per-player bandwidth allocation               |
|           globalBudget / activePlayerCount, capped per-player     |
|                                                                   |
|  Phase 4: For each player:                                        |
|     4a. Flush send queue (bandwidth + section/tick gated)         |
|     4b. Process request batches (submit to disk/gen/serialize)    |
|     4c. Drain completed batches (send RequestComplete)            |
|                                                                   |
|  Phase 5: Periodic dirty column broadcast                         |
|           (every dirtyBroadcastIntervalSeconds)                   |
|                                                                   |
+-------------------------------------------------------------------+
```

The ordering is deliberate: flushing the send queue (4a) before processing new batches (4b) keeps queue sizes stable.

### PlayerRequestState

Per-player state container:

| Field | Type | Purpose |
|-------|------|---------|
| `pendingBatches` | `LinkedHashMap<Integer, RequestBatch>` | FIFO queue of pending request batches |
| `sendQueue` | `PriorityQueue<QueuedPayload>` | Outbound payloads, ordered by submission sequence |
| `pendingDiskReads` | `LongOpenHashSet` | Packed positions with in-flight disk reads |
| `pendingGeneration` | `LongOpenHashSet` | Packed positions with in-flight generation |
| `diskReadDone` | `LongOpenHashSet` | Positions with completed async results |
| `cancelledBatchIds` | `IntOpenHashSet` | IDs of cancelled batches |
| `availableTokens` | `long` | Per-player bandwidth token bucket |

**RequestBatch** (inner class): Holds `batchId`, `positions[]`, `timestamps[]`, and a `nextPositionIndex` cursor. A batch is "fully processed" when the cursor reaches the end. It may be marked `cancelled` by a `CancelRequestC2SPayload`.

**QueuedPayload** (inner class): Wraps a `CustomPacketPayload` with `batchId`, `estimatedBytes`, and `submissionOrder`. The priority queue orders by `submissionOrder` to maintain FIFO within and across batches.

### Three Processing Pathways

When `processRequestBatches` walks a batch's positions, each takes one of three paths:

```
                        +------------------+
                        | Batch Position   |
                        +--------+---------+
                                 |
                    getChunkNow() != null?
                       /                \
                     yes                 no
                      |                   |
              +-------v-------+     disk reading enabled?
              | Path 1:       |        /           \
              | In-Memory     |      yes            no
              | Serialize on  |       |              |
              | server thread |  +----v----+    mark done
              +-------+-------+  | Path 2: |
                      |          | Disk     |
                      v          | Read     |
                  send queue     +----+-----+
                                      |
                                 result arrives
                                 next tick
                                      |
                              data found?
                              /         \
                            yes          no
                             |            |
                         send queue   within gen distance?
                                      /         \
                                    yes          no
                                     |            |
                              +------v------+  mark done
                              | Path 3:     |
                              | Generation  |
                              +------+------+
                                     |
                                chunk ready
                                     |
                                 send queue
```

**Path 1 -- In-Memory:** `getChunkNow()` returns a loaded chunk. Serialized immediately on the server thread. Each non-empty section becomes a `ChunkSectionS2CPayload` added to the send queue. Gated by `MAX_SERIALIZATIONS_PER_TICK` (1000).

**Path 2 -- Disk Read:** Submitted to `ChunkDiskReader`'s thread pool. Gated by `maxConcurrentDiskReads` per player (64) and `maxSendQueueSize` (4800). If either gate triggers, the batch cursor backs off and retries next tick.

**Path 3 -- Generation:** Submitted to `ChunkGenerationService` if within `generationDistanceChunks`. Gated by `pendingGenerationCount >= maxConcurrentGenerationsPerPlayer * 2`.

### ChunkDiskReader

Async region file reader with a fixed thread pool of daemon threads at minimum priority.

**Read flow:**
1. Fast-path: read 4-byte region header timestamp via `RegionTimestampReader`
2. If client timestamp >= header timestamp, return `upToDate=true` (no serialization needed)
3. Otherwise: call `ChunkMap.read()` with 10-second timeout
4. Parse NBT, check chunk status is FULL
5. Deserialize block states and biomes via `Codec<PalettedContainer<...>>`
6. Extract light data from NBT (`BlockLight`, `SkyLight` tags)
7. Serialize each section to a `ChunkSectionS2CPayload` with zstd compression
8. Post `ReadResult` to player's `ConcurrentLinkedQueue`

**ReadResult** (record): Contains `playerUuid`, `batchId`, `chunkX`, `chunkZ`, `payloads[]`, `columnTimestamp`, `upToDate`, `notFound`, and `submissionOrder`.

Results are drained on the server thread in phase 1 of the tick loop. Not-found results within generation distance are routed to the generation service.

### ChunkGenerationService

Ticket-based chunk generation with active/waiting queues:

| Queue | Capacity | Has Ticket |
|-------|----------|------------|
| Active | `maxConcurrentGenerations` (default 16) | Yes -- forces chunk load/generate |
| Waiting | Unbounded (backpressure in batch processor) | No -- lightweight metadata only |

**Deduplication:** Multiple requests for the same chunk (same dimension + coordinates) piggyback on one generation entry via a callback list.

**Promotion:** Each tick, waiting entries are promoted to active slots. Orphaned entries (all callback players disconnected) are evicted. Remaining entries are promoted closest-first (minimum Chebyshev distance to any callback's player).

**Timeout:** Active entries time out after `generationTimeoutSeconds * 20` ticks (default 60s). Timed-out entries return empty results.

**Per-player limits:** `maxConcurrentGenerationsPerPlayer` caps active generations per player. The batch processor's `pendingGenerationCount >= maxPerPlayer * 2` gate prevents flooding the waiting queue.

### ChunkChangeTracker

Static synchronized tracker of recently saved chunks per dimension:

- `markDirty(dimension, pos)` -- called by `ChunkMapSaveHook` mixin on chunk save
- `drainDirty(dimension)` -- returns and clears all dirty positions for a dimension

Used by `RequestProcessingService.broadcastDirtyColumns()` at `dirtyBroadcastIntervalSeconds` intervals. The broadcast filters positions to each player's LOD range and sends `DirtyColumnsS2CPayload`.

### Mixins (Fabric)

| Mixin | Target | Purpose |
|-------|--------|---------|
| `AccessorServerChunkCache` | `ServerChunkCache` | Exposes `ChunkMap` for disk reads |
| `AccessorChunkMap` | `ChunkMap` | Exposes internals for region file access |
| `ChunkMapSaveHook` | `ChunkMap` | Hooks chunk saves to feed `ChunkChangeTracker` |

## Component Data Flow

```
                 Client
                   |
          HandshakeC2S / ChunkRequestC2S / CancelRequestC2S
                   |
                   v
        +----------+----------+
        | LSSServerNetworking |
        +----------+----------+
                   |
                   v
     +-------------+---------------+
     | RequestProcessingService    |
     |  (orchestrator, server tick)|
     +----+--------+----------+---+
          |        |          |
          v        v          v
    +-----+--+ +--+------+ +-+----------+
    |In-Memory| |ChunkDisk| |ChunkGen    |
    |Serialize| |Reader   | |Service     |
    +---------+ |(threads)| |(tickets)   |
                +----+----+ +-----+------+
                     |            |
                     v            v
              +------+------------+------+
              |   PlayerRequestState     |
              |     sendQueue            |
              |   (PriorityQueue)        |
              +-----------+--------------+
                          |
                    bandwidth gate
                          |
                          v
            ChunkSectionS2C / ColumnUpToDateS2C /
            RequestCompleteS2C / DirtyColumnsS2C
                          |
                          v
                       Client
```
