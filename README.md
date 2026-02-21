# LOD Server Support

A Fabric mod that distributes LOD (Level of Detail) chunk data from the server to connected clients. Clients request the chunks they need and the server responds, enabling LOD rendering mods to display terrain far beyond the vanilla render distance on multiplayer servers.

## Installation

Install the mod on **both the server and all clients**. The server distributes LOD chunk data, and the client requests and processes it.

## Requirements

- Minecraft 1.21.11
- Fabric Loader 0.14.22+
- Fabric API

## Commands

### Server

- `/lsslod stats` - Show per-player transfer statistics
- `/lsslod diag` - Show detailed diagnostics (config, bandwidth, queue depths)

### Client

- `/lss clearcache` - Clear the local column cache, forcing all chunks to be re-requested from the server

## Configuration

### Server

Server config is generated at `config/lss-server-config.json` on first run. Key settings:

| Setting | Default | Description |
|---------|---------|-------------|
| `enabled` | `true` | Enable LOD distribution |
| `lodDistanceChunks` | `128` | Max LOD distance in chunks |
| `maxSectionsPerTickPerPlayer` | `200` | Max sections sent per tick per player |
| `maxBytesPerSecondPerPlayer` | `2097152` | Per-player bandwidth cap (2 MB/s) |
| `maxBytesPerSecondGlobal` | `10485760` | Total bandwidth cap (10 MB/s) |
| `sendLightData` | `true` | Include light data in sent sections |
| `enableDiskReading` | `true` | Read unloaded chunks from region files |
| `diskReaderThreads` | `2` | Thread pool size for async disk reads |
| `maxConcurrentDiskReads` | `64` | Max concurrent disk read operations |
| `maxSendQueueSize` | `4800` | Max queued sections per player |
| `maxRequestsPerBatch` | `256` | Max chunk requests per client batch |
| `maxPendingRequestsPerPlayer` | `512` | Max pending requests per player |
| `skipUndergroundSections` | `true` | Skip sections below the surface to save bandwidth |
| `undergroundSkipMargin` | `0` | Extra sections below surface to include |
| `enableChunkGeneration` | `true` | Generate missing chunks on demand for LOD data |
| `generationDistanceChunks` | `64` | Max distance from player for chunk generation |
| `maxConcurrentGenerations` | `16` | Max chunks generating server-wide at once |
| `maxConcurrentGenerationsPerPlayer` | `8` | Max chunks generating per player at once |
| `generationTimeoutSeconds` | `60` | Timeout for pending chunk generation |
| `maxWaitingGenerations` | `128` | Max chunks queued for generation |
| `maxWaitingGenerationsPerPlayer` | `64` | Max queued generation requests per player |

### Client

Client config is generated at `config/lss-client-config.json` on first run.

| Setting | Default | Description |
|---------|---------|-------------|
| `receiveServerLods` | `true` | Enable receiving LOD data from the server |
| `lodDistanceChunks` | `0` | Max LOD request distance in chunks (0 = use server limit) |
| `resyncIntervalSeconds` | `600` | Seconds between resync sweeps to detect world changes |
| `resyncBatchSize` | `32` | Max positions per resync batch |

## API

LOD rendering mods receive chunk data by registering a consumer:

```java
LSSApi.registerConsumer((level, dimension, section, chunkX, sectionY, chunkZ, blockLight, skyLight) -> {
    // Process the received chunk section
});
```

## License

MIT
