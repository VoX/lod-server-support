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
 * ({@code RENDER_AVAILABLE = true} — the four port lines carry a stub there).
 *
 * <p>The expected strings are LINE FACTS (this 1.21.10 line: {@code LightTexture} + the
 * extract/submit pipeline with the light floor set on the extracted render state, tags through
 * {@code submitText}, the armor lift as a pose scale through {@code LiftedSubmitCollector}, NO
 * frustum cull and NO shared-batch end — both named cuts, see per-version-surfaces.md; the
 * 1.21.1 line renders immediate-mode with {@code drawInBatch}/{@code LiftedBufferSource}) —
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
        if (!src.contains("RENDER_AVAILABLE = true")) return; // a stub line — nothing to pin
        pin(src, "neoforge");
        // NeoForge-only clauses (review folds D2/C4): the nameplate-distance attribute both ways
        // (a server-shortened range hides, a server-raised range defers to vanilla's own tag),
        // and every per-proxy containment restores the pose sentinel.
        assertTrue(src.contains("realPlayer.getAttribute(NeoForgeMod.NAMETAG_DISTANCE)")
                        && src.contains("if (range < 64.0) continue;")
                        && src.contains("if (range > 64.0 && cameraDistanceSq < range * range) continue;"),
                "neoforge: the NAMETAG_DISTANCE attribute must be honoured in both directions");
        assertEquals(4, count(src, "restorePose(poseStack, passMark);"),
                "neoforge: three per-proxy drops + the mount catch must restore the pose sentinel");
    }

    private static void pin(String src, String tree) throws java.io.IOException {
        // WI-1/WI-2: the sky-15 floor with the real block light, the full-bright short-circuit,
        // NO full-bright-by-chunk-state fallback (dead code: ClientLevel.hasChunk is
        // unconditionally true on this MC too), and — this line's pipeline — the floor applied
        // to the EXTRACTED render state before submit, proxies (both draws) and mounts alike
        // (LivingEntityRenderer.submit reads lightCoords at draw time).
        assertTrue(src.contains("LightTexture.pack(LightTexture.block(vanilla), 15)"),
                tree + ": proxy light must be the sky-15 floor over vanilla's block light");
        assertTrue(src.contains("if (fullBright) return LightTexture.FULL_BRIGHT;"),
                tree + ": farPlayersFullBright must short-circuit to FULL_BRIGHT");
        assertFalse(src.contains("LightTexture.pack(15, 15)"),
                tree + ": the loaded/unloaded full-bright split must stay deleted (dead code)");
        assertTrue(src.contains("renderState.lightCoords = floorLight(renderState.lightCoords, fullBright);")
                        && src.contains("vState.lightCoords = floorLight(vState.lightCoords, fullBright);"),
                tree + ": the floor must land on the extracted render state of proxies AND mounts");
        assertEquals(2, count(src, "submitProxy(dispatcher, proxy, partialTick, fullBright, cameraDistance,"),
                tree + ": both proxy draws (seated and unseated) must go through the one submit helper");
        assertTrue(src.contains("renderState.nameTag = null;") && src.contains("avatar.scoreText = null;"),
                tree + ": vanilla's own tag AND score plate must be nulled on the extracted proxy state");
        // WI-3 is a NAMED CUT on this line (no frustum on the render path — WorldRenderContext
        // carries commandQueue/matrices/worldState, CameraRenderState has none): no frustum
        // predicate at all, and never dispatcher.shouldRender (vanilla's distance term + Sable's
        // injection) as a substitute.
        assertFalse(src.contains("isInFrustum(") || src.contains("Frustum"),
                tree + ": the draw-call frustum cull is a named cut on this line (no frustum here)");
        assertFalse(src.contains("dispatcher.shouldRender("),
                tree + ": never dispatcher.shouldRender — it carries the distance cull");
        // WI-4 is n/a on this line: submits are deferred to vanilla's FeatureRenderDispatcher,
        // the pass opens no shared batch of its own, so it ends none (the arg-less endBatch
        // stays forbidden regardless).
        assertFalse(src.contains("endLastBatch") || src.contains(".endBatch()"),
                tree + ": no batch end on the deferred-submit pipeline");
        // WI-5: the model-parts byte, all layers but the cape (cloak physics never run) unless elytra.
        assertTrue(src.contains("(byte) (elytra ? 0x7F : 0x7E)"),
                tree + ": the model-parts byte must expose the overlay layers (cape only with an elytra)");
        // WI-6: own name tag — depth-tested only, sneak-hidden, sqrt-scaled, camera-distance based
        // (review fold F1); vanilla's path off.
        assertTrue(src.contains("Font.DisplayMode.NORMAL") && !src.contains("SEE_THROUGH"),
                tree + ": the far name tag is depth-tested only (no see-through pass)");
        assertTrue(src.contains("FarPlayerWire.POSE_SNEAK) != 0) return;"),
                tree + ": sneaking far players show no tag");
        assertTrue(src.contains("0.025f * (float) Math.clamp(Math.sqrt(cameraDistance / 64.0), 1.0, 8.0)"),
                tree + ": the tag scale rule is 0.025 × sqrt(d/64) clamped to [1, 8], d = camera distance");
        assertTrue(src.contains("this.setCustomNameVisible(false);") && !src.contains("setCustomName("),
                tree + ": vanilla's tag path stays off and the name is cached, not re-set per frame");
        // Live-rig fold (a): the plate/glyph z-fight fix is a SECOND, glyph-only draw in
        // POLYGON_OFFSET mode (vanilla's outline idiom), all first draws before all second ones.
        assertTrue(src.contains("Font.DisplayMode.NORMAL, true);")
                        && src.contains("Font.DisplayMode.POLYGON_OFFSET, false);")
                        && src.contains("collector.submitText(poseStack, x, 0.0f, tag.text(), false, mode, tag.light(), -1,\n"
                                + "                    withPlate ? background : 0, 0);"),
                tree + ": the second, glyph-only tag draw must use POLYGON_OFFSET and no plate (submitText, 10-arg)");
        assertTrue(src.contains("        poseStack.pushPose();\n        try {")
                        && src.contains("        } finally {\n            poseStack.popPose();\n        }"),
                tree + ": the tag draw's push must be unwound in a finally (fold D1)");
        // Fold (b): the 64..tracking-radius gap — tracked players past vanilla's cap get the LSS
        // tag under vanilla's ladder, camera-distance based, only where vanilla drew a body.
        assertTrue(src.contains("boolean nameTags = config.farPlayersNameTags && Minecraft.renderNames();"),
                tree + ": every tag (proxy or real) must honour the hide-GUI key (fold D3)");
        assertTrue(src.contains("for (var realPlayer : level.players())")
                        && src.contains("if (active.contains(realPlayer.getUUID())) continue;")
                        && src.contains("if (!level.isOutsideBuildHeight(pos.getY())")
                        && src.contains("&& !minecraft.levelRenderer.isSectionCompiled(pos)) continue;")
                        && src.contains("realPlayer.getYRot(partialTick)")
                        && src.contains("double cameraDistanceSq = cameraPosition.distanceToSqr(realPosition);")
                        && src.contains("if (cameraDistanceSq < 64.0 * 64.0) continue;")
                        && src.contains("if (!vanillaNameVisibleIgnoringDistance(realPlayer, localPlayer)) continue;")
                        && src.contains("Mth.lerp(partialTick, realPlayer.xOld, realPlayer.getX())"),
                tree + ": tracked players past vanilla's 64-block cap get the LSS tag under vanilla's ladder");
        assertTrue(src.contains("queueProxyTag(pendingTags, nameTags, tracked, proxy, localPlayer, position, light);")
                        && src.contains("if (!vanillaNameVisibleIgnoringDistance(proxy, localPlayer)) return;"),
                tree + ": proxy tags route through the option + sneak gate AND vanilla's team ladder (fold D3)");
        // Fold (c) does NOT apply here: this MC's WalkAnimationState.stop() zeroes speedOld as
        // well as speed (verified at the port), so vanilla's stop is the walk-cycle stop and no
        // setSpeed(0) helper may creep in (it would re-open the 1.21.1 sawtooth shape).
        assertEquals(2, count(src, "this.walkAnimation.stop();"),
                tree + ": both walk-cycle stops go through vanilla's stop()");
        assertFalse(src.contains("walkAnimation.setSpeed("), tree + ": no setSpeed on a line whose stop() zeroes speedOld");
        // Fold (d): the other tick-only render inputs — the FALL_FLYING shared flag + fall-fly
        // ticks (glide tilt, spread wings), the swim amount (swimming roll + stroke) and the
        // water flag behind the swim pitch term — advanced by the proxy itself, once per tick.
        assertTrue(src.contains("this.setSharedFlag(SHARED_FLAG_FALL_FLYING, gliding);")
                        && src.contains("this.fallFlyTicks = gliding ? Math.min(this.fallFlyTicks + 1, 10) : 0;")
                        && src.contains("swim.lss$setSwimAmountO(swimAmount);")
                        && src.contains("this.wasTouchingWater = swimming;"),
                tree + ": glide flag/ticks, swim amount and the water flag must be faked per tick on the proxy");
        // Fold (e): armor/held items are depth-lifted — on this line's deferred-submit pipeline
        // through the wrapping submit collector, which scales every non-skin submit's POSE
        // toward the camera (the skin's own type passes through with the original pose); the
        // overlay layers are distance-gated. Mounts are submitted through the bare collector.
        assertTrue(src.contains("new LiftedSubmitCollector(collector, skinRenderType(dispatcher, proxy),"),
                tree + ": the proxy draw must go through the lifting submit collector");
        assertTrue(src.contains("poseStack,\n                collector); // fold (e): mounts are NOT lifted"),
                tree + ": mounts are never lifted");
        assertTrue(src.contains("m.scaleLocal((d - tierLift) / d)")
                        && src.contains("public OrderedSubmitNodeCollector order(int order)"),
                tree + ": the lift is a uniform scale about the camera, and order() is wrapped (the armor layer submits through it)");
        assertTrue(src.contains("if (type == skinType) return poseStack;")
                        && src.contains("Math.clamp(distance * distance * 6e-6, 0.02, 4.0)")
                        && src.contains("armorLiftBlocks(cameraDistance)")
                        && src.contains("cameraDistance <= OVERLAY_MAX_DISTANCE_BLOCKS")
                        && src.contains("OVERLAY_MAX_DISTANCE_BLOCKS = 80.0")
                        && src.contains("(byte) (elytra ? 0x7F : 0x7E)"),
                tree + ": the skin buffer is never lifted, the lift is distance-scaled, overlays are gated");
        // Fold (e2): the lift is TIERED per render type from the proxy's own equipment — inner
        // armor model lowest, outer armor + trims + glint above, everything else above that.
        assertTrue(src.contains("refreshLiftTiers(equipmentAssets);")
                        && src.contains("int tier = tiers.getOrDefault(type, 2);")
                        && src.contains("lift * (1.0f + 0.8f * tier)")
                        && src.contains("boolean inner = slot == EquipmentSlot.LEGS;")
                        && src.contains("liftTiers.put(RenderType.armorCutoutNoCull(layer.getTextureLocation(layerType)), inner ? 0 : 1);")
                        && src.contains("a.lss$getEquipmentAssets() : null;"),
                tree + ": overlapping armor pieces must sit on different lift tiers");
        // Fold (D1): the pass runs above a sentinel pose and every containment restores to it.
        assertTrue(src.contains("passMark = poseStack == null ? null : markPose(poseStack);")
                        && src.contains("if (passMark != null) unwindPose(poseStack, passMark);")
                        && src.contains("restorePose(poseStack, passMark);"),
                tree + ": the pose-stack sentinel must guard the whole pass and its containments");
    }

    private static int count(String src, String needle) {
        int n = 0;
        for (int i = src.indexOf(needle); i >= 0; i = src.indexOf(needle, i + needle.length())) n++;
        return n;
    }
}
