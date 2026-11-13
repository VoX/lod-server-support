### Changed

- **Far players at full range** — "Extend Far Player Draw Range" is now on by default, so with an LOD mod such as Voxy far players are drawn as far as the server sends them. Turn it off on the mod's settings page if you prefer the old behaviour.

### New Features

- **Says why nothing is loading** — `/lss status` and the status screen now start with one line: working, or not working and why (the server doesn't run the mod, has it switched off, runs a different version, or nothing you have installed can show the terrain).
- **Chat notice** — The first few times you join a server where distant terrain can't work, one grey line in chat says why.
- **Permission notice** — Servers that give distant terrain only to some players now tell the others, once.
- **Server diagnostics** — `/lsslod diag` now lists errors the mod recovered from and how many sessions used each protocol version.

### Support

- **Beta** — A test build for Minecraft 26.3 and 1.21.1. The full 0.22.0 release for all supported versions follows after a few days of testing.
- **Platforms** — Fabric, Paper/Purpur and NeoForge for Minecraft 1.21.1.
- **Sponge** — From this version the Sponge build is made for the newest Minecraft version only; 0.21.0 stays available for Sponge on 1.21.1.
