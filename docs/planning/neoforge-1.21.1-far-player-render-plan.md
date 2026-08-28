# NeoForge 1.21.1 far-player rendering — implementation plan (v2, post plan-review)

**Branch:** `feat/neoforge-far-player-render` (off `support/mc1.21.1` HEAD `59356f70`, mod_version 0.14.0).
**Goal:** replace the NeoForge `FarPlayerRenderer` render-path stub (`RENDER_AVAILABLE = false`)
with a working renderer so far players render on **NeoForge 1.21.1**, and fold it into the
held v0.14.0 release for that line.

**Review status:** reviewed by 1 Fable + 2 Opus (safety-focused). All findings folded below;
§10 records the dispositions. Headline change from v1: **the explicit buffer-source flush is
DROPPED** (it was built on a wrong premise and was the one hazardous delta) — the render body is
now byte-verbatim with the shipping Fabric renderer.

## 1. Scope decision (1.21.1 NeoForge only)

- **In scope:** the NeoForge client on the **1.21.1 line** — the version the user was testing (the
  community Voxy port pairing) and the only line shipping NeoForge (`LINE_SHIP_NEOFORGE=true` here
  alone).
- **Out of scope (recorded follow-up — see §10 doc sweep):** NeoForge rendering on **26.1 / 26.2**.
  Those lines' Fabric renderer uses the 26.x **submit/extract pipeline** (`dispatcher.extractEntity`
  + `dispatcher.submit` + `SubmitNodeCollector`, see `research/seeu/neoforge/.../FarPlayerRenderer.java`),
  a materially different render port, and their NeoForge client is the Foxy fork. The options
  catalog already hides the renderer-only options where `RENDER_AVAILABLE` is false, so those lines
  degrade cleanly and unchanged.

## 2. Approach: fill the same-FQN twin (NOT an xplat shared-core refactor)

`FarPlayerRenderer` is **already a same-FQN twin pair**: `fabric/.../FarPlayerRenderer.java`
(full impl, ~742 lines) and `neoforge/.../FarPlayerRenderer.java` (stub), both
`dev.vox.lss.networking.client`. We fill the NeoForge twin with the ported renderer.

**Considered and rejected — Option B (extract a loader-agnostic core to xplat, thin adapters).**
DRY-er, but: (1) refactors the working, review-pinned Fabric renderer on a support branch — risk
for no user gain; (2) introduces xplat structural divergence between the 1.21.1 branch and
main/other lines, multiplying backport cost — the multi-week-fold trap in memory
`single-branch-consolidation` (1.21.1 is already the most-divergent line); (3) the class is already
a twin pair. All three plan reviewers endorsed filling the twin.

**Accepted cost:** the duplication between the two twins. With the flush dropped (§3) the twins
differ ONLY in imports + event plumbing + the small NeoForge-specific containment note (§3, item 6),
so the copy stays a faithful near-verbatim mirror. Header comment names the Fabric source of truth;
NeoForge is best-effort tier; logic changes update both twins like every other twin pair.

## 3. The port — surface deltas (everything else is verbatim Fabric)

The Fabric 1.21.1 renderer already recomputes camera / partialTick / level / dispatcher
loader-agnostically. It touches the Fabric API in **exactly two places** (`context.matrixStack()`
and `context.consumers()`), plus the two event registrations. The deltas:

1. `render(WorldRenderContext context)` → `render(RenderLevelStageEvent event)`. Registration
   filters `Stage.AFTER_ENTITIES` inside a single `NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, ...)`.
2. `context.matrixStack()` → `event.getPoseStack()`.
3. `context.consumers()` → **capture once**: `var bufferSource = minecraft.renderBuffers().bufferSource();`
   at the top of `renderContained`, and use that single reference at **all three** `dispatcher.render(...)`
   buffer-arg sites. **NO explicit `endBatch()`** — see the flush note below. **Drop** the Fabric
   `context.consumers() == null` guard (the vanilla buffer source is non-null during a render pass).
