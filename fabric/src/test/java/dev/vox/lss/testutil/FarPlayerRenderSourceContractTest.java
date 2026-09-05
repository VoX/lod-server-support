package dev.vox.lss.testutil;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source pins for the far-player renderer's 2026-09-04 hardening decisions
 * (far-player-render-hardening-plan.md; the renderer is version-volatile and render-only, so
 * no JUnit can exercise it — the DECISIONS are pinned as text instead, the
 * {@code NeoForgeLoaderSeamContractTest} idiom). The Fabric renderer is the source of truth
 * and is pinned unconditionally; the NeoForge twin is pinned only where it renders
 * ({@code RENDER_AVAILABLE = true} — the four port lines carry a stub there).
 *
 * <p>The expected strings are LINE FACTS (this 1.21.1 line: {@code LightTexture} +
 * immediate {@code dispatcher.render}; the 26.x lines use {@code LightCoordsUtil} + the
 * submit pipeline) — hand-mirrored per line like the other contract tests.
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
    }

    private static void pin(String src, String tree) {
        // WI-1/WI-2: the sky-15 floor with the real block light, the full-bright short-circuit,
        // and NO full-bright-by-chunk-state fallback (it was dead code: ClientLevel.hasChunk
        // is unconditionally true on this MC).
        assertTrue(src.contains("LightTexture.pack(LightTexture.block(vanilla), 15)"),
                tree + ": proxy light must be the sky-15 floor over vanilla's block light");
        assertTrue(src.contains("if (fullBright) return LightTexture.FULL_BRIGHT;"),
                tree + ": farPlayersFullBright must short-circuit to FULL_BRIGHT");
        assertFalse(src.contains("LightTexture.pack(15, 15)"),
                tree + ": the loaded/unloaded full-bright split must stay deleted (dead code)");
        // WI-3: the draw calls (only) are frustum-culled — proxies AND mounts — and never via
        // dispatcher.shouldRender (vanilla's distance term + Sable's injection).
        assertTrue(src.contains("if (isInFrustum(frustum, proxy))")
                        && src.contains("if (!isInFrustum(frustum, mount.entity)) return -1;"),
                tree + ": proxy and mount draws must be frustum-culled");
        assertFalse(src.contains("dispatcher.shouldRender("),
                tree + ": never dispatcher.shouldRender — it carries the distance cull");
        // WI-4: end our own shared batch; the arg-less endBatch stays forbidden.
        assertTrue(src.contains("bs.endLastBatch();"), tree + ": the pass must end its own shared batch");
        assertFalse(src.contains(".endBatch()"), tree + ": the arg-less endBatch() is forbidden here");
        // WI-5: the model-parts byte, all layers but the cape (cloak physics never run) unless elytra.
        assertTrue(src.contains("(byte) (elytra ? 0x7F : 0x7E)"),
                tree + ": the model-parts byte must expose the overlay layers (cape only with an elytra)");
        // WI-6: own name tag — depth-tested only, sneak-hidden, sqrt-scaled; vanilla's path off.
        assertTrue(src.contains("Font.DisplayMode.NORMAL") && !src.contains("SEE_THROUGH"),
                tree + ": the far name tag is depth-tested only (no see-through pass)");
        assertTrue(src.contains("FarPlayerWire.POSE_SNEAK) != 0) return;"),
                tree + ": sneaking far players show no tag");
        assertTrue(src.contains("Math.clamp(Math.sqrt(tag.distance() / 64.0), 1.0, 8.0)"),
                tree + ": the tag scale rule is sqrt(d/64) clamped to [1, 8]");
        assertTrue(src.contains("this.setCustomNameVisible(false);") && !src.contains("setCustomName("),
                tree + ": vanilla's tag path stays off and the name is cached, not re-set per frame");
        // WI-6 live-rig folds (2026-09-04): the plate/glyph z-fight fix is a SECOND glyph-only
        // draw on a plane lifted toward the camera by a distance-scaled margin, and the tag gap
        // between vanilla's 64-block cap and the tracking radius is filled for tracked players.
        assertTrue(src.contains("Math.clamp(tag.distance() * tag.distance() * 1.5e-5, 0.05, 24.0)")
                        && src.contains("new Matrix4f(matrix).translate(0.0f, 0.0f, liftBlocks / scale)")
                        && src.contains("Font.DisplayMode.NORMAL, 0, tag.light())"),
                tree + ": the second, glyph-only tag draw must sit on the lifted plane");
        assertTrue(src.contains("for (var realPlayer : level.players())")
                        && src.contains("if (active.contains(realPlayer.getUUID())) continue;")
                        && src.contains("< 64.0 * 64.0) continue;")
                        && src.contains("if (!vanillaNameVisibleIgnoringDistance(realPlayer, localPlayer)) continue;"),
                tree + ": tracked players past vanilla's 64-block cap get the LSS tag under vanilla's ladder");
        assertTrue(src.contains("queueProxyTag(pendingTags, frustum, nameTags, tracked, proxy, position, distance, light);"),
                tree + ": proxy tags still route through the option + sneak gate");
    }
}
