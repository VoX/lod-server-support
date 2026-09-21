package dev.vox.lss.paper;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Per-world LOD-distance resolution for Paper: the Bukkit world name is tried first
 * (so a Multiverse world like {@code creative} or a vanilla {@code world_nether}
 * matches on the name an admin actually sees), then the dimension id
 * ({@code minecraft:the_nether}) as a fallback. First key present in the override map
 * wins; an unlisted world falls through to {@code config.lodDistanceChunks}.
 *
 * <p>Resolution is LEVEL-keyed at the core ({@link #distance(PaperConfig, ServerLevel)});
 * the player and dimension-key entry points funnel into it. Every read is guarded — a
 * resolution failure degrades to the default, never an exception into a serve path.
 */
final class PaperWorldLod {

    /** A player resolves through its own level, so the Bukkit-name / dim-id extraction
     *  lives in ONE place ({@link #distance(PaperConfig, ServerLevel)}). */
    static int distance(PaperConfig config, ServerPlayer player) {
        return player == null ? config.lodDistanceChunks : distance(config, levelOf(player));
    }

    /** The core resolver: Bukkit world name, then dimension id, then the default. */
    static int distance(PaperConfig config, ServerLevel level) {
        if (level == null) return config.lodDistanceChunks;
        return config.lodDistanceForWorld(worldName(level), dimensionId(level));
    }

    /** The distance for a dimension {@link ResourceKey} — resolves the level so a
     *  Bukkit-name override still matches (a dim-id-only lookup would miss a name-keyed
     *  override). Used by the dimension-change compare for the PREVIOUS world. If the
     *  level is gone (the previous world unloaded within the tick — a near-impossible
     *  window for a world a player just left) it falls back to the dim-id key, which a
     *  name-keyed override would miss; the only cost is a skipped re-push that self-heals
     *  on the client's next rejoin (safe direction — never wrong terrain). */
    static int distanceForDimKey(PaperConfig config, MinecraftServer server, ResourceKey<Level> key) {
        if (key == null) return config.lodDistanceChunks;
        ServerLevel level = null;
        try {
            if (server != null) level = server.getLevel(key);
        } catch (Throwable ignored) {
            // fall through to the dim-id key
        }
        if (level != null) return distance(config, level);
        return config.lodDistanceForWorld(key.identifier().toString());
    }

    private static ServerLevel levelOf(ServerPlayer player) {
        try {
            return player.level() instanceof ServerLevel sl ? sl : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String worldName(ServerLevel level) {
        try {
            var world = level.getWorld();
            return world == null ? null : world.getName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String dimensionId(ServerLevel level) {
        try {
            var dim = level.dimension();
            return dim == null ? null : dim.identifier().toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private PaperWorldLod() {}
}
