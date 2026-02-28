package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record DirtyColumnsS2CPayload(
        long[] dirtyPositions
) {
    public static final int MAX_POSITIONS = LSSConstants.MAX_DIRTY_COLUMN_POSITIONS;

    public static final ResourceLocation ID = new ResourceLocation("lss", "dirty_columns");

    public static DirtyColumnsS2CPayload read(FriendlyByteBuf buf) {
        int rawLen = Math.max(buf.readVarInt(), 0);
        int len = Math.min(rawLen, MAX_POSITIONS);
        long[] positions = new long[len];
        for (int i = 0; i < rawLen; i++) {
            long pos = buf.readLong();
            if (i < len) positions[i] = pos;
        }
        return new DirtyColumnsS2CPayload(positions);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(this.dirtyPositions.length);
        for (long pos : this.dirtyPositions) {
            buf.writeLong(pos);
        }
    }
}