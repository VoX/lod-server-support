### Changed

- **Far players past the draw limit** — "Extend Far Player Draw Range" is now on by default: with an LOD mod such as Voxy, far players beyond the game's normal draw limit are drawn. It does nothing while a shader pack is on, and you can turn it off on the mod's settings page. On Minecraft 26.x the game's fog still hides players past about 900 blocks unless your LOD mod's fog is off.
- **Sharing message after saving settings** — While no server has received your "Share My Position" choice, the message after a save now says so plainly, and that a server uses its own default until it does.

### New Features

- **Says why nothing is loading** — `/lss status` and the status screen now start with one line: working, or not working and why (the server doesn't have the mod, has it switched off, hasn't answered, or nothing you have installed can show the terrain).
- **Chat notice** — The first few times you join a server where distant terrain can't work, one grey line in chat says why. Each kind of line is shown at most three times ever, and there is nothing to configure.
- **Server diagnostics** — `/lsslod diag` now lists errors the mod recovered from and how many sessions used each protocol version.

### Bug Fixes

- **Fabric server crash** — A Fabric server could crash when a player was kicked or timed out while distant terrain was still being generated for them. This fix was added after the beta and did not go through its three-day test.

### Support

- **Platforms** — Fabric, Paper/Purpur/Folia (experimental), NeoForge and Sponge (experimental) for Minecraft 26.3.
- **Minecraft 26.1 and 1.21.10** — These two now get crash and security fixes only; 0.21.1 brings them the Fabric server crash fix. 26.2, 1.21.11 and 1.21.1 are released alongside this one.
