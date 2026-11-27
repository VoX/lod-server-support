### New Features

- **Fill Map** — A map mod (Xaero's World Map or JourneyMap) that was switched on after distant terrain had already been downloaded used to stay empty. The mod now remembers what your map is missing and tells you in chat. Run `/lss fillmap`, or press **Fill Map** in the mod's options (Sodium 0.8 or newer), and the server sends that terrain once more for the map. Nothing is cleared, and Voxy keeps what it has. For terrain downloaded with an older version the mod cannot know what the map has: use Fill Map once if your map has gaps there, and not otherwise (it would download all of it again). `/lss fillmap stop` ends a fill early (in the dimension you are in).
- **Generation stops at the world border** — The server no longer generates distant terrain outside the vanilla world border (`/worldborder`); players still see terrain that already exists there. Server owners who want it generated set `generation.outside_world_border: true`.
- **Generation waits when the disk is nearly full** — With less than 2 GiB free on the world's disk the server stops generating distant terrain and stops adding to its LOD store, and goes on by itself once 3 GiB are free. The log says so; `-Dlss.lowDiskHoldMiB=0` turns this off.

### Changed

- **Lighter on the server's main thread** — On Fabric and NeoForge servers, noticing which chunks changed, at every chunk load and save, takes about a third of the time it did.
- **Terrain is not compressed twice** — On Fabric and NeoForge servers, terrain that is already compressed is no longer compressed again by the game on its way out, which takes most of the mod's work off the server's network threads. Players download at the same speed; it works through Velocity. If something on your server has trouble with it, `-Dlss.skipDoubleCompression=false` turns it off.

### Bug Fixes

- **Black strips at the edge of the render distance** — With Voxy and a light mod such as ScalableLux, chunks at the edge of the render distance could be given to Voxy before their sky light was ready, and stayed black. The mod now waits for the light. If you turned `lod.voxy_border_fill` off because of this, you can turn it back on.

### Support

- **Platforms** — Fabric, Paper/Purpur and NeoForge for Minecraft 1.21.1.
