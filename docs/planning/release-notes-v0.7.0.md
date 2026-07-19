### Breaking Changes

- **Protocol update — update the server first** — v0.7.0 changes the LOD networking protocol (now version 18). A v0.7.0 server still serves v0.6.x clients through a built-in compatibility layer (see below), but a v0.7.0 client on a v0.6.x server gets no LOD session at all — it behaves as if the server has no LSS installed. Update the server/plugin first (or both together); the compatibility layer covers v0.6.x clients only, older clients still need updating.
- **No Folia declaration on this version** — The Paper plugin no longer declares Folia support: no Folia build exists for Minecraft 26.2, so the declaration would only auto-load this Folia-unvalidated version the day Folia ships. Folia support (experimental) continues on the older support lines and returns here once Folia publishes a 26.2 build and validation passes.

### New Features

- **Faster, more reliable LOD loading** — LOD terrain streams in with fewer stalls and no more permanently missing patches: the client now declares everything it still wants once per second, so any request the server had to drop under load is retried automatically within a second instead of timing out, backing off, or silently never arriving.
- **Server performance is protected while LODs stream** — Players loading normal chunks no longer compete with LOD streaming: LOD disk reads run below vanilla's own chunk loads on all platforms (on Fabric servers with chunk-IO overhaul mods like C2ME an adaptive throttle takes over instead — see below), and LOD terrain generation runs at low priority on Paper so player-driven world loading always wins. LODs fill in using whatever capacity is spare — set `useBackgroundReadPriority: false` to restore the old behavior.
- **Distant terrain fills itself in** — Chunks nobody has ever visited now appear in your LODs automatically: the server generates anything it can't find on disk (when generation is enabled), and temporary overload never leaves permanent holes — affected chunks simply arrive a little later.
- **Terrain generation stays close to you** — LOD-driven terrain generation is now kept within a couple of rings of the nearest missing area, even on servers whose chunk systems finish work out of order (e.g. C2ME) — the request pipeline can no longer race far ahead while nearby terrain is still generating. (Some visual out-of-order fill remains normal, especially on C2ME: large rings fill as arcs, and terrain the server generated on its own is served the moment it appears.)
- **Old clients keep working** — Players still on v0.6.x keep receiving LODs from a v0.7.0 server: the server translates their old protocol on the fly (at the old client's slower pace). `/lsslod diag` shows a `V16Compat` line once a legacy client has connected; updating the client gets the full v0.7.0 experience.
- **LODs behave on C2ME servers** — On Fabric servers running chunk-IO overhaul mods (C2ME), LSS detects that its background-priority reads are unavailable and switches to an adaptive throttle, so LOD streaming still yields to gameplay instead of competing with it.
- **New diagnostics for admins** — `/lss trace` records a per-event log of the client's LOD activity (scans, movement, received columns with their serve source) for diagnosing issues; `/lsslod diag` now shows generation-ordering counters and the read-throttle state.
- **Also available as "Voxy Server Side"** — Starting with this release, the mod is additionally published on Modrinth under the [Voxy Server Side](https://modrinth.com/plugin/voxy-server-side) name. Both listings ship the exact same mod from the same build (identical internals, config, and networking) — every client/server combination across the two listings is fully compatible, and the v0.6.x compatibility layer serves legacy clients from either listing the same way. Install either one, but not both; swapping between them keeps your config.

### Bug Fixes

- **Flying no longer pauses LOD loading** — Crossing chunk borders faster than once per second used to silently stop all LOD requests until you stood still. Terrain now streams in along your flight path.
- **Heavy building no longer stalls LOD loading** — A steady stream of world edits could previously delay LOD requests indefinitely on busy servers; change notifications no longer affect the request cadence.
- **Disconnecting no longer freezes C2ME servers** — Leaving the server used to release all LOD generation tickets at once, which could freeze servers running C2ME for up to a minute. Releases are now spread across ticks.
- **Faster LOD start on join** — Players joining away from the world origin no longer lose their first request scan (~1 second faster LOD start).
- **No more stuck ghost terrain** — A repeatedly rejected clearing column can no longer strand outdated terrain in your LODs permanently, and stale cache entries from pre-v0.7.0 clients are purged automatically.

### Configuration

- **`enableV16Compat`** — New server option (default `true`) controlling the legacy-client compatibility layer. Set `false` to turn it off; v0.6.x clients then get no LOD session, like any other version mismatch.
- **`useBackgroundReadPriority`** — New server option (default `true`) putting LOD disk reads below vanilla's own chunk loads (Fabric: IOWorker background priority; Paper: Moonrise low priority). This is a behavior change on every server — set `false` to restore the old foreground reads.
- **`syncOnLoadConcurrencyLimitPerPlayer` retired** — The per-player disk-read limit is now a fixed constant (200). The old key is ignored and removed from the config file on the next save. On configurations with more than 6 disk reader threads this constant is now the binding per-player limit.
- **Generation concurrency caps are server-internal** — `generationConcurrencyLimitGlobal` / `generationConcurrencyLimitPerPlayer` still exist and still limit worldgen load, but no longer affect an updated client's behavior. (Legacy v0.6.x clients still receive the per-player cap through the compatibility layer, which paces them as before.)

### Performance

- **Lower server overhead with many players** — Loaded-chunk serialization now skips columns already queued to send, is shared fairly across players each tick, and is capped globally so many players backfilling at once cannot stretch the server tick.
