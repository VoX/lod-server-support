# Black distant terrain: chunks saved without sky light (#382) and Distant Horizons tree crowns (#383)

Investigated 2026-11-12 on `origin/main` @ `f12fe59da` (Minecraft 26.3 line, mod 0.20.0), in the
detached worktree `<investigation worktree>`. Nothing was committed, pushed or
posted. Evidence root: `<local evidence>/`.

Status: decided 2026-11-12 (maintainer decision, reviewed); implementation on the 0.21.0 train.
The evidence named below is kept on the maintainer's machine, not in the repository.

## Decisions and review fold (2026-11-12, authoritative)

Reviewed by two agents (correctness and failure cases; decisions critic). **Where this section and
the body differ, this section wins.** The body's recommendation (estimate on the server disk path
and in the client, plus a store audit) is replaced by a client-only design.

### What ships

1. **The estimate runs in the client decode only**, before the column is handed to its consumers
   (`ClientColumnProcessor.drainColumnQueue`, after `decodeSections`, before `withImplicitSkyAbove`).
   One `VoxelColumnData` goes to every consumer, so Voxy, Xaero's World Map, JourneyMap and API
   consumers all get it. Reasons: it works against servers of any version and platform (old
   Fabric/NeoForge/Paper/Sponge servers, rows already in LOD stores); real light replaces the
   estimate by itself, because the estimate only fires when a column arrives without sky light;
   the server's disk/live byte parity pin stays intact; and it touches one `common` routine plus
   the client flavors instead of four flavors of each serializer twin, the readers and the store.
   Lost: old clients on a new server (they get it by updating).
2. **Predicate** (per column, client side): the dimension has sky light, at least one decoded
   section has blocks, **no decoded section carries a non-zero sky nibble** (an all-zero layer counts
   as none), and the decode did not truncate the section count (`decodeSections` gains a signal for
   that). Verified sound for vanilla and Moonrise/Starlight saves: a lit column with an open-sky
   cell always has a non-zero sky nibble.
3. **The light rule is vanilla's, not "straight down minus opacity"** (correctness 1, verified in
   26.3 bytecode):
   - per block state: light dampening, and for each of the six faces whether the occlusion shape
     fills the face (slabs and stairs have dampening 0 and block light by shape);
   - source pass, top-down per (x, z): 15 continues while the cell's dampening is 0 and the faces
     between it and the cell above do not close the gap (`ChunkSkyLightSources.isEdgeOccluded`);
   - below that, and sideways, vanilla's increase rule: each step costs `max(1, dampening)` and is
     blocked by closed faces. A bounded six-direction flood fill inside the 16×16 column, limited
     to the content band, so overhang undersides, the ground under canopies, cave mouths and eaves
     get light from the side. Light that would arrive across the chunk border is missing, so the
     result can be darker than vanilla near borders and is never brighter.
   - No ambient floor.
4. **Opacity lookup on the client is per palette entry, by calling the state** (correctness 3):
   client state ids are remapped by registry sync, so no static id-indexed table. Errors from a
   modded block's light method are caught per state and that state is treated as opaque. Per line:
   26.x `getLightDampening()`, 1.21.10/1.21.11 `getLightBlock()`, 1.21.1
   `getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)`.
5. **Air sections:** an estimated all-air section is added only directly next to a content section
   and for the section above the top content section (what vanilla stores), never for a whole gap
   below a sky build.
6. **No store audit, no schema bump, no automatic cache flush.** Lightless rows in a store are
   harmless once clients estimate. Terrain a player already holds stays dark until it is sent again:
   the release note and the new Re-download Terrain button carry that.
7. **Server: a diagnostics counter only.** Disk reads of a sky-dimension column that has blocks and
   no non-zero sky nibble are counted and shown as `lightless=<n>` on the disk diag line when
   non-zero, so support can see the condition. No bytes change. (Implemented with the
   undecodable-chunk notes object; see that plan.)
8. **Not covered, recorded as follow-ups:** block light in such chunks (villages and lava are dark
   at night), dimensions without sky light, and moving a client from estimated to exact light after
   vanilla relights a chunk (the dirty filter's load seed hides that save).

### Distant Horizons leaves (§12)

Ships as planned, with one correction (correctness 6): the spans that **receive** carried light are
limited to the same set that carries it (DH-opaque, not occluding, no fluid, dampening below 15),
so water under an ice sheet is not brightened. `DhSkyLightBake` stays a faithful port; the change
is in `DhColumnConverter`; `DhReceiptStore.CONVERSION_VERSION` goes 6 → 7 (receipts hold no terrain,
so nothing is lost if the DH server is offline; the terrain downloads once again). The reporter of
#383 confirmed the visible case: a better-leaves resource pack makes Voxy draw leaf faces lit from
the leaf voxel itself.

### What to measure and see before release

- Client decode cost per estimated column and ingest-queue behaviour on a fully stripped
  real-terrain world at radius 256 or more.
- With Voxy on a private display: a stripped real-terrain world (forest, cliffs and overhangs,
  ocean, a village, a cave mouth) against the same area with real light.
- DH: the 1.21.1 DH 3.3.3 rig with and without a better-leaves pack.

### Status of issue #382

The reporter's world was created on their NeoForge 1.21.1 server and pregenerated with Chunky under
C2ME and ScalableLux; it is not an upgraded world. Whether that stack saves chunks without light is
being reproduced separately; the result is recorded in the as-built section.

Release notes: "Distant terrain from worlds saved without lighting (for example after a world
upgrade) is no longer black." / "Distant Horizons servers: tree crowns no longer show black faces;
that terrain downloads once again." / "If terrain you already downloaded is black, use Re-download
Terrain once."

## 1. Verdict (investigation as reviewed)

The defect is real and reproduced twice, deterministically:

