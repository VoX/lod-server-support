package dev.vox.lss.compat;

import net.fabricmc.loader.api.FabricLoader;

import java.util.OptionalInt;

/**
 * Handles optional mod integrations. Checks for supported LOD mods at startup
 * and registers consumers to bridge received chunk data into their pipelines.
 * <p>
 * Each integration is isolated in its own class to avoid classloading issues
 * when the target mod is not present.
 */
public final class ModCompat {
    private static boolean voxyLoaded;

    public static void init() {
        if (FabricLoader.getInstance().isModLoaded("voxy")) {
            voxyLoaded = VoxyCompat.init();
        }
    }

    public static OptionalInt getVoxyViewDistanceChunks() {
        if (!voxyLoaded) return OptionalInt.empty();
        return VoxyCompat.getViewDistanceChunks();
    }
}
