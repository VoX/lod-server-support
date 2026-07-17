package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Encodes S2C payloads and decodes C2S payloads using the same wire format as Fabric.
 *
 * S2C packets are sent directly via NMS using {@link DiscardedPayload} to wrap
 * raw bytes in a {@link ClientboundCustomPayloadPacket}. This bypasses Bukkit's
 * {@code sendPluginMessage()} which silently drops messages when the client hasn't
 * registered the channel via {@code minecraft:register} — a common issue with
 * Fabric clients connecting to Paper servers in 1.20.5+.
 */
public final class PaperPayloadHandler {
    private PaperPayloadHandler() {}

    // Cached Identifier instances for constant channel strings
    private static final Identifier ID_SESSION_CONFIG = Identifier.parse(LSSConstants.CHANNEL_SESSION_CONFIG);
    private static final Identifier ID_DIRTY_COLUMNS = Identifier.parse(LSSConstants.CHANNEL_DIRTY_COLUMNS);
    static final Identifier ID_VOXEL_COLUMN = Identifier.parse(LSSConstants.CHANNEL_VOXEL_COLUMN);
    private static final Identifier ID_BATCH_RESPONSE = Identifier.parse(LSSConstants.CHANNEL_BATCH_RESPONSE);

    // ---- S2C Encoding ----

    /** Four fields since the server-owned-generation fold into v17: both per-player
     *  concurrency caps left the wire (server-internal admission limiters only). */
    public static byte[] encodeSessionConfig(int protocolVersion, boolean enabled,
                                             int lodDistanceChunks,
                                             boolean generationEnabled) {
        return encodeToBytes(buf -> {
            buf.writeVarInt(protocolVersion);
            buf.writeBoolean(enabled);
            buf.writeVarInt(lodDistanceChunks);
            buf.writeBoolean(generationEnabled);
        });
    }

    public static void sendSessionConfig(Player player,
                                          int protocolVersion, boolean enabled,
                                          int lodDistanceChunks,
                                          boolean generationEnabled) {
        sendRawNmsPayload(player, ID_SESSION_CONFIG, encodeSessionConfig(
                protocolVersion, enabled, lodDistanceChunks, generationEnabled));
    }

