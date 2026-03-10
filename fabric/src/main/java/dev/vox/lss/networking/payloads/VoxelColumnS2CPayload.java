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
 * Dimension is encoded as a VarInt ordinal (0=overworld, 1=nether, 2=end)
 * with a UTF fallback for modded dimensions (ordinal=-1).
 */
public final class VoxelColumnS2CPayload implements CustomPacketPayload {

    private static final int MAX_SECTIONS_SIZE = 2_097_152; // 2MB
    private static final int MAX_DIMENSION_STRING_LENGTH = 256;

    public static final CustomPacketPayload.Type<VoxelColumnS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse(LSSConstants.CHANNEL_VOXEL_COLUMN));

    public static final StreamCodec<FriendlyByteBuf, VoxelColumnS2CPayload> CODEC =
            StreamCodec.of(
                    VoxelColumnS2CPayload::write,
                    VoxelColumnS2CPayload::read
            );

    private final int requestId;
    private final int chunkX;
    private final int chunkZ;
    private final ResourceKey<Level> dimension;
    private final long columnTimestamp;
    private final byte[] sectionBytes;

    public VoxelColumnS2CPayload(int requestId, int chunkX, int chunkZ,
                                  ResourceKey<Level> dimension, long columnTimestamp,
                                  byte[] sectionBytes) {
        this.requestId = requestId;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.dimension = dimension;
        this.columnTimestamp = columnTimestamp;
        this.sectionBytes = sectionBytes;
    }

    public int requestId() { return requestId; }
    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public ResourceKey<Level> dimension() { return dimension; }
    public long columnTimestamp() { return columnTimestamp; }

    /** Returns the raw section bytes (used by client-side processing). */
    public byte[] decompressedSections() { return sectionBytes; }

    public int estimatedBytes() {
        return sectionBytes.length + 25;
    }

    /** Map a dimension ResourceKey to a wire ordinal via identity comparison (avoids String allocation). */
    private static int dimensionToOrdinal(ResourceKey<Level> dim) {
        if (dim == Level.OVERWORLD) return LSSConstants.DIM_OVERWORLD;
        if (dim == Level.NETHER) return LSSConstants.DIM_THE_NETHER;
        if (dim == Level.END) return LSSConstants.DIM_THE_END;
        return LSSConstants.DIM_CUSTOM;
    }

    private static void write(FriendlyByteBuf buf, VoxelColumnS2CPayload payload) {
        buf.writeVarInt(payload.requestId);
        buf.writeInt(payload.chunkX);
        buf.writeInt(payload.chunkZ);
        int ordinal = dimensionToOrdinal(payload.dimension);
        buf.writeVarInt(ordinal);
        if (ordinal == LSSConstants.DIM_CUSTOM) {
            buf.writeUtf(payload.dimension.identifier().toString());
        }
        buf.writeLong(payload.columnTimestamp);
        buf.writeByteArray(payload.sectionBytes);
    }

    private static VoxelColumnS2CPayload read(FriendlyByteBuf buf) {
        int requestId = buf.readVarInt();
        int cx = buf.readInt();
        int cz = buf.readInt();
        ResourceKey<Level> dim;
        int ordinal = buf.readVarInt();
        dim = switch (ordinal) {
            case LSSConstants.DIM_OVERWORLD -> Level.OVERWORLD;
            case LSSConstants.DIM_THE_NETHER -> Level.NETHER;
            case LSSConstants.DIM_THE_END -> Level.END;
            default -> ResourceKey.create(Registries.DIMENSION, Identifier.parse(buf.readUtf(MAX_DIMENSION_STRING_LENGTH)));
        };
        long columnTimestamp = buf.readLong();
        byte[] sectionBytes = buf.readByteArray(MAX_SECTIONS_SIZE);

        return new VoxelColumnS2CPayload(requestId, cx, cz, dim, columnTimestamp, sectionBytes);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
