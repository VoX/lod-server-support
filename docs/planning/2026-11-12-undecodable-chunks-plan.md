# Never serve or store air for a chunk that failed to decode

Status: decided 2026-11-12 (maintainer decision, reviewed); implemented on main (26.3) the same day, see
"As built"; the five support lines follow on the 0.21.0 train. Researched on
main at f12fe59da and the five support
lines by `git show`.

**Branch names.** The live 26.1 and 1.21.11 lines are `origin/support/mc26.1-v0.14` and
`origin/support/mc1.21.11-v0.14`; the plain `support/mc26.1` / `support/mc1.21.11` refs are stale
at v0.8.1.

## Decisions and review fold (2026-11-12, authoritative)

Reviewed by two agents (correctness and failure cases; decisions critic). **Where this section and
the body differ, this section wins.**

Decisions:
1. **Never load a chunk LSS could not decode** (correctness D1). The body maps an undecodable column
   to the existing authoritative miss, which escalates to a generation ticket when generation is on.
   That would make LSS load chunks no player visited; vanilla turns unknown blocks into air on load,
   and a loaded chunk can be saved again (the lightless-chunk reproduction saw vanilla re-save 693
   chunks it had only loaded). For a world whose mod is only temporarily missing, that is LSS
   destroying blocks in unvisited chunks. So the outcome is a **new terminal flavor, "unservable"**:
   no miss memo, no generation, `ColumnNotGenerated` to the requester whether generation is on or
   off; the stale-row delete and stamp invalidation of an authoritative miss still apply. The client
   keeps what it has and asks again next session or after a dirty broadcast. The world is never
   touched.
2. **Where the knowledge lives.** The serializer twins only detect and report: they return `null`
   (so any caller that ignores the report skips the chunk, never stores air) and set a flag plus the
   first decode message on a small notes object passed in by the reader. The counter and the latched
   WARN live once in the shared reader (`AbstractChunkDiskReader` / `DiskReaderDiagnostics`), per
   reader instance, so they reset with the service (correctness D3) and can name the dimension
   (critic 3). The backfill passes no notes and skips on `null` as today.
3. **Count cells, not palette entries** (correctness D2): an entry counts as failed only when at
   least one cell uses it, on both routes; an unused leftover palette entry must not turn a truly
   empty column into "unservable". On the object route the count happens before the empty-section
   early return (D4).
4. **Threshold** stays: at least one failed, used entry and no non-air cell left in the column.
5. **WARN wording:** names the dimension and chunk, says LSS is not sending or storing that terrain,
   and gives the actions (put the missing mod back, or let the world upgrade to this Minecraft
   version). The release note must not claim removed-mod worlds change: they still show holes where
   that mod's blocks were.
6. **All six lines**, for parity (the critic argued main only; the hunks are the same text on every
   line and the next format change can land on any line that gets a Minecraft update).
7. **Port gate:** the legacy-format fixture test on 26.3 also asserts the reader reports nothing
   undecodable, so a missed format mirror in a future port is a red test, not slow terrain.
8. Existing all-air store rows are left alone; no `DataVersion` read.

Release note: "Servers no longer send or store blank terrain for chunks they can't read; the log says why."

## As built (2026-11-12, main / 26.3)

Implemented on `feat/undecodable-mc26.3` as the fold above decided. Where the body below
still says "authoritative miss", "static counter in each serializer twin" or "generation
loads it", read this section instead.

**Rule (both serializer twins, same text).** `serializeChunkNbt` gained a core overload with a
nullable `ColumnDecodeNotes` (new, `common/.../processing`). A column is undecodable when a block
palette entry that at least one cell uses was a hard decode error (`MemoizedNbtCodec` cached
`hardError() != null`) and no in-range section has a non-air cell left. Then the serializer
returns `null` and marks the notes with the first decode message. Details:

