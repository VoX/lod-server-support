package dev.vox.lss.testutil;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source pins for the far-player renderer's 2026-09-04 hardening decisions
 * (far-player-render-hardening-plan.md; the renderer is version-volatile and render-only, so
 * no JUnit can exercise it — the DECISIONS are pinned as text instead, the
 * {@code NeoForgeLoaderSeamContractTest} idiom). The Fabric renderer is the source of truth
 * and is pinned unconditionally; the NeoForge twin is pinned only where it renders
 * ({@code RENDER_AVAILABLE = true} — on this line it is a stub).
 *
 * <p>The expected strings are LINE FACTS (this 26.2 line: {@code LightCoordsUtil} + the
 * extract/submit pipeline, the frustum from the extracted camera state, {@code submitText}
 * tags, the pose-scaling {@code LiftedSubmitCollector} in place of 1.21.1's per-vertex
 * {@code LiftedBufferSource}, no batch to end, a real {@code WalkAnimationState.stop()}) —
 * hand-mirrored per line like the other contract tests.
 */
class FarPlayerRenderSourceContractTest {

    private static final String FABRIC = "fabric/src/main/java/dev/vox/lss/networking/client/FarPlayerRenderer.java";
    private static final String NEOFORGE = "neoforge/src/main/java/dev/vox/lss/networking/client/FarPlayerRenderer.java";

    @Test
    void theFabricRendererCarriesEveryHardeningDecision() throws IOException {
        pin(Files.readString(RepoPaths.locate(FABRIC)), "fabric");
    }

    @Test
    void theNeoForgeTwinCarriesThemWhereItRenders() throws IOException {
        Path twin = RepoPaths.locate(NEOFORGE);
        String src = Files.readString(twin);
        if (!src.contains("RENDER_AVAILABLE = true")) {
            // A stub line — nothing to pin except the xplat diag coupling it must satisfy.
            assertTrue(src.contains("public static String diagLine()"),
                    "neoforge: the stub must still carry diagLine() for the xplat command");
            return;
        }
        pin(src, "neoforge");
    }

