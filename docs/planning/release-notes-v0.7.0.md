### Breaking Changes

- **Protocol update — client and server must update together** — v0.7.0 changes the LOD networking protocol (now version 18). A v0.6.x client on a v0.7.0 server (or the reverse) gets no LOD session at all; both sides simply behave as if the other has no LSS installed. Update the mod on the client and the server/plugin together.

### New Features

- **Want-set request model** — The client now declares its complete set of wanted chunks once per second and the server replaces its queue wholesale. Lost or dropped requests heal automatically on the next declaration — no more per-request rate limiting, bounced requests, or stalls waiting on timeouts.
- **LOD disk reads yield to gameplay** — New `useBackgroundReadPriority` option (default `true`): LOD region-file reads run below vanilla's own chunk loads (Fabric: IOWorker background priority; Paper/Folia: Moonrise low priority), so players loading chunks never wait behind LOD streaming. Set `false` to restore the old foreground reads.
- **Server-owned generation** — The server now decides when to generate missing terrain: any chunk not found on disk is generated automatically (when generation is enabled), with concurrency managed entirely server-side. Clients no longer classify requests, and transient generation pressure never permanently blanks a chunk — it simply retries.
- **Near-first generation ordering** — New terrain generates closest-to-player first, even on servers whose chunk system completes work out of order (e.g. C2ME). A frontier gate keeps generation within a tight ring band around each player. On Paper and Folia, LOD generation additionally runs at low priority so player-driven generation always wins (Folia support remains experimental).
- **C2ME read protection** — On Fabric servers running chunk-IO overhaul mods (C2ME), LSS detects that its background-priority disk reads are unavailable and switches to an adaptive read throttle, so LOD reads still yield to gameplay instead of competing with it.
- **Client diagnostics** — `/lss trace` records a per-event JSONL log (scans, movement, received columns with their serve source) for diagnosing LOD behavior; `/lsslod diag` now shows generation ordering counters (`order_gated`, `inversions`) and the read-throttle state.

### Bug Fixes

- **Flying no longer pauses LOD loading** — Crossing chunk borders faster than once per second used to silently stop all LOD requests until the player stood still. The request scan now free-runs during movement, so terrain streams in along your flight path.
- **Leaving the server no longer stalls C2ME servers** — Disconnecting used to release all generation tickets at once, which could freeze servers running C2ME for up to a minute. Releases are now spread across ticks.
- **Faster LOD on join** — Players joining away from the world origin no longer lose their first request scan (~1 second faster LOD start).
- **Client cache hygiene** — A repeatedly rejected clearing column can no longer strand ghost terrain permanently, and stale cache entries from pre-v0.7.0 clients are purged automatically.

### Configuration

- **`syncOnLoadConcurrencyLimitPerPlayer` retired** — The per-player disk-read limit is now a fixed constant (200). The old key is ignored and removed from the config file on the next save. On configurations with more than 6 disk reader threads this constant is now the binding per-player limit.
- **Generation concurrency caps are server-internal** — `generationConcurrencyLimitGlobal` / `generationConcurrencyLimitPerPlayer` still exist but no longer affect client behavior; the client always declares a fixed want-set.

### Performance

- **Less redundant work per tick** — Loaded-chunk probing skips columns already in the send pipeline, the probe budget is shared fairly across players (with a global per-tick serialization ceiling protecting the server tick under many concurrent players), and the retired client-side vanilla-load throttle no longer suppresses LOD requests — server-side priority handles that protection.
