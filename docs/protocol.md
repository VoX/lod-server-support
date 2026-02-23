# Protocol Specification

This page documents the LSS protocol v5 wire format. Both Fabric and Paper produce identical bytes on the wire.

## Overview

LSS uses a client-request model with 8 payload types transmitted over Minecraft's Plugin Messaging channels. The client initiates requests; the server responds with chunk data or status updates.

### Channel Identifiers

| Direction | Channel | Payload |
|-----------|---------|---------|
| C2S | `lss:handshake_c2s` | HandshakeC2S |
| C2S | `lss:chunk_request` | ChunkRequestC2S |
| C2S | `lss:cancel_request` | CancelRequestC2S |
| S2C | `lss:session_config` | SessionConfigS2C |
| S2C | `lss:chunk_section` | ChunkSectionS2C |
| S2C | `lss:column_up_to_date` | ColumnUpToDateS2C |
| S2C | `lss:request_complete` | RequestCompleteS2C |
| S2C | `lss:dirty_columns` | DirtyColumnsS2C |

## Wire Primitives

All primitives use Minecraft's `FriendlyByteBuf` encoding (big-endian):

| Type | Size | Encoding |
|------|------|----------|
| VarInt | 1-5 bytes | Variable-length encoded int |
| Int | 4 bytes | Big-endian signed 32-bit |
| Long | 8 bytes | Big-endian signed 64-bit |
| Byte | 1 byte | Unsigned 8-bit |
| Boolean | 1 byte | 0 = false, 1 = true |
| Utf(maxLen) | VarInt + bytes | VarInt byte length, then UTF-8 bytes (max `maxLen` chars) |
| ByteArray(maxLen) | VarInt + bytes | VarInt byte length, then raw bytes (max `maxLen` bytes) |
| RawBytes(n) | n bytes | Fixed-size raw byte copy (no length prefix) |

## Position Packing

Chunk coordinates are packed into a single Long:

```
packed = ((long)chunkX << 32) | (chunkZ & 0xFFFFFFFFL)

chunkX = (int)(packed >> 32)
chunkZ = (int)packed
```

This supports the full signed 32-bit coordinate range.

## Payload Specifications

### HandshakeC2S

Sent by the client immediately after joining the server.

| Field | Type | Description |
|-------|------|-------------|
| `protocolVersion` | VarInt | Client's protocol version (current: 5) |

The server validates the version and responds with `SessionConfigS2C`. If the version doesn't match, the server logs a warning and sends `enabled=false` in the session config.

### ChunkRequestC2S

Batched chunk position request with timestamps for resync detection.

| Field | Type | Description |
|-------|------|-------------|
| `batchId` | VarInt | Client-assigned batch identifier |
| `count` | VarInt | Number of positions |
| `positions` | Long[count] | Packed chunk coordinates |
| `timestamps` | Long[count] | Client timestamps (0=first request, >0=resync) |

Wire limits:
- Positions beyond `MAX_CHUNK_REQUEST_POSITIONS` (1024) are read from the buffer but discarded
- Timestamps are optional -- if the buffer ends early, remaining timestamps default to 0

### CancelRequestC2S

Cancels one or more previously sent batches (e.g., when the player moves and positions go out of range).

| Field | Type | Description |
|-------|------|-------------|
| `count` | VarInt | Number of batch IDs |
| `batchIds` | VarInt[count] | Batch IDs to cancel |

Wire limit: IDs beyond `MAX_CANCEL_BATCH_IDS` (256) are read but discarded.

### SessionConfigS2C

Server's response to the handshake. Configures the client's request parameters.

| Field | Type | Description |
|-------|------|-------------|
| `protocolVersion` | VarInt | Server's protocol version |
| `enabled` | Boolean | Whether LSS is active on this server |
| `lodDistanceChunks` | VarInt | Maximum LOD distance in chunks |
| `maxRequestsPerBatch` | VarInt | Maximum positions per ChunkRequestC2S |
| `maxPendingRequests` | VarInt | Maximum total pending positions |
| `maxGenerationRequestsPerBatch` | VarInt | Generation sub-budget per batch (0 = generation disabled) |
| `generationDistanceChunks` | VarInt | Maximum distance for generation requests |

### ChunkSectionS2C

A single serialized chunk section with optional light data. The `sectionData` field contains zstd-compressed block state and biome palette data.

| Field | Type | Description |
|-------|------|-------------|
| `chunkX` | Int | Chunk X coordinate |
| `sectionY` | Int | Section Y index (vertical slice) |
| `chunkZ` | Int | Chunk Z coordinate |
| `dimension` | Utf(256) | Dimension resource location (e.g., `minecraft:overworld`) |
| `sectionData` | ByteArray(1048576) | Zstd-compressed section data (blocks + biomes) |
| `lightFlags` | Byte | Bitmask controlling light field presence |
| *(conditional)* | | Light fields based on `lightFlags` (see below) |
| `columnTimestamp` | Long | Server's version timestamp for this column |

