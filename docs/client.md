# Client

This page covers client-side behavior: connection lifecycle, spiral scanning, batch management, caching, and section deserialization.

## Connection Lifecycle

```
1. Player joins server
2. LSSClientNetworking.JOIN fires
3. Client sends HandshakeC2S with PROTOCOL_VERSION
4. Server responds with SessionConfigS2C
5. Client creates LodRequestManager, stores config
6. Each tick: LodRequestManager.tick() runs spiral scan
7. On disconnect: cache saved to disk, state reset
```

On singleplayer/integrated servers, LSS is not activated unless the system property `-Dlss.test.integratedServer=true` is set (used for testing only).

## Spiral Scanner

The scanner (`LodRequestManager.tickScan`) expands outward from the player in concentric square rings, collecting chunk positions to request.

### Position Categories

Each position is classified based on its timestamp in the `columnTimestamps` map (default value: -1):

| Category | Timestamp | Condition | Sent Timestamp | Budget |
|----------|-----------|-----------|----------------|--------|
| Fresh | -1 | Never requested | 0 | Main |
| Dirty | any | In `dirtyColumns` set | Stored ts | Main |
| Generation | 0 | Empty, within generation distance | 0 | Generation sub-budget |
| Resync | > 0 | Has cached data, initial join only | Stored ts | Resync sub-budget |
| Settled | > 0 | Has data, not dirty, not resync | -- | Skipped |

Settled positions are skipped. The scanner advances to the next radius only if all positions at the current radius are either settled or successfully collected. This prevents gaps in the spiral.

### Scan Algorithm

Each tick:

1. Check `pendingColumns.size() < maxPendingRequests` -- if not, return (hard cap)
2. Compute effective LOD distance: `min(serverDistance, clientOverride)` (or server distance if client override is 0)
3. Compute exclusion radius from vanilla render distance (skip chunks the vanilla client already has)
4. Allocate position and timestamp buffers
5. Walk concentric squares from `scanRadius` to effective LOD distance:
   - Skip positions within exclusion radius (Chebyshev distance)
   - Classify each position and collect if budget allows
   - If budget exhausted or pending limit reached: stop collecting, set `canAdvance = false`
   - If all positions at this radius are settled or collected: advance to next radius
6. If positions collected: send `ChunkRequestC2SPayload`, track batch, add to `pendingColumns`
7. If scan completes (all radii processed): reset `scanRadius` to 0, clear `needsResync`

### Budget System

**Main budget:** `maxRequestsPerBatch` (default 256) positions per tick. Fresh and dirty positions consume from this budget.

**Generation sub-budget:** `maxGenerationRequestsPerBatch` (default equals `maxConcurrentGenerationsPerPlayer`). Empty columns (timestamp 0) within generation distance consume from both the generation counter and the main budget. When exhausted, the scanner blocks at that radius.

**Resync sub-budget:** `min(resyncBatchSize, maxRequestsPerBatch / 4)` (default: min(32, 64) = 32). Active only during the initial join sweep (`needsResync = true`). Resync positions consume from both the resync counter and the main budget. Unused resync slots remain available for other categories.

**Pending column limit:** `maxPendingRequests` (default 512). Hard cap on total in-flight positions. Scanner does not run at all when at this limit.

### Advancement Blocking

When the scanner encounters a position it wants to request but can't (budget exhausted, pending limit reached), it sets `canAdvance = false` for that radius. The `scanRadius` stays at that radius, and those positions are picked up on the next tick. This prevents the scanner from racing ahead and leaving visible gaps in the LOD spiral.

## Batch Management

### Active Batches

Each `ChunkRequestC2S` creates a `TrackedBatch` in the `activeBatches` map:

```
TrackedBatch {
    long[] positions    -- packed chunk coordinates in this batch
    long createdAt      -- System.currentTimeMillis() at creation
}
```

Keyed by `batchId` (incrementing integer, wraps around).

### Cancellation

When the player moves, `pruneOutOfRangePositions` walks all active batches:

1. For each batch, filter positions to keep only those within LOD distance
2. Remove out-of-range positions from `pendingColumns` (frees slots immediately)
3. Batches where all positions are out of range are added to a cancel list
4. Send `CancelRequestC2SPayload` with the cancelled batch IDs
5. Remove cancelled batches from `activeBatches`

