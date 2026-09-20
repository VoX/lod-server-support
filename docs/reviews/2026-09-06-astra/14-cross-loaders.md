# Lens 14 — cross-line loader, renderer and optional UI integration

Reviewer: Astra; 2026-09-06. Review and repair plan only; no repository edits, Gradle runs, server operations or client-instance changes. Trees: `/home/vox/projects/lss-lines/{1.21.1,1.21.10,1.21.11,26.1,26.2}`. Earlier reports own common/client protocol issues, Xaero behavior, Voxy domain reset, packaging and server lifecycle; this report does not count those again.

## New finding CROSS-RENDER-1 — P2: 1.21.10/1.21.11 far-player wings never leave zero rotation

**Affected production sites:**

- `/home/vox/projects/lss-lines/1.21.10/fabric/src/main/java/dev/vox/lss/networking/client/FarPlayerRenderer.java:1179` — `Proxy.apply`'s new-tick block advances fallFlyTicks/swim state but never elytraAnimationState (block ends 1185).
- `/home/vox/projects/lss-lines/1.21.11/fabric/src/main/java/dev/vox/lss/networking/client/FarPlayerRenderer.java:1175` — equivalent omission, block ends 1181.
- `/home/vox/projects/lss-lines/1.21.11/fabric/src/test/java/dev/vox/lss/testutil/FarPlayerRenderSourceContractTest.java:133` — the contract describes flag/ticks as covering spread wings but only pins the flag, fallFlyTicks, swim amount and water flag at 136–140. The 1.21.10 contract has the same gap.

**Supported trigger and visible impact:** A normally configured Fabric 1.21.10 or 1.21.11 client renders a distant player wearing an elytra. Gliding sets the shared flag and eventually tilts the body correctly, but the wing angles remain their new-entity values of zero. They never spread with gliding or follow the standing/crouching wing poses. This is a cosmetic, consistently reachable rendering defect; it does not affect terrain delivery, flight physics or protocol compatibility. The finding is independent of the deliberately omitted velocity-dependent yaw banking.

**Evidence chain, using the real cached Minecraft classes:**

1. Both versions' actual `LivingEntity` allocate `ElytraAnimationState` in their constructor and call its `tick()` during the normal entity tick.
2. Both actual `ElytraAnimationState` constructors only store the entity reference. All six current/old rotation fields therefore start at Java's zero defaults. `tick()` is the mutation path; flag changes alone do not update angles. With zero velocity and `isFallFlying=true`, it interpolates toward full spread; yaw banking is not needed for basic spreading.
3. Both actual `AvatarRenderer.extractRenderState` implementations invoke `HumanoidMobRenderer.extractHumanoidRenderState`. That method copies `elytraAnimationState.getRotX/Y/Z(partialTick)` into `HumanoidRenderState.elytraRotX/Y/Z`.
4. Both actual `ElytraModel.setupAnim` implementations assign those render-state fields directly to the wing model rotations. On 1.21.11 the model moved to `net.minecraft.client.model.object.equipment.ElytraModel`; this package change does not change the state dependency.
5. LSS explicitly never ticks the render-only proxy as a world entity. Its `apply` method sets the glide flag and pose, but there is no `elytraAnimationState` reference anywhere in either affected renderer. The wings consequently stay at zero for the proxy's whole life.
6. 26.1 correctly calls `this.elytraAnimationState.tick()` at `fabric/.../FarPlayerRenderer.java:1243`; 26.2 does so at line 1262. The 26.x comment saying this is “this line only” versus 1.21.1 is incomplete: the same state also exists on 1.21.10 and 1.21.11.

Named MC jars examined are under `/home/vox/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.10-loom.mappings.1_21_10.layered+hash.2198-v2/` and the corresponding `1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2/`. Full `javap -p -c` evidence is retained in this review directory as `elytra-1.21.10-javap.txt`, `elytra-1.21.11-javap.txt`, `elytra-model-1.21.10-javap.txt`, and `elytra-model-1.21.11-javap.txt`. The initial broad javap dump includes a harmless class-not-found line for 1.21.11's old ElytraModel package; the second model dump resolves the correct class and verifies the complete chain.

**Repair:** Add the same once-per-animation-tick state update used by 26.1/26.2 to the two affected Fabric proxies, after setting the current glide/sneak pose inputs. Do not full-tick a synthetic player. Correct the per-line hardening prose and contracts so the state flavor is recorded as 1.21.10 onward, with 1.21.1 retaining direct model angle computation.

