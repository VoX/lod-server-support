package dev.vox.lss.compat;

import dev.vox.lss.common.LSSLogger;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/** The Fabric client's hooks for {@link VertigoCompat} (issue #437): chunk loads, and the
 *  once-a-second sweep for the chunks that left. Registered only with Vertigo installed and the
 *  Voxy bridge running (call after {@code ModCompat.init()}). */
public final class VertigoFabricHook {
    private VertigoFabricHook() {}

    public static void init() {
        if (!FabricLoader.getInstance().isModLoaded("vertigo") || !VoxyCompat.isInitialized()) return;
        VertigoCompat.enable();
        ClientChunkEvents.CHUNK_LOAD.register(VertigoCompat::chunkLoaded);
        ClientTickEvents.END_CLIENT_TICK.register(mc ->
                VertigoCompat.clientTick(mc.level, mc.options.serverRenderDistance));
        LSSLogger.info("Vertigo detected: terrain it hides from Voxy is fetched again after you pass");
    }
}
