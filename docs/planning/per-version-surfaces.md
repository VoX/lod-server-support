# Per-version surfaces — the R-7 re-verification table (V-1/D1)

The pinned-vanilla surfaces every port round must re-verify against the target line's
own MC artifact. Before V-1 this list was reconstructible only from javadoc trails and
progress-doc archaeology; this table is now canonical ON MAIN, and the support
branches' CLAUDE.md banners are POINTERS here plus a line-specific status column —
never a second live copy (two copies drift within one port).

Legend: "pin" = the contract test that reds on drift; "hand" = per-line manual
verification recorded in the port PR (no automatable pin exists).

## Current loader and artifact surfaces (2026-10-12)

The checked-in build/release definitions give this matrix. Shipping, terrain-consumer
compatibility and far-player rendering are separate checks.

| MC line | `LINE_SHIP_NEOFORGE` | NeoForge far-player renderer |
| --- | --- | --- |
| 1.21.1 | true | Live, immediate mode |
| 1.21.10 | false | Intentional stub; module remains built/tested |
| 1.21.11 | false | Intentional stub; module remains built/tested |
| 26.1 | true | Intentional stub |
| 26.2 | true | Intentional stub |
| 26.3 | true | Intentional stub |

On every line, common carries SQLite 3.49.1.0 in an opaque capsule trimmed to the
supported native matrix (`dev/vox/lss/internal/jdbc/sqlite-jdbc.jar.bin`), loaded by a private
platform-parent loader. No loader exposes SQLite classes or declares it as a nested
mod/library. NeoForge retains stock **zstd-jni** jarJar under `META-INF/jarjar/`;
Paper retains flat native-stripped zstd; Fabric retains its stripped nested zstd
mod. `release_check.py` and `tools/packaging/sqlite_capsule.py` pin all three shapes,
independent capsule identity, eight SQLite natives and LSS/VSS resource equality.

