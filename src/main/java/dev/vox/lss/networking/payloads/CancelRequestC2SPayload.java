package dev.vox.lss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CancelRequestC2SPayload(int[] batchIds) implements CustomPacketPayload {
    public static final int MAX_BATCH_IDS = 256;

    public static final CustomPacketPayload.Type<CancelRequestC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse("lss:cancel_request"));

    public static final StreamCodec<FriendlyByteBuf, CancelRequestC2SPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.batchIds.length);
                        for (int id : payload.batchIds) {
                            buf.writeVarInt(id);
                        }
                    },
                    buf -> {
                        int len = Math.min(buf.readVarInt(), MAX_BATCH_IDS);
                        int[] batchIds = new int[len];
                        for (int i = 0; i < len; i++) {
                            batchIds[i] = buf.readVarInt();
                        }
                        return new CancelRequestC2SPayload(batchIds);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
