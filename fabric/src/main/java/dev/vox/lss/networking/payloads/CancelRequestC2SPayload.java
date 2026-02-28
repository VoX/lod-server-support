package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record CancelRequestC2SPayload(int[] batchIds) {
    public static final int MAX_BATCH_IDS = LSSConstants.MAX_CANCEL_BATCH_IDS;

    public static final ResourceLocation ID = new ResourceLocation("lss", "cancel_request");

    public static CancelRequestC2SPayload read(FriendlyByteBuf buf) {
        int rawLen = Math.max(buf.readVarInt(), 0);
        int len = Math.min(rawLen, MAX_BATCH_IDS);
        int[] batchIds = new int[len];
        for (int i = 0; i < rawLen; i++) {
            int id = buf.readVarInt();
            if (i < len) batchIds[i] = id;
        }
        return new CancelRequestC2SPayload(batchIds);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(this.batchIds.length);
        for (int id : this.batchIds) {
            buf.writeVarInt(id);
        }
    }
}