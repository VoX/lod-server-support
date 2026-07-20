# Short player-focused variant — ONLY for manually fixing the Modrinth changelog
# (MODRINTH_TOKEN is CI-only; if release.yml publishes a wrong changelog, paste this
# by hand on project lKiXKLvv — and since v0.7.0 also on 84zcagOb/voxy-server-side,
# which receives the same changelog. The full notes are release-notes-v0.7.0.md.)

- **Protocol update — update the server first.** v0.7.0 servers keep serving v0.6.x clients through a built-in compatibility layer, but a v0.7.0 client needs a v0.7.0 server to receive LODs.
- **Smoother LOD loading** — terrain streams in closest-to-player first and keeps loading while you fly; fast movement and heavy building no longer pause LOD requests, and dropped requests retry automatically instead of leaving permanent gaps.
- **Terrain fills in order, even at speed** — distant terrain now generates strictly near-to-far (no more far chunks or whole lines popping in before the area around you), including during and after fast flight, with ~8× fewer disk reads and roughly twice as fast full-view fill while terrain generates (`missMemoTtlSeconds`, default 30).
- **Gameplay always wins** — LOD disk reads now run below normal chunk loading on all platforms, and on Paper LOD terrain generation runs at low priority too, so player-driven world loading takes precedence over LOD streaming.
- **No Folia on this version** — the plugin no longer declares Folia support (no Folia build exists for MC 26.2); experimental Folia support continues on the older support lines and returns once Folia ships 26.2.
- **Also published as "Voxy Server Side"** — the same mod, from the same build, is now also on the Voxy Server Side Modrinth page; install either one, not both.
- **Missing terrain generates automatically** — the server generates any chunk it can't find on disk, and temporary overload no longer leaves permanent holes in your LODs.
- **C2ME fixes** — disconnecting no longer freezes C2ME servers for up to a minute, and LOD reads adapt automatically where C2ME replaces vanilla chunk IO.
