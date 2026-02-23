package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CancelRequestC2SPayload(int[] batchIds) implements CustomPacketPayload {
    public static final int MAX_BATCH_IDS = LSSConstants.MAX_CANCEL_BATCH_IDS;

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
                        int rawLen = Math.max(buf.readVarInt(), 0);
                        int len = Math.min(rawLen, MAX_BATCH_IDS);
                        int[] batchIds = new int[len];
                        for (int i = 0; i < rawLen; i++) {
                            int id = buf.readVarInt();
                            if (i < len) batchIds[i] = id;
                        }
                        return new CancelRequestC2SPayload(batchIds);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
