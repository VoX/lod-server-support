package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

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
    private static final Identifier ID_CHUNK_SECTION = Identifier.parse(LSSConstants.CHANNEL_CHUNK_SECTION);
    private static final Identifier ID_COLUMN_UP_TO_DATE = Identifier.parse(LSSConstants.CHANNEL_COLUMN_UP_TO_DATE);
    private static final Identifier ID_REQUEST_COMPLETE = Identifier.parse(LSSConstants.CHANNEL_REQUEST_COMPLETE);
    private static final Identifier ID_DIRTY_COLUMNS = Identifier.parse(LSSConstants.CHANNEL_DIRTY_COLUMNS);

    // ---- S2C Encoding ----

    public static void sendSessionConfig(Player player,
                                          int protocolVersion, boolean enabled,
                                          int lodDistanceChunks, int maxRequestsPerBatch,
                                          int maxPendingRequests, int maxGenerationRequestsPerBatch,
                                          int generationDistanceChunks) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(protocolVersion);
            buf.writeBoolean(enabled);
            buf.writeVarInt(lodDistanceChunks);
            buf.writeVarInt(maxRequestsPerBatch);
            buf.writeVarInt(maxPendingRequests);
            buf.writeVarInt(maxGenerationRequestsPerBatch);
            buf.writeVarInt(generationDistanceChunks);
            sendNmsPayload(player, ID_SESSION_CONFIG, buf);
        } finally {
            buf.release();
        }
    }

    public static byte[] encodeChunkSection(int chunkX, int sectionY, int chunkZ,
                                             ResourceKey<Level> dimension,
                                             byte[] sectionData, byte lightFlags,
                                             byte[] blockLight, byte[] skyLight,
                                             byte uniformBlockLight, byte uniformSkyLight,
                                             long columnTimestamp) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeInt(chunkX);
            buf.writeInt(sectionY);
            buf.writeInt(chunkZ);
            buf.writeUtf(dimension.identifier().toString());
            buf.writeByteArray(sectionData);
            buf.writeByte(lightFlags);
            if ((lightFlags & 0x01) != 0 && blockLight != null) {
                buf.writeBytes(blockLight);
            }
            if ((lightFlags & 0x04) != 0) {
                buf.writeByte(uniformBlockLight);
            }
            if ((lightFlags & 0x02) != 0 && skyLight != null) {
                buf.writeBytes(skyLight);
            }
            if ((lightFlags & 0x08) != 0) {
                buf.writeByte(uniformSkyLight);
            }
            buf.writeLong(columnTimestamp);
            return extractBytes(buf);
        } finally {
            buf.release();
        }
    }

    public static void sendRequestComplete(Player player, int batchId, int status) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(batchId);
            buf.writeVarInt(status);
            sendNmsPayload(player, ID_REQUEST_COMPLETE, buf);
        } finally {
            buf.release();
        }
    }

    public static void sendDirtyColumns(Player player, long[] dirtyPositions) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(dirtyPositions.length);
            for (long pos : dirtyPositions) {
                buf.writeLong(pos);
            }
            sendNmsPayload(player, ID_DIRTY_COLUMNS, buf);
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
        nmsPlayer.connection.send(new ClientboundCustomPayloadPacket(
                new DiscardedPayload(channelId, data)));
    }

    /**
     * Returns the cached {@link Identifier} for a channel string constant.
     * Used by callers that need to send pre-encoded payloads via {@link #sendRawNmsPayload}.
     */
    public static Identifier channelId(String channel) {
        return switch (channel) {
            case LSSConstants.CHANNEL_SESSION_CONFIG -> ID_SESSION_CONFIG;
            case LSSConstants.CHANNEL_CHUNK_SECTION -> ID_CHUNK_SECTION;
            case LSSConstants.CHANNEL_COLUMN_UP_TO_DATE -> ID_COLUMN_UP_TO_DATE;
            case LSSConstants.CHANNEL_REQUEST_COMPLETE -> ID_REQUEST_COMPLETE;
            case LSSConstants.CHANNEL_DIRTY_COLUMNS -> ID_DIRTY_COLUMNS;
            default -> Identifier.parse(channel);
        };
    }

    // ---- C2S Decoding ----

    public static int decodeHandshakeProtocolVersion(byte[] data) {
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            return buf.readVarInt();
        } finally {
            buf.release();
        }
    }

    public record DecodedChunkRequest(int batchId, long[] positions, long[] timestamps) {}

    public static DecodedChunkRequest decodeChunkRequest(byte[] data) {
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            int batchId = buf.readVarInt();
            int rawLen = Math.max(buf.readVarInt(), 0);
            int len = Math.min(rawLen, LSSConstants.MAX_CHUNK_REQUEST_POSITIONS);
            long[] positions = new long[len];
            for (int i = 0; i < rawLen; i++) {
                long pos = buf.readLong();
                if (i < len) positions[i] = pos;
            }
            long[] timestamps = new long[len];
            if (buf.isReadable()) {
                for (int i = 0; i < rawLen; i++) {
                    long ts = buf.readLong();
                    if (i < len) timestamps[i] = ts;
                }
            }
            return new DecodedChunkRequest(batchId, positions, timestamps);
        } finally {
            buf.release();
        }
    }

    public static int[] decodeCancelRequest(byte[] data) {
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            int rawLen = Math.max(buf.readVarInt(), 0);
            int len = Math.min(rawLen, LSSConstants.MAX_CANCEL_BATCH_IDS);
            int[] batchIds = new int[len];
            for (int i = 0; i < rawLen; i++) {
                int id = buf.readVarInt();
                if (i < len) batchIds[i] = id;
            }
            return batchIds;
        } finally {
            buf.release();
        }
    }

    // ---- Helpers ----

    /**
     * Sends a custom payload packet directly via NMS, bypassing Bukkit's
     * {@code sendPluginMessage()} channel registration check.
     */
    private static void sendNmsPayload(Player player, Identifier channelId, FriendlyByteBuf buf) {
        byte[] bytes = extractBytes(buf);
        var nmsPlayer = ((CraftPlayer) player).getHandle();
        nmsPlayer.connection.send(new ClientboundCustomPayloadPacket(
                new DiscardedPayload(channelId, bytes)));
    }

    private static byte[] extractBytes(FriendlyByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }
}
