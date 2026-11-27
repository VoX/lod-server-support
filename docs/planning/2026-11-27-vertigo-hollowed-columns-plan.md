# Vertigo hollowed columns plan (issue #437)

Status: PLANNED (2026-11-27). Owner: maintainer runs. Target: 0.24.1 (beta first on 26.3 and
1.21.1, then 26.2 and 1.21.11).

## 1. Problem and evidence

With [Vertigo](https://github.com/Builderb0y/Vertigo) (MIT, Fabric only, 1.2.7 for 1.20.x to 26.3)
on both the server and the client, flying leaves black holes in the Voxy LOD terrain along the
flight path (#437, screenshot in the issue).

Cause, from the sources (Vertigo `7a64414`, Voxy branch `263` and the 1.21.1 fork, LSS main
`f5107cfb6`):

- Vertigo sends only the sections within the view distance above and below the player
  (`SectionTrackingManager`). Other sections reach the client as air. When the player moves
  vertically it loads and unloads single sections (`ChunkSectionLoadPacket` / `UnloadPacket`).
  It does this only when both sides have Vertigo (`VertigoAPI.isTrackingSections`).
- Voxy ingests every client chunk on Sodium's `onChunkAdded` and `onChunkRemoved`
  (`MixinRenderSectionManager`, `VoxelIngestService.tryAutoIngestChunk`). It also ingests a
  section that turns invisible (`updateSectionInfo` redirect). In every case it stores the air
  that Vertigo sent over the LOD terrain it already had. This happens without LSS too (Voxy
  request MCRcortex/voxy#562; Vertigo closed the same report for DH as not planned, Vertigo#6).
- What Voxy holds for a column after vanilla lets it go is what that chunk held at
  `onChunkRemoved`, which is the last write. So a column is hollow in Voxy exactly when, at the
  moment the chunk unloads, Vertigo had not loaded every one of its sections.
- LSS does not repair it. The column was served before the player arrived and its stamp stays.
  When the chunk leaves vanilla view, the scanner sees a satisfied column. A dirty mark would
  re-ask with the stamp and draw `up_to_date`. Only a forgotten stamp (ts = -1) gets the column
  served again.
- The LSS border fill (`VoxyVanillaCoverage`) also hands loaded edge chunks to Voxy, so it writes
  the same air. But Voxy's own add and remove hooks write that air anyway, so the border fill
  is not what decides whether the hole stays.

Not reproduced live yet: the reproduction is a P10 row (section 7). The plan rests on the code
paths above. The first P7 step is a live repro on the rig, to confirm the hole and that a
ts = -1 re-serve after the unload fills it.

## 2. Approaches considered

1. **Re-serve the columns Vertigo hollowed, once they leave view (chosen).** On a client chunk
   unload, ask Vertigo whether every section of that chunk was loaded. If not, forget the
   column's stamp so LSS's next scan asks with ts = -1 and the server serves it again. The
   cost is one store serve per hollowed chunk the player leaves behind, and no generation
   (the chunk exists, the player was just there). It fixes the band behind the player, which
   is the band the reporter sees. Holes under the player while the chunks are loaded are
   vanilla's own view (Vertigo's design) and Voxy does not draw there.
2. **The border fill skips Vertigo-hidden chunks** (the follow-up (1) in the 2026-11-27 reply).
   Rejected: Voxy writes the same air on add and remove, so skipping LSS's copy changes
   nothing a player sees. It would also make the border fill stop feeding Voxy during any
   flight.
3. **Re-request every column after any chunk unload.** Rejected: a store serve per unloaded
   chunk for every player, Vertigo or not. "Server load first".
4. **Hand Voxy only the loaded sections** (`rawIngest` per section instead of
   `tryAutoIngestChunk`). This covers only LSS's own border fill, which is approach 2's
   problem. Voxy's own hooks would still write the air.
5. **Patch Voxy** (a mixin into its ingest, or a filter on `shouldIngestSection`). Rejected:
   it reaches into another mod's internals, and the fix belongs there (voxy#562). If Voxy
   ships Vertigo support, approach 1 simply stops finding anything hollow to re-serve. A
   section Voxy skipped never turns to air in Voxy, but the check reads Vertigo, not Voxy.
   This is the one interaction to re-check when Voxy ships #562 (section 8).
6. **A server-side fix** (the LSS server notices Vertigo on the server and re-sends). Rejected:
   the server does not know what the client's Voxy wrote, and this would need a protocol
   change.

## 3. Design

### 3.1 Detecting a hollowed column (Fabric client only)

- New `xplat/.../compat/VertigoCompat.java`. It resolves `builderb0y.vertigo.api.VertigoAPI`
  with `Class.forName(name, false, loader)` only when `FabricLoader.isModLoaded("vertigo")`.
  Two public static methods are bound by exact descriptor through `MethodHandles.publicLookup`:
  - `isTrackingSections(Lnet/minecraft/world/entity/player/Player;)Z`
  - `isSectionLoaded(Lnet/minecraft/world/entity/player/Player;III)Z`

  Any resolution failure turns the feature off for the session, with one INFO line. It never
  affects anything else.
- The Fabric client registers a `ClientChunkEvents.CHUNK_UNLOAD` listener in its own phase
  `lss:before_vertigo`, ordered before `Event.DEFAULT_PHASE` (`addPhaseOrdering`). Vertigo
  registers in the default phase and removes its per-chunk state in its own unload listener
  (`SectionTrackingManager.onChunkUnloadedClient`). Running first means Vertigo's state for
  the chunk is still there. Without the ordering, Vertigo running first would make every
  chunk look hollow: only a needless re-serve, never a missed one, but the phase removes it.
- The listener does nothing unless every gate holds:
  - Vertigo resolved;
  - an LSS terrain session is active (`ClientNetGlue.getRequestManager()` non-null and
    acquisition active);
  - Voxy's ingest bridge is live (`VoxyCompat.isInitialized()`). Voxy is the consumer that
    writes the air, and a map-only client has no Voxy holes to repair;
  - `isTrackingSections(player)` is true.

  If all hold, it walks the chunk's section indices (min to max section Y). The chunk is
  hollowed if any `isSectionLoaded(player, x, y, z)` is false. The cost is about 24 calls per
  unload on the render thread, with no allocation.
- Hollowed positions go to `ClientNetGlue.reportHollowed(dimension, x, z)`, which hops to the
  main thread like `reportIngestFailure`, with the same owner and acquisition checks.

### 3.2 Forgetting the stamp without a strike

- New `LodRequestManager.onHollowed(dimension, packed)`.
  - Another dimension (unreachable from a chunk unload in practice): ignore. A dimension
    change retires the session's map anyway.
  - In the same dimension: `tracker.removeByPosition(packed)` is not needed, because the
    scanner never asks inside vanilla view. If a request is in flight, the column it brings
    back is the server's whole column, so leave it alone. Then call
    `columns.onHollowed(packed)` and record it in a new `metrics.recordHollowed()`.
  - Pending cache load: the same treatment as ingest failures (`failuresDuringCacheLoad`
    style list, re-applied over the loaded stamps).
- New `ColumnStateMap.onHollowed(packed)`: the lost-content unstamp of `onIngestFailed`
  without the strike counter and without the park.
  - Old stamp -1 (unknown, or an all-air park): nothing. An all-air column has nothing to
    lose, and an unknown one is asked anyway.
  - Old stamp > 0 or 0 (not generated): `tsPut(-1)`, the retry mark that makes the scan
    re-ask, and `persistentRemovals.add`. The removal must reach the cache file: if the
    session ends before the re-serve, the next session would otherwise draw a false
    `up_to_date` and keep the hole.
  - It never touches `ingestFailures`, `ingestParked` or `clearedResync`, so a column flown
    over many times never parks and never counts as a consumer failure. If a pending clear
    resync exists (`clearedResync` has the position), leave the stamp alone. Voxy now holds
    air, which matches the clear, so there is nothing to repair.
- Scanner: the forgotten position is outside vanilla view (it just unloaded), so the next
  scan picks it up like any other ts = -1 column. Reopen its ring the way
  `consumeStaleCrossing` does, so a confirmed prefix does not hide it.

### 3.3 What players see

- With Vertigo and Voxy on an LSS server, the band behind a flight fills back in a moment after
  each chunk leaves view (one round trip and one scan).
- Nothing changes for anyone without Vertigo on both sides.
- `/lss diag` (client) gains `vertigo_refills=<n>` on the existing request line, shown only
  when non-zero. It needs no translation (diag is untranslated).
- No setting. The behaviour triggers only when Vertigo hides sections, and the cost is
  bounded by the player's own chunk churn (section 4). An operator who wants none of it can
  remove Vertigo. A setting nobody needs to touch is clutter.

## 4. Server load (the "server load first" check)

- Each hollowed chunk the player leaves costs one ts = -1 request. That is a warm store serve
  (stored bytes shipped), or a region read on a cold store. It never generates: the chunk
  exists because the player just had it.
- Rate: at most the vanilla chunk unload rate, which is the rate vanilla itself sends chunks
  (each costs more than a store serve). Example: flying at 40 blocks/s with view distance 12
  unloads about 2.5 rows of 25 chunks per second, about 62 columns/s. On the 2026-11-21 rig
  measurement, the warm serve pipeline costs about 0.11 ms of CPU per column (1,482 col/s at
  16.5 % of a core), so about 0.7 % of a core per flying player, on top of the vanilla chunk
  sends that already happen.
- Bandwidth: one LOD column (about 6 KB on the wire, zstd) per unloaded chunk, against
  vanilla's chunk packet for the same chunk on the way in.
- Edge churn (a player hovering at a chunk boundary): each unload re-serves once. Vanilla
  re-sends the whole chunk on every load, so LSS's share stays below vanilla's.
- **Measured before release** (P10): a scripted flight on the measurement rig with Vertigo on
  both sides. Record re-serves per second and server CPU against the same flight without
  Vertigo. Acceptance: re-serves ≤ chunk unloads, no generation, and server CPU for LSS within
  1 % of a core per flying player.

## 5. Per-line differences

| Line | Applies | Notes |
| --- | --- | --- |
| 26.3 (main) | yes | Vertigo 1.2.7-MC26.3, Voxy `263` |
| 1.21.1 | yes (Fabric) | Vertigo 1.2.7-MC1.21.1; the 1.21.1 Voxy has the same add/remove hooks. NeoForge: Vertigo does not exist there, the listener is Fabric-only |
| 26.2 | yes (stable port) | Vertigo 1.2.7-MC26.2 |
| 1.21.11 | yes (stable port) | Vertigo 1.2.7-MC1.21.11 |
| 26.1, 1.21.10 | no (frozen) | |

Line seams to check at port time: `ChunkPos` accessors (fields on 1.21.11 and older),
`LevelChunk` section-Y helpers, the Fabric `Event.register(Identifier, T)` overload
(`ResourceLocation` on 1.21.x).

## 6. Settings, wire and downgrade

- Settings: none.
- Wire: none. It is a normal ts = -1 request, already in every dialect.
- Persistence: uses the existing `persistentRemovals` path. An older LSS reading the cache file
  sees a missing stamp, which it treats normally.

## 7. Tests

- **Unit (xplat/fabric tests):**
  - `ColumnStateMapHollowedTest`: >0 and 0 stamps go to -1 with a retry mark and a persistent
    removal; -1 and all-air parks are untouched; repeated reports (100 times) never park and
    never move `ingestFailures`/`ingestParked`; a pending clear resync is untouched.
  - `LodRequestManager` test: `onHollowed` during a pending cache load is re-applied over the
    loaded stamps; another dimension is ignored; the next scan emits ts = -1 for the position.
  - `VertigoCompat` decision test, with a fake API: a fully loaded chunk is not hollowed; one
    missing section is; not tracking sections means nothing; failed resolution means nothing,
    with no exception.
- **Binding surface:** `VertigoBindingSurfaceTest` checks both descriptors against the line's
  Vertigo golden jar (`maven.modrinth:vertigo:<version>`, new `vertigo_golden` property per
  line), at the classfile level like the Xaero one.
- **Phase order:** a Fabric unit test that registers a default-phase listener which clears a
  fake Vertigo state, fires `CHUNK_UNLOAD`, and asserts that LSS's listener saw the state
  first.
- **Live repro and check (P10, 26.3 and 1.21.1):** a disposable Fabric server with LSS and
  Vertigo 1.2.7 (official Modrinth jars), and a production-stack client (Sodium, Voxy, Vertigo,
  LSS) on a private display. Before the fix, fly at height over terrain served earlier, then
  look back: the band. After the fix, the band refills within seconds and `vertigo_refills`
  counts. Plus the measurement in section 4.
- **Astra row (visual pass):** with Vertigo on both sides, fly 300 blocks over hills at a small
  view distance, turn around: no black band in the LOD terrain.

Release-notes bullet (Bug Fixes): `- **Vertigo** — LOD terrain no longer keeps black holes
along your flight path when the server and client run Vertigo.`

## 8. Risks

- **Vertigo API changes.** It is a public API (`api` package) and the binding test pins it.
  If it changes, the feature turns itself off, with one INFO line.
- **Order with Voxy's own write.** Voxy writes the air at Sodium's `onChunkRemoved`, which is
  the same frame as the unload or the next. The re-serve arrives at least one round trip and
  one scan later, and Voxy's ingest queue is FIFO. If a later Voxy delays its remove ingest
  past a round trip, the air could land after the re-serve. Watch for it in the live check.
- **World unload (disconnect, dimension change).** If Fabric fires `CHUNK_UNLOAD` for loaded
  chunks while the manager is still active, the stamps are forgotten and saved, and the next
  visit re-serves them. If not, chunks loaded at disconnect keep their stamps. Whether Voxy
  writes those chunks at world unload is checked in the live repro. If it does and LSS misses
  them, record it as a known limitation (Re-download Terrain fixes it) rather than adding a
  teardown path.
- **Voxy ships Vertigo support (voxy#562).** If Voxy then skips hidden sections, LSS still
  re-serves hollowed columns: harmless, and only Vertigo users pay it. Revisit then.
- **A client-only Vertigo** (server without it): `isTrackingSections` is false, so nothing
  happens. That is correct, because the server then sends whole chunks.

## 9. Review fold

(Filled in by P4.)
