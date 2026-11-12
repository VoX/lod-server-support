# Troubleshooting terrain and status

Start with `/lss status` and `/lss diag`. They work without an active request manager and do not start a handshake or download. **More** pages through text; **Export** saves a local report. The detailed CLI retains its connection counters, while the summary counts progress from its first sample in the current world. A disconnect or replacement world invalidates the summary immediately.

| Observation | Check next |
| --- | --- |
| Reception OFF | Enable `lod.receive` through Sodium and Apply, or edit YAML and run `/lss reload`. OFF preserves stored world data; it is not a cache reset. |
| Awaiting negotiation or handshake send failure | Check that the server has the matching LSS platform artifact and that its startup log shows successful initialization. Preserve the failed-state report before reconnecting. |
| Protocol rejected | Compare the actual client/server versions and compatibility settings. Do not disable compatibility protections as a generic repair. |
| Server explicitly disabled | Ask an operator to inspect `/lsslod diag`, server configuration and any configured service permission gate. A world whose LOD distance is 0 also reads as disabled while you are in it (`/lsslod diag` lists those worlds); it clears when you enter a served world. The client cannot infer the remote reason. |
| No consumer; integration unknown/unavailable/failed | Check the exact loader, optional-mod versions and route in the [catalog](../compatibility.md). Unknown resolution is distinct from a failed or unsupported integration. |
| Queues or rate events | Compare several timestamped samples. Queue membership alone does not prove overload. Rate events include the composed manual/adaptive local gate, so they do not identify the manual setting as the cause. |
| Map ingestion: nothing on the map | `/lss diag` prints `XaeroMap:` and `JourneyMap:` lines. `state=inert(…)` names why JourneyMap takes nothing (its mapping switched off, a dimension with a ceiling, mapping not allowed by the server); `waiting(…)` is transient; `unsupported` means JourneyMap older than 6.0.0 (the 1.21.10 beta); `unavailable(…)` names an unrecognized JourneyMap member. Turning map ingestion on needs a reconnect. Terrain your client already had is not re-sent: run `/lss clearcache` once, or press Re-download Terrain on the mod's General page in Sodium's video settings (Sodium 0.8 or newer), to backfill the map. |
| LODs stop after downgrading LSS | LSS 0.17 and older cannot read a client settings file saved with `integrations.map_ingestion`; their log says `Client settings inactive: Unknown settings path …`. Rename `map_ingestion:` back to `xaero_map:` under `integrations:` (or delete the file). |
| Far-player renderer unavailable | Check the line/loader renderer capability. A deliberate renderer stub is not an integration failure. |
| Far players don't show up | `/lss status` shows "Far players sent by this server". **0:** the server sends this client nobody. Check `far_players.mode` (`opt_in` needs the other player to share), the distance limits, the other player's Share My Position (in mode `required` it counts only for holders of the hide permission), vanish and disguise plugins, `far_players.excluded_players`, and `far_players.hide_permission_holders` (on, it hides everyone who holds `lss.farplayers.hidden` or `vss.farplayers.hidden`, which a `*` grant includes). The `FarPlayers:` line of `/lsslod diag` adds `hidden=<n>` for players withheld by vanish, a disguise or that setting, `perm_holders=<n>` for holders online while the setting is off (the permission does not hide them; the count includes holders hidden for another reason or in another dimension), and `overridden=<n>` for players whose own sharing choice mode `required` is overriding and who can be shown to someone. **More than 0:** the client receives them but does not draw them. On 26.x an LOD mod's fog hides far players past about 900 blocks (turn that fog off); players past the game's far plane need "Extend Far Player Draw Range"; check "Show Far Players" and "Far Player Render Limit". The full checklist is in the [README](../../README.md). |
| A player gets "This server requires position sharing…" in chat | The server runs `far_players.mode: required`: everyone is shown as a far player, and Share My Position (or a share distance) applies only to players who hold `lss.farplayers.hidden` or `vss.farplayers.hidden`. The server sends the line once per connection to each player whose choice it overrides and who can be shown to someone (not while they are a spectator the server does not send, invisible, dead or vanished), on every client version. It acts on "does not hold the permission" only after two updates in a row, so a newly joined player who opted out stays hidden for one extra update. To let a player hide, grant the permission; to let everyone hide, use mode `on`. |
| A staff group reappeared as far players after updating to 0.21.0 | The hide permission no longer hides a player by itself (a `*` grant used to hide every admin). The server logs one warning naming the first holder it sees after each start (during a far-player update, so only once a player who receives far players is online). Set `far_players.hide_permission_holders: true` and run `/lsslod reload` to hide every holder as before, or list players in `far_players.excluded_players` to hide them whatever their permissions. |
| A player gets "You have the lss.farplayers.hidden permission…" in chat | The server's settings file is from before 0.21.0 and has never set `far_players.hide_permission_holders`, so the server tells each holder it shows, once per connection, that the permission no longer hides them (after two updates in a row read them as a holder; not to a holder who already has Share My Position off or is listed in `excluded_players`). To stop it for everyone, write the key under `far_players:` in the server settings file and run `/lsslod reload`: `hide_permission_holders: true` hides every holder as before, `false` keeps them shown. The player can hide on their own by turning off Share My Position. |
| After going back to LSS 0.20 or older, the server sends no LODs | The older version rejects settings it does not know. Its log says `Server settings unavailable; LOD service remains disabled. Repair YAML and restart: Unknown settings path: far_players.hide_permission_holders` (every server file 0.21.0 creates has that line: delete it) or `… Expected off, opt_in or on at far_players.mode` (the file says `mode: "required"`: change it to `on`, `opt_in` or `off`). Deleting the file also works. Then restart. |
| Dark LOD terrain on a world upgraded with `--forceUpgrade`, "Optimize World" or `--eraseCache` | Those tools strip the stored light from chunks last saved before Minecraft 1.21.9 (and `--eraseCache` from every chunk); the game relights a chunk only when it loads it, and LSS serves what is on disk. Clients on 0.21.0 or newer light such terrain themselves, so updating the client is the fix; older clients show it dark. Pre-loading the area (for example with Chunky) makes the game relight and re-save it, which gives new downloads exact light, but terrain a player already has is not sent again by that: use Re-download Terrain on the mod's Sodium page (or `/lss clearcache`) once. The server's `lightless=` diag token shows the condition. Avoid `--eraseCache` on a served world. |
| Distant terrain looks wrong or has gaps | Run `/lss clearcache` or press Re-download Terrain while connected: it clears this server's download records and the server sends the terrain again (terrain Voxy already saved stays; `/lss reset` deletes that too). "Not downloading distant terrain from this server right now. Nothing was cleared." means there is no download that could send it again: check the rows above (Reception OFF, negotiation, server disabled) first. On a Distant Horizons server the same answer appears while the server offers neither sync nor generation, while Voxy cannot take terrain, and while the source is stopped. A stopped Distant Horizons source (`/lss status` shows "stopped") is not restarted by this action: reconnect, or turn Receive Server LODs off and on. In singleplayer or at the title screen the same action clears the records of every server. |
| Missing terrain with generation disabled | Verify that the requested terrain exists. Disabling generation does not scan or certify the world's coverage. |
| No distant terrain in part of a world, and the server shows `undecodable=` or logs "could not be decoded" | The server found chunks whose blocks it does not know. See [chunks the server cannot decode](#chunks-the-server-cannot-decode). |
| The server shows `lightless=<n>` on the `DiskReader:` line of `/lsslod diag` | The server read chunks that have blocks but were saved without sky light, in a dimension that has sky light. That is typical after a world was upgraded or optimised from an older Minecraft version, or converted. Clients on 0.21.0 or newer light such terrain themselves; older clients show it dark. Loading the area in game (for example with Chunky) makes Minecraft relight and save it. The token counts reads and is absent while the count is zero. |
| The server log says a chunk "is saved without sky light" | The same condition as `lightless=`, logged once per LSS service start for the first such chunk. It is information, not an error: nothing is withheld and the terrain is sent as it is saved. |

Use `/lss diagnostics export` for a client report or `/lsslod diagnostics export` as an operator. The command displays the local JSON path; the adjacent text file is its summary. Reports exclude addresses, player identities, world keys, seeds, aliases, personal paths and raw exception messages. No upload occurs. The full local `/lss diag` and ordinary logs can contain identifying context, so they are not equivalent to the allowlisted export. See [export behavior](status-and-presets.md).

For a setting change, inspect its [apply timing](../reference/settings.md). Sodium Apply saves and automatically reloads client settings; wait for completion and reconnect if requested. Direct YAML edits require `/lss reload` for client settings or `/lsslod reload` for server settings. If saving fails, recover the retained draft and repair the target file's permissions or disk space before retrying. Server reports distinguish the owner's generation admission gate from the accepted configured value; generation changes apply through reload. Restart is required only for paths explicitly reported as pending. The schema-1 JSON field `generationConfiguredForRestart` retains its historical name for compatibility and now means the accepted configured generation flag. Do not use cache resets, store invalidation, privacy changes or anti-xray changes as an automatic response to a slow download. See [performance diagnosis](performance.md).

## Chunks the server cannot decode

Two signs, both on the server:

- `/lsslod diag` shows `undecodable=<n>` at the end of the gate counters on its `DiskReader:` line. The token is absent while the count is zero.
- The log has one warning per LSS service start: `Chunk [x, z] in <dimension> could not be decoded: every block it uses is unknown to this server (first error: …)`. It names the first chunk only; the counter carries the total.

What it means: the chunk is saved on disk, but none of the blocks in it can be read by this server. The usual causes are a mod that was removed (the chunk holds only that mod's blocks) and chunks saved by another Minecraft version that this server has not upgraded yet. The first error in the warning names the block or key that failed.

What LSS does: it does not send or store terrain for those chunks, and it does not load them. Players see no distant terrain there and keep whatever their client already had. LSS leaves the chunks alone on purpose: when Minecraft loads a chunk with unknown blocks it replaces them with air, and it may save the chunk that way, which would delete a temporarily removed mod's blocks from chunks nobody visited.

What to do:

- **Put the missing mod back.** After the restart the chunks read normally and their terrain is served.
- **Or let the world upgrade to this Minecraft version.** Minecraft upgrades a chunk when it loads it. LSS serves a chunk while the game has it loaded, and reads it from disk again once the game has saved it in the current format. Players visiting the area do this chunk by chunk; a world upgrade such as `--forceUpgrade` does it for the whole world. Back the world up first: an upgrade while a mod is missing removes that mod's blocks for good.

Notes:

- A chunk that has some unknown blocks beside known ones is still served, with air where the unknown blocks were, as Minecraft itself shows it. It is not counted.
- The counter counts reads, not chunks: a chunk is read again for each client session that asks for it. It is also included in `not_found=`.
- LSS 0.20 and older sent and stored such chunks as empty terrain. This version stops doing that but does not repair what is already there. If empty terrain remains after the cause is fixed, an operator can drop the stored rows with `/lsslod store invalidate all` (the store refills as terrain is served again), and each client clears its own copy with `/lss clearcache`.

## Private SQLite store runtime

`/lsslod store status` reports `driver=private/3.49.1.0` when the disk store is
active. SQLite is bundled privately; LSS does not require a separate SQLite mod.
Other mods can still conflict with each other independently of LSS.

The driver archive and native libraries are extracted under
`<working directory>/.lss/sqlite-runtime/`, shared across worlds for that install.
This cache is outside the LOD-store size cap. Driver archives are named by version
and SHA-256 and are never overwritten while running; Windows may keep them locked
until JVM exit. Reopening a world reuses the same engine. Clean obsolete cache
files only with the game/server JVM stopped. Do not remove `.lss-store.lock` while
a process may still own that store.

If initialization fails, terrain serving continues without disk-store acceleration.
The warning includes the underlying failure; preserve the database and inspect
permissions, available space, extraction paths and filesystem WAL support. A
file-backed WAL probe runs before the real database is opened. I/O, busy/locked,
permission and native-load failures do not authorize rebuilding an existing store.
Only confirmed SQLite corruption or the existing incompatible-store metadata
policy can recreate it.

An uncertain native close retains exclusive ownership until JVM exit, including
if the plugin is disabled. Restart the JVM before reopening that store; this rare
failure deliberately retains its loader to protect data. A normally completed
shutdown releases ownership immediately.

Existing `org.sqlite.tmpdir`, `org.sqlite.lib.path` and `org.sqlite.lib.name`
overrides are respected. A foreign native version is warned about; overrides
must match the bundled JDBC version. Java 25 may emit a restricted native-access
warning; `--enable-native-access=ALL-UNNAMED` grants the access used by the private
loader. Linux and Windows native execution are validated; bundled macOS/ARM
resources are checked for identity but do not imply live coverage on those systems.

## Sponge server plugin

The Sponge plugin is experimental. At startup Sponge prints a notice that the plugin "uses Mixins"; that notice is expected and is not an LSS failure. Check these first:

| Observation | Check next |
| --- | --- |
| "supports SpongeVanilla only" error, no service | The server runs SpongeNeo or SpongeForge. On SpongeNeo, use the LSS NeoForge jar instead, on lines that ship one (see the catalog). SpongeForge is not supported. |
| The plugin fails to load or a mixin error names `lss.sponge.mixins.json` | The SpongeVanilla build must match the plugin's Minecraft line exactly; each line's jar is pinned to one Minecraft version. Compare `/lsslod diag`'s `sponge-` loader token with the jar's line. |
| Region summaries, backfill or the header rung report unknown regions for one world | That world was loaded by a plugin after server start. LSS resolves region folders when the server starts, so it serves such worlds without region-header acceleration (a v1 limitation; serving still works). |
| A recommended SpongeVanilla build behaves differently from the RC LSS tested | The pinned build is in the line's `gradle.properties` (`spongevanilla_version`). Report the exact Sponge build with `/lsslod diagnostics export`. |
