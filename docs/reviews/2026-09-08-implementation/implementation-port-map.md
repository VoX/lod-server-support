# Implementation port map and final source parity

Read-only snapshot. Commit map derived from baseline..HEAD titles and explicit file lists, then reviewed against WI scope. Production parity uses current file SHA-256; test-only edits may be in flight and are recorded in status. No build/runtime invocation.

## Per-work-item commits

| WI | 1.21.1 | 1.21.10 | 1.21.11 | 26.1 | 26.2 |
| --- | --- | --- | --- | --- | --- |
| 1 Store invalidation | `4b29afd2` | `ef8b81ca` | `15fb4599` | `7e6d08ba` | `85c01017` |
| 2 Registration ownership | `a6562743` | `4c388643` | `7dd7baea` | `fa76056e` | `3759894a` |
| 3 Receive/receipt lifecycle | `cd0790e9`, `9b3fe935`, `3a0c61c2` | `801ed3f4`, `eec2c392`, `4b7229ca`, `89ac90c1` | `7b6b67e5`, `8a253e7a`, `d9df311d`, `c64f67b8` | `7d87d6ac`, `c97c0a4b`, `5c79387a`, `41020c0f` | `e3d67aae`, `71caf90b`, `0d046f42`, `84031716` |
| 4 Summary proof | `d01c3be0` | `f4523435` | `6f2b3b44` | `1eeba0a5` | `d1de559f` |
| 5 Xaero origin/debt | `0c613fca` | `b1f3ab22` | `6587b4da` | `324623fc` | `adfd0426` |
| 6 Far-player OFF/ON | `f84f9f6d` | `9e49dcf7` | `96d25bf5` | `11103640` | `d080f621` |
| 7 Migration integrity | `7a674e0b` | `787aa96c` | `3699ba9a` | `a385c5e0` | `44933a6e` |
| 8 Roster identity/rename | `e3a69850`, `b2756f81` | `f30984bf`, `3c62f570` | `c83634e3`, `fa92367c` | `d4d91b46`, `e7a5e138` | `29bf9bea`, `8eb7af1b` |
| 9 Seated recovery | `1f8bb5be` | Already-correct Fabric; Neo stub | Already-correct Fabric; Neo stub | Already-correct Fabric; Neo stub | Already-correct Fabric; Neo stub |
| 10 Persistence feedback | `8729d674` | `f8d0398b` | `61127067` | `4bae1626` | `20253500` |
| 11 Disguise loader | `e438a633` | `a0b3bbeb` | `5f7166af` | `781cd067` | `8974a7bf` |
| 12 Voxy reset | `6a57b37a` | `7fd5d9e8` | `a4514640` | `9119b989` | `c942fc0e` |
| 13 Harness ownership/provenance | `851479d7` | `390a70d7` | `8886a12f` | `ff1975da` | `20aa7c47` |
| 14 Profiles/docs/C2ME | `e1a38b63`, `8504568d`, `2da753f7`, `5887c45a` | `e6e98ecc`, `592de067`, `7e632b9a`, `28899adb` | `3f238fb3`, `c35213cd`, `471282ef`, `f683bf56` | `141abf64`, `1d691465`, `7caf3303` | `ffc01dac`, `caded5f0`, `9638042b` |
| 15 Wing animation | Direct-model control | `e8a0326f` | `b49f62af` | Existing native tick control | Existing native tick control |

WI3 includes the receipt-cancellation follow-up, receiver recorder correction and supported-line real-client LAN/receipt regression. WI8 includes stationary rename. WI14 includes documentation and the runtime IO/save fixture controls; the C2ME remapping helper applies only to the three obfuscated 1.21.x lines. The initial primary planning/evidence commit is preparation, not a product WI. Full SHA, subject and explicit changed-file lists are in the JSON.

## Candidate snapshots

| Line | Review baseline | HEAD at mapping | Working changes at mapping |
| --- | --- | --- | --- |
| 1.21.1 | `1b544494d66e` | `5887c45a6cee` | 9 paths (see JSON; parent/client own pending docs/test work) |
| 1.21.10 | `d08faa91428d` | `28899adb28f5` | 0 paths (see JSON; parent/client own pending docs/test work) |
| 1.21.11 | `da726358f2c0` | `f683bf56bac6` | 0 paths (see JSON; parent/client own pending docs/test work) |
| 26.1 | `8e900e69e8ce` | `7caf33035992` | 0 paths (see JSON; parent/client own pending docs/test work) |
| 26.2 | `2b2a0df728e2` | `9638042bb543` | 0 paths (see JSON; parent/client own pending docs/test work) |

## Production parity verdict

