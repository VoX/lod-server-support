package dev.vox.lss.networking.payloads;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record ChunkSectionS2CPayload(
        int chunkX,
        int sectionY,
        int chunkZ,
        ResourceKey<Level> dimension,
        byte[] sectionData,
        byte lightFlags,
        byte[] blockLight,
        byte[] skyLight,
        byte uniformBlockLight,
        byte uniformSkyLight,
        long columnTimestamp
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ChunkSectionS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse("lss:chunk_section"));

    public static final StreamCodec<FriendlyByteBuf, ChunkSectionS2CPayload> CODEC =
            StreamCodec.of(
                    ChunkSectionS2CPayload::write,
                    ChunkSectionS2CPayload::read
            );

    private static void write(FriendlyByteBuf buf, ChunkSectionS2CPayload payload) {
        buf.writeInt(payload.chunkX);
        buf.writeInt(payload.sectionY);
        buf.writeInt(payload.chunkZ);
        buf.writeUtf(payload.dimension.identifier().toString());
        buf.writeByteArray(payload.sectionData);
        buf.writeByte(payload.lightFlags);
        if ((payload.lightFlags & 0x01) != 0 && payload.blockLight != null) {
            buf.writeBytes(payload.blockLight);
        }
        if ((payload.lightFlags & 0x04) != 0) {
            buf.writeByte(payload.uniformBlockLight);
        }
        if ((payload.lightFlags & 0x02) != 0 && payload.skyLight != null) {
            buf.writeBytes(payload.skyLight);
        }
        if ((payload.lightFlags & 0x08) != 0) {
            buf.writeByte(payload.uniformSkyLight);
        }
        buf.writeLong(payload.columnTimestamp);
    }

    private static ChunkSectionS2CPayload read(FriendlyByteBuf buf) {
        int cx = buf.readInt();
        int sy = buf.readInt();
        int cz = buf.readInt();
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(buf.readUtf(256)));
        byte[] sectionData = buf.readByteArray(1_048_576);
        byte flags = buf.readByte();
        byte[] bl = null;
        byte[] sl = null;
        byte uniformBl = 0;
        byte uniformSl = 0;
        if ((flags & 0x01) != 0) {
            bl = new byte[2048];
            buf.readBytes(bl);
        }
        if ((flags & 0x04) != 0) {
            uniformBl = buf.readByte();
        }
        if ((flags & 0x02) != 0) {
            sl = new byte[2048];
            buf.readBytes(sl);
        }
        if ((flags & 0x08) != 0) {
            uniformSl = buf.readByte();
        }
        long columnTimestamp = buf.isReadable() ? buf.readLong() : 0L;
        return new ChunkSectionS2CPayload(cx, sy, cz, dim, sectionData, flags, bl, sl, uniformBl, uniformSl, columnTimestamp);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
