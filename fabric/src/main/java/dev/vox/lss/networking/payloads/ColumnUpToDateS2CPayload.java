package dev.vox.lss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ColumnUpToDateS2CPayload(
        int chunkX,
        int chunkZ
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ColumnUpToDateS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse("lss:column_up_to_date"));

    public static final StreamCodec<FriendlyByteBuf, ColumnUpToDateS2CPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeInt(payload.chunkX);
                        buf.writeInt(payload.chunkZ);
                    },
                    buf -> new ColumnUpToDateS2CPayload(buf.readInt(), buf.readInt())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
