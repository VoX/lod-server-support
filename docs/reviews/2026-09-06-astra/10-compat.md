# Lens 10 — MC 1.21.1 third-party / loader integration

Reviewed `/home/vox/projects/lss-lines/1.21.1`, content-identical to merged `1b544494` (parent verified). Astra, 2026-09-06. No repository edits, builds, live server/client operations or mod changes. Parent executed the external regression.

## COMPAT1 — P2, high confidence: reset cannot resolve the actual 1.21.1 Voxy 0.2.15 holder shape

**Location:** `xplat/src/main/java/dev/vox/lss/compat/VoxyCompat.java:730`–`:733` (fallback interface/method pairing); `:745`–`:752` (permanent unavailable latch). Callers at `:760`–`:762` and `:780`–`:784` consequently disable the Voxy reset and the force-wipe storage probe.

**Trigger / supported version scope:** use the MC **1.21.1** Voxy **0.2.15-beta**, commit `ff9b80ac96b030b21af3e868c1255e0ca9737ec7`, Sodium 0.8.12 port installed in the user's `LSS dev — 1.21.1 NeoForge - Copy For Far Testing` instance. This is a Fabric-format port run through Connector on that NeoForge rig, rather than the older native j-shelfwood 0.2.9 fork. Invoke `/lss reset`, `/vss reset`, or the Voxy force-storage probe. The same shared bridge has the same descriptor gap when this port runs directly on Fabric.

The bridge supports two combinations: new `IVoxyRenderSystemHolder.voxy$shutdownRenderer()` with its static holder accessor, or old `IGetVoxyRenderSystem.shutdownRenderer()` obtained from `Minecraft.levelRenderer`. The actual 1.21.1 0.2.15 port has **`IGetVoxyRenderSystem.voxy$shutdownRenderer()`**, with no `IVoxyRenderSystemHolder`. Thus the first lookup misses the class and the second misses the method; the reset domain latches unavailable even though the needed teardown operation exists. Its own `/voxy reload` bytecode obtains `Minecraft.levelRenderer`, casts to `IGetVoxyRenderSystem`, and invokes **`voxy$shutdownRenderer()`** before shutdown/create/allChanged.

**Impact:** the advertised reset only clears the LSS half; Voxy's existing disk/render state remains. The force command cannot arm through its reset-domain probe either. There is no crash, unsafe deletion, or loss of ingestion from this defect: those failure domains are correctly isolated and the user receives the unavailable fallback. The separate two-handle alias-storage observation still resolves. This is a concrete compatibility gap on the inspected working line port, not a claim that every Voxy build or every NeoForge pairing fails. The older native j-shelfwood 0.2.9 source carries unprefixed `shutdownRenderer()` and is covered by the existing fallback.

**Evidence:**

- Original deployed port: `/mnt/c/Users/Ian/AppData/Roaming/PrismLauncher/instances/LSS dev — 1.21.1 NeoForge - Copy For Far Testing/minecraft/mods/voxy-0.2.15-beta-sodium0.8.12.jar`. Its `fabric.mod.json` explicitly declares Minecraft `1.21.1`, Voxy `0.2.15-beta`, the commit above and Sodium `>=0.8.12-alpha.2+mc1.21.1`.
- Actual mapped output: the same instance's `minecraft/mods/.connector/voxy-0.2.15-beta-sodium0.8.12_mapped_moj_1.21.1.jar`, SHA256 `ef169cc785b898cc53b63b6c63a0a2a11a96565e1951d0ac99cebef610c95384`.
- Saved descriptor and bytecode evidence: `voxy-1211-015-javap.txt`. `IGetVoxyRenderSystem` method at evidence line 1484; its own reload invocation at line 1953. `IVoxyRenderSystemHolder` is absent. All ingest/backlog/storage/config methods consulted by LSS were checked and have the expected MC 1.21.1 descriptors.
- Historical rig log, not a new smoke: `minecraft/logs/latest.log` from **28 August 2026** identifies `Voxy 0.2.15-beta`; lines 277–278 record LSS registering its raw-ingest bridge. This is evidence the pairing reached the bridge, not a claim of current fresh end-to-end validation.
- External regression `probes/compat/dev/vox/lss/compat/VoxyHolderCompatibilityReviewTest.java`, `resetMustResolveTheNamespacedMethodOnTheOldHolderInterface`, injects this exact verified interface/method combination into production `initResetDomain`, with the rest of the existing real-package stubs. Parent ran it: **RED at line 27**, `initResetDomain()` false. Evidence `voxy-holder-probe.log` / `.xml`.