- **Unit level** — one chunk column through the production server disk serializer, the production
  client decode and the real Voxy bridge. A lit save reaches Voxy with sky 15 above the surface; the
  same chunk with its light removed reaches Voxy with sky 0 in every terrain section. Vanilla's own
  DataFixer produces exactly that chunk shape.
- **Live** — a 26.3 Fabric server and client on a private Xvfb display. 2401 saved chunks were
  stripped of light; all 2143 far columns the client received were dark, the LOD store kept 2143
  rows without a sky layer, and none of them healed during the session.

Recommended fix: **estimate sky light top-down for columns that have blocks but no sky light at
all, on the server disk path and again in the client decode** (so a new server fixes old clients
and a new client fixes old servers). Wire format unchanged. Details in §6–§8.

One thing to keep in mind when replying on the issue: **the reporter's world is not an upgraded
world** (§5.3), so this defect is confirmed in general but not yet confirmed as *their* cause.

## 2. Confirmed behaviour and evidence

### 2.1 Code path (read, then exercised)

| Step | Location (26.3 line) | Behaviour |
|---|---|---|
| Disk read gate | `xplat/.../networking/server/NbtSectionSerializer.java:356-357` | Only `Status == FULL` is required. |
| Light copy | same file `:386-387`, lit flags `:738-739` / `:812-813` | `BlockLight` / `SkyLight` copied verbatim; an absent or all-zero array is "no layer" on the wire. |
| Root keys | `SelectiveChunkNbtLoader.java:51` | Whitelist is `{"Status","sections"}`; `isLightOn` is never read. |
| Loaded chunks | `SectionSerializer.java:46-67` | Light comes from the light engine, so loaded chunks are fine. |
| Client decode | `xplat/.../networking/client/ClientColumnProcessor.java:341`, `:360-377` | Absent layer decodes to `null`; sections above the top served one are filled with sky 15. |
| Voxy bridge | `xplat/.../compat/VoxyCompat.java:203` | `null` sky becomes an explicit all-zero layer. |
| Voxy | `research/voxy-263/.../VoxelIngestService.java:60-88` (same in `research/voxy`) | A null or `isEmpty()` sky layer is sky 0. |

So a lightless column is sent as ordinary terrain with no light, and Voxy shades it with sky 0 and
block 0. The client's implicit-sky fill starts right above the top served section, so the
darkness is confined to the terrain itself.

### 2.2 Unit reproduction

Test: `fabric/src/test/java/dev/vox/lss/networking/client/LightlessChunkReproTest.java` plus two
small bridge helpers (worktree, uncommitted). Run:

```
bash tools/verify/run-gradle.sh :fabric:test --tests dev.vox.lss.networking.client.LightlessChunkReproTest -i
```

Result: 4 tests, 0 failures. Evidence in `repro-test/` (`run2.log`,
`TEST-dev.vox.lss.networking.client.LightlessChunkReproTest.xml`, copies of the three sources;
`run1.log` is the first attempt, which failed only in a test helper).

| Case | Wire | What Voxy is handed for the surface section (air above the grass) |
|---|---|---|
| Lit vanilla-shaped chunk (control) | 6330 B | sky 15 |
| Same chunk, `SkyLight`/`BlockLight`/`isLightOn` removed | 2223 B, 8 terrain sections, no light layers | **sky 0** (max sky 0 in all 8 terrain sections; 15 from the section above) |
| Lit 1.21.8-format chunk run through vanilla's real `DataFixers` (4440 → 5023) | 2223 B | **sky 0** — the fixer removed `isLightOn` and every light array, `Status` stays `minecraft:full` |
| Lit 1.21.10-format chunk through the same fixer (4556 → 5023, control) | 6330 B | sky 15 |
| Lit chunk with `isLightOn=false` | 6330 B | sky 15 (the flag is not consulted) |

Both serializer paths (transcode on and off) give the same result.

### 2.3 Live reproduction

Harness: `live/live.sh` + `live/run-phase.sh` (reuse the worktree's harness lock and owned-process
supervision; dev server and soak client from the worktree; Xvfb `:97`; superflat world; LOD
distance 24). The soak client was given a throwaway logger (`-Dlss.repro.colLight=<file>`, the only
change to tracked code, in `BenchmarkHook.java`) that records each delivered column's light as a
consumer receives it. Tools: `tools/mca_tool.py` (region scan/strip), `tools/StoreScan.java`,
`tools/analyze_col_light.py`.

1. `gen` phase (`live/gen-20261112T151020Z/`): fresh world, 2401 FULL chunks saved, all with
   `SkyLight` and `isLightOn=1` (`live/world-lit-scan.csv`). Control: all 2144 delivered columns lit.
2. Strip (`live/strip.log`): `isLightOn` and 8606 light arrays removed from 2401 chunks; nothing
   else changed (`live/world-stripped-scan.csv`: 0 chunks with sky light).
3. `live` phase (`live/live-20261112T151504Z/`), LOD store on. Player at spawn, moved 12 chunks
   east at +70 s, back at +110 s.

| Observation | Number | Source |
|---|---|---|
| Columns delivered dark (terrain section has no sky light) | **2143** — every stripped column in rings 8–24 | `col-light-analysis.txt` |
| Columns delivered lit | 787 — rings 0–8 (loaded and relit by vanilla) and freshly generated columns east of the old area | same |
| Dark column later re-delivered lit | **0** | same |
| LOD store rows with blocks but no sky layer | **2143** of 2885 | `store-scan.txt` |
| Stripped chunks that had light on disk again after the run | 693 (x −10..22, z −10..10), all `isLightOn=1` | `region-scan-after.txt`, `live/world-after-live-scan.csv` |
| Client columns still dark although their chunk is now lit on disk | 435 | derived from the two files above |
| Server dirty counters | `seeded_load=714`, `suppressed_total=1245`, `marked_total=57` | `server.jsonl` (last snapshot) |

