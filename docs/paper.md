# Paper

This page documents the Paper plugin implementation and its differences from the Fabric version.

## Overview

The Paper plugin (`paper/`) is a server-only implementation that provides the same LOD chunk distribution as Fabric, but uses Bukkit/Paper APIs and NMS access instead of Fabric's modding framework. Fabric clients connect to Paper servers transparently -- the wire protocol is identical.

Paper uses paperweight-userdev for compile-time NMS access with Mojang mappings. No reflection or mixins are needed -- Paper exposes the necessary internals directly.

## Differences from Fabric

### Networking

| Aspect | Fabric | Paper |
|--------|--------|-------|
| Payload registration | `PayloadTypeRegistry` + `StreamCodec` | Bukkit `registerIncomingPluginChannel()` |
| S2C sending | `ServerPlayNetworking.send(payload)` | `ClientboundCustomPayloadPacket` + `DiscardedPayload` via NMS |
| C2S receiving | `ServerPlayNetworking.registerGlobalReceiver()` | `PluginMessageListener.onPluginMessageReceived()` |
| Payload objects | `CustomPacketPayload` instances | `byte[]` arrays with channel name |
| Send queue entry | `QueuedPayload(CustomPacketPayload, ...)` | `QueuedPayload(byte[], String channel, ...)` |

Paper sends S2C payloads by constructing `ClientboundCustomPayloadPacket` with a `DiscardedPayload` wrapper, bypassing Bukkit's `sendPluginMessage()` channel registration check. This is necessary because Fabric clients using protocol v5+ don't register channels via `minecraft:register`.

C2S payloads arrive as raw byte arrays via `PluginMessageListener` and are decoded by `PaperPayloadHandler`.

### Disk Reading

| Aspect | Fabric | Paper |
|--------|--------|-------|
| ChunkMap access | `AccessorServerChunkCache` mixin | Direct: `((ServerChunkCache) level.getChunkSource()).chunkMap` |
| Region header timestamp | `RegionTimestampReader` fast-path | Same fast-path |
| NBT parsing | Codec-based deserialization | Same codec approach |
| Light data | From NBT tags on disk | Same approach |

Paper exposes `ChunkMap` directly through the NMS API, eliminating the need for the mixin accessor that Fabric uses.

### Chunk Generation

| Aspect | Fabric | Paper |
|--------|--------|-------|
| Load mechanism | `addRegionTicket()` / `removeRegionTicket()` | `World.getChunkAtAsync()` (Paper async API) |
| Ticket management | Manual ticket lifecycle | Automatic (Paper handles it) |
| Completion callback | Poll `getChunkNow()` each tick | `CompletableFuture.thenAccept()` on main thread |
| Timeout | Safety net: cancel after N ticks | Same safety net |

Fabric uses Minecraft's ticket system directly -- adding a loading ticket forces the chunk system to load/generate the chunk, and the service polls `getChunkNow()` each tick to check completion. Paper uses `World.getChunkAtAsync()`, which returns a `CompletableFuture` that completes on the main thread when the chunk is ready.

Both implementations share the same two-tier queue design (active + waiting), deduplication via callback lists, and closest-player promotion.

### Dirty Column Tracking

| Aspect | Fabric | Paper |
|--------|--------|-------|
| Chunk save hook | `ChunkMapSaveHook` mixin | Not implemented in Paper v1 |
| Change tracker | `ChunkChangeTracker` (static synchronized) | None |
| Dirty broadcast | `DirtyColumnsS2CPayload` at configured interval | Not sent |
| Client resync | Server-pushed dirty notifications | Client uses resync timer |

Paper v1 does not hook into chunk saves, so it cannot detect which columns have changed. Clients connected to Paper servers resync via the periodic resync batch mechanism (governed by `resyncBatchSize` in the client config) rather than server-pushed dirty column notifications.

### Send Queue

| Aspect | Fabric | Paper |
|--------|--------|-------|
| Payload type | `CustomPacketPayload` objects | Pre-encoded `byte[]` + channel name |
| Encoding | At send time via Fabric networking | At serialization time (pre-encoded) |
| Send call | `ServerPlayNetworking.send()` | `PaperPayloadHandler.sendRawNmsPayload()` |

Paper pre-encodes payloads to `byte[]` at serialization time (in the disk reader and generation service), then queues the raw bytes. This avoids re-encoding when the send queue flushes.

## Plugin Lifecycle

### LSSPaperPlugin

Extends `JavaPlugin`, implements `Listener` and `PluginMessageListener`.