- One helper, `firstUsedHardError(sectionTag)`, decides "used" for both routes. It reads the raw
  NBT (`block_states.palette`, `data` through `NbtOps.getLongStream`) and the palette memo only,
  so the transcode and object routes agree by construction and the walk is the same text on
  every Minecraft line (it names no container or identifier type).
- The transcoder calls it where its section WARN is, after every fallback return. The object
  route calls it when `resultOrPartial` reported a partial, before the empty-section return. A
  section that falls back is decided by the object route alone.
- Single-entry palette: every cell uses the entry. Otherwise the cell indices are walked; a
  leftover entry no cell points at never counts.
- **Difference from the body (§2.2):** the undecodable check runs BEFORE the unparseable-section
  rung, not after it, and it also fires whenever that rung would condemn the column while a cell
  uses an unknown entry, whatever else decoded (review fold, below). The plain miss may escalate
  to generation (decision 1). An unparseable section in a column with no used unknown entry is
  still today's plain miss.
- Callers without notes (the store backfill, the old overloads, the range-free test flavors) get a
  plain `null` and skip the chunk, as for an unparseable one.

**Reader (`AbstractChunkDiskReader`, shared).** New hook `NotedReadOperation` (the reader hands a
fresh `ColumnDecodeNotes` to each read); the existing `ReadOperation` overloads wrap into it and can
never report undecodable. `null` with the notes marked delivers `ChunkReadResult.notFoundUnservable`
(new record component `unservable`; `notFound = true`, `authoritativeMiss = false`, so the old flag
keeps its one meaning; every earlier constructor and factory still compiles). The reader counts it
with `DiskReaderDiagnostics.recordUndecodable()`, which bumps `not_found` and `undecodable`
together, renders `, undecodable=<n>` after the gate tokens only when non-zero, and logs one WARN
per reader instance through the overridable `warnUndecodable` (dimension, chunk, first decode
message, what LSS does, the two operator actions). `ChunkDiskReader` and `PaperChunkDiskReader`
pass the notes through in `submitReadDirect`; the backfill read passes none.

**Disposition (`OffThreadProcessor`).** `deliverDiskResult` passes `result.unservable()` to
`handleDiskNotFound`. After the unchanged ghost branch (no live pending: superseded +
miss_dropped), an unservable result adds `ColumnNotGenerated` and returns, before the memo write
and before `escalateMissToGeneration`, whether generation is available or not (an edit that
overtook the read changes the answer, never the ticket: see the review fold below). In the drain
block the stamp invalidation already runs for every
not-found flavor; the store-row delete gate became `authoritativeMiss() || unservable()`. Nothing
is deposited (deposits need `!notFound`).

**Why the row delete and stamp invalidation stay.** The store rung runs before any region read,
so a row that still exists when an unservable result drains was not served (a failed or racing
row); it must not outlive an answer that says the server cannot read the chunk. The worst case
is one re-deposit, and it can never cause a chunk load. A stale stamp would keep answering
`up_to_date` for a column the server can no longer vouch for.

**Laws.** `undecodable` is a subset of `not_found`, so the reader's completion partition is
unchanged. Soak law A5 needs no new term: the miss is answered (`responses.not_generated`), or
`miss_dropped` for a ghost, exactly like a generation-off miss; the documented multi-recipient
latent (one `not_found`, one answer per dedup recipient) is the same one. No exporter field was
added.

**Tests.** Serializer: both `NbtSectionSerializerTest`s (same text), including the port gate in
`legacyNamePropertiesPaletteEntriesSerializeIdenticallyToTheCurrentFormat`. Reader:
`AbstractChunkDiskReaderTest`, `PaperDiskReaderEnvelopeTest`, `ChunkDiskReaderTest` (the Fabric
`submitReadDirect` glue through a mocked level). Processor: `OffThreadProcessorDiskResultTest`,
`OffThreadProcessorStoreTest`, `DedupFanoutTest`, `DiagnosticsCountersTest`. Gametest:
`RegionFaultGameTests.undecodableChunkIsNeverServedAsAirNorGenerated` (a staged region file; also
asserts the chunk is never loaded and the region file stays byte-identical). It has its own
test environment (`lss:undecodable_chunk`), so its own batch, and stages the file in the
level's real region folder (`DimensionType.getStorageFolder`). The gametest tiers run with the
LOD store off, so "no store row" is pinned at the processor. Every new pin was mutation-checked
(the fix broken one piece at a time, red seen, restored).

