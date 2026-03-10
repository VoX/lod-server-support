package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.payloads.DirtyColumnsS2CPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Periodically drains dirty chunk positions from {@link DirtyColumnTracker}
 * and broadcasts {@link DirtyColumnsS2CPayload} to nearby players.
 */
class DirtyColumnBroadcaster {

    private final MinecraftServer server;
    private final Map<UUID, PlayerRequestState> players;
    private final FabricOffThreadProcessor offThreadProcessor;
    private final DirtyColumnTracker dirtyTracker;

    private int counter = 0;
    private long[] positionFilterBuffer = null;

    DirtyColumnBroadcaster(MinecraftServer server, Map<UUID, PlayerRequestState> players,
                           FabricOffThreadProcessor offThreadProcessor, DirtyColumnTracker dirtyTracker) {
        this.server = server;
        this.players = players;
        this.offThreadProcessor = offThreadProcessor;
        this.dirtyTracker = dirtyTracker;
    }

    void tick(LSSServerConfig config) {
        int intervalTicks = config.dirtyBroadcastIntervalSeconds * LSSConstants.TICKS_PER_SECOND;
        if (++this.counter < intervalTicks) return;
        this.counter = 0;

        Set<UUID> failedPlayers = null;

        for (var level : this.server.getAllLevels()) {
            String dimensionStr = level.dimension().identifier().toString();
            long[] dirty = this.dirtyTracker.drainDirty(dimensionStr);
            if (dirty == null || dirty.length == 0) continue;

            this.offThreadProcessor.invalidateTimestamps(dimensionStr, dirty);

            int bufLen = Math.min(dirty.length, DirtyColumnsS2CPayload.MAX_POSITIONS);
            if (this.positionFilterBuffer == null || this.positionFilterBuffer.length < bufLen) {
                this.positionFilterBuffer = new long[bufLen];
            }

            for (var state : this.players.values()) {
                if (!state.hasCompletedHandshake()) continue;

                var player = state.getPlayer();
                if (failedPlayers != null && failedPlayers.contains(player.getUUID())) continue;
                if (!state.getLastDimension().equals(level.dimension())) continue;
                if (player.isRemoved()) continue;
                int playerCx = player.getBlockX() >> 4;
                int playerCz = player.getBlockZ() >> 4;
                int lodDist = config.lodDistanceChunks;

                int count = 0;
                for (long packed : dirty) {
                    if (!PositionUtil.isOutOfRange(packed, playerCx, playerCz, lodDist)) {
                        this.positionFilterBuffer[count++] = packed;
                        if (count >= DirtyColumnsS2CPayload.MAX_POSITIONS) break;
                    }
                }

                if (count > 0) {
                    long[] result = new long[count];
                    System.arraycopy(this.positionFilterBuffer, 0, result, 0, count);
                    state.clearDiskReadDoneForPositions(result);
                    try {
                        ServerPlayNetworking.send(player, new DirtyColumnsS2CPayload(result));
                    } catch (Exception e) {
                        LSSLogger.error("Failed to send dirty columns to " + player.getName().getString(), e);
                        if (failedPlayers == null) failedPlayers = new HashSet<>();
                        failedPlayers.add(player.getUUID());
                    }
                }
            }
        }
    }
}
