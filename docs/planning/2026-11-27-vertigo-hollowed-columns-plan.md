# Vertigo hollowed columns plan (issue #437)

Status: PLANNED (2026-11-27), reviewed and folded (section 9). Owner: maintainer runs.
Target: 0.25.0 (New Features), beta first on 26.3 and 1.21.1, then 26.2 and 1.21.11.

## 1. Problem and evidence

With [Vertigo](https://github.com/Builderb0y/Vertigo) (MIT, Fabric only, 1.2.7 for 1.20.x to 26.3)
on both the server and the client, flying leaves black holes in the Voxy LOD terrain along the
flight path (#437, screenshot in the issue).

Cause, from the sources (Vertigo `7a64414`, Voxy branch `263` and the 1.21.1 fork, Sodium
mc26.3-0.9.2, Fabric lifecycle-events 4.1.9, LSS main `f5107cfb6`):

- **What Vertigo sends.** Vertigo sends only the sections within r of the player's section,
  where r is the view distance, clamped to world height (`SectionTrackingManager`). Other
  sections reach the client as air. Vertical moves load and unload single sections.
  - It filters section bytes and block entities only
    (`ClientboundLevelChunkPacketData_FilterSections`). The chunk's heightmaps are the
    server's real ones, and Vertigo's section packets swap array entries without touching
    them.
  - All of this happens only when both sides run Vertigo (`VertigoAPI.isTrackingSections`).
- **When Voxy writes the air.** Voxy ingests a client chunk on Sodium's `onChunkAdded` and
  `onChunkRemoved` (`MixinRenderSectionManager`). Sodium fires these when a chunk gains or
  loses "ready" status (the chunk and its 8 neighbours loaded), queued to the next frame.
  - A chunk usually stops being ready when an outer neighbour drops, one chunk step before
    its own unload.
  - On its own drop the slot is already null, so Voxy writes nothing then (except with Bobby).
  - Voxy also writes a section that turns invisible (`updateSectionInfo`), so a vertical
    unload writes air at once. A section that reloads with content is not written.
  - Each of these writes stores Vertigo's air over the LOD terrain Voxy already had. It
    happens without LSS too (Voxy request MCRcortex/voxy#562, open since 2026-06-13 with no
    reply; Vertigo closed the same report for DH as not planned, Vertigo#6).
- **The hollow condition.** A column is hollow in Voxy when, at its last Voxy write before
  it unloads, Vertigo had not loaded a section that holds visible terrain. Sections above
  the top block are air anyway. Sections well below the lowest surface are not visible in
  LOD terrain from above.
- **LSS does not repair it.** The column was served before the player arrived and its stamp
  stays. When the chunk leaves vanilla view, the scanner sees a satisfied column. A dirty
  mark would re-ask with the stamp and draw `up_to_date`. Only a forgotten stamp (ts = -1)
  gets the column served again.
- **The LSS border fill** (`VoxyVanillaCoverage`) writes the same air, but Voxy's own hooks
  write it anyway. The border fill does not decide whether the hole stays.

Not reproduced live yet. **P7 step 1 is the live repro** (section 7): confirm the hole, that
the client heightmap is the server's, and that a ts = -1 re-serve after the unload fills it.
If the repro disagrees with this section, the plan goes back to review.

## 2. Approaches considered

1. **Re-serve the columns Vertigo hollowed, once the server has let them go (chosen).**
   Detect a hollowed chunk when it unloads on the client. Some seconds later, once the
   column is past the server's own load distance, forget the column's stamp so that LSS's
   next scan asks with ts = -1. It fixes the band behind the player, which is the band the
   reporter sees. Holes under the player while the chunks are loaded are vanilla's own view
   (Vertigo's design), and Voxy does not draw there.
2. **A fix in Voxy** (a Vertigo check in `VoxelIngestService.shouldIngestSection`, about 15
   lines, reflection). This is the right place: it fixes every Voxy user, with or without
   LSS, and never sends anything again. But it is not ours to land. Filing on other projects
   is VoX's (maintainer guide §1), and voxy#562 has had no reply in five months. The LSS fix
   helps LSS players now. If Voxy ships it, LSS's detector keeps firing on Vertigo's state,
   which costs some needless re-serves for Vertigo users only. When that happens, gate the
   detector off for a Voxy that has the check (section 8). Recommended to VoX as a Voxy PR
   in the run report.
3. **The border fill skips Vertigo-hidden chunks.** Rejected: Voxy writes the same air, so
   nothing visible changes.
4. **Re-request every column after any chunk unload.** Rejected: a serve per unloaded chunk
   for every player. "Server load first".
5. **Hand Voxy only the loaded sections**, or re-serve only the missing sections. Rejected:
   the first covers only LSS's own border fill, and the second needs a protocol change.
6. **A server-side fix.** Rejected: the server does not know what the client's Voxy wrote,
   and a dirty push with the stamp draws `up_to_date`. It would need a protocol change.
7. **Vertigo's own client events alone** (`SECTION_UNLOADED`). They fire only for sections
   that were loaded, so they cannot show a chunk that arrived partial. They are used for the
   flag in 3.1, not as the detector.

## 3. Design

### 3.1 Detecting a hollowed chunk (Fabric client only)

- **`VertigoCompat`.** New `xplat/.../compat/VertigoCompat.java`. It resolves Vertigo with
  `Class.forName(name, false, loader)`, only when `FabricLoader.isModLoaded("vertigo")`, and
  binds by exact descriptor:
  - `VertigoAPI.isTrackingSections(Player)Z`;
  - `VertigoAPI.isSectionLoaded(Player,int,int,int)Z`;
  - `VertigoClientEvents.SECTION_LOADED` (a Fabric `Event`), registered with a
    `java.lang.reflect.Proxy` of `VertigoClientEvents$Load`.

  Any failure turns the feature off for the session with one INFO line, and touches nothing
  else.
- **Gates** (all must hold for anything to run):
  - Vertigo resolved;
  - `Minecraft.getInstance().player` non-null;
  - an LSS terrain session with active acquisition;
  - Voxy's ingest bridge live (`VoxyCompat.isInitialized()`), and Voxy's ingest enabled for
    the level (the `isIngestEnabled` check `VoxyVanillaCoverage` already makes). With Voxy
    ingest off, Voxy writes no air and a re-serve would be wasted;
  - `isTrackingSections(player)`.
- **Visible band.** For a chunk, take the min and max of its `WORLD_SURFACE` heightmap over
  its 256 columns: 256 array reads. The band runs from one section below the lowest
  surface section up to the highest surface section. A hidden section is relevant only if
  it is in the band. On the ground, Vertigo's ±r covers the band, so walking never
  triggers. Flying high, or deep underground under mountains, it does.
- **`partialNow(chunk)`**: some section in the band has `isSectionLoaded == false`.
- **`grewLater` flag** (per loaded chunk, `LongOpenHashSet`). It catches a chunk that was
  partial at Voxy's last write and then filled in before the unload (the player descended).
  - A `SECTION_LOADED` for a band section of an already-tracked chunk, in a later client tick
    than the chunk's first range, sets the flag. The first range of a new chunk fires its
    events together in one tick.
  - Over-firing (the chunk filled in early and Voxy then wrote it whole) only costs a needless
    re-serve.
- **Unload.** A `ClientChunkEvents.CHUNK_UNLOAD` listener runs in its own phase,
  `lss:before_vertigo`, ordered before `Event.DEFAULT_PHASE` with `addPhaseOrdering`.
  - Vertigo removes its per-chunk state in its own default-phase listener, so it must run
    after LSS. The unload fires before the slot is nulled and before Sodium's queued
    removal, and Vertigo's range-unload packet arrives after the forget packet.
  - If `partialNow(chunk)` or `grewLater` holds, the position goes to the manager. The
    listener runs on the client thread and calls `LodRequestManager.onHollowed(dimension,
    packed)` directly, with no `mc.execute` hop.
  - Either way, the position leaves the loaded set and the flag set.
- **Loaded set.** `CHUNK_LOAD` adds the position to a per-level loaded set while the gates
  hold. Section 3.3 uses it.

### 3.2 Releasing the re-ask once the server has let the chunk go

A re-ask while the server still has the chunk loaded is serialized on the server's main
thread (`RequestProcessingService.probeLoadedChunks`, about 0.2 ms each). After the server
unloads the chunk, it is a warm store serve off the main thread. So the manager holds
hollowed positions back:

- `onHollowed` puts the position in `hollowPending` (position → unload time, insertion
  order, capped at 16,384; at the cap the oldest is released at once).
- Each client tick, the manager looks at up to 64 entries from the head. An entry is released
  when both hold:
  - its Chebyshev distance from the player's chunk is more than `serverRenderDistance + 2`;
  - at least 5 s have passed since the unload.

  The server loads player chunks out to its view distance + 1. Vertigo needs a Fabric
  server, and Fabric unloads a chunk within ticks of its last ticket going.
  `Options.serverRenderDistance` is the value from the login packet; a port checks the field
  name on each line.
- If the chunk loads into vanilla again first, the entry is dropped. The next unload decides
  again.
- **Release** = `tracker.removeByPosition(packed)`, then `columns.onHollowed(packed)`, then
  `metrics.recordHollowed()`.
  - Removing the in-flight entry is load-bearing. An `up_to_date` answer to a request still
    carrying the old stamp would otherwise park the position as satisfied for the session.
    Untracked, that answer is discarded, while a real column is still accepted.
  - During a pending cache load, record the position in a `hollowedDuringCacheLoad` list and
    re-apply it with `onHollowed`, not `onIngestFailed`, over the loaded stamps. On a dimension
    change it goes to `ColumnCacheStore.removeAsync`, like the failure list.

### 3.3 Leaving the world or dimension

Fabric fires no `CHUNK_UNLOAD` on world unload or dimension change. On a dimension change,
the old chunks are dropped after the client has switched level, and the new `LocalPlayer` has
an empty Vertigo tracking manager. Voxy has already written the partial content of every
chunk in view, so stamps kept now would persist a hole across sessions.

- In `onDimensionChange` and at session teardown, before `saveCache`, every position in
  `hollowPending` and every position in the loaded set is forgotten with
  `columns.onHollowed` (persisted as a removal; no requests, since the session is leaving
  that dimension).
- Where Vertigo's state is still readable, a loaded position counts only if `partialNow` or
  `grewLater` holds. Otherwise all of the loaded set counts. That is one re-serve of the view
  disc the next time the player is there, and only for Vertigo users.

### 3.4 Forgetting the stamp without a strike

New `ColumnStateMap.onHollowed(packed)`: the lost-content unstamp of `onIngestFailed`,
without the strike counter or the park.

- Old stamp -1 (unknown, or an all-air park): nothing.
- `sessionSatisfied` set (not generated with a kept stamp, or parked at the ingest-failure
  cap): nothing. `classify` would keep returning SATISFIED, and the retry mark would stay
  for good and reopen its ring on every scan.
- `clearedResync` holds the position (the last delivery was a clear to air): nothing. Voxy
  holds air, which matches.
- Otherwise (stamp > 0, or a legacy 0):
  - `tsPut(-1)` and `persistentRemovals.add`;
  - clear `validated` and `summaryValidated`, and clear dirty;
  - `setRetry` and `recomputeNeeds`.
- It never touches `ingestFailures`, `ingestParked` or the failure metric, so a column flown
  over many times never parks.
- The forgotten stamp also clears the column's map debt. A Xaero or JourneyMap map gets the
  re-served column again, which repairs map holes from the same cause.
- No extra ring reopen: the retry mark already reopens its ring on every scan
  (`collectActionableRetryRings`).

### 3.5 What players see

- With Vertigo and Voxy on an LSS server, the band behind a flight fills back in about 5 to
  10 seconds after it leaves view.
- Nothing changes for anyone without Vertigo on both sides, or with Voxy ingest off.
- The client `/lss diag` request line gains `vertigo_refills=<n>`, shown only when non-zero.
- **No setting.** It runs only when Vertigo hides visible terrain, and its cost is bounded by
  the player's own chunk unloads (section 4). Settings stay strict (ADR 0009).

## 4. Server load (the "server load first" check)

- **Cost per column.** One ts = -1 request per hollowed chunk. It is released only after the
  server has unloaded the chunk, so it is a warm store serve: stored bytes shipped, off the
  main thread. On a cold store it is a region read. It never generates, because the chunk
  exists.
- **Rate.** Only chunks with hidden band sections count: flying high, or underground beneath
  higher terrain. Never while walking on the ground. The ceiling is the chunk unload rate.
  - Example: flying at 40 blocks/s with view distance 12 is about 62 chunks/s.
  - The 2026-11-21 rig measured about 0.11 ms of CPU per warm column (1,482 col/s at 16.5 % of
    a core), so about 0.7 % of a core per flying Vertigo player, off the main thread.
- **Bandwidth.** One LOD column per hollowed chunk, against vanilla's chunk packet for the
  same chunk on the way in.
- **Client.** Each retry mark makes the scan walk the leaves. Each re-serve writes to Voxy, and
  to a map if one is on.
- **Measured before release (P10)**, on the measurement rig with Vertigo on both sides, as a
  scripted flight against the same flight without Vertigo, at view distances 6, 8 and 12:
  - re-serves per chunk unload, flying and walking;
  - server MSPT, with the client's view distance below the server's as a required case;
  - share of re-serves that hit a loaded chunk (`probeLoadedChunks`): should be near 0;
  - client scan time with retry marks present;
  - column size on a tall world, if Big Globe 26.3 is available (where Vertigo is mostly
    used).

  Acceptance: no generation, no re-serves while walking, near-0 loaded-chunk probes, no MSPT
  change beyond noise, and LSS server CPU within 1 % of a core per flying player.

## 5. Per-line differences

| Line | Applies | Notes |
| --- | --- | --- |
| 26.3 (main) | yes | Vertigo 1.2.7-MC26.3, Voxy `263` |
| 1.21.1 | yes (Fabric) | Vertigo 1.2.7-MC1.21.1; the 1.21.1 Voxy has the same add/remove hooks. NeoForge has no Vertigo, so the listener is Fabric-only |
| 26.2 | yes (stable port) | Vertigo 1.2.7-MC26.2 |
| 1.21.11 | yes (stable port) | Vertigo 1.2.7-MC1.21.11 |
| 26.1, 1.21.10 | no (frozen) | |

Line seams to check at port time:
- `ChunkPos` accessors (fields on 1.21.11 and older) and the `LevelChunk` section-Y helpers;
- `Options.serverRenderDistance`;
- the Fabric `Event.register(Identifier, T)` overload (`ResourceLocation` on 1.21.x);
- the binding test with Minecraft types erased on 1.21.x (intermediary).

## 6. Settings, wire and downgrade

- Settings: none. Wire: none (a normal ts = -1 request, in every dialect).
- Persistence: the existing `persistentRemovals` path. An older LSS reading the cache file
  sees a missing stamp, which it handles normally.

## 7. Tests

- **Live repro first (P7 step 1, 26.3).**
  - Setup: a disposable Fabric server with LSS and Vertigo 1.2.7 (official Modrinth jars),
    and a client with Sodium, Voxy, Vertigo and LSS on a private display.
  - Before the fix: fly at height over terrain served earlier and look back; record the band
    (screenshot).
  - Confirm that the client `WORLD_SURFACE` heightmap matches the server's under Vertigo
    (debug probe), and that `/lss reset`-style forgetting of one column fills it.
  - After the fix: the band refills and `vertigo_refills` counts.
  - Also: a Xaero map shows whether its holes refill too.
- **Unit:**
  - `ColumnStateMapHollowedTest`:
    - > 0 and 0 stamps go to -1 with a retry mark, a persistent removal and cleared
      validation bits;
    - -1, `sessionSatisfied` and pending-clear positions are untouched;
    - 100 repeats never park and never move `ingestFailures`/`ingestParked`.

    Also update `ReferenceColumnStateMap` and the differential and fuzz tests for the new
    mutator.
  - `LodRequestManager`:
    - release waits for both distance and age, and a reload drops the entry;
    - release removes the in-flight entry, and a stale `up_to_date` then does not park;
    - the pending cache load is replayed with `onHollowed`;
    - a dimension change forgets the pending and loaded positions into the old bucket;
    - the next scan emits ts = -1.
  - `VertigoCompat` (fake API and heightmap):
    - band math, including world-height clamping and a hidden section above the top block
      (not relevant);
    - `grewLater` from a later-tick load only;
    - not tracking means nothing, and failed resolution means nothing, without throwing.
- **Binding surface:** `VertigoBindingSurfaceTest` checks the three members against the line's
  Vertigo golden jar (`maven.modrinth:vertigo:<version id>`, new `vertigo_golden` property),
  at the classfile level like the Xaero test.
- **Phase order:** a Fabric unit test registers a default-phase listener that clears a fake
  Vertigo state, fires `CHUNK_UNLOAD`, and asserts that LSS saw the state first.
- **Measurement:** section 4.
- **Astra row:** with Vertigo on both sides, fly 300 blocks at height over hills at view
  distance 8, turn around, and wait 15 s: no black band left in the LOD terrain. Walk the same
  way on the ground: `vertigo_refills` stays 0.

Release-notes bullet (New Features): `- **Vertigo** — With Vertigo on the server and your client,
Voxy's distant terrain behind you fills back in a few seconds after you fly past, instead of
keeping black holes.`

## 8. Risks

- **Vertigo API changes.** It is a public `api` package, and the binding test pins it. A change
  turns the feature off with one INFO line.
- **Voxy ships #562.** The detector then over-fires: needless re-serves, Vertigo users only.
  When a Voxy with the check appears, detect it (its `shouldIngestSection` behaviour or
  version) and turn the detector off for it.
- **Order with Voxy's writes.** Voxy's air is queued before the unload. The re-serve goes
  through the same FIFO `VoxelIngestService` queue (`rawIngest` → `rawIngest0`) at least 5 s
  later. This depends on that shared queue.
- **Release delay.** A player turning straight back sees the band for up to about 10 s. That
  is accepted for the server-load gain: a still-loaded chunk costs a main-thread serialize.
- **Dimension and disconnect flush.** It may re-serve some full chunks (the conservative
  branch). This is bounded at one view disc per change, Vertigo users only.
- **A client-only Vertigo** (server without it): `isTrackingSections` is false, so nothing
  runs. That is correct, because the server then sends whole chunks.

## 9. Review fold

Panel (2026-11-27): one correctness and failure-case reviewer (Opus), one decisions critic
(Opus). I verified every finding inline against the sources before folding.

| # | Finding | Disposition |
| --- | --- | --- |
| C1 / D2 | "Any unloaded section" fires on almost every unload: Vertigo's ±r leaves air sections above the surface unloaded even on the ground | **Fixed:** the heightmap band (3.1). Verified: Vertigo filters only sections and block entities |
| C2 | Root cause wrong in mechanism: Sodium add/remove fire on ready status, Voxy's last write is at lose-ready (the slot is null at the drop). False negative when the chunk fills in between | **Fixed:** §1 rewritten; the `grewLater` flag from Vertigo's `SECTION_LOADED` (3.1). Verified in `MixinClientChunkCache` / `MixinRenderSectionManager` |
| C3 | No `CHUNK_UNLOAD` on world unload or dimension change; Voxy has written the partial disc, so the hole persists across sessions | **Fixed:** the flush in 3.3 |
| C4 | `onHollowed` on a `sessionSatisfied` position strands a retry mark | **Fixed:** skip it; also clear the validation bits (3.4). Verified at `ColumnStateMap` classify and `onNotGenerated` |
| C5 | In-flight removal is load-bearing (a stale `up_to_date` parks a -1 position); call directly on the client thread | **Fixed** (3.1, 3.2) |
| C6 | Phase ordering works; add a null-player check | **Fixed** (gates) |
| C7 | No realistic ordering race; note the shared FIFO queue | **Fixed** (§8) |
| C8 | The ring reopen is redundant; map debt cleared; replay with `onHollowed`; erased types on 1.21.x; gate on Voxy's ingest setting | **Fixed** (3.2, 3.4, §5, gates); scan cost added to §4 |
| D1 | Send a Voxy PR first; build LSS only if it stalls | **Partly accepted:** recorded as the right upstream fix and recommended to VoX (filing on other projects is his). The LSS fix proceeds because voxy#562 has had no reply in five months and Voxy has no published 26.3 build. Section 8 records the switch-off |
| D3 | A re-ask right after the unload usually hits a still-loaded chunk: a main-thread serialize (`probeLoadedChunks`), about 0.2 ms each | **Fixed:** the delayed release (3.2); MSPT and loaded-probe share added to the measurement. Verified at `RequestProcessingService.probeLoadedChunks` |
| D4 | "No setting": keep | Kept |
| D5 | It is a new integration, not a bug fix; do not promise the disc under the player | **Fixed:** target 0.25.0, New Features, bullet reworded. It ships with the next train and gets no dedicated release |
| D6 | Check maps in the repro; probably an upside | **Fixed** (§7 repro, 3.4) |
| D7, D8 | Rejected alternatives and persistence: keep | Kept |
