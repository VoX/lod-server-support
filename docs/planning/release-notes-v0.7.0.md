### Breaking Changes

- **Protocol update — update the server first** — v0.7.0 changes the LOD networking protocol. A v0.7.0 server still serves clients back to v0.4.x through a built-in compatibility layer. Update the server/plugin first (or both together).

### New Features

- **Faster, more reliable LOD loading** — LOD terrain streams in with fewer stalls and no more permanently missing patches: the client now declares everything it still wants once per second, so any request the server had to drop under load is retried automatically within a second instead of timing out, backing off, or silently never arriving.
- **Server performance is protected while LODs stream** — Players loading normal chunks no longer compete with LOD streaming: LOD disk reads run below vanilla's own chunk loads on all platforms (on Fabric servers with chunk-IO overhaul mods like C2ME an adaptive throttle takes over instead — see below), and LOD terrain generation runs at low priority on Paper so player-driven world loading always wins. LODs fill in using whatever capacity is spare — set `useBackgroundReadPriority: false` to restore the old behavior.
- **Terrain generation fills near-to-far, even while flying** — LOD-driven terrain generation now follows strict ordering rules: it never overtakes closer terrain that is still loading, stays within a couple of rings of the nearest missing area, and no longer chases ahead of fast-moving players.
- **Old clients keep working** — Players still on v0.4.x or above keep receiving LODs from a v0.7.0 server: the server translates their old protocol on the fly (at the old client's slower pace). `/lsslod diag` shows a `V16Compat` line once a legacy client has connected; updating the client gets the full v0.7.0 experience.
- **LODs behave on C2ME servers** — On Fabric servers running chunk-IO overhaul mods (C2ME), LSS detects that its background-priority reads are unavailable and switches to an adaptive throttle, so LOD streaming still yields to gameplay instead of competing with it.
- **New diagnostics for admins** — `/lss trace` records a per-event log of the client's LOD activity (scans, movement, received columns with their serve source) for diagnosing issues; `/lsslod diag` now shows generation-ordering counters and the read-throttle state.
- **Also available as "Voxy Server Side"** — Starting with this release, the mod is additionally published on Modrinth under the [Voxy Server Side](https://modrinth.com/plugin/voxy-server-side) name. Both listings ship the exact same mod from the same build (identical internals, config, and networking) Install either one, **but not both**; swapping between them keeps your config.

### Bug Fixes

- **Flying no longer pauses LOD loading** — Crossing chunk borders faster than once per second used to silently stop all LOD requests until you stood still. Terrain now streams in along your flight path.
- **Heavy building no longer stalls LOD loading** — A steady stream of world edits could previously delay LOD requests indefinitely on busy servers; change notifications no longer affect the request cadence.
- **Quieter logs under heavy disk load** — A slow disk no longer floods the server console with per-chunk stack traces when LOD reads time out; timeouts are summarized in one throttled warning line per minute (they remain harmless — affected chunks are retried automatically).

### Configuration

- **`enableV16Compat`** — New server option (default `true`) controlling the legacy-client compatibility layer. Set `false` to turn it off; v0.6.x clients then get no LOD session, like any other version mismatch.
- **`useBackgroundReadPriority`** — New server option (default `true`) putting LOD disk reads below vanilla's own chunk loads (Fabric: IOWorker background priority; Paper: Moonrise low priority). This is a behavior change on every server — set `false` to restore the old foreground reads.
- **`syncOnLoadConcurrencyLimitPerPlayer` retired** — The per-player disk-read limit is now a fixed constant (200). The old key is ignored and removed from the config file on the next save. On configurations with more than 6 disk reader threads this constant is now the binding per-player limit.
- **Generation concurrency caps are server-internal** — `generationConcurrencyLimitGlobal` / `generationConcurrencyLimitPerPlayer` still exist and still limit worldgen load, but no longer affect an updated client's behavior. (Legacy v0.6.x clients still receive the per-player cap through the compatibility layer, which paces them as before.)
- **`missMemoTtlSeconds`** — New server option (default `30`, clamped 0-60) controlling how long the server remembers that a chunk is not generated yet, so waiting requests skip redundant disk lookups. `0` turns the memo off entirely and restores per-request disk reads.

### Performance

- **Lower server overhead with many players** — Loaded-chunk serialization now skips columns already queued to send, is shared fairly across players each tick, and is capped globally so many players backfilling at once cannot stretch the server tick.

**Full Changelog**: https://github.com/VoX/lod-server-support/compare/v0.6.2...v0.7.0
