### New Features

- **Per-world LOD distance limits** — Brings the Minecraft 26.2 feature to this support line. Set `lodDistanceChunksByWorld` to override the default radius for individual worlds, or use `/lsslod set lodDistanceChunks <world> <distance>` while the server is running. Use `<world> default` to remove an override.

### Configuration

- **World keys** — Fabric and NeoForge use dimension IDs such as `minecraft:the_nether`. Paper tries the Bukkit world name first, then the dimension ID. An empty override map preserves the existing global distance.
- **Live updates** — Current-protocol clients receive the appropriate distance when settings change or they enter a world with a different limit. Legacy clients pick up the advertised limit when they reconnect. Global presets preserve independent world overrides.

### Support and Compatibility

- **Platforms** — Fabric and Paper/Purpur on Minecraft 1.21.10. NeoForge remains a maintained build and is not published; Folia is unavailable.
- **Protocol** — Preserves the existing wire protocol and LSS/VSS configuration/cache adoption. This release publishes LSS only.
- **Known limitations** — Existing Xaero first-spawn gaps, region-boundary shading seams and shader/LOD-depth limitations remain.
