package dev.vox.lss.compat;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/** The Fabric client's chunk hooks for {@link VertigoCompat} (issue #437): the unload event,
 *  which fires while the chunk's sections are still in the client chunk cache, the load event,
 *  and the once-a-second sweep for chunks a teleport evicted without an unload event. */
public final class VertigoFabricHook {
    private VertigoFabricHook() {}

    public static void init() {
        if (!VertigoCompat.available()) return;
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) ->
                VertigoCompat.chunkUnloading(level, chunk, Minecraft.getInstance().options.serverRenderDistance));
        ClientChunkEvents.CHUNK_LOAD.register(VertigoCompat::chunkLoaded);
        ClientTickEvents.END_CLIENT_TICK.register(mc ->
                VertigoCompat.clientTick(mc.level, mc.options.serverRenderDistance));
    }
}