Answers to the two side questions:

- **Does vanilla relight and re-save such chunks once loaded?** Yes. `ChunkStatusTasks.light`
  relights when `isLightOn` is absent, and `ChunkAccess.setLightCorrect` marks the chunk unsaved
  (decompiled 26.3 and 1.21.1 in `vanilla-src/`). Live: 693 chunks were re-saved with light and
  `isLightOn=1` with no block change.
- **Does LSS then re-serve them?** Not to a client that already holds the dark copy. The dirty
  filter is seeded when the chunk loads (`DirtyContentFilter.seedLoaded`), which is after vanilla's
  relight, so the following save hashes equal and is suppressed. No dirty broadcast goes out, and
  the client's timestamp stays valid. A *new* request for the position (another player, or after
  `/lss clearcache`) is served lit.

All processes were stopped (no Xvfb, Gradle or Minecraft JVM left; port 25565 free).

## 3. Vanilla save-format rules for light (checked per line)

Decompiled sources: `vanilla-src/26.3/` and `vanilla-src/1.21.1/` (Vineflower, from the loom
caches). The rules are the same on both. 1.21.10, 1.21.11, 26.1 and 26.2 were not decompiled;
for them only the data version and The End's sky-light flag were read from the jars.

- **Save** (`SerializableChunkData.copyOf`/`write` on 26.3, `ChunkSerializer.write` on 1.21.1): a
  section's `SkyLight`/`BlockLight` is written only when the light engine has a layer for it and
  `!DataLayer.isEmpty()`. `isEmpty()` is "no array and default 0". A homogeneous-15 layer is
  written as 2048 × `0xFF`. An allocated-but-zero layer is written as zeros.
- **Meaning of an absent sky layer in a correctly lit chunk**
  (`SkyLightSectionStorage.getLightValue`): above the column's top stored layer it means **15**;
  below it, it means "repeat the bottom plane of the next stored layer above", which in practice is
  **0** (deep underground). ScalableLux's loader states the same rule in a comment ("skylight in
  sections below sections with initialized skylight is zero").
- **Consequence:** an absent sky layer is ambiguous per section, but not per column. Every lit
  column that has an open-sky cell inside the world range has at least one non-zero sky nibble
  (the surface section, or the uniform-15 layer one section above it).
- `isLightOn` is written only when `chunk.isLightCorrect()`; a missing key reads as false and makes
  vanilla relight on load.
- **`ChunkDeleteLightFix`** removes `isLightOn` and every section's `BlockLight`/`SkyLight`. It is
  registered at schema **3451** (1.20) **and again at 4537** (a 1.21.9 snapshot; 1.21.8 is 4440,
  1.21.10 is 4556). The second registration matches The End gaining sky light:
  `the_end.json` has `has_skylight: false` on 1.21.1 and `true` on 1.21.10, 1.21.11, 26.1, 26.2
  and 26.3 (checked in each jar).
- **Paper (Moonrise/Starlight), 26.3 dev bundle:** lit chunks are saved with **`isLightOn: false`**
  plus `starlight.light_version`; light arrays use the same `SkyLight`/`BlockLight` keys; all-zero
  nibbles are not written. So the value of `isLightOn` cannot be used across platforms.
- **ScalableLux** (sources fetched to `scalablelux/`): writes vanilla-format arrays and restores
  the real `isLightOn`.

## 4. What distinguishes "stripped" from "legitimately no sky layer"

Proposed predicate, evaluated per column:

> the dimension has sky light, **and** the column has at least one section with blocks, **and** no
> section entry of the chunk carries a `SkyLight` array with a non-zero nibble.

On the server the last clause looks at every entry of the chunk's `sections` list, including the
light-only entries outside the world range that the serializer otherwise drops. On the client it
looks at the sections that arrived.

Why this is safe:

- A column lit by vanilla, Starlight or ScalableLux that has any open-sky cell in range always has
  a non-zero sky nibble, so it is never matched and its data is passed through untouched. Caves
  and deep stone in correctly lit chunks are therefore never brightened.
- If a correctly lit column is matched anyway, its true sky light is zero everywhere in range
  (examples: terrain filled to the world top; a sky dimension with a solid ceiling; a flat world
  whose top layer ends exactly on a section boundary on a light engine that does not store the
  all-air layer above — not checked whether Starlight stores it). A top-down estimate gives light only to cells reachable straight down from the
  top through non-opaque blocks, and vanilla lights every such cell too. So for these columns the
  estimate is also all-zero: the result equals the truth.
- Dimensions without sky light are excluded, so the H-12 class (Nether/End air lit as daylight)
  cannot return. Note The End **is** a sky dimension from 1.21.10 up.
- `isLightOn` is deliberately not used: Paper writes `false` on lit chunks, and using it would
  need new root keys in the selective parse.

Not covered: block light in stripped chunks (torches, lava) and stripped chunks in dimensions
without sky light. Those stay as they are today until vanilla loads the chunk (see §11).

## 5. Where the background needs correcting

1. **`ChunkDeleteLightFix` runs at 4537 as well as 3451.** Any world with chunks last saved before
   1.21.9 loses their light when it is optimised or force-upgraded on 1.21.10, 1.21.11 or 26.x.
   This is far more common than "a pre-1.20 world". (On the 1.21.1 line only 3451 applies.)
2. **The Voxy comment at `VoxyCompat.java:195-202` is out of date.** Current Voxy treats a null
   sky layer as 0, so the zero substitution changes nothing on current Voxy. The pin
   `nullSkyLightIsSubstitutedWithAllZeroLayer…` can stay as is.
