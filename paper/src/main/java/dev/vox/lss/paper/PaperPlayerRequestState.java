package dev.vox.lss.paper;

import dev.vox.lss.common.processing.AbstractPlayerRequestState;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Per-player state tracking for the Paper plugin. Adapted from Fabric's PlayerRequestState
 * with QueuedPayload holding encoded byte[] + channel name instead of CustomPacketPayload.
 */
public class PaperPlayerRequestState extends AbstractPlayerRequestState<byte[]> {
    private volatile ServerPlayer player;
    private ResourceKey<Level> lastDimension;

    public PaperPlayerRequestState(ServerPlayer player, int syncConcurrency, int genConcurrency) {
        super(player.getUUID(), syncConcurrency, genConcurrency);
        this.player = player;
        this.lastDimension = player.level().dimension();
    }

    public void updatePlayer(ServerPlayer newPlayer) {
        this.player = newPlayer;
    }

    public ServerPlayer getPlayer() { return this.player; }

    @Override
    public String getPlayerName() { return this.player.getName().getString(); }
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
