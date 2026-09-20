# 13 — Cross-line wire and Minecraft surface parity

Reviewer: Astra. Date: 2026-09-06. Read-only review; no repository changes, Gradle runs, server starts, captured-corpus regeneration, or new protocol attack probes.

## Result

**No additional confirmed product defect or actionable port-parity finding in this lens.** The differences examined agree with each line's canonical `docs/planning/per-version-surfaces.md` and the Minecraft artifacts inspected. This is bounded source/bytecode review, not a claim that all supported mod combinations have been exercised live.

Scope: `/home/vox/projects/lss-lines/{26.2,26.1,1.21.11,1.21.10,1.21.1}` at the merged baselines recorded in `scope.json`. Read the CLAUDE banners, canonical surface tables, pinned-decision memory, mechanical production diff inventory, and relevant test implementations before classifying differences. SQLite internals and already assigned common/store/session defects are excluded.

## Independent evidence and coverage

### Native section shape, cursor, serializers, and transcode

| Platform line | Native count shorts | Native long-array encoding | Vanilla/Fabric one-short fold | Paper one-short fold |
|---|---:|---|---|---|
| 26.2 | 2 | Bare fixed-size words | Separate counts | Separate counts |
| 26.1 (MC 26.1.2) | 2 | Bare fixed-size words | Separate counts | Separate counts |
| 1.21.11 | 1 | Bare fixed-size words | Non-air + nonempty-fluid count | Non-air count |
| 1.21.10 | 1 | Bare fixed-size words | Non-air + nonempty-fluid count | Non-air count |
| 1.21.1 | 1 | VarInt-prefixed long array, including the single-value empty array | Non-air + nonempty-fluid count | Non-air count |

Verified the Minecraft side independently using cached artifact `javap -p -c` output, saved under `13-javap/`: each line's `LevelChunkSection.write`, `LevelChunkSection$1BlockCounter`, and `PalettedContainer$Data.write`; also inspected the Paper-patched section implementation for each MC version. The older vanilla counter really increments `nonEmptyBlockCount` for both non-air and nonempty fluid, while the Paper recalc path does not add fluid into that header count. These are observable engine differences, not descriptors merely agreeing with tests.

Corresponding source anchors on 1.21.1: `common/src/main/java/dev/vox/lss/common/wire/NativeSectionShape.java:40` and `:51`; `WireSectionCursor.java:192`, `:216`, `:333`, `:358`. All four prefix sites are gated on native layout. V20 remains two shorts and prefix-free on every line. The absence of the newer named prefix seam on the middle support branches reflects their older equivalent bare-array implementation; it is not a missing capability.

Compared the material differences in `NativeToV20Translator`, `V20ToNativeTranslator`, `WireSectionCursor`, loader serializers, NBT serializers, the registry identity resolver, and section-construction helpers. The translator bodies are shared; the native cursor and descriptor own the variable shape. The 1.21.1 NBT/container adaptations cover the native prefix and its size calculation, older registry/constructor APIs, and typed CompoundTag access. Unknown registry entries and default-biome fallback did not show an extra port-specific branch beyond the necessary API spelling changes.

Test strength: `fabric/src/test/java/dev/vox/lss/networking/server/NbtSectionSerializerTest.java:972` and `paper/src/test/java/dev/vox/lss/paper/NbtSectionSerializerTest.java:1001` on 1.21.1 are meaningful independent anchors: randomized air/fluid/waterlogged sections are codec-roundtripped into actual Minecraft `LevelChunkSection` instances, then direct V20 bytes are compared with translation of the real native writer output. Transcode/object equivalence tests additionally cover palette tiers and masking. `NativeSectionShapeTest`'s descriptor round trips alone would not establish fidelity, but those actual engine anchors address the concern. Tests were inspected, not executed by this reviewer.

Paper's cross-module fixture check at `paper/src/test/java/dev/vox/lss/paper/NbtSectionSerializerTest.java:611` deliberately ignores only the differing count headers when the engine families fold differently. It still compares the body, and the separate real-engine tests establish each family's count semantics. Do not replace this with unconditional byte equality.

Read-only corpus comparison: all 13 files in the captured `fabric/src/test/resources/xver-live-corpus` are byte-identical across the five trees. The 14-file V20 fixture sets are not all identical; line/family-native count-derived fixtures are explicitly allowed to differ. No corpus was regenerated or normalized.

### Height, opacity, identity, and construction seams

Cached `LevelHeightAccessor` bytecode confirms the boundary distinction: newer lines' `getMaxY()` is inclusive and `getMaxSectionY()` names the last section; 1.21.1's `getMaxBuildHeight()` and `getMaxSection()` are exclusive. The 1.21.1 disk reader passes `getMaxSection() - 1` consistently (`xplat/.../ChunkDiskReader.java:100`, `:149`, `:155`; Paper twin `PaperChunkDiskReader.java:66`). The Xaero consumer uses `getMinBuildHeight(), getMaxBuildHeight()` at `xplat/.../XaeroMapCompat.java:767`; newer lines use `getMinY(), getMaxY() + 1`. No added or missing top section was found.

Opacity adapters match cached method descriptors: 26.x `getLightDampening()`, 1.21.11/1.21.10 no-argument `getLightBlock()`, 1.21.1 two-argument `getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)` (`XaeroTileExtractor.java:255`, `:276`). The 1.21.1 constant-context opacity approximation is documented, not an accidental port omission.