3. **The reporter's world does not fit the upgrade story.** They wrote: world created on the
   server, pregenerated with Chunky, NeoForge 1.21.1, with C2ME and ScalableLux, Voxy 0.2.16.
   ScalableLux writes vanilla-format light, so on paper their chunks should carry light. Whether
   their region files really lack sky light is not established. Ask for one region file from a
   black area (or the `/vsslod diag` and trace they are already collecting) and run
   `tools/mca_tool.py scan` on it before telling them this fix is theirs. If their chunks do carry
   light, #382 has a second cause and needs its own reproduction on NeoForge 1.21.1 + C2ME +
   ScalableLux + Chunky.
4. **Vanilla's relight does not reach clients that already hold the dark column** (§2.3). The
   dirty filter's load seed hides it.
5. `isLightOn` is not just "never read"; it must not be read as a boolean (Paper).

## 6. Fix options compared

Requirement: works for players with no operator steps; wire unchanged.

### (a) Route lightless chunks to the load-through-vanilla path

Mechanism: the serializer returns `null` for a lightless FULL chunk, the miss escalates to a
generation ticket, vanilla loads and relights the chunk, the live serializer serves it.

- Light is exact, and the chunk is saved with light afterwards (confirmed live), so the world
  heals on disk.
- Cost: every such column becomes a chunk load with a full relight instead of a region read, and
  it is paced by the generation slots and the frontier gate. A force-upgraded world is lightless
  almost everywhere, so its LOD fill would be as slow as generating a fresh world, and the server
  would rewrite most of its region files.
- Semantics: with `generation.enabled: false` the column becomes a permanent `NOT_GENERATED`
  (a blank column, worse than a dark one). Timeouts and slot pressure are transient and would
  apply to terrain that already exists. The store backfill would skip these chunks.
- Verdict: **not as the default.** It changes the meaning of "generation" and makes the common
  case slow and heavy.

### (b) Estimate sky light on the server disk path

- Immediate, cheap (only the top one to three sections of a column need decoding, see §7.2), no
  chunk loads, independent of the generation setting. Fixes every client version, and the store
  and backfill keep working.
- Light is an estimate: straight-down only. Undersides of overhangs and cave mouths are darker
  than vanilla; nothing is brighter than vanilla except air under foliage by a level or two.
- Verdict: **do it.**

### (c) The same estimate in the client decode

- Fixes new clients on old servers and covers rows already sitting in LOD stores. Costs no
  bandwidth.
- Verdict: **do it**, with the same predicate and the same routine.

### Recommendation

**(b) + (c)**, sharing one routine. (a) is rejected as default; a later operator command could use
it to heal a world deliberately.

## 7. Design

### 7.1 Shared routine (new, `common`)

`common/src/main/java/dev/vox/lss/common/voxel/SkyLightEstimate.java`, no Minecraft dependency:

- Input: the section Y range to light (top first), and a callback that returns a cell's opacity
  (`0` passes light, `1..14` dampens, `15` blocks) for a section index and cell index, or reports
  the section as all-air.
- Per (x, z): start at 15; going down, a cell with opacity 0 keeps the value, any other cell
  reduces it by `max(1, opacity)`; the cell stores the reduced value. Water therefore fades with
  depth and leaves give 14, 13, … as in vanilla.
- Stops descending once all 256 columns are at 0 (after the surface this is almost always
  immediate).
- Output: one 2048-byte nibble array per section that received any non-zero value.

The opacity callback is the only per-platform part. Server: a lazily built `byte[]` indexed by
block-state id (26.x `state.getLightDampening()`; 1.21.1 `getLightBlock(EmptyBlockGetter, ZERO)`),
with the cell's palette index read from the section's bit storage. Client:
`section.getBlockState(x, y, z)` through the same table.

### 7.2 Server (b)

`NbtSectionSerializer.serializeChunkNbt(…)` (`:353`) gains one parameter, `estimateMissingSky`.

- `ChunkDiskReader.submitReadDirect` (`:96-121`) and `readColumnBytesSyncForBackfill` (`:145-162`)
  pass `level.dimensionType().hasSkyLight()`, captured at submit time like the mask entry and the
  section range. Four call sites.
- Every existing overload passes `false`. All goldens, the corpus and the fuzz tests keep their
  bytes.
- In the parse loop (`:378-404`), when the flag is on, all-air sections without light are kept in a
  side list instead of being dropped at `:741`/`:815`.
- After the loop: if the flag is on, the column has content, no parsed section is `litBySky`, and
  no raw section entry (any Y) has a non-zero `SkyLight`, run the estimate over the content
  sections and the kept air sections between and directly above them, replace `skyLight` and
  `litBySky` on the `ParsedSection`s, and add the air sections that received light. Otherwise
  discard the side list.
- The existing band rule (`:421-432`) then runs unchanged, so the served shape matches what the
  live path produces after a relight: content sections, plus sky-lit air within one section of the
  content band.
- All three emit paths (direct v20, masked, object) read light from `ParsedSection`, so nothing
  else changes.
- Diagnostics: a counter next to `DIRECT_V20_EMITS`, shown on the reader's diag line
  (`lightless=`) and in the exporters, and one INFO line per boot the first time it fires.

Paper twin: the same change in `paper/.../PaperNbtSectionSerializer.java` (light read at
`:236-237`) and `PaperChunkDiskReader.java:72`. Sponge and NeoForge compile the shared source.

### 7.3 Client (c)

`ClientColumnProcessor.drainColumnQueue`, between `decodeSections` (`:341`) and
`withImplicitSkyAbove` (`:376`):

- Condition: `hasSkyLight`, at least one decoded section has blocks, no decoded section has a sky
  layer, and the decode did not truncate the section count.
- Action: run the estimate from the top decoded section down; set the sky layers; add all-air
  sections with their estimated layer for absent Ys inside the served range that received light.
- Then the existing order continues: implicit sky above, then the resync air fill.
- A trace event (`col_light_est`) next to `col_light`.

