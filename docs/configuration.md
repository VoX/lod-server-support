# Configuration

This page documents all configuration fields, their defaults and valid ranges, and provides tuning guidance.

## Server Config (Fabric)

File: `config/lss-server-config.json` (auto-created on first launch)

| Field | Type | Default | Min | Max | Description |
|-------|------|---------|-----|-----|-------------|
| `enabled` | boolean | `true` | -- | -- | Enable/disable LSS |
| `lodDistanceChunks` | int | `128` | 1 | 512 | Maximum LOD distance in chunks |
| `maxSectionsPerTickPerPlayer` | int | `200` | 1 | 10,000 | Max chunk sections sent per tick per player |
| `maxBytesPerSecondPerPlayer` | int | `2097152` | 1,024 | 104,857,600 | Per-player bandwidth cap (bytes/sec) |
| `sendLightData` | boolean | `true` | -- | -- | Include block and sky light data in sections |
| `enableDiskReading` | boolean | `true` | -- | -- | Read chunks from disk for distant LOD |
| `maxConcurrentDiskReads` | int | `64` | 1 | 512 | Max in-flight async disk reads per player |
| `diskReaderThreads` | int | `2` | 1 | 64 | Thread pool size for async disk I/O |
| `maxSendQueueSize` | int | `4800` | 1 | 100,000 | Per-player send queue capacity (payloads) |
| `maxBytesPerSecondGlobal` | int | `10485760` | 1,024 | 1,073,741,824 | Global server bandwidth cap (bytes/sec) |
| `maxRequestsPerBatch` | int | `256` | 1 | 1,024 | Max chunk positions per client batch |
| `maxPendingRequestsPerPlayer` | int | `512` | 1 | 4,096 | Max in-flight requests per player |
| `enableChunkGeneration` | boolean | `true` | -- | -- | Generate missing chunks on demand |
| `generationDistanceChunks` | int | `64` | 1 | 512 | Max distance for chunk generation |
| `maxConcurrentGenerations` | int | `16` | 1 | 256 | Global concurrent generation limit |
| `maxConcurrentGenerationsPerPlayer` | int | `8` | 1 | 64 | Per-player concurrent generation limit |
| `generationTimeoutSeconds` | int | `60` | 1 | 600 | Generation timeout before cancellation |
| `dirtyBroadcastIntervalSeconds` | int | `15` | 1 | 300 | Interval for pushing dirty column notifications |

## Server Config (Paper)

File: `plugins/LodServerSupport/lss-server-config.json` (auto-created on first launch)

Same fields and defaults as Fabric server config, except:

- **No `dirtyBroadcastIntervalSeconds`** -- Paper v1 does not implement dirty column tracking. Clients resync via the periodic `resyncBatchSize` timer.

## Client Config (Fabric)

File: `config/lss-client-config.json` (auto-created on first launch)

| Field | Type | Default | Min | Max | Description |
|-------|------|---------|-----|-----|-------------|
| `receiveServerLods` | boolean | `true` | -- | -- | Enable receiving LOD chunks from the server |
| `lodDistanceChunks` | int | `0` | 0 | 512 | Override server LOD distance (0 = use server value) |
| `resyncBatchSize` | int | `32` | 1 | 256 | Max resync positions per batch during initial join sweep |

## Tuning Guidance

### Bandwidth

The default per-player cap of 2 MB/s and global cap of 10 MB/s are conservative. At ~3 KB per section payload (with light data), 2 MB/s supports ~660 sections/second per player.

**Low-bandwidth servers:** Reduce `maxBytesPerSecondPerPlayer` to 512 KB (524288) or lower. Also reduce `maxSectionsPerTickPerPlayer` to match: at 20 TPS, `maxSections = targetSectionsPerSecond / 20`.

**High-bandwidth servers (few players):** Increase both per-player and global caps. With 1-2 players, the global cap is the effective limit -- set it to match your available upload bandwidth minus vanilla traffic overhead.

**Many players:** The `SharedBandwidthLimiter` divides the global budget equally. With 10 players and a 10 MB/s global cap, each gets 1 MB/s (before the per-player cap applies). Increase `maxBytesPerSecondGlobal` proportionally to player count if bandwidth allows.

### Disk Reader

The default of 2 threads handles most SSDs well. HDDs benefit from limiting to 1 thread to reduce seek contention. NVMe drives can handle 4-8 threads.

`maxConcurrentDiskReads` (64) limits in-flight reads per player. This prevents a single player from monopolizing the thread pool. Reduce if you see high disk I/O wait times.

### Generation

Generation is the most expensive operation. The defaults (16 global, 8 per-player) are conservative.

**New worlds with many players:** Reduce `maxConcurrentGenerations` to 4-8 to prevent generation from overwhelming the server tick. Reduce `generationDistanceChunks` to limit how far out generation reaches.

**Established worlds (most chunks exist on disk):** Generation is rarely triggered. Default settings are fine.

**Disable generation entirely:** Set `enableChunkGeneration = false` if you want LOD data only for existing terrain.

### Send Queue

`maxSendQueueSize` (4800) bounds memory per player. At ~3 KB per payload, worst case is ~14 MB per player. The send queue acts as a buffer between async results and bandwidth-limited network writes.

**Reduce** if memory is constrained. Setting too low causes upstream backpressure earlier (disk reads and generation pause), which may leave the network underutilized.

**Increase** if you have ample memory and want to smooth out bursts from large disk read completions.

## Config Loading

Both Fabric and Paper use the same loading pattern:

1. Check if config file exists at the platform-specific path
2. If exists: read JSON, deserialize via GSON, call `validate()` (clamps all fields to safe ranges), save back (rewrites with clamped values)
3. If not exists: create instance with defaults, save to file
4. If read/parse fails: log warning, use defaults

Fabric configs extend an abstract `JsonConfig` base class that provides the GSON instance and load/save/validate infrastructure. Paper uses a standalone `PaperConfig` class with the same pattern.

All numeric config values are clamped to their min/max range on load. Invalid values are silently corrected to the nearest valid value. The corrected config is saved back to disk so the file always reflects the active values.
