### New Features

- **Settings mistakes are easy to find** — A settings file with a mistake still switches the mod off, but the mod now lists every mistake with its line number and the name you probably meant. Server admins are told in chat and by `/lsslod`; players by one chat line and `/lss status`.
- **Says which "switched off"** — When a server on this version has distant terrain off, `/lss status` and the chat notice say whether it is off on the whole server, in this world, or needs a permission you don't have. Server owners can keep the permission reason to themselves with `service.show_permission_refusal: false`.
- **Says when Voxy is not drawing** — `/lss status` no longer says "Working" when Voxy has switched itself off because it cannot run on your computer's graphics, or is switched off in Voxy's own settings.
- **`/lss` on Voxy Server Side** — The Voxy Server Side client now also answers to `/lss`, so instructions written for either build work.

### Changed

- **"Share My Position: off" always reaches the server** — Turning it off now hides you from far-player views even when your client receives no terrain from that server (Receive Server LODs off, or nothing installed that shows terrain). Server owners see these players as `opted_out=` in `/lsslod diag`.
- **`yes`/`no` and `on`/`off` in settings** — Switch settings accept these spellings as well as `true`/`false`.
- **Shorter option name** — "Extend Far Player Draw Range" is now "Extend Draw Range"; the old name was cut off on the settings screen.

### Bug Fixes

- **Status details** — The details no longer show `ingest=-1` when nothing reports a backlog.

### Support

- **Beta** — A test build for Minecraft 26.3 and 1.21.1. The full 0.23.0 release for all supported versions follows after a few days of testing.
- **Platforms** — Fabric, Paper/Purpur and NeoForge for Minecraft 1.21.1.
