# LOD Server Support

Distributes LOD (Level of Detail) chunk data from the server to connected clients. Clients request the chunks they need and the server responds, enabling LOD rendering mods to display terrain far beyond the vanilla render distance on multiplayer servers.

Supports both **Fabric** and **Paper** servers. The client is always a Fabric mod.

https://github.com/user-attachments/assets/721fb344-890e-4e03-ab36-539444427f7b

## Downloads

Built JARs are available as artifacts from the [GitHub Actions build workflow](https://github.com/VoX/lod-server-support/actions/workflows/build.yml?query=branch%3Amain+is%3Asuccess):

- **lod-server-support-fabric** — Fabric mod JAR (client + server)
- **lod-server-support-paper** — Paper plugin JAR (server only)

## Installation

### Fabric Server

Install the mod on **both the server and all clients**. The server distributes LOD chunk data, and the client requests and processes it.

### Paper Server

1. Place `lod-server-support-paper-all.jar` in the server's `plugins/` directory
2. Install the Fabric mod on all clients
3. Restart the server — config is generated at `plugins/LodServerSupport/lss-server-config.json`

## Requirements

### Fabric
- Minecraft 1.21.11
- Fabric Loader 0.14.22+
- Fabric API

### Paper
- Paper 1.21.11+
- Java 21+

## Commands

### Server (Fabric and Paper)

- `/lsslod stats` - Show per-player transfer statistics
- `/lsslod diag` - Show detailed diagnostics (config, bandwidth, queue depths)

### Client (Fabric only)

- `/lss clearcache` - Clear the local column cache, forcing all chunks to be re-requested from the server

## Configuration

### Fabric Server

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
| `enableChunkGeneration` | `true` | Generate missing chunks on demand for LOD data |
| `generationDistanceChunks` | `64` | Max distance from player for chunk generation |
| `maxConcurrentGenerations` | `16` | Max chunks generating server-wide at once |
| `maxConcurrentGenerationsPerPlayer` | `8` | Max chunks generating per player at once |
| `generationTimeoutSeconds` | `60` | Timeout for pending chunk generation |
| `dirtyBroadcastIntervalSeconds` | `15` | Interval for pushing dirty column notifications to clients |

### Paper Server

Server config is generated at `plugins/LodServerSupport/lss-server-config.json` on first run. Same settings as Fabric above, except `dirtyBroadcastIntervalSeconds` is not present (Paper v1 does not implement dirty column tracking — clients resync via the `resyncBatchSize` mechanism). Requires the `lss.admin` permission (or op) to use `/lsslod` commands.

### Client

Client config is generated at `config/lss-client-config.json` on first run.

| Setting | Default | Description |
|---------|---------|-------------|
| `receiveServerLods` | `true` | Enable receiving LOD data from the server |
| `lodDistanceChunks` | `0` | Max LOD request distance in chunks (0 = use server limit) |
| `resyncBatchSize` | `32` | Max resync positions per batch during initial join sweep |

## API

LOD rendering mods receive chunk data by registering a consumer:

```java
LSSApi.registerConsumer((level, dimension, section, chunkX, sectionY, chunkZ, blockLight, skyLight) -> {
    // Process the received chunk section
});
```

## License

MIT
