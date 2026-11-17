### New Features

- **Settings mistakes are easy to find** — A settings file with a mistake still switches the mod off, but the mod now lists every mistake with its line number and the name you probably meant. Server admins are told in chat and by `/lsslod`; players by one chat line and `/lss status`.
- **Says which "switched off"** — When a server on this version has distant terrain off, `/lss status` and the chat notice say whether it is off on the whole server, in this world, or needs a permission you don't have. Server owners can keep the permission reason to themselves with `service.show_permission_refusal: false`; players then see the general "switched off here".
- **Says when Voxy is not drawing** — `/lss status` no longer says "Working" when Voxy has switched itself off because it cannot run on your computer's graphics, or is switched off in Voxy's own settings.
- **`/lss` on Voxy Server Side** — The Voxy Server Side client now also answers to `/lss`, so instructions written for either build work.

### Changed

- **"Share My Position: off" always reaches the server** — It is now sent even when your client receives no terrain from that server (Receive Server LODs off, or nothing installed that shows terrain); before, such players stayed visible. Servers that require sharing still show everyone. `/lsslod diag` counts players who have it off as `opted_out=`.
- **`yes`/`no` and `on`/`off` in settings** — Switch settings accept these spellings as well as `true`/`false`. Older versions do not, so change them back before returning to one.
- **Shorter option name** — "Extend Far Player Draw Range" is now "Extend Draw Range"; the old name was cut off on the settings screen.

### Bug Fixes

- **Leaving a server on Fabric** — The mod now cleans up on the game's main thread when you leave or are disconnected. Before, on rare occasions, the game could crash instead of showing the disconnect screen, or far players could stay hidden on the next server until you rejoined.
- **Status details** — The details no longer show `ingest=-1` when nothing reports a backlog.

### Support

- **Beta** — A test build for Minecraft 26.3 and 1.21.1. The full 0.23.0 release for all supported versions follows after a few days of testing.
- **Platforms** — Fabric, Paper/Purpur/Folia (experimental), NeoForge and Sponge (experimental) for Minecraft 26.3.
