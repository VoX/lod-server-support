package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ChunkRequestC2SPayload(
        int batchId,
        long[] positions,
        long[] timestamps
) implements CustomPacketPayload {
    public static final int MAX_POSITIONS = LSSConstants.MAX_CHUNK_REQUEST_POSITIONS;

    public static final CustomPacketPayload.Type<ChunkRequestC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse("lss:chunk_request"));

    public static final StreamCodec<FriendlyByteBuf, ChunkRequestC2SPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.batchId);
                        buf.writeVarInt(payload.positions.length);
                        for (long pos : payload.positions) {
                            buf.writeLong(pos);
                        }
                        for (long ts : payload.timestamps) {
                            buf.writeLong(ts);
                        }
                    },
                    buf -> {
                        int batchId = buf.readVarInt();
                        int rawLen = Math.max(buf.readVarInt(), 0);
                        int len = Math.min(rawLen, MAX_POSITIONS);
                        long[] positions = new long[len];
                        for (int i = 0; i < rawLen; i++) {
                            long pos = buf.readLong();
                            if (i < len) positions[i] = pos;
                        }
                        long[] timestamps = new long[len];
                        if (buf.isReadable()) {
                            for (int i = 0; i < rawLen; i++) {
                                long ts = buf.readLong();
                                if (i < len) timestamps[i] = ts;
                            }
                        }
                        return new ChunkRequestC2SPayload(batchId, positions, timestamps);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
