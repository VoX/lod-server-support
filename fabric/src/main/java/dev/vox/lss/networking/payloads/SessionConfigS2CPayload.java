package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SessionConfigS2CPayload(
        int protocolVersion,
        boolean enabled,
        int lodDistanceChunks,
        int syncOnLoadConcurrencyLimitPerPlayer,
        int generationConcurrencyLimitPerPlayer,
        boolean generationEnabled
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SessionConfigS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.parse(LSSConstants.CHANNEL_SESSION_CONFIG));

    public static final StreamCodec<FriendlyByteBuf, SessionConfigS2CPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.protocolVersion);
                        buf.writeBoolean(payload.enabled);
                        buf.writeVarInt(payload.lodDistanceChunks);
                        buf.writeVarInt(payload.syncOnLoadConcurrencyLimitPerPlayer);
                        buf.writeVarInt(payload.generationConcurrencyLimitPerPlayer);
                        buf.writeBoolean(payload.generationEnabled);
                    },
                    buf -> {
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
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
