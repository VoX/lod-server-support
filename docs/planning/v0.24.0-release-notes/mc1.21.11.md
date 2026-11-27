### New Features

- **Fill Map** — A map mod (Xaero's World Map or JourneyMap) switched on after distant terrain was downloaded used to stay empty. The mod now remembers what your map is missing and tells you in chat. Run `/lss fillmap` or press **Fill Map** in the mod's Sodium options: the server sends that terrain once more for the map, and nothing is cleared. `/lss fillmap stop` ends a fill early. For terrain downloaded with an older version, use it once only if your map has gaps there.

### Changed

- **Generation stops at the world border** — The server no longer generates distant terrain outside the vanilla world border (`/worldborder`); players still see terrain that already exists there. Server owners who want it generated set `generation.outside_world_border: true`.
- **Generation waits when the disk is nearly full** — With less than 2 GiB free on the world's disk the server stops generating distant terrain and stops adding to its LOD store, and goes on once 3 GiB are free. The log says so. Free some space, or cap the store with `storage.lod_store.max_size_mib`; `-Dlss.lowDiskHoldMiB=0` turns the hold off.
- **Lighter on the server's main thread** — On Fabric servers, noticing which chunks changed, at every chunk load and save, takes much less time than it did.
- **Terrain is not compressed twice** — On Fabric servers, terrain that is already compressed is no longer compressed again by the game on its way out, which takes most of the mod's work off the server's network threads. Players download at the same speed (also tested through Velocity). If something on your server has trouble with it, `-Dlss.skipDoubleCompression=false` turns it off.

### Bug Fixes

- **Black strips at the edge of the render distance** — With Voxy and a light mod such as ScalableLux, chunks at the edge of the render distance could be given to Voxy before their sky light was ready, and stayed black. The mod now waits for the light. If you turned `lod.voxy_border_fill` off because of this, you can turn it back on.

### Support

- **Platforms** — Fabric and Paper/Purpur/Folia (experimental) for Minecraft 1.21.11.