**Light flags bitmask:**

| Bit | Value | Meaning | Field |
|-----|-------|---------|-------|
| 0 | `0x01` | Block light array present | RawBytes(2048) |
| 1 | `0x02` | Sky light array present | RawBytes(2048) |
| 2 | `0x04` | Uniform block light value present | Byte |
| 3 | `0x08` | Uniform sky light value present | Byte |

Light fields are written in this order when their flag bit is set:

1. If `0x01`: block light array (2048 bytes of 4-bit nibble pairs)
2. If `0x04`: uniform block light value (single byte, low nibble)
3. If `0x02`: sky light array (2048 bytes)
4. If `0x08`: uniform sky light value (single byte, low nibble)

The uniform encoding is an optimization for sections where all light values are identical (common underground). The client expands the nibble value to a full 2048-byte `DataLayer` using the pattern `(nibble & 0xF) | ((nibble & 0xF) << 4)`.

### ColumnUpToDateS2C

Lightweight response indicating the client's cached data is current. Sent instead of `ChunkSectionS2C` when the client's timestamp matches the server's region header timestamp.

| Field | Type | Description |
|-------|------|-------------|
| `chunkX` | Int | Chunk X coordinate |
| `chunkZ` | Int | Chunk Z coordinate |

### RequestCompleteS2C

Signals that all positions in a batch have been processed (submitted to disk/generation/serialized). Section payloads may still be in the send queue.

| Field | Type | Description |
|-------|------|-------------|
| `batchId` | VarInt | Batch ID from the original ChunkRequestC2S |
| `status` | VarInt | Completion status code |

**Status codes:**

| Code | Name | Meaning |
|------|------|---------|
| 0 | `STATUS_DONE` | Batch completed normally |
| 1 | `STATUS_CANCELLED` | Batch cancelled by client |
| 2 | `STATUS_REJECTED` | Batch rejected (too many pending batches) |

### DirtyColumnsS2C

Server-pushed notification of columns that have changed since the client last received them. The client re-requests these on its next scan tick.

| Field | Type | Description |
|-------|------|-------------|
| `count` | VarInt | Number of dirty positions |
| `dirtyPositions` | Long[count] | Packed chunk coordinates |

Wire limit: Positions beyond `MAX_DIRTY_COLUMN_POSITIONS` (4096) are read but discarded.

## Wire Format Limits

| Constant | Value | Purpose |
|----------|-------|---------|
| `MAX_CHUNK_REQUEST_POSITIONS` | 1024 | Max positions in a ChunkRequestC2S |
| `MAX_CANCEL_BATCH_IDS` | 256 | Max batch IDs in a CancelRequestC2S |
| `MAX_DIRTY_COLUMN_POSITIONS` | 4096 | Max positions in a DirtyColumnsS2C |
| `MAX_SECTION_DATA_SIZE` | 1,048,576 | Max bytes for compressed section data |

## Connection Flow

```
Client                                          Server
  |                                                |
  |--- HandshakeC2S (protocolVersion=5) ---------> |
  |                                                |
  | <-------- SessionConfigS2C (limits/config) --- |
  |                                                |
  |--- ChunkRequestC2S (batch #0, 256 pos) -----> |
  |--- ChunkRequestC2S (batch #1, 256 pos) -----> |
  |                                                |
  | <-- ChunkSectionS2C (cx, sY, cz, data) ------ |
  | <-- ChunkSectionS2C (...) -------------------- |
  | <-- ColumnUpToDateS2C (cx, cz) --------------- |
  | <-- ChunkSectionS2C (...) -------------------- |
  |                                                |
  | <-- RequestCompleteS2C (batch #0, DONE) ------ |
  |                                                |
  |  (player moves, some positions out of range)   |
  |                                                |
  |--- CancelRequestC2S ([batch #1]) -----------> |
  |                                                |
  | <-- RequestCompleteS2C (batch #1, CANCELLED) - |
  |                                                |
  |--- ChunkRequestC2S (batch #2, new area) ----> |
  |                                                |
  |  ...                                           |
  |                                                |
  | <-- DirtyColumnsS2C ([changed positions]) ---- |
  |                                                |
  |--- ChunkRequestC2S (batch #N, dirty pos) ---> |
  |                                                |
```

## Fabric vs Paper Encoding

Fabric registers payloads via `PayloadTypeRegistry` with `StreamCodec<RegistryFriendlyByteBuf, T>` lambdas. Paper encodes/decodes manually in `PaperPayloadHandler` using raw `FriendlyByteBuf` operations. Both produce identical bytes on the wire.

On Paper, S2C payloads are sent via `ClientboundCustomPayloadPacket` with a `DiscardedPayload` wrapper, bypassing Bukkit's `sendPluginMessage()` channel registration check. This is necessary because Fabric clients using protocol v5+ don't register channels via `minecraft:register`.
