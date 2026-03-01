package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
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
) {
    public static final ResourceLocation ID = new ResourceLocation("lss", "chunk_section");

    public static ChunkSectionS2CPayload read(FriendlyByteBuf buf) {
        int cx = buf.readInt();
        int sy = buf.readInt();
        int cz = buf.readInt();
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(buf.readUtf(256)));
        byte[] sectionData = buf.readByteArray(LSSConstants.MAX_SECTION_DATA_SIZE);
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
        long columnTimestamp = buf.readLong();
        return new ChunkSectionS2CPayload(cx, sy, cz, dim, sectionData, flags, bl, sl, uniformBl, uniformSl, columnTimestamp);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeInt(this.chunkX);
        buf.writeInt(this.sectionY);
        buf.writeInt(this.chunkZ);
        buf.writeUtf(this.dimension.location().toString()); 
        buf.writeByteArray(this.sectionData);
        buf.writeByte(this.lightFlags);
        if ((this.lightFlags & 0x01) != 0) { 
            buf.writeBytes(this.blockLight); 
        }
        if ((this.lightFlags & 0x04) != 0) {
            buf.writeByte(this.uniformBlockLight);
        }
        if ((this.lightFlags & 0x02) != 0) { 
            buf.writeBytes(this.skyLight); 
        }
        if ((this.lightFlags & 0x08) != 0) {
            buf.writeByte(this.uniformSkyLight);
        }
        buf.writeLong(this.columnTimestamp);
    }
}