package dev.vox.lss.compat;

import dev.vox.lss.testutil.SourcePaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiring pins for the Xaero map bridge (the {@code SaveHookContractTest}/
 * {@code LanHookContractTest} family): the whole feature hangs off THREE call
 * sites in shared glue — delete any of them and every behavioral test in
 * {@code XaeroMapCompatTest} stays green while the live bridge silently stops
 * (the queue fills to its bounds and drops forever, or the mod is never
 * detected at all). Source-regex pins, loader-neutral: both loaders call the
 * same {@code ClientNetGlue}/{@code ModCompat} bodies.
 */
class XaeroWiringContractTest {

    @Test
    void theClientTickGlueDrivesThePump() throws IOException {
        String glue = Files.readString(SourcePaths.mainSource(
                "dev/vox/lss/networking/client/ClientNetGlue.java"));
        int tickBody = glue.indexOf("public static void onEndClientTick()");
        assertTrue(tickBody >= 0, "onEndClientTick moved — retarget this pin");
        assertTrue(glue.indexOf("ModCompat.clientTick()", tickBody) > tickBody,
                "onEndClientTick must call ModCompat.clientTick() — the bridge's only pump");
    }

    @Test
    void theDisconnectGlueTearsDownTheSession() throws IOException {
        String glue = Files.readString(SourcePaths.mainSource(
                "dev/vox/lss/networking/client/ClientNetGlue.java"));
        int disconnectBody = glue.indexOf("public static void onDisconnect()");
        assertTrue(disconnectBody >= 0, "onDisconnect moved — retarget this pin");
        int tickBody = glue.indexOf("public static void onEndClientTick()");
        int end = tickBody > disconnectBody ? tickBody : glue.length();
        assertTrue(glue.substring(disconnectBody, end).contains("ModCompat.onDisconnect()"),
                "onDisconnect must call ModCompat.onDisconnect() — queue/latch/registration"
                        + " teardown (a stale queue can leak one tile into the NEXT server's map)");
        // The offer re-check under the queue lock (sweep C M2) is sound only because the
        // session gate flips BEFORE the bridge clears its queue — pin the order.
        String body = glue.substring(disconnectBody, end);
        assertTrue(body.indexOf("sessionGate.onDisconnect()") >= 0
                        && body.indexOf("sessionGate.onDisconnect()") < body.indexOf("ModCompat.onDisconnect()"),
                "sessionGate.onDisconnect() must precede ModCompat.onDisconnect()");
    }

    @Test
    void modCompatForwardsToTheBridge() throws IOException {
        String modCompat = Files.readString(SourcePaths.mainSource(
                "dev/vox/lss/compat/ModCompat.java"));
        assertTrue(modCompat.contains("isModLoaded(\"xaeroworldmap\")"),
                "init must gate on the xaeroworldmap mod id (identical on both loaders)");
        assertTrue(modCompat.contains("XaeroMapCompat.init()"),
                "init must initialize the bridge");
        assertTrue(modCompat.contains("XaeroMapCompat.clientTick()"),
                "clientTick must forward to the bridge's pump");
        assertTrue(modCompat.contains("XaeroMapCompat.renderFrame()"),
                "renderFrame must forward to the bridge's frame rebuild slice (plan §17)");
        assertTrue(modCompat.contains("XaeroMapCompat.onDisconnect()"),
                "onDisconnect must forward to the bridge's session teardown");

    }

    /**
     * The frame slice (plan §17) is the FOURTH wiring leg: without it every rebuild
     * falls back to tick cadence — all behavioral tests stay green (the fallback IS
     * the test path) while the live client stutters exactly like the pre-§17 build.
     */
    @Test
    void theRenderFrameGlueDrivesTheRebuildSlice() throws IOException {
        String glue = Files.readString(SourcePaths.mainSource(
                "dev/vox/lss/networking/client/ClientNetGlue.java"));
        int frameBody = glue.indexOf("public static void onRenderFrame()");
        assertTrue(frameBody >= 0, "onRenderFrame moved — retarget this pin");
        assertTrue(glue.indexOf("ModCompat.renderFrame()", frameBody) > frameBody,
                "onRenderFrame must call ModCompat.renderFrame() — the frame-cadence"
                        + " rebuild slice");
        String fabricGlue = Files.readString(SourcePaths.mainSource(
                "dev/vox/lss/networking/client/LSSClientNetworking.java"));
        // The event CLASS is per-line flavor (26.2 and 26.1 = level.LevelRenderEvents.END_MAIN,
        // the 1.21.x lines = a world.WorldRenderEvents member) — the invariant is that a
        // RENDER event drives onRenderFrame (§17.1 review fold: a bare call-site match
        // would also pass on a tick registration, the exact regression this pin exists
        // to catch; the lambda parameter name is not pinned).
        assertTrue(java.util.regex.Pattern.compile(
                        "RenderEvents\\s*\\.\\s*\\w+\\.register\\(\\s*\\w+ -> ClientNetGlue\\.onRenderFrame\\(\\)\\)")
                        .matcher(fabricGlue).find(),
                "the Fabric per-frame registration is gone or moved off a render event —"
                        + " rebuilds silently bunch back onto the client tick (the NeoForge"
                        + " twin is pinned in NeoForgeLoaderSeamContractTest)");
    }

    /**
     * The height derivation is PER-LINE data (26.x: getMinY/getMaxY()+1; 1.21.1:
     * getMinBuildHeight/getMaxBuildHeight, already exclusive) — the only test reaching
     * buildConsumer passes a null level, so the expression is pinned here (sweep C) and
     * this literal is edited on each line's port.
     */
    @Test
    void theConsumerPassesThisLinesWorldHeightExpression() throws IOException {
        String compat = Files.readString(SourcePaths.mainSource(
                "dev/vox/lss/compat/XaeroMapCompat.java"));
        assertTrue(compat.contains("level.getMinY(), level.getMaxY() + 1, columnData"),
                "the consumer's world-height arguments moved — a lost +1 silently drops the top section");
    }
}