**Review fold (2026-11-12, after the first merge into the 0.21.0 train).**
- *An edit that overtook the read.* An unservable delivery that is stale against an edit is no
  longer answered. It takes the ghost-style transient drop (superseded + miss_dropped, no wire
  answer), so the next want-set re-reads the bytes that are on disk now. Reason: a player loads
  the chunk, vanilla replaces the unknown blocks and saves while the read of the old bytes is in
  flight; `ColumnNotGenerated` off those bytes would park the position for the session, and a
  chunk load is not necessarily a dirty broadcast that revives it. Still no memo, no ticket; law
  A5 balances through `miss_dropped`. (`handleDiskNotFound` now takes `staleAgainstEdit`.)
- *An unparseable section beside used unknown blocks.* The serializer gate is
  `usedHardError != null && (unparseable > 0 || no non-air cell)`: a column with decoded blocks,
  used unknown blocks and one structurally corrupt section is undecodable, not the plain miss
  that would let generation load it. No unknown block in use: unchanged
  (`trulyUnparseableSection_condemnsTheColumnAsAuthoritativeMiss`).
- *Test gaps.* `usedEntryWalk_readsWidePalettesAtTheirOwnBitWidth` hand-builds 20-entry (5-bit)
  and 300-entry (9-bit) palettes with the unknown entry unused, and used at the last index, so a
  wrong index width in `firstUsedHardError` is caught (the earlier wide test marked every entry
  used). The gametest counts the batch-response statuses written to the mock player's channel:
  exactly one `NOT_GENERATED` for the position and no `up_to_date` (the server keeps no
  not_generated counter).

**Found on the way.**
- `RegionFaultGameTests.corruptRegionChunkResolvesAsContainedErrorAndReaderSurvives` staged its
  corrupt file under `<world>/region`. Since 26.2 the Overworld's regions live under
  `<world>/dimensions/minecraft/overworld/region`, so on 26.2 and 26.3 that file was never read
  and the test's "corrupt" read was a plain missing region. Fixed on main in a follow-up commit:
  the file is staged in the level's own region folder and the test asserts `raw_serves == 2`.
- `BacklogReplaceTest.concurrentHoldReleaseNeverRestsAStaleBatchAndBalancesAccounting` failed
  once in the full gate (round 3589 of 25000, "a STALE batch ... RESTED in the mailbox"). The
  code it tests is untouched by this change. Likely mechanism: `offerIncomingBatch` bumps the
  offer generation before it writes the mailbox, so a pump that reads the generation inside
  that window and then takes the older batch still resting there holds an old batch under the
  new generation, and both republish guards pass. Not in the flake catalog; handed to a
  separate investigation.

**Not done here.** The five support lines, the classification entries and the six-line re-pin
(`ColumnDecodeNotes.java` is a new common file).

## 0. Verdict and summary (draft as reviewed)

**Worth doing, as a small safety net. It is not a fix for a bug that a correct jar hits today.**

- The 0.20.0 guards remove the known trigger: Paper/Folia refuse a jar built for another Minecraft
  version, Fabric/NeoForge/Sponge loaders already did, and a store written by another build family
  is dropped (`build_mc`).
- What they do not cover: a **correct** jar that meets a chunk format it cannot read. That is
  exactly what a new Minecraft line can bring. 26.3 renamed the block-state keys
  (`Name`/`Properties` → `id`/`properties`) and the 26.3 port had to mirror vanilla's fix by hand
  (`NbtSectionSerializer.upgradeLegacyBlockStateEntry`). Had that mirror been missed, every
  upgraded 26.3 world on every loader would have served old chunks as empty and stored them, with
  one throttled WARN as the only sign. The next format change has the same exposure.