Reviewed `ClientIdentityResolver`'s resource-name and holder API substitutions and `SectionConstruction`'s 1.21.1 Registry-of-Biome constructor family. No divergent fallback or registry-identity policy was found. The Paper 1.21.1 deprecated empty-section constructor helper is retained dead code by explicit surface-table decision, not evidence of an active bad conversion.

### Disk IO, dirty-save targets, and world layout

Cached `IOWorker$Priority` has BACKGROUND ordinal 1 on all five versions. The 1.21.1 `BackgroundIoSubmit` ProcessorMailbox/tell variant (`xplat/.../BackgroundIoSubmit.java:38`, `:64`) and newer PriorityConsecutiveExecutor variants schedule at the same intended priority. The middle lines retain equivalent inline scheduling instead of the later named helper. The mailbox's silent shutdown drop and bounded caller timeout are explicitly documented; this lens did not recast them as a new parity bug.

Cached `ChunkMap` inheritance agrees with the accessor targets: SimpleRegionStorage on 26.2/26.1/1.21.11, ChunkStorage on 1.21.10/1.21.1. The class name retained by the accessor does not determine its actual target.

Vanilla save-route disassembly on all five versions finds the expected call site: `SerializableChunkData.copyOf(ServerLevel, ChunkAccess)` on the newer four; `ChunkSerializer.write(ServerLevel, ChunkAccess)` on 1.21.1. Both loader hook shims use the corresponding RETURN target. `fabric/src/test/java/dev/vox/lss/SaveHookContractTest.java:88` on 1.21.1 independently walks real `ChunkMap` instructions, rather than merely asserting a method exists; it also checks reflection descriptors and injector shape. Optional `require=0` is a deliberate mod-compatibility fallback. Current review reverified vanilla routing, not every Moonrise/C2ME build's replacement bytecode.

The Paper disk IO adapter uses the documented older `RegionFileIOThread`/nested-priority API on 1.21.1 (`PaperChunkDiskReader.java:97`), and the later RegionFileIO/flat-priority API on the newer branches. This is a compile-sensitive seam, not an omitted loader path.

Paper's region resolver correctly distinguishes unified 26.x storage from split 1.21.x world roots. The 1.21.x path is based on each level's Bukkit world folder before applying dimension nesting (`PaperRequestProcessingService.java:636` on 1.21.1, `:638` on 1.21.10/1.21.11). The corresponding test explicitly checks overworld plus `world_nether/DIM-1/region` and `world_the_end/DIM1/region` (`PaperRegionFreshnessWiringTest.java:76`, `:87`, `:89`), so its layout pin is not vacuous through an overworld-only fixture. Cached Paper CraftWorld output also supports the split/unified API distinction; on 26.x the concrete API is `getWorldPath()` using the server storage source's dimension path.

`RegionFileRawRead` and `SelectiveChunkNbtLoader` production bodies are shared across the lines. Reviewed header bounds, external `.mcc` selection, compression handoff, selective root-tag behavior and allocation accounting against the saved vanilla RegionFile disassembly/source intent. No port-specific algorithm drift was found. Deliberate omissions of DataVersion upgrading and unrelated NBT subtrees remain explicit decisions; this lens does not introduce a new requirement to rewrite or upgrade old world data.

### Java 21/25 scoped carrier

The Java 25 `ScopedCarrier` binds the relevant serialization scopes; Java 21 variants call through, matching the corresponding ThreadLocal-based compatibility shape. Fabric/NeoForge helper twins agree within each line. The pass-through helper is the canonical Java 21 flavor, not an accidental removal of a required Java 25 binding. No current live AntiXray integration was exercised, and no claim is made that an arbitrary future mod release keeps these internals.

## Dismissed additional candidate: delayed handshake versus queued dialect

Parent supplied the Paper concern that frames use current player dialect while queued bodies may predate that dialect. The specific proposed supported trigger was a delayed modern session response overlapping the client's discovery fallback.

Source reviewed: `xplat/src/main/java/dev/vox/lss/networking/client/ClientSessionGate.java:243`, `:324`, `:350`, `:365` on 1.21.1; shared chronology across the lines. Discovery stops once session config is accepted. If the client has already announced a lower fallback before accepting the delayed higher echo, it re-announces the accepted higher version before constructing its request manager. Its first requests therefore follow the healing handshake in normal FIFO transport. A subsequent lower echo is guarded and reasserts the established session version. Before this establishment there was no manager issuing the alleged old-session body requests.

**Disposition: not a confirmed finding for that chronology.** This does not prove arbitrary midstream handshakes, a failed healing send, or unrelated server-generated traffic safe; those require a concrete supported sequence and remain speculative rather than being promoted to a port defect. The separately reviewed arbitrary dialect-splice hypothesis remains with the owning wire/Paper lens.

## Verification limits and follow-up

- No tests were run by this reviewer. Test references establish what the existing suites actually pin; execution results belong to the parent's coordinated gate record.
- `13-javap/` preserves 45 vanilla disassemblies and 10 Paper disassemblies. Paper evidence was selected from cached named outputs with matching Minecraft version IDs; multiple build snapshots may exist, so it is corroborating version-shape evidence rather than a claim that every file came from the exact current dev-bundle patch build.
- No live mod-matrix, NeoForge client, AntiXray, C2ME, Moonrise, mixed-version client/server or disk-upgrade session was started. No captured corpus was regenerated. Existing tier/live-validation debts remain debts; this pass does not convert them into discovered compatibility failures.
- No production change or new regression probe is proposed from this lens. Preserve the real-engine serializer and bytecode save-route anchors in future ports; run the existing per-line gates under the parent's execution plan.
