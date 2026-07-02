package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.payloads.DirtyColumnsS2CPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Periodically drains dirty chunk positions from {@link DirtyColumnTracker}
 * and broadcasts {@link DirtyColumnsS2CPayload} to nearby players.
 */
class DirtyColumnBroadcaster {

    /**
     * Test seam: the broadcaster's only reads of the live ServerPlayer (chunk position,
     * removal) and the network send, injectable so unit tests can drive
     * {@link DirtyColumnBroadcaster#tick} without a running server. Production uses
     * {@link DirtyColumnBroadcaster#LIVE_PLAYER_VIEW}.
     */
    interface PlayerView {
        boolean isRemoved(PlayerRequestState state);
        int chunkX(PlayerRequestState state);
        int chunkZ(PlayerRequestState state);
        void send(PlayerRequestState state, DirtyColumnsS2CPayload payload) throws Exception;
    }

    private static final PlayerView LIVE_PLAYER_VIEW = new PlayerView() {
        @Override public boolean isRemoved(PlayerRequestState state) { return state.getPlayer().isRemoved(); }
        @Override public int chunkX(PlayerRequestState state) { return state.getPlayer().getBlockX() >> 4; }
        @Override public int chunkZ(PlayerRequestState state) { return state.getPlayer().getBlockZ() >> 4; }
        @Override public void send(PlayerRequestState state, DirtyColumnsS2CPayload payload) {
            ServerPlayNetworking.send(state.getPlayer(), payload);
        }
    };

    private final Map<UUID, PlayerRequestState> players;
    private final FabricOffThreadProcessor offThreadProcessor;
    private final DirtyColumnTracker dirtyTracker;
    private final Supplier<Iterable<ResourceKey<Level>>> dimensions;
    private final PlayerView playerView;

    private int counter = 0;
    private long[] positionFilterBuffer = null;

    DirtyColumnBroadcaster(MinecraftServer server, Map<UUID, PlayerRequestState> players,
                           FabricOffThreadProcessor offThreadProcessor, DirtyColumnTracker dirtyTracker) {
        this(players, offThreadProcessor, dirtyTracker, () -> serverDimensions(server), LIVE_PLAYER_VIEW);
    }

    /** Test seam: injectable dimension source and {@link PlayerView}; the production
     *  constructor above wires live defaults with identical behavior. */
    DirtyColumnBroadcaster(Map<UUID, PlayerRequestState> players,
                           FabricOffThreadProcessor offThreadProcessor, DirtyColumnTracker dirtyTracker,
                           Supplier<Iterable<ResourceKey<Level>>> dimensions, PlayerView playerView) {
        this.players = players;
        this.offThreadProcessor = offThreadProcessor;
        this.dirtyTracker = dirtyTracker;
        this.dimensions = dimensions;
        this.playerView = playerView;
    }

    private static List<ResourceKey<Level>> serverDimensions(MinecraftServer server) {
        var keys = new ArrayList<ResourceKey<Level>>(3);
        for (var level : server.getAllLevels()) keys.add(level.dimension());
        return keys;
    }

    void tick(LSSServerConfig config) {
        int intervalTicks = config.dirtyBroadcastIntervalSeconds * LSSConstants.TICKS_PER_SECOND;
        if (++this.counter < intervalTicks) return;
        this.counter = 0;

        Set<UUID> failedPlayers = null;

        for (var dimension : this.dimensions.get()) {
            String dimensionStr = dimension.location().toString();
            long[] dirty = this.dirtyTracker.drainDirty(dimensionStr);
            if (dirty == null || dirty.length == 0) continue;

            this.offThreadProcessor.invalidateTimestamps(dimensionStr, dirty);

            int bufLen = Math.min(dirty.length, DirtyColumnsS2CPayload.MAX_POSITIONS);
            if (this.positionFilterBuffer == null || this.positionFilterBuffer.length < bufLen) {
                this.positionFilterBuffer = new long[bufLen];
            }

            for (var state : this.players.values()) {
                if (!state.hasCompletedHandshake()) continue;

                UUID uuid = state.getPlayerUUID();
                if (failedPlayers != null && failedPlayers.contains(uuid)) continue;
                if (!state.getLastDimension().equals(dimension)) continue;
                if (this.playerView.isRemoved(state)) continue;
                int playerCx = this.playerView.chunkX(state);
                int playerCz = this.playerView.chunkZ(state);
                // Raw lodDistanceChunks, no LOD_DISTANCE_BUFFER: columns held via the request
                // gate's +32 buffer never receive dirty pushes.
                int lodDist = config.lodDistanceChunks;

                // Paginate: a single DirtyColumns payload caps at MAX_POSITIONS, so when a player
                // has more in-range dirty positions than the cap, send multiple payloads rather
                // than dropping the overflow (which was already drained+invalidated and would
                // otherwise stay stale on the client until rejoin). The global drain/mark
                // accounting is untouched — only the number of packets changes.
                int idx = 0;
                while (idx < dirty.length) {
                    int count = 0;
                    while (idx < dirty.length && count < DirtyColumnsS2CPayload.MAX_POSITIONS) {
                        long packed = dirty[idx++];
                        if (!PositionUtil.isOutOfRange(packed, playerCx, playerCz, lodDist)) {
                            this.positionFilterBuffer[count++] = packed;
                        }
                    }
                    if (count == 0) continue;

                    long[] result = new long[count];
                    System.arraycopy(this.positionFilterBuffer, 0, result, 0, count);
                    // clearDiskReadDone only ENQUEUES a mailbox event; correctness comes from
                    // applyEvents (which applies it) running before routeIncomingRequests in
                    // every processing cycle, so a re-request routed after this notification
                    // (network + client-scan latency later) sees the cleared done-bit and
                    // re-resolves — never a stale up_to_date.
                    this.offThreadProcessor.clearDiskReadDone(uuid, result);
                    try {
                        this.playerView.send(state, new DirtyColumnsS2CPayload(result));
                    } catch (Exception e) {
                        LSSLogger.error("Failed to send dirty columns to " + state.getPlayerName(), e);
                        if (failedPlayers == null) failedPlayers = new HashSet<>();
                        failedPlayers.add(uuid);
                        break; // stop paginating to this player for this broadcast
                    }
                }
            }
        }
    }
}