- Today the code turns "every block entry failed" into an ordinary all-air answer. Nothing counts
  it, the store keeps it, and clients are told the column is empty.
- The change: when a column decodes to nothing **and** at least one of its block entries failed to
  decode, treat it as an authoritative miss, the existing outcome for a chunk that exists but
  cannot be served. That path loads the chunk through Minecraft (which runs the data fixers) when
  generation is on, or answers "not available" when it is off. Never air, never a store row.
- Columns with some unknown blocks beside decoded ones keep today's behaviour (unknown blocks show
  as air, as in vanilla). That pinned decision is untouched.
- Size: about 35 lines in each serializer twin, a few in each reader, tests. No wire change.

## 1. Where palette entries are resolved today

Server disk path (Fabric/NeoForge/Sponge share `xplat`; Paper/Folia have a textual twin):

- `ChunkDiskReader.submitReadDirect` / `readColumnBytesSyncForBackfill` →
  `NbtSectionSerializer.readAndSerializeSections` (raw-record flavor with
  `SelectiveChunkNbtLoader`, or the `ChunkNbtRead` flavor for Moonrise/foreground) →
  `serializeChunkNbt(chunkNbt, registryAccess, maskEntry, minSectionY, maxSectionY, useNbtTranscode)`.
  Paper: `PaperChunkDiskReader` → `PaperNbtSectionSerializer` (same structure).
- Per section, two routes resolve block palette entries, both through one memo,
  `BlockCodecHolder.ELEMENT` (`MemoizedNbtCodec<BlockState>` over `BlockState.CODEC`, keyed by the
  raw entry tag; on 26.3 the miss path first applies `upgradeLegacyBlockStateEntry`):
  - **transcode** (default): `transcodeSection` calls `ELEMENT.resolve(palette.get(i))` and reads
    `globalId`, the air/fluid meta and `hardError()`;
  - **object path** (fallback shapes and `useNbtTranscode=false`): `parseSection` runs
    `PalettedContainer.codecRW(ELEMENT, …)` and takes `resultOrPartial`.
- `MemoizedNbtCodec.toCached`: a decode with a value or a partial is clean. A decode with neither
  is a **hard error**: the entry gets the codec default's meta (**air**) and `hardError` carries the
  message.

What an unknown name does today (a removed mod's block, a block from a newer version, a rename the
data fixer would have handled, or a key the codec rejects):
- The entry becomes air **in place**; indices do not shift; the section is kept. This is deliberate
  (R2-1, "vanilla leniency") and pinned by
  `renamedBlockPaletteEntry_sectionKeptWithAirSubstitution`,
  `malformedBlockStates_codecParseError_sectionSkippedSiblingsServe`,
  `renamedPaletteEntryTranscodesIdenticallyToTheObjectPath`,
  `golden_duplicateAirLenientPalette` (and the Paper twins of each).
- A section whose cells are then all air is dropped unless it carries light; sky-lit air is dropped
  when the column has no content.
- **If every entry of every section fails**, nothing is left: `serializeChunkNbt` returns
  `new byte[0]`. `AbstractChunkDiskReader.gatedReadAndDeliver` books it as `all_air`, and
  `OffThreadProcessor` then (a) stamps the timestamp cache, (b) deposits an all-air row in the LOD
  store, (c) sends a client that claims data an authoritative clearing 0-section column (wiping its
  cached terrain) or a first-time client `up_to_date`. With block light present the column is sent
  as lit air sections instead; same harm.
- `StoreBackfill` deposits the same `byte[0]` rows during its walk.
- A section with no partial at all (structural corruption) already makes the whole column `null`,
  an authoritative miss (`trulyUnparseableSection_condemnsTheColumnAsAuthoritativeMiss`).