Existing `VoxyCompatTest.resetDomainFallsToTheSecondHolderRungWhenThePrimaryIsAbsent` only supplies the unprefixed old method; primary-rung tests only supply the new class name. Neither exercises this valid intermediate combination. The external fixture is a resolver-shape test, not a graphics/mixin live test.

**Fix / regression plan:** extend the `IGetVoxyRenderSystem` branch to resolve the namespaced method first (or add a separate bounded rung), then retain the unprefixed older fallback. Keep the correct carrier: `Minecraft.levelRenderer`; do **not** treat this interface's `getNullable()` as a holder accessor, since it returns `VoxyRenderSystem`. Preserve all-or-nothing reset-domain publication and renderer-before-instance ordering. Add both interface-name/method-name combinations to the production handle-driver tests, assert a fake old-named carrier's teardown actually runs before shutdown, and retain primary-holder, no-holder abort, thrown teardown, storage-override and forced-path containment tests. Follow with a user-authorized live reset on this exact port when implementation is ready.

**Tradeoff distinction:** degrading reset on an unknown Voxy shape is the correct safety floor and is intentionally implemented. The missing shape is nevertheless present on this line's inspected user pairing and can be supported without weakening that floor. If the release policy intentionally excludes this particular port from reset support, record that bounded feature/support cut rather than treating the current command as validated there.

## Integration coverage / intentional behavior (not findings)

### Voxy ingestion and resource lifecycle

Read all of `VoxyCompat`, `ModCompat`, `VoxyStorageOverride`, relevant `VoxyCompatTest` / `VoxyStorageProbeTest` / `VoxyStorageOverrideTest`, current reset/alias plan, support/spike documents, and the installed port's descriptors/selected bytecode. Also checked the local native fork's `VoxelIngestService`, `WorldIdentifier`, renderer-holder interface and storage implementation for comparison; source from that distinct fork was not substituted for the deployed jar.

- Ingest binds `WorldIdentifier.of(Level)` and the seven-argument `rawIngest` by exact MC class literals. The verified port matches. Null world/false return/ordinary failure reports through `LSSApi.reportIngestFailure`; a linkage-dead bridge continues reporting each column so stamps cannot pretend accepted data exists. Partial section acceptance retries the whole column, intentionally idempotent at the consumer boundary.
- Missing sky light becomes an explicit zero `DataLayer`, preserving LSS's absent-light semantics instead of Voxy's full-daylight interpretation. Client column processing fills omitted sections with air for resync clearing; no new omitted-section ghost finding was established.
- Ingest, backlog, storage observation and reset resolve independently. Unknown optional methods correctly cost only their own capability. Backlog uses the live instance's current service; config render-distance getters widen int/float/double rather than pinning a fragile field primitive.
- Reset reads and cross-checks the live root before teardown, destroys renderer before instance, skips wipe after shutdown failure and attempts recovery. A missing holder aborts before teardown to avoid Voxy's busy-wait freeze. Forced wipe only waives the derived-root equality; exact shown-root binding and containment remain. A vanished instance under a force grant cannot switch to a different derived root. These guardrails are intentional, not broken reset behavior by themselves.
- File walk is non-following, and partial filesystem failures are warned/contained. Lexical storage containment and known override behavior were reviewed against the existing plan; no new supported-normal-operation foreign-delete trigger was established.

### Address / world identity and alias corroboration

Read `ClientWorldSeed`, `WorldSubKey`, `CacheKeyAliases`, `AliasCorroboration`, `AliasLatch` and `ClientNetGlue` key derivation, their tests and the cache-alias-keying/reset plan's review folds. Avoided duplicating the client's accepted same-address/same-seed residual and previously reported cache/master-toggle issues.

