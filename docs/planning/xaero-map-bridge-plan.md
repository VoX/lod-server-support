# Xaero's World Map bridge — implementation plan (issue #223)

Status: IMPLEMENTED 2026-08-23 on feat/xaero-map-bridge (2-Fable plan review folded
— §10; implementation notes in §11; the 1-Fable + 4-Opus implementation review
folded — §12). Targets main (the staged v0.12.0 release), designed for cheap
porting to all four support lines (§8.4 lists the three per-line API
substitutions).

## 0. Goal

Issue #223: Xaero's World Map only records chunks within vanilla render distance, so
on an LSS server the map is a small dot inside a huge rendered world. LSS receives
full MC-native section data (block states + biomes + light) for every LOD column —
enough to write Xaero map tiles CLIENT-SIDE, with no server change and no protocol
change. The referenced mod (voxyworldgenxaero-bridge) solves this for Voxy worldgen
in single-player only; its technique does not transfer (see §1). We build the
multiplayer equivalent from our own delivery stream.

Non-goals (v1): cave layers (surface only); Minimap-only installs (the minimap
renders World Map tiles when both Xaero mods are present — we target the World Map
only); reading Voxy's store to backfill terrain LSS never re-serves (§8.3);
server awareness of any kind; **single-player** (LSS's client is inert on
integrated servers — voxyworldgenxaero-bridge remains the SP answer; say so in the
README so nobody files "doesn't work in SP").

## 1. Research facts this plan builds on (2026-08-22, two research agents + the review's re-verification)

Artifacts: `scratchpad/xaero-research/Xaero-WorldGen` (MIT-licensed bridge-mod
source), `scratchpad/xaero-research-map/` (Xaero WM 1.45.0 jars for 26.2 + 1.21.1,
CFR decompile output in `cfr-out262/`, XaeroPlus clone). Key findings:

- **The SP bridge's technique is non-transferable.** It exploits Xaero's
  *world-save* mode (integrated server only): fabricated `RegionDetection`s backed
  by stub `.mca` files + mixins substituting synthesized vanilla chunk NBT inside
  `WorldDataReader.readChunk`. In multiplayer Xaero maps from the client chunk
  cache through a different pipeline. Transferable lessons: serve snapshots to
  Xaero threads, never fight real data, gate on completeness, throttle everything.
- **Xaero WM has no public write API and is closed-source (ARR)** — but the jar is
  fully unobfuscated under stable `xaero.map.*` FQNs. XaeroPlus (MIT, actively
  maintained) binds the same internals with `remap=false` mixins; the community
  norm the author tolerates is interop-from-a-separate-mod with no redistribution.
  We go one step softer than XaeroPlus: **reflection only, no mixins** — and the
  review VERIFIED every member this plan needs is `public` (fields
  `writerThreadPauseSync`/`mainStuffSync`/`mainWorld` included; `MapTileChunk`,
  `MapBlock`, `Overlay` ctors included). No `privateLookupIn`, no opens.
- **FQN stability is verified**, not assumed: the 26.2 and 1.21.1 jars (both
  1.45.0, shipped the same day — Xaero updates every MC line in lockstep) have an
  FQN-identical write pipeline, re-confirmed member-by-member for everything in
  §4. Xaero publishes current builds for every line we support: 26.2, 26.1.x,
  1.21.11, 1.21.10, 1.21.1.
- **The MP write pipeline**: packet mixins mark `LevelChunk.xaero_wm_chunkClean =
  false`; each frame the MAIN CLIENT THREAD runs `MapWriter.onRender → writeMap →
  writeChunk` over a window hard-clamped to `min(config, 32,
  effectiveRenderDistance)` around the player, reading chunks from the client
  chunk cache (skipping `EmptyLevelChunk` and chunks whose 8 neighbors aren't
  loaded). Thread enforcement is explicit in Xaero:
  `MapTileChunk.updateBuffers` and `MapProcessor.getLeafMapRegion(create=true)`
  both hard-throw off `Minecraft.isSameThread()`.
- **The native gate ladder** (`MapWriter.onRender:195-230`) — the checks a
  foreign writer must mirror, verbatim: under `renderThreadPauseSync`:
  `!mapProcessor.isWritingPaused() && !isWaitingForWorldUpdate() &&
  mapSaveLoad.isRegionDetectionComplete() && isCurrentMultiworldWritable()`;
  plus `getWorld() != null`, `!isCurrentMapLocked()`,
  `!getMapWorld().isCacheOnlyMode()`, `getCurrentWorldId() != null`,
  `!ignoreWorld(world)`; under `mainStuffSync`: `mainWorld == getWorld()` and
  `getWorld().dimension() == getMapWorld().getCurrentDimensionId()` (a
  `ResourceKey<Level>` equality — THE dimension binding; see §2.7).
- **The commit sequence** (`MapWriter.writeChunk:423-620`), the normative model:
  `synchronized (region.writerThreadPauseSync)` → **`!region.isWritingPaused()`**
  (the writer's half of the save-path exclusion — `MapSaveLoad` saves inside
  `pushWriterPause`) → `synchronized (region)` → `loadState == 2` +
  `registerVisit()` + `isResting()` → `setBeingWritten(true)` → get/create
  `MapTileChunk` (public ctor; `setLoadState((byte) 2)`;
  `region.setAllCachePrepared(false)`) → per-pixel `MapBlock` writes → per tile:
  `setWorldInterpretationVersion(1)` (BEFORE setTile — its
  tileWasLoadedWithTopHeightValues branch reads it) → `setWrittenCave(...)` →
  `tileChunk.setTile(insideX, insideZ, tile, blockStateShortShapeCache,
  mapProcessor)` → `setWrittenOnce(true)`/`setLoaded(true)`; tile-chunk write
  additionally gated on `tileChunk.getLoadState() == 2` and
  `!getLeafTexture().shouldDownloadFromPBO()`.
- **`setBeingWritten(true)` is set and NEVER cleared by the writer.**
  `MapSaveLoad.updateSave` only enqueues saves for regions with
  `isBeingWritten() == true` (and clears the flag itself after saving); the flag
  is also what stops the load drain demoting an empty fresh region. Clearing it
  after a commit = tiles never persist.
- **Region loading is mandatory**: fresh regions start loadState 0 and never
  self-promote; promotion to 2 happens only in `MapSaveLoad.run`'s load drain.
  The native writer's dance (`MapWriter:311-355`): per pass, if
  `canRequestReload_unsynced() && loadState != 2` → `setBeingWritten(true)` then
  `requestLoad(region, reason)`, paced to ~1 request per pass via
  `getNextToLoadByViewing().shouldAllowAnotherRegionToLoad()`. Also
  `getLeafMapRegion` returns **null until `isRegionDetectionComplete()`**.
- **The per-pixel primitive**: `MapBlock.write(BlockState state, int height,
  int topHeight, ResourceKey<Biome> biome, byte light, boolean glowing,
  boolean cave)` after `prepareForWriting(worldBottomY)`. Colors are computed
  later from the stored state/biome at texture time. **Fluids/transparents never
  become the main state** — they go into overlays (`MapBlock.addOverlay`,
  `Overlay(BlockState, byte light, boolean)` public ctor, opacity via
  `increaseOpacity`, interned through `OverlayManager.getOriginal`); the main
  state is the opaque floor. Light byte = BLOCK light at h+1 (sky light is
  cave-mode-only); biome sampled at topHeight; void column =
  `write(AIR, worldBottomY, worldBottomY, biome, 0, false, false)`; slopes may
  stay `slopeUnknown` (self-heal at render).
- ~~**No direct `updateBuffers` needed**: `setChanged(true)` +
  `setToUpdateBuffers(true)` is sufficient — Xaero's `LeafRegionTexture.preUpload`
  sweep performs the GPU work under its own locks/budget.~~ **SUPERSEDED by §15
  (field-test round 3)**: the flag is consumed by the sweep with NO `isResting()`
  check, i.e. possibly after the region was queued for cache-saving on prepared
  textures — the saver then throws. The native writer only ever un-prepares a
  region INSIDE its `isResting` gate (`updateBuffers` directly, at the (3,3)
  chunk / the bottom neighbor); the bridge now does the same, coalesced per
  tile chunk. `BlockTintProvider`/`MapUpdateFastConfig` joined the bound
  surface for it.
- **Persistence**: the MapRunner sweep saves being-written, terrain-bearing
  regions on its own cadence (`hasHadTerrain` propagates from
  `tileChunk.setHasHadTerrain()`), force-flushing on dimension finish. No
  explicit refresh call needed (the save path handles cache/refresh when
  `!isAllCachePrepared`).
- **Boundary self-heal verified both directions**: a newly loaded `LevelChunk`
  defaults `xaero_wm_chunkClean = false` and the native writer rewrites on that
  flag regardless of the existing tile — the native writer reclaims LOD tiles
  when the player arrives. Data model: `MapRegion` (32×32 chunks, `regX =
  chunkX >> 5`) → 8×8 `MapTileChunk` (4×4 chunks) → 4×4 `MapTile` (one chunk)
  → 16×16 `MapBlock`. Surface layer = caveLayer `Integer.MAX_VALUE`.
  `MapProcessor.getCurrentDimension()` returns the literal `"placeholder"`
  string in 1.45.0 — mirror whatever the decompile shows, never invent.
- **XaeroPlus warning**: heavy bytecode transforms inside `writeChunk` caused
  delayed JVM C2 crashes on ARM — reflection-from-outside avoids that class
  entirely.

## 2. Design decisions

1. **Data source = an internal `VoxelColumnConsumer`** registered by the bridge
   (the `VoxyCompat` shape). `LSSApi.dispatchColumn` already hands every decoded
   column — live serves, disk serves, generation serves, dirty re-serves — to
   consumers with the live `ClientLevel`, MC-native `LevelChunkSection`s and
   block/sky `DataLayer`s, on the LSS decode thread. No new tap into the
   pipeline, and the bridge automatically sees exactly what the LOD renderer
   sees (post-XVER-translation, post-masking — x-ray-masked bytes stay masked on
   the map, the correct privacy outcome). The dispatch level is dimension-matched
   to the payload by the drain (ClientColumnProcessor skips stale-dimension
   payloads), so the extractor can take world bounds / hasSkyLight from it.
2. **Reflection-only interop, zero compile-time dep, no mixins.** One xplat class
   `XaeroMapCompat` resolves the full handle set once (lazy, on first init with
   the mod present), in the `MoonriseReadCompat` style: any resolve failure →
   bridge unavailable, one warn naming the drift, LSS unaffected. Feasibility is
   review-verified: every needed member is public (§1). Gate:
   `LoaderServices.get().isModLoaded("xaeroworldmap")` (verify the NeoForge mod
   id at implementation; expected identical). `fabric.mod.json` gains
   `"suggests": {"xaeroworldmap": "*"}` — literal `"*"`, no gradle.properties
   backing (we bind reflectively and fail soft; a version range would be a lie),
   with `FabricModJsonContractTest.suggestsRangesAreTemplatedAndBackedByProperties`
   extended to pin the literal. ARR posture: we ship no Xaero code, no compile
   dep, no bytecode injection — softer than the tolerated community norm.
3. **Write path = direct tile synthesis (the WorldDataReader precedent), commit
   on the MAIN CLIENT THREAD.** We do NOT puppet `MapWriter.writeChunk` (it is
   hard-coupled to the client chunk cache, the player window, private cursor
   state, and the 8-neighbor edge rule) and we do NOT touch Xaero's discovery
   (no fake chunks, no fake packets). The bridge performs §1's commit sequence
   on the thread Xaero enforces (`isSameThread` throws are explicit), under the
   same locks, the same gate ladder, and the same lifecycle flags. Where the
   decompiled `writeChunk` and this plan disagree, **the decompile is
   normative** — mirror it (CFR output is in the research scratchpad;
   re-decompile on Xaero updates). Texture rebuilds are OURS, like the native
   writer's: `updateBuffers` under the same gates, coalesced per tile chunk
   (§15) — never the `setToUpdateBuffers` flag (its sweep-side consumption
   escapes `isResting`, the cache-not-prepared crash).
4. **Two-stage pipeline, budgeted.** Stage 1 (LSS decode thread, inside the
   consumer callback): extract a compact immutable `PreparedTile` — per pixel:
   floor BlockState, height, topHeight, biome, block-light byte, plus the fluid
   overlay runs (§5) — so no `LevelChunkSection` reference outlives the
   callback. Stage 2 (main client thread, from the existing shared
   `ClientNetGlue.onEndClientTick()` pump — both loaders already call it): drain
   a bounded queue under a per-tick budget — the 2 ms wall is the BINDING
   constraint, the commit-count cap (64) is a safety ceiling (impl review MAJOR:
   the original cap of 8 = 160 tiles/s against 300-1000 delivered columns/s
   made every fast-link backfill drop most of the map and made the clearcache
   heal circular) — plus up to a window's worth of `requestLoad` grants per
   pump (§14's memoryless outstanding window; supersedes the original ~1/pass
   gauge pacing). The drain is region-BUCKETED (§14) with the rotation at
   BUCKET granularity (the IncomingRequestRouter M4 precedent — an unrotated
   head-first walk would starve committable buckets behind a permanently-
   deferring prefix), the snapshot taken under ONE queue-lock acquisition and
   the per-entry filters run INSIDE the budgeted loop; the budget check is
   skipped until the pump has made one unit of progress (a drop or a commit
   attempt), so a degenerate budget can never live-lock (3-Opus fold MAJOR).