Counter or log today: **no counter.** One throttled WARN per 60 s,
`Section block_states parse error (Y=…): <message> (+N more suppressed)`, from both routes
(`PARSE_WARN_THROTTLE`). In `/lsslod diag` these columns are indistinguishable: they count under
`all_air=` (or as successful reads when lit).

Not gates, by standing decision: `DataVersion` is unread and must never become one
(`SelectiveChunkNbtLoader` javadoc: gating on it would turn every upgraded world into a generation
storm). This plan does not read it.

## 2. Outcome and rule

### 2.1 Which existing outcome
The response taxonomy (server-owned generation):
- **Authoritative miss** (`serializeChunkNbt` returns `null` → `ChunkReadResult.notFoundAuthoritative`):
  "storage answered: no servable chunk". Already covers a non-FULL chunk, FULL with no sections and
  an unparseable section. Downstream: stamps for the column are invalidated, any store row is
  deleted, nothing is deposited; with generation available the miss is memoized (30 s) and
  escalates to a generation ticket, which loads the chunk through vanilla (data fixers, vanilla's
  own leniency) and serves it from the live serializer; with generation off the client gets
  `ColumnNotGenerated`, the one permanent (per-session) answer.
- **Error triage** (`notFoundFromError`): for timeouts and IO failures. Never memoized, counted in
  `disk.errors`, logged at ERROR. Wrong here: the condition is deterministic for the bytes on disk,
  not transient.
- **Transient drop** (saturated/gated): wrong for the same reason; the client would re-ask every
  second and get the same decode.

**Map an undecodable column to the authoritative miss.** Reasons:
- It is the outcome already defined for "the chunk exists but cannot serve LOD data", and the
  unparseable-section case next to it has the same disposition and tests.
- Vanilla becomes the judge. If the chunk is in an older format, vanilla's load fixes it and the
  player gets the real terrain. If its blocks really are gone (a removed mod), vanilla loads air
  and the live path serves air: correct, and now on vanilla's word.
- Request ownership, receipt release, dedup fan-out, the miss memo and old-session rejection are
  all reused as they are. No new result flavor, no new wire answer.
- With generation off, "not available" is the honest answer. It does not clear a client's cached
  terrain and does not stamp the column as satisfied; the client asks again next session. This is
  not a transient moved into the permanent set: the same bytes decode the same way every time.

### 2.2 The rule
In `serializeChunkNbt`, after the unparseable-section check and before the empty/all-air return:

> If at least one block palette entry in the column's in-range sections was a hard decode error,
> and no in-range section has a non-air cell left, return `null`.

- "Hard decode error" is exactly `MemoizedNbtCodec.Cached.hardError() != null` on both routes, so
  the transcode and object routes agree (the transcode-versus-object parity tests compare them).
  The transcode route already counts `hardErrors` per section; the object route counts them, only
  when its `resultOrPartial` callback fired, by resolving the section's palette entries through
  the memo (hits, no decode).
- "No non-air cell left" is the existing `noContent` computation (no parsed section with
  `nonEmptyCount != 0`).
- Light does not matter: an all-failed column with block light is caught too.

Why this threshold and not another:

| Candidate | Result | Verdict |
| --- | --- | --- |
| **Whole column: ≥1 failed entry and nothing decoded** | Fires only when LSS would otherwise send or store a column made purely of substituted air | **Chosen** |
| Any unknown entry | Every chunk of an upgraded world with one renamed block (for example old `minecraft:grass`) goes through generation tickets; with generation off, whole columns vanish instead of showing small holes | Rejected: undoes pinned decision R2-1, heavy load |
| Any section where every entry failed | A floating structure made of a removed mod's blocks above normal terrain would condemn a column that vanilla shows fine | Rejected: false positives, no extra safety (a chunk is saved in one format, so a format mismatch fails every section) |
| A percentage of cells | The object route cannot count failed cells; a removed mod can exceed any percentage legitimately | Rejected |
| `DataVersion` compare | Banned by standing decision | Rejected |

