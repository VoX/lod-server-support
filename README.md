# LOD Server Support

Enables players with [Voxy](https://modrinth.com/mod/voxy) to see fully rendered terrain out to hundreds of chunks on multiplayer servers without needing to explore the world first. Also includes **Far Players**: players far beyond normal render distance appear in the LOD terrain with name tags, equipment, and mounts.

**Try it live**: join `lod-server-support.modrinth.gg` with Voxy and this mod installed. Supports Minecraft 26.3, 26.2, 26.1, 1.21.11, 1.21.10, and 1.21.1.

https://github.com/user-attachments/assets/721fb344-890e-4e03-ab36-539444427f7b

## Compatibility

Clients use the Fabric mod on every version; on 26.3, 26.2, 26.1, and 1.21.1 a NeoForge client works as well. Voxy has not published a Minecraft 26.3 build yet: until it does, a 26.3 client gets map ingestion and far players (Fabric) but no LOD terrain. Voxy's 26.3 support in development runs on the OpenGL graphics backend only, so keep Graphics API on Default or OpenGL. No Voxy port exists for NeoForge 26.3, so a NeoForge 26.3 client gets map ingestion only. Supported servers:

| Minecraft | Support | Fabric, Paper | Folia | NeoForge | Far players on a NeoForge client | Sponge |
| --- | --- | --- | --- | --- | --- | --- |
| 26.3 | full | yes | experimental | yes | no | experimental |
| 1.21.1 | full | yes | no | yes | yes | up to 0.21.0 |
| 26.2 | maintained | yes | experimental | yes | no | up to 0.21.0 |
| 1.21.11 | maintained | yes | experimental | no | no | up to 0.21.0 |
| 26.1 | frozen at 0.21.0 | yes | experimental | yes | no | 0.21.0 |
| 1.21.10 | frozen at 0.21.0 | yes | no | no | no | 0.21.0 |

**Support:** *full* versions get every release, including betas. *Maintained* versions get every stable release: fixes, and new features where they port cleanly. *Frozen* versions keep 0.21.0 and get crash and security fixes only. From 0.22.0 the Sponge plugin is built for 26.3 only. Details: [compatibility](docs/compatibility.md).

**Sponge (experimental):** a SpongeVanilla server plugin ships for 26.3 (0.21.0 and older also shipped it for the other versions). It speaks the same wire as every other server, so any Fabric or NeoForge client with LSS joins it normally. On SpongeNeo, use the NeoForge jar instead, on lines that ship one (see the matrix). SpongeForge is not supported.

NeoForge 1.21.1 has distinct native and Connector dependency routes. The recorded 2026-09-08 native Voxy 0.2.9-alpha trial was rejected; older successful reports do not establish current compatibility or its failure's upstream cause. Xaero-only legacy Sodium and the modern Connector Voxy route are separate profiles. Use the [dated profile inventory](docs/testing/astra-live-profiles.md) and exact dependency locks; do not combine their jars by filename.

The in-game settings page (Sodium's Video Settings → the LSS entry or tabs; on Fabric also ModMenu's Configure button) renders on both Sodium generations from v0.13.0: on Sodium 0.8+ it appears under LSS's own entry in the settings screen; on Sodium 0.6/0.7 (MC ≤1.21.10 and the 1.21.1 Voxy-fork pairing) it appears as LSS tabs beside Sodium's own. On NeoForge the page renders on both generations too — the 0.6/0.7 tabs on the Voxy-fork pairing, and LSS's own entry on native NeoForge Sodium 0.8+ builds (what the Connector stack pairs with) — including far-player options where the renderer is available (NeoForge 1.21.1). Other NeoForge lines retain intentional renderer stubs.

Compatible with [AntiXray](https://modrinth.com/mod/anti-xray), [Moonrise](https://modrinth.com/mod/moonrise-opt), [C2ME](https://modrinth.com/mod/c2me-fabric), [ViaVersion](https://modrinth.com/plugin/viaversion)/[ViaBackwards](https://modrinth.com/plugin/viabackwards), and most other mods. Can be run alongside Distant Horizons on the same server to support DH clients and Voxy clients simultaneously. 

For a Voxy player on an existing DH server, ask the admin to add LSS alongside DH. This provides native LSS terrain while preserving support for DH clients.

**Experimental: terrain from Distant Horizons servers (client feature; Fabric with Voxy, NeoForge with Foxy).** A Voxy client can show distant terrain from a server that runs the official [Distant Horizons](https://modrinth.com/mod/distanthorizons) server mod but has no LSS. It is on by default. To turn it off, disable "Allow DH Server Terrain" on the LSS settings page, or set `lod.source: lss` in `lss-client-experimental.yaml`. For the best results, servers should still install LSS. What to expect:

- Native LSS always wins. If the server offers LSS service (even disabled), the DH path stays idle.
- Voxy is required (on NeoForge, the Foxy port of Voxy), and the Distant Horizons *client* mod must not be installed.
- Changing the option applies on reconnect on Fabric. On NeoForge the network channel is registered at startup only when the option is on: turning it back on needs a game restart, and after turning it off the DH path stops on reconnect but the channel stays advertised until you restart the game.
- Supported servers: official DH 3.3.x (protocol 16) and DH Support 15. Older official DH (3.2.x) does not answer this client; `/lss diag` then shows "no DH reply" (the server may run DH 3.2.x or have DH networking off) and nothing further is sent.
- DH sends a block-resolution approximation. It can overwrite more detailed Voxy terrain you built up nearby with vanilla, except chunks your client loaded with vanilla during the current connection.
- Sync on load and live updates follow the server's settings and `lod.dh.sync_on_load` / `lod.dh.real_time_updates`. With sync on load, an edit missed while you are far away or the client is busy appears when that terrain comes back into view after the 20-minute load interval, after `/lss reset`, or on rejoin; with sync off it appears only after `/lss reset`.
- When the world can be identified (the server sends DH's world key and seed), the client keeps small per-server timestamp receipts, so a rejoin does not download terrain it already has. With sync on load, changed tiles are rechecked and refreshed; with sync turned off (on the server or in `lod.dh.sync_on_load`), terrain you already have is kept as it is, like the official DH client, and edits made while you were away are not fetched. `/lss reset` clears receipts.
- With the option on, the client advertises the optional `distant_horizons:msg` channel to every server; on a server without DH it stays idle and sends nothing.
- The experimental keys live in `lss-client-experimental.yaml` next to `lss-client-config.yaml`, so downgrading to a build without this feature keeps the main settings file readable. That file holds only changed values: turning the option off writes `source: lss` there.
- `/lss trace` files include block coordinates; review before sharing. This feature is not endorsed by the Distant Horizons team.

**Map ingestion.** With [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) 1.42.0 or newer or [JourneyMap](https://modrinth.com/mod/journeymap) 6.0.0 or newer installed on the client, downloaded LOD terrain is also written into the map, so it fills in far beyond vanilla render distance (multiplayer only — for single-player worldgen use [Xaero WorldGen](https://modrinth.com/mod/voxyworldgenxaero-bridge) instead). This works even without Voxy: a map mod plus this mod alone will download and map the server's terrain. If you add Voxy later, LSS downloads that terrain again so Voxy gets it too (for a map-only cache made by LSS 0.17 or older, run `/lss clearcache` once on that server, or press **Re-download Terrain** on the LSS Sodium options page). Map ingestion is ON in new settings files (an upgraded file keeps the choice written in it — every file LSS 0.16 or 0.17 generated says off — while a hand-trimmed file without the key follows the new default): toggle "Map Ingestion" on the LSS Sodium options page, or `integrations.map_ingestion.enabled` in `lss-client-config.yaml` (the older `integrations.xaero_map` group is still read), then run `/lss reload` and reconnect. A server can ask clients not to do this (`privacy.request_no_map_ingestion`); this client then pauses map writes for that connection and the options page shows "Turned off by this server", while Voxy keeps working. A client with a map mod but no Voxy downloads no LOD terrain at all until the server lifts the request. Map writes are saved map data — chunks near you stay the map's own and it redraws them whenever you revisit an area, but distant LOD-drawn tiles, slightly simplified and matching any anti-x-ray masking the server applies, persist until you do. While the map is catching up on a big download, LOD delivery is paced to what the map can draw (`integrations.map_ingestion.backpressure`, default on), so the map fills in completely as it goes — at the map's speed, which with Voxy also paces Voxy: measured on a 26.2 client filling a 128-chunk area of real terrain, JourneyMap draws about 550 columns per second (about 650 without a map). Other versions were not measured; on 1.21.1, 0.19.0's JourneyMap speed-up is smaller. With backpressure on, map writes also pause briefly when the client's heap is nearly full. Terrain that arrives while map writing is off (the setting, the map mod's own mapping switch, or the server's request) is not written later; as on a server you had already explored before installing, run `/lss clearcache` (or press Re-download Terrain) once while connected to re-stream the terrain and backfill the map (a full re-download).

- **Xaero's World Map:** tiles go to the map's surface layer and, within 128 chunks of you, to the cave layer the map is showing. Xaero keeps a separate cave map for every 16 blocks of height (whenever you're under a roof with auto cave mode, and always in the Nether below the roof), or one cave map in its "Full" cave mode. In the Nether, LSS also keeps a copy of the downloaded terrain on disk, so when you move to a different height the map fills that height's cave map from it within 128 chunks of you (around ten seconds for the default 64-chunk Nether distance on a focused game window). That copy takes roughly 65 MB at the default distance, and the copies of all servers together stay under 256 MB. To change that limit, set `integrations.map_ingestion.nether_store_mib` in `lss-client-experimental.yaml` (at most 1024; while you're in the Nether, the copy's index uses memory equal to about 2.5% of the copy's size). `/lss reload` applies it. A smaller limit frees disk space the next time a copy is written or read, usually on your next visit to the Nether; 0 turns the copy and the refill off and deletes the copies at once. `/lss clearcache` removes the copy for the server you're on; it stays on disk if you only turn map ingestion off. In the Overworld and End, terrain that arrives while you're under a roof goes into the cave map you're viewing; terrain that arrived while you were outside isn't copied into cave maps later (`/lss clearcache` or Re-download Terrain while underground backfills them). Near you the map draws cave layers itself, so you may see a slight shading seam where its tiles meet LSS's. On a LAN host, cave layers aren't written: Xaero builds them from the world save. The bridge follows Xaero's own "Load New Chunks" / "Update Chunks" switches. With the game window unfocused (Dynamic FPS's 1 FPS), Xaero frees map memory slowly, so the download pauses and resumes in cycles until the window is focused again.
- **JourneyMap:** columns go to its day/night and biome maps (its topography map only once JourneyMap's own topography renderer is ready), and the ring between JourneyMap's own mapping range and the LOD area is filled from the chunks your client has loaded. Nothing is written while JourneyMap's own mapping is switched off, in dimensions with a ceiling (the Nether), or where the server does not allow JourneyMap mapping. JourneyMap keeps one map per server-list entry: renaming the entry starts a new map while LSS still has the terrain, so run `/lss clearcache` (or press Re-download Terrain). JourneyMap stores about 1 MB of map images per 32×32-chunk region (day, night, biome and topography), so a 512-chunk LOD distance adds roughly 1 GB of map data. JourneyMap 1.21.10 (6.0.0 beta) is not supported.

LOD Server Support is backwards and forwards compatible from v0.4.0 through the current version. Server operators can freely update to take advantage of improvements without breaking clients on older versions (unless the server turns on `privacy.request_no_map_ingestion`, which needs 0.19.0 or newer clients), and clients can update without breaking compatibility with older servers. One local exception: once this version saves the client settings file with `integrations.map_ingestion` in it, LSS 0.17 and older cannot read that file and start without LODs; before downgrading, rename `map_ingestion:` back to `xaero_map:` under `integrations:` (or delete the file).

**Far players.** Players beyond your normal render distance are drawn as player models in the LOD terrain (Fabric client), lit as if under open sky (or at full brightness, mounts included, with the "Full Bright Far Players" option / `far_players.full_bright` in `lss-client-config.yaml`), with their name tags (also drawn over players still in normal range once they are past the game's own name-tag distance) and, within about 80 blocks, their skin overlay layers (farther out the depth buffer cannot separate that thin shell from the body). Two limits worth knowing: players past the game's far plane (your render distance × 64 blocks, or the cloud distance when that is larger) are not drawn unless you turn on "Extend Far Player Draw Range" (`far_players.extend_far_plane`, kept in `lss-client-experimental.yaml`), which is only visible with an LOD mod such as Voxy and is not used while a shader pack is on (fog can still hide very distant players: if they fade out, turn off your LOD mod's fog); and with Iris shaders, packs whose Voxy integration keeps LOD depth out of the vanilla depth buffer (Complementary, for one) draw far players on top of LOD terrain instead of behind it.

*Who is shown.* The server's `far_players.mode` decides:

- `off`: nobody.
- `opt_in`: only players whose own client shares their position.
- `on` (the default): every player in your dimension within the server's range (`far_players.distance.max_blocks`, 2048 blocks unless changed), including players without the mod. A player can hide with Share My Position.
- `required` (new in 0.21.0): every player is shown as a far player and cannot opt out, except holders of `lss.farplayers.hidden` (`vss.farplayers.hidden`). Players whose choice is overridden are told in chat.

Never sent, in any mode: spectators (unless `far_players.send_spectators` is on), invisible players, vanished players (the vanish plugins and mods LSS reads), players disguised with LibsDisguises, and anyone listed in `far_players.excluded_players`.

*How a player hides.* Turn off "Share My Position" on the Far Players page of the LSS options (`far_players.sharing.enabled` in `lss-client-config.yaml`); `far_players.sharing.max_distance_blocks` limits how far away others may see you instead of hiding you completely. The server honors both for every player in modes `on` and `opt_in`. In mode `required` it honors them only for players who hold the hide permission. Anyone else who has Share My Position off or a share distance set is told so in chat, once per connection: "This server requires position sharing: other players can see where you are from far away, and your Share My Position setting does not apply here." The server sends that line itself, so it also reaches older versions of the mod, and it sends it once the player can be shown to someone (not while they are vanished, invisible, dead or a spectator the server does not send). Clients on 0.21.0 or newer show it in their own language (English, Simplified Chinese, Traditional Chinese); every other client shows the English text.

*Players without the mod cannot hide themselves.* Share My Position is a setting of the LSS client mod. In mode `on` the server also shows players who do not have the mod, and they have no way to opt out. If that matters on your server, use `mode: opt_in` (only players whose own client shares are shown) or list those players in `far_players.excluded_players`.

*Changed in 0.21.0: the hide permission.* The `lss.farplayers.hidden` (`vss.farplayers.hidden`) permission no longer hides a player by itself. A grant of every permission (`*`) includes it, so it used to hide every admin from everyone without saying so. The permission now means "may be hidden": automatically if the server sets `far_players.hide_permission_holders: true` (every holder is hidden in every mode, as before 0.21.0), by the player's own choice on a `required` server, and with no effect in mode `on`, where everyone may hide. Both settings apply with `/lsslod reload` (no restart). While `hide_permission_holders` is off, the server logs one warning after each start, the first time it sees a player who holds the permission during a far-player update (so only while at least one player who receives far players is online); it names both ways to hide players again, `hide_permission_holders: true` and `far_players.excluded_players`. A server whose settings file is from before 0.21.0 and has never set `hide_permission_holders` also tells each holder it shows, in chat and once per connection: "You have the lss.farplayers.hidden permission. Since 0.21.0 it no longer hides you from far players by itself: turn off Share My Position to hide. Server owners: set far_players.hide_permission_holders in the server config (true hides holders as before, false keeps this) to stop this message." Writing the key into the file with either value and running `/lsslod reload` stops that message for everyone; a file 0.21.0 created already has the key, so a new server never sends it. The server sends it only after two updates in a row read the player as a holder, and not to a holder who already has Share My Position off or is listed in `excluded_players`. An upgraded settings file gains the `hide_permission_holders` key only when you change the setting or write it yourself. A file that carries the key (every file 0.21.0 or newer creates) or says `mode: "required"` loads on LSS 0.20 and older only after deleting that line or changing the mode.

*Far players don't show up?* Check in this order:

1. **Server mode and range.** `far_players.mode` is `on` or `required` (with `opt_in` the other player must be sharing), you are in the same dimension, and the other player is within `far_players.distance.max_blocks` and beyond `min_blocks`.
2. **The other player's Share My Position** is on (it is by default; on a `required` server it only matters for players who hold the hide permission), and they are not a spectator, invisible, vanished, disguised or in `excluded_players`.
3. **The setting and the permission.** With `far_players.hide_permission_holders: true`, everyone who holds the hide permission is hidden, and admins with `*` hold it. On the server, the `FarPlayers:` line of `/lsslod diag` adds `hidden=<n>` (players withheld by vanish, a disguise or that setting in the last update), `perm_holders=<n>` (holders online while the setting is off, including ones hidden for another reason or in another dimension) and `overridden=<n>` (players whose own sharing choice a `required` server is overriding).
4. **What your client receives.** `/lss status` shows "Far players sent by this server". 0 means the server sends you nobody, so the cause is one of the three above. Any other number means they arrive and your client is not drawing them:
5. **Fog.** On Minecraft 26.x, with your LOD mod's fog on (Voxy's default), far players past about 900 blocks fade into the fog. Turn the LOD mod's fog off.
6. **Draw range.** Players past the game's far plane need "Extend Far Player Draw Range"; also check that "Show Far Players" is on, that "Far Player Render Limit" is 0 or large enough, and that `/lss status` does not say the far-player renderer is unavailable on your loader.

[Voxy Server Side](https://modrinth.com/plugin/voxy-server-side) is the same mod. Voxy Server Side clients are compatible with LOD Server Support servers and vice versa.

## Installation

Install **LOD Server Support** on **both** the **server** (Fabric, Paper, NeoForge on a line that ships it, or the experimental Sponge plugin on SpongeVanilla) and **every participating client** (the matching Fabric or NeoForge LSS mod, with Voxy or the enabled Xaero map bridge). The server and client both need LSS for its terrain download service.

NeoForge ships on MC 1.21.1, 26.1, 26.2 and 26.3; the 1.21.10/1.21.11 modules remain maintained builds. NeoForge far-player rendering is live on 1.21.1 and remains an intentional stub on the other lines. See the [loader/artifact matrix](docs/planning/per-version-surfaces.md#current-loader-and-artifact-surfaces-2026-09-08) and [validation profiles](docs/testing/astra-live-profiles.md) for the separate packaging, consumer and live-test boundaries.

On SpongeVanilla, put the Sponge jar in the server's `mods/` folder. At startup Sponge prints a notice that the plugin uses Mixins; that is expected.

## Commands

### Server (Fabric, NeoForge, Paper, and Sponge)

- `/lsslod stats` - Show per-player transfer statistics
- `/lsslod diag` - Show detailed diagnostics (config, bandwidth, queue depths)
- `/lsslod reload` - Validate server YAML and apply supported changes; report restart requirements
- `/lsslod store status` - Show LOD store status (state, hit/miss counters, size)
- `/lsslod store backfill start|stop|status` - Control the background pre-warm walk (not on Paper)
- `/lsslod help` - List all commands

### Client (Fabric and supported NeoForge clients)

- `/lss reload` - Read saved client YAML and apply supported changes; report reconnect requirements

- `/lss clearcache` - Clear this server's download records so the server sends its distant terrain again (a full re-download). Terrain your LOD mod has already saved is not deleted; `/lss reset` does that. The **Re-download Terrain** button on the General page of the mod's entry in Sodium's video settings (Sodium 0.8 or newer) does the same after a confirm screen. In singleplayer or at the title screen it clears the records of every server. On a server that is not sending distant terrain at that moment (for example with Receive Server LODs off) it clears nothing and says so
- `/lss reset` - Wipe this server's LODs (local cache and Voxy's stored data) and re-stream them fresh
- `/lss reset voxy-force` - Same, but for the case where another mod has redirected Voxy's storage (a replay mod, or any other storage override) and the ordinary reset therefore left Voxy's disk data alone. Shows both storage paths first and deletes nothing until you run `/lss reset voxy-force confirm` within 60 seconds on the same connection
- `/lss diag` - Show client-side diagnostics (connection, throughput, scan progress, request budget)
- `/lss trace` - Start or stop a local event trace for native LSS or experimental DH terrain loading. Traces include movement coordinates and stay in the instance's `logs` directory.

## Configuration

Settings use annotated YAML: `config/lss-server-config.yaml` and
`config/lss-client-config.yaml` on Fabric/NeoForge (the server file also on Sponge), or
`plugins/LodServerSupport/lss-server-config.yaml` on Paper. VSS uses the corresponding
`vss-` names, adopting an existing file across brands. Startup migrates an old JSON
file once and retains both the original and an exact-byte backup. Existing distance
choices are preserved; fresh servers use **512 chunks in the Overworld and End,
64 in the Nether**.

Migration accepts comments and the old loader's loose JSON syntax. It is one-way:
v0.15.x reads the retained JSON and ignores YAML edits, so copy any desired changes
back into JSON before downgrading. `/lsslod set` was removed; edit YAML and run
`/lsslod reload` instead.

Edit the file and run `/lsslod reload` on the server or `/lss reload` on the client.
Sodium Apply **saves and reloads automatically**. Direct YAML edits still need the
reload command. The menus support English, Simplified Chinese and Traditional Chinese.
Invalid files leave active settings unchanged. Reload reports values that require a
restart or reconnect; it does not rewrite comments or normalized values.

| Setting group | Purpose |
| --- | --- |
| `lod.distance` | Server distance defaults and dimension overrides; Paper adds exact world-name overrides. |
| `generation` | Enable generation, bound concurrent work, and set ticket timeouts in ticks. Disabling stops new admissions while existing work drains. |
| `network` | Bandwidth limits in MiB/s before compression, queues, pacing and yielding to vanilla traffic. |
| `storage.disk` | Read concurrency and reader-pool configuration. Concurrency reloads; changing the pool requires a restart. |
| `storage.lod_store` | Cache processed columns to save repeated parsing/serialization CPU, using additional disk space that can be comparable to the world itself. Cap, resweep and backfill controls reload; enabling the store requires a restart. |
| `privacy.xray` | Anti-xray policy and fallback masking. Changes require a restart. |
| `privacy.request_no_map_ingestion` | Ask players' clients not to copy LOD terrain into map mods (below). Reloads. |
| `far_players` | Distant player display/sharing policies and distances in blocks. Players control local rendering manually. `mode` is `off`, `opt_in`, `on` or `required` (everyone is shown; only holders of the far-player hide permission can opt out). `hide_permission_holders` (off by default) hides every player who holds that permission, as before 0.21.0. |

**Going back from 0.21.0 to an older server version.** LSS 0.20 and older reject settings they do
not know, and then serve no LODs at all: the log says `Server settings unavailable; LOD service
remains disabled. Repair YAML and restart:` followed by the reason. Two things in a 0.21.0 server
file cause it:

- `far_players.hide_permission_holders` (`Unknown settings path: far_players.hide_permission_holders`).
  Every server file 0.21.0 creates has this line; a file upgraded from 0.20 has it only if you
  changed the setting or wrote it yourself. Delete the line.
- `far_players.mode: "required"` (`Expected off, opt_in or on at far_players.mode`). Change the mode
  to `on`, `opt_in` or `off`.

Deleting the whole file also works (the older version writes a fresh one with its defaults).

**Paper: use the jar for your Minecraft version.** Paper only refuses a plugin built for a
*newer* Minecraft version, so from 0.20.0 the Paper/Folia plugin checks for itself: on a
server running a Minecraft version its jar was not built for, it logs one error naming the
build to download and disables itself. A 26.x jar runs on that version's patch releases
(the 26.3 jar on 26.3.1); a 1.21.x jar runs only on its exact version. A jar for another
version used to start anyway and could fill the LOD store with empty terrain, so after the
update to 0.20.0 a Paper or Folia server rebuilds its LOD store once (it refills as players
are served); Fabric, NeoForge and Sponge keep theirs. Players who joined while a jar for
another version was running should run `/lss clearcache` once on that server (or press
Re-download Terrain on the LSS Sodium options page).

**Turning LODs off in a dimension.** Set its distance to `0`: no LOD terrain is sent there
(players stay connected to LSS, and far players keep working). Add `default_chunks: 0` to
serve only the dimensions you list. Paper's `by_world` takes world names and works the
same way. After `/lsslod reload`, `/lsslod diag` lists the worlds that are off. Clients
older than LSS 0.10 are never told a dimension is off, and 0.10–0.13 clients learn it only
when they join in that dimension: they keep asking, and the server simply sends them
nothing there. LSS 0.17 and older raised a `0` to 1, so check existing files for zeros
before upgrading.

**Keeping LOD terrain off players' maps.** Set `privacy.request_no_map_ingestion: true`
and run `/lsslod reload` to ask players' clients not to copy distant terrain into
Xaero's World Map or JourneyMap. Clients on 0.19.0 or newer honor it for as long as it is
on. Older clients can't, so while it is on they get no distant terrain (and no far
players) and see a chat line asking them to update; turning it back off offers them
terrain again without a rejoin. It is a request, not a lock: clients still receive the
terrain and Voxy still draws it, a modified client can ignore the request, maps players
already saved keep what they have, and the map mods still map the areas players explore
themselves. Legacy-protocol clients (older than 0.10) that are already connected when you
turn it on keep their terrain until they reconnect; they have no map support. On a server that
also runs Distant Horizons, a refused client doesn't fall back to DH terrain either. A server file with this
key will not load on LSS 0.18 or older; remove the line before downgrading.

To let some players (admins, say) keep filling their maps while it is on, grant them the
`lss.map_ingestion` permission (`vss.map_ingestion` for VSS) with a permissions plugin or
mod such as LuckPerms; nobody holds it by default, and on Fabric there is nothing to grant
it with until a permissions mod is installed. The server treats the request as off for
those players: clients on 0.19.0 or newer pick up a grant or a revoke within about 10
seconds and map the terrain that arrives from then on. Older clients that hold it are
served instead of refused (a refused one is offered terrain within about 10 seconds of a
grant); taking it away from an older client applies when it next joins. `/lsslod diag`
shows the count on its `MapPolicy:` line (`exempt=`).

```yaml
lod:
  distance:
    default_chunks: 512
    by_dimension:
      "minecraft:overworld": 512
      "minecraft:the_nether": 0   # no LODs in the Nether
      "minecraft:the_end": 512
```

The client cache uses `cache.use_world_sub_buckets` to separate remote worlds and
`cache.address_aliases` to group equivalent addresses. With Voxy, aliases also need
matching LoD Mirror/storage corroboration; otherwise LSS retains separate address
caches. Changes take effect on the next physical connection. `/lss diag` shows the
active identity. See the [complete settings reference](docs/reference/settings.md)
for exact keys, units, limits, defaults, zero meanings and activation timing.

### Server Performance Tuning

See [current performance diagnosis and tuning](docs/operations/performance.md) for workload-based checks, setting semantics and the measurement requirements for tuning. The [settings reference](docs/reference/settings.md) records exact domains and apply timing.

## Redistribution

This mod is MIT-licensed, redistribution with attribution is welcome, and modpacks can reference the official Modrinth project directly. Per Modrinth's reupload policy: [XANTHA](https://modrinth.com/user/XANTHA) via [Voxy Server Side](https://modrinth.com/plugin/voxy-server-side) has the copyright holder's explicit permission to distribute this mod, and derivatives of it, on Modrinth.

Per-version platform and support details: [compatibility](docs/compatibility.md).

Thanks to SeeU for the inspiration for distant player rendering.
