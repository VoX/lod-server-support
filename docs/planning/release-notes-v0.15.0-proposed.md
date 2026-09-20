### New Features

- **LOD status screen** — `/lss status` shows connection state, transfer activity and available integrations, with a reception toggle. It works without Sodium and is also accessible from supported Sodium settings screens.
- **Local diagnostic exports** — Client `/lss diagnostics export` and server `/lsslod diagnostics export` save sanitized troubleshooting reports locally. Nothing uploads automatically.
- **Previewable presets with undo** — Client presets include `map-only` and an explicit Xaero-writing variant; server presets include `pregenerated-world` and `conservative`. Preview changes before applying them, and undo the last application when its settings have not been changed independently.
- **Far-player visibility and privacy controls** — Adds readable, sneak-hidden name tags and an optional full-bright mode. Fabric servers honor the far-player hide permission and Melius Vanish; Paper also hides players disguised with LibsDisguises.

### Bug Fixes

- **Fewer redundant chunk downloads after restart** — Recognizes unchanged loaded chunks instead of broadcasting ordinary metadata saves as terrain changes.
- **More reliable Xaero map updates** — Retains deferred work for regions that are not ready, retries discarded work through the normal delivery lifecycle, and refreshes dependent shading within a map region after tile updates.
- **Cleaner reconnects and reception toggles** — Retires obsolete requests and callbacks when disconnecting, changing worlds or turning LOD reception off, while preserving valid map redraws already owed to the current world.
- **Safer terrain and cache updates** — Corrects stale loaded-data races, retracts uncertain cache validation, verifies legacy stored data before migration, and preserves incomplete invalidation after interruption.
- **Improved far-player rendering** — Corrects dark lighting, restores nearby skin overlay layers, reduces armor/item flicker, and improves gliding, swimming and stopped-walk poses. Stationary name changes and runtime server-side disabling now update existing far-player displays.
- **More reliable settings changes** — Applies related settings consistently, reports changes that could not be saved, and keeps restart-only changes staged until restart. Legacy Voxy cache-reset compatibility is improved.
- **Folia freshness corrections (experimental)** — Preserves newer loaded-chunk results when an earlier disk or stored-data response completes. Folia support remains experimental.

### Configuration

- **Conservative server preset is opt-in** — Selects a 32-chunk LOD radius, global generation concurrency 4 and per-player concurrency 1. It does not change the normal defaults or guarantee a performance improvement for every server.
- **Generation preset takes effect after restart** — `pregenerated-world` stages generation disabling for the next restart; diagnostics distinguish configured and running values.
- **Far-player animation range** — `farPlayersMaxAnimationDistanceBlocks` now defaults to 512 blocks instead of 256. The new `farPlayersFullBright` option defaults to off.

### Compatibility and Known Limitations

- **Platforms** — Fabric and Paper/Purpur on Minecraft 1.21.11, with experimental Folia support. NeoForge artifacts are not published for this line.
- **Wire compatibility** — This release preserves the existing LSS/VSS wire protocol and configuration/cache adoption behavior. VSS uses the equivalent `/vss` and `/vsslod` commands.
- **Xaero map limitations** — Brief gaps can remain near the player at first spawn, and native region-boundary slope approximation can produce shading seams. This release does not include the proposed acquisition or boundary-shading workarounds.
- **Shader limitations** — Some shader/Voxy combinations can draw far players over LOD terrain when LOD depth is unavailable to the player renderer. Players beyond the game’s far plane are not drawn.
