package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record SessionConfigS2CPayload(
        int protocolVersion,
        boolean enabled,
        int lodDistanceChunks,
        int syncOnLoadConcurrencyLimitPerPlayer,
        int generationConcurrencyLimitPerPlayer,
        boolean generationEnabled
) implements FabricPacket {
    public static final PacketType<SessionConfigS2CPayload> TYPE =
            PacketType.create(new ResourceLocation(LSSConstants.CHANNEL_SESSION_CONFIG), SessionConfigS2CPayload::read);

    public static SessionConfigS2CPayload read(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        if (version != LSSConstants.PROTOCOL_VERSION) {
            // Foreign layout (e.g. a v15 server's 10-field config). The version
            // VarInt is the first field in every layout, so drain the rest to
            // avoid a trailing-bytes decoder kick and let the handler's version
            // gate log the mismatch and disable LSS.
            buf.skipBytes(buf.readableBytes());
            return new SessionConfigS2CPayload(version, false, 0, 0, 0, false);
        }
        boolean enabled = buf.readBoolean();
        int lodDist = buf.readVarInt();
        int syncConc = buf.readVarInt();
        int genConc = buf.readVarInt();
        boolean genEnabled = buf.readBoolean();
        return new SessionConfigS2CPayload(version, enabled, lodDist,
                syncConc, genConc, genEnabled);
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(this.protocolVersion);
        buf.writeBoolean(this.enabled);
        buf.writeVarInt(this.lodDistanceChunks);
        buf.writeVarInt(this.syncOnLoadConcurrencyLimitPerPlayer);
        buf.writeVarInt(this.generationConcurrencyLimitPerPlayer);
        buf.writeBoolean(this.generationEnabled);
    }

    @Override
    public PacketType<?> getType() {
        return TYPE;
    }
}
