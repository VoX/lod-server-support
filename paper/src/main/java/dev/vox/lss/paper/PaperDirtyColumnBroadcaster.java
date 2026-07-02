package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Periodically drains dirty column positions from {@link DirtyColumnTracker}
 * and broadcasts dirty column notifications to nearby players via Plugin Messaging.
 */
class PaperDirtyColumnBroadcaster {
    private static final int MAX_POSITIONS = LSSConstants.MAX_DIRTY_COLUMN_POSITIONS;

    private final MinecraftServer server;
    private final Map<UUID, PaperPlayerRequestState> players;
    private final DirtyColumnTracker dirtyTracker;
    private final PaperOffThreadProcessor offThreadProcessor;

    private int counter = 0;
    private long[] positionFilterBuffer = null;

    /** Test seam: sends one dirty-columns notification frame to a player. Production
     *  default is the raw plugin-message send. */
    @FunctionalInterface
    interface DirtyColumnsSender {
        void send(ServerPlayer player, long[] positions) throws Exception;
    }

    private DirtyColumnsSender dirtySender = (player, positions) ->
            PaperPayloadHandler.sendDirtyColumns(player.getBukkitEntity(), positions);

    void setDirtySender(DirtyColumnsSender sender) {
        this.dirtySender = sender;
    }

    PaperDirtyColumnBroadcaster(MinecraftServer server, Map<UUID, PaperPlayerRequestState> players,
                                DirtyColumnTracker dirtyTracker, PaperOffThreadProcessor offThreadProcessor) {
        this.server = server;
        this.players = players;
        this.dirtyTracker = dirtyTracker;
        this.offThreadProcessor = offThreadProcessor;
    }

    void tick(PaperConfig config) {
        int intervalTicks = config.dirtyBroadcastIntervalSeconds * LSSConstants.TICKS_PER_SECOND;
        if (++this.counter < intervalTicks) return;
        this.counter = 0;

        Set<UUID> failedPlayers = null;

        for (ServerLevel level : this.server.getAllLevels()) {
            String dimensionStr = level.dimension().location().toString();
            long[] dirty = this.dirtyTracker.drainDirty(dimensionStr);
            if (dirty == null || dirty.length == 0) continue;

            this.offThreadProcessor.invalidateTimestamps(dimensionStr, dirty);

            int bufLen = Math.min(dirty.length, MAX_POSITIONS);
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

                // Paginate (twin of the Fabric broadcaster): a single dirty-columns frame caps
                // at MAX_POSITIONS, so when a player has more in-range dirty positions than the
                // cap, send multiple frames rather than dropping the overflow (already drained +
                // invalidated, otherwise stale on the client until rejoin). Drain/mark accounting
                // is untouched — only the packet count changes.
                int idx = 0;
                while (idx < dirty.length) {
                    int count = 0;
                    while (idx < dirty.length && count < MAX_POSITIONS) {
                        long packed = dirty[idx++];
                        if (!PositionUtil.isOutOfRange(packed, playerCx, playerCz, lodDist)) {
                            this.positionFilterBuffer[count++] = packed;
                        }
                    }
                    if (count == 0) continue;

                    long[] result = new long[count];
                    System.arraycopy(this.positionFilterBuffer, 0, result, 0, count);
                    // clearDiskReadDone only ENQUEUES a mailbox event; correctness comes from
                    // applyEvents running before routeIncomingRequests in every processing cycle,
                    // so a re-request routed after this notification sees the cleared done-bit and
                    // re-resolves — never a stale up_to_date.
                    this.offThreadProcessor.clearDiskReadDone(player.getUUID(), result);
                    try {
                        this.dirtySender.send(player, result);
                    } catch (Exception e) {
                        LSSLogger.error("Failed to send dirty columns to " + player.getName().getString(), e);
                        if (failedPlayers == null) failedPlayers = new HashSet<>();
                        failedPlayers.add(player.getUUID());
                        break; // stop paginating to this player for this broadcast
                    }
                }
            }
        }
    }
}