5. **Bounded latest-wins queue, keyed by packed chunk pos** — bounded by COUNT
   (8192) and BYTES (~48 MB estimated; §14 widened both from the original
   2048/24 MB — the ClientColumnProcessor discipline: plain tiles are ~4.7 KB
   but overlay-heavy ocean tiles reach ~90 KB, so the count cap alone would
   admit ~0.5 GB; impl review MAJOR corrected this plan's original ≤8 MB
   arithmetic). A newer tile for the same position replaces in
   place; overflow evicts oldest (counted). Cleared at session end
   (`ClientNetGlue.onDisconnect`) and on config-off; offers are additionally
   gated on a LIVE LSS session, closing the disconnect-drain race that could
   carry one stale tile into the NEXT server's (or a singleplayer world's)
   persistent map. Every pump-side removal is COMPARE-and-remove (entry + tile
   identity) so a commit racing a fresh re-offer can never delete the newer
   tile. Stale-DIMENSION entries are NOT a lifecycle event: the pump drops any
   entry whose dimension differs from the current level's (counted) — no
   dimension-change hook needed. This mirrors the LSS re-declaration
   philosophy: a dropped tile is not a correctness hole — the map is
   best-effort, and the position heals on the next dirty serve or a
   `/lss clearcache` backfill.
6. **Overlap policy: skip exactly what the native writer will write.** At COMMIT
   time (main thread — safe and current), skip a position only when its chunk
   AND all 8 neighbors are loaded in the `ClientLevel` (`EmptyLevelChunk`
   counts as not-loaded; counted `skipped_native`) — the native writer's OWN
   edge rule. Skipping on "loaded" alone was the v1 rule and produced the
   FIELD-TESTED boundary-ring defect (2026-08-23, the first manual test): the
   native writer never writes the outermost ring of loaded chunks (no outer
   neighbors), so with both writers declining it, every join point grew a
   1-chunk black circle at the vanilla/LOD boundary — the ring's columns HAD
   been served during the join window (before vanilla chunks arrived) and the
   broad skip threw the tiles away. Under the narrowed rule the edge ring is
   bridge-written and the native writer reclaims it on its clean-flag once
   fully surrounded (§1 boundary self-heal — LOD-over-native degradation stays
   self-limiting in both directions). Note: the scanner already excludes
   vanilla-view positions from the want-set, so fully-surrounded-chunk columns
   mostly never arrive — `skipped_native` is a race belt (movement crescents,
   RD shrink, chunk-loaded-between-serve-and-commit), not a volume path.
   Accepted edge: a chunk loaded AND surrounded but outside Xaero's own write
   window (`min(config, 32, effectiveRD)`) is skipped by both writers — a thin
   stale ring possible when server view distance exceeds Xaero's window; heals
   when the chunk enters the window or is re-served.
