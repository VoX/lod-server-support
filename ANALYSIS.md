# LSS (LOD Server Support) — Technical Analysis

## What the mod does and why

### The problem

Vanilla Minecraft servers only stream full chunk data to clients within the server's render distance (typically 10-16 chunks). Beyond that, the client sees nothing — just void or fog. Client-side LOD rendering mods like Voxy can render distant terrain at reduced detail, but they need the terrain data to exist on the client in the first place. In singleplayer, the LOD mod can read directly from the world files. On multiplayer, there is no mechanism for the server to distribute terrain data beyond the vanilla render distance.

### What LSS does

LSS implements a custom client-pull networking protocol that lets clients request distant chunks from the server in batches. The server reads chunks from memory, disk, or generates them on demand, serializes them using Minecraft's native section codec, and streams them back with bandwidth limiting. Supports both **Fabric** (mod, client + server) and **Paper** (plugin, server only) — the client is always Fabric. Both platforms produce identical wire format over Plugin Messaging channels, so the Fabric client works with either server. Minecraft's network layer applies zlib/DEFLATE compression to all packets >256 bytes, so raw section data compresses efficiently without application-level pre-compression. The client deserializes the data and dispatches it to LOD rendering mods (currently Voxy) via a reflection-based compatibility bridge. A persistent client-side cache tracks which chunks have been received and their timestamps. On Fabric, the server hooks chunk saves and periodically broadcasts dirty column notifications, enabling event-driven re-requests instead of blind periodic resync. On Paper, dirty column tracking is not yet implemented — clients rely on periodic resync.

### End-to-end lifecycle

```
Client joins -> sends Handshake(protocolVersion=5)
  -> Server validates version, sends SessionConfig(lodDistance, limits, gen config)
    -> Client creates LodRequestManager, loads column cache from disk
      -> Client tick loop: spiral scan outward from player
        -> Classifies each column: fresh / dirty / empty / has-data
        -> Builds batch of up to 256 positions + timestamps
        -> Sends ChunkRequestC2SPayload
          -> Server processes batch:
              Loaded chunk? -> serialize in-place (server thread)
              Not loaded?   -> submit async disk read (thread pool)
              Not on disk?  -> submit generation ticket (async)
            -> Results flow into per-player send queue
            -> flushSendQueue() respects bandwidth caps
            -> Sends ChunkSectionS2CPayload per section
            -> Sends ColumnUpToDateS2CPayload if timestamp matches
            -> Sends RequestCompleteS2CPayload when batch exhausted
          -> Client deserializes section (blocks + biomes + light)
          -> Dispatches to LSSApi -> VoxyCompat -> Voxy's rawIngest()
          -> Updates column cache with server timestamp
      -> Server periodically broadcasts DirtyColumnsS2CPayload (changed chunks)
        -> Client marks dirty columns for re-request on next scan pass
```

### Key design decisions

- **Client-pull, not server-push**: The client drives all requests. This offloads spatial tracking to the client (which only needs to track its own state) and avoids the server needing per-player coverage maps for potentially 50K+ columns per player.
- **Full section data, not voxelized**: The server sends Minecraft's native `LevelChunkSection.write()` output. Voxelization/mipping happens on the client inside the LOD mod. This keeps LSS format-agnostic — any LOD mod can consume the data.
- **Batch request-response**: Discrete batches with IDs, completion signals, and cancellation. Simpler to reason about than a streaming protocol, and fits naturally with Fabric's payload system.
- **Three-tier chunk sourcing**: Memory (instant) -> disk (async, 2 threads) -> generation (async, ticket-based). Graceful fallback with backpressure at each tier.
- **Uniform light optimization**: Detects all-same light layers (common: all-zero underground, all-15 sky) and sends 1 byte instead of 2048, saving ~73% of light bytes.

### Architecture (3 subprojects, ~50 files, ~5,000 lines)

