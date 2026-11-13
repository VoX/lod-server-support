### Changed

- **Far-player hide permission** — `lss.farplayers.hidden` no longer hides a player by itself, so admins with every permission (`*`) show up as far players again. To hide every holder as before, set `far_players.hide_permission_holders: true` on the server.
- **`/lss clearcache`** — While connected to a server it now only clears that server's download records, and says so when nothing is being received.

### New Features

- **Required position sharing** — New server mode `far_players.mode: required`: every player is shown as a far player, only holders of `lss.farplayers.hidden` can opt out, and players whose choice is overridden are told in chat.

### Bug Fixes

- **Black distant terrain** — Terrain from chunks saved without lighting (for example after a world upgrade) is no longer black. If terrain you already downloaded is black, run `/lss clearcache` once.
- **Distant Horizons servers** — Tree crowns no longer show black faces; that terrain downloads once again.
- **Unreadable chunks** — Servers no longer send or store blank terrain for chunks they can't read.

### Support

- **Platforms** — Fabric, Paper/Purpur and Sponge (experimental) for Minecraft 1.21.10.
- **Downgrading** — A server settings file that uses the new far-player settings doesn't load on 0.20 or older; remove those lines first.
