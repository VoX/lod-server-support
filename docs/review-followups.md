# Code Review Follow-ups

Deferred items from the multi-agent codebase review (branch `paper-release-26.1.2`, commit
`ec040de`). The review surfaced 40 findings → 18 confirmed; 14 were fixed in `ec040de`. The
items below were **deliberately not fixed** in that pass because they are either large/risky
refactors, new test infrastructure, or low-severity nits whose ROI didn't justify touching a
release-ready tree. Each is real; this is a backlog, not a dismissal.

---

## Deferred confirmed findings

### #15 — Fabric/Paper twin duplication → shared base  (MEDIUM, large)
The `fabric/…/server/*` and `paper/…/*` modules carry near-mirrored leaf classes
(serializers, disk reader, broadcaster, processing service). Real risk = **silent drift**
between the two wire/serialization paths. Full design + options + a phased plan:
see **[`shared-base-refactor-investigation.md`](shared-base-refactor-investigation.md)**.
TL;DR: the orchestration is already shared in `common/`; the residual dup is uneven
(serializers ~94%, gen service ~57%, networking ~36%) and the blocker is the build/mapping
boundary, not the logic. Recommended first step is parity tests (#6/#16), not a big refactor.

### #6 — Paper wire-parity test  ✅ DONE
**Implemented.** Added `WireParityTest` to BOTH modules (`fabric/src/test/.../payloads/` and a
new `paper/src/test` JUnit sourceset). Each asserts its codec against an *identical* explicit
`FriendlyByteBuf` byte reference for all 8 payloads — S2C: encoded bytes == reference; C2S:
decode of a reference frame == fields — covering negative coords, 5-byte varints, `Long.MIN_VALUE`,
and a custom dimension. Extracted Bukkit-free `encodeSessionConfig` / `encodeBatchResponse` from
`PaperPayloadHandler` (and dropped the now-dead `sendEncoded`) so the tests can reach them. An
8-payload adversarial workflow first confirmed all 8 are already byte-identical (no live wire
bug). Both suites pass (Fabric 9/9, Paper 9/9); any future Fabric/Paper wire drift now reds one
suite.

### #16 — Region-NBT → section serialization  ✅ DONE
**Implemented.** Extracted a testable `serializeChunkNbt(CompoundTag, RegistryAccess)` seam from
`readAndSerializeSections` (both modules) and added `NbtSectionSerializerTest` to each (15 tests).
Each bootstraps MC and builds a `RegistryAccess` with biomes (copied from
`VanillaRegistries.createLookup()` into a fresh tag-less `MappedRegistry` — the only registry
`PalettedContainerFactory` needs), constructs in-memory chunk NBT by encoding real
`LevelChunkSection` containers through the factory codecs, runs `serializeChunkNbt`, and
round-trips the output through the client's wire grammar. Covers: populated round-trip, multi-entry
block palette, non-default (DESERT) biome round-trip, block + sky light, air-only kept (block
light) / dropped (zero / none / sky-light-only), missing block_states, missing-biomes default
path, status guards, missing-Y skip, multi-section + negative Y. A 3-reviewer adversarial review
caught 6 coverage gaps (vacuous biome assertion, sky-light-only-air, Paper missing 3 cases,
single-block-only palette) — all fixed. fabric 107 JUnit + 10 gametests / paper 24 JUnit, green.

### #13 — `probeLoadedChunks` re-serializes undrained columns every tick  (MEDIUM, perf)
`RequestProcessingService`/`PaperRequestProcessingService` (~L299–319) re-serialize loaded
columns for requests still queued, with no pending/done guard — wasted CPU on the per-tick hot
path when a snapshot isn't drained before the next tick overwrites it.
- **Why deferred:** the clean fix (carry forward the dropped snapshot's `loadedChunkProbes`
  per player in `OffThreadProcessor.postSnapshot`) touches the snapshot lifecycle — a hot,
  concurrency-sensitive path. Risk > reward until profiling shows it matters.
- **Suggested approach:** measure first (benchmark `sections_per_second` with/without), then
  carry-forward probes for positions not re-serialized this tick.

---

## Low-severity findings (20, unverified)

Surfaced by the review but **not adversarially verified** — confirm each against current code
before acting. Grouped by area.

| # | Area | File:loc | Issue |
|---|------|----------|-------|
| 1 | concurrency | SharedBandwidthLimiter.java:15,40-47 | `totalBytesSent` non-volatile long read from stats/command threads |
| 2 | concurrency | ChunkMapSaveHook.java:15-28 | dimension string cached in non-volatile field with racy lazy init |
| 3 | wire | VoxelColumnS2CPayload.java:85 vs 101 | decode caps sectionBytes at 2MB but encode is unbounded → pathological column kicks client (Paper encode unbounded too) |
| 4 | wire | PaperPayloadHandler.java:129-133 vs HandshakeC2SPayload.java:19-23 | Handshake caps read: Paper guards `isReadable()`, Fabric reads unconditionally (benign now, fragile for protocol evolution) |
| 5 | untrusted-input | PayloadCodecNegativeLengthTest/OversizedTest | negative/oversized-length tests only cover an S2C payload; the untrusted C2S BatchChunkRequest count is untested |
| 6 | correctness | SectionSerializer.java:63 (+3 mirrors) | section Y serialized as a single signed byte; truncates for extreme custom dimension heights |
| 7 | correctness | IncomingRequestRouter.java:220-233 | up-to-date check uses serve-time epoch, not content version → needless full re-sends across reconnects |
| 8 | resource | ColumnTimestampCache.java:183-187 | (now partly fixed in ec040de — load aborts on bad count) remaining: `DataInputStream.skipBytes` can short-skip elsewhere |
| 9 | error-handling | OffThreadProcessor.java:94-106,220-230 | snapshot-overwrite discards an already-generated column; client recovers only via the 10s timeout + regen |
| 10 | error-handling | FabricOffThreadProcessor:70-79 / PaperOffThreadProcessor:63-75 | `submitDiskRead` silent no-op paths (level==null / reader shut down) leak the permit + orphan the dedup group (same vector as the fixed Error path) |
| 11 | mc-integration | PaperChunkGenerationService.java:187-202 vs 125-128 | gen timeout removes the active entry while its async future is still pending; a re-request creates a new entry the stale future then removes |
| 12 | mc-integration | SectionSerializer.java:45 / NbtSectionSerializer:148-158 | all-air sections carrying only sky light are dropped on both platforms — confirm intended |
| 13 | perf | SendActionBatcher.java:35-37 | per-tick `clear()` drops the PlayerBatch arrays instead of reusing them |
| 14 | perf | ChunkDiskReader.java:93,102 (+paper) | disk reader allocates `dimension.identifier().toString()` on every read |
| 15 | perf | DedupTracker.java:61-76 | `removePlayer` scans every pending group on each disconnect/dimension-change |
| 16 | config | ColumnTimestampCache.java:183-187 | skip-size `entryCount*16` int-overflow (mitigated in ec040de by aborting on bad count) |
| 17 | config | LSSClientConfig.java:19 | `lodDistanceChunks` clamps to hardcoded 0..512 instead of `LSSConstants.MAX_LOD_DISTANCE`; Sodium GUI Range hardcodes 512 too |
| 18 | config | ColumnCacheStore.java:47-50,83-99 | 2,000,000-entry cap enforced only on load; an oversized save is silently discarded wholesale next load |
| 19 | api-quality | DirtyColumnsS2CPayload.java:19-24 | (fixed in ec040de — Fabric encoder now clamps to match Paper) |
| 20 | wire | ClientColumnProcessor.java | (fixed in ec040de — section count now bounded by world height) |

*Notes: #8/#16/#19/#20 were partially or fully addressed in `ec040de`; left here for traceability.*

## Dismissed by the review (not bugs)
- **Paper gen `getChunkNow` null race** — does not hold under the Moonrise chunk system this build uses.
- **dedup-router untested** — a test-coverage suggestion; the traced logic is correct as written.
