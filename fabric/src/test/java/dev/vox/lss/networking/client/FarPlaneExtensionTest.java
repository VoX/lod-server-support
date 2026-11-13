package dev.vox.lss.networking.client;

import dev.vox.lss.config.ClientConfigTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Far-plane plan #364 §4/§5: the frame-start / apply / renderer protocol around the policy. */
class FarPlaneExtensionTest {

    private static final float VANILLA = 512f;
    private static final Object LEVEL = new Object();

    @BeforeEach
    void reset() {
        FarPlaneExtension.resetForTest();
        FarPlaneExtension.lodRenderer = () -> true;
        ClientConfigTestSupport.set("far_players.extend_far_plane", true);
    }

    @AfterEach
    void restore() {
        ClientConfigTestSupport.set("far_players.extend_far_plane", true); // the default
        FarPlaneExtension.resetForTest();
    }

    /** One frame: frame start, the far-plane read, then the renderer's pass. */
    private static float frame(Object level, double farthest) {
        FarPlaneExtension.frameStart(level);
        float used = FarPlaneExtension.apply(VANILLA);
        if (farthest > 0) FarPlaneExtension.report(farthest);
        FarPlaneExtension.rendererPass(used);
        return used;
    }

    @Test
    void growsOneFrameAfterTheRendererSeesAFarPlayer() {
        assertEquals(VANILLA, frame(LEVEL, 3000), "this frame's plane was fixed before the report");
        assertEquals(3072f, frame(LEVEL, 0), "the next frame publishes it");
        assertEquals("far_plane=3072 (vanilla 512)", FarPlaneExtension.diagFragment());
    }

    @Test
    void aLevelChangeAndADisconnectReset() {
        frame(LEVEL, 3000);
        assertEquals(3072f, frame(LEVEL, 0));
        assertEquals(VANILLA, frame(new Object(), 0), "a new ClientLevel (dimension change) starts vanilla");
        frame(LEVEL, 3000);
        FarPlaneExtension.onDisconnect();
        assertEquals(VANILLA, FarPlaneExtension.apply(VANILLA), "no level after a disconnect: vanilla");
    }

    @Test
    void noLevelStaysVanilla() {
        frame(null, 3000);
        assertEquals(VANILLA, frame(null, 0));
    }

    @Test
    void offIsVanillaAndSaysSo() {
        ClientConfigTestSupport.set("far_players.extend_far_plane", false);
        frame(LEVEL, 3000);
        assertEquals(VANILLA, frame(LEVEL, 0));
        assertEquals("far_plane=512 (vanilla 512), extend=off", FarPlaneExtension.diagFragment());
    }

    @Test
    void withoutAnLodRendererTheDefaultDoesNothingAndSaysSo() {
        // On by default, but a far player past the vanilla plane is fully fogged without distant
        // terrain: the plane must stay vanilla for players who only use a map mod.
        FarPlaneExtension.lodRenderer = () -> false;
        frame(LEVEL, 3000);
        assertEquals(VANILLA, frame(LEVEL, 0));
        assertEquals("far_plane=512 (vanilla 512), extend=idle (no LOD renderer)", FarPlaneExtension.diagFragment());
        // The renderer appears (Voxy starts taking columns): the next report grows the plane.
        FarPlaneExtension.lodRenderer = () -> true;
        frame(LEVEL, 3000);
        assertEquals(3072f, frame(LEVEL, 0));
    }

    @Test
    void anOverrideIsReported() {
        frame(LEVEL, 3000);
        FarPlaneExtension.frameStart(LEVEL);
        assertEquals(3072f, FarPlaneExtension.apply(VANILLA));
        FarPlaneExtension.rendererPass(VANILLA); // another mod lowered it after us
        assertTrue(FarPlaneExtension.diagFragment().endsWith("far_plane_off=override"),
                FarPlaneExtension.diagFragment());
        FarPlaneExtension.rendererPass(3072f); // recomputed each pass
        assertEquals("far_plane=3072 (vanilla 512)", FarPlaneExtension.diagFragment());
    }

    @Test
    void aMissingFrameStartHookIsReported() {
        // The getter hook runs (on 1.21.x the renderer's own getDepthFar() call stamps it) but the
        // frame-start hook never did: the option is silently dead without this check.
        float used = FarPlaneExtension.apply(VANILLA);
        FarPlaneExtension.rendererPass(used);
        assertTrue(FarPlaneExtension.diagFragment().endsWith("far_plane_off=hook"),
                FarPlaneExtension.diagFragment());
    }
}
