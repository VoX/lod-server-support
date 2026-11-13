### Changed

- **Far players past the draw limit** — "Extend Far Player Draw Range" is now on by default: with an LOD mod such as Voxy, far players beyond the game's normal draw limit are drawn. It does nothing while a shader pack is on, and you can turn it off on the mod's settings page. On Minecraft 26.x the game's fog still hides players past about 900 blocks unless your LOD mod's fog is off.
- **Sharing setting message** — When "Share My Position" is saved but no server has received it, the message now says so plainly, and that the server may still show your position until it does.

### New Features

- **Says why nothing is loading** — `/lss status` and the status screen now start with one line: working, or not working and why (the server doesn't have the mod, has it switched off, hasn't answered, or nothing you have installed can show the terrain).
- **Chat notice** — The first few times you join a server where distant terrain can't work, one grey line in chat says why. It never repeats more than three times and there is nothing to configure.
- **Server diagnostics** — `/lsslod diag` now lists errors the mod recovered from and how many sessions used each protocol version.

### Support

- **Beta** — A test build for Minecraft 26.3 and 1.21.1. The full 0.22.0 release for all supported versions follows after a few days of testing.
- **Platforms** — Fabric, Paper/Purpur/Folia (experimental), NeoForge and Sponge (experimental) for Minecraft 26.3.
