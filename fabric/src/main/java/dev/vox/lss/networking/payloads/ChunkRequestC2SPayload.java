package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record ChunkRequestC2SPayload(
        int batchId,
        long[] positions,
        long[] timestamps
) {
    public static final int MAX_POSITIONS = LSSConstants.MAX_CHUNK_REQUEST_POSITIONS;

    // Using ResourceLocation as the channel identifier in 1.20.1
    public static final ResourceLocation ID = new ResourceLocation("lss", "chunk_request");

    // Deserialization method (read from buffer on the server side)
    public static ChunkRequestC2SPayload read(FriendlyByteBuf buf) {
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

    // Serialization method (write to buffer on the client side)
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(this.batchId);
        buf.writeVarInt(this.positions.length);
        for (long pos : this.positions) {
            buf.writeLong(pos);
        }
        for (long ts : this.timestamps) {
            buf.writeLong(ts);
        }
    }
}