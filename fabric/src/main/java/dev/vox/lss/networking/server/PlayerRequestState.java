package dev.vox.lss.networking.server;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.AbstractPlayerRequestState;
import dev.vox.lss.common.processing.IncomingRequest;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

public class PlayerRequestState extends AbstractPlayerRequestState<PlayerRequestState.QueuedPayload> {
    private volatile ServerPlayer player;
    private ResourceKey<Level> lastDimension;

    public record QueuedPayload(CustomPacketPayload payload, int requestId,
                                int estimatedBytes, long submissionOrder) implements Comparable<QueuedPayload> {
        @Override
        public int compareTo(QueuedPayload other) {
            return Long.compare(this.submissionOrder, other.submissionOrder);
        }
    }

    public PlayerRequestState(ServerPlayer player, int syncRate, int syncConcurrency,
                              int genRate, int genConcurrency) {
        super(player.getUUID(), syncRate, syncConcurrency, genRate, genConcurrency);
        this.player = player;
        this.lastDimension = player.level().dimension();
    }

    public void addRequest(int requestId, long packedPosition, long clientTimestamp) {
        int cx = PositionUtil.unpackX(packedPosition);
        int cz = PositionUtil.unpackZ(packedPosition);
        enqueueIncomingRequest(new IncomingRequest(requestId, cx, cz, clientTimestamp));
    }

    /**
     * Clear concurrent queues on dimension change (called from main thread).
     */
    public void onDimensionChange() {
        onDimensionChangeBase();
    }

    public void updatePlayer(ServerPlayer newPlayer) {
        this.player = newPlayer;
    }

    public ServerPlayer getPlayer() { return this.player; }
    public ResourceKey<Level> getLastDimension() { return this.lastDimension; }

    public boolean checkDimensionChange() {
        var currentDim = this.player.level().dimension();
        if (!currentDim.equals(this.lastDimension)) {
            this.lastDimension = currentDim;
            return true;
        }
        return false;
    }
}