What players get:
- A modded world with one mod removed: columns with any surviving block serve as now, with air
  where the mod's blocks were, as vanilla shows them.
- A wholesale mismatch (a format this build cannot read): no column is sent as empty. With
  generation on, terrain arrives at generation speed and is correct; with it off, those areas stay
  unloaded for LODs and the log says why. Slow and right instead of fast, wrong and sticky.
- A column that held only a removed mod's blocks: one vanilla load, then served (and stored) as
  empty on vanilla's word. With generation off it is re-read once per session per client.

## 3. Interactions

- **LOD store.** A `null` result is never deposited (`OffThreadProcessor` deposits only on
  `!notFound`), and `StoreBackfill` skips `null` (counts a skip, still done-marks the region, no
  error). The authoritative miss also deletes any row for that column. **Rows already in a store
  are not touched:** a store hit is served before any disk decode, and a content scan cannot tell
  substituted air from real air (End void). 0.20.0's `build_mc` drop remains the heal for the known
  cause. No one-time purge of all-air rows is proposed: there is no evidence of poisoned stores
  outside the Paper incident, and the purge would also throw away every legitimate void row.
- **Clients that already hold an empty column.** Not healed by this change (as in 0.20.0 §3.3): the
  freshness rungs answer `up_to_date` from the timestamp cache and region headers. The remedy stays
  `/lss clearcache`, which plan C makes a button.
- **Timestamp cache / miss memo.** Not-found invalidates the column's stamps and (generation on)
  writes the memo, so the next declarations skip the pointless re-read and go straight to the
  generation ladder; every generation outcome clears the memo. Unchanged code.
- **Dirty-content filter (Fabric).** Seeded only by generation serves and chunk-load events on live
  chunks, never by disk-decoded bytes. The generation serve of a formerly undecodable chunk seeds
  it like any other. No change.
- **Region summary and header freshness.** They read region headers and only vouch for columns a
  client already holds with a stamp. An undecodable column is never sent or stamped, so there is
  nothing for them to vouch for. No change.
- **Generation load.** A wholesale case sends every such chunk through the generation ladder, which
  is already bounded (slot caps, order-spread gate, pacing) and behaves like filling a fresh world.
  Vanilla does not necessarily re-save a chunk it only loaded, so on a server without the store a
  new client can trigger the same load again later; with the store (default) the generation serve
  is deposited and later asks hit the store.
- **Diagnostics.** New counter `undecodable=` on the disk line of `/lsslod diag`, rendered only
  when non-zero (the conditional-token precedent, so existing diag goldens do not move). Following
  `SELECTIVE_FALLBACKS` / `DIRECT_V20_EMITS`, it is a static `AtomicLong` in each serializer twin,
  appended by `ChunkDiskReader.getDiagnostics()` and a new small override in `PaperChunkDiskReader`.
  The column also counts in `not_found=`, which keeps the reader's at-rest identity and soak law
  A5 as they are. Not added to the soak exporters (no law needs it).
- **Log.** One WARN per server start (an `AtomicBoolean` latch), from the serializer: the chunk
  coordinates (passed in by `readAndSerializeSections`; the selective loader keeps only `Status`
  and `sections`, and `chunkNbt` may not gain root reads, see the whitelist pin), the first decode
  message, what LSS does instead, the likely cause (saved by another Minecraft version or mod set)
  and the diag token. The existing throttled per-section WARN stays.
- **Paper / Folia.** Same rule in `PaperNbtSectionSerializer`; the result flows through the shared
  `AbstractChunkDiskReader` and `OffThreadProcessor`. Generation uses Paper's service (Moonrise LOW
  priority; Folia's region scheduler). Paper has no background backfill. Folia's experimental label
  is unaffected.
- **Sponge.** Compiles the server half of `xplat`; gets the change with no Sponge file touched.
- **X-ray masking.** Counts are taken before masking; no interaction.

## 4. Tests

