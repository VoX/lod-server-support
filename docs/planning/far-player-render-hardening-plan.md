# Far-player render hardening — plan (v2, 2026-09-04, review-folded)

Scope: the far-player proxy renderer and its server-side privacy ladder on EVERY line that
ships far players — five lines: `main` (26.2), `support/mc26.1-v0.14` (26.1),
`support/mc1.21.11-v0.14`, `support/mc1.21.10` (a released line: `v0.14.0+mc1.21.10` exists),
`support/mc1.21.1`. Fabric renders on all five; the NeoForge twin renders on 1.21.1 only (the
other four NeoForge twins are 25-line `RENDER_AVAILABLE = false` stubs) — but NeoForge SHIPS
on main and 26.1 too (`LINE_SHIP_NEOFORGE=true`, six jar families in `release_check`), so the
server-side privacy items reach NeoForge users on three lines. Order: the 1.21.1 line first
(branch `fix/far-player-light-floor`, stacked on `fix/vss-branding-sweep` = `support/mc1.21.1` +
the NeoForge render), then the four ports in two clusters.

Status: v2 after the 1-Fable + 4-Opus panel (§8 records what changed). Phase 1 (the 1.21.1
line) IMPLEMENTING on `fix/far-player-light-floor` since 2026-09-04.

## 0. What we know (2026-09-04 investigation, two-agent verified, panel re-verified)

Full record: project memory `far-player-1211-render-bugs`. Decompiled-source archives used
below (`~/.gradle/caches/neoformruntime/intermediate_results/transformSources_<hash>_output.zip`):
1.21.1 = `018e6231…`, 1.21.10 = `68d0aba3…`, 1.21.11 = `303e151c…`, 26.1.2 = `8ebe0811…`,
26.2 = `d674482d…`/`d5bba6c4…`. (v1 mis-cited the 1.21.11 archive as 26.2; every line-specific
API fact below was re-read from the right archive.)

- **F1 — the 1.21.1 light split is dead code, and so is the handoff's `hasChunk` conjunct on
  every line.** `ClientLevel.hasChunk(int,int)` is `return true` on 1.21.1 (`:343`) and on 26.2
  (`:538`), so `packedLightFor`'s `pack(15,15)` branch never runs and every proxy takes vanilla's
  `EntityRenderDispatcher.getPackedLightCoords` = the client light engine at the proxy's eye. The
  26.x/1.21.1x lines reach the identical lookup through `dispatcher.extractEntity`
  (`EntityRenderer.extractRenderState` sets `lightCoords = getPackedLightCoords(...)`).
- **F2 — no data → sky 15 / block 0; data → the stored value, and an `emptySkyYMask` section
  decodes as `new DataLayer()` = all ZEROS** (`SkyLightSectionStorage.getLightValue(pos,false)`,
  `BlockLightSectionStorage`, `ClientPacketListener.readSectionList`).
- **F3 — the proxy-only band.** `RemotePlayer.shouldRenderAtSqrDistance` is bbox×10 (~640 blocks),
  so vanilla draws every TRACKED real player; the proxy exists only beyond the server's
  entity-tracking radius `min(512·pct, clamp(requestedVD, 2, serverVD)·16)` ∩ the tracked-chunk
  view (`ChunkMap.updatePlayer` 1.21.1 `:1327-1336`, `getPlayerViewDistance` `:840-842`). With
  C2ME `notickvd` on the server (user-confirmed) chunks are SENT beyond the tick view distance
  while entity tracking stays inside it (its ChunkMap mixins touch only the send/drop bookkeeping,
  never `updatePlayer`/`isChunkTracked`) → a ring where the client HOLDS the chunk with whatever
  light C2ME's threaded lighting had baked when it was sent (often zeros) but has no real entity.
  The proxy there reads sky 0 → BLACK in daylight; that ring's terrain is never drawn (outside
  Sodium's circular render distance, Voxy paints LOD over it) so nothing else looks dark. Past
  the client's drop radius (~200 blocks at rd 12) the chunk is gone → F2 → lit. That is the
  user's "near black, farther lit". Same exposure on every line. Live check must record the
  server's `view-distance` and c2me `noTickViewDistance` (its `ext_render_distance` mixin may let
  a requested VD above 32 through, which moves the tracking radius).
- **F4 — refuted:** night/enclosure, a buried light probe (a standing body would be invisible;
  wire Y is feet at 1/16 block, unbiased), dead-reckoning (≤0.9 s dips), and the deferred-flush
  GL desync: Iris 1.8.14's batched entity renderer (`MixinRenderBuffers` →
  `FullyBufferedMultiBufferSource`, no mixin plugin, so active with shaders OFF too) defers ALL
  entity geometry, ours and vanilla's, to the same final `endBatch()`; a desync would hit real
  players too. On an Iris-free stack our last shared-type batch is drawn by whichever block
  entity next requests a shared type — cheap hardening remains sensible (WI-4).
- **F5 — issue #268 (proxies over Voxy LODs, NeoForge 1.21.1 + Voxy 0.2.16 via Roxy):** shaders
  OFF is depth-sound (Voxy renders inside vanilla's `solid()` call via Sodium's cutout pass,
  BEFORE entities, and blits LOD depth re-projected into vanilla's projection with LEQUAL;
  past-far-plane LODs clamp to 1−ε). Shaders ON with Complementary: `ComplementaryUnbound_r5.8.1
  .zip` `shaders/program/voxy.json:7` sets `"excludeLodsFromVanillaDepth": true` → Voxy's Iris
  pipeline never writes LOD depth → every entity, proxies included, draws over LODs (Voxy's
  `IrisShaderPatch.emitToVanillaDepth() = !excludeLodsFromVanillaDepth`). Iris also extends the
  far plane. Not fixable by LSS render ordering. Without shaders the vanilla far plane is
  `renderDistance × 64` blocks (rd 12 → 768; `GameRenderer.getDepthFar()`) and Voxy 0.2.16 does
  not extend it → proxies beyond are clipped, whatever `farPlayersMaxRenderDistanceBlocks` says.
  Note for the live check: the user's instance has `enableShaders=false` and its selected pack
  (Reimagined r5.7.1) is not installed — only Unbound r5.8.1 is; select it for the shaders-on run.