    public static byte[] encodeBatchResponse(byte[] responseTypes, long[] packedPositions, int count) {
        // Exact size up front (1 byte type + 8 byte position per entry, +5 for the count
        // VarInt): a full broadcast frame is ~37 KB, which from the default 256 bytes forces
        // ~8 doubling reallocations + copies every flush tick.
        return encodeToBytes(count * 9 + 5, buf -> {
            buf.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                buf.writeByte(responseTypes[i]);
                buf.writeLong(packedPositions[i]);
            }
        });
    }

    public static void sendBatchResponse(Player player, byte[] responseTypes, long[] packedPositions, int count) {
        sendRawNmsPayload(player, ID_BATCH_RESPONSE, encodeBatchResponse(responseTypes, packedPositions, count));
    }

    /**
     * Encode a column payload with serialized section bytes.
     * Writes the per-request header, then writes sectionBytes as a length-prefixed byte array.
     */
    /** Source-less convenience (tests/legacy rigs): source -1 = "unknown", a legal wire
     *  value. Production always passes a COLUMN_SOURCE_* tag. */
    public static byte[] encodeVoxelColumnPreEncoded(int chunkX, int chunkZ,
                                                      String dimensionStr, long columnTimestamp,
                                                      byte[] sectionBytes) {
        return encodeVoxelColumnPreEncoded(chunkX, chunkZ, dimensionStr, columnTimestamp,
                (byte) -1, sectionBytes);
    }

    public static byte[] encodeVoxelColumnPreEncoded(int chunkX, int chunkZ,
                                                      String dimensionStr, long columnTimestamp,
                                                      byte source, byte[] sectionBytes) {
        return encodeToBytes(sectionBytes.length + 64, buf -> {
            buf.writeInt(chunkX);
            buf.writeInt(chunkZ);
            buf.writeUtf(dimensionStr, LSSConstants.MAX_DIMENSION_STRING_LENGTH);
            buf.writeLong(columnTimestamp);
            buf.writeByte(source);
            buf.writeByteArray(sectionBytes);
        });
    }

    /**
     * Encode a DirtyColumnsS2CPayload. Wire format: VarInt length + long[] positions.
     * Identical to Fabric's DirtyColumnsS2CPayload.CODEC.
     */
    public static byte[] encodeDirtyColumns(long[] dirtyPositions) {
        int len = Math.min(dirtyPositions.length, LSSConstants.MAX_DIRTY_COLUMN_POSITIONS);
        // Exact size up front (8 bytes per position, +5 for the count VarInt): a full frame is
        // ~82 KB, otherwise grown from 256 bytes by repeated doubling+copy every broadcast.
        return encodeToBytes(len * 8 + 5, buf -> {
            buf.writeVarInt(len);
            for (int i = 0; i < len; i++) {
                buf.writeLong(dirtyPositions[i]);
            }
        });
    }

    public static void sendDirtyColumns(Player player, long[] dirtyPositions) {
        byte[] data = encodeDirtyColumns(dirtyPositions);
        sendRawNmsPayload(player, ID_DIRTY_COLUMNS, data);
    }

    // ---- C2S Decoding ----

    public record DecodedHandshake(int protocolVersion, int capabilities) {}

    public static DecodedHandshake decodeHandshake(byte[] data) {
        if (data == null || data.length == 0) {
            LSSLogger.warn("Received empty handshake payload");
            return null;
        }
        return withReadBuffer(data, buf -> {
            int version = buf.readVarInt();
            int caps = buf.isReadable() ? buf.readVarInt() : 0;
            return new DecodedHandshake(version, caps);
        });
    }

    public record DecodedBatchChunkRequest(long[] packedPositions, long[] clientTimestamps, int count) {}

    public static DecodedBatchChunkRequest decodeBatchChunkRequest(byte[] data) {
        if (data == null || data.length == 0) {
            LSSLogger.warn("Received empty batch chunk request payload");
            return null;
        }
        return withReadBuffer(data, buf -> {
            int count = buf.readVarInt();
            if (count < 0 || count > LSSConstants.MAX_BATCH_CHUNK_REQUESTS) {
                LSSLogger.warn("Batch chunk request count out of range: " + count);
                return null;
            }
            long[] packedPositions = new long[count];
            long[] clientTimestamps = new long[count];
            for (int i = 0; i < count; i++) {
                packedPositions[i] = buf.readLong();
                clientTimestamps[i] = buf.readLong();
            }
            return new DecodedBatchChunkRequest(packedPositions, clientTimestamps, count);
        });
    }

    // ---- Helpers ----

    private static <T> T withReadBuffer(byte[] data, Function<FriendlyByteBuf, T> fn) {
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            return fn.apply(buf);
        } finally {
            buf.release();
        }
    }

    private static byte[] encodeToBytes(Consumer<FriendlyByteBuf> writer) {
        return encodeToBytes(256, writer);
    }

    private static byte[] encodeToBytes(int initialCapacity, Consumer<FriendlyByteBuf> writer) {
        var buf = new FriendlyByteBuf(Unpooled.buffer(initialCapacity));
        try {
            writer.accept(buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    /**
     * Sends a pre-encoded payload directly via NMS for a given channel.
     * Used by the send queue flush in {@link PaperRequestProcessingService}.
     */
    public static void sendRawNmsPayload(Player player, Identifier channelId, byte[] data) {
        var nmsPlayer = ((CraftPlayer) player).getHandle();
        if (nmsPlayer.connection == null) return;
        nmsPlayer.connection.send(new ClientboundCustomPayloadPacket(
                new DiscardedPayload(channelId, data)));
    }
}
