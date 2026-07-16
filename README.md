# LOD Server Support

Distributes LOD (Level of Detail) chunk data from servers to connected clients over a custom networking protocol. Built primarily as a multiplayer backend for [Voxy](https://modrinth.com/mod/voxy) — clients request distant chunks in batches, the server reads them from disk or memory and streams the data back, enabling Voxy to render terrain far beyond the vanilla render distance on multiplayer servers without the need to travel there first.

Supports **Fabric** clients and **Fabric**, **Paper**, **Purpur**, **Folia** servers.

https://github.com/user-attachments/assets/721fb344-890e-4e03-ab36-539444427f7b

## Downloads

All builds are on [Modrinth](https://modrinth.com/plugin/lod-server-support) — pick the file matching your Minecraft version: `lod-server-support-fabric` (the client/server Fabric mod) or `lod-server-support-paper` (the server plugin).

## Installation

> [!IMPORTANT]
> Install **LOD Server Support (LSS)** on **both** the server (Fabric mod or Paper plugin) and **every client** (LSS Fabric mod + Voxy).

### Fabric Clients

1. Install [Voxy](https://modrinth.com/mod/voxy) and place `lod-server-support-fabric.jar` in the client's `mods/` directory (requires Fabric API)
2. Join a server running LSS — client config is generated at `config/lss-client-config.json`

### Fabric Server

1. Place `lod-server-support-fabric.jar` in the server's `mods/` directory (requires Fabric API)
2. Install the Fabric mod **and** [Voxy](https://modrinth.com/mod/voxy) on all clients
3. Restart the server — config is generated at `config/lss-server-config.json`

### Paper Server

1. Place `lod-server-support-paper.jar` in the server's `plugins/` directory (Paper or Purpur)
2. Install the Fabric mod **and** [Voxy](https://modrinth.com/mod/voxy) on all clients
3. Restart the server — config is generated at `plugins/LodServerSupport/lss-server-config.json`

## Version Compatibility

Each Minecraft version has its own build; only the latest is listed. Older-MC builds are versioned `v<x.y.z>+mc<version>` and carry the same feature set from long-lived support branches.

| Minecraft | LSS Version | Fabric | Paper | Folia | Voxy | Java |
|---|---|---|---|---|---|---|
| **26.2** | v0.6.1 | ✅ | ✅ | — | 0.2.16-beta+ | 25+ |
| **26.1.x** | v0.5.1 | ✅ | ✅ | ✅ | 0.2.16-beta+ | 25+ |
| **1.21.11** | v0.5.0+mc1.21.11 | ✅ | ✅ | ✅ | 0.2.15-beta+ | 21+ |
| **1.21.8** | v0.6.1+mc1.21.8 | ✅ | ✅ | ✅ | 0.2.5-alpha+ | 21+ |

Fabric builds are client + server; the Paper plugin is server-only and also runs on Purpur. Folia uses the same plugin JAR. 26.2 has no Folia build yet upstream.

> [!IMPORTANT]
> **Update the server and clients together.** LSS versions a networking protocol, and a client and server on different protocol versions simply establish no LOD session — you see vanilla render distance and no error. Release notes call out which updates carry a protocol bump.

On 1.21.8 the in-game config screen is unavailable (it requires Sodium 0.8+, and 1.21.8's newest Sodium is 0.7.3); the JSON config files still work as normal.

## How It Works

Without LSS, Voxy can only build LOD data from chunks the client has already loaded — limiting distant terrain rendering to areas the player has personally visited. LSS moves this work to the server:

1. Client connects and performs a handshake with the server
2. Server sends session config (distance limits, concurrency limits, generation settings)
3. Once a second, the client scans outward in an expanding spiral and declares the complete set of chunks it still wants, closest-first
4. The server replaces that player's queue with the new set, so it never works on chunks the player has already moved away from, and never rejects a request the client would just have to re-send
5. Server reads chunks from disk (or generates them on demand), serializes the raw MC section data (block states, biomes, lighting), and streams it back
6. Client receives the section data and feeds it directly into Voxy's rendering engine via `rawIngest`; served chunks drop out of the next second's set, so the request naturally stops repeating
7. After initial sync, the server pushes notifications when chunks change so clients stay up to date

The result: players see fully rendered terrain out to hundreds of chunks on multiplayer servers, without needing to explore the world first.

## Commands

### Server (Fabric and Paper)

- `/lsslod stats` - Show per-player transfer statistics
- `/lsslod diag` - Show detailed diagnostics (config, bandwidth, queue depths)

### Client (Fabric only)

- `/lss clearcache` - Clear the local column cache, forcing all chunks to be re-requested from the server
- `/lss diag` - Show client-side diagnostics (connection, throughput, scan progress, request budget)

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
| `useBackgroundReadPriority` | `true` | LOD disk reads yield to vanilla/gameplay chunk loading, so streaming distant terrain doesn't delay the chunks players are actively loading (Fabric: IOWorker BACKGROUND priority; Paper/Folia: Moonrise LOW priority). On Fabric servers running a chunk-IO-overhaul mod (e.g. C2ME) that replaces vanilla's IOWorker, LSS automatically switches to adaptive read throttling (self-restraint that still yields to gameplay), logging one warning. Set `false` to restore foreground reads with no read protection |
| `sendQueueLimitPerPlayer` | `4000` | Max queued column payloads per player (each carries a full chunk column of sections) |
| `generationConcurrencyLimitPerPlayer` | `16` | Max concurrently generating chunks per player — above this, requests queue on the server until a slot frees |
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

## License

MIT
