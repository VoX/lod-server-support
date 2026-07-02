package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record BatchResponseS2CPayload(
        byte[] responseTypes,
        long[] packedPositions,
        int count
) implements FabricPacket {

    public static final PacketType<BatchResponseS2CPayload> TYPE =
            PacketType.create(new ResourceLocation(LSSConstants.CHANNEL_BATCH_RESPONSE), BatchResponseS2CPayload::read);

    public static BatchResponseS2CPayload read(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > LSSConstants.MAX_BATCH_RESPONSES) {
            throw new IllegalArgumentException("Batch response count out of range: " + count);
        }
        byte[] responseTypes = new byte[count];
        long[] packedPositions = new long[count];
        for (int i = 0; i < count; i++) {
            responseTypes[i] = buf.readByte();
            packedPositions[i] = buf.readLong();
        }
        return new BatchResponseS2CPayload(responseTypes, packedPositions, count);
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(this.count);
        for (int i = 0; i < this.count; i++) {
            buf.writeByte(this.responseTypes[i]);
            buf.writeLong(this.packedPositions[i]);
        }
    }

    @Override
    public PacketType<?> getType() {
        return TYPE;
    }
}
