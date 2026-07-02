package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Server-to-client payload carrying MC-native chunk section data.
 * <p>
 * Section bytes are written as a length-prefixed byte array. Minecraft's
 * built-in network compression (zlib) handles wire-level compression
 * transparently for all packets exceeding {@code network-compression-threshold}.
 * <p>
 * Dimension is encoded as a length-capped UTF resource-location string (v16+).
 */
public final class VoxelColumnS2CPayload implements FabricPacket {

    public static final PacketType<VoxelColumnS2CPayload> TYPE =
            PacketType.create(new ResourceLocation(LSSConstants.CHANNEL_VOXEL_COLUMN), VoxelColumnS2CPayload::read);

    private final int chunkX;
    private final int chunkZ;
    private final ResourceKey<Level> dimension;
    private final long columnTimestamp;
    private final byte[] sectionBytes;

    public VoxelColumnS2CPayload(int chunkX, int chunkZ,
                                  ResourceKey<Level> dimension, long columnTimestamp,
                                  byte[] sectionBytes) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.dimension = dimension;
        this.columnTimestamp = columnTimestamp;
        this.sectionBytes = sectionBytes;
    }

    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public ResourceKey<Level> dimension() { return dimension; }
    public long columnTimestamp() { return columnTimestamp; }

    /** Returns the raw section bytes (used by client-side processing). */
    public byte[] decompressedSections() { return sectionBytes; }

    public int estimatedBytes() {
        return sectionBytes.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
    }

    public static VoxelColumnS2CPayload read(FriendlyByteBuf buf) {
        int cx = buf.readInt();
        int cz = buf.readInt();
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION,
                new ResourceLocation(buf.readUtf(LSSConstants.MAX_DIMENSION_STRING_LENGTH)));
        long columnTimestamp = buf.readLong();
        byte[] sectionBytes = buf.readByteArray(LSSConstants.MAX_SECTIONS_SIZE);

        return new VoxelColumnS2CPayload(cx, cz, dim, columnTimestamp, sectionBytes);
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeInt(this.chunkX);
        buf.writeInt(this.chunkZ);
        buf.writeUtf(this.dimension.location().toString(), LSSConstants.MAX_DIMENSION_STRING_LENGTH);
        buf.writeLong(this.columnTimestamp);
        buf.writeByteArray(this.sectionBytes);
    }

    @Override
    public PacketType<?> getType() {
        return TYPE;
    }
}
