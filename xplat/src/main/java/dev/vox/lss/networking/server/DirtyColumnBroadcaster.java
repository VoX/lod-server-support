package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.payloads.DirtyColumnsS2CPayload;
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
            dev.vox.lss.platform.LoaderServices.get().sendToPlayer(state.getPlayer(), payload);
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
        // Read the config field ONCE per tick: cadence and send-gate must agree (a torn
        // read across a mid-tick config flip could drain on the off cadence yet send).
        // The live-read-per-tick contract is pinned by the mid-run flip tests.
        int intervalSeconds = config.dirtyBroadcastIntervalSeconds;
        // 0 = sends disabled, but the drain must still run: it carries the invalidation
        // fan-out (store rows, tscache, in-flight taints) and the per-player clears. A raw
        // 0 would compute intervalTicks = 0 and drain EVERY tick, so the fallback cadence
        // is required, not cosmetic.
        boolean sendsEnabled = intervalSeconds > 0;
        int cadenceSeconds = sendsEnabled ? intervalSeconds
                : LSSConstants.DIRTY_DRAIN_ONLY_INTERVAL_SECONDS;
        int intervalTicks = cadenceSeconds * LSSConstants.TICKS_PER_SECOND;
        if (++this.counter < intervalTicks) return;
        this.counter = 0;

        Set<UUID> failedPlayers = null;

        for (var dimension : this.dimensions.get()) {
            String dimensionStr = dimension.identifier().toString();
            long[] dirty = this.dirtyTracker.drainDirty(dimensionStr);
            if (dirty == null || dirty.length == 0) continue;

            // The callback releases the stamping guard's second phase exactly when
            // the tscache/store invalidation has APPLIED on the processing thread
            // (stamped-up-to-date-plan.md §9.2 — the drain-to-apply window).
            this.offThreadProcessor.invalidateTimestamps(dimensionStr, dirty,
                    () -> this.dirtyTracker.confirmInvalidated(dimensionStr, dirty));

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
                // gate's +32 buffer never receive dirty pushes. dimensionStr IS this player's
                // dimension (the getLastDimension filter above already matched it), so reuse
                // the loop key — no per-player re-extraction of the same string.
                int lodDist = config.lodDistanceForWorld(dimensionStr);

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
                    // clearDiskReadDone only ENQUEUES a mailbox event. The clear is enqueued
                    // BEFORE the notice is sent, and the processor late-drains dirty-clears
                    // right before routing (applyLateDirtyEvents), so a re-request CAUSED by
                    // this notification can never route ahead of its clear — the residual
                    // race is only an event landing inside the phase-4 window itself
                    // (milliseconds), not the old full-cycle skew.
                    this.offThreadProcessor.clearDiskReadDone(uuid, result);
                    // Direct, non-mailboxed: a suppress stamp written by THIS tick's flush
                    // (before this broadcast ran) must not outlive the edit — see
                    // clearProbeSuppress (three-lens review, concurrency MINOR).
                    state.clearProbeSuppress(result);
                    if (!sendsEnabled) continue; // interval 0: drain + clears only, no wire
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
