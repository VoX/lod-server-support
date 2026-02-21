package dev.vox.lss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record RequestCompleteS2CPayload(
        int batchId,
        int status
) implements CustomPacketPayload {
    public static final int STATUS_DONE = 0;
    public static final int STATUS_CANCELLED = 1;
    public static final int STATUS_REJECTED = 2;

    public static final CustomPacketPayload.Type<RequestCompleteS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse("lss:request_complete"));

    public static final StreamCodec<FriendlyByteBuf, RequestCompleteS2CPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.batchId);
                        buf.writeVarInt(payload.status);
                    },
                    buf -> new RequestCompleteS2CPayload(
                            buf.readVarInt(),
                            buf.readVarInt()
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
