# Per-version surfaces — the R-7 re-verification table (V-1/D1)

The pinned-vanilla surfaces every port round must re-verify against the target line's
own MC artifact. Before V-1 this list was reconstructible only from javadoc trails and
progress-doc archaeology; this table is now canonical ON MAIN, and the support
branches' CLAUDE.md banners are POINTERS here plus a line-specific status column —
never a second live copy (two copies drift within one port).

Legend: "pin" = the contract test that reds on drift; "hand" = per-line manual
verification recorded in the port PR (no automatable pin exists).

| # | Surface | What to verify per line | Pin / hand |
|---|---|---|---|
| 1 | `IOWorker` priority ordinal + `consecutiveExecutor`/`storage` handles | The package-private `IOWorker$Priority` ordinal LSS hardcodes still means BACKGROUND; the accessor targets still exist (1.21.1: the executor is still `ProcessorMailbox` — a different shape, see the spike) | `SerializerParityGameTests` byte-parity (behavioral); accessor resolution is loud-fail (`defaultRequire: 1`); since V-3/S4 the ordinal + executor type + submit shape + accessor resolution live in `BackgroundIoSubmit` — the port flavors THAT file, never `ChunkDiskReader`'s signatures |
| 2 | `RegionFile` record-resolution branches (`RegionFileRawRead`) | The three `createChunkInputStream` branches (inline / external `.mcc` / oversized) still match vanilla's | vanilla-anchored byte-parity tests; javadoc annotation per line |
| 3 | `NbtIo` root protocol (`SelectiveChunkNbtLoader`) | Root-tag read shape + the byte-accounting constants still mirror `CompoundTag$1` | full-parse fallback + kill switch contain drift; hand-check the constants |
| 4 | `handleMovePlayer` warn/teleport census (move tracer) | The invoke census + slice anchor still match the line's bytecode | `MoveTraceHookContractTest` (ASM `ClassReader` scan) — re-flavor per line |
| 5 | `publishServer` overload set (LAN hook) | The mixin descriptor names the overload the GUI actually calls; single-vs-split overload set per line | `LanHookContractTest` — re-flavor per line (the whole 26.1 port was this) |
| 6 | `SerializableChunkData.copyOf` save choke point (dirty hook) | The class EXISTS on this line (≤1.21.1: it does not — target `ChunkSerializer.write`) AND the platform save paths (vanilla + Moonrise + C2ME) actually invoke it | `SaveHookContractTest` (existence, reflective); the vanilla arm's ASM invoke-census is `SaveHookContractTest.vanillaSavePathRoutesThroughCopyOf` (V-2/S7, landed — exactly one copyOf INVOKESTATIC in `ChunkMap.save`); Moonrise/C2ME arms stay hand-verified per line (reflective-only, off the test classpath) |
| 7 | `folia-supported` direction | Does THIS line's Folia exist upstream? Present-pin vs absent-pin flips accordingly | `PluginYmlContractTest` + `release_check.py` — the fresh-cut inherits main's PRESENCE pin and must actively re-derive it |
| 8 | Corpus identity rule | `xver-live-corpus` is NEVER regenerated on a support line (decoding capture-line columns IS the cross-version claim); `nbt-corpus` regenerates v20-first then natives (see the runbook §fixtures) | `XverLiveCorpusDecodeTest` (line-neutral since V-1/T4) |
| 9 | Native count-short shape | One short (1.21.x, fold rule differs by platform: Fabric sums fluid, Paper omits it) vs split pair (26.x) | V-2/S1 landed (as amended by the execution review): edit `common/wire/NativeSectionShape` (NATIVE_COUNT_SHORTS + the LINE-level cursor fold `foldedCountForNativeHeader` + the two family folds — all three folds THROW on 2-short lines by design) + regenerate goldens; the cursor emit (line fold), both serializers' `writeNativeCountHeader` + `emitV20Direct` count headers (family folds) + exact pre-size arithmetic, and the three relationship tests (translator counts, xver pass-through via the line fold, the paper corpus parity's strict-vs-normalized flip) all derive from it |
| 10 | Toolchain: Java release + mixin `compatibilityLevel` + mappings namespace | Class-file major == 44 + line.env `LINE_JAVA_VERSION`; all THREE mixin configs match (`lss.mixins.json`, `lss-trace.mixins.json`, `lss-sodium-legacy.mixins.json`); `release_check.py` `FABRIC_MAPPING_NAMESPACE` (official on 26.x / intermediary under loom-remap) | `ToolchainContractTest` (fabric + paper, V-1/T3c — incl. the resolved-MC-artifact anchor) |
| 11 | Ticket API shape (`ChunkGenerationService`) | The `TicketType` ctor/params still mean (timeout, flags) | compile (class literal); use the named vanilla constants (V-2's one-liner) so reorders red the compile; the GAMETEST hold/release sites route through `TestPositions.holdChunk`/`releaseChunk` since V-3/T2 (~48 sites, one flavor point) |
| 12 | Version-volatile file list | `FarPlayerRenderer` + `ChunkSaveDataHook` + `ScopedCarrier` (V-2/S5 — the Java-21 lines swap it for a pass-through; the twins are byte-identical on one line) stay per-loader-tree whole-file replacements | `VersionVolatileFileListTest` (V-1/S6) + the `NeoForgeModuleContractTest` twin-identity pin |
| 13 | Release-line identity | `.github/line.env` values (tag suffix, MC tokens, game-versions, loaders, NeoForge name prose, NeoForge ship flag `LINE_SHIP_NEOFORGE` — on main `release_check.py` DERIVES `SHIP_NEOFORGE` from line.env since R2-5; on THIS branch it is still the hand mirror — flip line.env AND release_check together, make_latest, Java) vs gradle.properties vs the resolved artifact | `ReleaseWorkflowContractTest` + `ToolchainContractTest` (the three-link chain) |
| 14 | Native long-array prefix | Whether the native container long array is VarInt-length-prefixed: 1.21.1 vanilla `writeLongArray` (prefix, empty array included) vs bare words (26.x, 1.21.11). V20 is prefix-free on EVERY line (wire spec — never derive it there) | `NativeSectionShape.NATIVE_LONG_ARRAY_PREFIXED` (fourth field, found at the 1.21.1 port) — the cursor's NATIVE parse+emit derive from it (dead branches on false lines); serializer transcode writers carry the same rule; goldens byte-pin the live value |
| 15 | Sodium options-page generation | Which Sodium GENERATIONS this line's players run (Modrinth listing + the README client stacks — 0.6/0.7 = the internal options screen, 0.8+ = the public config API; 1.21.1 runs BOTH): the 0.8+ walker is PRESENT iff the line has a 0.8+ artifact — on Fabric `LSSConfigMenu` + the `sodium:config_api_user` ENTRYPOINT (`modCompileOnly` pin, per-line build.gradle data); since 2026-08-26 the NeoForge module carries a same-FQN twin wired through the SAME key as a `[modproperties.lss]` TOML row (sodium-neoforge's ConfigLoaderForge route), compiled against compile-only `net.caffeinemc` stubs that never ship (shadowJar exclude + release_check NEOFORGE_FORBIDDEN); the catalog, the resource probe, the reflective legacy builder, the `@Pseudo` constructor hook and the ModMenu switch are line-invariant (sodium-options-page-generations-plan.md); the legacy mixin config's `compatibilityLevel` is per-line data like the other two; gradle.properties `sodium_legacy_golden` may point at the line's own 0.7 build | `ClientMenuEntrypointContractTest` (entrypoint ⇔ walker file), `SodiumLegacyHookContractTest` (hook shape, config, both descriptors), `ToolchainContractTest` (third config), `SodiumLegacySurfaceResolvesTest` (real-bytecode name+arity), `NeoForgeModuleContractTest` (twins + toml), `SodiumConfigApiContractTest` (TOML key + walker classfile shape + catalog walk), `SodiumNeoGoldenParityTest` (stub descriptors vs the real sodium-neoforge artifact), `release_check` (NeoForge lang + config rows + walker presence); hand: the per-line live gates in the plan §4 |
| 16 | Xaero World Map bridge (`XaeroMapCompat`/`XaeroTileExtractor`) | The consumer's world-height expression (THIS line: `getMinBuildHeight()`/`getMaxBuildHeight()` — exclusive already; 26.x/1.21.11/1.21.10: `getMinY()`/`getMaxY()+1`) and the extractor's light-opacity call (`getLightDampening()` 26.x / `getLightBlock()` 1.21.11-1.21.10 / 2-arg on 1.21.1); Xaero's own surface is line-INVARIANT (floor WM 1.42.0, FQN-identical on every line and loader — bridge plan §16.1) | `XaeroWiringContractTest.theConsumerPassesThisLinesWorldHeightExpression` (per-line literal) + `XaeroTileExtractorTest`; the reflective surface needs no per-line pin |
| 20 | `BiomeManager.biomeZoomSeed` accessor (world-axis cache key, v0.14 port) | The private `long biomeZoomSeed` field still exists under Mojang mappings AND the login chain still hands the obfuscated login seed to the client `BiomeManager` unmodified (javap the line's artifact; the chain claim is javadoc-recorded on `AccessorBiomeManager`); the accessor is registered in BOTH loaders' mixin configs (a missing NeoForge entry fails SILENT — Fabric sub-buckets while NeoForge keeps bare buckets) | `SeedAccessorContractTest` (reflective field + type + both mixin-config registrations) |
| 21 | Service-gate glue dialects (v0.14 port) | The gate's shared glue must be re-spelled per line where it touches pinned dialects: `Identifier` vs `ResourceLocation` in the two-axis/gate TESTS, `Level.OVERWORLD.identifier()` vs `.location()`, the gametest annotation dialect (`structure=` / `template=`) + the 1.21.10 `Gt` shim for the ServiceLifecycle append, and the `SharedConstants` data-version chain (`dataVersion().version()` vs `getDataVersion().getVersion()`). The NeoForge `PermissionNode`/gather API was verified IDENTICAL at 26.2.0.59/26.1.2.95/21.11.45/21.10.64/21.1.248 (sources jars, v0.14 port review) — re-verify only on a NEW neoforge_version | compile (all flavors are compile-loud) + `PluginYmlContractTest` gate-node pins + `release_check.py` gate-node check + `LoaderPermissionSeamContractTest` |

Numbering note (v0.14 port): the two new rows take MAIN's canonical numbers 20/21.
This line's local table never backfilled main's rows 15-17 (far-player render
phase / Moonrise IO entry / Bukkit world layout), so in-tree comments saying
"surfaces row 17" (PaperRequestProcessingService, PaperRegionFreshnessWiringTest)
mean MAIN's row 17 — the Bukkit split world-dir invariant — not a local row.
Row 20 addendum (this line): the seed chain was javap re-verified on the 1.21.1
artifact at the v0.14 port, AND the alias-corroboration probe was verified against
the real j-shelfwood voxy-neoforge 0.2.9-alpha jar — it carries the exact mainline
internals (VoxyCommon.getInstance / VoxyClientInstance.getStorageBasePath) and a
byte-identical .voxy path munge, so corroboration genuinely WORKS with the fork
pairing (stronger than the plan's fails-toward-off assumption).

NeoForge far-player RENDER surface (v0.14.0, this line — a NEW per-line surface, the
1.21.1 twin of main's far-player-render row). The NeoForge `FarPlayerRenderer` twin
(`neoforge/.../networking/client/FarPlayerRenderer.java`, `RENDER_AVAILABLE = true`)
renders far players immediate-mode, byte-verbatim with the Fabric twin except its
loader plumbing: the render pass hangs off `RenderLevelStageEvent` at
`Stage.AFTER_ENTITIES` (game bus, `getPoseStack()` is the identity `LevelRenderer`
PoseStack — the same object Fabric's `WorldRenderContext.matrixStack()` yields; NO
explicit buffer flush — vanilla's downstream `endBatch()` drains it), the crossfade
kill hangs off `EntityJoinLevelEvent` (game bus, `isClientSide()` = dist AND
integrated-server thread guard), and the unseated `dispatcher.render` carries an extra
per-proxy `try/catch` because NeoForge fires third-party render events inside it. The
buffer source is `minecraft.renderBuffers().bufferSource()`. Wired from
`LSSNeoClientBootstrap.init()` → `FarPlayerRenderer.initRenderer()`. Pinned by
`NeoForgeLoaderSeamContractTest.clientBootstrapExistsUnderTheReflectiveName` (present +
wired + gate + containment). FOLLOW-UP (recorded, not done): NeoForge rendering on the
26.1/26.2 lines needs the 26.x submit/extract pipeline (`dispatcher.extractEntity` +
`submit` + `SubmitNodeCollector`, their Fabric twins' shape) against the Foxy fork
client — a separate port, tracked here.

