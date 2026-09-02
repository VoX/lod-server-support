package dev.vox.lss.networking.server;

import dev.vox.lss.common.config.ServerConfigBase;
import net.minecraft.server.level.ServerPlayer;

/**
 * Per-world LOD-distance resolution for Fabric / NeoForge — the ONE home for the
 * dimension-key extraction so the handshake, the batch range gate, the v16 declare,
 * and the re-push paths cannot drift (the {@code HandshakeGate} anti-duplication
 * precedent). The Paper twin is {@link dev.vox.lss.paper.PaperWorldLod}, which adds a
 * Bukkit-world-name key ahead of the dimension id.
 *
 * <p>Every method is STATIC and every read is guarded: {@code ServerReceiverGlue}
 * resolves the handshake distance even when no request service exists (the reply sits
 * outside the {@code service != null} block), so resolution must not need a service
 * instance, and a diagnostic-grade lookup must never throw into the handshake path.
 */
final class ServerWorldLod {

    /** The player's dimension resource-location string, or null on any failure shape
     *  (→ the default distance at lookup). */
    static String dimensionKey(ServerPlayer player) {
        try {
            var level = player.level();
            if (level == null) return null;
            var dim = level.dimension();
            return dim == null ? null : dim.identifier().toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The LOD distance for the player's CURRENT dimension — the override if the
     *  dimension id is keyed, else {@code config.lodDistanceChunks}. */
    static int distance(ServerConfigBase config, ServerPlayer player) {
        return config.lodDistanceForWorld(dimensionKey(player));
    }

    private ServerWorldLod() {}
}
