package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CancelRequestC2SPayload(int requestId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CancelRequestC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse(LSSConstants.CHANNEL_CANCEL_REQUEST));

    public static final StreamCodec<FriendlyByteBuf, CancelRequestC2SPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeVarInt(payload.requestId),
                    buf -> new CancelRequestC2SPayload(buf.readVarInt())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
