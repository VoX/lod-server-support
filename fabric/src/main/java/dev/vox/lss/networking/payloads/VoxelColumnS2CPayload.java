package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
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
public final class VoxelColumnS2CPayload implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<VoxelColumnS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse(LSSConstants.CHANNEL_VOXEL_COLUMN));

    public static final StreamCodec<FriendlyByteBuf, VoxelColumnS2CPayload> CODEC =
            StreamCodec.of(
                    VoxelColumnS2CPayload::write,
                    VoxelColumnS2CPayload::read
            );

    private final int chunkX;
    private final int chunkZ;
    private final ResourceKey<Level> dimension;
    private final long columnTimestamp;
    private final byte source;
    private final byte[] sectionBytes;

    /** Source-less convenience (tests/legacy rigs): tags the column with source -1
     *  ("unknown"), a legal wire value the client passes through verbatim. Production
     *  always uses the full constructor with a COLUMN_SOURCE_* tag. */
    public VoxelColumnS2CPayload(int chunkX, int chunkZ,
                                  ResourceKey<Level> dimension, long columnTimestamp,
                                  byte[] sectionBytes) {
        this(chunkX, chunkZ, dimension, columnTimestamp, (byte) -1, sectionBytes);
    }

    public VoxelColumnS2CPayload(int chunkX, int chunkZ,
                                  ResourceKey<Level> dimension, long columnTimestamp,
                                  byte source, byte[] sectionBytes) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.dimension = dimension;
        this.columnTimestamp = columnTimestamp;
        this.source = source;
        this.sectionBytes = sectionBytes;
    }

    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public ResourceKey<Level> dimension() { return dimension; }
    public long columnTimestamp() { return columnTimestamp; }

    /** Serve-source tag (COLUMN_SOURCE_*): where the server got this column. Diagnostic
     *  attribution only — unknown values pass through untouched (forward-safe). */
    public byte source() { return source; }

    /** Returns the raw section bytes (used by client-side processing). */
    public byte[] decompressedSections() { return sectionBytes; }

    public int estimatedBytes() {
        return sectionBytes.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
    }

    private static void write(FriendlyByteBuf buf, VoxelColumnS2CPayload payload) {
        buf.writeInt(payload.chunkX);
        buf.writeInt(payload.chunkZ);
        buf.writeUtf(payload.dimension.identifier().toString(), LSSConstants.MAX_DIMENSION_STRING_LENGTH);
        buf.writeLong(payload.columnTimestamp);
        buf.writeByte(payload.source);
        buf.writeByteArray(payload.sectionBytes);
    }

    private static VoxelColumnS2CPayload read(FriendlyByteBuf buf) {
        int cx = buf.readInt();
        int cz = buf.readInt();
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION,
                Identifier.parse(buf.readUtf(LSSConstants.MAX_DIMENSION_STRING_LENGTH)));
        long columnTimestamp = buf.readLong();
        byte source = buf.readByte();
        byte[] sectionBytes = buf.readByteArray(LSSConstants.MAX_SECTIONS_SIZE);

        return new VoxelColumnS2CPayload(cx, cz, dim, columnTimestamp, source, sectionBytes);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
