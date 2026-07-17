# Short player-focused variant — ONLY for manually fixing the Modrinth changelog
# (MODRINTH_TOKEN is CI-only; if release.yml publishes a wrong changelog, paste this
# by hand on project lKiXKLvv. The full notes are release-notes-v0.7.0.md.)

- **Protocol update — update the server first.** v0.7.0 servers keep serving v0.6.x clients through a built-in compatibility layer, but a v0.7.0 client needs a v0.7.0 server to receive LODs.
- **Smoother LOD loading** — terrain streams in closest-to-player first and keeps loading while you fly; fast movement and heavy building no longer pause LOD requests, and dropped requests retry automatically instead of leaving permanent gaps.
- **Gameplay always wins** — LOD disk reads and terrain generation now run at low priority (Fabric, Paper, and experimental Folia), so normal chunk loading never waits behind LOD streaming.
- **Missing terrain generates automatically** — the server generates any chunk it can't find on disk, and temporary overload no longer leaves permanent holes in your LODs.
- **C2ME fixes** — disconnecting no longer freezes C2ME servers for up to a minute, and LOD reads adapt automatically where C2ME replaces vanilla chunk IO.
