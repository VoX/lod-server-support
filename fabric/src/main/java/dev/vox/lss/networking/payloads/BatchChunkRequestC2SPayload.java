package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record BatchChunkRequestC2SPayload(
        long[] packedPositions,
        long[] clientTimestamps,
        int count
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BatchChunkRequestC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.parse(LSSConstants.CHANNEL_CHUNK_REQUEST));

    public static final StreamCodec<FriendlyByteBuf, BatchChunkRequestC2SPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.count);
                        for (int i = 0; i < payload.count; i++) {
                            buf.writeLong(payload.packedPositions[i]);
                            buf.writeLong(payload.clientTimestamps[i]);
                        }
                    },
                    buf -> {
                        int count = buf.readVarInt();
                        if (count < 0 || count > LSSConstants.MAX_BATCH_CHUNK_REQUESTS) {
                            throw new IllegalArgumentException("Batch chunk request count out of range: " + count);
                        }
                        long[] packedPositions = new long[count];
                        long[] clientTimestamps = new long[count];
                        for (int i = 0; i < count; i++) {
                            packedPositions[i] = buf.readLong();
                            clientTimestamps[i] = buf.readLong();
                        }
                        return new BatchChunkRequestC2SPayload(packedPositions, clientTimestamps, count);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