4. `renderMount(...)`'s `WorldRenderContext context` parameter → the captured `MultiBufferSource bufferSource`
   (it only used `context.consumers()`). **Purge every `net.fabricmc.*` import** — `WorldRenderContext`,
   `WorldRenderEvents`, `ClientEntityEvents` — and the `WorldRenderContext` parameter type from
   `render`/`renderContained`/`renderMount` (a leftover Fabric import fails the NeoForge compile).
5. `WorldRenderEvents.AFTER_ENTITIES.register(renderer::render)` +
   `ClientEntityEvents.ENTITY_LOAD.register(...)` → in `initRenderer()`:
   - `NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, e -> { if (e.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) renderer.render(e); })`
   - `NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, e -> { if (e.getLevel().isClientSide() && e.getEntity() instanceof Player p) onRealPlayerLoad(p.getUUID()); })`
   - Add a `if (instance != null) return;` guard at the top of `initRenderer()` (double-registration
     armor; called-once today, cheap insurance).
6. **NeoForge-specific containment (new, per Opus crash-paths minor-2):** wrap the **unseated**
   `dispatcher.render(proxy, ...)` call in a `try/catch(Throwable)` that drops **that one proxy**
   for the frame (remove from `active` + `proxies`) and continues — symmetric with the already-present
   seated-path containment. Rationale: NeoForge fires third-party render events
   (`RenderLivingEvent.Pre/Post`, `RenderNameTagEvent`) INSIDE `dispatcher.render`, a throw surface
   Fabric's whole-pass latch was not sized for; without this, one bad rendering-mod listener on the
   common unmounted path disables ALL far players for the session instead of dropping one proxy. The
   `Proxy` construction / `apply(...)` throw surface continues to ride the whole-pass crash latch as
   on Fabric (documented, accepted).

**The flush — DROPPED (v1 was wrong).** Verified against the patched 1.21.1 `LevelRenderer`:
NeoForge dispatches `AFTER_ENTITIES` at the **same instruction boundary** Fabric injects its
`afterEntities` event (the `"blockentities"` profiler push), and the **same** vanilla catch-all
arg-less `bufferSource.endBatch()` downstream flushes anything buffered at the event — which is
exactly what makes the shipping Fabric renderer (which never flushes) work. Rendering into
`minecraft.renderBuffers().bufferSource()` with no explicit flush is therefore **byte-identical to
Fabric's validated behaviour**. An explicit `endBatch()` at `AFTER_ENTITIES` would be *hazardous*:
it flushes still-deferred batches (entityTranslucent skins, name-tag text, glint, banner/shield)
early, reordering geometry LSS doesn't own and changing proxy-vs-water blending, and it adds a new
throw site — precisely the corruption the live test is meant to rule out. So: no flush.

`RENDER_AVAILABLE` flips **false → true**. `clearInstance()` becomes the real teardown (`clear()`
+ `crashLatched = false` + `mountLadder.reset()`), replacing the no-op stub.

## 4. Wiring

`LSSNeoClientBootstrap.init()` (client dist only, already dist-gated behind
`FMLEnvironment.dist.isClient()` in `LSSNeoMod`): replace the "far players ... the render path is
not implemented (best-effort tier)" INFO block with `FarPlayerRenderer.initRenderer();` (mirrors
Fabric's `LSSClient.initRenderer()` → `FarPlayerRenderer.initRenderer()`). Keep a single one-line
INFO noting rendering is active (best-effort) so the launch log records the state.

## 5. Tests