No missing production port or new semantic drift was found in this bounded comparison. Of 37 production Java paths touched by mapped implementation commits, 19 are byte-identical across all five lines. The 18 remaining paths have documented version/API/render scope distinctions below. This is source parity, not a substitute for runtime acceptance.

### Byte-identical paths

- `common/src/main/java/dev/vox/lss/common/config/JsonConfig.java`
- `common/src/main/java/dev/vox/lss/common/farplayers/FarPlayerBroadcastService.java`
- `common/src/main/java/dev/vox/lss/common/farplayers/FarPlayerClientTracker.java`
- `common/src/main/java/dev/vox/lss/common/processing/AbstractChunkDiskReader.java`
- `common/src/main/java/dev/vox/lss/common/processing/AbstractPlayerRequestState.java`
- `common/src/main/java/dev/vox/lss/common/processing/DedupTracker.java`
- `common/src/main/java/dev/vox/lss/common/processing/IncomingRequestRouter.java`
- `common/src/main/java/dev/vox/lss/common/processing/OffThreadProcessor.java`
- `common/src/main/java/dev/vox/lss/common/processing/RequestRegistration.java`
- `common/src/main/java/dev/vox/lss/common/processing/TickSnapshot.java`
- `common/src/main/java/dev/vox/lss/common/store/SqliteLodStore.java`
- `paper/src/main/java/dev/vox/lss/paper/LibsDisguisesBridge.java`
- `paper/src/main/java/dev/vox/lss/paper/PaperOffThreadProcessor.java`
- `xplat/src/main/java/dev/vox/lss/api/LSSApi.java`
- `xplat/src/main/java/dev/vox/lss/compat/ModCompat.java`
- `xplat/src/main/java/dev/vox/lss/config/menu/SaveHook.java`
- `xplat/src/main/java/dev/vox/lss/networking/client/ClientSessionGate.java`
- `xplat/src/main/java/dev/vox/lss/networking/client/ColumnDelivery.java`
- `xplat/src/main/java/dev/vox/lss/networking/client/ColumnStateMap.java`

### Reviewed differences

| Path | Intentional difference / review |
| --- | --- |
| `common/src/main/java/dev/vox/lss/common/config/RuntimeSettings.java` | 26.2 retains its pre-existing per-world ApplyResult display/repush behavior. persisted outcome is added without replacing mutation-based repush. |
| `fabric/src/main/java/dev/vox/lss/networking/client/FarPlayerRenderer.java` | Scoped render repair, not whole-file parity: 1.21.1 Fabric and Neo add restorePose before seated failure continuation; 1.21.10/1.21.11 Fabric add guarded wing helper. 26.x already ticks native wing state. Other Neo renderers remain intentional stubs. |
| `neoforge/src/main/java/dev/vox/lss/networking/client/FarPlayerRenderer.java` | Scoped render repair, not whole-file parity: 1.21.1 Fabric and Neo add restorePose before seated failure continuation; 1.21.10/1.21.11 Fabric add guarded wing helper. 26.x already ticks native wing state. Other Neo renderers remain intentional stubs. |
| `paper/src/main/java/dev/vox/lss/paper/PaperChunkDiskReader.java` | 1.21.1 uses RegionFileIOThread/PrioritisedExecutor and exclusive max-section conversion; later lines use MoonriseRegionFileIO/Priority and inclusive section accessors. Registration is passed identically. |
| `paper/src/main/java/dev/vox/lss/paper/PaperChunkGenerationService.java` | 1.21.1 ChunkSystem/PrioritisedExecutor versus later PlatformHooks/Priority; location versus identifier spelling. Callback registration, ready data and per-owner counting are preserved. |
| `paper/src/main/java/dev/vox/lss/paper/PaperCommands.java` | 26.2 uses per-world-aware display/repush; all lines append persistence failure feedback without rolling back successful runtime apply. |
| `paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java` | Bukkit split world directories on 1.21.x versus unified 26.x; registry/level/position/payload APIs vary. 26.2 preserves per-world distance and dimension-change config repush. WI2 admission/removal and WI6 disabled control draining match. |
| `xplat/src/main/java/dev/vox/lss/compat/VoxyCompat.java` | Reset ladder fix is identical. Rebuild uses levelRenderer.allChanged through 26.1 and levelExtractor.allChanged on 26.2; some older-line comments differ. No broader reflection ladder or ingestion change. |
| `xplat/src/main/java/dev/vox/lss/compat/XaeroMapCompat.java` | Only meaningful difference is world height accessor: 1.21.1 max build height already exclusive; later maxY requires +1. Origin receipt/debt logic matches. |
| `xplat/src/main/java/dev/vox/lss/networking/client/ClientColumnProcessor.java` | 1.21.1 uses biome Registry and old section accessors; later lines use PalettedContainerFactory/global biome map. Queue epoch and delivery-owner behavior match. |
| `xplat/src/main/java/dev/vox/lss/networking/client/ClientNetGlue.java` | Only current-game data-version accessor spelling differs on 1.21.1. Session/receive reconciliation logic matches. |
| `xplat/src/main/java/dev/vox/lss/networking/client/FarPlayerWingAnimation.java` | New helper exists only on affected 1.21.10/1.21.11 and is byte-identical there. Absence on 1.21.1 direct-model and already-correct 26.x lines is intentional. |
| `xplat/src/main/java/dev/vox/lss/networking/client/LodRequestManager.java` | Only Util package and dimension location/identifier API spellings differ. Cancellation, summary proof and retired-manager publication behavior match. |
| `xplat/src/main/java/dev/vox/lss/networking/server/ChunkDiskReader.java` | 1.21.1 and 26.2 preserve BackgroundIoSubmit seam; middle lines retain inline PriorityConsecutiveExecutor rung. 1.21.1 section bounds differ. Captured registration reaches common read submission on every route. |
| `xplat/src/main/java/dev/vox/lss/networking/server/ChunkGenerationService.java` | 1.21.1 generic TicketType.create/addRegionTicket/removeRegionTicket versus later NO_TIMEOUT FLAG_LOADING radius APIs; position field/record and dimension accessor variations. Ownership/callback/removal semantics match. |
| `xplat/src/main/java/dev/vox/lss/networking/server/FabricOffThreadProcessor.java` | Only ResourceLocation/Identifier naming differs. Registration-aware send guards match. |
| `xplat/src/main/java/dev/vox/lss/networking/server/LSSServerCommands.java` | Command permission API differs by engine generation; 26.2 retains per-world display/repush. Runtime-success persistence-note behavior matches. |
| `xplat/src/main/java/dev/vox/lss/networking/server/RequestProcessingService.java` | Spawn/level/registry/data-version/dimension/position APIs vary; 26.2 retains per-world distance and dimension re-push. WI2 and WI6 control paths match. |

