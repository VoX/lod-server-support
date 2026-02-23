package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record DirtyColumnsS2CPayload(
        long[] dirtyPositions
) implements CustomPacketPayload {
    public static final int MAX_POSITIONS = LSSConstants.MAX_DIRTY_COLUMN_POSITIONS;

    public static final CustomPacketPayload.Type<DirtyColumnsS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse("lss:dirty_columns"));

    public static final StreamCodec<FriendlyByteBuf, DirtyColumnsS2CPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.dirtyPositions.length);
                        for (long pos : payload.dirtyPositions) {
                            buf.writeLong(pos);
                        }
                    },
                    buf -> {
                        int rawLen = Math.max(buf.readVarInt(), 0);
                        int len = Math.min(rawLen, MAX_POSITIONS);
                        long[] positions = new long[len];
                        for (int i = 0; i < rawLen; i++) {
                            long pos = buf.readLong();
                            if (i < len) positions[i] = pos;
                        }
                        return new DirtyColumnsS2CPayload(positions);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