`VoxyCompat.java:203` stays as it is.

### 7.4 What is deliberately left alone

- `SelectiveChunkNbtLoader.ROOT_KEY_WHITELIST` (no new root keys).
- The live serializer, the generation path, the probe path.
- Protocol version, payload layout, capability bits.

## 8. Interactions

### 8.1 LOD store

Rows deposited before the fix keep their lightless bytes and are served verbatim on the frame
rung. Today a row goes away mainly when its region file becomes newer than the row.

Proposal: a one-time **targeted audit** rather than a rebuild.

- New meta key (for example `light_audit=1`), absent on existing stores. It is **not** part of the
  core identity keys in `evaluateMeta` (`SqliteLodStore.java:684-713`), so nothing is dropped at
  boot.
- While the key is absent, the batcher walks the rows of sky dimensions in idle batches, like the
  existing 19→20 migration walk (`:101-132`): decompress, parse section headers with
  `WireSectionCursor`, delete the row if it has content and no sky layer. When the walk finishes
  it writes the key.
- Deleted rows are rebuilt by normal serves and by the backfill, now with estimated light.
- Until a row is audited, new clients are already covered by (c).
- Rejected alternative: bumping `SCHEMA_VERSION` drops every store on every server (the hosted
  test server's is 11.5 GB) for a problem most servers do not have.

### 8.2 Dirty-content filter and timestamps

- Disk serves do not seed the filter, so estimated bytes never enter it.
- When vanilla later loads a stripped chunk it relights and saves it. As measured, the load seed
  suppresses the dirty mark, so clients keep the estimated light. That is acceptable: the estimate
  is the intended result. New requests get vanilla's light from then on.
- At the next boot the store sweep drops the row (region newer than the row).
- Optional later step: skip the load seed for chunks that were loaded with light incorrect, so the
  first save marks dirty and clients move from estimated to exact light. It needs a hook at chunk
  read and re-sends each such column once; not needed for the fix.

### 8.3 Client caches

The client cache holds timestamps only; the dark terrain lives in Voxy's own storage.

- Columns received after the update are fixed on arrival, on old and new servers.
- Columns received **before** the update stay dark until they are re-served. The server answers
  `up_to_date` for them because nothing changed on disk.
- Minimum: say so in the release notes and in the issue reply — "if terrain you already downloaded
  is black, run `/lss clearcache` once".
- Optional, to make it automatic for affected players only: the first time the client estimate
  fires on a server, flush that server's cache once (per-server marker beside the cache). It does
  not fire on a server that already estimates server-side, so the release note is still needed.
  Decide at implementation; a forced refresh for everyone is not justified.

### 8.4 Protocol

Unchanged. Estimated light travels as ordinary sky layers. Old clients on a new server simply
receive lit columns. Bandwidth for affected columns rises by the sky layers of one to three
sections per column (2048 B each before compression, mostly `0xFF` and `0x00`).

### 8.5 The six lines

| Line | Branch | Fix registrations that can strip | End is a sky dimension | Notes |
|---|---|---|---|---|
| 1.21.1 | `support/mc1.21.1` | 3451 | no | Own flavor of both serializers and of `ClientColumnProcessor`; opacity call takes a getter and position; NeoForge ships (the reporter's platform). |
| 1.21.10 | `support/mc1.21.10` | 3451, 4537 | yes | Own serializer flavor. |
| 1.21.11 | `support/mc1.21.11-v0.14` | 3451, 4537 | yes | Serializers identical to 26.1 and 26.2. |
| 26.1 | `support/mc26.1-v0.14` | 3451, 4537 | yes | |
| 26.2 | `support/mc26.2` | 3451, 4537 | yes | |
| 26.3 | `main` | 3451, 4537 | yes | Extra `upgradeLegacyBlockStateEntry` flavor; no effect on the estimate (it works on resolved ids). |

Note the branch names: `support/mc1.21.11` and `support/mc26.1` without the suffix are old 0.8
branches.

Fingerprints at 0.20.0: `NbtSectionSerializer` and `PaperNbtSectionSerializer` exist in four
flavors each; `ClientColumnProcessor` in two (1.21.1 and the rest); `common` is identical
everywhere.

## 9. Tests

### 9.1 New

- `common`: `SkyLightEstimateTest` — open column, solid cap, water depth, leaves, column filled to
  the top (all zero), early stop, section boundaries.
- `fabric` and `paper` `NbtSectionSerializerTest`:
  - stripped chunk with the flag on is served with sky light on the surface section and the air
    section above it; transcode and object paths byte-identical; masked path covered.
  - lit chunk with the flag on is byte-identical to today.
  - stripped chunk with the flag off is byte-identical to today.
  - a column with zero sky everywhere (solid to the top) stays without sky layers.
  - a light-only entry outside the world range with non-zero `SkyLight` prevents the estimate.
  - the DataFixer case from `LightlessChunkReproTest` (4440 → current) as a permanent test.
- `SectionLightDefaultsTest` (both modules): nether-shaped and end-void-shaped chunks with the
  flag **off** stay exactly as pinned; add the statement that the flag is only ever on for sky
  dimensions.
- `ChunkDiskReaderTest` / `PaperDiskReaderEnvelopeTest`: the flag passed equals the level's
  `hasSkyLight`.
- `ClientColumnProcessorTest`: estimate fires for a content column without sky layers in a sky
  dimension; does not fire with any sky layer present, in a dark dimension, for a clear, or after
  truncation; order relative to the implicit-sky fill and the resync air fill.
- `SerializerParityGameTests` (and the NeoForge twin): save a chunk, strip its light on disk, read
  it through the disk path and expect the estimate; then load it and assert block bytes equal and
  the dirty filter behaves as documented.
- Store: audit walk deletes only content-without-sky rows in sky dimensions, is resumable, writes
  its key, and leaves other dimensions alone.
- Soak: a `lightless-world` scenario built from the live harness here (strip, join, assert zero
  dark columns via a client counter).

### 9.2 Existing pins and whether they move

| Pin | Effect |
|---|---|
| `SectionLightDefaultsTest` (fabric, paper) — "absent means all-zero, no dimension defaults" | Assertions unchanged (they use the dimension-free overloads). The class comment needs the one documented exception. |
| `VoxyCompatTest.nullSkyLightIsSubstitutedWithAllZeroLayer…` | Unchanged. |
| `ClientColumnProcessorTest` implicit-sky and air-fill tests (`:295-456`) | Unchanged; new step runs before them and only when no sky layer exists. |
| `NbtSectionSerializerTest` goldens, corpus, transcode-vs-object fuzz | Unchanged (flag off). |
| `SerializerParityGameTests.diskReadBytesMatchLiveBytes…` | Unchanged for lit chunks. Disk/live parity is knowingly not claimed for lightless chunks; record that in the test's comment. |
| `ChannelAccessorContractTest` (root whitelist) | Unchanged. |
| Dirty-filter seeding rules | Unchanged. |
| Store `evaluateMeta` pins | Unchanged; the audit key is outside the core set. |

## 10. Size, order and risks

Rough size on the 26.3 line: about 500 lines of production code (routine ~100, two serializers
~80 each, two readers ~10 each, client ~70, store audit ~130, diagnostics ~30) and about 700
lines of tests. Per support line the same change applied to that line's flavors, plus the
six-line re-pin. Suggested order:

1. Routine + server path + Paper twin + tests (26.3), then port.
2. Client path + tests, then port.
3. Store audit.
4. Release notes with the `/lss clearcache` line; reply on #382 after the reporter's world is
   checked (§5.3).

Risks:

- **Estimate is not vanilla light.** Overhang undersides and cave mouths in affected chunks are
  darker than vanilla. Only affected chunks; replaced by real light once vanilla loads the chunk
  and the column is requested again.
- **CPU on fully stripped worlds.** Every disk read runs the estimate. Bounded by the early stop;
  measure with the disk-read profile harness on a stripped copy before release.
- **Already-dark client data** needs `/lss clearcache` unless the optional auto-refresh is built.
- **The reporter may have a different cause.**
- **The hosted test server's world** was force-upgraded from 1.21.11 to 26.2. Chunks last saved
  before 1.21.9 would have been stripped by 4537. Not checked (that server was not touched); it
  is a natural place to see the fix working after a deploy.
- Block light and no-sky dimensions remain uncovered (§11).

## 11. Possible follow-ups (not part of the fix)

- Block-light estimate for stripped chunks (flood fill from emitting blocks inside the column);
  matters at night and near lava.
- Stripped chunks in the Nether: needs a trust signal (`isLightOn` present, or
  `starlight.light_version`) because there is no sky light to test.
- Skip the dirty filter's load seed for relit chunks (§8.2).
- Operator command that loads lightless chunks through vanilla to heal the world on disk
  (option (a), on request).

## 12. Distant Horizons adapter: dark leaves

### 12.1 What the converter does today

`xplat/.../networking/client/DhColumnConverter.java`:

- `:77-86` every voxel of a non-air span gets the span's DH sky light (`sky()`, `:118-121`;
  fluids fade with depth).
- `:76`, `:88-95`, `:124-132` an air run directly above a solid span takes that span's sky light.
- DH's own lighting treats leaves as fully opaque (`dhOpacity`, `:111-117`, pinned at 16 by
  `DhColumnConverterTest:73`). In DH's bake (`DhSkyLightBake`, third pass) a non-transparent span
  under another non-transparent span keeps sky 0. So only the top leaf span of a canopy is lit.

