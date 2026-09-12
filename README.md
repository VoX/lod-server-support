# LOD Server Support

Enables players with [Voxy](https://modrinth.com/mod/voxy) to see fully rendered terrain out to hundreds of chunks on multiplayer servers without needing to explore the world first. Also includes **Far Players**: players far beyond normal render distance appear in the LOD terrain with name tags, equipment, and mounts.

**Try it live**: join `lod-server-support.modrinth.gg` with Voxy and this mod installed. Supports Minecraft 26.2, 26.1, 1.21.11, and 1.21.1.

https://github.com/user-attachments/assets/721fb344-890e-4e03-ab36-539444427f7b

## Compatibility

Use the matching Fabric or shipped NeoForge artifact. Server support and renderer availability are separate; see the generated [compatibility matrix](docs/compatibility.md). Supported servers:

<!-- LSS SERVER MATRIX START -->
| Minecraft line | Fabric / Paper | Folia | NeoForge shipping | Neo far renderer |
| --- | --- | --- | --- | --- |
| 1.21.1 | maintained | unsupported | shipped; best-effort | available |
| 1.21.10 | maintained | unsupported | maintained build only | unsupported |
| 1.21.11 | maintained | experimental | maintained build only | unsupported |
| 26.1 | maintained | experimental | shipped; best-effort | unsupported |
| 26.2 | maintained | experimental | shipped; best-effort | unsupported |
<!-- LSS SERVER MATRIX END -->

NeoForge 1.21.1 has distinct native and Connector dependency routes. The recorded 2026-09-08 native Voxy 0.2.9-alpha trial was rejected; older successful reports do not establish current compatibility or its failure's upstream cause. Xaero-only legacy Sodium and the modern Connector Voxy route are separate profiles. Use the [dated profile inventory](docs/testing/astra-live-profiles.md) and exact dependency locks; do not combine their jars by filename.

The in-game settings page (Sodium's Video Settings → the LSS entry or tabs; on Fabric also ModMenu's Configure button) renders on both Sodium generations from v0.13.0: on Sodium 0.8+ it appears under LSS's own entry in the settings screen; on Sodium 0.6/0.7 (MC ≤1.21.10 and the 1.21.1 Voxy-fork pairing) it appears as LSS tabs beside Sodium's own. On NeoForge the page renders on both generations too — the 0.6/0.7 tabs on the Voxy-fork pairing, and LSS's own entry on native NeoForge Sodium 0.8+ builds (what the Connector stack pairs with) — including far-player options where the renderer is available (NeoForge 1.21.1). Other NeoForge lines retain intentional renderer stubs.

Compatible with [AntiXray](https://modrinth.com/mod/anti-xray), [Moonrise](https://modrinth.com/mod/moonrise-opt), [C2ME](https://modrinth.com/mod/c2me-fabric), [ViaVersion](https://modrinth.com/plugin/viaversion)/[ViaBackwards](https://modrinth.com/plugin/viabackwards), and most other mods. Can be run alongside Distant Horizons on the same server to support DH clients and Voxy clients simultaneously. 

With [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) 1.42.0 or newer installed on the client, downloaded LOD terrain is also written into the world map, so the map fills in far beyond vanilla render distance (multiplayer only — for single-player worldgen use [Xaero WorldGen](https://modrinth.com/mod/voxyworldgenxaero-bridge) instead). This works even without Voxy: Xaero's Map plus this mod alone will download and map the server's terrain. The bridge is OFF by default (map writes are saved map data — chunks near you stay Xaero's own and Xaero redraws its tiles whenever you revisit an area, but distant LOD-drawn tiles, slightly simplified and matching any anti-x-ray masking the server applies, persist until you do): turn it on with the "Write LODs to Xaero's Map" toggle on the LSS Sodium options page, or `enableXaeroMapBridge` in `lss-client-config.json`. Tiles go to the map's surface layer; while the map is showing a cave layer (underground with auto cave mode, and the Nether by default) terrain that arrives is not written to the map and is not retried — revisiting the area (or `/lss clearcache`) backfills it — and the bridge follows Xaero's own "Load New Chunks" / "Update Chunks" switches. While the map is catching up on a big download, LOD delivery is paced to what the map can draw (`enableXaeroMapBackpressure`, default on), so the map fills in completely as it goes at a slightly slower rate — including brief full pauses of the LOD download (a few seconds) while the map itself is busy writing the terrain around you. On a server you had already explored before installing, run `/lss clearcache` once while connected to re-stream the terrain and backfill the map (a full re-download).

LOD Server Support is backwards and forwards compatible from v0.4.0 through the current version. Server operators can freely update to take advantage of improvements without breaking clients on older versions, and clients can update without breaking compatibility with older servers.

**Far players.** Players beyond your normal render distance are drawn as player models in the LOD terrain, lit as if under open sky (or at full brightness, mounts included, with the "Full Bright Far Players" option / `farPlayersFullBright` in `lss-client-config.json`), with their name tags (also drawn over players still in normal range once they are past the game's own 64-block tag distance) and, within about 80 blocks, their skin overlay layers (farther out the depth buffer cannot separate that thin shell from the body). Two limits worth knowing: players past the game's far plane (your render distance × 64 blocks; Iris shaders extend it) are not drawn at all, whatever the far-player render limit says; and with Iris shaders, packs whose Voxy integration keeps LOD depth out of the vanilla depth buffer (Complementary, for one) draw far players on top of LOD terrain instead of behind it.

[Voxy Server Side](https://modrinth.com/plugin/voxy-server-side) is the same mod. Voxy Server Side clients are compatible with LOD Server Support servers and vice versa.

## Installation

Install **LOD Server Support** on **both** the **server** (Fabric, Paper, or NeoForge on a line that ships it) and **every participating client** (the matching Fabric or NeoForge LSS mod, with Voxy or the enabled Xaero map bridge). The server and client both need LSS for its terrain download service.

NeoForge ships on MC 1.21.1, 26.1 and 26.2; the 1.21.10/1.21.11 modules remain maintained builds. NeoForge far-player rendering is live on 1.21.1 and remains an intentional stub on the other lines. See the [loader/artifact matrix](docs/planning/per-version-surfaces.md#current-loader-and-artifact-surfaces-2026-09-08) and [validation profiles](docs/testing/astra-live-profiles.md) for the separate packaging, consumer and live-test boundaries.

## Commands

### Server (Fabric, NeoForge, and Paper)

- `/lsslod stats` - Show per-player transfer statistics
- `/lsslod diag` - Show detailed diagnostics (config, bandwidth, queue depths)
- `/lsslod set <setting> <value>` - Change common settings live, no restart needed
- `/lsslod store status` - Show LOD store status (state, hit/miss counters, size)
- `/lsslod store backfill start|stop|status` - Control the background pre-warm walk (not on Paper)
- `/lsslod help` - List all commands

### Client (Fabric and supported NeoForge clients)

- `/lss clearcache` - Clear the local column cache, forcing all chunks to be re-requested from the server
- `/lss reset` - Wipe this server's LODs (local cache and Voxy's stored data) and re-stream them fresh
- `/lss reset voxy-force` - Same, but for the case where another mod has redirected Voxy's storage (a replay mod, or any other storage override) and the ordinary reset therefore left Voxy's disk data alone. Shows both storage paths first and deletes nothing until you run `/lss reset voxy-force confirm` within 60 seconds on the same connection
- `/lss diag` - Show client-side diagnostics (connection, throughput, scan progress, request budget)

## Configuration

**Client cache identity.** The client keeps its per-server download cache in per-world buckets automatically: each remote world is identified by the (already hashed) seed value every vanilla login carries, so a server that resets or rotates its map stops serving you stale "already downloaded" terrain, and your first session after upgrading adopts the existing cache warmly. `useWorldSubBuckets: false` in `lss-client-config.json` turns the per-world split off. For a server reachable at several addresses, `cacheAddressAliases` (for example `[["play.example.com", "alt.example.com"]]`) lets all of them share one cache so the world only downloads once — with Voxy installed this needs voxy-extra's LoD Mirror configured with the same list (first entries identical), and LSS applies the alias only when Voxy's own storage confirms it, falling back to the per-address cache otherwise. (On NeoForge, corroboration depends on the actual Voxy/compatibility stack and its observed storage path; an uncorroborated alias keeps separate address caches. The per-world split works on both loaders.) `/lss diag` shows the active cache key on its `Cache:` line.

Config files are generated during first run at `config/lss-server-config.json` on Fabric and NeoForge or `plugins/LodServerSupport/lss-server-config.json` on Paper.

| Setting | Default | Description |
|---------|---------|-------------|
| `enabled` | `true` | Enable LOD distribution |
| `requireServicePermission` | `false` | Serve LOD data only to players who hold **both** the `lss.use` and `vss.use` permission nodes — everyone else is told LOD is unavailable and stops asking, with no session created; a mid-session revocation disconnects the LOD session within ~20 s and a re-grant (or turning the key back off) re-offers it automatically, no rejoin needed (current-protocol clients; a pre-0.10 legacy client keeps its session until it rejoins, where the handshake denies it). Works on all three platforms: Paper/Folia read Bukkit permissions natively; Fabric needs a permission provider implementing fabric-permissions-api (LuckPerms, most permission mods) — with no provider installed everyone is served and the log says so once (within ~10 s of arming); NeoForge registers native permission nodes. Both nodes default to **on for everyone**, so turning this key on by itself changes nothing; you then take a node away from whoever should not be served, and revoking *either* spelling is enough (LuckPerms: `/lp group default permission set lss.use false`, then grant it back to the groups you do want). Fail-open by design — a missing or broken permission backend serves everyone, so treat it as a distribution lever, **not a security boundary** (the data it gates is world terrain the client could also walk to). `false` skips the check entirely |
| `lodDistanceChunks` | `512` | Max LOD distance in chunks |
| `mbPerSecondLimitPerPlayer` | `25.0` | Per-player bandwidth cap in MiB/s (decimals like `12.5` work), counted **before** compression |
| `mbPerSecondLimitGlobal` | `75.0` | Total bandwidth cap across all players in MiB/s, counted **before** compression |
| `enableChunkGeneration` | `true` | Generate missing chunks on demand, so players see terrain nobody has visited |
| `generationConcurrencyLimitGlobal` | `40` | Max chunks generating server-wide at once |
| `generationConcurrencyLimitPerPlayer` | `40` | Max concurrently generating chunks per player |
| `maxConcurrentDiskReads` | `0` | Max LOD disk reads running at once. `0` = auto (half the reader threads while the LOD store is on, all of them otherwise). See **Server Performance Tuning** |
| `dirtyBroadcastIntervalSeconds` | `10` | Interval for pushing dirty column notifications to clients. `0` disables the pushes entirely |
| `farPlayers` | `"on"` | Show distant players as player models in the LOD terrain. `"opt-in"` shows only players who opted in, `"off"` disables. A per-player exclude list is honored on every loader, as are the `lss.farplayers.hidden` / `vss.farplayers.hidden` permissions (players holding either are never shown; note a wildcard permission grant counts as holding them) and vanish: vanish plugins on Paper, [Melius Vanish](https://modrinth.com/mod/vanish) on Fabric and NeoForge. The hide permission only resolves through a permission provider (a fabric-permissions-api backend such as LuckPerms on Fabric, a permission handler mod on NeoForge); without one nobody is hidden. On Paper, players disguised with LibsDisguises are hidden as well. |
| `farPlayersMaxDistanceBlocks` | `2048` | Max distance in blocks at which far players are visible |
| `lodYieldsToVanillaTransport` | `true` | Pause LOD sending to a player while their connection is backed up, so vanilla packets always go first |
| `enablePingBackstop` | `true` | Cut a player's LOD rate when their ping spikes, keeping gameplay responsive on slow connections |
| `enableSendPacing` | `true` | Smooth LOD sending into small per-tick slices instead of bursts |
| `enableRegionSummaries` | `true` | Answer clients' region-summary requests at join/dimension entry: one small frame tells a returning client which areas are unchanged, so it skips re-checking terrain it already has instead of re-asking column by column. Needs a current-version client (older clients simply never ask) |
| `lodStore` | `"on"` (new installs) | Keeps a compressed copy of every served LOD column in `<world>/lss-lod/` and serves repeat requests from it, which is far less CPU and disk work per chunk. The cost: it roughly doubles your world folder. Generated as `"on"` for brand-new servers; on an upgraded server whose config file doesn't have the key, it stays `"off"` until you enable it. See **Server Performance Tuning** |
| `lodStoreBackfill` | `true` | Pre-warms the store with a low-priority background walk of your existing world, so the first player to arrive already gets fast serves. Inert unless `lodStore` is on. Yields to players, pauses under load, resumes across restarts. Not available on Paper |
| `lodStoreMaxMB` | `0` | Size cap for the store. `0` = uncapped; set a value to bound it, and the oldest columns are evicted first |
| `enableV16Compat` | `true` | Serve legacy v0.4.x-v0.6.x clients through a built-in translation layer. `false` requires every client to match the server's protocol |
| `enableV18Compat` | `true` | Serve v0.7.x-v0.8.x clients natively, minus only the features their client predates. `false` drops them to the `enableV16Compat` fallback |
| `enableV19Compat` | `true` | Serve v0.9.x clients natively. `false` drops them to the `enableV16Compat` fallback |
| `xrayObfuscation` | `"auto"` | Anti-xray masking for LOD data. `"auto"` mirrors your anti-xray engine's own hidden-block list and height cutoff whenever one is detected (Paper's built-in, per world; the DrexHD AntiXray mod on Fabric). `"on"` forces masking, `"off"` disables it, in which case LOD data carries real ore locations even on anti-xray servers |
| `xrayHiddenBlocks` / `xrayMaxBlockHeight` | ore list / `64` | Fallback list and Y cutoff, used only when no engine settings can be adopted |


### Server Performance Tuning

See [current performance diagnosis and tuning](docs/operations/performance.md) for workload-based checks, setting semantics and the measurement requirements for presets. The [settings reference](docs/reference/settings.md) records exact domains and apply timing.

## Redistribution

This mod is MIT-licensed, redistribution with attribution is welcome, and modpacks can reference the official Modrinth project directly. Per Modrinth's reupload policy: [XANTHA](https://modrinth.com/user/XANTHA) via [Voxy Server Side](https://modrinth.com/plugin/voxy-server-side) has the copyright holder's explicit permission to distribute this mod, and derivatives of it, on Modrinth.

<!-- LSS COMPATIBILITY START -->
Current platform, shipping and renderer facts: [compatibility matrix](docs/compatibility.md). Dependency locks describe candidates; dated feature evidence establishes tested combinations.
<!-- LSS COMPATIBILITY END -->
