package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record BatchResponseS2CPayload(
        byte[] responseTypes,
        int[] requestIds,
        int count
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BatchResponseS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse(LSSConstants.CHANNEL_BATCH_RESPONSE));

    public static final StreamCodec<FriendlyByteBuf, BatchResponseS2CPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.count);
                        for (int i = 0; i < payload.count; i++) {
                            buf.writeByte(payload.responseTypes[i]);
                            buf.writeVarInt(payload.requestIds[i]);
                        }
                    },
                    buf -> {
                        int count = buf.readVarInt();
                        if (count < 0 || count > LSSConstants.MAX_BATCH_RESPONSES) {
                            throw new IllegalArgumentException("Batch response count out of range: " + count);
                        }
                        byte[] responseTypes = new byte[count];
                        int[] requestIds = new int[count];
                        for (int i = 0; i < count; i++) {
                            responseTypes[i] = buf.readByte();
                            requestIds[i] = buf.readVarInt();
                        }
                        return new BatchResponseS2CPayload(responseTypes, requestIds, count);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