Prior evidence (`<local evidence, #383>/dhscan/`): 38,727 leaf spans sit
directly under a non-air span and all have sky 0; faces that take their light from such a leaf
voxel are drawn black (leaf→leaf 33–55 %, log→leaf sideways 80 %).

### 12.2 Is "carry the lit value down through leaves, minus 1 per block" sound?

Yes, with four conditions. Checked against the same stored data with
`tools/DhLeafAbove.java` (output `dh/leaf-above.txt`):

- 38,671 of the 38,727 dark leaf spans (99.9 %) sit directly under another leaf span; 56 sit under
  an opaque block (logs, grass, mushroom).
- 98 % of them are 1–3 blocks tall. A vertical carry gives their voxels 12–14.
- 23,061 of 77,591 voxels would stay at 0, and 22,937 of those lie in 284 spans taller than 14
  blocks (252 of them 40 or more). Real canopies are not that tall, so treat these as suspect data
  or a decode artefact of the scan tool and look at them before deciding on a floor value. Outside
  those spans the carry leaves 124 voxels dark.

Conditions:

1. **Do it in the converter, not in the bake.** `DhSkyLightBake` is a line-for-line port of DH and
   must stay one; the converter change then covers stored replies and real-time pushes alike.
2. **Only change spans DH left at 0 that sit directly under a light-passing span.** Everything
   that is lit today stays byte-identical. The carried value is the light of the voxel just above
   the span; each voxel down subtracts the block's dampening.
3. **Stop at occluding blocks.** A leaf span under a log gets nothing from above (56 spans here).
   If those still show, add one sideways step (neighbour column at the same height minus 1); do
   not add it up front.
4. **Sky dimensions only**, which follows automatically because the carried value starts from DH's
   own sky value.

Which blocks carry: use the block's own light dampening rather than a leaf tag — not occluding, no
fluid, dampening below 15, and DH-opaque. That set is leaves plus a few others.

### 12.3 Effect on translucent blocks that are not leaves

