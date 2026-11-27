# Vertigo hollowed columns plan (issue #437)

Status: BUILT on main (2026-11-27), see section 10 for where the build departs from sections
3.1 and 7 (the detector reads the chunk, not Vertigo's API). Reviewed and folded (section 9).
Owner: maintainer runs.
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

**Reproduced live (2026-11-27)** on the measurement rig (pregenerated 26.3 world, warm store,
LSS 0.24.0, Vertigo 1.2.7 on both sides, a real Sodium + Voxy client at render distance 4,
spectator at y = 260). Rig: `~/.local/state/lss-repro/issue-437/`.
- A 16 blocks/s flight over terrain LSS had already served left a trench in the Voxy terrain
  with sky visible through it. It was still there 60 s later (`runs/base-flight2/`), like the
  issue's screenshot.
- The village at the start of the flight, never stamped because vanilla had it from the join,
  stayed intact. LSS asked for those columns normally once they unloaded, which confirms that
  a ts = -1 re-serve repairs the hole.
- A teleport away (`runs/base-vertigo2/`) leaves no trench: the whole disc drops at once, the
  slots are already null when Sodium's removals run, and Voxy writes nothing.

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

- **`VertigoCompat`.** New `xplat/.../compat/VertigoCompat.java`. Only when
  `FabricLoader.isModLoaded("vertigo")`, it resolves `builderb0y.vertigo.api.VertigoAPI` with
  `Class.forName(name, false, loader)` and binds by exact descriptor:
  - `isTrackingSections(Player)Z`;
  - `isSectionLoaded(Player,int,int,int)Z`.

  Any failure turns the feature off for the session with one INFO line, and touches nothing
  else.
- **Gates** (all must hold):
  - Vertigo resolved;
  - `Minecraft.getInstance().player` non-null;
  - an LSS terrain session with active acquisition;
  - Voxy's ingest bridge live (`VoxyCompat.isInitialized()`), and Voxy's ingest enabled for
    the level (the check `VoxyVanillaCoverage.destination` already makes);
  - `isTrackingSections(player)`.
- **`partialNow(chunk)`**: some section of the chunk is not loaded by Vertigo, and its bottom
  is at or below the chunk's top block.
  - The top block is the highest `WORLD_SURFACE` value of the chunk's 256 columns. The client
    holds the server's heightmap: Vertigo filters only sections and block entities, and its
    section packets do not touch heightmaps.
  - Sections above the top are air on the server too.
  - There is no lower bound. A lower bound would miss cliff faces at chunk edges, the seabed
    (`WORLD_SURFACE` counts water) and the Nether (its heightmap is the roof).
  - The price: at Vertigo ranges of 6 or less, walking on the ground re-serves too, because
    sections deep under the ground are hidden. That is measured (section 4).
- **When Voxy writes.** Voxy's last write for a chunk is Sodium's `onChunkRemoved`. It fires
  when the chunk loses "ready" status, which happens at the first unload among its 8
  neighbours. On its own drop, the slot is null and Voxy writes nothing. So the hole is
  decided at the first neighbour unload, plus the case where the chunk and its neighbours
  drop together (a teleport): then the write that counts is the one made when Voxy added it.
- **Unload listener.** It runs on `ClientChunkEvents.CHUNK_UNLOAD` in its own phase,
  `lss:vertigo_check` (its path sorts after `default`, so only the explicit ordering puts it first), ordered before `Event.DEFAULT_PHASE` (`addPhaseOrdering`).
  - Vertigo removes its per-chunk state in its own default-phase listener, so it must run
    after LSS.
  - The unload fires before the slot is nulled and before Sodium's queued removal.
  - Vertigo's range-unload packet arrives after the forget packet.

  For the unloading chunk C:
  1. **Neighbours.** For each of C's 8 neighbours N still in the client chunk cache: if
     `partialNow(N)`, add N to `suspect` (a `LongOpenHashSet`, cleared with the session and the
     level). Sodium takes N out of "ready" because of this unload, and Voxy writes N as it is
     now.
  2. **C itself.** C is hollowed if `suspect.remove(C)` or `partialNow(C)` holds. The second
     test covers a chunk dropped together with all its neighbours, and errs toward a needless
     re-serve when the player climbed since.
  3. **Report.** A hollowed C goes to `LodRequestManager.onHollowed(dimension, packed)`,
     called directly (the listener is on the client thread).

  The cost per unload is at most 9 evaluations of 24 API calls and 256 heightmap reads each,
  with no allocation.
- **Not detected** (recorded limitation): chunks that are loaded when the player leaves the
  dimension or disconnects keep their holes until the player passes there again; Re-download
  Terrain fills them at once. Fabric fires no `CHUNK_UNLOAD` there. The new `LocalPlayer` has
  an empty Vertigo state, and a flush of every loaded chunk would also run on the in-world
  teardowns (reception off, settings rebuild, map-policy park), with the probe cost of
  section 3.2. A player who comes back through the same portal or rejoins at the same spot
  repairs them when leaving.

### 3.2 Releasing the re-ask once the server has let the chunk go

A re-ask while the server still has the chunk loaded is serialized on the server's main thread
(`RequestProcessingService.probeLoadedChunks`, about 0.2 ms each). Once the chunk has unloaded,
the re-ask is a warm store serve off the main thread. So the manager holds hollowed positions
back:

- **Queue.** `onHollowed` ignores another dimension (the `lastDimension` guard
  `onIngestFailure` has). Otherwise it puts the position in `hollowPending`
  (`Long2LongLinkedOpenHashMap`, position → unload time), capped at 16,384; at the cap the
  oldest entry is released at once.
- **Release check.** Each client tick, the manager examines up to 64 entries from the head.
  - An entry is released when its Chebyshev distance from the player's chunk is more than
    `serverRenderDistance + 4`, and at least 5 s have passed since the unload.
  - An entry that is too close goes to the tail (`putAndMoveToLast`), so one close entry never
    blocks the rest.
  - Any entry is released after 30 s regardless, at the cost of one probe of a loaded chunk.
    This covers a player who hovers with a client view distance below the server's.
  - Why +4: player tickets keep chunks FULL out to `serverVD + 2`, and the server's ticket
    centre lags the client by a round trip. Vertigo needs a Fabric server, and Fabric unloads
    within ticks once the ticket goes.
  - `Options.serverRenderDistance` is the login packet's value. The port checks the field on
    each line.
- **Entries dropped without a release:**
  - the chunk loads into vanilla again first (the next unload decides again);
  - a column for the position is received after the unload (it is repaired already).
- **Release** = `columns.onHollowed(packed)`.
  - Only if that actually unstamped: `tracker.removeByPosition(packed)` and
    `metrics.recordHollowed()`. Removing the in-flight entry is load-bearing: an `up_to_date`
    answering a request that still carried the old stamp would otherwise park the -1
    position as satisfied for the session. Untracked, that answer is discarded, and a real
    column is still accepted.
  - During a pending cache load, record the position in `hollowedDuringCacheLoad`. It is
    re-applied with `onHollowed` over the loaded stamps, or on a dimension change sent to
    `ColumnCacheStore.removeAsync` like the failure list.
- **Leaving.** At `onDimensionChange` and at every manager teardown, before `saveCache`, every
  entry still in `hollowPending` is applied with `columns.onHollowed` (persisted as a removal;
  no request, since the session or dimension is ending).

### 3.4 Forgetting the stamp without a strike

New `ColumnStateMap.onHollowed(packed)`: the lost-content unstamp of `onIngestFailed`,
without the strike counter or the park.

- Old stamp -1 in memory (unknown, or an all-air park): nothing.
- `sessionSatisfied` set (not generated with a kept stamp, or parked at the ingest-failure
  cap): `tsPut(-1)` and `persistentRemovals.add` only, with no retry mark. `classify` keeps
  returning SATISFIED this session, and a retry mark would stay for good and reopen its ring
  on every scan.
- No in-memory state (pruned out of range before the release, for example after a teleport):
  `persistentRemovals.add`, so the merge-save drops the file's stamp.
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

- With Vertigo and Voxy on an LSS server, the trench behind a flight fills back in about 5 to
  10 seconds after it leaves view (up to 30 s if the client's view distance is below the
  server's).
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
    - a `sessionSatisfied` position with a stamp goes to -1 and a removal, with no retry mark;
    - a position without in-memory state gets a removal only;
    - -1 and pending-clear positions are untouched;
    - 100 repeats never park and never move `ingestFailures`/`ingestParked`.

    Also update `ReferenceColumnStateMap` and the differential and fuzz tests for the new
    mutator.
  - `LodRequestManager`:
    - release waits for both distance and age, a close head entry moves to the tail, 30 s
      releases regardless, and a reload or a received column drops the entry;
    - `onHollowed` from another dimension is ignored, and the pending entries are applied
      before `saveCache` on a dimension change and on teardown;
    - release removes the in-flight entry, and a stale `up_to_date` then does not park;
    - the pending cache load is replayed with `onHollowed`;
    - the next scan emits ts = -1.
  - `VertigoCompat` (fake API and heightmap):
    - `partialNow`: a hidden section above the top block does not count, one at or below
      does, and world-height clamping holds;
    - a neighbour unload marks a partial neighbour suspect, and its own later unload reports
      it even when it is full by then;
    - a chunk dropped with all its neighbours is judged by `partialNow`;
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
- **Leaving a dimension or disconnecting** keeps the holes of the chunks loaded at that moment
  (3.1, "Not detected"). Re-download Terrain fills them, and so does passing there again.
- **Small Vertigo ranges** (6 or less) re-serve while walking, because hidden sections deep
  underground count. This is bounded by the chunk unload rate, Vertigo users only. It is
  measured, and if it is too much, a lower bound comes back (minimum surface over the chunk
  and its neighbours, minus two sections).
- **A client-only Vertigo** (server without it): `isTrackingSections` is false, so nothing
  runs. That is correct, because the server then sends whole chunks.

## 9. Review fold

Panel (2026-11-27): one correctness and failure-case reviewer (Opus), one decisions critic
(Opus). I verified every finding inline against the sources before folding.

| # | Finding | Disposition |
| --- | --- | --- |
| C1 / D2 | "Any unloaded section" fires on almost every unload: Vertigo's ±r leaves air sections above the surface unloaded even on the ground | **Fixed:** the heightmap band (3.1). Verified: Vertigo filters only sections and block entities |
| C2 | Root cause wrong in mechanism: Sodium add/remove fire on ready status, Voxy's last write is at lose-ready (the slot is null at the drop). False negative when the chunk fills in between | **Fixed:** §1 rewritten; the neighbour `suspect` set (3.1) decides at the first neighbour unload. Verified in `MixinClientChunkCache` / `MixinRenderSectionManager`, and the teleport repro shows the null-slot case |
| C3 | No `CHUNK_UNLOAD` on world unload or dimension change; Voxy has written the partial disc, so the hole persists across sessions | **First fold:** a loaded-set flush. **Second fold:** dropped (see R3); `hollowPending` is still flushed as removals before `saveCache`; recorded limitation in 3.1 |
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

**Second round** (2026-11-27, one Opus reviewer over the folded design; verified inline):

| # | Finding | Disposition |
| --- | --- | --- |
| R1 | The release scan looks at 64 head entries only: a close head stalls the queue, and a hover with client VD below server VD keeps the band forever | **Fixed:** close entries move to the tail; release after 30 s regardless (3.2) |
| R2 | A position pruned before its release keeps its saved stamp (merge-save) | **Fixed:** `onHollowed` without in-memory state adds a persistent removal (3.4) |
| R3 | The loaded-set flush also runs on in-world teardowns (reception off, settings rebuild, map-policy park, proxy rejoin): it unstamps chunks both sides still have loaded, and they are re-asked at once on the main thread | **Fixed by removal:** the loaded-set flush is dropped (it also needed the join disc, R9, and lost the Vertigo state on a dimension change, R6). Recorded as a limitation in 3.1, since passing there again or Re-download Terrain repairs it |
| R4 | The heightmap band misses cliff faces at chunk edges, the seabed and the Nether | **Fixed:** no lower bound, only "at or below the chunk's top block" (3.1). Walking at small Vertigo ranges re-serves; measured, with the fallback recorded in §8 |
| R5 | The client heightmap is the server's (confirmed); local block updates can only lower it | No change (errs toward a needless re-serve) |
| R6 | The dimension-change flush always takes the conservative branch; no dimension guard on `onHollowed` | **Fixed:** the flush is gone (R3); `onHollowed` ignores another dimension (3.2) |
| R7 | `grewLater` fires on every descent, and its tick test needs care | **Replaced:** the neighbour `suspect` set (3.1). It judges a chunk at the moment Sodium takes it out of "ready", which is the moment Voxy writes it, and needs no Vertigo events |
| R8 | Player tickets keep chunks FULL to serverVD + 2, so the release margin was zero | **Fixed:** `serverRenderDistance + 4` (3.2) |
| R9 | The join disc is missing from the loaded set | Moot (R3) |
| R10 | `sessionSatisfied` with a stamp should still drop the stamp; remove the in-flight entry only on an actual unstamp; drop the pending entry when a column arrives | **Fixed** (3.2, 3.4) |
| Q4 | Teardown ordering before `saveCache`: no new problems | Kept |

The second fold simplified the design: there is no loaded set and no Vertigo event
registration, the suspect set replaces the flag, and the band lost its lower bound. It goes
to the implementation review, not a third plan round. That review reads the real code, and
the live repro checks the outcome.

## 10. As built (2026-11-27)

The live repro changed the detector. The rest (sections 3.2 to 3.5) is built as planned.

- **Vertigo's client API is not usable.** On the rig (Vertigo 1.2.7 on a 26.3 Fabric server and
  client) the server filtered sections, and the trench appeared. Yet the client's
  `VertigoAPI.isTrackingSections` was false at every unload (`runs/fix-dbg/`, 225 of 225).
  - Vertigo's client chooses its tracking manager when the `LocalPlayer` is constructed
    (`TrackingManager.createClient`, `ClientPlayNetworking.canSend(VertigoInstalledPacket)`).
    In this setup it falls back to whole-chunk tracking, which reports every section of a
    loaded chunk as loaded.
  - Its section packets still apply (`ChunkSectionLoadPacket.process` checks only the world and
    the chunk), so the vanilla chunks do get their real sections when the player moves
    vertically.
  - A detector gated on Vertigo's API would have fired for no one.
- **The detector reads the chunk** (`VertigoHollowDetector.hollow`). A chunk is hollow when a
  section between its lowest and highest `WORLD_SURFACE` block is all air (or absent) on the
  client.
  - The heightmap is the server's: Vertigo filters only sections and block entities. The
    unload log of the repro shows real heights (for example `top=71..84`) over empty sections.
  - Each column's top block sits in its section, so a section in that band that is all air is
    one Vertigo hid. A section of air below every column's top would be a cave the width of
    the whole chunk, and costs at most a needless re-serve.
  - Sky and deep terrain never count. The first tells nothing, and the second is out of sight
    in LOD terrain.
  - Not caught: a chunk whose surface Vertigo sent but whose cliff face below it is hidden.
    Rare, and recorded as a limitation.
  - Nothing of Vertigo is bound: no reflection, and no binding-surface test or golden jar.
    The only gate is `isModLoaded("vertigo")` with the Voxy bridge live. The event-phase
    ordering is gone too, since the chunk's own sections are still there in any
    `CHUNK_UNLOAD` listener.
- **Silent evictions.** After a teleport, the server's forget packets find the old chunks out of
  the client cache's range, so vanilla's `drop` does nothing and Fabric fires no unload event.
  Voxy never saw those chunks leave and keeps what it saved when they arrived.
  - The repro left a two-row strip there (`runs/fix2-dbg/`: unload events for rows 18 to 25,
    none for 16 and 17).
  - `VertigoCompat` therefore tracks the chunks it saw load. Once a second it treats a tracked
    chunk that is no longer what the cache holds at its position as dropped: reported if a
    neighbour's unload had marked it, or if it is hollow now.
- **Files.**
  - `xplat/.../compat/VertigoHollowDetector.java` (pure rule) and `VertigoCompat.java`
    (adapter, tracking and sweep).
  - `fabric/.../compat/VertigoFabricHook.java` (unload, load and tick events; it reads
    `Options.serverRenderDistance` through `lss.accesswidener`).
  - `LodRequestManager` (`onHollowed`, `tickHollowedPhase`, `flushHollowedPending` at the top
    of `saveCache`).
  - `ColumnStateMap.onHollowed`.
  - `ClientNetGlue` (`wantsHollowReports`, `reportHollowed`, `vanillaChunkLoaded`).
  - `vertigo_refills=` on the client diag's Columns line (a new `ClientDiagnosticSnapshot`
    field).
- **Tests.**
  - `VertigoHollowDetectorTest` (10), `ColumnStateMapHollowedTest` (6),
    `LodRequestManagerHollowedTest` (8).
  - The `SectionStateFuzzTest` differential has a new `onHollowed` op, mirrored in
    `ReferenceColumnStateMap`.
  - Mutation-checked: dropping the removal on a missing stamp, dropping the in-flight removal
    on release, and dropping the neighbour suspect each turn a test red.
- **Live result** (`~/.local/state/lss-repro/issue-437/runs/`), with 0.24.0 runs as the
  before:
  - `base-flight2`: the trench, still there 60 s after returning.
  - `base-novertigo`: the control, no trench.
  - `fix-b`: the build. The trench far from the player had refilled 15 s after returning, the
    stretch within the server's load distance had refilled by 60 s, and the client logged no
    LSS errors.