**Regression:** On each affected line, exercise proxy application across distinct animation ticks with a gliding pose and zero movement. Read the real ElytraAnimationState and assert nonzero X/negative Z, then assert a second render in the same animation tick does not advance it again. Switch to standing/crouching and verify convergence toward those poses. Prefer a render-input helper or proxy construction fixture over a string-only test; additionally extend the existing source/bytecode contract to pin the actual `ElytraAnimationState.tick()` invocation inside the tick guard. A live equipped-player/gliding visual comparison remains useful but is not the evidence relied upon for this report.

**Affected matrix:** Fabric 1.21.10 and 1.21.11 affected; Fabric 26.1/26.2 already correct; Fabric and NeoForge 1.21.1 use the older direct model behavior and are not affected; the other four NeoForge renderers are explicit no-op stubs, so the issue is not applicable to them.

**Confidence:** High from complete production-source + actual-Minecraft-bytecode dependency chain. No runtime visual reproduction or new JUnit result is claimed for this finding.

## Cross-line scope of existing FP2 — seated-render pose restoration

This is the issue already reported in `07-farplayers.md`, not an additional finding. Exact scope:

| Line | Fabric seated catch | NeoForge seated catch | Status |
| --- | --- | --- | --- |
| 1.21.1 | `fabric/.../FarPlayerRenderer.java:422–424` latches without restoring | `neoforge/.../FarPlayerRenderer.java:481–483` latches without restoring | Both affected |
| 1.21.10 | `fabric/.../FarPlayerRenderer.java:442` calls restorePose | Stub, RENDER_AVAILABLE=false at 18 | Fabric already contains repair; Neo not applicable |
| 1.21.11 | `fabric/.../FarPlayerRenderer.java:442` calls restorePose | Stub, RENDER_AVAILABLE=false at 18 | Fabric already contains repair; Neo not applicable |
| 26.1 | `fabric/.../FarPlayerRenderer.java:445` restores before latching at 446 | Stub, RENDER_AVAILABLE=false at 17 | Fabric already contains repair; Neo not applicable |
| 26.2 | `fabric/.../FarPlayerRenderer.java:449` restores before latching at 450 | Stub, RENDER_AVAILABLE=false at 17 | Fabric already contains repair; Neo not applicable |

All abbreviated renderer paths have the same suffix `src/main/java/dev/vox/lss/networking/client/FarPlayerRenderer.java`, under the indicated version root.

The trigger remains an exception during a seated rider's dispatcher render/extract, typically an incompatible modded layer or event callback, after a pose push. The immediate 1.21.1 dispatcher does not pop that frame on its exceptional path. The contained seated catch continues the LSS pass, so later riders/tags can inherit the leaked transform. The outer pass finally restores its sentinel, limiting harm to the current LSS pass; this is not a claim of a persistent vanilla pose-stack crash. Existing 1.21.10/11 restore after latching; 26.x restore before latching, which is the stronger pattern to carry to 1.21.1. Regression should throw after an extra pose push in a seated draw and inspect the next draw's transform, for both live 1.21.1 twins.

## Deliberate renderer differences checked and retained

| Line | Fabric hook / pipeline | NeoForge | Important intentional shape |
| --- | --- | --- | --- |
| 1.21.1 | WorldRenderEvents.AFTER_ENTITIES; immediate dispatcher.render; registration1256 | Live RenderLevelStageEvent.AFTER_ENTITIES at1335 plus EntityJoinLevelEvent handoff | Direct vertex buffer depth lift; endLastBatch; packed light floor; older player/model inputs |
| 1.21.10 | WorldRenderEvents.BEFORE_ENTITIES; deferred extract/submit; registration1328 | Explicit stub | Submit collector depth lift, no owned batch; named frustum-cull cut |
| 1.21.11 | WorldRenderEvents.BEFORE_ENTITIES; deferred extract/submit; registration1324 | Explicit stub | Same pipeline/cut; Identifier and RenderTypes API movement |
| 26.1 | LevelRenderEvents.COLLECT_SUBMITS; registration1384 | Explicit stub | Camera-state frustum available; submit collector; event before vanilla pose-stack check |
| 26.2 | LevelRenderEvents.COLLECT_SUBMITS; registration1406 | Explicit stub | Camera-state frustum available; changed collector APIs; event at submitFeatures RETURN after vanilla pose-stack check |

