package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record DirtyColumnsS2CPayload(
        long[] dirtyPositions
) implements CustomPacketPayload {
    public static final int MAX_POSITIONS = LSSConstants.MAX_DIRTY_COLUMN_POSITIONS;

    public static final CustomPacketPayload.Type<DirtyColumnsS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.parse(LSSConstants.CHANNEL_DIRTY_COLUMNS));

    public static final StreamCodec<FriendlyByteBuf, DirtyColumnsS2CPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        // Clamp on encode to match the decoder cap (and the Paper encoder),
                        // so an over-long array can never produce a frame the peer rejects.
                        int len = Math.min(payload.dirtyPositions.length, MAX_POSITIONS);
                        buf.writeVarInt(len);
                        for (int i = 0; i < len; i++) {
                            buf.writeLong(payload.dirtyPositions[i]);
                        }
                    },
                    buf -> {
                        int rawLen = Math.max(buf.readVarInt(), 0);
                        int len = Math.min(rawLen, MAX_POSITIONS);
                        long[] positions = new long[len];
                        for (int i = 0; i < len; i++) {
                            positions[i] = buf.readLong();
                        }
                        // Skip excess entries efficiently (O(1) vs per-long reads)
                        int excess = rawLen - len;
                        if (excess > 0) {
                            int toSkip = (int) Math.min((long) excess * Long.BYTES, buf.readableBytes());
                            buf.skipBytes(toSkip);
                        }
                        return new DirtyColumnsS2CPayload(positions);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