7. **Gate ladder = the native writer's, verbatim; deferral not deletion.** Every
   pump pass re-checks, in the native order and under the native monitors
   (§1's ladder): session present + usable, `!isWritingPaused()`,
   `!isWaitingForWorldUpdate()`, `isRegionDetectionComplete()`,
   `isCurrentMultiworldWritable()`, world non-null + not ignored + map not
   locked + not cache-only, and under `mainStuffSync` the `mainWorld` identity
   + the `ResourceKey<Level>` dimension equality. Mirroring that equality is
   the whole anti-wrong-dimension binding: writes are structurally impossible
   into a dimension the processor isn't currently writing — at the accepted
   cost that (exactly like Xaero's own writer) commits PAUSE while the user
   browses another dimension's map in the GUI; the queue holds meanwhile.
   Ladder-not-ready states DEFER (entries stay queued; the bounded queue is the
   TTL). Per region, mirror the write gates (`!region.isWritingPaused()` under
   `writerThreadPauseSync` — the save-race exclusion — then `loadState == 2` +
   `registerVisit()` + `isResting()` under the region monitor) and the
   MANDATORY load dance for regions not at loadState 2 (§14's grant phase —
   supersedes the original ~1/pass gauge pacing): under the region monitor,
   `loadState != 2` → the 3→4 revival if cache-parked → `isResting()` →
   `canRequestReload_unsynced()` → `setBeingWritten(true)` →
   `requestLoad(region, "lss-xaero-bridge")` — fresh regions NEVER
   self-promote, so without this the feature silently no-ops everywhere Xaero
   hasn't already been. Deferral scope is split (3-Opus fold MAJOR): REGION-
   scoped not-ready (being saved / not resting) burns the whole bucket's
   deferral counters and short-circuits; TILE-CHUNK-scoped not-ready (the 4×4
   chunk's loadState, a PBO download) burns only that entry and its bucket
   siblings keep committing. Entries for a region awaiting load defer without
   burning deferral budget; a per-entry deferral cap (~200 ladder-ready passes)
   then drops (counted, removal-guarded). `setBeingWritten(true)` is set and
   never cleared by us (§1 — the save path owns the reset; clearing it would
   silently lose every tile not later touched by the native writer).
8. **Failure containment — the map must never cost LOD correctness.** The
   consumer callback catches ALL its own throwables (nothing escapes to
   `dispatchColumn` — an escape would trigger `reportIngestFailure` and put the
   column into the re-serve loop for a map problem). [AMENDED 2026-08-24, hybrid-scan-plan.md §12 — this item's second half is
   REVERSED: the bridge now DOES override `pendingIngestBacklog` (the §12
   want-set backpressure — the map paces the stream to its writer's rate) and
   DOES call `reportIngestFailure`, through the kept immediate `DropReporter`
   only (stale-dimension/world-change drops always; governed drops under the
   composed kill switches). The first half stands: a throwing extraction still
   never escapes to `dispatchColumn`.] Commit-time throws: LogThrottle'd warn + drop the tile; a
   consecutive-failure latch (5 in a row) kills the bridge for the session
   (`state=dead` in diag), FarPlayerRenderer-crash-latch style.
9. **Config + Sodium toggle.** New client config key `enableXaeroMapBridge`
   (default FALSE — flipped from the plan's original true by user decision
   2026-08-23, pre-release: opt-in while the feature is new, because map writes
   are persistent saved data; ConfigValidationTest pins the OFF default) checked
   LIVE at both enqueue and pump, so the Sodium toggle
   applies mid-session (flip off → queue cleared). Sodium option
   `lss:xaero_map_bridge` on the LSS page (boolean, `join_slow_start` pattern,
   enabled-dep on `lss:receive_server_lods`), lang keys
   `lss.config.xaero_map_bridge{,.tooltip,.tooltip.not_installed}` — the
   not-installed tooltip selected at menu build when `xaeroworldmap` is absent
   (the join_slow_start governor-off / SeeU conditional-tooltip precedent).
   Strings stay brand-neutral (Brand discipline; the VSS jar shares them).
10. **The cached-server gap is documented, not engineered around (v1).** On a
    server where the client already holds stamps, converged columns answer
    `up_to_date` — no data flows, the map stays empty until terrain changes.
    The existing `/lss clearcache` (run WHILE CONNECTED — the no-session form
    clears all servers' caches; forget stamps → full re-serve → bridge
    repopulates; Voxy dedupes its own re-ingest) is the documented one-shot
    backfill. Release notes + README say so. A future option is reading Voxy's
    store directly (the SP bridge proves the read surface: `WorldEngine.
    acquireIfExists`/`WorldSection.copyData`/`Mapper` — the exact inverse of our
    ingest bridge) — recorded as v-next, out of scope.
11. **Porting is cheap by construction.** Everything lives in xplat (`compat/` +
    the pure extractor) + one `ModCompat.init` branch + one line in the shared
    tick glue — both loaders get it with zero loader-specific code (NeoForge's
    bootstrap already calls `ModCompat.init()` and
    `ClientNetGlue.onEndClientTick()`). Reflection makes Xaero's MC-typed
    signatures mapping-proof: our class literals remap per line at build, Xaero
    targets the same runtime names, and Xaero's own FQNs are identical on all
    five lines (§1). The Sodium menu addition is the only per-line divergence
    (the 1.21.10 line had no Sodium page at the time — SUPERSEDED 2026-08-23 by sodium-options-page-generations-plan.md, which renders the same catalog on Sodium 0.6/0.7; config-file key still works there;
    the port drops the menu hunk, exactly like the rest of its Sodium cut).

## 3. Components

New files (X = new, M = modified):

- X `xplat/…/compat/XaeroMapCompat.java` — resolve-once handle set (§4),
  consumer registration, the bounded latest-wins queue, the main-thread pump
  (`pumpFromClientTick()`) implementing §2.7's ladder + §2.6's skip + the
  budget, counters, throw latch, `clearQueue()`, diag snapshot accessor. Test
  seams: `ClassResolver` + registrar + a sink/clock seam so Tier 1 drives the
  pump against stubs (the `VoxyCompat`/`MoonriseReadCompat` seam discipline;
  instance-scoped resolution for order-independent tests, since
  `getCurrentSession()` is static state on the Xaero side).
- X `xplat/…/compat/XaeroTileExtractor.java` — PURE section→pixel computation,
  zero Xaero types (§5). Input: `VoxelColumnData` + world bounds/hasSkyLight
  from the dispatch level; output `PreparedTile` (record). Fully unit-testable.
- M `xplat/…/compat/ModCompat.java` — `xaeroworldmap` branch in `init()`,
  `clientTick()` forwarder, `xaeroDiagLine()` accessor.
- M `xplat/…/networking/client/ClientNetGlue.java` — `ModCompat.clientTick()`
  call in `onEndClientTick()`; queue clear in `onDisconnect` (beside
  `FarPlayerClientSupport.onSessionEnd()`).
- M `xplat/…/networking/client/ClientCommandActions.java` — the conditional
  `XaeroMap:` diag line (the `Summary:` conditional-line precedent, ~line 171).
- M `xplat/…/config/LSSClientConfig.java` — `enableXaeroMapBridge` (default
  true) + javadoc.
- M `fabric/…/config/LSSConfigMenu.java` — the toggle (§2.9).
- M `fabric/src/main/resources/assets/lss/lang/en_us.json` — 3 keys.
- M `fabric/src/main/resources/fabric.mod.json` — suggests `xaeroworldmap: "*"`.
- M `fabric/src/test/java/…/FabricModJsonContractTest.java` — extend the
  suggests pin for the new literal-`"*"` entry.
- M docs: README compatibility note (incl. the clearcache backfill + SP
  non-goal), this plan, release-notes bullet (main's v0.12.0 notes file; line
  notes at port time).

Tier 1 tests (fabric module, `fabric-loader-junit`; `LevelChunkSection`
construction under it is proven — SectionConstructionPinTest et al.):
- X `XaeroTileExtractorTest` — hand-built sections → expected pixel arrays:
  plains surface; water column (floor state + h/topH split + one overlay run
  with summed opacity); deep-ocean multi-run; void/all-air; a RESYNC all-air
  column (must produce the void pixel that erases stale map terrain); End (no
  sky light); bottom-of-world; missing mid-column sections (scan across as
  air); single non-air section (the superflat shape); light = block light at
  h+1, biome at topH.
- X `XaeroMapCompatTest` — against real-package-name stubs under
  `fabric/src/test/java/xaero/map/…` (the Moonrise stub pattern; Xaero classes
  are not remapped, so stub FQNs are literal): resolve happy path registers the
  consumer; each missing/wrong-shape member → unavailable + no consumer + one
  warn; the pump gate ladder (each not-ready gate defers; dimension-mismatch
  entry drops; loaded-chunk skip; budget stops; unloaded region issues EXACTLY
  ONE paced requestLoad with setBeingWritten(true) first and defers without
  burning deferral budget; ladder-ready deferral cap drops); commit-sequence
  order against a recording stub (worldInterpretationVersion before setTile;
  setBeingWritten never cleared; ~~setChanged + setToUpdateBuffers, no
  updateBuffers call~~ — INVERTED by §15: no flag ever, the rebuild coalesced into
  the pump's flush phase under the writer gates); latest-wins + bounds; config-off clears; throw latch
  kills after 5; a throwing commit NEVER escapes the consumer callback and
  NEVER reports ingest failure (pin: the report seam records zero calls).

## 4. The Xaero reflective surface (all verified public, 26.2 ≡ 1.21.1)

`WorldMapSession.getCurrentSession()`, `isUsable()`, `getMapProcessor()`.
`MapProcessor`: `isWritingPaused()`, `isWaitingForWorldUpdate()`,
`isCurrentMapLocked()`, `isCacheOnlyMode()`, `ignoreWorld(...)`, `getWorld()`,
`getCurrentWorldId()`, `isCurrentMultiworldWritable()`, `getMapWorld()`,
`getMapSaveLoad()`, `getLeafMapRegion(int, int, int, boolean)`,
`getTilePool()` (+ `MapTilePool.get(String, int, int)`),
`getOverlayManager()`, `getBlockStateShortShapeCache()`, fields
`mainStuffSync`, `mainWorld`, `renderThreadPauseSync` (whichever of these the
decompiled ladder actually reads — final list from the decompile).
`MapWorld.getCurrentDimensionId()` (+ `isCacheOnlyMode` if it lives here).
`MapSaveLoad`: `requestLoad(MapRegion, String)`, `isRegionDetectionComplete()`
(the pacing pair `getNextToLoadByViewing()`/`shouldAllowAnotherRegionToLoad()`
was in the original list — REMOVED by §14, never resolved as-built).
`MapRegion`: field `writerThreadPauseSync`, `isWritingPaused()`,
`getLoadState()`, `setLoadState(byte)` (§14's 3→4 cache-parked revival),
`isResting()`, `registerVisit()`, `setBeingWritten(boolean)`,
`canRequestReload_unsynced()`, `setAllCachePrepared(boolean)`, `getChunk(...)`.
`MapTileChunk`: ctor, `getTile`/`setTile(int, int, MapTile,
BlockStateShortShapeCache, MapProcessor)`, `setLoadState(byte)`,
`setChanged(boolean)`, `wasChanged()`, `updateBuffers(MapProcessor,
BlockTintProvider, OverlayManager, boolean, BlockStateShortShapeCache,
MapUpdateFastConfig)` (§15 — replaced `setToUpdateBuffers(boolean)`, which is
never bound now), `setHasHadTerrain()`, `getLoadState()`, `getLeafTexture()`
(+ `shouldDownloadFromPBO()`). Rebuild inputs (§15): `MapProcessor.
getWorldBlockTintProvider()` and the `MapUpdateFastConfig(MapProcessor)` ctor.
`MapTile`: ctor/pool source, `setBlock`, `getBlock`,
`setWorldInterpretationVersion(int)`, `setWrittenCave(int, int)`,
`setWrittenOnce`, `setLoaded`. `MapBlock`: ctor, `prepareForWriting(int)`,
`write(BlockState, int, int, ResourceKey, byte, boolean, boolean)`,
`addOverlay(Overlay)`. `Overlay`: ctor `(BlockState, byte, boolean)`,
`increaseOpacity(int)`. `OverlayManager.getOriginal(Overlay)`.

**Floor: Xaero World Map 1.42.0** (§16.1 — `setTile`'s 5-arg shape, the
`MapUpdateFastConfig(MapProcessor)` ctor and `getCaveModeDepthConfig()` all arrived
there; every member above is byte-identical 1.42.0 → 1.45.0).

Exact arities/owners come from the decompiled sequence at implementation time
(§2.3's decompile-is-normative rule); resolve all-or-nothing. Members ADDED at
implementation because the decompiled sequence uses them (impl review: each is
a single point of bridge-death on an Xaero update, so the list must be
complete for the next re-verification — all five verified present with
identical signatures in the 1.21.1 jar): `MapProcessor.
getMapRegionHighlightsPreparer()` + `MapRegionHighlightsPreparer.prepare(
MapRegion,int,int,boolean)` (the createdTileChunk block), `MapProcessor.
getCaveModeDepthConfig()` (setWrittenCave's live depth — mirroring the config
avoids a spurious native rewrite delta), `MapTileChunk.includeInSave()`,
`MapRegion.setChunk(int,int,MapTileChunk)`. Deliberately ABSENT:
`addToRefresh`, `BiomeColorCalculator` (Xaero's own sweeps handle
refresh/save; `updateBuffers` + its two input types were absent until §15
made the rebuild ours), and — since §14 — the ENTIRE shared load-pacing
surface: `shouldAllowAnotherRegionToLoad`/`getNextToLoadByViewing` (the
branch-region monitor deadlock class, gone structurally) AND
`setNextToLoadByViewing` (the 3-Opus fold: the loader never reads the token —
only the four native consumers' pacing does, and repointing it at a far bridge
region vetoed writer/minimap/GUI/reloader after every granted region's save;
pinned by theSharedPacingSurfaceIsNeverTouched's reflective no-handle scan).

## 5. Pixel recipe (v1 — mirrors the decompiled `loadPixel`)

Per column (16×16), scanning each (x,z) from the top present section downward
(missing sections scan as air):
- **topHeight** = Y of the first non-air block (fluids/transparents count).
- **Overlays + floor**: fluid/transparent runs above the floor become
  `Overlay(runState, light, false)` entries per contiguous run with
  `increaseOpacity(lightDampening × runLength)`, interned via
  `OverlayManager.getOriginal` — REQUIRED in v1 (fluids never become the main
  state; without overlays every ocean renders as dry floor). `state` = the
  first opaque block (the floor), `height` = its Y.
- **biome** = sampled at (x, topHeight, z), as the `MapBlock.write` signature
  wants it; overlay biome = the surface biome.
- **light** = the BLOCK-light nibble at (x, height+1, z) (sky light is
  cave-mode-only in Xaero's writer).
- **void column** = `write(AIR, worldBottomY, worldBottomY, biome-or-null, 0,
  false, false)` — this is also what ERASES stale map terrain when a resync
  serves an all-air column (ghost-terrain clears arrive air-filled).
- **glowing/cave** = false/false; `setWrittenCave(Integer.MAX_VALUE, 0)`
  (surface layer values per decompile); leave slopes `slopeUnknown` (Xaero
  self-heals at render).
Known accepted v1 gaps after the folds: sub-fluid detail fidelity vs Xaero's
exact transparency accumulation (match the decompile where cheap; eyeball in
the manual test), no slope polish.

## 6. Observability

Counters (`written`, `skipped_native`, `defer_events` — defer EVENTS: one per
BUCKET per pump for region-scoped flavors, one per entry for tile-chunk-scoped
(§14) — `dropped` = overflow+stale+expired aggregate (removal-guarded: counts
DROPS, never attempts), `commit_failures` — split out because it is the only
pre-death failure signal, unlike the benign drop flavors — `load_requests`,
`queued` gauge, `regions_waiting` gauge — waiting regions as PROBED by the last
pump, a lower bound under budget truncation; since §15 `buffer_updates` (texture
rebuilds run), `pending_updates` gauge (rebuilds owed) and `dropped_updates`
(owed rebuilds dropped: wrong dimension / unloaded or replaced tile chunk /
a region that never rested for ~60 s)) + `state` (active / unavailable
/ dead / disabled), surfaced ONLY in the client `/lss diag` conditional line
(comma-separated, the house style) — no exporter/soak schema churn. The
`unavailable` state is the RESOLVE-FAILED case (Xaero present, internals
unrecognized) and renders even though no bridge instance exists — without it a
drifted Xaero is indistinguishable from "not installed", hiding §7.1's top
risk. Harness inertness is by ABSENCE (no Xaero jar in soak/benchmark/gametest
runtimes, and no schema fields added), no property gate needed; the
lss-multi-test Prism profiles are real clients where bridge activity is correct
behavior.

## 7. Risks / accepted

1. **Internal-surface drift** (Xaero refactors internals): resolve-time shape
   validation catches renames; semantic drift lands in the throw latch →
   bridge-dead session, LODs unaffected. We deliberately do NOT hard-pin a WM
   version range (XaeroPlus does; we fail soft instead). Worst realistic case =
   map bridge silently off until we re-align — same class as Moonrise drift.
2. **Lock discipline**: we take Xaero's own monitors on the same thread in the
   native order — no new deadlock topology; the save-path race is excluded by
   the same `isWritingPaused`/`writerThreadPauseSync` discipline the native
   writer uses. Staying inside the native lifecycle also means the SP bridge's
   "cache not prepared" crash workaround is NOT needed (we `setAllCachePrepared
   (false)` at tile-chunk creation and let preUpload manage cache prep).
3. **Persistent-map writes are semi-irreversible** (Xaero saves our tiles to its
   region zips). Mitigations: the toggle, the loaded-chunk skip, the native
   writer reclaiming loaded chunks, and Xaero's own map UI can reset regions.
   Named in release notes.
4. **Login-order race**: `getCurrentSession()` null until Xaero's login hook →
   deferral (§2.7) absorbs it; multiworld confirmation likewise.
5. **1.21.x mapping namespaces**: none for reflection (§2.11); the extractor
   uses our own per-line MC types.
6. **`END_CLIENT_TICK` phase vs Xaero's frame phase**: both are the main client
   thread and the GPU work stays in Xaero's own sweep (§2.3), so no frame-phase
   assumption remains on our side. If implementation still finds a
   preUpload-order artifact, the fallback is already the design (flags only).

## 8. Follow-ups (recorded, out of scope)

1. Overlay fidelity beyond §5 (exact transparency accumulation) if the manual
   test finds visible seams at the native/LOD boundary.
2. Cave layers.
3. Voxy-store backfill for converged servers (§2.10).
4. Support-line ports — DONE 2026-08-23 (port/xaero-* branches, 2-Opus
   reviewed per line). The AS-BUILT substitutions, correcting this item's
   original recipe in two places (the backport reviews caught both): the
   light-opacity rename is `state.getLightDampening()` → **no-arg**
   `getLightBlock()` on 1.21.11 AND 1.21.10 (the 2-arg
   `getLightBlock(BlockGetter, BlockPos)` overload exists ONLY on 1.21.1,
   where the port passes `EmptyBlockGetter.INSTANCE, BlockPos.ZERO` — the
   constant-opacity approximation; the original "2-arg on all three 1.21.x"
   claim was wrong on two of the three), and the list was missing the test
   constant rename `Blocks.STAINED_GLASS.blue()/.red()` →
   `BLUE_/RED_STAINED_GLASS` (needed on EVERY support line — the flat
   constants predate 26.2's ColorCollection). The rest as originally planned:
   `getMinY()/getMaxY()+1` → `getMinBuildHeight()/getMaxBuildHeight()` on
   1.21.1 only (max already EXCLUSIVE — the +1 drops), `Identifier` →
   `ResourceLocation` on 1.21.1 and 1.21.10 (tests + the 1.21.1 menu hunk;
   1.21.11 keeps Identifier), the menu hunk dropped on 1.21.10 (its Sodium
   cut — config key + diag are that line's full control surface), and the
   1.21.1 test bench uses the line's `Registry<Biome>` section family via its
   `SectionConstruction` seam in place of `PalettedContainerFactory`.
   Still owed per line: the live check of that line's Xaero 1.45.0 jar in its
   test instance (the reflective surface was member-verified against the 26.2
   and 1.21.1 jars only; resolve failure fails soft to `state=unavailable`).

## 9. Execution order

Branch `feat/xaero-map-bridge` off main. (1) extractor + tests; (2) compat
class + stub tests against the decompiled sequence; (3) config/menu/lang/diag/
suggests + contract-test extension; (4) docs + release-notes bullet; (5) full
local gates (`:fabric:build -x runClientGameTest`, `:fabric:runGameTest`,
`:paper:test`, `:neoforge:build` — server modules untouched but the contract
suites must stay green); (6) PR → 1-Fable + 4-Opus implementation review →
fold → merge; (7) Prism test instance: clone of the lss-test-26.2 profile +
Xaero's World Map 1.45.0 (+ Minimap for the combined look) + the branch jar,
pointed at the Modrinth rig; hand to the user with a short test script (join →
map fills far beyond RD; `/lss clearcache` backfill; browse another dimension's
map → commits pause, resume on return; toggle off stops writes; no log spam).

## 10. Review record (2026-08-22, 2-Fable)

Reviewer A (evidence lens, decompile re-verification) — 4 MAJORs, all folded:
setBeingWritten set-never-clear (→ §1/§2.7); the mandatory paced requestLoad
dance incl. setBeingWritten-before-request (→ §2.7); water overlays required in
v1 (→ §5); the complete native gate ladder incl. `region.isWritingPaused` under
`writerThreadPauseSync`, `isRegionDetectionComplete`, `mainStuffSync` world +
dimension equality (→ §1/§2.7). MINORs folded: no direct updateBuffers
(setToUpdateBuffers instead), "main client thread" naming, pixel-recipe
corrections (biome at topH, block-light-only, void shape, setWrittenCave,
version-before-setTile, slopeUnknown), handle-set corrections, the
both-writers-skip window edge, tile-chunk creation details (setLoadState 2,
setAllCachePrepared false, registerVisit, PBO gate). Verified: all needed
members public; FQNs identical 26.2 ↔ 1.21.1; boundary self-heal both
directions; ~~no cache-prepared crash workaround needed~~ (FALSIFIED live — §15: the
flag-consuming sweep escapes `isResting`; the bridge now runs the rebuild itself).

Reviewer B (project lens) — 2 MAJORs, both folded: dimension write-context
pinning (resolved by mirroring the native mainStuffSync equality — wrong-
dimension writes structurally impossible, browsing pauses commits; → §2.7);
region loading load-bearing (same fold as A-MAJOR-2). MINORs folded: pump-side
stale-dimension drop replaces the dim-change clear hook; suggests literal-`"*"`
decision + contract-test extension; not-installed conditional tooltip; named
diag file (ClientCommandActions); END_CLIENT_TICK-phase fallback named.
Confirmed: scanner already excludes vanilla-view positions (skipped_loaded is a
race belt); resync all-air erases correctly; /lss reset is synergy; harness
inertness airtight; Tier 1 stub + section-construction feasibility proven; VSS
brand-neutrality; SP non-goal stated (§0).

## 11. As-built notes (2026-08-23)

- Files landed exactly per §3, plus: the consumer is (de)registered LIVE by a
  per-pump `reconcileRegistration()` — `LSSApi.hasVoxelConsumers()` drives the
  handshake's CAPABILITY_VOXEL_COLUMNS bit, so an Xaero-only install (no Voxy)
  legitimately subscribes to LOD data (that IS the feature), while a disabled or
  dead bridge releases the bit for the next join instead of downloading the disc
  for nothing.
- Handle resolution: exact-typed `findVirtual`/`findGetter` against the resolved
  Xaero classes for everything except the three ClientLevel-typed members
  (`getWorld`, `mainWorld`, `ignoreWorld`), which resolve by name+arity scan and
  are used as Objects behind the `LevelOps` seam — tests cannot construct a
  ClientLevel, and the stubs declare them as Object.
- §5 recipe refinements from the decompile: the deep-run extension charges the
  RUN state's light dampening (not the current block's); water is detected by
  fluid TYPE (`Fluids.WATER/FLOWING_WATER`), and the flower-tag invisibility
  term is contained against unbound tags — tag lookups throw both under
  fabric-loader-junit and (defensively) mid-reload.
- The pump takes no failure-count reset on a clean ladder pass — only a
  successful COMMIT resets the death latch (commit failures are contained per
  entry, so "the ladder returned" proves nothing; caught by the latch test).
- The per-pump nanos budget is an instance field so tests can neutralize
  MethodHandle warmup; the commit cap is asserted exactly.
- Tier 1: `XaeroTileExtractorTest` (11 tests) + `XaeroMapCompatTest` (16 tests)
  against real-package-name stubs under `fabric/src/test/java/xaero/map/` with a
  shared ordered event sink (`XaeroStubEvents`) pinning the cross-object commit
  sequence; `FabricModJsonContractTest` pins the literal-`"*"` suggests entry.

## 12. Implementation review record (2026-08-23, 1-Fable + 4-Opus over PR #229)

Reviewer 1 (Fable, decompile fidelity): NO MAJORs — all ~65 handle descriptors,
both hot paths, the requestLoad lock-order vs the MapRunner drain, the
flag-then-consume texture path, and the extractor recipe verified faithful
member-by-member against the shipped 1.45.0 jar. Folded MINORs: the skipped
`isNormalMapData` cross-layer branch recorded as an accepted gap (below);
requestLoad's secret main-thread-only-ness commented at the call site. NOTEs
recorded: post-cap overlay charging diverges in >10-layer stacks; run-merge is
per-state (native also merges same-particle-material states); nether portal
floors instead of overlaying (the translucency approximation); every commit
re-textures (no per-pixel equality short-circuit — bounded by the budget).

Reviewer 2 (Opus, LSS integration): MAJOR-1 — mid-session deregistration of the
sole consumer put every arriving column through the no-consumer ingest-failure
path (up to 4 re-serves per position, whole-disc churn); FIXED with the
registration lifecycle in §2 (add-only while live, no-op consumer when
disabled/dead, deregistration + latch re-arm only at session end). MAJOR-2 —
the 8-commit cap arithmetic (160 tiles/s vs 300-1000 columns/s); FIXED (§2.4:
64-cap safety ceiling, 2 ms binding; plus the full-queue extraction pre-skip).
MINORs folded: Errors swallowed in the consumer (dispatchColumn converts ANY
escape into a re-serve); the session-active offer gate (the cross-server /
singleplayer one-tile leak); compare-and-remove (the lost-fresher-tile race);
the byte gauge; the ConfigValidationTest default pin; the honest disabled init
log.

Reviewer 3 (Opus, concurrency/failure): MAJOR-1 — a REAL main-thread deadlock:
`shouldAllowAnotherRegionToLoad` (which synchronizes on its own, possibly
BRANCH, region) was called inside the leaf-region monitor while Xaero's loader
thread nests parent-then-leaf and the map GUI parks branch regions in
`nextToLoadByViewing`; FIXED by hoisting the gauge consult to once-per-pump
before any region monitor (the native shape) — pinned by an event-order test.
MAJOR-2 = the byte-gauge finding (fixed above; §2.5 arithmetic corrected).
MAJOR-3 — the death latch was process-permanent with no re-arm; FIXED:
session-scoped (re-armed at session end; genuine drift re-latches next session
within 5 commits). MINORs folded: extraction failures now feed their own
latch; the bugged-state memo (one exception per bugged state, not 256/column);
the one-shot extension guard restored (native parity — pinned by the
charge-arithmetic test); drain rotation (head-of-line starvation);
synthetic/bridge methods skipped in the name-scan. NOTEs verified clean:
memory-visibility audit, queueLock leaf-ness, extractor thread-confinement,
hostile-input handling; `registerVisit`'s wider footprint and the
reconfiguration-gap queue survival recorded as accepted.

Reviewer 4 (Opus, test rigor): stub-vs-jar descriptor audit CLEAN (zero
mismatches). MAJORs all folded: the stub `prepareForWriting` made faithful
(clears overlays — the per-pixel order pin is now real); the region save-race
gate test; holdsLock monitor-discipline checks inside the stubs; the two
latch-semantics tests (across-pumps + success-resets); the colorless rung now
actually reached (TRIPWIRE — visible but MapColor.NONE — with BARRIER kept as
the render-shape case); biome/glowing pinned end-to-end (extractor
biome-at-topH with a differing upper-section biome + commit pass-through).
MINORs folded: the five dead-knob branch tests, the surface-layer event
assert, the zero-nanos budget test, setChanged-before-setTile + ctor-args
order pins, distinct-tile latest-wins + oldest-eviction, cross-dimension
replacement, the facade/diag tests, the wiring contract test
(XaeroWiringContractTest — the SaveHookContractTest family), @AfterEach stub
hygiene, and the ranked extractor cases (waterlogged, ice, multi-run,
water-to-void, run light, boundary light, extension arithmetic).

Reviewer 5 (Opus, product surface): MAJORs folded: the release-notes
"never overwrites" claim corrected (only currently-loaded chunks are
protected; Xaero reclaims on revisit); the `unavailable` diag state made REAL
(a resolve-failed latch renders it — it was unreachable dead code); the five
added reflective members recorded in §4. MINORs folded: §8.4's port-cost
correction (three API substitutions, not verbatim); the Configuration bullet
for `enableXaeroMapBridge`; counter semantics split (`commit_failures` out of
`dropped`, `defer_events` naming); comma-separated diag (house style) + a
describe() test; README permanence/masking/re-download-cost/works-without-Voxy
wording; the NeoForge mod id VERIFIED (`xaeroworldmap` in the NeoForge jar's
neoforge.mods.toml — §2.2's open item closed). Notes accepted: no map-only
backfill verb (v-next, §8.3); single-player silence (README states MP-only);
the load reason string "lss-xaero-bridge" may surface in Xaero's own logs.

Accepted gaps (recorded, deliberate): the native `!isNormalMapData()`
cross-layer outdating branch is skipped (converted-legacy-map regions can show
stale CAVE layers where LOD tiles landed until a native rewrite); post-cap
overlay charging and >10-run stacks diverge from native; `registerVisit` fires
per deferred pass (wider visit footprint than the native player-window
writer); a server-initiated play→config reconfiguration skips the disconnect
teardown (the session-active offer gate + stale-dimension drops contain it);
`AWAITING_LOAD` entries have no expiry short of queue eviction (transient in
practice; the rotation keeps them from starving the drain).

## 13. Field-test round 1 (2026-08-23, the user's first manual test)

The map filled correctly out to the LOD distance, with ONE defect: a 1-chunk-wide
black ring at chunk radius ~11 around the join point (measured off the user's map
export — thickness exactly 16 px = 1 chunk, circular like vanilla's cylindrical
chunk loading). Root cause was an interaction of two skip rules on the same ring:
the native writer's edge rule (all 8 neighbors must be loaded — decompiled
`writeChunk`'s edgeChunk loop) never writes the outermost loaded ring, and the
bridge's v1 skip ("any loaded chunk") dropped the served join-window tiles for
that same ring. Fix: the skip is now the native edge rule itself
(`nativelyWritable` — chunk + all 8 neighbors loaded, short-circuit on the
center), counter renamed `skipped_loaded` → `skipped_native`, pinned by
`aLoadedEdgeChunkIsBridgeWritten` (a loaded-but-edge chunk commits) and the
updated fully-surrounded skip test. Healing an ALREADY-recorded ring needs a
column re-serve — `/lss clearcache` while connected (rejoins alone answer
up_to_date, no data flows).

## 14. Region-throughput round (2026-08-23, field-test round 2 — AMENDS §2.4/§2.5/§2.7/§4/§6, all amended in place)

Field observation (the user's second test): at large map radius the bridge lost
many tiles. Mechanism: a spiral ring at chunk radius r crosses ~r/4 Xaero map
regions (~75 at r=300 vs ~3 at r=12), while region loads were granted at ≤1 per
pump (20/s) — entries flooded in for ungranted regions, the queue overflowed,
and evict-oldest discarded whole awaiting-load clusters (permanent holes: the
positions are stamped and never re-served).

Decompile findings that reshaped the design (all verified in MapSaveLoad/
LeveledRegion):
- `requestLoad(region, reason)` → `addToLoad(…, prioritize=TRUE)`: front-inserts
  and lands even mid-drain (the non-prioritized path's `loadingFiles` drop guard
  does not apply).
- The loader's drain (`MapSaveLoad.run`) processes UNLIMITED cheap loads per
  MapRunner cycle (virgin regions with no file — most of an LOD backfill — and
  cache-only loads) but exits after ONE expensive file load.
- `shouldAllowAnotherRegionToLoad` is semantically a 1-IN-FLIGHT window: it
  refuses while the last-requested region's `reloadHasBeenRequested` is still
  set (cleared when the drain finishes that region). It also synchronizes on its
  own — possibly BRANCH — region (§12 reviewer 3's deadlock).

The round as-built (supersedes §2.4's rotation-over-entries and §2.7's
gauge-paced ~1/pass request; items reshaped in place by the §14.1 fold):
1. **Bucketed drain**: ONE queue-lock snapshot, pure-arithmetic grouping by
   Xaero map region (32×32 chunks — Xaero's consent granularity), the
   stale-dimension/natively-writable filters per entry INSIDE the budgeted
   commit loop, and a progress guarantee — the budget check is skipped until
   one drop or commit attempt has happened, so a broke budget can never
   live-lock (fold MAJOR: the original pre-walk ran outside the budget with a
   lock acquisition + chunk lookups per entry). Each region is probed ONCE per
   pump; a REGION-scoped not-ready outcome short-circuits its whole bucket
   (defer_events counts per bucket per pump), while TILE-CHUNK-scoped busy
   states (the 4×4 loadState, a PBO download) defer only their own entry and
   the bucket's siblings keep committing (fold MAJOR: region-wide burn expired
   whole buckets over one busy tile chunk). Rotation is at bucket granularity;
   region-scoped DEFERRED burns every bucketed entry's deferral counter (cap
   semantics preserved; drops are removal-guarded so counters count drops).
2. **MEMORYLESS outstanding-load window** (`MAX_OUTSTANDING_LOADS` 8) replaces
   the gauge: the commit probe classifies every awaiting region from Xaero's
   OWN state read in the same region monitor — `canRequestReload_unsynced()`
   false means a request is genuinely queued/loading/refreshing (IN-FLIGHT,
   occupies a slot); loadState 3 means cache-parked (needs revival); otherwise
   REQUESTABLE. The grant phase requests the largest pending clusters into the
   remaining slots. No bookkeeping set exists to leak or skew (fold MAJORs:
   the first cut intersected a remembered set with the probed-this-pump
   buckets, which both released slots for merely-unprobed regions AND leaked
   slots forever against the loader's three dead ends). Dead ends self-heal:
   failed/empty loads end in removeMapRegion and the next probe's
   getLeafMapRegion(create=true) hands back a fresh requestable region;
   cache-only loads park at loadState 3 (isResting and canRequestReload both
   false forever) and are revived via Xaero's own 3→4 transition (the
   clearRegion idiom; restored to 3 if the guards still refuse). Requests are
   ISSUED smallest-of-the-chosen-first (fold MINOR): the loader drains
   toLoad.get(0) against our priority front-inserts (LIFO), so the largest
   cluster must be the FINAL front-insert to drain first. Self-clocking:
   cheap-load (virgin) mixes refill every pump; expensive mixes hold at the
   window near the loader's real drain (~10 expensive loads/s at the 100 ms
   MapRunner cadence — the original text said ~160 grants/s off a 2× cadence
   error; the cheap-mix refill ceiling is ~8/pump at 20 pumps/s). Each request
   still runs the native dance (region monitor; setBeingWritten BEFORE
   requestLoad — state-pinned, not order-pinned). The ENTIRE shared pacing
   surface is untouched: `shouldAllowAnotherRegionToLoad`/
   `getNextToLoadByViewing` were never resolved (branch-region-monitor
   deadlock, gone structurally), and `setNextToLoadByViewing` was REMOVED by
   the fold — the loader never reads the token; it is purely the pacing input
   of the four native consumers (writer/minimap/GUI/reloader), and repointing
   it at a far bridge region vetoed all four for multi-second stretches after
   each granted region's save (`hasRemovableSourceData` post-save, refreshes).
   Left alone, the native writer's own requests front-insert AHEAD of our
   batch — the right priority. Pinned: theSharedPacingSurfaceIsNeverTouched
   (events + token-identity + reflective no-handle scan).
3. **Wider survival window**: MAX_QUEUE 2048 → 8192 entries, MAX_QUEUE_BYTES
   24 → 48 MB.
4. **Observability**: `regions_waiting=` gauge added to the diag line (waiting
   regions as PROBED by the last pump — a lower bound under budget truncation;
   the direct grant-pressure signal).

Expected field shape: grant rate stops binding for virgin-region backfills,
commits bound at 64/tick (1280/s) > delivery, `dropped` collapses toward 0;
post-clearcache re-streams over EXISTING map files stay expensive-load-bound
(the loader's ~10/s) but the 8-deep window + 8192-entry queue ride it out.

Accepted (documented) edges: budget-truncated pumps under-count in-flight
regions (unprobed buckets are unknown) and can transiently over-grant by at
most one window per pump — requests are idempotent (a queued region reads
not-requestable), so the excess is bounded and only widens the in-flight set,
never re-requests; a region-scoped DEFERRED burns deferral budget for entries
that might individually be committable next pump (blast radius accepted — the
flavors are save-transient); `regions_waiting` can read low right after a
truncated pump.

### 14.1 3-Opus review + fold record (2026-08-23, PR #231)

Three Opus reviewers (drain correctness / loader-model fidelity / test-and-doc
rigor) over the first cut. All findings folded; the decisive reshapes:
- **Live-lock class** (R2-MAJOR-1/R1-MAJOR-3): the bucketing pre-walk ran
  per-entry queue locks + chunk lookups OUTSIDE the nanos budget; a budget
  break before any bucket then intersected the window memory with an empty
  map, releasing every slot — a zero-work pump that repeated forever. Fixed:
  one-lock snapshot, filters inside the budget, the progress guarantee
  (pinned: aZeroBudgetPumpStillMakesProgressEveryPump).
- **Window memory unsound** (R2-MAJOR-2 + R1-MAJOR-1): retainAll(pending)
  mistook not-probed for landed (window degenerates to request-all, LIFO then
  inverts nearest-first) while the loader's three dead-end outcomes (fail →
  loadState 4 + removeMapRegion; empty → removeMapRegion; cache-only →
  loadState 3) leaked remembered slots forever — 8 leaks = bridge stops
  requesting for the session. Fixed by deleting the memory: verdicts from
  canRequestReload_unsynced in the commit probe's monitor (R2's key decompile
  fact: it is false exactly while reloadHasBeenRequested/recache/refreshing),
  plus the 3→4 revival for the parked dead end (pinned:
  theWindowRefillsAsLoadsLand, aRemovedDeadEndRegionIsReRequestedOnAFreshObject,
  aCacheParkedRegionIsRevivedViaTheNativeThreeToFourTransition,
  aParkedRegionWithPendingNativeWorkIsLeftAlone).
- **Native-consumer veto** (R1-MINOR-2, upgraded on inspection): our
  setNextToLoadByViewing repointing blocked all four native consumers'
  shouldAllowAnotherRegionToLoad for recurring multi-second stretches; the
  loader itself never reads the token (grep-verified: MapLimiter,
  MapFullReloader, MapWriter, GuiMap, MinimapRenderListener only). Removed.
- **Tile-chunk deferral scope** (R3-M3/R1-MINOR-4): split DEFERRED_TILE from
  region-scoped DEFERRED (pinned:
  aBusyTileChunkDefersOnlyItsOwnEntriesAndSiblingsCommit).
- **Vacuous pins** (R3-M1/M2): the largest-first test's insertion order
  equaled its sorted order (big cluster now offered LAST, and the pin is
  issued-LAST per the LIFO inversion); the setBeingWritten-before-requestLoad
  order pin matched the commit probe's event (the stub now records the
  region's beingWritten STATE inside requestLoad). Stub honesty upgrades:
  requestLoad flips canRequestReload false (models reloadHasBeenRequested),
  canRequestReload_unsynced/isResting use the decompiled formulas,
  setLoadState is monitor-enforced + event-recorded, createdRegionLoadState
  knob for detection-creates-unloaded scenarios.
- **Counter honesty** (R2-MINOR-2): removeIfCurrent returns whether it
  removed; drop counters count drops (a survived fresher tile no longer
  recounts every pump); an in-place tile replace resets the entry's deferral
  patience. Grant loop got the dead check (R2-MINOR-3).
- REFUTED by bytecode (recorded so nobody re-chases them): duplicate
  requestLoad is impossible (remove+add(0) dedupe + canRequestReload guard);
  unbounded toLoad growth is impossible; the filter walk was ~1× not 9×
  isChunkLoaded per entry (nativelyWritable short-circuits on the center).

## 15. Field-test round 3 (2026-08-23, the saver crash — AMENDS §1/§2.3/§4/§6 in place)

Three client crashes in ~1 h on the lss-test-1.21.11 instance (Xaero WM 1.45.0,
bridge `state=active`, `commit_failures=0` in every pre-crash diag), all the
same shape and all off the MapRunner thread:
`RuntimeException: Trying to save cache for a region with cache not prepared:
(…) 1_-6 L0 xaero.map.region.MapRegion@… 2 64` (then `-11_9 … 3 64`,
`-1_-7 … 2 64`) — `MapSaveLoad.run`'s cache section (1.45.0 line 1191). Three
different regions, loadStates 2/3/2: not a corrupt region, a protocol race.

**Mechanism (1.45.0 bytecode, javap — every claim below is from it):**

- The render loop (`MapProcessor.onRenderProcess`, ALL `toProcess` regions each
  frame, under the region monitor) queues a region for caching when
  `shouldCache && recacheHasBeenRequested && isAllCachePrepared && !isRefreshing`
  (`MapSaveLoad.requestCache`). The saver later pops it and THROWS if
  `!isAllCachePrepared()` — no requeue (`requestCache` has ONE call site, inside
  the region monitor).
- `MapTileChunk.updateBuffers` (the 64×64 texture rebuild; render-thread-only —
  it throws "Wrong thread!" otherwise) does `region.setAllCachePrepared(false)`
  near its START (pc 109, before the 4096-pixel pass). So any rebuild landing
  between the queueing and the pop crashes the saver. (The pop re-checks
  `shouldCache && recacheHasBeenRequested && !skipCaching` — failing THAT takes a
  cancel path; the unprepared check itself has no such exit.)
- What keeps the NATIVE writer out of that window: `MapRegion.isResting()` =
  `loadState ∉ {1,3} && !recacheHasBeenRequested`, and `writeChunk` writes (and
  rebuilds — `updateBuffers` directly on the written tile chunk at inside (3,3)
  and on the bottom neighbor, both inside the same `isResting` gate) ONLY while
  resting. A cache request implies `recacheHasBeenRequested`, so a native
  rebuild can never flip a queued region. (Its right/bottom-right neighbors are
  only FLAGGED — the same exposure, which is why this crash class exists
  upstream at all; rare natively because those flags are consumed within a
  frame for the player's own region.)
- The bridge's v1 pattern (§1, "no direct updateBuffers needed") set
  `setToUpdateBuffers(true)` on EVERY written tile chunk and left the rebuild to
  `LeafRegionTexture.preUpload`. That sweep is gated by the writer-pause
  monitors but NOT by `isResting` — and it runs under a per-frame upload
  budget, so the flag can linger for frames. Sequence: commit (flag set,
  `beingWritten`) → the saver saves the region (`recacheHasBeenRequested`,
  `shouldCache`, `beingWritten=false`) → the render loop sees the textures
  still prepared+uploaded from before, queues the cache → a later frame's sweep
  consumes our flag → `updateBuffers` → `allCachePrepared=false` → the saver pops
  → throw. At LOD scale (hundreds of regions written across many pumps, each
  saved several times while still being written) the window is hit constantly.

**Fix — the rebuild is OURS, like the native writer's, coalesced per tile chunk:**

- The flag is never set. `commitPixels` leaves the tile chunk in the native
  transient state (`changed=true`, unflagged — every non-(3,3) native chunk
  write sits there too) and records it in `pendingUpdates` (keyed by tile-chunk
  coords, last-touch ordered).
- `flushPendingUpdates` runs at the top of every pump ladder pass (before the
  commit drain; ALSO when the queue is empty and when the bridge was just
  disabled — a rebuild owed to a written tile chunk must never be dropped).
  A rebuild is DUE when its tile chunk went `UPDATE_IDLE_PUMPS` (40, ~2 s)
  without a new tile, or it is the oldest beyond `PENDING_UPDATES_SOFT_CAP`
  (256), or it stalled before. Each due rebuild re-runs the writer's own
  region gates — `writerThreadPauseSync` + `!isWritingPaused()`, the region
  monitor, `isResting()` — so it can never land inside the cache window (the
  whole point); a not-resting region keeps the entry for a later pump (a
  region that never rests for `UPDATE_MAX_STALL_PUMPS` ≈ 60 s drops it,
  counted — the texture self-heals on reload); an unloaded (loadState≠2) or
  replaced tile chunk drops it (a reload rebuilds its own textures); a wrong-
  dimension entry drops it (the pixel recipe reads the CURRENT world). Then:
  `setBeingWritten(true)` (a save may have reset it since the commit — the
  rebuilt texture must reach the region cache, and the save path is what
  requests the recache), `if (wasChanged()) { updateBuffers(mp,
  getWorldBlockTintProvider(), getOverlayManager(), false /* the writer's
  detailed-debug flag, log-only */, getBlockStateShortShapeCache(),
  new MapUpdateFastConfig(mp)); setChanged(false); }` — exactly the native
  call, args resolved once per flush like the native per-pass config snapshot.
- Budgets: the rebuild phase has its own `UPDATE_NANOS_BUDGET` (2 ms, ≥1 per
  pump) so the commit budget is untouched; at `PENDING_UPDATES_HARD_CAP` (1024)
  owed rebuilds, COMMITS pause until the flush drains (never an unbounded set).
  Cost model: a rebuild is the expensive half of a native write (4096 pixels
  through `getPixelColour`, biome blending included); per-tile direct calls
  would have been 16 rebuilds per tile chunk — coalescing over the spiral's
  ~4-tiles-per-ring-per-tile-chunk delivery lands at ~1-4, on the same order as
  what Xaero's own sweep was doing for the v1 flags, now on the pump's tick
  budget instead of Xaero's frame budget. Cost is visible: `buffer_updates` /
  `pending_updates` / `dropped_updates` in the diag line (§6).
- Handles: `MapTileChunk.updateBuffers` (6-arg) + `wasChanged()`,
  `MapProcessor.getWorldBlockTintProvider()`, `MapUpdateFastConfig(MapProcessor)`
  — all verified against the 1.21.11 1.45.0 jar (FQN-identical on every line;
  `setToUpdateBuffers` is no longer bound). The stub `updateBuffers` enforces
  the writer-pause AND region monitors and mirrors the `setAllCachePrepared(false)`
  side effect, so the test pins assert the real invariant (the flip happens only
  under the gates). Precision (review A): the native writer holds only
  `writerThreadPauseSync` at its own `updateBuffers` calls (the region monitor is
  released after the write); the bridge's additional region-monitor hold is a
  chosen tightening, safe because the jar's only two nesting orders are both
  render-thread-only (no inversion possible) and `updateBuffers` never takes a
  foreign region's monitor (`getNeighbourTileChunk(..., allowOtherRegions=false)`).
- Pins (XaeroMapCompatTest, the rebuild-phase block): flag never set + no
  rebuild at commit; rebuild after the idle window under the gates with
  `beingWritten` re-armed and the change consumed after; per-tile-chunk
  coalescing; not-resting waits, permanent stall drops; hard cap pauses
  commits and the flush-before-drain order frees them; soft cap forces only
  the overflow; unloaded/replaced drops; native-consumed change skips;
  flush after disable and on an empty queue; session end clears; dimension
  change drops; rebuild throws count toward the death latch.

Owed: the live re-test on the lss-test-1.21.11 instance (the crash reproduced
within ~20-40 min of map browsing each time; a clean hour with
`buffer_updates` climbing, `pending_updates` NOT pinned at the hard cap and
`commit_failures=0` closes it), then the port to every line (xplat-only +
stubs/tests — no line flavor points). How to read a recurrence (review A): the
bridge no longer sets the flag anywhere, but Xaero itself does — the native
writer's right/bottom-right neighbor flags, and `MapProcessor.handleRefresh`
(after a save that found the region `!isAllCachePrepared()`) flags every
terrain-bearing tile chunk of the region — and both go through the same
`isResting`-blind sweep. A recurrence therefore points at Xaero's own flags (a
region saved while a bridge rebuild was still owed makes the refresh path more
likely), not at a bridge rebuild; keep rebuild latency well inside the save
cadence (the idle window + the age ceiling are seconds; a hard-cap backlog is
what would stretch it).

### 15.1 Review fold (2026-08-23, 2-Opus over PR #237 — review B landed first)

Reviewer B (cost / handles / pins) verified the four new members against all nine
Xaero jars on the box (1.44.2 + every 1.45.0 incl. the NeoForge 1.21.1 build —
identical descriptors; 1.40.x/1.41.0 already failed the 5-arg `setTile` bind, so the
floor is unchanged), traced the soft-cap/hard-cap/flush-before-drain pins as real,
confirmed `updateBuffers` takes no cross-region monitor (`getNeighbourTileChunk`
is called with `allowOtherRegions=false`) and does not arm our own `DEFERRED_TILE`
(`setShouldDownloadFromPBO(false)`). Folded:

- **MAJOR — the death latch pinned Xaero objects**: `pump()` returned before the
  flush while `pendingUpdates` (≤1024 strong refs to regions + tile chunks, each
  leaf texture a direct buffer) stayed populated until disconnect. A dead pump now
  clears the map (main thread; the decode-side latch never touches it).
- **MAJOR — per-tile-chunk gate re-probe**: the flush re-took the writer-pause +
  region monitors for every owed entry — up to 64 per region, one verdict — the
  per-entry-probe pattern §14 removed; and `NOT_READY` counted as budget progress,
  so a not-resting prefix could burn the whole rebuild budget doing nothing while
  the hard cap paused commits. Now a not-ready verdict is memoized per region per
  flush (no monitors for its siblings) and only REMOVING outcomes count toward the
  budget's forward-progress guard (the javadoc/constant wording corrected: "the
  first removing outcome is exempt", not "one rebuild per pump").
- MINOR — session identity: a server-initiated reconfiguration skips the disconnect
  event and a `ResourceKey` is identity-stable across servers, so an old world's
  entry could pass every gate on the NEW world's objects. `PendingUpdate` now
  carries the `MapProcessor` identity + `getCurrentWorldId()`; a mismatch DROPS.
- MINOR — foreign dimension: dropping at once left a stale texture for a quick
  portal trip; entries now WAIT (NOT_READY, no probe) and drop only after the
  60 s stall window — the same accepted residual as a never-resting region.
- MINOR — age ceiling `UPDATE_MAX_DEFER_PUMPS` (160 ≈ 8 s from the FIRST commit):
  a tile chunk trickled into more often than the idle window can no longer defer
  its rebuild indefinitely; the flush walks past not-due entries instead of
  breaking (the ceiling is not a touch-order prefix; ≤1024 cheap checks).
- MINOR — accounting: `FAILED` now counts `dropped_updates` (owed and never
  rebuilt), so `buffer_updates + dropped_updates` reconciles every entry.
- MINOR — pins added: the zero-budget flush (one removing outcome per pump, resumes),
  probed-ONCE-per-flush + no progress consumed (stub `gateProbes`), the stall-clock
  reset on a re-touch under budget truncation (the only reachable path), a replaced
  tile chunk gets a fresh entry, the trickle ceiling, session identity (processor
  AND world id), the dead-latch release, and the exact `updateBuffers` argument
  identities + `debug=false` (every parameter is Object-erased behind the handle —
  a transposition would only surface live as a 5-failure latch).
- Recorded, not changed: the accepted residual that a DROPPED rebuild (60 s stall /
  long dimension absence / a previous session) leaves `changed=true` with a stale
  texture that the region cache may hold until the next native write or reload
  (Reviewer B's one unverifiable claim — the live hour on 1.21.11 is the check);
  `describeRendersTheHouseStyle` remains a token-presence pin (pre-existing).

### 15.2 Review fold — reviewer A (protocol / deadlock lens, verified pc-level)

Reviewer A re-derived §15 from the jar and VERIFIED the exclusion argument end to
end (`requestCache`'s single call site and guards, the saver's pop + throw, the
sweep's gates, the native writer's post-write block, the save section's
`isBeingWritten` throw-on-false at pc 1611 — which our set-never-clear cannot
trip — and `toSave`'s dedupe), and found NO lock-order inversion (§15's
precision note above). Refuted wording fixed in place (the region monitor, the
"ends with", the "no re-check"). Folded:

- **MAJOR — the flag was also Xaero's park guard.** `LeafRegionTexture.postUpload`
  parks a region (`setLoadState(3)`, `tileChunk.clean()` releasing its 16 tiles)
  once it is not being written, 1 s has passed since `registerVisit`, and no tile
  chunk is flagged `toUpdateBuffers`. With the flag never set, a save that reset
  `beingWritten` plus one quiet second could park a tile chunk whose rebuild was
  still owed → `loadState != 2` → dropped, texture never built (blank until a
  reload; a re-written tile chunk could even be cached pre-rewrite). The flush now
  keeps every region with an owed rebuild VISITED each pump — `registerVisit`
  once per region under the region monitor, the writer's own signal — so Xaero
  cannot park it under us; pinned (`owedRegionsAreKeptVisitedSoXaeroCannotParkThem`).
- **MAJOR — the 2 ms rebuild budget could pin commits at the hard cap**: the flush
  now borrows the commit budget once the queue is empty (commits need nothing)
  and half of it while the owed set is past the soft cap (`UPDATE_BORROW_NANOS`,
  saturating add — the seams take `Long.MAX_VALUE`, which is how the latch test
  caught the overflow); the frame-hook lever is recorded in the constant's
  javadoc for the case the live counters show `pending_updates` pinned.
- MAJOR (interpretation) — Xaero's own `handleRefresh` flags: recorded in the
  "owed" paragraph so a live recurrence is read correctly.
- MINOR — `rebuildTileChunk` checks the TILE CHUNK's loadState too (a tile-chunk-only
  teardown leaves the region's untouched); unloaded/parked/replaced drops count
  `dropped_unloaded` (own counter — a parking race must be tellable apart from the
  stall/dimension/session drops); the dead latch clears the owed set on the next
  pump's dead path (NOT inside `noteFailure` — that runs inside the flush's own
  iteration, and clearing there tore the map out from under the iterator);
  `onSessionEnd` counts the owed rebuilds it discards (accepted: the last ≤2 s of
  commits before a disconnect may reach the region cache with a stale texture —
  no best-effort flush against a world that is going away); a hard-capped drain
  pass keeps the last `regions_waiting` gauge instead of writing 0; the stub's
  "both monitors" javadoc reworded as the bridge's tightening.
- Pins added: keep-visited (once per region per pump; never for an unloaded
  region), budget borrowing (none under the soft cap with a non-empty queue, half
  past it, all of it on an empty queue), the tile-chunk teardown drop, the
  session-end count, the rebuild path never sets the flag. Reviewer A's other test
  gaps were already covered by the review-B fold (zero budget, two regions,
  stall reset, latch release).

## 16. Compatibility sweep (2026-08-23, three Opus lenses — surface × versions, Xaero runtime, MC/loader side)

Scope: the bridge on main @ 0ee4b62f (post-§15) against **132 Modrinth jars** — every
Xaero World Map release ≥1.40.0 for 26.2 / 26.1.2 / 1.21.11 / 1.21.10 / 1.21.1 on both
loaders, plus 1.38.8–1.39.12 as a below-floor probe — and pc-level reading of 1.45.0
(1.44.2 drift-checked; minimap 26.4.2; the nested xaerolib). Raw matrices/tooling:
`scratchpad/xaero-sweep/{surface,protocol,extract}/` (session-scoped). Everything
below that says "fixed" landed on `fix/xaero-sweep`.

### 16.1 The surface across versions (sweep A)

- **Floor = Xaero World Map 1.42.0 on every line, both loaders**, a clean step:
  everything ≥1.42.0 resolves all 65 bindings; everything below misses the same three
  (`MapProcessor.getCaveModeDepthConfig` added 1.42.0, `MapUpdateFastConfig(MapProcessor)`
  was a no-arg ctor, `MapTileChunk.setTile` was 4-arg). Below-floor failure is TOTAL and
  graceful (`Handles.resolve` throws inside the ctor, no consumer registers, diag
  `state=unavailable`). Two of the box's own instances (`smp`, `smp-old`: 1.40.6 /
  1.41.0) were silently bridge-off — the warn text and the README now name the floor.
- **No resolves-but-misbehaves version**: every method the bridge's correctness rests
  on (`writeChunk`'s ladder + post-write block, `isResting`, `canRequestReload_unsynced`,
  `setTile`/`updateBuffers`, `MapBlock.write`, `requestLoad`, `MapSaveLoad.run`,
  `preUpload`/`postUpload`, `onRenderProcess`) is byte-identical 1.42.0 → 1.45.0.
  `SURFACE_LAYER` (`ldc 2147483647`), loadState 2/3/4 and `CURRENT_WORLD_INTERPRETATION_
  VERSION == 1` hold across the range. NeoForge builds are class-identical to Fabric for
  all 17 bridge classes (60 same-version pairs compared); mod id `xaeroworldmap` in all
  132 (Better PVP is the minimap-side variant and still depends on it). The minimap has no
  `xaero.map` classes or mixins; it reads regions read-only under the same
  `renderThreadPauseSync`.
- **Fixed — two native ladder gates the "verbatim" mirror had omitted** (bound
  OPTIONALLY: a miss leaves the gate open and never raises the floor; diag shows
  `optional_unbound=…`; A's follow-up verified all eleven optional members resolve on
  every jar ≥1.40.0 — the config chain lives in the jarjar'd xaerolib, whose version
  moves independently per line (1.0.25 → 1.7.1; 26.2 on its own track), hence bound by
  name+arity): (1) `WorldMap.crashHandler.getCrashedBy() != null` — the native writer's
  FIRST gate (`onRender` pc 4-10); the bridge kept mutating tiles into a latched crash
  (diag `xaero_crashed=true` while it holds). (2) The user's/server's map-writing
  switches `LOAD_NEW_CHUNKS` / `UPDATE_CHUNKS`, read exactly as `onRender` reads them
  (`WorldMap.INSTANCE.getConfigs().getClientConfigManager().getEffective(...)` — the
  effective value consults the SERVER-synced config first, so this is also "the server
  forbade map writing") and applied as `writeChunk` applies them (pc 577-604): a NEW
  tile needs Load New Chunks, an EXISTING one Update Chunks; refusals count
  `skipped_settings`; both off drops the backlog (owed rebuilds still flush).
- Fixed — `MapTile.CURRENT_WORLD_INTERPRETATION_VERSION` is read reflectively (=1 on all
  132 jars; the native writer emits a literal 1 too, so the two constants merely agree
  today — a Xaero bump would now reach our tiles).
- Not done: a floor reduction to 1.40.0 (three call-site branches for no correctness
  gain). Owed: ONE live NeoForge check that `Class.forName` reaches the nested xaerolib
  classes (no `module-info`, single game layer — expected to, never run).

### 16.2 Xaero's runtime state machine (sweep B)

- MAJOR-1/2 there = the shipping 1.21.1 tag (`port/xaero-1.21.1`) lacking §15/§16 —
  `port/sodium-1.21.1` already carries §15; §16 is cherry-picked to every port branch
  with this round. Nothing new to design.
- **Fixed — a regression §16's switch gate introduced (m3)**: a tile chunk created for a
  write the switches then refused stayed installed empty (~13 KB, never terrain-marked,
  poisoned for later pumps). Now rolled back like native (`writeChunk` pc 1526-1537:
  `region.setChunk(lx, lz, null)`).
- **Fixed — the cave-layer gate (m1, also sweep C N1)**: `MapProcessor.updateCaveStart`
  returns the surface sentinel only when cave mode is off for the dimension, and the
  default cave-mode type is LAYERED — so underground (auto cave mode) and in the Nether
  the map renders a cave layer while the bridge wrote the surface layer: invisible, yet
  creating regions, front-inserting load requests, forcing saves and holding MapLimiter
  slots. The ladder now WAITS while `getCurrentCaveLayer() != SURFACE_LAYER`
  (optional bind; entries retained, owed rebuilds still flush; diag
  `cave_layer_waits`). Consequence, documented: Nether LOD terrain reaches the map only
  when the user views the Nether's surface layer; a cave-layer write is a v-next item
  (§8.2).
- **Fixed — `pendingUpdates` keyed without the dimension (m2)**: the End/Nether reuse
  the Overworld's tile-chunk coords around the origin, so a same-coords commit evicted
  the other dimension's owed rebuild uncounted. The key carries the dimension; a
  replaced tile chunk's evicted entry counts `dropped_unloaded`.
- **Fixed — light range (m7)**: `MapBlock.getParametres` packs `light << 8` UNMASKED
  beside the height bits and the loader masks on read (a one-way file corruption); the
  extractor already yields a nibble — the write site clamps as the belt.
- Recorded, not changed: (m4) the grant phase front-inserts up to 8 loads per pump via
  `requestLoad(region, cause)` = `prioritize=true`, ahead of the map's and minimap's
  own viewing loads (native grants ONE per pass); the §14 window was live-tested at 8 —
  a `prioritize=false` variant or a per-pump cap is the follow-up if map panning feels
  delayed during a fill. (m5) Bridge-touched regions are un-evictable by `MapLimiter`
  while `beingWritten` (native's own set-never-clear; bounded by the 60 s save cadence +
  the 1024 owed-rebuild cap) — a retention cost, not a leak. (m6) `topHeight` is
  persisted as one unsigned byte (a negative topY reloads wrong; native feeds the same
  field; cosmetic — comment at the extractor). (m9) the minimap's static
  `SupportXaeroWorldmap.seedsUsed` grows per rendered tile chunk with the slime overlay
  on — Xaero's.
- **Lock invariants the bridge must keep (m8, verified acyclic today)**: the MapRunner
  holds `processorThreadPauseSync → MapProcessor.this → uiSync` and THEN takes
  `renderThreadPauseSync` (across `FileChannel.tryLock` and `Thread.sleep`) — so nothing
  holding `renderThreadPauseSync` (the whole pump) may take `MapProcessor.this`,
  `uiSync` or `processorThreadPauseSync`, or call `changeWorld` / `checkForWorldUpdate`
  / `forceClean` / `setMainValues`. And **never `setBeingWritten(false)`**:
  `MapSaveLoad.run` throws on the MapRunner for a `toSave` region that reports
  `!isBeingWritten()` — straight into the crash handler.
- Verified CLEAN: null biome at every consumer (`MapBlock.write`, the texture palette,
  `BiomeColorCalculator`, `MapPixel`) — identical to native's own unknown-biome
  sentinel; `topHeight <= height` encoded + handled; void pixels → VOID_COLOR;
  `setTile` requires all 256 blocks (the bridge writes all 256); pool `get` synchronized,
  `clean` nulls slots; `prepareForWriting` resets slopes; `increaseOpacity` clamps;
  the format round trip (`writtenCaveStart` full int, depth byte, save version 7/8,
  unknown biomes survive by string, `loadRegion` catches Throwable, world-save region
  files never touched, `loadRegion` flags every tile chunk `toUpdateBuffers` so "a
  reload rebuilds its own textures" is CONFIRMED); the full monitor graph acyclic with
  the bridge's order, nothing waits/sleeps under `renderThreadPauseSync`,
  `requestLoad` cannot block, `highlightsPrepare` is main-thread-only (we are); the
  minimap never touches `MapTile`/`MapBlock`; `LSSApi.dispatchColumn` isolates the
  consumer; concurrent `PalettedContainer` reads are safe.

### 16.3 The MC-facing side (sweep C)

- **Fixed — session end on the netty thread (M1)**: Fabric's `ConnectionMixin` fires
  `ClientPlayConnectionEvents.DISCONNECT` from `channelInactive` on an abrupt close
  (timeout, reset, server death) while the main thread may be inside `pump()`;
  `onSessionEnd` cleared the main-thread-only owed-rebuild map and flipped the
  registration flag from there (a `LinkedHashMap` under concurrent mutation = CME at
  best, a corrupted chain = client hang at worst). NeoForge fires its event from
  `Minecraft.disconnect` (main thread) — the loaders are NOT thread-equivalent here.
  `onSessionEnd` now does only the lock-protected half (queue clear, latches) and sets
  `sessionEndPending`; the main-thread half settles at the top of the next pump (which
  runs on the title screen).
- **Fixed — a tile extracted across the session end (M2)**: the decode thread passed
  `offerColumn`'s gate, the session ended (gate off, queue cleared), the 50-400 µs
  extraction finished and enqueued; on the title screen the pump idles, and on the next
  join the stale-dimension filter cannot see it (`minecraft:overworld` is the same
  interned key on every server) — one tile of server A committed into server B's saved
  map. `offerPrepared` re-checks dead/enabled/session under the queue lock (the gate
  flips before the clear and both serialize on that monitor).
- N3 (the byte cap binds first — ~740 overlay-heavy ocean tiles — and the decode-thread
  pre-check only knows the count cap) — recorded, NOT changed: the tile's size is
  unknown before extraction, and past the byte cap the enqueue evicts the OLDEST entry,
  so the extraction is not wasted (a review of the attempted fix showed a base-size
  pre-check never trips once the queue sits just under the cap). The NeoForge client
  wiring is pinned (N2:
  `ClientTickEvent.Post` → pump, `LoggingOut` → disconnect, `LoggingIn` → join,
  `ModCompat.init()`); the tooltip carries the floor, the "downloaded while off is not
  backfilled — /lss clearcache" caveat and the "takes effect on the next join without
  Voxy" caveat (N6); pins for negative coordinates (Xaero's own arithmetic-shift
  convention), the per-line world-height expression (a lost `+1` silently drops the top
  section) and the void column's null biome (N7).
- Recorded, not changed: (N4) an absent section costs 16 wasted iterations per pixel
  under a lone high block (a `sectionAbsent` skip is a v-next perf item); (N5) overlay
  runs split on BlockState where native splits on particle sprite — sub-fluid cosmetic
  only. Decode-thread cost model: a land tile ≈ 30-60 µs / 5 KB, a deep-ocean tile ≈
  250-400 µs / 110 KB, serial with Voxy ingest on the single decode executor — the byte
  pre-check is the limiter that now engages.
- Verified CLEAN: the pixel recipe opcode-by-opcode against `loadPixel`/`loadPixelHelp`
  /`getSectionBasedHeight`/`isGlowing`/`isInvisible`/`shouldOverlay` (light at h+1,
  fluid branch, deep-run extension, void shape, biome at topH, the glowing cast);
  LSS never culls a block-lit section so an absent section IS light 0; synthetic air
  sections cannot shift the scan start; `getMapColor` ignores its arguments on 26.2;
  XVER fallback states map as stone; masked columns map the replacement; the consumer
  is swallow-all and cannot depress Voxy's backlog gauge; **all five ports** verified
  against their loom-resolved mojmap jars (light call per line, the 1.21.1 exclusive
  max height, `HalfTransparentBlock` family identical, no Java 22+ construct in the
  21-target trees); **NeoForge 1.21.1 will activate** (modId, all 66 members in the
  outer jar) — that half of the standing "one live check per loader" debt is closed by
  inspection; the pump runs last in `onEndClientTick` on both loaders. (The two
  sweeps counted the surface as 65 bindings / 66 members — same set, one member
  bound twice.)

### 16.4 What the sweep did NOT cover

The 1.21.11 minimap build (26.4.2/26.2 was analysed); Xaero versions below 1.40.0
(out of scope — the floor is 1.42.0, so 1.40.0-1.41.x is ALSO below it, verified
bridge-off there); live behavior of the xaerolib-nested config chain on NeoForge
(owed — the live signal is `optional_unbound` absent from `/lss diag` on both
loaders); the m4 load-priority follow-up under a real fill.

### 16.5 Review fold (2026-08-23, 2-Opus over the sweep fold)

The release-lens reviewer caught: two pins asserting opposite things about a
refused new tile (the switch test predated the rollback — fixed); the both-off
shortcut ran the owed-rebuild flush BEFORE the ladder's `mainStuffSync` dimension
gates and read the dimension off-monitor (the gates are hoisted above the settings
read; the flush uses the validated id); the settings read failed CLOSED on a
foreign value shape and a throwing read latched the bridge dead through
`noteFailure` (now: non-Boolean = ON, a throw is contained, warned once, latches
`settings_gate=broken` for the session — diag-visible); `skipped_settings`
undercounted the both-off drop (`clearQueue` returns the count); `regions_waiting`
went stale on the cave-layer wait; the M2 re-check's load-bearing call order
(`sessionGate.onDisconnect()` before `ModCompat.onDisconnect()`) is pinned; the
byte-cap test's loop is bounded with its premise asserted; the height pin is its
own per-line test; the tooltip no longer names `/lss clearcache` (the VSS rebrand
cannot rewrite a slash command); the `interpretation-version`/`cave-layer`
unbound paths are Tier-1-unreachable (recorded, live-checked via `optional_unbound`).

The protocol-lens reviewer (no MAJORs, verified the crash gate's placement, the
per-tile switch rule byte-exact against `writeChunk` pc 582-624, the cave-layer
value as the native writer's own `writingLayer` source, the session-end split, the
`PendingKey` semantics and the both-off path under the dimension gates) added:
singleplayer's `isUsingWorldSave()` is OR'd into BOTH switches like native
(`onRender` pc 679-733 — reached through the LAN hook; two more optional members);
a world-id change under a live LSS session (the reconfiguration residual: neither
loader fires its disconnect event) drops the queued tiles (`dropped_stale`) — the
owed-rebuild map already carried the id, the queue did not; the optional binds
tolerate `LinkageError` (the nested xaerolib is the one cross-mod bind); the
overlay light gets the same clamp as the block light; the crash-gate comment is
corrected (a ONE-TICK shield before `checkForCrashes` re-throws on the client
thread — not a persistent latch) and `xaero_crashed` is session-scoped; pins: the
rollback runs under the region monitor (the stub records the lock state), a
latched crash skips the owed-rebuild flush too, world-save mode opens both
switches, the world-id drop, and the 2-arg `getEffective` overload the real
manager declares (the 1-arg bind must not pick it). Recorded, not changed: the
rollback is unconditional where native keeps a tile chunk carrying an
undiscovered-structure highlight (cosmetic; re-prepared on the next native
write/reload); decode-thread extraction still runs while writing is off / a cave
layer renders (a volatile "off" flag for `offerColumn` if it shows live); the
`getCurrentCaveLayer`-unbound path stays Tier-1-unreachable.

## 17. The frame slice (2026-08-24) — rebuild cadence moved off the tick

The first live session on the §15 build (1.21.11, the v0.12.1 staging) reported
the saver crash gone and "really bad stuttering" in its place. Mechanism, from
the §16 record's own cost note: §15 moved the texture rebuilds onto the CLIENT
TICK — commit budget (2 ms) + rebuild budget (2 ms) + borrow (up to another
2 ms once the queue empties) + the one-rebuild overshoot ≈ 5-7+ ms on a single
tick, every tick, for the duration of a map fill. v0.12.0 never stuttered
because the flag path handed the same recolors to Xaero's own sweep, which runs
per FRAME in idle frame time. Review A's recorded escalation lever ("the next
lever is a per-FRAME flush hook") is now pulled — as the default, not as a
cap-pinned escalation.

The change:

- `XaeroMapCompat.renderFrame()` (static facade → `frameFlush()`): a per-frame
  entry that fast-outs on dead / session-end-pending / no-owed-rebuilds, then
  runs `frameLadder()` — the pump ladder's gate envelope verbatim down to the
  `mainStuffSync` dimension equality (a rebuild must never run under weaker
  gates than the pump's flush did). The settings and cave-layer gates sit BELOW
  the flush in the pump ladder (rebuilds are owed debt to already-committed
  tile chunks, not new writes) and are skipped for the same reason. A crashed
  Xaero returns untouched (the pump owns the `xaero_crashed` diag flag); a
  changed world id returns (the pump owns the queue drop). Shares the pump's
  containment + death latch.
- The frame flush runs `flushPendingUpdates` with `maxRebuilds =
  FRAME_MAX_REBUILDS` (1) and NO region visits: one 64×64 recolor per frame is
  Xaero's own sweep grain — at 60-120 fps that is 60-120 tile chunks/s
  (~1000-2000 coalesced tiles/s of drain), above the serve rate, while a frame
  never pays more than one recolor.
- The tick pump's flush (`tickFlush`) consumes a `frameFlushRan` marker: with a
  frame flush since the previous pump it passes `maxRebuilds = 0` —
  session-identity drops and wrong-dimension stall bookkeeping only, never
  `rebuildTileChunk`. With NO frame since the last pump (loading screens,
  hidden window, headless test JVMs — and every pre-§17 T1 test, which is why
  the §15 suite runs unchanged) it falls back to the full §15 behavior,
  budget-with-borrow included. `keepOwedRegionsVisited` stays tick-side
  unconditionally (the 1 s park guard needs only pump cadence).
- Wiring: `ClientNetGlue.onRenderFrame()` → `ModCompat.renderFrame()`. Fabric
  registers a level-render event (26.2: `LevelRenderEvents.END_MAIN` —
  `WorldRenderEvents` no longer exists there; the 1.21.x/26.1 ports use
  `WorldRenderEvents.END`; the event CLASS is line flavor and any end-of-frame
  point works — the slice renders nothing, so unlike surfaces row 15 there is
  no ordering invariant to verify on a port). NeoForge registers
  `RenderFrameEvent.Post`.
- Instruments (diag): `frame_flushes` (the scheduler is alive — absent-live it
  means the render hook is not firing and the tick fallback is doing the work),
  `rebuild_ms` (total inside `updateBuffers`), `rebuild_max_us` (worst single
  recolor).
- Pins: three behavioral tests (frame does the rebuild + the tick stands down;
  the one-per-frame cap; the gate envelope + no frame-side visits) and the
  wiring pins in `XaeroWiringContractTest` (glue forwarders + the Fabric
  registration by CALL — the event name deliberately unpinned) and
  `NeoForgeLoaderSeamContractTest` (the `RenderFrameEvent.Post` listener).

Worst-case pump during a fill returns to the §14 commit ceiling; the rebuild
cost amortizes across frames. (The original closing claim here — "below 20 fps
the drain drops and the tick fallback rescues it" — was INVERTED; §17.1 has the
corrected scheduling analysis and the throughput fixes.)

### 17.1 Review fold (2026-08-24, 2-Opus — lock-invariants lens + adversarial scheduling lens)

Both reviewers converged on one core MAJOR family, from opposite ends:

- **The tick fallback was unreachable at fps ≥ 20** (scheduling lens M1): MC runs
  client ticks INSIDE the frame loop (ticks first, then render), so every pump is
  preceded by a gate-passing frame and the boolean marker suppressed the fallback
  at every pump. Drain was fps-proportional and NON-monotonic: ~20-30 recolors/s
  in the 20-40 fps band (vs ~40-80/s pre-§17 saturated), recovering only BELOW
  20 fps where multi-tick frames leave later pumps unmarked. Consequence chain
  (invariants lens M1, the §15.2 valve disarmed): pendingUpdates → hard cap →
  commits pause (`drainEntries` breaks) → queue (8192) overflows → dropped tiles
  that LSS never re-serves (the consumer ingested them; by design no ingest
  failure is reported for map problems) — permanent session map holes.
- **No wall ceiling at high fps** (scheduling M2): one recolor per frame × 144-240
  fps could burn 30-50% of EVERY frame budget during a fill — the same complaint
  at a different grain.

The fold (one mechanism answers both, keeping the no-tick-bunching goal):

- **Interval allowance**: the §15.2 budget-with-borrow, re-armed at each pump and
  metered by MEASURED recolor nanos (`rebuildSpentSinceLastPumpNanos`). Frames
  stop recoloring once spent ≥ the allowance (fast-out, marker still armed) — the
  high-fps wall ceiling equals what the tick fallback would have paid, spread one
  recolor per frame. The `spent == 0` case always falls through: the first
  recolor of an interval is never blocked, or a degenerate zero budget would
  stand the tick down forever and void the §15 always-drains exemption (pinned).
- **Scarce-frame pressure cap**: the per-frame cap is base 1, +1 past the soft
  cap, +1 past half the hard cap — but the bumps apply ONLY while frames are
  scarce (≤1 since the last pump, i.e. fps ≲ 2× tick rate, where a 25-50 ms
  frame absorbs a few recolors and the fps × 1 drain would under-run the serve
  rate). At high fps the cap stays 1 (one per frame already outruns the serve
  rate; a multi-recolor 144 Hz frame would miss vsync). The frame flush budget is
  the allowance's REMAINDER, so a multi-rebuild frame stays inside the interval
  wall rate. The invariants lens's alternative — re-engaging the tick fallback
  past the soft cap — was REJECTED: it re-creates the reported tick bunching
  under exactly the fill pressure that triggers it.
- **Marker consumed at the top of `pump()`** into `frameActiveThisPump`
  (invariants m3): a pump that returns at a ladder gate no longer leaves a stale
  marker for a later pump.
- **Nothing-due head fast-out** (invariants m2): below the soft cap, the HEAD
  entry (oldest last-touch — touch order) is checked for idle/age/stall due-ness
  before the reflective ladder; most frames of the ~2 s coalescing window skip
  the ~22 handle invokes + 2 monitors and just arm the marker (safe: a
  nothing-due tick flush is a no-op either way). Accepted slack: a NON-head entry
  due by age or stall waits at most one idle window (~2 s) extra.
- **Probe floor** (scheduling m4, reconciled with the §15 pin
  `aNotReadyRegionIsProbedOncePerFlushAndConsumesNoProgress`, which caught the
  first cut of this fix): not-ready region probes stay budget-exempt up to
  `FLUSH_PROBE_EXEMPT_FLOOR` (8) distinct regions per flush; past it the budget
  applies even with zero removals, so a fully-not-ready set cannot walk hundreds
  of region monitors unbounded at frame cadence.
- **Instruments/pins**: `rebuild_nanos_total`/`rebuild_nanos_max` in
  `counterForTest`, the three new diag tokens in the house-style pin,
  `rebuild_max_us` session-scoped (reset at `settleSessionEnd`; the total stays
  lifetime), and the Fabric wiring pin is now a regex requiring a RENDER event
  (`RenderEvents.*.register(... onRenderFrame ...)`) — a tick-attached
  registration no longer passes (scheduling m6). New tests: fallback re-engages
  one pump after frames stop; a gated frame leaves the fallback armed; the
  pressure cap; the allowance exhaust/re-arm cycle.

Recorded, not changed:

- **The overflow-holes chain is pre-existing and stays accepted**: at the hard
  cap commits pause and queue overflow drops tiles permanently for the session
  (healed by a dirty broadcast, rejoin re-serves after cache loss, or
  `/lss reset`). The fold restores drain to ≈ the pre-§17 saturated wall rate at
  every fps, so exposure is no worse than the §15 build; the flag-era build
  (v0.12.0) had the same queue and the same drop path.
- **Client-tick catch-up bursts** (≤10 ticks in one frame after a hitch): later
  ticks of the burst see no intervening frame and each takes a full fallback
  flush — up to ~10 × budget of recolors added to an already-late frame. The
  §15 build behaved identically; gating it would starve the headless fallback.
- **A renderer stack that fires no level-render event** degrades to the tick
  fallback silently — i.e. exactly the §15/§16 build. The live detector is
  `frame_flushes` climbing in `/lss diag`; the owed live check for this round is
  explicitly: frame_flushes climbing WITH Sodium (+Iris if available) installed,
  on at least one line.
- **Iris shadow-pass double-fire** can run the frame slice twice per frame —
  each invocation is capped and allowance-metered, so the ceiling holds.

> **SUPERSEDED (2026-08-24, hybrid-scan-plan.md §12/§12.1):** the §18/§18.1
> ledger heal below is REMOVED — §12's want-set backpressure prevents the drops
> at the source (the taper), and the immediate `DropReporter` path (the one
> §18 piece that is KEPT) covers the dimension-switch/world-change residuals.
> These sections remain as the historical design record only.

## 18. The dropped-tile heal (2026-08-24, field-test round 4 — far-radius drops)

Field report (26.2 and expected on all lines, 91-minute 34 GB fill session):
`written=892459, dropped=159512` — ~15% of all tiles dropped, visibly missing
from the map, onset ~5979 blocks (~chunk ring 374) and "a lot" beyond. The §14
round widened the survival window (8192-entry queue, 8-deep load window,
priority front-inserts) but the far-radius regime still overwhelms it: a ring
at r≈380 crosses ~95 regions at once, a re-stream over EXISTING map files is
expensive-load-bound at the loader's ~10/s drain, and Xaero's limiter parks
far regions between loads — tiles pile up awaiting their regions and the
bounded queue evicts the oldest (plus ladder-ready `DEFER_CAP` expiries). The
drops were PERMANENT: the positions are stamped client-side and nothing ever
re-serves them.

Why not report at drop time: `LSSApi.reportIngestFailure` is the designed
bounded re-serve channel (`ColumnStateMap.MAX_INGEST_FAILURES` = 3, then the
position parks) — but re-serves land ~1-2 s after a report, while a far-radius
saturation phase persists for minutes; naive report-on-drop burns all three
retries into the same full queue and parks the holes anyway (with 2-3× the
serve traffic as pure waste).

The heal (`enableXaeroMapBridgeHeal`, client config, default ON; inert with
the bridge off):

- **The ledger**: dropped positions are remembered per region — a
  `DroppedLedger` of 1024 bits over the region's 32×32 chunk grid (~160 B),
  keyed by region in insertion order, guarded by `queueLock` (decode-thread
  evictions and the offerColumn full-queue pre-gate record; main-thread
  deferral expiries record via a brief lock). Capped at `LEDGER_MAX_REGIONS`
  (4096, ≈ a radius-1000 disc): beyond it the oldest region's holes stay
  permanent — the pre-§18 behavior.
- **Flush only when committable**: after the drain and before the grants
  (`healPhase`), regions that COMMITTED this pump flush their ledger sets
  first (the region is provably accepting writes); then ONE further ledger
  region per pump is probed under the commit probe's monitors — loaded (state
  2) flushes now (plus a `registerVisit` to hold the park off), requestable/
  parked joins the GRANT list ONLY when the drain has no real waiting work
  (§18.1) and stays at the ledger head so the granted load flushes within a
  few pumps (a 100-probe belt rotates a wedged head to the tail; foreign-
  dimension heads rotate through a bounded 8-entry scan and flush after the
  player returns). Reports run inline on the main thread,
  ≤ `LEDGER_FLUSH_PER_PUMP` (40 — matched to the client's ~800 col/s
  re-serve channel, §18.1) per pump; bits are cleared under the lock, reports
  fire outside it, each contained. The heal phase is skipped at the rebuild
  hard cap, past the drain's nanos budget, and — the §18.1 headline — while
  the QUEUE lacks headroom (size or bytes above half cap): the drop condition
  is a global queue condition, so a region-only proof would re-serve straight
  back into the saturated queue.
- **Stale-dimension drain drops report immediately** (no ledger): their region
  is only ever probed under the CURRENT map dimension, so a ledger entry would
  rot; the re-serve lands after the player returns to that dimension.
- **Teardowns never heal**: every `clearQueue` caller (session end, world-id
  change, the death latch, the enable toggle, both-switches-off) clears the
  ledger uncounted.
- **The idle pump keeps running**: the pump's empty-early-return now also
  requires an empty ledger — post-saturation (empty queue, no owed rebuilds)
  the heal phase is what drains the backlog (see the corrected §18.1
  arithmetic: ledger leg ~3-5 regions/s load-bound; the heal itself is
  channel-bound at ~800 col/s).
- **Bounds**: each healed position costs exactly one re-serve (one column,
  ~30 KB) when it can land; re-dropped positions re-enter the ledger and burn
  one of their 3 client retries per cycle — the client cap is the loop bound,
  and it now bounds honest attempts instead of being burned by timing.
  Client-side `ingest_failed` climbing during heavy map fills is EXPECTED with
  the heal on (each report counts there).
- **Instruments**: diag gains `dropped_overflow=`/`dropped_expired=` (the old
  `dropped=` aggregate hid which class fired — the field report's 159k was
  indistinguishable), `heal_pending=` (ledger positions owed) and
  `heal_reported=`; all in `counterForTest` + the house-style pin. Seams:
  `maxQueue`, `deferCap` (the §18 tests drive overflow/expiry without 8192
  offers).

Expected field shape on the 26.2 rig (arithmetic corrected by §18.1): during
the far fill `heal_pending` grows into the thousands while `dropped_overflow`
climbs — the heal deliberately does almost nothing yet (queue-headroom gate).
After the fill, the ledger leg drains load-bound at ~3-5 regions/s (the probe
is head-parked serial: request → in-flight pumps → flush), but the HEAL is
bound by the client's re-serve channel (~800 col/s = WANT_SET_BUDGET × 1 Hz ≈
the 25 MiB/s default cap): a 159k-drop session needs ≥3.5 min of idling near
the terrain and re-downloads ~5 GB. SUCCESS is judged by `ingest_parked` (the
client Columns diag line) staying ≈ 0 and `heal_redropped` staying low —
`heal_reported` alone cannot distinguish a completed heal from one whose every
re-delivery re-dropped and parked (§18.1 B-M2). `heal_pending` stuck high with
`load_requests` flat means the probe/grant leg regressed; with loads climbing,
Xaero's loader is refusing (limiter pressure — check the region cache
setting). One `valve=` bump at heal onset is EXPECTED (the reopen valve trips
once on ~139 reopened rings); the 4 Hz fast re-scan stays disarmed while
retry marks exist, which is part of why the heal is 1 Hz-paced.

### 18.1 Review fold (2026-08-24, 2-Opus — loop-safety/locks + field-effectiveness)

Converged MAJOR family, both reviewers: the first cut's flush was mis-rated
and mis-gated. (A-M1) "committable REGION" is not "admissible QUEUE" — during
saturation commits continue while the queue sheds, so committed-region flushes
fired ~5120 reports/s into a still-full queue, burning the 3-strike budget
the design exists to protect (fix: the queue-headroom gate above). (B-M1) the
flush ceiling 256/pump = 5120/s was 6.4× the ~800 col/s channel that consumes
reports, so the committable proof went stale in a 100k+ report backlog whose
re-deliveries landed minutes later (fix: `LEDGER_FLUSH_PER_PUMP` 40). B also
corrected the design rationale: strikes count DELIVERIES, not reports
(`onIngestFailed`'s `old == -1` absorbs duplicate reports free) — the gate's
entire value is timing the re-DELIVERY into a non-saturated queue. (A-M2) the
dimension-conflict path reported un-gated/un-capped under `queueLock` (and,
via the bulk deferral-expiry site, under a Xaero monitor), and foreign
dimensions could enter the ledger through that bulk site: foreign expiries now
take the stale route, the conflict DISCARDS counted, and `reportWholeLedger`
is deleted. (B-M2) outcome instruments added: `ingest_parked` (ColumnStateMap
cap-park count — the definitive permanent-hole signal, on the client Columns
diag line), `heal_redropped` (the re-drop probability meter: permanent loss ≈
p³), `heal_regions`, `heal_abandoned` (teardowns/cap/conflicts/kill-switch,
so a cut-short heal is visible). (B-M3) the drain/heal arithmetic above.

Minors folded: the kill switch now stops an in-progress heal (abandon,
counted); the probe requires `isResting` like the commit probe (a stuck saver
must not draw re-serves that re-expire); the heal runs under the drain's
nanos clock; per-report throws are contained (an LSS-side throw must never
feed the XAERO bridge's death latch); the ledger cap evicts the NEWEST region,
never the probe's head; ledger loads are granted only when the drain's
waiting list is empty (no §14-window/`setBeingWritten` pressure while real
work waits); the both-switches-off path now KEEPS the ledger (not a teardown
— the heal resumes when map writing is switched back on); the orphaned
Outcome javadoc moved back. Recorded, not changed: a foreign-dimension-only
ledger keeps the idle pump running the reflective ladder (~tens of µs/tick,
session-bounded, heals on return); `ColumnStateMap.ingestFailures`/
`persistentRemovals` reach disc scale under a heavy heal (~5 MB, comment
updated); the one-shot reopen-valve trip; duplicate `WaitingRegion`/in-flight
under-count slack (§14's accepted one-window-per-pump bound); the 100-probe
belt ships untested (6 lines, low risk — the bounded head scan covers the
foreign-head half). Tests: the vacuous grant assert replaced (the ledger
region's load is now asserted by count on an idle pump), plus pins for the
headroom hold, the conflict discard, the deferral-expiry split, negative
coordinates, the cap's newest-eviction, the re-drop meter, and the
mid-session kill-switch flip.