Pins that must stay green unchanged (they are the partial-unknown decision):
`renamedBlockPaletteEntry_sectionKeptWithAirSubstitution`,
`malformedBlockStates_codecParseError_sectionSkippedSiblingsServe`,
`renamedPaletteEntryTranscodesIdenticallyToTheObjectPath`, `golden_duplicateAirLenientPalette`,
`trulyUnparseableSection_condemnsTheColumnAsAuthoritativeMiss`,
`dataLengthMismatchCondemnsTheColumnOnBothPaths`, the `airOnly_*` family (real air is still
`byte[0]` or dropped), on 26.3 `legacyNamePropertiesPaletteEntriesSerializeIdenticallyToTheCurrentFormat`,
`MemoizedNbtCodecTest` / `PaperMemoizedNbtCodecTest`, the root-whitelist pin in
`SelectiveChunkNbtLoaderTest` (regex over `chunkNbt.get…/contains…` calls), and the Paper twins.
No existing test asserts that an all-unknown column serializes to an empty array (searched both
serializer suites and the gametests), so nothing pinned is reversed.

Add, failing first (in `fabric/.../NbtSectionSerializerTest` and the Paper
`NbtSectionSerializerTest`, same text; fixtures use unknown names through the existing
`renamePaletteEntry` helper so the test is identical on every line):
1. `everyBlockEntryUnknown_columnIsAMissNeverAir`: two sections (a single-entry palette and a
   multi-entry palette with data), every entry renamed to an unknown id → `assertNull` on both
   routes (`useNbtTranscode` true and false). Fails today (returns an empty array).
2. The same with sky light and with block light on the sections → still `null` (today: empty array,
   or a lit-air column).
3. An entry with neither `id` nor `Name` (a rejected key shape) → `null`. On the five older lines
   optionally also an `id`-keyed fixture, the exact shape of the 0.20.0 incident.
4. `unknownEntriesBesideADecodedBlock_stillServe`: one section all-unknown, a sibling with stone →
   served; the counter does not move.
5. `realAirIsStillAllAir`: an all-air chunk with clean palettes → `byte[0]`, counter 0.
6. Counter and latch: the miss bumps `UNDECODABLE_COLUMNS` once per column; the WARN latch fires
   once across many (test seam like `capWarnedForTest`).

Reader level:
- `PaperDiskReaderEnvelopeTest` (it injects the NBT read): an undecodable chunk resolves
  `notFound && authoritativeMiss`; diag shows `not_found=1`, `all_air=0`, and the `undecodable=1`
  token; a clean reader's diag string is unchanged.
- `ChunkDiskReaderTest`: the Fabric diag token appears only when non-zero.
- Downstream needs no new test: `OffThreadProcessorDiskResultTest`, `OffThreadProcessorStoreTest`
  and `StoreBackfillTest` already pin "authoritative miss → no deposit, row deleted, memo,
  generation or NOT_GENERATED" and "null → backfill skip".

Optional gametest (Fabric, main only, beside `RegionFaultGameTests`, which already writes a crafted
region file and drives the live pipeline): a FULL chunk whose palettes are all unknown is requested
by a mock player; assert no all-air answer, `not_found` counted, a generation ticket taken, no
store row. Worth it once as the end-to-end proof; not required on the support lines.

Mutation checks: drop the gate (tests 1 to 3 fail); make it fire on any hard error (the pinned
rename tests fail).

## 5. Lines, wire, files, steps

Per-line differences:
- The insert points have the same shape on all six lines in both twins: the `unparseable` array,
  the `hardErrors` block in `transcodeSection`, the `resultOrPartial` callback in `parseSection`,
  the content-band loop.
- 26.3 only: `upgradeLegacyBlockStateEntry` runs before the decode, so legacy `Name` entries are
  not hard errors there.
- 1.21.1 uses the older NBT getters. Write the new palette walk as
  `sectionTag.get("block_states") instanceof CompoundTag bs` /
  `bs.get("palette") instanceof ListTag palette`, which compiles on every line, so the added lines
  are textually identical everywhere.