Renderer check: the WI9 commit adds precisely the missing `restorePose(poseStack, passMark)` in both 1.21.1 seated catches. The two WI15 commits invoke the same `FarPlayerWingAnimation.advance(state, newTick)` helper after applying pose inputs; 26.1/26.2 retain their existing once-per-new-tick `elytraAnimationState.tick()`. No intentional Neo renderer stub was replaced. Full renderer files intentionally differ substantially across immediate/submit/extract generations; this review compares the repair scope, not arbitrary line-normalized files.

## Harness and build seams

The ownership lock, owned-process supervisor, current-result manifest utility, mc-run helper, harness regression suite, store/compression gates and multi-phase store wrappers are byte-identical across all five lines. The remaining benchmark/profile script differences are the deliberate absence of `-x runClientGameTest` on 1.21.1, where that task does not exist. `soak.sh` additionally preserves Bukkit split-directory staging on 1.21.x, unified staging on 26.1 and the pre-existing superset world* staging on 26.2. Workflow files retain their own line/JDK/task conventions; no demand for byte equality is appropriate. Hash groups for all 19 harness commit paths are in the JSON.

The C2ME dev-runtime helper is byte-identical on all three 1.21.x lines, with explicit Java21 module selection; 26.x declarations are intentionally untouched. Stock nested dependency/artifact differences are independently checked in `final-artifact-inspection.json`: all 30 exact versioned jars PASS. Common major65 on Java25 platform lines is intentional.

## Limits and follow-through

The parent has confirmed 26.2 explicit client+Neo runtime exit0 / BUILD SUCCESSFUL (1m14), superseding the pending runtime-log observation in `final-ledger-review.md`. Parent owns the current ledger. The client agent is still strengthening test-only persistence premises; these later fixture commits should be appended to the map if committed after this snapshot, without implying another production port is missing. This report makes no genuine soak, visual, optional-plugin or deployment claim. Preserve those statuses from the current ledger and remaining runtime matrix.


## Post-snapshot test, documentation and harness commits

Shipping production path hashes remain unchanged from the map/accepted artifact snapshot. Later commits: primary1.21.1 `e176ac7d` proves persisted gametest fixture premises; `ceb93e60` records acceptance documentation. Dev-only command validation and scenario API corrections:1.21.1 `49c8c689`,1.21.10 `948b0bf0`,1.21.11 `8ac799a9`,26.1 `1e7c9a9d`,26.2 `98b67abc`. Full source/test scope and exact SHAs: `soak-command-fix.md`. The original JSON map is retained as its dated snapshot, not a claim of current branch tips.