| Layer | Key files | Role |
|---|---|---|
| Common | `LSSConstants`, `LSSLogger`, `SharedBandwidthLimiter`, `PositionUtil` | Shared pure-Java utilities |
| Fabric entry | `LSSMod`, `LSSClient` | Register payloads, start services |
| Paper entry | `LSSPaperPlugin` | Plugin Messaging channels, tick loop |
| Protocol (Fabric) | `LSSNetworking` + 8 payload records | Wire format (v5) via StreamCodec |
| Protocol (Paper) | `PaperPayloadHandler` | Same wire format via raw FriendlyByteBuf |
| Server engine (Fabric) | `RequestProcessingService` (563 lines) | Per-tick orchestration, dirty column broadcast |
| Server engine (Paper) | `PaperRequestProcessingService` (522 lines) | Same orchestration, Plugin Messaging sends |
| Server I/O (Fabric) | `ChunkDiskReader` (281 lines) | Async region file reading (mixin accessor) |
| Server I/O (Paper) | `PaperChunkDiskReader` (314 lines) | Async region file reading (direct NMS) |
| Server gen (Fabric) | `ChunkGenerationService` (310 lines) | Ticket-based async chunk generation |
| Server gen (Paper) | `PaperChunkGenerationService` (365 lines) | Same, using `addRegionTicket` API |
| Server state (Fabric) | `PlayerRequestState` (287 lines) | Per-player batch tracking, send queue |
| Server state (Paper) | `PaperPlayerRequestState` | Adapted for byte[] payloads |
| Server change tracking | `ChunkChangeTracker` + `ChunkMapSaveHook` mixin | Fabric only; tracks saved chunks per dimension |
| Client scan | `LodRequestManager` (441 lines) | Expanding spiral scan, batch management |
| Client networking | `LSSClientNetworking` (245 lines) | Packet handlers, section deserialization |
| Client cache | `ColumnCacheStore` (118 lines) | Persistent binary cache (per-server, per-dimension) |
| Compat | `VoxyCompat` (60 lines) | MethodHandle bridge to Voxy's rawIngest() |
| API | `LSSApi` + `ChunkSectionConsumer` | Public consumer registration |
| Config (Fabric) | `LSSServerConfig`, `LSSClientConfig` | GSON-based with validation |
| Config (Paper) | `PaperConfig` | GSON JSON, same defaults as Fabric |

### Performance profile