- 1.21.10/1.21.1 use `ResourceLocation`; the new code names no identifier type.
- `MemoizedNbtCodec`, `AbstractChunkDiskReader`, `OffThreadProcessor`, `StoreBackfill`,
  `DiskReaderDiagnostics` are identical on all lines and are **not** changed.

Wire impact: none. No payload, field, capability or protocol constant changes; the outcomes are
existing ones (column from generation, `ColumnNotGenerated`, silent transient drops on the
generation ladder).

Files on main:
- `xplat/src/main/java/dev/vox/lss/networking/server/NbtSectionSerializer.java`: the gate, the
  counter, the latched WARN, an internal core overload carrying `cx, cz` for the log.
- `paper/src/main/java/dev/vox/lss/paper/PaperNbtSectionSerializer.java`: the twin.
- `xplat/.../server/ChunkDiskReader.java`: append the conditional diag token (all return paths).
- `paper/.../PaperChunkDiskReader.java`: `getDiagnostics()` override with the same token.
- `common/.../processing/ChunkReadResult.java`: javadoc only (add this case to the
  `authoritativeMiss` list). Optional; it re-pins a file that is identical on all lines.
- tests: both `NbtSectionSerializerTest`s, `PaperDiskReaderEnvelopeTest`, `ChunkDiskReaderTest`,
  optionally `RegionFaultGameTests`.
- docs (line-local): `docs/operations/troubleshooting.md` (what `undecodable=` and the WARN mean),
  the plan record.
- `config/lines/classification.json` review notes and reviewed blobs, then the six-line re-pin.

Classification: all four production files are already `adapted` (per-line blobs); the hunks are the
same on every line. Test files likewise. No new files unless the gametest gets its own class.

Steps:
1. Add the failing serializer tests on main (Fabric and Paper).
2. `NbtSectionSerializer`: thread a `hardErrorEntries` out-parameter through `transcodeSection`
   (add where the section WARN is, after every fallback return, so a section that falls back to the
   object route is not counted twice) and `parseSection` (count through the memo when the callback
   fired); add the gate using `noContent`; counter; latched WARN.
3. Mirror in `PaperNbtSectionSerializer`.
4. Diag token in both readers; reader tests.
5. Run `:common:test`, the Fabric and Paper test tasks, the serializer parity gametests; optional
   new gametest; full gate.
6. Port the same hunks to the five lines (start with 26.2: its test fixtures still use `Name`),
   classify, re-pin.

Release-notes bullet:
- Chunks the server cannot read (saved by another Minecraft version or mod set) are no longer sent or stored as empty terrain.

## 6. Risks and open decisions

Risks:
- A world where a format mismatch is wholesale now loads those chunks through generation tickets.
  That is bounded and correct, but LOD fill for those areas runs at generation speed and adds chunk
  loads. The WARN and counter make it visible; the real fix for a known format change remains a
  per-entry mirror like 26.3's.
- With generation off, affected columns answer "not available" each session instead of an empty
  column. Same picture for the player, one disk read per session per client.
- Twin drift: the rule must land identically in both serializers; the parity tests and the
  duplicated unit tests are the guard.
- It touches the area of a pinned decision (R2-1). The rule is chosen so every R2-1 test stays
  green; the review should confirm that reading.

Open decisions (recommendation first):
1. Threshold: whole column with nothing decoded. Alternatives in §2.2.
2. Outcome: authoritative miss. Alternative: error triage (rejected, §2.1).
3. Counter: static in each serializer twin with a conditional diag token. Alternative: a dedicated
   exception caught in `AbstractChunkDiskReader` so the counter and WARN live once in common code
   and can name the dimension; rejected because backfill and every direct caller would need a new
   catch and the tests would turn into exception checks.
4. Gametest: one on main; skip on support lines.
5. Existing all-air store rows: leave them.