The event names are not interchangeable: on 1.21.10/11 AFTER_ENTITIES is already after the submit drain, while on 1.21.1 it is the correct immediate-mode point. The per-version surfaces row and renderer comments document the actual ordering. Deferred pipelines do not own a shared buffer batch to end. Their missing endLastBatch is intentional. 1.21.10/11 missing frustum culling is an explicit named cut, with the context limitations and future extraction stash documented; it is not reported as drift.

Other reviewed differences match their engine surfaces: 1.21.10 forwards the extra submitHitbox collector method; 1.21.11 removes it and moves RenderType statics; 26.x collector overloads track model, custom geometry and particle API changes. The real-entity name-tag gap predicate uses isSectionCompiled on 1.21.10 and isSectionCompiledAndVisible on 1.21.11, as required by each line's vanilla extraction. 26.2 adds the real entity NAME_TAG_DISTANCE attribute surface and HUD visibility accessor; 26.1 uses its older vanilla predicate. Radial armor/item lift and mount treatment were compared through the complete renderer files, not just the last diff. No additional concrete rendering defect was established in those differences.

Saved complete source diffs: `render-1.21.1-vs-1.21.11.diff`, `render-1.21.10-vs-1.21.11.diff`, `render-1.21.11-vs-26.1.diff`, `render-26.1-vs-26.2.diff`.

## Loader/bootstrap, callback and Sodium comparison

No additional defect established in these surfaces.

- Fabric LSSClient and LSSMod entrypoints are byte-identical across all five lines. LoaderServices is also byte-identical. FarPlayerClientSupport behavior is unchanged across lines, aside from explanatory comments. This preserves one shared tracker/session-reset path while render events remain loader-specific.
- NeoForge LSSNeoClientBootstrap differs where 1.21.1 actually registers the live renderer and the other lines log the explicit cut. LSSNeoMod's 1.21.1 FMLEnvironment.dist versus later getDist() is an API adaptation. The same-FQN no-op renderer twins still expose their required support/diagnostic surface, and RENDER_AVAILABLE correctly gates renderer-only UI. Sharing preferences remain available independently of rendering, by design.
- NeoForge LSSClientNetworking is byte-identical on all five lines. Fabric variants differ only in the per-frame Xaero pump event namespace/phase and comments: 1.21.1 WorldRenderEvents.END, 1.21.10/11 world.WorldRenderEvents.END_MAIN, 26.x level.LevelRenderEvents.END_MAIN. This callback does not submit geometry; its tick fallback remains present. ClientNetGlue differs only in the old versus new SharedConstants data-version accessor. Fabric LSSNetworking's payload registration names change from playC2S/playS2C to serverboundPlay/clientboundPlay on 26.x, without a missing payload registration.
- LegacySodiumPage and SodiumLegacyOptionsHook are byte-identical across all ten loader/line combinations. Fabric SodiumConfigScreens is byte-identical across all five lines. Modern LSSConfigMenu keeps the same catalog, defaults, enable dependencies and save behavior; Fabric metadata discovery versus NeoForge ModList metadata discovery is the intended loader seam.
- The modern config API walker and sodium:config_api_user registration are intentionally absent on 1.21.10 in both loaders: this is the documented Sodium 0.7 legacy-only line, not a lost port. They are present on 1.21.1, 1.21.11 and both 26.x lines. 1.21.1 uses ResourceLocation in the walker/API descriptor stubs, later modern lines use Identifier. The native NeoForge TOML property matches the presence of its walker. The existing bytecode contract tests cover the public interface/no-arg constructor and TOML property without linking absent MC classes in a plain JUnit JVM.
- 1.21.1 is intentionally the dual Sodium generation line (0.6.13 community Voxy pairing and modern 0.8.13); 1.21.10 is the 0.7 proof line. Stubbed/non-shipping NeoForge lines are not held to 1.21.1's live renderer promise. Voxy compatibility findings and the carefully bounded installed-instance coverage gap remain owned by report10.

## Review limits and repair order

Read the relevant CLAUDE banners, per-version surfaces rows, far-player render-hardening plan and source contract tests before judging differences. Actual cached Minecraft bytecode was used to validate the new elytra finding. This lens did not launch any client, renderer, Gradle process or dedicated server and does not claim new loader/Sodium live smoke coverage. Parent-coordinated build and artifact validation are reported separately by lenses13/15.

Carry FP2's two local pose-restoration repairs only to the two affected 1.21.1 renderers. Independently add the missing once-per-tick wing-state advance and its regression to Fabric 1.21.10 and 1.21.11. Preserve explicit stubs/cuts and the per-line render-phase/API seams rather than mechanically copying a whole renderer between versions.
