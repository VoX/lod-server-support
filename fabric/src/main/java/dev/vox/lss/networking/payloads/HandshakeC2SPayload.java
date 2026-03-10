package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record HandshakeC2SPayload(int protocolVersion, int capabilities) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<HandshakeC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse(LSSConstants.CHANNEL_HANDSHAKE));

    public static final StreamCodec<FriendlyByteBuf, HandshakeC2SPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.protocolVersion);
                        buf.writeVarInt(payload.capabilities);
                    },
                    buf -> {
                        int version = buf.readVarInt();
                        int caps = buf.readVarInt();
                        return new HandshakeC2SPayload(version, caps);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
