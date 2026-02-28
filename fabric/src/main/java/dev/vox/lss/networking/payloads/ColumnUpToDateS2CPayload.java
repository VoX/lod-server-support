package dev.vox.lss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record ColumnUpToDateS2CPayload(
        int chunkX,
        int chunkZ
) {
    public static final ResourceLocation ID = new ResourceLocation("lss", "column_up_to_date");

    public static ColumnUpToDateS2CPayload read(FriendlyByteBuf buf) {
        return new ColumnUpToDateS2CPayload(buf.readInt(), buf.readInt());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeInt(this.chunkX);
        buf.writeInt(this.chunkZ);
    }
}