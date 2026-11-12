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
| Dark LOD terrain on a world upgraded with `--forceUpgrade`, "Optimize World" or `--eraseCache` | Those tools strip the stored light from chunks last saved before Minecraft 1.21.9 (and `--eraseCache` from every chunk); the game relights a chunk only when it loads it, and LSS serves what is on disk. Pre-load the area once (for example with Chunky) so the game relights and re-saves it; LSS then re-sends the relit terrain. Avoid `--eraseCache` on a served world. |
| Distant terrain looks wrong or has gaps | Run `/lss clearcache` or press Re-download Terrain while connected: it clears this server's download records and the server sends the terrain again (terrain Voxy already saved stays; `/lss reset` deletes that too). "Not receiving distant terrain from this server right now. Nothing was cleared." means there is no active download to restart: check the rows above (Reception OFF, negotiation, server disabled) first. In singleplayer or at the title screen the same action clears the records of every server. |
| Missing terrain with generation disabled | Verify that the requested terrain exists. Disabling generation does not scan or certify the world's coverage. |

Use `/lss diagnostics export` for a client report or `/lsslod diagnostics export` as an operator. The command displays the local JSON path; the adjacent text file is its summary. Reports exclude addresses, player identities, world keys, seeds, aliases, personal paths and raw exception messages. No upload occurs. The full local `/lss diag` and ordinary logs can contain identifying context, so they are not equivalent to the allowlisted export. See [export behavior](status-and-presets.md).

For a setting change, inspect its [apply timing](../reference/settings.md). Sodium Apply saves and automatically reloads client settings; wait for completion and reconnect if requested. Direct YAML edits require `/lss reload` for client settings or `/lsslod reload` for server settings. If saving fails, recover the retained draft and repair the target file's permissions or disk space before retrying. Server reports distinguish the owner's generation admission gate from the accepted configured value; generation changes apply through reload. Restart is required only for paths explicitly reported as pending. The schema-1 JSON field `generationConfiguredForRestart` retains its historical name for compatibility and now means the accepted configured generation flag. Do not use cache resets, store invalidation, privacy changes or anti-xray changes as an automatic response to a slow download. See [performance diagnosis](performance.md).

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
