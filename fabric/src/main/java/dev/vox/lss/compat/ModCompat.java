package dev.vox.lss.compat;

import dev.vox.lss.common.LSSLogger;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Handles optional mod integrations. Checks for supported LOD mods at startup
 * and registers consumers to bridge received chunk data into their pipelines.
 * <p>
 * Each integration is isolated in its own class to avoid classloading issues
 * when the target mod is not present.
 */
public final class ModCompat {
    public static void init() {
        if (FabricLoader.getInstance().isModLoaded("voxy")) {
            VoxyCompat.init();
        }
    }
}
