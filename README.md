# LOD Server Support

Distributes LOD (Level of Detail) chunk data from servers to connected clients over a custom networking protocol. Built primarily as a multiplayer backend for [Voxy](https://modrinth.com/mod/voxy) — clients request distant chunks in batches, the server reads them from disk or memory and streams the data back, enabling Voxy to render terrain far beyond the vanilla render distance on multiplayer servers.

Supports **Fabric**, **Paper**, and **Folia** (experimental) servers. The client is always a Fabric mod.

### Version Compatibility

| LSS Version | Minecraft | Fabric | Paper | Voxy | Java |
|---|---|---|---|---|---|
| **v0.5.x** | **26.1.x** | Server + Client | Server + Folia (experimental) | 0.2.16-beta+ | 25+ |
| **v0.5.x+mc1.21.11** | **1.21.11** | Server + Client | Server + Folia (experimental) | 0.2.15-beta+ | 21+ |
| **v0.5.x+mc1.20.1** | **1.20.1** | Server + Client | Server (no Folia — see note) | — (no public Voxy build) | 17+ |
| v0.4.x | 26.1.x | Server + Client | Server | 0.2.16-beta+ | 25+ |
| v0.3.x | 26.1.x | Server + Client | — | 0.2.14-alpha+ | 25+ |
| v0.2.x | 1.21.11 | Server + Client | Server | 0.2.13-alpha | 21+ |

v0.5.0 adds **Folia** support (experimental — see the Paper Server section) and a set of
client-sync correctness fixes; after upgrading, clients rebuild their local LOD cache once
(the cache format changed), so the first rejoin re-syncs from the server. Paper server
support is built against Paper 26.1.2 and also runs on Purpur. Servers still on **Minecraft
1.21.11** get the full v0.5.0 feature set from the long-lived
[`support/mc1.21.11`](https://github.com/VoX/lod-server-support/tree/support/mc1.21.11) branch
(`v<version>+mc1.21.11` releases); servers on **Minecraft 1.20.1** likewise from
[`support/mc1.20.1`](https://github.com/VoX/lod-server-support/tree/support/mc1.20.1)
(`v<version>+mc1.20.1` releases). **Notes for the 1.20.1 line:** Voxy has no public 1.20.1 build,
so the bundled Voxy bridge stays dormant there — that release serves servers whose players use a
private/self-built Voxy port or another LOD renderer integrating via `LSSApi`. Folia is NOT
supported on 1.20.1 (only frozen 2023 alpha builds exist and their chunk-load semantics break
LSS's generation path); the plugin declines to load there rather than run degraded.

https://github.com/user-attachments/assets/721fb344-890e-4e03-ab36-539444427f7b

## How It Works

Without LSS, Voxy can only build LOD data from chunks the client has already loaded — limiting distant terrain rendering to areas the player has personally visited. LSS moves this work to the server:

1. Client connects and performs a handshake with the server
2. Server sends session config (distance limits, concurrency limits, generation settings)
3. Client scans outward in an expanding spiral, batch-requesting chunks it doesn't have cached
4. Server reads chunks from disk (or generates them on demand), serializes the raw MC section data (block states, biomes, lighting), and streams it back
5. Client receives the section data and feeds it directly into Voxy's rendering engine via `rawIngest`
6. After initial sync, the server pushes notifications when chunks change so clients stay up to date

The result: players see fully rendered terrain out to hundreds of chunks on multiplayer servers, without needing to explore the world first.

## Downloads

Download from [Modrinth](https://modrinth.com/plugin/lod-server-support):

- **v0.5.x (MC 26.1.x):** `lod-server-support-fabric` — Fabric mod JAR (client + server) — and `lod-server-support-paper` — Paper/Purpur/Folia server plugin
- **v0.5.x+mc1.21.11 (MC 1.21.11):** the same `lod-server-support-fabric` + `lod-server-support-paper` artifacts built for 1.21.11 — the full v0.5.0 feature set (incl. Folia) for the older line
- **v0.5.x+mc1.20.1 (MC 1.20.1):** the same artifacts built for 1.20.1 (requires an `LSSApi` consumer — no public Voxy build exists for 1.20.1)
- **v0.2.x (MC 1.21.11):** `lod-server-support-fabric` + `lod-server-support-paper` — the original 1.21.11 line (superseded by v0.5.x+mc1.21.11)

## Installation

### Fabric Server

1. Place `lod-server-support-fabric.jar` in the server's `mods/` directory
2. Install the Fabric mod **and** [Voxy](https://modrinth.com/mod/voxy) on all clients
3. Restart the server — config is generated at `config/lss-server-config.json`

### Fabric Client

1. Install [Voxy](https://modrinth.com/mod/voxy) and place `lod-server-support-fabric.jar` in the client's `mods/` directory
2. Join a server running LSS — client config is generated at `config/lss-client-config.json`

### Paper Server

Requires Paper, Purpur, or Folia for Minecraft 26.1.2.

1. Place `lod-server-support-paper.jar` in the server's `plugins/` directory
2. Install the Fabric mod **and** [Voxy](https://modrinth.com/mod/voxy) on all clients
3. Restart the server — config is generated at `plugins/LodServerSupport/lss-server-config.json`

**Folia notes (experimental, since v0.5.0):** the same plugin JAR runs on Folia's regionized
threading — the request pipeline runs on Folia's global region tick. Support is validated by
an automated single-player soak suite against a real Folia server; busy multi-region servers
are less tested, so treat it as experimental and report issues. `/reload` is not supported on
Folia (restart the server instead), and runtime plugin managers (enable/disable without a
restart) are best-effort.

## Requirements (v0.5.x — MC 26.1.x)

### Fabric Server
- Minecraft 26.1.x
- Fabric Loader 0.18.4+
- Fabric API
- Java 25+

### Paper Server
- Paper, Purpur, or Folia (experimental) for Minecraft 26.1.2
- Java 25+

### Client
- Minecraft 26.1.x
- Fabric Loader 0.18.4+
- Fabric API
- [Voxy](https://modrinth.com/mod/voxy) 0.2.16-beta+
- Java 25+

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
| `sendQueueLimitPerPlayer` | `4000` | Max queued column payloads per player (each carries a full chunk column of sections) |
| `syncOnLoadConcurrencyLimitPerPlayer` | `200` | Max in-flight sync requests per player |
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


## License

MIT