- Alias membership normalizes trim/case but intentionally does not strip default ports (SRV can distinguish servers). The canonical raw spelling determines storage; invalid groups drop whole, overlaps/colliding canonical buckets are rejected, and reserved seeded suffixes are escaped.
- Alias applies only after Voxy's live storage directory corroborates the canonical; unobservable/mismatched Voxy falls back. An armed Xaero bridge forces per-address storage. Pure-API/no-Voxy aliasing is the documented user-configured risk.
- Alias is latched per **play join**, not disconnect, so play→config→play reconfiguration gets a fresh decision. The world subkey re-derives at builds and dimension/cache-phase entry; unreadable seed alone carries the prior key, whereas a readable different/zero seed replaces it. Realms/singleplayer/default-off exceptions are explicit predicates.
- Bare-to-seeded adoption happens once per address component and precedes first load on the cache IO queue. Same-seed worlds, seedless lobbies, initial adoption after a historical reseed, and consumer partitions richer than `(address, seed)` are already documented limitations. No new alias/seed correctness finding beyond these was established.

### Moonrise / C2ME / loader seams

Read `MoonriseReadCompat`, `ChunkDiskReader` path-selection/latch/fallback family, `BackgroundIoSubmit`, accessor twins, loader services and entrypoints, NeoForge optional MAIN-thread payload registration and receiver lifecycle. Read `MoonriseReadCompatTest`, `ChunkDiskReaderTest`, seam contracts and the support/read-priority plans. Server session ownership and store read/save findings stay in their existing reports.

- Moonrise's 1.21.1 Fabric name is intentionally `MoonriseRegionFileIO`; Paper's older `RegionFileIOThread` is a different platform fact. The bridge discovers shaded enum classes from the exact seven-argument signature, selects CHUNK_DATA / LOW and adapts callback null/error. No installed Moonrise jar was located for an independent fresh bytecode check in this lens; existing line verification is documented in the bridge. Do not claim a fresh Moonrise runtime gate.
- Moonrise is selected before touching the replaced vanilla worker. Synchronous linkage/adaptation failures latch and fall back; runtime-state and asynchronous failures remain per-read. Config rollback deliberately disables priority protection entirely. Ignoring the cancellable and the pending-write gap of direct vanilla storage reads are accepted, tested/documented choices, not new findings.
- Verified the actual installed server C2ME jar: `/home/vox/projects/lss-port-1.21.1/test-server/fabric/mods/c2me.jar`, metadata `0.4.0-alpha.0.27+1.21.1`, Java >=21, Minecraft 1.21/1.21.1. Extracted its chunk-IO nested jar externally; `MixinStorageIoWorker.onInit` nulls the vanilla mailbox/storage on the C2ME replacement, exactly the condition LSS's one-way fallback/throttle handles. Saved `c2me-1211-027-javap.txt` and `c2me-chunkio-1211.jar`. No new runtime was launched.
- MC 1.21.1's worker is on `ChunkStorage`, with a `ProcessorMailbox` and BACKGROUND ordinal 1; the source/accessor seams carry that actual line flavor. Closed mailbox drops/timeout behavior is an accepted shutdown exposure.
- Loader install precedes shared consumer initialization; physical-client impls replace common impls behind dist gates. Optional NeoForge channels execute on MAIN; no-channel sends intentionally no-op while a missing live connection throws to existing retry logic. Cross-loader channel-announcement constraints, lack of Voxy itself and incompatible Sodium/Voxy packs are not disguised as LSS ingestion failures.

## Rig inventory limit (not an LSS finding)

The dedicated `lss-test-1.21.1/minecraft/mods` currently has no top-level Voxy jar. The dedicated `lss-test-neo-1.21.1` has a top-level `voxy-0.2.16-beta+1.21.11.jar` whose Fabric descriptor declares MC 1.21.11 and contains **no NeoForge TOML**, while `mmc-pack.json` declares MC 1.21.1 / NeoForge 21.1.248. The directory also has historical Connector outputs. This inventory alone is insufficient to claim what the currently launched loader would select or whether any override is configured. Treat those dedicated instances as **unverified current Voxy gate fixtures**, not as proof of an LSS incompatibility or a reason to change deployed mods. COMPAT1 instead uses the separately identified, descriptor-correct 1.21.1 far-testing copy and its actual mapped jar. No mod or runtime changes were made.

## Validation summary

One new deterministic regression executed by parent, RED at its intended assertion; actual dependency metadata/bytecode checked read-only. No fresh Fabric/NeoForge graphical session, reload, reset or server run was performed. The report does not upgrade source checks or historical logs to a current live compatibility certification.
