# LOD Server Support

Distributes LOD (Level of Detail) chunk data from servers to connected clients over a custom networking protocol. Built primarily as a multiplayer backend for [Voxy](https://modrinth.com/mod/voxy) — clients request distant chunks in batches, the server reads them from disk or memory and streams the data back, enabling Voxy to render terrain far beyond the vanilla render distance on multiplayer servers.

Supports both **Fabric** and **Paper** servers. The client is always a Fabric mod.

https://github.com/user-attachments/assets/721fb344-890e-4e03-ab36-539444427f7b

## How It Works

Without LSS, Voxy can only build LOD data from chunks the client has already loaded — limiting distant terrain rendering to areas the player has personally visited. LSS moves this work to the server:

1. Client connects and performs a handshake with the server
2. Server sends session config (distance limits, rate limits, generation settings)
3. Client scans outward in an expanding spiral, batch-requesting chunks it doesn't have cached
4. Server reads chunks from disk (or generates them on demand), serializes the raw MC section data (block states, biomes, lighting), and streams it back
5. Client receives the section data and feeds it directly into Voxy's rendering engine via `rawIngest`
6. After initial sync, the server pushes notifications when chunks change so clients stay up to date

The result: players see fully rendered terrain out to hundreds of chunks on multiplayer servers, without needing to explore the world first.

## Downloads

Built JARs are available as artifacts from the [GitHub Actions build workflow](https://github.com/VoX/lod-server-support/actions/workflows/build.yml?query=branch%3Amain+is%3Asuccess):

- **lod-server-support-fabric** — Fabric mod JAR (client + server)
- **lod-server-support-paper** — Paper plugin JAR (server only)

## Installation

### Fabric Server

Install the mod on **both the server and all clients**. The server distributes LOD chunk data, and the client requests and processes it.

Clients should also install [Voxy](https://modrinth.com/mod/voxy) to actually render the received data.

### Paper Server

1. Place `lod-server-support-paper-all.jar` in the server's `plugins/` directory
2. Install the Fabric mod **and** Voxy on all clients
3. Restart the server — config is generated at `plugins/LodServerSupport/lss-server-config.json`

## Requirements

### Fabric
- Minecraft 1.21.11
- Fabric Loader 0.14.22+
- Fabric API
- [Voxy](https://modrinth.com/mod/voxy) (client-side, for rendering)

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
| `lodDistanceChunks` | `256` | Max LOD distance in chunks |
| `bytesPerSecondLimitPerPlayer` | `20971520` | Per-player pre-compression bandwidth cap (20 MB/s) |
| `bytesPerSecondLimitGlobal` | `104857600` | Total pre-compression bandwidth cap (100 MB/s) |
| `diskReaderThreads` | `5` | Thread pool size for async disk reads |
| `sendQueueLimitPerPlayer` | `4000` | Max queued sections per player |
| `syncOnLoadRateLimitPerPlayer` | `800` | Sync-on-load requests per second per player |
| `syncOnLoadConcurrencyLimitPerPlayer` | `200` | Max in-flight sync requests per player |
| `generationRateLimitPerPlayer` | `80` | Generation requests per second per player |
| `generationConcurrencyLimitPerPlayer` | `16` | Max in-flight generation requests per player |
| `enableChunkGeneration` | `true` | Generate missing chunks on demand for LOD data |
| `generationConcurrencyLimitGlobal` | `32` | Max chunks generating server-wide at once |
| `generationTimeoutSeconds` | `60` | Timeout for pending chunk generation |
| `dirtyBroadcastIntervalSeconds` | `10` | Interval for pushing dirty column notifications to clients |

### Paper Server

Server config is generated at `plugins/LodServerSupport/lss-server-config.json` on first run. Same settings as Fabric, plus an `updateEvents` list of Bukkit event class names used for dirty chunk detection. Requires the `lss.admin` permission (or op) to use `/lsslod` commands.

### Client

Client config is generated at `config/lss-client-config.json` on first run.

| Setting | Default | Description |
|---------|---------|-------------|
| `receiveServerLods` | `true` | Enable receiving LOD data from the server |
| `lodDistanceChunks` | `0` | Max LOD request distance in chunks (0 = use server limit) |
| `offThreadSectionProcessing` | `true` | Process received sections off the render thread |

## License

MIT