### Batch Timeout

Batches older than 60 seconds (`BATCH_TIMEOUT_MS`) are purged:

1. Remove all positions in the batch from `pendingColumns`
2. Remove the batch from `activeBatches`

This handles edge cases where the server drops a `RequestComplete` or crashes mid-batch.

## Response Handling

### onSectionReceived(cx, cz, columnTimestamp)

Called when a `ChunkSectionS2C` payload arrives:

1. Remove position from `pendingColumns`
2. Remove from `dirtyColumns` (if present)
3. Store `columnTimestamp` in `columnTimestamps`

### onColumnUpToDate(cx, cz)

Called when a `ColumnUpToDateS2C` payload arrives:

1. Remove position from `pendingColumns`
2. Keep existing timestamp (data is already current)

### onBatchComplete(batchId, status)

Called when a `RequestCompleteS2C` payload arrives:

1. Remove batch from `activeBatches`
2. For each position in the batch:
   - Remove from `pendingColumns`
   - If no timestamp stored and status != `REJECTED`: mark as empty (timestamp 0) for generation retry
3. Positions with timestamp 0 will be re-requested on the next scan if within generation distance and budget allows

### onDirtyColumns(dirtyPositions)

Called when a `DirtyColumnsS2C` payload arrives:

1. For each position with stored data (timestamp > 0): add to `dirtyColumns` set
2. Positions without cached data are ignored (the scanner will request them when it reaches that radius)

On the next scan tick, dirty positions are collected like fresh requests, using the stored timestamp so the server can detect if the client is already up-to-date.

## Timestamp Pruning

When the player moves beyond a chunk boundary, `pruneOutOfRangeTimestamps` removes entries beyond `lodDistance + 32` chunks from the player. The 32-chunk buffer prevents thrashing at the boundary. At `lodDistance=128`, worst case is ~(257x257) = ~66K entries x 16 bytes = ~1 MB.

## Dimension Changes

When the player changes dimension:

1. Save current dimension's timestamps to disk via `ColumnCacheStore`
2. Clear all state: timestamps, pending columns, active batches, dirty columns
3. Load cache for the new dimension
4. Set `needsResync = true` to enable resync sub-budget

## ColumnCacheStore

Persists column timestamps to disk per-server, per-dimension for cross-session resync.

### File Location

```
<config_dir>/lss/cache/<sanitized_server_address>/<sanitized_dimension>.bin
```

Server addresses and dimension names are sanitized (non-alphanumeric characters replaced with underscores).

### Binary Format

```
Offset  Size     Field
0       4 bytes  FORMAT_VERSION (int, currently 1)
4       4 bytes  Entry count (int)
8+      16 bytes Each entry: packed position (long) + timestamp (long)
```

Maximum 2,000,000 entries on load (sanity check).

### Persistence Lifecycle

- **Load:** On first tick after join or dimension change. Creates empty map if file doesn't exist.
- **Save:** On disconnect or dimension change. Writes to `.tmp` file, then atomically moves to final path.
- **Clear:** `clearForServer()` deletes all dimension caches for a server. `clearAll()` deletes the entire cache directory.

## Section Deserialization

When `LSSClientNetworking` receives a `ChunkSectionS2C`:

1. Check `receiveServerLods` config and `LSSApi.hasConsumers()`
2. Wrap `sectionData` in a `RegistryFriendlyByteBuf` with level registry access
3. Decompress zstd data
4. Read block state palette: `PalettedContainer<BlockState>`
5. Read biome palette: `PalettedContainer<Holder<Biome>>`
6. Construct `LevelChunkSection` with block/biome palettes
7. Deserialize light layers from `lightFlags`:
   - `0x01`: clone 2048-byte array into `DataLayer`
   - `0x04`: expand uniform nibble: `(nibble & 0xF) | ((nibble & 0xF) << 4)` into 2048-byte array
   - `0x02` / `0x08`: same pattern for sky light
8. Dispatch to all `LSSApi` consumers via `LSSApi.dispatch()`

The dispatch runs on the client main thread. Exceptions in individual consumers are caught and logged.