**onEnable:**
1. Load `PaperConfig` from `plugins/LodServerSupport/lss-server-config.json`
2. Register incoming Plugin Messaging channels (3 C2S channels)
3. Register `PlayerQuitEvent` listener
4. Create `PaperRequestProcessingService`
5. Register `/lsslod` command via `PaperCommands`
6. Schedule tick task via `BukkitRunnable.runTaskTimer(plugin, 0L, 1L)`

**onDisable:**
1. Shut down `PaperRequestProcessingService`
2. Unregister Plugin Messaging channels

**onPluginMessageReceived:** Routes incoming messages by channel name:
- `lss:handshake_c2s` -> decode, validate, send session config, register player
- `lss:chunk_request` -> decode, forward to service
- `lss:cancel_request` -> decode, forward to service

**PlayerQuitEvent:** Removes player from `PaperRequestProcessingService`.

### Tick Scheduling

Fabric uses `ServerTickEvents.END_SERVER_TICK` to tick the service each server tick. Paper uses `BukkitRunnable.runTaskTimer(plugin, 0L, 1L)` which runs a task every tick on the main thread.

Both approaches ensure the service ticks synchronously on the server main thread.

## PaperRequestProcessingService

Mirrors `RequestProcessingService` from Fabric with these differences:

- Uses `PaperPlayerRequestState` (byte[] payloads) instead of `PlayerRequestState` (CustomPacketPayload)
- Uses `PaperChunkDiskReader` and `PaperChunkGenerationService`
- Sends via `PaperPayloadHandler.sendRawNmsPayload()` with channel IDs
- Omits dirty column broadcast phase

The 5-phase tick loop is otherwise identical: drain disk results, tick generation, calculate bandwidth, per-player flush/process/drain, (no dirty broadcast).

## PaperPayloadHandler

Central encoder/decoder for all payloads. Produces identical wire bytes to Fabric's `StreamCodec` implementations.

**S2C encoding methods:**
- `sendSessionConfig()` -- writes config fields, sends immediately
- `encodeChunkSection()` -- returns pre-encoded `byte[]` for queueing
- `sendRequestComplete()` -- writes batch ID + status, sends immediately
- `sendDirtyColumns()` -- writes position array, sends immediately (unused in v1)

**C2S decoding methods:**
- `decodeHandshakeProtocolVersion()` -- reads single VarInt
- `decodeChunkRequest()` -- reads batch ID, positions, timestamps
- `decodeCancelRequest()` -- reads batch ID array

**NMS sending:**
```java
// PaperPayloadHandler.sendRawNmsPayload()
ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
DiscardedPayload payload = new DiscardedPayload(channelId, new FriendlyByteBuf(Unpooled.wrappedBuffer(data)));
nmsPlayer.connection.send(new ClientboundCustomPayloadPacket(payload));
```

Channel identifiers are cached as `ResourceLocation` instances to avoid repeated parsing.

## Configuration

`PaperConfig` uses the same GSON-based loading pattern as Fabric's `JsonConfig`:

- File: `plugins/LodServerSupport/lss-server-config.json`
- Same fields and defaults as Fabric (minus `dirtyBroadcastIntervalSeconds`)
- Same validate-and-clamp-on-load behavior
- Auto-creates with defaults if file missing

See [Configuration](configuration.md) for the full field reference.

## Commands

Both `/lsslod stats` and `/lsslod diag` are available, registered via Bukkit's `CommandExecutor` interface:

**`/lsslod stats`** -- Per-player metrics:
- Handshake status
- Total sections sent, bytes sent
- Pending batches, pending disk reads
- Send queue size
- Request counts (received, rejected)

**`/lsslod diag`** -- Server diagnostics:
- Active config values
- Disk reader stats (submitted, completed, errors, empty)
- Generation stats (submitted, completed, timeouts, evicted)
- Tick diagnostics (sections sent, disk queued/drained, batches completed)

## NMS Access Pattern

Paper with paperweight-userdev provides compile-time access to NMS (net.minecraft.server) classes using Mojang mappings. Key NMS types used:

| NMS Type | Usage |
|----------|-------|
| `ServerPlayer` | Player entity for connection access |
| `ServerLevel` | World instance for chunk access |
| `ServerChunkCache` | Chunk source, provides `ChunkMap` |
| `ChunkMap` | Region file access for disk reads |
| `LevelChunk` | Loaded chunk data for serialization |
| `LevelChunkSection` | Section data (blocks + biomes) |
| `ClientboundCustomPayloadPacket` | NMS packet for Plugin Messaging |
| `DiscardedPayload` | Wraps raw bytes for custom channel payloads |
| `FriendlyByteBuf` | Minecraft's byte buffer (wraps Netty ByteBuf) |

Access pattern: `((CraftPlayer) bukkitPlayer).getHandle()` gives the NMS `ServerPlayer`, from which `.connection.send()` sends packets directly.
