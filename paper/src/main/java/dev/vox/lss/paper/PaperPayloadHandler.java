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

    public static void sendSessionConfig(Player player,
                                          int protocolVersion, boolean enabled,
                                          int lodDistanceChunks, int serverCapabilities,
                                          int syncOnLoadRateLimitPerPlayer, int syncOnLoadConcurrencyLimitPerPlayer,
                                          int generationRateLimitPerPlayer, int generationConcurrencyLimitPerPlayer,
                                          boolean generationEnabled, long playerBandwidthLimit) {
        sendEncoded(player, ID_SESSION_CONFIG, buf -> {
            buf.writeVarInt(protocolVersion);
            buf.writeBoolean(enabled);
            buf.writeVarInt(lodDistanceChunks);
            buf.writeVarInt(serverCapabilities);
            buf.writeVarInt(syncOnLoadRateLimitPerPlayer);
            buf.writeVarInt(syncOnLoadConcurrencyLimitPerPlayer);
            buf.writeVarInt(generationRateLimitPerPlayer);
            buf.writeVarInt(generationConcurrencyLimitPerPlayer);
            buf.writeBoolean(generationEnabled);
            buf.writeVarLong(playerBandwidthLimit);
        });
    }

    public static void sendBatchResponse(Player player, byte[] responseTypes, int[] requestIds, int count) {
        sendEncoded(player, ID_BATCH_RESPONSE, buf -> {
            buf.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                buf.writeByte(responseTypes[i]);
                buf.writeVarInt(requestIds[i]);
            }
        });
    }

    /**
     * Returns the cached {@link Identifier} for a channel string constant.
     * Used by callers that need to send pre-encoded payloads via {@link #sendRawNmsPayload}.
     */
    public static Identifier channelId(String channel) {
        return switch (channel) {
            case LSSConstants.CHANNEL_SESSION_CONFIG -> ID_SESSION_CONFIG;
            case LSSConstants.CHANNEL_DIRTY_COLUMNS -> ID_DIRTY_COLUMNS;
            case LSSConstants.CHANNEL_VOXEL_COLUMN -> ID_VOXEL_COLUMN;
            case LSSConstants.CHANNEL_BATCH_RESPONSE -> ID_BATCH_RESPONSE;
            default -> Identifier.parse(channel);
        };
    }

    /**
     * Encode a column payload with serialized section bytes.
     * Writes the per-request header, then writes sectionBytes as a length-prefixed byte array.
     */
    public static byte[] encodeVoxelColumnPreEncoded(int requestId, int chunkX, int chunkZ,
                                                      String dimensionStr, long columnTimestamp,
                                                      byte[] sectionBytes) {
        return encodeToBytes(buf -> {
            buf.writeVarInt(requestId);
            buf.writeInt(chunkX);
            buf.writeInt(chunkZ);
            int ordinal = LSSConstants.dimensionStringToOrdinal(dimensionStr);
            buf.writeVarInt(ordinal);
            if (ordinal == LSSConstants.DIM_CUSTOM) {
                buf.writeUtf(dimensionStr);
            }
            buf.writeLong(columnTimestamp);
            buf.writeByteArray(sectionBytes);
        });
    }

    /**
     * Encode a DirtyColumnsS2CPayload. Wire format: VarInt length + long[] positions.
     * Identical to Fabric's DirtyColumnsS2CPayload.CODEC.
     */
    public static byte[] encodeDirtyColumns(long[] dirtyPositions) {
        int len = Math.min(dirtyPositions.length, LSSConstants.MAX_DIRTY_COLUMN_POSITIONS);
        return encodeToBytes(buf -> {
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

    public record DecodedBatchChunkRequest(int[] requestIds, long[] packedPositions, long[] clientTimestamps, int count) {}

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
            int[] requestIds = new int[count];
            long[] packedPositions = new long[count];
            long[] clientTimestamps = new long[count];
            for (int i = 0; i < count; i++) {
                requestIds[i] = buf.readVarInt();
                packedPositions[i] = buf.readLong();
                clientTimestamps[i] = buf.readLong();
            }
            return new DecodedBatchChunkRequest(requestIds, packedPositions, clientTimestamps, count);
        });
    }

    public record DecodedCancelRequest(int requestId) {}

    public static DecodedCancelRequest decodeCancelRequest(byte[] data) {
        if (data == null || data.length == 0) return null;
        return withReadBuffer(data, buf -> new DecodedCancelRequest(buf.readVarInt()));
    }

    public record DecodedBandwidthUpdate(long desiredRate) {}

    public static DecodedBandwidthUpdate decodeBandwidthUpdate(byte[] data) {
        if (data == null || data.length == 0) return null;
        return withReadBuffer(data, buf -> new DecodedBandwidthUpdate(buf.readVarLong()));
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

    private static void sendEncoded(Player player, Identifier channelId, Consumer<FriendlyByteBuf> writer) {
        byte[] bytes = encodeToBytes(writer);
        var nmsPlayer = ((CraftPlayer) player).getHandle();
        if (nmsPlayer.connection == null) return;
        nmsPlayer.connection.send(new ClientboundCustomPayloadPacket(
                new DiscardedPayload(channelId, bytes)));
    }

    private static byte[] encodeToBytes(Consumer<FriendlyByteBuf> writer) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
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
