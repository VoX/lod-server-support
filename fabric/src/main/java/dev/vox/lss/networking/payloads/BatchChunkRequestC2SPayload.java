package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record BatchChunkRequestC2SPayload(
        long[] packedPositions,
        long[] clientTimestamps,
        int count
) implements FabricPacket {

    public static final PacketType<BatchChunkRequestC2SPayload> TYPE =
            PacketType.create(new ResourceLocation(LSSConstants.CHANNEL_CHUNK_REQUEST), BatchChunkRequestC2SPayload::read);

    public static BatchChunkRequestC2SPayload read(FriendlyByteBuf buf) {
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

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(this.count);
        for (int i = 0; i < this.count; i++) {
            buf.writeLong(this.packedPositions[i]);
            buf.writeLong(this.clientTimestamps[i]);
        }
    }

    @Override
    public PacketType<?> getType() {
        return TYPE;
    }
}
