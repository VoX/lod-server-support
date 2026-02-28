package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record RequestCompleteS2CPayload(
        int batchId,
        int status
) {
    public static final int STATUS_DONE = LSSConstants.STATUS_DONE;
    public static final int STATUS_CANCELLED = LSSConstants.STATUS_CANCELLED;
    public static final int STATUS_REJECTED = LSSConstants.STATUS_REJECTED;

    public static final ResourceLocation ID = new ResourceLocation("lss", "request_complete");

    public static RequestCompleteS2CPayload read(FriendlyByteBuf buf) {
        return new RequestCompleteS2CPayload(
                buf.readVarInt(),
                buf.readVarInt()
        );
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(this.batchId);
        buf.writeVarInt(this.status);
    }
}