Rewrite **`NeoForgeLoaderSeamContractTest.clientBootstrapExistsUnderTheReflectiveName`** — the
assertion (~lines 124-127) currently *forbids* the neoforge renderer from referencing
`capabilityBit` / `FarPlayerClientSupport` (it enforced the render-path cut, which we are undoing).
Replace it with assertions that pin the **present-and-contained** shape (source-string checks, the
codebase's established contract-test style):
- `LSSNeoClientBootstrap` **calls** `FarPlayerRenderer.initRenderer()` — the compiles-but-never-runs
  trap this codebase pins everywhere (a fully-formed renderer that is never wired renders nothing,
  all tests green).
- the twin declares `RENDER_AVAILABLE = true`, registers on `RenderLevelStageEvent`, and defines
  `initRenderer`.
- **the gate conjunction survives**: `renderContained` still short-circuits on
  `capabilityBit() == 0` / `!effectiveFarPlayersEnabled()` (a config-disabled NeoForge client must
  render nothing yet still deliver its prefs — the prefs-carrier rule).
- **the containment invariants survive**: the whole-pass `catch (Throwable`, the `crashLatched`
  latch, and `clearInstance()` resetting `crashLatched = false` + calling `mountLadder.reset()` —
  so the 700-line twin can't silently regress its safety.
- update the method + preceding javadoc that describe "the far-player RENDER-PATH cut".

**`ClientOptionCatalogTest`** (Fabric module) — **no change** (verified): it runs where
`RENDER_AVAILABLE` is already true and asserts `Visibility.RENDER_AVAILABLE.test(...)` over explicit
`MenuContext` booleans, never the live NeoForge constant.

**No new gametest** — rendering is client-only; the NeoForge gametest is an 8-test server smoke.
Validation is compile + contract tests + the **live** test scenario (§7).

## 6. Risks (post-review)

1. **Multi-mod / shader coexistence at `AFTER_ENTITIES`.** With the flush dropped, our proxies enter
   the shared buffer source exactly as Fabric's do, so Fabric+Iris parity carries over — but NeoForge
   Iris/Oculus re-drives level rendering for the shadow pass and can fire `AFTER_ENTITIES` more than
   once per frame. This is a **visual** surface the crash latch cannot catch (no throw). **Gate: the
   live test includes an Iris/Oculus run** (§7).
2. **Third-party render-event throws inside `dispatcher.render`** — contained per-proxy on the
   unseated path now (§3 item 6); seated/mount paths already contained; construction/`apply` rides
   the whole-pass latch (accepted).
3. **`EntityJoinLevelEvent` fires on both dists AND, on an integrated server, on the server thread.**
   The `getLevel().isClientSide()` filter is therefore **load-bearing twice**: dist safety (no
   client-code on a dedicated server) AND thread confinement (the proxy `HashMap` is render-thread-only;
   a server-thread `onRealPlayerLoad` would race the render pass). Pin this in the impl comment so it
   is never "optimized" away.
4. **Entity-construction/size events.** Building `RemotePlayer` proxies / ladder mounts in the pass
   fires NeoForge entity-construction/size events into other mods' listeners (no Fabric analogue) —
   contained by the whole-pass latch + per-type mount ladder; the live test's modded/shader client
   exercises it.
5. **Drift** between the twins — accepted best-effort cost; Fabric is the source of truth (header
   comment); with the flush gone the delta is minimal.

## 7. Release re-staging + live test scenario

**Re-staging (1.21.1 line only):**
1. Land the feature on `support/mc1.21.1`; CI green.
2. Clean rebuild: `./gradlew clean` then
   `CI=true ./gradlew :fabric:build :paper:test :paper:shadowJar :neoforge:build -Pmod_version=0.14.0`
   + `python3 scripts/release_check.py --version 0.14.0` → `OK`. **Note:** do NOT pass
   `-x runClientGameTest` — Tier 3 is unregistered on this line and excluding an unknown task fails.
3. Add one user-facing bullet to the **1.21.1** v0.14.0 release notes
   (`release-tag-v0.14.0-mc1.21.1.txt`): NeoForge far players now render (best-effort). Only the
   1.21.1 line's notes change.
4. Hold the tag (release still on hold per the user).

**Live test scenario:**
1. `test-server.sh run-neoforge` on the 1.21.1 build → NeoForge server :25569. (test-server.sh has
   no view-distance knob — hand-edit `test-server/neoforge/server.properties` `view-distance=4` if a
   small vanilla circle is wanted to force far-player handoff nearby.)
2. Install the freshly-built NeoForge client jar into the `lss-test-neo-1.21.1` Prism instance (never
   a bare-version instance; never hot-swap under a running JVM).
3. Join with a headless test client under Xvfb (:99) as a far-player TARGET the user can see:
   `runSoakClient -Psoak.server=localhost:25569` (its default is :25565 — the soak-harness port; a
   stray join there contaminates a running soak, so the explicit `:25569` is required).
4. **Confirm the listener actually fires** (guards against a silent mod-bus mis-registration) and, on
   the user's Prism client, eyeball: far players render, handoff to real entities is clean, and — with
   **Iris/Oculus enabled** — no shadow double-render / proxy corruption.
5. Hand the user the Prism join details.

## 8. Ordered task list

1. Write the NeoForge `FarPlayerRenderer` (port from Fabric; §3) + `initRenderer()`.
2. Wire `LSSNeoClientBootstrap.init()` (§4).
3. Rewrite `NeoForgeLoaderSeamContractTest` (§5).
4. Doc sweep (§10).
5. `:neoforge:build` + `:fabric:build` green.
6. 1-Fable + 4-Opus implementation review; fold.
7. Release re-staging (§7) + preflight green.
8. Live test scenario prep (§7).

## 9. VERIFIED render-flow facts (de-risking the port)

Confirmed against the decompiled/patched 1.21.1 `LevelRenderer` (NeoForge 21.1.248 in the neoform
cache) and the `neoforge-21.1.248` sources jar:

- **`AFTER_ENTITIES` timing = Fabric's `afterEntities`.** NeoForge dispatches
  `RenderLevelStageEvent.Stage.AFTER_ENTITIES` right after vanilla flushes the four opaque entity
  render types (`entitySolid/entityCutout/entityCutoutNoCull/entitySmoothCutout`) and before the
  block-entity pass — the same boundary Fabric injects at. The downstream arg-less
  `bufferSource.endBatch()` flushes anything we buffer. → **no explicit flush needed** (§3).
- **PoseStack:** `event.getPoseStack()` at `AFTER_ENTITIES` is the same live `LevelRenderer` pose
  stack Fabric captures (camera rotation rides the global modelview; camera-relative offsets passed
  as `dispatcher.render` args). Identical transform state.
- **Events are game-bus:** `RenderLevelStageEvent` (`getStage()/getPoseStack()/getCamera()`,
  `Stage.AFTER_ENTITIES`) and `EntityJoinLevelEvent` (`net.neoforged.neoforge.event.entity`,
  `extends EntityEvent`, `getLevel()` → `Level`) both register via `NeoForge.EVENT_BUS.addListener`,
  matching the bootstrap's existing `RenderFrameEvent.Post` / `ClientTickEvent.Post` pattern. The
  live test's "listener fires" step is the final gate against a mis-registration.
- **Dist-safety:** `FarPlayerRenderer` is reachable only from `FarPlayerClientSupport.onSessionEnd()`
  (a method body — lazy resolution) and `MenuContext` reading `RENDER_AVAILABLE` (a compile-time
  constant, inlined). `onSessionEnd()` is reached only via `ClientNetGlue.onDisconnect()` /
  `ClientSessionGate`, wired solely from the dist-gated bootstrap → the renderer never loads on a
  dedicated server; `clearInstance()` never runs there (and would be `instance == null`-safe anyway).
- **9-arg `dispatcher.render`** (immediate path) is unchanged on both loaders (Mojang mappings).

## 10. Review fold (dispositions)

- **Flush is wrong / hazardous (Fable MAJOR-1, Opus-1/Opus-2 MAJOR-1):** DROP the flush → §3. Also
  resolves both Opus "flush escapes the latch" concerns and Opus-2's "arg-less flush corrupts
  shaders/other mods".
- **Preflight `-x runClientGameTest` fails on this line (Fable MAJOR-2):** removed → §7.
- **Test must pin the wiring + gate + containment (Fable MAJOR-3, Opus-2 minor-1):** → §5.
- **Per-proxy containment on the unseated render (Opus-1 minor-2):** added → §3 item 6, §6.2.
- **`isClientSide()` is also the integrated-server thread guard (Fable minor-5, Opus-1 minor-3,
  Opus-2 minor-2):** pinned in the impl comment → §6.3.
- **Purge Fabric imports + param types; capture bufferSource once; drop null-guard (Opus-1 nit-6,
  Opus-2 minor-3):** → §3 items 3-4.
- **`initRenderer()` idempotency guard (Opus-1, Opus-2 minor-4):** added → §3 item 5.
- **Add an Iris/shader run + "listener fires" to the live test (Opus-2 MAJOR-2, Opus-1 nit-5):** → §7.
- **Doc sweep (Fable minor-4):** update the CLAUDE.md branch banner ("only the NeoForge renderer is
  the no-op stub" → now live), add the new per-line surface to `docs/planning/per-version-surfaces.md`
  (`RenderLevelStageEvent.AFTER_ENTITIES` + identity PoseStack + `EntityJoinLevelEvent`), record the
  26.x NeoForge-render follow-up there, and fix the stale "NeoForge v1 stub" comments in
  `MenuContext.java` (~31), `ClientOptionCatalog.java` (~146), `release_check.py` (~535), and
  `FarPlayerClientSupport.java`'s javadoc.
- **Confirmed no gaps (all three):** issue-#160 mount containment carried verbatim (ladder is shared
  xplat — zero divergence), hostile-input caps preserved, session/dimension lifecycle resets correct,
  SeeU-coexist + prefs-carrier preserved, NeoForge registry semantics identical, scope call correct.

## 11. Implementation review fold (1 Fable + 4 Opus, all on commit cad206b8)

**Verdicts: ZERO MAJORs.** Fable (port fidelity) — faithful & correct, all 14 twin-diff
hunks map to the 8 intended deltas, no unintended divergence. Opus (mount crash paths) —
crash-safe, every issue-#160 containment block byte-identical to Fabric. Opus (render
pipeline) — pipeline correct, the no-flush decision VERIFIED sound against the decompiled
1.21.1 LevelRenderer (vanilla's arg-less catch-all endBatch drains our geometry; an explicit
flush would have been wrong). Opus (lifecycle/threading/dist) — sound, thread-confinement and
dist-safety (RENDER_AVAILABLE inlined, no server class-load) verified in sources/bytecode.
Opus (hostile input/test/docs) — faithful, no MAJOR, hostile-input caps intact.

