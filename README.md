# LOD Server Support

Distributes LOD (Level of Detail) chunk data from servers to connected clients over a custom networking protocol. Built primarily as a multiplayer backend for [Voxy](https://modrinth.com/mod/voxy) — clients request distant chunks in batches, the server reads them from disk or memory and streams the data back, enabling Voxy to render terrain far beyond the vanilla render distance on multiplayer servers.

Supports both **Fabric** and **Paper** servers. The client is always a Fabric mod.

### Version Compatibility

| LSS Version | Minecraft | Fabric | Paper | Voxy | Java |
|---|---|---|---|---|---|
| **v0.3.x** | **26.1.x** | Server + Client | *Coming soon* | 0.2.14-alpha+ | 25+ |
| v0.2.x | 1.21.11 | Server + Client | Server | 0.2.13-alpha | 21+ |

Paper 26.1 support will ship in a future release once Paper 26.1 stabilizes. Paper server admins on 1.21.11 should continue using [v0.2.2](https://modrinth.com/plugin/lod-server-support/versions).

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

Download from [Modrinth](https://modrinth.com/plugin/lod-server-support):

- **v0.3.x (MC 26.1):** `lod-server-support-fabric` — Fabric mod JAR (client + server)
- **v0.2.x (MC 1.21.11):** `lod-server-support-fabric` + `lod-server-support-paper` — includes Paper plugin

## Installation

### Fabric Server

1. Place `lod-server-support-fabric.jar` in the server's `mods/` directory
2. Install the Fabric mod **and** [Voxy](https://modrinth.com/mod/voxy) on all clients
3. Restart the server — config is generated at `config/lss-server-config.json`

### Fabric Client

1. Install [Voxy](https://modrinth.com/mod/voxy) and place `lod-server-support-fabric.jar` in the client's `mods/` directory
2. Join a server running LSS — client config is generated at `config/lss-client-config.json`

### Paper Server (v0.2.x only — MC 1.21.11)

Paper 26.1 support is not yet available. Paper server admins should use [v0.2.2](https://modrinth.com/plugin/lod-server-support/versions) with MC 1.21.11.

1. Place `lod-server-support-paper.jar` in the server's `plugins/` directory
2. Install the Fabric mod **and** [Voxy](https://modrinth.com/mod/voxy) on all clients
3. Restart the server — config is generated at `plugins/LodServerSupport/lss-server-config.json`

## Requirements (v0.3.x — MC 26.1)

### Fabric Server
- Minecraft 26.1
- Fabric Loader 0.18.4+
- Fabric API
- Java 25+

### Client
- Minecraft 26.1
- Fabric Loader 0.18.4+
- Fabric API
- [Voxy](https://modrinth.com/mod/voxy) 0.2.14-alpha+
- Java 25+

## Commands

### Server (Fabric and Paper)

- `/lsslod stats` - Show per-player transfer statistics
- `/lsslod diag` - Show detailed diagnostics (config, bandwidth, queue depths)

### Client (Fabric only)

- `/lss clearcache` - Clear the local column cache, forcing all chunks to be re-requested from the server

## Configuration

### Server (Fabric and Paper)

Server config is generated on first run:
- **Fabric:** `config/lss-server-config.json`
- **Paper:** `plugins/LodServerSupport/lss-server-config.json`

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
| `perDimensionTimestampCacheSizeMB` | `32` | Max timestamp cache size per dimension in MB (used for up-to-date checks on reconnect) |
| `dirtyBroadcastIntervalSeconds` | `10` | Interval for pushing dirty column notifications to clients |

**Paper-specific:** The config also includes an `updateEvents` list of Bukkit event class names used for dirty chunk detection. `/lsslod` commands require the `lss.admin` permission (or op).

### Client

Client config is generated at `config/lss-client-config.json` on first run.

| Setting | Default | Description |
|---------|---------|-------------|
| `receiveServerLods` | `true` | Enable receiving LOD data from the server |
| `lodDistanceChunks` | `0` | Max LOD request distance in chunks (0 = use server limit) |
| `offThreadSectionProcessing` | `true` | Process received sections off the render thread |


## License

MIT