- **F6 — side defects (panel-corrected):** (a) proxies never set the model-parts byte → hat/
  jacket/sleeve/pants layers invisible on every proxy, every line. (b) **vanilla gates name
  tags at 64 blocks on EVERY line** — 1.21.1 in `LivingEntityRenderer.shouldShowName` (64/32 sneak)
  plus `renderNameTag`'s own `4096.0` check; 1.21.10/1.21.11/26.1 in
  `EntityRenderer.extractRenderState` (`distanceToCameraSq < 4096.0`); 26.2 in
  `extractNameTags(…, 64.0, 10.0)`. Proxies exist only beyond ~127 → `farPlayersNameTags` is inert
  on all five lines; `renderState.nameTag`/`nameTagAttachment` are already null at proxy range.
  (c) NeoForge's `RenderNameTagEvent` + `isNameplateInRenderDistance` gate inside
  `dispatcher.render` also caps living entities at 64.
- **F7 — prior art, SeeU (cat4blep/SeeU; `research/seeu`, branches `backport-1.21.1`/`1.21.11`
  fetched).** LICENSE: SeeU relicensed to `LicenseRef-SeeU-Restricted-1.0` (all rights reserved,
  no derivative works, no reuse of source) at 0.8 (`4d1439e`); earlier MIT releases keep MIT; the
  `backport-1.21.1` head ships NO license file. **This plan copies no SeeU code, structure or
  snippet; every item is reached independently from vanilla APIs and observable facts.** What we
  learned by observation: its 1.21.1 backport passes vanilla's `LightTexture.FULL_BRIGHT` for
  every proxy and vehicle (no black reports in its tracker); it frustum-culls through
  `dispatcher.shouldRender` (which carries vanilla's distance term — wrong for far mounts); it
  reflects into Melius Vanish on Fabric (with a fail-open throw path — the bug not to repeat);
  it ships a vanilla-fog disable (broke Voxy fog once, its issue #9) and `setGlowingTag(true)`
  (rejected by LSS for privacy); it tried a reflective Voxy raycast + depth readback for LOD
  occlusion and reverted it the same day; its issues #2/#4 are the 640-block cull and the
  768-block far plane, closed unresolved. `far-player-proxies-plan.md:306` ("SeeU is MIT-licensed")
  is stale and gets corrected (WI-11).
- **User decision (2026-09-04):** render proxies BRIGHTER than possibly correct rather than dark.

## 1. Goals / non-goals

Goals: (1) proxies never render dark whatever light data the client holds; (2) the
independently-reached pure wins: a full-bright option, frustum culling, Fabric/NeoForge
hidden-permission + Melius Vanish parity with Paper; (3) make `farPlayersNameTags` real on every
line (it is inert everywhere today); (4) fix the skin-layer defect; (5) end our own shared batch
on the immediate-mode line; (6) resolve #268 honestly (document, reply, decide on the optional
occlusion feature); (7) identical behaviour on Fabric and the NeoForge 1.21.1 twin; (8) a diag
instrument so the next regression is measurable, not eyeballed.

Non-goals: no wire/protocol change (verified: the `lss:far_player_*` channels, `FarPlayerWire`,
the capability bit and the prefs carrier are untouched; `WireParityTest`'s channel census would
red otherwise); no fog mixin (Roxy already forces no-fog under Voxy; SeeU's broke Voxy fog); no
glow; no far-plane extension (Voxy does not extend it — a proxy past the far plane is invisible,
documented); no change to the handoff predicate or the mount ladder semantics (E2/E3 pins); no
copying of SeeU code (F7).

## 2. Work items

Every 1.21.1 renderer edit lands in the Fabric file (SOURCE OF TRUTH) first, then the NeoForge
twin as the identical hunk. There is NO byte-identity pin between the two renderers
(`NeoForgeModuleContractTest.BYTE_IDENTICAL_TWINS` does not list them; `VersionVolatileFileListTest`
pins existence only); the content pins are `NeoForgeLoaderSeamContractTest`'s substrings —
`RENDER_AVAILABLE = true`, `public static void initRenderer()`, `RenderLevelStageEvent.Stage
.AFTER_ENTITIES`, `EntityJoinLevelEvent`, `isClientSide()`, `capabilityBit() == 0`,
`effectiveFarPlayersEnabled()`, `catch (Throwable`, `crashLatched`, `crashLatched = false`,
`mountLadder.reset()`, and the per-player containment strings — every edit keeps them.
`per-version-surfaces.md` documents the twin as "byte-verbatim except loader plumbing" and
enumerates the deltas; WI-3 adds one (the frustum source) — the row is updated (WI-11).

### WI-1 Light floor (all lines) — the black-player fix

- What: proxy light = `pack(block(vanilla), 15)` with `vanilla = dispatcher.getPackedLightCoords(
  entity, partialTick)`. Sky FLOORED to 15 (a proxy stands in for LOD terrain, which Voxy lights
  as sky-lit); real block light kept (a torch-lit far player at night still reads lit). Proxies
  AND mounts. Because we still call `getPackedLightCoords`, third-party `@ModifyReturnValue`
  hooks on it (Sable's sub-level lighting) apply before the floor — say so in the javadoc.
- Class per line: `net.minecraft.client.renderer.LightTexture` on 1.21.1/1.21.10/1.21.11;
  **`net.minecraft.util.LightCoordsUtil`** on 26.2/26.1 (`LightTexture` does not exist there;
  same members `FULL_BRIGHT/pack/block/sky`).
- 1.21.1 both twins: `packedLightFor(dispatcher, entity, partialTick)` (3 call sites each); delete
  the dead split; javadoc records F1-F3 + the decision.
- 26.2/26.1/1.21.11/1.21.10 Fabric: after each `dispatcher.extractEntity(...)` (proxy ×2, mount ×1)
  set `renderState.lightCoords = floor(renderState.lightCoords)` before `dispatcher.submit(...)`
  — `lightCoords` is a public int on all four and `LivingEntityRenderer.submit` reads it at draw
  time (verified per archive), so set-after-extract takes effect.
- Nether/End: no sky engine → the floor makes proxies sky-15 there too (by design).
- Tests: no JUnit can exercise the renderer. Pin the DECISION: new
  `fabric/src/test/java/dev/vox/lss/testutil/FarPlayerRenderSourceContractTest.java` (the home of
  the twin/version-volatile discipline; it needs its own parent-walk resolver — the catalog test's
  `locate` is package-private) asserting the POSITIVE floor expression in the Fabric renderer
  and the file-wide ABSENCE of `LightTexture.pack(15, 15)`; the NeoForge half asserted ONLY when
  the twin contains `RENDER_AVAILABLE = true` (the four port lines have stubs — the
  `ClientMenuEntrypointContractTest` "iff the file exists" idiom). Do NOT assert `hasChunk`
  absence (it stays in the handoff predicate by the non-goal). The expected strings are per line
  (`LightCoordsUtil` vs `LightTexture`) — a hand-mirrored line fact like the other contract tests.
- Risk: none functional (strictly brighter).

### WI-2 `farPlayersFullBright` option (all lines)

- What: client boolean `farPlayersFullBright`, **default `false`** (panel unanimous: WI-1 already
  kills black; full-bright at night is a beacon; the repo's rule for a new visible behaviour is
  the cautious default — `LSSClientConfig` `enableXaeroMapBridge` precedent). `true` → proxies
  and mounts use vanilla's `FULL_BRIGHT` constant (`LightTexture`/`LightCoordsUtil` per line);
  `false` → WI-1's floor. Render-only in the sense that it is NOT a capability-bit term and NOT
  in the prefs content.
- Catalog row (`ClientOptionCatalog.farPlayersPage()`, id `lss:far_players_full_bright`, boolean,
  after the name-tags row): `.visibility(Visibility.RENDER_AVAILABLE)`, `.impact(Impact.LOW)`,
  `.enabledBy(ID_FAR_PLAYERS_ENABLED)`, and **`.saveHook(SAVE_AND_PUSH_FAR_PLAYER_PREFS)` like
  every far-players row** — the page-uniform push hook is a pinned invariant
  (`ClientOptionCatalogTest:140-143`, `LSSConfigMenuTest:159,166-167`, `LegacySodiumPageTest:206`)
  and the push is a no-op because `maybeSendPrefs` dedupes on unchanged prefs content.
- Lang: `lss.config.far_players_full_bright` + `.tooltip` in `en_us.json`, `zh_cn.json`,
  `zh_tw.json` — the zh files carry REAL translations (28 keys each today), so provide genuine
  zh_cn/zh_tw text; NeoForge derives its lang files from the fabric tree (the "derived from the
  fabric tree like the icon" block in `neoforge/build.gradle`). Tooltip wording: avoid the literal
  "LSS" unless the brand rewrite to "VSS" is intended (`release_check`'s `_check_vss_lang_rebrand`
  rebrands every value). Mention that LSS's own tag draw ignores third-party name-tag
  suppressions (WI-6) in the NAME-TAGS tooltip, not here.
- Tests to change: `ClientOptionCatalogTest` (`renderOnly` ordered list → four options, id census
  `:165-169`, page size `List.of(5)` → `6`), `LSSConfigMenuTest:80` (9 → 10), `LegacySodiumPageTest`
  (`:122` 4 → 5, `:133` 5 → 6), `Visibility.java:12-15` javadoc ("three render-only options" →
  four), CLAUDE.md's same enumeration. Recommended: extend
  `everyTranslationKeyExistsInTheLangFile` to assert identical key sets across the three lang
  files (nothing pins zh parity today).
- 1.21.10 note: its Sodium 0.8 walker + `sodium:config_api_user` entrypoint are CUT — the row
  renders only through the legacy 0.6/0.7 page there.

### WI-3 Frustum culling (1.21.1 both twins, 26.2, 26.1; SKIPPED on 1.21.11/1.21.10 in this cut)

- What: skip the DRAW CALLS ONLY when the culling box is outside the frame frustum. Everything
  else in the loop runs unchanged — `apply`, `mountFor`, the ride link, `activeVehicles.add`,
  `submittedVehicles.add` + `applyMountState` — because the frame-end sweep evicts any
  `MountInstance` no rider referenced (`vehicles.keySet().removeIf(!activeVehicles.contains)`):
  culling the whole body would recreate mounts every frame (the per-frame re-seat E3/R-10
  forbids) and a culled rider could claim `submittedVehicles` first and starve a visible second
  rider's mount. The mount's own frustum test runs immediately before ITS render, after
  `applyMountState`. A culled proxy stays in `active`/`proxies`.
- Predicate shape: 1.21.1 — `proxy.noCulling || frustum.isVisible(proxy.getBoundingBoxForCulling()
  .inflate(0.5))` with vanilla's NaN/zero-size fallback box (`EntityRenderer.shouldRender`).
  26.2/26.1 — `Entity.noCulling`/`getBoundingBoxForCulling` do NOT exist (they moved to
  `EntityRenderer` as protected members): use `frustum.isVisible(entity.getBoundingBox()
  .inflate(0.5))` with the same fallback. Never `dispatcher.shouldRender`: it carries vanilla's
  distance cull (kills far mounts) and **Sable injects into `EntityRenderer.shouldRender`**
  (`sable$shouldRender`) — record both reasons in the javadoc.
- Frustum source per line: 1.21.1 Fabric `context.frustum()` (`@Nullable` → null = visible);
  1.21.1 NeoForge `event.getFrustum()`; 26.2/26.1 `context.levelState().cameraRenderState.cullFrustum`
  (a public field, non-null-initialised → guard on `cameraRenderState.initialized`);
  **1.21.11/1.21.10: NO frustum on the render path** (`WorldRenderContext` there is
  `commandQueue/matrices/consumers/worldState/…`, `CameraRenderState` has no frustum, and
  `LevelRenderer`'s is a private local). It is reachable only from `WorldExtractionContext.frustum()`
  on `WorldRenderEvents.END_EXTRACTION` — a second event registration that stashes the frame's
  frustum, with a null/stale-stash = visible rule. Decision: SKIP WI-3 on those two lines in this
  cut, record it in their per-version-surfaces, keep the stash approach as the recorded follow-up.
- Tag geometry: the WI-6 tag can be on screen while the body's box is just off screen; draw the
  tag iff the body passed OR a small box around the tag anchor is visible.
- Tests: source pin of the predicate (line-specific text) in the WI-1 test.

### WI-4 End our own shared batch (1.21.1 both twins only) — hardening, near-zero value

- Panel finding: after `endLastBatch()` the four typed `endBatch(entity*)` calls are no-ops on
  vanilla (one shared type at a time; unstarted fixed buffers are no-ops) and the whole item is
  inert under Iris (`FullyBufferedMultiBufferSource` overrides `getBuffer`, so `lastSharedType`
  is never set and `endBatch(RenderType)` is a bare `return`).
- What: after the proxy loop (before pruning) call **`bufferSource.endLastBatch()` only**, guarded
  `instanceof MultiBufferSource.BufferSource` on Fabric (`context.consumers()` is declared
  `MultiBufferSource`; Iris's class extends `BufferSource`). Never a bare `endBatch()` — it drains
  the fixed glint/translucent buffers early (the existing javadoc's warning stands).
- Javadoc: state plainly that this ends the batch OUR proxies opened on Iris-free stacks and is
  inert otherwise; do not claim it fixes the reported bug. Fabric's AFTER_ENTITIES injection is at
  the `"blockentities"` string constant = after vanilla's own flushes (verified from the
  fabric-rendering-v1 0.116.15 mixin), the same boundary as NeoForge's dispatch.
- Docs: this SUPERSEDES the review-folded "NO explicit flush" decision — add a dated SUPERSEDED
  block to `neoforge-1.21.1-far-player-render-plan.md` §3/§9/§10 distinguishing the still-forbidden
  arg-less `endBatch()` from `endLastBatch()`, and rewrite `per-version-surfaces.md`'s "NO explicit
  buffer flush" clause. (WI-11.)

### WI-5 Skin overlay layers (all lines)

- What: per frame in `apply`, after `applyEquipment`: `getEntityData().set(
  DATA_PLAYER_MODE_CUSTOMISATION, chestIsElytra ? (byte) 0x7F : (byte) 0x7E)`. Rationale: CAPE
  (bit 0) stays OFF because `CapeLayer` positions the cape from cloak fields only `Player.tick`
  updates (`xCloak…`; on 26.x `ClientAvatarState.getInterpolatedCloakX`) — never run on a
  render-only proxy, so an enabled cape renders relative to the origin; but `ElytraLayer` selects
  the cape-textured elytra via `isModelPartShown(CAPE)` and `CapeLayer` bails when the chest item
  is an elytra, so 0x7F is safe exactly then. `PlayerModelPart` bits are identical on all lines.
- Owner class: `Player.DATA_PLAYER_MODE_CUSTOMISATION` on 1.21.1;
  **`net.minecraft.world.entity.Avatar.DATA_PLAYER_MODE_CUSTOMISATION`** on 26.2/26.1/1.21.11/
  1.21.10 (`Player extends Avatar`; `protected static` → reachable from the `RemotePlayer` subclass).
- Compat: skinlayers3d gates its 3D layers on `renderDistanceLOD` (14 blocks default) → inert
  for far proxies; ETF/EMF do not read the byte.
- Tests: source pin (`0x7E`, `0x7F`, the cape rationale).

### WI-6 Name tags that actually show (all lines — LSS draws its own)

- Why own-draw: F6(b) — vanilla can never draw a tag for a proxy on any line. There is no
  vanilla fallback to keep.
- Design (all lines): content = the tracked name (cache the `Component`/`FormattedCharSequence`
  per proxy — no per-frame `Component.literal`; drop the dead `setCustomName`, force
  `setCustomNameVisible(false)` once in the constructor); anchor = `proxy.getAttachments()
  .getNullable(EntityAttachment.NAME_TAG, 0, yRot)` + 0.5 (computed by LSS — `renderState
  .nameTagAttachment` is null at proxy range), billboarded with the camera orientation; scale
  `(s, -s, s)` with **`s = 0.025 × clamp(sqrt(d / 64), 1, 8)`** (a fixed rule, no option:
  constant-apparent-size linear scaling turned a two-pixel body into a full-size plate — an ESP
  HUD look; sqrt keeps tags legible while preserving the depth cue; revisit the constant after the
  live check); depth-tested NORMAL mode only (stricter than vanilla's see-through pass — a
  deliberate privacy choice, recorded); colour −1 with vanilla's `getBackgroundOpacity(0.25F)`
  background (vanilla's NORMAL pass draws no background — deliberate legibility deviation,
  recorded); hidden when the pose flags carry `POSE_SNEAK` (a NEW LSS policy, not vanilla parity
  — vanilla's discrete cap lives in code that never runs for proxies; "sneak = don't advertise
  me"); lit with the proxy's WI-1/WI-2 light; gated per frame on `farPlayersNameTags`. Collect
  `(pose, text, light)` during the loop and draw ALL tags after it (on Iris-free stacks each
  entity→text→entity switch flushes; the per-frame bound is `MAX_UPDATE_ENTRIES` = 1024 proxies,
  not the visible cap). Tags bypass third-party suppressions (Sodium Extra `player_name_tag`,
  PuzzlesLib's callback, EntityCulling's through-walls option) — say so in the option tooltip.
  Under Iris the text is drawn in Iris's own order — the live check on the user's stack (Iris
  present, shaders off) is mandatory.
- 1.21.1 both twins: a private helper mirroring `EntityRenderer.renderNameTag`'s math —
  `Font.drawInBatch(Component, x, y, color, dropShadow, Matrix4f, MultiBufferSource,
  Font.DisplayMode.NORMAL, backgroundColor, packedLight)` (`Font.java:102-113`), `Minecraft.font`,
  `EntityRenderDispatcher.cameraOrientation()`, `Options.getBackgroundOpacity`. NeoForge's
  `RenderNameTagEvent` still fires inside `dispatcher.render` for the proxy and resolves to no
  vanilla tag beyond 64 blocks; nametag mods that force tags on would double-draw within 64 only,
  where proxies do not exist.
- 26.2/26.1/1.21.11/1.21.10: **`SubmitNodeCollector.submitText(PoseStack, float x, float y,
  FormattedCharSequence, boolean dropShadow, Font.DisplayMode, int lightCoords, int color, int
  backgroundColor, int outlineColor)` — identical 10-arg signature on all four** — with OUR pose
  (translate anchor+0.5, `mulPose(cameraRenderState.orientation)`, scale); it captures the pose
  matrix verbatim. NOT `submitNameTag`: it is 7-arg on 26.2 and 8-arg on the other three, applies
  its own translate-then-scale (so a pre-scaled pose needs `y' = (a.y + 0.5)/f − 0.5`, v1's
  "divide the attachment" was wrong by `0.5·(f−1)` blocks), and no-ops on a null attachment.
  Background-opacity source differs (26.2 `gameRenderer.gameRenderState().optionsRenderState
  .getBackgroundOpacity`; 1.21.x `options.getBackgroundOpacity`); `texts` drain one phase after
  `nameTags` (harmless). Prototype the 26.2 call before committing the cluster.
- Tests: source pins (helper present, `setCustomNameVisible(false)`, `DisplayMode.NORMAL`, the
  sqrt rule constant). Option stays in the catalog unchanged; its tooltip gains the bypass note.

### WI-7 Privacy parity on Fabric + NeoForge servers (all lines, server side)

- **WI-7a hidden-node parity — fail-VISIBLE.** `FabricFarPlayerSnapshots` (an XPLAT file — one
  edit serves Fabric AND NeoForge; it must call `LoaderServices.get().checkPermission`, and
  `XplatLoaderPurityTest` bans loader package names anywhere in the source text, comments
  included) hardcodes `hidden=false`. Change: extract `static boolean hiddenFor(Predicate<String>
  check)` = `check(lss.farplayers.hidden) || check(vss.farplayers.hidden)` (pure, JUnit-testable —
  the fabric test module has no mocking framework) and call it with `node -> LoaderServices.get()
  .checkPermission(p, node, false)`. Fail direction: **VISIBLE** — the seam contracts
  "implementations NEVER throw; doubt answers the default", so `false` cannot express "the backend
  threw", and `true` would hide every player on any provider-less server. Paper's fail-HIDDEN
  exists only for Folia's cross-region `PermissibleBase` race; Fabric/NeoForge read on the single
  server tick thread. Document the asymmetry in both places. fabric-permissions-api semantics:
  undefined → the passed default, so `false` matches plugin.yml's `default: false` (both spellings
  already declared there on every line; `PluginYmlContractTest` + `release_check` dual-spelling
  pins stay green). Constants: `LSSPermissions.FARPLAYERS_HIDDEN_LSS/_VSS`; route Paper's literals
  through them + a `PluginYmlContractTest` tie-back. NeoForge: `LSSNeoPermissions` registers the
  two nodes with **DEFAULT-FALSE** resolvers at `PermissionGatherEvent.Nodes` (an unregistered node
  THROWS `UnregisteredPermissionException` — verified in `PermissionAPI` bytecode) and `check()`
  maps them; update `LSSNeoPermissionsContractTest`'s literal pins (`event.addNodes(SERVICE_LSS,
  SERVICE_VSS)`, `? SERVICE_VSS : null`) in the same commit. Javadocs that become false and must
  change together: `LSSPermissions:13-16` ("default-TRUE everywhere"), `LoaderServices:45-51`,
  `LSSNeoPermissions` class doc ("UNCONDITIONALLY default-TRUE"), `FarPlayerBroadcastService:75-80`,
  `FabricFarPlayerSnapshots:76-78`; `LoaderPermissionSeamContractTest` gets a comment that the
  privacy node is the one deliberate `false` default. Upgrade note (release notes + README): with
  LuckPerms, a wildcard grant (`*`, `lss.*`) resolves the hidden node TRUE, so wildcard admins
  disappear from far-player rendering on Fabric/NeoForge after upgrade (Paper already behaves so).
- **WI-7b Melius Vanish bridge — fail-HIDDEN.** Real API (fetched, identical on Melius `main`,
  `1.21`, `1.21.2`): `VanishAPI.canSeePlayer(@NotNull ServerPlayer actor, @NotNull ServerPlayer
  observer)` — `actor` = LSS's TARGET, `observer` = LSS's VIEWER; UUID overload `canSeePlayer(
  MinecraftServer, UUID actor, ServerPlayer observer)` (prefer it: target stays a UUID, only the
  viewer needs `getPlayerList().getPlayer`); `isVanished(Entity)` / `isVanished(MinecraftServer,
  UUID)` (there is NO `isVanished(ServerPlayer)`). `VanishManager.canSeePlayer` already starts
  with `isVanished(actor)` → true for non-vanished targets; still memoize `isVanished(server,
  targetUuid)` once per broadcast pass and call `canSeePlayer` only for vanished targets (the seam
  sits inside `isVisible` = V×T calls per pass). New xplat `MeliusVanishBridge`: pure
  `Class.forName("me.drex.vanish.api.VanishAPI")` (no `isModLoaded`), MC parameter types bound with
  class literals (`MinecraftServer.class`, `ServerPlayer.class`, `UUID.class`) never
  `Class.forName` on MC names; mapping `canSee(viewer, target) → canSeePlayer(server, targetUuid,
  viewerPlayer)`; absent mod → visible; present-but-throwing (`Throwable` minus
  `VirtualMachineError`, the `FabricPermissionsBridge` idiom — a version mismatch surfaces as a
  `LinkageError`) → HIDDEN + once-warn (`volatile`, server tick thread on both loaders).
  **Wiring: the `FarPlayerBroadcastService` field initializer at `RequestProcessingService:93-94`
  runs BEFORE the constructor sets `this.server`** — pass a lambda that reads `this.server` at
  invoke time, never a bridge instance built from `server` at field init (a null there is swallowed
  by `tickFarPlayers`' once-warn containment → far players silently dead). Paper's site stays
  null (its vanish is the metadata read). Tests: `MeliusVanishBridgeTest` against a real-package-name
  stub in `fabric/src/test/java/me/drex/vanish/api/VanishAPI.java` (an interface with static
  methods delegating to a `dev.vox.lss.testutil` holder — interface fields are final), covering
  present/absent/throwing AND an order-sensitivity case (the stub returns false for exactly one
  `(actor, observer)` pairing so a swapped argument order REDS — the SeeU-order trap);
  `FarPlayerBroadcastServiceTest.filterLadderExcludesEveryIneligibleShape` already drives the seam.
  Optional: a `far_players.vanish_dropped` counter.

### WI-8 Issue #268 — honest resolution

- Reply (post only after user approval; the panel's plain-style rewrite, §7).
- README note ("Far players, shaders and LODs"): with Iris, packs whose Voxy patch excludes LOD
  depth draw players over LOD terrain; proxies beyond vanilla's far plane (`render distance × 64`
  blocks; Iris extends it) are not drawn; `farPlayersMaxRenderDistanceBlocks` cannot exceed that.
- Code: none. WI-9 is the optional real fix.

### WI-9 (OPTIONAL, deferred) Client-side LOD occlusion of proxies

- Idea: a per-column max-height map filled from LSS's own delivered columns, a camera→eye ray
  march, cull occluded proxies (and their tags). Works with and without shaders.
- Holes recorded now (panel): (a) it is COSMETIC, not privacy — the position still crosses the
  wire and a modified client ignores the cull; never describe it as closing #268's privacy angle;
  (b) a WARM rejoin delivers no column bodies (`UP_TO_DATE`, the store and header freshness rungs
  answer without bytes) so the map is empty for exactly the clients with the most LOD terrain —
  persistence beside `ColumnCacheStore` is a prerequisite; (c) a per-chunk MAX height occludes
  every ray under a ceiling (Nether roof) and self-occludes under trees — skip ceilinged
  dimensions, exclude the samples nearest the target, require ≥2 consecutive occluding samples.
- Decision: defer; revisit after the #268 reporter answers (shaders-on → this is the only fix;
  shaders-off → F5 says something else is going on and this would mask it). Its own plan/PR.

### WI-10 Cleanups and the observability instrument

- 1.21.1 both twins: remove the dead `packedLightFor` split (WI-1); note in the handoff javadoc
  that `hasChunk` is unconditionally true (true on 26.2 as well — the note goes on every line;
  the predicate SHAPE stays); rewrite the flush paragraphs (WI-4); correct the false premise.
- Client diag: the `FarPlayers:` line gains `drawn= culled= mounts= tags= light=floor|full` so
  WI-1/3/6 have a live-gate instrument (the original bug was invisible until eyeballed).

### WI-11 Docs, decisions, notes

- Decisions convention: `far-player-proxies-plan.md` has NO decisions log — the log lives in the
  program progress doc (`v0.11.0-progress.md` `## Decisions log`) with a §6.1 "pair" amendment
  inline at the superseded paragraph. v0.14.x has no progress doc, so this plan carries its own
  `## Decisions log` (§9) as the dated record, plus the inline amendments: the light floor + the
  full-bright option; name tags (own-draw, sqrt scale, NORMAL only, sneak-hide); hidden-node parity
  (fail-visible) + Melius (fail-hidden); WI-4's SUPERSEDED block in
  `neoforge-1.21.1-far-player-render-plan.md` §3/§9/§10; "no fog mixin" and "no far-plane mixin"
  reaffirmed; `far-player-proxies-plan.md:306` "SeeU is MIT-licensed" → the F7 licence facts.
- `per-version-surfaces.md` (per line, hand-edited — never cherry-picked): the 1.21.1 NeoForge
  render row loses "NO explicit buffer flush" and gains the WI-3 frustum delta + WI-6; the
  1.21.11/1.21.10 rows record "frustum cull skipped (no frustum on the render path)".
- README (per line — all five README blobs differ): the client keys are PROSE, not a table — add
  `farPlayersFullBright` + the now-real name tags there; fix the two overclaims (`README.md:3`
  "with name tags" — false until WI-6; `:72` "a hide permission, and vanish plugins are honored" —
  false on Fabric/NeoForge until WI-7); the WI-8 shaders/far-plane note; the wildcard-admin
  upgrade note.
- CLAUDE.md: `:395` "(Fabric only)" is stale on 1.21.1; `:397` "three render-only options" → four;
  the far-players config bullets; the `FarPlayerRenderer` entry (light floor, culling, own tag,
  batch end, diag).
- Release notes: the shipped format is `docs/planning/release-tag-v<x.y.z>-mc1.21.1.txt` —
  `### Category` headers, `- **Bold summary.**` + one or two user sentences, no em dashes, loader
  prefixes, and a `### Compatibility` section naming the best-effort tier and any cut (the WI-3
  skip on 1.21.11/1.21.10 is a cut to name there). Draft bullets: Bug Fixes — far players no
  longer render black when standing between your server's simulation distance and your render
  distance; far players show every skin layer. New Features — far player name tags now show and
  stay readable at distance; Full Bright option for far players; far players outside the camera
  view are skipped. Configuration — `farPlayersFullBright` (default off); Fabric and NeoForge
  servers now honor the far-player hide permission and Melius Vanish (note the wildcard-admin
  effect). Compatibility — shader packs that keep LOD depth out of the vanilla depth buffer draw
  far players over LOD terrain; players beyond the vanilla far plane are not drawn.
- Memory: update `far-player-1211-render-bugs` with the outcome; new pointer once implemented.

## 3. Per-line matrix

| Item | 1.21.1 Fabric | 1.21.1 NeoForge twin | 26.2 (`main`) Fabric | 26.1 Fabric | 1.21.11 Fabric | 1.21.10 Fabric | NeoForge stubs (26.2/26.1/1.21.11/1.21.10) |
|---|---|---|---|---|---|---|---|
| Render API | immediate `dispatcher.render` ×3 | same | `extractEntity`+`submit` (3+3) | same | same | same | no render |
| Event / context | `WorldRenderEvents.AFTER_ENTITIES` / `WorldRenderContext` | `RenderLevelStageEvent` | `LevelRenderEvents.COLLECT_SUBMITS` / `LevelRenderContext` | same | `WorldRenderEvents.BEFORE_ENTITIES` / `world.WorldRenderContext` | same | — |
| WI-1 light class | `LightTexture` | `LightTexture` | `LightCoordsUtil` | `LightCoordsUtil` | `LightTexture` | `LightTexture` | — |
| WI-2 option | config/catalog/lang | reads it | same | same | same (legacy page only on 1.21.10) | same | row hidden (`RENDER_AVAILABLE=false`) |
| WI-3 frustum | `context.frustum()` (nullable) | `event.getFrustum()` | `levelState().cameraRenderState.cullFrustum` (+`initialized`) | same | **skipped** (follow-up: `END_EXTRACTION` stash) | **skipped** | — |
| WI-3 box | `noCulling`/`getBoundingBoxForCulling` | same | `getBoundingBox().inflate(0.5)` | same | (skipped) | (skipped) | — |
| WI-4 batch end | `endLastBatch()` | same | n/a | n/a | n/a | n/a | — |
| WI-5 byte owner | `Player` | `Player` | `Avatar` | `Avatar` | `Avatar` | `Avatar` | — |
| WI-6 tag API | `Font.drawInBatch` | same | `submitText` 10-arg | same | same | same | — |
| WI-7a hidden node | xplat snapshot + `LSSPermissions` | `LSSNeoPermissions` nodes | same | same | same | same | server half SHIPS on main + 26.1 |
| WI-7b Melius bridge | xplat bridge, lambda-wired | same class | same | same | same | same | server half SHIPS on main + 26.1 |
| Mapping family | `ResourceLocation` | same | `Identifier` | `Identifier` | `Identifier` | `ResourceLocation` | — |
| Java target | 21 | 21 | 25 | 25 | 21 | 21 | — |
| Tier 3 | CUT | — | yes (Xvfb :99) | yes | yes | yes | — |
| `LINE_SHIP_NEOFORGE` | true | true | true | true | false | false | — |

## 4. Phasing, branches, gates

1. **1.21.1 line** on `fix/far-player-light-floor` (stacked on `fix/vss-branding-sweep`): WI-1,
   WI-2, WI-3, WI-4, WI-5, WI-6, WI-7, WI-10, WI-11 for this line, commits grouped by item.
   Gates: `./gradlew :fabric:build` (T1 + T2), `:neoforge:build`, `:paper:test` (the constants
   touch common → Paper pins), `CI=true` build + `python3 scripts/release_check.py --version <next>`,
   Java 21 JDK. Live: the user's Create+ NeoForge instance against a C2ME server with
   `view-distance` < client rd (record both server values; F3 band; diag `light=`/`drawn=`);
   Iris present + shaders OFF (tag draw order) and shaders ON with Unbound selected (#268 shape);
   the Fabric 1.21.1 Prism rig with two GUI clients + the SoakPlayer dummy (E2/E3 gate shape,
   Xvfb :99 for harness clients); Melius Vanish on the Fabric test server (`test-server.sh
   run-fabric` + the mod jar) incl. the argument-order check with a real vanished player; a
   LuckPerms negative/wildcard grant for WI-7a on both loaders. Merge: PR into `support/mc1.21.1`
   AFTER `fix/vss-branding-sweep` lands; `v0.14.1+mc1.21.1` or the next planned release — user's
   call.
2. **Ports — two clusters, each written once and mirrored:** the 26.2/26.1 renderers differ by
   6 lines (`mainCamera()` vs `getMainCamera()`), the 1.21.11/1.21.10 renderers by 6 lines
   (`Identifier`/`ResourceLocation`); the clusters differ from each other by ~50 lines and from
   1.21.1 by ~120. So: write the 26.2 renderer (`main`), mirror to 26.1; write the 1.21.11
   renderer, mirror to 1.21.10. Order `main` → 26.1 → 1.21.11 → 1.21.10 (one cluster crossing).
   Mechanics per file: the change-core applier (memory `multiline-changecore-applier`, with its
   idempotency guard and `nofind=0` as the line-invariance proof) for the byte-identical files
   (`LSSClientConfig.java`, the three lang files, `FarPlayerBroadcastService.java`,
   `LSSPermissions.java`, `LSSNeoPermissions.java`, `ClientOptionCatalogTest.java`) and for the
   identical anchors in differing files (`RequestProcessingService.java:93-94`,
   `FabricFarPlayerSnapshots.java:76-78`, `ClientOptionCatalog.farPlayersPage()`);
   cherry-pick only new `docs/planning/*` files; hand-edit README and `per-version-surfaces.md`
   (all differ per line); hand-write the two renderer clusters; `FarPlayerClientSupport.java` is
   ahead on main (Brand sweep) — the applier, not cherry-pick, for anything near it. Per line:
   `gradle clean` first (memory `local-multiline-build-pollution`), Java 21 JDK on the 1.21.x
   lines, then each branch's own CLAUDE.md release pre-flight command (`CI=true ./gradlew
   :fabric:build -x runClientGameTest :paper:test :paper:shadowJar :neoforge:build -Pmod_version=<v>
   && python3 scripts/release_check.py --version <v>`) plus Tier 3 (`:fabric:runClientGameTest`,
   Xvfb) on all four; `release_check` hard-requires the NeoForge families on main and 26.1. Live
   smoke on main = the Modrinth server (memory `modrinth-server-deploy`). 1.21.10 landmines: the
   Sodium 0.8 walker cut, int-level permissions, the `Gt` gametest shim, no Folia.
3. **WI-9** only after the #268 reporter answers; its own plan section/PR.

## 5. Risks and mitigations

- Twin drift on 1.21.1: Fabric file first, NeoForge twin as the identical hunk, seam-test
  substrings re-checked before commit; the frustum-source delta is recorded in the surfaces row.
- Row/census tests: updated in the same commit as the catalog row (WI-2 list).
- Name-tag call shapes differ per cluster (1.21.1 `drawInBatch`; four newer lines `submitText`);
  never share the helper across clusters; prototype the 26.2 call first.
- WI-7a fail-visible is a deliberate asymmetry with Paper — documented in both snapshot classes.
- Melius argument order is the exposure-direction trap — pinned by the order-sensitive test.
- Field-initializer wiring — the lambda reads `this.server` at call time.
- Nothing changes the wire, the prefs carrier, the handoff semantics or the mount ladder.

## 6. Panel answers (folded)

- Q1 full-bright default: **false** (unanimous).
- Q2 tag depth: **NORMAL only** (stricter than vanilla; recorded as deliberate).
- Q3 sneaking: **hide** (recorded as a new LSS policy, not vanilla parity).
- Q4 scale: **`0.025 × clamp(sqrt(d/64), 1, 8)`, fixed rule, no option** (linear constant-size
  rejected as an ESP-HUD look); revisit the constant after the live check.
- Q5 WI-9: **defer**, holes recorded.
- Q6 fail direction: **permission read fail-VISIBLE (seam cannot express "threw"); vanish bridge
  fail-HIDDEN (our own catch)**; Paper keeps fail-hidden for its Folia reason.
- Q7 more prior art: **nothing further**; licence-first — no code reuse. Explicitly out of scope:
  a measured-interpolation rewrite (LSS's declared-cadence motion is review-pinned), a packet
  sequence gate (epoch + latest-wins mailbox cover it), forced-first-frame on subscribe,
  SeeU Extra/non-player entities, the locator bar, a settings hotkey (page-less stacks — a
  separate ask), fog mixin and glow (rejected).

## 7. Draft reply for #268 (post only after user approval)

> Hi @<reporter>, thanks for the report.
>
> Before I dig further: do you have shaders on, and if so which pack? And does the problem go
> away with shaders off?
>
> Here is what I found testing on NeoForge 1.21.1 with Voxy 0.2.16 via Roxy. With shaders off,
> Voxy writes its LOD depth into the vanilla depth buffer before entities are drawn, so far
> players are correctly hidden behind LOD terrain. With Iris shaders on, the pack decides whether
> LOD depth reaches that buffer. The Complementary packs I checked (Unbound r5.8.1) set
> "excludeLodsFromVanillaDepth": true in shaders/program/voxy.json, and with that set every
> entity, far players included, draws on top of the LOD terrain. That is the pack's Voxy
> integration, so LSS cannot fix it by changing its render order.
>
> If you are on such a pack, the options are a pack that emits LOD depth, or turning far players
> off in the LSS settings (Far Players page, or "farPlayersEnabled": false in
> lss-client-config.json, vss-client-config.json on the VSS build).
>
> Separate and unrelated: players past vanilla's far plane are not drawn at all, whatever the
> far-player render limit says. Iris extends that plane, plain vanilla does not.
>
> If you are running without shaders I will keep digging, since that path looked correct in my
> testing.

## 8. Review fold record (v1 → v2)

Panel: R1 Fable (MC-API/render), R2 Opus (server privacy), R3 Opus (config/tests/pins),
R4 Opus (multi-line port), R5 Opus (adversarial UX/compat/scope). All verdicts:
ship-with-corrections / request-changes; no design refutation. Folded MAJORs: SeeU licence
(R5) → no code reuse, F7 rewritten; name tags gated at 64 on ALL lines (R1/R4/R5) → own-draw
everywhere, fallback deleted; `LightCoordsUtil` on 26.x (R1/R4); `Avatar` owns the model-parts
byte on newer lines (R1/R4); no frustum on 1.21.11/1.21.10 and no `noCulling`/
`getBoundingBoxForCulling` on newer lines (R1/R4) → skip + `getBoundingBox()`; `submitText`
instead of `submitNameTag`, whose arity differs per line and whose pre-scale maths v1 got wrong
(R1/R4); WI-3 must cull draw calls only or it breaks the R-10 mount pin (R5); WI-4 reduced to
`endLastBatch()`, inert under Iris, supersedes a recorded decision in two docs (R3/R5); the
save hook must be the page-uniform push (R3); the source pin must not assert `hasChunk` absence
and must guard the stub lines (R3); fail-hidden is unreachable through the permission seam →
split fail directions (R2/R3/R5); Melius API shape and argument order, no `isVanished(
ServerPlayer)`, field-initializer null-server trap, `LSSNeoPermissionsContractTest` pins (R2);
decisions-log convention (R3); Tier 3 on four lines and NeoForge shipping on main + 26.1 (R4);
WI-9's privacy/warm-rejoin/ceiling holes (R5); elytra cape-texture nuance for WI-5 (R5); sqrt tag
scale (R5 over R1's linear); wrong archive citations (R1/R4). Folded MINORs: diag instrument,
tag batching + name caching, third-party tag suppression note, `Visibility.java` + CLAUDE.md
enumerations, zh translations + parity test, VSS lang rebrand caution, README overclaims,
per-line gate commands, port recipe per file, 1.21.10 landmines, Sable `shouldRender` note,
Iris live-check requirement, the instance's shader-pack mismatch.

## 9. Decisions log

- 2026-09-04 — Far-player proxies are lit with a sky-15 floor (real block light kept), optional
  full-bright; brighter-than-correct beats dark (user). Supersedes the 1.21.1 fresh cut's
  loaded/unloaded light split (which was dead code).
- 2026-09-04 — Far-player name tags are drawn by LSS (vanilla cannot draw them for proxies on
  any line): depth-tested only, hidden for sneaking players, `0.025 × clamp(sqrt(d/64), 1, 8)`.
- 2026-09-04 — Frustum culling of proxy/mount DRAW CALLS only; skipped on 1.21.11/1.21.10 (no
  frustum on the render path) as a named cut.
- 2026-09-04 — `endLastBatch()` after the proxy pass on the immediate-mode line supersedes the
  "no explicit flush" decision of `neoforge-1.21.1-far-player-render-plan.md`; the arg-less
  `endBatch()` stays forbidden.
- 2026-09-04 — Fabric/NeoForge honor `lss./vss.farplayers.hidden` (fail-visible) and Melius
  Vanish (fail-hidden); Paper keeps fail-hidden for its Folia race.
- 2026-09-04 — No SeeU code is reused (restricted licence since 0.8); "no fog mixin", "no glow",
  "no far-plane mixin" reaffirmed.
