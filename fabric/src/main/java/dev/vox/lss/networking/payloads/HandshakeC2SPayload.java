package dev.vox.lss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record HandshakeC2SPayload(int protocolVersion) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<HandshakeC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse("lss:handshake_c2s"));

    public static final StreamCodec<FriendlyByteBuf, HandshakeC2SPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeVarInt(payload.protocolVersion),
                    buf -> new HandshakeC2SPayload(buf.readVarInt())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