- Glass, panes, flowers, short grass, torches, fences: DH already treats them as transparent
  (opacity 0). Unchanged.
- Water, kelp, seagrass, waterlogged blocks: handled by the existing fluid path
  (`waterSkyLoss`), which `officialOceanFloorUses…` pins. Unchanged.
- Ice, slime and honey blocks, mangrove roots, cobweb, spawners, powder snow, copper grates:
  DH-opaque but they dampen by 1 in vanilla. They change only where such a block lies directly
  under another light-passing non-air span and DH stored 0, for example ice under leaves. Those
  voxels go from 0 to the carried value, which is what vanilla does. A single uniform span (an ice
  sheet under open sky) is one span and is unchanged.
- Tinted glass dampens by 15 and stays dark.

### 12.4 Changes and tests

- `DhColumnConverter.convert` (`:68-96`): walk each column's spans top-down once, keeping the
  carried value; four line flavors (1.21.1, 1.21.10, 1.21.11, and 26.x).
- `DhReceiptStore.CONVERSION_VERSION` 6 → 7 (`common/.../dh/DhReceiptStore.java:30`), so stored
  receipts re-download once.
- `DhColumnConverterTest`: canopy of two and three leaf states; leaves under a log stay 0; ice
  under leaves; no-sky world unchanged; every existing floor, water and push test unchanged.
- Re-run `DhScan`/`DhFaces` on the stored tiles and compare the black-face table before and after.

## 13. Worktree state

`<investigation worktree>` (detached at `f12fe59da`), uncommitted:

- modified `fabric/src/main/java/dev/vox/lss/benchmark/BenchmarkHook.java` — throwaway
  `-Dlss.repro.colLight` logger (copy: `live/BenchmarkHook-instrumentation.patch`)
- new `fabric/src/test/java/dev/vox/lss/networking/client/LightlessChunkReproTest.java`
- new `fabric/src/test/java/dev/vox/lss/networking/server/LightlessReproServerBridge.java`
- new `fabric/src/test/java/dev/vox/lss/compat/LightlessReproVoxyBridge.java`
- ignored build output, including `fabric/build/run/soak-server` and `soak-client`

## 14. As built (2026-11-12, 26.3 line)

Branch `feat/sky-light-estimate-mc26.3`. Both fixes are client-side; the wire, the protocol,
the settings and the server are untouched. What follows is where the code differs from the
sections above, and what was measured.

### 14.1 Sky-light estimate (#382)

Files: `common/.../voxel/SkyLightEstimate.java` (the rule, no Minecraft types),
`xplat/.../networking/client/ClientSkyLightEstimator.java` (the Minecraft side),
`ClientColumnProcessor.drainColumnQueue` (the call).

- **Faces have three classes, not one bit.** A cell's descriptor carries its dampening and, per
  face, whether the occlusion shape fills the face or covers part of it. A full face closes the
  gap by itself. Two partial faces that touch are decided by the caller, which asks vanilla's
  `Shapes.faceShapeOccludes` for the two states (a bottom stair beside the matching top stair
  closes; two equal slabs do not). Without that the result would be brighter than vanilla for
  such pairs. The routine's default for an undecided pair is "closed".
- **The result is exactly vanilla's for the column alone.** `ClientSkyLightEstimatorTest` lights
  the same chunk with vanilla's `LevelLightEngine` (one chunk, no neighbours; the unit-test
  classpath can run it on a `ProtoChunk` without a world) and compares every stored sky layer
  nibble for nibble: 17 hand-built shapes and 40 random chunks built from 42 block states. To get there
  the routine also copies one vanilla quirk: `ChunkSkyLightSources.findLowestSourceY` passes an
  all-air section without the face check, so a bottom slab on a section's lowest layer over an
  all-air section lets the light through.
