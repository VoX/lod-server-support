package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SessionConfigS2CPayload(
        int protocolVersion,
        boolean enabled,
        int lodDistanceChunks,
        int serverCapabilities,
        int syncOnLoadRateLimitPerPlayer,
        int syncOnLoadConcurrencyLimitPerPlayer,
        int generationRateLimitPerPlayer,
        int generationConcurrencyLimitPerPlayer,
        boolean generationEnabled,
        long playerBandwidthLimit
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SessionConfigS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse(LSSConstants.CHANNEL_SESSION_CONFIG));

    public static final StreamCodec<FriendlyByteBuf, SessionConfigS2CPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.protocolVersion);
                        buf.writeBoolean(payload.enabled);
                        buf.writeVarInt(payload.lodDistanceChunks);
                        buf.writeVarInt(payload.serverCapabilities);
                        buf.writeVarInt(payload.syncOnLoadRateLimitPerPlayer);
                        buf.writeVarInt(payload.syncOnLoadConcurrencyLimitPerPlayer);
                        buf.writeVarInt(payload.generationRateLimitPerPlayer);
                        buf.writeVarInt(payload.generationConcurrencyLimitPerPlayer);
                        buf.writeBoolean(payload.generationEnabled);
                        buf.writeVarLong(payload.playerBandwidthLimit);
                    },
                    buf -> {
                        int version = buf.readVarInt();
                        boolean enabled = buf.readBoolean();
                        int lodDist = buf.readVarInt();
                        int serverCaps = buf.readVarInt();
                        int syncRate = buf.readVarInt();
                        int syncConc = buf.readVarInt();
                        int genRate = buf.readVarInt();
                        int genConc = buf.readVarInt();
                        boolean genEnabled = buf.readBoolean();
                        long bwLimit = buf.readVarLong();
                        return new SessionConfigS2CPayload(version, enabled, lodDist,
                                serverCaps, syncRate, syncConc, genRate, genConc,
                                genEnabled, bwLimit);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