Use [the current live-profile inventory](../testing/astra-live-profiles.md) for
candidate Voxy/Connector/Sodium/Xaero stacks and their validation limits. A renderer
stub or a disabled shipping flag does not establish that no terrain consumer can
run; installed metadata alone does not establish a successful live integration.
Dated port records below describe their original checkpoints.

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
| 13 | Release-line identity | `.github/line.env` values (tag suffix, MC tokens, game-versions, loaders, NeoForge name prose, NeoForge ship flag `LINE_SHIP_NEOFORGE` — `release_check.py` DERIVES `SHIP_NEOFORGE` from line.env since R2-5 (the hand mirror is retired; one line.env edit is the whole flip), make_latest, Java) vs gradle.properties vs the resolved artifact | `ReleaseWorkflowContractTest` + `ToolchainContractTest` (the three-link chain) |
| 14 | Native long-array prefix | Whether the native container long array is VarInt-length-prefixed: 1.21.1 vanilla `writeLongArray` (prefix, empty array included) vs bare words (26.x, 1.21.11). V20 is prefix-free on EVERY line (wire spec — never derive it there) | `NativeSectionShape.NATIVE_LONG_ARRAY_PREFIXED` (fourth field, found at the 1.21.1 port) — the cursor's NATIVE parse+emit derive from it (dead branches on false lines); serializer transcode writers carry the same rule; goldens byte-pin the live value |
| 15 | Far-player render phase | The registered event must fire BEFORE the line's submit-storage drain — verify against LevelRenderer BYTECODE, never transfer the event NAME (26.2 `COLLECT_SUBMITS` ↔ 1.21.11 `BEFORE_ENTITIES`, while `AFTER_ENTITIES` is CORRECT on 1.21.1's immediate-mode renderer; the same name flips meaning per line — shipped wrong once, 6-Opus review 2026-08-15). Since the 2026-09-04 render hardening (far-player-render-hardening-plan.md §11 = this line's port record) the pass also carries per-line render seams: the frame frustum from `levelState().cameraRenderState.cullFrustum` (gated on `initialized` — no context accessor here), the sky-15 light floor written into the extracted `lightCoords`, name tags through the 10-arg `submitText` (never `submitNameTag`), the armor depth-lift as a pose-scaling `SubmitNodeCollector` wrapper (`LiftedSubmitCollector` — the collector's abstract method set and Fabric's FRAPI interface-injected overloads are per-line; 26.1 differs in ~10 signatures) with tiers from `EquipmentClientInfo` via the `AccessorEntityRenderDispatcher` client accessor, and `elytraAnimationState.tick()` on the proxy (wing angles are tick-state here) | hand row — the R2-4 comment at the registration site names this row; `FarPlayerRenderSourceContractTest` pins the seams as line-specific strings |
| 16 | Moonrise IO entry class | Per-PLATFORM, never transferred: verify Fabric against the moonrise-opt jar and Paper against the dev bundle (Moonrise-Fabric 1.21.1 = `MoonriseRegionFileIO`; Paper 1.21.1 = `RegionFileIOThread` — a cross-platform 'fix' was refuted only by downloading the real jar) | hand row — the R2-4 comment at the resolution ladder names this row |
| 17 | Bukkit world layout | UNIFIED (26.x: one world dir, `dimensions/minecraft/<dim>`; sweep roots at server worldRoot; `getWorldFolder()` returns the per-dim SUBFOLDER — R2-9 live probe 2026-08-15) vs SPLIT (1.21.x: `world_nether`/`world_the_end`; sweep re-roots per level via `getWorldFolder()`). The two sweep forms are NOT interchangeable in either direction. Consumers: store sweep + soak world staging (staging is line-invariant since R2-3; the sweep form is per-line forever) | hand row — the row-17 comment at the sweep site; the sweep's own drop counters red a wrong form |
| 18 | Sodium options-page generation | Which Sodium GENERATIONS this line's players run (Modrinth listing + the README client stacks — 0.6/0.7 = the internal options screen, 0.8+ = the public config API; 1.21.1 runs BOTH): the 0.8+ walker is PRESENT iff the line has a 0.8+ artifact — on Fabric `LSSConfigMenu` + the `sodium:config_api_user` ENTRYPOINT (`modCompileOnly` pin, per-line build.gradle data); since 2026-08-26 the NeoForge module carries a same-FQN twin wired through the SAME key as a `[modproperties.lss]` TOML row (sodium-neoforge's ConfigLoaderForge route), compiled against compile-only `net.caffeinemc` stubs that never ship (shadowJar exclude + release_check NEOFORGE_FORBIDDEN); the catalog, the resource probe, the reflective legacy builder, the `@Pseudo` constructor hook and the ModMenu switch are line-invariant (sodium-options-page-generations-plan.md); the legacy mixin config's `compatibilityLevel` is per-line data like the other two; gradle.properties `sodium_legacy_golden` may point at the line's own 0.7 build | `ClientMenuEntrypointContractTest` (entrypoint ⇔ walker file), `SodiumLegacyHookContractTest` (hook shape, config, both descriptors), `ToolchainContractTest` (third config), `SodiumLegacySurfaceResolvesTest` (real-bytecode name+arity), `NeoForgeModuleContractTest` (twins + toml), `SodiumConfigApiContractTest` (TOML key + walker classfile shape + catalog walk), `SodiumNeoGoldenParityTest` (stub descriptors vs the real sodium-neoforge artifact), `release_check` (NeoForge lang + config rows + walker presence); hand: the per-line live gates in the plan §4 |
| 19 | Xaero World Map bridge (`XaeroMapCompat`/`XaeroTileExtractor`) | The consumer's world-height expression (26.x `getMinY()`/`getMaxY()+1`; 1.21.1 `getMinBuildHeight()`/`getMaxBuildHeight()` — exclusive already) and the extractor's light-opacity call (`getLightDampening()` 26.x / `getLightBlock()` 1.21.11-1.21.10 / 2-arg on 1.21.1); Xaero's own surface is line-INVARIANT (floor WM 1.42.0, FQN-identical on every line and loader — bridge plan §16.1) | `XaeroWiringContractTest.theConsumerPassesThisLinesWorldHeightExpression` (per-line literal) + `XaeroTileExtractorTest`; the reflective surface needs no per-line pin |
| 20 | `BiomeManager.biomeZoomSeed` accessor (world-axis cache key, v0.14 port) | The private `long biomeZoomSeed` field still exists under Mojang mappings AND the login chain still hands the obfuscated login seed to the client `BiomeManager` unmodified (javap the line's artifact; the chain claim is javadoc-recorded on `AccessorBiomeManager`); the accessor is registered in BOTH loaders' mixin configs (a missing NeoForge entry fails SILENT — Fabric sub-buckets while NeoForge keeps bare buckets) | `SeedAccessorContractTest` (reflective field + type + both mixin-config registrations) |
| 21 | Service-gate glue dialects (v0.14 port) | The gate's shared glue must be re-spelled per line where it touches pinned dialects: `Identifier` vs `ResourceLocation` in the two-axis/gate TESTS, `Level.OVERWORLD.identifier()` vs `.location()`, the gametest annotation dialect (`structure=` / `template=`) + the 1.21.10 `Gt` shim for the ServiceLifecycle append, and the `SharedConstants` data-version chain (`dataVersion().version()` vs `getDataVersion().getVersion()`). The NeoForge `PermissionNode`/gather API was verified IDENTICAL at 26.2.0.59/26.1.2.95/21.11.45/21.10.64/21.1.248 (sources jars, v0.14 port review) — re-verify only on a NEW neoforge_version | compile (all flavors are compile-loud) + `PluginYmlContractTest` gate-node pins + `release_check.py` gate-node check + `LoaderPermissionSeamContractTest` |
| 22 | Chunk-load seam (the dirty content filter's load baseline, xaero-scatter-remediation-plan.md WI-1b) | The Fabric callback ARITY: the fabric-api 4.x lifecycle module (26.2 AND 26.1 — 4.1.0 nested in fabric-api 0.151.0+26.1.2) `ServerChunkEvents.Load.onChunkLoad(level, chunk, generated)` vs the 2.x module's `(level, chunk)` on 1.21.x; NeoForge `ChunkEvent.Load` is line-invariant. Both fire from the FULL status task (fabric-api's `ChunkStatusTasksMixin` on 4.x and 2.6.15, the same injection under the older name `ChunkGeneratingMixin` on 2.6.9/2.6.0; the NeoForge `ChunkStatusTasks` patch). Newly GENERATED chunks are skipped: the 4.x `generated` flag, NeoForge `isNewChunk()`, and on 2.x `CHUNK_GENERATE` (fires right after `CHUNK_LOAD`) via `onChunkGenerated` — verified per line at port time; Moonrise/C2ME fire it through their platform hooks (live instrument: `seeded_load=` in `/lsslod diag`) | The lambda literal in each line's `LSSServerNetworking`; `DirtyContentFilterTest.seedLoaded*` + `check_cold_restart_resync` (marked ≤ 50 on a restart) |
| 23 | Sponge plugin (sponge-support-plan.md §4) | The pinned SpongeVanilla build + compile API (gradle.properties `spongevanilla_version`/`spongevanilla_sha1`/`sponge_api_version`/`sponge_api_dependency`), the VanillaGradle version (0.3.x needs Gradle on Java 25; 0.2.x on the Java-21 lines), the FULL-lambda and `tickServer` descriptors, the save-hook target (the dirty-hook row), the command-source permission elevation (`SpongeLineSurface`: int op level on 1.21.1/1.21.10, `PermissionSet` on 1.21.11+), and `LINE_*_SPONGE` + the `SHIP_SPONGE` mirror (derived on 26.3 and 26.2, hand-mirrored on the older support lines) | `SpongeMixinTargetContractTest`, `SpongeToolchainContractTest`, `SpongePluginMetadataContractTest`, `ReleaseWorkflowContractTest`, the two-phase `:sponge:bootSmoke` on the pinned server |
| 24 | `ClientPacketListener.close()` (client disconnect cleanup, 0.23.0 plan §12) | The method still exists as `close()V`, its ONLY caller is `Minecraft.disconnect(...)`, and that call still comes before the level is cleared. Fabric's `ClientPlayConnectionEvents.DISCONNECT` must not be used for the cleanup: it fires from netty | `ClientDisconnectHookContractTest` (ASM scan of the line's `Minecraft` class; no re-flavor needed while the three facts hold); the client gametest fails if the hook stops firing |

### Soak setup command data (2026-09-08)

The shared Fabric/Paper/Folia scenario JSON has a game-version seam. On **1.21.11,
26.1 and 26.2**, use registered gamerule identifiers: `minecraft:random_tick_speed`,
`minecraft:spawn_mobs`, `minecraft:mob_griefing`, `minecraft:advance_time`; legacy
`doFireTick false` becomes `minecraft:fire_spread_radius_around_player 0`. Vanilla's
`GameRuleRegistryFix` proves that last value transformation (minus one is unrestricted
spread distance, not disabled). The same fix removes `spawnChunkRadius`; these engines
have no `TicketType.START` constant or registered spawn-chunk-radius rule. Remove its
obsolete timeline step on those three lines **and 1.21.10**: the real command-tree
regression and cached 1.21.10 GameRules/TicketType prove that this removal predates
the gamerule rename. This is not a promise that no chunk near
spawn can be held: player, forced, portal and temporary spawn tickets still exist, and
the summary scenarios retain their actual offline-window/probe/convergence assertions.
**1.21.1 and 1.21.10 retain their other legacy gamerule names**. Only 1.21.1
retains spawnChunkRadius (its real GameRules registration and TicketType.START remain).

`CommandGameTests.soakScenarioGamerulesExecuteAndReadBack` reads the actual scenario
JSON, executes each setup rule against this line's real command tree, checks its value,
and restores the original value in the same test callback. The excluded dev-only
`SoakCommandExecutor` twins distinguish generic parse/dispatch acceptance from strict
gamerule setter-plus-readback success (zero-valued success is valid). Paper preserves
its dimension fan-out and queries the same dimension prefix. Folia's explicitly
acknowledged save-all no-op remains separate. `check_soak.py` rejects failed commands
and gamerule rows without semantic-readback proof; historical `ok=true` alone meant
only did-not-throw and is not acceptable setup evidence. See
[the correction record](../reviews/2026-09-08-implementation/soak-command-validation.md).

### Per-world LOD distance (v0.15.1)

`lodDistanceChunksByWorld` and its runtime commands are available on every support line. Common config, settings, preset isolation and wire-dialect guards are identical. `ServerWorldLod` and `PaperWorldLod` adapt only the dimension accessor (`identifier()` on 1.21.11/26.x, `location()` on 1.21.1/1.21.10); request-service code retains the existing data-version and world-layout seams. Global sizing uses the largest configured radius, while handshake, ingress and dirty broadcasts resolve the current world. The cross-line PR audit is [recorded here](../implementation/v0.15.1-pr-parity-audit.md).

### Sponge (2026-10-10)

The SpongeVanilla plugin (`sponge/`, experimental + best-effort) is the first server-only consumer of `xplat`. Everything under `sponge/` is byte-identical across the lines except `SpongeLineSurface` (the one flavor file) and the two same-FQN twins (`ScopedCarrier`, byte-identical to the line's fabric copy; `ChunkSaveDataHook`, the line's target). The mixin config declares `JAVA_21` on every line because Sponge bundles Mixin 0.8.7, whose compatibility enum ends there (Java-25 class files load fine under it). The access widener declares `named` everywhere: SpongeVanilla runs on Mojang names and its reader applies no namespace check.

| Line | SpongeVanilla pin | Recommended | Compile API | VanillaGradle | FULL lambda | Command elevation |
| --- | --- | --- | --- | --- | --- | --- |
| 26.3 | `26.3-21.0.0-RC2745` | no (RC) | `21.0.0-20261005.191039-3` | 0.3.2 | descriptor-selected | `LevelBasedPermissionSet.GAMEMASTER` |
| 26.2 | `26.2-20.0.0-RC2743` | no (RC) | `20.0.0-20261005.184902-4` | 0.3.2 | `lambda$full$0` | `LevelBasedPermissionSet.GAMEMASTER` |
| 26.1 | `26.1.2-19.0.0-RC2742` | no (RC) | `19.0.0-20261005.184029-8` | 0.3.2 | descriptor-selected | `LevelBasedPermissionSet.GAMEMASTER` |
| 1.21.11 | `1.21.11-18.0.0-RC2741` | no (RC) | `18.0.0-20261002.154539-15` | 0.2.2 | descriptor-selected | `LevelBasedPermissionSet.GAMEMASTER` |
| 1.21.10 | `1.21.10-17.0.0` | yes | `17.0.0` | 0.2.2 | descriptor-selected | `Commands.LEVEL_GAMEMASTERS` |
| 1.21.1 | `1.21.1-12.0.3` | yes | `12.0.0` | 0.2.2 | `lambda$full$2` | `Commands.LEVEL_GAMEMASTERS` |

The FULL lambda is selected by descriptor, `(ChunkAccess, WorldGenContext, GenerationChunkHolder)ChunkAccess`, so the per-line synthetic name never appears in source; `SpongeMixinTargetContractTest` asserts exactly one match that constructs the `LevelChunk`. The `generated` flag is fabric-api's own derivation (`!(proto instanceof ImposterProtoChunk)`) on every line. Refresh chores: a timestamped API snapshot can expire upstream (the build fails loudly at resolution; bump `sponge_api_version`), and an RC SpongeVanilla pin is replaced by the line's first recommended build once Sponge publishes one (update `spongevanilla_version` + `spongevanilla_sha1` together; the boot smoke re-proves it).

### Vanilla send pad and the LOD exclusion (2026-10-14)

The client leaves to vanilla exactly the chunks vanilla delivers together with their whole 8-neighbourhood (`SpiralScanner.isVanillaRendered`; Xaero's native `MapWriter.writeChunk` maps a client chunk only when all 8 neighbours are loaded). It asks the line's own `ChunkTrackingView.isWithinDistance(…, includeOuter=true)` — the send test vanilla `ChunkMap` and Paper/Moonrise's `wantChunkLoaded` both use — at the outward diagonal neighbour, never a copied formula. The send pad is the per-line difference:

| Line | `isWithinDistance(…, true)` | Exclusion (`R` = effective render distance) |
| --- | --- | --- |
| 1.21.1 (Mojang + NeoForge 21.1.248) | `i=max(0,abs(dx)-1)`, `j=max(0,abs(dz)-1)`; `max(0,max(i,j)-1)² + min(i,j)² < R²` (dominant axis padded only) | `max(0,max(abs(dx),abs(dz))-1)² + min(abs(dx),abs(dz))² < R²` — `isInViewDistance` minus the circle-edge seam cells, e.g. (±3,±4)/(±4,±3) at R=4 |
| 1.21.8, 1.21.10, 1.21.11, 26.1, 26.2, 26.3 | `max(0,abs(dx)-2)² + max(0,abs(dz)-2)² < R²` (both axes padded) | exactly `isInViewDistance`: `max(0,abs(dx)-1)² + max(0,abs(dz)-1)² < R²` |

`isInViewDistance` itself is identical on every line. Pin: `VanillaViewExclusionTest` (oracle equality against the line's vanilla, plus a literal per-line shape branch on `minecraft_version`). Before this fix 1.21.1 excluded the whole `isInViewDistance` disc, leaving the seam cells without map data or LOD — the eight black Xaero tiles of the 2026-10-14 C-1.21.1 run. A cross-version Via connection can still disagree with the client's own pad (the client cannot see the server's).