    private static void pin(String src, String tree) {
        // WI-1/WI-2: the sky-15 floor with the real block light, the full-bright short-circuit,
        // written INTO the extracted render state (this line's submit pipeline reads
        // state.lightCoords at draw time), for proxies AND mounts; no chunk-state fallback.
        assertTrue(src.contains("LightCoordsUtil.pack(LightCoordsUtil.block(vanilla), 15)"),
                tree + ": proxy light must be the sky-15 floor over vanilla's block light");
        assertTrue(src.contains("if (fullBright) return LightCoordsUtil.FULL_BRIGHT;"),
                tree + ": farPlayersFullBright must short-circuit to FULL_BRIGHT");
        assertTrue(src.contains("renderState.lightCoords = light;")
                        && src.contains("vState.lightCoords = packedLightFor(dispatcher, mount.entity, partialTick, fullBright);"),
                tree + ": the floored light must be written into the extracted state (proxy and mount)");
        assertFalse(src.contains("pack(15, 15)"),
                tree + ": no loaded/unloaded full-bright split (ClientLevel.hasChunk is unconditionally true)");
        // WI-3: the submits (only) are frustum-culled — proxies AND mounts — from the extracted
        // camera state's frustum (gated on initialized), and never via dispatcher.shouldRender
        // (vanilla's distance term + Sable's injection).
        assertTrue(src.contains("Frustum frustum = cameraState.initialized ? cameraState.cullFrustum : null;"),
                tree + ": the frustum comes from the extracted camera state, gated on initialized");
        assertTrue(src.contains("if (!isInFrustum(frustum, proxy)) return CULLED;")
                        && src.contains("if (!isInFrustum(frustum, mount.entity)) return -1;"),
                tree + ": proxy and mount submits must be frustum-culled");
        assertTrue(src.indexOf("if (!isInFrustum(frustum, proxy)) return CULLED;")
                        < src.indexOf("var renderState = dispatcher.extractEntity(proxy, partialTick);"),
                tree + ": the proxy's frustum test must run BEFORE its extraction (port review fold 1)");
        assertTrue(src.contains("() -> light != CULLED ? light : packedLightFor(dispatcher, proxy, partialTick, fullBright)"),
                tree + ": a culled proxy reads the light only if its tag survives (lazy supplier)");
        // Port review fold 2: inside vanilla's tag range (tracking radius < 64) vanilla would draw
        // its own tag + score plate over LSS's — both are cleared on the extracted state.
        assertTrue(src.contains("renderState.nameTag = null;")
                        && src.contains("if (renderState instanceof AvatarRenderState avatar) avatar.scoreText = null;"),
                tree + ": vanilla's name tag and score text must be suppressed on the proxy's render state");
        assertTrue(src.contains("AABB box = entity.getBoundingBox().inflate(0.5);"),
                tree + ": the cull box is the entity box (noCulling/getBoundingBoxForCulling are renderer-protected here)");
        assertFalse(src.contains("dispatcher.shouldRender("),
                tree + ": never dispatcher.shouldRender — it carries the distance cull");
        // WI-4 has no analogue on the submit pipeline (the storage drains the frame itself);
        // the arg-less endBatch stays forbidden everywhere.
        assertFalse(src.contains(".endBatch()"), tree + ": the arg-less endBatch() is forbidden here");
        // WI-5: the model-parts byte, all layers but the cape (cloak physics never run) unless elytra.
        assertTrue(src.contains("(byte) (elytra ? 0x7F : 0x7E)")
                        && src.contains("this.getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, parts);"),
                tree + ": the model-parts byte must expose the overlay layers (cape only with an elytra)");
        // WI-6: own name tag through submitText (never submitNameTag — its own translate/scale
        // and null-attachment no-op) — depth-tested only, sneak-hidden, sqrt-scaled,
        // camera-distance based (review fold F1); vanilla's path off.
        assertTrue(src.contains("collector.submitText(poseStack, x, 0.0f, name.getVisualOrderText(), false, mode, tag.light(), -1,\n"
                        + "                    withPlate ? background : 0, 0);")
                        && !src.contains("collector.submitNameTag("),
                tree + ": the far name tag is a submitText with OUR pose, plate on the first submit only");
        assertTrue(src.contains("Font.DisplayMode.NORMAL") && !src.contains("SEE_THROUGH"),
                tree + ": the far name tag is depth-tested only (no see-through pass)");
        assertTrue(src.contains("FarPlayerWire.POSE_SNEAK) != 0) return;"),
                tree + ": sneaking far players show no tag");
        assertTrue(src.contains("0.025f * (float) Math.clamp(Math.sqrt(cameraDistance / 64.0), 1.0, 8.0)"),
                tree + ": the tag scale rule is 0.025 × sqrt(d/64) clamped to [1, 8], d = camera distance");
        assertTrue(src.contains("this.setCustomNameVisible(false);") && !src.contains("setCustomName("),
                tree + ": vanilla's tag path stays off and the name is cached, not re-set per frame");
        // Live-rig fold (a): the plate/glyph z-fight fix is a SECOND, glyph-only submit in
        // POLYGON_OFFSET mode (vanilla's outline idiom), all first submits before all second ones.
        assertTrue(src.contains("Font.DisplayMode.NORMAL, true);")
                        && src.contains("Font.DisplayMode.POLYGON_OFFSET, false);"),
                tree + ": the second, glyph-only tag submit must use POLYGON_OFFSET and no plate");
        assertTrue(src.contains("        poseStack.pushPose();\n        try {")
                        && src.contains("        } finally {\n            poseStack.popPose();\n        }"),
                tree + ": the tag submit's push must be unwound in a finally (fold D1)");
        // Fold (b): the tag gap past vanilla's own cap — tracked players past it get the LSS tag
        // under vanilla's ladder, camera-distance based, only where vanilla drew a body. On this
        // line vanilla's cap is the NAME_TAG_DISTANCE attribute (default 64), honoured both ways.
        assertTrue(src.contains("boolean nameTags = config.farPlayersNameTags && !minecraft.gui.hud.isHidden();"),
                tree + ": every tag (proxy or real) must honour the hide-GUI key (fold D3)");
        assertTrue(src.contains("for (var realPlayer : level.players())")
                        && src.contains("if (active.contains(realPlayer.getUUID())) continue;")
                        && src.contains("&& !minecraft.levelRenderer.isSectionCompiledAndVisible(realBlock)) continue;")
                        && src.contains("double cameraDistanceSq = cameraPosition.distanceToSqr(realPosition);")
                        && src.contains("double range = realPlayer.getAttributeValue(Attributes.NAME_TAG_DISTANCE);")
                        && src.contains("if (cameraDistanceSq < range * range) continue;")
                        && src.contains("if (!vanillaNameVisibleIgnoringDistance(realPlayer, localPlayer)) continue;")
                        && src.contains("Mth.lerp(partialTick, realPlayer.xOld, realPlayer.getX())"),
                tree + ": tracked players past vanilla's tag range get the LSS tag under vanilla's ladder");
        assertTrue(src.contains("queueProxyTag(pendingTags, frustum, nameTags, tracked, proxy, localPlayer, position,")
                        && src.contains("if (!vanillaNameVisibleIgnoringDistance(proxy, localPlayer)) return;"),
                tree + ": proxy tags route through the option + sneak gate AND vanilla's team ladder (fold D3)");
        // Fold (c): this line's WalkAnimationState.stop() zeroes speedOld (verified) — the stop
        // goes through it, never a bare setSpeed(0).
        assertTrue(src.contains("this.walkAnimation.stop();") && !src.contains("walkAnimation.setSpeed("),
                tree + ": the walk-cycle stop is this line's WalkAnimationState.stop()");
        // Fold (d): the other tick-only render inputs — the FALL_FLYING shared flag + fall-fly
        // ticks (glide tilt), the swim amount (swimming roll + stroke), the water flag behind the
        // swim pitch term, and (this line) the elytra animation state behind the wing angles —
        // advanced by the proxy itself, once per tick.
        assertTrue(src.contains("this.setSharedFlag(FLAG_FALL_FLYING, gliding);")
                        && src.contains("this.fallFlyTicks = gliding ? Math.min(this.fallFlyTicks + 1, 10) : 0;")
                        && src.contains("swim.lss$setSwimAmountO(swimAmount);")
                        && src.contains("this.wasTouchingWater = swimming;")
                        && src.contains("this.elytraAnimationState.tick();"),
                tree + ": glide flag/ticks, swim amount, water flag and elytra state must be faked per tick on the proxy");
        // Fold (e): armor/held items are depth-lifted through the wrapping collector (the skin's
        // own submission passes through; the pose is scaled about the camera), the overlay
        // layers are distance-gated; mounts are not wrapped.
        assertEquals(1, count(src, "new LiftedSubmitCollector(collector, skinModel(dispatcher, proxy), skinRenderType(dispatcher, proxy),"),
                tree + ": the proxy submit must go through the lifting collector");
        assertTrue(src.contains("int tier = model == skinModel ? -1 : tierOf(renderType);")
                        && src.contains("if (type == skinType) return -1;")
                        && src.contains("pose.pose().scaleLocal(f);")
                        && src.contains("Math.clamp(distance * distance * 6e-6, 0.02, 4.0)")
                        && src.contains("OVERLAY_MAX_DISTANCE_BLOCKS = 80.0")
                        && src.contains("byte parts = cameraDistance <= OVERLAY_MAX_DISTANCE_BLOCKS")
                        && src.contains("armorLiftBlocks(cameraDistance), cameraDistance, proxy.liftTiers));"),
                tree + ": the skin submission is never lifted, the lift and the overlay gate use the CAMERA distance, overlays are gated");
        assertTrue(src.contains("                collector); // mounts are not lifted"),
                tree + ": mounts submit through the raw collector");
        // Fold (e2): the lift is TIERED per render type from the proxy's own equipment — inner
        // armor model lowest, outer armor + trims + glint above, everything else above that —
        // through the equipment-asset layers (the accessor mixin), never string-sniffed.
        assertTrue(src.contains("refreshLiftTiers(equipmentAssets);")
                        && src.contains("return tiers.getOrDefault(type, 2);")
                        && src.contains("float pull = lift * (1.0f + 0.8f * tier);")
                        && src.contains("boolean inner = slot == EquipmentSlot.LEGS;")
                        && src.contains("liftTiers.put(RenderTypes.armorCutoutNoCull(layer.getTextureLocation(layerType)), inner ? 0 : 1);")
                        && src.contains("((AccessorEntityRenderDispatcher) dispatcher).lss$getEquipmentAssets()"),
                tree + ": overlapping armor pieces must sit on different lift tiers");
        assertTrue(src.contains("return root == null ? this : new LiftedSubmitCollector(root.order(order), this);"),
                tree + ": ordered sub-collectors (the armor layer's) must be wrapped too");
        assertTrue(src.contains("tierOf(ChunkSectionLayerHelper.getRenderType(translucent))"),
                tree + ": the FRAPI block-model overload resolves its tier like the vanilla one");
        // Fold (D1): the pass runs above a sentinel pose and every containment restores to it.
        assertTrue(src.contains("passMark = poseStack == null ? null : markPose(poseStack);")
                        && src.contains("if (passMark != null) unwindPose(poseStack, passMark);"),
                tree + ": the pose-stack sentinel must guard the whole pass");
        assertEquals(2, count(src, "restorePose(poseStack, passMark);"),
                tree + ": the seated-proxy catch and the mount catch must restore the pose sentinel");
    }

    private static int count(String src, String needle) {
        int n = 0;
        for (int i = src.indexOf(needle); i >= 0; i = src.indexOf(needle, i + needle.length())) n++;
        return n;
    }
}