**Folded (all minor/nit — a second commit):**
- **Complete per-player containment** (Fable minor + mount-review minor + hostile-input #4):
  every throw-capable statement on the non-mounted path — construction, `apply()`
  (EntityEvent.Size), and `dispatcher.render` (RenderLivingEvent/RenderNameTagEvent) — now
  drops THAT proxy for the frame via `dropProxyContained` (once-guarded warn) instead of the
  whole-pass latch. NeoForge fires these third-party listeners inside the render pass where
  Fabric fires nothing; the whole-pass latch stays the final backstop, and the seated/mount
  paths keep their granular type-latching. This is the fold that most directly serves the
  "won't error in unexpected conditions" bar.
- **Test pins** (hostile-input #4/#5): the contract test now pins `dropProxyContained` and the
  `isClientSide()` filter — both were removable while the test stayed green.
- **Doc completeness** (hostile-input #1/#2/#3/#7): fixed the stale "NeoForge v1 stub" claims
  the first sweep missed — the FABRIC twin's RENDER_AVAILABLE comment, `Visibility.java`,
  `CLAUDE.md` options-page line, and the normative `neoforge-support-plan.md` /
  `pre-authorized-cuts.md` (un-cut-on-1.21.1 addenda).

**Accepted as-is (documented):** construction was already low-risk (a raw `new RemotePlayer`
does not go through `EntityType.create`, so per-instance construction listeners largely don't
fire — render-pipeline reviewer) but is contained anyway now; the seated path attributes a
third-party render throw to the vehicle type (cosmetic mis-attribution, contained); the
play→config reconfiguration path skips `onSessionEnd()` so the crash latch resets only at the
next real disconnect (fail-safe, loader-equivalent to Fabric — a pre-existing residual, not a
port defect); the dead `poseStack == null` guard (NeoForge never returns null there) is kept
for Fabric-verbatim minimization.

**Deferred to the live test (unverifiable statically):** the exact NeoForge-API firing points
(EntityMountEvent/EntityEvent.Size/render events inside the pass), Iris/Oculus shadow-pass
behavior, and in-game buffer-drain/translucency parity — the modded-mount + shader live smoke
(§7) is their gate.
