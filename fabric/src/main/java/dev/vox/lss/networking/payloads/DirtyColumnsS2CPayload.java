package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record DirtyColumnsS2CPayload(
        long[] dirtyPositions
) implements FabricPacket {
    public static final int MAX_POSITIONS = LSSConstants.MAX_DIRTY_COLUMN_POSITIONS;

    public static final PacketType<DirtyColumnsS2CPayload> TYPE =
            PacketType.create(new ResourceLocation(LSSConstants.CHANNEL_DIRTY_COLUMNS), DirtyColumnsS2CPayload::read);

    public static DirtyColumnsS2CPayload read(FriendlyByteBuf buf) {
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

    @Override
    public void write(FriendlyByteBuf buf) {
        // Clamp on encode to match the decoder cap (and the Paper encoder),
        // so an over-long array can never produce a frame the peer rejects.
        int len = Math.min(this.dirtyPositions.length, MAX_POSITIONS);
        buf.writeVarInt(len);
        for (int i = 0; i < len; i++) {
            buf.writeLong(this.dirtyPositions[i]);
        }
    }

    @Override
    public PacketType<?> getType() {
        return TYPE;
    }
}