- **The band** is every section that was sent plus one section of air above the top content
  section and one below the bottom one. Those two take part in the spread even when they lie
  outside the level (vanilla's light engine has them too); a layer is only ever added inside
  the level, and only directly above or below a content section.
- **Opacity lookup.** Vanilla's section API gives block states, not palette indices, so the
  cache is keyed by the `BlockState` object and lives for one column (not per section palette
  entry). Nothing is indexed by state id. The three calls on a state are one small interface
  (`ClientSkyLightEstimator.StateLight`, constant `VANILLA`); that constant is the whole
  per-line surface of the estimate.
- **Nothing lit, nothing changed.** A column that has blocks and no sky light but receives none
  from the estimate (solid to the level top) is dispatched exactly as it arrived and is not
  counted.
- **Spread budget.** 131,072 steps per column; past it the spread stops and the column stays
  darker. Real terrain used at most 12,887 (below).
- **Signals.** Trace event `col_light_est`:
  `"pos":[x,z],"steps":n,"capped":0|1,"sections":[[sectionY,hasOnlyAir,added],…]`. Client
  diagnostics show `Light: sky_estimated=<n>` when non-zero, and the diagnostics export carries
  `getSkyEstimatedColumns` among its counters (one new field in `ClientDiagnosticSnapshot`).
  The diagnostics line itself has no test (there is no seam for that output).
- **Truncation.** `ClientColumnProcessor.decodeColumn` returns the sections and a `truncated`
  flag; `decodeSections` is kept for the callers that only need the sections.
- **Consumers.** Checked in code: the drain builds one `VoxelColumnData` and
  `LSSApi.dispatchColumn` hands that object to every registered consumer (Voxy, Xaero's World
  Map, JourneyMap). The Distant Horizons adapter goes `DhClientSession` →
  `DhColumnConverter.convertTile` → `VoxyCompat.ingestDistantColumn` and never enters this drain.
- `VoxyCompat`: comment only.

Measured on this machine (16 cores, Java 25), tool `SkyLightEstimateTimingTool`, 1,667 FULL
chunks of real Overworld terrain (`benchmark-worlds/base`, four region files), every chunk
stripped of its light, three measured rounds after one warm-up round (5,001 samples):

| Per column | median | p90 | p99 |
|---|---|---|---|
| decode alone | 6.3 µs | 8.5 µs | 13.4 µs |
| predicate + estimate | 92.6 µs | 165.7 µs | 258.7 µs |
| predicate alone, on the same columns with their light | 0.13 µs | 0.19 µs | 0.37 µs |

All 1,667 columns were estimated, none capped; three sections per column carry an estimated
layer at the median (at most eight), one of them an added air section. Spread steps per
column: median 1,084, p99 4,405, maximum 12,887. A stack sample puts about three quarters of
the estimate's time in vanilla's `PalettedContainer.get`, one call per cell read.

What that means: a fully lightless world costs the decode thread about 0.1 ms per column, so
it can still take roughly 9,000 columns a second here, against a few hundred a second that
arrive in practice. An ordinary lit column pays 0.13 µs. If a slow client ever shows the
decode queue growing on such a world, the next step is to read palette indices in bulk from
the native section bytes (`WireSectionCursor`) instead of cell by cell; estimated at three
times faster, not done because it ties the estimate to the wire layout.

Not measured here, still to be seen: ingest-queue behaviour on a stripped real-terrain world
at radius 256 on a live client, and the picture in Voxy (below).

### 14.2 Distant Horizons tree crowns (#383)

File: `DhColumnConverter` (`carryLoss`, `carriedLight`, one changed line in `convert`).
`DhSkyLightBake` is untouched. `CONVERSION_VERSION` is 7.

- **Rule as built.** Top-down per column: a span in the carrying set (opaque to DH, not
  occluding, no fluid, dampening below 15) that DH left at sky 0 and that sits under another
  carrying-set span takes that span's light minus `max(1, dampening)` per voxel. A carrying-set
  span DH lit passes its own value down. Every other span (air, glass, fluids, stone, logs)
  keeps DH's value and resets the carry. So "the light-passing span above" is always a
  carrying-set span: a dark leaf span under air or water does not occur with light above it,
  because DH itself lights a span under a transparent or translucent one.
- **No adjacency check and no sky gate in the code.** The decoder already guarantees both: a
  converted column is COMPLETE (its spans tile the world height, overlaps are rejected), and
  in a world without sky light it zeroes every span's sky light, so no carry can start. The
  no-sky case is pinned by a test through the decoder.
- **Span order.** DH sends spans top-down; a column in any other order is walked through a
  sorted index (pinned).
- **The 284 leaf spans taller than 14 blocks are real data, not a scan artefact.** They are DH's
  hidden-block culling (`FullDataOcclusionCuller`, on by default as world compression
  `VISUALLY_EQUAL`): a datapoint whose four neighbours are opaque is removed and the datapoint
  above it is stretched over it. Under a crown the datapoint above the hidden ground is the
  lowest dark leaf, so the stored "leaf" span runs down to the next visible change or to
  bedrock (94 of the 284 end on bedrock, bottoms cluster at y 1–5; e.g. `y 1..204 oak_leaves`
  under `y 205..208 oak_leaves sky 15`). The decode is right: the scan's 62×62 columns are the
  tile's interior, the border strips are separate blobs. No floor value is used: such a span
  fades from 14 to 0 over its top 14 voxels and stays dark below, which is right for ground
  nobody sees.
- **Face scan before and after** (the earlier `DhFaces` tool on the same 66 stored tiles, with
  the carry added to its converter emulation; 38,624 spans took carried light):

  | Face (block → cell it is lit from) | drawn | black before | black after |
  |---|---|---|---|
  | leaf → leaf, side | 446,010 | 170,461 (38.2 %) | 30,190 (6.8 %) |
  | leaf → leaf, down | 141,987 | 77,557 (54.6 %) | 23,027 (16.2 %) |
  | leaf → leaf, up | 141,987 | 46,530 (32.8 %) | 22,742 (16.0 %) |
  | log → leaf, side | 32,097 | 25,579 (79.7 %) | 3,127 (9.7 %) |
  | solid → leaf, side | 148,604 | 66,794 (44.9 %) | 57,747 (38.9 %) |
  | all 25 classes | | 392,009 | 141,289 |

  What stays black is almost entirely inside the culled-ground spans (the 22,937 voxels below
  their fade) and the 50 leaf spans under logs.

### 14.3 Tests

- `common`: `SkyLightEstimateTest` (24 cases).
- `fabric`: `ClientSkyLightEstimatorTest` (9), `ClientColumnProcessorTest` (10 new),
  `LightlessChunkDeliveryTest` (4; the 2026-11-12 reproduction made permanent, with the two
  test doors `DiskSerializerTestAccess` and `VoxyBridgeTestAccess`), `DhColumnConverterTest`
  (11 new), `SkyLightEstimateTimingTool` (self-skipping).
- Every new pin was mutation-checked (break the fix, see the named test red, restore): 12
  mutations of the routine in `common`, 22 of the client and DH code in seven runs. Two
  mutations were first caught only by the random comparison, so each got a named case
  (a full face stopping the sideways spread; partial faces above each other).

### 14.4 Left open

- Voxy on a private display: a stripped real-terrain world against the same area with real
  light (forest, cliffs and overhangs, ocean, a village, a cave mouth), and the DH 3.3.3 rig
  with and without a better-leaves pack.
- Block light in lightless chunks, lightless chunks in dimensions without sky light, and
  moving a client from estimated to exact light after vanilla relights a chunk: unchanged
  follow-ups from the decisions above.
- The server's `lightless=` diagnostics counter is a separate task.
- The other five lines: `SkyLightEstimate` and its test are the same text everywhere; the rest
  needs each line's flavor (the three state calls, the section constructor seam, the test
  fixtures).