| Metric | Value |
|---|---|
| Server memory per player | ~270 KB (state + send queue) |
| Client memory | ~200 KB (timestamp map + pending sets) |
| Global bandwidth cap | 10 MB/s (configurable) |
| Per-player bandwidth cap | 2 MB/s (configurable) |
| Avg section payload | 1-6 KB raw (DEFLATE-compressed by Minecraft's network layer) |
| Serializations/tick cap | 1000 global, 512 work ops/tick/player |
| Disk reader | 2 threads, 64 concurrent reads, 200 results drained/tick |
| Generation | 16 concurrent, 128 queued, 60s timeout |
| Dirty broadcast interval | 15s default (event-driven) |
| LOD radius | 128 chunks default (~2km) |

### Current limitations

1. **No application-level compression needed** — Minecraft's network layer applies zlib/DEFLATE (level 6) to all packets >256 bytes. Pre-compressing with zstd was tested but removed: it prevented DEFLATE from compressing the raw section data, resulting in double CPU cost with no net bandwidth benefit on dedicated servers. The 7 MB zstd-jni native binary also inflated the mod JAR from ~100 KB to ~7 MB.
2. **Full sections only** — No multi-resolution LOD; distant chunks get the same detail as nearby ones.
3. ~~**Periodic resync**~~ — **Resolved**: Server hooks chunk saves via `ChunkMapSaveHook` mixin, tracks dirty columns per dimension in `ChunkChangeTracker`, and periodically broadcasts `DirtyColumnsS2CPayload` to clients. Client re-requests only changed columns.
4. **No deduplication** — Same chunk serialized independently for each player requesting it.
5. **No priority ordering** — Server processes batch positions in client-submitted order (spiral), doesn't reorder by current distance.
6. **No delta updates** — Any change to a section causes full re-send.
7. **Integrated server unsupported** — Only runs on dedicated servers (Fabric or Paper).
8. **No cross-mod protocol** — Only works with LSS client mod; can't serve Distant Horizons or other LOD clients.
9. **Paper: no dirty column tracking** — Paper lacks the `ChunkMapSaveHook` mixin. Clients on Paper servers rely on the `resyncBatchSize` mechanism during initial join sweeps to detect world changes.

---

## Alternative approaches to the same problem

### 1. Server-push model

The server tracks each player's LOD coverage and proactively pushes data as chunks enter their radius or change. No client requests needed.

- **Advantage**: Near-instant change detection (server knows when blocks change), no round-trip latency for initial load, simpler client.
- **Disadvantage**: Server must maintain per-player spatial coverage (~50K entries per player). Reconnection requires rebuilding state or persisting it. Wastes bandwidth if client doesn't need LOD data (AFK, mod disabled).
- **Why LSS chose differently**: Offloading spatial tracking to the client is simpler and scales better with player count.

### 2. Server-side voxelization before transmission

Server converts sections to a compact LOD representation (e.g., palette-compressed voxel data at reduced resolution) before sending. Distant chunks get mipped to lower LOD levels.

- **Advantage**: Massive bandwidth reduction — an L2 section (4x4x4 = 64 voxels) might be 50 bytes vs 8KB raw. The progressive-LOD branch already implements this.
- **Disadvantage**: CPU cost on the server for voxelization. Couples the wire format to a specific LOD representation. Client must understand the format (and may re-mip internally, causing double work).
- **Status**: Already implemented on the `progressive-lod-upgrades` branch. Not on main because the raw path is simpler and the client-side mod handles conversion.

### 3. Delta/incremental updates

Send only the blocks that changed since the client's last-known version, rather than re-sending entire sections.

- **Advantage**: A single block change becomes ~20 bytes instead of ~8KB. Makes short resync intervals practical.
- **Disadvantage**: Requires server-side change tracking (mixin on `LevelChunk.setBlockState`), a delta serialization format, fallback for bulk edits, and client-side patch application. Significant complexity.

### 4. Shared serialization cache across players

Cache recently serialized sections on the server. When multiple players request the same chunk, serialize once and send the cached bytes to all.

- **Advantage**: Reduces server CPU proportionally to player clustering (common near spawn). Zero bandwidth change per-player.
- **Disadvantage**: Cache invalidation on chunk changes. Memory cost (~400MB for full coverage at 128-chunk radius). Doesn't help for unique distant chunks.

### 5. Hybrid push-pull

Push for a near zone (e.g., 32 chunks) where freshness matters, pull for the distant zone where staleness is tolerable.

- **Advantage**: Near terrain loads instantly on join/teleport. Block changes appear in LOD immediately for nearby terrain. Pull handles the bulk volume cheaply.
- **Disadvantage**: Two code paths. Boundary discontinuities. Requires server-side change detection for the push zone.

### 6. Pre-computed LOD store on disk

Pre-compute and store wire-ready LOD data in a secondary on-disk structure. Updated when chunks save. Serving becomes a simple byte read.

- **Advantage**: Near-zero CPU for serving. Enables memory-mapped zero-copy reads. LOD computation amortized over save events.
- **Disadvantage**: Essentially building a secondary storage engine. ~50-100% additional disk space. Cache coherence between LOD store and world data. Major engineering investment.

### 7. Streaming protocol with continuous flow control

Replace batch request-response with a persistent data stream. Client declares interest (position + radius); server streams sections in priority order continuously.

- **Advantage**: No batch overhead, better bandwidth utilization (no idle between batch round-trips), natural priority reordering.
- **Disadvantage**: Application-level flow control is harder to get right. Harder to debug without batch boundaries. Doesn't fit Fabric's discrete payload model cleanly.

### 8. Columnar run-length encoding

Send entire columns (full height) with vertical RLE instead of per-section. Exploits vertical homogeneity (long runs of air, stone, etc.).

- **Advantage**: 30-60% bandwidth reduction for overworld terrain. Fewer packets (one per column vs up to 24).
- **Disadvantage**: Breaks section-as-atomic-unit assumption. Client must split back into sections for LOD mod ingestion. Less effective in the Nether.

---

## Future improvement paths (within current architecture)

### High impact, low complexity

**1. ~~zstd compression of section payloads~~ (EVALUATED AND REMOVED)**
Zstd pre-compression was implemented and tested but removed in v5. Minecraft's network layer already applies zlib/DEFLATE (level 6) to all packets >256 bytes. Pre-compressing with zstd prevents DEFLATE from compressing the raw section data effectively, resulting in double CPU cost with no bandwidth benefit on dedicated servers. The zstd-jni dependency also added 7 MB of native binaries to the mod JAR.

**2. Connection-aware initial burst**
Temporarily elevate a new player's bandwidth allocation (e.g., 3x for the first 60 seconds) to fill their initial LOD view faster. ~10 lines in `RequestProcessingService.tick()`. No protocol change.

**3. Batch position sorting by distance**
In `processRequestBatches()`, sort `batch.positions` by distance to the player's current position before processing. Ensures close sections are served first even if the batch was built before the player moved. One-line sort. No protocol change.

**4. Directional scan bias**
Track player velocity in `LodRequestManager`. When moving, prioritize the forward 180-degree arc in the spiral scan. Terrain in the player's path loads 2-3x faster. Falls back to symmetric scan when stationary. ~40 lines. No protocol change.

### High impact, moderate complexity

**5. ~~Event-driven change detection~~ (IMPLEMENTED in v4)**
`ChunkMapSaveHook` mixin feeds `ChunkChangeTracker`. Server broadcasts `DirtyColumnsS2CPayload` per dimension every 15s (configurable). Client marks dirty columns for re-request. Replaces blind periodic resync.

**6. Server-side serialization cache**
`ConcurrentHashMap<SectionKey, CachedPayload>` keyed by `(dimension, cx, sectionY, cz, lastModified)`. Check before serializing in both `serializeLoadedSection()` and `parseSectionFromNbt()`. Evict on chunk change. Reduces CPU proportionally to player overlap. ~100 lines. No protocol change.

**7. Adaptive LOD distance**
Dynamically scale `lodDistanceChunks` based on server load metrics (tick time, disk queue depth, bandwidth utilization, player count). Send updated `SessionConfigS2CPayload` mid-session when load changes. Prevents overload on busy servers. Requires client support for mid-session config updates (minor protocol extension).

**8. Feature-flag protocol negotiation**
Extend handshake to exchange supported feature sets (compression algorithms, delta updates, voxelized format, etc.). Both sides use the intersection. Designed to be the last breaking protocol change — future features are negotiated within the framework. ~80 lines across handshake and session config payloads.

### Moderate impact, moderate complexity

**9. Tiered palette simplification**
For distant sections (>64 chunks), simplify block state palettes by collapsing variants to their base block (all stair orientations -> default stair, strip waterlogged/powered/etc). Roughly halves palette size for complex sections. No protocol change — same format, lower fidelity.

**10. Region-batched disk reads**
Group disk read requests by region file (32x32 chunk regions). Submit entire region batches to the thread pool so one thread opens a region file once and reads multiple chunks sequentially. Reduces file descriptor churn and seek overhead. ~60 lines in `ChunkDiskReader`.

**11. Client-side section data cache**
Extend `ColumnCacheStore` to optionally persist actual section data (LRU-bounded, e.g., 500MB). On resync, if the server says a column is up-to-date, re-dispatch cached section data to the LOD mod without re-downloading. Eliminates re-download on reconnect/dimension-change-and-back. ~150 lines. No protocol change.

### Lower priority / larger scope

**12. Integrated server support** — Run `RequestProcessingService` on integrated servers (singleplayer). Requires careful thread separation since the client and server share a process.

**13. Distant Horizons protocol compatibility** — Implement DH's network protocol server-side so DH clients can receive LOD data from an LSS server without needing the LSS client mod. Dramatically increases potential user base but requires reverse-engineering DH's protocol.

**14. Async serialization pool** — Move `serializeLoadedSection()` off the server tick thread to a dedicated pool. Requires snapshotting `LevelChunkSection` data (immutable copy) before handing off. Eliminates tick-time impact from serialization.
